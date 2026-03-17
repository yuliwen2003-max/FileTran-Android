package com.yuliwen.filetran

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
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
import java.net.Inet6Address
import java.net.NetworkInterface

// ============================================================
// SharedPreferences
// ============================================================
private const val PREFS_UDP_MESH = "udp_mesh_prefs"
private const val KEY_UDP_SERVER  = "udp_server_cfg"
private const val KEY_UDP_CLIENT  = "udp_client_cfg"

private fun saveUdpCfg(ctx: Context, key: String, cfg: UdpMeshConfig) =
    ctx.getSharedPreferences(PREFS_UDP_MESH, Context.MODE_PRIVATE)
        .edit().putString(key, udpMeshConfigToJson(cfg)).apply()

private fun loadUdpCfg(ctx: Context, key: String): UdpMeshConfig? =
    ctx.getSharedPreferences(PREFS_UDP_MESH, Context.MODE_PRIVATE)
        .getString(key, null)?.let { runCatching { parseUdpMeshConfig(it) }.getOrNull() }

// ============================================================
// 页面路由
// ============================================================
private enum class UdpMeshPage { HOME, SERVER_SETUP, CLIENT_SETUP }

// ============================================================
// 顶层入口
// ============================================================
@Composable
fun UdpMeshNetScreen(onBack: () -> Unit) {
    val context      = LocalContext.current
    var page         by rememberSaveable { mutableStateOf(UdpMeshPage.HOME) }
    var vpnRunning    by remember { mutableStateOf(UdpMeshNetService.isRunning) }
    var statusMsg     by remember { mutableStateOf("") }
    var localVpnIp    by remember { mutableStateOf(UdpMeshNetService.localVpnIp) }
    var peerVpnIp     by remember { mutableStateOf(UdpMeshNetService.peerVpnIp) }
    var txBytes       by remember { mutableStateOf(0L) }
    var rxBytes       by remember { mutableStateOf(0L) }
    var txPackets     by remember { mutableStateOf(0L) }
    var rxPackets     by remember { mutableStateOf(0L) }
    var handshakeMs   by remember { mutableStateOf(0L) }
    var reconnecting  by remember { mutableStateOf(false) }
    var pendingCfg    by remember { mutableStateOf<UdpMeshConfig?>(null) }

    val vpnPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingCfg?.let { startUdpMeshVpn(context, it) }
            statusMsg = "正在连接…"
        } else {
            statusMsg = "VPN 权限被拒绝"
        }
        pendingCfg = null
    }

    fun launchVpn(cfg: UdpMeshConfig) {
        pendingCfg = cfg
        val prep = VpnService.prepare(context)
        if (prep != null) vpnPermLauncher.launch(prep)
        else { startUdpMeshVpn(context, cfg); statusMsg = "正在连接…" }
    }

    DisposableEffect(Unit) {
        val recv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                vpnRunning   = i.getBooleanExtra(UdpMeshNetService.EXTRA_RUNNING, false)
                statusMsg    = i.getStringExtra(UdpMeshNetService.EXTRA_MESSAGE) ?: ""
                localVpnIp   = i.getStringExtra(UdpMeshNetService.EXTRA_LOCAL_VPN_IP) ?: ""
                peerVpnIp    = i.getStringExtra(UdpMeshNetService.EXTRA_PEER_VPN_IP)  ?: ""
                txBytes      = i.getLongExtra(UdpMeshNetService.EXTRA_TX_BYTES,    0L)
                rxBytes      = i.getLongExtra(UdpMeshNetService.EXTRA_RX_BYTES,    0L)
                txPackets    = i.getLongExtra(UdpMeshNetService.EXTRA_TX_PACKETS,  0L)
                rxPackets    = i.getLongExtra(UdpMeshNetService.EXTRA_RX_PACKETS,  0L)
                handshakeMs  = i.getLongExtra(UdpMeshNetService.EXTRA_HANDSHAKE_MS, 0L)
                reconnecting = i.getBooleanExtra(UdpMeshNetService.EXTRA_RECONNECTING, false)
            }
        }
        val filter = IntentFilter(UdpMeshNetService.BROADCAST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(recv, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(recv, filter)
        onDispose { context.unregisterReceiver(recv) }
    }

    when (page) {
        UdpMeshPage.HOME ->
            UdpMeshHomeScreen(
                vpnRunning = vpnRunning, statusMsg = statusMsg,
                localVpnIp = localVpnIp, peerVpnIp = peerVpnIp,
                txBytes = txBytes, rxBytes = rxBytes,
                txPackets = txPackets, rxPackets = rxPackets,
                handshakeMs = handshakeMs, reconnecting = reconnecting,
                onBack = onBack,
                onSetupServer = { page = UdpMeshPage.SERVER_SETUP },
                onSetupClient = { page = UdpMeshPage.CLIENT_SETUP },
                onStop = { stopUdpMeshVpn(context) }
            )
        UdpMeshPage.SERVER_SETUP ->
            UdpMeshServerSetupScreen(
                onBack  = { page = UdpMeshPage.HOME },
                onStart = { cfg ->
                    saveUdpCfg(context, KEY_UDP_SERVER, cfg)
                    launchVpn(cfg)
                    page = UdpMeshPage.HOME
                }
            )
        UdpMeshPage.CLIENT_SETUP ->
            UdpMeshClientSetupScreen(
                onBack  = { page = UdpMeshPage.HOME },
                onStart = { cfg ->
                    saveUdpCfg(context, KEY_UDP_CLIENT, cfg)
                    launchVpn(cfg)
                    page = UdpMeshPage.HOME
                }
            )
    }
}

