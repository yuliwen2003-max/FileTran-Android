package com.yuliwen.filetran

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CLIPBOARD_SYNC_PAGE_DEFAULT_PORT = 19333
private const val CLIPBOARD_SYNC_UI_PREFS = "clipboard_sync_ui_prefs"
private const val CLIPBOARD_SYNC_UI_KEY_USE_IPV6 = "clipboard_sync_ui_use_ipv6"

@Composable
fun ClipboardSyncPage(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtime by ClipboardSyncRuntime.stateFlow.collectAsState()
    val persistedConfig = remember(context) { ClipboardSyncService.readPersistedConfig(context) }
    val uiPrefs = remember(context) {
        context.getSharedPreferences(CLIPBOARD_SYNC_UI_PREFS, Context.MODE_PRIVATE)
    }

    var useIpv6 by rememberSaveable {
        mutableStateOf(uiPrefs.getBoolean(CLIPBOARD_SYNC_UI_KEY_USE_IPV6, false))
    }
    var localHostInput by rememberSaveable {
        mutableStateOf(
            runtime.localHost.ifBlank { persistedConfig.localHost }
        )
    }
    var localPortInput by rememberSaveable {
        mutableStateOf(
            runtime.localPort
                .takeIf { it in 1..65535 }
                ?.toString()
                ?: persistedConfig.localPort.takeIf { it in 1..65535 }?.toString()
                ?: CLIPBOARD_SYNC_PAGE_DEFAULT_PORT.toString()
        )
    }
    var peerHostInput by rememberSaveable {
        mutableStateOf(runtime.peerHost.ifBlank { persistedConfig.peerHost })
    }
    var peerPortInput by rememberSaveable {
        mutableStateOf(
            runtime.peerPort
                .takeIf { it in 1..65535 }
                ?.toString()
                ?: persistedConfig.peerPort.takeIf { it in 1..65535 }?.toString()
                ?: ""
        )
    }
    var reconnectMode by rememberSaveable { mutableStateOf(persistedConfig.reconnectMode) }
    var reconnectLimitInput by rememberSaveable {
        mutableStateOf(persistedConfig.reconnectLimit.takeIf { it > 0 }?.toString() ?: "30")
    }
    var focusOverlayEnabled by rememberSaveable { mutableStateOf(persistedConfig.focusOverlayEnabled) }
    var activityProbeEnabled by rememberSaveable { mutableStateOf(persistedConfig.activityProbeEnabled) }
    var activityProbeIntervalInput by rememberSaveable {
        mutableStateOf(persistedConfig.activityProbeIntervalMs.toString())
    }
    var quickProbeMagnetEnabled by rememberSaveable { mutableStateOf(persistedConfig.quickProbeMagnetEnabled) }
    var quickProbeMagnetSizeDp by rememberSaveable {
        mutableStateOf(persistedConfig.quickProbeMagnetSizeDp.toFloat())
    }
    var quickProbeMagnetAlpha by rememberSaveable { mutableStateOf(persistedConfig.quickProbeMagnetAlpha) }
    var localQrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var localStatusMessage by remember { mutableStateOf<String?>(null) }

    fun refreshLocalHost() {
        scope.launch {
            val host = withContext(Dispatchers.IO) {
                if (useIpv6) {
                    NetworkUtils.getLocalGlobalIpv6Address().orEmpty()
                } else {
                    NetworkUtils.getLocalIpAddress(context).orEmpty()
                }
            }
            localHostInput = host
            localStatusMessage = if (host.isBlank()) {
                if (useIpv6) "未获取到可用 IPv6，请确认网络。"
                else "未获取到可用 IPv4，请确认网络。"
            } else {
                "已刷新本机地址：$host"
            }
        }
    }

    LaunchedEffect(Unit) {
        if (runtime.running) {
            if (runtime.localHost.isNotBlank() && localHostInput.isBlank()) localHostInput = runtime.localHost
            if (runtime.peerHost.isNotBlank() && peerHostInput.isBlank()) peerHostInput = runtime.peerHost
            if (runtime.peerPort in 1..65535 && peerPortInput.isBlank()) peerPortInput = runtime.peerPort.toString()
            reconnectMode = runtime.autoReconnectMode
            reconnectLimitInput = runtime.autoReconnectLimit.takeIf { it > 0 }?.toString() ?: reconnectLimitInput
            focusOverlayEnabled = runtime.focusOverlayEnabled
            activityProbeEnabled = runtime.activityProbeEnabled
            activityProbeIntervalInput = runtime.activityProbeIntervalMs.toString()
            quickProbeMagnetEnabled = runtime.quickProbeMagnetEnabled
            quickProbeMagnetSizeDp = runtime.quickProbeMagnetSizeDp.toFloat()
            quickProbeMagnetAlpha = runtime.quickProbeMagnetAlpha
        } else if (localHostInput.isBlank()) {
            refreshLocalHost()
        }
    }

    LaunchedEffect(useIpv6) {
        uiPrefs.edit().putBoolean(CLIPBOARD_SYNC_UI_KEY_USE_IPV6, useIpv6).apply()
    }

    LaunchedEffect(
        localHostInput,
        localPortInput,
        peerHostInput,
        peerPortInput,
        reconnectMode,
        reconnectLimitInput,
        focusOverlayEnabled,
        activityProbeEnabled,
        activityProbeIntervalInput,
        quickProbeMagnetEnabled,
        quickProbeMagnetSizeDp,
        quickProbeMagnetAlpha
    ) {
        val localPort = localPortInput.toIntOrNull()?.coerceIn(1, 65535) ?: CLIPBOARD_SYNC_PAGE_DEFAULT_PORT
        val peerPort = peerPortInput.toIntOrNull()?.coerceIn(0, 65535) ?: 0
        val reconnectLimit = reconnectLimitInput.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val probeInterval = activityProbeIntervalInput.toIntOrNull()?.coerceIn(400, 15_000) ?: 1000
        ClipboardSyncService.saveDraftConfig(
            context = context,
            config = ClipboardSyncConfig(
                localHost = localHostInput.trim(),
                localPort = localPort,
                peerHost = peerHostInput.trim(),
                peerPort = peerPort,
                reconnectMode = reconnectMode,
                reconnectLimit = reconnectLimit,
                focusOverlayEnabled = focusOverlayEnabled,
                activityProbeEnabled = activityProbeEnabled,
                activityProbeIntervalMs = probeInterval,
                quickProbeMagnetEnabled = quickProbeMagnetEnabled,
                quickProbeMagnetSizeDp = quickProbeMagnetSizeDp.toInt().coerceIn(36, 120),
                quickProbeMagnetAlpha = quickProbeMagnetAlpha.coerceIn(0.2f, 1.0f)
            )
        )
    }

    LaunchedEffect(localHostInput, localPortInput) {
        val port = localPortInput.toIntOrNull()
        localQrBitmap = if (localHostInput.isBlank() || port == null || port !in 1..65535) {
            null
        } else {
            QRCodeGenerator.generateQRCode(
                buildClipboardSyncSharePayload(localHostInput.trim(), port),
                512
            )
        }
    }

    if (showScanner) {
        QRCodeScanner(
            onQRCodeScanned = { content ->
                showScanner = false
                val parsed = parseClipboardSyncPeerPayload(content)
                if (parsed == null) {
                    localStatusMessage = "二维码无法识别为剪贴板共享地址。"
                } else {
                    peerHostInput = parsed.first
                    peerPortInput = parsed.second.toString()
                    localStatusMessage = "已识别对端：${parsed.first}:${parsed.second}"
                }
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
            Text("返回发送页")
        }
        Text("剪贴板共享", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "双方交换地址并协商后，会实时同步剪贴板文本。页面可退出，前台服务会继续运行。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val overlayPermissionGranted = if (runtime.running) {
            runtime.overlayPermissionGranted
        } else {
            canDrawOverlayPermission(context)
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("1. 本机信息", fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { useIpv6 = false; refreshLocalHost() },
                        modifier = Modifier.weight(1f)
                    ) { Text("使用 IPv4") }
                    OutlinedButton(
                        onClick = { useIpv6 = true; refreshLocalHost() },
                        modifier = Modifier.weight(1f)
                    ) { Text("使用 IPv6") }
                }
                OutlinedTextField(
                    value = localHostInput,
                    onValueChange = { localHostInput = it.trim() },
                    label = { Text("本机地址") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = localPortInput,
                    onValueChange = { localPortInput = it.filter(Char::isDigit) },
                    label = { Text("本机端口（默认 19333）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { refreshLocalHost() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("刷新本机地址")
                    }
                    OutlinedButton(
                        onClick = {
                            val port = localPortInput.toIntOrNull()
                            if (localHostInput.isBlank() || port == null || port !in 1..65535) {
                                localStatusMessage = "请先填写有效的本机地址和端口。"
                                return@OutlinedButton
                            }
                            val payload = buildClipboardSyncSharePayload(localHostInput.trim(), port)
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("clipboard_sync_addr", payload))
                            localStatusMessage = "已复制本机共享信息。"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("复制共享信息")
                    }
                }
                localQrBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "clipboard_sync_qr",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("2. 对端信息", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = peerHostInput,
                    onValueChange = { peerHostInput = it.trim() },
                    label = { Text("对端地址（可留空，仅被动等待）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = peerPortInput,
                    onValueChange = { peerPortInput = it.filter(Char::isDigit) },
                    label = { Text("对端端口") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showScanner = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.QrCode, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("扫码识别")
                    }
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                                .orEmpty()
                            val parsed = parseClipboardSyncPeerPayload(text)
                            if (parsed == null) {
                                localStatusMessage = if (text.isBlank()) "剪贴板为空。" else "剪贴板内容无法识别。"
                            } else {
                                peerHostInput = parsed.first
                                peerPortInput = parsed.second.toString()
                                localStatusMessage = "已从剪贴板识别对端地址。"
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("读剪贴板识别")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("3. 自动重连策略", fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReconnectOptionButton(
                        label = "一直重连",
                        selected = reconnectMode == ClipboardSyncReconnectMode.ALWAYS,
                        onClick = { reconnectMode = ClipboardSyncReconnectMode.ALWAYS },
                        modifier = Modifier.weight(1f)
                    )
                    ReconnectOptionButton(
                        label = "按次数",
                        selected = reconnectMode == ClipboardSyncReconnectMode.ATTEMPTS,
                        onClick = { reconnectMode = ClipboardSyncReconnectMode.ATTEMPTS },
                        modifier = Modifier.weight(1f)
                    )
                    ReconnectOptionButton(
                        label = "按时长",
                        selected = reconnectMode == ClipboardSyncReconnectMode.DURATION,
                        onClick = { reconnectMode = ClipboardSyncReconnectMode.DURATION },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (reconnectMode != ClipboardSyncReconnectMode.ALWAYS) {
                    OutlinedTextField(
                        value = reconnectLimitInput,
                        onValueChange = { reconnectLimitInput = it.filter(Char::isDigit) },
                        label = {
                            Text(
                                if (reconnectMode == ClipboardSyncReconnectMode.ATTEMPTS) {
                                    "重连次数上限"
                                } else {
                                    "重连时长上限（分钟）"
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("4. 后台剪贴板读取增强", fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("焦点悬浮窗增强")
                        Text(
                            "通过 1x1 焦点悬浮窗 + 前台服务，尝试提升 Android 10+ 后台剪贴板可读性。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = focusOverlayEnabled,
                        onCheckedChange = { focusOverlayEnabled = it }
                    )
                }
                Text(
                    if (overlayPermissionGranted) "悬浮窗权限：已授权" else "悬浮窗权限：未授权（增强模式不可用）",
                    fontSize = 12.sp,
                    color = if (overlayPermissionGranted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                if (!overlayPermissionGranted) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { context.startActivity(intent) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("打开悬浮窗权限设置")
                    }
                }
                if (runtime.running) {
                    Text(
                        if (runtime.focusOverlayActive) {
                            "焦点悬浮窗状态：运行中"
                        } else {
                            "焦点悬浮窗状态：未启用或未生效"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("5. 透明悬浮窗定时检测", fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("后台悬浮窗定时检测")
                        Text(
                            "周期性创建 1x1 透明悬浮窗读取剪贴板，读取后立即关闭，默认 1000ms，可自定义。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = activityProbeEnabled,
                        onCheckedChange = { activityProbeEnabled = it }
                    )
                }
                if (focusOverlayEnabled && overlayPermissionGranted) {
                    Text(
                        "已启用焦点悬浮窗：为避免干扰前台应用，透明悬浮窗定时探测会自动暂停（仅悬浮窗增强不可用时才回退）。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (activityProbeEnabled) {
                    OutlinedTextField(
                        value = activityProbeIntervalInput,
                        onValueChange = { activityProbeIntervalInput = it.filter(Char::isDigit) },
                        label = { Text("检测间隔（毫秒，400-15000）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                Text(
                    "该模式依赖悬浮窗权限（SYSTEM_ALERT_WINDOW），请在系统权限中放行。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("打开应用权限设置（悬浮窗）")
                }
                if (runtime.running) {
                    Text(
                        if (runtime.activityProbeRunning) {
                            "透明悬浮窗探测：运行中（${runtime.activityProbeIntervalMs}ms）"
                        } else if (runtime.activityProbeEnabled && runtime.focusOverlayActive) {
                            "透明悬浮窗探测：已暂停（焦点悬浮窗优先）"
                        } else {
                            "透明悬浮窗探测：未运行"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("任务栏磁铁快捷")
                        Text(
                            "显示悬浮磁铁，点击后立即执行一次透明悬浮窗识别；通知栏也会提供“立即识别”按钮。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = quickProbeMagnetEnabled,
                        onCheckedChange = { quickProbeMagnetEnabled = it }
                    )
                }
                if (runtime.running) {
                    Text(
                        if (runtime.quickProbeMagnetActive) "磁铁快捷：已显示" else "磁铁快捷：未显示",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (quickProbeMagnetEnabled) {
                    Text(
                        "磁铁大小：${quickProbeMagnetSizeDp.toInt()}dp",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = quickProbeMagnetSizeDp,
                        onValueChange = { quickProbeMagnetSizeDp = it },
                        valueRange = 36f..120f
                    )
                    Text(
                        "磁铁透明度：${(quickProbeMagnetAlpha * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = quickProbeMagnetAlpha,
                        onValueChange = { quickProbeMagnetAlpha = it },
                        valueRange = 0.2f..1.0f
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val localPort = localPortInput.toIntOrNull()
                    if (localPort == null || localPort !in 1..65535 || localHostInput.isBlank()) {
                        localStatusMessage = "请填写有效的本机地址与端口。"
                        return@Button
                    }
                    val peerPort = peerPortInput.toIntOrNull() ?: 0
                    val limit = reconnectLimitInput.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val probeIntervalMs = activityProbeIntervalInput.toIntOrNull()
                        ?.coerceIn(400, 15_000)
                        ?: 1000
                    ClipboardSyncService.startOrUpdate(
                        context = context,
                        config = ClipboardSyncConfig(
                            localHost = localHostInput.trim(),
                            localPort = localPort,
                            peerHost = peerHostInput.trim(),
                            peerPort = peerPort,
                            reconnectMode = reconnectMode,
                            reconnectLimit = limit,
                            focusOverlayEnabled = focusOverlayEnabled,
                            activityProbeEnabled = activityProbeEnabled,
                            activityProbeIntervalMs = probeIntervalMs,
                            quickProbeMagnetEnabled = quickProbeMagnetEnabled,
                            quickProbeMagnetSizeDp = quickProbeMagnetSizeDp.toInt().coerceIn(36, 120),
                            quickProbeMagnetAlpha = quickProbeMagnetAlpha.coerceIn(0.2f, 1.0f)
                        )
                    )
                    localStatusMessage = if (peerHostInput.isBlank() || peerPort !in 1..65535) {
                        "已启动共享，当前为被动等待模式。"
                    } else {
                        "已启动共享，正在协商连接对端。"
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (runtime.running) "更新配置" else "启动共享")
            }
            OutlinedButton(
                onClick = { ClipboardSyncService.stop(context) },
                modifier = Modifier.weight(1f)
            ) {
                Text("停止共享")
            }
        }
        OutlinedButton(
            onClick = { ClipboardSyncService.reconnectNow(context) },
            modifier = Modifier.fillMaxWidth(),
            enabled = runtime.running
        ) {
            Text("立即重连")
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("6. 当前状态", fontWeight = FontWeight.SemiBold)
                val onlineText = when {
                    runtime.connected && runtime.peerOnline -> "在线"
                    runtime.connected -> "已连接（心跳等待）"
                    else -> "离线"
                }
                Text("会话：${runtime.sessionMessage}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("连接：$onlineText", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "对端：${runtime.peerEndpoint.ifBlank { if (runtime.peerHost.isBlank()) "-" else "${runtime.peerHost}:${runtime.peerPort}" }}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("发送 ${runtime.sentCount} 条，接收 ${runtime.receivedCount} 条", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (runtime.reconnectStopped && !runtime.reconnectReason.isNullOrBlank()) {
                    Text(runtime.reconnectReason.orEmpty(), color = MaterialTheme.colorScheme.error)
                } else if (runtime.reconnectAttempts > 0) {
                    Text("重连次数：${runtime.reconnectAttempts}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (runtime.lastLocalTextPreview.isNotBlank()) {
                    Text(
                        "最近发送：${runtime.lastLocalTextPreview}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (runtime.lastRemoteTextPreview.isNotBlank()) {
                    Text(
                        "最近接收：${runtime.lastRemoteTextPreview}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "说明：已内置防回环，收到远端写入后不会再次反向发送同一条更新。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        localStatusMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ReconnectOptionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.heightIn(min = 40.dp)) {
            Text(label, fontSize = 12.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 40.dp)) {
            Text(label, fontSize = 12.sp)
        }
    }
}

private fun canDrawOverlayPermission(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    return Settings.canDrawOverlays(context)
}
