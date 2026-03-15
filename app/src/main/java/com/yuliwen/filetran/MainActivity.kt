package com.yuliwen.filetran

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuliwen.filetran.airgap.AirGapSender
import com.yuliwen.filetran.ui.theme.FileTranTheme
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import xdsopl.robot36.RealtimeSpectrumAnalyzer
import xdsopl.robot36.SstvRobot36Receiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlin.coroutines.resume
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

data class ShareFileSpec(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val port: Int
)

data class ShareEndpoint(
    val host: String,
    val publicPort: Int,
    val listenPort: Int,
    val note: String? = null
)

data class FileTransferScreenArgs(
    val initialFileUri: Uri?,
    val initialSharedText: String?,
    val onFileSelected: (Uri, String, String, Int) -> Unit,
    val onFilesSelected: (List<ShareFileSpec>) -> Unit,
    val onStopServer: () -> Unit,
    val allowedFileSendFlags: Int,
    val initialFileSendType: Int,
    val onBack: (() -> Unit)?,
    val onSecondaryPageVisibleChanged: ((Boolean) -> Unit)?,
    val onInitialFileConsumed: () -> Unit,
    val onInitialTextConsumed: () -> Unit,
    val onRequestOpenEnhancedReceive: (Boolean) -> Unit
)

private const val FILE_SEND_FLAG_NORMAL = 1 shl 0
private const val FILE_SEND_FLAG_CIMBAR = 1 shl 1
private const val FILE_SEND_FLAG_SSTV = 1 shl 2
private const val FILE_SEND_FLAGS_ALL = FILE_SEND_FLAG_NORMAL or FILE_SEND_FLAG_CIMBAR or FILE_SEND_FLAG_SSTV

private data class ShareEndpointProbeResult(
    val localHost: String,
    val localPort: Int,
    val natBatch: StunProbeBatchResult?
)

private data class PendingMainShareRequest(
    val probe: ShareEndpointProbeResult,
    val singleUri: Uri? = null,
    val singleName: String? = null,
    val singleMime: String? = null,
    val batchUris: List<Uri> = emptyList()
) {
    val isSingle: Boolean
        get() = singleUri != null && !singleName.isNullOrBlank() && !singleMime.isNullOrBlank()
}

private data class ReversePushEndpoint(
    val protocol: String,
    val host: String,
    val port: Int
)

private data class ReversePushSendProgress(
    val fileName: String = "",
    val fileIndex: Int = 0,
    val totalFiles: Int = 0,
    val sentBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val running: Boolean = false,
    val message: String? = null
)

private class ReversePushSendControl {
    val cancelled = AtomicBoolean(false)
}

private data class QuickInviteReverseTask(
    val requestId: String,
    val endpoint: String,
    val files: List<Triple<Uri, String, String>>
)

private data class LocalSendDraftItem(
    val uri: Uri? = null,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val inlineBytes: ByteArray? = null,
    val previewText: String? = null
)

private data class ForegroundLocalSendReceiveSheetState(
    val requestId: String,
    val senderAlias: String,
    val senderIp: String,
    val firstFileName: String,
    val fileCount: Int,
    val totalSize: Long,
    val defaultAccept: Boolean,
    val timeoutSeconds: Int,
    val decisionDeadlineAtMs: Long,
    val countdownSeconds: Int,
    val awaitingDecision: Boolean,
    val accepted: Boolean,
    val statusText: String,
    val lastFileName: String = "",
    val failureMessage: String = "",
    val cancelledBySender: Boolean = false,
    val messageReceived: Boolean = false
)

private class MainShareStunProbeControl {
    val requestStopEarly = AtomicBoolean(false)
    val requestDisableNat = AtomicBoolean(false)
    val successCount = AtomicInteger(0)
}

