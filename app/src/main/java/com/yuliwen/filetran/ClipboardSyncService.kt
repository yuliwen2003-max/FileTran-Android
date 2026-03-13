package com.yuliwen.filetran

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

enum class ClipboardSyncReconnectMode {
    ALWAYS,
    ATTEMPTS,
    DURATION
}

data class ClipboardSyncConfig(
    val localHost: String = "",
    val localPort: Int = CLIPBOARD_SYNC_DEFAULT_PORT,
    val peerHost: String = "",
    val peerPort: Int = 0,
    val reconnectMode: ClipboardSyncReconnectMode = ClipboardSyncReconnectMode.ALWAYS,
    val reconnectLimit: Int = 0,
    val focusOverlayEnabled: Boolean = true,
    val activityProbeEnabled: Boolean = false,
    val activityProbeIntervalMs: Int = 1000,
    val quickProbeMagnetEnabled: Boolean = false,
    val quickProbeMagnetSizeDp: Int = 54,
    val quickProbeMagnetAlpha: Float = 0.82f
)

data class ClipboardSyncRuntimeState(
    val running: Boolean = false,
    val localHost: String = "",
    val localPort: Int = CLIPBOARD_SYNC_DEFAULT_PORT,
    val peerHost: String = "",
    val peerPort: Int = 0,
    val connected: Boolean = false,
    val peerOnline: Boolean = false,
    val peerEndpoint: String = "",
    val sessionMessage: String = "未启动",
    val sentCount: Int = 0,
    val receivedCount: Int = 0,
    val reconnectAttempts: Int = 0,
    val reconnectStopped: Boolean = false,
    val reconnectReason: String? = null,
    val lastLocalTextPreview: String = "",
    val lastRemoteTextPreview: String = "",
    val autoReconnectMode: ClipboardSyncReconnectMode = ClipboardSyncReconnectMode.ALWAYS,
    val autoReconnectLimit: Int = 0,
    val focusOverlayEnabled: Boolean = false,
    val focusOverlayActive: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val activityProbeEnabled: Boolean = false,
    val activityProbeIntervalMs: Int = 1000,
    val activityProbeRunning: Boolean = false,
    val quickProbeMagnetEnabled: Boolean = false,
    val quickProbeMagnetActive: Boolean = false,
    val quickProbeMagnetSizeDp: Int = 54,
    val quickProbeMagnetAlpha: Float = 0.82f,
    val lastMessageAt: Long = 0L
)

object ClipboardSyncRuntime {
    val stateFlow = MutableStateFlow(ClipboardSyncRuntimeState())

    fun update(block: (ClipboardSyncRuntimeState) -> ClipboardSyncRuntimeState) {
        stateFlow.value = block(stateFlow.value)
    }

    fun reset() {
        stateFlow.value = ClipboardSyncRuntimeState()
    }
}

