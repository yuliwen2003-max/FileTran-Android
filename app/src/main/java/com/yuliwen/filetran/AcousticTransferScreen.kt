package com.yuliwen.filetran

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

@Composable
fun AcousticTransferScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val historyManager = remember { DownloadHistoryManager(context) }
    var tab by remember { mutableIntStateOf(0) } // 0 send, 1 receive

    var sendSourceUri by remember { mutableStateOf<Uri?>(null) }
    var sendInfo by remember { mutableStateOf<AcousticFileTransfer.EncodedAudio?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var isEncoding by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackPositionMs by remember { mutableIntStateOf(0) }
    var playbackDurationMs by remember { mutableIntStateOf(0) }
    var playbackJob by remember { mutableStateOf<Job?>(null) }
    var sendTxStatus by remember { mutableStateOf("空闲") }
    var sendMeta by remember { mutableStateOf<AcousticFileTransfer.FileMeta?>(null) }
    var sendRetransmitMode by remember { mutableStateOf(false) }
    var sendRetransmitSessionId by remember { mutableStateOf("") }
    var sendRetransmitRepeatText by remember { mutableStateOf("6") }
    var sendSelectedBlocks by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var sendSelectedBlocksText by remember { mutableStateOf("") }

    var receiveInfo by remember { mutableStateOf<AcousticFileTransfer.DecodedFile?>(null) }
    var receiveError by remember { mutableStateOf<String?>(null) }
    var isDecoding by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var recorder by remember { mutableStateOf<AudioRecord?>(null) }
    var recorderJob by remember { mutableStateOf<Job?>(null) }
    var progressJob by remember { mutableStateOf<Job?>(null) }
    var rawPcmFile by remember { mutableStateOf<File?>(null) }
    var rawOut by remember { mutableStateOf<FileOutputStream?>(null) }
    var recordedBytes by remember { mutableStateOf(0L) }
    var liveProgress by remember { mutableStateOf<AcousticFileTransfer.ReceiveProgress?>(null) }
    var receiveRetransmitMode by remember { mutableStateOf(false) }
    var receiveSuggestedMissing by remember { mutableStateOf<List<Int>>(emptyList()) }
    var receiveSuggestedSessionId by remember { mutableStateOf<Int?>(null) }
    var receiveLockedSessionId by remember { mutableStateOf<Int?>(null) }
    var receiveLatchedBlocks by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showHelpDialog by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            sendSourceUri = uri
            sendInfo = null
            sendError = null
            sendMeta = null
            sendSelectedBlocks = emptySet()
            sendSelectedBlocksText = ""
            scope.launch {
                val meta = withContext(Dispatchers.IO) {
                    runCatching { AcousticFileTransfer.inspectFileUri(context, uri) }.getOrNull()
                }
                sendMeta = meta
            }
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMicPermission = granted
        if (!granted) {
            receiveError = "未授予麦克风权限。"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            playbackJob?.cancel()
            player?.release()
            runCatching { recorder?.stop() }
            recorder?.release()
            recorder = null
            recorderJob?.cancel()
            progressJob?.cancel()
            runCatching { rawOut?.close() }
        }
    }

    val keepScreenOn = isEncoding || isPlaying || isRecording || isDecoding
    DisposableEffect(view, keepScreenOn) {
        val prev = view.keepScreenOn
        if (keepScreenOn) view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = prev
        }
    }

    fun stopAndDecode() {
        if (!isRecording || isDecoding) return
        isRecording = false
        val ar = recorder
        recorder = null
        runCatching { ar?.stop() }
        ar?.release()
        recorderJob?.cancel()
        recorderJob = null
        progressJob?.cancel()
        progressJob = null

        val out = rawOut
        rawOut = null
        runCatching { out?.flush() }
        runCatching { out?.close() }

        val raw = rawPcmFile
        if (raw == null || !raw.exists() || raw.length() <= 0L) {
            receiveError = "没有录音数据。"
            return
        }
        recordedBytes = raw.length()
        isDecoding = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val wav = File(raw.parentFile, raw.nameWithoutExtension + ".wav")
                    AcousticFileTransfer.wrapRawPcm16ToWav(raw, wav)
                    AcousticFileTransfer.decodeWaveFileToFile(context, wav)
                }
            }
            isDecoding = false
            result.onSuccess {
                receiveInfo = it
                runCatching {
                    historyManager.addRecord(
                        DownloadRecord(
                            fileName = it.fileName,
                            filePath = it.file.absolutePath,
                            fileSize = it.fileSize.toLong(),
                            sourceUrl = "acoustic://session/${liveProgress?.sessionId ?: "unknown"}"
                        )
                    )
                }
                receiveRetransmitMode = false
                receiveSuggestedMissing = emptyList()
                receiveLockedSessionId = null
                receiveLatchedBlocks = emptySet()
            }.onFailure {
                    receiveError = "解码失败: ${it.message}"
                    liveProgress?.let { p ->
                        receiveSuggestedMissing = p.missingDataIndices
                        receiveSuggestedSessionId = p.sessionId
                        if (p.missingDataIndices.isNotEmpty()) receiveRetransmitMode = true
                    }
                }
        }
    }

    fun tryAutoDecodeWithoutStopping() {
        if (!isRecording || isDecoding) return
        val raw = rawPcmFile ?: return
        if (!raw.exists() || raw.length() <= 0L) return
        isDecoding = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    runCatching { rawOut?.flush() }
                    val snapshotPcm = File(raw.parentFile, "snapshot_${System.currentTimeMillis()}.pcm")
                    raw.inputStream().use { input ->
                        FileOutputStream(snapshotPcm).use { output -> input.copyTo(output) }
                    }
                    val wav = File(raw.parentFile, snapshotPcm.nameWithoutExtension + ".wav")
                    AcousticFileTransfer.wrapRawPcm16ToWav(snapshotPcm, wav)
                    AcousticFileTransfer.decodeWaveFileToFile(context, wav)
                }
            }
            isDecoding = false
            result.onSuccess { info ->
                receiveInfo = info
                receiveError = null
                runCatching {
                    historyManager.addRecord(
                        DownloadRecord(
                            fileName = info.fileName,
                            filePath = info.file.absolutePath,
                            fileSize = info.fileSize.toLong(),
                            sourceUrl = "acoustic://session/${liveProgress?.sessionId ?: "unknown"}"
                        )
                    )
                }
                // Stop only after decode succeeds, so retransmit rounds can be recorded continuously.
                isRecording = false
                val ar = recorder
                recorder = null
                runCatching { ar?.stop() }
                ar?.release()
                recorderJob?.cancel()
                recorderJob = null
                progressJob?.cancel()
                progressJob = null
                val out = rawOut
                rawOut = null
                runCatching { out?.flush() }
                runCatching { out?.close() }
                recordedBytes = raw.length()
                receiveRetransmitMode = false
                receiveSuggestedMissing = emptyList()
            }.onFailure {
                // Keep recording and wait for next retransmit blocks.
                receiveError = "自动解码尚未成功，继续录音中... ${it.message}"
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
            Text("声波文件传输", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { showHelpDialog = true }) {
                Icon(Icons.Default.HelpOutline, contentDescription = "帮助")
            }
        }

        if (showHelpDialog) {
            AlertDialog(
                onDismissRequest = { showHelpDialog = false },
                confirmButton = {
                    TextButton(onClick = { showHelpDialog = false }) { Text("知道了") }
                },
                title = { Text("声波传输使用说明") },
                text = {
                    Text(
                        "功能简介：\n" +
                            "通过扬声器和麦克风传输小文件，适用于无网、无蓝牙或临时离线场景。\n\n" +
                            "基本用法：\n" +
                            "1. 发送端选择文件并生成音频，点击播放。\n" +
                            "2. 接收端开始录音，系统会实时识别区块并显示进度。\n" +
                            "3. 区块齐全后会自动解码并生成文件。\n\n" +
                            "优势：\n" +
                            "- 不依赖网络和配对。\n" +
                            "- 支持纠错与冗余，抗丢帧能力更强。\n" +
                            "- 支持续录音与多轮补收。\n\n" +
                            "重传用法：\n" +
                            "1. 接收端查看“发送端填写会话ID”和“需重传区块”。\n" +
                            "2. 发送端切到“重传”，填写目标会话ID。\n" +
                            "3. 勾选缺失区块（或粘贴区块列表）并生成重传音频。\n" +
                            "4. 接收端保持/继续录音，直到自动解码成功。"
                    )
                }
            )
        }

        Card(colors = CardDefaults.cardColors()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("协议：BFSK + Hamming(12,8) + XOR 奇偶校验 + CRC32")
                Text("建议发送小文件（<=8KB），环境尽量安静，发送端音量 70%-85%。")
                Text("文件越大冗余越高，生成音频时长会增加。")
            }
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("发送端") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("接收端") })
        }

        if (tab == 0) {
            Card {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { filePicker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                        Text("选择要发送的文件（<=8KB）")
                    }
                    Text("来源: ${sendSourceUri?.toString() ?: "未选择"}", fontSize = 12.sp)
                    sendMeta?.let { meta ->
                        Text("区块数: ${meta.totalDataFrames}（${meta.fileSize} 字节）", fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { sendRetransmitMode = false }, modifier = Modifier.weight(1f)) {
                            Text("完整发送")
                        }
                        OutlinedButton(onClick = {
                            sendRetransmitMode = true
                            if (sendRetransmitSessionId.isBlank()) {
                                sendRetransmitSessionId =
                                    (receiveSuggestedSessionId ?: liveProgress?.sessionId ?: sendInfo?.sessionId)?.toString().orEmpty()
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Text("重传")
                        }
                    }
                    if (sendRetransmitMode) {
                        Text("跨设备重传：填写会话ID，并按接收端缺块信息选择区块。", fontSize = 12.sp)
                        OutlinedTextField(
                            value = sendRetransmitSessionId,
                            onValueChange = { sendRetransmitSessionId = it.filter { c -> c.isDigit() } },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("目标会话ID") }
                        )
                        OutlinedTextField(
                            value = sendRetransmitRepeatText,
                            onValueChange = { sendRetransmitRepeatText = it.filter { c -> c.isDigit() } },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("重传轮次（2-12）") }
                        )
                        OutlinedTextField(
                            value = sendSelectedBlocksText,
                            onValueChange = { sendSelectedBlocksText = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("区块列表（逗号分隔）") },
                            placeholder = { Text("例如 0,1,5,9") }
                        )
                        val total = sendMeta?.totalDataFrames ?: 0
                        if (total > 0) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        sendSelectedBlocks = (0 until total).toSet()
                                        sendSelectedBlocksText = sendSelectedBlocks.sorted().joinToString(",")
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("全选") }
                                OutlinedButton(
                                    onClick = {
                                        sendSelectedBlocks = emptySet()
                                        sendSelectedBlocksText = ""
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("清空") }
                                OutlinedButton(
                                    onClick = {
                                        val parsed = sendSelectedBlocksText
                                            .split(",", " ", "\n", "\t")
                                            .mapNotNull { it.trim().toIntOrNull() }
                                            .filter { it in 0 until total }
                                            .toSet()
                                        sendSelectedBlocks = parsed
                                        sendSelectedBlocksText = parsed.sorted().joinToString(",")
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("应用列表") }
                            }
                            Text("已选区块: ${sendSelectedBlocks.size}/$total", fontSize = 12.sp)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                for (i in 0 until total) {
                                    val picked = sendSelectedBlocks.contains(i)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row {
                                            Checkbox(
                                                checked = picked,
                                                onCheckedChange = { checked ->
                                                    sendSelectedBlocks = if (checked) sendSelectedBlocks + i else sendSelectedBlocks - i
                                                    sendSelectedBlocksText = sendSelectedBlocks.sorted().joinToString(",")
                                                }
                                            )
                                            Text("区块 $i", modifier = Modifier.padding(top = 12.dp))
                                        }
                                        Text(if (picked) "已选择" else "", modifier = Modifier.padding(top = 14.dp), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    Button(
                        onClick = {
                            val src = sendSourceUri ?: return@Button
                            isEncoding = true
                            sendError = null
                            sendInfo = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        if (sendRetransmitMode) {
                                            val sid = sendRetransmitSessionId.toIntOrNull()
                                                ?: throw IllegalArgumentException("目标会话ID无效")
                                            val selected = sendSelectedBlocks
                                            if (selected.isEmpty()) throw IllegalArgumentException("请至少选择一个区块")
                                            val repeat = sendRetransmitRepeatText.toIntOrNull()?.coerceIn(2, 12) ?: 6
                                            AcousticFileTransfer.encodeFileUriToWave(
                                                context = context,
                                                fileUri = src,
                                                sessionIdOverride = sid,
                                                retransmitIndices = selected,
                                                retransmitRepeat = repeat
                                            )
                                        } else {
                                            AcousticFileTransfer.encodeFileUriToWave(context = context, fileUri = src)
                                        }
                                    }
                                }
                                isEncoding = false
                                result.onSuccess { sendInfo = it }
                                    .onFailure { sendError = "编码失败: ${it.message}" }
                                playbackPositionMs = 0
                                playbackDurationMs = ((sendInfo?.durationSec ?: 0.0) * 1000.0).roundToInt()
                                sendTxStatus = "已就绪"
                            }
                        },
                        enabled = !isEncoding && sendSourceUri != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isEncoding) "编码中..." else "生成音频")
                    }

                    sendInfo?.let { info ->
                        Text("文件: ${info.inputName}（${info.inputSize} 字节）")
                        Text("会话: ${info.sessionId}  总区块=${info.totalDataFrames}")
                        Text("帧统计: 数据=${info.dataFrames}，奇偶=${info.parityFrames}，模式=${if (info.isRetransmit) "重传" else "完整"}")
                        Text("时长: ${"%.1f".format(info.durationSec)} 秒")

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    runCatching {
                                        playbackJob?.cancel()
                                        player?.release()
                                        val p = MediaPlayer().apply {
                                            setDataSource(context, info.wavUri)
                                            setOnCompletionListener { mp ->
                                                isPlaying = false
                                                playbackPositionMs = playbackDurationMs
                                                sendTxStatus = "发送完成"
                                                mp.release()
                                                if (player === mp) player = null
                                            }
                                            prepare()
                                            playbackDurationMs = duration.coerceAtLeast(0)
                                            playbackPositionMs = 0
                                            start()
                                        }
                                        player = p
                                        isPlaying = true
                                        playbackJob = scope.launch {
                                            while (isPlaying && player === p) {
                                                playbackPositionMs = runCatching { p.currentPosition }.getOrDefault(0)
                                                val infoNow = sendInfo
                                                if (infoNow != null) {
                                                    val units = infoNow.txUnits
                                                    if (units.isNotEmpty() && playbackDurationMs > 0) {
                                                        val idx = ((playbackPositionMs.toDouble() / playbackDurationMs.toDouble()) * units.size.toDouble())
                                                            .toInt()
                                                            .coerceIn(0, units.size - 1)
                                                        val u = units[idx]
                                                        sendTxStatus = when (u.type) {
                                                            1 -> "发送 ${idx + 1}/${units.size}: 数据区块 ${u.index + 1}/${infoNow.totalDataFrames}（轮次 ${u.pass + 1}）"
                                                            2 -> "发送 ${idx + 1}/${units.size}: 奇偶组 ${u.index - infoNow.totalDataFrames + 1}（轮次 ${u.pass + 1}）"
                                                            else -> "发送 ${idx + 1}/${units.size}: 帧类型 ${u.type}（轮次 ${u.pass + 1}）"
                                                        }
                                                    }
                                                }
                                                delay(200)
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (isPlaying) "重新播放" else "播放") }

                            OutlinedButton(
                                onClick = {
                                    playbackJob?.cancel()
                                    playbackJob = null
                                    player?.let {
                                        runCatching { it.stop() }
                                        it.release()
                                    }
                                    player = null
                                    isPlaying = false
                                    playbackPositionMs = 0
                                    sendTxStatus = "已停止"
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("停止") }
                        }
                        Text("发送状态: $sendTxStatus", fontSize = 12.sp)
                        if (playbackDurationMs > 0) {
                            Text(
                                "播放进度: ${"%.1f".format(playbackPositionMs / 1000.0)}秒 / ${"%.1f".format(playbackDurationMs / 1000.0)}秒",
                                fontSize = 12.sp
                            )
                            LinearProgressIndicator(
                                progress = {
                                    val v = if (playbackDurationMs <= 0) 0f else playbackPositionMs.toFloat() / playbackDurationMs.toFloat()
                                    if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                val chooser = Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "audio/wav"
                                        putExtra(Intent.EXTRA_STREAM, info.wavUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    },
                                    "分享声波 WAV"
                                )
                                runCatching { context.startActivity(chooser) }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("分享 WAV") }
                    }
                    sendError?.let { Text(it) }
                }
            }
        } else {
            Card {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("接收端直接使用麦克风，请让两台设备靠近并保持安静。", fontSize = 12.sp)
                    Text("已录制: ${recordedBytes} 字节", fontSize = 12.sp)
                    liveProgress?.let { p ->
                        Text(
                            "进度: ${(p.progressFraction * 100f).roundToInt()}%  " +
                                "(数据 ${p.receivedDataFrames}/${if (p.totalDataFrames > 0) p.totalDataFrames else "?"}, " +
                                "可恢复 ${p.recoverableFrames}, 缺失 ${p.missingDataIndices.size}, 错误 ${p.frameErrors})",
                            fontSize = 12.sp
                        )
                        if (p.missingDataIndices.isNotEmpty()) {
                            val preview = p.missingDataIndices.take(12).joinToString(",")
                            val suffix = if (p.missingDataIndices.size > 12) "..." else ""
                            Text("缺失区块: $preview$suffix", fontSize = 12.sp)
                        }
                        Text(
                            if (p.hasAllData) {
                                "已具备完整恢复条件，准备解码。"
                            } else {
                                "等待剩余数据帧/奇偶帧..."
                            },
                            fontSize = 12.sp
                        )
                        LinearProgressIndicator(
                            progress = {
                                val v = p.progressFraction
                                if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (receiveRetransmitMode) {
                        Text("重传模式：已启用", fontSize = 12.sp)
                    }
                    receiveSuggestedSessionId?.let { sid ->
                        Text("发送端填写会话ID: $sid", fontSize = 12.sp)
                    }
                    if (receiveSuggestedMissing.isNotEmpty()) {
                        Text("发送端需重传区块: ${receiveSuggestedMissing.joinToString(",")}", fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val p = liveProgress ?: return@OutlinedButton
                                if (p.sessionId == null) return@OutlinedButton
                                receiveRetransmitMode = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("启用重传模式")
                        }
                        OutlinedButton(
                            onClick = {
                                receiveRetransmitMode = false
                                liveProgress = null
                                receiveSuggestedMissing = emptyList()
                                receiveSuggestedSessionId = null
                                receiveLockedSessionId = null
                                receiveLatchedBlocks = emptySet()
                                rawPcmFile = null
                                recordedBytes = 0L
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("重置会话")
                        }
                    }

                    Button(
                        onClick = {
                            if (!hasMicPermission) {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                return@Button
                            }
                            if (isRecording) return@Button
                            receiveError = null
                            receiveInfo = null
                            val minBuffer = AudioRecord.getMinBufferSize(
                                44_100,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                            if (minBuffer <= 0) {
                                receiveError = "录音初始化失败。"
                                return@Button
                            }
                            val outDir = File(context.externalCacheDir ?: context.cacheDir, "acoustic_rx").apply { mkdirs() }
                            val appendMode = receiveRetransmitMode && rawPcmFile?.exists() == true
                            val rawFile = if (appendMode) {
                                rawPcmFile!!
                            } else {
                                liveProgress = null
                                File(outDir, "capture_${System.currentTimeMillis()}.pcm")
                            }
                            if (!appendMode) {
                                receiveLockedSessionId = null
                                receiveLatchedBlocks = emptySet()
                            }
                            val out = runCatching { FileOutputStream(rawFile, appendMode) }.getOrNull()
                            if (out == null) {
                                receiveError = "无法创建录音文件。"
                                return@Button
                            }
                            val ar = AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                44_100,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                maxOf(minBuffer * 2, 4096)
                            )
                            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                                out.close()
                                receiveError = "麦克风初始化失败。"
                                return@Button
                            }
                            runCatching { ar.startRecording() }.onFailure {
                                ar.release()
                                out.close()
                                receiveError = "开始录音失败: ${it.message}"
                                return@Button
                            }
                            rawPcmFile = rawFile
                            rawOut = out
                            recorder = ar
                            recordedBytes = rawFile.length()
                            isRecording = true
                            recorderJob = scope.launch(Dispatchers.IO) {
                                val buf = ShortArray(maxOf(minBuffer, 2048))
                                while (isRecording) {
                                    val n = ar.read(buf, 0, buf.size)
                                    if (n <= 0) continue
                                    val bytes = ByteArray(n * 2)
                                    var p = 0
                                    for (i in 0 until n) {
                                        val v = buf[i].toInt()
                                        bytes[p++] = (v and 0xFF).toByte()
                                        bytes[p++] = ((v ushr 8) and 0xFF).toByte()
                                    }
                                    out.write(bytes)
                                }
                            }
                            progressJob = scope.launch(Dispatchers.IO) {
                                while (isRecording) {
                                    delay(1200)
                                    val progress = runCatching {
                                        val f = rawPcmFile
                                        if (f != null) {
                                            AcousticFileTransfer.estimateProgressFromRawPcmFile(f, receiveLockedSessionId)
                                        } else {
                                            null
                                        }
                                    }.getOrNull()
                                    if (progress != null) {
                                        withContext(Dispatchers.Main) {
                                            if (receiveLockedSessionId == null && progress.sessionId != null) {
                                                receiveLockedSessionId = progress.sessionId
                                            }
                                            val locked = receiveLockedSessionId
                                            if (locked != null && progress.sessionId != locked) {
                                                return@withContext
                                            }
                                            receiveLatchedBlocks = receiveLatchedBlocks + progress.receivedDataIndices.toSet()
                                            val latched = receiveLatchedBlocks
                                            val stableMissing = progress.missingDataIndices.filterNot { latched.contains(it) }
                                            val stableReceivedCount = latched.size.coerceAtMost(progress.totalDataFrames)
                                            val stableHasAll = (stableReceivedCount + progress.recoverableFrames) >= progress.totalDataFrames &&
                                                progress.totalDataFrames > 0
                                            val stableProgress = progress.copy(
                                                receivedDataFrames = stableReceivedCount,
                                                receivedDataIndices = latched.toList().sorted(),
                                                missingDataIndices = stableMissing,
                                                hasAllData = stableHasAll
                                            )
                                            liveProgress = stableProgress
                                            receiveSuggestedMissing = stableProgress.missingDataIndices
                                            receiveSuggestedSessionId = stableProgress.sessionId
                                            if (stableProgress.isDecodeReady && isRecording && !isDecoding) {
                                                tryAutoDecodeWithoutStopping()
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isRecording && !isDecoding,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (receiveRetransmitMode) "继续录音（重传）" else "开始录音")
                    }

                    OutlinedButton(
                        onClick = {
                            stopAndDecode()
                        },
                        enabled = isRecording && !isDecoding,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isDecoding) "解码中..." else "停止并解码")
                    }

                    receiveInfo?.let { info ->
                        Text("恢复文件: ${info.fileName}")
                        Text("大小: ${info.fileSize} 字节")
                        Text("恢复组数: ${info.correctedGroups}，帧错误: ${info.frameErrors}")

                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "*/*"
                                                putExtra(Intent.EXTRA_STREAM, info.fileUri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            },
                                            "分享恢复文件"
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("分享文件") }
                    }
                    receiveError?.let { Text(it) }
                }
            }
        }
    }
}
