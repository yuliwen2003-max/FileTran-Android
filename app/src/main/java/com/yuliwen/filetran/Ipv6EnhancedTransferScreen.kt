package com.yuliwen.filetran

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val IPV6_BOOST_QR_TYPE = "filetran_ipv6_boost_udp_v1"
private const val IPV6_BOOST_QR_TYPE_COMPAT = "filetran_ipv6_boost_v1"
private const val IPV6_BOOST_TIMEOUT_MS = 15_000L
private const val UDP_HELLO = "FILETRAN_IPV6_UDP_HELLO"
private const val UDP_ACK = "FILETRAN_IPV6_UDP_ACK"

private data class Ipv6BoostPeer(
    val host: String,
    val port: Int
)

private data class Ipv6BoostResult(
    val success: Boolean,
    val method: String,
    val message: String
)

@Composable
fun Ipv6EnhancedTransferScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var localIpv6 by remember { mutableStateOf("") }
    var localPortInput by remember { mutableStateOf("18080") }
    var remoteHostInput by remember { mutableStateOf("") }
    var remotePortInput by remember { mutableStateOf("18080") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var localInterfaceIpv6 by remember { mutableStateOf("") }
    var showStunPicker by remember { mutableStateOf(false) }
    var stunProbeResult by remember { mutableStateOf<StunProbeBatchResult?>(null) }
    var statusText by remember { mutableStateOf("请先获取本机 IPv6，并交换双方二维码。") }
    var running by remember { mutableStateOf(false) }

    suspend fun refreshLocalIpv6() {
        val localPort = localPortInput.toIntOrNull()
        if (localPort == null || localPort !in 1..65535) {
            statusText = "请先填写有效本地端口。"
            return
        }
        val localIpv6FromInterface = withContext(Dispatchers.IO) { NetworkUtils.getLocalGlobalIpv6Address() }
        localInterfaceIpv6 = localIpv6FromInterface.orEmpty()
        val batch = withContext(Dispatchers.IO) {
            NetworkUtils.probeStunMappedEndpointBatch(
                localPort = localPort,
                preferIpv6 = true,
                transport = StunTransportType.UDP
            )
        }
        stunProbeResult = batch.takeIf { it.endpoints.isNotEmpty() }
        val mapped = batch.preferredEndpoint
        if (mapped != null) {
            localIpv6 = mapped.address
            localPortInput = mapped.port.toString()
            statusText = when {
                batch.allMismatch -> {
                    showStunPicker = true
                    "STUN 探测成功，但所有结果与 ipip 不一致，请先手动选择地址。"
                }
                batch.matchedByIpip.isNotEmpty() -> {
                    "STUN 探测成功（存在 ipip 匹配项，默认先用首个 STUN 结果）：${mapped.address}:${mapped.port}"
                }
                else -> {
                    "STUN 探测成功：${mapped.address}:${mapped.port}（${mapped.stunServer}）"
                }
            }
            return
        }
        if (localIpv6FromInterface.isNullOrBlank()) {
            statusText = "未获取到可用公网 IPv6，请确认当前网络支持 IPv6。"
        } else {
            localIpv6 = localIpv6FromInterface
            statusText = "STUN 探测失败，已回退网卡 IPv6：$localIpv6FromInterface"
        }
    }

    fun parsePeerFromRaw(input: String): Ipv6BoostPeer? {
        val raw = input.trim()
        if (raw.isBlank()) return null
        if (raw.startsWith("[") && raw.contains("]:")) {
            val end = raw.indexOf(']')
            if (end <= 1 || end + 2 >= raw.length) return null
            val host = raw.substring(1, end).trim().replace("%25", "%")
            val port = raw.substring(end + 2).trim().toIntOrNull() ?: return null
            if (host.isBlank() || port !in 1..65535) return null
            return Ipv6BoostPeer(host, port)
        }
        val colon = raw.lastIndexOf(':')
        if (colon <= 0 || colon >= raw.length - 1) return null
        val host = raw.substring(0, colon).trim().replace("%25", "%")
        val port = raw.substring(colon + 1).trim().toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return Ipv6BoostPeer(host, port)
    }

    fun parsePeerFromQr(content: String): Ipv6BoostPeer? {
        val raw = content.trim()
        if (raw.isBlank()) return null
        runCatching {
            val json = JSONObject(raw)
            val type = json.optString("type")
            if (type == IPV6_BOOST_QR_TYPE || type == IPV6_BOOST_QR_TYPE_COMPAT) {
                val ip = json.optString("ip").trim()
                val port = json.optInt("port", -1)
                if (ip.isNotBlank() && port in 1..65535) {
                    return Ipv6BoostPeer(ip, port)
                }
            }
        }
        return parsePeerFromRaw(raw)
    }

    suspend fun tryIpv6UdpBoost(
        localPort: Int,
        remoteHost: String,
        remotePort: Int
    ): Ipv6BoostResult = withContext(Dispatchers.IO) {
        val success = AtomicBoolean(false)
        val outboundAttempts = AtomicInteger(0)
        val inboundPackets = AtomicInteger(0)
        var finalMethod = "未知"
        var finalMessage = "UDP 打洞失败"
        val startAt = System.currentTimeMillis()
        val deadline = startAt + IPV6_BOOST_TIMEOUT_MS

        val targetAddresses = runCatching {
            InetAddress.getAllByName(remoteHost)
                .toList()
                .sortedByDescending { it is Inet6Address }
        }.getOrElse { e ->
            return@withContext Ipv6BoostResult(
                success = false,
                method = "解析失败",
                message = "无法解析对端地址：${e.message ?: "unknown"}"
            )
        }
        if (targetAddresses.isEmpty()) {
            return@withContext Ipv6BoostResult(
                success = false,
                method = "解析失败",
                message = "未解析到可用对端地址"
            )
        }

        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 700
                bind(InetSocketAddress(InetAddress.getByName("::"), localPort))
            }
        }.getOrElse { e ->
            return@withContext Ipv6BoostResult(
                success = false,
                method = "绑定失败",
                message = "无法绑定本地 UDP 端口 $localPort：${e.message ?: "unknown"}"
            )
        }

        try {
            val helloBytes = UDP_HELLO.toByteArray(Charsets.UTF_8)
            val ackBytes = UDP_ACK.toByteArray(Charsets.UTF_8)

            coroutineScope {
                val receiveTask = async {
                    val buffer = ByteArray(2048)
                    while (isActive && !success.get() && System.currentTimeMillis() < deadline) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            inboundPackets.incrementAndGet()
                            val text = packet.data.decodeToString(0, packet.length)
                            if (text.startsWith(UDP_HELLO) || text.startsWith(UDP_ACK)) {
                                if (success.compareAndSet(false, true)) {
                                    finalMethod = "入站 UDP 成功"
                                    finalMessage = "收到来自 ${packet.address.hostAddress}:${packet.port} 的 UDP 包"
                                }
                                val ack = DatagramPacket(ackBytes, ackBytes.size, packet.address, packet.port)
                                runCatching { socket.send(ack) }
                            }
                        } catch (_: SocketTimeoutException) {
                            // keep waiting
                        } catch (_: Exception) {
                            // keep probing within timeout
                        }
                    }
                }

                val sendTask = async {
                    while (isActive && !success.get() && System.currentTimeMillis() < deadline) {
                        for (addr in targetAddresses) {
                            if (success.get() || System.currentTimeMillis() >= deadline) break
                            val packet = DatagramPacket(helloBytes, helloBytes.size, addr, remotePort)
                            runCatching { socket.send(packet) }
                            outboundAttempts.incrementAndGet()
                        }
                        if (!success.get()) delay(280)
                    }
                }

                while (!success.get() && System.currentTimeMillis() < deadline) {
                    delay(120)
                }
                receiveTask.cancel()
                sendTask.cancel()
            }
        } finally {
            runCatching { socket.close() }
        }

        if (!success.get()) {
            return@withContext Ipv6BoostResult(
                success = false,
                method = "超时",
                message = "15 秒内未打通（发包 ${outboundAttempts.get()} 次，收包 ${inboundPackets.get()} 次）"
            )
        }

        Ipv6BoostResult(success = true, method = finalMethod, message = finalMessage)
    }

    if (showScanner) {
        QRCodeScanner(
            onQRCodeScanned = { content ->
                showScanner = false
                val peer = parsePeerFromQr(content)
                if (peer == null) {
                    statusText = "二维码内容无法识别为 IPv6 增强模式地址。"
                } else {
                    remoteHostInput = peer.host
                    remotePortInput = peer.port.toString()
                    statusText = "已识别对端：${peer.host}:${peer.port}"
                }
            },
            onDismiss = { showScanner = false }
        )
    }

    StunEndpointPickerDialog(
        visible = showStunPicker,
        title = "选择 IPv6 STUN 地址",
        result = stunProbeResult,
        selected = stunProbeResult
            ?.endpoints
            ?.firstOrNull { it.address == localIpv6 && it.port == localPortInput.toIntOrNull() },
        onSelect = { chosen ->
            localIpv6 = chosen.address
            localPortInput = chosen.port.toString()
            statusText = "已切换 STUN 地址：${chosen.address}:${chosen.port}（${chosen.stunServer}）"
        },
        onDismiss = { showStunPicker = false }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
            enabled = !running
        ) {
            Text("返回传输实验室")
        }

        Text("IPv6 增强传输模式（UDP实验）", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "双方先用 UDP 多 STUN 获取公网映射后再交换地址，使用同端口并发发包与收包，最多尝试 15 秒，验证 UDP 打洞是否可行。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("我的信息", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = localIpv6,
                    onValueChange = { localIpv6 = it.trim() },
                    label = { Text("本机 IPv6") },
                    placeholder = { Text("点击下方按钮自动获取") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
                OutlinedTextField(
                    value = localPortInput,
                    onValueChange = { localPortInput = it.filter(Char::isDigit) },
                    label = { Text("本地端口") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
                if (localInterfaceIpv6.isNotBlank()) {
                    Text(
                        "网卡 IPv6 参考：$localInterfaceIpv6",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                if (!running) refreshLocalIpv6()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !running
                    ) {
                        Text("获取 IPv6")
                    }
                    OutlinedButton(
                        onClick = {
                            val port = localPortInput.toIntOrNull()
                            if (localIpv6.isBlank() || port !in 1..65535) {
                                statusText = "请先填写有效的本机 IPv6 和端口。"
                                return@OutlinedButton
                            }
                            val payload = JSONObject()
                                .put("type", IPV6_BOOST_QR_TYPE)
                                .put("ip", localIpv6)
                                .put("port", port)
                                .toString()
                            qrBitmap = QRCodeGenerator.generateQRCode(payload, 512)
                            statusText = if (qrBitmap == null) "二维码生成失败。" else "已生成本机连接二维码。"
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !running
                    ) {
                        Text("生成二维码")
                    }
                }
                if (stunProbeResult?.endpoints?.isNotEmpty() == true) {
                    OutlinedButton(
                        onClick = { showStunPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !running
                    ) {
                        Text("选择 STUN 地址")
                    }
                }
                if (qrBitmap != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "ipv6_boost_qr",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("对端信息", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = remoteHostInput,
                    onValueChange = { remoteHostInput = it.trim() },
                    label = { Text("对端 IPv6/主机") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
                OutlinedTextField(
                    value = remotePortInput,
                    onValueChange = { remotePortInput = it.filter(Char::isDigit) },
                    label = { Text("对端端口") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
                OutlinedButton(
                    onClick = { showScanner = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !running
                ) {
                    Text("扫码识别对端")
                }
                Button(
                    onClick = {
                        val localPort = localPortInput.toIntOrNull()
                        val remotePort = remotePortInput.toIntOrNull()
                        if (localPort == null || remotePort == null || localPort !in 1..65535 || remotePort !in 1..65535 || remoteHostInput.isBlank()) {
                            statusText = "请先填写有效的本地端口和对端地址端口。"
                            return@Button
                        }
                        running = true
                        statusText = "正在尝试 IPv6 UDP 打洞，最长 15 秒..."
                        scope.launch {
                            val result = tryIpv6UdpBoost(
                                localPort = localPort,
                                remoteHost = remoteHostInput,
                                remotePort = remotePort
                            )
                            running = false
                            statusText = if (result.success) {
                                "验证成功（${result.method}）：${result.message}"
                            } else {
                                "验证失败（${result.method}）：${result.message}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !running
                ) {
                    Text(if (running) "验证中..." else "开始 15 秒 UDP 打洞验证")
                }
            }
        }

        Text(
            text = statusText,
            color = if (statusText.startsWith("验证失败")) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
