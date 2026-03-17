package com.yuliwen.filetran

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

// ============================================================
// 持久化 key
// ============================================================
private const val PREFS_MESH = "mesh_net_prefs"
private const val KEY_SERVER_CFG = "server_cfg_json"
private const val KEY_CLIENT_CFG = "client_cfg_json"

private fun saveMeshCfg(ctx: Context, key: String, cfg: MeshSessionConfig) {
    ctx.getSharedPreferences(PREFS_MESH, Context.MODE_PRIVATE)
        .edit().putString(key, meshConfigToJson(cfg)).apply()
}

private fun loadMeshCfg(ctx: Context, key: String): MeshSessionConfig? =
    ctx.getSharedPreferences(PREFS_MESH, Context.MODE_PRIVATE)
        .getString(key, null)?.let { runCatching { parseMeshConfig(it) }.getOrNull() }

// ============================================================
// 页面路由
// ============================================================
private enum class MeshPage { HOME, SERVER_SETUP, CLIENT_SETUP }

// ============================================================
// 顶层入口
// ============================================================
@Composable
fun MeshNetScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var page       by rememberSaveable { mutableStateOf(MeshPage.HOME) }
    var vpnRunning by remember { mutableStateOf(MeshNetService.isRunning) }
    var statusMsg  by remember { mutableStateOf("") }
    var localVpnIp by remember { mutableStateOf(MeshNetService.localVpnIp) }
    var peerVpnIp  by remember { mutableStateOf(MeshNetService.peerVpnIp) }
    var isReconnecting by remember { mutableStateOf(false) }
    var pendingCfg by remember { mutableStateOf<MeshSessionConfig?>(null) }

    val vpnPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            pendingCfg?.let { startMeshVpn(context, it) }
            statusMsg = "正在连接…"
        } else { statusMsg = "VPN 权限被拒绝" }
        pendingCfg = null
    }

    fun launchVpn(cfg: MeshSessionConfig) {
        pendingCfg = cfg
        val prep = VpnService.prepare(context)
        if (prep != null) vpnPermLauncher.launch(prep)
        else { startMeshVpn(context, cfg); statusMsg = "正在连接…" }
    }

    fun stopVpn() { stopMeshVpn(context); statusMsg = "正在断开…" }

    DisposableEffect(Unit) {
        val recv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                vpnRunning     = i.getBooleanExtra(MeshNetService.EXTRA_RUNNING, false)
                statusMsg      = i.getStringExtra(MeshNetService.EXTRA_MESSAGE) ?: ""
                localVpnIp     = i.getStringExtra(MeshNetService.EXTRA_LOCAL_VPN_IP) ?: ""
                peerVpnIp      = i.getStringExtra(MeshNetService.EXTRA_PEER_VPN_IP)  ?: ""
                isReconnecting = i.getBooleanExtra(MeshNetService.EXTRA_RECONNECTING, false)
            }
        }
        val filter = IntentFilter(MeshNetService.BROADCAST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(recv, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(recv, filter)
        onDispose { context.unregisterReceiver(recv) }
    }

    when (page) {
        MeshPage.HOME -> MeshHomeScreen(
            vpnRunning = vpnRunning, statusMsg = statusMsg,
            localVpnIp = localVpnIp, peerVpnIp = peerVpnIp,
            isReconnecting = isReconnecting,
            onBack = onBack, onSetupServer = { page = MeshPage.SERVER_SETUP },
            onSetupClient = { page = MeshPage.CLIENT_SETUP }, onStop = ::stopVpn
        )
        MeshPage.SERVER_SETUP -> MeshServerSetupScreen(
            onBack  = { page = MeshPage.HOME },
            onStart = { cfg -> saveMeshCfg(context, KEY_SERVER_CFG, cfg); launchVpn(cfg); page = MeshPage.HOME }
        )
        MeshPage.CLIENT_SETUP -> MeshClientSetupScreen(
            onBack  = { page = MeshPage.HOME },
            onStart = { cfg -> saveMeshCfg(context, KEY_CLIENT_CFG, cfg); launchVpn(cfg); page = MeshPage.HOME }
        )
    }
}