private fun formatTransferBytes(bytes: Long): String {
    return when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format("%.2f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun describeLocalSendReceiveFailure(code: String): String {
    return when (code.trim()) {
        "sender_cancelled" -> "发送方已取消本次传输。"
        "network_interrupted" -> "接收已中断，网络连接已断开。"
        "write_failed" -> "文件写入失败。"
        "empty_body" -> "发送方没有传输有效内容。"
        "size_mismatch" -> "文件大小与声明信息不一致。"
        else -> if (code.isBlank()) "接收失败。" else "接收失败：$code"
    }
}

private const val MULTI_SHARE_PAYLOAD_TYPE = "filetran_multi_download_v2"
private const val MAX_BATCH_QR_FILES = 200
private const val REVERSE_PUSH_CANCELLED = "REVERSE_PUSH_CANCELLED"

class MainActivity : ComponentActivity() {
    private val fileServers = mutableListOf<NanoHTTPD>()
    private var sharedFileUri by mutableStateOf<Uri?>(null)
    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NetworkUtils.initializeStunServerSettings(this)
        handleIncomingIntent(intent)

        setContent {
            FileTranTheme {
                MainScreen(
                    initialFileUri = sharedFileUri,
                    initialSharedText = sharedText,
                    onFileSelected = { uri, fileName, mimeType, port ->
                        startServer(uri, fileName, mimeType, port)
                    },
                    onFilesSelected = { specs ->
                        startServers(specs)
                    },
                    onStopServer = { stopServer() },
                    onInitialFileConsumed = { sharedFileUri = null },
                    onInitialTextConsumed = { sharedText = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val streamUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (streamUri != null) {
                    sharedFileUri = streamUri
                    sharedText = null
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        sharedText = text
                        sharedFileUri = null
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streams = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                val first = streams.firstOrNull()
                if (first != null) {
                    sharedFileUri = first
                    sharedText = null
                }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    sharedFileUri = uri
                    sharedText = null
                }
            }
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
                val messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
                    ?.mapNotNull { it as? NdefMessage }
                    .orEmpty()
                NfcIntentBus.publish(NfcIntentPayload(tag = tag, messages = messages))
            }
        }
    }

    private fun startServer(uri: Uri, fileName: String, mimeType: String, port: Int) {
        stopServer()
        val server = FileServer(this, port, uri, fileName, mimeType)
        fileServers.add(server)
        server.start()
    }

    private fun startServers(specs: List<ShareFileSpec>) {
        stopServer()
        if (specs.isEmpty()) return
        if (specs.size == 1) {
            val spec = specs.first()
            val server = FileServer(this, spec.port, spec.uri, spec.fileName, spec.mimeType)
            fileServers.add(server)
            server.start()
            return
        }
        val port = specs.first().port
        val items = specs.map { spec ->
            MultiShareFile(
                uri = spec.uri,
                fileName = spec.fileName,
                mimeType = spec.mimeType,
                size = -1L
            )
        }
        val server = MultiFileServer(this, port, items)
        fileServers.add(server)
        server.start()
    }

    private fun stopServer() {
        fileServers.forEach { runCatching { it.stop() } }
        fileServers.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    initialFileUri: Uri?,
    initialSharedText: String?,
    onFileSelected: (Uri, String, String, Int) -> Unit,
    onFilesSelected: (List<ShareFileSpec>) -> Unit,
    onStopServer: () -> Unit,
    onInitialFileConsumed: () -> Unit = {},
    onInitialTextConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val baseDensity = LocalDensity.current
    val speedTestPrefs = remember {
        context.getSharedPreferences(SPEED_TEST_PREFS, Context.MODE_PRIVATE)
    }
    val uiScalePrefs = remember {
        context.getSharedPreferences(UI_SCALE_PREFS, Context.MODE_PRIVATE)
    }
    var defaultOpenSpeedTest by remember {
        mutableStateOf(speedTestPrefs.getBoolean(SPEED_TEST_KEY_DEFAULT_APP_ENTRY, false))
    }
    var stealthModeEnabled by remember {
        mutableStateOf(speedTestPrefs.getBoolean(SPEED_TEST_KEY_STEALTH_MODE, false))
    }
    var uiScale by remember {
        mutableStateOf(uiScalePrefs.getFloat(UI_SCALE_KEY, UI_SCALE_DEFAULT).coerceIn(0.75f, 1.25f))
    }
    val scaledDensity = remember(baseDensity, uiScale) {
        Density(
            density = baseDensity.density * uiScale,
            fontScale = baseDensity.fontScale * uiScale
        )
    }
    val shouldOpenSpeedTestByDefault = if (stealthModeEnabled) {
        true
    } else {
        initialFileUri == null &&
            initialSharedText.isNullOrBlank() &&
            defaultOpenSpeedTest
    }
    var lastBackPressMs by remember { mutableStateOf(0L) }
    var sendSecondaryOpen by remember { mutableStateOf(false) }
    var receiveSecondaryOpen by remember { mutableStateOf(false) }
    var transferLabSecondaryOpen by remember { mutableStateOf(false) }
    var pendingNavTarget by remember { mutableStateOf<Int?>(null) }
    var pendingNavAtMs by remember { mutableStateOf(0L) }
    var pendingEnhancedReceiveMode by remember { mutableIntStateOf(0) } // 0:none 4:ipv4 6:ipv6
    var pendingQuickSendInvite by remember { mutableStateOf<QuickSendInvite?>(null) }
    var pendingQuickSendCancelId by remember { mutableStateOf<String?>(null) }
    val initialNeighborSettings = remember { NeighborDiscoverySettingsStore.get(context) }
    var neighborDiscoveryEnabled by remember { mutableStateOf(initialNeighborSettings.enabled) }
    var localSendV2Enabled by remember { mutableStateOf(initialNeighborSettings.localSendV2Enabled) }
    var appInForeground by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val tabStateHolder = rememberSaveableStateHolder()
    val pagerState = rememberPagerState(initialPage = if (shouldOpenSpeedTestByDefault) 2 else 0) { 5 }
    val scope = rememberCoroutineScope()
    val selectedTab = pagerState.currentPage
    var foregroundLocalSendSheetState by remember { mutableStateOf<ForegroundLocalSendReceiveSheetState?>(null) }
    var foregroundLocalSendResolveDecision by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val foregroundLocalSendFileSizes = remember { mutableStateMapOf<String, Long>() }
    val foregroundLocalSendReceivedBytes = remember { mutableStateMapOf<String, Long>() }
    val foregroundLocalSendCompletedFiles = remember { mutableStateMapOf<String, Boolean>() }

    fun clearForegroundLocalSendSheet() {
        foregroundLocalSendResolveDecision = null
        foregroundLocalSendSheetState = null
        foregroundLocalSendFileSizes.clear()
        foregroundLocalSendReceivedBytes.clear()
        foregroundLocalSendCompletedFiles.clear()
    }

    fun isForegroundLocalSendInteractionActive(): Boolean {
        val current = foregroundLocalSendSheetState ?: return false
        val completedCount = foregroundLocalSendCompletedFiles.values.count { it }
        val allCompleted = current.fileCount > 0 && completedCount >= current.fileCount
        return current.awaitingDecision ||
            (current.accepted &&
                current.failureMessage.isBlank() &&
                !current.cancelledBySender &&
                !current.messageReceived &&
                !allCompleted)
    }

    fun updateForegroundLocalSendSheet(event: LocalSendReceiveEvent) {
        if (event.stage == LocalSendReceiveStage.MESSAGE_RECEIVED) {
            val text = event.textContent.ifBlank { event.message }
            if (text.isNotBlank()) {
                copyPlainTextToClipboard(
                    context = context,
                    label = if (event.fileName.startsWith("clipboard_", ignoreCase = true)) {
                        "localsend_clipboard"
                    } else {
                        "localsend_text"
                    },
                    text = text
                )
                if (foregroundLocalSendSheetState == null) {
                    Toast.makeText(context, "LocalSend 文本已复制到剪贴板。", Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }
        val current = foregroundLocalSendSheetState ?: return
        if (current.requestId.isNotBlank() && current.requestId != event.sessionId) {
            return
        }
        val nextAlias = event.senderAlias.ifBlank { current.senderAlias }
        val nextIp = event.senderIp.ifBlank { current.senderIp }
        when (event.stage) {
            LocalSendReceiveStage.STARTED -> {
                if (event.fileId.isNotBlank()) {
                    foregroundLocalSendFileSizes[event.fileId] = event.fileSize.coerceAtLeast(0L)
                    foregroundLocalSendReceivedBytes.putIfAbsent(event.fileId, 0L)
                    foregroundLocalSendCompletedFiles[event.fileId] = false
                }
                foregroundLocalSendResolveDecision = null
                foregroundLocalSendSheetState = current.copy(
                    senderAlias = nextAlias,
                    senderIp = nextIp,
                    accepted = true,
                    awaitingDecision = false,
                    statusText = "正在等待发送端开始传输...",
                    lastFileName = event.fileName.ifBlank { current.lastFileName },
                    failureMessage = "",
                    cancelledBySender = false,
                    messageReceived = false
                )
            }

            LocalSendReceiveStage.PROGRESS -> {
                val knownSize = maxOf(
                    event.fileSize.coerceAtLeast(0L),
                    foregroundLocalSendFileSizes[event.fileId] ?: 0L
                )
                val received = event.receivedBytes.coerceAtLeast(0L)
                foregroundLocalSendFileSizes[event.fileId] = knownSize
                foregroundLocalSendReceivedBytes[event.fileId] = maxOf(
                    foregroundLocalSendReceivedBytes[event.fileId] ?: 0L,
                    if (knownSize > 0L) received.coerceAtMost(knownSize) else received
                )
                if (knownSize > 0L && (foregroundLocalSendReceivedBytes[event.fileId] ?: 0L) >= knownSize) {
                    foregroundLocalSendCompletedFiles[event.fileId] = true
                }
                foregroundLocalSendSheetState = current.copy(
                    senderAlias = nextAlias,
                    senderIp = nextIp,
                    accepted = true,
                    awaitingDecision = false,
                    statusText = if (event.fileName.isNotBlank()) {
                        "正在接收 ${event.fileName}"
                    } else {
                        "正在接收文件..."
                    },
                    lastFileName = event.fileName.ifBlank { current.lastFileName },
                    failureMessage = "",
                    cancelledBySender = false,
                    messageReceived = false
                )
            }

            LocalSendReceiveStage.COMPLETED -> {
                val finalSize = maxOf(
                    event.fileSize.coerceAtLeast(0L),
                    foregroundLocalSendFileSizes[event.fileId] ?: 0L,
                    foregroundLocalSendReceivedBytes[event.fileId] ?: 0L
                )
                foregroundLocalSendFileSizes[event.fileId] = finalSize
                foregroundLocalSendReceivedBytes[event.fileId] = finalSize
                foregroundLocalSendCompletedFiles[event.fileId] = true
                val completedCount = foregroundLocalSendCompletedFiles.values.count { it }
                val doneAll = current.fileCount > 0 && completedCount >= current.fileCount
                foregroundLocalSendSheetState = current.copy(
                    senderAlias = nextAlias,
                    senderIp = nextIp,
                    accepted = true,
                    awaitingDecision = false,
                    statusText = if (doneAll) {
                        "已接收完成"
                    } else {
                        "已完成 $completedCount/${current.fileCount} 个文件"
                    },
                    lastFileName = event.fileName.ifBlank { current.lastFileName },
                    failureMessage = "",
                    cancelledBySender = false,
                    messageReceived = false
                )
            }

            LocalSendReceiveStage.MESSAGE_RECEIVED -> {
                foregroundLocalSendResolveDecision = null
                foregroundLocalSendSheetState = current.copy(
                    senderAlias = nextAlias,
                    senderIp = nextIp,
                    accepted = true,
                    awaitingDecision = false,
                    statusText = "文本已复制到剪贴板。",
                    lastFileName = event.fileName.ifBlank { current.lastFileName },
                    failureMessage = "",
                    cancelledBySender = false,
                    messageReceived = true
                )
            }

            LocalSendReceiveStage.FAILED -> {
                val message = describeLocalSendReceiveFailure(event.message)
                foregroundLocalSendSheetState = current.copy(
                    senderAlias = nextAlias,
                    senderIp = nextIp,
                    accepted = true,
                    awaitingDecision = false,
                    statusText = message,
                    lastFileName = event.fileName.ifBlank { current.lastFileName },
                    failureMessage = message,
                    cancelledBySender = false,
                    messageReceived = false
                )
            }

            LocalSendReceiveStage.CANCELLED -> {
                foregroundLocalSendSheetState = current.copy(
                    senderAlias = nextAlias,
                    senderIp = nextIp,
                    accepted = true,
                    awaitingDecision = false,
                    statusText = "发送方已取消本次传输。",
                    failureMessage = "",
                    cancelledBySender = true,
                    messageReceived = false
                )
            }
        }
    }
    val currentTabHasProtectedContent = when (selectedTab) {
        0 -> sendSecondaryOpen
        1 -> receiveSecondaryOpen
        2 -> transferLabSecondaryOpen
        else -> false
    }
    fun navigateToTab(target: Int) {
        if (pagerState.currentPage == target) return
        val requiresConfirm = currentTabHasProtectedContent && target != selectedTab
        if (requiresConfirm) {
            val now = System.currentTimeMillis()
            if (pendingNavTarget == target && now - pendingNavAtMs <= 1800L) {
                pendingNavTarget = null
                pendingNavAtMs = 0L
                scope.launch { pagerState.scrollToPage(target) }
            } else {
                pendingNavTarget = target
                pendingNavAtMs = now
                Toast.makeText(
                    context,
                    "当前正在传输实验室二级页面，再点一次切换，当前内容可能被中断。",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        pendingNavTarget = null
        pendingNavAtMs = 0L
        scope.launch { pagerState.scrollToPage(target) }
    }
    DisposableEffect(speedTestPrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SPEED_TEST_KEY_DEFAULT_APP_ENTRY) {
                defaultOpenSpeedTest = speedTestPrefs.getBoolean(SPEED_TEST_KEY_DEFAULT_APP_ENTRY, false)
            }
            if (key == SPEED_TEST_KEY_STEALTH_MODE) {
                stealthModeEnabled = speedTestPrefs.getBoolean(SPEED_TEST_KEY_STEALTH_MODE, false)
            }
        }
        speedTestPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            speedTestPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    DisposableEffect(uiScalePrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == UI_SCALE_KEY) {
                uiScale = uiScalePrefs.getFloat(UI_SCALE_KEY, UI_SCALE_DEFAULT).coerceIn(0.75f, 1.25f)
            }
        }
        uiScalePrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            uiScalePrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    LaunchedEffect(Unit) {
        AppKeepAliveService.syncFromPrefs(context)
        ClipboardSyncService.syncFromPrefs(context)
        NeighborDiscoveryBackgroundService.syncFromPrefs(context)
    }
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            val current = NeighborDiscoverySettingsStore.get(context)
            neighborDiscoveryEnabled = current.enabled
            localSendV2Enabled = current.localSendV2Enabled
            NeighborDiscoveryBackgroundService.syncFromPrefs(context)
        }
        val prefs = NeighborDiscoverySettingsStore.registerChangeListener(context, listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    appInForeground = true
                    NeighborDiscoveryBackgroundService.updateAppForeground(context, true)
                    Log.i("NeighborRoute", "app foreground=true")
                }
                Lifecycle.Event.ON_STOP -> {
                    appInForeground = false
                    NeighborDiscoveryBackgroundService.updateAppForeground(context, false)
                    Log.i("NeighborRoute", "app foreground=false")
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val started = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        appInForeground = started
        NeighborDiscoveryBackgroundService.updateAppForeground(context, started)
        Log.i("NeighborRoute", "app foreground=$started (sync)")
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            appInForeground = false
            NeighborDiscoveryBackgroundService.updateAppForeground(context, false)
            Log.i("NeighborRoute", "app foreground=false (dispose)")
        }
    }
    LaunchedEffect(foregroundLocalSendSheetState?.requestId) {
        while (true) {
            val current = foregroundLocalSendSheetState ?: break
            if (!current.awaitingDecision || current.decisionDeadlineAtMs <= 0L) {
                break
            }
            val remaining = ((current.decisionDeadlineAtMs - System.currentTimeMillis() + 999L) / 1000L)
                .coerceAtLeast(0L)
                .toInt()
            if (remaining != current.countdownSeconds) {
                foregroundLocalSendSheetState = current.copy(countdownSeconds = remaining)
            }
            delay(250L)
        }
    }
    DisposableEffect(context, neighborDiscoveryEnabled, appInForeground) {
        if (!neighborDiscoveryEnabled || !appInForeground) {
            onDispose { }
        } else {
            val handler = Handler(Looper.getMainLooper())
            val runtime = LanNeighborRuntime(
                context.applicationContext,
                onQuickSendInvite = { invite ->
                    handler.post {
                        pendingQuickSendInvite = invite
                        pendingNavTarget = null
                        pendingNavAtMs = 0L
                        scope.launch { pagerState.scrollToPage(1) }
                    }
                },
                onQuickSendCancel = { requestId ->
                    handler.post {
                        pendingQuickSendCancelId = requestId
                        pendingNavTarget = null
                        pendingNavAtMs = 0L
                        scope.launch { pagerState.scrollToPage(1) }
                    }
                },
                ownerTag = "activity"
            )
            runtime.start()
            onDispose {
                runtime.stop()
            }
        }
    }
    DisposableEffect(context, neighborDiscoveryEnabled, appInForeground, localSendV2Enabled) {
        if (!neighborDiscoveryEnabled || !appInForeground || !localSendV2Enabled) {
            onDispose { }
        } else {
            val mainHandler = Handler(Looper.getMainLooper())
            val runtime = LocalSendRuntime(
                context = context.applicationContext,
                ownerTag = "activity",
                onPeersChanged = {},
                onReceiveEvent = { event ->
                    mainHandler.post {
                        updateForegroundLocalSendSheet(event)
                    }
                },
                canAcceptIncoming = {
                    !isForegroundLocalSendInteractionActive()
                },
                requestIncomingDecision = { request ->
                    val settings = NeighborDiscoverySettingsStore.get(context)
                    val defaultAccept = settings.defaultAction == NeighborDefaultAction.ACCEPT
                    val timeoutSeconds = settings.timeoutSeconds.coerceIn(0, 25)
                    if (timeoutSeconds <= 0) {
                        if (defaultAccept) {
                            return@LocalSendRuntime LocalSendIncomingDecision.ACCEPT
                        }
                        return@LocalSendRuntime LocalSendIncomingDecision.REJECT
                    }

                    val acceptedRef = AtomicBoolean(defaultAccept)
                    val resolvedRef = AtomicBoolean(false)
                    val latch = CountDownLatch(1)
                    mainHandler.post {
                        val hostActivity = context as? Activity
                        val invalidHost = hostActivity == null ||
                            hostActivity.isFinishing ||
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && hostActivity.isDestroyed)
                        if (invalidHost) {
                            latch.countDown()
                            return@post
                        }

                        foregroundLocalSendFileSizes.clear()
                        foregroundLocalSendReceivedBytes.clear()
                        foregroundLocalSendCompletedFiles.clear()

                        fun resolveDecision(accept: Boolean) {
                            if (!resolvedRef.compareAndSet(false, true)) {
                                return
                            }
                            acceptedRef.set(accept)
                            foregroundLocalSendResolveDecision = null
                            if (accept) {
                                foregroundLocalSendSheetState = foregroundLocalSendSheetState?.copy(
                                    awaitingDecision = false,
                                    accepted = true,
                                    countdownSeconds = 0,
                                    statusText = "已允许接收，等待发送端开始传输..."
                                )
                            } else {
                                clearForegroundLocalSendSheet()
                            }
                            latch.countDown()
                        }

                        val decisionDeadlineAtMs = System.currentTimeMillis() + timeoutSeconds * 1000L
                        foregroundLocalSendSheetState = ForegroundLocalSendReceiveSheetState(
                            requestId = request.requestId,
                            senderAlias = request.senderAlias.ifBlank { "LocalSend 设备" },
                            senderIp = request.senderIp,
                            firstFileName = request.firstFileName.ifBlank { "LocalSend 文件" },
                            fileCount = request.fileCount.coerceAtLeast(1),
                            totalSize = request.totalSize.coerceAtLeast(0L),
                            defaultAccept = defaultAccept,
                            timeoutSeconds = timeoutSeconds,
                            decisionDeadlineAtMs = decisionDeadlineAtMs,
                            countdownSeconds = timeoutSeconds,
                            awaitingDecision = true,
                            accepted = false,
                            statusText = "等待你的决定"
                        )
                        foregroundLocalSendResolveDecision = { accept: Boolean ->
                            resolveDecision(accept)
                        }
                        mainHandler.postDelayed(
                            {
                                resolveDecision(defaultAccept)
                            },
                            timeoutSeconds * 1000L
                        )
                    }
                    latch.await((timeoutSeconds + 2).toLong(), TimeUnit.SECONDS)
                    if (acceptedRef.get()) {
                        LocalSendIncomingDecision.ACCEPT
                    } else {
                        LocalSendIncomingDecision.REJECT
                    }
                }
            )
            val startJob = scope.launch {
                // Wait for background service runtime to release ports during foreground handoff.
                delay(450)
                val maxAttempts = 5
                repeat(maxAttempts) { attempt ->
                    runtime.start()
                    if (runtime.isRunning()) {
                        LocalSendRuntimeBridge.attach(runtime)
                        Log.i("LocalSendRuntime", "[activity] started attempt=${attempt + 1}")
                        return@launch
                    }
                    runtime.stop()
                    if (attempt < maxAttempts - 1) {
                        val backoffMs = 220L + attempt * 180L
                        Log.w(
                            "LocalSendRuntime",
                            "[activity] start failed attempt=${attempt + 1}, retry in ${backoffMs}ms"
                        )
                        delay(backoffMs)
                    }
                }
                Log.e("LocalSendRuntime", "[activity] failed to start after foreground handoff retries")
            }
            onDispose {
                startJob.cancel()
                LocalSendRuntimeBridge.detach(runtime)
                runtime.stop()
            }
        }
    }
    LaunchedEffect(initialSharedText) {
        if (!initialSharedText.isNullOrBlank()) {
            pagerState.scrollToPage(0)
        }
    }
    LaunchedEffect(shouldOpenSpeedTestByDefault) {
        if (shouldOpenSpeedTestByDefault) {
            pagerState.scrollToPage(2)
        }
    }
    LaunchedEffect(selectedTab) {
        pendingNavTarget = null
        pendingNavAtMs = 0L
    }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackPressMs <= 2000L) {
            (context as? Activity)?.finish()
        } else {
            lastBackPressMs = now
            Toast.makeText(context, "再按一次退出应用", Toast.LENGTH_SHORT).show()
        }
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        Scaffold(
            bottomBar = {
                if (!stealthModeEnabled) {
                    NavigationBar {
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Send, contentDescription = null) },
                            label = { BottomNavLabel("发送") },
                            selected = selectedTab == 0,
                            onClick = { navigateToTab(0) }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Download, contentDescription = null) },
                            label = { BottomNavLabel("接收") },
                            selected = selectedTab == 1,
                            onClick = { navigateToTab(1) }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                            label = { BottomNavLabel("传输实验室") },
                            selected = selectedTab == 2,
                            onClick = { navigateToTab(2) }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.History, contentDescription = null) },
                            label = { BottomNavLabel("历史") },
                            selected = selectedTab == 3,
                            onClick = { navigateToTab(3) }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { BottomNavLabel("设置") },
                            selected = selectedTab == 4,
                            onClick = { navigateToTab(4) }
                        )
                    }
                }
            }
        ) { paddingValues ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
                userScrollEnabled = !defaultOpenSpeedTest &&
                    !stealthModeEnabled &&
                    !currentTabHasProtectedContent
            ) { tab ->
                tabStateHolder.SaveableStateProvider("main_tab_$tab") {
                    when (tab) {
                        0 -> FileTransferScreen(
                            args = FileTransferScreenArgs(
                                initialFileUri = initialFileUri,
                                initialSharedText = initialSharedText,
                                onFileSelected = onFileSelected,
                                onFilesSelected = onFilesSelected,
                                onStopServer = onStopServer,
                                allowedFileSendFlags = FILE_SEND_FLAG_NORMAL or FILE_SEND_FLAG_SSTV,
                                initialFileSendType = 0,
                                onBack = null,
                                onSecondaryPageVisibleChanged = { sendSecondaryOpen = it },
                                onInitialFileConsumed = onInitialFileConsumed,
                                onInitialTextConsumed = onInitialTextConsumed,
                                onRequestOpenEnhancedReceive = { ipv6 ->
                                    pendingEnhancedReceiveMode = if (ipv6) 6 else 4
                                    pendingNavTarget = null
                                    pendingNavAtMs = 0L
                                    scope.launch { pagerState.scrollToPage(1) }
                                }
                            )
                        )
                        1 -> ReceiveFileScreen(
                            onSecondaryPageVisibleChanged = { receiveSecondaryOpen = it },
                            requestedEnhancedMode = pendingEnhancedReceiveMode,
                            onEnhancedModeRequestHandled = { pendingEnhancedReceiveMode = 0 },
                            pendingQuickSendInvite = pendingQuickSendInvite,
                            onQuickSendInviteHandled = { pendingQuickSendInvite = null },
                            pendingQuickSendCancelId = pendingQuickSendCancelId,
                            onQuickSendCancelHandled = { pendingQuickSendCancelId = null }
                        )
                        2 -> TransferLabScreen(
                            initialFileUri = initialFileUri,
                            onFileSelected = onFileSelected,
                            onStopServer = onStopServer,
                            initialOpenSpeedTest = shouldOpenSpeedTestByDefault,
                            onSecondaryPageChanged = { transferLabSecondaryOpen = it }
                        )
                        3 -> DownloadHistoryScreen()
                        4 -> AboutScreen()
                    }
                }
            }
        }
        foregroundLocalSendSheetState?.let { sheet ->
            val completedCount = foregroundLocalSendCompletedFiles.values.count { it }
            val receivedBytes = foregroundLocalSendReceivedBytes.values.sumOf { it }
            val knownTotalBytes = foregroundLocalSendFileSizes.values.sumOf { it }
            val totalBytes = maxOf(sheet.totalSize, knownTotalBytes)
            val allCompleted = sheet.fileCount > 0 && completedCount >= sheet.fileCount
            val canDismissSheet = !sheet.awaitingDecision &&
                (sheet.failureMessage.isNotBlank() || sheet.cancelledBySender || sheet.messageReceived || allCompleted)
            val startedTransfer = foregroundLocalSendFileSizes.isNotEmpty() ||
                foregroundLocalSendReceivedBytes.isNotEmpty() ||
                completedCount > 0
            val progressFraction = when {
                totalBytes > 0L -> (receivedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                sheet.fileCount > 0 -> (completedCount.toFloat() / sheet.fileCount.toFloat()).coerceIn(0f, 1f)
                else -> 0f
            }
            val title = when {
                sheet.awaitingDecision -> "LocalSend 接收请求"
                sheet.messageReceived -> "LocalSend 文本已接收"
                sheet.failureMessage.isNotBlank() || sheet.cancelledBySender -> "LocalSend 接收已中断"
                allCompleted -> "LocalSend 接收完成"
                else -> "LocalSend 接收中"
            }
            val summaryFileText = if (sheet.fileCount <= 1) {
                sheet.firstFileName
            } else {
                "${sheet.firstFileName} 等 ${sheet.fileCount} 个文件"
            }
            val autoActionText = if (sheet.defaultAccept) "接收" else "拒绝"
            val sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { value ->
                    value != SheetValue.Hidden || canDismissSheet
                }
            )
            ModalBottomSheet(
                onDismissRequest = {
                    if (canDismissSheet) {
                        clearForegroundLocalSendSheet()
                    }
                },
                sheetState = sheetState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        sheet.statusText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("发送方：${sheet.senderAlias}", fontWeight = FontWeight.Medium)
                            if (sheet.senderIp.isNotBlank()) {
                                Text(
                                    "地址：${sheet.senderIp}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("文件：$summaryFileText")
                            Text(
                                "总大小：${if (sheet.totalSize > 0L) formatTransferBytes(sheet.totalSize) else "未知"}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (sheet.awaitingDecision) {
                        Text(
                            "${sheet.countdownSeconds} 秒后自动$autoActionText",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    foregroundLocalSendResolveDecision?.invoke(false)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("拒绝")
                            }
                            Button(
                                onClick = {
                                    foregroundLocalSendResolveDecision?.invoke(true)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("允许接收")
                            }
                        }
                    } else {
                        if (sheet.accepted && !sheet.messageReceived) {
                            if (sheet.failureMessage.isBlank() && !sheet.cancelledBySender && (startedTransfer || allCompleted)) {
                                LinearProgressIndicator(
                                    progress = { progressFraction },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("${(progressFraction * 100).roundToInt()}%", fontSize = 14.sp)
                                    Text("$completedCount/${sheet.fileCount} 个文件", fontSize = 14.sp)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(formatTransferBytes(receivedBytes), fontSize = 12.sp)
                                    Text(
                                        if (totalBytes > 0L) formatTransferBytes(totalBytes) else "总大小未知",
                                        fontSize = 12.sp
                                    )
                                }
                            } else if (sheet.failureMessage.isBlank() && !sheet.cancelledBySender) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            if (sheet.lastFileName.isNotBlank()) {
                                Text(
                                    "当前文件：${sheet.lastFileName}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (sheet.failureMessage.isNotBlank()) {
                            Text(sheet.failureMessage, color = MaterialTheme.colorScheme.error)
                        } else if (sheet.cancelledBySender) {
                            Text("发送方已取消本次传输。", color = MaterialTheme.colorScheme.error)
                        }
                        if (canDismissSheet) {
                            Button(
                                onClick = {
                                    clearForegroundLocalSendSheet()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (allCompleted) "完成" else "关闭")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomNavLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun TransferLabScreen(
    initialFileUri: Uri?,
    onFileSelected: (Uri, String, String, Int) -> Unit,
    onStopServer: () -> Unit,
    initialOpenSpeedTest: Boolean = false,
    onSecondaryPageChanged: (Boolean) -> Unit = {}
) {
    var showNatTypeDetect by rememberSaveable { mutableStateOf(false) }
    var showNat34Punch by rememberSaveable { mutableStateOf(false) }
    var showIpv4Stun by rememberSaveable { mutableStateOf(false) }
    var showIpv6Enhanced by rememberSaveable { mutableStateOf(false) }
    var showUdpFileExperiment by rememberSaveable { mutableStateOf(false) }
    var showIperfLab by rememberSaveable { mutableStateOf(false) }
    var showLibreSpeedLab by rememberSaveable { mutableStateOf(false) }
    var showSpeedTestLab by rememberSaveable { mutableStateOf(initialOpenSpeedTest) }
    var showFileCodeBoxLab by rememberSaveable { mutableStateOf(false) }
    var showAcoustic by rememberSaveable { mutableStateOf(false) }
    var showSstv by rememberSaveable { mutableStateOf(false) }
    var showNfc by rememberSaveable { mutableStateOf(false) }
    var showCimbar by rememberSaveable { mutableStateOf(false) }
    val isSecondaryPageOpen =
        showNatTypeDetect ||
            showNat34Punch ||
            showIpv4Stun ||
            showIpv6Enhanced ||
            showUdpFileExperiment ||
            showIperfLab ||
            showLibreSpeedLab ||
            showSpeedTestLab ||
            showFileCodeBoxLab ||
            showAcoustic ||
            showSstv ||
            showNfc ||
            showCimbar
    LaunchedEffect(isSecondaryPageOpen) {
        onSecondaryPageChanged(isSecondaryPageOpen)
    }
    DisposableEffect(Unit) {
        onDispose {
            onSecondaryPageChanged(false)
        }
    }
    if (showNatTypeDetect) {
        NatTypeDetectScreen(onBack = { showNatTypeDetect = false })
        return
    }
    if (showNat34Punch) {
        Nat34UdpPunchScreen(onBack = { showNat34Punch = false })
        return
    }
    if (showIpv4Stun) {
        Ipv4StunTcpTransferScreen(onBack = { showIpv4Stun = false })
        return
    }
    if (showIpv6Enhanced) {
        Ipv6EnhancedTransferScreen(onBack = { showIpv6Enhanced = false })
        return
    }
    if (showUdpFileExperiment) {
        UdpFileTransferExperimentScreen(onBack = { showUdpFileExperiment = false })
        return
    }
    if (showIperfLab) {
        IperfLabScreen(onBack = { showIperfLab = false })
        return
    }
    if (showLibreSpeedLab) {
        LibreSpeedLabScreen(onBack = { showLibreSpeedLab = false })
        return
    }
    if (showSpeedTestLab) {
        SpeedTestLabScreen(onBack = { showSpeedTestLab = false })
        return
    }
    if (showFileCodeBoxLab) {
        FileCodeBoxLabScreen(onBack = { showFileCodeBoxLab = false })
        return
    }
    if (showAcoustic) {
        AcousticTransferScreen(onBack = { showAcoustic = false })
        return
    }
    if (showSstv) {
        SstvTransferScreen(onBack = { showSstv = false })
        return
    }
    if (showNfc) {
        NfcTransferScreen(onBack = { showNfc = false })
        return
    }
    if (showCimbar) {
        CimbarTransferScreen(onBack = { showCimbar = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("传输实验室", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            text = "这里会持续加入新的实验型传输模式。",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Text(
            text = "打流&测速",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showSpeedTestLab = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("SpeedTest（多地址下载 / 上传测速）")
                }
                OutlinedButton(
                    onClick = { showIperfLab = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("iperf 打流测试（iperf2 / iperf3）")
                }
                OutlinedButton(
                    onClick = { showLibreSpeedLab = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Liber Speed Test（小型测速服务器）")
                }
                OutlinedButton(
                    onClick = { showFileCodeBoxLab = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("文件快递柜(FileCodeBox)")
                }
                OutlinedButton(
                    onClick = { showUdpFileExperiment = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("UDP传文件（实验，含单向冗余无握手）")
                }
            }
        }

        Text(
            text = "打洞",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showNatTypeDetect = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("NAT类型探测")
                }
                OutlinedButton(
                    onClick = { showIpv4Stun = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("IPv4 STUN UDP 打洞验证（实验）")
                }
                OutlinedButton(
                    onClick = { showIpv6Enhanced = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("IPv6打洞验证")
                }
            }
        }

        Text(
            text = "扬声器 & 麦克风 & 摄像头",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showAcoustic = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("声波文件传输（实验）")
                }
                OutlinedButton(
                    onClick = { showSstv = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("SSTV 图片传输（Robot36）")
                }
                OutlinedButton(
                    onClick = { showCimbar = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("CIMBAR 文件传输（实验）")
                }
                OutlinedButton(
                    onClick = { showNfc = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("NFC 文件传输（实验）")
                }
            }
        }

    }
}

@Composable
fun CimbarTransferScreen(onBack: () -> Unit) {
    var tabIndex by remember { mutableIntStateOf(0) } // 0: send, 1: receive
    if (tabIndex == 1) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("返回传输实验室")
                }
                TabRow(selectedTabIndex = tabIndex) {
                    Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("CIMBAR 发送") })
                    Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("CIMBAR 接收") })
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                AirGapReceiveScreen(onBack = { tabIndex = 0 })
            }
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cimbarMaxBytes = 20L * 1024L * 1024L
    val cimbarFrameDispatcher = remember { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var cimbarSender by remember { mutableStateOf<AirGapSender?>(null) }
    var cimbarFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cimbarRunning by remember { mutableStateOf(false) }
    var cimbarMode by remember { mutableIntStateOf(68) } // 4 / 66 / 67 / 68
    var cimbarLastFrameW by remember { mutableIntStateOf(0) }
    var cimbarLastFrameH by remember { mutableIntStateOf(0) }
    var showCimbarFullscreen by remember { mutableStateOf(false) }
    var cimbarFullscreenRotation by remember { mutableIntStateOf(0) } // 0..3 => 0/90/180/270
    var showCimbarHelpDialog by remember { mutableStateOf(false) }

    fun resetCimbarSender() {
        cimbarRunning = false
        cimbarFrameBitmap = null
        showCimbarFullscreen = false
        cimbarFullscreenRotation = 0
        cimbarLastFrameW = 0
        cimbarLastFrameH = 0
        cimbarSender?.shutdown()
        cimbarSender = null
    }

    suspend fun handlePickedFile(uri: Uri) {
        val fileName = getFileName(context, uri)
        val fileSize = getFileSize(context, uri)
        if (fileSize <= 0L) {
            errorMessage = "无法读取文件大小，CIMBAR 模式仅支持小于 20MB 的文件。"
            return
        }
        if (fileSize >= cimbarMaxBytes) {
            errorMessage = "CIMBAR 模式仅支持小于 20MB 的文件，当前：${formatSize(fileSize)}"
            return
        }
        val fileBytes = readUriBytes(context, uri, cimbarMaxBytes)
        if (fileBytes == null || fileBytes.isEmpty()) {
            errorMessage = "读取文件失败，无法启动 CIMBAR 发送。"
            return
        }

        resetCimbarSender()
        val sender = AirGapSender()
        if (!sender.isReady()) {
            errorMessage = "CIMBAR 编码器初始化失败：${sender.getInitError() ?: "unknown"}"
            return
        }
        val prepared = withContext(Dispatchers.Default) {
            sender.prepare(fileName, fileBytes, cimbarMode, 3)
        }
        if (!prepared) {
            sender.shutdown()
            errorMessage = "CIMBAR 编码准备失败。"
            return
        }
        selectedFileUri = uri
        selectedFileName = fileName
        cimbarSender = sender
        cimbarRunning = true
        statusMessage = "CIMBAR 文件已就绪：$fileName（${formatSize(fileSize)}），模式 $cimbarMode，正在播放动态彩码。"
        errorMessage = null
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch { handlePickedFile(uri) }
        }
    }

    LaunchedEffect(cimbarRunning, cimbarSender) {
        val sender = cimbarSender ?: return@LaunchedEffect
        if (!cimbarRunning) return@LaunchedEffect
        while (isActive && cimbarRunning && cimbarSender === sender) {
            val frame = withContext(cimbarFrameDispatcher) { sender.nextFrame() }
            if (frame != null && frame.size > 2) {
                val width = frame[0]
                val height = frame[1]
                if (width > 0 && height > 0 && frame.size >= 2 + width * height) {
                    cimbarLastFrameW = width
                    cimbarLastFrameH = height
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(frame, 2, width, 0, 0, width, height)
                    cimbarFrameBitmap = bitmap
                }
            }
            delay(66)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            resetCimbarSender()
            cimbarFrameDispatcher.close()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回传输实验室")
        }
        TabRow(selectedTabIndex = tabIndex) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("CIMBAR 发送") })
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("CIMBAR 接收") })
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("CIMBAR 文件发送", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(
                    text = "选择文件后播放动态彩码，接收端在同一页切到“CIMBAR 接收”即可扫码接收。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CIMBAR 模式选择",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = { showCimbarHelpDialog = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = "CIMBAR 说明"
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(4, 66, 67, 68).forEach { mode ->
                        OutlinedButton(
                            onClick = {
                                cimbarMode = mode
                                if (selectedFileUri != null) {
                                    scope.launch { handlePickedFile(selectedFileUri!!) }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = cimbarMode != mode
                        ) {
                            Text(mode.toString(), fontSize = 12.sp)
                        }
                    }
                }
                Button(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("选择文件（CIMBAR）")
                }
                OutlinedButton(
                    onClick = {
                        resetCimbarSender()
                        selectedFileUri = null
                        selectedFileName = null
                        statusMessage = "已停止 CIMBAR 发送。"
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = cimbarSender != null || cimbarFrameBitmap != null
                ) {
                    Text("停止当前分享")
                }
            }
        }

        if (selectedFileName != null || statusMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    selectedFileName?.let { Text("当前内容: $it", fontWeight = FontWeight.SemiBold) }
                    statusMessage?.let { Text(it, fontSize = 13.sp) }
                }
            }
        }

        if (cimbarFrameBitmap != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 380.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = cimbarFrameBitmap!!.asImageBitmap(),
                        contentDescription = "cimbar_dynamic_frame",
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { showCimbarFullscreen = true },
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        errorMessage?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }

    if (showCimbarFullscreen && cimbarFrameBitmap != null) {
        Dialog(
            onDismissRequest = { showCimbarFullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val src = cimbarFrameBitmap!!
                val rotated = rotateBitmapByQuarterTurns(src, cimbarFullscreenRotation)
                Image(
                    bitmap = rotated.asImageBitmap(),
                    contentDescription = "cimbar_dynamic_frame_fullscreen",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { cimbarFullscreenRotation = (cimbarFullscreenRotation + 1) % 4 }
                    ) {
                        Text("旋转 90°")
                    }
                    OutlinedButton(
                        onClick = { showCimbarFullscreen = false }
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }

    if (showCimbarHelpDialog) {
        AlertDialog(
            onDismissRequest = { showCimbarHelpDialog = false },
            title = { Text("什么是 CIMBAR") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("CIMBAR 是一种通过动态彩码传输文件的数据编码方式。")
                    Text(
                        "当无法使用局域网/互联网时，可以仅依靠屏幕与摄像头完成传输。",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("当前本应用因实现与稳定性限制，仅支持小于 20MB 文件。")
                    Text("模式建议：")
                    Text("4：低密度，兼容性高，速度较慢。")
                    Text("66：中等密度。")
                    Text("67：一般手机推荐，速度与稳定性更均衡。")
                    Text("68：高密度，环境要求更高。")
                    Text("实测建议：将彩码放大后，旋转 90° 更容易识别。")
                    Text("官网：https://cimbar.org/")
                    Text("GitHub：https://github.com/sz3/libcimbar")
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://cimbar.org/")
                                )
                            )
                        }
                    }) { Text("打开官网") }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/sz3/libcimbar")
                                )
                            )
                        }
                    }) { Text("打开 GitHub") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showCimbarHelpDialog = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
fun SstvTransferScreen(onBack: () -> Unit) {
    data class SstvModeItem(
        val id: String,
        val label: String,
        val implemented: Boolean,
        val encoderMode: Robot36SstvEncoder.Mode?
    )

    val modeItems = remember {
        listOf(
            SstvModeItem("ROBOT36", "Robot36", true, Robot36SstvEncoder.Mode.ROBOT_36),
            SstvModeItem("ROBOT72", "Robot72", true, Robot36SstvEncoder.Mode.ROBOT_72),
            SstvModeItem("PD50", "PD50", true, Robot36SstvEncoder.Mode.PD_50),
            SstvModeItem("PD90", "PD90", true, Robot36SstvEncoder.Mode.PD_90),
            SstvModeItem("PD120", "PD120", true, Robot36SstvEncoder.Mode.PD_120),
            SstvModeItem("PD160", "PD160", true, Robot36SstvEncoder.Mode.PD_160),
            SstvModeItem("PD180", "PD180", true, Robot36SstvEncoder.Mode.PD_180),
            SstvModeItem("PD240", "PD240", true, Robot36SstvEncoder.Mode.PD_240),
            SstvModeItem("PD590", "PD590", true, Robot36SstvEncoder.Mode.PD_590),
            SstvModeItem("M1", "Martin M1", true, Robot36SstvEncoder.Mode.MARTIN_M1),
            SstvModeItem("M2", "Martin M2", true, Robot36SstvEncoder.Mode.MARTIN_M2),
            SstvModeItem("S1", "Scottie S1", true, Robot36SstvEncoder.Mode.SCOTTIE_S1),
            SstvModeItem("S2", "Scottie S2", true, Robot36SstvEncoder.Mode.SCOTTIE_S2),
            SstvModeItem("SDX", "SDX", true, Robot36SstvEncoder.Mode.SCOTTIE_DX),
            SstvModeItem("WSC2_180", "WSC2-180", true, Robot36SstvEncoder.Mode.WRAASE_SC2_180),
            SstvModeItem("HF_FAX", "HF Fax", true, Robot36SstvEncoder.Mode.HF_FAX)
        )
    }

    val context = LocalContext.current
    val historyManager = remember { DownloadHistoryManager(context) }
    var panelIndex by remember { mutableIntStateOf(0) } // 0: send, 1: receive
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isEncoding by remember { mutableStateOf(false) }
    var wavUri by remember { mutableStateOf<Uri?>(null) }
    var wavName by remember { mutableStateOf<String?>(null) }
    var selectedMode by remember { mutableStateOf("M1") }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackPositionMs by remember { mutableIntStateOf(0) }
    var playbackDurationMs by remember { mutableIntStateOf(0) }
    var playbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var scaleMode by remember { mutableStateOf(Robot36SstvEncoder.ScaleMode.CROP_CENTER) }
    var qualityPreset by remember { mutableStateOf(Robot36SstvEncoder.QualityPreset.STRICT) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var isReceiving by remember { mutableStateOf(false) }
    var receiveMode by remember { mutableStateOf(SstvRobot36Receiver.AUTO_MODE_NAME) }
    var forceDecodeWithoutVis by remember { mutableStateOf(false) }
    var receiver by remember { mutableStateOf<SstvRobot36Receiver?>(null) }
    var recorder by remember { mutableStateOf<AudioRecord?>(null) }
    var recorderAgc by remember { mutableStateOf<AutomaticGainControl?>(null) }
    var recorderNs by remember { mutableStateOf<NoiseSuppressor?>(null) }
    var recorderAec by remember { mutableStateOf<AcousticEchoCanceler?>(null) }
    var receiverJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var receivedImageFile by remember { mutableStateOf<File?>(null) }
    var receivedImageUri by remember { mutableStateOf<Uri?>(null) }
    var receivedImageName by remember { mutableStateOf<String?>(null) }
    var receivedModeName by remember { mutableStateOf<String?>(null) }
    var receivedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var receiveProgress by remember { mutableStateOf(0f) }
    var spectrumBins by remember { mutableStateOf(FloatArray(96)) }
    var waterfallLines by remember { mutableStateOf<List<FloatArray>>(emptyList()) }
    var autoSavedOnFinish by remember { mutableStateOf(false) }
    var receiveModeDetected by remember { mutableStateOf(false) }
    var receiveStageText by remember { mutableStateOf("空闲") }
    var receiveMessage by remember { mutableStateOf<String?>(null) }
    var receiveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val localView = LocalView.current

    DisposableEffect(Unit) {
        onDispose {
            playbackJob?.cancel()
            player?.release()
            receiverJob?.cancel()
            runCatching { recorder?.stop() }
            recorder?.release()
            recorderAgc?.release()
            recorderNs?.release()
            recorderAec?.release()
            receivedBitmap?.recycle()
        }
    }

    DisposableEffect(isEncoding, isPlaying, isReceiving, localView) {
        val prev = localView.keepScreenOn
        localView.keepScreenOn = isEncoding || isPlaying || isReceiving
        onDispose { localView.keepScreenOn = prev }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (!granted) {
            receiveError = "未授予麦克风权限，无法进行 SSTV 接收。"
        }
    }

    fun stopReceiving() {
        isReceiving = false
        receiverJob?.cancel()
        receiverJob = null
        runCatching { recorder?.stop() }
        recorder?.release()
        recorderAgc?.release()
        recorderNs?.release()
        recorderAec?.release()
        recorder = null
        recorderAgc = null
        recorderNs = null
        recorderAec = null
        receiver = null
    }

    LaunchedEffect(panelIndex) {
        if (panelIndex != 1 && isReceiving) {
            stopReceiving()
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        sourceUri = uri
        sourceName = getFileName(context, uri)
        errorMessage = null
        statusMessage = null
        isEncoding = true
        wavUri = null
        wavName = null
        playbackPositionMs = 0
        playbackDurationMs = 0
        val mode = modeItems.firstOrNull { it.id == selectedMode } ?: modeItems.first()
        val encoderMode = mode.encoderMode
        if (!mode.implemented || encoderMode == null) {
            isEncoding = false
            errorMessage = "当前版本暂未实现 ${mode.label} 编码内核。"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val wavFile = withContext(Dispatchers.IO) {
                runCatching {
                    val sourceBitmap = decodeBitmapFromUri(context, uri)
                        ?: throw IllegalArgumentException("图片读取失败")
                    try {
                        val baseDir = context.externalCacheDir ?: context.cacheDir
                        val outDir = File(baseDir, "sstv").apply { mkdirs() }
                        val outFile = File(outDir, "sstv_martin_m1_${System.currentTimeMillis()}.wav")
                        Robot36SstvEncoder.encodeToWavFile(
                            source = sourceBitmap,
                            output = outFile,
                            mode = encoderMode,
                            scaleMode = scaleMode,
                            qualityPreset = qualityPreset
                        )
                        outFile
                    } finally {
                        sourceBitmap.recycle()
                    }
                }.getOrNull()
            }
            isEncoding = false
            if (wavFile == null || !wavFile.exists()) {
                errorMessage = "SSTV 编码失败。"
                return@launch
            }
            wavUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", wavFile)
            wavName = wavFile.name
            playbackDurationMs = (((wavFile.length() - 44L).coerceAtLeast(0L) / 2.0 / 44_100.0) * 1000.0).roundToInt()
            statusMessage = if (mode.id == "HF_FAX") {
                "已生成 ${mode.label} 音频。Robot36 端需手动切换到 HF Fax 模式接收。"
            } else {
                "已生成 ${mode.label} 音频，可在对端 Robot36 选择对应模式接收。"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("返回") }
            Text("SSTV 图片传输", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        TabRow(selectedTabIndex = panelIndex) {
            Tab(selected = panelIndex == 0, onClick = { panelIndex = 0 }, text = { Text("发送") })
            Tab(selected = panelIndex == 1, onClick = { panelIndex = 1 }, text = { Text("接收") })
        }
        if (panelIndex == 1) {
            Text("解码器模式", fontSize = 12.sp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SstvRobot36Receiver.modeOptions.forEach { mode ->
                    OutlinedButton(
                        onClick = {
                            receiveMode = mode
                            val runtimeMode = if (mode == SstvRobot36Receiver.AUTO_MODE_NAME) null else mode
                            receiver?.setMode(runtimeMode)
                        },
                        enabled = receiveMode != mode,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(mode)
                    }
                }
            }
            Text("当前接收模式: $receiveMode", fontSize = 12.sp)
            val hfFaxForced = receiveMode == "HF Fax"
            val forceNoVisEffective = hfFaxForced || forceDecodeWithoutVis
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (hfFaxForced) "HF Fax: 已自动启用无VIS强制解码" else "无VIS强制解码（手动模式）",
                    fontSize = 12.sp
                )
                Switch(
                    checked = if (hfFaxForced) true else forceDecodeWithoutVis,
                    onCheckedChange = { checked ->
                        if (!hfFaxForced) {
                            forceDecodeWithoutVis = checked
                            val runtimeMode = if (receiveMode == SstvRobot36Receiver.AUTO_MODE_NAME) null else receiveMode
                            receiver?.setMode(runtimeMode)
                        }
                    },
                    enabled = !hfFaxForced
                )
            }
            Text("识别到模式: ${receivedModeName ?: "-"}", fontSize = 12.sp)
            LinearProgressIndicator(
                progress = { safeProgress(receiveProgress) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (!hasMicPermission) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@Button
                        }
                        if (isReceiving) return@Button

                        receiveError = null
                        receiveProgress = 0f
                        waterfallLines = emptyList()
                        spectrumBins = FloatArray(96)
                        receivedModeName = null
                        receiveModeDetected = false
                        receiveStageText = "等待识别模式"
                        receivedImageFile = null
                        receivedImageUri = null
                        receivedImageName = null
                        receivedBitmap?.recycle()
                        receivedBitmap = null
                        autoSavedOnFinish = false

                        val sampleRate = 44_100
                        val audioSource = selectSstvAudioSource()
                        val minBuffer = AudioRecord.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        if (minBuffer <= 0) {
                            receiveError = "AudioRecord 初始化失败。"
                            return@Button
                        }
                        val ar = AudioRecord(
                            audioSource,
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            (minBuffer * 6).coerceAtLeast(12 * 1024)
                        )
                        if (ar.state != AudioRecord.STATE_INITIALIZED) {
                            ar.release()
                            receiveError = "麦克风不可用。"
                            return@Button
                        }
                        val engine = SstvRobot36Receiver(sampleRate)
                        val spectrum = RealtimeSpectrumAnalyzer(256)
                        val modeToUse = if (receiveMode == SstvRobot36Receiver.AUTO_MODE_NAME) null else receiveMode
                        engine.setMode(modeToUse)
                        receiveMessage = if (modeToUse != null) {
                            "正在监听并解码（手动模式：$modeToUse，44.1kHz）..."
                        } else {
                            "正在监听并解码（VIS/同步自动识别，44.1kHz）..."
                        }
                        receiver = engine
                        recorder = ar
                        runCatching {
                            if (AutomaticGainControl.isAvailable()) {
                                recorderAgc = AutomaticGainControl.create(ar.audioSessionId)?.apply { enabled = false }
                            }
                            if (NoiseSuppressor.isAvailable()) {
                                recorderNs = NoiseSuppressor.create(ar.audioSessionId)?.apply { enabled = false }
                            }
                            if (AcousticEchoCanceler.isAvailable()) {
                                recorderAec = AcousticEchoCanceler.create(ar.audioSessionId)?.apply { enabled = false }
                            }
                        }
                        runCatching { ar.startRecording() }.onFailure {
                            ar.release()
                            recorder = null
                            receiver = null
                            receiveError = "开始录音失败：${it.message}"
                            return@Button
                        }

                        isReceiving = true
                        receiverJob = scope.launch(Dispatchers.IO) {
                            val readBuffer = ShortArray(2048)
                            var lastPreviewAt = 0L
                            var completed = false
                            while (isActive && isReceiving) {
                                val n = ar.read(readBuffer, 0, readBuffer.size)
                                if (n <= 0) continue
                                val result = engine.processPcm16(readBuffer, n)
                                val spectrumUpdated = spectrum.pushPcm16(readBuffer, n)
                                val now = System.currentTimeMillis()
                                val recognized = result.modeDetected
                                val preview = if (now - lastPreviewAt >= 180L) {
                                    lastPreviewAt = now
                                    val imagePreview = engine.snapshotImageProgress()
                                    if (forceNoVisEffective) {
                                        engine.snapshotScopeImage() ?: imagePreview
                                    } else {
                                        if (recognized || receiveModeDetected) {
                                            imagePreview ?: engine.snapshotScopeImage()
                                        } else {
                                            null
                                        }
                                    }
                                } else {
                                    null
                                }
                                val image = engine.consumeImageIfReady()
                                withContext(Dispatchers.Main) {
                                    receivedModeName = result.modeName
                                    if (recognized) {
                                        receiveModeDetected = true
                                        receiveStageText = "已识别模式：${result.modeName ?: receiveMode}，正在解码"
                                    }
                                    receiveProgress = engine.progressFraction()
                                    if (spectrumUpdated) {
                                        val bins = spectrum.downsample(96)
                                        spectrumBins = bins
                                        val next = ArrayList<FloatArray>(waterfallLines.size + 1)
                                        next.add(bins)
                                        next.addAll(waterfallLines)
                                        if (next.size > 110) next.removeAt(next.lastIndex)
                                        waterfallLines = next
                                    }
                                    if (preview != null && image == null) {
                                        receivedBitmap?.recycle()
                                        receivedBitmap = preview
                                    }
                                    if (image != null) {
                                        val isHfFax = (receiveMode == "HF Fax") || (result.modeName == "HF Fax")
                                        val finalBitmap = if (isHfFax) {
                                            engine.snapshotScopeImagePostProcessed() ?: image
                                        } else {
                                            image
                                        }
                                        if (finalBitmap !== image) {
                                            image.recycle()
                                        }
                                        receivedBitmap?.recycle()
                                        receivedBitmap = finalBitmap
                                        val outFile = saveBitmapToSstvReceiveFile(context, finalBitmap)
                                        if (outFile != null) {
                                            receivedImageFile = outFile
                                            receivedImageName = outFile.name
                                            receivedImageUri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                outFile
                                            )
                                            historyManager.addRecord(
                                                DownloadRecord(
                                                    fileName = outFile.name,
                                                    filePath = outFile.absolutePath,
                                                    fileSize = outFile.length(),
                                                    sourceUrl = "SSTV接收-麦克风解码"
                                                )
                                            )
                                            if (!autoSavedOnFinish) {
                                                val localUri = FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    outFile
                                                )
                                                val galleryUri = saveImageUriToGallery(
                                                    context = context,
                                                    sourceUri = localUri,
                                                    fileName = outFile.name
                                                )
                                                if (galleryUri != null) {
                                                    historyManager.addRecord(
                                                        DownloadRecord(
                                                            fileName = outFile.name,
                                                            filePath = galleryUri.toString(),
                                                            fileSize = outFile.length(),
                                                            sourceUrl = "SSTV接收-自动保存图库"
                                                        )
                                                    )
                                                    receiveMessage = "接收结束，已自动保存到图库。"
                                                    receiveStageText = "接收完成"
                                                    autoSavedOnFinish = true
                                                } else {
                                                    receiveMessage = "接收结束，但自动保存图库失败。"
                                                    receiveStageText = "接收完成（自动保存失败）"
                                                }
                                            } else {
                                                receiveMessage = "解码成功，已生成图片文件。"
                                                receiveStageText = "接收完成"
                                            }
                                            receiveProgress = 1f
                                            completed = true
                                        } else {
                                            receiveError = "解码成功但保存文件失败。"
                                            receiveStageText = "接收完成（保存失败）"
                                        }
                                    }
                                }
                                if (completed) break
                            }
                            if (completed) {
                                withContext(Dispatchers.Main) {
                                    stopReceiving()
                                    receiveMessage = (receiveMessage ?: "接收结束，已停止接收。")
                                }
                            }
                        }
                    },
                    enabled = !isReceiving,
                    modifier = Modifier.weight(1f)
                ) { Text("开始接收") }
                OutlinedButton(
                    onClick = {
                        val snapshot = receiver?.snapshotScopeImagePostProcessed()
                            ?: receiver?.snapshotImageProgress()
                            ?: receivedBitmap
                        if (snapshot != null) {
                            val out = saveBitmapToSstvReceiveFile(context, snapshot)
                            if (out != null) {
                                receivedImageFile = out
                                receivedImageName = out.name
                                receivedImageUri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    out
                                )
                                historyManager.addRecord(
                                    DownloadRecord(
                                        fileName = out.name,
                                        filePath = out.absolutePath,
                                        fileSize = out.length(),
                                        sourceUrl = "SSTV接收-手动停止导出"
                                    )
                                )
                                receiveMessage = "已手动停止，并导出当前接收图像。"
                                receiveStageText = "已手动停止"
                            } else {
                                receiveError = "手动停止后导出图像失败。"
                                receiveStageText = "已手动停止（导出失败）"
                            }
                        } else {
                            receiveMessage = "已停止接收。"
                            receiveStageText = "已手动停止"
                        }
                        stopReceiving()
                    },
                    enabled = isReceiving,
                    modifier = Modifier.weight(1f)
                ) { Text("停止接收") }
            }
            Text(if (isReceiving) "状态: 接收中" else "状态: 空闲", fontSize = 12.sp)
            Text("解码阶段: $receiveStageText", fontSize = 12.sp)

            Text("实时解码预览", fontSize = 12.sp)
            receivedBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "接收图片预览",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    contentScale = ContentScale.Crop
                )
            }

            Text("实时频谱/瀑布流", fontSize = 12.sp)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color(0xFF0A0F14), RoundedCornerShape(10.dp))
                    .padding(6.dp)
            ) {
                val bins = spectrumBins
                val rows = waterfallLines
                val w = size.width
                val h = size.height
                val topH = h * 0.35f
                val bottomH = h - topH
                if (bins.isNotEmpty()) {
                    val bw = w / bins.size.toFloat()
                    for (i in bins.indices) {
                        val v = bins[i].coerceIn(0f, 1f)
                        val bh = v * topH
                        drawRect(
                            color = spectrumColor(v),
                            topLeft = Offset(i * bw, topH - bh),
                            size = Size((bw - 1f).coerceAtLeast(1f), bh.coerceAtLeast(1f))
                        )
                    }
                }
                if (rows.isNotEmpty()) {
                    val maxRows = rows.size.coerceAtLeast(1)
                    val rh = bottomH / maxRows.toFloat()
                    val bw = w / (rows[0].size.coerceAtLeast(1)).toFloat()
                    for (r in rows.indices) {
                        val y = topH + r * rh
                        val row = rows[r]
                        for (i in row.indices) {
                            val v = row[i].coerceIn(0f, 1f)
                            drawRect(
                                color = spectrumColor(v),
                                topLeft = Offset(i * bw, y),
                                size = Size((bw + 0.2f).coerceAtLeast(1f), (rh + 0.2f).coerceAtLeast(1f))
                            )
                        }
                    }
                }
            }

            receivedImageUri?.let { uri ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val saved = saveImageUriToGallery(
                                context = context,
                                sourceUri = uri,
                                fileName = receivedImageName
                            )
                            if (saved == null) {
                                receiveError = "保存到图库失败。"
                            } else {
                                receiveMessage = "已保存到图库。"
                                val size = (receivedImageFile?.length() ?: getFileSize(context, uri)).coerceAtLeast(0L)
                                historyManager.addRecord(
                                    DownloadRecord(
                                        fileName = receivedImageName ?: "sstv_received_${System.currentTimeMillis()}.png",
                                        filePath = saved.toString(),
                                        fileSize = size,
                                        sourceUrl = "SSTV接收-保存图库"
                                    )
                                )
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("保存到图库") }
                    OutlinedButton(
                        onClick = {
                            val mime = getMimeType(context, uri)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mime)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(Intent.createChooser(intent, "选择应用打开")) }
                                .onFailure { receiveError = "打开失败：${it.message}" }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("其他方式打开") }
                }
            }
            receiveMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            receiveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            return@Column
        }
        Card {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("模式选择")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    modeItems.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(
                                onClick = { selectedMode = item.id },
                                modifier = Modifier.weight(1f),
                                enabled = selectedMode != item.id
                            ) {
                                Text(item.label)
                            }
                            Text(
                                text = if (item.implemented) "可用" else "待实现",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 8.dp, top = 10.dp)
                            )
                        }
                    }
                }
                val currentMode = modeItems.firstOrNull { it.id == selectedMode } ?: modeItems.first()
                Text("当前模式：${currentMode.label}", fontSize = 12.sp)
                Text("建议发送端音量 70%-90%，并让接收端麦克风靠近扬声器。", fontSize = 12.sp)
                Text("画幅策略", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { scaleMode = Robot36SstvEncoder.ScaleMode.CROP_CENTER },
                        enabled = scaleMode != Robot36SstvEncoder.ScaleMode.CROP_CENTER,
                        modifier = Modifier.weight(1f)
                    ) { Text("填满裁切", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { scaleMode = Robot36SstvEncoder.ScaleMode.FIT_BLACK_BARS },
                        enabled = scaleMode != Robot36SstvEncoder.ScaleMode.FIT_BLACK_BARS,
                        modifier = Modifier.weight(1f)
                    ) { Text("完整黑边", fontSize = 12.sp) }
                }
                Text("音质预设", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { qualityPreset = Robot36SstvEncoder.QualityPreset.STRICT },
                        enabled = qualityPreset != Robot36SstvEncoder.QualityPreset.STRICT,
                        modifier = Modifier.weight(1f)
                    ) { Text("严格原始", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { qualityPreset = Robot36SstvEncoder.QualityPreset.CLARITY },
                        enabled = qualityPreset != Robot36SstvEncoder.QualityPreset.CLARITY,
                        modifier = Modifier.weight(1f)
                    ) { Text("清晰优先", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { qualityPreset = Robot36SstvEncoder.QualityPreset.STANDARD },
                        enabled = qualityPreset != Robot36SstvEncoder.QualityPreset.STANDARD,
                        modifier = Modifier.weight(1f)
                    ) { Text("标准", fontSize = 12.sp) }
                }
            }
        }

        OutlinedButton(
            onClick = { picker.launch("image/*") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isEncoding
        ) {
            Text(if (isEncoding) "编码中..." else "选择图片并生成 SSTV 音频")
        }
        Text("图片: ${sourceName ?: "未选择"}", fontSize = 12.sp)
        wavUri?.let { readyUri ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        runCatching {
                            playbackJob?.cancel()
                            player?.release()
                            val p = MediaPlayer().apply {
                                setDataSource(context, readyUri)
                                setOnCompletionListener { mp ->
                                    isPlaying = false
                                    playbackPositionMs = playbackDurationMs
                                    mp.release()
                                    if (player === mp) player = null
                                }
                                prepare()
                                playbackDurationMs = duration.coerceAtLeast(playbackDurationMs)
                                playbackPositionMs = 0
                                start()
                            }
                            player = p
                            isPlaying = true
                            playbackJob = scope.launch {
                                while (isPlaying && player === p) {
                                    playbackPositionMs = runCatching { p.currentPosition }.getOrDefault(0)
                                    delay(200)
                                }
                            }
                        }.onFailure {
                            isPlaying = false
                            errorMessage = "播放失败：${it.message}"
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (isPlaying) "重新播放" else "播放") }
                OutlinedButton(
                    onClick = {
                        playbackJob?.cancel()
                        playbackJob = null
                        player?.let { p ->
                            runCatching { p.stop() }
                            p.release()
                        }
                        player = null
                        isPlaying = false
                        playbackPositionMs = 0
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("停止") }
            }
            if (playbackDurationMs > 0) {
                Text(
                    "时长: ${"%.1f".format(playbackDurationMs / 1000.0)} 秒，播放: ${"%.1f".format(playbackPositionMs / 1000.0)} 秒",
                    fontSize = 12.sp
                )
                LinearProgressIndicator(
                    progress = { safeProgress(playbackPositionMs, playbackDurationMs) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedButton(
                onClick = {
                    val chooser = Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "audio/wav"
                            putExtra(Intent.EXTRA_STREAM, readyUri)
                            putExtra(Intent.EXTRA_SUBJECT, wavName ?: "sstv_martin_m1.wav")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "分享 SSTV 音频"
                    )
                    runCatching { context.startActivity(chooser) }
                        .onFailure { errorMessage = "分享失败：${it.message}" }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("分享 SSTV 音频文件") }
        }
        statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun HighlightToggleButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier
        ) {
            Text(label)
        }
    }
}

