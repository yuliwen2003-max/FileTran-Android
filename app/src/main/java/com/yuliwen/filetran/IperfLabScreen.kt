package com.yuliwen.filetran

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableStateListOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale

private const val IPERF_LAB_QR_TYPE = "filetran_iperf_server_v1"
private const val IPERF2_LATEST_VERSION = "2.2.1"
private const val IPERF3_LATEST_VERSION = "3.20"
private const val IPERF_LOG_MAX_LINES = 500
private val IPERF_RATE_REGEX = Regex("""([0-9]+(?:\.[0-9]+)?)\s*([KMGTP]?)(i?)(bits|Bytes)/sec""", RegexOption.IGNORE_CASE)
private val IPERF_SUM_TAG_REGEX = Regex("""\[\s*SUM\s*]""", RegexOption.IGNORE_CASE)
private val IPERF_STREAM_TAG_REGEX = Regex("""\[\s*\d+\s*]""")

private enum class IperfBinary(
    val label: String,
    val nativeSoName: String,
    val version: String
) {
    IPERF2(label = "iperf2", nativeSoName = "libiperf2.so", version = IPERF2_LATEST_VERSION),
    IPERF3(label = "iperf3", nativeSoName = "libiperf3.so", version = IPERF3_LATEST_VERSION)
}

private enum class IperfRole(val label: String) {
    CLIENT("客户端"),
    SERVER("服务端")
}

private enum class IperfTransport(val label: String, val id: String) {
    TCP("TCP", "tcp"),
    UDP("UDP", "udp");

    companion object {
        fun fromIdOrNull(id: String): IperfTransport? = entries.firstOrNull { it.id == id.trim().lowercase(Locale.getDefault()) }
    }
}

private enum class IperfIpFamily(val label: String, val id: String) {
    IPV4("IPv4", "ipv4"),
    IPV6("IPv6", "ipv6");

    companion object {
        fun fromIdOrNull(id: String): IperfIpFamily? = entries.firstOrNull { it.id == id.trim().lowercase(Locale.getDefault()) }
    }
}

private enum class RateDirection {
    SEND, RECEIVE, UNKNOWN
}

private data class IperfQrServer(
    val host: String,
    val port: Int,
    val transport: IperfTransport,
    val ipFamily: IperfIpFamily,
    val binary: IperfBinary?
)

private data class ParsedRate(
    val text: String,
    val direction: RateDirection
)