// ============================================================
// 首页
// ============================================================
@Composable
private fun UdpMeshHomeScreen(
    vpnRunning: Boolean, statusMsg: String,
    localVpnIp: String, peerVpnIp: String,
    txBytes: Long, rxBytes: Long,
    txPackets: Long, rxPackets: Long,
    handshakeMs: Long, reconnecting: Boolean,
    onBack: () -> Unit, onSetupServer: () -> Unit,
    onSetupClient: () -> Unit, onStop: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    val animAngle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f, label = "rot",
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Restart)
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 动态渐变背景
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.sweepGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                Spacer(Modifier.width(6.dp))
                Text("返回传输实验室")
            }

            // 标题区
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFF00C6FF),
                                    Color(0xFF0072FF)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Wifi, null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Column {
                    Text(
                        "UDP 异地组网",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    )
                    Text(
                        "FileTran Over UDP",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 特性标签行
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                UdpFeatureChip("AES-256-GCM", Icons.Default.Lock)
                UdpFeatureChip("ECDH-P256", Icons.Default.Key)
                UdpFeatureChip("IPv4 / IPv6", Icons.Default.Language)
                UdpFeatureChip("二维码交换", Icons.Default.QrCode)
            }

            // 状态卡
            UdpStatusCard(
                running      = vpnRunning,
                statusMsg    = statusMsg,
                localVpnIp   = localVpnIp,
                peerVpnIp    = peerVpnIp,
                txBytes      = txBytes,
                rxBytes      = rxBytes,
                txPackets    = txPackets,
                rxPackets    = rxPackets,
                handshakeMs  = handshakeMs,
                reconnecting = reconnecting,
                onStop       = onStop,
                onCopy       = { clipboard.setText(AnnotatedString(localVpnIp)) }
            )

            if (!vpnRunning) {
                // 功能说明卡
                UdpInfoCard()

                Spacer(Modifier.height(4.dp))
                Text(
                    "选择角色开始组网",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    UdpRoleCard(
                        icon    = Icons.Default.Router,
                        title   = "服务端",
                        badge   = "监听方",
                        desc    = "开启监听，生成二维码\n让客户端扫码连接\n支持 NAT 后方",
                        gradient = listOf(Color(0xFF004D40), Color(0xFF00695C)),
                        onClick  = onSetupServer,
                        modifier = Modifier.weight(1f)
                    )
                    UdpRoleCard(
                        icon    = Icons.Default.PhoneAndroid,
                        title   = "客户端",
                        badge   = "连接方",
                        desc    = "扫描服务端二维码\n或手动填写地址\n快速加入网络",
                        gradient = listOf(Color(0xFF1A237E), Color(0xFF283593)),
                        onClick  = onSetupClient,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ============================================================
// 服务端配置页
// ============================================================
@Composable
private fun UdpMeshServerSetupScreen(
    onBack : () -> Unit,
    onStart: (UdpMeshConfig) -> Unit
) {
    val context   = LocalContext.current
    val scope     = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val localIps  = remember { udpCollectLocalIps() }
    val saved     = remember { loadUdpCfg(context, KEY_UDP_SERVER) }

    var passcode       by rememberSaveable { mutableStateOf(saved?.passcode         ?: UdpMeshCrypto.generatePasscode()) }
    var listenPort     by rememberSaveable { mutableStateOf(saved?.listenPort?.toString()  ?: "7891") }
    var serverVpnIp    by rememberSaveable { mutableStateOf(saved?.serverVpnIp      ?: "192.168.200.1") }
    var clientVpnIp    by rememberSaveable { mutableStateOf(saved?.clientVpnIp      ?: "192.168.200.2") }
    var subnetMask     by rememberSaveable { mutableStateOf(saved?.subnetMask       ?: "255.255.255.0") }
    var mtu            by rememberSaveable { mutableStateOf(saved?.mtu?.toString()  ?: "1380") }
    var keepalive      by rememberSaveable { mutableStateOf(saved?.keepaliveIntervalSec?.toString() ?: "15") }
    var serverLanCidrs by rememberSaveable { mutableStateOf(saved?.serverLanCidrs   ?: "") }
    var clientLanCidrs by rememberSaveable { mutableStateOf(saved?.clientLanCidrs   ?: "") }
    var publicIpv4     by rememberSaveable { mutableStateOf(saved?.serverPublicIpv4 ?: "") }
    var publicIpv6     by rememberSaveable { mutableStateOf(saved?.serverPublicIpv6 ?: "") }
    var stunMappedPort by rememberSaveable { mutableStateOf(saved?.stunMappedPort?.takeIf { it > 0 }?.toString() ?: "") }
    var tunnelIface    by rememberSaveable { mutableStateOf(saved?.tunnelIface      ?: "") }
    // 预留：打洞信令服务器
    var stunServer     by rememberSaveable { mutableStateOf(saved?.stunServer        ?: "") }
    var stunPort       by rememberSaveable { mutableStateOf(saved?.stunPort?.toString() ?: "3478") }
    // STUN 探测状态
    var stunProbing    by remember { mutableStateOf(false) }
    var stunProbeMsg   by remember { mutableStateOf("") }
    var retryInterval  by rememberSaveable { mutableStateOf(saved?.retryIntervalSec?.toString() ?: "10") }
    var maxRetry       by rememberSaveable { mutableStateOf(saved?.maxRetryCount?.toString() ?: "0") }

    var fetchingIp by remember { mutableStateOf(false) }
    var fetchMsg   by remember { mutableStateOf("") }
    var showQr     by remember { mutableStateOf(false) }
    var qrMode     by rememberSaveable { mutableStateOf(0) }  // 0=IPv4 1=IPv6 2=局域网

    fun buildCfg() = UdpMeshConfig(
        role             = UdpMeshRole.SERVER,
        passcode         = passcode.trim(),
        listenPort       = listenPort.toIntOrNull() ?: 7891,
        serverVpnIp      = serverVpnIp.trim(),
        clientVpnIp      = clientVpnIp.trim(),
        subnetMask       = subnetMask.trim(),
        mtu              = mtu.toIntOrNull() ?: 1380,
        keepaliveIntervalSec = keepalive.toIntOrNull() ?: 15,
        serverLanCidrs   = serverLanCidrs.trim(),
        clientLanCidrs   = clientLanCidrs.trim(),
        serverPublicIpv4 = publicIpv4.trim(),
        serverPublicIpv6 = publicIpv6.trim(),
        stunServer       = stunServer.trim(),
        stunPort         = stunPort.toIntOrNull() ?: 3478,
        stunMappedPort   = stunMappedPort.toIntOrNull() ?: 0,
        tunnelIface      = tunnelIface.trim()
    )

    val qrJson   = remember(showQr, qrMode, publicIpv4, publicIpv6, passcode, listenPort,
                            serverVpnIp, clientVpnIp, subnetMask, serverLanCidrs, clientLanCidrs,
                            stunMappedPort) {
        if (showQr) UdpMeshCrypto.buildQrPayload(buildCfg().copy(
            serverPublicIpv4 = when (qrMode) { 2 -> localIps.firstOrNull() ?: publicIpv4; else -> publicIpv4 },
            serverPublicIpv6 = publicIpv6,
            // 打洞模式：listenPort 替换为 stunMappedPort
            listenPort = if (qrMode == 3 && stunMappedPort.toIntOrNull() ?: 0 > 0)
                stunMappedPort.toInt() else listenPort.toIntOrNull() ?: 7891
        )) else null
    }
    val qrBitmap = remember(qrJson) {
        qrJson?.let { QRCodeGenerator.generateQRCode(it, 900) }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
            Spacer(Modifier.width(6.dp)); Text("返回")
        }
        Text("服务端配置", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("配置完成后生成二维码，让客户端扫码一键接入。",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // 公网 IP 获取
        UdpSetupCard {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Public, null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Text("公网 IP", fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = publicIpv4, onValueChange = { publicIpv4 = it.trim() },
                    modifier = Modifier.weight(1f), singleLine = true, label = { Text("IPv4") },
                    placeholder = { Text("自动获取或手动填") })
                OutlinedTextField(value = publicIpv6, onValueChange = { publicIpv6 = it.trim() },
                    modifier = Modifier.weight(1f), singleLine = true, label = { Text("IPv6") },
                    placeholder = { Text("可选") })
            }
            Button(
                onClick = {
                    fetchingIp = true; fetchMsg = "获取中…"
                    scope.launch {
                        val v4 = withContext(Dispatchers.IO) { meshFetchPublicIp("https://v4.ipip.net/") }
                        val v6 = withContext(Dispatchers.IO) { meshFetchPublicIp("https://v6.ipip.net/") }
                        if (v4 != null) publicIpv4 = v4
                        if (v6 != null) publicIpv6 = v6
                        fetchMsg = when {
                            v4 != null && v6 != null -> "✅ $v4 / $v6"
                            v4 != null -> "✅ IPv4: $v4（无 IPv6）"
                            v6 != null -> "✅ IPv6: $v6（无 IPv4）"
                            else -> "❌ 获取失败，请手动填写"
                        }
                        fetchingIp = false
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = !fetchingIp
            ) {
                if (fetchingIp) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (fetchingIp) "获取中…" else "自动获取公网 IP")
            }
            if (fetchMsg.isNotBlank()) Text(fetchMsg, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (localIps.isNotEmpty()) {
                Text("本机局域网 IP：", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    localIps.take(4).forEach { ip ->
                        OutlinedButton(onClick = { publicIpv4 = ip },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(ip, fontSize = 11.sp) }
                    }
                }
            }
        }

        // 基础配置
        UdpSetupCard {
            UdpFieldLabel("连接密码", "两端必须一致") {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = passcode, onValueChange = { passcode = it },
                        modifier = Modifier.weight(1f), singleLine = true,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                    IconButton(onClick = { passcode = UdpMeshCrypto.generatePasscode() }) {
                        Icon(Icons.Default.Refresh, "重新生成") }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(passcode)) }) {
                        Icon(Icons.Default.ContentCopy, "复制") }
                }
            }
            UdpFieldLabel("监听端口", "UDP 端口，默认 7891") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = listenPort,
                        onValueChange = { listenPort = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("7891", "8388", "9001").forEach { p ->
                        OutlinedButton(onClick = { listenPort = p },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(p, fontSize = 11.sp) }
                    }
                }
            }
            UdpFieldLabel("服务端 VPN IP", "") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = serverVpnIp, onValueChange = { serverVpnIp = it.trim() },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("192.168.200.1", "10.10.0.1").forEach { ip ->
                        OutlinedButton(onClick = { serverVpnIp = ip },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(ip, fontSize = 10.sp) }
                    }
                }
            }
            UdpFieldLabel("客户端 VPN IP", "分配给对端的地址") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = clientVpnIp, onValueChange = { clientVpnIp = it.trim() },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("192.168.200.2", "10.10.0.2").forEach { ip ->
                        OutlinedButton(onClick = { clientVpnIp = ip },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(ip, fontSize = 10.sp) }
                    }
                }
            }
        }

        // 内网互访
        UdpSetupCard {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.AccountTree, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary)
                Text("内网互访（需 Root）", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            Text("填写内网段后连接时自动配置路由和 iptables，实现点对网或网对网互访。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            UdpFieldLabel("服务端内网段", "客户端连接后可访问") {
                OutlinedTextField(value = serverLanCidrs, onValueChange = { serverLanCidrs = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("如 192.168.1.0/24，多段逗号分隔") })
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("192.168.1.0/24", "192.168.0.0/24", "10.0.0.0/8").forEach { cidr ->
                        OutlinedButton(onClick = {
                            serverLanCidrs = if (serverLanCidrs.isBlank()) cidr else "$serverLanCidrs,$cidr"
                        }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(cidr, fontSize = 10.sp) }
                    }
                }
            }
            UdpFieldLabel("客户端内网段", "服务端连接后可访问") {
                OutlinedTextField(value = clientLanCidrs, onValueChange = { clientLanCidrs = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("如 192.168.2.0/24，留空则不配置") })
            }
        }

        // 高级 + 打洞预留
        UdpSetupCard {
            UdpFieldLabel("MTU", "UDP 模式推荐 1380") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = mtu,
                        onValueChange = { mtu = it.filter(Char::isDigit).take(4) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("1380", "1350", "1280").forEach { m ->
                        OutlinedButton(onClick = { mtu = m },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(m, fontSize = 11.sp) }
                    }
                }
            }
            UdpFieldLabel("心跳间隔（秒）", "") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = keepalive,
                        onValueChange = { keepalive = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("15", "20", "30").forEach { k ->
                        OutlinedButton(onClick = { keepalive = k },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(k, fontSize = 11.sp) }
                    }
                }
            }
        }

        // STUN 探测打洞
        UdpSetupCard {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.TrackChanges, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Text("STUN 打洞探测", fontWeight = FontWeight.SemiBold)
            }
            Text("探测本机公网映射端口，用于 NAT 后方穿透。探测成功后可将映射端口写入二维码，客户端直连打洞地址。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = stunMappedPort,
                    onValueChange = { stunMappedPort = it.filter(Char::isDigit).take(5) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    label = { Text("打洞映射端口") },
                    placeholder = { Text("探测后自动填入") }
                )
                Button(
                    onClick = {
                        stunProbing = true; stunProbeMsg = "探测中…"
                        scope.launch {
                            val port = listenPort.toIntOrNull() ?: 7891
                            // 先尝试 IPv6，再尝试 IPv4
                            val ep = withContext(Dispatchers.IO) {
                                NetworkUtils.probeStunMappedEndpointBatch(
                                    localPort = port, preferIpv6 = publicIpv6.isNotBlank(),
                                    transport = StunTransportType.UDP
                                ).preferredEndpoint
                                    ?: NetworkUtils.probeStunMappedEndpointBatch(
                                        localPort = port, preferIpv6 = false,
                                        transport = StunTransportType.UDP
                                    ).preferredEndpoint
                            }
                            if (ep != null) {
                                stunMappedPort = ep.port.toString()
                                // 自动填入公网 IP
                                if (ep.address.contains(":") && publicIpv6.isBlank()) publicIpv6 = ep.address
                                else if (!ep.address.contains(":") && publicIpv4.isBlank()) publicIpv4 = ep.address
                                stunProbeMsg = "✅ 映射端点: ${ep.address}:${ep.port}"
                            } else {
                                stunProbeMsg = "❌ 探测失败，请检查网络或 STUN 服务器配置"
                            }
                            stunProbing = false
                        }
                    },
                    enabled = !stunProbing,
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) {
                    if (stunProbing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Search, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("探测", fontSize = 12.sp)
                }
            }
            if (stunProbeMsg.isNotBlank())
                Text(stunProbeMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 隧道接口（移动数据 IPv6 + WiFi 内网场景）
        UdpSetupCard {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.SettingsEthernet, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary)
                Text("隧道网络接口（高级）", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            Text("留空=自动选择。移动数据 IPv6 + WiFi 内网场景：填入移动数据接口名（如 rmnet_data0），隧道走移动网络，内网路由走 WiFi。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = tunnelIface,
                onValueChange = { tunnelIface = it.trim() },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("留空=自动，如 rmnet_data0、wlan0") }
            )
            // 列出当前可用接口
            val availIfaces = remember {
                runCatching {
                    java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                        ?.filter { it.isUp && !it.isLoopback }
                        ?.map { it.name } ?: emptyList()
                }.getOrDefault(emptyList())
            }
            if (availIfaces.isNotEmpty()) {
                Text("当前可用接口：", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    availIfaces.take(6).forEach { name ->
                        OutlinedButton(onClick = { tunnelIface = name },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(name, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
        }
        Button(
            onClick = { showQr = !showQr }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Icon(Icons.Default.QrCode, null)
            Spacer(Modifier.width(8.dp))
            Text(if (showQr) "隐藏二维码" else "生成二维码（供客户端扫描）")
        }

        AnimatedVisibility(visible = showQr,
            enter = expandVertically() + fadeIn(),
            exit  = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // IP 模式切换
                UdpSetupCard {
                    Text("二维码内嵌连接地址", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        listOf(
                            0 to "IPv4 公网\n${publicIpv4.ifBlank { "未获取" }}",
                            1 to "IPv6 公网\n${publicIpv6.ifBlank { "未获取" }}",
                            2 to "局域网\n${localIps.firstOrNull() ?: "无"}",
                            3 to "打洞端口\n${if (stunMappedPort.isNotBlank()) ":$stunMappedPort" else "未探测"}"
                        ).forEach { (idx, label) ->
                            if (qrMode == idx) {
                                Button(
                                    onClick = { qrMode = idx },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                                ) {
                                    Text(label, fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 14.sp)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { qrMode = idx },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                                ) {
                                    Text(label, fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 14.sp)
                                }
                            }
                        }
                    }
                    val chosenHost = when (qrMode) {
                        1    -> publicIpv6.ifBlank { "(IPv6 未获取)" }
                        2    -> localIps.firstOrNull() ?: "(无局域网IP)"
                        3    -> publicIpv4.ifBlank { publicIpv6.ifBlank { "(IP 未获取)" } }
                        else -> publicIpv4.ifBlank { "(IPv4 未获取)" }
                    }
                    val chosenPort = if (qrMode == 3 && stunMappedPort.isNotBlank())
                        stunMappedPort else listenPort
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "连接地址：$chosenHost:$chosenPort",
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(10.dp, 6.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                if (qrBitmap != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("扫码接入", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                "客户端扫描后自动填写所有参数\n确认后点击连接即可",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "UDP组网二维码",
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                            )
                            OutlinedButton(
                                onClick = { clipboard.setText(AnnotatedString(qrJson ?: "")) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.ContentCopy, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("复制配置 JSON")
                            }
                        }
                    }
                }
            }
        }

        // 启动按钮
        Button(
            onClick = { onStart(buildCfg()) },
            modifier = Modifier.fillMaxWidth(),
            enabled = passcode.isNotBlank() && (listenPort.toIntOrNull() ?: 0) in 1..65535,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1565C0)
            )
        ) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("保存并启动服务端")
        }
    }
}

// ============================================================
// 客户端配置页
// ============================================================
@Composable
private fun UdpMeshClientSetupScreen(
    onBack : () -> Unit,
    onStart: (UdpMeshConfig) -> Unit
) {
    val context    = LocalContext.current
    val saved      = remember { loadUdpCfg(context, KEY_UDP_CLIENT) }
    var showScanner by remember { mutableStateOf(false) }
    var scanMsg     by remember { mutableStateOf("") }

    var passcode       by rememberSaveable { mutableStateOf(saved?.passcode         ?: "") }
    var serverHost     by rememberSaveable { mutableStateOf(saved?.serverHost       ?: "") }
    var serverPort     by rememberSaveable { mutableStateOf(saved?.listenPort?.toString() ?: "7891") }
    var clientVpnIp    by rememberSaveable { mutableStateOf(saved?.clientVpnIp      ?: "192.168.200.2") }
    var subnetMask     by rememberSaveable { mutableStateOf(saved?.subnetMask       ?: "255.255.255.0") }
    var mtu            by rememberSaveable { mutableStateOf(saved?.mtu?.toString()  ?: "1380") }
    var keepalive      by rememberSaveable { mutableStateOf(saved?.keepaliveIntervalSec?.toString() ?: "15") }
    var serverLanCidrs by rememberSaveable { mutableStateOf(saved?.serverLanCidrs   ?: "") }
    var clientLanCidrs by rememberSaveable { mutableStateOf(saved?.clientLanCidrs   ?: "") }
    var stunServer     by rememberSaveable { mutableStateOf(saved?.stunServer        ?: "") }
    var stunPort       by rememberSaveable { mutableStateOf(saved?.stunPort?.toString() ?: "3478") }
    var retryInterval  by rememberSaveable { mutableStateOf(saved?.retryIntervalSec?.toString() ?: "10") }
    var maxRetry       by rememberSaveable { mutableStateOf(saved?.maxRetryCount?.toString() ?: "0") }
    var tunnelIface    by rememberSaveable { mutableStateOf(saved?.tunnelIface      ?: "") }

    if (showScanner) {
        QRCodeScanner(
            onQRCodeScanned = { raw ->
                showScanner = false
                val cfg = UdpMeshCrypto.parseQrPayload(raw)
                if (cfg != null) {
                    passcode       = cfg.passcode
                    serverHost     = cfg.serverHost.ifBlank { cfg.serverPublicIpv4.ifBlank { cfg.serverPublicIpv6 } }
                    serverPort     = cfg.listenPort.toString()
                    clientVpnIp    = cfg.clientVpnIp
                    subnetMask     = cfg.subnetMask
                    mtu            = cfg.mtu.toString()
                    serverLanCidrs = cfg.serverLanCidrs
                    clientLanCidrs = cfg.clientLanCidrs
                    scanMsg        = "✅ 扫码成功，确认后连接"
                } else {
                    scanMsg = "❌ 非 UDP 组网二维码（请扫 FileTran Over UDP 的码）"
                }
            },
            onDismiss = { showScanner = false }
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
            Spacer(Modifier.width(6.dp)); Text("返回")
        }
        Text("客户端配置", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("扫描服务端二维码一键填写，或手动输入后连接。",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // 扫码
        Button(
            onClick = { showScanner = true }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Icon(Icons.Default.QrCodeScanner, null)
            Spacer(Modifier.width(8.dp)); Text("扫描服务端二维码")
        }
        if (scanMsg.isNotBlank())
            Text(scanMsg, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)

        // 连接参数
        UdpSetupCard {
            UdpFieldLabel("服务端地址", "公网 IP 或域名，支持 IPv4 / IPv6") {
                OutlinedTextField(
                    value = serverHost, onValueChange = { serverHost = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("如 1.2.3.4 或 [2001:db8::1]") }
                )
            }
            UdpFieldLabel("服务端端口", "") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = serverPort,
                        onValueChange = { serverPort = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                    listOf("7891", "8388", "9001").forEach { p ->
                        OutlinedButton(
                            onClick = { serverPort = p },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text(p, fontSize = 11.sp) }
                    }
                }
            }
            UdpFieldLabel("连接密码", "与服务端一致") {
                OutlinedTextField(
                    value = passcode, onValueChange = { passcode = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    placeholder = { Text("扫码自动填写或手动粘贴") }
                )
            }
            UdpFieldLabel("本机 VPN IP", "") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = clientVpnIp, onValueChange = { clientVpnIp = it.trim() },
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                    listOf("192.168.200.2", "10.10.0.2").forEach { ip ->
                        OutlinedButton(
                            onClick = { clientVpnIp = ip },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text(ip, fontSize = 10.sp) }
                    }
                }
            }
        }

        // 内网互访
        UdpSetupCard {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.AccountTree, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary)
                Text("内网互访（需 Root）", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            UdpFieldLabel("服务端内网段", "扫码自动填入，可手动修改") {
                OutlinedTextField(
                    value = serverLanCidrs, onValueChange = { serverLanCidrs = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("如 192.168.1.0/24") }
                )
            }
            UdpFieldLabel("客户端内网段", "本机局域网，服务端可访问") {
                OutlinedTextField(
                    value = clientLanCidrs, onValueChange = { clientLanCidrs = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("如 192.168.2.0/24，留空则不配置") }
                )
            }
        }

        // 高级
        UdpSetupCard {
            UdpFieldLabel("MTU", "推荐 1380") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = mtu,
                        onValueChange = { mtu = it.filter(Char::isDigit).take(4) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("1380", "1350", "1280").forEach { m ->
                        OutlinedButton(onClick = { mtu = m },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(m, fontSize = 11.sp) }
                    }
                }
            }
            UdpFieldLabel("心跳间隔（秒）", "") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = keepalive,
                        onValueChange = { keepalive = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("15", "20", "30").forEach { k ->
                        OutlinedButton(onClick = { keepalive = k },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(k, fontSize = 11.sp) }
                    }
                }
            }
        }

        // 打洞预留通道
        UdpPunchReservedCard(
            stunServer = stunServer, stunPort = stunPort,
            onStunServer = { stunServer = it }, onStunPort = { stunPort = it }
        )

        // 隧道接口（移动数据 IPv6 + WiFi 内网场景）
        UdpSetupCard {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.SettingsEthernet, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary)
                Text("隧道网络接口（高级）", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            Text("留空=自动选择。移动数据 IPv6 + WiFi 内网场景：填入移动数据接口名，隧道走移动网络，内网路由走 WiFi。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = tunnelIface,
                onValueChange = { tunnelIface = it.trim() },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("留空=自动，如 rmnet_data0、wlan0") }
            )
            val availIfaces = remember {
                runCatching {
                    java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                        ?.filter { it.isUp && !it.isLoopback }
                        ?.map { it.name } ?: emptyList()
                }.getOrDefault(emptyList())
            }
            if (availIfaces.isNotEmpty()) {
                Text("当前可用接口：", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    availIfaces.take(6).forEach { name ->
                        OutlinedButton(onClick = { tunnelIface = name },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(name, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
        }

        // 自动重试配置
        UdpSetupCard {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Text("自动重连（仅客户端）", fontWeight = FontWeight.SemiBold)
            }
            Text("连接断开后自动重试，适用于网络不稳定场景。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            UdpFieldLabel("重试间隔（秒）", "每次重试之间的等待时间") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = retryInterval,
                        onValueChange = { retryInterval = it.filter(Char::isDigit).take(4) },
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                    listOf("5", "10", "30", "60").forEach { v ->
                        OutlinedButton(onClick = { retryInterval = v },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(v, fontSize = 11.sp) }
                    }
                }
            }
            UdpFieldLabel("最大重试次数", "0=不重试，-1=无限重试") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = maxRetry,
                        onValueChange = { maxRetry = it.filter { c -> c.isDigit() || c == '-' }.take(4) },
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                    listOf("0" to "不重试", "3" to "3次", "10" to "10次", "-1" to "无限").forEach { (v, lbl) ->
                        OutlinedButton(onClick = { maxRetry = v },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(lbl, fontSize = 11.sp) }
                    }
                }
            }
        }

        Button(
            onClick = {
                onStart(UdpMeshConfig(
                    role             = UdpMeshRole.CLIENT,
                    passcode         = passcode.trim(),
                    serverHost       = serverHost.trim(),
                    listenPort       = serverPort.toIntOrNull() ?: 7891,
                    clientVpnIp      = clientVpnIp.trim(),
                    subnetMask       = subnetMask.trim(),
                    mtu              = mtu.toIntOrNull() ?: 1380,
                    keepaliveIntervalSec = keepalive.toIntOrNull() ?: 15,
                    serverLanCidrs   = serverLanCidrs.trim(),
                    clientLanCidrs   = clientLanCidrs.trim(),
                    stunServer       = stunServer.trim(),
                    stunPort         = stunPort.toIntOrNull() ?: 3478,
                    retryIntervalSec = retryInterval.toIntOrNull() ?: 10,
                    maxRetryCount    = maxRetry.toIntOrNull() ?: 0,
                    tunnelIface      = tunnelIface.trim()
                ))
            },
            modifier = Modifier.fillMaxWidth(),
            enabled  = serverHost.isNotBlank() && passcode.isNotBlank()
                    && (serverPort.toIntOrNull() ?: 0) in 1..65535,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00695C))
        ) {
            Icon(Icons.Default.Link, null)
            Spacer(Modifier.width(8.dp))
            Text("保存并连接到服务端")
        }
    }
}

