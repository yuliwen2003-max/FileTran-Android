package com.yuliwen.filetran

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val UDP_TRANSFER_QR_TYPE = "filetran_ipv6_udp_transfer_v1"
private const val UDP_PUNCH_TIMEOUT_MS = 5_000L
private const val UDP_CHUNK_SIZE = 1100
private const val UDP_SEND_WINDOW = 96
private const val UDP_RETX_MS = 80L
private const val UDP_IDLE_TIMEOUT_MS = 20_000L
private const val UDP_HELLO_READY_TIMEOUT_MS = 15_000L
private const val UDP_HELLO_DUP_SEND = 3
private const val UDP_HELLO_RECV_TIMEOUT_MS = 120
private const val UDP_CONTROL_FLOOD_TIMEOUT_MS = 30_000L
private const val UDP_CONTROL_SEND_STATE_UPDATE_MS = 300L
private const val UDP_META_WAIT_TIMEOUT_MS = 10_000L
private const val UDP_FIN_WAIT_AFTER_DATA_MS = 700L
private const val UDP_DEFAULT_THREADS = 8
private const val UDP_MAX_THREADS = 128
private const val UDP_READY_BURST_COUNT = 48
private const val UDP_READY_BURST_INTERVAL_MS = 40L
private const val UDP_READY_KEEPALIVE_INTERVAL_MS = 90L
private const val UDP_INITIAL_DATA_DUP_SEND = 2
private const val UDP_ACK_DUP_SEND = 2
private const val UDP_CHANNEL_DIAL_ATTEMPT_TIMEOUT_MS = 1_200L
private const val UDP_CHANNEL_DIAL_RETRY_MS = 150L
private const val UDP_THREAD_PKT_STAT_INTERVAL_MS = 1_000L
private const val UDP_LOG_TAG = "Ipv6UdpXfer"
private const val UDP_PROFILE_CLASSIC = "udp_classic"
private const val UDP_PROFILE_SPRINT = "udp_sprint_exp"
private const val UDP_PROFILE_TURBO = "udp_turbo_exp"
private const val UDP_HANDSHAKE_LOG_LIMIT = 320
private const val UDP_CHANNEL_PORT_MODE_PREDICT = "predict"
private const val UDP_CHANNEL_PORT_MODE_PER_STUN = "per_stun"
private const val UDP_CTRL_HELLO = "FILETRAN_IPV6_CTRL_HELLO"
private const val UDP_CTRL_ACK = "FILETRAN_IPV6_CTRL_ACK"
private const val UDP_QR_ROLE_SEND = "sender"
private const val UDP_QR_ROLE_RECV = "receiver"

private const val PKT_PUNCH: Byte = 1
private const val PKT_PUNCH_ACK: Byte = 2
private const val PKT_HELLO: Byte = 3
private const val PKT_READY: Byte = 4
private const val PKT_DATA: Byte = 5
private const val PKT_ACK: Byte = 6
private const val PKT_FIN: Byte = 7
private const val PKT_FIN_ACK: Byte = 8

private data class UdpPeer(
    val host: String,
    val port: Int
)

private data class UdpPeerParseResult(
    val peer: UdpPeer,
    val role: String? = null
)

private data class UdpTransferMeta(
    val sid: String,
    val name: String,
    val mime: String,
    val size: Long,
    val threads: Int,
    val profile: String,
    val batchId: String,
    val batchIndex: Int,
    val batchTotal: Int,
    val seqBase: Int,
    val packedZip: Boolean,
    val packedCount: Int
)

private data class UdpTransferProgress(
    val progress: Float,
    val stage: String,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val speedBytesPerSec: Long,
    val sendEfficiencyPercent: Float? = null,
    val threadSpeedsBytesPerSec: List<Long> = emptyList()
)

private data class UdpDataChannel(
    val socket: DatagramSocket,
    val endpoint: InetSocketAddress
)

private data class UdpSendFileItem(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long
)

private data class UdpReceiveResult(
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val batchId: String,
    val batchIndex: Int,
    val batchTotal: Int,
    val packedZip: Boolean,
    val packedCount: Int
)

private data class UdpSendRuntimeSession(
    val controlSocket: DatagramSocket,
    val controlEndpoint: InetSocketAddress,
    var channels: List<UdpDataChannel> = emptyList(),
    val requestedThreads: Int
)

private data class UdpReceiveRuntimeSession(
    val controlSocket: DatagramSocket,
    val controlEndpoint: InetSocketAddress,
    var channels: List<UdpDataChannel> = emptyList(),
    var agreedThreads: Int = 0
)

private val UDP_STAGE_REGEX = Regex("""\[[A-Z]+\]\[[^\]]*]\[([^\]]+)]""")
private val udpHandshakeLogListeners = CopyOnWriteArrayList<(String) -> Unit>()

private fun addUdpHandshakeLogListener(listener: (String) -> Unit): () -> Unit {
    udpHandshakeLogListeners += listener
    return { udpHandshakeLogListeners.remove(listener) }
}

private fun extractUdpStage(logLine: String): String? =
    UDP_STAGE_REGEX.find(logLine)?.groupValues?.getOrNull(1)

private fun mapUdpStageLabel(stage: String?): String {
    return when (stage) {
        "START" -> "初始化"
        "PUNCH_TRY" -> "打洞探测"
        "PUNCH_SEND_STATE" -> "发包状态"
        "PUNCH_OK" -> "打洞成功"
        "SID" -> "创建会话"
        "HELLO_BEGIN" -> "发送元信息"
        "HELLO_OK" -> "收到元信息"
        "THREAD_MAP_LOCAL", "THREAD_MAP_REMOTE" -> "线程端口映射"
        "THREAD_PUNCH_PRIME", "THREAD_PUNCH_PRIME_OK" -> "线程预打洞"
        "THREAD_PUNCH_TRY" -> "线程打洞探测"
        "THREAD_PUNCH_OK" -> "线程打洞成功"
        "THREAD_ACTIVE" -> "线程实际联通"
        "THREAD_PKT_STAT" -> "线程收发统计"
        "THREAD_REPUNCH_PRIME", "THREAD_REPUNCH_PRIME_OK", "THREAD_REPUNCH_PRIME_FAIL" -> "线程重连预打洞"
        "THREAD_STATE", "THREAD_REPUNCH_TRY", "THREAD_REPUNCH_OK", "THREAD_REPUNCH_FAIL" -> "线程重连状态"
        "CONTROL_REPUNCH_TRY", "CONTROL_REPUNCH_OK", "CONTROL_REPUNCH_FAIL" -> "控制通道重连"
        "HELLO_EXTEND", "META_EXTEND" -> "延长握手窗口"
        "READY_SENT", "READY_OK", "READY_RESEND" -> "READY确认"
        "READY_TIMEOUT", "META_TIMEOUT" -> "握手超时"
        "CHANNEL_LAZY_OPEN", "CHANNEL_READY", "CHANNEL_OPEN", "CHANNEL_REUSE", "CHANNEL_OPEN_TIMEOUT" -> "建立并行通道"
        "FIN_ACK_TIMEOUT" -> "收尾确认超时"
        "FIN_RX", "FIN_ACK_OK", "FINISH", "FINALIZE_DONE" -> "完成收尾"
        else -> stage ?: "等待开始"
    }
}

private fun buildUdpHandshakeProgress(logs: List<String>): String {
    fun has(vararg stages: String): Boolean {
        val targets = stages.toSet()
        return logs.any { extractUdpStage(it) in targets }
    }
    val s1 = if (has("PUNCH_OK")) "✓" else "…"
    val s2 = if (has("HELLO_BEGIN", "HELLO_OK")) "✓" else "…"
    val s3 = if (has("READY_OK", "READY_SENT", "READY_RESEND")) "✓" else "…"
    val s4 = if (has("CHANNEL_OPEN", "CHANNEL_READY", "CHANNEL_REUSE", "CHANNEL_LAZY_OPEN")) "✓" else "…"
    val s5 = if (has("FINISH", "FIN_ACK_OK", "FINALIZE_DONE")) "✓" else "…"
    return "握手进度：①打洞$s1 ②HELLO$s2 ③READY$s3 ④通道$s4 ⑤收尾$s5"
}

private fun buildUdpStallHint(logs: List<String>): String? {
    val timeoutStage = logs.asReversed().mapNotNull { extractUdpStage(it) }.firstOrNull { it.contains("TIMEOUT") }
    return when (timeoutStage) {
        "READY_TIMEOUT" -> "卡在 READY 回包阶段，建议双方同时重试或启用稳定性增强模式。"
        "META_TIMEOUT" -> "卡在元信息阶段，建议检查双方地址/端口并重试。"
        "CHANNEL_OPEN_TIMEOUT" -> "卡在并行通道建立阶段，建议降低线程数或继续重试。"
        "FIN_ACK_TIMEOUT" -> "尾确认丢包，通常数据已完成写入，可直接看接收端落地结果。"
        else -> null
    }
}

private data class UdpRouteRow(
    val label: String,
    val senderEndpoint: String,
    val receiverEndpoint: String,
    val primeOk: Boolean,
    val channelOk: Boolean,
    val sentPackets: Long = 0L,
    val recvPackets: Long = 0L
)

private fun parseUdpRouteRows(logs: List<String>, senderView: Boolean): List<UdpRouteRow> {
    val startRegex = Regex("""\[[A-Z]+]\[-]\[START] .*local=(\d+)\s+remote=([^\s]+)""")
    val controlRegex = Regex("""\[[A-Z]+]\[-]\[PUNCH_OK] controlEndpoint=([^\s]+)""")
    val localRegex = Regex("""\[[A-Z]+]\[[^\]]*]\[THREAD_MAP_LOCAL] thread=(\d+)\s+public=([^\s]+)""")
    val remoteRegex = Regex("""\[[A-Z]+]\[[^\]]*]\[THREAD_MAP_REMOTE] thread=(\d+)\s+peerPublic=([^\s]+)""")
    val punchMapRegex = Regex("""\[[A-Z]+]\[[^\]]*]\[THREAD_PUNCH_OK] thread=(\d+)\s+localPublic=([^\s]+)\s+peerPublic=([^\s]+)""")
    val primeOkRegex = Regex("""\[[A-Z]+]\[[^\]]*]\[THREAD_PUNCH_PRIME_OK] thread=(\d+)\b""")
    val activeRegex = Regex("""\[[A-Z]+]\[[^\]]*]\[THREAD_ACTIVE] thread=(\d+)\b""")
    val pktStatRegex = Regex("""\[[A-Z]+]\[[^\]]*]\[THREAD_PKT_STAT] thread=(\d+)\s+sent=(\d+)\s+recv=(\d+)""")

    var localControlPort: Int? = null
    var controlRemote: String? = null
    var startRemote: String? = null
    val localMap = mutableMapOf<Int, String>()
    val remoteMap = mutableMapOf<Int, String>()
    val primeOkSet = mutableSetOf<Int>()
    val channelOkSet = mutableSetOf<Int>()
    val pktSentMap = mutableMapOf<Int, Long>()
    val pktRecvMap = mutableMapOf<Int, Long>()

    logs.forEach { line ->
        startRegex.find(line)?.let {
            localControlPort = it.groupValues[1].toIntOrNull() ?: localControlPort
            startRemote = it.groupValues[2]
        }
        controlRegex.find(line)?.let { controlRemote = it.groupValues[1] }
        localRegex.find(line)?.let {
            val idx = it.groupValues[1].toIntOrNull()
            val ep = it.groupValues[2]
            if (idx != null && idx > 0) localMap[idx] = ep
        }
        remoteRegex.find(line)?.let {
            val idx = it.groupValues[1].toIntOrNull()
            val ep = it.groupValues[2]
            if (idx != null && idx > 0) remoteMap[idx] = ep
        }
        punchMapRegex.find(line)?.let {
            val idx = it.groupValues[1].toIntOrNull()
            val localEp = it.groupValues[2]
            val peerEp = it.groupValues[3]
            if (idx != null && idx > 0) {
                localMap[idx] = localEp
                remoteMap[idx] = peerEp
            }
        }
        primeOkRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { if (it > 0) primeOkSet += it }
        activeRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { if (it > 0) channelOkSet += it }
        pktStatRegex.find(line)?.let {
            val idx = it.groupValues[1].toIntOrNull()
            val sent = it.groupValues[2].toLongOrNull()
            val recv = it.groupValues[3].toLongOrNull()
            if (idx != null && idx > 0) {
                if (sent != null && sent >= 0L) pktSentMap[idx] = sent
                if (recv != null && recv >= 0L) pktRecvMap[idx] = recv
            }
        }
    }

    val controlLocal = localControlPort?.let { "local:$it" } ?: "?"
    val controlPeer = controlRemote ?: startRemote ?: "?"
    val rows = mutableListOf<UdpRouteRow>()
    rows += if (senderView) {
        UdpRouteRow("控制线程", controlLocal, controlPeer, primeOk = controlRemote != null, channelOk = controlRemote != null)
    } else {
        UdpRouteRow("控制线程", controlPeer, controlLocal, primeOk = controlRemote != null, channelOk = controlRemote != null)
    }

    val indexes = (localMap.keys + remoteMap.keys + primeOkSet + channelOkSet + pktSentMap.keys + pktRecvMap.keys).toSortedSet()
    indexes.forEach { idx ->
        val senderEp = if (senderView) localMap[idx] else remoteMap[idx]
        val receiverEp = if (senderView) remoteMap[idx] else localMap[idx]
        rows += UdpRouteRow(
            label = "线程$idx",
            senderEndpoint = senderEp ?: "?",
            receiverEndpoint = receiverEp ?: "?",
            primeOk = idx in primeOkSet,
            channelOk = idx in channelOkSet,
            sentPackets = pktSentMap[idx] ?: 0L,
            recvPackets = pktRecvMap[idx] ?: 0L
        )
    }
    return rows
}

