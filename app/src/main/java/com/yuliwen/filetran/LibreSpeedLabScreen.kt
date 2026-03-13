package com.yuliwen.filetran

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.LinkedHashSet

@Composable
fun LibreSpeedLabScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverMode by rememberSaveable { mutableIntStateOf(0) } // 0: IPv4, 1: IPv6
    var portInput by rememberSaveable { mutableStateOf("8989") }
    var advertiseHost by rememberSaveable { mutableStateOf("") }
    var natStunEnabled by rememberSaveable { mutableStateOf(false) }
    var natKeepAliveEnabled by rememberSaveable { mutableStateOf(false) }
    var natKeepAliveIntervalInput by rememberSaveable { mutableStateOf("25") }
    var statusText by remember { mutableStateOf("\u672a\u521b\u5efa\u670d\u52a1\u5668\u3002") }
    var starting by remember { mutableStateOf(false) }

    var localIpv4List by remember { mutableStateOf<List<String>>(emptyList()) }
    var localIpv6List by remember { mutableStateOf<List<String>>(emptyList()) }

    var server by remember { mutableStateOf<LibreSpeedServer?>(null) }
    var runningHost by remember { mutableStateOf<String?>(null) } // public or selected host
    var runningPort by remember { mutableStateOf<Int?>(null) } // public or selected port
    var runningLocalUrl by remember { mutableStateOf<String?>(null) } // local listen URL
    var endpointUrl by remember { mutableStateOf<String?>(null) } // URL for QR and external access
    var natStunStatus by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var stunProbeResult by remember { mutableStateOf<StunProbeBatchResult?>(null) }
    var showStunPicker by remember { mutableStateOf(false) }
    var natKeepAliveStatus by remember { mutableStateOf<String?>(null) }

    fun stopServer(reason: String) {
        runCatching { server?.stop() }
        server = null
        runningHost = null
        runningPort = null
        runningLocalUrl = null
        endpointUrl = null
        natStunStatus = null
        qrBitmap = null
        stunProbeResult = null
        showStunPicker = false
        natKeepAliveStatus = null
        statusText = reason
        starting = false
    }

    fun applyMappedEndpoint(mapped: StunMappedEndpoint?, batch: StunProbeBatchResult?, localHost: String, port: Int) {
        stunProbeResult = batch?.takeIf { it.endpoints.isNotEmpty() }
        val publicHost = mapped?.address?.trim()?.takeIf { it.isNotBlank() } ?: localHost
        val publicPort = mapped?.port?.takeIf { it in 1..65535 } ?: port
        val publicFormattedHost = NetworkUtils.formatHostForUrl(publicHost)
        val publicUrl = "http://$publicFormattedHost:$publicPort/"
        runningHost = publicHost
        runningPort = publicPort
        endpointUrl = publicUrl
        qrBitmap = QRCodeGenerator.generateQRCode(publicUrl, 520)
        natStunStatus = when {
            !natStunEnabled -> "未开启 NAT STUN，当前使用局域网地址。"
            mapped != null && (batch?.allMismatch == true) -> {
                showStunPicker = true
                "已获取 STUN 映射，但均与 ipip 不一致，请手动选择地址。"
            }
            mapped != null && (batch?.matchedByIpip?.isNotEmpty() == true) -> {
                "已获取 STUN 映射（存在 ipip 匹配项，默认使用首个 STUN 结果）：${mapped.address}:${mapped.port}"
            }
            mapped != null -> "已通过 STUN 获取外网映射：${mapped.address}:${mapped.port}（${mapped.stunServer}）"
            else -> "未获取到 STUN 映射，已回退为局域网地址。"
        }
    }

    fun refreshLocalCandidates(fillHost: Boolean = false) {
        localIpv4List = collectLocalIpv4Candidates(context)
        localIpv6List = collectLocalIpv6Candidates()
        val currentCandidates = if (serverMode == 0) localIpv4List else localIpv6List
        if (fillHost || advertiseHost.isBlank()) {
            advertiseHost = currentCandidates.firstOrNull().orEmpty()
        }
        statusText = if (currentCandidates.isEmpty()) {
            if (serverMode == 0) {
                "\u672a\u68c0\u6d4b\u5230\u53ef\u7528 IPv4 \u5730\u5740\uff0c\u8bf7\u624b\u52a8\u586b\u5199\u3002"
            } else {
                "\u672a\u68c0\u6d4b\u5230\u53ef\u7528 IPv6 \u5730\u5740\uff0c\u8bf7\u624b\u52a8\u586b\u5199\u3002"
            }
        } else {
            if (serverMode == 0) {
                "\u5df2\u5237\u65b0\u672c\u673a IPv4 \u5730\u5740\u3002"
            } else {
                "\u5df2\u5237\u65b0\u672c\u673a IPv6 \u5730\u5740\u3002"
            }
        }
    }

    fun startServer() {
        if (starting) return
        val port = portInput.trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            statusText = "\u7aef\u53e3\u65e0\u6548\uff081~65535\uff09\u3002"
            return
        }

        val manualHost = advertiseHost.trim()
        runCatching { server?.stop() }
        server = null
        starting = true
        statusText = if (serverMode == 0) {
            "\u6b63\u5728\u542f\u52a8 IPv4 \u6d4b\u901f\u670d\u52a1\u5668..."
        } else {
            "\u6b63\u5728\u542f\u52a8 IPv6 \u6d4b\u901f\u670d\u52a1\u5668..."
        }

        scope.launch {
            val fallbackHost = withContext(Dispatchers.IO) {
                val candidates = if (serverMode == 0) {
                    collectLocalIpv4Candidates(context)
                } else {
                    collectLocalIpv6Candidates()
                }
                candidates.firstOrNull().orEmpty()
            }
            val localHost = if (manualHost.isNotBlank()) manualHost else fallbackHost
            if (localHost.isBlank()) {
                starting = false
                statusText = if (serverMode == 0) {
                    "\u672a\u83b7\u53d6\u5230 IPv4 \u5730\u5740\uff0c\u65e0\u6cd5\u542f\u52a8\u3002"
                } else {
                    "\u672a\u83b7\u53d6\u5230 IPv6 \u5730\u5740\uff0c\u65e0\u6cd5\u542f\u52a8\u3002"
                }
                return@launch
            }

            val batch = if (natStunEnabled) {
                withContext(Dispatchers.IO) {
                    NetworkUtils.probeStunMappedEndpointBatch(
                        localPort = port,
                        preferIpv6 = serverMode == 1,
                        transport = StunTransportType.TCP
                    )
                }
            } else {
                null
            }
            val mapped = batch?.preferredEndpoint

            val localFormattedHost = NetworkUtils.formatHostForUrl(localHost)
            val localUrl = "http://$localFormattedHost:$port/"

            val newServer = LibreSpeedServer(context, port)
            val startResult = withContext(Dispatchers.IO) {
                runCatching { newServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            }

            startResult.onSuccess {
                server = newServer
                runningLocalUrl = localUrl
                applyMappedEndpoint(mapped, batch, localHost, port)
                natKeepAliveStatus = null
                statusText = if (serverMode == 0) {
                    "Liber Speed Test \u5df2\u542f\u52a8\uff08IPv4\uff09\u3002"
                } else {
                    "Liber Speed Test \u5df2\u542f\u52a8\uff08IPv6\uff09\u3002"
                }
            }.onFailure { e ->
                runCatching { newServer.stop() }
                natStunStatus = null
                statusText = "\u542f\u52a8\u5931\u8d25: ${e.message ?: "unknown"}"
            }
            starting = false
        }
    }

    LaunchedEffect(serverMode) {
        refreshLocalCandidates(fillHost = true)
    }
    LaunchedEffect(server, natStunEnabled, natKeepAliveEnabled, natKeepAliveIntervalInput, portInput, serverMode, advertiseHost) {
        if (server == null || !natStunEnabled || !natKeepAliveEnabled) {
            if (!natKeepAliveEnabled) natKeepAliveStatus = null
            return@LaunchedEffect
        }
        val port = portInput.trim().toIntOrNull()
        val intervalSeconds = natKeepAliveIntervalInput.trim().toLongOrNull()
        val localHost = advertiseHost.trim().ifBlank {
            val candidates = if (serverMode == 0) localIpv4List else localIpv6List
            candidates.firstOrNull().orEmpty()
        }
        if (port == null || port !in 1..65535 || intervalSeconds == null || intervalSeconds < 5L || localHost.isBlank()) {
            natKeepAliveStatus = "状态保持未启动，请检查端口、地址和间隔。"
            return@LaunchedEffect
        }
        while (isActive && server != null && natStunEnabled && natKeepAliveEnabled) {
            val batch = withContext(Dispatchers.IO) {
                NetworkUtils.probeStunMappedEndpointBatch(
                    localPort = port,
                    preferIpv6 = serverMode == 1,
                    transport = StunTransportType.TCP
                )
            }
            val mapped = batch.preferredEndpoint
            if (mapped != null) {
                applyMappedEndpoint(mapped, batch, localHost, port)
                natKeepAliveStatus = "状态保持已发送，最近映射：${mapped.address}:${mapped.port}"
            } else {
                natKeepAliveStatus = "状态保持发送失败，未获取到新的映射。"
            }
            delay(intervalSeconds * 1000L)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopServer("\u9875\u9762\u9000\u51fa\uff0c\u6d4b\u901f\u670d\u52a1\u5668\u5df2\u505c\u6b62\u3002")
        }
    }

    StunEndpointPickerDialog(
        visible = showStunPicker,
        title = "选择 LibreSpeed STUN 地址",
        result = stunProbeResult,
        selected = stunProbeResult
            ?.endpoints
            ?.firstOrNull { it.address == runningHost && it.port == runningPort },
        onSelect = { chosen ->
            val chosenHost = chosen.address.trim()
            if (chosenHost.isBlank() || chosen.port !in 1..65535) return@StunEndpointPickerDialog
            applyMappedEndpoint(chosen, stunProbeResult, advertiseHost.ifBlank { chosenHost }, runningPort ?: chosen.port)
            natStunStatus = "已手动选择 STUN 映射：$chosenHost:${chosen.port}（${chosen.stunServer}）"
            statusText = "已切换展示地址为：$chosenHost:${chosen.port}"
        },
        onDismiss = { showStunPicker = false }
    )

    val currentCandidates = if (serverMode == 0) localIpv4List else localIpv6List

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("\u8fd4\u56de\u4f20\u8f93\u5b9e\u9a8c\u5ba4")
        }

        Text("Liber Speed Test", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "\u652f\u6301 IPv4 / IPv6 \u8bbf\u95ee\uff0c\u53ef\u9009 STUN \u591a\u670d\u52a1\u5668\u63a2\u6d4b\u5916\u7f51\u6620\u5c04\u5730\u5740\u3002",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("\u8bbf\u95ee\u6a21\u5f0f", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (serverMode == 0) {
                        Button(onClick = {}, modifier = Modifier.weight(1f), enabled = !starting) { Text("IPv4") }
                    } else {
                        OutlinedButton(
                            onClick = { serverMode = 0 },
                            modifier = Modifier.weight(1f),
                            enabled = !starting
                        ) { Text("IPv4") }
                    }
                    if (serverMode == 1) {
                        Button(onClick = {}, modifier = Modifier.weight(1f), enabled = !starting) { Text("IPv6") }
                    } else {
                        OutlinedButton(
                            onClick = { serverMode = 1 },
                            modifier = Modifier.weight(1f),
                            enabled = !starting
                        ) { Text("IPv6") }
                    }
                }

                OutlinedTextField(
                    value = portInput,
                    onValueChange = { raw -> portInput = raw.filter { it.isDigit() } },
                    label = { Text("\u76d1\u542c\u7aef\u53e3") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !starting
                )
                OutlinedTextField(
                    value = advertiseHost,
                    onValueChange = { advertiseHost = it.trim() },
                    label = {
                        Text(
                            if (serverMode == 0) {
                                "\u5c55\u793a IPv4\uff08\u4e8c\u7ef4\u7801\u4f7f\u7528\u6b64\u5730\u5740\uff09"
                            } else {
                                "\u5c55\u793a IPv6\uff08\u4e8c\u7ef4\u7801\u4f7f\u7528\u6b64\u5730\u5740\uff09"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !starting
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("NAT STUN \u5916\u7f51\u63a2\u6d4b")
                    Switch(
                        checked = natStunEnabled,
                        onCheckedChange = { natStunEnabled = it },
                        enabled = !starting
                    )
                }
                Text(
                    "\u5f00\u542f\u540e\u5c06\u5c1d\u8bd5\u83b7\u53d6 NAT \u6620\u5c04\u7684\u5916\u7f51 IP \u548c\u7aef\u53e3\uff0c\u4f9b\u5916\u90e8\u7f51\u7edc\u8bbf\u95ee\u3002",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (natStunEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("状态保持")
                        Switch(
                            checked = natKeepAliveEnabled,
                            onCheckedChange = { natKeepAliveEnabled = it },
                            enabled = !starting && server != null
                        )
                    }
                    OutlinedTextField(
                        value = natKeepAliveIntervalInput,
                        onValueChange = { natKeepAliveIntervalInput = it.filter(Char::isDigit) },
                        label = { Text("状态保持间隔（秒）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !starting && server != null
                    )
                    natKeepAliveStatus?.let {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (stunProbeResult?.endpoints?.isNotEmpty() == true) {
                    OutlinedButton(
                        onClick = { showStunPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !starting
                    ) {
                        Text("选择 STUN 地址")
                    }
                }

                OutlinedButton(
                    onClick = { refreshLocalCandidates(fillHost = true) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !starting
                ) {
                    Text(if (serverMode == 0) "\u5237\u65b0\u672c\u673a IPv4" else "\u5237\u65b0\u672c\u673a IPv6")
                }

                if (currentCandidates.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        currentCandidates.take(6).forEach { host ->
                            if (host == advertiseHost) {
                                Button(onClick = { advertiseHost = host }, enabled = !starting) { Text(host) }
                            } else {
                                OutlinedButton(onClick = { advertiseHost = host }, enabled = !starting) { Text(host) }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { startServer() },
                        modifier = Modifier.weight(1f),
                        enabled = !starting
                    ) {
                        Text(
                            if (starting) {
                                "\u542f\u52a8\u4e2d..."
                            } else if (server == null) {
                                "\u521b\u5efa\u670d\u52a1\u5668"
                            } else {
                                "\u91cd\u542f\u670d\u52a1\u5668"
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = { stopServer("\u6d4b\u901f\u670d\u52a1\u5668\u5df2\u505c\u6b62\u3002") },
                        modifier = Modifier.weight(1f),
                        enabled = server != null && !starting
                    ) {
                        Text("\u505c\u6b62\u670d\u52a1\u5668")
                    }
                }
            }
        }

        Text("\u72b6\u6001: $statusText", color = MaterialTheme.colorScheme.primary)

        if (endpointUrl != null && runningHost != null && runningPort != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("\u670d\u52a1\u5668\u4fe1\u606f", fontWeight = FontWeight.SemiBold)
                    Text("\u5916\u90e8 IP: $runningHost")
                    Text("\u5916\u90e8\u7aef\u53e3: $runningPort")
                    runningLocalUrl?.let { Text("\u672c\u673a\u76d1\u542c: $it") }
                    Text("\u8bbf\u95ee\u5730\u5740: $endpointUrl")
                    natStunStatus?.let {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { endpointUrl?.let { copyToClipboard(context, it) } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("\u590d\u5236\u5730\u5740")
                        }
                        OutlinedButton(
                            onClick = { endpointUrl?.let { openInBrowser(context, it) } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("\u6d4f\u89c8\u5668\u6253\u5f00")
                        }
                    }
                    qrBitmap?.let { bitmap ->
                        Spacer(Modifier.height(4.dp))
                        Text("\u626b\u7801\u6d4b\u901f", fontWeight = FontWeight.SemiBold)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "libre_speed_test_qr",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun collectLocalIpv4Candidates(context: Context): List<String> {
    val out = LinkedHashSet<String>()
    NetworkUtils.getLocalIpAddress(context)?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
        while (interfaces.hasMoreElements()) {
            val itf = interfaces.nextElement()
            if (!itf.isUp || itf.isLoopback) continue
            val addresses = itf.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    out += address.hostAddress.orEmpty()
                }
            }
        }
    }
    return out.filter { it.isNotBlank() }
}

private fun collectLocalIpv6Candidates(): List<String> {
    val out = LinkedHashSet<String>()
    NetworkUtils.getInterfaceGlobalIpv6Address()
        ?.trim()
        ?.substringBefore('%')
        ?.takeIf { it.isNotBlank() }
        ?.let(out::add)
    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
        while (interfaces.hasMoreElements()) {
            val itf = interfaces.nextElement()
            if (!itf.isUp || itf.isLoopback) continue
            val addresses = itf.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet6Address && !address.isLoopbackAddress) {
                    val ip = address.hostAddress.orEmpty().substringBefore('%')
                    if (ip.isBlank() || ip.equals("::1", ignoreCase = true)) continue
                    if (ip.startsWith("fe80", ignoreCase = true)) continue
                    out += ip
                }
            }
        }
    }
    return out.filter { it.isNotBlank() }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("librespeed_url", text))
}

private fun openInBrowser(context: Context, url: String) {
    val target = runCatching { Uri.parse(url) }.getOrNull() ?: return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, target).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