// ============================================================
// 打洞预留通道卡片（折叠式）
// ============================================================
@Composable
private fun UdpPunchReservedCard(
    stunServer : String, stunPort: String,
    onStunServer: (String) -> Unit, onStunPort: (String) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border   = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.SwapCalls, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text("打洞预留通道（后续功能）",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                    Text("配置 STUN/信令服务器，用于后续 UDP 打洞穿透 NAT",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(0.2f))
                    Surface(
                        color  = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                        shape  = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, null, Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text(
                                "当前版本为直连模式。填写后将在打洞功能上线时自动启用，无需重新配置。",
                                fontSize = 11.sp, lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                    UdpFieldLabel("STUN/信令服务器地址", "留空=直连模式") {
                        OutlinedTextField(
                            value = stunServer, onValueChange = onStunServer,
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("如 stun.example.com") },
                            trailingIcon = {
                                if (stunServer.isNotBlank())
                                    IconButton(onClick = { onStunServer("") }) {
                                        Icon(Icons.Default.Clear, null, Modifier.size(18.dp))
                                    }
                            }
                        )
                    }
                    UdpFieldLabel("STUN 端口", "") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = stunPort,
                                onValueChange = { onStunPort(it.filter(Char::isDigit).take(5)) },
                                modifier = Modifier.weight(1f), singleLine = true
                            )
                            listOf("3478", "5349").forEach { p ->
                                OutlinedButton(
                                    onClick = { onStunPort(p) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) { Text(p, fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 状态卡片
// ============================================================
// 格式化字节数
private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L        -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

// 格式化握手时间
private fun formatHandshakeTime(ms: Long): String {
    if (ms == 0L) return "—"
    val now = System.currentTimeMillis()
    val sec = (now - ms) / 1000L
    return when {
        sec < 60   -> "${sec}s 前"
        sec < 3600 -> "${sec/60}m ${sec%60}s 前"
        else       -> "${sec/3600}h ${(sec%3600)/60}m 前"
    }
}

@Composable
private fun UdpStatusCard(
    running: Boolean, statusMsg: String,
    localVpnIp: String, peerVpnIp: String,
    txBytes: Long, rxBytes: Long,
    txPackets: Long, rxPackets: Long,
    handshakeMs: Long, reconnecting: Boolean,
    onStop: () -> Unit, onCopy: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        0.4f, 1f, label = "dot",
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse)
    )
    // 重连时用橙色
    val bgColor by animateColorAsState(
        when {
            running      -> MaterialTheme.colorScheme.primaryContainer
            reconnecting -> MaterialTheme.colorScheme.tertiaryContainer
            else         -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "bg"
    )
    val textColor = when {
        running      -> MaterialTheme.colorScheme.onPrimaryContainer
        reconnecting -> MaterialTheme.colorScheme.onTertiaryContainer
        else         -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // 握手时间需要定时刷新
    var handshakeLabel by remember { mutableStateOf(formatHandshakeTime(handshakeMs)) }
    LaunchedEffect(handshakeMs) {
        while (true) {
            handshakeLabel = formatHandshakeTime(handshakeMs)
            kotlinx.coroutines.delay(5000)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = bgColor),
        shape    = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 标题行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                running      -> MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                                reconnecting -> Color(0xFFFF9800).copy(alpha = alpha)
                                else         -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            }
                        )
                )
                Text(
                    when {
                        running      -> "UDP 隧道已建立"
                        reconnecting -> "正在重连…"
                        else         -> "未连接"
                    },
                    fontWeight = FontWeight.Bold, fontSize = 16.sp, color = textColor
                )
                Spacer(Modifier.weight(1f))
                if (running) Icon(Icons.Default.Lock, null, tint = textColor, modifier = Modifier.size(18.dp))
            }

            if (running) {
                HorizontalDivider(color = textColor.copy(0.15f))

                // IP 行
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UdpIpChip("本机 VPN", localVpnIp, Modifier.weight(1f))
                    Icon(
                        Icons.Default.SyncAlt, null,
                        modifier = Modifier.size(20.dp).align(Alignment.CenterVertically),
                        tint = textColor
                    )
                    UdpIpChip("对端 VPN", peerVpnIp, Modifier.weight(1f))
                }

                // 流量统计行
                HorizontalDivider(color = textColor.copy(0.10f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    UdpStatChip(
                        label = "↑ 发送",
                        value = formatBytes(txBytes),
                        sub   = "$txPackets 包",
                        color = Color(0xFF4CAF50),
                        modifier = Modifier.weight(1f)
                    )
                    UdpStatChip(
                        label = "↓ 接收",
                        value = formatBytes(rxBytes),
                        sub   = "$rxPackets 包",
                        color = Color(0xFF2196F3),
                        modifier = Modifier.weight(1f)
                    )
                    UdpStatChip(
                        label = "握手时间",
                        value = handshakeLabel,
                        sub   = "",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 操作按钮
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onCopy, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp)); Text("复制本机 IP")
                    }
                    Button(
                        onClick = onStop, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.LinkOff, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp)); Text("断开隧道")
                    }
                }
            }

            if (statusMsg.isNotBlank()) {
                Surface(
                    color  = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    shape  = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        statusMsg, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp, 6.dp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun UdpStatChip(
    label: String, value: String, sub: String,
    color: Color, modifier: Modifier = Modifier
) {
    Surface(
        color    = color.copy(alpha = 0.12f),
        shape    = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 9.sp, color = color.copy(alpha = 0.8f))
            Text(value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color,
                fontFamily = FontFamily.Monospace)
            if (sub.isNotBlank())
                Text(sub, fontSize = 9.sp, color = color.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun UdpIpChip(label: String, ip: String, modifier: Modifier = Modifier) {
    Surface(
        color    = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        shape    = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f))
            Text(
                ip.ifBlank { "—" }, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

// ============================================================
// 角色卡片
// ============================================================
@Composable
private fun UdpRoleCard(
    icon    : androidx.compose.ui.graphics.vector.ImageVector,
    title   : String,
    badge   : String,
    desc    : String,
    gradient: List<Color>,
    onClick : () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick  = onClick,
        modifier = modifier,
        shape    = RoundedCornerShape(18.dp),
        colors   = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(gradient))
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White.copy(alpha = 0.15f),
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(30.dp), tint = Color.White)
                    }
                }
                Text(title, fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp, color = Color.White)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.2f)
                ) {
                    Text(badge, fontSize = 10.sp, color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Text(desc, fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(0.85f),
                    lineHeight = 16.sp)
            }
        }
    }
}