@Composable
private fun ShareHeroBadge(
    label: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                shape = RoundedCornerShape(999.dp)
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                shape = RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun FileShareHeroCard(
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = shape
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.98f),
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.96f),
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f)
                        )
                    ),
                    shape = shape
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    shape = shape
                )
                .padding(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(132.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(999.dp)
                        )
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .size(88.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(999.dp)
                        )
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ShareHeroBadge("二维码主分享入口")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "把文件直接变成二维码",
                            fontSize = 26.sp,
                            lineHeight = 31.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "支持文件、照片、视频和 APK。入口更集中，选完内容后直接进入分享。",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(92.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                shape = RoundedCornerShape(26.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(26.dp)
                            )
                            .padding(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(38.dp)
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(32.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(14.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "↑",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ShareHeroBadge("文件")
                    ShareHeroBadge("照片 / 视频")
                    ShareHeroBadge("应用 APK")
                }
                Button(
                    onClick = onClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("选择文件并生成二维码", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileTransferScreen(
    args: FileTransferScreenArgs
) {
    val initialFileUri = args.initialFileUri
    val initialSharedText = args.initialSharedText
    val onFileSelected = args.onFileSelected
    val onFilesSelected = args.onFilesSelected
    val onStopServer = args.onStopServer
    val allowedFileSendFlags = args.allowedFileSendFlags
    val initialFileSendType = args.initialFileSendType
    val onBack = args.onBack
    val onSecondaryPageVisibleChanged = args.onSecondaryPageVisibleChanged
    val onInitialFileConsumed = args.onInitialFileConsumed
    val onInitialTextConsumed = args.onInitialTextConsumed
    val onRequestOpenEnhancedReceive = args.onRequestOpenEnhancedReceive
    fun supportsSendType(type: Int): Boolean {
        val bit = when (type) {
            0 -> FILE_SEND_FLAG_NORMAL
            1 -> FILE_SEND_FLAG_CIMBAR
            2 -> FILE_SEND_FLAG_SSTV
            else -> 0
        }
        return bit != 0 && (allowedFileSendFlags and bit) != 0
    }

    fun firstSupportedSendType(): Int {
        return when {
            supportsSendType(0) -> 0
            supportsSendType(1) -> 1
            supportsSendType(2) -> 2
            else -> 0
        }
    }

    val cimbarMaxBytes = 20L * 1024L * 1024L
    var selectedMode by remember { mutableIntStateOf(0) } // 0: file, 1: enhanced, 2: clipboard/text
    val normalizedInitialType = if (supportsSendType(initialFileSendType)) {
        initialFileSendType
    } else {
        firstSupportedSendType()
    }
    var fileSendType by remember(allowedFileSendFlags, normalizedInitialType) {
        mutableIntStateOf(normalizedInitialType)
    } // 0: normal, 1: cimbar, 2: sstv
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var selectedFileMimeType by remember { mutableStateOf<String?>(null) }
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var qrCodeBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var hasBluetoothPermission by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showQrDetailPage by remember { mutableStateOf(false) }
    var showQrExitConfirmDialog by remember { mutableStateOf(false) }
    var isCustomTextQrShare by remember { mutableStateOf(false) }
    var showCopyUrlConfirmDialog by remember { mutableStateOf(false) }
    var pendingCopyUrl by remember { mutableStateOf<String?>(null) }
    var cimbarSender by remember { mutableStateOf<AirGapSender?>(null) }
    var cimbarFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cimbarRunning by remember { mutableStateOf(false) }
    var cimbarMode by remember { mutableIntStateOf(68) } // 4 / 66 / 67 / 68
    var cimbarLastFrameW by remember { mutableIntStateOf(0) }
    var cimbarLastFrameH by remember { mutableIntStateOf(0) }
    var showCimbarFullscreen by remember { mutableStateOf(false) }
    var cimbarFullscreenRotation by remember { mutableIntStateOf(0) } // 0..3 => 0/90/180/270
    var showCimbarHelpDialog by remember { mutableStateOf(false) }
    var sstvWavUri by remember { mutableStateOf<Uri?>(null) }
    var sstvWavName by remember { mutableStateOf<String?>(null) }
    var sstvPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var sstvPlaying by remember { mutableStateOf(false) }
    var sstvScaleMode by remember { mutableStateOf(Robot36SstvEncoder.ScaleMode.CROP_CENTER) }
    var sstvQualityPreset by remember { mutableStateOf(Robot36SstvEncoder.QualityPreset.STRICT) }

    val scope = rememberCoroutineScope()
    val cimbarFrameDispatcher = remember { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
    val context = LocalContext.current
    val localView = LocalView.current
    var localSendV2Enabled by remember { mutableStateOf(NeighborDiscoverySettingsStore.get(context).localSendV2Enabled) }
    val hotspotPreferences = remember { HotspotPreferences(context) }
    var hotspotConfig by remember { mutableStateOf<HotspotConfig?>(null) }
    var hotspotEnabled by remember { mutableStateOf(false) }
    var showHotspotDetailPage by remember { mutableStateOf(false) }
    var showHotspotPermissionDialog by remember { mutableStateOf(false) }
    var hotspotPermissionMessage by remember { mutableStateOf("热点权限不足，请先授权后再开启。") }
    var networkShareMode by remember { mutableIntStateOf(0) } // 0: IPv4 局域网, 1: IPv6 公网
    var shareRandomPortEnabled by remember {
        mutableStateOf(NeighborDiscoverySettingsStore.get(context).shareRandomPort)
    }
    var configuredFixedSharePort by remember {
        mutableIntStateOf(NeighborDiscoverySettingsStore.get(context).shareFixedPort)
    }
    var manualPortInput by remember {
        mutableStateOf(NeighborDiscoverySettingsStore.get(context).shareFixedPort.toString())
    }
    var enableNatAssist by remember { mutableStateOf(false) }
    var showMainShareStunProbing by remember { mutableStateOf(false) }
    var mainShareStunProbeChecked by remember { mutableIntStateOf(0) }
    var mainShareStunProbeTotal by remember { mutableIntStateOf(0) }
    var mainShareStunProbeSuccessCount by remember { mutableIntStateOf(0) }
    var mainShareStunProbeCurrentServer by remember { mutableStateOf("") }
    var mainShareStunProbeControl by remember { mutableStateOf<MainShareStunProbeControl?>(null) }
    var showMainShareStunPicker by remember { mutableStateOf(false) }
    var pendingMainShareRequest by remember { mutableStateOf<PendingMainShareRequest?>(null) }
    var selectedMainShareStun by remember { mutableStateOf<StunMappedEndpoint?>(null) }
    var showFilePickOptionSheet by remember { mutableStateOf(false) }
    var showMultiFileShareChoiceSheet by remember { mutableStateOf(false) }
    var pendingMultiFileUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingMultiFileTotalBytes by remember { mutableStateOf(0L) }
    var pendingMultiFileCount by remember { mutableIntStateOf(0) }
    var currentBatchShareUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showClipboardFileShareConfig by remember { mutableStateOf(false) }
    var pendingClipboardFileUri by remember { mutableStateOf<Uri?>(null) }
    var pendingClipboardFileName by remember { mutableStateOf<String?>(null) }
    var pendingClipboardMimeType by remember { mutableStateOf<String?>(null) }
    var reversePushEnabled by remember { mutableStateOf(false) }
    var reversePushAddress by remember { mutableStateOf("") }
    var showReversePushConfigSheet by remember { mutableStateOf(false) }
    var showReversePushScanner by remember { mutableStateOf(false) }
    var showReversePendingSheet by remember { mutableStateOf(false) }
    var reversePendingFiles by remember { mutableStateOf<List<Triple<Uri, String, String>>>(emptyList()) }
    var showExternalShareChoiceDialog by remember { mutableStateOf(false) }
    var pendingExternalShareUri by remember { mutableStateOf<Uri?>(null) }
    var pendingExternalShareFileName by remember { mutableStateOf("") }
    var pendingExternalShareMimeType by remember { mutableStateOf("") }
    var reverseSendControl by remember { mutableStateOf<ReversePushSendControl?>(null) }
    var reverseSendProgress by remember { mutableStateOf(ReversePushSendProgress()) }
    var showReverseSendProgressSheet by remember { mutableStateOf(false) }
    var showInstalledApkPicker by remember { mutableStateOf(false) }
    var externalShareAutoModeCountDown by remember { mutableIntStateOf(10) }
    var pendingIpv4EnhancedIncomingUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingIpv6EnhancedIncomingUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showIpv4UdpEnhancedSend by rememberSaveable { mutableStateOf(false) }
    var showIpv6UdpEnhancedSend by rememberSaveable { mutableStateOf(false) }
    var showClipboardSyncPage by remember { mutableStateOf(false) }
    var showClipboardActionSheet by remember { mutableStateOf(false) }
    var showTextActionSheet by remember { mutableStateOf(false) }
    val neighborPeers = remember { mutableStateListOf<LanNeighborPeer>() }
    val cachedFileTranPeers = remember { mutableStateListOf<LanNeighborPeer>() }
    val cachedLocalSendPeers = remember { mutableStateListOf<LanNeighborPeer>() }
    var neighborScanRunning by remember { mutableStateOf(false) }
    var neighborScanJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var manualNeighborIp by remember { mutableStateOf("") }
    var showManualNeighborIpDialog by remember { mutableStateOf(false) }
    val neighborHistory = remember { mutableStateListOf<NeighborHistoryEntry>() }
    var neighborInfiniteScan by remember { mutableStateOf(true) }
    var showQuickInviteProgressDialog by remember { mutableStateOf(false) }
    var quickInviteProgress by remember { mutableStateOf(0f) }
    var quickInviteProgressText by remember { mutableStateOf("准备发送请求...") }
    var quickInviteSendCancelled by remember { mutableStateOf(false) }
    var quickInviteForceCancelAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var quickInvitePendingTargetIp by remember { mutableStateOf("") }
    var quickInvitePendingRequestId by remember { mutableStateOf("") }
    var quickInvitePendingReverseTask by remember { mutableStateOf<QuickInviteReverseTask?>(null) }
    var quickInviteRejectDialogMessage by remember { mutableStateOf<String?>(null) }
    var quickInviteBusyDialogMessage by remember { mutableStateOf<String?>(null) }
    var quickInviteTimeoutDialogMessage by remember { mutableStateOf<String?>(null) }
    var localSendDraftItems by remember { mutableStateOf<List<LocalSendDraftItem>>(emptyList()) }
    var localSendDraftEphemeral by remember { mutableStateOf(false) }
    var showLocalSendTargetSheet by remember { mutableStateOf(false) }
    var localSendTargetSheetTitle by remember { mutableStateOf("选择 LocalSend 设备") }
    val isSecondaryPageVisible =
        showIpv4UdpEnhancedSend ||
            showIpv6UdpEnhancedSend ||
            showClipboardSyncPage ||
            showClipboardActionSheet ||
            showTextActionSheet ||
            showQrDetailPage ||
            showMainShareStunProbing ||
            showMainShareStunPicker ||
            showQrExitConfirmDialog ||
            showFilePickOptionSheet ||
            showMultiFileShareChoiceSheet ||
            showClipboardFileShareConfig ||
            showReversePushConfigSheet ||
            showReversePushScanner ||
            showReversePendingSheet ||
            showLocalSendTargetSheet ||
            showManualNeighborIpDialog ||
            showQuickInviteProgressDialog ||
            quickInviteRejectDialogMessage != null ||
            quickInviteTimeoutDialogMessage != null ||
            showExternalShareChoiceDialog ||
            showReverseSendProgressSheet ||
            showInstalledApkPicker ||
            showHotspotDetailPage ||
            showHotspotPermissionDialog ||
            showCopyUrlConfirmDialog ||
            showCimbarFullscreen ||
            showCimbarHelpDialog
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            val settings = NeighborDiscoverySettingsStore.get(context)
            localSendV2Enabled = settings.localSendV2Enabled
            shareRandomPortEnabled = settings.shareRandomPort
            configuredFixedSharePort = settings.shareFixedPort
            if (!settings.shareRandomPort) {
                manualPortInput = settings.shareFixedPort.toString()
            } else if (manualPortInput.toIntOrNull() !in 1024..65535) {
                manualPortInput = settings.shareFixedPort.toString()
            }
        }
        val prefs = NeighborDiscoverySettingsStore.registerChangeListener(context, listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    LaunchedEffect(isSecondaryPageVisible) {
        onSecondaryPageVisibleChanged?.invoke(isSecondaryPageVisible)
    }
    DisposableEffect(Unit) {
        onDispose {
            onSecondaryPageVisibleChanged?.invoke(false)
        }
    }
    LaunchedEffect(shareRandomPortEnabled, configuredFixedSharePort) {
        val displayPort = if (shareRandomPortEnabled) {
            runCatching {
                ServerSocket(0).use { socket ->
                    socket.reuseAddress = true
                    socket.localPort
                }
            }.getOrNull()?.takeIf { it in 1024..65535 } ?: configuredFixedSharePort
        } else {
            configuredFixedSharePort.coerceIn(1024, 65535)
        }
        manualPortInput = displayPort.toString()
    }
    if (showIpv4UdpEnhancedSend) {
        Ipv4UdpEnhancedSendScreen(
            onBack = { showIpv4UdpEnhancedSend = false },
            initialFileUris = pendingIpv4EnhancedIncomingUris,
            onInitialFilesConsumed = { pendingIpv4EnhancedIncomingUris = emptyList() },
            onRequestSwitchToReceive = {
                showIpv4UdpEnhancedSend = false
                onRequestOpenEnhancedReceive(false)
            }
        )
        return
    }
    if (showIpv6UdpEnhancedSend) {
        Ipv6UdpEnhancedSendScreen(
            onBack = { showIpv6UdpEnhancedSend = false },
            initialFileUris = pendingIpv6EnhancedIncomingUris,
            onInitialFilesConsumed = { pendingIpv6EnhancedIncomingUris = emptyList() },
            onRequestSwitchToReceive = {
                showIpv6UdpEnhancedSend = false
                onRequestOpenEnhancedReceive(true)
            }
        )
        return
    }
    if (showClipboardSyncPage) {
        ClipboardSyncPage(onBack = { showClipboardSyncPage = false })
        return
    }

    fun clearLocalSendDraftSelection(clearPersistent: Boolean) {
        if (clearPersistent || localSendDraftEphemeral) {
            localSendDraftItems = emptyList()
            localSendDraftEphemeral = false
        }
        showLocalSendTargetSheet = false
        localSendTargetSheetTitle = "选择 LocalSend 设备"
    }

    fun nextRandomSharePort(): Int {
        val preferredFallback = configuredFixedSharePort.coerceIn(1024, 65535)
        return runCatching {
            ServerSocket(0).use { socket ->
                socket.reuseAddress = true
                socket.localPort
            }
        }.getOrNull()
            ?.takeIf { it in 1024..65535 }
            ?: preferredFallback
    }

    fun resolveSharePort(forceRefreshRandom: Boolean = false): Int {
        return if (shareRandomPortEnabled) {
            val existing = manualPortInput.toIntOrNull()
            val port = if (forceRefreshRandom || existing !in 1024..65535) {
                nextRandomSharePort()
            } else {
                existing ?: nextRandomSharePort()
            }
            manualPortInput = port.toString()
            port
        } else {
            configuredFixedSharePort.coerceIn(1024, 65535).also {
                manualPortInput = it.toString()
            }
        }
    }

    fun clearShareState() {
        selectedFileUri = null
        selectedFileName = null
        selectedFileMimeType = null
        serverUrl = null
        qrCodeBitmap = null
        showQrDetailPage = false
        showQrExitConfirmDialog = false
        isCustomTextQrShare = false
        showCopyUrlConfirmDialog = false
        pendingCopyUrl = null
        showHotspotDetailPage = false
        showFilePickOptionSheet = false
        showMultiFileShareChoiceSheet = false
        pendingMultiFileUris = emptyList()
        pendingMultiFileTotalBytes = 0L
        pendingMultiFileCount = 0
        currentBatchShareUris = emptyList()
        showMainShareStunProbing = false
        mainShareStunProbeChecked = 0
        mainShareStunProbeTotal = 0
        mainShareStunProbeSuccessCount = 0
        mainShareStunProbeCurrentServer = ""
        mainShareStunProbeControl = null
        showMainShareStunPicker = false
        pendingMainShareRequest = null
        selectedMainShareStun = null
        showClipboardSyncPage = false
        showClipboardActionSheet = false
        showTextActionSheet = false
        showClipboardFileShareConfig = false
        pendingClipboardFileUri = null
        pendingClipboardFileName = null
        pendingClipboardMimeType = null
        reversePendingFiles = emptyList()
        showReversePendingSheet = false
        showInstalledApkPicker = false
        pendingIpv4EnhancedIncomingUris = emptyList()
        pendingIpv6EnhancedIncomingUris = emptyList()
        clearLocalSendDraftSelection(clearPersistent = true)
        quickInvitePendingReverseTask = null
        neighborPeers.clear()
        cachedFileTranPeers.clear()
        cachedLocalSendPeers.clear()
        neighborInfiniteScan = true
        neighborScanJob?.cancel()
    }

    fun buildInlineLocalSendDraft(text: String, prefix: String): List<LocalSendDraftItem> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return emptyList()
        val bytes = trimmed.toByteArray(Charsets.UTF_8)
        return listOf(
            LocalSendDraftItem(
                fileName = "${prefix}_${System.currentTimeMillis()}.txt",
                mimeType = "text/plain",
                size = bytes.size.toLong(),
                inlineBytes = bytes,
                previewText = trimmed
            )
        )
    }

    fun describeClipboardContent(): Pair<String, String> {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clipData = clipboard?.primaryClip
        if (clipData == null || clipData.itemCount <= 0) {
            return "剪贴板为空" to ""
        }
        val item = clipData.getItemAt(0)
        val directText = item.text?.toString().orEmpty().trim()
        if (directText.isNotBlank()) {
            return "剪贴板文本" to directText.take(1200)
        }
        val uri = item.uri
        if (uri != null) {
            val fileName = getFileName(context, uri).ifBlank { uri.lastPathSegment ?: "clipboard_item" }
            val mimeType = getMimeType(context, uri).ifBlank { "未知类型" }
            return "剪贴板文件" to "$fileName · $mimeType"
        }
        val fallback = item.coerceToText(context)?.toString().orEmpty().trim()
        if (fallback.isNotBlank()) {
            return "剪贴板内容" to fallback.take(1200)
        }
        return "暂不支持的剪贴板内容" to ""
    }

    suspend fun buildClipboardLocalSendDrafts(): List<LocalSendDraftItem> {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clipData = clipboard?.primaryClip ?: return emptyList()
        if (clipData.itemCount <= 0) return emptyList()
        val item = clipData.getItemAt(0)
        val directText = item.text?.toString().orEmpty().trim()
        if (directText.isNotBlank()) {
            return buildInlineLocalSendDraft(directText, "clipboard")
        }

        val uri = item.uri
        if (uri != null) {
            val fileName = getFileName(context, uri).ifBlank { "clipboard_${System.currentTimeMillis()}" }
            val mimeType = getMimeType(context, uri).ifBlank {
                URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
            }
            return listOf(
                LocalSendDraftItem(
                    uri = uri,
                    fileName = fileName,
                    mimeType = mimeType,
                    size = getFileSize(context, uri).coerceAtLeast(0L)
                )
            )
        }

        val fallback = item.coerceToText(context)?.toString().orEmpty().trim()
        return if (fallback.isNotBlank()) {
            buildInlineLocalSendDraft(fallback, "clipboard")
        } else {
            emptyList()
        }
    }

    suspend fun collectLocalSendFolderDrafts(treeUri: Uri): List<LocalSendDraftItem> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val rootName = root.name?.trim().orEmpty().ifBlank { "folder_${System.currentTimeMillis()}" }
        val drafts = mutableListOf<LocalSendDraftItem>()

        fun walk(parent: DocumentFile, prefix: String) {
            parent.listFiles().forEach { child ->
                val childName = child.name?.trim().orEmpty().ifBlank { "file_${drafts.size + 1}" }
                val relativePath = "$prefix/$childName".replace('\\', '/')
                when {
                    child.isDirectory -> walk(child, relativePath)
                    child.isFile -> {
                        val mimeType = child.type.orEmpty().ifBlank {
                            URLConnection.guessContentTypeFromName(childName) ?: "application/octet-stream"
                        }
                        drafts += LocalSendDraftItem(
                            uri = child.uri,
                            fileName = relativePath,
                            mimeType = mimeType,
                            size = child.length().coerceAtLeast(0L)
                        )
                    }
                }
            }
        }

        walk(root, rootName)
        drafts
    }

    suspend fun promptLocalSendTargetSelection(
        drafts: List<LocalSendDraftItem>,
        title: String,
        ephemeral: Boolean
    ) {
        if (drafts.isEmpty()) {
            errorMessage = "当前没有可发送到 LocalSend 的内容。"
            return
        }
        localSendDraftItems = drafts
        localSendDraftEphemeral = ephemeral
        localSendTargetSheetTitle = title
        errorMessage = null
        showLocalSendTargetSheet = true
    }

    fun refreshNeighborHistory() {
        neighborHistory.clear()
        neighborHistory.addAll(NeighborHistoryStore.load(context))
    }

    fun mergeNeighborPeerSources(
        fileTranPeers: List<LanNeighborPeer>,
        localSendPeers: List<LanNeighborPeer>
    ): List<LanNeighborPeer> {
        val mergedByIp = LinkedHashMap<String, LanNeighborPeer>()
        localSendPeers.forEach { peer ->
            mergedByIp[peer.ip.lowercase()] = peer.copy(peerProtocol = NeighborPeerProtocol.LOCALSEND)
        }
        // FileTran takes precedence over LocalSend for the same IP.
        fileTranPeers.forEach { peer ->
            mergedByIp[peer.ip.lowercase()] = peer.copy(peerProtocol = NeighborPeerProtocol.FILETRAN)
        }
        return mergedByIp.values.sortedWith(compareBy<LanNeighborPeer> { it.name.lowercase() }.thenBy { it.ip })
    }

    fun applyMergedNeighborPeers() {
        val merged = mergeNeighborPeerSources(
            fileTranPeers = cachedFileTranPeers,
            localSendPeers = cachedLocalSendPeers
        )
        neighborPeers.clear()
        neighborPeers.addAll(merged)
    }

    suspend fun scanNeighbors(targetIp: String? = null) {
        neighborScanRunning = true
        try {
            val target = targetIp?.trim()?.takeIf { it.isNotBlank() }
            // Probe FileTran first, then probe LocalSend.
            val fileTranPeers = withContext(Dispatchers.IO) {
                discoverLanNeighbors(context, targetIp = target, timeoutMs = 1400)
            }
            val localSendPeers = if (!localSendV2Enabled) {
                emptyList()
            } else if (target == null) {
                LocalSendRuntimeBridge.announceNow()
                delay(320)
                LocalSendRuntimeBridge.snapshotPeers()
            } else {
                withContext(Dispatchers.IO) {
                    listOfNotNull(LocalSendRuntimeBridge.probePeer(target))
                }
            }

            if (target == null) {
                cachedFileTranPeers.clear()
                cachedFileTranPeers.addAll(fileTranPeers)
                cachedLocalSendPeers.clear()
                cachedLocalSendPeers.addAll(localSendPeers)
            } else {
                cachedFileTranPeers.removeAll { it.ip.equals(target, ignoreCase = true) }
                cachedLocalSendPeers.removeAll { it.ip.equals(target, ignoreCase = true) }
                cachedFileTranPeers.addAll(fileTranPeers.filter { it.ip.equals(target, ignoreCase = true) })
                cachedLocalSendPeers.addAll(localSendPeers.filter { it.ip.equals(target, ignoreCase = true) })
            }
            applyMergedNeighborPeers()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
        } finally {
            neighborScanRunning = false
        }
    }

    fun requestScanNeighbors(targetIp: String? = null) {
        neighborScanJob?.cancel()
        neighborScanJob = scope.launch {
            scanNeighbors(targetIp)
        }
    }

    fun ipv4Prefix(ip: String): String? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        if (parts.any { it.toIntOrNull() !in 0..255 }) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}"
    }

    fun shouldPreferReverseForTarget(senderIp: String, targetIp: String): Boolean {
        val senderPrefix = ipv4Prefix(senderIp)
        val targetPrefix = ipv4Prefix(targetIp)
        if (senderPrefix == null || targetPrefix == null) return false
        return senderPrefix != targetPrefix
    }

    fun buildQuickInviteReverseFiles(): List<Triple<Uri, String, String>> {
        if (currentBatchShareUris.isNotEmpty()) {
            return currentBatchShareUris.mapIndexed { index, uri ->
                val name = getFileName(context, uri).ifBlank { "file_${index + 1}" }
                val mime = getMimeType(context, uri).ifBlank {
                    URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
                }
                Triple(uri, name, mime)
            }
        }
        val uri = selectedFileUri ?: return emptyList()
        val name = selectedFileName?.ifBlank { getFileName(context, uri) } ?: getFileName(context, uri)
        val mime = selectedFileMimeType?.ifBlank {
            getMimeType(context, uri).ifBlank {
                URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
            }
        } ?: getMimeType(context, uri).ifBlank {
            URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
        }
        return listOf(Triple(uri, name, mime))
    }

    fun buildLocalSendFilesFromDrafts(drafts: List<LocalSendDraftItem>): List<LocalSendSendFile> {
        if (drafts.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return drafts.mapIndexed { index, draft ->
            val inlineSize = draft.inlineBytes?.size?.toLong()
            LocalSendSendFile(
                id = "file_${index + 1}_${now}",
                uri = draft.uri,
                fileName = draft.fileName.ifBlank { "file_${index + 1}" },
                mimeType = draft.mimeType.ifBlank { "application/octet-stream" },
                size = (inlineSize ?: draft.size).coerceAtLeast(0L),
                inlineBytes = draft.inlineBytes,
                previewText = draft.previewText
            )
        }
    }

    fun buildLocalSendFilesForCurrentSelection(): List<LocalSendSendFile> {
        if (selectedMode == 2) {
            val textDrafts = when {
                localSendDraftEphemeral && localSendDraftItems.isNotEmpty() -> localSendDraftItems
                customText.trim().isNotBlank() -> buildInlineLocalSendDraft(customText, "message")
                else -> emptyList()
            }
            return buildLocalSendFilesFromDrafts(textDrafts)
        }

        val reverseFiles = buildQuickInviteReverseFiles()
        if (reverseFiles.isNotEmpty()) {
            val now = System.currentTimeMillis()
            return reverseFiles.mapIndexed { index, (uri, fileName, mimeType) ->
                LocalSendSendFile(
                    id = "file_${index + 1}_${now}",
                    uri = uri,
                    fileName = fileName.ifBlank { "file_${index + 1}" },
                    mimeType = mimeType.ifBlank { "application/octet-stream" },
                    size = getFileSize(context, uri).coerceAtLeast(0L)
                )
            }
        }
        return buildLocalSendFilesFromDrafts(localSendDraftItems)
    }

    suspend fun requestLocalSendPinFromUser(peer: LanNeighborPeer, invalidPin: Boolean): String? {
        return withContext(Dispatchers.Main) {
            val hostActivity = context as? Activity ?: return@withContext null
            suspendCancellableCoroutine { cont ->
                val input = EditText(hostActivity).apply {
                    hint = "请输入 6 位 PIN"
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    setText(LocalSendProtocol.getSavedPin(context, peer).orEmpty())
                    setSelection(text.length)
                }
                val message = if (invalidPin) {
                    "PIN 错误，请重新输入。"
                } else {
                    "该 LocalSend 设备开启了 PIN 验证，请输入后重试。"
                }
                val dialog = android.app.AlertDialog.Builder(hostActivity)
                    .setTitle("输入 LocalSend PIN")
                    .setMessage(message)
                    .setView(input)
                    .setCancelable(false)
                    .setNegativeButton("取消") { d, _ ->
                        if (cont.isActive) cont.resume(null)
                        d.dismiss()
                    }
                    .setPositiveButton("确定") { d, _ ->
                        if (cont.isActive) cont.resume(input.text?.toString()?.trim())
                        d.dismiss()
                    }
                    .setOnCancelListener {
                        if (cont.isActive) cont.resume(null)
                    }
                    .create()
                dialog.show()
                cont.invokeOnCancellation {
                    runCatching { dialog.dismiss() }
                }
            }
        }
    }

    suspend fun sendByLocalSendPeer(peer: LanNeighborPeer) {
        showLocalSendTargetSheet = false
        val files = buildLocalSendFilesForCurrentSelection()
        if (files.isEmpty()) {
            errorMessage = "LocalSend 发送需要先选择文件、文件夹或文本。"
            return
        }
        val clearEphemeralAfterSend = localSendDraftEphemeral
        quickInvitePendingTargetIp = peer.ip
        quickInvitePendingRequestId = "localsend_${System.currentTimeMillis()}"
        quickInviteSendCancelled = false
        showQuickInviteProgressDialog = true
        quickInviteProgress = 0f
        quickInviteProgressText = "正在连接 LocalSend 设备..."
        val progressHandler = Handler(Looper.getMainLooper())
        val sendControl = LocalSendSendControl()
        quickInviteForceCancelAction = {
            quickInviteSendCancelled = true
            quickInviteProgressText = "正在强制中断 LocalSend 发送..."
            showQuickInviteProgressDialog = false
            statusMessage = "正在强制中断 LocalSend 发送..."
            errorMessage = null
            sendControl.cancel(force = true)
        }

        var currentPin = LocalSendProtocol.getSavedPin(context, peer)
        var pinFirstAttempt = true
        try {
            while (true) {
                val result = withContext(Dispatchers.IO) {
                    LocalSendProtocol.sendFilesToPeer(
                        context = context,
                        peer = peer,
                        files = files,
                        pin = currentPin,
                        control = sendControl,
                        isCancelled = { quickInviteSendCancelled }
                    ) { progress ->
                        progressHandler.post {
                            quickInviteProgress = progress.progress
                            quickInviteProgressText = progress.message
                        }
                    }
                }

                if (quickInviteSendCancelled) {
                    showQuickInviteProgressDialog = false
                    quickInvitePendingTargetIp = ""
                    quickInvitePendingRequestId = ""
                    statusMessage = "已强制中断 LocalSend 发送。"
                    errorMessage = null
                    return
                }

                if (result.isSuccess) {
                    showQuickInviteProgressDialog = false
                    quickInvitePendingTargetIp = ""
                    quickInvitePendingRequestId = ""
                    LocalSendProtocol.savePin(context, peer, currentPin)
                    statusMessage = "LocalSend 发送完成。"
                    errorMessage = null
                    NeighborHistoryStore.upsert(
                        context,
                        NeighborHistoryEntry(
                            ip = peer.ip,
                            name = peer.name,
                            hostName = peer.hostName,
                            lastUsedAt = System.currentTimeMillis()
                        )
                    )
                    refreshNeighborHistory()
                    return
                }

                val errorText = result.exceptionOrNull()?.message.orEmpty()
                if (errorText == "AUTH_REQUIRED" || errorText.contains("prepare_upload_failed:401")) {
                    showQuickInviteProgressDialog = false
                    val pin = requestLocalSendPinFromUser(peer, invalidPin = !pinFirstAttempt)
                    pinFirstAttempt = false
                    if (pin.isNullOrBlank()) {
                        LocalSendProtocol.savePin(context, peer, null)
                        quickInvitePendingTargetIp = ""
                        quickInvitePendingRequestId = ""
                        errorMessage = "LocalSend 发送已取消（未输入 PIN）。"
                        return
                    }
                    currentPin = pin.trim()
                    showQuickInviteProgressDialog = true
                    quickInviteProgress = 0f
                    quickInviteProgressText = "PIN 已输入，正在重试..."
                    continue
                }

                showQuickInviteProgressDialog = false
                quickInvitePendingTargetIp = ""
                quickInvitePendingRequestId = ""
                when (errorText) {
                    "RECEIVER_BUSY" -> {
                        quickInviteBusyDialogMessage = "对方设备正忙，正在处理另一个接收请求，请稍后再试。"
                        errorMessage = null
                    }
                    "RECEIVER_DISABLED" -> {
                        errorMessage = "对方设备当前未开启 LocalSend 接收。"
                    }
                    "REJECTED" -> {
                        quickInviteRejectDialogMessage = "对方拒绝了本次 LocalSend 接收请求。"
                        errorMessage = null
                    }
                    else -> {
                        errorMessage = "LocalSend 发送失败: ${result.exceptionOrNull()?.message ?: "unknown"}"
                    }
                }
                return
            }
        } finally {
            quickInviteForceCancelAction = null
            if (clearEphemeralAfterSend) {
                clearLocalSendDraftSelection(clearPersistent = true)
            }
        }
    }

    suspend fun sendClipboardToLocalSendPeer(peer: LanNeighborPeer) {
        val drafts = buildClipboardLocalSendDrafts()
        if (drafts.isEmpty()) {
            errorMessage = "当前没有可发送的剪贴板内容。"
            return
        }
        localSendDraftItems = drafts
        localSendDraftEphemeral = true
        showClipboardActionSheet = false
        sendByLocalSendPeer(peer)
    }

    suspend fun sendTextToLocalSendPeer(peer: LanNeighborPeer) {
        val drafts = buildInlineLocalSendDraft(customText, "message")
        if (drafts.isEmpty()) {
            errorMessage = "文本内容为空。"
            return
        }
        localSendDraftItems = drafts
        localSendDraftEphemeral = true
        showTextActionSheet = false
        sendByLocalSendPeer(peer)
    }

    suspend fun sendQuickInviteToIp(targetIp: String, forceReverse: Boolean = false) {
        quickInviteForceCancelAction = null
        val senderTimeoutMs = 30_000
        val trimmedIp = targetIp.trim()
        if (trimmedIp.isBlank()) {
            errorMessage = "请输入有效 IPv4 地址。"
            return
        }
        val url = serverUrl?.trim().orEmpty()
        if (url.isBlank()) {
            errorMessage = "当前没有可发送内容，请先生成二维码分享地址。"
            return
        }
        val fileName = selectedFileName?.ifBlank { "FileTran 分享" } ?: "FileTran 分享"
        val fileSize = selectedFileUri?.let { getFileSize(context, it) } ?: -1L
        val senderIp = resolvePreferredIpv4ForTarget(
            targetIp = trimmedIp,
            fallback = NetworkUtils.getLocalIpAddress(context).orEmpty()
        )
        val autoPreferReverse = shouldPreferReverseForTarget(senderIp = senderIp, targetIp = trimmedIp)
        val preferredTransferMode = when {
            forceReverse -> QUICK_TRANSFER_MODE_REVERSE_PUSH
            autoPreferReverse -> QUICK_TRANSFER_MODE_REVERSE_PUSH
            else -> QUICK_TRANSFER_MODE_AUTO
        }
        val reverseFilesSnapshot = if (preferredTransferMode == QUICK_TRANSFER_MODE_REVERSE_PUSH) {
            buildQuickInviteReverseFiles()
        } else {
            emptyList()
        }
        if (preferredTransferMode == QUICK_TRANSFER_MODE_REVERSE_PUSH && reverseFilesSnapshot.isEmpty()) {
            errorMessage = "当前没有可反向传输的文件，请先选择文件后再发送。"
            return
        }
        val invite = QuickSendInvite(
            senderName = buildLocalNeighborName(context),
            senderHostName = buildLocalHostName(),
            senderIp = senderIp,
            fileName = fileName,
            fileSize = fileSize,
            downloadUrl = url,
            preferredTransferMode = preferredTransferMode
        )
        Log.i(
            "QuickInviteTx",
            "sendQuickInvite requestId=${invite.requestId} targetIp=$trimmedIp senderIp=${invite.senderIp} preferred=$preferredTransferMode url=${invite.downloadUrl}"
        )
        quickInvitePendingTargetIp = trimmedIp
        quickInvitePendingRequestId = invite.requestId
        showQuickInviteProgressDialog = true
        quickInviteProgress = 0f
        quickInviteProgressText = if (preferredTransferMode == QUICK_TRANSFER_MODE_REVERSE_PUSH) {
            "正在发送请求（反向传输）..."
        } else {
            "正在发送请求..."
        }
        quickInviteSendCancelled = false
        val waitingStartedAt = System.currentTimeMillis()
        val progressTicker = scope.launch {
            while (showQuickInviteProgressDialog && !quickInviteSendCancelled) {
                val elapsedMs = (System.currentTimeMillis() - waitingStartedAt).coerceAtLeast(0L)
                val elapsedSec = (elapsedMs / 1000L).toInt()
                quickInviteProgress = (elapsedMs.toFloat() / senderTimeoutMs.toFloat()).coerceIn(0f, 0.98f)
                quickInviteProgressText = if (preferredTransferMode == QUICK_TRANSFER_MODE_REVERSE_PUSH) {
                    "等待对方确认（反向传输，${elapsedSec}s/30s）"
                } else {
                    "等待对方确认（${elapsedSec}s/30s）"
                }
                delay(200)
            }
        }
        val response = withContext(Dispatchers.IO) {
            sendQuickSendInviteAndAwaitResponse(
                targetIp = trimmedIp,
                invite = invite,
                timeoutMs = senderTimeoutMs,
                isCancelled = { quickInviteSendCancelled }
            )
        }
        progressTicker.cancel()
        if (quickInviteSendCancelled) {
            showQuickInviteProgressDialog = false
            withContext(Dispatchers.IO) {
                sendQuickSendInviteCancel(
                    targetIp = quickInvitePendingTargetIp,
                    requestId = quickInvitePendingRequestId
                )
            }
            statusMessage = "已中断发送请求，可继续选择邻居发送。"
            errorMessage = null
            quickInvitePendingTargetIp = ""
            quickInvitePendingRequestId = ""
            quickInvitePendingReverseTask = null
            return
        }
        quickInviteProgress = 1f
        showQuickInviteProgressDialog = false
        if (response != null) {
            Log.i(
                "QuickInviteTx",
                "invite response requestId=${invite.requestId} accepted=${response.accepted} mode=${response.transferMode} endpoint=${response.reverseEndpoint}"
            )
            NeighborHistoryStore.upsert(
                context,
                NeighborHistoryEntry(
                    ip = trimmedIp,
                    name = neighborPeers.firstOrNull { it.ip == trimmedIp }?.name ?: "FileTran 邻居",
                    hostName = neighborPeers.firstOrNull { it.ip == trimmedIp }?.hostName.orEmpty(),
                    lastUsedAt = System.currentTimeMillis()
                )
            )
            refreshNeighborHistory()
            if (response.accepted) {
                if (response.transferMode.equals(QUICK_TRANSFER_MODE_REVERSE_PUSH, ignoreCase = true)) {
                    val reverseEndpoint = response.reverseEndpoint.trim()
                    if (reverseEndpoint.isBlank()) {
                        Log.w("QuickInviteTx", "reverse accepted but endpoint empty requestId=${invite.requestId}")
                        quickInviteRejectDialogMessage = "对方已接受，但未返回有效反向地址。"
                    } else if (reverseFilesSnapshot.isEmpty()) {
                        Log.w("QuickInviteTx", "reverse accepted but no file snapshot requestId=${invite.requestId}")
                        quickInviteRejectDialogMessage = "当前没有可发送文件，请重新选择后再试。"
                    } else {
                        statusMessage = "对方已接受请求，正在切换为反向传输..."
                        errorMessage = null
                        quickInvitePendingTargetIp = ""
                        quickInvitePendingRequestId = ""
                        Log.i("QuickInviteTx", "switch to reverse endpoint=$reverseEndpoint requestId=${invite.requestId}")
                        quickInvitePendingReverseTask = QuickInviteReverseTask(
                            requestId = invite.requestId,
                            endpoint = reverseEndpoint,
                            files = reverseFilesSnapshot
                        )
                        return
                    }
                } else {
                    statusMessage = "对方已接收请求，正在下载。"
                    errorMessage = null
                }
            } else {
                quickInviteRejectDialogMessage = response.message.ifBlank { "对方已拒绝接收。" }
            }
            quickInvitePendingTargetIp = ""
            quickInvitePendingRequestId = ""
        } else {
            Log.w("QuickInviteTx", "invite timeout requestId=${invite.requestId} target=$trimmedIp")
            quickInviteTimeoutDialogMessage = "等待对方确认超时（30s）。你可以重试，或让对方检查邻居发现设置。"
            quickInvitePendingTargetIp = ""
            quickInvitePendingRequestId = ""
            quickInvitePendingReverseTask = null
        }
    }

    suspend fun sendQuickInviteSmart(targetIp: String, forceReverse: Boolean = false) {
        val ip = targetIp.trim()
        if (ip.isBlank()) {
            errorMessage = "请输入有效 IPv4 地址。"
            return
        }
        val fileTranPeer = withContext(Dispatchers.IO) {
            discoverLanNeighbors(context, targetIp = ip, timeoutMs = 900)
                .firstOrNull { it.ip.equals(ip, ignoreCase = true) }
        }
        if (fileTranPeer != null) {
            sendQuickInviteToIp(ip, forceReverse = forceReverse)
            return
        }
        if (localSendV2Enabled) {
            val localSendPeer = withContext(Dispatchers.IO) {
                LocalSendRuntimeBridge.probePeer(ip)
            }
            if (localSendPeer != null) {
                sendByLocalSendPeer(localSendPeer)
                return
            }
        }
        sendQuickInviteToIp(ip, forceReverse = forceReverse)
    }

    suspend fun sendToNeighbor(peer: LanNeighborPeer, forceReverse: Boolean = false) {
        if (peer.peerProtocol == NeighborPeerProtocol.LOCALSEND) {
            sendByLocalSendPeer(peer)
        } else {
            sendQuickInviteToIp(peer.ip, forceReverse = forceReverse)
        }
    }

    fun resetCimbarSender() {
        cimbarRunning = false
        cimbarFrameBitmap = null
        showCimbarFullscreen = false
        cimbarFullscreenRotation = 0
        cimbarLastFrameW = 0
        cimbarLastFrameH = 0
        cimbarSender?.shutdown()
        cimbarSender = null
    }

    fun resetSstvState() {
        sstvPlaying = false
        sstvPlayer?.let { player ->
            runCatching { player.stop() }
            player.release()
        }
        sstvPlayer = null
        sstvWavUri = null
        sstvWavName = null
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasBluetoothPermission = permissions.values.all { it }
    }

    val hotspotPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        if (!granted) {
            hotspotPermissionMessage = "热点相关权限未全部授予，可能无法在应用内开启热点。"
            showHotspotPermissionDialog = true
        }
    }

    fun hotspotRuntimePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    fun hasHotspotRuntimePermissions(): Boolean {
        return hotspotRuntimePermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    suspend fun probeShareEndpoint(preferredPort: Int): ShareEndpointProbeResult? {
        val host = withContext(Dispatchers.IO) {
            if (networkShareMode == 1) {
                NetworkUtils.getLocalGlobalIpv6Address()
            } else {
                NetworkUtils.getLocalIpAddress(context)
            }
        } ?: return null
        val localPort = preferredPort
        val batch = if (enableNatAssist) {
            val preferIpv6 = networkShareMode == 1
            val configuredServers = NetworkUtils.configuredStunServers(
                transport = StunTransportType.TCP,
                preferIpv6 = preferIpv6
            )
            val control = MainShareStunProbeControl()
            mainShareStunProbeControl = control
            mainShareStunProbeChecked = 0
            mainShareStunProbeTotal = configuredServers.size
            mainShareStunProbeSuccessCount = 0
            mainShareStunProbeCurrentServer = ""
            showMainShareStunProbing = true

            var disableNatRequested = false
            val probed = try {
                withContext(Dispatchers.IO) {
                    NetworkUtils.probeStunMappedEndpointBatchWithProgress(
                        localPort = localPort,
                        preferIpv6 = preferIpv6,
                        transport = StunTransportType.TCP,
                        shouldStop = {
                            control.requestDisableNat.get() ||
                                (control.requestStopEarly.get() && control.successCount.get() > 0)
                        },
                        onServerStatus = { checked, total, status, successCount ->
                            control.successCount.set(successCount)
                            withContext(Dispatchers.Main) {
                                mainShareStunProbeChecked = checked
                                mainShareStunProbeTotal = total
                                mainShareStunProbeSuccessCount = successCount
                                mainShareStunProbeCurrentServer = status.server
                            }
                        }
                    )
                }
            } finally {
                disableNatRequested = control.requestDisableNat.get()
                showMainShareStunProbing = false
                mainShareStunProbeControl = null
                mainShareStunProbeChecked = 0
                mainShareStunProbeTotal = 0
                mainShareStunProbeSuccessCount = 0
                mainShareStunProbeCurrentServer = ""
            }

            if (disableNatRequested) {
                enableNatAssist = false
                statusMessage = "已关闭 NAT 并继续，当前将使用本地地址生成二维码。"
                null
            } else {
                probed
            }
        } else {
            null
        }
        return ShareEndpointProbeResult(
            localHost = host,
            localPort = localPort,
            natBatch = batch
        )
    }

    fun buildShareEndpointFromProbe(
        probe: ShareEndpointProbeResult,
        selectedStun: StunMappedEndpoint? = null
    ): ShareEndpoint {
        if (!enableNatAssist) {
            return ShareEndpoint(
                host = probe.localHost,
                publicPort = probe.localPort,
                listenPort = probe.localPort,
                note = null
            )
        }
        val batch = probe.natBatch
        val chosen = selectedStun ?: batch?.preferredEndpoint
        if (chosen == null || chosen.port !in 1..65535 || chosen.address.isBlank()) {
            return ShareEndpoint(
                host = probe.localHost,
                publicPort = probe.localPort,
                listenPort = probe.localPort,
                note = "NAT 探测失败，已回退本地地址。本机监听端口：${probe.localPort}，对外端口：${probe.localPort}。"
            )
        }
        val note = when {
            selectedStun != null && batch?.allMismatch == true -> {
                "已启用 NAT（已手动选择 STUN 地址）。本机监听端口：${probe.localPort}，对外端口：${chosen.port}。"
            }
            selectedStun != null -> {
                "已启用 NAT（已手动选择 STUN 地址）。本机监听端口：${probe.localPort}，对外端口：${chosen.port}。"
            }
            batch?.allMismatch == true -> {
                val ipip = batch.publicIpByIpip ?: "-"
                "已启用 NAT（多 STUN，默认首个结果）。但所有 STUN 返回 IP 与 ipip($ipip) 不一致；当前使用 ${chosen.address}:${chosen.port}。"
            }
            batch?.matchedByIpip?.isNotEmpty() == true -> {
                "已启用 NAT（多 STUN，存在 ipip 匹配项，默认首个结果）。本机监听端口：${probe.localPort}，对外端口：${chosen.port}。"
            }
            else -> {
                "已启用 NAT（STUN: ${chosen.stunServer}）。本机监听端口：${probe.localPort}，对外端口：${chosen.port}。若对端仍无法访问，请关闭 NAT 或改用 ZIP/局域网。"
            }
        }
        val extraWarning = if (batch?.hasPortMismatch == true) {
            "警告：不同 STUN 返回端口不一致（${batch.uniquePorts.joinToString("/") }），当前网络可能为 NAT4，传输可能失败。"
        } else {
            null
        }
        return ShareEndpoint(
            host = chosen.address,
            publicPort = chosen.port,
            listenPort = probe.localPort,
            note = listOfNotNull(note, extraWarning).joinToString(" ")
        )
    }

    fun parseHostPortInput(input: String): Pair<String, Int>? {
        val raw = input.trim()
        if (raw.isBlank()) return null
        if (raw.contains("://")) return null
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

    fun parseReversePushEndpoint(rawInput: String): ReversePushEndpoint? {
        val input = rawInput.trim()
        if (input.isBlank()) return null
        if (input.startsWith("filetranpush://")) {
            val withoutScheme = input.removePrefix("filetranpush://")
            parseHostPortInput(withoutScheme)?.let { (host, port) ->
                return ReversePushEndpoint(protocol = "tcp", host = host, port = port)
            }
        }
        parseHostPortInput(input)?.let { (host, port) ->
            return ReversePushEndpoint(protocol = "tcp", host = host, port = port)
        }
        val normalized = if (input.startsWith("http://") || input.startsWith("https://")) input else "http://$input"
        return runCatching {
            val parsed = URL(normalized)
            val host = parsed.host.orEmpty().trim()
            val port = parsed.port.takeIf { it in 1..65535 } ?: parsed.defaultPort.takeIf { it in 1..65535 }
            if (host.isBlank() || port == null) null else ReversePushEndpoint(protocol = "http", host = host, port = port)
        }.getOrNull()
    }

    fun normalizeReversePushAddress(rawInput: String): String? {
        val endpoint = parseReversePushEndpoint(rawInput) ?: return null
        return when (endpoint.protocol) {
            "tcp" -> "filetranpush://${NetworkUtils.formatHostForUrl(endpoint.host)}:${endpoint.port}"
            else -> "http://${NetworkUtils.formatHostForUrl(endpoint.host)}:${endpoint.port}/upload"
        }
    }

    fun parseReversePushAddressFromQr(content: String): String? {
        val raw = content.trim()
        if (raw.isBlank()) return null
        runCatching {
            val json = JSONObject(raw)
            val type = json.optString("type")
            if (type == "filetran_reverse_push_v2") {
                val endpoint = json.optString("endpoint").trim()
                if (endpoint.isNotBlank()) {
                    return normalizeReversePushAddress(endpoint)
                }
            }
            if (type == "filetran_reverse_upload_v1") {
                val uploadUrl = json.optString("uploadUrl").trim()
                if (uploadUrl.isNotBlank()) {
                    return normalizeReversePushAddress(uploadUrl)
                }
            }
        }
        return normalizeReversePushAddress(raw)
    }

    val reversePushReady = !normalizeReversePushAddress(reversePushAddress).isNullOrBlank()

    suspend fun uploadToReverseTargetHttp(
        endpoint: ReversePushEndpoint,
        uri: Uri,
        fileName: String,
        mimeType: String
    ): String? {
        val targetUrl = "http://${NetworkUtils.formatHostForUrl(endpoint.host)}:${endpoint.port}/upload"
        return withContext(Dispatchers.IO) {
            runCatching {
                val uploadTempDir = File(context.cacheDir, "reverse_push_upload").apply { mkdirs() }
                val uploadTempFile = File(uploadTempDir, "push_${System.currentTimeMillis()}_${fileName.hashCode()}.bin")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(uploadTempFile).use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                } ?: throw IllegalStateException("无法读取待发送文件")
                if (!uploadTempFile.exists() || uploadTempFile.length() <= 0L) {
                    throw IllegalStateException("待发送文件为空")
                }

                val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 120000
                    doOutput = true
                    setRequestProperty("Content-Type", mimeType.ifBlank { "application/octet-stream" })
                    setRequestProperty("X-File-Name", URLEncoder.encode(fileName, "UTF-8"))
                    setRequestProperty("X-File-Mime", mimeType.ifBlank { "application/octet-stream" })
                    setRequestProperty("Connection", "close")
                    setRequestProperty("Accept", "application/json")
                    setFixedLengthStreamingMode(uploadTempFile.length())
                }
                FileInputStream(uploadTempFile).use { input ->
                    conn.outputStream.use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
                val responseCode = conn.responseCode
                val responseText = runCatching {
                    (if (responseCode in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                }.getOrNull().orEmpty()
                conn.disconnect()
                runCatching { uploadTempFile.delete() }
                if (responseCode !in 200..299) {
                    throw IllegalStateException("HTTP $responseCode ${responseText.take(120)}")
                }
                responseText
            }.getOrElse { throw it }
        }
    }

    fun writeSizedUtf(out: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    fun readSizedUtf(input: DataInputStream, maxBytes: Int): String {
        val length = input.readInt()
        if (length < 0 || length > maxBytes) throw IllegalStateException("Bad text length")
        if (length == 0) return ""
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    suspend fun uploadToReverseTargetTcp(
        endpoint: ReversePushEndpoint,
        uri: Uri,
        fileName: String,
        mimeType: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): String? {
        val logTag = "ReversePushTx"
        return withContext(Dispatchers.IO) {
            runCatching {
                val size = getFileSize(context, uri)
                if (size < 0L) throw IllegalStateException("无法读取文件大小")
                if (isCancelled()) throw IllegalStateException(REVERSE_PUSH_CANCELLED)
                val host = endpoint.host.trim().removePrefix("[").removeSuffix("]").replace("%25", "%")
                val localIpv4 = NetworkUtils.getLocalIpAddress(context)
                val localIpv6 = NetworkUtils.getInterfaceGlobalIpv6Address()
                val connectivityManager = context.getSystemService(android.net.ConnectivityManager::class.java)
                Log.i(logTag, "local net ipv4=$localIpv4 ipv6=$localIpv6")
                Log.i(logTag, "start tcp push host=$host port=${endpoint.port} file=$fileName size=$size mime=$mimeType")
                val addresses = InetAddress.getAllByName(host).toList().sortedByDescending { it is Inet6Address }
                Log.i(logTag, "resolved addresses=${addresses.joinToString { it.hostAddress ?: "?" }}")
                connectivityManager?.allNetworks?.forEach { network ->
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    val lp = connectivityManager.getLinkProperties(network)
                    val hasInternet = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    val transports = buildList {
                        if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) add("WIFI")
                        if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("CELL")
                        if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ETH")
                        if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
                    }.joinToString("|")
                    val ipv4 = lp?.linkAddresses
                        ?.mapNotNull { it.address }
                        ?.filterIsInstance<java.net.Inet4Address>()
                        ?.joinToString { it.hostAddress ?: "?" }
                        ?: ""
                    val ipv6 = lp?.linkAddresses
                        ?.mapNotNull { it.address }
                        ?.filterIsInstance<java.net.Inet6Address>()
                        ?.filterNot { it.isLinkLocalAddress || it.isLoopbackAddress }
                        ?.joinToString { it.hostAddress ?: "?" }
                        ?: ""
                    Log.i(
                        logTag,
                        "network=$network internet=$hasInternet transports=$transports ipv4=[$ipv4] ipv6=[$ipv6]"
                    )
                }
                var lastError: Throwable? = null
                var socket: Socket? = null
                val maxAttempts = 6
                attemptLoop@ for (attempt in 1..maxAttempts) {
                    if (isCancelled()) throw IllegalStateException(REVERSE_PUSH_CANCELLED)
                    for (addr in addresses) {
                        val networkPlans: List<android.net.Network?> = run {
                            val cm = connectivityManager ?: return@run listOf(null)
                            val matched = cm.allNetworks.filter { network ->
                                val caps = cm.getNetworkCapabilities(network)
                                if (caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) return@filter false
                                val lp = cm.getLinkProperties(network) ?: return@filter false
                                val hasFamily = if (addr is java.net.Inet6Address) {
                                    lp.linkAddresses.any {
                                        val a = it.address
                                        a is java.net.Inet6Address && !a.isLinkLocalAddress && !a.isLoopbackAddress
                                    }
                                } else {
                                    lp.linkAddresses.any { it.address is java.net.Inet4Address }
                                }
                                hasFamily
                            }
                            (matched + listOf<android.net.Network?>(null)).distinctBy { it?.toString() ?: "default" }
                        }
                        for (network in networkPlans) {
                            if (isCancelled()) throw IllegalStateException(REVERSE_PUSH_CANCELLED)
                            val candidate = try {
                                if (network != null) network.socketFactory.createSocket() else Socket()
                            } catch (e: Throwable) {
                                lastError = e
                                Log.w(
                                    logTag,
                                    "socket create failed attempt=$attempt addr=${addr.hostAddress} network=${network ?: "default"} ${e.javaClass.simpleName} ${e.message}"
                                )
                                continue
                            }
                            try {
                                Log.i(
                                    logTag,
                                    "attempt=$attempt connect addr=${addr.hostAddress} port=${endpoint.port} network=${network ?: "default"}"
                                )
                                candidate.connect(InetSocketAddress(addr, endpoint.port), 4000)
                                candidate.soTimeout = 120000
                                socket = candidate
                                Log.i(
                                    logTag,
                                    "connected network=${network ?: "default"} local=${candidate.localAddress?.hostAddress}:${candidate.localPort} remote=${candidate.inetAddress?.hostAddress}:${candidate.port}"
                                )
                                break@attemptLoop
                            } catch (e: Throwable) {
                                lastError = e
                                Log.w(
                                    logTag,
                                    "connect failed attempt=$attempt addr=${addr.hostAddress} network=${network ?: "default"}: ${e.javaClass.simpleName} ${e.message}"
                                )
                                runCatching { candidate.close() }
                            }
                        }
                    }
                    // Route can transiently report unreachable; retry briefly before giving up.
                    Thread.sleep(500)
                }
                if (socket == null) {
                    Log.e(logTag, "all attempts failed host=$host port=${endpoint.port}", lastError)
                    throw IllegalStateException("无法连接到 $host:${endpoint.port}（已重试${maxAttempts}次）", lastError)
                }
                socket.use {
                    val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 16 * 1024))
                    out.writeInt(0x4654524E)
                    out.writeByte(1)
                    writeSizedUtf(out, fileName)
                    writeSizedUtf(out, mimeType.ifBlank { "application/octet-stream" })
                    out.writeLong(size)
                    var sentBytes = 0L
                    context.contentResolver.openInputStream(uri)?.use { fileIn ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (isCancelled()) throw IllegalStateException(REVERSE_PUSH_CANCELLED)
                            val read = fileIn.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            sentBytes += read.toLong()
                            onProgress(sentBytes, size)
                        }
                    } ?: throw IllegalStateException("无法读取待发送文件")
                    if (sentBytes != size) {
                        throw IllegalStateException("文件大小不一致，声明=$size，实际发送=$sentBytes")
                    }
                    out.flush()
                    onProgress(size, size)
                    if (isCancelled()) throw IllegalStateException(REVERSE_PUSH_CANCELLED)
                    Log.i(logTag, "payload sent; waiting ack")
                    val ack = input.readUnsignedByte()
                    val msg = readSizedUtf(input, 8 * 1024)
                    Log.i(logTag, "ack=$ack msg=$msg")
                    if (ack != 1) throw IllegalStateException(msg.ifBlank { "接收端拒绝" })
                    msg
                }
            }.getOrElse { throw it }
        }
    }

    suspend fun uploadToReverseTarget(targetUrl: String, uri: Uri, fileName: String, mimeType: String): String? {
        val endpoint = parseReversePushEndpoint(targetUrl)
            ?: throw IllegalStateException("反向传输地址无效")
        return when (endpoint.protocol) {
            "tcp" -> uploadToReverseTargetTcp(endpoint, uri, fileName, mimeType)
            else -> uploadToReverseTargetHttp(endpoint, uri, fileName, mimeType)
        }
    }

    suspend fun pushFilesToReverseTarget(files: List<Triple<Uri, String, String>>) {
        val targetUrl = normalizeReversePushAddress(reversePushAddress)
        if (!reversePushEnabled || targetUrl.isNullOrBlank()) {
            errorMessage = "反向传输地址无效，请先填写接收端上传地址。"
            return
        }
        val sendControl = ReversePushSendControl()
        reverseSendControl = sendControl
        reverseSendProgress = ReversePushSendProgress(
            fileIndex = 0,
            totalFiles = files.size,
            running = true,
            message = "准备开始传输..."
        )
        showReverseSendProgressSheet = true
        runCatching {
            val endpoint = parseReversePushEndpoint(targetUrl)
                ?: throw IllegalStateException("反向传输地址无效")
            withContext(Dispatchers.Main) {
                statusMessage = "开始反向传输，共 ${files.size} 个文件..."
                errorMessage = null
            }
            var sent = 0
            files.forEach { (uri, fileName, mimeType) ->
                if (sendControl.cancelled.get()) {
                    throw IllegalStateException(REVERSE_PUSH_CANCELLED)
                }
                withContext(Dispatchers.Main) {
                    reverseSendProgress = reverseSendProgress.copy(
                        fileName = fileName,
                        fileIndex = sent + 1,
                        totalFiles = files.size,
                        sentBytes = 0L,
                        totalBytes = 0L,
                        running = true,
                        message = "正在发送"
                    )
                }
                if (endpoint.protocol == "tcp") {
                    uploadToReverseTargetTcp(
                        endpoint = endpoint,
                        uri = uri,
                        fileName = fileName,
                        mimeType = mimeType,
                        onProgress = { sentBytes, totalBytes ->
                            scope.launch(Dispatchers.Main) {
                                reverseSendProgress = reverseSendProgress.copy(
                                    fileName = fileName,
                                    fileIndex = sent + 1,
                                    totalFiles = files.size,
                                    sentBytes = sentBytes,
                                    totalBytes = totalBytes,
                                    running = true,
                                    message = "正在发送"
                                )
                            }
                        },
                        isCancelled = { sendControl.cancelled.get() }
                    )
                } else {
                    uploadToReverseTargetHttp(endpoint, uri, fileName, mimeType)
                    withContext(Dispatchers.Main) {
                        reverseSendProgress = reverseSendProgress.copy(
                            fileName = fileName,
                            fileIndex = sent + 1,
                            totalFiles = files.size,
                            sentBytes = 0L,
                            totalBytes = 0L,
                            running = true,
                            message = "正在发送"
                        )
                    }
                }
                sent += 1
                withContext(Dispatchers.Main) {
                    statusMessage = "反向传输中：$sent/${files.size}（$fileName）"
                }
            }
            withContext(Dispatchers.Main) {
                selectedFileUri = null
                selectedFileName = null
                selectedFileMimeType = null
                currentBatchShareUris = emptyList()
                serverUrl = targetUrl
                qrCodeBitmap = null
                showQrDetailPage = false
                resetCimbarSender()
                resetSstvState()
                statusMessage = "反向传输完成，共发送 ${files.size} 个文件到 $targetUrl"
                errorMessage = null
                reversePendingFiles = emptyList()
                reverseSendProgress = reverseSendProgress.copy(
                    running = false,
                    fileIndex = files.size,
                    totalFiles = files.size,
                    message = "传输完成"
                )
                reverseSendControl = null
            }
        }.onFailure { e ->
            withContext(Dispatchers.Main) {
                if (e.message == REVERSE_PUSH_CANCELLED) {
                    errorMessage = "反向传输已取消。"
                    statusMessage = "反向传输已由用户取消。"
                    reverseSendProgress = reverseSendProgress.copy(
                        running = false,
                        message = "已取消"
                    )
                } else {
                    val root = generateSequence(e) { it.cause }.lastOrNull()
                    val rootText = if (root != null && root !== e) "；root=${root.javaClass.simpleName}:${root.message}" else ""
                    val routeHint = if ((root ?: e) is java.net.NoRouteToHostException) {
                        "；发送端到目标地址无路由（Host unreachable），请确认发送端当前网络具备可用IPv6路由，或改用IPv4反向地址"
                    } else {
                        ""
                    }
                    errorMessage = "反向传输失败：${e.javaClass.simpleName}:${e.message ?: "unknown"}$rootText$routeHint"
                    statusMessage = "反向传输中断，请检查接收端上传服务是否开启、地址是否可达。"
                    reverseSendProgress = reverseSendProgress.copy(
                        running = false,
                        message = "失败：${e.message ?: "unknown"}"
                    )
                }
                reverseSendControl = null
            }
            Log.e("ReversePushTx", "push failed", e)
        }
    }

    suspend fun startReversePendingTransfer() {
        if (!reversePushEnabled) return
        if (reversePendingFiles.isEmpty()) {
            errorMessage = "请先选择要传输的文件。"
            return
        }
        pushFilesToReverseTarget(reversePendingFiles)
    }

    LaunchedEffect(quickInvitePendingReverseTask?.requestId) {
        val task = quickInvitePendingReverseTask ?: return@LaunchedEffect
        val endpoint = task.endpoint.trim()
        if (endpoint.isBlank()) return@LaunchedEffect
        val normalized = normalizeReversePushAddress(endpoint)
        if (normalized.isNullOrBlank()) {
            errorMessage = "接收端返回的反向地址无效。"
            Log.w("QuickInviteTx", "reverse task endpoint invalid requestId=${task.requestId} endpoint=$endpoint")
            if (quickInvitePendingReverseTask?.requestId == task.requestId) {
                quickInvitePendingReverseTask = null
            }
            return@LaunchedEffect
        }
        val files = task.files
        if (files.isEmpty()) {
            errorMessage = "当前没有可发送的文件，无法执行反向传输。"
            Log.w("QuickInviteTx", "reverse task file list empty requestId=${task.requestId}")
            if (quickInvitePendingReverseTask?.requestId == task.requestId) {
                quickInvitePendingReverseTask = null
            }
            return@LaunchedEffect
        }
        Log.i("QuickInviteTx", "start reverse task requestId=${task.requestId} endpoint=$normalized files=${files.size}")
        val previousEnabled = reversePushEnabled
        val previousAddress = reversePushAddress
        reversePushEnabled = true
        reversePushAddress = normalized
        try {
            pushFilesToReverseTarget(files)
        } finally {
            reversePushEnabled = previousEnabled
            reversePushAddress = previousAddress
            if (quickInvitePendingReverseTask?.requestId == task.requestId) {
                quickInvitePendingReverseTask = null
            }
        }
    }

    suspend fun shareAsFileLink(
        uri: Uri,
        fileName: String,
        mimeType: String,
        skipStunPicker: Boolean = false,
        selectedStun: StunMappedEndpoint? = null,
        preProbed: ShareEndpointProbeResult? = null
    ) {
        clearLocalSendDraftSelection(clearPersistent = true)
        if (reversePushEnabled && selectedMode == 1) {
            pushFilesToReverseTarget(listOf(Triple(uri, fileName, mimeType)))
            return
        }
        val localPort = resolveSharePort(forceRefreshRandom = true)
        val probe = preProbed ?: probeShareEndpoint(localPort)
        if (probe == null) {
            errorMessage = if (networkShareMode == 1) {
                "未检测到可用公网 IPv6 地址，请确认当前网络具备 IPv6 出口。"
            } else {
                "未检测到局域网 IPv4，请先连接同一 Wi-Fi 或热点。"
            }
            return
        }
        val batch = probe.natBatch
        if (
            enableNatAssist &&
            !skipStunPicker &&
            selectedStun == null &&
            batch != null &&
            batch.endpoints.isNotEmpty()
        ) {
            pendingMainShareRequest = PendingMainShareRequest(
                probe = probe,
                singleUri = uri,
                singleName = fileName,
                singleMime = mimeType
            )
            selectedMainShareStun = batch.preferredEndpoint
            showMainShareStunPicker = true
            statusMessage = if (batch.allMismatch) {
                "检测到多个 STUN 地址且与 ipip 不一致，请先手动选择后再生成二维码。"
            } else if (batch.hasPortMismatch) {
                "检测到多个 STUN 地址且返回端口不一致（可能 NAT4），传输可能失败，请确认后继续。"
            } else {
                "检测到多个 STUN 地址，请确认要用于二维码的地址。"
            }
            errorMessage = null
            return
        }
        val endpoint = buildShareEndpointFromProbe(probe, selectedStun)
        val host = endpoint.host
        val publicPort = endpoint.publicPort
        val listenPort = endpoint.listenPort
        val natNote = endpoint.note
        manualPortInput = listenPort.toString()
        val url = "http://${NetworkUtils.formatHostForUrl(host)}:$publicPort"
        val bitmap = withContext(Dispatchers.Default) { QRCodeGenerator.generateQRCode(url, 512) }
        if (bitmap == null) {
            errorMessage = "二维码生成失败。"
            return
        }

        onFileSelected(uri, fileName, mimeType, listenPort)
        selectedFileUri = uri
        selectedFileName = fileName
        selectedFileMimeType = mimeType
        currentBatchShareUris = emptyList()
        serverUrl = url
        qrCodeBitmap = bitmap
        showQrDetailPage = true
        resetCimbarSender()
        resetSstvState()
        statusMessage = if (networkShareMode == 1) {
            "文件已就绪，IPv6 分享地址已生成（端口 $publicPort）。"
        } else {
            "文件已就绪，IPv4 局域网地址已生成（端口 $publicPort）。"
        }
        if (!natNote.isNullOrBlank()) {
            statusMessage = "$statusMessage $natNote"
        }
        errorMessage = null
    }

    fun buildMultiSharePayload(manifestUrl: String): String {
        val json = JSONObject()
        json.put("type", MULTI_SHARE_PAYLOAD_TYPE)
        json.put("concurrency", 3)
        json.put("manifestUrl", manifestUrl)
        return json.toString()
    }

    suspend fun shareMultipleAsBatchDownload(
        uris: List<Uri>,
        skipStunPicker: Boolean = false,
        selectedStun: StunMappedEndpoint? = null,
        preProbed: ShareEndpointProbeResult? = null
    ) {
        clearLocalSendDraftSelection(clearPersistent = true)
        if (uris.isEmpty()) {
            errorMessage = "未选择文件。"
            return
        }
        if (uris.size > MAX_BATCH_QR_FILES) {
            errorMessage = "多地址二维码最多支持 $MAX_BATCH_QR_FILES 个文件，文件较多请使用 ZIP 分享。"
            return
        }
        if (reversePushEnabled && selectedMode == 1) {
            val files = uris.map { uri ->
                val fileName = getFileName(context, uri)
                val mimeType = getMimeType(context, uri).ifBlank {
                    URLConnection.guessContentTypeFromName(fileName) ?: "*/*"
                }
                Triple(uri, fileName, mimeType)
            }
            pushFilesToReverseTarget(files)
            return
        }
        val localPort = resolveSharePort(forceRefreshRandom = true)
        val probe = preProbed ?: probeShareEndpoint(localPort)
        if (probe == null) {
            errorMessage = if (networkShareMode == 1) {
                "未检测到可用公网 IPv6 地址，请确认当前网络具备 IPv6 出口。"
            } else {
                "未检测到局域网 IPv4，请先连接同一 Wi-Fi 或热点。"
            }
            return
        }
        val batch = probe.natBatch
        if (
            enableNatAssist &&
            !skipStunPicker &&
            selectedStun == null &&
            batch != null &&
            batch.endpoints.isNotEmpty()
        ) {
            pendingMainShareRequest = PendingMainShareRequest(
                probe = probe,
                batchUris = uris
            )
            selectedMainShareStun = batch.preferredEndpoint
            showMainShareStunPicker = true
            statusMessage = if (batch.allMismatch) {
                "检测到多个 STUN 地址且与 ipip 不一致，请先手动选择后再生成二维码。"
            } else if (batch.hasPortMismatch) {
                "检测到多个 STUN 地址且返回端口不一致（可能 NAT4），传输可能失败，请确认后继续。"
            } else {
                "检测到多个 STUN 地址，请确认要用于二维码的地址。"
            }
            errorMessage = null
            return
        }
        val endpoint = buildShareEndpointFromProbe(probe, selectedStun)
        val host = endpoint.host
        val publicPort = endpoint.publicPort
        val basePort = endpoint.listenPort
        val natNote = endpoint.note
        manualPortInput = basePort.toString()
        val specs = mutableListOf<ShareFileSpec>()
        val cleanHost = NetworkUtils.formatHostForUrl(host)

        uris.forEach { uri ->
            val fileName = getFileName(context, uri)
            val mimeType = getMimeType(context, uri).ifBlank {
                URLConnection.guessContentTypeFromName(fileName) ?: "*/*"
            }
            specs.add(ShareFileSpec(uri = uri, fileName = fileName, mimeType = mimeType, port = basePort))
        }

        if (specs.isEmpty()) {
            errorMessage = "可用端口不足，无法分享。"
            return
        }

        val manifestUrl = "http://$cleanHost:$publicPort/manifest"
        val payload = buildMultiSharePayload(manifestUrl)
        val bitmap = withContext(Dispatchers.Default) { QRCodeGenerator.generateQRCode(payload, 512) }
        if (bitmap == null) {
            errorMessage = "二维码生成失败。"
            return
        }

        onFilesSelected(specs)
        currentBatchShareUris = uris
        selectedFileUri = null
        selectedFileName = "多文件共享（${specs.size} 个）"
        selectedFileMimeType = "application/json"
        serverUrl = manifestUrl
        qrCodeBitmap = bitmap
        showQrDetailPage = true
        resetCimbarSender()
        resetSstvState()
        manualPortInput = basePort.toString()
        statusMessage = "已生成多文件下载二维码：${specs.size} 个文件。并发下载在接收端控制（默认 3）。"
        if (!natNote.isNullOrBlank()) {
            statusMessage = "$statusMessage $natNote"
        }
        errorMessage = null
    }

    suspend fun createZipFromUris(uris: List<Uri>): Triple<Uri, String, Long>? {
        if (uris.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val outDir = File(context.externalCacheDir ?: context.cacheDir, "multi_zip").apply { mkdirs() }
                val outName = "filetran_multi_${System.currentTimeMillis()}.zip"
                val outFile = File(outDir, outName)
                val usedNames = mutableSetOf<String>()
                fun uniqueName(name: String): String {
                    if (usedNames.add(name)) return name
                    val base = name.substringBeforeLast('.', name)
                    val ext = name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
                    var index = 1
                    while (true) {
                        val candidate = "${base}_$index$ext"
                        if (usedNames.add(candidate)) return candidate
                        index++
                    }
                }
                ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                    uris.forEachIndexed { idx, uri ->
                        val sourceName = getFileName(context, uri).ifBlank { "file_${idx + 1}" }
                        val safeName = uniqueName(sourceName)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            zos.putNextEntry(ZipEntry(safeName))
                            input.copyTo(zos, 16 * 1024)
                            zos.closeEntry()
                        }
                    }
                }
                val zipUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
                Triple(zipUri, outName, outFile.length())
            }.getOrNull()
        }
    }

    suspend fun refreshCurrentFileShareQr() {
        if (currentBatchShareUris.isNotEmpty()) {
            shareMultipleAsBatchDownload(currentBatchShareUris)
            return
        }
        val uri = selectedFileUri
        val name = selectedFileName
        val mime = selectedFileMimeType
        if (uri == null || name.isNullOrBlank() || mime.isNullOrBlank()) {
            errorMessage = "当前不是文件分享二维码，无法刷新网络状态。"
            return
        }
        shareAsFileLink(uri, name, mime)
    }

    fun stopHotspotNow() {
        HotspotManager.stopHotspot(context)
        hotspotEnabled = false
        hotspotConfig = null
        showHotspotDetailPage = false
        statusMessage = "热点已关闭"
    }

    fun startHotspotNow() {
        if (!hasHotspotRuntimePermissions()) {
            hotspotPermissionMessage = "检测到热点相关权限不足，请先授权后再开启热点。"
            showHotspotPermissionDialog = true
            return
        }
        val startupConfig = hotspotPreferences.getHotspotConfig()
            ?: HotspotConfig(
                ssid = HotspotManager.generateRandomSSID(),
                password = HotspotManager.generateRandomPassword(),
                band = WifiBand.BAND_2GHZ
            ).also {
                hotspotPreferences.saveHotspotConfig(it.ssid, it.password, it.band)
            }
        hotspotConfig = startupConfig
        val started = HotspotManager.startHotspot(context, startupConfig)
        statusMessage = HotspotManager.getLastStartStatus()
        errorMessage = null
        scope.launch {
            repeat(10) {
                delay(200)
                hotspotEnabled = HotspotManager.isHotspotEnabled(context)
                HotspotManager.getActiveHotspotConfig()?.let { runtimeCfg ->
                    hotspotConfig = runtimeCfg
                }
                statusMessage = HotspotManager.getLastStartStatus()
            }
            if (!hotspotEnabled && !started) {
                val status = HotspotManager.getLastStartStatus()
                if (status.contains("权限不足")) {
                    hotspotPermissionMessage = "$status\n请授予热点权限或前往系统设置开启相关权限。"
                    showHotspotPermissionDialog = true
                } else {
                    errorMessage = "应用内开启热点失败。"
                }
            }
        }
    }

    suspend fun handlePickedFile(uri: Uri) {
        val fileName = getFileName(context, uri)
        val mimeType = getMimeType(context, uri)
        val fileSize = getFileSize(context, uri)
        if (fileSendType == 0) {
            clearLocalSendDraftSelection(clearPersistent = true)
        }
        if (fileSendType == 1) {
            if (fileSize <= 0L) {
                errorMessage = "无法读取文件大小，CIMBAR 模式仅支持小于 20MB 的文件。"
                return
            }
            if (fileSize >= cimbarMaxBytes) {
                errorMessage = "CIMBAR 模式仅支持小于 20MB 的文件，当前：${formatSize(fileSize)}"
                return
            }
            onStopServer()
            clearShareState()
            selectedFileUri = uri
            selectedFileName = fileName
            val fileBytes = readUriBytes(context, uri, cimbarMaxBytes)
            if (fileBytes == null || fileBytes.isEmpty()) {
                errorMessage = "读取文件失败，无法启动 CIMBAR 发送。"
                return
            }
            resetCimbarSender()
            val sender = AirGapSender()
            if (!sender.isReady()) {
                errorMessage = "CIMBAR 编码器初始化失败：${sender.getInitError() ?: "unknown"}"
                return
            }
            val prepared = withContext(Dispatchers.Default) {
                sender.prepare(fileName, fileBytes, cimbarMode, 3)
            }
            if (!prepared) {
                sender.shutdown()
                errorMessage = "CIMBAR 编码准备失败。"
                return
            }
            cimbarSender = sender
            cimbarRunning = true
            statusMessage = "CIMBAR 文件已就绪：$fileName（${formatSize(fileSize)}），模式 $cimbarMode，正在播放动态彩码。"
            errorMessage = null
            return
        }
        if (fileSendType == 2) {
            if (!mimeType.startsWith("image/")) {
                errorMessage = "SSTV 模式仅支持图片文件。"
                return
            }
            val sourceBitmap = withContext(Dispatchers.IO) {
                decodeBitmapFromUri(context, uri)
            }
            if (sourceBitmap == null) {
                errorMessage = "图片读取失败，无法生成 SSTV 音频。"
                return
            }
            onStopServer()
            clearShareState()
            resetCimbarSender()
            resetSstvState()
            selectedFileUri = uri
            selectedFileName = fileName
            val wavFile = withContext(Dispatchers.IO) {
                runCatching {
                    val baseDir = context.externalCacheDir ?: context.cacheDir
                    val outDir = File(baseDir, "sstv").apply { mkdirs() }
                    val outFile = File(outDir, "sstv_martin_m1_${System.currentTimeMillis()}.wav")
                    Robot36SstvEncoder.encodeToWavFile(
                        source = sourceBitmap,
                        output = outFile,
                        scaleMode = sstvScaleMode,
                        qualityPreset = sstvQualityPreset
                    )
                    outFile
                }.getOrNull()
            }
            sourceBitmap.recycle()
            if (wavFile == null || !wavFile.exists()) {
                errorMessage = "SSTV 编码失败。"
                return
            }
            val wavUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", wavFile)
            sstvWavUri = wavUri
            sstvWavName = wavFile.name
            statusMessage = "SSTV 音频已生成（Martin M1，${if (sstvScaleMode == Robot36SstvEncoder.ScaleMode.CROP_CENTER) "填满裁切" else "完整黑边"}，${sstvQualityPreset.displayName}）。点击播放，用另一台设备的 Robot36 接收。"
            errorMessage = null
            return
        }
        if (reversePushEnabled && selectedMode == 1) {
            selectedFileUri = uri
            selectedFileName = fileName
            selectedFileMimeType = mimeType
            reversePendingFiles = listOf(Triple(uri, fileName, mimeType))
            statusMessage = "已选择 1 个文件，点击“开始传输”将推送到接收端。"
            errorMessage = null
            return
        }
        shareAsFileLink(uri, fileName, mimeType)
    }

    suspend fun handlePickedFiles(uris: List<Uri>) {
        val unique = uris.distinct()
        if (unique.isEmpty()) return
        if (unique.size == 1) {
            handlePickedFile(unique.first())
            return
        }
        if (fileSendType != 0) {
            errorMessage = "当前模式不支持多选，请切换到“文件二维码”模式。"
            return
        }
        clearLocalSendDraftSelection(clearPersistent = true)
        if (reversePushEnabled && selectedMode == 1) {
            reversePendingFiles = unique.map { uri ->
                val fileName = getFileName(context, uri)
                val mimeType = getMimeType(context, uri).ifBlank {
                    URLConnection.guessContentTypeFromName(fileName) ?: "*/*"
                }
                Triple(uri, fileName, mimeType)
            }
            selectedFileUri = null
            selectedFileName = "已选择 ${reversePendingFiles.size} 个文件（反向传输）"
            selectedFileMimeType = "application/octet-stream"
            statusMessage = "已选择 ${reversePendingFiles.size} 个文件，点击“开始传输”将推送到接收端。"
            errorMessage = null
            return
        }
        val totalBytes = withContext(Dispatchers.IO) {
            unique.sumOf { uri -> getFileSize(context, uri).coerceAtLeast(0L) }
        }
        pendingMultiFileUris = unique
        pendingMultiFileCount = unique.size
        pendingMultiFileTotalBytes = totalBytes
        showMultiFileShareChoiceSheet = true
        errorMessage = null
    }

    suspend fun shareTextContent(rawText: String, sourceLabel: String) {
        val text = rawText.trim()
        if (text.isBlank()) {
            errorMessage = "文本内容为空。"
            return
        }

        if (reversePushEnabled && selectedMode == 1) {
            val textFileUri = createTempTextUri(context, text)
            if (textFileUri == null) {
                errorMessage = "文本转文件失败。"
                return
            }
            val tempName = "${sourceLabel}_${System.currentTimeMillis()}.txt"
            pushFilesToReverseTarget(
                listOf(
                    Triple(
                        textFileUri,
                        tempName,
                        "text/plain"
                    )
                )
            )
            return
        }

        onStopServer()
        resetCimbarSender()
        resetSstvState()
        selectedFileUri = null

        val payload = TextShareCodec.encode(text)
        val directBitmap = withContext(Dispatchers.Default) {
            QRCodeGenerator.generateQRCode(payload, 512)
        }

        if (directBitmap != null) {
            selectedFileName = "$sourceLabel（文本二维码）"
            serverUrl = null
            qrCodeBitmap = directBitmap
            showQrDetailPage = true
            isCustomTextQrShare = sourceLabel == "自定义文本"
            statusMessage = "文本已生成二维码，扫码后可直接复制。"
            errorMessage = null
            return
        }

        val textFileUri = createTempTextUri(context, text)
        if (textFileUri == null) {
            errorMessage = "文本转文件失败。"
            return
        }
        val tempName = "${sourceLabel}_${System.currentTimeMillis()}.txt"
        shareAsFileLink(
            uri = textFileUri,
            fileName = tempName,
            mimeType = "text/plain"
        )
        isCustomTextQrShare = false
        statusMessage = "文本过长，已转为文件分享。"
    }

    suspend fun shareClipboardContent() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clipData = clipboard?.primaryClip
        if (clipData == null || clipData.itemCount == 0) {
            errorMessage = "剪贴板为空。"
            return
        }

        val item = clipData.getItemAt(0)
        val text = item.text?.toString().orEmpty()
        if (text.isNotBlank()) {
            customText = text
            shareTextContent(text, "剪贴板文本")
            return
        }

        val uri = item.uri
        if (uri != null) {
            shareAsFileLink(
                uri = uri,
                fileName = getFileName(context, uri),
                mimeType = getMimeType(context, uri)
            )
            statusMessage = "剪贴板内容为文件或图片，已走文件分享。"
            return
        }

        val fallback = item.coerceToText(context)?.toString().orEmpty()
        if (fallback.isNotBlank()) {
            customText = fallback
            shareTextContent(fallback, "剪贴板内容")
            return
        }

        errorMessage = "当前剪贴板类型暂不支持。"
    }

    suspend fun buildSelfApkShare(): Triple<Uri, String, Long>? {
        val one = extractCurrentAppApkShare(context) ?: return null
        return Triple(one.uri, one.fileName, one.sizeBytes)
    }

    suspend fun shareSelfApkByQr() {
        onStopServer()
        resetCimbarSender()
        resetSstvState()
        clearShareState()
        fileSendType = 0

        val apkShare = buildSelfApkShare()

        if (apkShare == null) {
            errorMessage = "提取当前应用 APK 失败。"
            return
        }

        val (apkUri, apkName, apkSize) = apkShare
        shareAsFileLink(
            uri = apkUri,
            fileName = apkName,
            mimeType = "application/vnd.android.package-archive"
        )
        statusMessage = "已生成本应用 APK 下载二维码：$apkName（${formatSize(apkSize)}）"
    }

    fun dispatchPendingExternalShare(mode: Int) {
        val pendingUri = pendingExternalShareUri ?: return
        val pendingName = pendingExternalShareFileName.ifBlank { getFileName(context, pendingUri) }
        val pendingMime = pendingExternalShareMimeType.ifBlank {
            getMimeType(context, pendingUri).ifBlank { "*/*" }
        }
        showExternalShareChoiceDialog = false
        pendingExternalShareUri = null
        pendingExternalShareFileName = ""
        pendingExternalShareMimeType = ""
        externalShareAutoModeCountDown = 0
        selectedMode = 0
        fileSendType = 0
        when (mode) {
            2 -> {
                reversePushEnabled = true
                reversePendingFiles = listOf(Triple(pendingUri, pendingName, pendingMime))
                showReversePushConfigSheet = true
            }
            3 -> {
                reversePushEnabled = false
                pendingIpv4EnhancedIncomingUris = listOf(pendingUri)
                showIpv4UdpEnhancedSend = true
            }
            4 -> {
                reversePushEnabled = false
                pendingIpv6EnhancedIncomingUris = listOf(pendingUri)
                showIpv6UdpEnhancedSend = true
            }
            else -> {
                reversePushEnabled = false
                scope.launch {
                    handlePickedFile(pendingUri)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        hasBluetoothPermission = BluetoothTransferManager.hasBluetoothPermissions(context)
    }

    LaunchedEffect(context) {
        while (isActive) {
            hotspotEnabled = withContext(Dispatchers.IO) {
                HotspotManager.isHotspotEnabled(context)
            }
            delay(1000)
        }
    }

    LaunchedEffect(initialFileUri) {
        initialFileUri?.let { uri ->
            selectedMode = 0
            pendingExternalShareUri = uri
            pendingExternalShareFileName = getFileName(context, uri)
            pendingExternalShareMimeType = getMimeType(context, uri)
            externalShareAutoModeCountDown = 10
            showExternalShareChoiceDialog = true
            onInitialFileConsumed()
        }
    }
    LaunchedEffect(showExternalShareChoiceDialog, pendingExternalShareUri) {
        if (!showExternalShareChoiceDialog || pendingExternalShareUri == null) return@LaunchedEffect
        externalShareAutoModeCountDown = 10
        while (showExternalShareChoiceDialog && pendingExternalShareUri != null && externalShareAutoModeCountDown > 0) {
            delay(1000)
            if (!showExternalShareChoiceDialog || pendingExternalShareUri == null) break
            externalShareAutoModeCountDown -= 1
        }
        if (showExternalShareChoiceDialog && pendingExternalShareUri != null && externalShareAutoModeCountDown <= 0) {
            dispatchPendingExternalShare(mode = 1)
        }
    }
    LaunchedEffect(initialSharedText) {
        val incomingText = initialSharedText?.trim().orEmpty()
        if (incomingText.isNotEmpty()) {
            selectedMode = 2
            customText = incomingText
            showQrDetailPage = false
            showClipboardFileShareConfig = false
            errorMessage = null
            statusMessage = "已导入外部分享文本，可直接生成二维码分享。"
            onInitialTextConsumed()
        }
    }
    fun stopShareSession() {
        reverseSendControl?.cancelled?.set(true)
        reverseSendControl = null
        reverseSendProgress = ReversePushSendProgress()
        showReverseSendProgressSheet = false
        onStopServer()
        clearShareState()
        resetCimbarSender()
        resetSstvState()
        statusMessage = null
        errorMessage = null
        showQrExitConfirmDialog = false
    }
    BackHandler(enabled = showQrDetailPage) {
        if (isCustomTextQrShare) {
            stopShareSession()
        } else {
            showQrExitConfirmDialog = true
        }
    }

    if (showExternalShareChoiceDialog && pendingExternalShareUri != null) {
        val pendingName = pendingExternalShareFileName.ifBlank { "未命名文件" }
        AlertDialog(
            onDismissRequest = {
                showExternalShareChoiceDialog = false
                pendingExternalShareUri = null
                pendingExternalShareFileName = ""
                pendingExternalShareMimeType = ""
            },
            title = { Text("外部文件已导入") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("请选择传输方式：")
                    Text(
                        pendingName,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "未操作时将在 ${externalShareAutoModeCountDown}s 后默认选择：普通 IPv4/IPv6 传输。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { dispatchPendingExternalShare(mode = 1) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("1. 普通 IPv4/IPv6 传输")
                    }
                    OutlinedButton(
                        onClick = { dispatchPendingExternalShare(mode = 2) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("2. 反向传输")
                    }
                    OutlinedButton(
                        onClick = { dispatchPendingExternalShare(mode = 3) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("3. IPv4 增强传输")
                    }
                    OutlinedButton(
                        onClick = { dispatchPendingExternalShare(mode = 4) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("4. IPv6 增强传输")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showExternalShareChoiceDialog = false
                        pendingExternalShareUri = null
                        pendingExternalShareFileName = ""
                        pendingExternalShareMimeType = ""
                        externalShareAutoModeCountDown = 0
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }
    InstalledApkPickerSheet(
        visible = showInstalledApkPicker,
        context = context,
        onDismiss = { showInstalledApkPicker = false },
        onConfirm = { selectedApps ->
            showInstalledApkPicker = false
            scope.launch {
                if (selectedApps.isEmpty()) {
                    errorMessage = "未选择应用。"
                    return@launch
                }
                statusMessage = "正在提取 ${selectedApps.size} 个应用 APK..."
                val shares = extractInstalledApkShares(context, selectedApps)
                if (shares.isEmpty()) {
                    errorMessage = "提取应用 APK 失败。"
                    return@launch
                }
                if (reversePushEnabled && selectedMode == 1) {
                    val append = shares.map { Triple(it.uri, it.fileName, it.mimeType) }
                    reversePendingFiles = reversePendingFiles + append
                    selectedFileUri = null
                    selectedFileName = "已选择 ${reversePendingFiles.size} 个文件（反向传输）"
                    selectedFileMimeType = "application/octet-stream"
                    statusMessage = "已加入 ${shares.size} 个应用 APK 到待传输列表。"
                    errorMessage = null
                    showReversePendingSheet = true
                } else {
                    handlePickedFiles(shares.map { it.uri })
                    statusMessage = if (shares.size == 1) {
                        "已提取应用 APK：${shares.first().fileName}"
                    } else {
                        "已提取 ${shares.size} 个应用 APK，可继续选择分享方式。"
                    }
                    errorMessage = null
                }
            }
        }
    )

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                handlePickedFile(it)
                if (reversePushEnabled && selectedMode == 1) {
                    showReversePendingSheet = true
                }
            }
        }
    }

    val multiFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                handlePickedFiles(uris)
                if (reversePushEnabled && selectedMode == 1) {
                    showReversePendingSheet = true
                }
            }
        }
    }

    val localSendFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            selectedMode = 0
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val drafts = collectLocalSendFolderDrafts(treeUri)
            if (drafts.isEmpty()) {
                errorMessage = "所选文件夹为空或暂时无法访问。"
                return@launch
            }
            val rootName = DocumentFile.fromTreeUri(context, treeUri)
                ?.name
                ?.trim()
                .orEmpty()
                .ifBlank { "文件夹" }
            onStopServer()
            resetCimbarSender()
            resetSstvState()
            localSendDraftItems = drafts
            localSendDraftEphemeral = false
            showLocalSendTargetSheet = false
            localSendTargetSheetTitle = "选择 LocalSend 设备"
            selectedFileUri = null
            selectedFileName = "文件夹：$rootName（${drafts.size} 项，仅 LocalSend）"
            selectedFileMimeType = "application/octet-stream"
            currentBatchShareUris = emptyList()
            serverUrl = null
            qrCodeBitmap = null
            showQrDetailPage = false
            isCustomTextQrShare = false
            pendingClipboardFileUri = null
            pendingClipboardFileName = null
            pendingClipboardMimeType = null
            statusMessage = "已选择文件夹“$rootName”，仅支持通过 LocalSend 发送并保留目录结构。"
            errorMessage = null
        }
    }


    LaunchedEffect(cimbarRunning, cimbarSender) {
        val sender = cimbarSender ?: return@LaunchedEffect
        if (!cimbarRunning) return@LaunchedEffect
        while (isActive && cimbarRunning && cimbarSender === sender) {
            val frame = withContext(cimbarFrameDispatcher) { sender.nextFrame() }
            if (frame != null && frame.size > 2) {
                val width = frame[0]
                val height = frame[1]
                if (width > 0 && height > 0 && frame.size >= 2 + width * height) {
                    if (width != cimbarLastFrameW || height != cimbarLastFrameH) {
                        Log.i(
                            "AirGapSenderUI",
                            "frame size changed ${cimbarLastFrameW}x${cimbarLastFrameH} -> ${width}x${height}, mode=$cimbarMode"
                        )
                        cimbarLastFrameW = width
                        cimbarLastFrameH = height
                    }
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(frame, 2, width, 0, 0, width, height)
                    cimbarFrameBitmap = bitmap
                }
            }
            delay(66)
        }
    }

    LaunchedEffect(selectedMode) {
        if (selectedMode != 0) {
            resetCimbarSender()
            resetSstvState()
        }
    }
    LaunchedEffect(allowedFileSendFlags) {
        if (!supportsSendType(fileSendType)) {
            fileSendType = firstSupportedSendType()
            resetCimbarSender()
            resetSstvState()
            clearShareState()
            statusMessage = null
            errorMessage = null
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            cimbarSender?.shutdown()
            cimbarFrameDispatcher.close()
            sstvPlayer?.release()
        }
    }

    DisposableEffect(fileSendType, localView) {
        val previous = localView.keepScreenOn
        localView.keepScreenOn = fileSendType == 1
        onDispose {
            localView.keepScreenOn = previous
        }
    }

    if (showFilePickOptionSheet && fileSendType == 0) {
        val filePickSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showFilePickOptionSheet = false },
            sheetState = filePickSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("选择要分享的内容", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "你可以直接选文件，或从相册快速选择照片/视频后生成二维码。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        showFilePickOptionSheet = false
                        filePickerLauncher.launch("*/*")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("选择文件")
                }
                OutlinedButton(
                    onClick = {
                        showFilePickOptionSheet = false
                        filePickerLauncher.launch("image/*")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("从相册选择照片")
                }
                OutlinedButton(
                    onClick = {
                        showFilePickOptionSheet = false
                        filePickerLauncher.launch("video/*")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("从相册选择视频")
                }
                OutlinedButton(
                    onClick = {
                        showFilePickOptionSheet = false
                        multiFilePickerLauncher.launch("*/*")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("多选文件/照片/视频")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            showFilePickOptionSheet = false
                            scope.launch { shareSelfApkByQr() }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("分享本APP")
                    }
                    OutlinedButton(
                        onClick = {
                            showFilePickOptionSheet = false
                            showInstalledApkPicker = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("分享其他APP")
                    }
                }
                TextButton(
                    onClick = { showFilePickOptionSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消")
                }
            }
        }
    }

    if (showLocalSendTargetSheet) {
        val localSendTargetPeers = neighborPeers
            .filter { it.peerProtocol == NeighborPeerProtocol.LOCALSEND }
            .distinctBy { "${it.ip}:${it.localSendPort}:${it.localSendProtocol}" }
            .sortedWith(compareBy<LanNeighborPeer> { it.name.lowercase() }.thenBy { it.ip })
        val localSendTargetSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val localSendTargetPagerState = rememberPagerState(initialPage = 1) { 2 }
        val localSendItemCount = localSendDraftItems.size.coerceAtLeast(1)
        val localSendTotalBytes = localSendDraftItems.sumOf { draft ->
            (draft.inlineBytes?.size?.toLong() ?: draft.size).coerceAtLeast(0L)
        }
        val localSendFirstDraft = localSendDraftItems.firstOrNull()
        val localSendSummaryLabel = when {
            localSendDraftItems.isEmpty() -> "当前没有可发送内容"
            localSendDraftItems.any { it.inlineBytes != null } -> {
                if (localSendFirstDraft?.fileName?.startsWith("clipboard_", ignoreCase = true) == true) {
                    "剪贴板消息"
                } else {
                    "文本消息"
                }
            }
            localSendDraftItems.any { it.fileName.contains('/') } -> "文件夹内容"
            localSendFirstDraft?.fileName?.startsWith("clipboard_", ignoreCase = true) == true -> "剪贴板文件"
            localSendItemCount > 1 -> "多文件"
            else -> "文件"
        }
        LaunchedEffect(showLocalSendTargetSheet, localSendTargetPagerState.currentPage) {
            if (!showLocalSendTargetSheet || localSendTargetPagerState.currentPage != 1) return@LaunchedEffect
            refreshNeighborHistory()
            if (!neighborInfiniteScan) {
                neighborInfiniteScan = true
            }
            requestScanNeighbors()
        }
        LaunchedEffect(showLocalSendTargetSheet, neighborInfiniteScan, localSendTargetPagerState.currentPage) {
            if (!showLocalSendTargetSheet) return@LaunchedEffect
            if (!neighborInfiniteScan || localSendTargetPagerState.currentPage != 1) return@LaunchedEffect
            while (showLocalSendTargetSheet && neighborInfiniteScan && localSendTargetPagerState.currentPage == 1) {
                scanNeighbors()
                delay(1200)
            }
        }
        ModalBottomSheet(
            onDismissRequest = {
                if (localSendDraftEphemeral) {
                    clearLocalSendDraftSelection(clearPersistent = true)
                } else {
                    clearLocalSendDraftSelection(clearPersistent = false)
                }
            },
            sheetState = localSendTargetSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(localSendTargetSheetTitle, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                HorizontalPager(
                    state = localSendTargetPagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) { page ->
                    if (page == 0) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "当前内容会按 LocalSend 协议发送。文本与剪贴板会在兼容终端中直接按消息处理。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(localSendSummaryLabel, fontWeight = FontWeight.SemiBold)
                                    Text("项目数：$localSendItemCount", fontSize = 12.sp)
                                    Text("总大小：${formatSize(localSendTotalBytes)}", fontSize = 12.sp)
                                    localSendFirstDraft?.let { draft ->
                                        Text(
                                            "首项：${draft.fileName}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        draft.previewText?.takeIf { it.isNotBlank() }?.let { preview ->
                                            Text(
                                                preview.take(180),
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                            Button(
                                onClick = {
                                    scope.launch { localSendTargetPagerState.animateScrollToPage(1) }
                                },
                                enabled = localSendDraftItems.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("进入邻居列表")
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "这里复用邻居发现流程。进入 LocalSend 入口后默认停在这一页，可直接刷新并选择设备。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (neighborInfiniteScan) {
                                            neighborInfiniteScan = false
                                            neighborScanJob?.cancel()
                                        } else {
                                            neighborInfiniteScan = true
                                            requestScanNeighbors()
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                ) {
                                    Text(if (neighborInfiniteScan) "停止刷新" else "开始刷新")
                                }
                                OutlinedButton(
                                    onClick = { showManualNeighborIpDialog = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                ) {
                                    Text("手动输入IP")
                                }
                            }
                            if (neighborInfiniteScan) {
                                Text(
                                    "无限探测中",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                            }
                            if (manualNeighborIp.isNotBlank()) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "手动目标：$manualNeighborIp",
                                            fontSize = 12.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedButton(
                                            onClick = { requestScanNeighbors(targetIp = manualNeighborIp) }
                                        ) { Text("探测") }
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    val targetIp = manualNeighborIp.trim()
                                                    val peer = localSendTargetPeers.firstOrNull {
                                                        it.ip.equals(targetIp, ignoreCase = true)
                                                    } ?: withContext(Dispatchers.IO) {
                                                        LocalSendRuntimeBridge.probePeer(targetIp)
                                                    }
                                                    if (peer == null) {
                                                        errorMessage = "该地址上未发现 LocalSend 设备。"
                                                        return@launch
                                                    }
                                                    cachedLocalSendPeers.removeAll {
                                                        it.ip.equals(peer.ip, ignoreCase = true)
                                                    }
                                                    cachedLocalSendPeers.add(peer)
                                                    applyMergedNeighborPeers()
                                                    sendByLocalSendPeer(peer)
                                                }
                                            },
                                            enabled = manualNeighborIp.isNotBlank() && localSendDraftItems.isNotEmpty()
                                        ) { Text("发送") }
                                    }
                                }
                            }
                            Text("在线 LocalSend 设备（${localSendTargetPeers.size}）", fontWeight = FontWeight.SemiBold)
                            if (localSendTargetPeers.isEmpty()) {
                                Text(
                                    "暂未发现 LocalSend 邻居。可以继续等待广播，也可以手动输入 IP 探测。",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            localSendTargetPeers.forEach { peer ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(peer.name.ifBlank { peer.ip }, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${peer.ip}:${peer.localSendPort}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Button(
                                            onClick = {
                                                scope.launch { sendByLocalSendPeer(peer) }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = localSendDraftItems.isNotEmpty()
                                        ) {
                                            Text("发送到这台 LocalSend 设备")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(2) { index ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .size(if (localSendTargetPagerState.currentPage == index) 10.dp else 8.dp)
                                .background(
                                    if (localSendTargetPagerState.currentPage == index) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                    },
                                    shape = RoundedCornerShape(50)
                                )
                                .clickable {
                                    scope.launch { localSendTargetPagerState.animateScrollToPage(index) }
                                }
                        )
                    }
                }
                TextButton(
                    onClick = {
                        if (localSendDraftEphemeral) {
                            clearLocalSendDraftSelection(clearPersistent = true)
                        } else {
                            clearLocalSendDraftSelection(clearPersistent = false)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消")
                }
            }
        }
    }

    if (showClipboardActionSheet) {
        val (clipboardLabel, clipboardPreview) = describeClipboardContent()
        val clipboardReady = clipboardLabel != "剪贴板为空" && clipboardLabel != "暂不支持的剪贴板内容"
        val clipboardActionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val clipboardLocalSendPeers = neighborPeers
            .filter { it.peerProtocol == NeighborPeerProtocol.LOCALSEND }
            .distinctBy { "${it.ip}:${it.localSendPort}:${it.localSendProtocol}" }
            .sortedWith(compareBy<LanNeighborPeer> { it.name.lowercase() }.thenBy { it.ip })
        LaunchedEffect(showClipboardActionSheet) {
            if (!showClipboardActionSheet) return@LaunchedEffect
            refreshNeighborHistory()
            if (!neighborInfiniteScan) {
                neighborInfiniteScan = true
            }
            requestScanNeighbors()
        }
        LaunchedEffect(showClipboardActionSheet, neighborInfiniteScan) {
            if (!showClipboardActionSheet) return@LaunchedEffect
            if (!neighborInfiniteScan) return@LaunchedEffect
            while (showClipboardActionSheet && neighborInfiniteScan) {
                scanNeighbors()
                delay(1200)
            }
        }
        ModalBottomSheet(
            onDismissRequest = { showClipboardActionSheet = false },
            sheetState = clipboardActionSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("发送到 LocalSend", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = if (clipboardReady) clipboardPreview else "",
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp),
                    minLines = 4,
                    maxLines = 8,
                    label = { Text(clipboardLabel) },
                    placeholder = {
                        Text(
                            if (clipboardLabel == "剪贴板为空") {
                                "当前没有可发送的剪贴板内容"
                            } else {
                                "当前剪贴板类型暂不支持"
                            }
                        )
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (neighborInfiniteScan) {
                                neighborInfiniteScan = false
                                neighborScanJob?.cancel()
                            } else {
                                neighborInfiniteScan = true
                                requestScanNeighbors()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text(if (neighborInfiniteScan) "停止刷新" else "开始刷新")
                    }
                    OutlinedButton(
                        onClick = { showManualNeighborIpDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("手动输入IP")
                    }
                }
                if (neighborInfiniteScan) {
                    Text(
                        "无限探测中",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
                if (manualNeighborIp.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "手动目标：$manualNeighborIp",
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = { requestScanNeighbors(targetIp = manualNeighborIp) }
                            ) { Text("探测") }
                            Button(
                                onClick = {
                                    scope.launch {
                                        val targetIp = manualNeighborIp.trim()
                                        val peer = clipboardLocalSendPeers.firstOrNull {
                                            it.ip.equals(targetIp, ignoreCase = true)
                                        } ?: withContext(Dispatchers.IO) {
                                            LocalSendRuntimeBridge.probePeer(targetIp)
                                        }
                                        if (peer == null) {
                                            errorMessage = "该地址上未发现 LocalSend 设备。"
                                            return@launch
                                        }
                                        cachedLocalSendPeers.removeAll {
                                            it.ip.equals(peer.ip, ignoreCase = true)
                                        }
                                        cachedLocalSendPeers.add(peer)
                                        applyMergedNeighborPeers()
                                        sendClipboardToLocalSendPeer(peer)
                                    }
                                },
                                enabled = clipboardReady && manualNeighborIp.isNotBlank()
                            ) { Text("发送") }
                        }
                    }
                }
                Text("在线 LocalSend 设备（${clipboardLocalSendPeers.size}）", fontWeight = FontWeight.SemiBold)
                if (clipboardLocalSendPeers.isEmpty()) {
                    Text(
                        "暂无在线 LocalSend 设备。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    clipboardLocalSendPeers.forEach { peer ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(peer.name.ifBlank { peer.ip }, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${peer.ip}:${peer.localSendPort}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        scope.launch { sendClipboardToLocalSendPeer(peer) }
                                    },
                                    enabled = clipboardReady,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("发送到这台设备")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTextActionSheet) {
        val trimmedText = customText.trim()
        val textReady = trimmedText.isNotBlank()
        val textActionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val textLocalSendPeers = neighborPeers
            .filter { it.peerProtocol == NeighborPeerProtocol.LOCALSEND }
            .distinctBy { "${it.ip}:${it.localSendPort}:${it.localSendProtocol}" }
            .sortedWith(compareBy<LanNeighborPeer> { it.name.lowercase() }.thenBy { it.ip })
        LaunchedEffect(showTextActionSheet) {
            if (!showTextActionSheet) return@LaunchedEffect
            refreshNeighborHistory()
            if (!neighborInfiniteScan) {
                neighborInfiniteScan = true
            }
            requestScanNeighbors()
        }
        LaunchedEffect(showTextActionSheet, neighborInfiniteScan) {
            if (!showTextActionSheet) return@LaunchedEffect
            if (!neighborInfiniteScan) return@LaunchedEffect
            while (showTextActionSheet && neighborInfiniteScan) {
                scanNeighbors()
                delay(1200)
            }
        }
        ModalBottomSheet(
            onDismissRequest = { showTextActionSheet = false },
            sheetState = textActionSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("发送到 LocalSend", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = trimmedText,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 240.dp),
                    minLines = 5,
                    maxLines = 10,
                    label = { Text("文本内容") },
                    placeholder = { Text("请先输入要发送的文本") }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (neighborInfiniteScan) {
                                neighborInfiniteScan = false
                                neighborScanJob?.cancel()
                            } else {
                                neighborInfiniteScan = true
                                requestScanNeighbors()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text(if (neighborInfiniteScan) "停止刷新" else "开始刷新")
                    }
                    OutlinedButton(
                        onClick = { showManualNeighborIpDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("手动输入IP")
                    }
                }
                if (neighborInfiniteScan) {
                    Text(
                        "无限探测中",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
                if (manualNeighborIp.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "手动目标：$manualNeighborIp",
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = { requestScanNeighbors(targetIp = manualNeighborIp) }
                            ) { Text("探测") }
                            Button(
                                onClick = {
                                    scope.launch {
                                        val targetIp = manualNeighborIp.trim()
                                        val peer = textLocalSendPeers.firstOrNull {
                                            it.ip.equals(targetIp, ignoreCase = true)
                                        } ?: withContext(Dispatchers.IO) {
                                            LocalSendRuntimeBridge.probePeer(targetIp)
                                        }
                                        if (peer == null) {
                                            errorMessage = "该地址上未发现 LocalSend 设备。"
                                            return@launch
                                        }
                                        cachedLocalSendPeers.removeAll {
                                            it.ip.equals(peer.ip, ignoreCase = true)
                                        }
                                        cachedLocalSendPeers.add(peer)
                                        applyMergedNeighborPeers()
                                        sendTextToLocalSendPeer(peer)
                                    }
                                },
                                enabled = textReady && manualNeighborIp.isNotBlank()
                            ) { Text("发送") }
                        }
                    }
                }
                Text("在线 LocalSend 设备（${textLocalSendPeers.size}）", fontWeight = FontWeight.SemiBold)
                if (textLocalSendPeers.isEmpty()) {
                    Text(
                        "暂无在线 LocalSend 设备。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    textLocalSendPeers.forEach { peer ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(peer.name.ifBlank { peer.ip }, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${peer.ip}:${peer.localSendPort}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        scope.launch { sendTextToLocalSendPeer(peer) }
                                    },
                                    enabled = textReady,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("发送到这台设备")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showReversePushScanner) {
        QRCodeScanner(
            onQRCodeScanned = { qrContent ->
                showReversePushScanner = false
                val parsed = parseReversePushAddressFromQr(qrContent)
                if (parsed.isNullOrBlank()) {
                    errorMessage = "未识别到有效反向传输地址。"
                } else {
                    reversePushAddress = parsed
                    showReversePushConfigSheet = false
                    showReversePendingSheet = true
                    errorMessage = null
                    statusMessage = "已识别接收端上传地址。"
                }
            },
            onDismiss = { showReversePushScanner = false }
        )
    }

    if (showMultiFileShareChoiceSheet && pendingMultiFileUris.isNotEmpty()) {
        val multiShareSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showMultiFileShareChoiceSheet = false },
            sheetState = multiShareSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("多文件分享方式", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("共 $pendingMultiFileCount 个文件，总大小 ${formatSize(pendingMultiFileTotalBytes)}")
                Text(
                    "请选择“压缩包”或“一起下载”。一起下载会生成包含多个地址的二维码，接收端可并发下载。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "提示：并发数量在接收端设置，默认 3；当前多地址二维码最多支持 $MAX_BATCH_QR_FILES 个文件。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Button(
                    onClick = {
                        val uris = pendingMultiFileUris
                        showMultiFileShareChoiceSheet = false
                        pendingMultiFileUris = emptyList()
                        pendingMultiFileTotalBytes = 0L
                        pendingMultiFileCount = 0
                        scope.launch {
                            shareMultipleAsBatchDownload(uris)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("一起下载（多地址二维码）")
                }
                OutlinedButton(
                    onClick = {
                        val uris = pendingMultiFileUris
                        showMultiFileShareChoiceSheet = false
                        pendingMultiFileUris = emptyList()
                        pendingMultiFileTotalBytes = 0L
                        pendingMultiFileCount = 0
                        scope.launch {
                            val zip = createZipFromUris(uris)
                            if (zip == null) {
                                errorMessage = "压缩失败，请重试。"
                            } else {
                                val (zipUri, zipName, zipSize) = zip
                                shareAsFileLink(zipUri, zipName, "application/zip")
                                statusMessage = "已打包压缩并生成二维码：$zipName（${formatSize(zipSize)}）"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("压缩为 ZIP 再分享")
                }
                TextButton(
                    onClick = {
                        showMultiFileShareChoiceSheet = false
                        pendingMultiFileUris = emptyList()
                        pendingMultiFileTotalBytes = 0L
                        pendingMultiFileCount = 0
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消")
                }
            }
        }
    }

    StunProbeProgressDialog(
        visible = showMainShareStunProbing,
        checked = mainShareStunProbeChecked,
        total = mainShareStunProbeTotal,
        successCount = mainShareStunProbeSuccessCount,
        currentServer = mainShareStunProbeCurrentServer,
        onFinishNow = {
            if (mainShareStunProbeSuccessCount > 0) {
                mainShareStunProbeControl?.requestStopEarly?.set(true)
            }
        },
        onDisableNatAndContinue = {
            mainShareStunProbeControl?.requestDisableNat?.set(true)
        }
    )

    StunEndpointPickerDialog(
        visible = showMainShareStunPicker,
        title = "选择用于二维码的 STUN 地址",
        result = pendingMainShareRequest?.probe?.natBatch,
        selected = selectedMainShareStun,
        onSelect = { chosen ->
            selectedMainShareStun = chosen
            val pending = pendingMainShareRequest
            showMainShareStunPicker = false
            pendingMainShareRequest = null
            if (pending == null) return@StunEndpointPickerDialog
            scope.launch {
                if (pending.isSingle) {
                    val uri = pending.singleUri ?: return@launch
                    val name = pending.singleName ?: return@launch
                    val mime = pending.singleMime ?: return@launch
                    shareAsFileLink(
                        uri = uri,
                        fileName = name,
                        mimeType = mime,
                        skipStunPicker = true,
                        selectedStun = chosen,
                        preProbed = pending.probe
                    )
                } else if (pending.batchUris.isNotEmpty()) {
                    shareMultipleAsBatchDownload(
                        uris = pending.batchUris,
                        skipStunPicker = true,
                        selectedStun = chosen,
                        preProbed = pending.probe
                    )
                }
            }
        },
        onDismiss = {
            showMainShareStunPicker = false
            pendingMainShareRequest = null
            selectedMainShareStun = null
            statusMessage = "已取消 STUN 地址选择，未生成二维码。"
        }
    )

    if (showClipboardFileShareConfig && pendingClipboardFileUri != null) {
        val clipboardConfigSheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden }
        )
        ModalBottomSheet(
            onDismissRequest = { showClipboardFileShareConfig = false },
            sheetState = clipboardConfigSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("文件分享配置", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "剪贴板内容过长，已转为文件。请选择网络模式并生成二维码。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                pendingClipboardFileName?.let {
                    Text("文件：$it", fontWeight = FontWeight.SemiBold)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HighlightToggleButton(
                        label = "IPv4 局域网",
                        selected = networkShareMode == 0,
                        onClick = { networkShareMode = 0 },
                        modifier = Modifier.weight(1f)
                    )
                    HighlightToggleButton(
                        label = "IPv6 公网",
                        selected = networkShareMode == 1,
                        onClick = { networkShareMode = 1 },
                        modifier = Modifier.weight(1f)
                        )
                    }
                if (networkShareMode == 1) {
                    Text(
                        text = "说明：两台手机位于同一基站时，可能受客户端隔离影响导致不通；部分省份/运营商默认禁止入站或存在防火墙策略，也会导致 IPv6 不通。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "当前端口：$manualPortInput · ${if (shareRandomPortEnabled) "随机" else "固定"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用NAT", fontWeight = FontWeight.SemiBold)
                        Text(
                            "通过 STUN 探测公网映射端口（实验）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enableNatAssist,
                        onCheckedChange = { enableNatAssist = it },
                        enabled = true
                    )
                }
                if (enableNatAssist) {
                    Text(
                        text = "已启用 NAT（支持 IPv4/IPv6，基于 STUN 映射公网地址与端口）。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("反向传输（PUSH）", fontWeight = FontWeight.SemiBold)
                        Text(
                            "适用于发送端不允许入站、接收端允许入站的场景",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = reversePushEnabled,
                        onCheckedChange = {
                            reversePushEnabled = it
                            if (it) {
                                showReversePushConfigSheet = true
                            } else {
                                showReversePendingSheet = false
                            }
                        }
                    )
                }
                OutlinedButton(
                    onClick = { showReversePushConfigSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = reversePushEnabled
                ) {
                    Text("反向传输参数")
                }
                if (reversePushEnabled && reversePushAddress.isNotBlank()) {
                    Text(
                        text = "当前上传地址：$reversePushAddress",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = {
                        val uri = pendingClipboardFileUri ?: return@Button
                        val name = pendingClipboardFileName ?: "clipboard_${System.currentTimeMillis()}.txt"
                        val mime = pendingClipboardMimeType ?: "text/plain"
                        showClipboardFileShareConfig = false
                        scope.launch { shareAsFileLink(uri, name, mime) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("生成二维码")
                }
                OutlinedButton(
                    onClick = {
                        showClipboardFileShareConfig = false
                        pendingClipboardFileUri = null
                        pendingClipboardFileName = null
                        pendingClipboardMimeType = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消本次分享")
                }
            }
        }
    }

    if (showReversePushConfigSheet) {
        val reverseConfigSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showReversePushConfigSheet = false },
            sheetState = reverseConfigSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("反向传输参数", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "可手动编辑或扫码识别接收端地址（例如 filetranpush://host:18080）。扫码成功后会自动完成。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = reversePushAddress,
                    onValueChange = { reversePushAddress = it.trim() },
                    label = { Text("接收端上传地址") },
                    placeholder = { Text("filetranpush://host:port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(
                    onClick = { showReversePushScanner = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("扫码识别")
                }
                TextButton(
                    onClick = {
                        reversePushAddress = normalizeReversePushAddress(reversePushAddress).orEmpty()
                        showReversePushConfigSheet = false
                        showReversePendingSheet = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("完成")
                }
            }
        }
    }

    if (showReversePendingSheet && reversePushEnabled) {
        val reversePendingSheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true
        )
        ModalBottomSheet(
            onDismissRequest = { showReversePendingSheet = false },
            sheetState = reversePendingSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.86f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("反向传输内容", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Button(
                    onClick = {
                        showFilePickOptionSheet = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = reversePushReady
                ) {
                    Text("选择文件/照片/视频")
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val apkShare = buildSelfApkShare()
                            if (apkShare == null) {
                                errorMessage = "提取当前应用 APK 失败。"
                            } else {
                                val (apkUri, apkName, apkSize) = apkShare
                                reversePendingFiles = reversePendingFiles + Triple(
                                    apkUri,
                                    apkName,
                                    "application/vnd.android.package-archive"
                                )
                                statusMessage = "已加入待传输：$apkName（${formatSize(apkSize)}）"
                                errorMessage = null
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = reversePushReady
                    ) {
                    Text("提取并加入本应用 APK")
                }
                OutlinedButton(
                    onClick = { showInstalledApkPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = reversePushReady
                ) {
                    Text("提取并加入其他应用 APK（可多选）")
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("传输参数", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (reversePushReady) {
                                "上传地址：${normalizeReversePushAddress(reversePushAddress).orEmpty()}"
                            } else {
                                "上传地址未完成，请先设置反向传输参数。"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "待传输列表（${reversePendingFiles.size}）",
                    fontWeight = FontWeight.SemiBold
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (reversePendingFiles.isEmpty()) {
                        Text(
                            "暂未选择文件，请先点击“选择文件并生成二维码”添加待传输内容。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        reversePendingFiles.forEachIndexed { index, (_, name, _) ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "${index + 1}. $name",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        showReversePendingSheet = false
                        showReverseSendProgressSheet = true
                        scope.launch {
                            runCatching { startReversePendingTransfer() }
                                .onFailure { e ->
                                    errorMessage = "反向传输启动失败：${e.message ?: "unknown"}"
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = reversePushReady && reversePendingFiles.isNotEmpty()
                ) {
                    Text("开始传输")
                }
                OutlinedButton(
                    onClick = {
                        showReversePendingSheet = false
                        showReversePushConfigSheet = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("修改传输参数")
                }
                TextButton(
                    onClick = { showReversePendingSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消")
                }
            }
        }
    }

    if (showReverseSendProgressSheet && (reverseSendProgress.running || reverseSendProgress.totalFiles > 0)) {
        val reverseSendSheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { target ->
                if (target == SheetValue.Hidden) !reverseSendProgress.running else true
            }
        )
        ModalBottomSheet(
            onDismissRequest = {
                if (!reverseSendProgress.running) {
                    showReverseSendProgressSheet = false
                }
            },
            sheetState = reverseSendSheetState
        ) {
            val currentTotal = reverseSendProgress.totalBytes.coerceAtLeast(0L)
            val currentSent = reverseSendProgress.sentBytes.coerceAtLeast(0L).coerceAtMost(currentTotal.takeIf { it > 0L } ?: Long.MAX_VALUE)
            val progress = if (currentTotal > 0L) {
                (currentSent.toFloat() / currentTotal.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("反向传输进度", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (reverseSendProgress.running) "发送中..." else "已停止",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (reverseSendProgress.fileName.isNotBlank()) {
                    Text(
                        "当前文件：${reverseSendProgress.fileName}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (reverseSendProgress.totalFiles > 0) {
                    Text(
                        "文件进度：${reverseSendProgress.fileIndex.coerceAtLeast(0)}/${reverseSendProgress.totalFiles}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${(progress * 100f).toInt()}%", fontSize = 12.sp)
                    Text(
                        if (currentTotal > 0L) "${formatSize(currentSent)} / ${formatSize(currentTotal)}" else "-- / --",
                        fontSize = 12.sp
                    )
                }
                reverseSendProgress.message?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = {
                        reverseSendControl?.cancelled?.set(true)
                        statusMessage = "正在取消反向传输..."
                        showReverseSendProgressSheet = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = reverseSendProgress.running
                ) {
                    Text("取消传输")
                }
                if (!reverseSendProgress.running) {
                    OutlinedButton(
                        onClick = { showReverseSendProgressSheet = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshNeighborHistory()
    }

    if (showQrDetailPage && qrCodeBitmap != null) {
        val qrSheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden }
        )
        ModalBottomSheet(
            onDismissRequest = {
                if (isCustomTextQrShare) {
                    stopShareSession()
                } else {
                    showQrExitConfirmDialog = true
                }
            },
            sheetState = qrSheetState
        ) {
            val neighborPageEnabled = selectedMode == 0 &&
                fileSendType == 0 &&
                networkShareMode == 0 &&
                !reversePushEnabled &&
                !isCustomTextQrShare
            val pageCount = if (neighborPageEnabled) 2 else 1
            val initialPage = remember(neighborPageEnabled) {
                if (neighborPageEnabled) {
                    NeighborDiscoverySettingsStore.get(context).qrDefaultPage.coerceIn(0, 1)
                } else {
                    0
                }
            }
            val qrDetailPagerState = rememberPagerState(initialPage = initialPage) { pageCount }
            val neighborSettings = NeighborDiscoverySettingsStore.get(context)
            LaunchedEffect(neighborPageEnabled, qrDetailPagerState.currentPage) {
                if (!neighborPageEnabled) {
                    if (qrDetailPagerState.currentPage != 0) {
                        qrDetailPagerState.scrollToPage(0)
                    }
                    return@LaunchedEffect
                }
                NeighborDiscoverySettingsStore.setQrDefaultPage(context, qrDetailPagerState.currentPage)
                if (qrDetailPagerState.currentPage == 1) {
                    refreshNeighborHistory()
                    if (!neighborInfiniteScan) {
                        neighborInfiniteScan = true
                    }
                    requestScanNeighbors()
                }
            }
            LaunchedEffect(neighborInfiniteScan, qrDetailPagerState.currentPage, neighborPageEnabled) {
                if (!neighborPageEnabled) return@LaunchedEffect
                if (!neighborInfiniteScan || qrDetailPagerState.currentPage != 1) return@LaunchedEffect
                while (neighborInfiniteScan && qrDetailPagerState.currentPage == 1) {
                    scanNeighbors()
                    delay(1200)
                }
            }
            LaunchedEffect(neighborPageEnabled, qrDetailPagerState.currentPage, localSendV2Enabled) {
                if (!neighborPageEnabled || qrDetailPagerState.currentPage != 1) return@LaunchedEffect
                if (!localSendV2Enabled) {
                    cachedLocalSendPeers.clear()
                    applyMergedNeighborPeers()
                    return@LaunchedEffect
                }
                var tick = 0
                while (isActive && neighborPageEnabled && qrDetailPagerState.currentPage == 1) {
                    if (tick % 4 == 0) {
                        LocalSendRuntimeBridge.announceNow()
                    }
                    cachedLocalSendPeers.clear()
                    cachedLocalSendPeers.addAll(LocalSendRuntimeBridge.snapshotPeers())
                    applyMergedNeighborPeers()
                    tick += 1
                    delay(700)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    if (neighborPageEnabled && qrDetailPagerState.currentPage == 1) "邻居发现" else "二维码分享",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                HorizontalPager(
                    state = qrDetailPagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    userScrollEnabled = neighborPageEnabled
                ) { detailPage ->
                    if (detailPage == 0) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            selectedFileName?.let {
                                Text("当前内容：$it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            serverUrl?.let { url ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            pendingCopyUrl = url
                                            showCopyUrlConfirmDialog = true
                                        },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text("下载地址", fontWeight = FontWeight.SemiBold)
                                        Text(url, fontSize = 12.sp)
                                    }
                                }
                            }
                            if (serverUrl != null && ((selectedFileUri != null && !selectedFileMimeType.isNullOrBlank()) || currentBatchShareUris.isNotEmpty())) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    HighlightToggleButton(
                                        label = "IPv4",
                                        selected = networkShareMode == 0,
                                        onClick = {
                                            if (networkShareMode != 0) {
                                                networkShareMode = 0
                                                scope.launch { refreshCurrentFileShareQr() }
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    HighlightToggleButton(
                                        label = "IPv6",
                                        selected = networkShareMode == 1,
                                        onClick = {
                                            if (networkShareMode != 1) {
                                                networkShareMode = 1
                                                scope.launch { refreshCurrentFileShareQr() }
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedButton(
                                        onClick = { scope.launch { refreshCurrentFileShareQr() } },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("刷新")
                                    }
                                }
                            }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.White)
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = qrCodeBitmap!!.asImageBitmap(),
                                        contentDescription = "share_qrcode_detail",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val shareUri = if (fileSendType == 2) sstvWavUri else selectedFileUri
                                        val shareType = if (fileSendType == 2) {
                                            "audio/wav"
                                        } else {
                                            shareUri?.let { getMimeType(context, it) } ?: "*/*"
                                        }
                                        shareUri?.let { uri ->
                                            val chooser = Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).apply {
                                                    type = shareType
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                },
                                                "其他方式发送"
                                            ).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            runCatching { context.startActivity(chooser) }
                                                .onFailure { err ->
                                                    errorMessage = "调用系统分享失败：${err.message ?: "unknown"}"
                                                }
                                        } ?: serverUrl?.let { url ->
                                            val chooser = Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, url)
                                                },
                                                "分享下载地址"
                                            ).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            runCatching { context.startActivity(chooser) }
                                                .onFailure { err ->
                                                    errorMessage = "调用系统分享失败：${err.message ?: "unknown"}"
                                                }
                                        } ?: run {
                                            errorMessage = "当前没有可发送的文件。"
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("其他方式发送")
                                }
                                Button(
                                    onClick = { stopShareSession() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("停止分享")
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (neighborInfiniteScan) {
                                            neighborInfiniteScan = false
                                            neighborScanJob?.cancel()
                                        } else {
                                            neighborInfiniteScan = true
                                            requestScanNeighbors()
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp),
                                    colors = if (neighborInfiniteScan) {
                                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    } else {
                                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                    }
                                ) {
                                    Text(if (neighborInfiniteScan) "停止刷新" else "开始刷新")
                                }
                                OutlinedButton(
                                    onClick = {
                                        showManualNeighborIpDialog = true
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                ) {
                                    Text("手动输入IP")
                                }
                            }
                            if (neighborInfiniteScan) {
                                Text(
                                    "无限探测中",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                            }
                            if (manualNeighborIp.isNotBlank()) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "手动目标：$manualNeighborIp",
                                            fontSize = 12.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedButton(
                                            onClick = { requestScanNeighbors(targetIp = manualNeighborIp) }
                                        ) { Text("探测") }
                                        Button(
                                            onClick = { scope.launch { sendQuickInviteSmart(manualNeighborIp) } },
                                            enabled = serverUrl != null || selectedFileUri != null || currentBatchShareUris.isNotEmpty()
                                        ) { Text("发送") }
                                        OutlinedButton(
                                            onClick = { scope.launch { sendQuickInviteSmart(manualNeighborIp, forceReverse = true) } },
                                            enabled = serverUrl != null || selectedFileUri != null || currentBatchShareUris.isNotEmpty()
                                        ) { Text("反向") }
                                    }
                                }
                            }
                            Text("在线邻居（${neighborPeers.size}）", fontWeight = FontWeight.SemiBold)
                            if (neighborPeers.isEmpty()) {
                                Text("暂无邻居，先点“刷新邻居”或手动输入 IP。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            neighborPeers.forEach { peer ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(peer.name, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (peer.peerProtocol == NeighborPeerProtocol.LOCALSEND) "协议：LocalSend v2" else "协议：FileTran",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text("${peer.ip}${if (peer.hostName.isBlank()) "" else " · ${peer.hostName}"}", fontSize = 12.sp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = { manualNeighborIp = peer.ip },
                                                modifier = Modifier.weight(1f)
                                            ) { Text("填入IP") }
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        if (peer.peerProtocol == NeighborPeerProtocol.LOCALSEND) {
                                                            sendByLocalSendPeer(peer)
                                                        } else {
                                                            sendQuickInviteToIp(peer.ip, forceReverse = false)
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.weight(1f),
                                                enabled = if (peer.peerProtocol == NeighborPeerProtocol.LOCALSEND) {
                                                    selectedFileUri != null || currentBatchShareUris.isNotEmpty()
                                                } else {
                                                    serverUrl != null
                                                }
                                            ) {
                                                Text(if (peer.peerProtocol == NeighborPeerProtocol.LOCALSEND) "LocalSend发送" else "FileTran发送")
                                            }
                                        }
                                    }
                                }
                            }
                            Text("历史目标（${neighborHistory.size}）", fontWeight = FontWeight.SemiBold)
                            if (neighborHistory.isEmpty()) {
                                Text("暂无历史记录。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            neighborHistory.forEach { item ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(item.name.ifBlank { "FileTran 邻居" }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                            Text("${item.ip}${if (item.hostName.isBlank()) "" else " · ${item.hostName}"}", fontSize = 12.sp)
                                        }
                                        OutlinedButton(
                                            onClick = { scope.launch { sendQuickInviteSmart(item.ip) } },
                                            enabled = serverUrl != null || selectedFileUri != null || currentBatchShareUris.isNotEmpty()
                                        ) { Text("发送") }
                                        OutlinedButton(
                                            onClick = { scope.launch { sendQuickInviteSmart(item.ip, forceReverse = true) } },
                                            enabled = serverUrl != null || selectedFileUri != null || currentBatchShareUris.isNotEmpty()
                                        ) { Text("反向") }
                                        TextButton(
                                            onClick = {
                                                NeighborHistoryStore.delete(context, item.ip)
                                                refreshNeighborHistory()
                                            }
                                        ) { Text("删除") }
                                    }
                                }
                            }
                        }
                    }
                }
                if (neighborPageEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(2) { index ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                                    .size(if (qrDetailPagerState.currentPage == index) 10.dp else 8.dp)
                                    .background(
                                        if (qrDetailPagerState.currentPage == index) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                        },
                                        shape = RoundedCornerShape(50)
                                    )
                                    .clickable {
                                        scope.launch { qrDetailPagerState.animateScrollToPage(index) }
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
    if (showManualNeighborIpDialog) {
        var manualInputDraft by remember(manualNeighborIp) { mutableStateOf(manualNeighborIp) }
        AlertDialog(
            onDismissRequest = { showManualNeighborIpDialog = false },
            title = { Text("手动输入目标IP") },
            text = {
                OutlinedTextField(
                    value = manualInputDraft,
                    onValueChange = { manualInputDraft = it.trim() },
                    label = { Text("目标 IPv4") },
                    placeholder = {
                        Text(
                            buildLocalIpv4Prefix(context).ifBlank { "例如 192.168.1.88" } + if (buildLocalIpv4Prefix(context).isBlank()) "" else "88"
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        manualNeighborIp = manualInputDraft
                        showManualNeighborIpDialog = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        manualNeighborIp = ""
                        showManualNeighborIpDialog = false
                    }
                ) { Text("清空") }
            }
        )
    }
    if (showQuickInviteProgressDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("发送进度") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(quickInviteProgressText)
                    LinearProgressIndicator(
                        progress = { quickInviteProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val forceCancelAction = quickInviteForceCancelAction
                        if (forceCancelAction != null) {
                            forceCancelAction()
                        } else {
                            quickInviteSendCancelled = true
                        }
                    }
                ) {
                    Text("中断发送")
                }
            }
        )
    }
    quickInviteBusyDialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { quickInviteBusyDialogMessage = null },
            title = { Text("对方正忙") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { quickInviteBusyDialogMessage = null }) {
                    Text("知道了")
                }
            }
        )
    }
    quickInviteRejectDialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { quickInviteRejectDialogMessage = null },
            title = { Text("对方已拒绝") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { quickInviteRejectDialogMessage = null }) {
                    Text("知道了")
                }
            }
        )
    }
    quickInviteTimeoutDialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { quickInviteTimeoutDialogMessage = null },
            title = { Text("发送超时") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { quickInviteTimeoutDialogMessage = null }) {
                    Text("知道了")
                }
            }
        )
    }
    if (showQrExitConfirmDialog && showQrDetailPage) {
        AlertDialog(
            onDismissRequest = { showReversePendingSheet = false },
            title = { Text("退出二维码分享页") },
            text = { Text("是否保留当前分享状态？") },
            confirmButton = {
                TextButton(onClick = {
                    showQrExitConfirmDialog = false
                    showQrDetailPage = false
                }) {
                    Text("保留分享")
                }
            },
            dismissButton = {
                TextButton(onClick = { stopShareSession() }) {
                    Text("停止分享")
                }
            }
        )
    }
    if (showCopyUrlConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showCopyUrlConfirmDialog = false },
            title = { Text("复制下载地址") },
            text = { Text("是否将当前下载地址复制到剪贴板？") },
            confirmButton = {
                TextButton(onClick = {
                    val url = pendingCopyUrl
                    if (!url.isNullOrBlank()) {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("download_url", url))
                        statusMessage = "下载地址已复制到剪贴板。"
                        errorMessage = null
                    }
                    showCopyUrlConfirmDialog = false
                }) {
                    Text("复制")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCopyUrlConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        onBack?.let { back ->
            OutlinedButton(onClick = back, modifier = Modifier.fillMaxWidth()) {
                Text("返回")
            }
        }
        TabRow(selectedTabIndex = selectedMode) {
            Tab(
                selected = selectedMode == 0,
                onClick = { selectedMode = 0 },
                text = { Text("文件分享") },
                icon = { Icon(Icons.Default.InsertDriveFile, contentDescription = null) }
            )
            Tab(
                selected = selectedMode == 1,
                onClick = { selectedMode = 1 },
                text = { Text("增强传输模式") },
                icon = { Icon(Icons.Default.Wifi, contentDescription = null) }
            )
            Tab(
                selected = selectedMode == 2,
                onClick = { selectedMode = 2 },
                text = { Text("剪贴板与文本") },
                icon = { Icon(Icons.Default.TextSnippet, contentDescription = null) }
            )
        }

        if (selectedMode == 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("文件分享", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    if (fileSendType == 0) {
                        FileShareHeroCard(
                            onClick = { showFilePickOptionSheet = true }
                        )
                    }

                    if (fileSendType == 0) {
                        Text(
                            text = "传输模式",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HighlightToggleButton(
                                label = "IPv4 局域网",
                                selected = networkShareMode == 0,
                                onClick = { networkShareMode = 0 },
                                modifier = Modifier.weight(1f)
                            )
                            HighlightToggleButton(
                                label = "IPv6 公网",
                                selected = networkShareMode == 1,
                                onClick = { networkShareMode = 1 },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            "当前端口：$manualPortInput · ${if (shareRandomPortEnabled) "随机" else "固定"}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (networkShareMode == 1) {
                            Text(
                                text = "说明：两台手机位于同一基站时，可能受客户端隔离影响导致不通；部分省份/运营商默认禁止入站或存在防火墙策略，也会导致 IPv6 不通。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("启用NAT", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "通过 STUN 探测公网映射端口（实验）",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = enableNatAssist,
                                onCheckedChange = { enableNatAssist = it },
                                enabled = true
                            )
                        }
                        if (enableNatAssist) {
                            Text(
                                text = "已启用 NAT（支持 IPv4/IPv6，基于 STUN 映射公网地址与端口）。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (supportsSendType(1)) {
                        OutlinedButton(
                            onClick = {
                                fileSendType = 1
                                onStopServer()
                                clearShareState()
                                resetCimbarSender()
                                resetSstvState()
                                statusMessage = null
                                errorMessage = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = fileSendType != 1
                        ) { Text("CIMBAR 发送（仅<20MB）") }
                    }
                    if (fileSendType == 2) {
                        Text(
                            text = "SSTV 固定模式：Martin M1（稳定优先）",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "音频参数",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    sstvQualityPreset = Robot36SstvEncoder.QualityPreset.STRICT
                                    if (sstvWavUri != null) {
                                        resetSstvState()
                                        statusMessage = "参数已切到“严格原始”，请重新选择图片生成音频。"
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = sstvQualityPreset != Robot36SstvEncoder.QualityPreset.STRICT
                            ) { Text("严格原始", fontSize = 12.sp) }
                            OutlinedButton(
                                onClick = {
                                    sstvQualityPreset = Robot36SstvEncoder.QualityPreset.CLARITY
                                    if (sstvWavUri != null) {
                                        resetSstvState()
                                        statusMessage = "参数已切到“清晰优先”，请重新选择图片生成音频。"
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = sstvQualityPreset != Robot36SstvEncoder.QualityPreset.CLARITY
                            ) { Text("清晰优先", fontSize = 12.sp) }
                            OutlinedButton(
                                onClick = {
                                    sstvQualityPreset = Robot36SstvEncoder.QualityPreset.STANDARD
                                    if (sstvWavUri != null) {
                                        resetSstvState()
                                        statusMessage = "参数已切到“标准”，请重新选择图片生成音频。"
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = sstvQualityPreset != Robot36SstvEncoder.QualityPreset.STANDARD
                            ) { Text("标准", fontSize = 12.sp) }
                        }
                        Text(
                            text = when (sstvQualityPreset) {
                                Robot36SstvEncoder.QualityPreset.STRICT -> "当前：严格原始（不做平滑/插值/包络）"
                                Robot36SstvEncoder.QualityPreset.CLARITY -> "当前：清晰优先（更稳、更抗噪）"
                                Robot36SstvEncoder.QualityPreset.STANDARD -> "当前：标准（处理更少）"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "画幅策略",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    sstvScaleMode = Robot36SstvEncoder.ScaleMode.CROP_CENTER
                                    if (sstvWavUri != null) {
                                        resetSstvState()
                                        statusMessage = "画幅策略已切换到“填满裁切”，请重新选择图片生成音频。"
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = sstvScaleMode != Robot36SstvEncoder.ScaleMode.CROP_CENTER
                            ) { Text("填满裁切", fontSize = 12.sp) }
                            OutlinedButton(
                                onClick = {
                                    sstvScaleMode = Robot36SstvEncoder.ScaleMode.FIT_BLACK_BARS
                                    if (sstvWavUri != null) {
                                        resetSstvState()
                                        statusMessage = "画幅策略已切换到“完整黑边”，请重新选择图片生成音频。"
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = sstvScaleMode != Robot36SstvEncoder.ScaleMode.FIT_BLACK_BARS
                            ) { Text("完整黑边", fontSize = 12.sp) }
                        }
                        Text(
                            text = if (sstvScaleMode == Robot36SstvEncoder.ScaleMode.CROP_CENTER) {
                                "当前：填满裁切（默认，画面更大，边缘会被裁掉）"
                            } else {
                                "当前：完整黑边（不裁图，但可能出现黑边和画面偏小）"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (fileSendType == 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "CIMBAR 模式选择",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(
                                onClick = { showCimbarHelpDialog = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.HelpOutline,
                                    contentDescription = "CIMBAR 说明"
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(4, 66, 67, 68).forEach { mode ->
                                OutlinedButton(
                                    onClick = {
                                        cimbarMode = mode
                                        if (fileSendType == 1 && selectedFileUri != null) {
                                            scope.launch { handlePickedFile(selectedFileUri!!) }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = cimbarMode != mode
                                ) {
                                    Text(mode.toString(), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    if (fileSendType != 0) {
                        Button(
                            onClick = {
                                if (fileSendType == 2) {
                                    filePickerLauncher.launch("image/*")
                                } else {
                                    filePickerLauncher.launch("*/*")
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(58.dp)
                        ) {
                            Text(
                                text = when (fileSendType) {
                                    1 -> "选择文件（CIMBAR）"
                                    2 -> "选择图片并生成 SSTV 音频"
                                    else -> "选择文件并生成二维码"
                                },
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        } else if (selectedMode == 1) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("增强传输模式", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    OutlinedButton(
                        onClick = { showIpv4UdpEnhancedSend = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("IPv4 增强传输")
                    }
                    OutlinedButton(
                        onClick = { showIpv6UdpEnhancedSend = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("IPv6 增强传输")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("反向传输（PUSH）", fontWeight = FontWeight.SemiBold)
                            Text(
                                "适用于发送端不允许入站、接收端允许入站的场景",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = reversePushEnabled,
                            onCheckedChange = {
                                reversePushEnabled = it
                                if (it) {
                                    showReversePushConfigSheet = true
                                } else {
                                    showReversePendingSheet = false
                                }
                            }
                        )
                    }
                    if (reversePushEnabled && reversePushAddress.isNotBlank()) {
                        Text(
                            "当前上传地址：$reversePushAddress",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showReversePushConfigSheet = true },
                            modifier = Modifier.weight(1f),
                            enabled = reversePushEnabled
                        ) {
                            Text("反向参数")
                        }
                        Button(
                            onClick = {
                                fileSendType = 0
                                showFilePickOptionSheet = true
                            },
                            modifier = Modifier.weight(1f),
                            enabled = reversePushEnabled
                        ) {
                            Text("添加文件")
                        }
                    }
                    if (reversePushEnabled) {
                        OutlinedButton(
                            onClick = { showReversePendingSheet = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = reversePushReady
                        ) {
                            Text("查看待传输列表")
                        }
                        if (!showReverseSendProgressSheet && (reverseSendProgress.running || reverseSendProgress.totalFiles > 0)) {
                            OutlinedButton(
                                onClick = { showReverseSendProgressSheet = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("查看传输进度")
                            }
                        }
                    }
                }
            }
        } else {
            val (clipboardGroupLabel, clipboardGroupPreview) = describeClipboardContent()
            val clipboardShareReady = clipboardGroupLabel != "剪贴板为空" && clipboardGroupLabel != "暂不支持的剪贴板内容"
            val trimmedCustomText = customText.trim()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("剪贴板", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        OutlinedTextField(
                            value = if (clipboardShareReady) clipboardGroupPreview else "",
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4,
                            maxLines = 8,
                            label = { Text(clipboardGroupLabel) },
                            placeholder = {
                                Text(
                                    if (clipboardGroupLabel == "剪贴板为空") {
                                        "当前没有可发送的剪贴板内容"
                                    } else {
                                        "当前剪贴板类型暂不支持"
                                    }
                                )
                            }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch { shareClipboardContent() }
                                },
                                enabled = clipboardShareReady,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("直接分享")
                            }
                            OutlinedButton(
                                onClick = { showClipboardActionSheet = true },
                                enabled = clipboardShareReady,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("LocalSend")
                            }
                        }
                        OutlinedButton(
                            onClick = { showClipboardSyncPage = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Wifi, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("进入剪贴板共享（实时）")
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("文本", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = { customText = "" },
                                enabled = customText.isNotEmpty()
                            ) {
                                Text("一键清空")
                            }
                        }
                        OutlinedTextField(
                            value = customText,
                            onValueChange = { customText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 140.dp, max = 240.dp),
                            minLines = 5,
                            maxLines = 10,
                            label = { Text("自定义文本") },
                            placeholder = { Text("输入要分享的文本") }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch { shareTextContent(customText, "自定义文本") }
                                },
                                enabled = trimmedCustomText.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.QrCode, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("生成分享码")
                            }
                            OutlinedButton(
                                onClick = { showTextActionSheet = true },
                                enabled = trimmedCustomText.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("LocalSend")
                            }
                        }
                    }
                }
            }
        }

        if (qrCodeBitmap != null) {
            OutlinedButton(
                onClick = { showQrDetailPage = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("查看二维码分享页")
            }
        }

        if (fileSendType == 1) {
            cimbarFrameBitmap?.let { bitmap ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "cimbar_dynamic_frame",
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { showCimbarFullscreen = true },
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }

        if (fileSendType == 2 && sstvWavUri != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("SSTV 音频已就绪（Martin M1）", fontWeight = FontWeight.SemiBold)
                    Text(
                        "建议发送端音量 70%-90%，并让接收端麦克风靠近扬声器。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val wavUri = sstvWavUri ?: return@Button
                                runCatching {
                                    sstvPlayer?.release()
                                    val player = MediaPlayer().apply {
                                        setDataSource(context, wavUri)
                                        setOnCompletionListener { mp ->
                                            sstvPlaying = false
                                            mp.release()
                                            if (sstvPlayer === mp) sstvPlayer = null
                                        }
                                        setOnErrorListener { mp, _, _ ->
                                            sstvPlaying = false
                                            mp.release()
                                            if (sstvPlayer === mp) sstvPlayer = null
                                            true
                                        }
                                        prepare()
                                        start()
                                    }
                                    sstvPlayer = player
                                    sstvPlaying = true
                                    statusMessage = "正在播放 Martin M1 音频，请在对端 Robot36 选择 Martin M1 接收。"
                                    errorMessage = null
                                }.onFailure { err ->
                                    sstvPlaying = false
                                    errorMessage = "播放 SSTV 失败：${err.message ?: "unknown"}"
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(if (sstvPlaying) "重新播放" else "播放") }
                        OutlinedButton(
                            onClick = {
                                sstvPlayer?.let { player ->
                                    runCatching { player.stop() }
                                    player.release()
                                    sstvPlayer = null
                                }
                                sstvPlaying = false
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("停止") }
                    }
                    OutlinedButton(
                        onClick = {
                            val wavUri = sstvWavUri ?: return@OutlinedButton
                            val chooser = Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "audio/wav"
                                    putExtra(Intent.EXTRA_STREAM, wavUri)
                                    putExtra(Intent.EXTRA_SUBJECT, sstvWavName ?: "sstv_martin_m1.wav")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                "分享 SSTV 音频"
                            )
                            runCatching { context.startActivity(chooser) }
                                .onFailure { err ->
                                    errorMessage = "分享 SSTV 音频失败：${err.message ?: "unknown"}"
                                }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("分享 SSTV 音频文件")
                    }
                }
            }
        }

        if (showCimbarFullscreen && fileSendType == 1 && cimbarFrameBitmap != null) {
            Dialog(
                onDismissRequest = { showCimbarFullscreen = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside = true,
                    dismissOnBackPress = true
                )
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    val src = cimbarFrameBitmap!!
                    val rotated = rotateBitmapByQuarterTurns(src, cimbarFullscreenRotation)
                    Image(
                        bitmap = rotated.asImageBitmap(),
                        contentDescription = "cimbar_dynamic_frame_fullscreen",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { cimbarFullscreenRotation = (cimbarFullscreenRotation + 1) % 4 }
                        ) {
                            Text("旋转 90°")
                        }
                        OutlinedButton(
                            onClick = { showCimbarFullscreen = false }
                        ) {
                            Text("关闭")
                        }
                    }
                }
            }
        }

        errorMessage?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        val hasActiveShare = selectedFileUri != null ||
            selectedFileName != null ||
            serverUrl != null ||
            qrCodeBitmap != null ||
            cimbarSender != null ||
            cimbarFrameBitmap != null ||
            sstvWavUri != null
        if (hasActiveShare) {
            OutlinedButton(
                onClick = {
                    onStopServer()
                    clearShareState()
                    resetCimbarSender()
                    resetSstvState()
                    statusMessage = null
                    errorMessage = null
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("停止当前分享")
            }
        }

        if (showCimbarHelpDialog) {
            AlertDialog(
                onDismissRequest = { showCimbarHelpDialog = false },
                title = { Text("什么是 CIMBAR") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("CIMBAR 是一种通过动态彩码传输文件的数据编码方式。")
                        Text(
                            "当无法使用局域网/互联网时，可以仅依靠屏幕与摄像头完成传输。",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text("当前本应用因实现与稳定性限制，仅支持小于 20MB 文件。")
                        Text("模式建议：")
                        Text("4：低密度，兼容性高，速度较慢。")
                        Text("66：中等密度。")
                        Text("67：一般手机推荐，速度与稳定性更均衡。")
                        Text("68：高密度，环境要求更高。")
                        Text("实测建议：将彩码放大后，旋转 90° 更容易识别。")
                        Text("官网：https://cimbar.org/")
                        Text("GitHub：https://github.com/sz3/libcimbar")
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://cimbar.org/")
                                    )
                                )
                            }
                        }) { Text("打开官网") }
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/sz3/libcimbar")
                                    )
                                )
                            }
                        }) { Text("打开 GitHub") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCimbarHelpDialog = false }) { Text("关闭") }
                }
            )
        }

        if (showHotspotPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showHotspotPermissionDialog = false },
                title = { Text("热点权限不足") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(hotspotPermissionMessage)
                        Text(
                            "建议先授予权限，再尝试应用内开启热点。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            hotspotPermissionLauncher.launch(hotspotRuntimePermissions())
                            showHotspotPermissionDialog = false
                        }) { Text("授予权限") }
                        TextButton(onClick = {
                            HotspotManager.requestWriteSettings(context)
                            showHotspotPermissionDialog = false
                        }) { Text("系统设置授权") }
                        TextButton(onClick = {
                            HotspotManager.openHotspotSettings(context)
                            showHotspotPermissionDialog = false
                        }) { Text("热点设置") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showHotspotPermissionDialog = false }) { Text("取消") }
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (hotspotEnabled) {
                Button(
                    onClick = { stopHotspotNow() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("关闭热点")
                }
            } else {
                OutlinedButton(
                    onClick = { startHotspotNow() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("开启热点")
                }
            }
            OutlinedButton(
                onClick = {
                    hotspotConfig = HotspotManager.getActiveHotspotConfig() ?: hotspotConfig
                    showHotspotDetailPage = true
                    errorMessage = null
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("热点二维码页")
            }
        }

    }

    if (showHotspotDetailPage) {
        val hotspotSheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden }
        )
        ModalBottomSheet(
            onDismissRequest = { showHotspotDetailPage = false },
            sheetState = hotspotSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("热点二维码", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (!hotspotEnabled) {
                    Text(
                        "热点当前未开启，请先返回并开启热点。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { showHotspotDetailPage = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("返回")
                    }
                } else {
                    val activeConfig = HotspotManager.getActiveHotspotConfig() ?: hotspotConfig
                    if (activeConfig != null) {
                        HotspotInfoCard(
                            config = activeConfig,
                            onStopHotspot = { stopHotspotNow() }
                        )
                    } else {
                        Text(
                            "未读取到热点配置，请稍后重试。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { showHotspotDetailPage = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("返回")
                    }
                }
            }
        }
    }
}

private fun rotateBitmapByQuarterTurns(source: Bitmap, quarterTurns: Int): Bitmap {
    val turns = ((quarterTurns % 4) + 4) % 4
    if (turns == 0) return source
    val matrix = android.graphics.Matrix().apply { postRotate((turns * 90).toFloat()) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private fun decodeBitmapFromUri(context: android.content.Context, uri: Uri): Bitmap? {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }
    }.getOrNull()
}

private fun createTempTextUri(context: android.content.Context, text: String): Uri? {
    return try {
        val baseDir = context.externalCacheDir ?: context.cacheDir
        val shareDir = File(baseDir, "share_text").apply { mkdirs() }
        val file = File(shareDir, "clipboard_${System.currentTimeMillis()}.txt")
        file.writeText(text)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (_: Exception) {
        null
    }
}

private fun getFileName(context: android.content.Context, uri: Uri): String {
    var result = "unknown_file"
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    result = it.getString(nameIndex)
                }
            }
        }
    }
    if (result == "unknown_file") {
        result = uri.path?.let { path ->
            val cut = path.lastIndexOf('/')
            if (cut != -1) path.substring(cut + 1) else path
        } ?: "unknown_file"
    }
    return result
}

private fun getMimeType(context: android.content.Context, uri: Uri): String {
    return if (uri.scheme == "content") {
        context.contentResolver.getType(uri) ?: "application/octet-stream"
    } else {
        val fileExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension.lowercase())
            ?: "application/octet-stream"
    }
}

private fun getFileSize(context: android.content.Context, uri: Uri): Long {
    return runCatching {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) return@runCatching cursor.getLong(idx)
                }
            }
            -1L
        } else {
            uri.path?.let { File(it).length() } ?: -1L
        }
    }.getOrElse { -1L }
}

private fun readUriBytes(context: android.content.Context, uri: Uri, maxBytes: Long): ByteArray? {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val data = input.readBytes()
            if (data.size.toLong() >= maxBytes) null else data
        }
    }.getOrNull()
}

private fun saveImageUriToGallery(
    context: android.content.Context,
    sourceUri: Uri,
    fileName: String?
): Uri? {
    return runCatching {
        val resolver = context.contentResolver
        val mime = resolver.getType(sourceUri) ?: "image/png"
        val ext = when {
            mime.contains("jpeg", ignoreCase = true) || mime.contains("jpg", ignoreCase = true) -> ".jpg"
            mime.contains("webp", ignoreCase = true) -> ".webp"
            else -> ".png"
        }
        val safeName = (fileName ?: "sstv_received_${System.currentTimeMillis()}").let {
            if (it.contains('.')) it else "$it$ext"
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, safeName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FileTran")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val targetUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        try {
            resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(targetUri)?.use { output ->
                    input.copyTo(output)
                } ?: error("output stream unavailable")
            } ?: error("input stream unavailable")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(targetUri, done, null, null)
            }
            targetUri
        } catch (e: Exception) {
            resolver.delete(targetUri, null, null)
            throw e
        }
    }.getOrNull()
}

private fun saveBitmapToSstvReceiveFile(
    context: android.content.Context,
    bitmap: Bitmap
): File? {
    return runCatching {
        val baseDir = context.externalCacheDir ?: context.cacheDir
        val outDir = File(baseDir, "sstv_received").apply { mkdirs() }
        val out = File(outDir, "sstv_rx_${System.currentTimeMillis()}.png")
        val finalBitmap = trimTransparent(bitmap)
        FileOutputStream(out).use { fos ->
            finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        if (finalBitmap !== bitmap) {
            finalBitmap.recycle()
        }
        out
    }.getOrNull()
}

private fun trimTransparent(bitmap: Bitmap): Bitmap {
    if (!bitmap.hasAlpha()) return bitmap
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return bitmap
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    var minX = w
    var minY = h
    var maxX = -1
    var maxY = -1
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            val a = (pixels[row + x] ushr 24) and 0xFF
            if (a > 0) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    if (maxX < minX || maxY < minY) {
        return bitmap
    }
    if (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) {
        return bitmap
    }
    return Bitmap.createBitmap(bitmap, minX, minY, maxX - minX + 1, maxY - minY + 1)
}

private fun selectSstvAudioSource(): Int {
    // Prefer cleaner capture path for analog SSTV tones.
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> MediaRecorder.AudioSource.UNPROCESSED
        else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    }
}

private fun spectrumColor(v: Float): Color {
    val x = v.coerceIn(0f, 1f)
    return when {
        x < 0.25f -> Color(0xFF091A2A).copy(alpha = 0.8f + x * 0.8f)
        x < 0.5f -> Color(0xFF0A7B83).copy(alpha = 0.85f)
        x < 0.75f -> Color(0xFFD4A017).copy(alpha = 0.9f)
        else -> Color(0xFFF6511D).copy(alpha = 0.95f)
    }
}

private fun safeProgress(value: Float): Float {
    return if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
}

private fun safeProgress(numerator: Int, denominator: Int): Float {
    if (denominator <= 0) return 0f
    val v = numerator.toFloat() / denominator.toFloat()
    return if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 0 -> "未知"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format("%.2f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}





