package com.yuliwen.filetran

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

private const val NAT34_M_HEART = "heart"
private const val NAT34_M_INIT = "init"
private const val NAT34_M_INFO = "info"
private const val NAT34_M_TOLINK = "tolink"
private const val NAT34_M_PUNCH = "punchHole"
private const val NAT34_M_WANT = "want_to_link"
private const val NAT34_M_MSG = "msg"

private data class NatAlive(
    val ip: String,
    val port: Int,
    val group: String = "default"
)

@Composable
fun Nat34UdpPunchScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var mode by remember { mutableIntStateOf(1) } // 0: server, 1: NAT3, 2: NAT4
    var running by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf("") }

    var serverBindPortInput by remember { mutableStateOf("9001") }
    var signalServerHostInput by remember { mutableStateOf("") }
    var signalServerPortInput by remember { mutableStateOf("9001") }
    var localPortInput by remember { mutableStateOf("6688") }

    var nat3GroupInput by remember { mutableStateOf("L") }
    var nat4SelfGroupInput by remember { mutableStateOf("R") }
    var nat4WantGroupInput by remember { mutableStateOf("L") }

    var nat4TargetIp by remember { mutableStateOf("") }
    var nat4TargetPortInput by remember { mutableStateOf("") }
    var sendTextInput by remember { mutableStateOf("") }

    var runtimeSocket by remember { mutableStateOf<DatagramSocket?>(null) }
    var runtimeExtraSockets by remember { mutableStateOf<List<DatagramSocket>>(emptyList()) }
    var runtimeMainJob by remember { mutableStateOf<Job?>(null) }
    var runtimeHeartJob by remember { mutableStateOf<Job?>(null) }
    var runtimeNat4InitJob by remember { mutableStateOf<Job?>(null) }

    fun appendLog(line: String) {
        scope.launch(Dispatchers.Main) {
            val prefix = if (logs.isBlank()) "" else "\n"
            logs = (logs + prefix + line).takeLast(24_000)
        }
    }

    fun sendJson(socket: DatagramSocket, ip: String, port: Int, obj: JSONObject) {
        val data = obj.toString().toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(data, data.size, InetSocketAddress(ip, port))
        runCatching { socket.send(packet) }
    }

    fun stopRuntime() {
        runtimeNat4InitJob?.cancel(); runtimeNat4InitJob = null
        runtimeHeartJob?.cancel(); runtimeHeartJob = null
        runtimeMainJob?.cancel(); runtimeMainJob = null
        runCatching { runtimeSocket?.close() }
        runtimeSocket = null
        runtimeExtraSockets.forEach { runCatching { it.close() } }
        runtimeExtraSockets = emptyList()
        running = false
        appendLog("[system] stopped")
    }

    fun startServerMode() {
        val bindPort = serverBindPortInput.toIntOrNull()?.coerceIn(1, 65535) ?: 9001
        stopRuntime()
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 800
                bind(InetSocketAddress("0.0.0.0", bindPort))
            }
        }.getOrNull()
        if (socket == null) {
            appendLog("[server] bind failed on $bindPort")
            return
        }
        runtimeSocket = socket
        running = true
        appendLog("[server] listening 0.0.0.0:$bindPort")

        val alive = linkedMapOf<String, NatAlive>()
        val groups = linkedMapOf<String, MutableList<String>>()

        runtimeHeartJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1500)
                alive.values.forEach { item ->
                    sendJson(socket, item.ip, item.port, JSONObject().put("type", NAT34_M_HEART))
                }
            }
        }

        runtimeMainJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val ip = packet.address.hostAddress ?: continue
                    val port = packet.port
                    val text = packet.data.decodeToString(0, packet.length)
                    appendLog("[server][recv] $ip:$port $text")
                    val msg = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    val type = msg.optString("type")
                    if (type == NAT34_M_HEART) continue

                    if (type == NAT34_M_INIT) {
                        val group = msg.optString("group").ifBlank { "default" }
                        val label = "$ip:$port"
                        alive[label] = NatAlive(ip, port, group)
                        val list = groups.getOrPut(group) { mutableListOf() }
                        if (!list.contains(label)) list += label
                        sendJson(
                            socket,
                            ip,
                            port,
                            JSONObject()
                                .put("type", NAT34_M_INFO)
                                .put("data", JSONObject().put("ip", ip).put("port", port).put("group", group))
                        )
                    } else if (type == NAT34_M_WANT) {
                        val data = msg.optJSONObject("data")
                        val wantGroup = data?.optString("group").orEmpty()
                        val firstLabel = groups[wantGroup]?.firstOrNull()
                        if (firstLabel.isNullOrBlank() || !alive.containsKey(firstLabel)) {
                            sendJson(
                                socket,
                                ip,
                                port,
                                JSONObject().put("type", NAT34_M_TOLINK)
                                    .put("data", JSONObject().put("has_group", false))
                            )
                        } else {
                            val target = alive[firstLabel]!!
                            sendJson(
                                socket,
                                target.ip,
                                target.port,
                                JSONObject().put("type", NAT34_M_PUNCH)
                                    .put("data", JSONObject().put("ip", ip).put("port", port))
                            )
                            delay(1000)
                            sendJson(
                                socket,
                                ip,
                                port,
                                JSONObject().put("type", NAT34_M_TOLINK)
                                    .put("data", JSONObject().put("has_group", true).put("ip", target.ip).put("port", target.port))
                            )
                        }
                    } else {
                        sendJson(socket, ip, port, JSONObject().put("type", "err").put("msg", "wrong type"))
                    }
                } catch (_: SocketTimeoutException) {
                } catch (e: Exception) {
                    appendLog("[server][err] ${e.message ?: "unknown"}")
                }
            }
        }
    }

    fun startNat3Mode() {
        val localPort = localPortInput.toIntOrNull()?.coerceIn(1, 65535) ?: 6688
        val sPort = signalServerPortInput.toIntOrNull()?.coerceIn(1, 65535) ?: 9001
        val sHost = signalServerHostInput.trim()
        if (sHost.isBlank()) {
            appendLog("[nat3] server host required")
            return
        }
        stopRuntime()
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 700
                bind(InetSocketAddress("0.0.0.0", localPort))
            }
        }.getOrNull()
        if (socket == null) {
            appendLog("[nat3] bind failed on $localPort")
            return
        }
        runtimeSocket = socket
        running = true
        appendLog("[nat3] listening ${socket.localAddress.hostAddress}:${socket.localPort}")

        val alive = linkedMapOf<String, NatAlive>()
        alive["$sHost:$sPort"] = NatAlive(sHost, sPort, "server")

        sendJson(socket, sHost, sPort, JSONObject().put("type", NAT34_M_INIT).put("group", nat3GroupInput.ifBlank { "L" }))

        runtimeHeartJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1500)
                alive.values.forEach { item ->
                    sendJson(socket, item.ip, item.port, JSONObject().put("type", NAT34_M_HEART))
                }
            }
        }

        runtimeMainJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(4096)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val ip = packet.address.hostAddress ?: continue
                    val port = packet.port
                    val text = packet.data.decodeToString(0, packet.length)
                    appendLog("[nat3][recv] $ip:$port $text")
                    val msg = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    when (msg.optString("type")) {
                        NAT34_M_HEART -> Unit
                        NAT34_M_INIT -> {
                            val key = "$ip:$port"
                            if (alive.containsKey(key)) {
                                sendJson(
                                    socket,
                                    ip,
                                    port,
                                    JSONObject().put("type", NAT34_M_MSG)
                                        .put("data", JSONObject().put("msg", "link success"))
                                )
                            } else {
                                alive[key] = NatAlive(ip, port, "peer")
                            }
                        }
                        NAT34_M_INFO -> {
                            val data = msg.optJSONObject("data")
                            val pubIp = data?.optString("ip").orEmpty()
                            val pubPort = data?.optInt("port", -1) ?: -1
                            appendLog("[nat3] mapped by server: $pubIp:$pubPort")
                        }
                        NAT34_M_TOLINK -> {
                            val data = msg.optJSONObject("data")
                            val toIp = data?.optString("ip").orEmpty()
                            val toPort = data?.optInt("port", -1) ?: -1
                            if (toIp.isNotBlank() && toPort in 1..65535) {
                                sendJson(
                                    socket,
                                    toIp,
                                    toPort,
                                    JSONObject().put("type", NAT34_M_INIT)
                                        .put("data", JSONObject().put("group", nat3GroupInput.ifBlank { "L" }))
                                )
                            }
                        }
                        NAT34_M_PUNCH -> {
                            val data = msg.optJSONObject("data")
                            val targetIp = data?.optString("ip").orEmpty()
                            val targetPort = data?.optInt("port", -1) ?: -1
                            if (targetIp.isBlank() || targetPort !in 1..65535) continue
                            scope.launch(Dispatchers.IO) {
                                for (i in 0 until 65536) {
                                    if (!isActive) break
                                    val p = targetPort + i
                                    if (p !in 1..65535) break
                                    sendJson(
                                        socket,
                                        targetIp,
                                        p,
                                        JSONObject().put("type", NAT34_M_INIT)
                                            .put("data", JSONObject().put("group", nat3GroupInput.ifBlank { "L" }).put("action", "punchHole"))
                                    )
                                }
                            }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                } catch (e: Exception) {
                    appendLog("[nat3][err] ${e.message ?: "unknown"}")
                }
            }
        }
    }

    fun startNat4Mode() {
        val localPort = localPortInput.toIntOrNull()?.coerceIn(1, 65535) ?: 6677
        val sPort = signalServerPortInput.toIntOrNull()?.coerceIn(1, 65535) ?: 9001
        val sHost = signalServerHostInput.trim()
        if (sHost.isBlank()) {
            appendLog("[nat4] server host required")
            return
        }
        stopRuntime()
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 700
                bind(InetSocketAddress("0.0.0.0", localPort))
            }
        }.getOrNull()
        if (socket == null) {
            appendLog("[nat4] bind failed on $localPort")
            return
        }
        runtimeSocket = socket
        running = true
        appendLog("[nat4] listening ${socket.localAddress.hostAddress}:${socket.localPort}")

        val alive = linkedMapOf<String, NatAlive>()
        alive["$sHost:$sPort"] = NatAlive(sHost, sPort, "server")

        sendJson(socket, sHost, sPort, JSONObject().put("type", NAT34_M_INIT).put("group", nat4SelfGroupInput.ifBlank { "R" }))

        runtimeHeartJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1500)
                alive.values.forEach { item ->
                    sendJson(socket, item.ip, item.port, JSONObject().put("type", NAT34_M_HEART))
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            delay(3000)
            if (running) {
                sendJson(
                    socket,
                    sHost,
                    sPort,
                    JSONObject().put("type", NAT34_M_WANT)
                        .put("data", JSONObject().put("group", nat4WantGroupInput.ifBlank { "L" }))
                )
            }
        }

        runtimeMainJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(4096)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val ip = packet.address.hostAddress ?: continue
                    val port = packet.port
                    val text = packet.data.decodeToString(0, packet.length)
                    appendLog("[nat4][recv] $ip:$port $text")
                    val msg = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    when (msg.optString("type")) {
                        NAT34_M_HEART -> Unit
                        NAT34_M_INIT -> {
                            val data = msg.optJSONObject("data")
                            val rIp = data?.optString("ip").orEmpty()
                            val rPort = data?.optInt("port", -1) ?: -1
                            if (rIp.isNotBlank() && rPort in 1..65535) {
                                nat4TargetIp = rIp
                                nat4TargetPortInput = rPort.toString()
                            }
                        }
                        NAT34_M_TOLINK -> {
                            val data = msg.optJSONObject("data")
                            val hasGroup = data?.optBoolean("has_group", false) ?: false
                            if (!hasGroup) {
                                appendLog("[nat4] no target group found")
                                continue
                            }
                            val tIp = data?.optString("ip").orEmpty()
                            val tPort = data?.optInt("port", -1) ?: -1
                            if (tIp.isBlank() || tPort !in 1..65535) continue
                            nat4TargetIp = tIp
                            nat4TargetPortInput = tPort.toString()

                            runtimeNat4InitJob?.cancel()
                            runtimeNat4InitJob = scope.launch(Dispatchers.IO) {
                                delay(1500)
                                while (isActive && running) {
                                    sendJson(
                                        socket,
                                        tIp,
                                        tPort,
                                        JSONObject().put("type", NAT34_M_INIT)
                                            .put("data", JSONObject().put("group", nat4SelfGroupInput.ifBlank { "R" }))
                                    )
                                    delay(500)
                                }
                            }
                        }
                        NAT34_M_MSG -> {
                            val data = msg.optJSONObject("data")
                            val m = data?.optString("msg").orEmpty()
                            if (m == "link success") {
                                appendLog("[nat4] link success with $ip:$port")
                                alive["$ip:$port"] = NatAlive(ip, port, "peer")
                                runtimeNat4InitJob?.cancel()
                                runtimeNat4InitJob = null
                            }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                } catch (e: Exception) {
                    appendLog("[nat4][err] ${e.message ?: "unknown"}")
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = {
                if (running) stopRuntime()
                onBack()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("返回传输实验室")
        }

        Text("NAT3-NAT4打洞验证", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "按 li1055107552/p2p 的 server + NAT3 + NAT4 UDP 脚本流程迁移。",
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
                Text("模式", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { mode = 0 },
                        modifier = Modifier.weight(1f),
                        enabled = !running && mode != 0
                    ) { Text("协调服务器") }
                    OutlinedButton(
                        onClick = { mode = 1; if (localPortInput == "6677") localPortInput = "6688" },
                        modifier = Modifier.weight(1f),
                        enabled = !running && mode != 1
                    ) { Text("NAT3 客户端") }
                    OutlinedButton(
                        onClick = { mode = 2; if (localPortInput == "6688") localPortInput = "6677" },
                        modifier = Modifier.weight(1f),
                        enabled = !running && mode != 2
                    ) { Text("NAT4 客户端") }
                }

                if (mode == 0) {
                    OutlinedTextField(
                        value = serverBindPortInput,
                        onValueChange = { serverBindPortInput = it.filter(Char::isDigit) },
                        label = { Text("服务器绑定端口") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !running
                    )
                } else {
                    OutlinedTextField(
                        value = signalServerHostInput,
                        onValueChange = { signalServerHostInput = it.trim() },
                        label = { Text("协调服务器 IP/域名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !running
                    )
                    OutlinedTextField(
                        value = signalServerPortInput,
                        onValueChange = { signalServerPortInput = it.filter(Char::isDigit) },
                        label = { Text("协调服务器端口") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !running
                    )
                    OutlinedTextField(
                        value = localPortInput,
                        onValueChange = { localPortInput = it.filter(Char::isDigit) },
                        label = { Text("本地 UDP 端口") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !running
                    )
                    if (mode == 1) {
                        OutlinedTextField(
                            value = nat3GroupInput,
                            onValueChange = { nat3GroupInput = it.trim().ifBlank { "L" } },
                            label = { Text("NAT3 group") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !running
                        )
                    }
                    if (mode == 2) {
                        OutlinedTextField(
                            value = nat4SelfGroupInput,
                            onValueChange = { nat4SelfGroupInput = it.trim().ifBlank { "R" } },
                            label = { Text("NAT4 self group") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !running
                        )
                        OutlinedTextField(
                            value = nat4WantGroupInput,
                            onValueChange = { nat4WantGroupInput = it.trim().ifBlank { "L" } },
                            label = { Text("want_to_link group") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !running
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            logs = ""
                            when (mode) {
                                0 -> startServerMode()
                                1 -> startNat3Mode()
                                else -> startNat4Mode()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !running
                    ) { Text("启动") }
                    OutlinedButton(
                        onClick = { stopRuntime() },
                        modifier = Modifier.weight(1f),
                        enabled = running
                    ) { Text("停止") }
                }
            }
        }

        if (mode == 2) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("NAT4 消息发送", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = nat4TargetIp,
                        onValueChange = { nat4TargetIp = it.trim() },
                        label = { Text("目标 IP") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = nat4TargetPortInput,
                        onValueChange = { nat4TargetPortInput = it.filter(Char::isDigit) },
                        label = { Text("目标端口") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = sendTextInput,
                        onValueChange = { sendTextInput = it },
                        label = { Text("发送文本") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val socket = runtimeSocket
                            val port = nat4TargetPortInput.toIntOrNull()
                            if (socket == null || !running || nat4TargetIp.isBlank() || port == null || port !in 1..65535) {
                                appendLog("[nat4] send target invalid")
                                return@Button
                            }
                            val text = sendTextInput.ifBlank { "hello" }
                            val data = text.toByteArray(Charsets.UTF_8)
                            val packet = DatagramPacket(data, data.size, InetSocketAddress(nat4TargetIp, port))
                            runCatching { socket.send(packet) }
                            appendLog("[nat4][send] $nat4TargetIp:$port $text")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = running
                    ) { Text("发送") }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("日志", fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (logs.isBlank()) "暂无日志" else logs,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}