@Composable
private fun UdpRouteCard(logs: List<String>, senderView: Boolean) {
    val rows = parseUdpRouteRows(logs, senderView)
    val endpointCache = remember { mutableStateMapOf<String, Pair<String, String>>() }
    val resolvedRows = rows.map { row ->
        val cached = endpointCache[row.label]
        val senderEndpoint = if (row.senderEndpoint != "?") row.senderEndpoint else cached?.first ?: "?"
        val receiverEndpoint = if (row.receiverEndpoint != "?") row.receiverEndpoint else cached?.second ?: "?"
        if (senderEndpoint != "?" || receiverEndpoint != "?") {
            endpointCache[row.label] = senderEndpoint to receiverEndpoint
        }
        row.copy(senderEndpoint = senderEndpoint, receiverEndpoint = receiverEndpoint)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("通道映射（发送端 -> 接收端）", fontWeight = FontWeight.SemiBold)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp, max = 200.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (rows.isEmpty()) {
                    Text("暂无通道映射信息。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    resolvedRows.forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            Text(
                                text = "${row.label}：${row.senderEndpoint} -----> ${row.receiverEndpoint}（发送端------->接收端）",
                                fontSize = 11.sp,
                                softWrap = false,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (row.primeOk || row.channelOk || row.sentPackets > 0L || row.recvPackets > 0L) {
                            val status = buildList {
                                if (row.primeOk) add("预打洞成功")
                                if (row.channelOk) add("通道已连通")
                                add("发包=${row.sentPackets}")
                                add("收包=${row.recvPackets}")
                            }.joinToString(" / ")
                            Text(
                                text = "状态：$status",
                                fontSize = 11.sp,
                                color = if (row.channelOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UdpHandshakeLogCard(logs: List<String>, onClear: () -> Unit) {
    var showFullscreen by remember { mutableStateOf(false) }
    val latestStage = mapUdpStageLabel(logs.asReversed().mapNotNull { extractUdpStage(it) }.firstOrNull())
    val stallHint = buildUdpStallHint(logs)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("握手日志", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showFullscreen = true }) { Text("放大") }
                    OutlinedButton(onClick = onClear, enabled = logs.isNotEmpty()) { Text("清空") }
                }
            }
            Text("当前阶段：$latestStage", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(buildUdpHandshakeProgress(logs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (stallHint != null) {
                Text("卡点提示：$stallHint", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (logs.isEmpty()) {
                    Text("暂无日志，开始打洞后会实时显示。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    logs.takeLast(120).forEach { line ->
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            Text(
                                text = line,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
    if (showFullscreen) {
        UdpHandshakeLogFullscreenDialog(
            logs = logs,
            onClear = onClear,
            onDismiss = { showFullscreen = false }
        )
    }
}

@Composable
private fun UdpHandshakeLogFullscreenDialog(
    logs: List<String>,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("握手日志（全屏）", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onClear, enabled = logs.isNotEmpty()) { Text("清空") }
                        Button(onClick = onDismiss) { Text("关闭") }
                    }
                }
                Text(
                    "日志条数：${logs.size}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text("暂无日志，开始打洞后会实时显示。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        logs.forEach { line ->
                            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                Text(
                                    text = line,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class UdpTransportTuning(
    val helloDupSend: Int,
    val helloRecvTimeoutMs: Int,
    val helloReadyTimeoutMs: Long,
    val readyBurstCount: Int,
    val readyBurstIntervalMs: Long,
    val readyKeepaliveIntervalMs: Long,
    val initialDupSend: Int,
    val ackDupSend: Int,
    val channelDialRetryMs: Long,
    val ackPollTimeoutMs: Int,
    val maxWorkerSendWindow: Int
)

private fun resolveTransportTuning(
    stabilityModeEnabled: Boolean,
    turboModeEnabled: Boolean = false
): UdpTransportTuning {
    return when {
        turboModeEnabled -> {
            UdpTransportTuning(
                helloDupSend = 2,
                helloRecvTimeoutMs = 70,
                helloReadyTimeoutMs = 12_000L,
                readyBurstCount = 8,
                readyBurstIntervalMs = 6L,
                readyKeepaliveIntervalMs = 30L,
                initialDupSend = 1,
                ackDupSend = 1,
                channelDialRetryMs = 30L,
                ackPollTimeoutMs = 18,
                maxWorkerSendWindow = 384
            )
        }
        stabilityModeEnabled -> {
        UdpTransportTuning(
            helloDupSend = 4,
            helloRecvTimeoutMs = 90,
            helloReadyTimeoutMs = 18_000L,
            readyBurstCount = 20,
            readyBurstIntervalMs = 8L,
            readyKeepaliveIntervalMs = 22L,
            initialDupSend = 3,
            ackDupSend = 2,
            channelDialRetryMs = 55L,
            ackPollTimeoutMs = 30,
            maxWorkerSendWindow = 192
        )
        }
        else -> {
        UdpTransportTuning(
            helloDupSend = UDP_HELLO_DUP_SEND.coerceAtLeast(4),
            helloRecvTimeoutMs = UDP_HELLO_RECV_TIMEOUT_MS.toInt().coerceIn(60, 200),
            helloReadyTimeoutMs = UDP_HELLO_READY_TIMEOUT_MS,
            readyBurstCount = UDP_READY_BURST_COUNT.coerceIn(8, 20),
            readyBurstIntervalMs = minOf(UDP_READY_BURST_INTERVAL_MS, 16L),
            readyKeepaliveIntervalMs = minOf(UDP_READY_KEEPALIVE_INTERVAL_MS, 70L),
            initialDupSend = 2,
            ackDupSend = UDP_ACK_DUP_SEND.coerceAtLeast(1),
            channelDialRetryMs = minOf(UDP_CHANNEL_DIAL_RETRY_MS, 120L),
            ackPollTimeoutMs = 60,
            maxWorkerSendWindow = UDP_SEND_WINDOW
        )
        }
    }
}

private fun parsePeerFromRaw(input: String): UdpPeer? {
    val raw = input.trim()
    if (raw.isBlank()) return null
    if (raw.startsWith("[") && raw.contains("]:")) {
        val end = raw.indexOf(']')
        if (end <= 1 || end + 2 >= raw.length) return null
        val host = raw.substring(1, end).trim().replace("%25", "%")
        val port = raw.substring(end + 2).trim().toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return UdpPeer(host, port)
    }
    val colon = raw.lastIndexOf(':')
    if (colon <= 0 || colon >= raw.length - 1) return null
    val host = raw.substring(0, colon).trim().replace("%25", "%")
    val port = raw.substring(colon + 1).trim().toIntOrNull() ?: return null
    if (host.isBlank() || port !in 1..65535) return null
    return UdpPeer(host, port)
}

private fun parsePeerFromQr(raw: String): UdpPeerParseResult? {
    val content = raw.trim()
    if (content.isBlank()) return null
    runCatching {
        val json = JSONObject(content)
        if (json.optString("type") == UDP_TRANSFER_QR_TYPE) {
            val ip = json.optString("ip").trim()
            val port = json.optInt("port", -1)
            if (ip.isNotBlank() && port in 1..65535) {
                val role = json.optString("role").trim().lowercase(Locale.ROOT).ifBlank { null }
                return UdpPeerParseResult(
                    peer = UdpPeer(ip, port),
                    role = role
                )
            }
        }
    }
    return parsePeerFromRaw(content)?.let { UdpPeerParseResult(peer = it) }
}

private fun encodeJsonPacket(type: Byte, json: JSONObject): ByteArray {
    val body = json.toString().toByteArray(Charsets.UTF_8)
    return byteArrayOf(type) + body
}

private fun parseJsonPacket(packet: DatagramPacket): Pair<Byte, JSONObject?> {
    if (packet.length <= 0) return 0.toByte() to null
    val type = packet.data[packet.offset]
    if (packet.length == 1) return type to null
    val text = packet.data.decodeToString(packet.offset + 1, packet.offset + packet.length)
    val json = runCatching { JSONObject(text) }.getOrNull()
    return type to json
}

private fun parsePortList(json: JSONObject?, key: String): List<Int> {
    val arr = json?.optJSONArray(key) ?: return emptyList()
    val out = mutableListOf<Int>()
    for (i in 0 until arr.length()) {
        val p = arr.optInt(i, -1)
        if (p in 1..65535) out += p
    }
    return out
}

private data class UdpMappedEndpoint(
    val host: String,
    val port: Int
)

private fun mappedEndpointsToJsonArray(endpoints: List<UdpMappedEndpoint>): JSONArray {
    return JSONArray().apply {
        endpoints.forEach { ep ->
            put(JSONObject().put("host", ep.host).put("port", ep.port))
        }
    }
}

private fun parseMappedEndpoints(json: JSONObject?, key: String, fallbackPortsKey: String): List<UdpMappedEndpoint> {
    val arr = json?.optJSONArray(key)
    val out = mutableListOf<UdpMappedEndpoint>()
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            when (item) {
                is JSONObject -> {
                    val host = item.optString("host", item.optString("address", item.optString("ip", ""))).trim()
                    val port = item.optInt("port", item.optInt("p", -1))
                    if (port in 1..65535) out += UdpMappedEndpoint(host = host, port = port)
                }
                is Number -> {
                    val port = item.toInt()
                    if (port in 1..65535) out += UdpMappedEndpoint(host = "", port = port)
                }
            }
        }
    }
    if (out.isNotEmpty()) return out
    return parsePortList(json, fallbackPortsKey).map { p -> UdpMappedEndpoint(host = "", port = p) }
}

private fun buildChannelPortCandidates(
    primaryPort: Int,
    predictedPort: Int,
    controlPort: Int,
    extraPorts: IntArray = intArrayOf(),
    sprintModeEnabled: Boolean
): IntArray {
    val spread = if (sprintModeEnabled) 6 else 3
    val ordered = linkedSetOf<Int>()
    fun addPort(port: Int) {
        if (port in 1..65535) ordered += port
    }
    addPort(primaryPort)
    extraPorts.forEach { addPort(it) }
    addPort(predictedPort)
    addPort(controlPort)
    for (delta in 1..spread) {
        addPort(primaryPort + delta)
        addPort(primaryPort - delta)
        addPort(predictedPort + delta)
        addPort(predictedPort - delta)
    }
    val arr = ordered.toIntArray()
    return if (arr.isNotEmpty()) arr else intArrayOf(controlPort.coerceIn(1, 65535))
}

private suspend fun probeChannelMappedEndpointsIpv6(
    baseLocalPort: Int,
    threadCount: Int,
    enabled: Boolean
): List<UdpMappedEndpoint> = withContext(Dispatchers.IO) {
    if (threadCount <= 0) return@withContext emptyList()
    if (!enabled) {
        return@withContext List(threadCount) { idx ->
            UdpMappedEndpoint(host = "", port = baseLocalPort + 1 + idx)
        }
    }
    val jobs = (0 until threadCount).map { idx ->
        async {
            val local = baseLocalPort + 1 + idx
            val mapped = runCatching {
                NetworkUtils.probeStunMappedEndpointBatch(
                    localPort = local,
                    preferIpv6 = true,
                    transport = StunTransportType.UDP,
                    verifyWithIpip = false
                ).preferredEndpoint
            }.getOrNull()
            val mappedPort = mapped?.port?.takeIf { it in 1..65535 } ?: local
            val mappedHost = mapped?.address.orEmpty()
            UdpMappedEndpoint(host = mappedHost, port = mappedPort)
        }
    }
    jobs.awaitAll()
}

private fun encodeDataPacket(seq: Int, payload: ByteArray, len: Int): ByteArray {
    val head = ByteBuffer.allocate(1 + 4 + 2).order(ByteOrder.BIG_ENDIAN)
    head.put(PKT_DATA)
    head.putInt(seq)
    head.putShort(len.toShort())
    return head.array() + payload.copyOf(len)
}

private fun decodeDataPacket(packet: DatagramPacket): Triple<Int, Int, ByteArray>? {
    if (packet.length < 7 || packet.data[packet.offset] != PKT_DATA) return null
    val bb = ByteBuffer.wrap(packet.data, packet.offset + 1, 6).order(ByteOrder.BIG_ENDIAN)
    val seq = bb.int
    val len = bb.short.toInt() and 0xffff
    if (len < 0 || 7 + len > packet.length) return null
    val payload = packet.data.copyOfRange(packet.offset + 7, packet.offset + 7 + len)
    return Triple(seq, len, payload)
}

private fun encodeAckPacket(seq: Int): ByteArray {
    val bb = ByteBuffer.allocate(1 + 4).order(ByteOrder.BIG_ENDIAN)
    bb.put(PKT_ACK)
    bb.putInt(seq)
    return bb.array()
}

private fun decodeAckSeq(packet: DatagramPacket): Int? {
    if (packet.length < 5 || packet.data[packet.offset] != PKT_ACK) return null
    val bb = ByteBuffer.wrap(packet.data, packet.offset + 1, 4).order(ByteOrder.BIG_ENDIAN)
    return bb.int
}

private suspend fun resolveIpv6(): String? = withContext(Dispatchers.IO) {
    NetworkUtils.getLocalGlobalIpv6Address()
}

private suspend fun establishUdpPunch(
    socket: DatagramSocket,
    remoteHost: String,
    remotePort: Int,
    timeoutMs: Long? = UDP_PUNCH_TIMEOUT_MS,
    probeBurst: Int = 2,
    sendIntervalMs: Long = 170L,
    recvTimeoutMs: Int = 220,
    labCompatMode: Boolean = false,
    onSendStat: ((sentPackets: Long, remainingMs: Long) -> Unit)? = null,
    isCancelled: () -> Boolean = { false }
): InetSocketAddress? = withContext(Dispatchers.IO) {
    establishUdpPunchMulti(
        socket = socket,
        remoteHost = remoteHost,
        remotePorts = intArrayOf(remotePort),
        timeoutMs = timeoutMs,
        probeBurst = probeBurst,
        sendIntervalMs = sendIntervalMs,
        recvTimeoutMs = recvTimeoutMs,
        labCompatMode = labCompatMode,
        onSendStat = onSendStat,
        isCancelled = isCancelled
    )
}

private suspend fun prewarmUdpCandidates(
    socket: DatagramSocket,
    remoteHost: String,
    remotePorts: IntArray,
    includeHello: Boolean = false,
    isCancelled: () -> Boolean = { false }
): InetSocketAddress? = withContext(Dispatchers.IO) {
    val addrs = runCatching {
        InetAddress.getAllByName(remoteHost)
            .toList()
            .sortedByDescending { it is Inet6Address }
    }.getOrDefault(emptyList())
    if (addrs.isEmpty()) return@withContext null
    val ports = remotePorts.asSequence()
        .filter { it in 1..65535 }
        .distinct()
        .toList()
    if (ports.isEmpty()) return@withContext null
    val punch = byteArrayOf(PKT_PUNCH)
    val helloBytes = UDP_CTRL_HELLO.toByteArray(Charsets.UTF_8)
    val ack = byteArrayOf(PKT_PUNCH_ACK)
    val oldTimeout = runCatching { socket.soTimeout }.getOrDefault(0)
    addrs.forEach { addr ->
        ports.forEach { port ->
            if (isCancelled()) throw CancellationException("用户已中断")
            runCatching { socket.send(DatagramPacket(punch, punch.size, addr, port)) }
            if (includeHello) {
                runCatching { socket.send(DatagramPacket(helloBytes, helloBytes.size, addr, port)) }
            }
        }
    }
    runCatching { socket.soTimeout = 30 }
    val readBuf = ByteArray(256)
    repeat(2) {
        if (isCancelled()) throw CancellationException("用户已中断")
        try {
            val packet = DatagramPacket(readBuf, readBuf.size)
            socket.receive(packet)
            if (packet.port in ports) {
                runCatching { socket.send(DatagramPacket(ack, ack.size, packet.address, packet.port)) }
                runCatching { socket.soTimeout = oldTimeout }
                return@withContext InetSocketAddress(packet.address, packet.port)
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: Exception) {
        }
    }
    runCatching { socket.soTimeout = oldTimeout }
    null
}

private suspend fun establishUdpPunchMulti(
    socket: DatagramSocket,
    remoteHost: String,
    remotePorts: IntArray,
    timeoutMs: Long? = UDP_PUNCH_TIMEOUT_MS,
    probeBurst: Int = 2,
    sendIntervalMs: Long = 170L,
    recvTimeoutMs: Int = 220,
    labCompatMode: Boolean = false,
    onSendStat: ((sentPackets: Long, remainingMs: Long) -> Unit)? = null,
    isCancelled: () -> Boolean = { false }
): InetSocketAddress? = withContext(Dispatchers.IO) {
    val addrs = runCatching {
        InetAddress.getAllByName(remoteHost)
            .toList()
            .sortedByDescending { it is Inet6Address }
    }.getOrDefault(emptyList())
    if (addrs.isEmpty()) return@withContext null
    val ports = remotePorts.asSequence()
        .filter { it in 1..65535 }
        .distinct()
        .toList()
    if (ports.isEmpty()) return@withContext null
    val deadline = timeoutMs?.let { System.currentTimeMillis() + it.coerceAtLeast(800L) }
    var nextSendAt = 0L
    val readBuffer = ByteArray(2048)
    val helloBytes = UDP_CTRL_HELLO.toByteArray(Charsets.UTF_8)
    val ackBytes = UDP_CTRL_ACK.toByteArray(Charsets.UTF_8)
    val safeProbeBurst = probeBurst.coerceIn(1, 8)
    val safeSendInterval = sendIntervalMs.coerceIn(70L, 400L)
    var sentPackets = 0L
    var lastStatNotifyAt = 0L
    fun notifySendState(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastStatNotifyAt >= UDP_CONTROL_SEND_STATE_UPDATE_MS) {
            lastStatNotifyAt = now
            onSendStat?.invoke(sentPackets, deadline?.let { (it - now).coerceAtLeast(0L) } ?: Long.MAX_VALUE)
        }
    }
    fun sendAndCount(packet: DatagramPacket) {
        runCatching { socket.send(packet) }.onSuccess {
            sentPackets++
            notifySendState()
        }
    }
    notifySendState(force = true)
    socket.soTimeout = recvTimeoutMs.coerceIn(80, 450)
    while (deadline == null || System.currentTimeMillis() < deadline) {
        if (isCancelled()) throw CancellationException("用户已中断")
        val now = System.currentTimeMillis()
        if (now >= nextSendAt) {
            val probe = byteArrayOf(PKT_PUNCH)
            repeat(safeProbeBurst) { burstIdx ->
                addrs.forEach { addr ->
                    ports.forEach { port ->
                        sendAndCount(DatagramPacket(probe, probe.size, addr, port))
                        if (labCompatMode) {
                            sendAndCount(DatagramPacket(helloBytes, helloBytes.size, addr, port))
                        }
                    }
                }
                if (burstIdx < safeProbeBurst - 1) {
                    delay(6L)
                }
            }
            nextSendAt = now + safeSendInterval
        }
        try {
            val packet = DatagramPacket(readBuffer, readBuffer.size)
            socket.receive(packet)
            val packetType = packet.data[packet.offset]
            if (packetType == PKT_PUNCH || packetType == PKT_PUNCH_ACK) {
                val ack = byteArrayOf(PKT_PUNCH_ACK)
                sendAndCount(DatagramPacket(ack, ack.size, packet.address, packet.port))
                notifySendState(force = true)
                return@withContext InetSocketAddress(packet.address, packet.port)
            } else if (labCompatMode) {
                val payload = runCatching {
                    packet.data.decodeToString(packet.offset, packet.offset + packet.length)
                }.getOrDefault("")
                if (payload.startsWith(UDP_CTRL_HELLO) || payload.startsWith(UDP_CTRL_ACK)) {
                    sendAndCount(DatagramPacket(ackBytes, ackBytes.size, packet.address, packet.port))
                    notifySendState(force = true)
                    return@withContext InetSocketAddress(packet.address, packet.port)
                }
                if (packet.length > 0 && packet.port in 1..65535) {
                    sendAndCount(DatagramPacket(ackBytes, ackBytes.size, packet.address, packet.port))
                    notifySendState(force = true)
                    return@withContext InetSocketAddress(packet.address, packet.port)
                }
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: Exception) {
        }
    }
    notifySendState(force = true)
    null
}

private suspend fun openParallelChannels(
    localBasePort: Int,
    remoteHost: String,
    remoteBasePort: Int,
    threadCount: Int,
    isCancelled: () -> Boolean = { false }
): List<UdpDataChannel> = coroutineScope {
    (0 until threadCount).map { i ->
        async(Dispatchers.IO) {
            if (isCancelled()) throw CancellationException("用户已中断")
            val localPort = localBasePort + 1 + i
            val remotePort = remoteBasePort + 1 + i
            val socket = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("::"), localPort))
                }
            }.getOrNull() ?: return@async null
            try {
                val endpoint = establishUdpPunch(socket, remoteHost, remotePort, isCancelled = isCancelled)
                if (endpoint != null) UdpDataChannel(socket, endpoint) else {
                    runCatching { socket.close() }
                    null
                }
            } catch (e: CancellationException) {
                runCatching { socket.close() }
                throw e
            } catch (_: Exception) {
                runCatching { socket.close() }
                null
            }
        }
    }.awaitAll().filterNotNull()
}

private suspend fun queryFileMeta(context: Context, uri: Uri): Triple<String, String, Long>? = withContext(Dispatchers.IO) {
    runCatching {
        var name = "file_${System.currentTimeMillis()}"
        var mime = context.contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) name = cursor.getString(nameIdx).orEmpty().ifBlank { name }
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        Triple(name, mime, size)
    }.getOrNull()
}

private suspend fun materializeUriToTempFile(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "udp_mt_send").apply { mkdirs() }
    val out = File(dir, "f_${System.currentTimeMillis()}_${uri.hashCode()}.bin")
    val inputStream = if (uri.scheme == "file") {
        FileInputStream(File(uri.path ?: throw IllegalStateException("Invalid file uri")))
    } else {
        context.contentResolver.openInputStream(uri)
    }
    inputStream?.use { input ->
        FileOutputStream(out).use { output ->
            input.copyTo(output, 128 * 1024)
        }
    } ?: throw IllegalStateException("Unable to read source file")
    if (!out.exists() || out.length() <= 0L) {
        throw IllegalStateException("Source file is empty")
    }
    out
}

private fun flowLog(role: String, sid: String = "-", stage: String, detail: String) {
    val line = "[$role][$sid][$stage] $detail"
    Log.i(UDP_LOG_TAG, line)
    val stamped = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())} $line"
    udpHandshakeLogListeners.forEach { listener ->
        runCatching { listener(stamped) }
    }
}

private fun buildLocalPeerPayload(ipv6: String, port: Int, role: String? = null): String {
    return JSONObject()
        .put("type", UDP_TRANSFER_QR_TYPE)
        .put("ip", ipv6)
        .put("port", port)
        .apply {
            if (!role.isNullOrBlank()) put("role", role)
        }
        .toString()
}

private fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun readClipboardText(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    val clip = clipboard.primaryClip ?: return ""
    if (clip.itemCount <= 0) return ""
    return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
}

private suspend fun createZipFromItems(context: Context, items: List<UdpSendFileItem>): File = withContext(Dispatchers.IO) {
    if (items.isEmpty()) throw IllegalStateException("没有可压缩的文件")
    val dir = File(context.cacheDir, "udp_zip_send").apply { mkdirs() }
    val zipFile = File(dir, "batch_${System.currentTimeMillis()}.zip")
    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
        val usedNames = HashSet<String>()
        items.forEach { item ->
            var entryName = item.name.ifBlank { "file_${System.currentTimeMillis()}" }
            if (usedNames.contains(entryName)) {
                val base = entryName.substringBeforeLast('.', entryName)
                val ext = entryName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
                var i = 1
                while (usedNames.contains("${base}_$i$ext")) i++
                entryName = "${base}_$i$ext"
            }
            usedNames += entryName
            val inStream = if (item.uri.scheme == "file") {
                FileInputStream(File(item.uri.path ?: ""))
            } else {
                context.contentResolver.openInputStream(item.uri)
            } ?: return@forEach
            inStream.use { input ->
                zos.putNextEntry(ZipEntry(entryName))
                input.copyTo(zos, 128 * 1024)
                zos.closeEntry()
            }
        }
    }
    if (!zipFile.exists() || zipFile.length() <= 0L) throw IllegalStateException("压缩失败")
    zipFile
}

private suspend fun unzipReceivedFile(zipPath: String, fileName: String): Int = withContext(Dispatchers.IO) {
    val zipFile = File(zipPath)
    if (!zipFile.exists()) return@withContext 0
    val baseDir = zipFile.parentFile ?: return@withContext 0
    val folderName = fileName.substringBeforeLast('.', fileName).ifBlank { "unzipped_${System.currentTimeMillis()}" }
    val outDir = File(baseDir, "${folderName}_unzipped").apply { mkdirs() }
    var count = 0
    ZipInputStream(FileInputStream(zipFile)).use { zis ->
        while (true) {
            val entry = zis.nextEntry ?: break
            val safeName = entry.name.replace("\\", "/").removePrefix("/").substringAfterLast("../")
            if (safeName.isBlank()) continue
            val outFile = File(outDir, safeName)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { output -> zis.copyTo(output, 128 * 1024) }
                count++
            }
            zis.closeEntry()
        }
    }
    count
}

private fun formatTransferSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}

private fun uniqueTargetFile(dir: File, fileName: String): File {
    val sanitized = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "received_${System.currentTimeMillis()}" }
    val first = File(dir, sanitized)
    if (!first.exists()) return first
    val base = sanitized.substringBeforeLast('.', sanitized)
    val ext = sanitized.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
    var i = 1
    while (true) {
        val c = File(dir, "${base}_$i$ext")
        if (!c.exists()) return c
        i++
    }
}

private suspend fun sendFileByUdpPunch(
    context: Context,
    fileUri: Uri,
    fileName: String,
    mimeType: String,
    fileSize: Long,
    localPort: Int,
    remoteHost: String,
    remotePort: Int,
    requestedThreads: Int,
    batchId: String,
    batchIndex: Int,
    batchTotal: Int,
    packedZip: Boolean = false,
    packedCount: Int = 1,
    seqBase: Int = 0,
    runtimeSession: UdpSendRuntimeSession? = null,
    punchTimeoutMs: Long? = UDP_CONTROL_FLOOD_TIMEOUT_MS,
    stabilityModeEnabled: Boolean = false,
    turboModeEnabled: Boolean = false,
    perPortStunProbeEnabled: Boolean = false,
    isCancelled: () -> Boolean = { false },
    onProgress: (UdpTransferProgress) -> Unit
): String = withContext(Dispatchers.IO) {
    val sprintModeEnabled = stabilityModeEnabled || turboModeEnabled
    val turboFastMode = turboModeEnabled
    val tuning = resolveTransportTuning(stabilityModeEnabled, turboFastMode)
    val profile = when {
        turboFastMode -> UDP_PROFILE_TURBO
        stabilityModeEnabled -> UDP_PROFILE_SPRINT
        else -> UDP_PROFILE_CLASSIC
    }
    val sourceFile = materializeUriToTempFile(context, fileUri)
    val actualFileSize = sourceFile.length().coerceAtLeast(0L)
    val ownControl = runtimeSession == null
    val controlSocket = runtimeSession?.controlSocket ?: DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("::"), localPort))
    }
    try {
        onProgress(UdpTransferProgress(0f, "正在协商文件信息...", fileName, actualFileSize, 0L, 0L))
        flowLog(
            role = "SEND",
            stage = "START",
            detail = "file=$fileName size=$actualFileSize local=$localPort remote=$remoteHost:$remotePort batch=$batchIndex/$batchTotal packedZip=$packedZip packedCount=$packedCount profile=$profile"
        )
        var controlEndpoint = runtimeSession?.controlEndpoint ?: run {
            val floodTimeoutMs = punchTimeoutMs
            var sentPackets = 0L
            var lastProgressNotifyAt = 0L
            flowLog("SEND", stage = "PUNCH_TRY", detail = "file=$fileName attempt=1 timeoutMs=${floodTimeoutMs ?: -1L} mode=flood")
            val endpoint = establishUdpPunch(
                socket = controlSocket,
                remoteHost = remoteHost,
                remotePort = remotePort,
                timeoutMs = floodTimeoutMs,
                probeBurst = if (turboFastMode) 5 else if (sprintModeEnabled) 8 else 6,
                sendIntervalMs = if (turboFastMode) 90L else if (sprintModeEnabled) 70L else 85L,
                recvTimeoutMs = if (turboFastMode) 100 else if (sprintModeEnabled) 120 else 150,
                labCompatMode = true,
                onSendStat = { sent, remainingMs ->
                    sentPackets = sent
                    val now = System.currentTimeMillis()
                    if (now - lastProgressNotifyAt >= UDP_CONTROL_SEND_STATE_UPDATE_MS || (floodTimeoutMs != null && remainingMs <= 0L)) {
                        lastProgressNotifyAt = now
                        val remainingText = if (floodTimeoutMs == null) "无限" else "${((remainingMs + 999L) / 1000L).coerceAtLeast(0L)} 秒"
                        flowLog("SEND", stage = "PUNCH_SEND_STATE", detail = "controlSent=$sent remainingMs=$remainingMs")
                        onProgress(
                            UdpTransferProgress(
                                progress = 0.01f,
                                stage = "发包状态：控制信道已发 $sent 包，剩余 $remainingText",
                                fileName = fileName,
                                totalBytes = actualFileSize,
                                transferredBytes = 0L,
                                speedBytesPerSec = 0L
                            )
                        )
                    }
                },
                isCancelled = isCancelled
            )
            if (endpoint == null) {
                throw IllegalStateException(
                    if (floodTimeoutMs == null) "打洞失败（未建立连接，控制发包=$sentPackets）"
                    else "打洞超时（30秒灌包结束，控制发包=$sentPackets）"
                )
            }
            endpoint
        } ?: throw IllegalStateException("打洞超时")
        flowLog("SEND", stage = "PUNCH_OK", detail = "controlEndpoint=${controlEndpoint.address.hostAddress}:${controlEndpoint.port} prePrime=true")
        if (runtimeSession == null) {
            onProgress(UdpTransferProgress(0.02f, "打洞成功，正在协商文件信息", fileName, actualFileSize, 0L, 0L))
        }

        val sid = UUID.randomUUID().toString()
        flowLog("SEND", sid, "SID", "created for file=$fileName")
        val threads = runtimeSession?.requestedThreads ?: requestedThreads.coerceIn(1, UDP_MAX_THREADS)
        val predictedSenderDataEndpoints = probeChannelMappedEndpointsIpv6(
            baseLocalPort = localPort,
            threadCount = threads,
            enabled = false
        )
        var senderDataEndpoints = predictedSenderDataEndpoints
        val senderProbeDeferred: kotlinx.coroutines.Deferred<List<UdpMappedEndpoint>>? = null
        if (perPortStunProbeEnabled) {
            onProgress(UdpTransferProgress(0.02f, "正在探测并同步各线程端口映射...", fileName, actualFileSize, 0L, 0L))
            val probeTimeoutMs = if (turboFastMode) 2_800L else if (sprintModeEnabled) 4_200L else 5_200L
            senderDataEndpoints = kotlinx.coroutines.withTimeoutOrNull(probeTimeoutMs) {
                probeChannelMappedEndpointsIpv6(
                    baseLocalPort = localPort,
                    threadCount = threads,
                    enabled = true
                )
            } ?: predictedSenderDataEndpoints
        }
        val channelPortMode = if (perPortStunProbeEnabled) UDP_CHANNEL_PORT_MODE_PER_STUN else UDP_CHANNEL_PORT_MODE_PREDICT
        senderDataEndpoints.forEachIndexed { idx, ep ->
            val host = ep.host.ifBlank { controlEndpoint.address.hostAddress }
            flowLog(
                "SEND",
                sid,
                "THREAD_MAP_LOCAL",
                "thread=${idx + 1} public=$host:${ep.port} local=${localPort + 1 + idx} mode=$channelPortMode"
            )
        }
        val safeBatchTotal = batchTotal.coerceAtLeast(1)
        val safeBatchIndex = batchIndex.coerceIn(1, safeBatchTotal)
        fun buildHelloPacket(endpoints: List<UdpMappedEndpoint>): ByteArray {
            val senderDataPortsJson = JSONArray().apply { endpoints.forEach { put(it.port) } }
            val meta = JSONObject()
                .put("sid", sid)
                .put("name", fileName)
                .put("mime", mimeType)
                .put("size", actualFileSize)
                .put("threads", threads)
                .put("batchId", batchId)
                .put("batchIndex", safeBatchIndex)
                .put("batchTotal", safeBatchTotal)
                .put("seqBase", seqBase)
                .put("packedZip", packedZip)
                .put("packedCount", packedCount.coerceAtLeast(1))
                .put("profile", profile)
                .put("channelPortMode", channelPortMode)
                .put("senderDataEndpoints", mappedEndpointsToJsonArray(endpoints))
                .put("senderDataPorts", senderDataPortsJson)
            return encodeJsonPacket(PKT_HELLO, meta)
        }
        var helloPacket = buildHelloPacket(senderDataEndpoints)
        if (runtimeSession == null) {
            drainControlSocket(controlSocket)
        }
        controlSocket.soTimeout = tuning.helloRecvTimeoutMs
        var ready = false
        var agreedThreads = threads
        var receiverDataEndpoints = emptyList<UdpMappedEndpoint>()
        val optimisticReuse = runtimeSession != null && runtimeSession.channels.isNotEmpty()
        val helloWaitMs = if (optimisticReuse) 2_000L else tuning.helloReadyTimeoutMs
        var helloDeadline = System.currentTimeMillis() + helloWaitMs
        val helloStartAt = System.currentTimeMillis()
        var lastNotifySecond = -1L
        var helloAttempts = 0
        var helloExtendCount = 0
        val helloMaxExtends = if (optimisticReuse) 0 else if (turboFastMode) 2 else if (sprintModeEnabled) 4 else 3
        var helloFloodBursts = 0
        val helloFloodMaxBursts = if (turboFastMode) 16 else if (sprintModeEnabled) 28 else 20
        var senderMapApplied = false
        flowLog("SEND", sid, "HELLO_BEGIN", "waitMs=$helloWaitMs optimisticReuse=$optimisticReuse")
        while (!ready) {
            if (isCancelled()) throw CancellationException("用户已中断")
            val loopNow = System.currentTimeMillis()
            if (loopNow >= helloDeadline) {
                if (!optimisticReuse && helloExtendCount < helloMaxExtends) {
                    val extraMs = if (turboFastMode) 2_500L else if (sprintModeEnabled) 8_000L else 6_000L
                    helloDeadline = loopNow + extraMs
                    helloExtendCount++
                    flowLog("SEND", sid, "HELLO_EXTEND", "extraMs=$extraMs count=$helloExtendCount/$helloMaxExtends attempts=$helloAttempts")
                    onProgress(
                        UdpTransferProgress(
                            progress = 0.02f,
                            stage = "高延迟网络，延长 READY 等待窗口（$helloExtendCount/$helloMaxExtends）...",
                            fileName = fileName,
                            totalBytes = actualFileSize,
                            transferredBytes = 0L,
                            speedBytesPerSec = 0L
                        )
                    )
                    continue
                }
                break
            }
            if (!senderMapApplied && senderProbeDeferred != null) {
                val mapped = kotlinx.coroutines.withTimeoutOrNull(1L) { senderProbeDeferred.await() }
                if (!mapped.isNullOrEmpty()) {
                    senderDataEndpoints = mapped
                    helloPacket = buildHelloPacket(senderDataEndpoints)
                    senderMapApplied = true
                    senderDataEndpoints.forEachIndexed { idx, ep ->
                        val host = ep.host.ifBlank { controlEndpoint.address.hostAddress }
                        flowLog(
                            "SEND",
                            sid,
                            "THREAD_MAP_LOCAL",
                            "thread=${idx + 1} public=$host:${ep.port} local=${localPort + 1 + idx} mode=$channelPortMode async=true"
                        )
                    }
                }
            }
            helloAttempts++
            val helloDupBurst = when {
                helloAttempts <= 4 -> tuning.helloDupSend * 3
                helloAttempts <= 12 -> tuning.helloDupSend * 2
                else -> tuning.helloDupSend
            }.coerceIn(tuning.helloDupSend, 32)
            repeat(helloDupBurst) {
                controlSocket.send(DatagramPacket(helloPacket, helloPacket.size, controlEndpoint))
            }
            val shouldFlood = helloFloodBursts < helloFloodMaxBursts &&
                helloAttempts % (if (turboFastMode) 2 else 3) == 0
            if (shouldFlood) {
                val floodPackets = if (turboFastMode) 24 else if (sprintModeEnabled) 32 else 28
                repeat(floodPackets) {
                    controlSocket.send(DatagramPacket(helloPacket, helloPacket.size, controlEndpoint))
                }
                helloFloodBursts++
                flowLog(
                    "SEND",
                    sid,
                    "HELLO_FLOOD",
                    "burst=$helloFloodBursts/$helloFloodMaxBursts packets=$floodPackets attempts=$helloAttempts"
                )
            }
            val elapsedSec = ((System.currentTimeMillis() - helloStartAt) / 1000L).coerceAtLeast(0L)
            if (elapsedSec != lastNotifySecond && elapsedSec > 0L) {
                lastNotifySecond = elapsedSec
                onProgress(
                    UdpTransferProgress(
                        progress = 0.02f,
                        stage = "正在协商文件信息...(${elapsedSec}s)",
                        fileName = fileName,
                        totalBytes = actualFileSize,
                        transferredBytes = 0L,
                        speedBytesPerSec = 0L
                    )
                )
            }
            try {
                val inBuf = ByteArray(2048)
                val incoming = DatagramPacket(inBuf, inBuf.size)
                controlSocket.receive(incoming)
                val (type, json) = parseJsonPacket(incoming)
                if (type == PKT_READY && json?.optString("sid") == sid) {
                    ready = true
                    agreedThreads = json.optInt("threads", threads).coerceIn(1, UDP_MAX_THREADS)
                    receiverDataEndpoints = parseMappedEndpoints(json, "receiverDataEndpoints", "receiverDataPorts")
                    receiverDataEndpoints.forEachIndexed { idx, ep ->
                        val host = ep.host.ifBlank { incoming.address.hostAddress }
                        flowLog("SEND", sid, "THREAD_MAP_REMOTE", "thread=${idx + 1} peerPublic=$host:${ep.port}")
                    }
                    flowLog("SEND", sid, "READY_OK", "threads=$agreedThreads attempts=$helloAttempts receiverPorts=${receiverDataEndpoints.size} from=${incoming.address.hostAddress}:${incoming.port}")
                } else if (type == PKT_PUNCH) {
                    val ack = byteArrayOf(PKT_PUNCH_ACK)
                    controlSocket.send(DatagramPacket(ack, ack.size, incoming.address, incoming.port))
                }
            } catch (_: SocketTimeoutException) {
                runCatching {
                    val keepAlive = byteArrayOf(PKT_PUNCH)
                    repeat(if (turboFastMode) 1 else if (sprintModeEnabled) 3 else 2) {
                        controlSocket.send(DatagramPacket(keepAlive, keepAlive.size, controlEndpoint))
                    }
                }
            }
            delay(if (turboFastMode) 30L else if (sprintModeEnabled) 16L else 24L)
        }
        if (!ready) {
            agreedThreads = if (optimisticReuse) runtimeSession.channels.size.coerceAtLeast(1) else threads
            flowLog("SEND", sid, "READY_TIMEOUT", "attempts=$helloAttempts continueDirect=true threads=$agreedThreads")
            Log.w(UDP_LOG_TAG, "[SEND][$sid][READY_TIMEOUT] attempts=$helloAttempts continueDirect=true threads=$agreedThreads")
            onProgress(
                UdpTransferProgress(
                    progress = 0.03f,
                    stage = "未收到 READY，尝试直接发送（$agreedThreads 线程）",
                    fileName = fileName,
                    totalBytes = actualFileSize,
                    transferredBytes = 0L,
                    speedBytesPerSec = 0L
                )
            )
        }

        val reuseChannels = runtimeSession != null && runtimeSession.channels.isNotEmpty()
        val workerCount = if (reuseChannels) runtimeSession.channels.size else agreedThreads
        val totalSendBudgetPackets = when {
            turboFastMode && workerCount >= 32 -> 288
            turboFastMode && workerCount >= 16 -> 384
            turboFastMode -> 512
            sprintModeEnabled && workerCount >= 32 -> 160
            sprintModeEnabled && workerCount >= 16 -> 224
            sprintModeEnabled -> 320
            workerCount >= 32 -> 96
            workerCount >= 16 -> 128
            else -> 192
        }
        val workerSendWindow = (totalSendBudgetPackets / workerCount.coerceAtLeast(1))
            .coerceIn(2, tuning.maxWorkerSendWindow)
        val workerInitialDupSend = tuning.initialDupSend
        val workerRetxMs = when {
            turboFastMode && workerCount >= 32 -> 300L
            turboFastMode && workerCount >= 16 -> 240L
            turboFastMode -> 180L
            sprintModeEnabled && workerCount >= 32 -> 180L
            sprintModeEnabled && workerCount >= 16 -> 120L
            sprintModeEnabled -> 65L
            workerCount >= 32 -> maxOf(UDP_RETX_MS, 320L)
            workerCount >= 16 -> maxOf(UDP_RETX_MS, 220L)
            else -> UDP_RETX_MS
        }
        if (reuseChannels && runtimeSession.channels.size != agreedThreads) {
            throw IllegalStateException("并行线程数变更，无法复用会话")
        }
        if (reuseChannels) {
            flowLog("SEND", sid, "CHANNEL_REUSE", "count=${runtimeSession.channels.size}")
        } else {
            onProgress(UdpTransferProgress(0.03f, "正在建立并行通道（$agreedThreads）...", fileName, actualFileSize, 0L, 0L))
            flowLog("SEND", sid, "CHANNEL_LAZY_OPEN", "expected=$agreedThreads")
        }

        val total = actualFileSize.coerceAtLeast(1L)
        val totalChunks = ((actualFileSize + UDP_CHUNK_SIZE - 1L) / UDP_CHUNK_SIZE).toInt().coerceAtLeast(0)
        val nextChunk = AtomicInteger(0)
        val ackedBytes = AtomicLong(0L)
        val ackedChunks = AtomicInteger(0)
        val sentPayloadBytes = AtomicLong(0L)
        val lastAckAt = AtomicLong(System.currentTimeMillis())
        val openedChannels = AtomicInteger(if (reuseChannels) workerCount else 0)
        val channelOpenDeadlineAt = if (reuseChannels) {
            Long.MAX_VALUE
        } else {
            System.currentTimeMillis() + if (turboFastMode) 18_000L else if (sprintModeEnabled) 36_000L else 22_000L
        }
        val transferDone = AtomicBoolean(false)
        var speedBytes = 0L
        var lastSpeedBytes = 0L
        var lastSpeedAt = System.currentTimeMillis()
        var controlRepunchAttempts = 0
        val controlRepunchMax = if (turboFastMode) 1 else if (sprintModeEnabled) 4 else 2

        val workers = (0 until workerCount).map { workerIndex ->
            kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext).async {
                var ownSocket = false
                val channelLocalPort = localPort + 1 + workerIndex
                val predictedRemotePort = remotePort + 1 + workerIndex
                var workerRemotePort = receiverDataEndpoints.getOrNull(workerIndex)?.port ?: predictedRemotePort
                val workerPortCandidates = buildChannelPortCandidates(
                    primaryPort = workerRemotePort,
                    predictedPort = predictedRemotePort,
                    controlPort = remotePort,
                    extraPorts = receiverDataEndpoints.map { it.port }.toIntArray(),
                    sprintModeEnabled = sprintModeEnabled
                )
                val socket: DatagramSocket
                var endpoint: InetSocketAddress
                if (reuseChannels) {
                    val channel = runtimeSession!!.channels[workerIndex]
                    socket = channel.socket
                    endpoint = channel.endpoint
                    workerRemotePort = endpoint.port
                } else {
                    ownSocket = true
                    var openedSocket: DatagramSocket? = null
                    var channelEndpoint: InetSocketAddress? = null
                    while (channelEndpoint == null) {
                        if (isCancelled() || transferDone.get()) {
                            runCatching { openedSocket?.close() }
                            return@async
                        }
                        if (System.currentTimeMillis() >= channelOpenDeadlineAt) {
                            flowLog("SEND", sid, "CHANNEL_OPEN_TIMEOUT", "index=${workerIndex + 1} opened=${openedChannels.get()}/$workerCount")
                            runCatching { openedSocket?.close() }
                            return@async
                        }
                        openedSocket = runCatching {
                            DatagramSocket(null).apply {
                                reuseAddress = true
                                bind(InetSocketAddress(InetAddress.getByName("::"), channelLocalPort))
                            }
                        }.getOrNull()
                        if (openedSocket == null) {
                            delay(tuning.channelDialRetryMs)
                            continue
                        }
                        workerRemotePort = workerPortCandidates.firstOrNull() ?: predictedRemotePort
                        flowLog(
                            "SEND",
                            sid,
                            "THREAD_PUNCH_PRIME",
                            "thread=${workerIndex + 1} local=$channelLocalPort targets=${workerPortCandidates.size}"
                        )
                        val primedEndpoint = prewarmUdpCandidates(
                            socket = openedSocket,
                            remoteHost = remoteHost,
                            remotePorts = workerPortCandidates,
                            isCancelled = { isCancelled() || transferDone.get() }
                        )
                        if (primedEndpoint != null) {
                            flowLog(
                                "SEND",
                                sid,
                                "THREAD_PUNCH_PRIME_OK",
                                "thread=${workerIndex + 1} from=${primedEndpoint.address.hostAddress}:${primedEndpoint.port}"
                            )
                        }
                        flowLog(
                            "SEND",
                            sid,
                            "THREAD_PUNCH_TRY",
                            "thread=${workerIndex + 1} local=$channelLocalPort candidates=${workerPortCandidates.size} head=${workerPortCandidates.take(6).joinToString(",")}"
                        )
                        channelEndpoint = primedEndpoint ?: try {
                            establishUdpPunchMulti(
                                socket = openedSocket,
                                remoteHost = remoteHost,
                                remotePorts = workerPortCandidates,
                                timeoutMs = UDP_CHANNEL_DIAL_ATTEMPT_TIMEOUT_MS,
                                probeBurst = if (turboFastMode) 2 else if (sprintModeEnabled) 3 else 2,
                                sendIntervalMs = if (turboFastMode) 170L else if (sprintModeEnabled) 120L else 170L,
                                recvTimeoutMs = if (turboFastMode) 220 else if (sprintModeEnabled) 130 else 200,
                                isCancelled = { isCancelled() || transferDone.get() }
                            )
                        } catch (e: CancellationException) {
                            runCatching { openedSocket.close() }
                            if (isCancelled() || transferDone.get()) return@async
                            null
                        } catch (_: Exception) {
                            null
                        }
                        if (channelEndpoint == null) {
                            runCatching { openedSocket.close() }
                            delay(tuning.channelDialRetryMs)
                        }
                    }
                    socket = openedSocket ?: return@async
                    endpoint = channelEndpoint
                    workerRemotePort = endpoint.port
                    val opened = openedChannels.incrementAndGet()
                    flowLog(
                        "SEND",
                        sid,
                        "CHANNEL_READY",
                        "index=${workerIndex + 1} opened=$opened/$workerCount endpoint=${endpoint.address.hostAddress}:${endpoint.port}"
                    )
                    val localMapped = senderDataEndpoints.getOrNull(workerIndex)
                    val peerMapped = receiverDataEndpoints.getOrNull(workerIndex)
                    val localPublicHost = localMapped?.host?.takeIf { it.isNotBlank() } ?: "predicted"
                    val localPublicPort = localMapped?.port ?: channelLocalPort
                    val peerPublicHost = peerMapped?.host?.takeIf { it.isNotBlank() } ?: endpoint.address.hostAddress
                    val peerPublicPort = peerMapped?.port ?: endpoint.port
                    flowLog(
                        "SEND",
                        sid,
                        "THREAD_PUNCH_OK",
                        "thread=${workerIndex + 1} localPublic=$localPublicHost:$localPublicPort peerPublic=$peerPublicHost:$peerPublicPort local=$channelLocalPort remote=${endpoint.address.hostAddress}:${endpoint.port} success=true"
                    )
                    flowLog(
                        "SEND",
                        sid,
                        "THREAD_STATE",
                        "thread=${workerIndex + 1} status=connected local=$channelLocalPort remote=${endpoint.address.hostAddress}:${endpoint.port}"
                    )
                    if (opened == 1) {
                        onProgress(
                            UdpTransferProgress(
                                progress = 0.035f,
                                stage = "首个并行通道就绪，开始发送（$opened/$workerCount）",
                                fileName = fileName,
                                totalBytes = actualFileSize,
                                transferredBytes = 0L,
                                speedBytesPerSec = 0L
                            )
                        )
                    }
                }
                socket.soTimeout = tuning.ackPollTimeoutMs
                val inflight = linkedMapOf<Int, ByteArray>()
                val inflightSize = linkedMapOf<Int, Int>()
                val inflightTime = linkedMapOf<Int, Long>()
                val readBuf = ByteArray(UDP_CHUNK_SIZE)
                val raf = RandomAccessFile(sourceFile, "r")
                var threadLastAckAt = System.currentTimeMillis()
                var threadRepunchAttempts = 0
                var threadActiveLogged = false
                var threadSentPackets = 0L
                var threadRecvPackets = 0L
                var threadLastPktStatAt = 0L
                val threadRepunchMax = if (turboFastMode) 4 else if (sprintModeEnabled) 10 else 6
                val threadRepunchIdleMs = if (turboFastMode) 4_800L else if (sprintModeEnabled) 2_600L else 4_200L
                fun logThreadPktStat(force: Boolean = false) {
                    val now = System.currentTimeMillis()
                    if (force || now - threadLastPktStatAt >= UDP_THREAD_PKT_STAT_INTERVAL_MS) {
                        threadLastPktStatAt = now
                        flowLog(
                            "SEND",
                            sid,
                            "THREAD_PKT_STAT",
                            "thread=${workerIndex + 1} sent=$threadSentPackets recv=$threadRecvPackets"
                        )
                    }
                }
                try {
                    while (true) {
                        if (isCancelled()) throw CancellationException("用户已中断")
                        while (inflight.size < workerSendWindow) {
                            val idx = nextChunk.getAndIncrement()
                            if (idx >= totalChunks) break
                            val offset = idx.toLong() * UDP_CHUNK_SIZE.toLong()
                            val len = minOf(UDP_CHUNK_SIZE.toLong(), actualFileSize - offset).toInt().coerceAtLeast(0)
                            if (len <= 0) continue
                            raf.seek(offset)
                            raf.readFully(readBuf, 0, len)
                            val seq = seqBase + idx
                            val pkt = encodeDataPacket(seq, readBuf, len)
                            repeat(workerInitialDupSend) {
                                socket.send(DatagramPacket(pkt, pkt.size, endpoint))
                                sentPayloadBytes.addAndGet(len.toLong())
                                threadSentPackets++
                            }
                            logThreadPktStat()
                            inflight[seq] = pkt
                            inflightSize[seq] = len
                            inflightTime[seq] = System.currentTimeMillis()
                        }

                        if (inflight.isEmpty() && nextChunk.get() >= totalChunks) break

                        try {
                            val inBuf = ByteArray(256)
                            val incoming = DatagramPacket(inBuf, inBuf.size)
                            socket.receive(incoming)
                            threadRecvPackets++
                            logThreadPktStat()
                            val ackSeq = decodeAckSeq(incoming)
                            if (ackSeq != null && inflight.containsKey(ackSeq)) {
                                inflight.remove(ackSeq)
                                inflightTime.remove(ackSeq)
                                val bytes = inflightSize.remove(ackSeq)?.toLong() ?: 0L
                                if (bytes > 0L) {
                                    ackedBytes.addAndGet(bytes)
                                    ackedChunks.incrementAndGet()
                                    val ackAt = System.currentTimeMillis()
                                    lastAckAt.set(ackAt)
                                    threadLastAckAt = ackAt
                                    threadRepunchAttempts = 0
                                    if (!threadActiveLogged) {
                                        threadActiveLogged = true
                                        flowLog(
                                            "SEND",
                                            sid,
                                            "THREAD_ACTIVE",
                                            "thread=${workerIndex + 1} local=$channelLocalPort remote=${incoming.address.hostAddress}:${incoming.port} via=ack"
                                        )
                                    }
                                }
                            } else {
                                when (incoming.data[incoming.offset]) {
                                    PKT_PUNCH -> {
                                        val ack = byteArrayOf(PKT_PUNCH_ACK)
                                        socket.send(DatagramPacket(ack, ack.size, incoming.address, incoming.port))
                                        threadSentPackets++
                                        logThreadPktStat()
                                        threadLastAckAt = System.currentTimeMillis()
                                        endpoint = InetSocketAddress(incoming.address, incoming.port)
                                        workerRemotePort = incoming.port
                                        threadRepunchAttempts = 0
                                    }
                                    PKT_PUNCH_ACK -> {
                                        threadLastAckAt = System.currentTimeMillis()
                                        endpoint = InetSocketAddress(incoming.address, incoming.port)
                                        workerRemotePort = incoming.port
                                        threadRepunchAttempts = 0
                                    }
                                }
                            }
                        } catch (_: SocketTimeoutException) {
                            val nowTimeout = System.currentTimeMillis()
                            val stalledMs = nowTimeout - threadLastAckAt
                            if (inflight.isNotEmpty() && stalledMs >= threadRepunchIdleMs && threadRepunchAttempts < threadRepunchMax) {
                                val repunchPortHint =
                                    workerPortCandidates.getOrNull(threadRepunchAttempts % workerPortCandidates.size)
                                        ?: workerRemotePort
                                flowLog(
                                    "SEND",
                                    sid,
                                    "THREAD_REPUNCH_PRIME",
                                    "thread=${workerIndex + 1} attempt=${threadRepunchAttempts + 1}/$threadRepunchMax local=$channelLocalPort remotePortHint=$repunchPortHint candidates=${workerPortCandidates.size}"
                                )
                                val prepunched = runCatching {
                                    prewarmUdpCandidates(
                                        socket = socket,
                                        remoteHost = remoteHost,
                                        remotePorts = workerPortCandidates,
                                        isCancelled = { isCancelled() || transferDone.get() }
                                    )
                                }.getOrNull()
                                if (prepunched == null) {
                                    threadRepunchAttempts++
                                    threadLastAckAt = nowTimeout
                                    flowLog(
                                        "SEND",
                                        sid,
                                        "THREAD_REPUNCH_PRIME_FAIL",
                                        "thread=${workerIndex + 1} attempt=$threadRepunchAttempts/$threadRepunchMax local=$channelLocalPort remotePortHint=$repunchPortHint"
                                    )
                                    continue
                                }
                                flowLog(
                                    "SEND",
                                    sid,
                                    "THREAD_REPUNCH_PRIME_OK",
                                    "thread=${workerIndex + 1} local=$channelLocalPort from=${prepunched.address.hostAddress}:${prepunched.port}"
                                )
                                val repunchCandidates = intArrayOf(prepunched.port) + workerPortCandidates
                                flowLog(
                                    "SEND",
                                    sid,
                                    "THREAD_REPUNCH_TRY",
                                    "thread=${workerIndex + 1} attempt=${threadRepunchAttempts + 1}/$threadRepunchMax local=$channelLocalPort remotePortHint=$repunchPortHint candidates=${workerPortCandidates.size} stalledMs=$stalledMs inflight=${inflight.size}"
                                )
                                val repunched = runCatching {
                                    establishUdpPunchMulti(
                                        socket = socket,
                                        remoteHost = remoteHost,
                                        remotePorts = repunchCandidates,
                                        timeoutMs = if (turboFastMode) 1_100L else if (sprintModeEnabled) 2_000L else 1_500L,
                                        probeBurst = if (turboFastMode) 2 else if (sprintModeEnabled) 3 else 2,
                                        sendIntervalMs = if (turboFastMode) 180L else if (sprintModeEnabled) 110L else 150L,
                                        recvTimeoutMs = if (turboFastMode) 240 else if (sprintModeEnabled) 130 else 180,
                                        isCancelled = { isCancelled() || transferDone.get() }
                                    )
                                }.getOrNull()
                                if (repunched != null) {
                                    endpoint = repunched
                                    workerRemotePort = repunched.port
                                    threadLastAckAt = nowTimeout
                                    threadRepunchAttempts = 0
                                    flowLog(
                                        "SEND",
                                        sid,
                                        "THREAD_REPUNCH_OK",
                                        "thread=${workerIndex + 1} local=$channelLocalPort remote=${repunched.address.hostAddress}:${repunched.port}"
                                    )
                                    flowLog(
                                        "SEND",
                                        sid,
                                        "THREAD_STATE",
                                        "thread=${workerIndex + 1} status=reconnected local=$channelLocalPort remote=${repunched.address.hostAddress}:${repunched.port}"
                                    )
                                } else {
                                    threadRepunchAttempts++
                                    threadLastAckAt = nowTimeout
                                    flowLog(
                                        "SEND",
                                        sid,
                                        "THREAD_REPUNCH_FAIL",
                                        "thread=${workerIndex + 1} attempt=$threadRepunchAttempts/$threadRepunchMax local=$channelLocalPort remotePortHint=$repunchPortHint candidates=${workerPortCandidates.size}"
                                    )
                                }
                            }
                        }

                        val now = System.currentTimeMillis()
                        inflight.forEach { (seq, pkt) ->
                            val ts = inflightTime[seq] ?: now
                            if (now - ts >= workerRetxMs) {
                                socket.send(DatagramPacket(pkt, pkt.size, endpoint))
                                sentPayloadBytes.addAndGet((inflightSize[seq] ?: 0).toLong())
                                threadSentPackets++
                                logThreadPktStat()
                                inflightTime[seq] = now
                            }
                        }
                    }
                } finally {
                    logThreadPktStat(force = true)
                    runCatching { raf.close() }
                    if (ownSocket) {
                        runCatching { socket.close() }
                    }
                }
            }
        }

        while (ackedChunks.get() < totalChunks) {
            if (isCancelled()) {
                workers.forEach { it.cancel() }
                throw CancellationException("用户已中断")
            }
            delay(120)
            if (!reuseChannels && openedChannels.get() <= 0 && workers.all { it.isCompleted }) {
                throw IllegalStateException("并行通道建立失败")
            }
            if (!reuseChannels && openedChannels.get() <= 0 && System.currentTimeMillis() >= channelOpenDeadlineAt) {
                workers.forEach { it.cancel() }
                throw IllegalStateException("并行通道建立超时")
            }
            val globalIdleMs = System.currentTimeMillis() - lastAckAt.get()
            if (globalIdleMs > UDP_IDLE_TIMEOUT_MS) {
                if (controlRepunchAttempts < controlRepunchMax) {
                    controlRepunchAttempts++
                    flowLog(
                        "SEND",
                        sid,
                        "CONTROL_REPUNCH_TRY",
                        "attempt=$controlRepunchAttempts/$controlRepunchMax idleMs=$globalIdleMs control=${controlEndpoint.address.hostAddress}:${controlEndpoint.port}"
                    )
                    val renewedEndpoint = runCatching {
                        establishUdpPunch(
                            socket = controlSocket,
                            remoteHost = remoteHost,
                            remotePort = remotePort,
                            timeoutMs = if (turboFastMode) 2_500L else if (sprintModeEnabled) 6_000L else 4_000L,
                            probeBurst = if (turboFastMode) 2 else if (sprintModeEnabled) 4 else 3,
                            sendIntervalMs = if (turboFastMode) 185L else if (sprintModeEnabled) 115L else 165L,
                            recvTimeoutMs = if (turboFastMode) 230 else if (sprintModeEnabled) 150 else 220,
                            labCompatMode = true,
                            isCancelled = isCancelled
                        )
                    }.getOrNull()
                    if (renewedEndpoint != null) {
                        controlEndpoint = renewedEndpoint
                        lastAckAt.set(System.currentTimeMillis())
                        flowLog(
                            "SEND",
                            sid,
                            "CONTROL_REPUNCH_OK",
                            "attempt=$controlRepunchAttempts endpoint=${renewedEndpoint.address.hostAddress}:${renewedEndpoint.port}"
                        )
                        continue
                    } else {
                        lastAckAt.set(System.currentTimeMillis())
                        flowLog("SEND", sid, "CONTROL_REPUNCH_FAIL", "attempt=$controlRepunchAttempts")
                        continue
                    }
                }
                workers.forEach { it.cancel() }
                throw IllegalStateException("发送超时：长时间未收到确认")
            }
            val currentAcked = ackedBytes.get().coerceAtMost(actualFileSize)
            val now = System.currentTimeMillis()
            if (now - lastSpeedAt >= 500L) {
                val delta = currentAcked - lastSpeedBytes
                speedBytes = ((delta * 1000L) / (now - lastSpeedAt).coerceAtLeast(1L)).coerceAtLeast(0L)
                lastSpeedBytes = currentAcked
                lastSpeedAt = now
            }
            val sentPayloadNow = sentPayloadBytes.get().coerceAtLeast(0L)
            val efficiency = if (sentPayloadNow > 0L) {
                ((currentAcked.toDouble() * 100.0) / sentPayloadNow.toDouble()).coerceIn(0.0, 100.0).toFloat()
            } else {
                0f
            }
            onProgress(
                UdpTransferProgress(
                    progress = (currentAcked.toFloat() / total).coerceIn(0f, 1f),
                    stage = "发送中：$currentAcked / $actualFileSize（${openedChannels.get().coerceAtLeast(1)}/${workerCount}线程）",
                    fileName = fileName,
                    totalBytes = actualFileSize,
                    transferredBytes = currentAcked,
                    speedBytesPerSec = speedBytes,
                    sendEfficiencyPercent = efficiency,
                    threadSpeedsBytesPerSec = emptyList()
                )
            )
        }
        transferDone.set(true)
        val finalSentPayload = sentPayloadBytes.get().coerceAtLeast(0L)
        val finalEfficiency = if (finalSentPayload > 0L) {
            ((ackedBytes.get().toDouble() * 100.0) / finalSentPayload.toDouble()).coerceIn(0.0, 100.0)
        } else {
            0.0
        }
        flowLog(
            "SEND",
            sid,
            "DATA_DONE",
            "ackedChunks=${ackedChunks.get()}/$totalChunks bytes=${ackedBytes.get()}/$actualFileSize sentPayload=$finalSentPayload efficiency=${"%.2f".format(finalEfficiency)}%"
        )
        workers.forEach { it.await() }
        runCatching { sourceFile.delete() }

        val fin = encodeJsonPacket(PKT_FIN, JSONObject().put("sid", sid))
        var finAck = false
        var finAttempts = 0
        val finMaxAttempts = if (turboFastMode) 14 else if (sprintModeEnabled) 36 else 28
        repeat(finMaxAttempts) {
            if (isCancelled()) throw CancellationException("用户已中断")
            if (finAck) return@repeat
            finAttempts++
            repeat(if (turboFastMode) 1 else if (sprintModeEnabled) 3 else 2) {
                controlSocket.send(DatagramPacket(fin, fin.size, controlEndpoint))
            }
            try {
                val inBuf = ByteArray(2048)
                val incoming = DatagramPacket(inBuf, inBuf.size)
                controlSocket.receive(incoming)
                val (type, json) = parseJsonPacket(incoming)
                if (type == PKT_FIN_ACK && json?.optString("sid") == sid) {
                    finAck = true
                    flowLog("SEND", sid, "FIN_ACK_OK", "attempts=$finAttempts from=${incoming.address.hostAddress}:${incoming.port}")
                }
            } catch (_: SocketTimeoutException) {
            }
        }
        if (!finAck) {
            flowLog("SEND", sid, "FIN_ACK_TIMEOUT", "attempts=$finAttempts softComplete=true")
            onProgress(
                UdpTransferProgress(
                    1f,
                    "数据已发送完成，完成确认超时（按成功处理）",
                    fileName,
                    actualFileSize,
                    actualFileSize,
                    0L,
                    sendEfficiencyPercent = finalEfficiency.toFloat()
                )
            )
            flowLog("SEND", sid, "FINISH", "file=$fileName finAck=false")
            return@withContext "传输完成（完成确认超时，按成功处理）：$fileName"
        }

        onProgress(
            UdpTransferProgress(
                1f,
                "传输完成",
                fileName,
                actualFileSize,
                actualFileSize,
                0L,
                sendEfficiencyPercent = finalEfficiency.toFloat()
            )
        )
        flowLog("SEND", sid, "FINISH", "file=$fileName")
        "传输完成：$fileName"
    } finally {
        runCatching { sourceFile.delete() }
        if (ownControl) {
            runCatching { controlSocket.close() }
        }
    }
}