// ============================================================
// HOME
// ============================================================
@Composable
private fun MeshHomeScreen(
    vpnRunning: Boolean, statusMsg: String,
    localVpnIp: String, peerVpnIp: String,
    isReconnecting: Boolean,
    onBack: () -> Unit, onSetupServer: () -> Unit,
    onSetupClient: () -> Unit, onStop: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
            Spacer(Modifier.width(6.dp)); Text("返回传输实验室")
        }
        Text("异地组网", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "自研加密隧道 · AES-256-GCM · ECDH · 内网互访\n流量全程端对端加密，支持 Root 内网路由。",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp
        )
        MeshStatusCard(
            running = vpnRunning, statusMsg = statusMsg,
            localVpnIp = localVpnIp, peerVpnIp = peerVpnIp,
            isReconnecting = isReconnecting,
            onStop = onStop, onCopy = { clipboard.setText(AnnotatedString(localVpnIp)) }
        )
        if (!vpnRunning && !isReconnecting) {
            MeshFeatureCard()
            Spacer(Modifier.height(4.dp))
            Text("选择角色开始连接", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MeshRoleCard(
                    icon = Icons.Default.Router, title = "作为服务端",
                    desc = "本机监听端口\n生成二维码供对方扫码\n需有公网IP或端口转发",
                    color = MaterialTheme.colorScheme.primaryContainer,
                    onColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = onSetupServer, modifier = Modifier.weight(1f)
                )
                MeshRoleCard(
                    icon = Icons.Default.PhoneAndroid, title = "作为客户端",
                    desc = "扫码或手动填写\n服务端地址和密码\n可在 NAT 后方",
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    onColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onSetupClient, modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ============================================================
// 客户端配置页（扫码/手动填写 + 保存）
// ============================================================
@Composable
private fun MeshClientSetupScreen(onBack: () -> Unit, onStart: (MeshSessionConfig) -> Unit) {
    val context   = LocalContext.current
    val saved     = remember { loadMeshCfg(context, KEY_CLIENT_CFG) }

    var passcode       by rememberSaveable { mutableStateOf(saved?.passcode    ?: "") }
    var serverHost     by rememberSaveable { mutableStateOf(saved?.serverHost  ?: "") }
    var serverPort     by rememberSaveable { mutableStateOf(saved?.serverPort?.toString() ?: "7890") }
    var clientVpnIp    by rememberSaveable { mutableStateOf(saved?.clientVpnIp ?: "192.168.100.2") }
    var subnetMask     by rememberSaveable { mutableStateOf(saved?.subnetMask  ?: "255.255.255.0") }
    var mtu            by rememberSaveable { mutableStateOf(saved?.mtu?.toString() ?: "1400") }
    var keepalive      by rememberSaveable { mutableStateOf(saved?.keepaliveIntervalSec?.toString() ?: "20") }
    var serverLanCidrs by rememberSaveable { mutableStateOf(saved?.serverLanCidrs ?: "") }
    var clientLanCidrs by rememberSaveable { mutableStateOf(saved?.clientLanCidrs ?: "") }
    var showScanner    by remember { mutableStateOf(false) }
    var savedMsg       by remember { mutableStateOf("") }
    var portMappings   by rememberSaveable { mutableStateOf(saved?.portMappings ?: "") }

    // 扫码解析
    if (showScanner) {
        QRCodeScanner(
            onQRCodeScanned = { raw ->
                showScanner = false
                runCatching {
                    val cfg = parseMeshConfig(raw)
                    // 扫码填入所有字段（可继续修改）
                    passcode       = cfg.passcode
                    serverHost     = cfg.serverHost.ifBlank { cfg.publicIpv4.ifBlank { cfg.publicIpv6 } }
                    serverPort     = cfg.serverPort.toString()
                    clientVpnIp    = cfg.clientVpnIp
                    subnetMask     = cfg.subnetMask
                    mtu            = cfg.mtu.toString()
                    keepalive      = cfg.keepaliveIntervalSec.toString()
                    serverLanCidrs = cfg.serverLanCidrs
                    clientLanCidrs = cfg.clientLanCidrs
                    savedMsg = "✅ 扫码成功，请确认后连接"
                }.onFailure { savedMsg = "❌ 二维码解析失败: ${it.message}" }
            },
            onDismiss = { showScanner = false }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("返回")
        }
        Text("客户端配置", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("扫描服务端生成的二维码自动填写，或手动输入后保存。",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // 扫码按钮
        Button(onClick = { showScanner = true }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary)) {
            Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp))
            Text("扫码自动填写")
        }
        if (savedMsg.isNotBlank())
            Text(savedMsg, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)

        MeshSetupCard {
            MeshFieldLabel("服务端地址", "服务端的公网 IP 或域名") {
                OutlinedTextField(value = serverHost, onValueChange = { serverHost = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("如 1.2.3.4 或 myhome.example.com") })
            }
            MeshFieldLabel("服务端端口", "") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = serverPort,
                        onValueChange = { serverPort = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("7890","8388","9000").forEach { p ->
                        OutlinedButton(onClick = { serverPort = p },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(p, fontSize = 11.sp) }
                    }
                }
            }
            MeshFieldLabel("连接密码", "与服务端一致") {
                OutlinedTextField(value = passcode, onValueChange = { passcode = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    placeholder = { Text("粘贴或扫码自动填写") })
            }
            MeshFieldLabel("本机 VPN IP", "服务端握手时分配，可保持默认") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = clientVpnIp, onValueChange = { clientVpnIp = it.trim() },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("192.168.100.2","10.10.0.2").forEach { ip ->
                        OutlinedButton(onClick = { clientVpnIp = ip },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(ip, fontSize = 10.sp) }
                    }
                }
            }
        }

        // 内网互访
        MeshSetupCard {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.AccountTree, null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary)
                Text("内网互访（需 Root）", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            Text("扫码会自动填入，也可手动修改。连接后自动配置路由和 iptables。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MeshFieldLabel("服务端内网段", "连接后可访问的服务端局域网") {
                OutlinedTextField(value = serverLanCidrs, onValueChange = { serverLanCidrs = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("如 192.168.1.0/24") })
            }
            MeshFieldLabel("客户端内网段", "本机局域网，服务端可访问此段") {
                OutlinedTextField(value = clientLanCidrs, onValueChange = { clientLanCidrs = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("如 192.168.2.0/24，留空则不配置") })
            }
        }

        // 高级
        MeshSetupCard {
            MeshFieldLabel("MTU", "默认 1400") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = mtu, onValueChange = { mtu = it.filter(Char::isDigit).take(4) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("1400","1360","1280").forEach { m ->
                        OutlinedButton(onClick = { mtu = m },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(m, fontSize = 11.sp) }
                    }
                }
            }
            MeshFieldLabel("心跳间隔（秒）", "默认 20") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = keepalive,
                        onValueChange = { keepalive = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("20","30","60").forEach { k ->
                        OutlinedButton(onClick = { keepalive = k },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(k, fontSize = 11.sp) }
                    }
                }
            }
        }

        // ---- 端口映射 ----
        MeshSetupCard {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Text("端口映射", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text("将对端内网端口映射到本机，格式：本机IP:本机端口:目标IP:目标端口，多条逗号分隔。\n例：192.168.100.2:5000:10.62.48.99:8989",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
            OutlinedTextField(
                value = portMappings, onValueChange = { portMappings = it.trim() },
                modifier = Modifier.fillMaxWidth(), singleLine = false, minLines = 2,
                placeholder = { Text("如 192.168.100.2:5000:10.62.48.99:8989", fontSize = 11.sp) }
            )
        }

        Button(
            onClick = {
                val cfg = MeshSessionConfig(
                    role           = MeshRole.CLIENT,
                    passcode       = passcode.trim(),
                    serverHost     = serverHost.trim(),
                    serverPort     = serverPort.toIntOrNull() ?: 7890,
                    clientVpnIp    = clientVpnIp.trim(),
                    subnetMask     = subnetMask.trim(),
                    mtu            = mtu.toIntOrNull() ?: 1400,
                    keepaliveIntervalSec = keepalive.toIntOrNull() ?: 20,
                    serverLanCidrs = serverLanCidrs.trim(),
                    clientLanCidrs = clientLanCidrs.trim(),
                    portMappings   = portMappings.trim()
                )
                onStart(cfg)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = serverHost.isNotBlank() && passcode.isNotBlank()
                    && (serverPort.toIntOrNull() ?: 0) in 1..65535
        ) {
            Icon(Icons.Default.Link, null); Spacer(Modifier.width(8.dp))
            Text("保存并连接到服务端")
        }
    }
}

// ============================================================
// 通用 UI 组件
// ============================================================
@Composable
private fun MeshSetupCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun MeshFieldLabel(label: String, hint: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (hint.isNotBlank()) Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

// ============================================================
// VPN 启停
// ============================================================
fun startMeshVpn(context: Context, cfg: MeshSessionConfig) {
    val i = Intent(context, MeshNetService::class.java).apply {
        action = MeshNetService.ACTION_START
        putExtra(MeshNetService.EXTRA_CONFIG, meshConfigToJson(cfg))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
    else context.startService(i)
}

fun stopMeshVpn(context: Context) {
    context.startService(Intent(context, MeshNetService::class.java).apply {
        action = MeshNetService.ACTION_STOP
    })
}

// ============================================================
// 工具函数
// ============================================================
private fun meshCollectLocalIpv4(): List<String> {
    val out = mutableListOf<String>()
    runCatching {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
        while (ifaces.hasMoreElements()) {
            val itf = ifaces.nextElement()
            if (!itf.isUp || itf.isLoopback) continue
            val addrs = itf.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a is Inet4Address && !a.isLoopbackAddress) out += a.hostAddress.orEmpty()
            }
        }
    }
    return out
}

/** 从 ipip.net 获取公网 IP，返回纯 IP 字符串，失败返回 null */
fun meshFetchPublicIp(url: String): String? {
    return runCatching {
        val conn = URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout    = 5000
        conn.setRequestProperty("User-Agent", "curl/7.68.0")
        val body = conn.inputStream.bufferedReader().readText().trim()
        conn.disconnect()
        // ipip.net 返回纯 IP
        body.takeIf { it.isNotBlank() && it.length < 50 }
    }.getOrNull()
}

// ============================================================
// 状态卡片
// ============================================================
@Composable
private fun MeshStatusCard(
    running: Boolean, statusMsg: String,
    localVpnIp: String, peerVpnIp: String,
    isReconnecting: Boolean,
    onStop: () -> Unit, onCopy: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.45f, targetValue = 1f, label = "dot",
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse)
    )
    val bgColor by animateColorAsState(
        when {
            running         -> MaterialTheme.colorScheme.primaryContainer
            isReconnecting  -> MaterialTheme.colorScheme.secondaryContainer
            else            -> MaterialTheme.colorScheme.surfaceVariant
        }, label = "bg"
    )
    val textColor = when {
        running        -> MaterialTheme.colorScheme.onPrimaryContainer
        isReconnecting -> MaterialTheme.colorScheme.onSecondaryContainer
        else           -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(18.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(
                    when {
                        running        -> MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                        isReconnecting -> MaterialTheme.colorScheme.secondary.copy(alpha = alpha)
                        else           -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    }
                ))
                Text(
                    when {
                        running        -> "隧道已建立"
                        isReconnecting -> "重连中…"
                        else           -> "未连接"
                    },
                    fontWeight = FontWeight.Bold, fontSize = 16.sp, color = textColor
                )
                Spacer(Modifier.weight(1f))
                when {
                    running        -> Icon(Icons.Default.Lock, null, tint = textColor, modifier = Modifier.size(18.dp))
                    isReconnecting -> Icon(Icons.Default.Autorenew, null, tint = textColor, modifier = Modifier.size(18.dp))
                    else           -> {}
                }
            }
            if (running) {
                HorizontalDivider(color = textColor.copy(0.15f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MeshIpChip("本机 VPN IP", localVpnIp, Modifier.weight(1f))
                    Icon(Icons.Default.SyncAlt, null,
                        modifier = Modifier.size(20.dp).align(Alignment.CenterVertically),
                        tint = textColor)
                    MeshIpChip("对端 VPN IP", peerVpnIp, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor)) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp)); Text("复制本机 IP")
                    }
                    Button(onClick = onStop, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.LinkOff, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp)); Text("断开隧道")
                    }
                }
            }
            if (isReconnecting && !running) {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp)); Text("停止重连")
                }
            }
            if (statusMsg.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(statusMsg, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun MeshIpChip(label: String, ip: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f))
            Text(ip.ifBlank { "—" }, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun MeshRoleCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String, desc: String, color: Color, onColor: Color,
    onClick: () -> Unit, modifier: Modifier = Modifier
) {
    Card(onClick = onClick, modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, modifier = Modifier.size(36.dp), tint = onColor)
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = onColor)
            Text(desc, fontSize = 11.sp, textAlign = TextAlign.Center,
                color = onColor.copy(alpha = 0.8f), lineHeight = 16.sp)
        }
    }
}

