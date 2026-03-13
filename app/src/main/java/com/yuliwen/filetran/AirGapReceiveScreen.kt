package com.yuliwen.filetran

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Size
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.yuliwen.filetran.airgap.AirGapDecoder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

private data class AirGapMode(val name: String, val modeValue: Int)
private data class DecodeSpeedOption(val name: String, val frameStride: Int)

private data class DecodedFileEntry(
    val path: String,
    val name: String,
    val size: Long,
    val timestamp: Long
)

private data class PixelFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirGapReceiveScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val localView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val outputDir = remember {
        File(FileDownloadManager.getDownloadDirectory(context), "AirGap").apply { mkdirs() }
    }
    val historyManager = remember { DownloadHistoryManager(context) }
    val uiExecutor = remember { ContextCompat.getMainExecutor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val stopCamera: () -> Unit = {
        cameraProviderFuture.addListener({
            runCatching { cameraProviderFuture.get().unbindAll() }
        }, uiExecutor)
    }

    val modes = remember {
        listOf(
            AirGapMode("自动", 0),
            AirGapMode("模式 4", 4),
            AirGapMode("模式 66", 66),
            AirGapMode("模式 67", 67),
            AirGapMode("模式 68", 68)
        )
    }
    val speedOptions = remember {
        listOf(
            DecodeSpeedOption("极速", 1),
            DecodeSpeedOption("均衡", 2),
            DecodeSpeedOption("省电", 3)
        )
    }
    var modeIndex by remember { mutableIntStateOf(0) }
    var speedIndex by remember { mutableIntStateOf(0) }
    val decoder = remember {
        AirGapDecoder(outputDir.absolutePath)
    }
    DisposableEffect(decoder) {
        onDispose { decoder.shutdown() }
    }
    DisposableEffect(Unit) {
        onDispose {
            stopCamera()
            cameraExecutor.shutdown()
        }
    }

    var status by remember { mutableStateOf("等待扫描...") }
    var frameCount by remember { mutableLongStateOf(0L) }
    var hitCount by remember { mutableLongStateOf(0L) }
    var fps by remember { mutableFloatStateOf(0f) }
    var decodedFiles by remember { mutableStateOf<List<DecodedFileEntry>>(emptyList()) }
    val seenPaths = remember { mutableSetOf<String>() }
    var selectedFile by remember { mutableStateOf<DecodedFileEntry?>(null) }
    var finishedFileDialog by remember { mutableStateOf<DecodedFileEntry?>(null) }

    var receiveProgress by remember { mutableFloatStateOf(0f) }
    var pendingFileName by remember { mutableStateOf<String?>(null) }
    var pendingFileSize by remember { mutableLongStateOf(-1L) }
    var currentInputResolution by remember { mutableStateOf("--") }
    var showPreviewGuide by remember { mutableStateOf(false) }
    var showCimbarHelpDialog by remember { mutableStateOf(false) }

    DisposableEffect(localView) {
        val previous = localView.keepScreenOn
        localView.keepScreenOn = true
        onDispose { localView.keepScreenOn = previous }
    }

    @Composable
    fun CameraPreview(modifier: Modifier) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
                previewView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                cameraProviderFuture.addListener({
                    runCatching {
                        val cameraProvider = cameraProviderFuture.get()
                        var frameCounterLocal = 0
                        var fpsWindowStart = System.currentTimeMillis()
                        var fpsWindowFrames = 0L

                        val preview = Preview.Builder()
                            .build()
                            .also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                        val analysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(1920, 1080))
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { ia ->
                                ia.setAnalyzer(cameraExecutor) { imageProxy ->
                                    try {
                                        frameCounterLocal++
                                        uiExecutor.execute { frameCount++ }
                                        fpsWindowFrames++

                                        val now = System.currentTimeMillis()
                                        val elapsed = now - fpsWindowStart
                                        if (elapsed >= 1000) {
                                            val currentFps = fpsWindowFrames * 1000f / elapsed
                                            uiExecutor.execute { fps = currentFps }
                                            fpsWindowStart = now
                                            fpsWindowFrames = 0
                                        }

                                        val shouldDecodeFrame = decoder.isReady() &&
                                            frameCounterLocal % speedOptions[speedIndex].frameStride == 0
                                        if (!shouldDecodeFrame) {
                                            return@setAnalyzer
                                        }

                                        val rgbaPlane = imageProxy.planes[0]
                                        val srcWidth = imageProxy.width
                                        val srcHeight = imageProxy.height
                                        val srcRowStride = rgbaPlane.rowStride
                                        uiExecutor.execute {
                                            currentInputResolution = "${srcWidth}x${srcHeight}"
                                        }
                                        val rgba = extractTightRgbaPlane(
                                            buffer = rgbaPlane.buffer,
                                            width = srcWidth,
                                            height = srcHeight,
                                            rowStride = srcRowStride
                                        )

                                        val inputFrame = PixelFrame(
                                            data = rgba,
                                            width = srcWidth,
                                            height = srcHeight
                                        )
                                        val decoded = decoder.processImageRgba(
                                            inputFrame.data,
                                            inputFrame.width,
                                            inputFrame.height,
                                            modes[modeIndex].modeValue
                                        )
                                        if (!decoded.isNullOrBlank()) {
                                            if (decoded.startsWith("@PROGRESS|")) {
                                                val p = decoded.substringAfter("@PROGRESS|", "0").toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
                                                uiExecutor.execute {
                                                    receiveProgress = p
                                                    if (pendingFileName == null) {
                                                        status = "接收中..."
                                                    }
                                                }
                                                return@setAnalyzer
                                            }
                                            if (decoded.startsWith("@FILE|")) {
                                                val parts = decoded.split('|')
                                                if (parts.size >= 4) {
                                                    val filePath = parts[1]
                                                    val nameFromNative = parts[2]
                                                    val sizeFromNative = parts[3].toLongOrNull() ?: -1L
                                                    val file = File(filePath)
                                                    if (file.exists() && seenPaths.add(file.absolutePath)) {
                                                        uiExecutor.execute { hitCount++ }
                                                        historyManager.addRecord(
                                                            DownloadRecord(
                                                                fileName = file.name,
                                                                filePath = file.absolutePath,
                                                                fileSize = if (sizeFromNative > 0) sizeFromNative else file.length(),
                                                                sourceUrl = "airgap://cimbar"
                                                            )
                                                        )
                                                        val entry = DecodedFileEntry(
                                                            path = file.absolutePath,
                                                            name = file.name,
                                                            size = if (sizeFromNative > 0) sizeFromNative else file.length(),
                                                            timestamp = System.currentTimeMillis()
                                                        )
                                                        uiExecutor.execute {
                                                            decodedFiles = listOf(entry) + decodedFiles
                                                            status = "接收完成：${file.name}"
                                                            receiveProgress = 1f
                                                            pendingFileName = nameFromNative
                                                            pendingFileSize = sizeFromNative
                                                            finishedFileDialog = entry
                                                        }
                                                    } else {
                                                        uiExecutor.execute {
                                                            receiveProgress = 1f
                                                            pendingFileName = nameFromNative
                                                            pendingFileSize = sizeFromNative
                                                        }
                                                    }
                                                }
                                                return@setAnalyzer
                                            }
                                            val modeSignal = Regex("^/\\d{1,3}$")
                                            if (modeSignal.matches(decoded)) {
                                                val detectedMode = decoded.removePrefix("/").toIntOrNull()
                                                val detectedIndex = modes.indexOfFirst { it.modeValue == detectedMode }
                                                if (detectedIndex >= 0 && modeIndex != detectedIndex) {
                                                    uiExecutor.execute {
                                                        modeIndex = detectedIndex
                                                        status = "自动已锁定到${modes[detectedIndex].name}"
                                                    }
                                                }
                                                return@setAnalyzer
                                            }
                                            val file = File(decoded)
                                            if (file.exists() && seenPaths.add(file.absolutePath)) {
                                                uiExecutor.execute { hitCount++ }
                                                historyManager.addRecord(
                                                    DownloadRecord(
                                                        fileName = file.name,
                                                        filePath = file.absolutePath,
                                                        fileSize = file.length(),
                                                        sourceUrl = "airgap://cimbar"
                                                    )
                                                )
                                                val entry = DecodedFileEntry(
                                                    path = file.absolutePath,
                                                    name = file.name,
                                                    size = file.length(),
                                                    timestamp = System.currentTimeMillis()
                                                )
                                                uiExecutor.execute {
                                                    decodedFiles = listOf(entry) + decodedFiles
                                                    status = "接收完成：${file.name}"
                                                    receiveProgress = 1f
                                                    pendingFileName = file.name
                                                    pendingFileSize = file.length()
                                                    finishedFileDialog = entry
                                                }
                                            }
                                        } else {
                                            uiExecutor.execute {
                                                status = "等待扫描..."
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        uiExecutor.execute {
                                            status = "解码异常：${t.message}"
                                        }
                                    } finally {
                                        imageProxy.close()
                                    }
                                }
                            }
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    }.onFailure { e ->
                        uiExecutor.execute { status = "摄像头绑定失败：${e.message}" }
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = modifier
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("CIMBAR 接收", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    onClick = { showCimbarHelpDialog = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.HelpOutline,
                        contentDescription = "CIMBAR 说明"
                    )
                }
                OutlinedButton(onClick = {
                    stopCamera()
                    onBack()
                }) {
                    Text("返回")
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { showPreviewGuide = true }
        ) {
            if (!showPreviewGuide) {
                CameraPreview(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = previewStretchScaleX(currentInputResolution),
                            scaleY = previewStretchScaleY(currentInputResolution)
                        )
                )
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
            )
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("状态：$status", fontSize = 12.sp)
                LinearProgressIndicator(
                    progress = receiveProgress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "进度 ${"%.1f".format(receiveProgress * 100f)}%  ·  FPS ${"%.1f".format(fps)}  ·  ${currentInputResolution}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (decoder.isReady()) "解码器：已加载" else "解码器：加载失败", fontSize = 12.sp)
                if (!decoder.isReady()) {
                    Text(
                        "错误：${decoder.getInitError() ?: "未知"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "文件：${pendingFileName ?: "识别中"}  ${if (pendingFileSize > 0) formatSize(pendingFileSize) else "--"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TabRow(selectedTabIndex = modeIndex) {
                    modes.forEachIndexed { index, mode ->
                        Tab(
                            selected = modeIndex == index,
                            onClick = {
                                modeIndex = index
                                status = "已切换到${mode.name}"
                            },
                            text = { Text(mode.name) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    speedOptions.forEachIndexed { idx, sp ->
                        OutlinedButton(
                            onClick = { speedIndex = idx },
                            modifier = Modifier.weight(1f),
                            enabled = speedIndex != idx
                        ) {
                            Text(sp.name)
                        }
                    }
                }
            }
        }

        if (decodedFiles.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(decodedFiles, key = { it.path }) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedFile = item },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${formatSize(item.size)}  ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(item.timestamp))}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { selectedFile = item }) {
                                Icon(Icons.Default.MoreHoriz, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }

        OutlinedButton(onClick = { openFolder(context, outputDir) }, modifier = Modifier.fillMaxWidth()) {
            Text("打开接收目录")
        }
    }

    if (showPreviewGuide) {
        Dialog(
            onDismissRequest = { showPreviewGuide = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                CameraPreview(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = previewStretchScaleX(currentInputResolution),
                            scaleY = previewStretchScaleY(currentInputResolution)
                        )
                )
                Card(
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("对准动态彩码并调整距离至尽可能大", fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(
                            progress = receiveProgress.coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("接收进度：${"%.1f".format(receiveProgress * 100f)}%", fontSize = 12.sp)
                        Text(
                            "状态：$status",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OutlinedButton(
                    onClick = { showPreviewGuide = false },
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Text("关闭")
                }
            }
        }
    }

    selectedFile?.let { file ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedFile = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(file.name, fontWeight = FontWeight.SemiBold)
                Text(file.path, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = {
                        openFileDefault(context, file.path)
                        selectedFile = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("默认打开") }
                OutlinedButton(
                    onClick = {
                        openFileWithChooser(context, file.path)
                        selectedFile = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("选择其他应用") }
                Button(
                    onClick = {
                        shareFile(context, file.path)
                        selectedFile = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("分享")
                }
                TextButton(onClick = { selectedFile = null }, modifier = Modifier.fillMaxWidth()) {
                    Text("关闭")
                }
            }
        }
    }

    finishedFileDialog?.let { file ->
        AlertDialog(
            onDismissRequest = { finishedFileDialog = null },
            title = { Text("接收完成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("文件名：${file.name}")
                    Text("大小：${formatSize(file.size)}")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    openFileDefault(context, file.path)
                    finishedFileDialog = null
                }) { Text("默认打开") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        openFileWithChooser(context, file.path)
                        finishedFileDialog = null
                    }) { Text("选择应用") }
                    TextButton(onClick = {
                        shareFile(context, file.path)
                        finishedFileDialog = null
                    }) { Text("分享") }
                }
            }
        )
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

private fun openFolder(context: android.content.Context, folder: File) {
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
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", folder)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

private fun openFileDefault(context: android.content.Context, filePath: String) {
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

private fun openFileWithChooser(context: android.content.Context, filePath: String) {
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

private fun shareFile(context: android.content.Context, filePath: String) {
    val file = File(filePath)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(intent, "分享到").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}

private fun previewStretchScaleX(resolution: String): Float {
    val ratio = parseAspectRatio(resolution) ?: return 1f
    return if (ratio < 1f) (1f / ratio).coerceIn(1f, 3f) else 1f
}

private fun previewStretchScaleY(resolution: String): Float {
    val ratio = parseAspectRatio(resolution) ?: return 1f
    return if (ratio > 1f) ratio.coerceIn(1f, 3f) else 1f
}

private fun parseAspectRatio(resolution: String): Float? {
    val parts = resolution.split("x")
    if (parts.size != 2) return null
    val w = parts[0].trim().toFloatOrNull() ?: return null
    val h = parts[1].trim().toFloatOrNull() ?: return null
    if (w <= 0f || h <= 0f) return null
    return w / h
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

private fun extractTightRgbaPlane(
    buffer: ByteBuffer,
    width: Int,
    height: Int,
    rowStride: Int
): ByteArray {
    val src = buffer.duplicate()
    src.rewind()
    val bpp = 4
    val tightRowBytes = width * bpp
    val out = ByteArray(tightRowBytes * height)

    if (rowStride == tightRowBytes) {
        src.get(out, 0, out.size)
        return out
    }

    var dstOffset = 0
    var srcOffset = 0
    for (row in 0 until height) {
        src.position(srcOffset)
        src.get(out, dstOffset, tightRowBytes)
        dstOffset += tightRowBytes
        srcOffset += rowStride
    }
    return out
}

private fun saveRgbaDebugFrame(
    debugDir: File,
    rgba: ByteArray,
    width: Int,
    height: Int
): String? {
    return try {
        if (!debugDir.exists()) debugDir.mkdirs()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        var src = 0
        var dst = 0
        while (dst < pixels.size && src + 3 < rgba.size) {
            val r = rgba[src].toInt() and 0xFF
            val g = rgba[src + 1].toInt() and 0xFF
            val b = rgba[src + 2].toInt() and 0xFF
            val a = rgba[src + 3].toInt() and 0xFF
            pixels[dst++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            src += 4
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val file = File(debugDir, "preprocessed_${System.currentTimeMillis()}_${width}x$height.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        file.absolutePath
    } catch (_: Exception) {
        null
    }
}


