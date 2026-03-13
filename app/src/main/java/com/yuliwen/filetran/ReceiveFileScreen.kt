package com.yuliwen.filetran

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URI
import java.net.Socket
import java.net.URL

private data class ReceivedDownloadFile(
    val name: String,
    val path: String,
    val size: Long
)

private data class WifiQrCredential(
    val ssid: String,
    val password: String,
    val security: String
)

private const val MULTI_SHARE_PAYLOAD_TYPE = "filetran_multi_download_v2"
private const val MAX_BATCH_TASKS_ACCEPTED = 60

private enum class BatchTaskStatus {
    PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
}

private enum class GalleryMediaKind {
    IMAGE, VIDEO
}

private data class BatchDownloadTask(
    val id: Int,
    val url: String,
    val fileName: String = "",
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speed: Long = 0L,
    val status: BatchTaskStatus = BatchTaskStatus.PENDING,
    val message: String? = null,
    val localPath: String? = null
)

private data class MultiDownloadPayload(
    val urls: List<String>,
    val concurrency: Int,
    val manifestUrl: String? = null
)

private data class TraceHopRow(
    val hop: Int,
    val ip: String,
    val rtt: String,
    val ptr: String,
    val asn: String,
    val whois: String,
    val location: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveFileScreen(
    onSecondaryPageVisibleChanged: ((Boolean) -> Unit)? = null,
    requestedEnhancedMode: Int = 0,
    onEnhancedModeRequestHandled: () -> Unit = {}
) {
    var showIpv4UdpEnhancedReceive by rememberSaveable { mutableStateOf(false) }
    var showIpv6UdpEnhancedReceive by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(requestedEnhancedMode) {
        when (requestedEnhancedMode) {
            4 -> {
                showIpv6UdpEnhancedReceive = false
                showIpv4UdpEnhancedReceive = true
                onEnhancedModeRequestHandled()
            }
            6 -> {
                showIpv4UdpEnhancedReceive = false
                showIpv6UdpEnhancedReceive = true
                onEnhancedModeRequestHandled()
            }
        }
    }
    if (showIpv4UdpEnhancedReceive) {
        LaunchedEffect(Unit) {
            onSecondaryPageVisibleChanged?.invoke(true)
        }
        DisposableEffect(Unit) {
            onDispose {
                onSecondaryPageVisibleChanged?.invoke(false)
            }
        }
        Ipv4UdpEnhancedReceiveScreen(
            onBack = { showIpv4UdpEnhancedReceive = false }
        )
        return
    }
    if (showIpv6UdpEnhancedReceive) {
        LaunchedEffect(Unit) {
            onSecondaryPageVisibleChanged?.invoke(true)
        }
        DisposableEffect(Unit) {
            onDispose {
                onSecondaryPageVisibleChanged?.invoke(false)
            }
        }
        Ipv6UdpEnhancedReceiveScreen(
            onBack = { showIpv6UdpEnhancedReceive = false }
        )
        return
    }

    var hostInput by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("12333") }
    var showPasteAddressDialog by remember { mutableStateOf(false) }
    var pasteAddressInput by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }
    var isDownloadPaused by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadControl by remember { mutableStateOf<FileDownloadManager.DownloadControl?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var showWifiJoinScanner by remember { mutableStateOf(false) }
    var pendingScanTarget by remember { mutableStateOf(0) } // 1: normal qr, 2: wifi qr
    var hasCameraPermission by remember { mutableStateOf(false) }
    var hasStoragePermission by remember { mutableStateOf(false) }
    var showTextSheet by remember { mutableStateOf(false) }
    var pendingWifiCredential by remember { mutableStateOf<WifiQrCredential?>(null) }
    var receivedText by remember { mutableStateOf("") }
    var editableText by remember { mutableStateOf("") }
    var completedDownload by remember { mutableStateOf<ReceivedDownloadFile?>(null) }
    var textSource by remember { mutableStateOf("扫码文本") }
    var pendingUrlChoices by remember { mutableStateOf<List<String>>(emptyList()) }
    var showUrlPickerDialog by remember { mutableStateOf(false) }
    var activeBatchPayload by remember { mutableStateOf<MultiDownloadPayload?>(null) }
    var showBatchConfirmDialog by remember { mutableStateOf(false) }
    val batchTasks = remember { mutableStateListOf<BatchDownloadTask>() }
    var batchSchedulerJob by remember { mutableStateOf<Job?>(null) }
    var batchConcurrency by remember { mutableStateOf(3) }
    var batchConcurrencyInput by remember { mutableStateOf("3") }
    var batchPaused by remember { mutableStateOf(false) }
    var showBatchTaskSheet by remember { mutableStateOf(true) }
    var showBatchMediaPickerDialog by remember { mutableStateOf(false) }
    val selectedBatchMediaIds = remember { mutableStateListOf<Int>() }
    val batchControls = remember { mutableMapOf<Int, FileDownloadManager.DownloadControl>() }
    val batchJobs = remember { mutableMapOf<Int, Job>() }
    var reverseServerMode by remember { mutableIntStateOf(0) } // 0: IPv4, 1: IPv6
    var reverseServerPortInput by remember { mutableStateOf("18080") }
    var reverseUploadServer by remember { mutableStateOf<ReversePushTcpServer?>(null) }
    var reverseServerAddress by remember { mutableStateOf<String?>(null) }
    var reverseServerLocalAddress by remember { mutableStateOf<String?>(null) }
    var reverseServerNatEnabled by remember { mutableStateOf(false) }
    var reverseServerNatStatus by remember { mutableStateOf<String?>(null) }
    var reverseServerQr by remember { mutableStateOf<Bitmap?>(null) }
    var showReverseServerSheet by remember { mutableStateOf(false) }
    var reverseReceiveProgress by remember {
        mutableStateOf(
            ReversePushReceiveProgress(
                stage = ReversePushReceiveStage.COMPLETED,
                fileName = "",
                mimeType = "",
                receivedBytes = 0L,
                totalBytes = 0L
            )
        )
    }
    var reverseReceiveRunning by remember { mutableStateOf(false) }
    var showReverseReceiveProgressSheet by remember { mutableStateOf(false) }
    val reverseSessionReceivedFiles = remember { mutableStateListOf<ReceivedDownloadFile>() }
    var showReverseReceivedListSheet by remember { mutableStateOf(false) }
    var selectedReverseReceivedFile by remember { mutableStateOf<ReceivedDownloadFile?>(null) }
    var showDiagnosticsSheet by remember { mutableStateOf(false) }
    var diagnosticsTab by remember { mutableIntStateOf(0) } // 0: ping, 1: traceroute
    var pingProbeMode by remember { mutableIntStateOf(0) } // 0: icmp, 1: tcp
    var diagnosticsHostInput by remember { mutableStateOf("") }
    var diagnosticsTcpPortInput by remember { mutableStateOf("12333") }
    var diagnosticsMaxHopsInput by remember { mutableStateOf("20") }
    var diagnosticsOutput by remember { mutableStateOf("") }
    var diagnosticsTraceRows by remember { mutableStateOf<List<TraceHopRow>>(emptyList()) }
    var diagnosticsRunning by remember { mutableStateOf(false) }
    var diagnosticsJob by remember { mutableStateOf<Job?>(null) }
    val isSecondaryPageVisible =
        showIpv4UdpEnhancedReceive ||
            showIpv6UdpEnhancedReceive ||
            showPasteAddressDialog ||
            showScanner ||
            showWifiJoinScanner ||
            showTextSheet ||
            showUrlPickerDialog ||
            showBatchConfirmDialog ||
            (batchTasks.isNotEmpty() && showBatchTaskSheet) ||
            showBatchMediaPickerDialog ||
            showReverseServerSheet ||
            showReverseReceivedListSheet ||
            showReverseReceiveProgressSheet && (reverseReceiveRunning || reverseReceiveProgress.fileName.isNotBlank()) ||
            showDiagnosticsSheet ||
            pendingWifiCredential != null ||
            selectedReverseReceivedFile != null ||
            completedDownload != null
    LaunchedEffect(isSecondaryPageVisible) {
        onSecondaryPageVisibleChanged?.invoke(isSecondaryPageVisible)
    }
    DisposableEffect(Unit) {
        onDispose {
            onSecondaryPageVisibleChanged?.invoke(false)
        }
    }

    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardHistoryManager = remember { ClipboardHistoryManager(context) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            if (pendingScanTarget == 2) {
                showWifiJoinScanner = true
            } else {
                showScanner = true
            }
        }
        pendingScanTarget = 0
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasStoragePermission = permissions.values.any { it }
    }

    fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true
        } else {
            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                storagePermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
            hasPermission
        }
    }

    fun showTextForCopy(content: String, source: String) {
        if (content.isBlank()) return
        receivedText = content
        editableText = content
        textSource = source
        showTextSheet = true
        clipboardHistoryManager.addRecord(content, source)
    }

    fun startSingleDownload() {
        if (hostInput.isBlank() || port.isBlank()) return
        if (!checkStoragePermission()) {
            errorMessage = "需要存储权限后才能下载。"
            return
        }

        val formattedHost = NetworkUtils.formatHostForUrl(hostInput)
        val url = "http://$formattedHost:$port"
        downloadJob?.cancel()
        isDownloading = true
        isDownloadPaused = false
        errorMessage = null
        downloadProgress = null
        val control = FileDownloadManager.DownloadControl()
        downloadControl = control

        downloadJob = scope.launch {
            try {
                FileDownloadManager.downloadFile(url, context, control = control).collect { progress ->
                    downloadProgress = progress
                    if (progress.progress >= 1.0f) {
                        val filePath = FileDownloadManager.getFilePath(progress.fileName, context)
                        val finalSize = if (progress.totalBytes > 0L) {
                            progress.totalBytes
                        } else {
                            File(filePath).takeIf { it.exists() }?.length() ?: 0L
                        }
                        runCatching {
                            DownloadHistoryManager(context).addRecord(
                                DownloadRecord(
                                    fileName = progress.fileName,
                                    filePath = filePath,
                                    fileSize = finalSize,
                                    sourceUrl = url
                                )
                            )
                        }.onFailure { e ->
                            errorMessage = "下载完成，但写入历史失败: ${e.message}"
                        }

                        if (shouldAutoPreviewDownloadedText(progress.fileName)) {
                            runCatching {
                                tryReadTextFromDownloadedFile(filePath)?.let { text ->
                                    showTextForCopy(text, "下载文件")
                                }
                            }.onFailure { e ->
                                errorMessage = "下载完成，但文本解析失败: ${e.message}"
                            }
                        }

                        isDownloading = false
                        isDownloadPaused = false
                        downloadProgress = null
                        downloadControl = null
                        downloadJob = null
                        completedDownload = ReceivedDownloadFile(
                            name = progress.fileName,
                            path = filePath,
                            size = finalSize
                        )
                    }
                }
            } catch (e: CancellationException) {
                isDownloading = false
                isDownloadPaused = false
                downloadControl = null
                downloadJob = null
            } catch (e: Exception) {
                if (e.message == "DOWNLOAD_CANCELLED") {
                    errorMessage = "下载已取消。"
                } else {
                    errorMessage = "下载失败: ${e.message}"
                }
                isDownloading = false
                isDownloadPaused = false
                downloadProgress = null
                downloadControl = null
                downloadJob = null
            }
        }
    }

    fun openDiagnostics(tab: Int) {
        diagnosticsTab = tab.coerceIn(0, 1)
        if (diagnosticsHostInput.isBlank()) {
            diagnosticsHostInput = hostInput.trim()
        } else {
            diagnosticsHostInput = hostInput.trim().ifBlank { diagnosticsHostInput }
        }
        if (diagnosticsTcpPortInput.isBlank()) {
            diagnosticsTcpPortInput = port.filter(Char::isDigit).ifBlank { "12333" }
        }
        if (diagnosticsTab == 0) {
            pingProbeMode = 0
        }
        diagnosticsOutput = ""
        diagnosticsTraceRows = emptyList()
        showDiagnosticsSheet = true
    }

    fun startPingDiagnostic() {
        val host = diagnosticsHostInput.trim()
        if (host.isBlank()) {
            diagnosticsOutput = "请先输入主机地址。"
            return
        }
        val pingMode = pingProbeMode
        val tcpPort = diagnosticsTcpPortInput.toIntOrNull()
        if (pingMode == 1 && (tcpPort == null || tcpPort !in 1..65535)) {
            diagnosticsOutput = "TCPing 端口无效。"
            return
        }
        diagnosticsJob?.cancel()
        diagnosticsJob = scope.launch {
            diagnosticsRunning = true
            diagnosticsOutput = if (pingMode == 0) {
                "正在执行 ICMP Ping: $host ..."
            } else {
                "正在执行 TCPing: $host:${tcpPort ?: 0} ..."
            }
            try {
                val output = if (pingMode == 0) {
                    runIcmpPingDiagnostic(host)
                } else {
                    runTcpPingDiagnostic(host = host, port = tcpPort ?: 0)
                }
                diagnosticsOutput = output
                diagnosticsTraceRows = emptyList()
            } catch (_: CancellationException) {
                diagnosticsOutput = "诊断已取消。"
                diagnosticsTraceRows = emptyList()
            } catch (e: Exception) {
                diagnosticsOutput = "诊断失败：${e.message ?: "unknown"}"
                diagnosticsTraceRows = emptyList()
            } finally {
                diagnosticsRunning = false
            }
        }
    }

    fun startTraceRouteDiagnosticJob() {
        val host = diagnosticsHostInput.trim()
        if (host.isBlank()) {
            diagnosticsOutput = "请先输入主机地址。"
            return
        }
        val maxHops = diagnosticsMaxHopsInput.toIntOrNull()?.coerceIn(5, 64) ?: 20
        diagnosticsMaxHopsInput = maxHops.toString()
        diagnosticsJob?.cancel()
        diagnosticsJob = scope.launch {
            diagnosticsRunning = true
            diagnosticsOutput = "正在执行 TraceRoute: host=$host hops=$maxHops ..."
            diagnosticsTraceRows = emptyList()
            try {
                val output = runTraceRouteDiagnostic(host = host, maxHops = maxHops)
                diagnosticsOutput = output
                diagnosticsTraceRows = parseTraceHopRows(output)
            } catch (_: CancellationException) {
                diagnosticsOutput = "诊断已取消。"
                diagnosticsTraceRows = emptyList()
            } catch (e: Exception) {
                diagnosticsOutput = "诊断失败：${e.message ?: "unknown"}"
                diagnosticsTraceRows = emptyList()
            } finally {
                diagnosticsRunning = false
            }
        }
    }

    fun stopReverseUploadServer() {
        reverseUploadServer?.let { server ->
            runCatching { server.stop() }
        }
        reverseUploadServer = null
        reverseReceiveRunning = false
        reverseServerAddress = null
        reverseServerLocalAddress = null
        reverseServerNatStatus = null
        reverseServerQr = null
    }

    fun startReverseUploadServer() {
        val listenPort = reverseServerPortInput.toIntOrNull()?.takeIf { it in 1024..65535 } ?: 18080
        reverseServerPortInput = listenPort.toString()
        scope.launch {
            val host = withContext(Dispatchers.IO) {
                if (reverseServerMode == 1) {
                    val apiV6 = NetworkUtils.getLocalGlobalIpv6Address()
                    apiV6 ?: NetworkUtils.getInterfaceGlobalIpv6Address()
                } else {
                    NetworkUtils.getLocalIpAddress(context)
                }
            }
            if (host.isNullOrBlank()) {
                errorMessage = if (reverseServerMode == 1) {
                    "未检测到可用公网 IPv6 地址，无法启动反向上传服务。"
                } else {
                    "未检测到可用局域网 IPv4 地址，无法启动反向上传服务。"
                }
                return@launch
            }
            runCatching {
                stopReverseUploadServer()
                reverseSessionReceivedFiles.clear()
                val localListenAddress = "filetranpush://${NetworkUtils.formatHostForUrl(host)}:$listenPort"
                val batch = if (reverseServerNatEnabled) {
                    withContext(Dispatchers.IO) {
                        NetworkUtils.probeStunMappedEndpointBatch(
                            localPort = listenPort,
                            preferIpv6 = reverseServerMode == 1,
                            transport = StunTransportType.TCP
                        )
                    }
                } else {
                    null
                }
                val mapped = batch?.preferredEndpoint
                val publicHost = mapped?.address ?: host
                val publicPort = mapped?.port ?: listenPort
                val server = ReversePushTcpServer(
                    context = context,
                    port = listenPort,
                    ipv6Mode = reverseServerMode == 1,
                    onProgress = { progress ->
                        scope.launch(Dispatchers.Main) {
                            when (progress.stage) {
                                ReversePushReceiveStage.RECEIVING -> {
                                    reverseReceiveRunning = true
                                    reverseReceiveProgress = progress
                                    showReverseReceiveProgressSheet = true
                                }
                                ReversePushReceiveStage.COMPLETED -> {
                                    reverseReceiveRunning = false
                                    reverseReceiveProgress = progress
                                    showReverseReceiveProgressSheet = true
                                    progress.localPath?.let { path ->
                                        reverseSessionReceivedFiles.add(
                                            ReceivedDownloadFile(
                                                name = progress.fileName,
                                                path = path,
                                                size = progress.totalBytes
                                            )
                                        )
                                    }
                                    Toast.makeText(context, "反向接收完成：${progress.fileName}", Toast.LENGTH_SHORT).show()
                                }
                                ReversePushReceiveStage.CANCELLED -> {
                                    reverseReceiveRunning = false
                                    reverseReceiveProgress = progress
                                    errorMessage = progress.message ?: "已取消当前接收"
                                }
                                ReversePushReceiveStage.FAILED -> {
                                    reverseReceiveRunning = false
                                    reverseReceiveProgress = progress
                                    errorMessage = progress.message ?: "反向接收失败"
                                }
                            }
                        }
                    }
                )
                server.start()
                reverseUploadServer = server
                val uploadUrl = "filetranpush://${NetworkUtils.formatHostForUrl(publicHost)}:$publicPort"
                reverseServerAddress = uploadUrl
                reverseServerLocalAddress = localListenAddress
                reverseServerNatStatus = if (reverseServerNatEnabled) {
                    when {
                        mapped == null -> "NAT 预热失败，已回退本地地址。本机监听端口：$listenPort"
                        batch?.allMismatch == true -> "NAT 已启用（多 STUN，均与 ipip 不一致，默认首个结果），公网端口：${mapped.port}，本机监听端口：$listenPort"
                        batch?.matchedByIpip?.isNotEmpty() == true -> "NAT 已启用（多 STUN，存在 ipip 匹配项，默认首个结果），公网端口：${mapped.port}，本机监听端口：$listenPort"
                        else -> "NAT 已启用（${mapped.stunServer}），公网端口：${mapped.port}，本机监听端口：$listenPort"
                    }
                } else null
                val payload = JSONObject()
                    .put("type", "filetran_reverse_push_v2")
                    .put("endpoint", uploadUrl)
                    .toString()
                reverseServerQr = QRCodeGenerator.generateQRCode(payload, 512)
                if (reverseServerMode == 1) {
                    val apiV6 = NetworkUtils.getLocalGlobalIpv6Address()
                    val nicV6 = NetworkUtils.getInterfaceGlobalIpv6Address()
                    val source = when {
                        !apiV6.isNullOrBlank() && apiV6 == host -> "API IPv6"
                        !nicV6.isNullOrBlank() && nicV6 == host -> "网卡IPv6"
                        else -> "IPv6"
                    }
                    errorMessage = null
                    Toast.makeText(context, "反向服务地址来源：$source", Toast.LENGTH_SHORT).show()
                }
                errorMessage = null
            }.onFailure { e ->
                reverseUploadServer = null
                reverseServerAddress = null
                reverseServerQr = null
                errorMessage = "反向上传服务启动失败: ${e.message}"
            }
        }
    }

    fun switchReverseServerMode(mode: Int) {
        if (reverseServerMode == mode) return
        reverseServerMode = mode
        if (mode == 1) {
            reverseServerNatEnabled = false
        }
        if (reverseUploadServer != null) {
            startReverseUploadServer()
            Toast.makeText(context, "已切换到 ${if (mode == 1) "IPv6" else "IPv4"}，上传服务已自动重启", Toast.LENGTH_SHORT).show()
        }
    }

    fun updateBatchTask(id: Int, updater: (BatchDownloadTask) -> BatchDownloadTask) {
        val idx = batchTasks.indexOfFirst { it.id == id }
        if (idx >= 0) {
            batchTasks[idx] = updater(batchTasks[idx])
        }
    }

    fun stopBatchTask(id: Int) {
        batchControls[id]?.cancelled = true
        batchJobs[id]?.cancel()
        batchControls.remove(id)
        batchJobs.remove(id)
        updateBatchTask(id) { task ->
            task.copy(status = BatchTaskStatus.CANCELLED, message = "已停止")
        }
    }

    fun startBatchDownloads(payload: MultiDownloadPayload) {
        if (payload.urls.size > MAX_BATCH_TASKS_ACCEPTED) {
            errorMessage = "批量任务过多（${payload.urls.size}），最多支持 $MAX_BATCH_TASKS_ACCEPTED 个，请改用 ZIP 下载。"
            return
        }
        batchSchedulerJob?.cancel()
        batchJobs.values.forEach { it.cancel() }
        batchJobs.clear()
        batchControls.clear()
        batchTasks.clear()
        payload.urls.forEachIndexed { index, url ->
            batchTasks.add(BatchDownloadTask(id = index, url = url))
        }
        showBatchTaskSheet = true
        batchPaused = false
        batchConcurrency = payload.concurrency.coerceIn(1, 8)
        errorMessage = null

        batchSchedulerJob = scope.launch {
            while (isActive) {
                val running = batchTasks.count { it.status == BatchTaskStatus.RUNNING || it.status == BatchTaskStatus.PAUSED }
                if (!batchPaused && running < batchConcurrency) {
                    val next = batchTasks.firstOrNull { it.status == BatchTaskStatus.PENDING }
                    if (next != null) {
                        val control = FileDownloadManager.DownloadControl()
                        batchControls[next.id] = control
                        updateBatchTask(next.id) { it.copy(status = BatchTaskStatus.RUNNING, message = null) }
                        val job = launch {
                            try {
                                FileDownloadManager.downloadFile(next.url, context, control = control).collect { progress ->
                                    updateBatchTask(next.id) {
                                        it.copy(
                                            fileName = progress.fileName.ifBlank { it.fileName },
                                            progress = progress.progress.coerceIn(0f, 1f),
                                            downloadedBytes = progress.downloadedBytes,
                                            totalBytes = progress.totalBytes,
                                            speed = progress.speed,
                                            status = if (control.paused) BatchTaskStatus.PAUSED else BatchTaskStatus.RUNNING
                                        )
                                    }
                                    if (progress.progress >= 1.0f) {
                                        val filePath = FileDownloadManager.getFilePath(progress.fileName, context)
                                        val finalSize = if (progress.totalBytes > 0L) progress.totalBytes else {
                                            File(filePath).takeIf { it.exists() }?.length() ?: 0L
                                        }
                                        runCatching {
                                            DownloadHistoryManager(context).addRecord(
                                                DownloadRecord(
                                                    fileName = progress.fileName,
                                                    filePath = filePath,
                                                    fileSize = finalSize,
                                                    sourceUrl = next.url
                                                )
                                            )
                                        }
                                        updateBatchTask(next.id) {
                                            it.copy(
                                                fileName = progress.fileName.ifBlank { it.fileName },
                                                progress = 1f,
                                                downloadedBytes = finalSize,
                                                totalBytes = finalSize,
                                                speed = 0L,
                                                status = BatchTaskStatus.COMPLETED,
                                                message = "完成",
                                                localPath = filePath
                                            )
                                        }
                                    }
                                }
                            } catch (e: CancellationException) {
                                if (batchTasks.firstOrNull { it.id == next.id }?.status != BatchTaskStatus.CANCELLED) {
                                    updateBatchTask(next.id) { it.copy(status = BatchTaskStatus.CANCELLED, message = "已停止") }
                                }
                            } catch (e: Exception) {
                                val msg = if (e.message == "DOWNLOAD_CANCELLED") "已停止" else "失败: ${e.message}"
                                val finalStatus = if (e.message == "DOWNLOAD_CANCELLED") {
                                    BatchTaskStatus.CANCELLED
                                } else {
                                    BatchTaskStatus.FAILED
                                }
                                updateBatchTask(next.id) { it.copy(status = finalStatus, message = msg) }
                            } finally {
                                batchControls.remove(next.id)
                                batchJobs.remove(next.id)
                            }
                        }
                        batchJobs[next.id] = job
                    }
                }

                val hasPending = batchTasks.any { it.status == BatchTaskStatus.PENDING }
                val hasRunning = batchTasks.any { it.status == BatchTaskStatus.RUNNING || it.status == BatchTaskStatus.PAUSED }
                if (!hasPending && !hasRunning) {
                    break
                }
                delay(150)
            }
        }
    }

    suspend fun resolvePayloadUrls(payload: MultiDownloadPayload): MultiDownloadPayload? {
        if (payload.urls.isNotEmpty()) return payload
        val manifestUrl = payload.manifestUrl?.trim().orEmpty()
        if (manifestUrl.isEmpty()) return null
        val urls = withContext(Dispatchers.IO) { fetchUrlsFromManifest(manifestUrl) }
        if (urls.isEmpty()) return null
        return payload.copy(urls = urls)
    }

    fun pauseOrResumeAllBatchTasks() {
        val hasRunningOrPaused = batchTasks.any {
            it.status == BatchTaskStatus.RUNNING || it.status == BatchTaskStatus.PAUSED
        }
        if (!hasRunningOrPaused) return
        if (!batchPaused) {
            batchPaused = true
            batchControls.values.forEach { it.paused = true }
            for (i in batchTasks.indices) {
                val task = batchTasks[i]
                if (task.status == BatchTaskStatus.RUNNING) {
                    batchTasks[i] = task.copy(status = BatchTaskStatus.PAUSED)
                }
            }
        } else {
            batchPaused = false
            batchControls.values.forEach { it.paused = false }
            for (i in batchTasks.indices) {
                val task = batchTasks[i]
                if (task.status == BatchTaskStatus.PAUSED) {
                    batchTasks[i] = task.copy(status = BatchTaskStatus.RUNNING)
                }
            }
        }
    }

    fun cancelAllBatchTasks() {
        batchPaused = false
        batchSchedulerJob?.cancel()
        batchControls.values.forEach { it.cancelled = true }
        batchJobs.values.forEach { it.cancel() }
        batchControls.clear()
        batchJobs.clear()
        for (i in batchTasks.indices) {
            val task = batchTasks[i]
            batchTasks[i] = when (task.status) {
                BatchTaskStatus.COMPLETED,
                BatchTaskStatus.FAILED,
                BatchTaskStatus.CANCELLED -> task
                else -> task.copy(status = BatchTaskStatus.CANCELLED, message = "已取消")
            }
        }
    }

    LaunchedEffect(Unit) {
        val localIp = NetworkUtils.getLocalIpAddress(context)
        if (localIp != null) {
            hostInput = localIp
        }
        hasStoragePermission = checkStoragePermission()
    }

    if (showScanner) {
        QRCodeScanner(
            onQRCodeScanned = { qrContent ->
                showScanner = false
                val multiPayload = parseMultiDownloadPayload(qrContent)
                    ?: parseMultiDownloadPayload(TextShareCodec.tryDecode(qrContent).orEmpty())
                if (multiPayload != null) {
                    activeBatchPayload = multiPayload
                    batchConcurrencyInput = multiPayload.concurrency.toString()
                    showBatchConfirmDialog = true
                    return@QRCodeScanner
                }
                val decodedText = TextShareCodec.tryDecode(qrContent)
                when {
                    decodedText != null -> {
                        showTextForCopy(decodedText, "扫码文本")
                    }
                    qrContent.startsWith("http://") || qrContent.startsWith("https://") -> {
                        runCatching {
                            val parsed = Uri.parse(qrContent)
                            val parsedHost = parsed.host
                            val parsedPort = parsed.port
                            if (!parsedHost.isNullOrBlank()) {
                                hostInput = parsedHost
                            }
                            if (parsedPort in 1..65535) {
                                port = parsedPort.toString()
                                startSingleDownload()
                            }
                        }.onFailure { e ->
                            errorMessage = "二维码解析失败: ${e.message}"
                        }
                    }
                    else -> {
                        showTextForCopy(qrContent, "扫码原始文本")
                    }
                }
            },
            onDismiss = { showScanner = false }
        )
    }

    if (showWifiJoinScanner) {
        QRCodeScanner(
            onQRCodeScanned = { qrContent ->
                showWifiJoinScanner = false
                val credential = parseWifiQrContent(qrContent)
                if (credential == null) {
                    errorMessage = "未识别到 WiFi 二维码，请重试。"
                } else {
                    pendingWifiCredential = credential
                    errorMessage = null
                }
            },
            onDismiss = { showWifiJoinScanner = false }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            diagnosticsJob?.cancel()
            stopReverseUploadServer()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(modifier = Modifier.height(20.dp))

            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                modifier = Modifier.size(100.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Text("接收内容", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "扫码可接收文本，或输入 IPv4 / IPv6 地址下载文件",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )

            OutlinedTextField(
                value = hostInput,
                onValueChange = { hostInput = it.trim() },
                label = { Text("主机地址") },
                placeholder = { Text("例如 192.168.1.10 或 2408:xxxx:...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isDownloading
            )

            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                label = { Text("端口") },
                placeholder = { Text("12333") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isDownloading,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("诊断", fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { openDiagnostics(tab = 0) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Ping测试")
                        }
                        OutlinedButton(
                            onClick = { openDiagnostics(tab = 1) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("TraceRoute")
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (hasCameraPermission) {
                        showScanner = true
                    } else {
                        pendingScanTarget = 1
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isDownloading
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.padding(4.dp))
                Text("扫描二维码")
            }

            Button(
                onClick = {
                    startSingleDownload()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isDownloading && hostInput.isNotBlank() && port.isNotBlank()
            ) {
                Text("开始下载", fontSize = 16.sp)
            }

            OutlinedButton(
                onClick = { showReverseServerSheet = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("反向传输")
            }
            OutlinedButton(
                onClick = { showIpv4UdpEnhancedReceive = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("IPv4 增强传输（UDP 打洞后直收）")
            }
            OutlinedButton(
                onClick = { showIpv6UdpEnhancedReceive = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("IPv6 增强传输（UDP 打洞后直收）")
            }
            if (!showReverseReceiveProgressSheet && (reverseReceiveRunning || reverseReceiveProgress.fileName.isNotBlank())) {
                OutlinedButton(
                    onClick = { showReverseReceiveProgressSheet = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("查看反向传输进度")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (hasCameraPermission) {
                            showWifiJoinScanner = true
                        } else {
                            pendingScanTarget = 2
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isDownloading
                ) {
                    Text("快速加入热点")
                }
                OutlinedButton(
                    onClick = {
                        pasteAddressInput = ""
                        showPasteAddressDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isDownloading
                ) {
                    Text("粘贴识别地址")
                }
            }

            if (batchTasks.isNotEmpty() && !showBatchTaskSheet) {
                OutlinedButton(
                    onClick = { showBatchTaskSheet = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("查看批量下载进度")
                }
            }

        errorMessage?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }

    pendingWifiCredential?.let { wifi ->
        AlertDialog(
            onDismissRequest = { pendingWifiCredential = null },
            title = { Text("快速加入热点") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("SSID: ${wifi.ssid}")
                    Text("加密: ${wifi.security}")
                    Text("密码: ${if (wifi.password.isBlank()) "无密码" else wifi.password}")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val message = quickJoinWifiByCredential(context, wifi)
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        pendingWifiCredential = null
                    }
                ) {
                    Text("立即加入")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingWifiCredential = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showReverseServerSheet) {
        val reverseSheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true
        )
        ModalBottomSheet(
            onDismissRequest = { showReverseServerSheet = false },
            sheetState = reverseSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 680.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("反向传输", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "接收端启动上传服务后，发送端可扫码或粘贴识别地址并执行 PUSH。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (reverseServerMode == 0) {
                        Button(
                            onClick = {},
                            modifier = Modifier.weight(1f)
                        ) { Text("IPv4") }
                    } else {
                        OutlinedButton(
                            onClick = { switchReverseServerMode(0) },
                            modifier = Modifier.weight(1f)
                        ) { Text("IPv4") }
                    }
                    if (reverseServerMode == 1) {
                        Button(
                            onClick = {},
                            modifier = Modifier.weight(1f)
                        ) { Text("IPv6") }
                    } else {
                        OutlinedButton(
                            onClick = { switchReverseServerMode(1) },
                            modifier = Modifier.weight(1f)
                        ) { Text("IPv6") }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = reverseServerPortInput,
                        onValueChange = { reverseServerPortInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("上传服务端口") },
                        placeholder = { Text("18080") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("NAT", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = reverseServerNatEnabled,
                            onCheckedChange = { enabled ->
                                reverseServerNatEnabled = enabled
                                if (reverseUploadServer != null && reverseServerMode == 0) {
                                    startReverseUploadServer()
                                }
                            },
                            enabled = reverseServerMode == 0
                        )
                    }
                }
                Text(
                    "仅 IPv4：启用 NAT 后将通过 STUN 获取公网映射端口，本机仍监听输入端口。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { startReverseUploadServer() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (reverseUploadServer == null) "启动服务" else "重启服务")
                    }
                    OutlinedButton(
                        onClick = { stopReverseUploadServer() },
                        modifier = Modifier.weight(1f),
                        enabled = reverseUploadServer != null
                    )
                    {
                        Text("停止服务")
                    }
                }
                reverseServerAddress?.let { addr ->
                    Text("上传地址：$addr", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    reverseServerLocalAddress?.let { local ->
                        Text(
                            "本机监听：$local",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    reverseServerNatStatus?.let { note ->
                        Text(
                            note,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { copyToClipboard(context, addr) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("复制上传地址")
                    }
                }
                reverseServerQr?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "reverse_upload_qr",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                    )
                }
                TextButton(
                    onClick = { showReverseServerSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("关闭") }
            }
        }
    }

    if (showReverseReceiveProgressSheet && (reverseReceiveRunning || reverseReceiveProgress.fileName.isNotBlank())) {
        val reverseProgressSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showReverseReceiveProgressSheet = false },
            sheetState = reverseProgressSheetState
        ) {
            val total = reverseReceiveProgress.totalBytes.coerceAtLeast(0L)
            val received = reverseReceiveProgress.receivedBytes.coerceAtLeast(0L)
                .coerceAtMost(total.takeIf { it > 0L } ?: Long.MAX_VALUE)
            val progress = if (total > 0L) (received.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("反向传输进度", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (reverseReceiveRunning) "接收中..." else "已停止",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (reverseReceiveProgress.fileName.isNotBlank()) {
                    Text(
                        "当前文件：${reverseReceiveProgress.fileName}",
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
                        if (total > 0L) "${formatBytes(received)} / ${formatBytes(total)}" else "-- / --",
                        fontSize = 12.sp
                    )
                }
                reverseReceiveProgress.message?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(
                    onClick = { showReverseReceivedListSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = reverseSessionReceivedFiles.isNotEmpty()
                ) {
                    Text("查看本次接收内容（${reverseSessionReceivedFiles.size}）")
                }
                Button(
                    onClick = {
                        reverseUploadServer?.cancelCurrentTransfer()
                        reverseReceiveRunning = false
                        errorMessage = "已取消当前反向接收。"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = reverseReceiveRunning
                ) {
                    Text("取消接收")
                }
                OutlinedButton(
                    onClick = { showReverseReceiveProgressSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("收起面板")
                }
            }
        }
    }

    if (showPasteAddressDialog) {
        AlertDialog(
            onDismissRequest = { showPasteAddressDialog = false },
            title = { Text("粘贴下载地址") },
            text = {
                OutlinedTextField(
                    value = pasteAddressInput,
                    onValueChange = { pasteAddressInput = it.trim() },
                    label = { Text("下载地址") },
                    placeholder = { Text("http://... 或 192.168.1.2:12333 / [2408:...]:12333") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = parseDownloadEndpoint(pasteAddressInput)
                        if (parsed == null) {
                            errorMessage = "未识别到有效下载地址或端口。"
                        } else {
                            hostInput = parsed.first
                            port = parsed.second.toString()
                            errorMessage = null
                        }
                        showPasteAddressDialog = false
                    }
                ) {
                    Text("识别")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteAddressDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showDiagnosticsSheet) {
        val diagnosticsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                diagnosticsJob?.cancel()
                diagnosticsRunning = false
                showDiagnosticsSheet = false
            },
            sheetState = diagnosticsSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 720.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("网络诊断", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (diagnosticsTab == 0) {
                        Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Ping") }
                    } else {
                        OutlinedButton(
                            onClick = { diagnosticsTab = 0 },
                            modifier = Modifier.weight(1f)
                        ) { Text("Ping") }
                    }
                    if (diagnosticsTab == 1) {
                        Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("TraceRoute") }
                    } else {
                        OutlinedButton(
                            onClick = { diagnosticsTab = 1 },
                            modifier = Modifier.weight(1f)
                        ) { Text("TraceRoute") }
                    }
                }
                OutlinedTextField(
                    value = diagnosticsHostInput,
                    onValueChange = { diagnosticsHostInput = it.trim() },
                    label = { Text("目标主机") },
                    placeholder = { Text("默认取“主机地址”") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !diagnosticsRunning
                )
                if (diagnosticsTab == 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (pingProbeMode == 0) {
                            Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("ICMP Ping") }
                        } else {
                            OutlinedButton(
                                onClick = { pingProbeMode = 0 },
                                modifier = Modifier.weight(1f),
                                enabled = !diagnosticsRunning
                            ) { Text("ICMP Ping") }
                        }
                        if (pingProbeMode == 1) {
                            Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("TCPing") }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    pingProbeMode = 1
                                    diagnosticsTcpPortInput = diagnosticsTcpPortInput.ifBlank {
                                        port.filter(Char::isDigit).ifBlank { "12333" }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !diagnosticsRunning
                            ) { Text("TCPing") }
                        }
                    }
                    if (pingProbeMode == 1) {
                        OutlinedTextField(
                            value = diagnosticsTcpPortInput,
                            onValueChange = { diagnosticsTcpPortInput = it.filter(Char::isDigit) },
                            label = { Text("TCPing端口") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !diagnosticsRunning,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { startPingDiagnostic() },
                            modifier = Modifier.weight(1f),
                            enabled = !diagnosticsRunning
                        ) {
                            Text("开始测试")
                        }
                        OutlinedButton(
                            onClick = {
                                diagnosticsJob?.cancel()
                                diagnosticsRunning = false
                            },
                            modifier = Modifier.weight(1f),
                            enabled = diagnosticsRunning
                        ) {
                            Text("取消")
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = diagnosticsMaxHopsInput,
                        onValueChange = { diagnosticsMaxHopsInput = it.filter(Char::isDigit) },
                        label = { Text("最大跳数") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !diagnosticsRunning,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { startTraceRouteDiagnosticJob() },
                            modifier = Modifier.weight(1f),
                            enabled = !diagnosticsRunning
                        ) {
                            Text("开始跟踪")
                        }
                        OutlinedButton(
                            onClick = {
                                diagnosticsJob?.cancel()
                                diagnosticsRunning = false
                            },
                            modifier = Modifier.weight(1f),
                            enabled = diagnosticsRunning
                        ) {
                            Text("取消")
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
                            .heightIn(min = 160.dp, max = 380.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        if (diagnosticsTab == 1 && diagnosticsTraceRows.isNotEmpty()) {
                            Text("逐跳结果", fontWeight = FontWeight.SemiBold)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "Hop  IP  RTT  PTR  ASN  Whois  Location",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                )
                                diagnosticsTraceRows.forEach { hop ->
                                    Text(
                                        "%2d  %s  %s  %s  %s  %s  %s".format(
                                            hop.hop,
                                            hop.ip,
                                            hop.rtt,
                                            hop.ptr,
                                            hop.asn,
                                            hop.whois,
                                            hop.location
                                        ),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            if (diagnosticsTab == 1 && diagnosticsTraceRows.isNotEmpty()) {
                                stripTraceHopRowsFromOutput(diagnosticsOutput).ifBlank { "诊断完成。" }
                            } else {
                                diagnosticsOutput.ifBlank { "点击上方按钮开始诊断。" }
                            },
                            fontSize = 12.sp
                        )
                    }
                }
                TextButton(
                    onClick = {
                        diagnosticsJob?.cancel()
                        diagnosticsRunning = false
                        showDiagnosticsSheet = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("关闭")
                }
            }
        }
    }

    if (showBatchConfirmDialog && activeBatchPayload != null) {
        val payload = activeBatchPayload!!
        AlertDialog(
            onDismissRequest = { showBatchConfirmDialog = false },
            title = { Text("批量下载") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (payload.urls.isNotEmpty()) {
                        Text("检测到 ${payload.urls.size} 个下载地址。")
                    } else {
                        Text("检测到批量下载清单链接。")
                    }
                    OutlinedTextField(
                        value = batchConcurrencyInput,
                        onValueChange = { batchConcurrencyInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("并发下载数") },
                        placeholder = { Text("默认 3") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text("将在当前页面自动排队下载，可单独停止任务。")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatchConfirmDialog = false
                        val c = batchConcurrencyInput.toIntOrNull()?.coerceIn(1, 8) ?: payload.concurrency
                        scope.launch {
                            val resolved = resolvePayloadUrls(payload.copy(concurrency = c))
                            if (resolved == null || resolved.urls.isEmpty()) {
                                errorMessage = "批量清单解析失败，请确认发送端仍在共享状态。"
                            } else {
                                startBatchDownloads(resolved)
                            }
                        }
                    }
                ) {
                    Text("开始")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    val progressSnapshot = downloadProgress
    if (isDownloading && progressSnapshot != null) {
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden }
        )
        ModalBottomSheet(
            onDismissRequest = {},
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    if (isDownloadPaused) "下载已暂停" else "下载中...",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(progressSnapshot.fileName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(
                    progress = { progressSnapshot.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${progressSnapshot.getProgressPercentage()}%", fontSize = 14.sp)
                    Text(progressSnapshot.getFormattedSpeed(), fontSize = 14.sp)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatBytes(progressSnapshot.downloadedBytes), fontSize = 12.sp)
                    Text(formatBytes(progressSnapshot.totalBytes), fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        val control = downloadControl ?: return@Button
                        if (isDownloadPaused) {
                            control.paused = false
                            isDownloadPaused = false
                        } else {
                            control.paused = true
                            isDownloadPaused = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isDownloadPaused) "继续下载" else "暂停下载")
                }
                OutlinedButton(
                    onClick = {
                        downloadControl?.cancelled = true
                        downloadJob?.cancel()
                        isDownloading = false
                        isDownloadPaused = false
                        val fileName = progressSnapshot.fileName
                        if (fileName.isNotBlank()) {
                            FileDownloadManager.deleteFile(FileDownloadManager.getFilePath(fileName, context))
                        }
                        downloadProgress = null
                        downloadControl = null
                        downloadJob = null
                        errorMessage = "下载已取消。"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消下载")
                }
            }
        }
    }

    if (batchTasks.isNotEmpty() && showBatchTaskSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                showBatchTaskSheet = false
            },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("批量下载任务", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "并发上限 $batchConcurrency，已完成 ${batchTasks.count { it.status == BatchTaskStatus.COMPLETED }}/${batchTasks.size}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { pauseOrResumeAllBatchTasks() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (batchPaused) "全部继续" else "全部暂停")
                    }
                    OutlinedButton(
                        onClick = { cancelAllBatchTasks() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("全部取消下载")
                    }
                }
                OutlinedButton(
                    onClick = { showBatchTaskSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("收起进度面板")
                }
                val completedMediaTasks = batchTasks.filter { task ->
                    task.status == BatchTaskStatus.COMPLETED &&
                        !task.localPath.isNullOrBlank() &&
                        detectGalleryMediaKind(task.localPath) != null
                }
                if (completedMediaTasks.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        importBatchTasksToGallery(context, completedMediaTasks)
                                    }
                                    Toast.makeText(
                                        context,
                                        "已导入 ${result.first} 个，失败 ${result.second} 个",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("一键导入图库")
                        }
                        OutlinedButton(
                            onClick = {
                                selectedBatchMediaIds.clear()
                                selectedBatchMediaIds.addAll(completedMediaTasks.map { it.id })
                                showBatchMediaPickerDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("选择导入图库")
                        }
                    }
                }
                val displayTasks = if (batchTasks.size > 30) batchTasks.take(30) else batchTasks
                if (batchTasks.size > displayTasks.size) {
                    Text(
                        "任务较多，仅展示前 ${displayTasks.size} 项（总计 ${batchTasks.size}）。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                displayTasks.forEach { task ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                task.fileName.ifBlank { task.url },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                when (task.status) {
                                    BatchTaskStatus.PENDING -> "等待中"
                                    BatchTaskStatus.RUNNING -> "下载中"
                                    BatchTaskStatus.PAUSED -> "已暂停"
                                    BatchTaskStatus.COMPLETED -> "已完成"
                                    BatchTaskStatus.FAILED -> task.message ?: "下载失败"
                                    BatchTaskStatus.CANCELLED -> task.message ?: "已停止"
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (task.status == BatchTaskStatus.RUNNING || task.status == BatchTaskStatus.PAUSED) {
                                LinearProgressIndicator(
                                    progress = { task.progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("${(task.progress * 100f).toInt()}%", fontSize = 12.sp)
                                    Text(formatBytes(task.speed) + "/s", fontSize = 12.sp)
                                }
                            }
                            if (task.status == BatchTaskStatus.RUNNING || task.status == BatchTaskStatus.PAUSED || task.status == BatchTaskStatus.PENDING) {
                                OutlinedButton(
                                    onClick = { stopBatchTask(task.id) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("停止此任务")
                                }
                            }
                        }
                    }
                }
                val allFinished = batchTasks.all {
                    it.status == BatchTaskStatus.COMPLETED ||
                        it.status == BatchTaskStatus.FAILED ||
                        it.status == BatchTaskStatus.CANCELLED
                }
                if (allFinished) {
                    TextButton(
                        onClick = {
                            batchSchedulerJob?.cancel()
                            batchJobs.values.forEach { it.cancel() }
                            batchJobs.clear()
                            batchControls.clear()
                            batchTasks.clear()
                            showBatchTaskSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("关闭任务面板")
                    }
                }
            }
        }
    }

    if (showReverseReceivedListSheet) {
        val reverseReceivedListSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showReverseReceivedListSheet = false },
            sheetState = reverseReceivedListSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("本次接收内容", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (reverseSessionReceivedFiles.isEmpty()) {
                    Text(
                        "暂无内容",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    reverseSessionReceivedFiles.asReversed().forEach { file ->
                        OutlinedButton(
                            onClick = { selectedReverseReceivedFile = file },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(file.name, maxLines = 2)
                                Text(
                                    formatBytes(file.size),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                TextButton(
                    onClick = { showReverseReceivedListSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("关闭")
                }
            }
        }
    }

    selectedReverseReceivedFile?.let { file ->
        val mediaKind = detectGalleryMediaKind(file.path)
        AlertDialog(
            onDismissRequest = { selectedReverseReceivedFile = null },
            title = { Text(file.name) },
            text = {
                Text(
                    "大小：${formatBytes(file.size)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        openDownloadedFileDefault(context, file.path)
                        selectedReverseReceivedFile = null
                    }
                ) {
                    Text("默认打开")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mediaKind != null) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        saveDownloadedMediaToGallery(context, file.path)
                                    }
                                    Toast.makeText(
                                        context,
                                        if (ok) "已保存到图库" else "保存到图库失败",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    selectedReverseReceivedFile = null
                                }
                            }
                        ) {
                            Text("导入图库")
                        }
                    }
                    TextButton(
                        onClick = {
                            openDownloadedFileWithChooser(context, file.path)
                            selectedReverseReceivedFile = null
                        }
                    ) {
                        Text("其他方式打开")
                    }
                }
            }
        )
    }

    if (showBatchMediaPickerDialog) {
        val mediaTasks = batchTasks.filter { task ->
            task.status == BatchTaskStatus.COMPLETED &&
                !task.localPath.isNullOrBlank() &&
                detectGalleryMediaKind(task.localPath) != null
        }
        AlertDialog(
            onDismissRequest = { showBatchMediaPickerDialog = false },
            title = { Text("选择导入图库") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    mediaTasks.forEach { task ->
                        val checked = selectedBatchMediaIds.contains(task.id)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { selected ->
                                    if (selected) {
                                        if (!selectedBatchMediaIds.contains(task.id)) selectedBatchMediaIds.add(task.id)
                                    } else {
                                        selectedBatchMediaIds.remove(task.id)
                                    }
                                }
                            )
                            Text(task.fileName.ifBlank { task.localPath ?: "unknown" }, maxLines = 2)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val picked = mediaTasks.filter { selectedBatchMediaIds.contains(it.id) }
                        showBatchMediaPickerDialog = false
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                importBatchTasksToGallery(context, picked)
                            }
                            Toast.makeText(
                                context,
                                "已导入 ${result.first} 个，失败 ${result.second} 个",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    Text("导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchMediaPickerDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    completedDownload?.let { file ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { completedDownload = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val mediaKind = detectGalleryMediaKind(file.path)
                Text("下载完成", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("文件名: ${file.name}")
                Text("文件大小: ${formatBytes(file.size)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (mediaKind != null) {
                    Button(
                        onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    saveDownloadedMediaToGallery(context, file.path)
                                }
                                Toast.makeText(
                                    context,
                                    if (ok) "已保存到图库" else "保存到图库失败",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("保存到图库")
                    }
                }
                OutlinedButton(
                    onClick = {
                        openDownloadedFileDefault(context, file.path)
                        completedDownload = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("默认打开")
                }
                OutlinedButton(
                    onClick = {
                        openDownloadedFileWithChooser(context, file.path)
                        completedDownload = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("选择方式打开")
                }
                OutlinedButton(
                    onClick = {
                        openDownloadedInFileManager(context, file.path)
                        completedDownload = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("在文件管理器中查看")
                }
                TextButton(
                    onClick = { completedDownload = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("关闭")
                }
            }
        }
    }
    if (showTextSheet) {
        val extractedUrls = remember(receivedText) { extractWebUrls(receivedText) }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showTextSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("已接收文本", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("来源: $textSource", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedTextField(
                    value = editableText,
                    onValueChange = { editableText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    label = { Text("可编辑后复制（支持部分复制）") }
                )

                Button(
                    onClick = {
                        copyToClipboard(context, receivedText)
                        clipboardHistoryManager.addRecord(receivedText, "$textSource-全部复制")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text("全部复制")
                }

                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, editableText)
                        clipboardHistoryManager.addRecord(editableText, "$textSource-部分复制")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text("复制编辑后文本")
                }

                if (extractedUrls.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            if (extractedUrls.size == 1) {
                                openInSystemBrowser(context, extractedUrls.first())
                                clipboardHistoryManager.addRecord(extractedUrls.first(), "$textSource-浏览器打开")
                            } else {
                                pendingUrlChoices = extractedUrls
                                showUrlPickerDialog = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("使用系统浏览器打开")
                    }
                }

                TextButton(
                    onClick = { showTextSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("关闭")
                }
            }
        }
    }

    if (showUrlPickerDialog && pendingUrlChoices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showUrlPickerDialog = false },
            title = { Text("选择要打开的网址") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pendingUrlChoices.forEach { url ->
                        OutlinedButton(
                            onClick = {
                                openInSystemBrowser(context, url)
                                clipboardHistoryManager.addRecord(url, "$textSource-浏览器打开")
                                showUrlPickerDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(url, maxLines = 2)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUrlPickerDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

private fun resolveRouteInterface(target: InetAddress): NetworkInterface? {
    return runCatching {
        DatagramSocket().use { sock ->
            sock.connect(target, 33434)
            val local = sock.localAddress ?: return null
            if (local.isAnyLocalAddress || local.isLoopbackAddress) return null
            NetworkInterface.getByInetAddress(local)
        }
    }.getOrNull()
}

private suspend fun runIcmpPingDiagnostic(
    host: String,
    count: Int = 4,
    timeoutMs: Int = 2_000
): String = withContext(Dispatchers.IO) {
    val sanitizedHost = normalizeDiagnosticHost(host)
    if (sanitizedHost.isBlank()) return@withContext "目标主机为空。"
    val target = runCatching { InetAddress.getByName(sanitizedHost) }.getOrNull()
        ?: return@withContext "ICMP Ping 失败：无法解析主机 $sanitizedHost"
    val attempts = count.coerceAtLeast(1)
    val rounds = mutableListOf<String>()
    var ok = 0
    var sumMs = 0L
    repeat(attempts) { idx ->
        val start = System.nanoTime()
        val reachable = runCatching { target.isReachable(timeoutMs) }.getOrDefault(false)
        val ms = (System.nanoTime() - start) / 1_000_000L
        if (reachable) {
            ok++
            sumMs += ms
            rounds += "icmp_seq=${idx + 1} reachable time=${ms}ms"
        } else {
            rounds += "icmp_seq=${idx + 1} timeout"
        }
    }
    buildString {
        appendLine("ICMP Ping (app): $sanitizedHost (${target.hostAddress})")
        rounds.forEach { appendLine(it) }
        appendLine("--- summary ---")
        appendLine("sent=$attempts recv=$ok loss=${((attempts - ok) * 100 / attempts)}%")
        if (ok > 0) append("avg=${sumMs / ok}ms")
    }
}

private suspend fun runTcpPingDiagnostic(
    host: String,
    port: Int,
    count: Int = 4,
    timeoutMs: Int = 2_000
): String = withContext(Dispatchers.IO) {
    val sanitizedHost = normalizeDiagnosticHost(host)
    if (sanitizedHost.isBlank()) return@withContext "目标主机为空。"
    if (port !in 1..65535) return@withContext "端口无效：$port"
    val attempts = count.coerceAtLeast(1)
    val rounds = mutableListOf<String>()
    var ok = 0
    var sumMs = 0L
    repeat(attempts) { idx ->
        val start = System.nanoTime()
        val result = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(sanitizedHost, port), timeoutMs)
            }
        }
        val ms = (System.nanoTime() - start) / 1_000_000L
        if (result.isSuccess) {
            ok++
            sumMs += ms
            rounds += "tcp_seq=${idx + 1} connected time=${ms}ms"
        } else {
            val reason = result.exceptionOrNull()?.message ?: "timeout"
            rounds += "tcp_seq=${idx + 1} failed $reason"
        }
    }
    buildString {
        appendLine("TCPing (app): $sanitizedHost:$port")
        rounds.forEach { appendLine(it) }
        appendLine("--- summary ---")
        appendLine("sent=$attempts recv=$ok loss=${((attempts - ok) * 100 / attempts)}%")
        if (ok > 0) append("avg=${sumMs / ok}ms")
    }
}

private fun parseTraceHopRows(output: String): List<TraceHopRow> {
    val rowRegex = Regex("^\\s*(\\d+)\\s{2}(.+?)\\s{2}(.+?)\\s{2}(.+?)\\s{2}(.+?)\\s{2}(.+?)\\s{2}(.+)$")
    return output.lineSequence()
        .mapNotNull { line ->
            val match = rowRegex.find(line.trimEnd()) ?: return@mapNotNull null
            val hop = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            TraceHopRow(
                hop = hop,
                ip = match.groupValues.getOrElse(2) { "*" },
                rtt = match.groupValues.getOrElse(3) { "*" },
                ptr = match.groupValues.getOrElse(4) { "*" },
                asn = match.groupValues.getOrElse(5) { "*" },
                whois = match.groupValues.getOrElse(6) { "*" },
                location = match.groupValues.getOrElse(7) { "*" }
            )
        }
        .toList()
}

private fun stripTraceHopRowsFromOutput(output: String): String {
    val rowRegex = Regex("^\\s*\\d+\\s{2}.+")
    return output.lineSequence()
        .filterNot { rowRegex.matches(it.trimEnd()) }
        .joinToString("\n")
}

private suspend fun runTraceRouteDiagnostic(
    host: String,
    maxHops: Int = 20
): String = withContext(Dispatchers.IO) {
    val sanitizedHost = normalizeDiagnosticHost(host)
    if (sanitizedHost.isBlank()) return@withContext "目标主机为空。"
    NextTraceRouteModule.run(
        rawHost = sanitizedHost,
        maxHops = maxHops.coerceIn(5, 64),
        config = NextTraceRouteConfig(
            dnsMode = "udp",
            dnsServer = "1.1.1.1",
            dohServer = "https://1.1.1.1/dns-query",
            apiHostNamePow = "origin-fallback.nxtrace.org",
            apiDnsNamePow = "api.nxtrace.org",
            apiHostName = "origin-fallback.nxtrace.org",
            apiDnsName = "api.nxtrace.org",
            pingTimeoutSec = 1,
            traceProbeCount = 1,
            hopRttProbeCount = 3
        )
    )
}

private fun normalizeDiagnosticHost(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return trimmed
    val noScheme = runCatching {
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            URI(trimmed).host ?: trimmed
        } else {
            trimmed
        }
    }.getOrDefault(trimmed)

    var host = noScheme.trim()
    if (host.startsWith("[") && host.contains("]")) {
        host = host.substringAfter("[").substringBefore("]")
    } else if (host.count { it == ':' } == 1 && host.substringAfter(':').all { it.isDigit() }) {
        host = host.substringBefore(':')
    }
    return host.replace("%25", "%").trim()
}

private fun parseMultiDownloadPayload(raw: String): MultiDownloadPayload? {
    val content = raw.trim()
    if (content.isEmpty() || !content.startsWith("{")) return null
    return runCatching {
        val obj = JSONObject(content)
        val type = obj.optString("type")
        if (
            !type.equals(MULTI_SHARE_PAYLOAD_TYPE, ignoreCase = true) &&
            !type.equals("filetran_multi_download_v1", ignoreCase = true)
        ) return null
        val urls = mutableListOf<String>()
        val urlsJson = obj.optJSONArray("urls")
        if (urlsJson != null) {
            for (i in 0 until urlsJson.length()) {
                val url = urlsJson.optString(i).trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    urls.add(url)
                }
                if (urls.size >= MAX_BATCH_TASKS_ACCEPTED) break
            }
        }
        val manifestUrl = obj.optString("manifestUrl").trim().takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        }
        if (urls.isEmpty() && manifestUrl == null) return null
        val concurrency = obj.optInt("concurrency", 3).coerceIn(1, 8)
        MultiDownloadPayload(
            urls = urls.distinct(),
            concurrency = concurrency,
            manifestUrl = manifestUrl
        )
    }.getOrNull()
}

private fun fetchUrlsFromManifest(manifestUrl: String): List<String> {
    return runCatching {
        val connection = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            return emptyList()
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val obj = JSONObject(body)
        if (!obj.optString("type").equals("filetran_manifest_v1", ignoreCase = true)) {
            return emptyList()
        }
        val files = obj.optJSONArray("files") ?: return emptyList()
        val base = URI(manifestUrl)
        val urls = mutableListOf<String>()
        for (i in 0 until files.length()) {
            val item = files.optJSONObject(i) ?: continue
            val rawUrl = item.optString("url").trim()
            val path = item.optString("path").trim()
            val resolved = when {
                rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
                path.isNotBlank() -> base.resolve(path).toString()
                else -> ""
            }
            if (resolved.startsWith("http://") || resolved.startsWith("https://")) {
                urls.add(resolved)
            }
            if (urls.size >= MAX_BATCH_TASKS_ACCEPTED) break
        }
        urls
    }.getOrElse { emptyList() }
}

private fun parseDownloadEndpoint(raw: String): Pair<String, Int>? {
    val input = raw.trim()
    if (input.isBlank()) return null

    // URL form: http://host:port/path
    if (input.startsWith("http://", ignoreCase = true) || input.startsWith("https://", ignoreCase = true)) {
        val uri = runCatching { Uri.parse(input) }.getOrNull() ?: return null
        val host = uri.host?.trim().orEmpty()
        val port = uri.port
        if (host.isBlank() || port !in 1..65535) return null
        return host to port
    }

    // Bracketed IPv6: [2408:...]:12333
    if (input.startsWith("[") && input.contains("]:")) {
        val end = input.indexOf("]:")
        if (end > 1) {
            val host = input.substring(1, end).trim()
            val port = input.substring(end + 2).trim().toIntOrNull() ?: return null
            if (host.isNotBlank() && port in 1..65535) return host to port
        }
        return null
    }

    // IPv4/domain with explicit port: host:12333
    val firstColon = input.indexOf(':')
    val lastColon = input.lastIndexOf(':')
    if (firstColon > 0 && firstColon == lastColon) {
        val host = input.substring(0, firstColon).trim()
        val port = input.substring(firstColon + 1).trim().toIntOrNull() ?: return null
        if (host.isNotBlank() && port in 1..65535) return host to port
    }
    return null
}

private fun detectGalleryMediaKind(filePath: String?): GalleryMediaKind? {
    val path = filePath?.trim().orEmpty()
    if (path.isEmpty()) return null
    val ext = File(path).extension.lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext).orEmpty()
    return when {
        mime.startsWith("image/") -> GalleryMediaKind.IMAGE
        mime.startsWith("video/") -> GalleryMediaKind.VIDEO
        else -> null
    }
}

private fun importBatchTasksToGallery(
    context: Context,
    tasks: List<BatchDownloadTask>
): Pair<Int, Int> {
    var success = 0
    var failed = 0
    tasks.forEach { task ->
        val path = task.localPath
        if (path.isNullOrBlank()) return@forEach
        if (saveDownloadedMediaToGallery(context, path)) {
            success++
        } else {
            failed++
        }
    }
    return success to failed
}

private fun saveDownloadedMediaToGallery(context: Context, filePath: String): Boolean {
    return try {
        val source = File(filePath)
        if (!source.exists() || !source.isFile) return false
        val mediaKind = detectGalleryMediaKind(filePath) ?: return false
        val ext = source.extension.lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext).orEmpty().ifBlank {
            if (mediaKind == GalleryMediaKind.IMAGE) "image/*" else "video/*"
        }
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (mediaKind == GalleryMediaKind.IMAGE) {
                        "Pictures/FileTran"
                    } else {
                        "Movies/FileTran"
                    }
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val targetUri = when (mediaKind) {
            GalleryMediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            GalleryMediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val resolver = context.contentResolver
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
    } catch (_: Exception) {
        false
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("received_text", text))
}

private fun isPureWebUrl(text: String): Boolean {
    val normalized = text.trim()
    if (normalized.isEmpty()) return false
    if (normalized.contains(' ') || normalized.contains('\n') || normalized.contains('\t')) return false
    if (!(normalized.startsWith("http://", ignoreCase = true) ||
            normalized.startsWith("https://", ignoreCase = true))
    ) return false
    val parsed = runCatching { Uri.parse(normalized) }.getOrNull() ?: return false
    return !parsed.host.isNullOrBlank()
}

private fun extractWebUrls(text: String): List<String> {
    val regex = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
    return regex.findAll(text)
        .map { it.value.trim().trimEnd('.', ',', ';', '!', '?', ')', ']', '}', '"', '\'') }
        .filter { isPureWebUrl(it) }
        .distinct()
        .toList()
}

private fun openInSystemBrowser(context: Context, url: String) {
    val target = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, target).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

private fun parseWifiQrContent(raw: String): WifiQrCredential? {
    val content = raw.trim()
    if (!content.startsWith("WIFI:", ignoreCase = true)) return null
    val body = content.removePrefix("WIFI:").removeSuffix(";;")
    val parts = mutableListOf<String>()
    val token = StringBuilder()
    var escaped = false
    for (ch in body) {
        if (escaped) {
            token.append(ch)
            escaped = false
            continue
        }
        when (ch) {
            '\\' -> escaped = true
            ';' -> {
                parts.add(token.toString())
                token.clear()
            }
            else -> token.append(ch)
        }
    }
    if (token.isNotEmpty()) {
        parts.add(token.toString())
    }

    val fields = mutableMapOf<String, String>()
    parts.forEach { part ->
        val idx = part.indexOf(':')
        if (idx > 0 && idx < part.lastIndex) {
            fields[part.substring(0, idx)] = part.substring(idx + 1)
        }
    }

    val ssid = fields["S"]?.trim().orEmpty()
    if (ssid.isBlank()) return null
    val security = fields["T"]?.trim().orEmpty().ifBlank { "nopass" }
    val password = fields["P"]?.trim().orEmpty()
    return WifiQrCredential(ssid = ssid, password = password, security = security)
}

private fun quickJoinWifiByCredential(context: Context, wifi: WifiQrCredential): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val suggestionBuilder = WifiNetworkSuggestion.Builder().setSsid(wifi.ssid)
        if (wifi.password.isNotBlank() && !wifi.security.equals("nopass", ignoreCase = true)) {
            suggestionBuilder.setWpa2Passphrase(wifi.password)
        }
        val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
            putParcelableArrayListExtra(
                Settings.EXTRA_WIFI_NETWORK_LIST,
                arrayListOf(suggestionBuilder.build())
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }
        "已打开系统 WiFi 加入页面，请确认连接。"
    } else {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val payload = "SSID=${wifi.ssid}  密码=${if (wifi.password.isBlank()) "无密码" else wifi.password}"
        clipboard?.setPrimaryClip(ClipData.newPlainText("wifi_credentials", payload))
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
        "已打开 WiFi 设置，并复制热点信息到剪贴板。"
    }
}

private fun tryReadTextFromDownloadedFile(filePath: String): String? {
    return try {
        val file = File(filePath)
        if (!file.exists()) return null
        if (file.length() > 128 * 1024L) return null

        val ext = file.extension.lowercase()
        val textLike = ext in setOf("txt", "md", "csv", "json", "xml", "html", "log")
        if (!textLike) return null

        FileInputStream(file).use { input ->
            val buffer = ByteArray(file.length().toInt())
            val read = input.read(buffer)
            if (read <= 0) return null
            if (buffer.indexOf(0) >= 0) return null
            buffer.copyOf(read).toString(Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }
}

private fun shouldAutoPreviewDownloadedText(fileName: String): Boolean {
    val name = fileName.lowercase()
    return name.startsWith("clipboard_") && name.endsWith(".txt")
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

private fun openDownloadedFileDefault(context: Context, filePath: String) {
    val file = File(filePath)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

private fun openDownloadedFileWithChooser(context: Context, filePath: String) {
    val file = File(filePath)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(intent, "选择应用").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}

private fun openDownloadedInFileManager(context: Context, filePath: String) {
    val file = File(filePath)
    val folder = file.parentFile ?: return
    if (!folder.exists()) return
    runCatching {
        val docUri = DocumentsContract.buildRootUri("com.android.externalstorage.documents", "primary")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(docUri, DocumentsContract.Root.MIME_TYPE_ITEM)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        runCatching {
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", folder)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