private fun drainControlSocket(socket: DatagramSocket, maxPackets: Int = 64) {
    val oldTimeout = socket.soTimeout
    try {
        socket.soTimeout = 1
        repeat(maxPackets) {
            try {
                val buf = ByteArray(2048)
                val pkt = DatagramPacket(buf, buf.size)
                socket.receive(pkt)
            } catch (_: SocketTimeoutException) {
                return
            } catch (_: Exception) {
                return
            }
        }
    } finally {
        runCatching { socket.soTimeout = oldTimeout }
    }
}

private suspend fun openSendRuntimeSession(
    localPort: Int,
    remoteHost: String,
    remotePort: Int,
    requestedThreads: Int,
    punchTimeoutMs: Long? = UDP_PUNCH_TIMEOUT_MS,
    isCancelled: () -> Boolean = { false }
): UdpSendRuntimeSession = withContext(Dispatchers.IO) {
    val controlSocket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("::"), localPort))
    }
    try {
        val endpoint = establishUdpPunch(controlSocket, remoteHost, remotePort, timeoutMs = punchTimeoutMs, labCompatMode = true, isCancelled = isCancelled)
            ?: throw IllegalStateException("打洞超时")
        UdpSendRuntimeSession(
            controlSocket = controlSocket,
            controlEndpoint = endpoint,
            channels = emptyList(),
            requestedThreads = requestedThreads.coerceIn(1, UDP_MAX_THREADS)
        )
    } catch (e: Exception) {
        runCatching { controlSocket.close() }
        throw e
    }
}