class ClipboardSyncService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writerMutex = Mutex()
    private val socketLock = Any()

    private var currentConfig = ClipboardSyncConfig()
    private var deviceId: String = ""
    private var foregroundStarted = false
    private var shuttingDown = false

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var connectorJob: Job? = null
    private var heartbeatJob: Job? = null
    private var clipboardPollJob: Job? = null
    private var overlayFocusJob: Job? = null
    private var activityProbeJob: Job? = null
    private var sessionJob: Job? = null

    private var overlayWindowManager: WindowManager? = null
    private var overlayFocusView: EditText? = null
    private var overlayFocusActive = false
    private var magnetWindowManager: WindowManager? = null
    private var magnetView: TextView? = null
    private var magnetActive = false
    private var overlayPermissionCache: Boolean? = null
    private var probeSuppressedByOverlay: Boolean? = null
    private var probeBlockedByPermission: Boolean? = null
    @Volatile
    private var activityProbeInFlight = false
    private var lastActivityProbeLaunchAtMs = 0L
    private var lastManualProbeTriggerAtMs = 0L

    private var reconnectAttempts = 0
    private var reconnectWindowStartMs = 0L
    private var reconnectStopped = false
    private var reconnectStopReason: String? = null

    @Volatile
    private var activeSocket: Socket? = null
    @Volatile
    private var activeWriter: BufferedWriter? = null
    @Volatile
    private var activePeerEndpoint: String = ""
    @Volatile
    private var lastIncomingAtMs: Long = 0L

    private var sentCount = 0
    private var receivedCount = 0
    private var lastLocalPreview = ""
    private var lastRemotePreview = ""
    private var suppressLocalHash: String? = null
    private var suppressLocalUntilMs: Long = 0L
    private var lastSentHash: String? = null
    private var lastObservedLocalHash: String? = null

    private val recentMessageIds = ArrayDeque<String>()
    private val recentMessageIdSet = hashSetOf<String>()

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleLocalClipboardChanged()
    }
    private var clipboardListenerRegistered = false

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(CLIPBOARD_SYNC_PREFS, Context.MODE_PRIVATE)
        deviceId = prefs.getString(CLIPBOARD_SYNC_DEVICE_ID, null).orEmpty().ifBlank {
            UUID.randomUUID().toString().also {
                prefs.edit().putString(CLIPBOARD_SYNC_DEVICE_ID, it).apply()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                markEnabledFlag(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT_NOW -> {
                forceReconnect("手动重连")
                return START_STICKY
            }
            ACTION_TRIGGER_MANUAL_PROBE -> {
                serviceScope.launch {
                    val source = intent.getStringExtra(EXTRA_MANUAL_PROBE_SOURCE).orEmpty().ifBlank { "manual" }
                    runManualProbeFromAction(source)
                }
                return START_STICKY
            }
        }

        val newConfig = if (intent == null) {
            val persisted = readPersistedConfig(this)
            if (persisted.localHost.isNotBlank() || persisted.peerHost.isNotBlank()) {
                persisted
            } else {
                currentConfig
            }
        } else {
            intentToConfig(intent)
        }
        applyConfig(newConfig)
        persistConfig(this, newConfig, enabled = true)
        AppKeepAliveService.pauseForClipboardSync(this)
        ensureForeground()
        ensureClipboardListener()
        syncFocusOverlayByConfig()
        syncActivityProbeByConfig()
        syncQuickProbeMagnetByConfig()
        ensureClipboardPollingLoop()
        ensureServerLoop(restart = true)
        ensureConnectorLoop(restart = true)
        ensureHeartbeatLoop()
        pushRuntimeState("剪贴板共享服务已启动。")
        return START_STICKY
    }

    override fun onDestroy() {
        shuttingDown = true
        unregisterClipboardListener()
        closeActiveSocket("服务停止")
        runCatching { serverSocket?.close() }
        serverJob?.cancel()
        connectorJob?.cancel()
        heartbeatJob?.cancel()
        clipboardPollJob?.cancel()
        overlayFocusJob?.cancel()
        activityProbeJob?.cancel()
        sessionJob?.cancel()
        removeFocusOverlay()
        removeQuickProbeMagnet()
        serviceScope.cancel()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        AppKeepAliveService.resumeAfterClipboardSync(this)
        ClipboardSyncRuntime.update {
            it.copy(
                running = false,
                connected = false,
                peerOnline = false,
                sessionMessage = "已停止"
            )
        }
        super.onDestroy()
    }

    private fun intentToConfig(intent: Intent?): ClipboardSyncConfig {
        val localPort = intent?.getIntExtra(EXTRA_LOCAL_PORT, currentConfig.localPort)
            ?.coerceIn(1, 65535) ?: currentConfig.localPort
        val peerPort = intent?.getIntExtra(EXTRA_PEER_PORT, currentConfig.peerPort)
            ?.coerceIn(0, 65535) ?: currentConfig.peerPort
        val reconnectMode = intent?.getStringExtra(EXTRA_RECONNECT_MODE)
            ?.let { raw -> runCatching { ClipboardSyncReconnectMode.valueOf(raw) }.getOrNull() }
            ?: currentConfig.reconnectMode
        val reconnectLimit = intent?.getIntExtra(EXTRA_RECONNECT_LIMIT, currentConfig.reconnectLimit)
            ?.coerceAtLeast(0) ?: currentConfig.reconnectLimit
        val focusOverlayEnabled = intent?.getBooleanExtra(EXTRA_FOCUS_OVERLAY_ENABLED, currentConfig.focusOverlayEnabled)
            ?: currentConfig.focusOverlayEnabled
        val activityProbeEnabled = intent?.getBooleanExtra(EXTRA_ACTIVITY_PROBE_ENABLED, currentConfig.activityProbeEnabled)
            ?: currentConfig.activityProbeEnabled
        val activityProbeIntervalMs = intent?.getIntExtra(EXTRA_ACTIVITY_PROBE_INTERVAL_MS, currentConfig.activityProbeIntervalMs)
            ?: currentConfig.activityProbeIntervalMs
        val quickProbeMagnetEnabled = intent?.getBooleanExtra(
            EXTRA_QUICK_PROBE_MAGNET_ENABLED,
            currentConfig.quickProbeMagnetEnabled
        ) ?: currentConfig.quickProbeMagnetEnabled
        val quickProbeMagnetSizeDp = intent?.getIntExtra(
            EXTRA_QUICK_PROBE_MAGNET_SIZE_DP,
            currentConfig.quickProbeMagnetSizeDp
        ) ?: currentConfig.quickProbeMagnetSizeDp
        val quickProbeMagnetAlpha = intent?.getFloatExtra(
            EXTRA_QUICK_PROBE_MAGNET_ALPHA,
            currentConfig.quickProbeMagnetAlpha
        ) ?: currentConfig.quickProbeMagnetAlpha
        return ClipboardSyncConfig(
            localHost = intent?.getStringExtra(EXTRA_LOCAL_HOST)?.trim().orEmpty().ifBlank { currentConfig.localHost },
            localPort = localPort,
            peerHost = intent?.getStringExtra(EXTRA_PEER_HOST)?.trim().orEmpty(),
            peerPort = peerPort,
            reconnectMode = reconnectMode,
            reconnectLimit = reconnectLimit,
            focusOverlayEnabled = focusOverlayEnabled,
            activityProbeEnabled = activityProbeEnabled,
            activityProbeIntervalMs = activityProbeIntervalMs.coerceIn(MIN_ACTIVITY_PROBE_INTERVAL_MS, MAX_ACTIVITY_PROBE_INTERVAL_MS),
            quickProbeMagnetEnabled = quickProbeMagnetEnabled,
            quickProbeMagnetSizeDp = quickProbeMagnetSizeDp.coerceIn(MIN_MAGNET_SIZE_DP, MAX_MAGNET_SIZE_DP),
            quickProbeMagnetAlpha = quickProbeMagnetAlpha.coerceIn(MIN_MAGNET_ALPHA, MAX_MAGNET_ALPHA)
        )
    }

    private fun applyConfig(newConfig: ClipboardSyncConfig) {
        val old = currentConfig
        currentConfig = newConfig
        if (old.peerHost != newConfig.peerHost || old.peerPort != newConfig.peerPort) {
            reconnectAttempts = 0
            reconnectWindowStartMs = System.currentTimeMillis()
            reconnectStopped = false
            reconnectStopReason = null
            forceReconnect("对端参数已更新")
        }
        if (old.localPort != newConfig.localPort) {
            ensureServerLoop(restart = true)
        }
        val focusChanged = old.focusOverlayEnabled != newConfig.focusOverlayEnabled
        if (focusChanged) {
            syncFocusOverlayByConfig()
        }
        if (focusChanged ||
            old.activityProbeEnabled != newConfig.activityProbeEnabled ||
            old.activityProbeIntervalMs != newConfig.activityProbeIntervalMs
        ) {
            syncActivityProbeByConfig()
        }
        if (old.quickProbeMagnetEnabled != newConfig.quickProbeMagnetEnabled ||
            old.quickProbeMagnetSizeDp != newConfig.quickProbeMagnetSizeDp ||
            old.quickProbeMagnetAlpha != newConfig.quickProbeMagnetAlpha ||
            focusChanged
        ) {
            syncQuickProbeMagnetByConfig()
        }
    }

    private fun ensureForeground() {
        ensureNotificationChannel()
        val notification = buildForegroundNotification()
        if (!foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    CLIPBOARD_SYNC_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(CLIPBOARD_SYNC_NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } else {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(CLIPBOARD_SYNC_NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CLIPBOARD_SYNC_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CLIPBOARD_SYNC_CHANNEL_ID,
                "剪贴板共享",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于保持剪贴板共享会话在后台持续运行。"
                setShowBadge(false)
            }
        )
    }

    private fun buildForegroundNotification(): Notification {
        val runtime = ClipboardSyncRuntime.stateFlow.value
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingLaunch = PendingIntent.getActivity(
            this,
            1011,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, ClipboardSyncService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = createServiceActionPendingIntent(1012, stopIntent)
        val probeIntent = Intent(this, ClipboardSyncService::class.java).apply {
            action = ACTION_TRIGGER_MANUAL_PROBE
            putExtra(EXTRA_MANUAL_PROBE_SOURCE, MANUAL_SOURCE_NOTIFICATION)
        }
        val pendingProbe = createServiceActionPendingIntent(1013, probeIntent)
        val onlineText = if (runtime.connected && runtime.peerOnline) "在线" else "离线"
        val peerText = runtime.peerEndpoint.ifBlank {
            if (runtime.peerHost.isNotBlank() && runtime.peerPort in 1..65535) {
                "${runtime.peerHost}:${runtime.peerPort}"
            } else {
                "-"
            }
        }
        return NotificationCompat.Builder(this, CLIPBOARD_SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("剪贴板共享运行中")
            .setContentText("对端：$peerText（$onlineText）")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "状态：${runtime.sessionMessage}\n对端：$peerText（$onlineText）\n发送：${runtime.sentCount} 条，接收：${runtime.receivedCount} 条"
                )
            )
            .setContentIntent(pendingLaunch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_search, "立即识别", pendingProbe)
            .addAction(android.R.drawable.ic_media_pause, "停止共享", pendingStop)
            .build()
    }

    private fun createServiceActionPendingIntent(
        requestCode: Int,
        intent: Intent
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        } else {
            PendingIntent.getService(this, requestCode, intent, flags)
        }
    }

    private fun ensureClipboardListener() {
        if (clipboardListenerRegistered) return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.addPrimaryClipChangedListener(clipboardListener)
        clipboardListenerRegistered = true
    }

    private fun unregisterClipboardListener() {
        if (!clipboardListenerRegistered) return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        runCatching { clipboard.removePrimaryClipChangedListener(clipboardListener) }
        clipboardListenerRegistered = false
    }

    private fun hasOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.canDrawOverlays(this)
    }

    private fun syncFocusOverlayByConfig() {
        if (!currentConfig.focusOverlayEnabled) {
            removeFocusOverlay()
            syncActivityProbeByConfig()
            return
        }
        ensureFocusOverlay()
    }

    private fun ensureFocusOverlay() {
        val granted = hasOverlayPermission()
        handleOverlayPermissionChange(granted)
        if (!granted) {
            removeFocusOverlay()
            overlayFocusActive = false
            pushRuntimeState("悬浮窗权限未授权，后台剪贴板读取增强不可用。")
            return
        }
        if (overlayFocusView != null) {
            ensureOverlayFocusLoop()
            overlayFocusActive = true
            return
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
        }
        val view = EditText(this).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setTextColor(AndroidColor.TRANSPARENT)
            setHintTextColor(AndroidColor.TRANSPARENT)
            alpha = 0.0f
            isFocusable = true
            isFocusableInTouchMode = true
            setSingleLine(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                showSoftInputOnFocus = false
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        runCatching {
            wm.addView(view, params)
            overlayWindowManager = wm
            overlayFocusView = view
            overlayFocusActive = true
            ensureOverlayFocusLoop()
            pushRuntimeState("已启用焦点悬浮窗增强后台剪贴板读取。")
        }.onFailure {
            overlayFocusActive = false
            overlayFocusView = null
            overlayWindowManager = null
            pushRuntimeState("焦点悬浮窗启动失败：${it.message ?: "unknown"}")
        }
    }

    private fun ensureOverlayFocusLoop() {
        if (overlayFocusJob?.isActive == true) return
        overlayFocusJob = serviceScope.launch {
            while (isActive && currentConfig.focusOverlayEnabled) {
                delay(OVERLAY_FOCUS_REFRESH_MS)
                withContext(Dispatchers.Main) {
                    val granted = hasOverlayPermission()
                    handleOverlayPermissionChange(granted)
                    if (!granted) {
                        removeFocusOverlay()
                        return@withContext
                    }
                    if (overlayFocusView == null) {
                        ensureFocusOverlay()
                    }
                }
            }
        }
    }

    private fun removeFocusOverlay() {
        overlayFocusJob?.cancel()
        overlayFocusJob = null
        val view = overlayFocusView
        val wm = overlayWindowManager
        overlayFocusView = null
        overlayWindowManager = null
        overlayFocusActive = false
        if (view != null && wm != null) {
            runCatching { wm.removeViewImmediate(view) }
        }
        syncActivityProbeByConfig()
    }

    private fun ensureClipboardPollingLoop() {
        if (clipboardPollJob?.isActive == true) return
        clipboardPollJob = serviceScope.launch {
            while (isActive) {
                delay(CLIPBOARD_POLL_INTERVAL_MS)
                val text = readClipboardWithOverlayAssist() ?: readClipboardPlainText() ?: continue
                syncLocalClipboardText(text, source = "poll")
            }
        }
    }

    private fun syncActivityProbeByConfig() {
        if (shuttingDown) {
            activityProbeJob?.cancel()
            activityProbeJob = null
            activityProbeInFlight = false
            return
        }
        val suppressed = shouldSuppressActivityProbe()
        val overlayPermissionGranted = hasOverlayPermission()
        val blockedByPermission = currentConfig.activityProbeEnabled &&
            !suppressed &&
            !overlayPermissionGranted
        val shouldRun = currentConfig.activityProbeEnabled &&
            !suppressed &&
            overlayPermissionGranted
        if (!shouldRun) {
            activityProbeJob?.cancel()
            activityProbeJob = null
            activityProbeInFlight = false
            if (suppressed != probeSuppressedByOverlay) {
                probeSuppressedByOverlay = suppressed
                if (suppressed && currentConfig.activityProbeEnabled) {
                    pushRuntimeState("焦点悬浮窗已生效，透明悬浮窗定时探测已自动暂停，避免干扰当前前台应用。")
                }
            }
            if (blockedByPermission != probeBlockedByPermission) {
                probeBlockedByPermission = blockedByPermission
                if (blockedByPermission) {
                    pushRuntimeState("透明悬浮窗定时探测需要悬浮窗权限，请先授权。")
                }
            }
            return
        }
        probeSuppressedByOverlay = false
        probeBlockedByPermission = false
        ensureActivityProbeLoop()
    }

    private fun ensureActivityProbeLoop() {
        if (activityProbeJob?.isActive == true) return
        activityProbeJob = serviceScope.launch {
            while (isActive && currentConfig.activityProbeEnabled) {
                val interval = currentConfig.activityProbeIntervalMs
                    .coerceIn(MIN_ACTIVITY_PROBE_INTERVAL_MS, MAX_ACTIVITY_PROBE_INTERVAL_MS)
                delay(interval.toLong())
                requestActivityProbeLaunch()
            }
        }
    }

    private suspend fun requestActivityProbeLaunch() {
        if (shouldSuppressActivityProbe()) return
        runOneShotProbe(source = "timer", bypassCooldown = false)
    }

    private suspend fun runManualProbeFromAction(source: String) {
        val now = System.currentTimeMillis()
        if (now - lastManualProbeTriggerAtMs < MANUAL_PROBE_ACTION_COOLDOWN_MS) {
            pushRuntimeState("手动识别触发过快，请稍后再试。")
            return
        }
        lastManualProbeTriggerAtMs = now
        pushRuntimeState("正在执行手动识别...")
        if (source == MANUAL_SOURCE_NOTIFICATION) {
            closeSystemDialogsForManualProbe()
            delay(MANUAL_PROBE_PANEL_SETTLE_MS)
        }
        runOneShotProbe(source = "manual", bypassCooldown = true)
    }

    private suspend fun runOneShotProbe(source: String, bypassCooldown: Boolean) {
        if (!hasOverlayPermission()) {
            pushRuntimeState("透明悬浮窗探测需要悬浮窗权限，请先授权。")
            return
        }
        val now = System.currentTimeMillis()
        val maxInFlightMs = (currentConfig.activityProbeIntervalMs * 3L)
            .coerceAtLeast(2_000L)
            .coerceAtMost(12_000L)
        if (!bypassCooldown && activityProbeInFlight && now - lastActivityProbeLaunchAtMs < maxInFlightMs) {
            return
        }
        activityProbeInFlight = true
        lastActivityProbeLaunchAtMs = now
        var completed = false
        var hasSnapshot = false
        runCatching {
            val snapshot = readClipboardWithTransientOverlay()
            completed = snapshot != null
            snapshot?.let {
                hasSnapshot = true
                val forceSend = source != "timer"
                val connected = hasActiveConnection()
                if (!connected && forceSend) {
                    pushRuntimeState("已手动识别剪贴板，但当前未连接对端，无法发送。")
                }
                if (forceSend) {
                    val sameAsLast = sha256(snapshot) == lastObservedLocalHash
                    if (sameAsLast) {
                        pushRuntimeState("已读取到剪贴板，但内容与上次一致，仍将强制尝试同步。")
                    } else {
                        pushRuntimeState("已识别到新的剪贴板内容，正在尝试同步。")
                    }
                }
                syncLocalClipboardText(
                    snapshot,
                    source = "probe_overlay_$source",
                    forceSend = forceSend
                )
            }
        }.onFailure {
            pushRuntimeState("透明悬浮窗探测失败：${it.message ?: "unknown"}")
        }.also {
            activityProbeInFlight = false
        }
        if (!hasSnapshot && source != "timer") {
            pushRuntimeState("手动识别未读取到剪贴板，可能受系统限制。建议开启焦点悬浮窗增强后重试。")
        } else if (completed && source != "timer") {
            pushRuntimeState("已完成一次手动识别并尝试同步。")
        }
    }

    private fun syncQuickProbeMagnetByConfig() {
        if (!currentConfig.quickProbeMagnetEnabled) {
            removeQuickProbeMagnet()
            return
        }
        if (!hasOverlayPermission()) {
            removeQuickProbeMagnet()
            return
        }
        removeQuickProbeMagnet()
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val density = resources.displayMetrics.density
        val sizePx = (currentConfig.quickProbeMagnetSizeDp.coerceIn(MIN_MAGNET_SIZE_DP, MAX_MAGNET_SIZE_DP) * density)
            .toInt()
            .coerceAtLeast((MIN_MAGNET_SIZE_DP * density).toInt())
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val maxX = (screenWidth - sizePx).coerceAtLeast(0)
        val maxY = (screenHeight - sizePx).coerceAtLeast(0)
        val savedPos = readMagnetPosition()
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = savedPos.first.coerceIn(0, maxX)
            y = savedPos.second.coerceIn(0, maxY)
        }
        val magnet = TextView(this).apply {
            text = "M"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(AndroidColor.WHITE)
            alpha = currentConfig.quickProbeMagnetAlpha.coerceIn(MIN_MAGNET_ALPHA, MAX_MAGNET_ALPHA)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AndroidColor.parseColor("#CC00897B"))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Magnet quick probe"
            setOnTouchListener(object : View.OnTouchListener {
                private var downRawX = 0f
                private var downRawY = 0f
                private var startX = 0
                private var startY = 0
                private var moved = false
                private val dragThreshold = (6 * density).toInt().coerceAtLeast(4)

                override fun onTouch(v: View?, event: android.view.MotionEvent?): Boolean {
                    event ?: return false
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            downRawX = event.rawX
                            downRawY = event.rawY
                            startX = params.x
                            startY = params.y
                            moved = false
                            return true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - downRawX).toInt()
                            val dy = (event.rawY - downRawY).toInt()
                            if (!moved && (kotlin.math.abs(dx) > dragThreshold || kotlin.math.abs(dy) > dragThreshold)) {
                                moved = true
                            }
                            params.x = (startX + dx).coerceIn(0, maxX)
                            params.y = (startY + dy).coerceIn(0, maxY)
                            v?.let { view -> runCatching { wm.updateViewLayout(view, params) } }
                            return true
                        }
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            if (!moved) {
                                serviceScope.launch {
                                    runManualProbeFromAction("magnet")
                                }
                                return true
                            }
                            params.x = if (params.x + sizePx / 2 < screenWidth / 2) 0 else maxX
                            params.y = params.y.coerceIn(0, maxY)
                            v?.let { view -> runCatching { wm.updateViewLayout(view, params) } }
                            saveMagnetPosition(params.x, params.y)
                            return true
                        }
                    }
                    return false
                }
            })
        }
        runCatching {
            wm.addView(magnet, params)
            magnetWindowManager = wm
            magnetView = magnet
            magnetActive = true
            saveMagnetPosition(params.x, params.y)
        }.onFailure {
            magnetWindowManager = null
            magnetView = null
            magnetActive = false
        }
    }

    private fun removeQuickProbeMagnet() {
        val view = magnetView
        val wm = magnetWindowManager
        magnetView = null
        magnetWindowManager = null
        magnetActive = false
        if (view != null && wm != null) {
            runCatching { wm.removeViewImmediate(view) }
        }
    }

    private fun closeSystemDialogsForManualProbe() {
        runCatching {
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        }
    }

    private fun readMagnetPosition(): Pair<Int, Int> {
        val prefs = getSharedPreferences(MAGNET_PREFS, Context.MODE_PRIVATE)
        val defaultX = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        val defaultY = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        val x = prefs.getInt(MAGNET_POS_X, defaultX)
        val y = prefs.getInt(MAGNET_POS_Y, defaultY)
        return x to y
    }

    private fun saveMagnetPosition(x: Int, y: Int) {
        val prefs = getSharedPreferences(MAGNET_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt(MAGNET_POS_X, x).putInt(MAGNET_POS_Y, y).apply()
    }

    private suspend fun readClipboardWithTransientOverlay(): String? {
        val holder = withContext(Dispatchers.Main) {
            createTransientOverlayProbeView()
        } ?: return null
        return try {
            var result: String? = null
            for (attempt in 0 until TRANSIENT_PROBE_READ_RETRY_COUNT) {
                withContext(Dispatchers.Main) {
                    holder.view.requestFocus()
                    holder.view.post { holder.view.requestFocus() }
                }
                val focused = waitForTransientOverlayFocus(holder.view, FOCUS_WAIT_TIMEOUT_MS)
                if (!focused) {
                    continue
                }
                delay(TRANSIENT_PROBE_READ_DELAY_MS + (attempt * 20L))
                val snapshot = readClipboardPlainText()
                if (snapshot != null) {
                    result = snapshot
                    break
                }
            }
            result
        } finally {
            withContext(Dispatchers.Main) {
                removeTransientOverlayProbeView(holder)
            }
        }
    }

    private suspend fun waitForTransientOverlayFocus(view: EditText, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val hasFocusNow = withContext(Dispatchers.Main) { view.hasFocus() }
            if (hasFocusNow) return true
            delay(FOCUS_WAIT_STEP_MS)
        }
        return false
    }

    private data class OverlayProbeHolder(
        val windowManager: WindowManager,
        val view: EditText
    )

    private fun createTransientOverlayProbeView(): OverlayProbeHolder? {
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
        }
        val view = EditText(this).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setTextColor(AndroidColor.TRANSPARENT)
            setHintTextColor(AndroidColor.TRANSPARENT)
            alpha = 0.0f
            isFocusable = true
            isFocusableInTouchMode = true
            setSingleLine(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                showSoftInputOnFocus = false
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return runCatching {
            wm.addView(view, params)
            view.requestFocus()
            view.post { view.requestFocus() }
            OverlayProbeHolder(wm, view)
        }.getOrNull()
    }

    private fun removeTransientOverlayProbeView(holder: OverlayProbeHolder) {
        runCatching {
            holder.view.clearFocus()
            holder.windowManager.removeViewImmediate(holder.view)
        }
    }

    private suspend fun readClipboardWithOverlayAssist(): String? {
        if (!currentConfig.focusOverlayEnabled || !hasOverlayPermission()) return null
        val focusApplied = withContext(Dispatchers.Main) {
            if (overlayFocusView == null) {
                ensureFocusOverlay()
            }
            setOverlayFocusable(true)
        }
        if (!focusApplied) return null
        delay(80L)
        val snapshot = readClipboardPlainText()
        withContext(Dispatchers.Main) {
            setOverlayFocusable(false)
        }
        return snapshot
    }

    private fun setOverlayFocusable(focusable: Boolean): Boolean {
        val view = overlayFocusView ?: return false
        val wm = overlayWindowManager ?: return false
        val params = (view.layoutParams as? WindowManager.LayoutParams) ?: return false
        val desiredFlags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (desiredFlags != params.flags) {
            params.flags = desiredFlags
            runCatching { wm.updateViewLayout(view, params) }
        }
        if (focusable) {
            view.requestFocus()
            view.post { view.requestFocus() }
        } else {
            view.clearFocus()
        }
        return true
    }

    private fun shouldSuppressActivityProbe(): Boolean {
        return currentConfig.focusOverlayEnabled && hasOverlayPermission()
    }

    private fun handleOverlayPermissionChange(granted: Boolean) {
        if (overlayPermissionCache == granted) return
        overlayPermissionCache = granted
        if (!granted && currentConfig.focusOverlayEnabled) {
            pushRuntimeState("悬浮窗权限不可用，相关后台识别增强将受限。")
        }
        syncActivityProbeByConfig()
        syncQuickProbeMagnetByConfig()
    }

    private fun ensureServerLoop(restart: Boolean) {
        if (!restart && serverJob?.isActive == true) return
        runCatching { serverSocket?.close() }
        serverJob?.cancel()
        val listenPort = currentConfig.localPort.coerceIn(1, 65535)
        serverJob = serviceScope.launch {
            runCatching {
                ServerSocket().use { server ->
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(listenPort))
                    serverSocket = server
                    pushRuntimeState("本机监听已启动：$listenPort")
                    while (isActive) {
                        val socket = server.accept()
                        socket.tcpNoDelay = true
                        socket.soTimeout = SOCKET_READ_TIMEOUT_MS
                        attachSession(socket, source = "inbound")
                    }
                }
            }.onFailure { e ->
                if (isActive) {
                    pushRuntimeState("监听失败：${e.message ?: "unknown"}")
                }
            }.also {
                serverSocket = null
            }
        }
    }

    private fun ensureConnectorLoop(restart: Boolean) {
        if (!restart && connectorJob?.isActive == true) return
        connectorJob?.cancel()
        reconnectAttempts = 0
        reconnectWindowStartMs = System.currentTimeMillis()
        reconnectStopped = false
        reconnectStopReason = null
        connectorJob = serviceScope.launch {
            while (isActive) {
                delay(CONNECT_RETRY_INTERVAL_MS)
                val cfg = currentConfig
                val peerHost = cfg.peerHost.trim()
                val peerPort = cfg.peerPort
                if (peerHost.isBlank() || peerPort !in 1..65535) continue
                if (hasActiveConnection()) continue
                if (!isReconnectAllowed(cfg)) {
                    pushRuntimeState(reconnectStopReason ?: "自动重连已停止。")
                    continue
                }
                reconnectAttempts += 1
                pushRuntimeState("正在重连（第 $reconnectAttempts 次）...")
                val socket = runCatching {
                    Socket().apply {
                        connect(InetSocketAddress(peerHost, peerPort), SOCKET_CONNECT_TIMEOUT_MS)
                        tcpNoDelay = true
                        soTimeout = SOCKET_READ_TIMEOUT_MS
                    }
                }.getOrNull()
                if (socket == null) {
                    pushRuntimeState("重连失败，等待下一次重试。")
                } else {
                    attachSession(socket, source = "outbound")
                }
            }
        }
    }

    private fun ensureHeartbeatLoop() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!hasActiveConnection()) {
                    pushRuntimeState()
                    continue
                }
                val now = System.currentTimeMillis()
                val idleMs = now - lastIncomingAtMs
                if (idleMs > HEARTBEAT_TIMEOUT_MS) {
                    closeActiveSocket("心跳超时，正在重连。")
                    continue
                }
                sendJson(
                    JSONObject()
                        .put("type", MSG_TYPE_PING)
                        .put("ts", now)
                        .put("from", deviceId)
                )
                pushRuntimeState()
            }
        }
    }

    private fun attachSession(socket: Socket, source: String) {
        synchronized(socketLock) {
            val current = activeSocket
            if (current != null && current.isConnected && !current.isClosed) {
                runCatching { socket.close() }
                return
            }
            activeSocket = socket
        }
        sessionJob?.cancel()
        sessionJob = serviceScope.launch {
            runSession(socket, source)
        }
    }

    private suspend fun runSession(socket: Socket, source: String) {
        val endpoint = "${socket.inetAddress?.hostAddress ?: "unknown"}:${socket.port}"
        val reader = runCatching {
            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        }.getOrNull() ?: run {
            closeActiveSocket("连接初始化失败。")
            return
        }
        val writer = runCatching {
            BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        }.getOrNull() ?: run {
            closeActiveSocket("连接初始化失败。")
            return
        }
        synchronized(socketLock) {
            if (activeSocket === socket) {
                activeWriter = writer
                activePeerEndpoint = endpoint
            }
        }
        reconnectAttempts = 0
        reconnectStopped = false
        reconnectStopReason = null
        lastIncomingAtMs = System.currentTimeMillis()
        pushRuntimeState(if (source == "inbound") "对端已接入，正在协商..." else "已连上对端，正在协商...")
        sendJson(
            JSONObject()
                .put("type", MSG_TYPE_HELLO)
                .put("deviceId", deviceId)
                .put("version", 1)
        )

        while (serviceScope.isActive && !socket.isClosed) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: break
            lastIncomingAtMs = System.currentTimeMillis()
            handleInboundLine(line)
        }

        closeActiveSocket("连接断开，等待重连。")
    }

    private fun handleInboundLine(line: String) {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (json.optString("type")) {
            MSG_TYPE_HELLO -> {
                val peerId = json.optString("deviceId")
                if (peerId.isNotBlank() && peerId == deviceId) {
                    closeActiveSocket("检测到回环连接，已断开。")
                    return
                }
                serviceScope.launch {
                    sendJson(
                        JSONObject()
                            .put("type", MSG_TYPE_HELLO_ACK)
                            .put("deviceId", deviceId)
                    )
                }
                pushRuntimeState("协商成功，剪贴板共享中。")
                syncCurrentClipboardSnapshot()
            }
            MSG_TYPE_HELLO_ACK -> {
                pushRuntimeState("协商成功，剪贴板共享中。")
                syncCurrentClipboardSnapshot()
            }
            MSG_TYPE_PING -> {
                serviceScope.launch {
                    sendJson(
                        JSONObject()
                            .put("type", MSG_TYPE_PONG)
                            .put("ts", System.currentTimeMillis())
                            .put("from", deviceId)
                    )
                }
                pushRuntimeState()
            }
            MSG_TYPE_PONG -> {
                pushRuntimeState()
            }
            MSG_TYPE_CLIP -> {
                handleRemoteClipboardMessage(json)
            }
        }
    }

    private fun handleRemoteClipboardMessage(json: JSONObject) {
        val msgId = json.optString("msgId")
        if (msgId.isBlank()) return
        if (isRecentMessageId(msgId)) return

        val incomingText = json.optString("text", "")
        if (incomingText.length > MAX_CLIPBOARD_TEXT_LENGTH) return
        val hash = json.optString("hash").ifBlank { sha256(incomingText) }
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return

        suppressLocalHash = hash
        suppressLocalUntilMs = System.currentTimeMillis() + LOCAL_SUPPRESS_WINDOW_MS
        lastObservedLocalHash = hash
        runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("clipboard_sync_remote", incomingText))
        }
        receivedCount += 1
        lastRemotePreview = preview(incomingText)
        pushRuntimeState("收到对端剪贴板更新。")
    }

    private fun handleLocalClipboardChanged() {
        val text = readClipboardPlainText() ?: return
        syncLocalClipboardText(text, source = "listener")
    }

    private fun readClipboardPlainText(): String? {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        val item = clip.getItemAt(0)
        return item.coerceToText(this)?.toString()
    }

    private fun syncLocalClipboardText(text: String, source: String, forceSend: Boolean = false) {
        val normalized = text
        val hash = sha256(normalized)
        if (!forceSend && source != "snapshot" && hash == lastObservedLocalHash) {
            return
        }
        lastObservedLocalHash = hash

        if (!hasActiveConnection()) return
        if (normalized.length > MAX_CLIPBOARD_TEXT_LENGTH) {
            pushRuntimeState("本地剪贴板文本过长，已跳过本次同步。")
            return
        }
        val now = System.currentTimeMillis()
        val suppressHash = suppressLocalHash
        if (suppressHash != null && suppressHash == hash && now <= suppressLocalUntilMs) {
            return
        }
        if (!forceSend && hash == lastSentHash) {
            return
        }
        serviceScope.launch {
            val sent = sendJson(
                JSONObject()
                    .put("type", MSG_TYPE_CLIP)
                    .put("msgId", "clip_${UUID.randomUUID()}")
                    .put("from", deviceId)
                    .put("hash", hash)
                    .put("text", normalized)
                    .put("ts", now)
            )
            if (sent) {
                lastSentHash = hash
                sentCount += 1
                lastLocalPreview = preview(normalized)
                pushRuntimeState("本地剪贴板已同步。")
            }
        }
    }

    private fun syncCurrentClipboardSnapshot() {
        val text = readClipboardPlainText() ?: return
        syncLocalClipboardText(text, source = "snapshot")
    }

    private suspend fun sendJson(payload: JSONObject): Boolean {
        val writer = synchronized(socketLock) { activeWriter } ?: return false
        return runCatching {
            writerMutex.withLock {
                writer.write(payload.toString())
                writer.newLine()
                writer.flush()
            }
        }.onFailure {
            closeActiveSocket("发送失败，等待重连。")
        }.isSuccess
    }

    private fun hasActiveConnection(): Boolean {
        val socket = synchronized(socketLock) { activeSocket }
        return socket != null && socket.isConnected && !socket.isClosed
    }

    private fun closeActiveSocket(reason: String) {
        val socketToClose: Socket?
        synchronized(socketLock) {
            socketToClose = activeSocket
            activeSocket = null
            activeWriter = null
            activePeerEndpoint = ""
        }
        runCatching { socketToClose?.close() }
        pushRuntimeState(reason)
    }

    private fun forceReconnect(reason: String) {
        reconnectAttempts = 0
        reconnectWindowStartMs = System.currentTimeMillis()
        reconnectStopped = false
        reconnectStopReason = null
        closeActiveSocket(reason)
    }

    private fun isReconnectAllowed(config: ClipboardSyncConfig): Boolean {
        if (config.peerHost.isBlank() || config.peerPort !in 1..65535) return false
        if (reconnectStopped) return false
        val limit = config.reconnectLimit.coerceAtLeast(0)
        when (config.reconnectMode) {
            ClipboardSyncReconnectMode.ALWAYS -> return true
            ClipboardSyncReconnectMode.ATTEMPTS -> {
                if (limit <= 0) return true
                val allowed = reconnectAttempts < limit
                if (!allowed) {
                    reconnectStopped = true
                    reconnectStopReason = "自动重连已达到次数上限（$limit 次）。"
                }
                return allowed
            }
            ClipboardSyncReconnectMode.DURATION -> {
                if (limit <= 0) return true
                val used = System.currentTimeMillis() - reconnectWindowStartMs
                val limitMs = limit * 60_000L
                val allowed = used <= limitMs
                if (!allowed) {
                    reconnectStopped = true
                    reconnectStopReason = "自动重连已达到时长上限（$limit 分钟）。"
                }
                return allowed
            }
        }
    }

    private fun isRecentMessageId(msgId: String): Boolean {
        synchronized(recentMessageIdSet) {
            if (recentMessageIdSet.contains(msgId)) return true
            recentMessageIdSet += msgId
            recentMessageIds.addLast(msgId)
            while (recentMessageIds.size > RECENT_MESSAGE_ID_LIMIT) {
                val removed = recentMessageIds.removeFirst()
                recentMessageIdSet.remove(removed)
            }
        }
        return false
    }

    private fun pushRuntimeState(message: String? = null) {
        val connected = hasActiveConnection()
        val now = System.currentTimeMillis()
        val peerOnline = connected && now - lastIncomingAtMs <= PEER_ONLINE_TIMEOUT_MS
        val endpoint = synchronized(socketLock) { activePeerEndpoint }
        ClipboardSyncRuntime.update { current ->
            current.copy(
                running = true,
                localHost = currentConfig.localHost,
                localPort = currentConfig.localPort,
                peerHost = currentConfig.peerHost,
                peerPort = currentConfig.peerPort,
                connected = connected,
                peerOnline = peerOnline,
                peerEndpoint = endpoint,
                sessionMessage = message ?: current.sessionMessage,
                sentCount = sentCount,
                receivedCount = receivedCount,
                reconnectAttempts = reconnectAttempts,
                reconnectStopped = reconnectStopped,
                reconnectReason = reconnectStopReason,
                lastLocalTextPreview = lastLocalPreview,
                lastRemoteTextPreview = lastRemotePreview,
                autoReconnectMode = currentConfig.reconnectMode,
                autoReconnectLimit = currentConfig.reconnectLimit,
                focusOverlayEnabled = currentConfig.focusOverlayEnabled,
                focusOverlayActive = overlayFocusActive,
                overlayPermissionGranted = hasOverlayPermission(),
                activityProbeEnabled = currentConfig.activityProbeEnabled,
                activityProbeIntervalMs = currentConfig.activityProbeIntervalMs,
                activityProbeRunning = activityProbeJob?.isActive == true,
                quickProbeMagnetEnabled = currentConfig.quickProbeMagnetEnabled,
                quickProbeMagnetActive = magnetActive,
                quickProbeMagnetSizeDp = currentConfig.quickProbeMagnetSizeDp,
                quickProbeMagnetAlpha = currentConfig.quickProbeMagnetAlpha,
                lastMessageAt = lastIncomingAtMs
            )
        }
        ensureForeground()
    }

    companion object {
        private const val ACTION_START_OR_UPDATE = "com.yuliwen.filetran.clipboardsync.START_OR_UPDATE"
        private const val ACTION_STOP = "com.yuliwen.filetran.clipboardsync.STOP"
        private const val ACTION_RECONNECT_NOW = "com.yuliwen.filetran.clipboardsync.RECONNECT_NOW"
        private const val ACTION_TRIGGER_MANUAL_PROBE = "com.yuliwen.filetran.clipboardsync.TRIGGER_MANUAL_PROBE"

        private const val EXTRA_LOCAL_HOST = "extra_local_host"
        private const val EXTRA_LOCAL_PORT = "extra_local_port"
        private const val EXTRA_PEER_HOST = "extra_peer_host"
        private const val EXTRA_PEER_PORT = "extra_peer_port"
        private const val EXTRA_RECONNECT_MODE = "extra_reconnect_mode"
        private const val EXTRA_RECONNECT_LIMIT = "extra_reconnect_limit"
        private const val EXTRA_FOCUS_OVERLAY_ENABLED = "extra_focus_overlay_enabled"
        private const val EXTRA_ACTIVITY_PROBE_ENABLED = "extra_activity_probe_enabled"
        private const val EXTRA_ACTIVITY_PROBE_INTERVAL_MS = "extra_activity_probe_interval_ms"
        private const val EXTRA_QUICK_PROBE_MAGNET_ENABLED = "extra_quick_probe_magnet_enabled"
        private const val EXTRA_QUICK_PROBE_MAGNET_SIZE_DP = "extra_quick_probe_magnet_size_dp"
        private const val EXTRA_QUICK_PROBE_MAGNET_ALPHA = "extra_quick_probe_magnet_alpha"
        private const val EXTRA_MANUAL_PROBE_SOURCE = "extra_manual_probe_source"

        private const val CLIPBOARD_SYNC_PREFS = "clipboard_sync_prefs"
        private const val CLIPBOARD_SYNC_DEVICE_ID = "clipboard_sync_device_id"

        private const val CLIPBOARD_SYNC_CHANNEL_ID = "clipboard_sync_channel"
        private const val CLIPBOARD_SYNC_NOTIFICATION_ID = 32401

        private const val MSG_TYPE_HELLO = "hello"
        private const val MSG_TYPE_HELLO_ACK = "hello_ack"
        private const val MSG_TYPE_PING = "ping"
        private const val MSG_TYPE_PONG = "pong"
        private const val MSG_TYPE_CLIP = "clip"

        private const val SOCKET_CONNECT_TIMEOUT_MS = 3500
        private const val SOCKET_READ_TIMEOUT_MS = 12_000
        private const val CONNECT_RETRY_INTERVAL_MS = 2_500L
        private const val HEARTBEAT_INTERVAL_MS = 4_000L
        private const val HEARTBEAT_TIMEOUT_MS = 20_000L
        private const val PEER_ONLINE_TIMEOUT_MS = 12_000L
        private const val MAX_CLIPBOARD_TEXT_LENGTH = 100_000
        private const val LOCAL_SUPPRESS_WINDOW_MS = 8_000L
        private const val RECENT_MESSAGE_ID_LIMIT = 200
        private const val CLIPBOARD_POLL_INTERVAL_MS = 1_200L
        private const val OVERLAY_FOCUS_REFRESH_MS = 1_500L
        private const val MIN_ACTIVITY_PROBE_INTERVAL_MS = 400
        private const val MAX_ACTIVITY_PROBE_INTERVAL_MS = 15_000
        private const val TRANSIENT_PROBE_READ_RETRY_COUNT = 4
        private const val TRANSIENT_PROBE_READ_DELAY_MS = 120L
        private const val FOCUS_WAIT_TIMEOUT_MS = 380L
        private const val FOCUS_WAIT_STEP_MS = 24L
        private const val MANUAL_PROBE_ACTION_COOLDOWN_MS = 1_200L
        private const val MANUAL_PROBE_PANEL_SETTLE_MS = 320L
        private const val MANUAL_SOURCE_NOTIFICATION = "notification"
        private const val MIN_MAGNET_SIZE_DP = 36
        private const val MAX_MAGNET_SIZE_DP = 120
        private const val MIN_MAGNET_ALPHA = 0.2f
        private const val MAX_MAGNET_ALPHA = 1.0f
        private const val MAGNET_PREFS = "clipboard_sync_magnet_prefs"
        private const val MAGNET_POS_X = "magnet_pos_x"
        private const val MAGNET_POS_Y = "magnet_pos_y"
        private const val CLIPBOARD_SYNC_KEY_ENABLED = "clipboard_sync_enabled"
        private const val CLIPBOARD_SYNC_KEY_LOCAL_HOST = "clipboard_sync_local_host"
        private const val CLIPBOARD_SYNC_KEY_LOCAL_PORT = "clipboard_sync_local_port"
        private const val CLIPBOARD_SYNC_KEY_PEER_HOST = "clipboard_sync_peer_host"
        private const val CLIPBOARD_SYNC_KEY_PEER_PORT = "clipboard_sync_peer_port"
        private const val CLIPBOARD_SYNC_KEY_RECONNECT_MODE = "clipboard_sync_reconnect_mode"
        private const val CLIPBOARD_SYNC_KEY_RECONNECT_LIMIT = "clipboard_sync_reconnect_limit"
        private const val CLIPBOARD_SYNC_KEY_FOCUS_OVERLAY_ENABLED = "clipboard_sync_focus_overlay_enabled"
        private const val CLIPBOARD_SYNC_KEY_ACTIVITY_PROBE_ENABLED = "clipboard_sync_activity_probe_enabled"
        private const val CLIPBOARD_SYNC_KEY_ACTIVITY_PROBE_INTERVAL_MS = "clipboard_sync_activity_probe_interval_ms"
        private const val CLIPBOARD_SYNC_KEY_QUICK_MAGNET_ENABLED = "clipboard_sync_quick_magnet_enabled"
        private const val CLIPBOARD_SYNC_KEY_QUICK_MAGNET_SIZE_DP = "clipboard_sync_quick_magnet_size_dp"
        private const val CLIPBOARD_SYNC_KEY_QUICK_MAGNET_ALPHA = "clipboard_sync_quick_magnet_alpha"

        fun startOrUpdate(context: Context, config: ClipboardSyncConfig) {
            val appContext = context.applicationContext
            persistConfig(appContext, config, enabled = true)
            val intent = Intent(appContext, ClipboardSyncService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_LOCAL_HOST, config.localHost)
                putExtra(EXTRA_LOCAL_PORT, config.localPort)
                putExtra(EXTRA_PEER_HOST, config.peerHost)
                putExtra(EXTRA_PEER_PORT, config.peerPort)
                putExtra(EXTRA_RECONNECT_MODE, config.reconnectMode.name)
                putExtra(EXTRA_RECONNECT_LIMIT, config.reconnectLimit)
                putExtra(EXTRA_FOCUS_OVERLAY_ENABLED, config.focusOverlayEnabled)
                putExtra(EXTRA_ACTIVITY_PROBE_ENABLED, config.activityProbeEnabled)
                putExtra(EXTRA_QUICK_PROBE_MAGNET_ENABLED, config.quickProbeMagnetEnabled)
                putExtra(
                    EXTRA_QUICK_PROBE_MAGNET_SIZE_DP,
                    config.quickProbeMagnetSizeDp.coerceIn(MIN_MAGNET_SIZE_DP, MAX_MAGNET_SIZE_DP)
                )
                putExtra(
                    EXTRA_QUICK_PROBE_MAGNET_ALPHA,
                    config.quickProbeMagnetAlpha.coerceIn(MIN_MAGNET_ALPHA, MAX_MAGNET_ALPHA)
                )
                putExtra(
                    EXTRA_ACTIVITY_PROBE_INTERVAL_MS,
                    config.activityProbeIntervalMs.coerceIn(
                        MIN_ACTIVITY_PROBE_INTERVAL_MS,
                        MAX_ACTIVITY_PROBE_INTERVAL_MS
                    )
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
        }

        fun reconnectNow(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, ClipboardSyncService::class.java).apply {
                action = ACTION_RECONNECT_NOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            markEnabledFlag(appContext, false)
            appContext.stopService(Intent(appContext, ClipboardSyncService::class.java))
        }

        fun syncFromPrefs(context: Context) {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(CLIPBOARD_SYNC_PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(CLIPBOARD_SYNC_KEY_ENABLED, false)) return
            startOrUpdate(appContext, readPersistedConfig(appContext))
        }

        fun saveDraftConfig(context: Context, config: ClipboardSyncConfig) {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(CLIPBOARD_SYNC_PREFS, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean(CLIPBOARD_SYNC_KEY_ENABLED, false)
            persistConfig(appContext, config, enabled)
        }

        private fun markEnabledFlag(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(CLIPBOARD_SYNC_PREFS, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(CLIPBOARD_SYNC_KEY_ENABLED, enabled).apply()
        }

        private fun persistConfig(context: Context, config: ClipboardSyncConfig, enabled: Boolean) {
            val prefs = context.getSharedPreferences(CLIPBOARD_SYNC_PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(CLIPBOARD_SYNC_KEY_ENABLED, enabled)
                .putString(CLIPBOARD_SYNC_KEY_LOCAL_HOST, config.localHost)
                .putInt(CLIPBOARD_SYNC_KEY_LOCAL_PORT, config.localPort)
                .putString(CLIPBOARD_SYNC_KEY_PEER_HOST, config.peerHost)
                .putInt(CLIPBOARD_SYNC_KEY_PEER_PORT, config.peerPort)
                .putString(CLIPBOARD_SYNC_KEY_RECONNECT_MODE, config.reconnectMode.name)
                .putInt(CLIPBOARD_SYNC_KEY_RECONNECT_LIMIT, config.reconnectLimit)
                .putBoolean(CLIPBOARD_SYNC_KEY_FOCUS_OVERLAY_ENABLED, config.focusOverlayEnabled)
                .putBoolean(CLIPBOARD_SYNC_KEY_ACTIVITY_PROBE_ENABLED, config.activityProbeEnabled)
                .putInt(CLIPBOARD_SYNC_KEY_ACTIVITY_PROBE_INTERVAL_MS, config.activityProbeIntervalMs)
                .putBoolean(CLIPBOARD_SYNC_KEY_QUICK_MAGNET_ENABLED, config.quickProbeMagnetEnabled)
                .putInt(CLIPBOARD_SYNC_KEY_QUICK_MAGNET_SIZE_DP, config.quickProbeMagnetSizeDp)
                .putFloat(CLIPBOARD_SYNC_KEY_QUICK_MAGNET_ALPHA, config.quickProbeMagnetAlpha)
                .apply()
        }

        fun readPersistedConfig(context: Context): ClipboardSyncConfig {
            val prefs = context.getSharedPreferences(CLIPBOARD_SYNC_PREFS, Context.MODE_PRIVATE)
            val reconnectMode = prefs.getString(
                CLIPBOARD_SYNC_KEY_RECONNECT_MODE,
                ClipboardSyncReconnectMode.ALWAYS.name
            ).orEmpty().let { raw ->
                runCatching { ClipboardSyncReconnectMode.valueOf(raw) }
                    .getOrDefault(ClipboardSyncReconnectMode.ALWAYS)
            }
            return ClipboardSyncConfig(
                localHost = prefs.getString(CLIPBOARD_SYNC_KEY_LOCAL_HOST, "").orEmpty(),
                localPort = prefs.getInt(CLIPBOARD_SYNC_KEY_LOCAL_PORT, CLIPBOARD_SYNC_DEFAULT_PORT).coerceIn(1, 65535),
                peerHost = prefs.getString(CLIPBOARD_SYNC_KEY_PEER_HOST, "").orEmpty(),
                peerPort = prefs.getInt(CLIPBOARD_SYNC_KEY_PEER_PORT, 0).coerceIn(0, 65535),
                reconnectMode = reconnectMode,
                reconnectLimit = prefs.getInt(CLIPBOARD_SYNC_KEY_RECONNECT_LIMIT, 0).coerceAtLeast(0),
                focusOverlayEnabled = prefs.getBoolean(CLIPBOARD_SYNC_KEY_FOCUS_OVERLAY_ENABLED, true),
                activityProbeEnabled = prefs.getBoolean(CLIPBOARD_SYNC_KEY_ACTIVITY_PROBE_ENABLED, false),
                activityProbeIntervalMs = prefs.getInt(
                    CLIPBOARD_SYNC_KEY_ACTIVITY_PROBE_INTERVAL_MS,
                    1000
                ).coerceIn(MIN_ACTIVITY_PROBE_INTERVAL_MS, MAX_ACTIVITY_PROBE_INTERVAL_MS),
                quickProbeMagnetEnabled = prefs.getBoolean(CLIPBOARD_SYNC_KEY_QUICK_MAGNET_ENABLED, false),
                quickProbeMagnetSizeDp = prefs.getInt(
                    CLIPBOARD_SYNC_KEY_QUICK_MAGNET_SIZE_DP,
                    54
                ).coerceIn(MIN_MAGNET_SIZE_DP, MAX_MAGNET_SIZE_DP),
                quickProbeMagnetAlpha = prefs.getFloat(
                    CLIPBOARD_SYNC_KEY_QUICK_MAGNET_ALPHA,
                    0.82f
                ).coerceIn(MIN_MAGNET_ALPHA, MAX_MAGNET_ALPHA)
            )
        }
    }
}

private const val CLIPBOARD_SYNC_DEFAULT_PORT = 19333
private const val CLIPBOARD_SYNC_QR_TYPE = "filetran_clip_sync_v1"

fun buildClipboardSyncSharePayload(host: String, port: Int): String {
    val safePort = port.coerceIn(1, 65535)
    val endpoint = "filetranclip://${NetworkUtils.formatHostForUrl(host)}:$safePort"
    return JSONObject()
        .put("type", CLIPBOARD_SYNC_QR_TYPE)
        .put("endpoint", endpoint)
        .put("host", host)
        .put("port", safePort)
        .toString()
}

fun parseClipboardSyncPeerPayload(raw: String): Pair<String, Int>? {
    val content = raw.trim()
    if (content.isBlank()) return null
    if (content.startsWith("filetranclip://")) {
        return parseClipboardHostPort(content.removePrefix("filetranclip://"))
    }
    runCatching {
        val json = JSONObject(content)
        if (json.optString("type") == CLIPBOARD_SYNC_QR_TYPE) {
            val endpoint = json.optString("endpoint")
            if (endpoint.startsWith("filetranclip://")) {
                parseClipboardHostPort(endpoint.removePrefix("filetranclip://"))?.let { return it }
            }
            val host = json.optString("host").trim().replace("%25", "%")
            val port = json.optInt("port", 0)
            if (host.isNotBlank() && port in 1..65535) return host to port
        }
    }
    return parseClipboardHostPort(content)
}

private fun parseClipboardHostPort(input: String): Pair<String, Int>? {
    val raw = input.trim()
    if (raw.isBlank()) return null
    if (raw.startsWith("[") && raw.contains("]:")) {
        val end = raw.indexOf("]")
        if (end <= 1 || end + 2 > raw.length) return null
        val host = raw.substring(1, end).trim().replace("%25", "%")
        val port = raw.substring(end + 2).trim().toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return host to port
    }
    val colon = raw.lastIndexOf(':')
    if (colon <= 0 || colon == raw.length - 1) return null
    val host = raw.substring(0, colon).trim().replace("%25", "%")
    val port = raw.substring(colon + 1).trim().toIntOrNull() ?: return null
    if (host.isBlank() || port !in 1..65535) return null
    return host to port
}

private fun preview(text: String): String {
    return text.replace('\n', ' ').replace('\r', ' ').take(90)
}

private fun sha256(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(text.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