// ============================================================
// 功能说明卡片
// ============================================================
@Composable
private fun UdpInfoCard() {
    val features = listOf(
        Icons.Default.Lock         to "AES-256-GCM 全程加密，零明文传输",
        Icons.Default.Key          to "ECDH P-256 密钥交换，每次会话独立",
        Icons.Default.Language     to "IPv4 / IPv6 双栈，自动选择最优路径",
        Icons.Default.QrCode       to "二维码交换配置，扫码即组网",
        Icons.Default.AccountTree  to "Root 内网路由，点对网 / 网对网",
        Icons.Default.SwapCalls    to "打洞通道预留，后续一键穿透 NAT"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Shield, null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("功能特点", fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            features.forEach { (icon, text) ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, null, Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(0.8f))
                    Text(text, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

// ============================================================
// 特性标签
// ============================================================
@Composable
private fun UdpFeatureChip(
    label: String,
    icon : androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(label, fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

// ============================================================
// 通用布局组件
// ============================================================
@Composable
private fun UdpSetupCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
private fun UdpFieldLabel(
    label: String, hint: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (hint.isNotBlank())
            Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

// ============================================================
// 工具函数
// ============================================================
private fun udpCollectLocalIps(): List<String> {
    val result = mutableListOf<String>()
    runCatching {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
        for (iface in ifaces.toList()) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses.toList()) {
                when {
                    addr is Inet4Address && !addr.isLoopbackAddress ->
                        result.add(0, addr.hostAddress.orEmpty())  // IPv4 优先
                    addr is Inet6Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress ->
                        result.add(addr.hostAddress?.substringBefore("%").orEmpty())
                }
            }
        }
    }
    return result.distinct().filter { it.isNotBlank() }
} 