@Composable
private fun MeshFeatureCard() {
    val items = listOf(
        Icons.Default.Lock         to "AES-256-GCM 加密，全程端对端",
        Icons.Default.Key          to "ECDH P-256 密钥交换，每次会话唯一",
        Icons.Default.Password     to "密码验证，防止未授权接入",
        Icons.Default.QrCode       to "二维码分享，扫码即可接入",
        Icons.Default.AccountTree   to "Root 内网路由，访问对端局域网",
        Icons.Default.Speed         to "心跳保活，稳定长连接"
    )
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Shield, null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(20.dp))
                Text("功能特点", fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            items.forEach { (icon, text) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(0.8f))
                    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
}

// ============================================================
// 服务端配置页（含公网IP获取 + 二维码）
// ============================================================
@Composable
private fun MeshServerSetupScreen(onBack: () -> Unit, onStart: (MeshSessionConfig) -> Unit) {
    val context   = LocalContext.current
    val scope     = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val localIpList = remember { meshCollectLocalIpv4() }

    // 从 prefs 恢复上次配置
    val saved = remember { loadMeshCfg(context, KEY_SERVER_CFG) }
    var passcode      by rememberSaveable { mutableStateOf(saved?.passcode    ?: MeshCrypto.generatePasscode()) }
    var listenPort    by rememberSaveable { mutableStateOf(saved?.listenPort?.toString() ?: "7890") }
    var serverVpnIp   by rememberSaveable { mutableStateOf(saved?.serverVpnIp ?: "192.168.100.1") }
    var clientVpnIp   by rememberSaveable { mutableStateOf(saved?.clientVpnIp ?: "192.168.100.2") }
    var subnetMask    by rememberSaveable { mutableStateOf(saved?.subnetMask  ?: "255.255.255.0") }
    var mtu           by rememberSaveable { mutableStateOf(saved?.mtu?.toString() ?: "1400") }
    var keepalive     by rememberSaveable { mutableStateOf(saved?.keepaliveIntervalSec?.toString() ?: "20") }
    var serverLanCidrs by rememberSaveable { mutableStateOf(saved?.serverLanCidrs ?: "") }
    var clientLanCidrs by rememberSaveable { mutableStateOf(saved?.clientLanCidrs ?: "") }
    var publicIpv4    by rememberSaveable { mutableStateOf(saved?.publicIpv4 ?: "") }
    var publicIpv6    by rememberSaveable { mutableStateOf(saved?.publicIpv6 ?: "") }
    var portMappings  by rememberSaveable { mutableStateOf(saved?.portMappings ?: "") }
    var fetchingIp    by remember { mutableStateOf(false) }
    var fetchIpMsg    by remember { mutableStateOf("") }
    var showQr        by remember { mutableStateOf(false) }
    // 0=IPv4  1=IPv6  2=局域网
    var qrIpVersion   by rememberSaveable { mutableStateOf(0) }

    // 构建要编码进二维码的 JSON（客户端配置，只含连接所需字段）
    fun buildQrJson(): String {
        val host = when (qrIpVersion) {
            1    -> publicIpv6.trim().ifBlank { publicIpv4.trim().ifBlank { localIpList.firstOrNull() ?: "" } }
            2    -> localIpList.firstOrNull() ?: publicIpv4.trim()
            else -> publicIpv4.trim().ifBlank { publicIpv6.trim().ifBlank { localIpList.firstOrNull() ?: "" } }
        }
        val cfg = MeshSessionConfig(
            role           = MeshRole.CLIENT,
            passcode       = passcode.trim(),
            serverHost     = host,
            serverPort     = listenPort.toIntOrNull() ?: 7890,
            clientVpnIp    = clientVpnIp.trim(),
            subnetMask     = subnetMask.trim(),
            mtu            = mtu.toIntOrNull() ?: 1400,
            keepaliveIntervalSec = keepalive.toIntOrNull() ?: 20,
            serverLanCidrs = serverLanCidrs.trim(),
            clientLanCidrs = clientLanCidrs.trim(),
            publicIpv4     = publicIpv4.trim(),
            publicIpv6     = publicIpv6.trim()
        )
        return meshConfigToJson(cfg)
    }

    val qrBitmap = remember(showQr, qrIpVersion, publicIpv4, publicIpv6, passcode, listenPort, serverLanCidrs, clientLanCidrs) {
        if (showQr) QRCodeGenerator.generateQRCode(buildQrJson(), 900) else null
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("返回")
        }
        Text("服务端配置", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("服务端监听端口，生成二维码供客户端扫码接入。",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // ---- 公网 IP 获取 ----
        MeshSetupCard {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Public, null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Text("公网 IP（自动获取）", fontWeight = FontWeight.SemiBold)
            }
            Text("从 ipip.net 获取本机公网 IP，将内嵌到二维码供客户端自动填写。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = publicIpv4, onValueChange = { publicIpv4 = it.trim() },
                    modifier = Modifier.weight(1f), singleLine = true, label = { Text("IPv4") },
                    placeholder = { Text("自动获取或手动填写") })
                OutlinedTextField(value = publicIpv6, onValueChange = { publicIpv6 = it.trim() },
                    modifier = Modifier.weight(1f), singleLine = true, label = { Text("IPv6") },
                    placeholder = { Text("可选") })
            }
            Button(
                onClick = {
                    fetchingIp = true; fetchIpMsg = "正在获取…"
                    scope.launch {
                        val v4 = withContext(Dispatchers.IO) { meshFetchPublicIp("https://v4.ipip.net/") }
                        val v6 = withContext(Dispatchers.IO) { meshFetchPublicIp("https://v6.ipip.net/") }
                        if (v4 != null) publicIpv4 = v4
                        if (v6 != null) publicIpv6 = v6
                        fetchIpMsg = when {
                            v4 != null && v6 != null -> "✅ IPv4: $v4  IPv6: $v6"
                            v4 != null -> "✅ IPv4: $v4（无 IPv6）"
                            v6 != null -> "✅ IPv6: $v6（无 IPv4）"
                            else -> "❌ 获取失败，请手动填写"
                        }
                        fetchingIp = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !fetchingIp
            ) {
                if (fetchingIp) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (fetchingIp) "获取中…" else "自动获取公网 IP")
            }
            if (fetchIpMsg.isNotBlank())
                Text(fetchIpMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (localIpList.isNotEmpty()) {
                Text("本机局域网 IP（仅局域网测试）：", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    localIpList.take(3).forEach { ip ->
                        OutlinedButton(onClick = { publicIpv4 = ip },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(ip, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // ---- 基础配置 ----
        MeshSetupCard {
            MeshFieldLabel("连接密码", "两端必须一致，用于身份验证") {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = passcode, onValueChange = { passcode = it },
                        modifier = Modifier.weight(1f), singleLine = true,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                    IconButton(onClick = { passcode = MeshCrypto.generatePasscode() }) {
                        Icon(Icons.Default.Refresh, "重新生成") }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(passcode)) }) {
                        Icon(Icons.Default.ContentCopy, "复制") }
                }
            }
            MeshFieldLabel("监听端口", "客户端将连接此 TCP 端口") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = listenPort,
                        onValueChange = { listenPort = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("7890", "8388", "9000").forEach { p ->
                        OutlinedButton(onClick = { listenPort = p },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(p, fontSize = 11.sp) }
                    }
                }
            }
            MeshFieldLabel("服务端 VPN IP", "服务端在 VPN 隧道中的地址") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = serverVpnIp, onValueChange = { serverVpnIp = it.trim() },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("192.168.100.1", "10.10.0.1").forEach { ip ->
                        OutlinedButton(onClick = { serverVpnIp = ip },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(ip, fontSize = 10.sp) }
                    }
                }
            }
            MeshFieldLabel("客户端 VPN IP", "分配给对端的地址") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = clientVpnIp, onValueChange = { clientVpnIp = it.trim() },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("192.168.100.2", "10.10.0.2").forEach { ip ->
                        OutlinedButton(onClick = { clientVpnIp = ip },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(ip, fontSize = 10.sp) }
                    }
                }
            }
            MeshFieldLabel("子网掩码", "") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = subnetMask, onValueChange = { subnetMask = it.trim() },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("255.255.255.0", "255.255.0.0").forEach { m ->
                        OutlinedButton(onClick = { subnetMask = m },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(m, fontSize = 10.sp) }
                    }
                }
            }
        }

        // ---- 内网互访（Root）----
        MeshSetupCard {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.AccountTree, null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary)
                Text("内网互访（需 Root）", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            Text("填写后连接时自动用 su 配置路由和 iptables，实现双向内网访问。留空则跳过。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MeshFieldLabel("服务端内网段", "客户端连接后可访问此段，如 192.168.1.0/24，多段逗号分隔") {
                OutlinedTextField(value = serverLanCidrs, onValueChange = { serverLanCidrs = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("如 192.168.1.0/24,10.0.0.0/8") })
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("192.168.1.0/24", "192.168.0.0/24", "10.0.0.0/8").forEach { cidr ->
                        OutlinedButton(onClick = {
                            serverLanCidrs = if (serverLanCidrs.isBlank()) cidr
                            else "$serverLanCidrs,$cidr"
                        }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(cidr, fontSize = 10.sp) }
                    }
                }
            }
            MeshFieldLabel("客户端内网段", "服务端连接后可访问此段（客户端所在局域网）") {
                OutlinedTextField(value = clientLanCidrs, onValueChange = { clientLanCidrs = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("如 192.168.2.0/24，留空则不配置") })
            }
        }

        // ---- 高级 ----
        MeshSetupCard {
            MeshFieldLabel("MTU", "默认 1400") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = mtu,
                        onValueChange = { mtu = it.filter(Char::isDigit).take(4) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("1400","1360","1280").forEach { m ->
                        OutlinedButton(onClick = { mtu = m },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(m, fontSize = 11.sp) }
                    }
                }
            }
            MeshFieldLabel("心跳间隔（秒）", "默认 20") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = keepalive,
                        onValueChange = { keepalive = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("20","30","60").forEach { k ->
                        OutlinedButton(onClick = { keepalive = k },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(k, fontSize = 11.sp) }
                    }
                }
            }
        }

        // ---- 端口映射 ----
        MeshSetupCard {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Text("端口映射", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text("将对端内网端口映射到本机，格式：本机IP:本机端口:目标IP:目标端口，多条逗号分隔。\n例：192.168.100.1:4000:10.62.48.99:8989",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
            OutlinedTextField(
                value = portMappings, onValueChange = { portMappings = it.trim() },
                modifier = Modifier.fillMaxWidth(), singleLine = false, minLines = 2,
                placeholder = { Text("如 192.168.100.1:4000:10.62.48.99:8989", fontSize = 11.sp) }
            )
        }

        // ---- 二维码展示 ----
        Button(onClick = { showQr = !showQr }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary)) {
            Icon(Icons.Default.QrCode, null); Spacer(Modifier.width(8.dp))
            Text(if (showQr) "隐藏二维码" else "生成客户端二维码")
        }
        if (showQr) {
            // IP 版本选择
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("二维码内嵌连接地址", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val opts = listOf(
                            0 to "IPv4 公网" + if (publicIpv4.isNotBlank()) "\n${publicIpv4}" else "\n(未获取)",
                            1 to "IPv6 公网" + if (publicIpv6.isNotBlank()) "\n${publicIpv6}" else "\n(未获取)",
                            2 to "局域网 IP" + if (localIpList.isNotEmpty()) "\n${localIpList.first()}" else "\n(无)"
                        )
                        opts.forEach { (idx, label) ->
                            val selected = qrIpVersion == idx
                            if (selected) {
                                Button(
                                    onClick = { qrIpVersion = idx },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                                ) {
                                    Text(label, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 15.sp)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { qrIpVersion = idx },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                                ) {
                                    Text(label, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 15.sp)
                                }
                            }
                        }
                    }
                    val chosenHost = when (qrIpVersion) {
                        1    -> publicIpv6.ifBlank { "(IPv6 未获取)" }
                        2    -> localIpList.firstOrNull() ?: "(无局域网IP)"
                        else -> publicIpv4.ifBlank { "(IPv4 未获取)" }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "将连接到：$chosenHost:$listenPort",
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // 二维码图片
            if (qrBitmap != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(14.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("扫码接入", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "客户端扫描后自动填写所有参数，可修改后连接。",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "mesh_qr",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                        )
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(buildQrJson())) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp)); Text("复制配置 JSON")
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                val cfg = MeshSessionConfig(
                    role           = MeshRole.SERVER,
                    passcode       = passcode.trim(),
                    listenPort     = listenPort.toIntOrNull() ?: 7890,
                    serverVpnIp    = serverVpnIp.trim(),
                    clientVpnIp    = clientVpnIp.trim(),
                    subnetMask     = subnetMask.trim(),
                    mtu            = mtu.toIntOrNull() ?: 1400,
                    keepaliveIntervalSec = keepalive.toIntOrNull() ?: 20,
                    serverLanCidrs = serverLanCidrs.trim(),
                    clientLanCidrs = clientLanCidrs.trim(),
                    publicIpv4     = publicIpv4.trim(),
                    publicIpv6     = publicIpv6.trim(),
                    portMappings   = portMappings.trim()
                )
                onStart(cfg)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = passcode.isNotBlank() && (listenPort.toIntOrNull() ?: 0) in 1..65535
        ) {
            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp))
            Text("保存并启动服务端")
        }
    }
}