@Composable
fun IperfLabScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiDateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    var binaryName by rememberSaveable { mutableStateOf(IperfBinary.IPERF3.name) }
    var roleName by rememberSaveable { mutableStateOf(IperfRole.CLIENT.name) }
    var transportName by rememberSaveable { mutableStateOf(IperfTransport.TCP.name) }
    var ipFamilyName by rememberSaveable { mutableStateOf(IperfIpFamily.IPV4.name) }

    var clientHost by rememberSaveable { mutableStateOf("") }
    var clientPort by rememberSaveable { mutableStateOf("5201") }
    var durationSeconds by rememberSaveable { mutableStateOf("10") }
    var reportInterval by rememberSaveable { mutableStateOf("1") }
    var parallelStreams by rememberSaveable { mutableStateOf("1") }
    var udpBandwidth by rememberSaveable { mutableStateOf("100M") }
    var packetLength by rememberSaveable { mutableStateOf("") }
    var omitSeconds by rememberSaveable { mutableStateOf("0") }
    var reverseTransfer by rememberSaveable { mutableStateOf(false) }
    var bidirectionalTransfer by rememberSaveable { mutableStateOf(false) }

    var serverPort by rememberSaveable { mutableStateOf("5201") }
    var serverReportInterval by rememberSaveable { mutableStateOf("1") }
    var serverOneOff by rememberSaveable { mutableStateOf(false) }
    var serverAdvertiseHost by rememberSaveable { mutableStateOf("") }

    var localIpv4List by remember { mutableStateOf<List<String>>(emptyList()) }
    var localIpv6List by remember { mutableStateOf<List<String>>(emptyList()) }
    var statusText by remember { mutableStateOf("就绪。请先选择 iperf2 / iperf3 与角色。") }
    val logLines = remember { mutableStateListOf<String>() }

    var currentSendRate by remember { mutableStateOf("--") }
    var currentReceiveRate by remember { mutableStateOf("--") }
    var currentRateTime by remember { mutableStateOf("--") }
    var preferAggregateRate by remember { mutableStateOf(false) }

    var running by remember { mutableStateOf(false) }
    var activeProcess by remember { mutableStateOf<Process?>(null) }
    var readerJob by remember { mutableStateOf<Job?>(null) }
    var waiterJob by remember { mutableStateOf<Job?>(null) }

    var showScanner by remember { mutableStateOf(false) }
    var baseChoiceExpanded by rememberSaveable { mutableStateOf(true) }
    var parameterExpanded by rememberSaveable { mutableStateOf(true) }
    var logExpanded by rememberSaveable { mutableStateOf(false) }

    val binary = IperfBinary.valueOf(binaryName)
    val role = IperfRole.valueOf(roleName)
    val transport = IperfTransport.valueOf(transportName)
    val ipFamily = IperfIpFamily.valueOf(ipFamilyName)
    val localIpCandidates = if (ipFamily == IperfIpFamily.IPV6) localIpv6List else localIpv4List

    fun parsePortOrNull(raw: String): Int? {
        val p = raw.trim().toIntOrNull() ?: return null
        return p.takeIf { it in 1..65535 }
    }

    fun parseRateDirection(line: String): RateDirection {
        val lower = line.lowercase(Locale.getDefault())
        return when {
            "sender" in lower || "sending" in lower || " tx " in lower || "发送" in line -> RateDirection.SEND
            "receiver" in lower || "receiving" in lower || " rx " in lower || "接收" in line -> RateDirection.RECEIVE
            else -> RateDirection.UNKNOWN
        }
    }

    fun parseRate(line: String): ParsedRate? {
        val match = IPERF_RATE_REGEX.findAll(line).lastOrNull() ?: return null
        val num = match.groupValues[1]
        val unit = match.groupValues[2]
        val i = match.groupValues[3]
        val metric = match.groupValues[4]
        val text = "$num ${unit}${i}${metric}/s"
        return ParsedRate(text = text, direction = parseRateDirection(line))
    }

    fun updateRateCardByLine(line: String) {
        val parsed = parseRate(line) ?: return
        val hasSumTag = IPERF_SUM_TAG_REGEX.containsMatchIn(line)
        val hasStreamTag = IPERF_STREAM_TAG_REGEX.containsMatchIn(line)
        val configuredParallel = parallelStreams.trim().toIntOrNull()?.coerceAtLeast(1) ?: 1

        if (hasSumTag) {
            preferAggregateRate = true
        } else {
            // Once [SUM] appears, ignore per-thread lines to keep card stable at aggregate speed.
            if (preferAggregateRate && hasStreamTag) return
            // If user configured multi-thread client, drop per-thread rows even before [SUM] arrives.
            if (configuredParallel > 1 && hasStreamTag) return
        }

        when (parsed.direction) {
            RateDirection.SEND -> currentSendRate = parsed.text
            RateDirection.RECEIVE -> currentReceiveRate = parsed.text
            RateDirection.UNKNOWN -> {
                when {
                    bidirectionalTransfer -> {
                        currentSendRate = parsed.text
                        currentReceiveRate = parsed.text
                    }
                    role == IperfRole.CLIENT && !reverseTransfer -> currentSendRate = parsed.text
                    role == IperfRole.CLIENT && reverseTransfer -> currentReceiveRate = parsed.text
                    role == IperfRole.SERVER -> currentReceiveRate = parsed.text
                }
            }
        }
        currentRateTime = uiDateFormat.format(Date())
    }

    fun appendLog(message: String) {
        val line = "[${uiDateFormat.format(Date())}] $message"
        logLines += line
        if (logLines.size > IPERF_LOG_MAX_LINES) {
            val drop = logLines.size - IPERF_LOG_MAX_LINES
            repeat(drop) { logLines.removeAt(0) }
        }
    }

    fun stopCurrentProcess(reason: String) {
        val process = activeProcess
        if (process != null) {
            appendLog("停止运行: $reason")
            process.destroy()
            scope.launch(Dispatchers.IO) {
                delay(500)
                if (process.isAlive) process.destroyForcibly()
            }
        }
        readerJob?.cancel()
        waiterJob?.cancel()
        readerJob = null
        waiterJob = null
        activeProcess = null
        running = false
    }

    suspend fun launchIperfProcess(args: List<String>) {
        val executable = ensureIperfBinaryExecutable(context, binary)
        val command = listOf(executable.absolutePath) + args
        appendLog("$ ${command.joinToString(" ")}")
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        }
        activeProcess = process
        running = true
        currentSendRate = "--"
        currentReceiveRate = "--"
        currentRateTime = "--"
        preferAggregateRate = false

        readerJob = scope.launch(Dispatchers.IO) {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        withContext(Dispatchers.Main) {
                            appendLog(line)
                            updateRateCardByLine(line)
                        }
                    }
                }
            }
        }
        waiterJob = scope.launch(Dispatchers.IO) {
            val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
            withContext(Dispatchers.Main) {
                if (activeProcess == process) {
                    running = false
                    activeProcess = null
                    readerJob?.cancel()
                    readerJob = null
                    waiterJob = null
                    statusText = if (exitCode == 0) "进程已结束。" else "进程结束，退出码: $exitCode"
                    appendLog(statusText)
                }
            }
        }
    }

    fun buildIpFamilyArgs(): List<String> {
        return when (binary) {
            IperfBinary.IPERF3 -> listOf(if (ipFamily == IperfIpFamily.IPV6) "-6" else "-4")
            IperfBinary.IPERF2 -> if (ipFamily == IperfIpFamily.IPV6) listOf("-V") else emptyList()
        }
    }
    fun startClient() {
        val host = clientHost.trim()
        val port = parsePortOrNull(clientPort)
        val duration = durationSeconds.trim().toIntOrNull()?.takeIf { it > 0 }
        val parallel = parallelStreams.trim().toIntOrNull()?.coerceIn(1, 128)
        if (host.isBlank()) {
            statusText = "请填写服务端地址。"
            return
        }
        if (port == null) {
            statusText = "端口无效（1~65535）。"
            return
        }
        if (duration == null) {
            statusText = "测试时长必须为正整数秒。"
            return
        }
        if (parallel == null) {
            statusText = "线程数量必须为 1~128。"
            return
        }

        val interval = reportInterval.trim().takeIf { it.isNotBlank() } ?: "1"
        val args = mutableListOf<String>()
        args += buildIpFamilyArgs()
        args += listOf("-c", host, "-p", port.toString(), "-t", duration.toString(), "-i", interval)
        if (parallel > 1) args += listOf("-P", parallel.toString())
        if (transport == IperfTransport.UDP) {
            args += "-u"
            if (udpBandwidth.trim().isNotBlank()) args += listOf("-b", udpBandwidth.trim())
        }
        packetLength.trim().toIntOrNull()?.takeIf { it > 0 }?.let { args += listOf("-l", it.toString()) }

        if (binary == IperfBinary.IPERF3) {
            val omit = omitSeconds.trim().toIntOrNull()?.takeIf { it >= 0 } ?: 0
            if (omit > 0) args += listOf("-O", omit.toString())
            if (reverseTransfer) args += "-R"
            if (bidirectionalTransfer) args += "--bidir"
        } else {
            if (reverseTransfer) args += "-r"
            if (bidirectionalTransfer) args += "-d"
        }

        statusText = "正在启动客户端..."
        scope.launch {
            runCatching { launchIperfProcess(args) }
                .onFailure {
                    running = false
                    activeProcess = null
                    statusText = "启动失败: ${it.message ?: "unknown"}"
                    appendLog(statusText)
                }
        }
    }

    fun startServer() {
        val port = parsePortOrNull(serverPort)
        if (port == null) {
            statusText = "端口无效（1~65535）。"
            return
        }
        val interval = serverReportInterval.trim().takeIf { it.isNotBlank() } ?: "1"
        val args = mutableListOf<String>()
        args += buildIpFamilyArgs()
        args += listOf("-s", "-p", port.toString(), "-i", interval)
        if (binary == IperfBinary.IPERF2 && transport == IperfTransport.UDP) {
            args += "-u"
        }
        if (serverOneOff) args += "-1"

        statusText = "正在启动服务端..."
        scope.launch {
            runCatching { launchIperfProcess(args) }
                .onFailure {
                    running = false
                    activeProcess = null
                    statusText = "启动失败: ${it.message ?: "unknown"}"
                    appendLog(statusText)
                }
        }
    }

    fun refreshLocalCandidates(forceAssignServerHost: Boolean = false) {
        localIpv4List = collectLocalIpv4Candidates(context)
        localIpv6List = collectLocalIpv6Candidates(context)
        val candidates = if (ipFamily == IperfIpFamily.IPV6) localIpv6List else localIpv4List
        if (forceAssignServerHost) {
            serverAdvertiseHost = candidates.firstOrNull().orEmpty()
            statusText = if (serverAdvertiseHost.isNotBlank()) {
                "已填充服务端地址并刷新二维码。"
            } else {
                "未获取到本机${ipFamily.label}地址，请手动输入。"
            }
            return
        }
        if (serverAdvertiseHost.isBlank()) {
            serverAdvertiseHost = candidates.firstOrNull().orEmpty()
        }
        statusText = if (candidates.isEmpty()) {
            "未获取到本机${ipFamily.label}地址，请手动输入。"
        } else {
            "已刷新本机${ipFamily.label}地址。"
        }
    }

    fun applyServerAddress(host: String, family: IperfIpFamily? = null) {
        family?.let { ipFamilyName = it.name }
        serverAdvertiseHost = host.trim()
        if (serverAdvertiseHost.isNotBlank()) {
            statusText = "服务端地址已填充，二维码已更新。"
        }
    }

    val qrContent = remember(binaryName, transportName, ipFamilyName, serverAdvertiseHost, serverPort) {
        buildIperfServerQrPayload(
            host = serverAdvertiseHost,
            port = serverPort,
            transport = transport,
            ipFamily = ipFamily,
            binary = binary
        )
    }
    val qrBitmap: Bitmap? = remember(qrContent) {
        qrContent?.let { QRCodeGenerator.generateQRCode(it, 520) }
    }

    LaunchedEffect(ipFamilyName) {
        refreshLocalCandidates()
        if (clientHost.isBlank()) {
            clientHost = localIpCandidates.firstOrNull().orEmpty()
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopCurrentProcess("页面退出") }
    }

    if (showScanner) {
        QRCodeScanner(
            onQRCodeScanned = { raw ->
                showScanner = false
                val parsed = parseIperfServerFromQrOrRaw(raw)
                if (parsed == null) {
                    statusText = "二维码不是有效的 iperf 服务端配置。"
                    return@QRCodeScanner
                }
                clientHost = parsed.host
                clientPort = parsed.port.toString()
                transportName = parsed.transport.name
                ipFamilyName = parsed.ipFamily.name
                parsed.binary?.let { binaryName = it.name }
                statusText = "已导入服务端: ${parsed.host}:${parsed.port}"
            },
            onDismiss = { showScanner = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回传输实验室")
        }

        Text("iPerf 打流 & 测速", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "内置版本: iperf2 $IPERF2_LATEST_VERSION / iperf3 $IPERF3_LATEST_VERSION",
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("基础选择", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { baseChoiceExpanded = !baseChoiceExpanded }) {
                        Text(if (baseChoiceExpanded) "收起" else "展开")
                    }
                }
                if (baseChoiceExpanded) {
                    LabeledChoiceRow(
                        label = "版本",
                        options = IperfBinary.entries.map { it.label to (it == binary) },
                        onSelect = { binaryName = if (it == "iperf2") IperfBinary.IPERF2.name else IperfBinary.IPERF3.name }
                    )
                    LabeledChoiceRow(
                        label = "角色",
                        options = IperfRole.entries.map { it.label to (it == role) },
                        onSelect = { roleName = if (it == IperfRole.CLIENT.label) IperfRole.CLIENT.name else IperfRole.SERVER.name }
                    )
                    LabeledChoiceRow(
                        label = "传输协议",
                        options = IperfTransport.entries.map { it.label to (it == transport) },
                        onSelect = { transportName = if (it == IperfTransport.TCP.label) IperfTransport.TCP.name else IperfTransport.UDP.name }
                    )
                    LabeledChoiceRow(
                        label = "传输模式",
                        options = IperfIpFamily.entries.map { it.label to (it == ipFamily) },
                        onSelect = { ipFamilyName = if (it == IperfIpFamily.IPV4.label) IperfIpFamily.IPV4.name else IperfIpFamily.IPV6.name }
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("实时速率", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    RateBox(title = "发送", value = currentSendRate, modifier = Modifier.weight(1f))
                    RateBox(title = "接收", value = currentReceiveRate, modifier = Modifier.weight(1f))
                }
                Text("更新时间: $currentRateTime", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(if (role == IperfRole.CLIENT) "客户端参数" else "服务端参数", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { parameterExpanded = !parameterExpanded }) {
                        Text(if (parameterExpanded) "收起" else "展开")
                    }
                }
                if (parameterExpanded) {
                    if (role == IperfRole.CLIENT) {
                    OutlinedTextField(
                        value = clientHost,
                        onValueChange = { clientHost = it.trim() },
                        label = { Text("服务端地址（支持 IPv4 / IPv6 / 域名）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !running
                    )
                    OutlinedTextField(
                        value = clientPort,
                        onValueChange = { clientPort = it.trim() },
                        label = { Text("端口") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !running
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("测试时长", fontWeight = FontWeight.SemiBold)
                            QuickOptionRow(
                                title = "快捷选择",
                                values = listOf("10", "30", "60", "120"),
                                current = durationSeconds,
                                onPick = { durationSeconds = it },
                                enabled = !running
                            )
                            OutlinedTextField(
                                value = durationSeconds,
                                onValueChange = { durationSeconds = it.trim() },
                                label = { Text("测试时长（秒）") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !running
                            )
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("报告间隔", fontWeight = FontWeight.SemiBold)
                            QuickOptionRow(
                                title = "快捷选择",
                                values = listOf("1", "2", "5", "10"),
                                current = reportInterval,
                                onPick = { reportInterval = it },
                                enabled = !running
                            )
                            OutlinedTextField(
                                value = reportInterval,
                                onValueChange = { reportInterval = it.trim() },
                                label = { Text("报告间隔（秒）") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !running
                            )
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("线程数量", fontWeight = FontWeight.SemiBold)
                            QuickOptionRow(
                                title = "快捷选择",
                                values = listOf("1", "2", "4", "8", "16"),
                                current = parallelStreams,
                                onPick = { parallelStreams = it },
                                enabled = !running
                            )
                            OutlinedTextField(
                                value = parallelStreams,
                                onValueChange = { parallelStreams = it.trim() },
                                label = { Text("线程数量（-P）") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !running
                            )
                        }
                    }

                    if (transport == IperfTransport.UDP) {
                        QuickOptionRow(
                            title = "UDP 带宽快捷",
                            values = listOf("20M", "50M", "100M", "500M"),
                            current = udpBandwidth,
                            onPick = { udpBandwidth = it },
                            enabled = !running
                        )
                        OutlinedTextField(
                            value = udpBandwidth,
                            onValueChange = { udpBandwidth = it.trim() },
                            label = { Text("UDP 带宽（-b）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !running
                        )
                    }

                    OutlinedTextField(
                        value = packetLength,
                        onValueChange = { packetLength = it.trim() },
                        label = { Text("包长度（-l，可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !running
                    )

                    if (binary == IperfBinary.IPERF3) {
                        OutlinedTextField(
                            value = omitSeconds,
                            onValueChange = { omitSeconds = it.trim() },
                            label = { Text("忽略预热秒数（-O）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !running
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val reverseLabel = if (binary == IperfBinary.IPERF3) "反向传输（-R）" else "反向传输（-r）"
                        Text(reverseLabel)
                        Switch(
                            checked = reverseTransfer,
                            onCheckedChange = {
                                reverseTransfer = it
                                if (it) bidirectionalTransfer = false
                            },
                            enabled = !running
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val bidirLabel = if (binary == IperfBinary.IPERF3) "双向传输（--bidir）" else "双向传输（-d）"
                        Text(bidirLabel)
                        Switch(
                            checked = bidirectionalTransfer,
                            onCheckedChange = {
                                bidirectionalTransfer = it
                                if (it) reverseTransfer = false
                            },
                            enabled = !running
                        )
                    }
                    OutlinedButton(
                        onClick = { showScanner = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !running
                    ) {
                        Text("扫码导入服务端")
                    }
                    } else {
                    OutlinedTextField(
                        value = serverPort,
                        onValueChange = { serverPort = it.trim() },
                        label = { Text("监听端口") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !running
                    )

                    QuickOptionRow(
                        title = "服务端报告间隔快捷",
                        values = listOf("1", "2", "5", "10"),
                        current = serverReportInterval,
                        onPick = { serverReportInterval = it },
                        enabled = !running
                    )
                    OutlinedTextField(
                        value = serverReportInterval,
                        onValueChange = { serverReportInterval = it.trim() },
                        label = { Text("报告间隔（秒）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !running
                    )
                    OutlinedTextField(
                        value = serverAdvertiseHost,
                        onValueChange = { serverAdvertiseHost = it.trim() },
                        label = { Text("二维码地址（客户端将连接此地址）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !running
                    )
                    OutlinedButton(
                        onClick = { refreshLocalCandidates(forceAssignServerHost = true) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !running
                    ) {
                        Text("刷新本机地址")
                    }
                    if (localIpv4List.isNotEmpty()) {
                        QuickOptionRow(
                            title = "本机 IPv4 快捷填充",
                            values = localIpv4List.take(6),
                            current = if (ipFamily == IperfIpFamily.IPV4) serverAdvertiseHost else "",
                            onPick = {
                                applyServerAddress(it, IperfIpFamily.IPV4)
                            },
                            enabled = !running
                        )
                    }
                    if (localIpv6List.isNotEmpty()) {
                        QuickOptionRow(
                            title = "本机 IPv6 快捷填充",
                            values = localIpv6List.take(6),
                            current = if (ipFamily == IperfIpFamily.IPV6) serverAdvertiseHost else "",
                            onPick = {
                                applyServerAddress(it, IperfIpFamily.IPV6)
                            },
                            enabled = !running
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("单次服务（-1）")
                        Switch(checked = serverOneOff, onCheckedChange = { serverOneOff = it }, enabled = !running)
                    }
                    if (qrBitmap != null) {
                        Spacer(Modifier.height(4.dp))
                        Text("服务端二维码", fontWeight = FontWeight.SemiBold)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "iperf_server_qr",
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

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (running) stopCurrentProcess("用户手动停止")
                    else if (role == IperfRole.CLIENT) startClient() else startServer()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (running) "停止" else if (role == IperfRole.CLIENT) "启动客户端" else "启动服务端")
            }
            OutlinedButton(
                onClick = {
                    logLines.clear()
                    statusText = "日志已清空。"
                },
                modifier = Modifier.weight(1f),
                enabled = !running
            ) {
                Text("清空日志")
            }
        }

        Text("状态: $statusText", color = MaterialTheme.colorScheme.primary)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("运行日志", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { logExpanded = !logExpanded }) {
                        Text(if (logExpanded) "收起" else "展开")
                    }
                }
                if (logExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        val text = if (logLines.isEmpty()) "暂无日志。点击启动后将显示 iperf 输出。" else logLines.joinToString("\n")
                        Text(text = text, fontSize = 12.sp)
                    }
                } else {
                    Text(
                        text = if (logLines.isEmpty()) "日志已折叠（暂无输出）。" else "日志已折叠（当前 ${logLines.size} 行）。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledChoiceRow(
    label: String,
    options: List<Pair<String, Boolean>>,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SingleChoiceRow(options = options, onSelect = onSelect)
    }
}

@Composable
private fun SingleChoiceRow(
    options: List<Pair<String, Boolean>>,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (label, selected) ->
            if (selected) {
                Button(
                    onClick = { onSelect(label) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(label)
                }
            } else {
                OutlinedButton(onClick = { onSelect(label) }) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun QuickOptionRow(
    title: String,
    values: List<String>,
    current: String,
    onPick: (String) -> Unit,
    enabled: Boolean
) {
    Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        values.forEach { value ->
            if (value == current.trim()) {
                Button(
                    onClick = { onPick(value) },
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(value)
                }
            } else {
                OutlinedButton(onClick = { onPick(value) }, enabled = enabled) {
                    Text(value)
                }
            }
        }
    }
}
@Composable
private fun RateBox(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 10.dp)
        ) {
            Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

private fun buildIperfServerQrPayload(
    host: String,
    port: String,
    transport: IperfTransport,
    ipFamily: IperfIpFamily,
    binary: IperfBinary
): String? {
    val cleanHost = host.trim()
    val cleanPort = port.trim().toIntOrNull()
    if (cleanHost.isBlank() || cleanPort == null || cleanPort !in 1..65535) return null
    return JSONObject()
        .put("type", IPERF_LAB_QR_TYPE)
        .put("host", cleanHost)
        .put("port", cleanPort)
        .put("transport", transport.id)
        .put("ipFamily", ipFamily.id)
        .put("binary", binary.label)
        .put("version", binary.version)
        .toString()
}

private fun parseIperfServerFromQrOrRaw(raw: String): IperfQrServer? {
    val content = raw.trim()
    if (content.isBlank()) return null

    runCatching {
        val json = JSONObject(content)
        if (json.optString("type") == IPERF_LAB_QR_TYPE) {
            val host = json.optString("host").trim()
            val port = json.optInt("port", -1)
            val transport = IperfTransport.fromIdOrNull(json.optString("transport")) ?: IperfTransport.TCP
            val ipFamily = IperfIpFamily.fromIdOrNull(json.optString("ipFamily")) ?: IperfIpFamily.IPV4
            val binary = when (json.optString("binary").trim().lowercase(Locale.getDefault())) {
                "iperf2" -> IperfBinary.IPERF2
                "iperf3" -> IperfBinary.IPERF3
                else -> null
            }
            if (host.isNotBlank() && port in 1..65535) {
                return IperfQrServer(host = host, port = port, transport = transport, ipFamily = ipFamily, binary = binary)
            }
        }
    }

    val bracketEnd = if (content.startsWith("[")) content.indexOf("]:") else -1
    if (bracketEnd > 0) {
        val host = content.substring(1, bracketEnd).trim()
        val port = content.substring(bracketEnd + 2).trim().toIntOrNull()
        if (host.isNotBlank() && port != null && port in 1..65535) {
            return IperfQrServer(
                host = host,
                port = port,
                transport = IperfTransport.TCP,
                ipFamily = IperfIpFamily.IPV6,
                binary = null
            )
        }
    }
    val idx = content.lastIndexOf(':')
    if (idx > 0 && content.indexOf(':') == idx) {
        val host = content.substring(0, idx).trim()
        val port = content.substring(idx + 1).trim().toIntOrNull()
        if (host.isNotBlank() && port != null && port in 1..65535) {
            return IperfQrServer(
                host = host,
                port = port,
                transport = IperfTransport.TCP,
                ipFamily = IperfIpFamily.IPV4,
                binary = null
            )
        }
    }
    return null
}

private suspend fun ensureIperfBinaryExecutable(context: Context, binary: IperfBinary): File = withContext(Dispatchers.IO) {
    val nativeDir = File(context.applicationInfo.nativeLibraryDir.orEmpty())
    val nativeBin = File(nativeDir, binary.nativeSoName)
    if (nativeBin.exists()) {
        nativeBin.setReadable(true, true)
        nativeBin.setExecutable(true, false)
        return@withContext nativeBin
    }
    throw IllegalStateException("未找到可执行二进制: ${nativeBin.absolutePath}，请安装最新 APK。")
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

private fun collectLocalIpv6Candidates(context: Context): List<String> {
    val out = LinkedHashSet<String>()
    NetworkUtils.getLocalGlobalIpv6Address()?.trim()?.substringBefore('%')?.takeIf { it.isNotBlank() }?.let(out::add)
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
                    if (ip.isNotBlank() && !ip.equals("::1", ignoreCase = true)) {
                        out += ip
                    }
                }
            }
        }
    }
    return out.filter { it.isNotBlank() }
}
