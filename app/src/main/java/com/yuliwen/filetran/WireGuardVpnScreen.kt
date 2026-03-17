package com.yuliwen.filetran

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

// ============================================================
// 页面路由枚举
// ============================================================
private enum class WgPage {
    HOME, SERVER_SETUP, CLIENT_SETUP, CLIENT_QR, VIEW_CONF, EDIT_CLIENT_CFG
}

// ============================================================
// 顶层入口
// ============================================================
@Composable
fun WireGuardVpnScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var page               by rememberSaveable { mutableStateOf(WgPage.HOME) }
    var configs            by remember { mutableStateOf(WireGuardConfigStore.loadAll(context)) }
    var vpnRunning         by remember { mutableStateOf(WireGuardVpnService.isRunning) }
    var activeId           by remember { mutableStateOf(WireGuardVpnService.currentConfigId) }
    var statusMsg          by remember { mutableStateOf("") }
    var handshakeInfo      by remember { mutableStateOf("") }
    var viewConfTarget     by remember { mutableStateOf<WgConfig?>(null) }
    var generatedClientCfg by remember { mutableStateOf<WgConfig?>(null) }
    var editingClientCfg   by remember { mutableStateOf<WgConfig?>(null) }

    fun refreshConfigs() { configs = WireGuardConfigStore.loadAll(context) }

    val vpnPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val cfg = configs.firstOrNull { it.id == activeId } ?: return@rememberLauncherForActivityResult
            startWgVpn(context, cfg); statusMsg = "正在连接 ${cfg.name}…"
        } else { statusMsg = "VPN 权限被拒绝" }
    }

    fun connectVpn(cfg: WgConfig) {
        activeId = cfg.id
        val p = VpnService.prepare(context)
        if (p != null) vpnPermLauncher.launch(p)
        else { startWgVpn(context, cfg); statusMsg = "正在连接 ${cfg.name}…" }
    }
    fun disconnectVpn() { stopWgVpn(context); statusMsg = "正在断开…" }

    DisposableEffect(Unit) {
        val recv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                vpnRunning    = WireGuardVpnService.isRunning
                activeId      = WireGuardVpnService.currentConfigId
                statusMsg     = i.getStringExtra(WireGuardVpnService.EXTRA_MESSAGE) ?: ""
                handshakeInfo = i.getStringExtra(WireGuardVpnService.EXTRA_HANDSHAKE) ?: ""
            }
        }
        val f = IntentFilter(WireGuardVpnService.BROADCAST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(recv, f, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(recv, f)
        onDispose { context.unregisterReceiver(recv) }
    }

    when (page) {
        WgPage.HOME -> WgHomePage(
            configs = configs, vpnRunning = vpnRunning, activeId = activeId,
            statusMsg = statusMsg, handshakeInfo = handshakeInfo,
            onBack = onBack, onNewServer = { page = WgPage.SERVER_SETUP },
            onNewClient = { page = WgPage.CLIENT_SETUP },
            onConnect = ::connectVpn, onDisconnect = ::disconnectVpn,
            onViewConf = { cfg -> viewConfTarget = cfg; page = WgPage.VIEW_CONF },
            onEdit = { cfg -> editingClientCfg = cfg; page = WgPage.EDIT_CLIENT_CFG },
            onShowClientQr = { clientCfg ->
                generatedClientCfg = clientCfg
                page = WgPage.CLIENT_QR
            },
            onDelete = { cfg ->
                // 若为服务端，同步删除关联的客户端配置
                if (cfg.linkedClientId.isNotBlank()) {
                    WireGuardConfigStore.delete(context, cfg.linkedClientId)
                }
                WireGuardConfigStore.delete(context, cfg.id)
                if (activeId == cfg.id && vpnRunning) disconnectVpn()
                refreshConfigs()
            }
        )
        WgPage.SERVER_SETUP -> WgServerSetupPage(
            onBack = { page = WgPage.HOME },
            onSaved = { serverCfg, clientCfg ->
                WireGuardConfigStore.save(context, clientCfg)   // 先存客户端（ID 已关联在 serverCfg.linkedClientId）
                WireGuardConfigStore.save(context, serverCfg)   // 再存服务端
                generatedClientCfg = clientCfg
                refreshConfigs()
                page = WgPage.CLIENT_QR
            }
        )
        WgPage.CLIENT_SETUP -> WgClientSetupPage(
            onBack = { page = WgPage.HOME },
            onSaved = { cfg -> WireGuardConfigStore.save(context, cfg); refreshConfigs(); page = WgPage.HOME }
        )
        WgPage.CLIENT_QR -> WgClientQrPage(
            clientConfig = generatedClientCfg,
            onBack = { generatedClientCfg = null; page = WgPage.HOME },
            onEditGui = { cfg -> editingClientCfg = cfg; page = WgPage.EDIT_CLIENT_CFG },
            onSaved = { cfg -> WireGuardConfigStore.save(context, cfg); generatedClientCfg = cfg; refreshConfigs() }
        )
        WgPage.VIEW_CONF -> WgViewConfPage(config = viewConfTarget, onBack = { page = WgPage.HOME })
        WgPage.EDIT_CLIENT_CFG -> WgEditClientCfgPage(
            config = editingClientCfg,
            onBack = { page = WgPage.HOME },
            onSaved = { cfg -> WireGuardConfigStore.save(context, cfg); refreshConfigs(); page = WgPage.HOME }
        )
    }
}