private suspend fun openReceiveRuntimeSession(
    localPort: Int,
    remoteHost: String,
    remotePort: Int,
    punchTimeoutMs: Long? = UDP_PUNCH_TIMEOUT_MS,
    isCancelled: () -> Boolean = { false }
): UdpReceiveRuntimeSession = withContext(Dispatchers.IO) {
    val controlSocket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("::"), localPort))
    }
    try {
        val endpoint = establishUdpPunch(controlSocket, remoteHost, remotePort, timeoutMs = punchTimeoutMs, labCompatMode = true, isCancelled = isCancelled)
            ?: throw IllegalStateException("打洞超时")
        UdpReceiveRuntimeSession(
            controlSocket = controlSocket,
            controlEndpoint = endpoint
        )
    } catch (e: Exception) {
        runCatching { controlSocket.close() }
        throw e
    }
}

private fun closeSendRuntimeSession(session: UdpSendRuntimeSession) {
    session.channels.forEach { runCatching { it.socket.close() } }
    runCatching { session.controlSocket.close() }
}

private fun closeReceiveRuntimeSession(session: UdpReceiveRuntimeSession) {
    session.channels.forEach { runCatching { it.socket.close() } }
    runCatching { session.controlSocket.close() }
}

private suspend fun receiveFileByUdpPunch(
    context: Context,
    localPort: Int,
    remoteHost: String,
    remotePort: Int,
    runtimeSession: UdpReceiveRuntimeSession? = null,
    punchTimeoutMs: Long? = UDP_CONTROL_FLOOD_TIMEOUT_MS,
    metaWaitTimeoutMs: Long = UDP_META_WAIT_TIMEOUT_MS,
    stabilityModeEnabled: Boolean = false,
    turboModeEnabled: Boolean = false,
    perPortStunProbeEnabled: Boolean = false,
    isCancelled: () -> Boolean = { false },
    onProgress: (UdpTransferProgress) -> Unit
): UdpReceiveResult = withContext(Dispatchers.IO) {
    val sprintModeEnabled = stabilityModeEnabled || turboModeEnabled
    val turboFastMode = turboModeEnabled
    val tuning = resolveTransportTuning(stabilityModeEnabled, turboFastMode)
    val ownControl = runtimeSession == null
    val socket = runtimeSession?.controlSocket ?: DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("::"), localPort))
    }
    try {
        onProgress(UdpTransferProgress(0f, "等待发送端元信息...", "", 0L, 0L, 0L))
        flowLog("RECV", stage = "START", detail = "local=$localPort remote=$remoteHost:$remotePort")
        var endpoint = runtimeSession?.controlEndpoint ?: run {
            val floodTimeoutMs = punchTimeoutMs
            var sentPackets = 0L
            var lastProgressNotifyAt = 0L
            flowLog("RECV", stage = "PUNCH_TRY", detail = "attempt=1 timeoutMs=${floodTimeoutMs ?: -1L} mode=flood")
            val e = establishUdpPunch(
                socket = socket,
                remoteHost = remoteHost,
                remotePort = remotePort,
                timeoutMs = floodTimeoutMs,
                probeBurst = if (turboFastMode) 5 else if (sprintModeEnabled) 8 else 6,
                sendIntervalMs = if (turboFastMode) 90L else if (sprintModeEnabled) 70L else 85L,
                recvTimeoutMs = if (turboFastMode) 100 else if (sprintModeEnabled) 120 else 150,
                labCompatMode = true,
                onSendStat = { sent, remainingMs ->
                    sentPackets = sent
                    val now = System.currentTimeMillis()
                    if (now - lastProgressNotifyAt >= UDP_CONTROL_SEND_STATE_UPDATE_MS || (floodTimeoutMs != null && remainingMs <= 0L)) {
                        lastProgressNotifyAt = now
                        val remainingText = if (floodTimeoutMs == null) "无限" else "${((remainingMs + 999L) / 1000L).coerceAtLeast(0L)} 秒"
                        flowLog("RECV", stage = "PUNCH_SEND_STATE", detail = "controlSent=$sent remainingMs=$remainingMs")
                        onProgress(UdpTransferProgress(0.01f, "发包状态：控制信道已发 $sent 包，剩余 $remainingText", "", 0L, 0L, 0L))
                    }
                },
                isCancelled = isCancelled
            )
            if (e == null) {
                throw IllegalStateException(
                    if (floodTimeoutMs == null) "打洞失败（未建立连接，控制发包=$sentPackets）"
                    else "打洞超时（30秒灌包结束，控制发包=$sentPackets）"
                )
            }
            e
        }
            ?: throw IllegalStateException("打洞超时")
        flowLog("RECV", stage = "PUNCH_OK", detail = "controlEndpoint=${endpoint.address.hostAddress}:${endpoint.port} prePrime=true")
        if (runtimeSession == null) {
            onProgress(UdpTransferProgress(0.02f, "打洞成功，等待发送端元信息", "", 0L, 0L, 0L))
        }

        if (runtimeSession == null) {
            drainControlSocket(socket)
        }
        socket.soTimeout = 300
        var meta: UdpTransferMeta? = null
        var senderDataEndpoints = emptyList<UdpMappedEndpoint>()
        var senderChannelPortMode = UDP_CHANNEL_PORT_MODE_PREDICT
        var helloReplyEndpoint: InetSocketAddress? = null
        val metaStartAt = System.currentTimeMillis()
        var metaDeadline = metaStartAt + metaWaitTimeoutMs.coerceAtLeast(1000L)
        var metaExtendCount = 0
        val metaMaxExtends = if (turboFastMode) 3 else if (sprintModeEnabled) 5 else 4
        var lastProbeAt = 0L
        var lastMetaNotifySecond = -1L
        var helloPackets = 0
        while (meta == null) {
            if (isCancelled()) throw CancellationException("用户已中断")
            val now = System.currentTimeMillis()
            if (now >= metaDeadline) {
                if (metaExtendCount < metaMaxExtends) {
                    val extraMs = if (turboFastMode) 4_000L else if (sprintModeEnabled) 10_000L else 8_000L
                    metaDeadline = now + extraMs
                    metaExtendCount++
                    val reason = if (helloPackets == 0) "noHello" else "helloSeen=$helloPackets"
                    flowLog(
                        "RECV",
                        stage = "META_EXTEND",
                        detail = "extraMs=$extraMs count=$metaExtendCount/$metaMaxExtends reason=$reason"
                    )
                    onProgress(
                        UdpTransferProgress(
                            0.02f,
                            "高延迟网络，延长元信息等待窗口（$metaExtendCount/$metaMaxExtends）...",
                            "",
                            0L,
                            0L,
                            0L
                        )
                    )
                    continue
                }
                break
            }
            if (now - lastProbeAt >= if (turboFastMode) 420L else if (sprintModeEnabled) 180L else 320L) {
                lastProbeAt = now
                runCatching {
                    val keepAlive = byteArrayOf(PKT_PUNCH)
                    repeat(if (turboFastMode) 1 else if (sprintModeEnabled) 3 else 2) {
                        socket.send(DatagramPacket(keepAlive, keepAlive.size, endpoint))
                    }
                }
            }
            val elapsedSec = ((now - metaStartAt) / 1000L).coerceAtLeast(0L)
            if (elapsedSec != lastMetaNotifySecond && elapsedSec > 0L && helloPackets == 0) {
                lastMetaNotifySecond = elapsedSec
                onProgress(UdpTransferProgress(0.02f, "等待发送端元信息...(${elapsedSec}s)", "", 0L, 0L, 0L))
            }
            try {
                val inBuf = ByteArray(4096)
                val incoming = DatagramPacket(inBuf, inBuf.size)
                socket.receive(incoming)
                val (type, json) = parseJsonPacket(incoming)
                if (type == PKT_HELLO && json != null) {
                    helloPackets++
                    helloReplyEndpoint = InetSocketAddress(incoming.address, incoming.port)
                    val sid = json.optString("sid")
                    val name = json.optString("name")
                    val mime = json.optString("mime").ifBlank { "application/octet-stream" }
                    val size = json.optLong("size", -1L)
                    val threads = json.optInt("threads", UDP_DEFAULT_THREADS).coerceIn(1, UDP_MAX_THREADS)
                    val profile = json.optString("profile", UDP_PROFILE_CLASSIC).ifBlank { UDP_PROFILE_CLASSIC }
                    val batchId = json.optString("batchId").ifBlank { sid }
                    val batchTotal = json.optInt("batchTotal", 1).coerceAtLeast(1)
                    val batchIndex = json.optInt("batchIndex", 1).coerceIn(1, batchTotal)
                    val packedZip = json.optBoolean("packedZip", false)
                    val packedCount = json.optInt("packedCount", 1).coerceAtLeast(1)
                    if (sid.isNotBlank() && name.isNotBlank() && size >= 0L) {
                        Log.i(
                            UDP_LOG_TAG,
                            "recv got HELLO sid=$sid from=${incoming.address.hostAddress}:${incoming.port} size=$size threads=$threads"
                        )
                        meta = UdpTransferMeta(
                            sid = sid,
                            name = name,
                            mime = mime,
                            size = size,
                            threads = threads,
                            profile = profile,
                            batchId = batchId,
                            batchIndex = batchIndex,
                            batchTotal = batchTotal,
                            seqBase = json.optInt("seqBase", 0),
                            packedZip = packedZip,
                            packedCount = packedCount
                        )
                        senderDataEndpoints = parseMappedEndpoints(json, "senderDataEndpoints", "senderDataPorts").take(threads)
                        senderChannelPortMode = json.optString("channelPortMode", UDP_CHANNEL_PORT_MODE_PREDICT)
                        senderDataEndpoints.forEachIndexed { idx, ep ->
                            val host = ep.host.ifBlank { incoming.address.hostAddress }
                            flowLog("RECV", sid, "THREAD_MAP_REMOTE", "thread=${idx + 1} peerPublic=$host:${ep.port}")
                        }
                    } else {
                        Log.w(
                            UDP_LOG_TAG,
                            "recv drop HELLO invalid sid='${sid.take(16)}' nameBlank=${name.isBlank()} size=$size"
                        )
                    }
                } else if (type == PKT_PUNCH) {
                    val ack = byteArrayOf(PKT_PUNCH_ACK)
                    socket.send(DatagramPacket(ack, ack.size, incoming.address, incoming.port))
                }
            } catch (_: SocketTimeoutException) {
            }
        }
        if (meta == null) {
            flowLog(
                "RECV",
                stage = "META_TIMEOUT",
                detail = "waitMs=${System.currentTimeMillis() - metaStartAt} helloPackets=$helloPackets extendCount=$metaExtendCount/$metaMaxExtends"
            )
        }
        val transferMeta = meta ?: throw IllegalStateException("协商超时：未收到发送端元信息")
        flowLog(
            "RECV",
            transferMeta.sid,
            "HELLO_OK",
            "file=${transferMeta.name} size=${transferMeta.size} threads=${transferMeta.threads} profile=${transferMeta.profile} helloPackets=$helloPackets senderPortMode=$senderChannelPortMode senderPorts=${senderDataEndpoints.size}"
        )
        onProgress(
            UdpTransferProgress(
                progress = 0.025f,
                stage = if (transferMeta.profile == UDP_PROFILE_TURBO) {
                    "已收到发送端元信息（极速模式实验），正在建立并行通道..."
                } else if (transferMeta.profile == UDP_PROFILE_SPRINT) {
                    "已收到发送端元信息（稳定性增强实验），正在建立并行通道..."
                } else {
                    "已收到发送端元信息，正在建立并行通道..."
                },
                fileName = transferMeta.name,
                totalBytes = transferMeta.size,
                transferredBytes = 0L,
                speedBytesPerSec = 0L
            )
        )
        val receiverPredictedEndpoints = probeChannelMappedEndpointsIpv6(
            baseLocalPort = localPort,
            threadCount = transferMeta.threads,
            enabled = false
        )
        var receiverDataEndpoints = receiverPredictedEndpoints
        val receiverProbeDeferred: kotlinx.coroutines.Deferred<List<UdpMappedEndpoint>>? = null
        if (perPortStunProbeEnabled) {
            onProgress(UdpTransferProgress(0.028f, "正在探测并回传各线程端口映射...", transferMeta.name, transferMeta.size, 0L, 0L))
            val probeTimeoutMs = if (turboFastMode) 2_800L else if (sprintModeEnabled) 4_200L else 5_200L
            receiverDataEndpoints = kotlinx.coroutines.withTimeoutOrNull(probeTimeoutMs) {
                probeChannelMappedEndpointsIpv6(
                    baseLocalPort = localPort,
                    threadCount = transferMeta.threads,
                    enabled = true
                )
            } ?: receiverPredictedEndpoints
        }
        receiverDataEndpoints.forEachIndexed { idx, ep ->
            val host = ep.host.ifBlank { endpoint.address.hostAddress }
            flowLog(
                "RECV",
                transferMeta.sid,
                "THREAD_MAP_LOCAL",
                "thread=${idx + 1} public=$host:${ep.port} local=${localPort + 1 + idx} mode=${if (perPortStunProbeEnabled) UDP_CHANNEL_PORT_MODE_PER_STUN else UDP_CHANNEL_PORT_MODE_PREDICT}"
            )
        }
        val ready = encodeJsonPacket(
            PKT_READY,
            JSONObject()
                .put("sid", transferMeta.sid)
                .put("threads", transferMeta.threads)
                .put("channelPortMode", if (perPortStunProbeEnabled) UDP_CHANNEL_PORT_MODE_PER_STUN else UDP_CHANNEL_PORT_MODE_PREDICT)
                .put("receiverDataEndpoints", mappedEndpointsToJsonArray(receiverDataEndpoints))
                .put("receiverDataPorts", JSONArray().apply { receiverDataEndpoints.forEach { put(it.port) } })
        )
        val readyTargets = linkedSetOf<InetSocketAddress>().apply {
            helloReplyEndpoint?.let { add(it) }
            add(endpoint)
        }
        repeat(tuning.readyBurstCount) {
            readyTargets.forEach { target ->
                socket.send(DatagramPacket(ready, ready.size, target))
            }
            delay(tuning.readyBurstIntervalMs)
        }
        flowLog(
            "RECV",
            transferMeta.sid,
            "READY_SENT",
            "threads=${transferMeta.threads} portMode=${if (perPortStunProbeEnabled) UDP_CHANNEL_PORT_MODE_PER_STUN else UDP_CHANNEL_PORT_MODE_PREDICT} receiverPorts=${receiverDataEndpoints.size} targets=${readyTargets.joinToString { "${it.address.hostAddress}:${it.port}" }}"
        )
        val readyKeepAlive = AtomicBoolean(true)
        val readyKeepAliveJob = kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext).async {
            while (readyKeepAlive.get() && !isCancelled()) {
                readyTargets.forEach { target ->
                    runCatching { socket.send(DatagramPacket(ready, ready.size, target)) }
                }
                delay(tuning.readyKeepaliveIntervalMs)
            }
        }
        val readyResendMinIntervalMs = if (turboFastMode) 320L else if (sprintModeEnabled) 180L else 260L
        val readyResendMaxBursts = if (turboFastMode) 12 else if (sprintModeEnabled) 48 else 24
        var lastReadyResendAt = 0L
        var readyResendBursts = 0
        val reuseChannels = runtimeSession != null && runtimeSession.channels.isNotEmpty()
        if (reuseChannels && runtimeSession.agreedThreads > 0 && runtimeSession.agreedThreads != transferMeta.threads) {
            throw IllegalStateException("并行线程数变更，无法复用会话")
        }
        val workerCount = if (reuseChannels) runtimeSession.channels.size else transferMeta.threads
        val mappedLater = receiverProbeDeferred?.let { kotlinx.coroutines.withTimeoutOrNull(1L) { it.await() } }
        if (!mappedLater.isNullOrEmpty()) {
            receiverDataEndpoints = mappedLater
            receiverDataEndpoints.forEachIndexed { idx, ep ->
                val host = ep.host.ifBlank { endpoint.address.hostAddress }
                flowLog(
                    "RECV",
                    transferMeta.sid,
                    "THREAD_MAP_LOCAL",
                    "thread=${idx + 1} public=$host:${ep.port} local=${localPort + 1 + idx} mode=${if (perPortStunProbeEnabled) UDP_CHANNEL_PORT_MODE_PER_STUN else UDP_CHANNEL_PORT_MODE_PREDICT} async=true"
                )
            }
        }
        val workerAckDupSend = if (workerCount >= 24) 1 else tuning.ackDupSend
        val dynamicSockets = mutableListOf<DatagramSocket>()
        val openedChannels = AtomicInteger(if (reuseChannels) workerCount else 0)
        val channelOpenDeadlineAt = if (reuseChannels) {
            Long.MAX_VALUE
        } else {
            System.currentTimeMillis() + if (turboFastMode) 18_000L else if (sprintModeEnabled) 40_000L else 25_000L
        }
        if (reuseChannels) {
            flowLog("RECV", transferMeta.sid, "CHANNEL_REUSE", "count=${runtimeSession.channels.size}")
        } else {
            flowLog("RECV", transferMeta.sid, "CHANNEL_LAZY_OPEN", "expected=$workerCount")
        }
        onProgress(
            UdpTransferProgress(
                progress = 0.03f,
                stage = "元信息已就绪，开始接收（最多${workerCount}线程）",
                fileName = transferMeta.name,
                totalBytes = transferMeta.size,
                transferredBytes = 0L,
                speedBytesPerSec = 0L
            )
        )

        val fileSeqBase = transferMeta.seqBase
        val downloadDir = FileDownloadManager.getDownloadDirectory(context).apply { mkdirs() }
        val outFile = uniqueTargetFile(downloadDir, transferMeta.name)
        var receivedBytes = 0L
        var speedBytes = 0L
        var lastSpeedBytes = 0L
        var lastSpeedAt = System.currentTimeMillis()
        val lastDataAt = AtomicLong(System.currentTimeMillis())
        val total = transferMeta.size.coerceAtLeast(1L)
        val receivedFlags = BooleanArray(((transferMeta.size + UDP_CHUNK_SIZE - 1L) / UDP_CHUNK_SIZE).toInt().coerceAtLeast(0))
        val perThreadReceived = AtomicLongArray(workerCount)
        val threadSpeeds = LongArray(workerCount)
        val lastThreadBytes = LongArray(workerCount)
        val writeLock = Any()
        val speedLock = Any()
        val raf = RandomAccessFile(outFile, "rw")
        val workerJobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
        var completed = false
        var allReceivedAt = 0L
        var finalizeDeadlineAt = 0L
        val receiveDone = AtomicBoolean(false)
        var controlRepunchAttempts = 0
        val controlRepunchMax = if (turboFastMode) 1 else if (sprintModeEnabled) 4 else 2
        try {
            raf.setLength(transferMeta.size.coerceAtLeast(0L))
            workerJobs += (0 until workerCount).map { workerIndex ->
                kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext).async {
                    var ownSocket = false
                    val channelLocalPort = localPort + 1 + workerIndex
                    val predictedRemotePort = remotePort + 1 + workerIndex
                    var channelRemotePort = senderDataEndpoints.getOrNull(workerIndex)?.port ?: predictedRemotePort
                    val channelPortCandidates = buildChannelPortCandidates(
                        primaryPort = channelRemotePort,
                        predictedPort = predictedRemotePort,
                        controlPort = remotePort,
                        extraPorts = senderDataEndpoints.map { it.port }.toIntArray(),
                        sprintModeEnabled = sprintModeEnabled
                    )
                    var endpointReady: InetSocketAddress? = null
                    val s: DatagramSocket
                    if (reuseChannels) {
                        val channel = runtimeSession!!.channels[workerIndex]
                        s = channel.socket
                        endpointReady = channel.endpoint
                        channelRemotePort = channel.endpoint.port
                    } else {
                        ownSocket = true
                        var openedSocket: DatagramSocket? = null
                        while (endpointReady == null) {
                            if (isCancelled() || receiveDone.get()) {
                                runCatching { openedSocket?.close() }
                                return@async
                            }
                            if (System.currentTimeMillis() >= channelOpenDeadlineAt) {
                                flowLog("RECV", transferMeta.sid, "CHANNEL_OPEN_TIMEOUT", "index=${workerIndex + 1} opened=${openedChannels.get()}/$workerCount")
                                runCatching { openedSocket?.close() }
                                return@async
                            }
                            openedSocket = runCatching {
                                DatagramSocket(null).apply {
                                    reuseAddress = true
                                    bind(InetSocketAddress(InetAddress.getByName("::"), channelLocalPort))
                                }
                        }.getOrNull()
                        if (openedSocket == null) {
                            delay(tuning.channelDialRetryMs)
                            continue
                        }
                            channelRemotePort = channelPortCandidates.firstOrNull() ?: predictedRemotePort
                            flowLog(
                                "RECV",
                                transferMeta.sid,
                                "THREAD_PUNCH_PRIME",
                                "thread=${workerIndex + 1} local=$channelLocalPort targets=${channelPortCandidates.size}"
                            )
                            val primedEndpoint = prewarmUdpCandidates(
                                socket = openedSocket,
                                remoteHost = remoteHost,
                                remotePorts = channelPortCandidates,
                                isCancelled = { isCancelled() || receiveDone.get() }
                            )
                            if (primedEndpoint != null) {
                                flowLog(
                                    "RECV",
                                    transferMeta.sid,
                                    "THREAD_PUNCH_PRIME_OK",
                                    "thread=${workerIndex + 1} from=${primedEndpoint.address.hostAddress}:${primedEndpoint.port}"
                                )
                            }
                            flowLog(
                                "RECV",
                                transferMeta.sid,
                                "THREAD_PUNCH_TRY",
                                "thread=${workerIndex + 1} local=$channelLocalPort candidates=${channelPortCandidates.size} head=${channelPortCandidates.take(6).joinToString(",")}"
                            )
                            endpointReady = primedEndpoint ?: try {
                                establishUdpPunchMulti(
                                    socket = openedSocket,
                                    remoteHost = remoteHost,
                                    remotePorts = channelPortCandidates,
                                    timeoutMs = UDP_CHANNEL_DIAL_ATTEMPT_TIMEOUT_MS,
                                    probeBurst = if (turboFastMode) 2 else if (sprintModeEnabled) 3 else 2,
                                    sendIntervalMs = if (turboFastMode) 170L else if (sprintModeEnabled) 120L else 170L,
                                    recvTimeoutMs = if (turboFastMode) 220 else if (sprintModeEnabled) 130 else 200,
                                    isCancelled = { isCancelled() || receiveDone.get() }
                                )
                            } catch (e: CancellationException) {
                                runCatching { openedSocket.close() }
                                if (isCancelled() || receiveDone.get()) return@async
                                null
                            } catch (_: Exception) {
                                null
                            }
                            if (endpointReady == null) {
                                runCatching { openedSocket.close() }
                                delay(tuning.channelDialRetryMs)
                            }
                        }
                        val readySocket = openedSocket ?: return@async
                        synchronized(dynamicSockets) { dynamicSockets += readySocket }
                        s = readySocket
                        channelRemotePort = endpointReady.port
                        val opened = openedChannels.incrementAndGet()
                        flowLog(
                            "RECV",
                            transferMeta.sid,
                            "CHANNEL_READY",
                            "index=${workerIndex + 1} opened=$opened/$workerCount endpoint=${endpointReady.address.hostAddress}:${endpointReady.port}"
                        )
                        val localMapped = receiverDataEndpoints.getOrNull(workerIndex)
                        val peerMapped = senderDataEndpoints.getOrNull(workerIndex)
                        val localPublicHost = localMapped?.host?.takeIf { it.isNotBlank() } ?: "predicted"
                        val localPublicPort = localMapped?.port ?: channelLocalPort
                        val peerPublicHost = peerMapped?.host?.takeIf { it.isNotBlank() } ?: endpointReady.address.hostAddress
                        val peerPublicPort = peerMapped?.port ?: endpointReady.port
                        flowLog(
                            "RECV",
                            transferMeta.sid,
                            "THREAD_PUNCH_OK",
                            "thread=${workerIndex + 1} localPublic=$localPublicHost:$localPublicPort peerPublic=$peerPublicHost:$peerPublicPort local=$channelLocalPort remote=${endpointReady.address.hostAddress}:${endpointReady.port} success=true"
                        )
                        flowLog(
                            "RECV",
                            transferMeta.sid,
                            "THREAD_STATE",
                            "thread=${workerIndex + 1} status=connected local=$channelLocalPort remote=${endpointReady.address.hostAddress}:${endpointReady.port}"
                        )
                        if (opened == 1) {
                            readyKeepAlive.set(false)
                            readyKeepAliveJob.cancel()
                        }
                    }
                    s.soTimeout = 500
                    var threadLastPacketAt = System.currentTimeMillis()
                    var threadRepunchAttempts = 0
                    var threadActiveLogged = false
                    var threadSentPackets = 0L
                    var threadRecvPackets = 0L
                    var threadLastPktStatAt = 0L
                    val threadRepunchMax = if (turboFastMode) 4 else if (sprintModeEnabled) 10 else 6
                    val threadRepunchIdleMs = if (turboFastMode) 5_500L else if (sprintModeEnabled) 2_800L else 4_500L
                    fun logThreadPktStat(force: Boolean = false) {
                        val now = System.currentTimeMillis()
                        if (force || now - threadLastPktStatAt >= UDP_THREAD_PKT_STAT_INTERVAL_MS) {
                            threadLastPktStatAt = now
                            flowLog(
                                "RECV",
                                transferMeta.sid,
                                "THREAD_PKT_STAT",
                                "thread=${workerIndex + 1} sent=$threadSentPackets recv=$threadRecvPackets"
                            )
                        }
                    }
                    try {
                        while (true) {
                            if (isCancelled() || receiveDone.get()) throw CancellationException("用户已中断")
                            try {
                                val inBuf = ByteArray(2048)
                                val incoming = DatagramPacket(inBuf, inBuf.size)
                                s.receive(incoming)
                                threadRecvPackets++
                                logThreadPktStat()
                                when (incoming.data[incoming.offset]) {
                                    PKT_DATA -> {
                                        val decoded = decodeDataPacket(incoming) ?: continue
                                        val (seq, len, payload) = decoded
                                        lastDataAt.set(System.currentTimeMillis())
                                        threadLastPacketAt = System.currentTimeMillis()
                                        threadRepunchAttempts = 0
                                        endpointReady = InetSocketAddress(incoming.address, incoming.port)
                                        channelRemotePort = incoming.port
                                        if (!threadActiveLogged) {
                                            threadActiveLogged = true
                                            flowLog(
                                                "RECV",
                                                transferMeta.sid,
                                                "THREAD_ACTIVE",
                                                "thread=${workerIndex + 1} local=$channelLocalPort remote=${incoming.address.hostAddress}:${incoming.port} via=data"
                                            )
                                        }
                                        val relSeq = seq - fileSeqBase
                                        if (relSeq >= 0 && relSeq < receivedFlags.size) {
                                            var newly = false
                                            synchronized(writeLock) {
                                                if (!receivedFlags[relSeq]) {
                                                    val offset = relSeq.toLong() * UDP_CHUNK_SIZE.toLong()
                                                    raf.seek(offset)
                                                    raf.write(payload, 0, len)
                                                    receivedFlags[relSeq] = true
                                                    receivedBytes += len
                                                    newly = true
                                                }
                                            }
                                            val ack = encodeAckPacket(seq)
                                            repeat(workerAckDupSend) {
                                                s.send(DatagramPacket(ack, ack.size, incoming.address, incoming.port))
                                                threadSentPackets++
                                            }
                                            logThreadPktStat()
                                            if (newly) {
                                                perThreadReceived.addAndGet(workerIndex, len.toLong())
                                                val now = System.currentTimeMillis()
                                                synchronized(speedLock) {
                                                    if (now - lastSpeedAt >= 500L) {
                                                        val delta = receivedBytes - lastSpeedBytes
                                                        speedBytes = ((delta * 1000L) / (now - lastSpeedAt).coerceAtLeast(1L)).coerceAtLeast(0L)
                                                        for (i in threadSpeeds.indices) {
                                                            val current = perThreadReceived.get(i)
                                                            val threadDelta = current - lastThreadBytes[i]
                                                            threadSpeeds[i] =
                                                                ((threadDelta * 1000L) / (now - lastSpeedAt).coerceAtLeast(1L)).coerceAtLeast(0L)
                                                            lastThreadBytes[i] = current
                                                        }
                                                        lastSpeedBytes = receivedBytes
                                                        lastSpeedAt = now
                                                    }
                                                }
                                                onProgress(
                                                    UdpTransferProgress(
                                                        progress = (receivedBytes.toFloat() / total).coerceIn(0f, 1f),
                                                        stage = "接收中：$receivedBytes / ${transferMeta.size}",
                                                        fileName = transferMeta.name,
                                                        totalBytes = transferMeta.size,
                                                        transferredBytes = receivedBytes,
                                                        speedBytesPerSec = speedBytes,
                                                        threadSpeedsBytesPerSec = threadSpeeds.toList()
                                                    )
                                                )
                                                if (receivedBytes >= transferMeta.size && allReceivedAt == 0L) {
                                                    allReceivedAt = System.currentTimeMillis()
                                                }
                                            }
                                        }
                                    }
                                    PKT_PUNCH -> {
                                        val ack = byteArrayOf(PKT_PUNCH_ACK)
                                        s.send(DatagramPacket(ack, ack.size, incoming.address, incoming.port))
                                        threadSentPackets++
                                        logThreadPktStat()
                                        threadLastPacketAt = System.currentTimeMillis()
                                        endpointReady = InetSocketAddress(incoming.address, incoming.port)
                                        channelRemotePort = incoming.port
                                    }
                                    PKT_PUNCH_ACK -> {
                                        threadLastPacketAt = System.currentTimeMillis()
                                        endpointReady = InetSocketAddress(incoming.address, incoming.port)
                                        channelRemotePort = incoming.port
                                        threadRepunchAttempts = 0
                                    }
                                }
                            } catch (_: SocketTimeoutException) {
                                val nowTimeout = System.currentTimeMillis()
                                if (nowTimeout - threadLastPacketAt >= threadRepunchIdleMs && threadRepunchAttempts < threadRepunchMax) {
                                    val repunchPortHint =
                                        channelPortCandidates.getOrNull(threadRepunchAttempts % channelPortCandidates.size)
                                            ?: channelRemotePort
                                    flowLog(
                                        "RECV",
                                        transferMeta.sid,
                                        "THREAD_REPUNCH_PRIME",
                                        "thread=${workerIndex + 1} attempt=${threadRepunchAttempts + 1}/$threadRepunchMax local=$channelLocalPort remotePortHint=$repunchPortHint candidates=${channelPortCandidates.size}"
                                    )
                                    val prepunched = runCatching {
                                        prewarmUdpCandidates(
                                            socket = s,
                                            remoteHost = remoteHost,
                                            remotePorts = channelPortCandidates,
                                            isCancelled = { isCancelled() || receiveDone.get() }
                                        )
                                    }.getOrNull()
                                    if (prepunched == null) {
                                        threadRepunchAttempts++
                                        threadLastPacketAt = nowTimeout
                                        flowLog(
                                            "RECV",
                                            transferMeta.sid,
                                            "THREAD_REPUNCH_PRIME_FAIL",
                                            "thread=${workerIndex + 1} attempt=$threadRepunchAttempts/$threadRepunchMax local=$channelLocalPort remotePortHint=$repunchPortHint"
                                        )
                                        continue
                                    }
                                    flowLog(
                                        "RECV",
                                        transferMeta.sid,
                                        "THREAD_REPUNCH_PRIME_OK",
                                        "thread=${workerIndex + 1} local=$channelLocalPort from=${prepunched.address.hostAddress}:${prepunched.port}"
                                    )
                                    val repunchCandidates = intArrayOf(prepunched.port) + channelPortCandidates
                                    flowLog(
                                        "RECV",
                                        transferMeta.sid,
                                        "THREAD_REPUNCH_TRY",
                                        "thread=${workerIndex + 1} attempt=${threadRepunchAttempts + 1}/$threadRepunchMax local=$channelLocalPort remotePortHint=$repunchPortHint candidates=${channelPortCandidates.size} stalledMs=${nowTimeout - threadLastPacketAt}"
                                    )
                                    val repunched = runCatching {
                                        establishUdpPunchMulti(
                                            socket = s,
                                            remoteHost = remoteHost,
                                            remotePorts = repunchCandidates,
                                            timeoutMs = if (turboFastMode) 1_100L else if (sprintModeEnabled) 2_000L else 1_500L,
                                            probeBurst = if (turboFastMode) 2 else if (sprintModeEnabled) 3 else 2,
                                            sendIntervalMs = if (turboFastMode) 180L else if (sprintModeEnabled) 110L else 150L,
                                            recvTimeoutMs = if (turboFastMode) 240 else if (sprintModeEnabled) 130 else 180,
                                            isCancelled = { isCancelled() || receiveDone.get() }
                                        )
                                    }.getOrNull()
                                    if (repunched != null) {
                                        endpointReady = repunched
                                        channelRemotePort = repunched.port
                                        threadLastPacketAt = nowTimeout
                                        threadRepunchAttempts = 0
                                        flowLog(
                                            "RECV",
                                            transferMeta.sid,
                                            "THREAD_REPUNCH_OK",
                                            "thread=${workerIndex + 1} local=$channelLocalPort remote=${repunched.address.hostAddress}:${repunched.port}"
                                        )
                                        flowLog(
                                            "RECV",
                                            transferMeta.sid,
                                            "THREAD_STATE",
                                            "thread=${workerIndex + 1} status=reconnected local=$channelLocalPort remote=${repunched.address.hostAddress}:${repunched.port}"
                                        )
                                    } else {
                                        threadRepunchAttempts++
                                        threadLastPacketAt = nowTimeout
                                        flowLog(
                                            "RECV",
                                            transferMeta.sid,
                                            "THREAD_REPUNCH_FAIL",
                                            "thread=${workerIndex + 1} attempt=$threadRepunchAttempts/$threadRepunchMax local=$channelLocalPort remotePortHint=$repunchPortHint candidates=${channelPortCandidates.size}"
                                        )
                                    }
                                }
                                if (System.currentTimeMillis() - lastDataAt.get() > UDP_IDLE_TIMEOUT_MS) break
                            } catch (_: Exception) {
                            }
                        }
                    } finally {
                        logThreadPktStat(force = true)
                        if (ownSocket) {
                            runCatching { s.close() }
                        }
                    }
                }
            }

            socket.soTimeout = 120
            while (true) {
                if (isCancelled()) throw CancellationException("用户已中断")
                val now = System.currentTimeMillis()
                if (allReceivedAt > 0L && finalizeDeadlineAt == 0L && now - allReceivedAt >= UDP_FIN_WAIT_AFTER_DATA_MS) {
                    val finAck = encodeJsonPacket(PKT_FIN_ACK, JSONObject().put("sid", transferMeta.sid))
                    repeat(3) {
                        runCatching { socket.send(DatagramPacket(finAck, finAck.size, endpoint)) }
                    }
                    finalizeDeadlineAt = now + 1_200L
                    flowLog("RECV", transferMeta.sid, "FINALIZE_ENTER", "receivedBytes=$receivedBytes total=${transferMeta.size}")
                }
                if (!completed && finalizeDeadlineAt > 0L && now >= finalizeDeadlineAt) {
                    completed = true
                    receiveDone.set(true)
                    flowLog("RECV", transferMeta.sid, "FINALIZE_DONE", "reason=timeoutAfterData")
                    break
                }
                try {
                    val inBuf = ByteArray(2048)
                    val incoming = DatagramPacket(inBuf, inBuf.size)
                    socket.receive(incoming)
                    when (incoming.data[incoming.offset]) {
                        PKT_FIN -> {
                            val (_, json) = parseJsonPacket(incoming)
                            if (json?.optString("sid") == transferMeta.sid) {
                                val finAck = encodeJsonPacket(PKT_FIN_ACK, JSONObject().put("sid", transferMeta.sid))
                                repeat(3) {
                                    runCatching { socket.send(DatagramPacket(finAck, finAck.size, incoming.address, incoming.port)) }
                                    if (incoming.address != endpoint.address || incoming.port != endpoint.port) {
                                        runCatching { socket.send(DatagramPacket(finAck, finAck.size, endpoint)) }
                                    }
                                }
                                completed = true
                                receiveDone.set(true)
                                flowLog("RECV", transferMeta.sid, "FIN_RX", "from=${incoming.address.hostAddress}:${incoming.port}")
                                break
                            }
                        }
                        PKT_HELLO -> {
                            val (_, json) = parseJsonPacket(incoming)
                            if (json?.optString("sid") == transferMeta.sid) {
                                val nowResend = System.currentTimeMillis()
                                val canResend = openedChannels.get() == 0 &&
                                    readyResendBursts < readyResendMaxBursts &&
                                    nowResend - lastReadyResendAt >= readyResendMinIntervalMs
                                if (canResend) {
                                    val readyAgain = encodeJsonPacket(
                                        PKT_READY,
                                        JSONObject().put("sid", transferMeta.sid).put("threads", transferMeta.threads)
                                    )
                                    repeat(if (turboFastMode) 1 else if (sprintModeEnabled) 3 else 2) {
                                        runCatching { socket.send(DatagramPacket(readyAgain, readyAgain.size, incoming.address, incoming.port)) }
                                        runCatching { socket.send(DatagramPacket(readyAgain, readyAgain.size, endpoint)) }
                                    }
                                    readyResendBursts++
                                    lastReadyResendAt = nowResend
                                    flowLog("RECV", transferMeta.sid, "READY_RESEND", "to=${incoming.address.hostAddress}:${incoming.port} burst=$readyResendBursts/$readyResendMaxBursts")
                                }
                            }
                        }
                        PKT_PUNCH -> {
                            val ack = byteArrayOf(PKT_PUNCH_ACK)
                            socket.send(DatagramPacket(ack, ack.size, incoming.address, incoming.port))
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    if (finalizeDeadlineAt > 0L) {
                        continue
                    }
                    if (System.currentTimeMillis() - lastDataAt.get() > UDP_IDLE_TIMEOUT_MS) {
                        if (controlRepunchAttempts < controlRepunchMax) {
                            controlRepunchAttempts++
                            flowLog(
                                "RECV",
                                transferMeta.sid,
                                "CONTROL_REPUNCH_TRY",
                                "attempt=$controlRepunchAttempts/$controlRepunchMax idleMs=${System.currentTimeMillis() - lastDataAt.get()} control=${endpoint.address.hostAddress}:${endpoint.port}"
                            )
                            val renewedEndpoint = runCatching {
                                establishUdpPunch(
                                    socket = socket,
                                    remoteHost = remoteHost,
                                    remotePort = remotePort,
                                    timeoutMs = if (turboFastMode) 2_500L else if (sprintModeEnabled) 6_000L else 4_000L,
                                    probeBurst = if (turboFastMode) 2 else if (sprintModeEnabled) 4 else 3,
                                    sendIntervalMs = if (turboFastMode) 185L else if (sprintModeEnabled) 115L else 165L,
                                    recvTimeoutMs = if (turboFastMode) 230 else if (sprintModeEnabled) 150 else 220,
                                    labCompatMode = true,
                                    isCancelled = isCancelled
                                )
                            }.getOrNull()
                            if (renewedEndpoint != null) {
                                endpoint = renewedEndpoint
                                lastDataAt.set(System.currentTimeMillis())
                                flowLog(
                                    "RECV",
                                    transferMeta.sid,
                                    "CONTROL_REPUNCH_OK",
                                    "attempt=$controlRepunchAttempts endpoint=${renewedEndpoint.address.hostAddress}:${renewedEndpoint.port}"
                                )
                                continue
                            } else {
                                lastDataAt.set(System.currentTimeMillis())
                                flowLog("RECV", transferMeta.sid, "CONTROL_REPUNCH_FAIL", "attempt=$controlRepunchAttempts")
                                continue
                            }
                        }
                        if (!reuseChannels && openedChannels.get() <= 0 && System.currentTimeMillis() >= channelOpenDeadlineAt) {
                            throw IllegalStateException("接收超时：并行通道建立超时")
                        }
                        if (!reuseChannels && openedChannels.get() <= 0 && workerJobs.all { it.isCompleted }) {
                            throw IllegalStateException("接收超时：并行通道建立失败")
                        }
                        throw IllegalStateException("接收超时：长时间未收到数据")
                    }
                }
            }
        } finally {
            receiveDone.set(true)
            readyKeepAlive.set(false)
            readyKeepAliveJob.cancel()
            flowLog("RECV", transferMeta.sid, "FINALIZE_CLEANUP_BEGIN", "completed=$completed")
            workerJobs.forEach { it.cancel() }
            // Close sockets first so blocked receive() exits immediately.
            if (runtimeSession == null) {
                synchronized(dynamicSockets) {
                    dynamicSockets.forEach { runCatching { it.close() } }
                }
            }
            // Do not wait workers; finish UI path immediately after sockets are closed.
            runCatching { raf.close() }
            if (!completed) {
                runCatching { outFile.delete() }
            }
            flowLog("RECV", transferMeta.sid, "FINALIZE_CLEANUP_END", "completed=$completed")
        }

        if (completed) {
            runCatching {
                DownloadHistoryManager(context).addRecord(
                    DownloadRecord(
                        fileName = outFile.name,
                        filePath = outFile.absolutePath,
                        fileSize = outFile.length(),
                        sourceUrl = "udp-punch://${endpoint.address.hostAddress}:${endpoint.port}"
                    )
                )
            }
        }
        onProgress(
            UdpTransferProgress(
                progress = 1f,
                stage = "接收完成",
                fileName = outFile.name,
                totalBytes = outFile.length(),
                transferredBytes = outFile.length(),
                speedBytesPerSec = 0L
            )
        )
        if (!completed) throw IllegalStateException("文件未接收完整，已丢弃")
        flowLog("RECV", transferMeta.sid, "FINISH", "file=${outFile.name} size=${outFile.length()}")
        UdpReceiveResult(
            fileName = outFile.name,
            filePath = outFile.absolutePath,
            fileSize = outFile.length(),
            batchId = transferMeta.batchId,
            batchIndex = transferMeta.batchIndex,
            batchTotal = transferMeta.batchTotal,
            packedZip = transferMeta.packedZip,
            packedCount = transferMeta.packedCount
        )
    } finally {
        if (ownControl) {
            runCatching { socket.close() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Ipv6UdpEnhancedSendScreen(
    onBack: () -> Unit,
    initialFileUris: List<Uri> = emptyList(),
    onInitialFilesConsumed: () -> Unit = {},
    onRequestSwitchToReceive: (() -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val localView = LocalView.current
    DisposableEffect(localView) {
        val prev = localView.keepScreenOn
        localView.keepScreenOn = true
        onDispose { localView.keepScreenOn = prev }
    }
    val scope = rememberCoroutineScope()
    var selectedFiles by remember { mutableStateOf<List<UdpSendFileItem>>(emptyList()) }
    var localIpv6 by remember { mutableStateOf("") }
    var localPortInput by remember { mutableStateOf("18080") }
    var remoteHostInput by remember { mutableStateOf("") }
    var remotePortInput by remember { mutableStateOf("18080") }
    var threadInput by remember { mutableStateOf(UDP_DEFAULT_THREADS.toString()) }
    var unlimitedPunchEnabled by remember { mutableStateOf(false) }
    var sprintModeEnabled by remember { mutableStateOf(false) }
    var turboModeEnabled by remember { mutableStateOf(false) }
    var perPortStunProbeEnabled by remember { mutableStateOf(false) }
    var natEnabled by remember { mutableStateOf(false) }
    var natPublicIpv6 by remember { mutableStateOf("") }
    var natPublicPort by remember { mutableStateOf<Int?>(null) }
    var stunProbeResult by remember { mutableStateOf<StunProbeBatchResult?>(null) }
    var showStunPicker by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("请先选择文件并配置打洞参数。") }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var showProgressSheet by remember { mutableStateOf(false) }
    var progressFileName by remember { mutableStateOf("") }
    var progressTotalBytes by remember { mutableStateOf(0L) }
    var progressTransferredBytes by remember { mutableStateOf(0L) }
    var progressSpeed by remember { mutableStateOf(0L) }
    var progressEfficiency by remember { mutableStateOf<Float?>(null) }
    var sendDoneCount by remember { mutableStateOf(0) }
    var sendTotalCount by remember { mutableStateOf(0) }
    var sendOverallTransferredBytes by remember { mutableStateOf(0L) }
    var sendOverallTotalBytes by remember { mutableStateOf(0L) }
    var sendCompleted by remember { mutableStateOf(false) }
    var compressMultiAsZip by remember { mutableStateOf(false) }
    var showInstalledApkPicker by remember { mutableStateOf(false) }
    val cancelSignal = remember { AtomicBoolean(false) }
    val handshakeLogs = remember { mutableStateListOf<String>() }
    DisposableEffect(Unit) {
        val unregister = addUdpHandshakeLogListener { line ->
            if (!line.contains("[SEND]")) return@addUdpHandshakeLogListener
            scope.launch {
                handshakeLogs += line
                val overflow = handshakeLogs.size - UDP_HANDSHAKE_LOG_LIMIT
                if (overflow > 0) repeat(overflow) { handshakeLogs.removeAt(0) }
            }
        }
        onDispose {
            cancelSignal.set(true)
            unregister()
        }
    }

    fun currentAdvertiseHost(): String {
        return if (natEnabled && natPublicIpv6.isNotBlank()) natPublicIpv6 else localIpv6
    }

    fun currentAdvertisePort(localPort: Int): Int {
        return if (natEnabled && (natPublicPort in 1..65535)) natPublicPort!! else localPort
    }

    fun refreshLocalInfo() {
        scope.launch {
            val ipv6 = resolveIpv6()
            val port = localPortInput.toIntOrNull()
            if (ipv6.isNullOrBlank()) {
                status = "未获取到可用 IPv6。"
                return@launch
            }
            localIpv6 = ipv6
            natPublicIpv6 = ""
            natPublicPort = null
            stunProbeResult = null
            var batch: StunProbeBatchResult? = null
            if (natEnabled && port != null && port in 1..65535) {
                batch = withContext(Dispatchers.IO) {
                    NetworkUtils.probeStunMappedEndpointBatch(
                        localPort = port,
                        preferIpv6 = true,
                        transport = StunTransportType.UDP
                    )
                }
                stunProbeResult = batch?.takeIf { it.endpoints.isNotEmpty() }
                val mapped = batch?.preferredEndpoint
                if (mapped != null) {
                    natPublicIpv6 = mapped.address
                    natPublicPort = mapped.port
                }
                if (batch?.allMismatch == true && stunProbeResult != null) {
                    showStunPicker = true
                }
            }
            if (port != null && port in 1..65535) {
                val advertiseHost = currentAdvertiseHost()
                val advertisePort = currentAdvertisePort(port)
                qrBitmap = QRCodeGenerator.generateQRCode(
                    buildLocalPeerPayload(advertiseHost, advertisePort, role = UDP_QR_ROLE_SEND),
                    512
                )
                status = if (qrBitmap == null) {
                    "IPv6 已更新，二维码生成失败。"
                } else if (natEnabled && natPublicPort != null) {
                    when {
                        batch?.allMismatch == true -> "已刷新本机 IPv6，公网映射端口：${natPublicPort}（与 ipip 不一致，请手动选择）。"
                        batch?.matchedByIpip?.isNotEmpty() == true -> "已刷新本机 IPv6，公网映射端口：${natPublicPort}（存在 ipip 匹配项，默认首个 STUN 结果）。"
                        else -> "已刷新本机 IPv6，公网映射端口：${natPublicPort}。"
                    }
                } else {
                    "已刷新本机 IPv6 与二维码。"
                }
            } else {
                status = "已刷新本机 IPv6。"
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshLocalInfo()
    }
    LaunchedEffect(initialFileUris) {
        if (initialFileUris.isEmpty()) return@LaunchedEffect
        val files = mutableListOf<UdpSendFileItem>()
        for (u in initialFileUris.distinct()) {
            val meta = queryFileMeta(context, u) ?: continue
            files += UdpSendFileItem(uri = u, name = meta.first, mime = meta.second, size = meta.third)
        }
        if (files.isNotEmpty()) {
            selectedFiles = files
            status = "已导入外部分享文件 ${files.size} 个。"
        } else {
            status = "外部分享文件读取失败。"
        }
        onInitialFilesConsumed()
    }

    InstalledApkPickerSheet(
        visible = showInstalledApkPicker,
        context = context,
        onDismiss = { showInstalledApkPicker = false },
        onConfirm = { selectedApps ->
            showInstalledApkPicker = false
            scope.launch {
                if (selectedApps.isEmpty()) {
                    status = "未选择应用。"
                    return@launch
                }
                status = "正在提取 ${selectedApps.size} 个应用 APK..."
                val shares = extractInstalledApkShares(context, selectedApps)
                if (shares.isEmpty()) {
                    status = "提取应用 APK 失败。"
                    return@launch
                }
                selectedFiles = shares.map {
                    UdpSendFileItem(
                        uri = it.uri,
                        name = it.fileName,
                        mime = it.mimeType,
                        size = it.sizeBytes
                    )
                }
                status = "已提取 ${shares.size} 个应用 APK。"
            }
        }
    )

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val meta = queryFileMeta(context, uri)
            if (meta == null) {
                status = "读取文件信息失败。"
                return@launch
            }
            selectedFiles = listOf(UdpSendFileItem(uri = uri, name = meta.first, mime = meta.second, size = meta.third))
            status = "已选择文件：${meta.first}"
        }
    }
    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val files = mutableListOf<UdpSendFileItem>()
            for (u in uris.distinct()) {
                val meta = queryFileMeta(context, u) ?: continue
                files += UdpSendFileItem(uri = u, name = meta.first, mime = meta.second, size = meta.third)
            }
            if (files.isEmpty()) {
                status = "读取多文件失败。"
            } else {
                selectedFiles = files
                status = "已选择 ${files.size} 个文件（将串行传输）"
            }
        }
    }

    if (showScanner) {
        QRCodeScanner(
            onQRCodeScanned = { content ->
                showScanner = false
                val parsed = parsePeerFromQr(content)
                if (parsed == null) {
                    status = "二维码不是有效的 IPv6 增强地址。"
                } else {
                    if (parsed.role == UDP_QR_ROLE_SEND) {
                        status = "检测到这是发送端二维码，已自动切换到接收端。"
                        onRequestSwitchToReceive?.invoke()
                        return@QRCodeScanner
                    }
                    val peer = parsed.peer
                    remoteHostInput = peer.host
                    remotePortInput = peer.port.toString()
                    status = if (selectedFiles.isEmpty()) {
                        "已识别接收端：${peer.host}:${peer.port}。请先选择文件后发送。"
                    } else {
                        "已识别接收端：${peer.host}:${peer.port}"
                    }
                }
            },
            onDismiss = { showScanner = false }
        )
    }

    StunEndpointPickerDialog(
        visible = showStunPicker,
        title = "选择 IPv6 STUN 地址（发送端）",
        result = stunProbeResult,
        selected = stunProbeResult
            ?.endpoints
            ?.firstOrNull { it.address == natPublicIpv6 && it.port == natPublicPort },
        onSelect = { chosen ->
            natPublicIpv6 = chosen.address
            natPublicPort = chosen.port
            val localPort = localPortInput.toIntOrNull()
            if (localPort != null && localPort in 1..65535) {
                val payload = buildLocalPeerPayload(
                    currentAdvertiseHost(),
                    currentAdvertisePort(localPort),
                    role = UDP_QR_ROLE_SEND
                )
                qrBitmap = QRCodeGenerator.generateQRCode(payload, 512)
            }
            status = "已切换 STUN 地址：${chosen.address}:${chosen.port}（${chosen.stunServer}）"
        },
        onDismiss = { showStunPicker = false }
    )

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), enabled = !running) { Text("返回发送页") }
        Text("IPv6 增强传输（发送端）", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("流程：先选文件，再交换二维码/地址，打洞成功后立即传输。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("说明：支持中断传输；中断后当前未完成文件会丢弃，已完成文件保留；不支持断点续传。", color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch("*/*") }, modifier = Modifier.weight(1f), enabled = !running) { Text("1. 选单文件") }
            OutlinedButton(onClick = { multiPicker.launch("*/*") }, modifier = Modifier.weight(1f), enabled = !running) { Text("多文件模式") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val one = extractCurrentAppApkShare(context)
                        if (one == null) {
                            status = "提取当前应用 APK 失败。"
                        } else {
                            selectedFiles = listOf(
                                UdpSendFileItem(
                                    uri = one.uri,
                                    name = one.fileName,
                                    mime = one.mimeType,
                                    size = one.sizeBytes
                                )
                            )
                            status = "已提取当前应用 APK：${one.fileName}"
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !running
            ) { Text("提取本应用 APK") }
            OutlinedButton(
                onClick = { showInstalledApkPicker = true },
                modifier = Modifier.weight(1f),
                enabled = !running
            ) { Text("提取其他应用 APK") }
        }
        if (selectedFiles.isNotEmpty()) {
            Text("文件数：${selectedFiles.size}（串行逐个传输）", fontWeight = FontWeight.SemiBold)
            Text(
                text = "当前文件：${selectedFiles.first().name}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val totalSize = selectedFiles.sumOf { it.size.coerceAtLeast(0L) }
            if (totalSize > 0L) Text("总大小：${formatTransferSize(totalSize)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selectedFiles.size > 1) {
                if (compressMultiAsZip) {
                    Button(
                        onClick = { compressMultiAsZip = false },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("已启用：压缩后发送（ZIP）") }
                } else {
                    OutlinedButton(
                        onClick = { compressMultiAsZip = true },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("未启用：压缩后发送（ZIP）") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. 我的打洞信息", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = localIpv6,
                    onValueChange = { localIpv6 = it.trim() },
                    label = { Text("本机 IPv6") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
                OutlinedTextField(
                    value = localPortInput,
                    onValueChange = { localPortInput = it.filter(Char::isDigit) },
                    label = { Text("本地端口") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
                OutlinedTextField(
                    value = threadInput,
                    onValueChange = { threadInput = it.filter(Char::isDigit) },
                    label = { Text("线程数（默认8，最大128）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!sprintModeEnabled && !turboModeEnabled) {
                        Button(
                            onClick = { },
                            modifier = Modifier.weight(0.9f),
                            enabled = !running
                        ) { Text("标准") }
                    } else {
                        OutlinedButton(
                            onClick = {
                                sprintModeEnabled = false
                                turboModeEnabled = false
                            },
                            modifier = Modifier.weight(0.9f),
                            enabled = !running
                        ) { Text("标准") }
                    }
                    if (sprintModeEnabled) {
                        Button(
                            onClick = { },
                            modifier = Modifier.weight(1.2f),
                            enabled = !running
                        ) { Text("稳定性增强") }
                    } else {
                        OutlinedButton(
                            onClick = {
                                sprintModeEnabled = true
                                turboModeEnabled = false
                            },
                            modifier = Modifier.weight(1.2f),
                            enabled = !running
                        ) { Text("稳定性增强") }
                    }
                    if (turboModeEnabled) {
                        Button(
                            onClick = { },
                            modifier = Modifier.weight(0.9f),
                            enabled = !running
                        ) { Text("极速") }
                    } else {
                        OutlinedButton(
                            onClick = {
                                turboModeEnabled = true
                                sprintModeEnabled = false
                            },
                            modifier = Modifier.weight(0.9f),
                            enabled = !running
                        ) { Text("极速") }
                    }
                }
                Text(
                    text = when {
                        turboModeEnabled -> "极速会尽量减少重复包与校验冗余，优先拉高吞吐；弱网下完整性与成功率会下降。"
                        sprintModeEnabled -> "稳定性增强会提高握手与重传频率，并延长阶段等待时间（仍为 UDP 打洞协议）。"
                        else -> "标准在速度与稳定性之间取平衡。"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("启用 NAT 端口展示", fontWeight = FontWeight.SemiBold)
                        Text(
                            "通过 UDP 多 STUN 探测公网暴露端口；监听仍使用本地端口。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = natEnabled,
                        onCheckedChange = {
                            natEnabled = it
                            if (!it) perPortStunProbeEnabled = false
                            natPublicIpv6 = ""
                            natPublicPort = null
                            refreshLocalInfo()
                        },
                        enabled = !running
                    )
                }
                if (natEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("通道端口策略", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (perPortStunProbeEnabled) {
                                    "逐端口 STUN 探测：每个并行端口单独探测公网端口后再交换。"
                                } else {
                                    "端口预测：按基准端口 + 偏移推导并行端口（默认）。"
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = perPortStunProbeEnabled,
                            onCheckedChange = { perPortStunProbeEnabled = it },
                            enabled = !running
                        )
                    }
                } else {
                    Text(
                        text = "通道端口策略：默认端口预测（开启 NAT 端口展示后可切换为逐端口 STUN）。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("打洞时间无限制", fontWeight = FontWeight.SemiBold)
                        Text(
                            "开启后会持续发包直到建立连接或手动中断。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = unlimitedPunchEnabled,
                        onCheckedChange = { unlimitedPunchEnabled = it },
                        enabled = !running
                    )
                }
                if (natEnabled) {
                    val shownHost = if (natPublicIpv6.isBlank()) "-" else natPublicIpv6
                    val shownPort = natPublicPort?.toString() ?: "-"
                    Text(
                        text = "公网映射：$shownHost:$shownPort（本地监听端口：${localPortInput.ifBlank { "-" }}）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (stunProbeResult?.endpoints?.isNotEmpty() == true) {
                        OutlinedButton(
                            onClick = { showStunPicker = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !running
                        ) {
                            Text("选择 STUN 地址")
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { refreshLocalInfo() },
                        modifier = Modifier.weight(1f),
                        enabled = !running
                    ) { Text("刷新 IPv6+二维码") }
                    OutlinedButton(
                        onClick = {
                            val port = localPortInput.toIntOrNull()
                            if (localIpv6.isBlank() || port == null || port !in 1..65535) {
                                status = "请填写有效的本机 IPv6 和端口。"
                                return@OutlinedButton
                            }
                            val advertiseHost = currentAdvertiseHost()
                            val advertisePort = currentAdvertisePort(port)
                            val payload = buildLocalPeerPayload(
                                advertiseHost,
                                advertisePort,
                                role = UDP_QR_ROLE_SEND
                            )
                            qrBitmap = QRCodeGenerator.generateQRCode(payload, 512)
                            if (qrBitmap == null) {
                                status = "二维码生成失败。"
                            } else {
                                copyText(context, "filetran_peer_info", payload)
                                status = "已生成并复制发送端信息（监听端口仍为 $port）。"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !running
                    ) { Text("生成并复制") }
                }
                if (qrBitmap != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "udp_send_qr",
                        modifier = Modifier.fillMaxWidth().height(260.dp)
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("3. 接收端信息", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = remoteHostInput,
                    onValueChange = { remoteHostInput = it.trim() },
                    label = { Text("接收端 IPv6/主机") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
                OutlinedTextField(
                    value = remotePortInput,
                    onValueChange = { remotePortInput = it.filter(Char::isDigit) },
                    label = { Text("接收端端口") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showScanner = true },
                        modifier = Modifier.weight(1f),
                        enabled = !running
                    ) { Text("扫码识别接收端") }
                    OutlinedButton(
                        onClick = {
                            val source = readClipboardText(context)
                            val parsed = parsePeerFromQr(source)
                            if (parsed == null) {
                                status = if (source.isBlank()) "剪贴板为空。" else "剪贴板内容无法识别。"
                            } else {
                                if (parsed.role == UDP_QR_ROLE_SEND) {
                                    status = "检测到这是发送端二维码，已自动切换到接收端。"
                                    onRequestSwitchToReceive?.invoke()
                                    return@OutlinedButton
                                }
                                val peer = parsed.peer
                                remoteHostInput = peer.host
                                remotePortInput = peer.port.toString()
                                status = if (selectedFiles.isEmpty()) {
                                    "已识别接收端：${peer.host}:${peer.port}。请先选择文件后发送。"
                                } else {
                                    "已识别接收端：${peer.host}:${peer.port}"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !running
                    ) { Text("读取剪贴板并识别") }
                }
                Button(
                    onClick = {
                        val lp = localPortInput.toIntOrNull()
                        val rp = remotePortInput.toIntOrNull()
                        val threads = threadInput.toIntOrNull()?.coerceIn(1, UDP_MAX_THREADS) ?: UDP_DEFAULT_THREADS
                        if (selectedFiles.isEmpty()) {
                            status = "请先选择文件。"
                            return@Button
                        }
                        if (lp == null || rp == null || lp !in 1..65535 || rp !in 1..65535 || remoteHostInput.isBlank()) {
                            status = "请填写有效的打洞参数。"
                            return@Button
                        }
                        handshakeLogs.clear()
                        running = true
                        cancelSignal.set(false)
                        progress = 0f
                        showProgressSheet = true
                        progressFileName = selectedFiles.first().name
                        progressTotalBytes = selectedFiles.first().size.coerceAtLeast(0L)
                        progressTransferredBytes = 0L
                        progressSpeed = 0L
                        progressEfficiency = null
                        sendDoneCount = 0
                        sendTotalCount = selectedFiles.size
                        sendOverallTransferredBytes = 0L
                        sendOverallTotalBytes = selectedFiles.sumOf { it.size.coerceAtLeast(0L) }
                        sendCompleted = false
                        status = when {
                            turboModeEnabled -> "准备传输（极速）..."
                            sprintModeEnabled -> "准备传输（稳定性增强）..."
                            else -> "准备传输..."
                        }
                        scope.launch {
                            runCatching {
                                val sendList = if (compressMultiAsZip && selectedFiles.size > 1) {
                                    status = "正在压缩文件..."
                                    val zip = createZipFromItems(context, selectedFiles)
                                    listOf(
                                        UdpSendFileItem(
                                            uri = Uri.fromFile(zip),
                                            name = "multi_files_${System.currentTimeMillis()}.zip",
                                            mime = "application/zip",
                                            size = zip.length()
                                        )
                                    )
                                } else {
                                    selectedFiles
                                }
                                sendTotalCount = sendList.size
                                sendOverallTotalBytes = sendList.sumOf { it.size.coerceAtLeast(0L) }
                                for (idx in sendList.indices) {
                                    if (cancelSignal.get()) throw CancellationException("用户已中断")
                                    val item = sendList[idx]
                                    val oneBatchId = UUID.randomUUID().toString()
                                    progress = 0f
                                    progressFileName = item.name
                                    progressTotalBytes = item.size.coerceAtLeast(0L)
                                    progressTransferredBytes = 0L
                                    progressSpeed = 0L
                                    progressEfficiency = null
                                    status = "准备发送第 ${idx + 1}/${sendList.size} 个文件..."
                                    sendFileByUdpPunch(
                                        context = context,
                                        fileUri = item.uri,
                                        fileName = item.name,
                                        mimeType = item.mime,
                                        fileSize = item.size.coerceAtLeast(0L),
                                        localPort = lp,
                                        remoteHost = remoteHostInput,
                                        remotePort = rp,
                                        requestedThreads = threads,
                                        batchId = oneBatchId,
                                        batchIndex = 1,
                                        batchTotal = 1,
                                        packedZip = compressMultiAsZip && selectedFiles.size > 1,
                                        packedCount = if (compressMultiAsZip && selectedFiles.size > 1) selectedFiles.size else 1,
                                        punchTimeoutMs = if (unlimitedPunchEnabled) null else UDP_CONTROL_FLOOD_TIMEOUT_MS,
                                        stabilityModeEnabled = sprintModeEnabled,
                                        turboModeEnabled = turboModeEnabled,
                                        perPortStunProbeEnabled = natEnabled && perPortStunProbeEnabled,
                                        isCancelled = { cancelSignal.get() }
                                    ) { event ->
                                        progress = event.progress
                                        status = "(${idx + 1}/${sendList.size}) ${event.stage}"
                                        progressFileName = event.fileName.ifBlank { item.name }
                                        progressTotalBytes = event.totalBytes.coerceAtLeast(progressTotalBytes)
                                        progressTransferredBytes = event.transferredBytes.coerceAtLeast(0L)
                                        progressSpeed = event.speedBytesPerSec.coerceAtLeast(0L)
                                        progressEfficiency = event.sendEfficiencyPercent
                                        val doneBytes = sendList.take(idx).sumOf { it.size.coerceAtLeast(0L) }
                                        sendOverallTransferredBytes = (doneBytes + event.transferredBytes).coerceAtLeast(0L)
                                    }
                                    sendDoneCount = idx + 1
                                    sendOverallTransferredBytes = sendList.take(idx + 1).sumOf { it.size.coerceAtLeast(0L) }
                                    if (idx < sendList.lastIndex) {
                                        delay(320)
                                    }
                                }
                                if (compressMultiAsZip && selectedFiles.size > 1) {
                                    "压缩包发送完成（原始文件 ${selectedFiles.size} 个）"
                                } else {
                                    "全部文件发送完成（${sendList.size}）"
                                }
                            }.onSuccess {
                                status = it
                                sendCompleted = true
                            }.onFailure { e ->
                                status = if (e is CancellationException) {
                                    "已中断：当前未完成文件已丢弃，已完成文件已保留。"
                                } else {
                                    "发送失败：${e.message ?: "未知"}"
                                }
                                sendCompleted = false
                            }
                            running = false
                            cancelSignal.set(false)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !running
                ) { Text(if (running) "传输中..." else "开始打洞并立即发送") }
                if (running) {
                    OutlinedButton(
                        onClick = {
                            cancelSignal.set(true)
                            status = "正在中断传输..."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("中断传输/打洞") }
                }
            }
        }
        if (running || progress > 0f) {
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        if (!showProgressSheet && (running || progressTransferredBytes > 0L || sendCompleted)) {
            OutlinedButton(
                onClick = { showProgressSheet = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("查看发送抽屉")
            }
        }
        Text(status, color = if (status.startsWith("发送失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        UdpHandshakeLogCard(logs = handshakeLogs, onClear = { handshakeLogs.clear() })
        UdpRouteCard(logs = handshakeLogs, senderView = true)
    }

        if (showProgressSheet && (running || progressTransferredBytes > 0L || progressTotalBytes > 0L || sendCompleted)) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                showProgressSheet = false
            },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("发送抽屉", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                if (!running && sendCompleted) {
                    Text("发送完毕", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
                if (sendTotalCount > 0) {
                    val remain = (sendTotalCount - sendDoneCount).coerceAtLeast(0)
                    Text("文件进度：($sendDoneCount/$sendTotalCount)，剩余 $remain 个")
                }
                Text(
                    text = "文件： ${progressFileName.ifBlank { "-" }}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text("大小： ${if (progressTotalBytes > 0L) formatTransferSize(progressTotalBytes) else "-"}")
                Text("已发送：${formatTransferSize(progressTransferredBytes)}")
                if (sendOverallTotalBytes > 0L) {
                    Text("总已发送：${formatTransferSize(sendOverallTransferredBytes)} / ${formatTransferSize(sendOverallTotalBytes)}")
                    LinearProgressIndicator(
                        progress = { (sendOverallTransferredBytes.toFloat() / sendOverallTotalBytes.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text("速度： ${if (progressSpeed > 0L) "${formatTransferSize(progressSpeed)}/s" else "-"}")
                Text(
                    "发送效率： ${
                        progressEfficiency?.let { "${"%.2f".format(it.coerceIn(0f, 100f))}%" } ?: "-"
                    }"
                )
                val efficiencyProgress = ((progressEfficiency ?: 0f) / 100f).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { efficiencyProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { showProgressSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (running) "收起" else "关闭")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Ipv6UdpEnhancedReceiveScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val localView = LocalView.current
    DisposableEffect(localView) {
        val prev = localView.keepScreenOn
        localView.keepScreenOn = true
        onDispose { localView.keepScreenOn = prev }
    }
    val scope = rememberCoroutineScope()
    var localIpv6 by remember { mutableStateOf("") }
    var localPortInput by remember { mutableStateOf("18080") }
    var remoteHostInput by remember { mutableStateOf("") }
    var remotePortInput by remember { mutableStateOf("18080") }
    var unlimitedPunchEnabled by remember { mutableStateOf(false) }
    var sprintModeEnabled by remember { mutableStateOf(false) }
    var turboModeEnabled by remember { mutableStateOf(false) }
    var perPortStunProbeEnabled by remember { mutableStateOf(false) }
    var natEnabled by remember { mutableStateOf(false) }
    var natPublicIpv6 by remember { mutableStateOf("") }
    var natPublicPort by remember { mutableStateOf<Int?>(null) }
    var stunProbeResult by remember { mutableStateOf<StunProbeBatchResult?>(null) }
    var showStunPicker by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("请先交换双方地址，再开始接收。") }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var showProgressSheet by remember { mutableStateOf(false) }
    var progressFileName by remember { mutableStateOf("") }
    var progressTotalBytes by remember { mutableStateOf(0L) }
    var progressTransferredBytes by remember { mutableStateOf(0L) }
    var progressSpeed by remember { mutableStateOf(0L) }
    var progressThreadSpeeds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var receivedFiles by remember { mutableStateOf<List<UdpReceiveResult>>(emptyList()) }
    var receiveDoneCount by remember { mutableStateOf(0) }
    var receiveTotalCount by remember { mutableStateOf(0) }
    var autoUnzipAfterReceive by remember { mutableStateOf(false) }
    val cancelSignal = remember { AtomicBoolean(false) }
    val handshakeLogs = remember { mutableStateListOf<String>() }
    DisposableEffect(Unit) {
        val unregister = addUdpHandshakeLogListener { line ->
            if (!line.contains("[RECV]")) return@addUdpHandshakeLogListener
            scope.launch {
                handshakeLogs += line
                val overflow = handshakeLogs.size - UDP_HANDSHAKE_LOG_LIMIT
                if (overflow > 0) repeat(overflow) { handshakeLogs.removeAt(0) }
            }
        }
        onDispose {
            cancelSignal.set(true)
            unregister()
        }
    }

    fun currentAdvertiseHost(): String {
        return if (natEnabled && natPublicIpv6.isNotBlank()) natPublicIpv6 else localIpv6
    }

    fun currentAdvertisePort(localPort: Int): Int {
        return if (natEnabled && (natPublicPort in 1..65535)) natPublicPort!! else localPort
    }

    fun refreshLocalInfo() {
        scope.launch {
            val ipv6 = resolveIpv6()
            val port = localPortInput.toIntOrNull()
            if (ipv6.isNullOrBlank()) {
                status = "未获取到可用 IPv6。"
                return@launch
            }
            localIpv6 = ipv6
            natPublicIpv6 = ""
            natPublicPort = null
            stunProbeResult = null
            var batch: StunProbeBatchResult? = null
            if (natEnabled && port != null && port in 1..65535) {
                batch = withContext(Dispatchers.IO) {
                    NetworkUtils.probeStunMappedEndpointBatch(
                        localPort = port,
                        preferIpv6 = true,
                        transport = StunTransportType.UDP
                    )
                }
                stunProbeResult = batch?.takeIf { it.endpoints.isNotEmpty() }
                val mapped = batch?.preferredEndpoint
                if (mapped != null) {
                    natPublicIpv6 = mapped.address
                    natPublicPort = mapped.port
                }
                if (batch?.allMismatch == true && stunProbeResult != null) {
                    showStunPicker = true
                }
            }
            if (port != null && port in 1..65535) {
                val advertiseHost = currentAdvertiseHost()
                val advertisePort = currentAdvertisePort(port)
                qrBitmap = QRCodeGenerator.generateQRCode(
                    buildLocalPeerPayload(advertiseHost, advertisePort, role = UDP_QR_ROLE_RECV),
                    512
                )
                status = if (qrBitmap == null) {
                    "IPv6 已更新，二维码生成失败。"
                } else if (natEnabled && natPublicPort != null) {
                    when {
                        batch?.allMismatch == true -> "已刷新本机 IPv6，公网映射端口：${natPublicPort}（与 ipip 不一致，请手动选择）。"
                        batch?.matchedByIpip?.isNotEmpty() == true -> "已刷新本机 IPv6，公网映射端口：${natPublicPort}（存在 ipip 匹配项，默认首个 STUN 结果）。"
                        else -> "已刷新本机 IPv6，公网映射端口：${natPublicPort}。"
                    }
                } else {
                    "已刷新本机 IPv6 与二维码。"
                }
            } else {
                status = "已刷新本机 IPv6。"
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshLocalInfo()
    }

    if (showScanner) {
        QRCodeScanner(
            onQRCodeScanned = { content ->
                showScanner = false
                val parsed = parsePeerFromQr(content)
                if (parsed == null) {
                    status = "二维码不是有效的 IPv6 增强地址。"
                } else {
                    val peer = parsed.peer
                    remoteHostInput = peer.host
                    remotePortInput = peer.port.toString()
                    status = "已识别发送端：${peer.host}:${peer.port}"
                }
            },
            onDismiss = { showScanner = false }
        )
    }

    StunEndpointPickerDialog(
        visible = showStunPicker,
        title = "选择 IPv6 STUN 地址（接收端）",
        result = stunProbeResult,
        selected = stunProbeResult
            ?.endpoints
            ?.firstOrNull { it.address == natPublicIpv6 && it.port == natPublicPort },
        onSelect = { chosen ->
            natPublicIpv6 = chosen.address
            natPublicPort = chosen.port
            val localPort = localPortInput.toIntOrNull()
            if (localPort != null && localPort in 1..65535) {
                val payload = buildLocalPeerPayload(
                    currentAdvertiseHost(),
                    currentAdvertisePort(localPort),
                    role = UDP_QR_ROLE_RECV
                )
                qrBitmap = QRCodeGenerator.generateQRCode(payload, 512)
            }
            status = "已切换 STUN 地址：${chosen.address}:${chosen.port}（${chosen.stunServer}）"
        },
        onDismiss = { showStunPicker = false }
    )

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), enabled = !running) { Text("返回接收页") }
        Text("IPv6 增强传输（接收端）", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("流程：生成接收二维码，填写发送端地址，打洞成功后立即接收。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("说明：支持中断接收；中断后当前未完成文件会丢弃，已完成文件保留；不支持断点续传。", color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. 我的接收信息", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = localIpv6,
                    onValueChange = { localIpv6 = it.trim() },
                    label = { Text("本机 IPv6") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
                OutlinedTextField(
                    value = localPortInput,
                    onValueChange = { localPortInput = it.filter(Char::isDigit) },
                    label = { Text("本地端口") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!sprintModeEnabled && !turboModeEnabled) {
                        Button(
                            onClick = { },
                            modifier = Modifier.weight(0.9f),
                            enabled = !running
                        ) { Text("标准") }
                    } else {
                        OutlinedButton(
                            onClick = {
                                sprintModeEnabled = false
                                turboModeEnabled = false
                            },
                            modifier = Modifier.weight(0.9f),
                            enabled = !running
                        ) { Text("标准") }
                    }
                    if (sprintModeEnabled) {
                        Button(
                            onClick = { },
                            modifier = Modifier.weight(1.2f),
                            enabled = !running
                        ) { Text("稳定性增强") }
                    } else {
                        OutlinedButton(
                            onClick = {
                                sprintModeEnabled = true
                                turboModeEnabled = false
                            },
                            modifier = Modifier.weight(1.2f),
                            enabled = !running
                        ) { Text("稳定性增强") }
                    }
                    if (turboModeEnabled) {
                        Button(
                            onClick = { },
                            modifier = Modifier.weight(0.9f),
                            enabled = !running
                        ) { Text("极速") }
                    } else {
                        OutlinedButton(
                            onClick = {
                                turboModeEnabled = true
                                sprintModeEnabled = false
                            },
                            modifier = Modifier.weight(0.9f),
                            enabled = !running
                        ) { Text("极速") }
                    }
                }
                Text(
                    text = when {
                        turboModeEnabled -> "极速会尽量减少重复包与校验冗余，优先拉高吞吐；弱网下完整性与成功率会下降。"
                        sprintModeEnabled -> "稳定性增强会提高握手与重传频率，并延长阶段等待时间（仍为 UDP 打洞协议）。"
                        else -> "标准在速度与稳定性之间取平衡。"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("启用 NAT 端口展示", fontWeight = FontWeight.SemiBold)
                        Text(
                            "通过 UDP 多 STUN 探测公网暴露端口；监听仍使用本地端口。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = natEnabled,
                        onCheckedChange = {
                            natEnabled = it
                            if (!it) perPortStunProbeEnabled = false
                            natPublicIpv6 = ""
                            natPublicPort = null
                            refreshLocalInfo()
                        },
                        enabled = !running
                    )
                }
                if (natEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("通道端口策略", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (perPortStunProbeEnabled) {
                                    "逐端口 STUN 探测：每个并行端口单独探测公网端口后再交换。"
                                } else {
                                    "端口预测：按基准端口 + 偏移推导并行端口（默认）。"
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = perPortStunProbeEnabled,
                            onCheckedChange = { perPortStunProbeEnabled = it },
                            enabled = !running
                        )
                    }
                } else {
                    Text(
                        text = "通道端口策略：默认端口预测（开启 NAT 端口展示后可切换为逐端口 STUN）。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("打洞时间无限制", fontWeight = FontWeight.SemiBold)
                        Text(
                            "开启后会持续发包直到建立连接或手动中断。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = unlimitedPunchEnabled,
                        onCheckedChange = { unlimitedPunchEnabled = it },
                        enabled = !running
                    )
                }
                if (natEnabled) {
                    val shownHost = if (natPublicIpv6.isBlank()) "-" else natPublicIpv6
                    val shownPort = natPublicPort?.toString() ?: "-"
                    Text(
                        text = "公网映射：$shownHost:$shownPort（本地监听端口：${localPortInput.ifBlank { "-" }}）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (stunProbeResult?.endpoints?.isNotEmpty() == true) {
                        OutlinedButton(
                            onClick = { showStunPicker = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !running
                        ) {
                            Text("选择 STUN 地址")
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { refreshLocalInfo() },
                        modifier = Modifier.weight(1f),
                        enabled = !running
                    ) { Text("刷新 IPv6+二维码") }
                    OutlinedButton(
                        onClick = {
                            val port = localPortInput.toIntOrNull()
                            if (localIpv6.isBlank() || port == null || port !in 1..65535) {
                                status = "请填写有效的本机 IPv6 和端口。"
                                return@OutlinedButton
                            }
                            val advertiseHost = currentAdvertiseHost()
                            val advertisePort = currentAdvertisePort(port)
                            val payload = buildLocalPeerPayload(
                                advertiseHost,
                                advertisePort,
                                role = UDP_QR_ROLE_RECV
                            )
                            qrBitmap = QRCodeGenerator.generateQRCode(payload, 512)
                            if (qrBitmap == null) {
                                status = "二维码生成失败。"
                            } else {
                                copyText(context, "filetran_peer_info", payload)
                                status = "已生成并复制接收端信息（监听端口仍为 $port）。"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !running
                    ) { Text("生成并复制") }
                }
                if (qrBitmap != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "udp_recv_qr",
                        modifier = Modifier.fillMaxWidth().height(260.dp)
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. 发送端信息", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = remoteHostInput,
                    onValueChange = { remoteHostInput = it.trim() },
                    label = { Text("发送端 IPv6/主机") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
                OutlinedTextField(
                    value = remotePortInput,
                    onValueChange = { remotePortInput = it.filter(Char::isDigit) },
                    label = { Text("发送端端口") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showScanner = true },
                        modifier = Modifier.weight(1f),
                        enabled = !running
                    ) { Text("扫码识别发送端") }
                    OutlinedButton(
                        onClick = {
                            val source = readClipboardText(context)
                            val parsed = parsePeerFromQr(source)
                            if (parsed == null) {
                                status = if (source.isBlank()) "剪贴板为空。" else "剪贴板内容无法识别。"
                            } else {
                                val peer = parsed.peer
                                remoteHostInput = peer.host
                                remotePortInput = peer.port.toString()
                                status = "已识别发送端：${peer.host}:${peer.port}"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !running
                    ) { Text("读取剪贴板并识别") }
                }
                if (autoUnzipAfterReceive) {
                    Button(
                        onClick = { autoUnzipAfterReceive = false },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !running
                    ) {
                        Text("已启用：接收后自动解压ZIP")
                    }
                } else {
                    OutlinedButton(
                        onClick = { autoUnzipAfterReceive = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !running
                    ) {
                        Text("未启用：接收后自动解压ZIP")
                    }
                }
                Button(
                    onClick = {
                        val lp = localPortInput.toIntOrNull()
                        val rp = remotePortInput.toIntOrNull()
                        if (lp == null || rp == null || lp !in 1..65535 || rp !in 1..65535 || remoteHostInput.isBlank()) {
                            status = "请填写有效的打洞参数。"
                            return@Button
                        }
                        handshakeLogs.clear()
                        running = true
                        cancelSignal.set(false)
                        progress = 0f
                        showProgressSheet = true
                        progressFileName = ""
                        progressTotalBytes = 0L
                        progressTransferredBytes = 0L
                        progressSpeed = 0L
                        progressThreadSpeeds = emptyList()
                        receivedFiles = emptyList()
                        receiveDoneCount = 0
                        receiveTotalCount = 0
                        status = when {
                            turboModeEnabled -> "准备接收（极速）..."
                            sprintModeEnabled -> "准备接收（稳定性增强）..."
                            else -> "准备接收..."
                        }
                        scope.launch {
                            runCatching {
                                var done = 0
                                while (true) {
                                    if (cancelSignal.get()) throw CancellationException("用户已中断")
                                    val one = runCatching {
                                        receiveFileByUdpPunch(
                                            context = context,
                                            localPort = lp,
                                            remoteHost = remoteHostInput,
                                            remotePort = rp,
                                            runtimeSession = null,
                                            punchTimeoutMs = if (unlimitedPunchEnabled) null else UDP_CONTROL_FLOOD_TIMEOUT_MS,
                                            metaWaitTimeoutMs = if (done == 0) {
                                                if (turboModeEnabled) UDP_META_WAIT_TIMEOUT_MS + 2_000L
                                                else if (sprintModeEnabled) UDP_META_WAIT_TIMEOUT_MS + 8_000L
                                                else UDP_META_WAIT_TIMEOUT_MS
                                            } else {
                                                if (turboModeEnabled) 1_500L else if (sprintModeEnabled) 5_000L else 2_000L
                                            },
                                            stabilityModeEnabled = sprintModeEnabled,
                                            turboModeEnabled = turboModeEnabled,
                                            perPortStunProbeEnabled = natEnabled && perPortStunProbeEnabled,
                                            isCancelled = { cancelSignal.get() }
                                        ) { event ->
                                            progress = event.progress
                                            status = event.stage
                                            progressFileName = event.fileName
                                            progressTotalBytes = event.totalBytes.coerceAtLeast(progressTotalBytes)
                                            progressTransferredBytes = event.transferredBytes.coerceAtLeast(0L)
                                            progressSpeed = event.speedBytesPerSec.coerceAtLeast(0L)
                                            progressThreadSpeeds = event.threadSpeedsBytesPerSec
                                        }
                                    }
                                    if (one.isFailure) {
                                        val msg = one.exceptionOrNull()?.message.orEmpty()
                                        val isIdleEnd = done > 0 && (
                                            msg.contains("协商超时", ignoreCase = true) ||
                                                msg.contains("打洞超时", ignoreCase = true) ||
                                                msg.contains("未收到发送端元信息", ignoreCase = true)
                                            )
                                        if (isIdleEnd) break
                                        throw (one.exceptionOrNull() ?: IllegalStateException("未知错误"))
                                    }
                                    val oneFile = one.getOrThrow()
                                    done++
                                    receiveDoneCount = done
                                    receiveTotalCount = done
                                    if (oneFile.packedZip && autoUnzipAfterReceive) {
                                        val unzipCount = unzipReceivedFile(oneFile.filePath, oneFile.fileName)
                                        status = "已完成 $done：${oneFile.fileName}，已解压 $unzipCount 个文件"
                                    } else {
                                        status = "已完成 $done：${oneFile.fileName}"
                                    }
                                    receivedFiles = receivedFiles + oneFile
                                    progress = 0f
                                    progressTransferredBytes = 0L
                                    progressSpeed = 0L
                                    progressThreadSpeeds = emptyList()
                                }
                                if (done > 0) "接收完成，共接收 $done 个文件。" else "未接收到文件"
                            }.onSuccess {
                                status = it
                            }.onFailure { e ->
                                status = if (e is CancellationException) {
                                    "已中断：当前未完成文件已丢弃，已完成文件已保留。"
                                } else {
                                    "接收失败：${e.message ?: "未知"}"
                                }
                            }
                            running = false
                            cancelSignal.set(false)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !running
                ) { Text(if (running) "接收中..." else "开始打洞并立即接收") }
                if (running) {
                    OutlinedButton(
                        onClick = {
                            cancelSignal.set(true)
                            status = "正在中断接收..."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("中断接收/打洞") }
                }
            }
        }
        if (running || progress > 0f) {
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        if (!showProgressSheet && (running || progressTransferredBytes > 0L || receivedFiles.isNotEmpty())) {
            OutlinedButton(
                onClick = { showProgressSheet = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("查看接收抽屉")
            }
        }
        Text(status, color = if (status.startsWith("接收失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        UdpHandshakeLogCard(logs = handshakeLogs, onClear = { handshakeLogs.clear() })
        UdpRouteCard(logs = handshakeLogs, senderView = false)
    }

    if (showProgressSheet && (running || progressTransferredBytes > 0L || progressTotalBytes > 0L || receivedFiles.isNotEmpty())) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                showProgressSheet = false
            },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("接收抽屉", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                if (!running && receivedFiles.isNotEmpty()) {
                    Text("接收完成", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text("可在历史接收中查看本次接收文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (receiveTotalCount > 0) {
                    val remain = (receiveTotalCount - receiveDoneCount).coerceAtLeast(0)
                    Text("进度：($receiveDoneCount/$receiveTotalCount)，剩余 $remain 个")
                }
                Text(
                    text = "文件： ${progressFileName.ifBlank { "-" }}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text("大小： ${if (progressTotalBytes > 0L) formatTransferSize(progressTotalBytes) else "-"}")
                Text("已接收：${formatTransferSize(progressTransferredBytes)}")
                Text("速度： ${if (progressSpeed > 0L) "${formatTransferSize(progressSpeed)}/s" else "-"}")
                if (progressThreadSpeeds.isNotEmpty()) {
                    Text("线程实时速度", fontWeight = FontWeight.SemiBold)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        progressThreadSpeeds.forEachIndexed { index, bps ->
                            Text("线程 ${index + 1}：${if (bps > 0L) "${formatTransferSize(bps)}/s" else "-"}")
                        }
                    }
                }
                if (receivedFiles.isNotEmpty()) {
                    Text("本次传输文件", fontWeight = FontWeight.SemiBold)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        receivedFiles.forEachIndexed { idx, item ->
                            Text(
                                text = if (item.packedZip) {
                                    "${idx + 1}. ${item.fileName} (${formatTransferSize(item.fileSize)}) [压缩包:${item.packedCount}]"
                                } else {
                                    "${idx + 1}. ${item.fileName} (${formatTransferSize(item.fileSize)})"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { showProgressSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (running) "收起" else "关闭")
                }
            }
        }
    }
}





