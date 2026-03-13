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
import androidx.compose.ui.platform.LocalContext
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val IPV4_STUN_QR_TYPE = "filetran_ipv4_stun_udp_v1"
private const val IPV4_STUN_TIMEOUT_MS = 15_000L
private const val IPV4_STUN_HELLO = "FILETRAN_IPV4_STUN_HELLO"
private const val IPV4_STUN_ACK = "FILETRAN_IPV4_STUN_ACK"

private data class Ipv4Peer(
    val host: String,
    val port: Int
)

private data class Ipv4StunTestResult(
    val success: Boolean,
    val method: String,
    val message: String
)

@Composable
fun Ipv4StunTcpTransferScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localLanIpv4 by remember { mutableStateOf("") }
    var localListenPortInput by remember { mutableStateOf("18080") }
    var localPublicHost by remember { mutableStateOf("") }
    var localPublicPortInput by remember { mutableStateOf("") }
    var remoteHostInput by remember { mutableStateOf("") }
    var remotePortInput by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var showStunPicker by remember { mutableStateOf(false) }
    var stunProbeResult by remember { mutableStateOf<StunProbeBatchResult?>(null) }
    var statusText by remember { mutableStateOf("请先探测本机 IPv4 STUN 公网映射，再交换二维码后同时点击验证。") }

    fun parsePeerFromRaw(input: String): Ipv4Peer? {
        val raw = input.trim()
        if (raw.isBlank()) return null
        val split = raw.lastIndexOf(':')
        if (split <= 0 || split >= raw.length - 1) return null
        val host = raw.substring(0, split).trim()
        val port = raw.substring(split + 1).trim().toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return Ipv4Peer(host, port)
    }

    fun parsePeerFromQr(raw: String): Ipv4Peer? {
        val content = raw.trim()
        if (content.isBlank()) return null
        runCatching {
            val json = JSONObject(content)
            if (json.optString("type") == IPV4_STUN_QR_TYPE) {
                val ip = json.optString("ip").trim()
                val port = json.optInt("port", -1)
                if (ip.isNotBlank() && port in 1..65535) {
                    return Ipv4Peer(ip, port)
                }
            }
        }
        return parsePeerFromRaw(content)
    }

    suspend fun runUdpHolePunchTest(
        localPort: Int,
        remoteHost: String,
        remotePort: Int
    ): Ipv4StunTestResult = withContext(Dispatchers.IO) {
        val success = AtomicBoolean(false)
        val inboundPackets = AtomicInteger(0)
        val outboundPackets = AtomicInteger(0)
        var finalMethod = "未知"
        var finalMessage = "UDP 打洞验证失败"

        val targetAddresses = runCatching { InetAddress.getAllByName(remoteHost).toList() }
            .getOrElse { e ->
                return@withContext Ipv4StunTestResult(
                    success = false,
                    method = "解析失败",
                    message = "无法解析对端地址：${e.message ?: "unknown"}"
                )
            }
        if (targetAddresses.isEmpty()) {
            return@withContext Ipv4StunTestResult(false, "解析失败", "未解析到可用对端地址")
        }

        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 700
                bind(InetSocketAddress("0.0.0.0", localPort))
            }
        }.getOrElse { e ->
            return@withContext Ipv4StunTestResult(
                success = false,
                method = "绑定失败",
                message = "无法绑定本地 UDP 端口 $localPort：${e.message ?: "unknown"}"
            )
        }

        val deadline = System.currentTimeMillis() + IPV4_STUN_TIMEOUT_MS

        try {
            val helloBytes = IPV4_STUN_HELLO.toByteArray(Charsets.UTF_8)
            val ackBytes = IPV4_STUN_ACK.toByteArray(Charsets.UTF_8)

            coroutineScope {
                val receiveTask = async {
                    val buffer = ByteArray(2048)
                    while (isActive && !success.get() && System.currentTimeMillis() < deadline) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            inboundPackets.incrementAndGet()
                            val text = packet.data.decodeToString(0, packet.length)
                            if (text.startsWith(IPV4_STUN_HELLO) || text.startsWith(IPV4_STUN_ACK)) {
                                if (success.compareAndSet(false, true)) {
                                    finalMethod = "入站 UDP 成功"
                                    finalMessage = "收到来自 ${packet.address.hostAddress}:${packet.port} 的 UDP 包。"
                                }
                                val ack = DatagramPacket(ackBytes, ackBytes.size, packet.address, packet.port)
                                runCatching { socket.send(ack) }
                            }
                        } catch (_: SocketTimeoutException) {
                        } catch (_: Exception) {
                        }
                    }
                }

                val sendTask = async {
                    while (isActive && !success.get() && System.currentTimeMillis() < deadline) {
                        for (addr in targetAddresses) {
                            if (success.get() || System.currentTimeMillis() >= deadline) break
                            val packet = DatagramPacket(helloBytes, helloBytes.size, addr, remotePort)
                            runCatching { socket.send(packet) }
                            outboundPackets.incrementAndGet()
                        }
                        if (!success.get()) delay(260)
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
            return@withContext Ipv4StunTestResult(
                success = false,
                method = "超时",
                message = "15 秒内未打通（发包 ${outboundPackets.get()} 次，收包 ${inboundPackets.get()} 次）"
            )
        }

        Ipv4StunTestResult(
            success = true,
            method = finalMethod,
            message = "$finalMessage 发包 ${outboundPackets.get()} 次，收包 ${inboundPackets.get()} 次。"
        )
    }

    suspend fun probeStunAndFill() {
        val localPort = localListenPortInput.toIntOrNull()
        if (localPort == null || localPort !in 1..65535) {
            statusText = "请先填写有效本地监听端口。"
            return
        }
        probing = true
        statusText = "正在通过多 STUN(UDP) 探测 IPv4 公网映射..."
        val batch = withContext(Dispatchers.IO) {
            NetworkUtils.probeStunMappedEndpointBatch(
                localPort = localPort,
                preferIpv6 = false,
                transport = StunTransportType.UDP
            )
        }
        val mapped = batch.preferredEndpoint
        stunProbeResult = batch.takeIf { it.endpoints.isNotEmpty() }
        probing = false
        if (mapped == null) {
            statusText = "STUN 探测失败。请确认当前网络可访问公网并允许 UDP 出站。"
            return
        }
        localLanIpv4 = NetworkUtils.getLocalIpAddress(context).orEmpty()
        localPublicHost = mapped.address
        localPublicPortInput = mapped.port.toString()
        statusText = when {
            batch.allMismatch -> {
                showStunPicker = true
                "STUN 探测成功，但所有结果与 ipip 不一致，请先手动选择地址。"
            }
            batch.matchedByIpip.isNotEmpty() -> {
                "STUN 探测成功（检测到 ipip 匹配项，默认使用首个 STUN 结果）：${mapped.address}:${mapped.port}。"
            }
            else -> {
                "STUN 探测成功：${mapped.address}:${mapped.port}（${mapped.stunServer}）。"
            }
        }
    }

    if (showScanner) {
        QRCodeScanner(
            onQRCodeScanned = { content ->
                showScanner = false
                val peer = parsePeerFromQr(content)
                if (peer == null) {
                    statusText = "二维码内容无法识别为 IPv4 STUN 端点。"
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
        title = "选择 IPv4 STUN 地址",
        result = stunProbeResult,
        selected = stunProbeResult
            ?.endpoints
            ?.firstOrNull { it.address == localPublicHost && it.port == localPublicPortInput.toIntOrNull() },
        onSelect = { chosen ->
            localPublicHost = chosen.address
            localPublicPortInput = chosen.port.toString()
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
            enabled = !running && !probing
        ) {
            Text("返回传输实验室")
        }

        Text("IPv4 STUN UDP 打洞验证（实验）", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            text = "双方先用 UDP 多 STUN 获取公网映射 IP:端口，扫码交换后同时启动 UDP 打洞验证。",
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
                Text("我的端点", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = localLanIpv4,
                    onValueChange = { localLanIpv4 = it.trim() },
                    label = { Text("本地局域网 IPv4（只读参考）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = false
                )
                OutlinedTextField(
                    value = localListenPortInput,
                    onValueChange = { localListenPortInput = it.filter(Char::isDigit) },
                    label = { Text("本地监听端口") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running && !probing
                )
                OutlinedTextField(
                    value = localPublicHost,
                    onValueChange = { localPublicHost = it.trim() },
                    label = { Text("STUN 公网 IPv4") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = false
                )
                OutlinedTextField(
                    value = localPublicPortInput,
                    onValueChange = { localPublicPortInput = it.filter(Char::isDigit) },
                    label = { Text("STUN 公网端口") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = false
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { scope.launch { probeStunAndFill() } },
                        modifier = Modifier.weight(1f),
                        enabled = !running && !probing
                    ) {
                        Text(if (probing) "探测中..." else "STUN 探测")
                    }
                    OutlinedButton(
                        onClick = {
                            val port = localPublicPortInput.toIntOrNull()
                            if (localPublicHost.isBlank() || port !in 1..65535) {
                                statusText = "请先完成 STUN 探测。"
                                return@OutlinedButton
                            }
                            val payload = JSONObject()
                                .put("type", IPV4_STUN_QR_TYPE)
                                .put("ip", localPublicHost)
                                .put("port", port)
                                .toString()
                            qrBitmap = QRCodeGenerator.generateQRCode(payload, 512)
                            statusText = if (qrBitmap == null) {
                                "二维码生成失败。"
                            } else {
                                "二维码已生成，请让对端扫码。"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !running && !probing
                    ) {
                        Text("生成二维码")
                    }
                }
                if (!probing && (stunProbeResult?.endpoints?.isNotEmpty() == true)) {
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
                        contentDescription = "ipv4_stun_qr",
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
                Text("对端端点", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = remoteHostInput,
                    onValueChange = { remoteHostInput = it.trim() },
                    label = { Text("对端公网 IPv4 / 主机") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running && !probing
                )
                OutlinedTextField(
                    value = remotePortInput,
                    onValueChange = { remotePortInput = it.filter(Char::isDigit) },
                    label = { Text("对端公网端口") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running && !probing
                )
                OutlinedButton(
                    onClick = { showScanner = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !running && !probing
                ) {
                    Text("扫码识别对端")
                }
                Button(
                    onClick = {
                        val localPort = localListenPortInput.toIntOrNull()
                        val remotePort = remotePortInput.toIntOrNull()
                        if (localPort == null || remotePort == null || localPort !in 1..65535 || remotePort !in 1..65535 || remoteHostInput.isBlank()) {
                            statusText = "请先填写有效本地端口与对端端点。"
                            return@Button
                        }
                        running = true
                        statusText = "请与对端同时点击开始，正在进行 15 秒 UDP 打洞验证..."
                        scope.launch {
                            val result = runUdpHolePunchTest(
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
                    enabled = !running && !probing
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
