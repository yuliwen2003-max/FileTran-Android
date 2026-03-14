package com.yuliwen.filetran

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private data class BgDone(val fileName: String, val filePath: String, val fileSize: Long)

private enum class BgGalleryMediaKind {
    IMAGE,
    VIDEO
}

private data class PendingLocalSendDecision(
    val invite: QuickSendInvite,
    val defaultAccept: Boolean,
    val timeoutSeconds: Int,
    val waitLatch: CountDownLatch = CountDownLatch(1),
    @Volatile var accepted: Boolean? = null,
    @Volatile var hiddenByUser: Boolean = false
)

class NeighborDiscoveryBackgroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Mutex()
    private val floatingPopup by lazy { NeighborFloatingPopupController(this) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val localSendDecisionLock = Any()

    private var runtime: LanNeighborRuntime? = null
    private var localSendRuntime: LocalSendRuntime? = null
    private var pendingInvite: QuickSendInvite? = null
    @Volatile
    private var pendingLocalSendDecision: PendingLocalSendDecision? = null
    private var receivingRequestId: String? = null
    private var inviteTimerJob: Job? = null
    private var inviteDecisionJob: Job? = null
    private var receiveJob: Job? = null
    private var receiveControl: FileDownloadManager.DownloadControl? = null
    private var reverseServer: ReversePushTcpServer? = null
    private var reverseWaitJob: Job? = null
    private var doneFile: BgDone? = null
    private var runtimeSwitchJob: Job? = null
    private var inviteDefaultAccept = false
    private var inviteSecondsLeft = 0
    private var inviteSheetHiddenByUser = false
    private var acceptNotificationOnlyRequestId: String? = null
    private var receiveNotificationOnly = false
    private var overlayHiddenDuringReceive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_APP_FOREGROUND -> {
                val foreground = intent.getBooleanExtra(EXTRA_APP_FOREGROUND, false)
                setAppForeground(foreground)
                Log.i("NeighborRoute", "app foreground=$foreground pending=${pendingInvite?.requestId} receiving=$receivingRequestId")
                if (!shouldRun()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startFg()
                runtimeSwitchJob?.cancel()
                if (foreground) {
                    // App is visible now; remove background overlay immediately to avoid touch interception.
                    floatingPopup.hideImmediate()
                    dismissInviteNotification()
                    if (!receiveNotificationOnly) {
                        dismissReceiveNotification()
                    }
                    stopRuntime()
                } else {
                    runtimeSwitchJob = scope.launch {
                        // Wait briefly so activity-side runtime can fully release UDP port.
                        delay(400)
                        if (!isAppForeground() && shouldRun()) {
                            startRuntime()
                        }
                    }
                }
                return START_STICKY
            }

            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_INVITE_ACCEPT -> {
                val id = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
                if (id.isNotBlank() && tryResolveLocalSendDecision(id, accept = true)) {
                    return START_STICKY
                }
                if (intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false) && id.isNotBlank()) {
                    acceptNotificationOnlyRequestId = id
                }
                scope.launch { decideById(id, accept = true, timedOut = false) }
                return START_STICKY
            }

            ACTION_INVITE_REJECT -> {
                val id = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
                if (id.isNotBlank() && tryResolveLocalSendDecision(id, accept = false)) {
                    return START_STICKY
                }
                acceptNotificationOnlyRequestId = null
                scope.launch { decideById(id, accept = false, timedOut = false) }
                return START_STICKY
            }

            ACTION_INVITE_HIDE -> {
                val id = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
                if (id.isNotBlank() && tryHideLocalSendDecision(id)) {
                    return START_STICKY
                }
                if (id.isNotBlank() && pendingInvite?.requestId == id) {
                    inviteSheetHiddenByUser = true
                    hideSheet()
                    pendingInvite?.let { invite ->
                        showInviteNotification(invite, inviteSecondsLeft, inviteDefaultAccept)
                    }
                }
                return START_STICKY
            }

            ACTION_RECEIVE_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
                if (id.isNotBlank() && id == receivingRequestId) {
                    cancelReceive("user_cancelled")
                }
                return START_STICKY
            }

            ACTION_RECEIVE_HIDE -> {
                val id = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
                if (id.isNotBlank() && id == receivingRequestId) {
                    overlayHiddenDuringReceive = true
                    hideSheet()
                }
                return START_STICKY
            }

            ACTION_DONE_OPEN_DEFAULT -> {
                val path = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
                if (path.isNotBlank()) {
                    openFile(path, chooser = false)
                } else {
                    doneFile?.let { openFile(it.filePath, chooser = false) }
                }
                doneFile = null
                dismissReceiveNotification()
                hideSheet()
                return START_STICKY
            }

            ACTION_DONE_OPEN_CHOOSER -> {
                val path = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
                if (path.isNotBlank()) {
                    openFile(path, chooser = true)
                } else {
                    doneFile?.let { openFile(it.filePath, chooser = true) }
                }
                doneFile = null
                dismissReceiveNotification()
                hideSheet()
                return START_STICKY
            }

            ACTION_DONE_IMPORT_GALLERY -> {
                val path = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
                    .ifBlank { doneFile?.filePath.orEmpty() }
                if (path.isNotBlank()) {
                    val ok = importMediaToGallery(path)
                    toast(if (ok) "已导入图库" else "导入图库失败")
                }
                return START_STICKY
            }

            ACTION_DONE_OPEN_DOWNLOADS -> {
                openDownloads()
                doneFile = null
                dismissReceiveNotification()
                hideSheet()
                return START_STICKY
            }

            ACTION_DONE_DISMISS -> {
                doneFile = null
                dismissReceiveNotification()
                hideSheet()
                return START_STICKY
            }
        }

        if (!shouldRun()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startFg()
        if (isAppForeground()) {
            stopRuntime()
        } else {
            startRuntime()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        inviteTimerJob?.cancel()
        inviteDecisionJob?.cancel()
        runtimeSwitchJob?.cancel()
        releasePendingLocalSendDecision(defaultAccept = false)
        acceptNotificationOnlyRequestId = null
        receiveControl?.cancelled = true
        receiveJob?.cancel()
        reverseWaitJob?.cancel()
        stopReverseServer()
        stopRuntime()
        dismissInviteNotification()
        dismissReceiveNotification()
        hideSheet()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startRuntime() {
        if (isAppForeground()) return
        val settings = NeighborDiscoverySettingsStore.get(this)
        val existing = runtime
        if (existing?.isRunning() != true) {
            if (existing != null) {
                Log.w("NeighborRoute", "runtime instance stale, restarting")
                existing.stop()
                runtime = null
            }
            runtime = LanNeighborRuntime(
                context = applicationContext,
                onQuickSendInvite = { invite -> scope.launch { onInvite(invite) } },
                onQuickSendCancel = { requestId -> scope.launch { onCancel(requestId) } },
                ownerTag = "service"
            ).also { it.start() }
        }

        if (!settings.localSendV2Enabled) {
            localSendRuntime?.let {
                LocalSendRuntimeBridge.detach(it)
                it.stop()
            }
            localSendRuntime = null
            return
        }

        val localExisting = localSendRuntime
        if (localExisting?.isRunning() != true) {
            localExisting?.let {
                LocalSendRuntimeBridge.detach(it)
                it.stop()
            }
            val candidate = LocalSendRuntime(
                context = applicationContext,
                ownerTag = "service",
                onPeersChanged = {},
                onReceiveEvent = { event ->
                    scope.launch { onLocalSendEvent(event) }
                },
                canAcceptIncoming = {
                    pendingInvite == null &&
                        pendingLocalSendDecision == null &&
                        receivingRequestId == null &&
                        receiveJob?.isActive != true &&
                        reverseServer == null
                },
                requestIncomingDecision = { request ->
                    requestLocalSendDecision(request)
                }
            )
            candidate.start()
            if (candidate.isRunning()) {
                localSendRuntime = candidate
                LocalSendRuntimeBridge.attach(candidate)
            } else {
                candidate.stop()
                localSendRuntime = null
                Log.w("LocalSendRuntime", "[service] failed to start runtime, keep detached")
            }
        }
    }

    private fun stopRuntime() {
        runtime?.stop()
        runtime = null
        localSendRuntime?.let {
            LocalSendRuntimeBridge.detach(it)
            it.stop()
        }
        localSendRuntime = null
    }

    private suspend fun onCancel(requestId: String) {
        if (requestId.isBlank()) return

        val pendingCancelled = stateLock.withLock {
            if (pendingInvite?.requestId == requestId) {
                pendingInvite = null
                true
            } else {
                false
            }
        }

        if (pendingCancelled) {
            inviteTimerJob?.cancel()
            inviteTimerJob = null
            inviteDecisionJob?.cancel()
            inviteDecisionJob = null
            inviteSheetHiddenByUser = false
            acceptNotificationOnlyRequestId = null
            dismissInviteNotification()
            withContext(Dispatchers.Main) { hideSheet() }
            return
        }

        if (receivingRequestId == requestId) {
            cancelReceive("sender_cancelled")
        }
    }

    private fun buildLocalSendPromptInvite(request: LocalSendIncomingRequest): QuickSendInvite {
        val senderName = request.senderAlias.ifBlank { "LocalSend sender" }
        val firstName = request.firstFileName.ifBlank { "LocalSend file" }
        val displayFileName = if (request.fileCount <= 1) {
            firstName
        } else {
            "$firstName 等${request.fileCount}个文件"
        }
        val displaySize = if (request.totalSize > 0L) request.totalSize else -1L
        return QuickSendInvite(
            requestId = request.requestId,
            senderName = senderName,
            senderHostName = "",
            senderIp = request.senderIp,
            fileName = displayFileName,
            fileSize = displaySize,
            downloadUrl = "localsend://incoming/${request.requestId}",
            preferredTransferMode = QUICK_TRANSFER_MODE_PULL
        )
    }

    private fun requestLocalSendDecision(request: LocalSendIncomingRequest): LocalSendIncomingDecision {
        val settings = NeighborDiscoverySettingsStore.get(this)
        if (!settings.enabled || !settings.localSendV2Enabled) {
            return LocalSendIncomingDecision.REJECT
        }
        if (settings.backgroundAutoReceiveNoPrompt) {
            return LocalSendIncomingDecision.ACCEPT
        }
        val defaultAccept = settings.defaultAction == NeighborDefaultAction.ACCEPT
        val timeoutSeconds = settings.timeoutSeconds.coerceIn(0, 25)
        if (timeoutSeconds <= 0) {
            return if (defaultAccept) {
                LocalSendIncomingDecision.ACCEPT
            } else {
                LocalSendIncomingDecision.REJECT
            }
        }

        val invite = buildLocalSendPromptInvite(request)
        val pending = PendingLocalSendDecision(
            invite = invite,
            defaultAccept = defaultAccept,
            timeoutSeconds = timeoutSeconds
        )

        synchronized(localSendDecisionLock) {
            val busy = pendingLocalSendDecision != null ||
                pendingInvite != null ||
                receivingRequestId != null ||
                receiveJob?.isActive == true ||
                reverseServer != null
            if (busy) {
                return LocalSendIncomingDecision.BUSY
            }
            pendingLocalSendDecision = pending
        }

        val notificationOnlyUi = supportsLiveNotificationUi()
        val requestFullscreenFallback = shouldUseLegacyPopupUi()
        if (notificationOnlyUi) {
            pending.hiddenByUser = true
            showInviteNotification(
                invite = invite,
                seconds = timeoutSeconds,
                defaultAccept = defaultAccept,
                requestFullscreen = false
            )
        } else {
            val shownLatch = CountDownLatch(1)
            mainHandler.post {
                val shown = showInviteSheet(invite, timeoutSeconds, defaultAccept)
                if (!shown) {
                    pending.hiddenByUser = true
                    showInviteNotification(
                        invite = invite,
                        seconds = timeoutSeconds,
                        defaultAccept = defaultAccept,
                        requestFullscreen = requestFullscreenFallback
                    )
                } else if (requestFullscreenFallback) {
                    showInviteNotification(
                        invite = invite,
                        seconds = timeoutSeconds,
                        defaultAccept = defaultAccept,
                        requestFullscreen = true
                    )
                } else {
                    dismissInviteNotification()
                }
                shownLatch.countDown()
            }
            shownLatch.await(2, TimeUnit.SECONDS)
        }

        val accepted = if (pending.waitLatch.await(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
            pending.accepted ?: defaultAccept
        } else {
            defaultAccept
        }

        synchronized(localSendDecisionLock) {
            if (pendingLocalSendDecision?.invite?.requestId == invite.requestId) {
                pendingLocalSendDecision = null
            }
        }
        dismissInviteNotification()
        mainHandler.post { hideSheet() }

        return if (accepted) {
            LocalSendIncomingDecision.ACCEPT
        } else {
            LocalSendIncomingDecision.REJECT
        }
    }

    private fun tryResolveLocalSendDecision(requestId: String, accept: Boolean): Boolean {
        val pending = synchronized(localSendDecisionLock) {
            val current = pendingLocalSendDecision
            if (current?.invite?.requestId == requestId) {
                current.accepted = accept
                pendingLocalSendDecision = null
                current
            } else {
                null
            }
        } ?: return false
        pending.waitLatch.countDown()
        dismissInviteNotification()
        scope.launch(Dispatchers.Main) { hideSheet() }
        return true
    }

    private fun tryHideLocalSendDecision(requestId: String): Boolean {
        val pending = synchronized(localSendDecisionLock) {
            val current = pendingLocalSendDecision
            if (current?.invite?.requestId == requestId) {
                current.hiddenByUser = true
                current
            } else {
                null
            }
        } ?: return false

        hideSheet()
        showInviteNotification(
            invite = pending.invite,
            seconds = pending.timeoutSeconds,
            defaultAccept = pending.defaultAccept
        )
        return true
    }

    private fun releasePendingLocalSendDecision(defaultAccept: Boolean) {
        val pending = synchronized(localSendDecisionLock) {
            val current = pendingLocalSendDecision
            pendingLocalSendDecision = null
            current
        } ?: return
        pending.accepted = defaultAccept
        pending.waitLatch.countDown()
        dismissInviteNotification()
        mainHandler.post { hideSheet() }
    }

    private suspend fun onLocalSendEvent(event: LocalSendReceiveEvent) {
        when (event.stage) {
            LocalSendReceiveStage.STARTED -> {
                receivingRequestId = event.sessionId
                receiveNotificationOnly = true
                overlayHiddenDuringReceive = true
                val fileName = event.fileName.ifBlank { "LocalSend file" }
                showReceiveNotification(
                    requestId = event.sessionId,
                    name = fileName,
                    progress = 0f,
                    downloaded = 0L,
                    total = event.fileSize.coerceAtLeast(0L),
                    speed = 0L
                )
            }

            LocalSendReceiveStage.PROGRESS -> {
                receivingRequestId = event.sessionId
                receiveNotificationOnly = true
                overlayHiddenDuringReceive = true
                val fileName = event.fileName.ifBlank { "LocalSend file" }
                val downloaded = event.receivedBytes.coerceAtLeast(0L)
                val total = when {
                    event.fileSize > 0L -> event.fileSize
                    downloaded > 0L -> downloaded
                    else -> 0L
                }
                val ratio = if (total > 0L) {
                    downloaded.toFloat() / total.toFloat()
                } else {
                    0f
                }.coerceIn(0f, 0.99f)
                showReceiveNotification(
                    requestId = event.sessionId,
                    name = fileName,
                    progress = ratio,
                    downloaded = downloaded,
                    total = total,
                    speed = 0L
                )
            }

            LocalSendReceiveStage.COMPLETED -> {
                val filePath = event.localPath.trim()
                if (filePath.isBlank()) {
                    dismissReceiveNotification()
                    return
                }
                val file = File(filePath)
                val finalName = event.fileName.ifBlank { file.name.ifBlank { "LocalSend file" } }
                val finalSize = if (file.exists()) file.length() else event.fileSize.coerceAtLeast(0L)
                doneFile = BgDone(
                    fileName = finalName,
                    filePath = filePath,
                    fileSize = finalSize
                )
                receivingRequestId = null
                overlayHiddenDuringReceive = false
                receiveNotificationOnly = true
                withContext(Dispatchers.Main) {
                    doneFile?.let { showReceiveDoneNotification(it) }
                }
            }

            LocalSendReceiveStage.CANCELLED,
            LocalSendReceiveStage.FAILED -> {
                receivingRequestId = null
                overlayHiddenDuringReceive = false
                receiveNotificationOnly = false
                dismissReceiveNotification()
                val msg = event.message.ifBlank {
                    if (event.stage == LocalSendReceiveStage.CANCELLED) {
                        "LocalSend sender cancelled"
                    } else {
                        "LocalSend receive failed"
                    }
                }
                withContext(Dispatchers.Main) {
                    hideSheet()
                    toast(msg)
                }
            }
        }
    }

    private suspend fun onInvite(invite: QuickSendInvite) {
        Log.i(
            "NeighborRoute",
            "onInvite requestId=${invite.requestId} foreground=${isAppForeground()} preferred=${invite.preferredTransferMode} senderIp=${invite.senderIp}"
        )
        if (isAppForeground()) {
            Log.i("NeighborRoute", "background service ignore invite in foreground requestId=${invite.requestId}")
            return
        }
        val settings = NeighborDiscoverySettingsStore.get(this)
        if (!settings.enabled) return

        val busy = stateLock.withLock {
            if (
                pendingInvite != null ||
                pendingLocalSendDecision != null ||
                receivingRequestId != null ||
                receiveJob?.isActive == true ||
                reverseServer != null
            ) {
                true
            } else {
                pendingInvite = invite
                false
            }
        }
        if (busy) {
            sendQuickSendInviteResponse(
                invite = invite,
                accepted = false,
                message = "receiver_busy",
                transferMode = QUICK_TRANSFER_MODE_PULL
            )
            return
        }

        if (settings.backgroundAutoReceiveNoPrompt) {
            decideById(invite.requestId, accept = true, timedOut = true)
            return
        }

        inviteDefaultAccept = settings.defaultAction == NeighborDefaultAction.ACCEPT
        inviteSecondsLeft = settings.timeoutSeconds.coerceIn(0, 25)
        inviteSheetHiddenByUser = false
        val notificationOnlyUi = supportsLiveNotificationUi()
        val requestFullscreenFallback = shouldUseLegacyPopupUi()

        if (inviteSecondsLeft == 0) {
            decideById(invite.requestId, accept = currentDefaultAccept(), timedOut = true)
            return
        }

        if (notificationOnlyUi) {
            inviteSheetHiddenByUser = true
            showInviteNotification(
                invite = invite,
                seconds = inviteSecondsLeft,
                defaultAccept = inviteDefaultAccept,
                requestFullscreen = false
            )
        } else {
            val shown = withContext(Dispatchers.Main) {
                showInviteSheet(invite, inviteSecondsLeft, inviteDefaultAccept)
            }
            if (!shown) {
                inviteSheetHiddenByUser = true
                showInviteNotification(
                    invite = invite,
                    seconds = inviteSecondsLeft,
                    defaultAccept = inviteDefaultAccept,
                    requestFullscreen = requestFullscreenFallback
                )
            } else {
                if (requestFullscreenFallback) {
                    // Legacy devices often suppress background overlays. Keep a full-screen notification fallback.
                    showInviteNotification(
                        invite = invite,
                        seconds = inviteSecondsLeft,
                        defaultAccept = inviteDefaultAccept,
                        requestFullscreen = true
                    )
                } else {
                    dismissInviteNotification()
                }
            }
        }

        inviteTimerJob?.cancel()
        inviteDecisionJob?.cancel()
        inviteTimerJob = scope.launch {
            var left = inviteSecondsLeft
            while (left > 0) {
                delay(1000)
                left -= 1
                inviteSecondsLeft = left

                val stillPending = stateLock.withLock { pendingInvite?.requestId == invite.requestId }
                if (!stillPending) return@launch

                inviteDefaultAccept = currentDefaultAccept()
                if (notificationOnlyUi || inviteSheetHiddenByUser) {
                    showInviteNotification(invite, left, inviteDefaultAccept, requestFullscreen = requestFullscreenFallback)
                } else {
                    val shown = withContext(Dispatchers.Main) {
                        showInviteSheet(invite, left, inviteDefaultAccept)
                    }
                    if (!shown) {
                        inviteSheetHiddenByUser = true
                        showInviteNotification(invite, left, inviteDefaultAccept, requestFullscreen = requestFullscreenFallback)
                    } else if (requestFullscreenFallback) {
                        showInviteNotification(invite, left, inviteDefaultAccept, requestFullscreen = true)
                    }
                }
            }
        }
        inviteDecisionJob = scope.launch {
            delay(inviteSecondsLeft * 1000L)
            decideById(invite.requestId, accept = currentDefaultAccept(), timedOut = true)
        }
    }

    private suspend fun decideById(id: String, accept: Boolean, timedOut: Boolean) {
        if (id.isBlank()) return

        val invite = stateLock.withLock {
            val current = pendingInvite
            if (current?.requestId == id) {
                pendingInvite = null
                current
            } else {
                null
            }
        } ?: return

        inviteTimerJob?.cancel()
        inviteDecisionJob?.cancel()
        inviteSheetHiddenByUser = false
        dismissInviteNotification()
        withContext(Dispatchers.Main) { hideSheet() }

        if (!accept) {
            val msg = if (timedOut) "timeout_auto_reject" else "rejected"
            acceptNotificationOnlyRequestId = null
            sendQuickSendInviteResponse(invite, accepted = false, message = msg, transferMode = QUICK_TRANSFER_MODE_PULL)
            return
        }
        val currentSettings = NeighborDiscoverySettingsStore.get(this)
        val notificationOnly = currentSettings.backgroundAutoReceiveNoPrompt || supportsLiveNotificationUi()
        acceptNotificationOnlyRequestId = null

        if (useReverse(invite)) {
            val endpoint = prepareReverseEndpoint(invite, notificationOnly = notificationOnly)
            if (endpoint.isNullOrBlank()) {
                sendQuickSendInviteResponse(
                    invite = invite,
                    accepted = false,
                    message = "reverse_endpoint_unavailable",
                    transferMode = QUICK_TRANSFER_MODE_PULL
                )
                return
            }
            sendQuickSendInviteResponse(
                invite = invite,
                accepted = true,
                message = "accepted_switch_reverse",
                transferMode = QUICK_TRANSFER_MODE_REVERSE_PUSH,
                reverseEndpoint = endpoint
            )
            startReverseWaitTimeout(invite.requestId)
            return
        }

        sendQuickSendInviteResponse(
            invite = invite,
            accepted = true,
            message = "accepted_start_receive",
            transferMode = QUICK_TRANSFER_MODE_PULL
        )
        startReceive(invite, notificationOnly = notificationOnly)
    }

    private fun startReceive(invite: QuickSendInvite, notificationOnly: Boolean) {
        reverseWaitJob?.cancel()
        stopReverseServer()
        receivingRequestId = invite.requestId
        receiveNotificationOnly = notificationOnly
        overlayHiddenDuringReceive = notificationOnly
        receiveControl = FileDownloadManager.DownloadControl()

        if (!notificationOnly) {
            scope.launch(Dispatchers.Main) {
                val shown = showProgressSheet(
                    requestId = invite.requestId,
                    name = invite.fileName,
                    progress = 0f,
                    downloaded = 0L,
                    total = invite.fileSize,
                    speed = 0L
                )
                if (!shown) {
                    showReceiveNotification(invite.requestId, invite.fileName, 0f, 0L, invite.fileSize, 0L)
                }
            }
        } else {
            showReceiveNotification(invite.requestId, invite.fileName, 0f, 0L, invite.fileSize, 0L)
        }

        receiveJob?.cancel()
        val control = receiveControl ?: return
        receiveJob = scope.launch {
            var last: DownloadProgress? = null
            try {
                FileDownloadManager.downloadFile(invite.downloadUrl, this@NeighborDiscoveryBackgroundService, control = control)
                    .collect { progress ->
                        last = progress
                        withContext(Dispatchers.Main) {
                            val fileName = progress.fileName.ifBlank { invite.fileName }
                            val shown = if (!overlayHiddenDuringReceive && !receiveNotificationOnly) {
                                showProgressSheet(
                                    requestId = invite.requestId,
                                    name = fileName,
                                    progress = progress.progress,
                                    downloaded = progress.downloadedBytes,
                                    total = progress.totalBytes,
                                    speed = progress.speed
                                )
                            } else {
                                false
                            }
                            if (!shown || overlayHiddenDuringReceive || receiveNotificationOnly) {
                                showReceiveNotification(
                                    invite.requestId,
                                    fileName,
                                    progress.progress,
                                    progress.downloadedBytes,
                                    progress.totalBytes,
                                    progress.speed
                                )
                            }
                        }
                    }

                val done = last ?: throw IOException("no_progress")
                val path = FileDownloadManager.getFilePath(done.fileName, this@NeighborDiscoveryBackgroundService)
                val file = File(path)
                val size = if (file.exists() && file.length() > 0L) file.length() else done.totalBytes

                DownloadHistoryManager(this@NeighborDiscoveryBackgroundService).addRecord(
                    DownloadRecord(
                        fileName = done.fileName,
                        filePath = path,
                        fileSize = size,
                        sourceUrl = invite.downloadUrl
                    )
                )

                doneFile = BgDone(done.fileName, path, size.coerceAtLeast(0L))
                receivingRequestId = null
                receiveControl = null
                overlayHiddenDuringReceive = false

                withContext(Dispatchers.Main) {
                    val payload = doneFile ?: return@withContext
                    if (receiveNotificationOnly) {
                        showReceiveDoneNotification(payload)
                    } else {
                        dismissReceiveNotification()
                        val shown = showDoneSheet(payload)
                        if (!shown) {
                            notifyDone(payload)
                        }
                    }
                }
                receiveNotificationOnly = false
            } catch (e: CancellationException) {
                onReceiveStop("receive_cancelled", last)
            } catch (e: Throwable) {
                if (e is IOException && e.message == "DOWNLOAD_CANCELLED") {
                    onReceiveStop("receive_cancelled", last)
                } else {
                    onReceiveStop("receive_failed: ${e.message ?: "unknown"}", last)
                }
            }
        }
    }

    private suspend fun prepareReverseEndpoint(invite: QuickSendInvite, notificationOnly: Boolean): String? {
        val routeTargetIp = extractIpv4HostFromUrl(invite.downloadUrl)
            ?: invite.responseIp.ifBlank { invite.senderIp }
        val fallback = NetworkUtils.getLocalIpAddress(this).orEmpty()
        val localIp = withContext(Dispatchers.IO) {
            if (routeTargetIp.isBlank()) {
                fallback
            } else {
                resolvePreferredIpv4ForTarget(targetIp = routeTargetIp, fallback = fallback)
            }
        }.orEmpty()
        if (localIp.isBlank()) {
            Log.w("QuickInviteRx", "bg reverse endpoint local ip empty requestId=${invite.requestId}")
            return null
        }

        reverseWaitJob?.cancel()
        stopReverseServer()
        receivingRequestId = invite.requestId
        receiveNotificationOnly = notificationOnly
        overlayHiddenDuringReceive = notificationOnly

        val ports = intArrayOf(REVERSE_PORT_PRIMARY, REVERSE_PORT_FALLBACK_1, REVERSE_PORT_FALLBACK_2)
        for (port in ports) {
            val server = ReversePushTcpServer(
                context = this,
                port = port,
                ipv6Mode = false,
                onProgress = { progress ->
                    scope.launch { onReverseProgress(invite, progress) }
                }
            )
            val started = runCatching {
                server.start()
                true
            }.getOrElse { e ->
                Log.w("QuickInviteRx", "bg reverse bind failed port=$port requestId=${invite.requestId}: ${e.message}")
                false
            }
            if (!started) {
                runCatching { server.stop() }
                continue
            }
            reverseServer = server
            if (notificationOnly) {
                showReceiveNotification(
                    requestId = invite.requestId,
                    name = invite.fileName,
                    progress = 0f,
                    downloaded = 0L,
                    total = invite.fileSize,
                    speed = 0L
                )
            } else {
                withContext(Dispatchers.Main) {
                    val shown = showProgressSheet(
                        requestId = invite.requestId,
                        name = invite.fileName,
                        progress = 0f,
                        downloaded = 0L,
                        total = invite.fileSize,
                        speed = 0L
                    )
                    if (!shown) {
                        showReceiveNotification(
                            requestId = invite.requestId,
                            name = invite.fileName,
                            progress = 0f,
                            downloaded = 0L,
                            total = invite.fileSize,
                            speed = 0L
                        )
                    }
                }
            }
            val endpoint = "filetranpush://${NetworkUtils.formatHostForUrl(localIp)}:$port"
            Log.i("QuickInviteRx", "bg reverse endpoint ready endpoint=$endpoint requestId=${invite.requestId}")
            return endpoint
        }

        receivingRequestId = null
        receiveNotificationOnly = false
        overlayHiddenDuringReceive = false
        stopReverseServer()
        return null
    }

    private fun startReverseWaitTimeout(requestId: String) {
        reverseWaitJob?.cancel()
        reverseWaitJob = scope.launch {
            delay(REVERSE_WAIT_TIMEOUT_MS)
            if (receivingRequestId == requestId && reverseServer != null) {
                onReverseReceiveStop("reverse_wait_timeout")
            }
        }
    }

    private suspend fun onReverseProgress(invite: QuickSendInvite, progress: ReversePushReceiveProgress) {
        when (progress.stage) {
            ReversePushReceiveStage.RECEIVING -> {
                reverseWaitJob?.cancel()
                val fileName = progress.fileName.ifBlank { invite.fileName }
                val total = when {
                    progress.totalBytes > 0L -> progress.totalBytes
                    invite.fileSize > 0L -> invite.fileSize
                    else -> 0L
                }
                val ratio = if (total > 0L) {
                    progress.receivedBytes.toFloat() / total.toFloat()
                } else {
                    0f
                }.coerceIn(0f, 0.99f)
                withContext(Dispatchers.Main) {
                    if (receiveNotificationOnly || overlayHiddenDuringReceive) {
                        showReceiveNotification(
                            requestId = invite.requestId,
                            name = fileName,
                            progress = ratio,
                            downloaded = progress.receivedBytes,
                            total = total,
                            speed = 0L
                        )
                    } else {
                        val shown = showProgressSheet(
                            requestId = invite.requestId,
                            name = fileName,
                            progress = ratio,
                            downloaded = progress.receivedBytes,
                            total = total,
                            speed = 0L
                        )
                        if (!shown) {
                            showReceiveNotification(
                                requestId = invite.requestId,
                                name = fileName,
                                progress = ratio,
                                downloaded = progress.receivedBytes,
                                total = total,
                                speed = 0L
                            )
                        }
                    }
                }
            }

            ReversePushReceiveStage.COMPLETED -> {
                reverseWaitJob?.cancel()
                val localPath = progress.localPath.orEmpty()
                if (localPath.isBlank()) {
                    onReverseReceiveStop("receive_failed: local_path_empty")
                    return
                }
                val file = File(localPath)
                val finalName = progress.fileName.ifBlank { file.name.ifBlank { invite.fileName } }
                val finalSize = if (file.exists() && file.length() > 0L) {
                    file.length()
                } else {
                    progress.totalBytes.coerceAtLeast(0L)
                }
                doneFile = BgDone(finalName, localPath, finalSize)
                receivingRequestId = null
                stopReverseServer()
                overlayHiddenDuringReceive = false
                withContext(Dispatchers.Main) {
                    val payload = doneFile ?: return@withContext
                    if (receiveNotificationOnly) {
                        showReceiveDoneNotification(payload)
                    } else {
                        dismissReceiveNotification()
                        val shown = showDoneSheet(payload)
                        if (!shown) {
                            notifyDone(payload)
                        }
                    }
                }
                receiveNotificationOnly = false
            }

            ReversePushReceiveStage.CANCELLED -> {
                onReverseReceiveStop("receive_cancelled")
            }

            ReversePushReceiveStage.FAILED -> {
                onReverseReceiveStop(progress.message ?: "receive_failed")
            }
        }
    }

    private fun onReverseReceiveStop(msg: String) {
        reverseWaitJob?.cancel()
        stopReverseServer()
        receivingRequestId = null
        overlayHiddenDuringReceive = false
        receiveNotificationOnly = false
        dismissReceiveNotification()
        scope.launch(Dispatchers.Main) {
            hideSheet()
            toast(msg)
        }
    }

    private fun stopReverseServer() {
        reverseServer?.let { server ->
            runCatching { server.cancelCurrentTransfer() }
            runCatching { server.stop() }
        }
        reverseServer = null
    }

    private fun extractIpv4HostFromUrl(url: String): String? {
        val host = runCatching { Uri.parse(url).host.orEmpty().trim() }.getOrDefault("")
        val parts = host.split('.')
        if (parts.size != 4) return null
        if (parts.any { it.toIntOrNull() !in 0..255 }) return null
        return host
    }

    private fun onReceiveStop(msg: String, last: DownloadProgress?) {
        receivingRequestId = null
        receiveControl = null
        reverseWaitJob?.cancel()
        stopReverseServer()
        overlayHiddenDuringReceive = false
        receiveNotificationOnly = false
        dismissReceiveNotification()

        last?.fileName?.takeIf { it.isNotBlank() }?.let { name ->
            runCatching {
                FileDownloadManager.deleteFile(FileDownloadManager.getFilePath(name, this))
            }
        }

        scope.launch(Dispatchers.Main) {
            hideSheet()
            toast(msg)
        }
    }

    private fun cancelReceive(reason: String) {
        receiveControl?.cancelled = true
        receiveJob?.cancel(CancellationException(reason))
        if (reverseServer != null || reverseWaitJob != null) {
            onReverseReceiveStop(reason)
        }
    }

    private fun useReverse(invite: QuickSendInvite): Boolean {
        return when (invite.preferredTransferMode.lowercase()) {
            QUICK_TRANSFER_MODE_REVERSE_PUSH -> true
            QUICK_TRANSFER_MODE_PULL -> false
            else -> {
                val localIp = NetworkUtils.getLocalIpAddress(this).orEmpty()
                prefix(invite.senderIp) != null &&
                    prefix(localIp) != null &&
                    prefix(invite.senderIp) != prefix(localIp)
            }
        }
    }

    private fun prefix(ip: String): String? {
        val parts = ip.trim().split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}"
    }

    private fun startFg() {
        ensureChannels()

        val launchIntent = PendingIntent.getActivity(
            this,
            4001,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            4002,
            Intent(this, NeighborDiscoveryBackgroundService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CH_FG)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("邻居发现后台增强已开启")
            .setContentText("后台持续监听邻居快传请求")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchIntent)
            .addAction(0, "Stop", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_FG, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTI_FG, notification)
        }
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CH_FG) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CH_FG, "邻居后台服务", NotificationManager.IMPORTANCE_LOW)
            )
        }
        if (manager.getNotificationChannel(CH_PROMPT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CH_PROMPT, "邻居传输请求", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0L, 180L, 120L, 180L)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }
        if (manager.getNotificationChannel(CH_PROGRESS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "邻居实时进度", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    enableVibration(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }
        if (manager.getNotificationChannel(CH_DONE) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CH_DONE, "邻居接收完成", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0L, 120L, 80L, 120L)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun showInviteNotification(
        invite: QuickSendInvite,
        seconds: Int,
        defaultAccept: Boolean,
        requestFullscreen: Boolean = false
    ) {
        ensureChannels()

        val accept = PendingIntent.getService(
            this,
            4101,
            Intent(this, NeighborDiscoveryBackgroundService::class.java).apply {
                action = ACTION_INVITE_ACCEPT
                putExtra(EXTRA_REQUEST_ID, invite.requestId)
                putExtra(EXTRA_FROM_NOTIFICATION, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val reject = PendingIntent.getService(
            this,
            4102,
            Intent(this, NeighborDiscoveryBackgroundService::class.java).apply {
                action = ACTION_INVITE_REJECT
                putExtra(EXTRA_REQUEST_ID, invite.requestId)
                putExtra(EXTRA_FROM_NOTIFICATION, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openSheet = PendingIntent.getActivity(
            this,
            4103,
            Intent(this, NeighborPopupActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = buildString {
            append("文件：")
            append(invite.fileName)
            if (invite.fileSize > 0L) {
                append(" · ")
                append(format(invite.fileSize))
            }
            append(" · ")
            append(seconds)
            append("秒后自动")
            append(if (defaultAccept) "接收" else "拒绝")
        }

        val notification = NotificationCompat.Builder(this, CH_PROMPT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("收到 ${invite.senderName} 的传输请求")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openSheet)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShortCriticalText("${seconds}s")
            .setRequestPromotedOngoing(true)
            .addAction(0, "拒绝", reject)
            .addAction(0, "接收", accept)
            .apply {
                if (requestFullscreen) {
                    setFullScreenIntent(openSheet, true)
                }
            }
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTI_INVITE, notification)
    }

    private fun dismissInviteNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTI_INVITE)
    }

    private fun showReceiveNotification(
        requestId: String,
        name: String,
        progress: Float,
        downloaded: Long,
        total: Long,
        speed: Long
    ) {
        ensureChannels()

        val cancel = PendingIntent.getService(
            this,
            4201,
            Intent(this, NeighborDiscoveryBackgroundService::class.java).apply {
                action = ACTION_RECEIVE_CANCEL
                putExtra(EXTRA_REQUEST_ID, requestId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val percent = (progress.coerceIn(0f, 1f) * 100f).roundToInt().coerceAtMost(99)
        val subtitle = "已接收 ${format(downloaded)} / ${format(total)} · ${format(speed)}/s"
        val notification = NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("接收中：$name")
            .setContentText("$percent% · $subtitle")
            .setSubText("$percent%")
            .setStyle(NotificationCompat.BigTextStyle().bigText("文件：$name\n$percent% · $subtitle"))
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setShortCriticalText("$percent%")
            .setRequestPromotedOngoing(true)
            .addAction(0, "取消", cancel)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTI_PROGRESS, notification)
    }

    private fun showReceiveDoneNotification(done: BgDone) {
        ensureChannels()
        dismissReceiveNotification()
        val openDefault = PendingIntent.getService(
            this,
            4301,
            Intent(this, NeighborDiscoveryBackgroundService::class.java).apply {
                action = ACTION_DONE_OPEN_DEFAULT
                putExtra(EXTRA_FILE_PATH, done.filePath)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openChooser = PendingIntent.getService(
            this,
            4304,
            Intent(this, NeighborDiscoveryBackgroundService::class.java).apply {
                action = ACTION_DONE_OPEN_CHOOSER
                putExtra(EXTRA_FILE_PATH, done.filePath)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openFolder = PendingIntent.getService(
            this,
            4302,
            Intent(this, NeighborDiscoveryBackgroundService::class.java).apply {
                action = ACTION_DONE_OPEN_DOWNLOADS
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismiss = PendingIntent.getService(
            this,
            4303,
            Intent(this, NeighborDiscoveryBackgroundService::class.java).apply {
                action = ACTION_DONE_DISMISS
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val importGallery = PendingIntent.getService(
            this,
            4305,
            Intent(this, NeighborDiscoveryBackgroundService::class.java).apply {
                action = ACTION_DONE_IMPORT_GALLERY
                putExtra(EXTRA_FILE_PATH, done.filePath)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("接收完成")
            .setContentText("${done.fileName} · ${format(done.fileSize)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("文件：${done.fileName}\n大小：${format(done.fileSize)}\n点击通知可选择打开方式"))
            .setContentIntent(openChooser)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText("完成")
            .addAction(0, "选择方式", openChooser)
            .addAction(0, "默认打开", openDefault)
            .apply {
                if (canImportMediaToGallery(done.filePath)) {
                    addAction(0, "导入图库", importGallery)
                }
            }
            .addAction(0, "文件夹", openFolder)
            .addAction(0, "关闭", dismiss)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTI_DONE_LIVE, notification)
    }

    private fun dismissReceiveNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTI_PROGRESS)
        manager.cancel(NOTI_DONE_LIVE)
    }

    private fun notifyDone(done: BgDone) {
        if (!canNotify()) return
        ensureChannels()

        val notification = NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("接收完成")
            .setContentText(done.fileName)
            .setAutoCancel(true)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTI_DONE, notification)
    }

    private fun showInviteSheet(invite: QuickSendInvite, seconds: Int, defaultAccept: Boolean): Boolean {
        val payload = NeighborSheetInvite(
            requestId = invite.requestId,
            senderName = invite.senderName,
            senderIp = invite.senderIp,
            fileName = invite.fileName,
            fileSize = invite.fileSize,
            secondsLeft = seconds,
            defaultAccept = defaultAccept
        )
        if (shouldUseLegacyPopupUi() && !isAppForeground()) {
            // Prefer background activity popup first to validate "background popup window" permission path.
            NeighborSheetBridge.showInvite(payload)
            val activityShown = showPopupActivity()
            if (activityShown) {
                Log.i("NeighborRoute", "show invite by bg activity requestId=${invite.requestId}")
                return true
            }
            Log.w("NeighborRoute", "bg activity popup failed, fallback to overlay requestId=${invite.requestId}")
            val shown = floatingPopup.showInvite(payload)
            if (shown) {
                Log.i("NeighborRoute", "show invite by overlay requestId=${invite.requestId}")
                return true
            }
        }
        NeighborSheetBridge.showInvite(payload)
        return showSheetActivity()
    }

    private fun showProgressSheet(
        requestId: String,
        name: String,
        progress: Float,
        downloaded: Long,
        total: Long,
        speed: Long
    ): Boolean {
        val payload = NeighborSheetProgress(
            requestId = requestId,
            fileName = name,
            progress = progress,
            downloadedBytes = downloaded,
            totalBytes = total,
            speedBytesPerSecond = speed
        )
        if (shouldUseLegacyPopupUi() && !isAppForeground()) {
            NeighborSheetBridge.showProgress(payload)
            val activityShown = showPopupActivity()
            if (activityShown) {
                Log.i("NeighborRoute", "show progress by bg activity requestId=$requestId")
                return true
            }
            Log.w("NeighborRoute", "bg progress activity failed, fallback overlay requestId=$requestId")
            val shown = floatingPopup.showProgress(payload)
            if (shown) {
                Log.i("NeighborRoute", "show progress by overlay requestId=$requestId")
                return true
            }
        }
        NeighborSheetBridge.showProgress(payload)
        return showSheetActivity()
    }

    private fun showDoneSheet(done: BgDone): Boolean {
        val payload = NeighborSheetDone(
            fileName = done.fileName,
            filePath = done.filePath,
            fileSize = done.fileSize,
            canImportGallery = canImportMediaToGallery(done.filePath)
        )
        if (shouldUseLegacyPopupUi() && !isAppForeground()) {
            NeighborSheetBridge.showDone(payload)
            val activityShown = showPopupActivity()
            if (activityShown) {
                Log.i("NeighborRoute", "show done by bg activity file=${done.fileName}")
                return true
            }
            Log.w("NeighborRoute", "bg done activity failed, fallback overlay file=${done.fileName}")
            val shown = floatingPopup.showDone(payload)
            if (shown) {
                Log.i("NeighborRoute", "show done by overlay file=${done.fileName}")
                return true
            }
        }
        NeighborSheetBridge.showDone(payload)
        return showSheetActivity()
    }

    private fun showSheetActivity(): Boolean {
        val intent = Intent(this, NeighborSheetActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun showPopupActivity(): Boolean {
        val intent = Intent(this, NeighborPopupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun hideSheet() {
        floatingPopup.hide()
        NeighborSheetBridge.hide()
    }

    private fun openFile(path: String, chooser: Boolean) {
        val file = File(path)
        if (!file.exists()) return

        val uri: Uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrElse { return }

        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            .orEmpty()
            .ifBlank { "*/*" }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (chooser) {
            startActivity(Intent.createChooser(viewIntent, "Choose app").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            runCatching { startActivity(viewIntent) }
        }
    }

    private fun openDownloads() {
        runCatching {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun detectGalleryMediaKind(filePath: String?): BgGalleryMediaKind? {
        val path = filePath?.trim().orEmpty()
        if (path.isBlank()) return null
        val ext = File(path).extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext).orEmpty()
        return when {
            mime.startsWith("image/") -> BgGalleryMediaKind.IMAGE
            mime.startsWith("video/") -> BgGalleryMediaKind.VIDEO
            else -> null
        }
    }

    private fun canImportMediaToGallery(filePath: String?): Boolean {
        return detectGalleryMediaKind(filePath) != null
    }

    private fun importMediaToGallery(filePath: String): Boolean {
        return runCatching {
            val source = File(filePath)
            if (!source.exists() || !source.isFile) return false
            val mediaKind = detectGalleryMediaKind(filePath) ?: return false
            val ext = source.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext).orEmpty().ifBlank {
                if (mediaKind == BgGalleryMediaKind.IMAGE) "image/*" else "video/*"
            }
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        if (mediaKind == BgGalleryMediaKind.IMAGE) "Pictures/FileTran" else "Movies/FileTran"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val targetUri = when (mediaKind) {
                BgGalleryMediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                BgGalleryMediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val resolver = contentResolver
            val insertUri = resolver.insert(targetUri, contentValues) ?: return false
            try {
                resolver.openOutputStream(insertUri)?.use { output ->
                    FileInputStream(source).use { input ->
                        input.copyTo(output, 16 * 1024)
                    }
                } ?: return false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val readyValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(insertUri, readyValues, null, null)
                }
                true
            } catch (_: Exception) {
                resolver.delete(insertUri, null, null)
                false
            }
        }.getOrDefault(false)
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun supportsLiveNotificationUi(): Boolean {
        return Build.VERSION.SDK_INT >= LIVE_NOTIFICATION_MIN_SDK && canNotify()
    }

    private fun shouldUseLegacyPopupUi(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    private fun format(value: Long): String {
        val v = value.coerceAtLeast(0L)
        return when {
            v < 1024L -> "${v}B"
            v < 1024L * 1024L -> "${v / 1024L}KB"
            v < 1024L * 1024L * 1024L -> "${v / (1024L * 1024L)}MB"
            else -> "${v / (1024L * 1024L * 1024L)}GB"
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun shouldRun(): Boolean =
        NeighborDiscoverySettingsStore.get(this).let { it.enabled && it.backgroundEnhanced }

    private fun currentDefaultAccept(): Boolean =
        NeighborDiscoverySettingsStore.get(this).defaultAction == NeighborDefaultAction.ACCEPT

    private fun isAppForeground(): Boolean = appForeground

    companion object {
        private const val ACTION_APP_FOREGROUND = "com.yuliwen.filetran.neighbor_bg.app_foreground"
        private const val ACTION_STOP = "com.yuliwen.filetran.neighbor_bg.stop"
        @Volatile
        private var appForeground: Boolean = false

        const val ACTION_INVITE_ACCEPT = "com.yuliwen.filetran.neighbor_bg.invite_accept"
        const val ACTION_INVITE_REJECT = "com.yuliwen.filetran.neighbor_bg.invite_reject"
        const val ACTION_INVITE_HIDE = "com.yuliwen.filetran.neighbor_bg.invite_hide"
        const val ACTION_RECEIVE_CANCEL = "com.yuliwen.filetran.neighbor_bg.receive_cancel"
        const val ACTION_RECEIVE_HIDE = "com.yuliwen.filetran.neighbor_bg.receive_hide"
        const val ACTION_DONE_OPEN_DEFAULT = "com.yuliwen.filetran.neighbor_bg.done_open_default"
        const val ACTION_DONE_OPEN_CHOOSER = "com.yuliwen.filetran.neighbor_bg.done_open_chooser"
        const val ACTION_DONE_IMPORT_GALLERY = "com.yuliwen.filetran.neighbor_bg.done_import_gallery"
        const val ACTION_DONE_OPEN_DOWNLOADS = "com.yuliwen.filetran.neighbor_bg.done_open_downloads"
        const val ACTION_DONE_DISMISS = "com.yuliwen.filetran.neighbor_bg.done_dismiss"

        const val EXTRA_REQUEST_ID = "extra_request_id"
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_FROM_NOTIFICATION = "extra_from_notification"
        private const val EXTRA_APP_FOREGROUND = "extra_app_foreground"

        private const val CH_FG = "neighbor_bg_fg"
        private const val CH_PROMPT = "neighbor_bg_prompt_v2"
        private const val CH_PROGRESS = "neighbor_bg_progress_v2"
        private const val CH_DONE = "neighbor_bg_done_v1"

        private const val NOTI_FG = 34021
        private const val NOTI_DONE = 34022
        private const val NOTI_INVITE = 34023
        private const val NOTI_PROGRESS = 34024
        private const val NOTI_DONE_LIVE = 34025
        private const val REVERSE_PORT_PRIMARY = 18080
        private const val REVERSE_PORT_FALLBACK_1 = 18081
        private const val REVERSE_PORT_FALLBACK_2 = 18082
        private const val REVERSE_WAIT_TIMEOUT_MS = 35_000L
        private const val LIVE_NOTIFICATION_MIN_SDK = 36

        fun setAppForeground(foreground: Boolean) {
            appForeground = foreground
            Log.i("NeighborRoute", "setAppForeground=$foreground")
        }

        fun updateAppForeground(context: Context, foreground: Boolean) {
            setAppForeground(foreground)
            val shouldRun = NeighborDiscoverySettingsStore.get(context).let { it.enabled && it.backgroundEnhanced }
            if (!shouldRun) {
                return
            }
            val app = context.applicationContext
            val intent = Intent(app, NeighborDiscoveryBackgroundService::class.java).apply {
                action = ACTION_APP_FOREGROUND
                putExtra(EXTRA_APP_FOREGROUND, foreground)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(app, intent)
            } else {
                app.startService(intent)
            }
        }

        fun syncFromPrefs(context: Context) {
            val shouldRun = NeighborDiscoverySettingsStore.get(context).let { it.enabled && it.backgroundEnhanced }
            if (shouldRun) start(context) else stop(context)
        }

        fun start(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, NeighborDiscoveryBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(app, intent)
            } else {
                app.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, NeighborDiscoveryBackgroundService::class.java)
            )
        }
    }
}