// ============================================================
// HOME PAGE
// ============================================================
@Composable
private fun WgHomePage(
    configs: List<WgConfig>, vpnRunning: Boolean, activeId: String?,
    statusMsg: String, handshakeInfo: String,
    onBack: () -> Unit, onNewServer: () -> Unit, onNewClient: () -> Unit,
    onConnect: (WgConfig) -> Unit, onDisconnect: () -> Unit,
    onViewConf: (WgConfig) -> Unit, onEdit: (WgConfig) -> Unit,
    onDelete: (WgConfig) -> Unit, onShowClientQr: (WgConfig) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回传输实验室") }
        Text("异地组网 (WireGuard)", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("通过 WireGuard 协议，公网直连或 UDP 打洞，实现两台设备内网互通。支持 IPv4/IPv6 双栈。",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WgStatusBanner(vpnRunning, activeId, configs, statusMsg, handshakeInfo, onDisconnect)
        Text("新建配置", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            WgRoleCard(Icons.Default.Router, "服务端", "有公网 IP 的一侧，或作为中心节点", onNewServer, Modifier.weight(1f))
            WgRoleCard(Icons.Default.PhoneAndroid, "客户端", "扫码或手动填入服务端信息后接入", onNewClient, Modifier.weight(1f))
        }
        if (configs.isNotEmpty()) {
            Text("已保存配置 (${configs.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            configs.forEach { cfg ->
                val linkedClient = if (cfg.linkedClientId.isNotBlank())
                    configs.firstOrNull { it.id == cfg.linkedClientId } else null
                WgConfigCard(
                    config = cfg,
                    isActive = vpnRunning && activeId == cfg.id,
                    isRunning = vpnRunning,
                    linkedClient = linkedClient,
                    onConnect = { onConnect(cfg) },
                    onDisconnect = onDisconnect,
                    onViewConf = { onViewConf(cfg) },
                    onEdit = { onEdit(cfg) },
                    onDelete = { onDelete(cfg) },
                    onShowClientQr = if (linkedClient != null) onShowClientQr else null
                )
            }
        }
    }
}

@Composable
private fun WgRoleCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String, desc: String, onClick: () -> Unit, modifier: Modifier = Modifier
) {
    Card(onClick = onClick, modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(desc, fontSize = 11.sp, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun WgStatusBanner(
    running: Boolean, activeId: String?, configs: List<WgConfig>,
    msg: String, handshakeInfo: String, onDisconnect: () -> Unit
) {
    val name = configs.firstOrNull { it.id == activeId }?.name ?: ""
    val bg = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (running) Icons.Default.Lock else Icons.Default.LockOpen, null,
                    tint = if (running) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(if (running) "VPN 已连接" else "VPN 未连接", fontWeight = FontWeight.Bold,
                        color = if (running) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (running && name.isNotBlank())
                        Text(name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.8f))
                    if (msg.isNotBlank()) {
                        val isNatMsg = msg.startsWith("[NAT]")
                        val natSuccess = msg.contains("✅")
                        val natFail    = msg.contains("❌") || msg.contains("⚠️")
                        val msgColor = when {
                            isNatMsg && natSuccess -> MaterialTheme.colorScheme.tertiary
                            isNatMsg && natFail    -> MaterialTheme.colorScheme.error
                            isNatMsg               -> MaterialTheme.colorScheme.primary
                            running                -> MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f)
                            else                   -> MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f)
                        }
                        Surface(
                            color = if (isNatMsg) msgColor.copy(alpha = 0.12f)
                                    else androidx.compose.ui.graphics.Color.Transparent,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                        ) {
                            Text(
                                msg,
                                fontSize = 11.sp,
                                fontFamily = if (isNatMsg) FontFamily.Monospace else FontFamily.Default,
                                color = msgColor,
                                modifier = Modifier.padding(
                                    horizontal = if (isNatMsg) 6.dp else 0.dp,
                                    vertical   = if (isNatMsg) 4.dp else 0.dp
                                )
                            )
                        }
                    }
                }
                if (running) OutlinedButton(onClick = onDisconnect) { Text("断开") }
            }
            // 握手信息
            if (running && handshakeInfo.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.15f))
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Sync, null, modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Text("握手信息", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        Text(handshakeInfo, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun WgConfigCard(
    config: WgConfig,
    isActive: Boolean,
    isRunning: Boolean,
    linkedClient: WgConfig?,           // 服务端配置对应的客户端配置
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onViewConf: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onShowClientQr: ((WgConfig) -> Unit)?  // 仅服务端有效
) {
    val pubKey = remember(config.iface.privateKey) {
        runCatching { WireGuardKeyUtil.publicKeyFromPrivate(config.iface.privateKey) }.getOrDefault("(无效)")
    }
    // 服务端：有监听端口，且有 linkedClientId
    val isServer = config.iface.listenPort > 0 || config.linkedClientId.isNotBlank()
    // 客户端：有 peer endpoint，且无监听端口
    val isClient = !isServer && config.peers.any { it.endpoint.isNotBlank() }
    val roleTag = when {
        isServer -> "服务端"
        isClient -> "客户端"
        else -> ""
    }
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VpnKey, null, tint = if (isActive)
                    MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(config.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (roleTag.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Surface(color = MaterialTheme.colorScheme.secondary, shape = RoundedCornerShape(6.dp)) {
                        Text(roleTag, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onSecondary)
                    }
                }
                if (isActive) {
                    Spacer(Modifier.width(6.dp))
                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(6.dp)) {
                        Text("运行中", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            WgInfoRow("地址", config.iface.address)
            WgInfoRow("端口", if (config.iface.listenPort > 0) config.iface.listenPort.toString() else "(客户端)")
            WgInfoRow("公钥", pubKey.take(20) + "…")
            if (config.peers.isNotEmpty()) WgInfoRow("对端", "${config.peers.size} 个")
            config.peers.firstOrNull()?.endpoint?.takeIf { it.isNotBlank() }?.let {
                WgInfoRow("Endpoint", it)
            }
            // 服务端：显示已关联的客户端信息
            if (isServer && linkedClient != null) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text("已生成客户端：${linkedClient.name}",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!isRunning) Button(onClick = onConnect) { Text("连接") }
                else if (isActive) OutlinedButton(onClick = onDisconnect) { Text("断开") }
                // 服务端：显示客户端二维码按钮
                if (isServer && linkedClient != null && onShowClientQr != null) {
                    Button(
                        onClick = { onShowClientQr(linkedClient) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.QrCode, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("客户端二维码")
                    }
                }
                OutlinedButton(onClick = onViewConf) { Text("查看配置") }
                OutlinedButton(onClick = onEdit) { Text("编辑") }
                OutlinedButton(onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("删除") }
            }
        }
    }
}

@Composable
private fun WgInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}

private val MonoStyle @Composable get() =
    androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)

// ============================================================
// SERVER SETUP PAGE  服务端向导
// ============================================================
@Composable
private fun WgServerSetupPage(
    onBack: () -> Unit,
    onSaved: (serverCfg: WgConfig, clientCfg: WgConfig) -> Unit
) {
    var sName       by rememberSaveable { mutableStateOf("我的 WG 服务端") }
    var sPrivKey    by rememberSaveable { mutableStateOf(WireGuardKeyUtil.generatePrivateKey()) }
    var sVpnIp      by rememberSaveable { mutableStateOf("10.0.0.1") }
    var sVpnMask    by rememberSaveable { mutableStateOf("24") }
    var sPort       by rememberSaveable { mutableStateOf("51820") }
    var sNatEnabled by rememberSaveable { mutableStateOf(true) }
    var sMtu        by rememberSaveable { mutableStateOf("1420") }
    // 服务端公网 IP（内置到客户端 Endpoint）
    var sPublicIp   by rememberSaveable { mutableStateOf("") }
    // 服务端内网段（让客户端能访问服务端所在局域网）
    var sLanCidr    by rememberSaveable { mutableStateOf("") }
    // 客户端信息
    var cName       by rememberSaveable { mutableStateOf("客户端 1") }
    var cPrivKey    by rememberSaveable { mutableStateOf(WireGuardKeyUtil.generatePrivateKey()) }
    var cVpnIp      by rememberSaveable { mutableStateOf("10.0.0.2") }
    var cNatEnabled by rememberSaveable { mutableStateOf(false) }
    var cLanCidr    by rememberSaveable { mutableStateOf("") }
    var cMtu        by rememberSaveable { mutableStateOf("1420") }
    var cKeepalive  by rememberSaveable { mutableStateOf("25") }
    var psk         by rememberSaveable { mutableStateOf(WireGuardKeyUtil.generatePresharedKey()) }
    var sAllowedIPs by rememberSaveable { mutableStateOf("") }

    val sPubKey = remember(sPrivKey) {
        runCatching { WireGuardKeyUtil.publicKeyFromPrivate(sPrivKey) }.getOrDefault("(无效)")
    }
    val cPubKey = remember(cPrivKey) {
        runCatching { WireGuardKeyUtil.publicKeyFromPrivate(cPrivKey) }.getOrDefault("(无效)")
    }

    // 自动探测本机公网候选 IP
    val localIpv4List = remember { collectLocalIpv4ForWg() }

    LaunchedEffect(cVpnIp, sVpnMask, cLanCidr) {
        val vpnCidr = "$cVpnIp/32"
        sAllowedIPs = if (cLanCidr.isNotBlank()) "$vpnCidr,${cLanCidr.trim()}" else vpnCidr
    }

    // 客户端 AllowedIPs = VPN子网 + 服务端内网段（让客户端流量走隧道到达服务端内网）
    // 每当相关字段变化时重新计算
    var cAllowedIPsAuto by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(sVpnIp, sVpnMask, sLanCidr) {
        val mask = sVpnMask.toIntOrNull() ?: 24
        val vpnSubnet = "${sVpnIp.substringBeforeLast('.')}.0/$mask"
        cAllowedIPsAuto = if (sLanCidr.isNotBlank()) "$vpnSubnet,${sLanCidr.trim()}" else vpnSubnet
    }
    LaunchedEffect(sVpnIp) {
        val parts = sVpnIp.split(".")
        if (parts.size == 4) {
            cVpnIp = "${parts[0]}.${parts[1]}.${parts[2]}.${(parts[3].toIntOrNull()?.plus(1)) ?: 2}"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("返回")
        }
        Text("服务端配置向导", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("服务端通常是有公网 IP 的设备，或者通过端口转发暴露给外网的设备。",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // STEP 1
        WgSectionHeader("第一步：服务端本机设置", Icons.Default.Router)
        WgSetupCard {
            WgLabeledField("配置名称", "便于识别，例如：家庭服务器") {
                OutlinedTextField(value = sName, onValueChange = { sName = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            WgLabeledField("服务端 VPN IP", "此设备在 VPN 隧道中的 IP，通常为网段第一个地址") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = sVpnIp, onValueChange = { sVpnIp = it.trim() },
                        modifier = Modifier.weight(3f), singleLine = true, label = { Text("IP 地址") })
                    OutlinedTextField(value = sVpnMask,
                        onValueChange = { sVpnMask = it.filter(Char::isDigit).take(2) },
                        modifier = Modifier.weight(1f), singleLine = true,
                        label = { Text("掩码") }, prefix = { Text("/") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("10.0.0.1", "172.16.0.1", "192.168.200.1").forEach { preset ->
                        OutlinedButton(onClick = { sVpnIp = preset },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(preset, fontSize = 11.sp) }
                    }
                }
            }
            WgLabeledField("监听端口 (UDP)", "客户端将连接此端口，需在路由器/防火墙放行 UDP 此端口") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = sPort,
                        onValueChange = { sPort = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("51820", "51821", "4500").forEach { p ->
                        OutlinedButton(onClick = { sPort = p },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(p, fontSize = 11.sp) }
                    }
                }
            }
            WgLabeledField("MTU", "默认 1420，VPN 封包的最大传输单元") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = sMtu, onValueChange = { sMtu = it.filter(Char::isDigit).take(4) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("1420", "1380", "1280").forEach { m ->
                        OutlinedButton(onClick = { sMtu = m },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(m, fontSize = 11.sp) }
                    }
                }
            }
            WgLabeledField("私钥", "自动生成，无需修改。点击刷新图标重新生成。") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sPrivKey.take(32) + "…", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { sPrivKey = WireGuardKeyUtil.generatePrivateKey() }) {
                        Icon(Icons.Default.Refresh, "重新生成") }
                }
                Text("公钥（分享给客户端）：", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sPubKey, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("开启 IP 转发（NAT）", fontWeight = FontWeight.SemiBold)
                    Text("需要 root 权限。开启后自动执行 iptables MASQUERADE，客户端可访问服务端内网",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = sNatEnabled, onCheckedChange = { sNatEnabled = it })
            }
            // Root NAT 说明
            Surface(
                color = if (sNatEnabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp),
                        tint = if (sNatEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                               else MaterialTheme.colorScheme.onSurfaceVariant)
                    Column {
                        if (sNatEnabled) {
                            Text("将自动执行以下操作（需要 su/root）：",
                                fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(
                                "• sysctl -w net.ipv4.ip_forward=1\n" +
                                "• iptables -t nat -A POSTROUTING -s <vpn子网> -o <出口网卡> -j MASQUERADE\n" +
                                "• iptables -A FORWARD -s <vpn子网> -j ACCEPT\n" +
                                "断开 VPN 时自动清理上述规则。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            Text("未开启 NAT：客户端只能访问服务端 VPN IP，无法访问服务端内网其他设备。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // STEP 2: 服务端公网 IP（用于嵌入客户端 Endpoint）
        WgSectionHeader("第二步：填写服务端公网 IP", Icons.Default.Public)
        WgSetupCard {
            WgLabeledField("服务端公网 IP / 域名",
                "此 IP 将作为 Endpoint 内置到客户端二维码中，客户端扫码后无需手动填写") {
                OutlinedTextField(
                    value = sPublicIp,
                    onValueChange = { sPublicIp = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("如 1.2.3.4 或 myhome.ddns.net") }
                )
                if (localIpv4List.isNotEmpty()) {
                    Text("本机局域网 IP（仅局域网测试时使用）：", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        localIpv4List.take(3).forEach { ip ->
                            OutlinedButton(onClick = { sPublicIp = ip },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                Text(ip, fontSize = 11.sp) }
                        }
                    }
                }
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text("生成的客户端 Endpoint：${if (sPublicIp.isNotBlank()) "$sPublicIp:$sPort" else "(请先填写公网IP)"}」",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }

        // STEP 3: 客户端配置
        WgSectionHeader("第三步：为客户端生成配置", Icons.Default.PhoneAndroid)
        Text("填写后将生成客户端配置二维码，客户端扫码即可接入，无需手动填写公钥。",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WgSetupCard {
            WgLabeledField("客户端名称", "便于识别，例如：手机、笔记本") {
                OutlinedTextField(value = cName, onValueChange = { cName = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            WgLabeledField("客户端 VPN IP", "自动按服务端 IP 尾号 +1 填充") {
                OutlinedTextField(value = cVpnIp, onValueChange = { cVpnIp = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            WgLabeledField("客户端内网段（可选）",
                "若需要访问客户端所在的局域网，填写其网段（如 192.168.1.0/24）") {
                OutlinedTextField(value = cLanCidr, onValueChange = { cLanCidr = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("如 192.168.1.0/24，留空则仅 VPN 互通") })
            }
            WgLabeledField("服务端内网段（让客户端访问）",
                "客户端需要访问服务端哪个局域网？填写后自动加入客户端路由，如 192.168.39.0/24") {
                OutlinedTextField(value = sLanCidr, onValueChange = { sLanCidr = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("如 192.168.39.0/24，留空则仅 VPN 互通") })
                // 快捷按钮
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("192.168.0.0/24", "192.168.1.0/24", "192.168.39.0/24", "172.16.0.0/24").forEach { cidr ->
                        OutlinedButton(onClick = { sLanCidr = cidr },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(cidr, fontSize = 10.sp)
                        }
                    }
                }
                if (sLanCidr.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("客户端将能直接访问 $sLanCidr 内的所有设备",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
            WgLabeledField("MTU（客户端）", "客户端封包 MTU，默认 1420") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = cMtu, onValueChange = { cMtu = it.filter(Char::isDigit).take(4) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("1420", "1380", "1280").forEach { m ->
                        OutlinedButton(onClick = { cMtu = m },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(m, fontSize = 11.sp) }
                    }
                }
            }
            WgLabeledField("PersistentKeepalive",
                "保持 NAT 映射存活的心跳间隔（秒），默认 25，公网直连可设为 0 关闭") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = cKeepalive,
                        onValueChange = { cKeepalive = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("25", "15", "0").forEach { k ->
                        OutlinedButton(onClick = { cKeepalive = k },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(k, fontSize = 11.sp) }
                    }
                }
            }
            WgLabeledField("预共享密钥 (PresharedKey)",
                "可选，提供额外的对称加密层，两端必须一致。点击刷新随机生成。") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(psk.take(28) + "…", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { psk = WireGuardKeyUtil.generatePresharedKey() }) {
                        Icon(Icons.Default.Refresh, "重新生成") }
                    IconButton(onClick = { psk = "" }) {
                        Icon(Icons.Default.Clear, "清空") }
                }
                if (psk.isBlank()) Text("（已清空，不使用预共享密钥）", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // AllowedIPs 预览
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("服务端针对此客户端的 AllowedIPs（自动计算）：",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(sAllowedIPs.ifBlank { "$cVpnIp/32" }, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    Text("客户端路由（AllowedIPs，走 VPN 隧道的流量）：",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(cAllowedIPsAuto.ifBlank { "计算中…" }, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        OutlinedButton(onClick = { cAllowedIPsAuto = "0.0.0.0/0,::/0" },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("全部流量走 VPN", fontSize = 11.sp) }
                        OutlinedButton(onClick = {
                            val mask = sVpnMask.toIntOrNull() ?: 24
                            val vpnSubnet = "${sVpnIp.substringBeforeLast('.')}.0/$mask"
                            cAllowedIPsAuto = if (sLanCidr.isNotBlank()) "$vpnSubnet,${sLanCidr.trim()}" else vpnSubnet
                        }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("VPN + 服务端内网", fontSize = 11.sp) }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("客户端开启 IP 转发（NAT）", fontWeight = FontWeight.SemiBold)
                    Text("若客户端需要将其局域网共享给服务端，则开启",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = cNatEnabled, onCheckedChange = { cNatEnabled = it })
            }
        }

        // 保存并生成
        Button(
            onClick = {
                val mask     = sVpnMask.toIntOrNull() ?: 24
                val mtuS     = sMtu.toIntOrNull()    ?: 1420
                val mtuC     = cMtu.toIntOrNull()    ?: 1420
                val keepalive = cKeepalive.toIntOrNull() ?: 25
                val endpoint  = if (sPublicIp.isNotBlank()) "$sPublicIp:$sPort" else ""
                val vpnSubnet = "${sVpnIp.substringBeforeLast('.')}.0/$mask"
                val clientId  = java.util.UUID.randomUUID().toString()

                // 客户端 AllowedIPs：VPN子网 + 服务端内网段（核心路由配置）
                val finalCAllowedIPs = cAllowedIPsAuto.ifBlank {
                    if (sLanCidr.isNotBlank()) "$vpnSubnet,${sLanCidr.trim()}" else vpnSubnet
                }
                // 客户端配置（完整，含私钥，由服务端代为生成）
                val clientCfg = WgConfig(
                    id   = clientId,
                    name = "[C] $cName",
                    iface = WgInterface(
                        privateKey = cPrivKey, address = "$cVpnIp/$mask",
                        listenPort = 0, mtu = mtuC, natEnabled = cNatEnabled
                    ),
                    peers = listOf(WgPeer(
                        publicKey           = sPubKey,
                        presharedKey        = psk,
                        endpoint            = endpoint,
                        allowedIPs          = finalCAllowedIPs,
                        persistentKeepalive = keepalive,
                        label               = "[S] $sName"
                    ))
                )
                // 服务端配置，记录关联的客户端 ID
                val serverCfg = WgConfig(
                    id   = java.util.UUID.randomUUID().toString(),
                    name = "[S] $sName",
                    linkedClientId = clientId,
                    iface = WgInterface(
                        privateKey = sPrivKey, address = "$sVpnIp/$mask",
                        listenPort = sPort.toIntOrNull() ?: 51820, mtu = mtuS,
                        natEnabled = sNatEnabled
                    ),
                    peers = listOf(WgPeer(
                        publicKey           = cPubKey,
                        presharedKey        = psk,
                        endpoint            = "",
                        allowedIPs          = sAllowedIPs.ifBlank { "$cVpnIp/32" },
                        persistentKeepalive = keepalive,
                        label               = cName
                    ))
                )
                onSaved(serverCfg, clientCfg)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.QrCode, null); Spacer(Modifier.width(8.dp))
            Text("保存服务端配置并生成客户端二维码")
        }
    }
}

// ============================================================
// CLIENT SETUP PAGE  客户端向导
// ============================================================
@Composable
private fun WgClientSetupPage(
    onBack: () -> Unit,
    onSaved: (WgConfig) -> Unit
) {
    val context = LocalContext.current
    var cName       by rememberSaveable { mutableStateOf("我的手机") }
    var cPrivKey    by rememberSaveable { mutableStateOf(WireGuardKeyUtil.generatePrivateKey()) }
    var cVpnIp      by rememberSaveable { mutableStateOf("10.0.0.2") }
    var cVpnMask    by rememberSaveable { mutableStateOf("24") }
    var cNatEnabled by rememberSaveable { mutableStateOf(false) }
    var cMtu        by rememberSaveable { mutableStateOf("1420") }
    var cKeepalive  by rememberSaveable { mutableStateOf("25") }
    var sPubKey     by rememberSaveable { mutableStateOf("") }
    var sEndpoint   by rememberSaveable { mutableStateOf("") }
    var sLanCidr    by rememberSaveable { mutableStateOf("") }
    var psk         by rememberSaveable { mutableStateOf("") }
    var cAllowedIPs by rememberSaveable { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }

    val cPubKey = remember(cPrivKey) {
        runCatching { WireGuardKeyUtil.publicKeyFromPrivate(cPrivKey) }.getOrDefault("(无效)")
    }
    val localIpv4List = remember { collectLocalIpv4ForWg() }
    val localIpv6List = remember { collectLocalIpv6ForWg() }

    LaunchedEffect(cVpnIp, cVpnMask, sLanCidr) {
        val mask = cVpnMask.toIntOrNull() ?: 24
        val vpnSubnet = cVpnIp.substringBeforeLast('.') + ".0/$mask"
        cAllowedIPs = if (sLanCidr.isNotBlank()) "$vpnSubnet,${sLanCidr.trim()}" else vpnSubnet
    }

    if (showScanner) {
        QRCodeScanner(
            onQRCodeScanned = { raw ->
                showScanner = false
                // 优先尝试解析完整 wg-quick conf（标准格式，与官方 WireGuard App 互通）
                val parsed = WgConfig.fromScanContent(raw)
                if (parsed != null && parsed.iface.privateKey.isNotBlank()) {
                    // 完整配置：填充所有字段
                    cPrivKey    = parsed.iface.privateKey
                    cVpnIp      = parsed.iface.address.substringBefore('/')
                    cVpnMask    = parsed.iface.address.substringAfterLast('/').trim().takeIf { it.isNotBlank() } ?: "24"
                    cMtu        = if (parsed.iface.mtu > 0) parsed.iface.mtu.toString() else "1420"
                    val p = parsed.peers.firstOrNull()
                    if (p != null) {
                        sPubKey     = p.publicKey
                        sEndpoint   = p.endpoint
                        psk         = p.presharedKey
                        cKeepalive  = if (p.persistentKeepalive > 0) p.persistentKeepalive.toString() else "25"
                        cAllowedIPs = p.allowedIPs
                    }
                } else {
                    // 旧格式：仅含 pubkey 的 JSON 分享（向下兼容）
                    val peer = WgConfig.peerFromShareJson(raw)
                    if (peer != null) sPubKey = peer.publicKey
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
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("返回")
        }
        Text("客户端配置向导", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("客户端通过服务端公网地址发起连接，服务端需有公网 IP 或端口转发。",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        WgSectionHeader("第一步：本机（客户端）设置", Icons.Default.PhoneAndroid)
        WgSetupCard {
            WgLabeledField("设备名称", "便于识别，例如：我的手机") {
                OutlinedTextField(value = cName, onValueChange = { cName = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            WgLabeledField("本机 VPN IP", "在 VPN 隧道中的 IP，需与服务端分配的保持一致") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = cVpnIp, onValueChange = { cVpnIp = it.trim() },
                        modifier = Modifier.weight(3f), singleLine = true, label = { Text("IP") })
                    OutlinedTextField(value = cVpnMask,
                        onValueChange = { cVpnMask = it.filter(Char::isDigit).take(2) },
                        modifier = Modifier.weight(1f), singleLine = true,
                        label = { Text("掩码") }, prefix = { Text("/") })
                }
            }
            WgLabeledField("MTU", "默认 1420") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = cMtu, onValueChange = { cMtu = it.filter(Char::isDigit).take(4) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("1420", "1380", "1280").forEach { m ->
                        OutlinedButton(onClick = { cMtu = m },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(m, fontSize = 11.sp) }
                    }
                }
            }
            WgLabeledField("私钥", "自动生成，将公钥分享给服务端管理员。") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cPrivKey.take(32) + "…", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { cPrivKey = WireGuardKeyUtil.generatePrivateKey() }) {
                        Icon(Icons.Default.Refresh, "重新生成") }
                }
                Text("我的公钥（发给服务端管理员）：", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(cPubKey, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("开启 IP 转发（NAT）", fontWeight = FontWeight.SemiBold)
                    Text("若需要将本机局域网共享给服务端，则开启",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = cNatEnabled, onCheckedChange = { cNatEnabled = it })
            }
        }

        WgSectionHeader("第二步：填写服务端信息", Icons.Default.Router)
        WgSetupCard {
            WgLabeledField("服务端公钥", "向服务端管理员索取，或扫码自动填入") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = sPubKey, onValueChange = { sPubKey = it.trim() },
                        modifier = Modifier.weight(1f), singleLine = true,
                        textStyle = MonoStyle,
                        isError = sPubKey.isNotBlank() && !WireGuardKeyUtil.isValid(sPubKey),
                        placeholder = { Text("Base64 公钥…") }
                    )
                    OutlinedButton(onClick = { showScanner = true }) { Icon(Icons.Default.QrCode, null) }
                }
            }
            WgLabeledField("服务端地址和端口", "服务端的公网 IP（或域名）+ UDP 端口") {
                OutlinedTextField(
                    value = sEndpoint, onValueChange = { sEndpoint = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("如 1.2.3.4:51820 或 [2001:db8::1]:51820") }
                )
                if (localIpv6List.isNotEmpty()) {
                    Text("本机 IPv6（局域网测试）：", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        localIpv6List.take(3).forEach { ip ->
                            OutlinedButton(onClick = { sEndpoint = "[$ip]:51820" },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                Text(ip.take(20), fontSize = 10.sp) }
                        }
                    }
                }
                if (localIpv4List.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        localIpv4List.take(3).forEach { ip ->
                            OutlinedButton(onClick = { sEndpoint = "$ip:51820" },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                Text(ip, fontSize = 10.sp) }
                        }
                    }
                }
            }
            WgLabeledField("服务端内网段（可选）", "若需访问服务端局域网，填写其网段") {
                OutlinedTextField(
                    value = sLanCidr, onValueChange = { sLanCidr = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("如 192.168.0.0/24，留空则仅 VPN 互通") }
                )
            }
            WgLabeledField("PersistentKeepalive", "保持 NAT 映射存活，默认 25") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = cKeepalive,
                        onValueChange = { cKeepalive = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("25", "15", "0").forEach { k ->
                        OutlinedButton(onClick = { cKeepalive = k },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(k, fontSize = 11.sp) }
                    }
                }
            }
            WgLabeledField("预共享密钥 (PresharedKey)",
                "可选，提供额外对称加密，两端必须一致。留空表示不使用。") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = psk, onValueChange = { psk = it.trim() },
                        modifier = Modifier.weight(1f), singleLine = true,
                        textStyle = MonoStyle,
                        placeholder = { Text("Base64 预共享密钥，留空不使用") })
                    IconButton(onClick = { psk = WireGuardKeyUtil.generatePresharedKey() }) {
                        Icon(Icons.Default.Refresh, "随机生成") }
                    IconButton(onClick = { psk = "" }) {
                        Icon(Icons.Default.Clear, "清空") }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text("AllowedIPs（自动计算，表示哪些流量走 VPN）：",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(cAllowedIPs.ifBlank { "计算中…" }, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedButton(onClick = { cAllowedIPs = "0.0.0.0/0,::/0" },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("全部流量走 VPN", fontSize = 11.sp) }
                        OutlinedButton(onClick = {
                            val mask = cVpnMask.toIntOrNull() ?: 24
                            cAllowedIPs = cVpnIp.substringBeforeLast('.') + ".0/$mask"
                            if (sLanCidr.isNotBlank()) cAllowedIPs += ",${sLanCidr.trim()}"
                        }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("仅 VPN 内网", fontSize = 11.sp) }
                    }
                }
            }
        }

        Button(
            onClick = {
                val mask      = cVpnMask.toIntOrNull() ?: 24
                val mtu       = cMtu.toIntOrNull()    ?: 1420
                val keepalive = cKeepalive.toIntOrNull() ?: 25
                val cfg = WgConfig(
                    id   = java.util.UUID.randomUUID().toString(),
                    name = "[C] $cName",
                    iface = WgInterface(
                        privateKey = cPrivKey, address = "$cVpnIp/$mask",
                        listenPort = 0, mtu = mtu
                    ),
                    peers = listOf(WgPeer(
                        publicKey           = sPubKey.trim(),
                        presharedKey        = psk.trim(),
                        endpoint            = sEndpoint.trim(),
                        allowedIPs          = cAllowedIPs.ifBlank { "0.0.0.0/0" },
                        persistentKeepalive = keepalive,
                        label               = "服务端"
                    ))
                )
                onSaved(cfg)
            },
            enabled = sPubKey.isNotBlank() && WireGuardKeyUtil.isValid(sPubKey),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("保存客户端配置")
        }
    }
}

// ============================================================
// CLIENT QR PAGE  展示客户端配置二维码 + GUI编辑 + conf编辑
// ============================================================
@Composable
private fun WgClientQrPage(
    clientConfig: WgConfig?,
    onBack: () -> Unit,
    onEditGui: (WgConfig) -> Unit,
    onSaved: (WgConfig) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    if (clientConfig == null) { onBack(); return }

    // 允许直接在此页编辑 conf 文本
    var currentCfg   by remember { mutableStateOf(clientConfig) }
    var confText     by remember(currentCfg) { mutableStateOf(currentCfg.toWgQuickConf()) }
    var editingConf  by remember { mutableStateOf(false) }
    var editConfText by remember { mutableStateOf(confText) }
    var tabIndex     by remember { mutableStateOf(0) }  // 0=二维码, 1=配置文本, 2=直接编辑conf

    val qrBitmap = remember(confText) { QRCodeGenerator.generateQRCode(confText, 700) }

    LaunchedEffect(clientConfig.id) { WireGuardConfigStore.save(context, clientConfig) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Check, null); Spacer(Modifier.width(8.dp)); Text("完成，返回主页")
        }
        Text("客户端配置", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        // 使用说明卡
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("如何使用此配置", fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                listOf(
                    "用客户端设备扫描二维码（FileTran 客户端向导 > 扫码，或标准 WireGuard App）",
                    "服务端公网 IP 已内置到 Endpoint 中，扫码即可直接连接",
                    "也可点击【编辑配置】修改参数，或切换到【编辑 conf】直接修改文本"
                ).forEach { tip ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("•", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(tip, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }

        // Tab 切换
        TabRow(selectedTabIndex = tabIndex) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 },
                text = { Text("二维码") },
                icon = { Icon(Icons.Default.QrCode, null, modifier = Modifier.size(18.dp)) })
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 },
                text = { Text("配置文本") },
                icon = { Icon(Icons.Default.Description, null, modifier = Modifier.size(18.dp)) })
            Tab(selected = tabIndex == 2, onClick = { tabIndex = 2; editConfText = confText },
                text = { Text("编辑 conf") },
                icon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp)) })
        }

        when (tabIndex) {
            // ---- Tab 0: 二维码 ----
            0 -> {
                if (qrBitmap != null) {
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "客户端二维码",
                            modifier = Modifier.fillMaxWidth().padding(16.dp))
                    }
                } else {
                    Text("二维码生成失败", color = MaterialTheme.colorScheme.error)
                }
                // 快速信息
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("配置摘要", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        WgInfoRow("名称", currentCfg.name)
                        WgInfoRow("VPN IP", currentCfg.iface.address)
                        WgInfoRow("MTU", currentCfg.iface.mtu.toString())
                        currentCfg.peers.firstOrNull()?.let { p ->
                            WgInfoRow("Endpoint", p.endpoint.ifBlank { "(未设置)" })
                            WgInfoRow("AllowedIPs", p.allowedIPs)
                            WgInfoRow("Keepalive", p.persistentKeepalive.toString())
                            WgInfoRow("PSK", if (p.presharedKey.isNotBlank()) "已设置" else "未设置")
                        }
                    }
                }
                // 编辑按钮
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { onEditGui(currentCfg) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Edit, null); Spacer(Modifier.width(4.dp)); Text("GUI 编辑")
                    }
                    OutlinedButton(onClick = { tabIndex = 2; editConfText = confText }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Code, null); Spacer(Modifier.width(4.dp)); Text("编辑 conf")
                    }
                }
                Text("注意：此配置含私钥，请妥善保管，不要分享给不信任的人。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            // ---- Tab 1: 配置文本（只读+复制）----
            1 -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(confText)) }) {
                        Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(4.dp)); Text("复制")
                    }
                }
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(confText, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(14.dp))
                }
                Text("注意：此配置含私钥，请妥善保管。", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            // ---- Tab 2: 直接编辑 conf 文本 ----
            2 -> {
                Text("直接编辑 wg-quick 格式配置文本，修改后点击保存。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = editConfText,
                    onValueChange = { editConfText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                    textStyle = MonoStyle,
                    label = { Text("wg-quick conf") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { editConfText = confText; tabIndex = 1 },
                        modifier = Modifier.weight(1f)) { Text("重置") }
                    Button(
                        onClick = {
                            // 解析编辑后的 conf 文本，更新配置
                            val parsed = WgConfig.fromScanContent(editConfText, currentCfg.id, currentCfg.name)
                            if (parsed != null) {
                                currentCfg = parsed
                                confText   = editConfText
                                onSaved(parsed)
                                tabIndex   = 0
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, null); Spacer(Modifier.width(4.dp)); Text("保存")
                    }
                }
            }
        }
    }
}

// ============================================================
// EDIT CLIENT CFG PAGE  GUI 编辑客户端配置
// ============================================================
@Composable
private fun WgEditClientCfgPage(
    config: WgConfig?,
    onBack: () -> Unit,
    onSaved: (WgConfig) -> Unit
) {
    if (config == null) { onBack(); return }

    val peer0 = config.peers.firstOrNull() ?: WgPeer()
    var cfgName     by rememberSaveable { mutableStateOf(config.name) }
    var privKey     by rememberSaveable { mutableStateOf(config.iface.privateKey) }
    var address     by rememberSaveable { mutableStateOf(config.iface.address) }
    var mtu         by rememberSaveable { mutableStateOf(config.iface.mtu.toString()) }
    var listenPort  by rememberSaveable { mutableStateOf(if (config.iface.listenPort > 0) config.iface.listenPort.toString() else "") }
    var peerPubKey  by rememberSaveable { mutableStateOf(peer0.publicKey) }
    var peerPsk     by rememberSaveable { mutableStateOf(peer0.presharedKey) }
    var peerEndpoint by rememberSaveable { mutableStateOf(peer0.endpoint) }
    var peerAllowedIPs by rememberSaveable { mutableStateOf(peer0.allowedIPs) }
    var peerKeepalive  by rememberSaveable { mutableStateOf(peer0.persistentKeepalive.toString()) }

    val pubKey = remember(privKey) {
        runCatching { WireGuardKeyUtil.publicKeyFromPrivate(privKey) }.getOrDefault("(无效)")
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("返回")
        }
        Text("编辑配置", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        WgSectionHeader("Interface（本机）", Icons.Default.PhoneAndroid)
        WgSetupCard {
            WgLabeledField("配置名称", "") {
                OutlinedTextField(value = cfgName, onValueChange = { cfgName = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            WgLabeledField("Address（VPN IP/掩码）", "如 10.0.0.2/24") {
                OutlinedTextField(value = address, onValueChange = { address = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            WgLabeledField("MTU", "默认 1420") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = mtu, onValueChange = { mtu = it.filter(Char::isDigit).take(4) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("1420", "1380", "1280").forEach { m ->
                        OutlinedButton(onClick = { mtu = m },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(m, fontSize = 11.sp) }
                    }
                }
            }
            WgLabeledField("ListenPort", "服务端填写，客户端留空") {
                OutlinedTextField(value = listenPort,
                    onValueChange = { listenPort = it.filter(Char::isDigit).take(5) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("留空=不监听（客户端模式）") })
            }
            WgLabeledField("私钥 (PrivateKey)", "点击刷新重新生成") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(privKey.take(32) + "…", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { privKey = WireGuardKeyUtil.generatePrivateKey() }) {
                        Icon(Icons.Default.Refresh, "重新生成") }
                }
                Text("公钥：$pubKey", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        WgSectionHeader("Peer（对端）", Icons.Default.Router)
        WgSetupCard {
            WgLabeledField("PublicKey（对端公钥）", "") {
                OutlinedTextField(value = peerPubKey, onValueChange = { peerPubKey = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = MonoStyle,
                    isError = peerPubKey.isNotBlank() && !WireGuardKeyUtil.isValid(peerPubKey))
            }
            WgLabeledField("Endpoint（对端地址:端口）", "如 1.2.3.4:51820") {
                OutlinedTextField(value = peerEndpoint, onValueChange = { peerEndpoint = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("host:port") })
            }
            WgLabeledField("AllowedIPs", "哪些流量走 VPN") {
                OutlinedTextField(value = peerAllowedIPs, onValueChange = { peerAllowedIPs = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedButton(onClick = { peerAllowedIPs = "0.0.0.0/0,::/0" },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("全部流量", fontSize = 11.sp) }
                    OutlinedButton(onClick = {
                        val base = address.substringBefore('/').substringBeforeLast('.')
                        val mask = address.substringAfter('/').toIntOrNull() ?: 24
                        peerAllowedIPs = "$base.0/$mask"
                    }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("仅 VPN 内网", fontSize = 11.sp) }
                }
            }
            WgLabeledField("PersistentKeepalive", "保持 NAT 映射，默认 25") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = peerKeepalive,
                        onValueChange = { peerKeepalive = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    listOf("25", "15", "0").forEach { k ->
                        OutlinedButton(onClick = { peerKeepalive = k },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(k, fontSize = 11.sp) }
                    }
                }
            }
            WgLabeledField("PresharedKey（预共享密钥）", "可选，两端必须一致") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = peerPsk, onValueChange = { peerPsk = it.trim() },
                        modifier = Modifier.weight(1f), singleLine = true, textStyle = MonoStyle,
                        placeholder = { Text("留空=不使用") })
                    IconButton(onClick = { peerPsk = WireGuardKeyUtil.generatePresharedKey() }) {
                        Icon(Icons.Default.Refresh, "随机生成") }
                    IconButton(onClick = { peerPsk = "" }) {
                        Icon(Icons.Default.Clear, "清空") }
                }
            }
        }

        Button(
            onClick = {
                val updated = config.copy(
                    name  = cfgName,
                    iface = config.iface.copy(
                        privateKey = privKey, address = address,
                        mtu = mtu.toIntOrNull() ?: 1420,
                        listenPort = listenPort.toIntOrNull() ?: 0
                    ),
                    peers = listOf(WgPeer(
                        publicKey           = peerPubKey,
                        presharedKey        = peerPsk,
                        endpoint            = peerEndpoint,
                        allowedIPs          = peerAllowedIPs,
                        persistentKeepalive = peerKeepalive.toIntOrNull() ?: 25,
                        label               = peer0.label
                    ))
                )
                onSaved(updated)
            },
            enabled = peerPubKey.isBlank() || WireGuardKeyUtil.isValid(peerPubKey),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("保存修改")
        }
    }
}

// ============================================================
// VIEW CONF PAGE
// ============================================================
@Composable
private fun WgViewConfPage(config: WgConfig?, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    if (config == null) { onBack(); return }
    val confText = remember(config) { config.toWgQuickConf() }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("返回")
        }
        Text("wg-quick 配置", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("可直接用于 Linux/macOS/Windows wg-quick，与标准 WireGuard 完全互通。",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(confText)) }) {
                Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(4.dp)); Text("复制")
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()) {
            Text(confText, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(16.dp))
        }
    }
}

// ============================================================
// 通用 UI 辅助
// ============================================================
@Composable
private fun WgSectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun WgSetupCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun WgLabeledField(label: String, hint: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (hint.isNotBlank()) Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

// parseWgQuickConf 已移至 WgConfig.fromScanContent()

// ============================================================
// 网络工具
// ============================================================
private fun collectLocalIpv4ForWg(): List<String> {
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

private fun collectLocalIpv6ForWg(): List<String> {
    val out = mutableListOf<String>()
    runCatching {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
        while (ifaces.hasMoreElements()) {
            val itf = ifaces.nextElement()
            if (!itf.isUp || itf.isLoopback) continue
            val addrs = itf.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a is Inet6Address && !a.isLoopbackAddress) {
                    val ip = a.hostAddress.orEmpty().substringBefore('%')
                    if (ip.isNotBlank()) out += ip
                }
            }
        }
    }
    return out
}

// ============================================================
// VPN 启停
// ============================================================
private fun startWgVpn(context: Context, config: WgConfig) {
    val i = Intent(context, WireGuardVpnService::class.java).apply {
        action = WireGuardVpnService.ACTION_START
        putExtra(WireGuardVpnService.EXTRA_CONFIG_ID, config.id)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
    else context.startService(i)
}

private fun stopWgVpn(context: Context) {
    context.startService(Intent(context, WireGuardVpnService::class.java).apply {
        action = WireGuardVpnService.ACTION_STOP
    })
}
  