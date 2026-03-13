package com.yuliwen.filetran

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.TagLostException
import android.nfc.cardemulation.CardEmulation
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File

private const val HCE_AID = "F046494C455452414E31"

private data class UiFileMeta(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long
)

private data class ReceivedOpenPrompt(
    val file: File,
    val mimeType: String
)

@Composable
fun NfcTransferScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val historyManager = remember { DownloadHistoryManager(context) }
    val adapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    val busy = remember { AtomicBoolean(false) }

    var tab by remember { mutableIntStateOf(0) } // 0 send, 1 receive
    var selectedFile by remember { mutableStateOf<UiFileMeta?>(null) }
    var senderSession by remember { mutableStateOf<HceSessionInfo?>(null) }
    var sendGuardActive by remember { mutableStateOf(false) }
    var highSpeedMode by remember { mutableStateOf(false) }
    var chunkSizeInput by remember { mutableStateOf("220") }

    var receiving by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("空闲") }
    var error by remember { mutableStateOf<String?>(null) }
    var receivedPath by remember { mutableStateOf<String?>(null) }
    var receivedPrompt by remember { mutableStateOf<ReceivedOpenPrompt?>(null) }

    var routeDiag by remember { mutableStateOf<String?>(null) }
    var showDebugTools by remember { mutableStateOf(false) }
    var nfcSenseState by remember { mutableStateOf("未感应") }
    var nfcSenseCount by remember { mutableIntStateOf(0) }
    var nfcLastSenseTime by remember { mutableStateOf<String?>(null) }
    var nfcLastTagId by remember { mutableStateOf<String?>(null) }

    var receiveStartMs by remember { mutableStateOf<Long?>(null) }
    var receiveBytesDone by remember { mutableIntStateOf(0) }
    var receiveTotalBytes by remember { mutableIntStateOf(0) }
    var receiveSpeedBps by remember { mutableIntStateOf(0) }
    var receiveResumeState by remember { mutableStateOf<HceResumeState?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        selectedFile = queryMeta(context, uri)
        error = null
    }

    DisposableEffect(view, receiving, senderSession) {
        val prev = view.keepScreenOn
        view.keepScreenOn = receiving || senderSession != null
        onDispose { view.keepScreenOn = prev }
    }

    DisposableEffect(Unit) {
        onDispose { HceTransferStore.clear() }
    }

    DisposableEffect(activity, adapter, receiving) {
        if (activity == null || adapter == null || !adapter.isEnabled || !receiving) {
            onDispose { }
        } else {
            val callback = NfcAdapter.ReaderCallback { tag ->
                scope.launch {
                    nfcSenseCount += 1
                    nfcSenseState = "已感应到 Tag"
                    nfcLastSenseTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    nfcLastTagId = tag.id?.joinToString("") { b -> "%02X".format(b) } ?: "unknown"
                }
                if (!busy.compareAndSet(false, true)) return@ReaderCallback
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val isoDep = IsoDep.get(tag) ?: error("目标设备不支持 IsoDep/HCE")
                            scope.launch { nfcSenseState = "已建立 IsoDep，开始握手" }
                            HceApduProtocol.receiveFileFromIsoDep(
                                context = context,
                                isoDep = isoDep,
                                verifyIntegrity = false,
                                resumeState = receiveResumeState,
                                onCheckpoint = { checkpoint -> receiveResumeState = checkpoint }
                            ) { done, total, doneBytes, totalBytes ->
                                if (done == total || done % 8 == 0) {
                                    scope.launch {
                                        progress = if (total <= 0) 0f else done.toFloat() / total.toFloat()
                                        status = "接收中：$done / $total"
                                        nfcSenseState = "传输中"
                                        receiveBytesDone = doneBytes
                                        receiveTotalBytes = totalBytes
                                        val start = receiveStartMs ?: System.currentTimeMillis().also { receiveStartMs = it }
                                        val elapsedMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                                        receiveSpeedBps = ((doneBytes * 1000L) / elapsedMs).toInt()
                                    }
                                }
                            }
                        }
                    }
                    result.onSuccess { received ->
                        status = "接收成功：${received.file.name}"
                        nfcSenseState = "接收成功"
                        progress = 1f
                        receivedPath = received.file.absolutePath
                        error = null
                        runCatching {
                            historyManager.addRecord(
                                DownloadRecord(
                                    fileName = received.file.name,
                                    filePath = received.file.absolutePath,
                                    fileSize = received.file.length(),
                                    sourceUrl = "nfc-hce://direct"
                                )
                            )
                        }
                        receivedPrompt = ReceivedOpenPrompt(
                            file = received.file,
                            mimeType = received.mimeType
                        )
                        receiveResumeState = null
                        receiving = false
                    }.onFailure { e ->
                        if (e is TagLostException) {
                            error = "接收中断：NFC 链路断开（TagLost）。请两机背面紧贴并保持 2-5 秒不移动后重试。"
                            status = "接收中断，等待重新贴合"
                            nfcSenseState = "已感应但链路中断"
                        } else {
                            error = "接收失败：${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}"
                            status = "接收失败"
                            nfcSenseState = "已感应但握手失败"
                        }
                    }
                    busy.set(false)
                }
            }
            adapter.enableReaderMode(
                activity,
                callback,
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
            )
            onDispose { runCatching { adapter.disableReaderMode(activity) } }
        }
    }

    DisposableEffect(activity, adapter, senderSession) {
        val canPrefer = activity != null && adapter != null && senderSession != null
        if (!canPrefer) {
            onDispose { }
        } else {
            val emulation = runCatching { CardEmulation.getInstance(adapter) }.getOrNull()
            val component = ComponentName(context, HceFileTransferService::class.java)
            val setOk = runCatching {
                emulation?.setPreferredService(activity!!, component) == true
            }.getOrDefault(false)
            val regOtherOk = runCatching {
                emulation?.registerAidsForService(component, CardEmulation.CATEGORY_OTHER, listOf(HCE_AID)) == true
            }.getOrDefault(false)
            val regPaymentOk = runCatching {
                emulation?.registerAidsForService(component, CardEmulation.CATEGORY_PAYMENT, listOf(HCE_AID)) == true
            }.getOrDefault(false)
            routeDiag = "首选路由=$setOk, 注册OTHER=$regOtherOk, 注册PAYMENT=$regPaymentOk"
            if (!setOk) {
                error = "发送端未获得首选 HCE 路由，可能导致接收端连接失败（可先重试）"
            }
            onDispose { runCatching { emulation?.unsetPreferredService(activity!!) } }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("NFC 直传（HCE/APDU）", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            text = "支持手机对手机真直传。发送端可自定义分块大小，接收端显示实时速度。",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
        )
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回传输实验室") }

        if (adapter == null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("此设备不支持 NFC", modifier = Modifier.padding(12.dp))
            }
            return@Column
        }
        if (!adapter.isEnabled) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("NFC 未开启，请先在系统设置打开 NFC", modifier = Modifier.padding(12.dp))
            }
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("发送端") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("接收端") })
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("使用说明", fontWeight = FontWeight.SemiBold)
                Text("1. 两台手机都打开 NFC，并保持屏幕常亮。")
                Text("2. 发送端：选择文件 ->（可选）调整分块 -> 启动 HCE 发送。")
                Text("3. 接收端：点击开始接收后，将两机背面 NFC 区域贴紧，尽量不移动。")
                Text("4. 若连接失败：先在发送端重启发送会话，再重试。")
                Text("5. 发送端请优先使用 HCE 钱包/卡模拟路径（避免 eSE 抢路由）。")
                Text("6. 大文件建议先用标准模式，再逐步切到高速模式。")
            }
        }

        OutlinedButton(
            onClick = { showDebugTools = !showDebugTools },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (showDebugTools) "隐藏调试工具" else "显示调试工具")
        }

        if (showDebugTools) {
            OutlinedButton(
                onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_NFC_PAYMENT_SETTINGS)) }
                        .onFailure { error = "无法打开默认付款设置：${it.message}" }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("打开默认付款设置") }

            OutlinedButton(
                onClick = {
                    runCatching {
                        val intent = Intent(CardEmulation.ACTION_CHANGE_DEFAULT).apply {
                            putExtra(CardEmulation.EXTRA_SERVICE_COMPONENT, ComponentName(context, HceFileTransferService::class.java))
                            putExtra(CardEmulation.EXTRA_CATEGORY, CardEmulation.CATEGORY_PAYMENT)
                        }
                        context.startActivity(intent)
                    }.onFailure { error = "无法请求默认付款应用：${it.message}" }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("请求设为默认付款应用") }

            OutlinedButton(
                onClick = {
                    val act = activity
                    val ad = adapter
                    if (act == null || ad == null) {
                        routeDiag = "当前无法执行路由自检"
                    } else {
                        val emu = runCatching { CardEmulation.getInstance(ad) }.getOrNull()
                        val comp = ComponentName(context, HceFileTransferService::class.java)
                        val isDefaultOther = runCatching { emu?.isDefaultServiceForCategory(comp, CardEmulation.CATEGORY_OTHER) == true }.getOrDefault(false)
                        val isDefaultPayment = runCatching { emu?.isDefaultServiceForCategory(comp, CardEmulation.CATEGORY_PAYMENT) == true }.getOrDefault(false)
                        val isDefaultAid = runCatching { emu?.isDefaultServiceForAid(comp, HCE_AID) == true }.getOrDefault(false)
                        routeDiag = "OTHER默认=$isDefaultOther, PAYMENT默认=$isDefaultPayment, AID路由=$isDefaultAid"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("路由自检") }

            routeDiag?.let {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text("路由状态：$it", modifier = Modifier.padding(12.dp))
                }
            }
        }

        if (tab == 0) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("发送模式")
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(if (highSpeedMode) "高速模式（实验）" else "标准模式（稳定）")
                        Switch(
                            checked = highSpeedMode,
                            onCheckedChange = {
                                highSpeedMode = it
                                chunkSizeInput = if (it) "768" else "220"
                            }
                        )
                    }
                }
            }
            OutlinedButton(onClick = { picker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text("选择文件") }
            OutlinedTextField(
                value = chunkSizeInput,
                onValueChange = { chunkSizeInput = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text(if (highSpeedMode) "分块大小（>=64字节）" else "分块大小（建议 64-240）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            selectedFile?.let { file ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("文件名：${file.name}")
                        Text("大小：${file.size} 字节")
                        Text("类型：${file.mimeType}")
                    }
                }
                Button(
                    onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val chunk = chunkSizeInput.toIntOrNull() ?: 220
                                    val finalChunk = if (highSpeedMode) chunk.coerceAtLeast(64) else chunk.coerceIn(64, 240)
                                    HceTransferStore.prepare(context, file.uri, finalChunk)
                                }
                            }
                            result.onSuccess { session ->
                                senderSession = session
                                sendGuardActive = true
                                status = "发送就绪，分块数 ${session.chunkCount}，请让接收端贴近本机"
                                error = null
                            }.onFailure { e ->
                                error = "启动发送失败：${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}"
                                status = "发送未就绪"
                                sendGuardActive = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = adapter.isEnabled
                ) { Text("启动 HCE 发送") }
            }

            OutlinedButton(
                onClick = {
                    HceTransferStore.clear()
                    senderSession = null
                    sendGuardActive = false
                    status = "已停止发送"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("停止发送") }

            senderSession?.let { s ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("当前会话：${s.sessionId}")
                        Text("总大小：${s.totalBytes} 字节")
                        Text("分块大小：${s.chunkSize} 字节")
                        Text("分块数量：${s.chunkCount}")
                    }
                }
            }
        } else {
            Button(
                onClick = {
                    receiving = true
                    val hasResume = receiveResumeState != null
                    if (!hasResume) {
                        progress = 0f
                        receivedPath = null
                        receiveStartMs = null
                        receiveBytesDone = 0
                        receiveTotalBytes = 0
                        receiveSpeedBps = 0
                    }
                    status = if (hasResume) {
                        "继续接收已启动，请将接收端贴近发送端背面"
                    } else {
                        "接收已启动，请将接收端贴近发送端背面"
                    }
                    nfcSenseState = "等待感应"
                    nfcSenseCount = 0
                    nfcLastSenseTime = null
                    nfcLastTagId = null
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = adapter.isEnabled && !receiving
            ) {
                Text(
                    if (receiving) "接收中..."
                    else if (receiveResumeState != null) "继续接收"
                    else "开始接收"
                )
            }

            OutlinedButton(
                onClick = {
                    receiving = false
                    status = "已停止接收"
                    nfcSenseState = "未感应"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("停止接收") }

            receiveResumeState?.let { draft ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("检测到可续传会话")
                        Text("文件：${draft.fileName}")
                        Text("进度：${draft.nextChunkIndex}/${draft.chunkCount}")
                        OutlinedButton(
                            onClick = {
                                receiveResumeState = null
                                progress = 0f
                                receiveBytesDone = 0
                                receiveTotalBytes = 0
                                receiveSpeedBps = 0
                                status = "已放弃续传"
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("放弃续传并重来")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("感应状态：$nfcSenseState")
                    Text("感应次数：$nfcSenseCount")
                    Text("最近时间：${nfcLastSenseTime ?: "-"}")
                    Text("最近Tag ID：${nfcLastTagId ?: "-"}")
                }
            }

            if (receiving || progress > 0f) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("进度：${(progress * 100).toInt()}%")
                        Text("实时速度：${formatSpeed(receiveSpeedBps.toLong())}")
                        Text("已接收：${formatBytes(receiveBytesDone.toLong())} / ${formatBytes(receiveTotalBytes.toLong())}")
                    }
                }
            }

            receivedPath?.let {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("已接收文件")
                        Text(it)
                    }
                }
            }
        }

        error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, modifier = Modifier.padding(12.dp))
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Text("状态：$status", modifier = Modifier.padding(12.dp))
        }

        Spacer(modifier = Modifier.height(20.dp))
    }

    receivedPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { receivedPrompt = null },
            title = { Text("接收完成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("文件名：${prompt.file.name}")
                    Text("大小：${formatBytes(prompt.file.length())}")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching {
                            openFileWithDefaultApp(context, prompt.file, prompt.mimeType)
                        }.onFailure { error = "无法使用默认应用打开：${it.message}" }
                        receivedPrompt = null
                    }
                ) { Text("默认打开") }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    TextButton(
                        onClick = {
                            runCatching {
                                openFileWithChooser(context, prompt.file, prompt.mimeType)
                            }.onFailure { error = "无法弹出打开方式：${it.message}" }
                            receivedPrompt = null
                        }
                    ) { Text("其他方式") }
                    TextButton(onClick = { receivedPrompt = null }) { Text("稍后") }
                }
            }
        )
    }

    if (sendGuardActive) {
        senderSession?.let { session ->
            AlertDialog(
                onDismissRequest = { },
                title = { Text("发送保护中") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("已进入防误触状态。")
                        Text("文件：${session.fileName}")
                        Text("大小：${formatBytes(session.totalBytes.toLong())}")
                        Text("分块：${session.chunkSize} 字节")
                        Text("请保持本页前台并等待接收端完成。")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            HceTransferStore.clear()
                            senderSession = null
                            sendGuardActive = false
                            status = "已停止发送"
                        }
                    ) { Text("停止发送") }
                }
            )
        }
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    val bps = bytesPerSec.coerceAtLeast(0)
    return when {
        bps < 1024 -> "$bps B/s"
        bps < 1024 * 1024 -> "${bps / 1024} KB/s"
        else -> "${bps / (1024 * 1024)} MB/s"
    }
}

private fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0)
    return when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", b / 1024.0)
        else -> String.format(Locale.getDefault(), "%.2f MB", b / (1024.0 * 1024.0))
    }
}

private fun openFileWithDefaultApp(context: Context, file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun openFileWithChooser(context: Context, file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(intent, "选择打开方式").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

private fun queryMeta(context: Context, uri: Uri): UiFileMeta {
    var name = "hce_file_${System.currentTimeMillis()}"
    var size = -1L
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idxName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val idxSize = c.getColumnIndex(OpenableColumns.SIZE)
        if (c.moveToFirst()) {
            if (idxName >= 0) name = c.getString(idxName) ?: name
            if (idxSize >= 0) size = c.getLong(idxSize)
        }
    }
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    return UiFileMeta(uri, name, mime, size)
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
