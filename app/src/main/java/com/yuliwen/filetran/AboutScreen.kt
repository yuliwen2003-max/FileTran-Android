package com.yuliwen.filetran

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class StunGroupType {
    IPV4_TCP,
    IPV4_UDP,
    IPV6_TCP,
    IPV6_UDP
}

const val UI_SCALE_PREFS = "ui_scale_prefs"
const val UI_SCALE_KEY = "ui_scale"
const val UI_SCALE_DEFAULT = 1.0f
private const val UI_SCALE_MIN = 0.75f
private const val UI_SCALE_MAX = 1.25f

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    var showOpenSourceNotice by remember { mutableStateOf(false) }
    var showStunSettingsPage by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val keepAlivePrefs = remember {
        context.getSharedPreferences(APP_KEEP_ALIVE_PREFS, Context.MODE_PRIVATE)
    }
    val uiScalePrefs = remember {
        context.getSharedPreferences(UI_SCALE_PREFS, Context.MODE_PRIVATE)
    }
    var globalKeepAliveEnabled by remember {
        mutableStateOf(keepAlivePrefs.getBoolean(APP_KEEP_ALIVE_KEY_ENABLED, false))
    }
    var globalSuperKeepAliveEnabled by remember {
        mutableStateOf(keepAlivePrefs.getBoolean(APP_KEEP_ALIVE_KEY_SUPER_MODE, false))
    }
    var keepAliveCardExpanded by remember { mutableStateOf(true) }
    var uiScale by remember {
        mutableStateOf(uiScalePrefs.getFloat(UI_SCALE_KEY, UI_SCALE_DEFAULT).coerceIn(UI_SCALE_MIN, UI_SCALE_MAX))
    }
    var batteryOptimizationIgnored by remember {
        mutableStateOf(isBatteryOptimizationIgnored(context))
    }
    var overlayPermissionGranted by remember {
        mutableStateOf(isOverlayPermissionGranted(context))
    }
    var notificationPermissionGranted by remember {
        mutableStateOf(isNotificationPermissionGranted(context))
    }
    var pendingEnableGlobalKeepAlive by remember { mutableStateOf(false) }
    var stunSettings by remember {
        mutableStateOf(NetworkUtils.getStunServerSettings(context))
    }
    var stunConfigStatus by remember { mutableStateOf<String?>(null) }
    val stunProbeStatus = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()

    fun groupEntries(type: StunGroupType): List<StunServerDefinition> {
        return when (type) {
            StunGroupType.IPV4_TCP -> stunSettings.ipv4Tcp
            StunGroupType.IPV4_UDP -> stunSettings.ipv4Udp
            StunGroupType.IPV6_TCP -> stunSettings.ipv6Tcp
            StunGroupType.IPV6_UDP -> stunSettings.ipv6Udp
        }
    }

    fun updateGroup(type: StunGroupType, entries: List<StunServerDefinition>) {
        stunSettings = when (type) {
            StunGroupType.IPV4_TCP -> stunSettings.copy(ipv4Tcp = entries)
            StunGroupType.IPV4_UDP -> stunSettings.copy(ipv4Udp = entries)
            StunGroupType.IPV6_TCP -> stunSettings.copy(ipv6Tcp = entries)
            StunGroupType.IPV6_UDP -> stunSettings.copy(ipv6Udp = entries)
        }
    }

    fun clearProbeStatusForType(type: StunGroupType) {
        val prefix = "${type.name}_"
        stunProbeStatus.keys.filter { it.startsWith(prefix) }.forEach { key ->
            stunProbeStatus.remove(key)
        }
    }

    fun updateEntry(type: StunGroupType, index: Int, transform: (StunServerDefinition) -> StunServerDefinition) {
        val updated = groupEntries(type).toMutableList()
        if (index !in updated.indices) return
        updated[index] = transform(updated[index])
        stunProbeStatus.remove("${type.name}_$index")
        updateGroup(type, updated)
    }

    fun addEntry(type: StunGroupType) {
        val current = groupEntries(type)
        if (current.size >= 5) return
        clearProbeStatusForType(type)
        updateGroup(type, current + StunServerDefinition(server = "", ip = "", port = 3478))
    }

    fun removeEntry(type: StunGroupType, index: Int) {
        val current = groupEntries(type)
        if (index !in current.indices) return
        clearProbeStatusForType(type)
        updateGroup(type, current.toMutableList().also { it.removeAt(index) })
    }

    fun copyGroup(from: StunGroupType, to: StunGroupType) {
        val copied = groupEntries(from).map { it.copy() }.take(5)
        clearProbeStatusForType(to)
        updateGroup(to, copied)
    }

    fun probeEntry(type: StunGroupType, index: Int) {
        val entry = groupEntries(type).getOrNull(index) ?: return
        val key = "${type.name}_$index"
        stunProbeStatus[key] = "探测中..."
        val preferIpv6 = type == StunGroupType.IPV6_TCP || type == StunGroupType.IPV6_UDP
        val transport = if (type == StunGroupType.IPV4_UDP || type == StunGroupType.IPV6_UDP) {
            StunTransportType.UDP
        } else {
            StunTransportType.TCP
        }
        scope.launch {
            val mapped = withContext(Dispatchers.IO) {
                NetworkUtils.probeCustomStunServer(
                    definition = entry,
                    preferIpv6 = preferIpv6,
                    transport = transport
                )
            }
            stunProbeStatus[key] = if (mapped != null) {
                "可用：${mapped.address}:${mapped.port}"
            } else {
                "不可用：探测失败"
            }
        }
    }

    val requestNotificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted
        if (pendingEnableGlobalKeepAlive) {
            pendingEnableGlobalKeepAlive = false
            if (granted) {
                globalKeepAliveEnabled = true
                keepAlivePrefs.edit()
                    .putBoolean(APP_KEEP_ALIVE_KEY_ENABLED, true)
                    .apply()
                AppKeepAliveService.start(context, globalSuperKeepAliveEnabled)
            } else {
                globalKeepAliveEnabled = false
                keepAlivePrefs.edit()
                    .putBoolean(APP_KEEP_ALIVE_KEY_ENABLED, false)
                    .apply()
                AppKeepAliveService.stop(context)
            }
        }
    }

    val appIconBitmap = remember(context) {
        runCatching {
            context.packageManager
                .getApplicationIcon(context.packageName)
                .toBitmap()
                .asImageBitmap()
        }.getOrNull()
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOptimizationIgnored = isBatteryOptimizationIgnored(context)
                overlayPermissionGranted = isOverlayPermissionGranted(context)
                notificationPermissionGranted = isNotificationPermissionGranted(context)
                stunSettings = NetworkUtils.getStunServerSettings(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showOpenSourceNotice) {
        OpenSourceNoticeSheet(onDismiss = { showOpenSourceNotice = false })
    }

    if (showStunSettingsPage) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { showStunSettingsPage = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("返回关于页")
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("STUN 服务器自定义", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text(
                        "可分别设置 IPv4/IPv6 的 TCP/UDP STUN 列表。每组最多 5 个，端口默认 3478，可手动改。实时探测可验证可用性。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                copyGroup(StunGroupType.IPV4_TCP, StunGroupType.IPV4_UDP)
                                stunConfigStatus = "已将 IPv4 TCP 列表复用到 IPv4 UDP。"
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("IPv4 TCP→UDP 复用") }
                        OutlinedButton(
                            onClick = {
                                copyGroup(StunGroupType.IPV4_UDP, StunGroupType.IPV4_TCP)
                                stunConfigStatus = "已将 IPv4 UDP 列表复用到 IPv4 TCP。"
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("IPv4 UDP→TCP 复用") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                copyGroup(StunGroupType.IPV6_TCP, StunGroupType.IPV6_UDP)
                                stunConfigStatus = "已将 IPv6 TCP 列表复用到 IPv6 UDP。"
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("IPv6 TCP→UDP 复用") }
                        OutlinedButton(
                            onClick = {
                                copyGroup(StunGroupType.IPV6_UDP, StunGroupType.IPV6_TCP)
                                stunConfigStatus = "已将 IPv6 UDP 列表复用到 IPv6 TCP。"
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("IPv6 UDP→TCP 复用") }
                    }

                    StunGroupEditor(
                        title = "IPv4 TCP STUN",
                        type = StunGroupType.IPV4_TCP,
                        entries = groupEntries(StunGroupType.IPV4_TCP),
                        probeStatus = stunProbeStatus,
                        onUpdateEntry = { index, def -> updateEntry(StunGroupType.IPV4_TCP, index) { def } },
                        onAdd = { addEntry(StunGroupType.IPV4_TCP) },
                        onRemove = { index -> removeEntry(StunGroupType.IPV4_TCP, index) },
                        onProbe = { index -> probeEntry(StunGroupType.IPV4_TCP, index) }
                    )
                    StunGroupEditor(
                        title = "IPv4 UDP STUN",
                        type = StunGroupType.IPV4_UDP,
                        entries = groupEntries(StunGroupType.IPV4_UDP),
                        probeStatus = stunProbeStatus,
                        onUpdateEntry = { index, def -> updateEntry(StunGroupType.IPV4_UDP, index) { def } },
                        onAdd = { addEntry(StunGroupType.IPV4_UDP) },
                        onRemove = { index -> removeEntry(StunGroupType.IPV4_UDP, index) },
                        onProbe = { index -> probeEntry(StunGroupType.IPV4_UDP, index) }
                    )
                    StunGroupEditor(
                        title = "IPv6 TCP STUN",
                        type = StunGroupType.IPV6_TCP,
                        entries = groupEntries(StunGroupType.IPV6_TCP),
                        probeStatus = stunProbeStatus,
                        onUpdateEntry = { index, def -> updateEntry(StunGroupType.IPV6_TCP, index) { def } },
                        onAdd = { addEntry(StunGroupType.IPV6_TCP) },
                        onRemove = { index -> removeEntry(StunGroupType.IPV6_TCP, index) },
                        onProbe = { index -> probeEntry(StunGroupType.IPV6_TCP, index) }
                    )
                    StunGroupEditor(
                        title = "IPv6 UDP STUN",
                        type = StunGroupType.IPV6_UDP,
                        entries = groupEntries(StunGroupType.IPV6_UDP),
                        probeStatus = stunProbeStatus,
                        onUpdateEntry = { index, def -> updateEntry(StunGroupType.IPV6_UDP, index) { def } },
                        onAdd = { addEntry(StunGroupType.IPV6_UDP) },
                        onRemove = { index -> removeEntry(StunGroupType.IPV6_UDP, index) },
                        onProbe = { index -> probeEntry(StunGroupType.IPV6_UDP, index) }
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                NetworkUtils.saveStunServerSettings(context, stunSettings)
                                stunSettings = NetworkUtils.getStunServerSettings(context)
                                stunConfigStatus = "STUN 配置已保存并生效。"
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("保存并生效") }
                        OutlinedButton(
                            onClick = {
                                NetworkUtils.resetStunServerSettings(context)
                                stunSettings = NetworkUtils.getStunServerSettings(context)
                                stunProbeStatus.clear()
                                stunConfigStatus = "已恢复默认 STUN 配置。"
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("恢复默认") }
                    }
                    stunConfigStatus?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("设置", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                appIconBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = "app_icon",
                        modifier = Modifier.fillMaxWidth(0.35f)
                    )
                }
                Text("速传", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("版本：1.0beta")
                Text("作者：嘻嘻")
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "声明：本软件仅用于合法用途，严禁用于任何违法行为。",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { showOpenSourceNotice = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("开源声明")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("页面缩放", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(
                    "用于调节整个应用页面的显示大小。小屏设备可适当调小，避免内容显示不全。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Text("当前缩放：${(uiScale * 100).toInt()}%")
                Slider(
                    value = uiScale,
                    onValueChange = { value ->
                        val normalized = value.coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)
                        uiScale = normalized
                        uiScalePrefs.edit().putFloat(UI_SCALE_KEY, normalized).apply()
                    },
                    valueRange = UI_SCALE_MIN..UI_SCALE_MAX,
                    steps = 9
                )
                OutlinedButton(
                    onClick = {
                        uiScale = UI_SCALE_DEFAULT
                        uiScalePrefs.edit().putFloat(UI_SCALE_KEY, UI_SCALE_DEFAULT).apply()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("恢复默认缩放")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("全局后台保活", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { keepAliveCardExpanded = !keepAliveCardExpanded }) {
                        Text(if (keepAliveCardExpanded) "收起" else "展开")
                    }
                }
                if (keepAliveCardExpanded) {
                    Text(
                        "用于像 LibreSpeed 这类服务器场景的长期后台运行。SpeedTest 保活仍是页面级，退出 SpeedTest 页面会自动停止。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("开启全局保活", modifier = Modifier.weight(1f))
                        Switch(
                            checked = globalKeepAliveEnabled,
                            onCheckedChange = { checked ->
                                if (checked && !isNotificationPermissionGranted(context)) {
                                    pendingEnableGlobalKeepAlive = true
                                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    return@Switch
                                }
                                globalKeepAliveEnabled = checked
                                keepAlivePrefs.edit()
                                    .putBoolean(APP_KEEP_ALIVE_KEY_ENABLED, checked)
                                    .apply()
                                if (checked) {
                                    AppKeepAliveService.start(context, globalSuperKeepAliveEnabled)
                                } else {
                                    AppKeepAliveService.stop(context)
                                }
                            }
                        )
                    }
                    if (!notificationPermissionGranted) {
                        Text(
                            "通知权限：未授权（部分设备下将无法稳定显示保活通知）",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        OutlinedButton(
                            onClick = {
                                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("申请通知权限")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("超强保活（无声音乐 + 透明悬浮窗）", modifier = Modifier.weight(1f))
                        Switch(
                            checked = globalSuperKeepAliveEnabled,
                            onCheckedChange = { checked ->
                                globalSuperKeepAliveEnabled = checked
                                keepAlivePrefs.edit()
                                    .putBoolean(APP_KEEP_ALIVE_KEY_SUPER_MODE, checked)
                                    .apply()
                                if (globalKeepAliveEnabled) {
                                    AppKeepAliveService.start(context, checked)
                                }
                            }
                        )
                    }
                    Text(
                        if (batteryOptimizationIgnored) {
                            "后台无限制：已申请（电池优化已忽略）"
                        } else {
                            "后台无限制：未申请，建议开启以提升保活稳定性"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    OutlinedButton(
                        onClick = {
                            requestIgnoreBatteryOptimization(context)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("申请后台无限制（忽略电池优化）")
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Text(
                            if (overlayPermissionGranted) {
                                "悬浮窗权限：已授权"
                            } else {
                                "悬浮窗权限：未授权（仅影响超强保活中的透明悬浮窗）"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        OutlinedButton(
                            onClick = { requestOverlayPermission(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("打开悬浮窗权限设置")
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("STUN 服务器自定义", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(
                    "迁移到二级页面管理，支持按 IPv4/IPv6 + TCP/UDP 分组维护列表、实时探测、保存与恢复默认。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Button(
                    onClick = { showStunSettingsPage = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("进入 STUN 自定义页面")
                }
            }
        }
    }
}

@Composable
private fun StunGroupEditor(
    title: String,
    type: StunGroupType,
    entries: List<StunServerDefinition>,
    probeStatus: Map<String, String>,
    onUpdateEntry: (Int, StunServerDefinition) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onProbe: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onAdd, enabled = entries.size < 5) {
                    Text("新增（${entries.size}/5）")
                }
            }
            entries.forEachIndexed { index, entry ->
                val key = "${type.name}_$index"
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("服务器 ${index + 1}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = entry.server,
                            onValueChange = { onUpdateEntry(index, entry.copy(server = it)) },
                            label = { Text("STUN 地址（域名或名称）") },
                            placeholder = { Text("例如 stun.hot-chilli.net") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = entry.ip,
                            onValueChange = { onUpdateEntry(index, entry.copy(ip = it.trim())) },
                            label = { Text("服务器 IP（可选，优先于域名）") },
                            placeholder = { Text("例如 1.2.3.4 或 2400:3200::1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = entry.port.toString(),
                            onValueChange = { value ->
                                val parsed = value.filter(Char::isDigit).toIntOrNull() ?: 3478
                                onUpdateEntry(index, entry.copy(port = parsed))
                            },
                            label = { Text("端口") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onProbe(index) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("实时探测")
                            }
                            OutlinedButton(
                                onClick = { onRemove(index) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("删除")
                            }
                        }
                        probeStatus[key]?.let { status ->
                            Text(
                                text = status,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun isBatteryOptimizationIgnored(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun isOverlayPermissionGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    return Settings.canDrawOverlays(context)
}

private fun isNotificationPermissionGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

private fun requestIgnoreBatteryOptimization(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val packageUri = Uri.parse("package:${context.packageName}")
    val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (requestIntent.resolveActivity(context.packageManager) != null) {
        context.startActivity(requestIntent)
    } else {
        context.startActivity(fallbackIntent)
    }
}

private fun requestOverlayPermission(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
