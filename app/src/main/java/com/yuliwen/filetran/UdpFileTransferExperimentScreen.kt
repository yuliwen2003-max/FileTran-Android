package com.yuliwen.filetran

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.min

private const val UDP_EXP_QR_TYPE = "filetran_udp_file_exp_v1"
private const val UDP_EXP_PKT_HELLO = 1
private const val UDP_EXP_PKT_HELLO_ACK = 2
private const val UDP_EXP_PKT_DATA = 3
private const val UDP_EXP_PKT_ACK = 4
private const val UDP_EXP_PKT_ONEWAY_META = 11
private const val UDP_EXP_PKT_ONEWAY_DATA = 12
private const val UDP_EXP_PKT_ONEWAY_PARITY = 13
private const val UDP_EXP_PKT_ONEWAY_END = 14
private const val UDP_EXP_CHUNK_SIZE = 1200
private const val UDP_EXP_MAX_THREADS = 64
private const val UDP_EXP_SEND_ACK_TIMEOUT_MS = 240
private const val UDP_EXP_SEND_MAX_RETRY = 80
private const val UDP_EXP_HELLO_TIMEOUT_MS = 15_000L
private const val UDP_EXP_WAIT_HELLO_TIMEOUT_MS = 120_000L
private const val UDP_EXP_RECV_IDLE_TIMEOUT_MS = 20_000L
private const val UDP_EXP_ONEWAY_DEFAULT_CHUNK_SIZE = 1000
private const val UDP_EXP_ONEWAY_DEFAULT_REPEAT = 12
private const val UDP_EXP_ONEWAY_DEFAULT_GROUP_SIZE = 24
private const val UDP_EXP_ONEWAY_MAX_REPEAT = 4096
private const val UDP_EXP_ONEWAY_MAX_GROUP_SIZE = 256
private const val UDP_EXP_ONEWAY_META_BURST = 16
private const val UDP_EXP_ONEWAY_END_BURST = 16
private const val UDP_EXP_ONEWAY_META_INTERVAL_PACKETS = 48
private const val UDP_EXP_ONEWAY_WAIT_META_TIMEOUT_MS = 120_000L
private const val UDP_EXP_ONEWAY_RECV_IDLE_TIMEOUT_MS = 15_000L

private data class UdpExpPeer(val host: String, val port: Int)
private data class UdpExpSendFile(val uri: Uri, val name: String, val size: Long, val mime: String)
private data class UdpExpProgress(
    val progress: Float,
    val stage: String,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val speedBytesPerSec: Long
)

private data class UdpExpReceiveResult(val outFile: File, val fileName: String, val size: Long)
private data class UdpExpDataFrame(val index: Int, val length: Int, val payloadOffset: Int)
private data class UdpExpOneWayMeta(
    val sid: String,
    val sessionTag: Int,
    val fileName: String,
    val fileSize: Long,
    val chunkSize: Int,
    val totalChunks: Int,
    val totalGenerations: Int,
    val generationSize: Int,
    val repeatCount: Int,
    val fileSha256: String
)

private data class UdpExpOneWaySystemFrame(
    val sessionTag: Int,
    val generation: Int,
    val indexInGeneration: Int,
    val length: Int,
    val payloadOffset: Int
)

private data class UdpExpOneWayCodeFrame(
    val sessionTag: Int,
    val generation: Int,
    val seed: Int,
    val length: Int,
    val payloadOffset: Int
)

private enum class UdpExpTransferMode(val label: String, val summary: String) {
    RELIABLE_ACK(
        label = "双向可靠（握手 + ACK）",
        summary = "需要回包链路。发送端按块等待 ACK，可靠性高但无法在严格单向网络下工作。"
    ),
    ONEWAY_REDUNDANT(
        label = "单向冗余（无握手）",
        summary = "只要求发送端 -> 接收端单向可达。采用分代 RLNC（GF(256)）冗余编码，支持无限轮次持续发送。"
    )
}

private fun udpExpFormatSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}

private fun udpExpFormatRate(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "0 B/s"
    val kb = bytesPerSec / 1024.0
    if (kb < 1024) return String.format("%.1f KB/s", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.2f MB/s", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB/s", gb)
}

private fun parseUdpExpPeerFromRaw(input: String): UdpExpPeer? {
    val raw = input.trim()
    if (raw.isBlank()) return null
    val idx = raw.lastIndexOf(':')
    if (idx <= 0 || idx >= raw.length - 1) return null
    val host = raw.substring(0, idx).trim()
    val port = raw.substring(idx + 1).trim().toIntOrNull() ?: return null
    if (host.isBlank() || port !in 1..65535) return null
    return UdpExpPeer(host, port)
}

private fun parseUdpExpPeerFromQr(raw: String): UdpExpPeer? {
    val content = raw.trim()
    if (content.isBlank()) return null
    runCatching {
        val json = JSONObject(content)
        if (json.optString("type") == UDP_EXP_QR_TYPE) {
            val host = json.optString("host").trim()
            val port = json.optInt("port", -1)
            if (host.isNotBlank() && port in 1..65535) return UdpExpPeer(host, port)
        }
    }
    return parseUdpExpPeerFromRaw(content)
}

private fun encodeUdpExpJsonPacket(type: Int, json: JSONObject): ByteArray {
    val payload = json.toString().toByteArray(Charsets.UTF_8)
    return ByteArray(1 + payload.size).apply {
        this[0] = type.toByte()
        System.arraycopy(payload, 0, this, 1, payload.size)
    }
}

private fun parseUdpExpJsonPacket(packet: DatagramPacket): Pair<Int, JSONObject?> {
    if (packet.length <= 0) return 0 to null
    val type = packet.data[0].toInt() and 0xff
    if (packet.length == 1) return type to null
    val jsonStr = runCatching { packet.data.decodeToString(1, packet.length) }.getOrNull() ?: return type to null
    return type to runCatching { JSONObject(jsonStr) }.getOrNull()
}

private fun encodeUdpExpDataPacket(index: Int, payload: ByteArray, length: Int): ByteArray {
    val dataLen = length.coerceIn(0, payload.size)
    val buffer = ByteBuffer.allocate(1 + 4 + 2 + dataLen).order(ByteOrder.BIG_ENDIAN)
    buffer.put(UDP_EXP_PKT_DATA.toByte())
    buffer.putInt(index)
    buffer.putShort(dataLen.toShort())
    buffer.put(payload, 0, dataLen)
    return buffer.array()
}

private fun parseUdpExpDataPacket(packet: DatagramPacket): UdpExpDataFrame? {
    if (packet.length < 7) return null
    if ((packet.data[0].toInt() and 0xff) != UDP_EXP_PKT_DATA) return null
    val index = ByteBuffer.wrap(packet.data, 1, 4).order(ByteOrder.BIG_ENDIAN).int
    val len = ByteBuffer.wrap(packet.data, 5, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
    if (len < 0 || 7 + len > packet.length) return null
    return UdpExpDataFrame(index = index, length = len, payloadOffset = 7)
}

private fun encodeUdpExpAckPacket(index: Int): ByteArray {
    val buffer = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
    buffer.put(UDP_EXP_PKT_ACK.toByte())
    buffer.putInt(index)
    return buffer.array()
}

private fun parseUdpExpAckPacket(packet: DatagramPacket): Int? {
    if (packet.length < 5) return null
    if ((packet.data[0].toInt() and 0xff) != UDP_EXP_PKT_ACK) return null
    return ByteBuffer.wrap(packet.data, 1, 4).order(ByteOrder.BIG_ENDIAN).int
}

private fun udpExpExpectedChunkLength(index: Int, totalChunks: Int, fileSize: Long, chunkSize: Int): Int {
    if (index !in 0 until totalChunks) return 0
    if (index < totalChunks - 1) return chunkSize
    val tail = (fileSize - index.toLong() * chunkSize).toInt()
    return tail.coerceIn(0, chunkSize)
}

private fun encodeUdpExpOneWaySystemPacket(
    sessionTag: Int,
    generation: Int,
    indexInGeneration: Int,
    payload: ByteArray,
    length: Int
): ByteArray {
    val dataLen = length.coerceIn(0, payload.size)
    val buffer = ByteBuffer.allocate(1 + 4 + 2 + 2 + 2 + dataLen).order(ByteOrder.BIG_ENDIAN)
    buffer.put(UDP_EXP_PKT_ONEWAY_DATA.toByte())
    buffer.putInt(sessionTag)
    buffer.putShort(generation.toShort())
    buffer.putShort(indexInGeneration.toShort())
    buffer.putShort(dataLen.toShort())
    buffer.put(payload, 0, dataLen)
    return buffer.array()
}

private fun parseUdpExpOneWaySystemPacket(packet: DatagramPacket): UdpExpOneWaySystemFrame? {
    if (packet.length < 11) return null
    if ((packet.data[0].toInt() and 0xff) != UDP_EXP_PKT_ONEWAY_DATA) return null
    val sessionTag = ByteBuffer.wrap(packet.data, 1, 4).order(ByteOrder.BIG_ENDIAN).int
    val generation = ByteBuffer.wrap(packet.data, 5, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
    val indexInGeneration = ByteBuffer.wrap(packet.data, 7, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
    val len = ByteBuffer.wrap(packet.data, 9, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
    if (len <= 0 || 11 + len > packet.length) return null
    return UdpExpOneWaySystemFrame(
        sessionTag = sessionTag,
        generation = generation,
        indexInGeneration = indexInGeneration,
        length = len,
        payloadOffset = 11
    )
}

private fun encodeUdpExpOneWayCodePacket(
    sessionTag: Int,
    generation: Int,
    seed: Int,
    payload: ByteArray,
    length: Int
): ByteArray {
    val dataLen = length.coerceIn(0, payload.size)
    val buffer = ByteBuffer.allocate(1 + 4 + 2 + 4 + 2 + dataLen).order(ByteOrder.BIG_ENDIAN)
    buffer.put(UDP_EXP_PKT_ONEWAY_PARITY.toByte())
    buffer.putInt(sessionTag)
    buffer.putShort(generation.toShort())
    buffer.putInt(seed)
    buffer.putShort(dataLen.toShort())
    buffer.put(payload, 0, dataLen)
    return buffer.array()
}

private fun parseUdpExpOneWayCodePacket(packet: DatagramPacket): UdpExpOneWayCodeFrame? {
    if (packet.length < 13) return null
    if ((packet.data[0].toInt() and 0xff) != UDP_EXP_PKT_ONEWAY_PARITY) return null
    val sessionTag = ByteBuffer.wrap(packet.data, 1, 4).order(ByteOrder.BIG_ENDIAN).int
    val generation = ByteBuffer.wrap(packet.data, 5, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
    val seed = ByteBuffer.wrap(packet.data, 7, 4).order(ByteOrder.BIG_ENDIAN).int
    val len = ByteBuffer.wrap(packet.data, 11, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
    if (len <= 0 || 13 + len > packet.length) return null
    return UdpExpOneWayCodeFrame(
        sessionTag = sessionTag,
        generation = generation,
        seed = seed,
        length = len,
        payloadOffset = 13
    )
}

private fun parseUdpExpOneWayMeta(json: JSONObject?): UdpExpOneWayMeta? {
    if (json == null) return null
    if (json.optString("mode") != "oneway_rlnc_v1") return null
    val sid = json.optString("sid").trim()
    val sessionTag = json.optInt("tag", 0)
    val fileName = json.optString("name").trim().ifBlank { "udp_recv_${System.currentTimeMillis()}.bin" }
    val fileSize = json.optLong("size", 0L)
    val chunkSize = json.optInt("chunkSize", UDP_EXP_ONEWAY_DEFAULT_CHUNK_SIZE).coerceIn(512, 1300)
    val totalChunks = json.optInt("totalChunks", 0).coerceAtLeast(0)
    val generationSize = json.optInt("generationSize", UDP_EXP_ONEWAY_DEFAULT_GROUP_SIZE)
        .coerceIn(2, UDP_EXP_ONEWAY_MAX_GROUP_SIZE)
    val totalGenerations = json.optInt("totalGenerations", 0).coerceAtLeast(0)
    val repeatCount = json.optInt("repeat", UDP_EXP_ONEWAY_DEFAULT_REPEAT).coerceIn(0, UDP_EXP_ONEWAY_MAX_REPEAT)
    val fileSha256 = json.optString("sha256").trim().lowercase()
    if (sid.isBlank() || sessionTag == 0 || fileSize <= 0L || totalChunks <= 0 || totalGenerations <= 0) return null
    return UdpExpOneWayMeta(
        sid = sid,
        sessionTag = sessionTag,
        fileName = fileName,
        fileSize = fileSize,
        chunkSize = chunkSize,
        totalChunks = totalChunks,
        totalGenerations = totalGenerations,
        generationSize = generationSize,
        repeatCount = repeatCount,
        fileSha256 = fileSha256
    )
}

private object UdpExpGf256 {
    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11d
        }
        for (i in 255 until exp.size) exp[i] = exp[i - 255]
        log[0] = -1
    }

    fun add(a: Int, b: Int): Int = a xor b
    fun mul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return exp[log[a and 0xff] + log[b and 0xff]]
    }
    fun inv(a: Int): Int {
        if (a == 0) throw IllegalArgumentException("0 has no inverse")
        return exp[255 - log[a and 0xff]]
    }
    fun div(a: Int, b: Int): Int {
        if (a == 0) return 0
        if (b == 0) throw IllegalArgumentException("division by 0")
        return exp[(log[a and 0xff] - log[b and 0xff] + 255) % 255]
    }
}

private class UdpExpGenerationDecoder(
    private val symbolCount: Int,
    private val symbolBytes: Int
) {
    private val rowsCoeff = arrayOfNulls<ByteArray>(symbolCount)
    private val rowsPayload = arrayOfNulls<ByteArray>(symbolCount)
    private val pivotRowByCol = IntArray(symbolCount) { -1 }
    private val pivotColByRow = IntArray(symbolCount) { -1 }
    private var rank = 0
    private var solvedCache: Array<ByteArray>? = null

    fun rank(): Int = rank
    fun isSolved(): Boolean = rank >= symbolCount

    fun addEquation(coeffInput: ByteArray, payloadInput: ByteArray): Boolean {
        if (coeffInput.size != symbolCount || payloadInput.size != symbolBytes || rank >= symbolCount) return false
        val coeff = coeffInput.copyOf()
        val payload = payloadInput.copyOf()
        for (col in 0 until symbolCount) {
            val v = coeff[col].toInt() and 0xff
            if (v == 0) continue
            val pivotRow = pivotRowByCol[col]
            if (pivotRow >= 0) {
                val pivotCoeff = rowsCoeff[pivotRow] ?: continue
                val pivotPayload = rowsPayload[pivotRow] ?: continue
                val factor = UdpExpGf256.div(v, pivotCoeff[col].toInt() and 0xff)
                if (factor != 0) {
                    for (i in 0 until symbolCount) {
                        val mixed = UdpExpGf256.mul(factor, pivotCoeff[i].toInt() and 0xff)
                        coeff[i] = UdpExpGf256.add(coeff[i].toInt() and 0xff, mixed).toByte()
                    }
                    for (i in 0 until symbolBytes) {
                        val mixed = UdpExpGf256.mul(factor, pivotPayload[i].toInt() and 0xff)
                        payload[i] = UdpExpGf256.add(payload[i].toInt() and 0xff, mixed).toByte()
                    }
                }
                continue
            }

            val inv = UdpExpGf256.inv(v)
            for (i in 0 until symbolCount) coeff[i] = UdpExpGf256.mul(coeff[i].toInt() and 0xff, inv).toByte()
            for (i in 0 until symbolBytes) payload[i] = UdpExpGf256.mul(payload[i].toInt() and 0xff, inv).toByte()

            val newRow = rank
            rowsCoeff[newRow] = coeff
            rowsPayload[newRow] = payload
            pivotRowByCol[col] = newRow
            pivotColByRow[newRow] = col
            rank++
            solvedCache = null

            for (row in 0 until rank - 1) {
                val rowCoeff = rowsCoeff[row] ?: continue
                val rowPayload = rowsPayload[row] ?: continue
                val factor = rowCoeff[col].toInt() and 0xff
                if (factor == 0) continue
                for (i in 0 until symbolCount) {
                    val mixed = UdpExpGf256.mul(factor, coeff[i].toInt() and 0xff)
                    rowCoeff[i] = UdpExpGf256.add(rowCoeff[i].toInt() and 0xff, mixed).toByte()
                }
                for (i in 0 until symbolBytes) {
                    val mixed = UdpExpGf256.mul(factor, payload[i].toInt() and 0xff)
                    rowPayload[i] = UdpExpGf256.add(rowPayload[i].toInt() and 0xff, mixed).toByte()
                }
            }
            return true
        }
        return false
    }

    fun decodedSymbolsOrNull(): Array<ByteArray>? {
        if (!isSolved()) return null
        solvedCache?.let { return it }
        val out = Array(symbolCount) { ByteArray(symbolBytes) }
        for (row in 0 until rank) {
            val col = pivotColByRow[row]
            if (col < 0 || col >= symbolCount) continue
            val payload = rowsPayload[row] ?: continue
            System.arraycopy(payload, 0, out[col], 0, symbolBytes)
        }
        solvedCache = out
        return out
    }
}

private fun nextXorShift32(state: Int): Int {
    var x = if (state == 0) 0x6d2b79f5 else state
    x = x xor (x shl 13)
    x = x xor (x ushr 17)
    x = x xor (x shl 5)
    return x
}

private fun buildUdpExpRlncCoefficients(symbolCount: Int, seed: Int): ByteArray {
    if (symbolCount <= 0) return ByteArray(0)
    val coeff = ByteArray(symbolCount)
    var s = if (seed == 0) 0x13579bdf.toInt() else seed
    s = nextXorShift32(s)
    val profile = (s ushr 1) % 100
    val targetDegree = when {
        profile < 20 -> 1
        profile < 70 -> min(8, symbolCount).coerceAtLeast(1)
        else -> (symbolCount / 2).coerceAtLeast(2).coerceAtMost(symbolCount)
    }
    val selected = BooleanArray(symbolCount)
    var picked = 0
    while (picked < targetDegree) {
        s = nextXorShift32(s)
        val idx = (s ushr 1) % symbolCount
        if (selected[idx]) continue
        selected[idx] = true
        s = nextXorShift32(s)
        coeff[idx] = (((s ushr 1) % 255) + 1).toByte()
        picked++
    }
    return coeff
}

private fun buildUdpExpOneWayCodePayload(
    coeff: ByteArray,
    symbols: Array<ByteArray>,
    symbolCount: Int,
    symbolBytes: Int
): ByteArray {
    val out = ByteArray(symbolBytes)
    for (i in 0 until symbolCount) {
        val factor = coeff[i].toInt() and 0xff
        if (factor == 0) continue
        val src = symbols[i]
        for (j in 0 until symbolBytes) {
            val mixed = UdpExpGf256.mul(factor, src[j].toInt() and 0xff)
            out[j] = UdpExpGf256.add(out[j].toInt() and 0xff, mixed).toByte()
        }
    }
    return out
}

private fun sha256OfFile(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { ins ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun udpExpGenerationChunkCount(generation: Int, totalChunks: Int, generationSize: Int): Int {
    if (generation < 0 || generationSize <= 0) return 0
    val start = generation * generationSize
    if (start >= totalChunks) return 0
    return min(generationSize, totalChunks - start)
}

private suspend fun resolveUdpExpSendFile(context: Context, uri: Uri): UdpExpSendFile? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var name = "udp_send_${System.currentTimeMillis()}.bin"
    var size = -1L
    var mime = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nIdx >= 0) name = c.getString(nIdx).orEmpty().ifBlank { name }
                if (sIdx >= 0) size = c.getLong(sIdx)
            }
        }
    }
    if (size <= 0L) {
        runCatching {
            resolver.openInputStream(uri)?.use { ins ->
                val buf = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    total += n
                }
                size = total
            }
        }
    }
    if (size <= 0L) return@withContext null
    UdpExpSendFile(uri = uri, name = name, size = size, mime = mime)
}

private suspend fun materializeUdpExpUriToTempFile(context: Context, uri: Uri, fallbackName: String): File = withContext(Dispatchers.IO) {
    val base = fallbackName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "udp_send_${System.currentTimeMillis()}.bin" }
    val out = File(context.cacheDir, "udp_exp_${System.currentTimeMillis()}_$base")
    context.contentResolver.openInputStream(uri)?.use { ins ->
        FileOutputStream(out).use { os ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                os.write(buf, 0, n)
            }
            os.flush()
        }
    } ?: throw IllegalStateException("无法读取文件")
    out
}

private fun uniqueUdpExpTargetFile(dir: File, fileName: String): File {
    val sanitized = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "udp_recv_${System.currentTimeMillis()}.bin" }
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

private suspend fun sendFileByUdpExperiment(
    context: Context,
    file: UdpExpSendFile,
    localPort: Int,
    remoteHost: String,
    remotePort: Int,
    requestedThreads: Int,
    isCancelled: () -> Boolean = { false },
    onProgress: (UdpExpProgress) -> Unit
): String = withContext(Dispatchers.IO) {
    val tempFile = materializeUdpExpUriToTempFile(context, file.uri, file.name)
    val totalSize = tempFile.length().coerceAtLeast(0L)
    if (totalSize <= 0L) throw IllegalStateException("文件为空")

    val sid = UUID.randomUUID().toString()
    val requested = requestedThreads.coerceIn(1, UDP_EXP_MAX_THREADS)
    val chunkSize = UDP_EXP_CHUNK_SIZE
    val totalChunks = ceil(totalSize / chunkSize.toDouble()).toInt().coerceAtLeast(1)
    val controlSocket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress("0.0.0.0", localPort))
        soTimeout = 260
    }
    val resolvedAddrs = runCatching { InetAddress.getAllByName(remoteHost).toList() }
        .getOrElse {
            runCatching { controlSocket.close() }
            runCatching { tempFile.delete() }
            throw IllegalStateException("无法解析对端地址")
        }
    if (resolvedAddrs.isEmpty()) {
        runCatching { controlSocket.close() }
        runCatching { tempFile.delete() }
        throw IllegalStateException("对端地址为空")
    }

    var agreedThreads = requested
    var helloAcked = false
    val hello = encodeUdpExpJsonPacket(
        UDP_EXP_PKT_HELLO,
        JSONObject()
            .put("sid", sid)
            .put("name", file.name)
            .put("size", totalSize)
            .put("chunkSize", chunkSize)
            .put("threads", requested)
    )
    val helloDeadline = System.currentTimeMillis() + UDP_EXP_HELLO_TIMEOUT_MS
    onProgress(UdpExpProgress(0f, "正在握手...", file.name, totalSize, 0L, 0L))
    while (System.currentTimeMillis() < helloDeadline) {
        if (isCancelled()) throw CancellationException("用户已中断")
        resolvedAddrs.forEach { addr ->
            runCatching { controlSocket.send(DatagramPacket(hello, hello.size, addr, remotePort)) }
        }
        try {
            val inBuf = ByteArray(2048)
            val incoming = DatagramPacket(inBuf, inBuf.size)
            controlSocket.receive(incoming)
            val (type, json) = parseUdpExpJsonPacket(incoming)
            if (type == UDP_EXP_PKT_HELLO_ACK && json?.optString("sid") == sid) {
                agreedThreads = json.optInt("threads", requested).coerceIn(1, UDP_EXP_MAX_THREADS)
                helloAcked = true
                break
            }
        } catch (_: SocketTimeoutException) {
        }
        delay(230)
    }
    if (!helloAcked || agreedThreads <= 0) {
        runCatching { controlSocket.close() }
        runCatching { tempFile.delete() }
        throw IllegalStateException("握手失败")
    }

    val startedAt = System.currentTimeMillis()
    val ackedBytes = AtomicLong(0L)
    val ackedChunks = AtomicInteger(0)
    val lastEmitAt = AtomicLong(0L)
    val workerCount = agreedThreads
    onProgress(UdpExpProgress(0.01f, "握手成功，启动 $workerCount 线程发送...", file.name, totalSize, 0L, 0L))

    try {
        coroutineScope {
            val jobs = (0 until workerCount).map { worker ->
                async {
                    val socket = DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress("0.0.0.0", localPort + 1 + worker))
                        soTimeout = UDP_EXP_SEND_ACK_TIMEOUT_MS
                    }
                    val endpoint = InetSocketAddress(resolvedAddrs.first(), remotePort + 1 + worker)
                    val raf = RandomAccessFile(tempFile, "r")
                    try {
                        var idx = worker
                        val chunkBuf = ByteArray(chunkSize)
                        while (idx < totalChunks) {
                            if (isCancelled()) throw CancellationException("用户已中断")
                            val offset = idx.toLong() * chunkSize
                            val len = min(chunkSize.toLong(), totalSize - offset).toInt().coerceAtLeast(0)
                            if (len <= 0) break
                            raf.seek(offset)
                            raf.readFully(chunkBuf, 0, len)
                            val dataPacket = encodeUdpExpDataPacket(idx, chunkBuf, len)
                            var acked = false
                            var retry = 0
                            while (!acked && retry < UDP_EXP_SEND_MAX_RETRY) {
                                socket.send(DatagramPacket(dataPacket, dataPacket.size, endpoint))
                                retry++
                                try {
                                    val inBuf = ByteArray(64)
                                    val incoming = DatagramPacket(inBuf, inBuf.size)
                                    socket.receive(incoming)
                                    if (parseUdpExpAckPacket(incoming) == idx) acked = true
                                } catch (_: SocketTimeoutException) {
                                }
                                if (isCancelled()) throw CancellationException("用户已中断")
                            }
                            if (!acked) throw IllegalStateException("线程${worker + 1} ACK超时: chunk=$idx")
                            val done = ackedBytes.addAndGet(len.toLong())
                            val doneChunks = ackedChunks.incrementAndGet()
                            val now = System.currentTimeMillis()
                            if (now - lastEmitAt.get() >= 150L || doneChunks == totalChunks) {
                                lastEmitAt.set(now)
                                val elapsedSec = ((now - startedAt).coerceAtLeast(1L)) / 1000.0
                                val speed = (done / elapsedSec).toLong()
                                onProgress(
                                    UdpExpProgress(
                                        progress = (done.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f),
                                        stage = "发送中（线程 ${worker + 1}）",
                                        fileName = file.name,
                                        totalBytes = totalSize,
                                        transferredBytes = done,
                                        speedBytesPerSec = speed
                                    )
                                )
                            }
                            idx += workerCount
                        }
                    } finally {
                        runCatching { raf.close() }
                        runCatching { socket.close() }
                    }
                }
            }
            jobs.forEach { it.await() }
        }
    } finally {
        runCatching { controlSocket.close() }
        runCatching { tempFile.delete() }
    }

    val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
    val avg = ((totalSize * 1000L) / elapsedMs).coerceAtLeast(0L)
    onProgress(UdpExpProgress(1f, "发送完成", file.name, totalSize, totalSize, avg))
    return@withContext "发送完成：${file.name}（平均速率 ${udpExpFormatRate(avg)}）"
}

private suspend fun receiveFileByUdpExperiment(
    context: Context,
    localPort: Int,
    localThreads: Int,
    isCancelled: () -> Boolean = { false },
    onProgress: (UdpExpProgress) -> Unit
): UdpExpReceiveResult = withContext(Dispatchers.IO) {
    val controlSocket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress("0.0.0.0", localPort))
        soTimeout = 400
    }
    onProgress(UdpExpProgress(0f, "等待发送端握手...", "", 0L, 0L, 0L))
    val helloDeadline = System.currentTimeMillis() + UDP_EXP_WAIT_HELLO_TIMEOUT_MS
    var sid = ""
    var fileName = ""
    var fileSize = 0L
    var chunkSize = UDP_EXP_CHUNK_SIZE
    var senderThreads = 1
    var senderControl: InetSocketAddress? = null
    while (System.currentTimeMillis() < helloDeadline) {
        if (isCancelled()) throw CancellationException("用户已中断")
        try {
            val inBuf = ByteArray(4096)
            val incoming = DatagramPacket(inBuf, inBuf.size)
            controlSocket.receive(incoming)
            val (type, json) = parseUdpExpJsonPacket(incoming)
            if (type == UDP_EXP_PKT_HELLO && json != null) {
                sid = json.optString("sid").trim()
                fileName = json.optString("name").trim().ifBlank { "udp_recv_${System.currentTimeMillis()}.bin" }
                fileSize = json.optLong("size", 0L)
                chunkSize = json.optInt("chunkSize", UDP_EXP_CHUNK_SIZE).coerceIn(256, 60 * 1024)
                senderThreads = json.optInt("threads", 1).coerceIn(1, UDP_EXP_MAX_THREADS)
                if (sid.isNotBlank() && fileSize > 0L) {
                    senderControl = InetSocketAddress(incoming.address, incoming.port)
                    break
                }
            }
        } catch (_: SocketTimeoutException) {
        }
    }
    if (senderControl == null || sid.isBlank() || fileSize <= 0L) {
        runCatching { controlSocket.close() }
        throw IllegalStateException("等待发送端握手超时")
    }

    val workerCount = min(senderThreads, localThreads.coerceIn(1, UDP_EXP_MAX_THREADS)).coerceAtLeast(1)
    val totalChunks = ceil(fileSize / chunkSize.toDouble()).toInt().coerceAtLeast(1)
    val recvDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        ?: File(context.filesDir, "udp-exp-downloads").apply { mkdirs() }
    recvDir.mkdirs()
    val outFile = uniqueUdpExpTargetFile(recvDir, fileName)
    val raf = RandomAccessFile(outFile, "rw")
    raf.setLength(fileSize)
    val rafLock = Any()
    val flags = BooleanArray(totalChunks)
    val receivedChunks = AtomicInteger(0)
    val receivedBytes = AtomicLong(0L)
    val lastPacketAt = AtomicLong(System.currentTimeMillis())
    val startedAt = System.currentTimeMillis()
    val done = AtomicBoolean(false)

    val ackHello = encodeUdpExpJsonPacket(
        UDP_EXP_PKT_HELLO_ACK,
        JSONObject().put("sid", sid).put("threads", workerCount)
    )
    repeat(8) {
        runCatching { controlSocket.send(DatagramPacket(ackHello, ackHello.size, senderControl)) }
        delay(80)
    }
    onProgress(UdpExpProgress(0.01f, "握手成功，启动 $workerCount 线程接收...", fileName, fileSize, 0L, 0L))

    try {
        coroutineScope {
            val ackKeepalive = async {
                while (isActive && !done.get() && !isCancelled()) {
                    runCatching { controlSocket.send(DatagramPacket(ackHello, ackHello.size, senderControl)) }
                    delay(550)
                }
            }
            val workers = (0 until workerCount).map { worker ->
                async {
                    val socket = DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress("0.0.0.0", localPort + 1 + worker))
                        soTimeout = 320
                    }
                    try {
                        while (!isCancelled() && !done.get()) {
                            if (receivedChunks.get() >= totalChunks) {
                                done.set(true)
                                break
                            }
                            try {
                                val inBuf = ByteArray(chunkSize + 64)
                                val incoming = DatagramPacket(inBuf, inBuf.size)
                                socket.receive(incoming)
                                val frame = parseUdpExpDataPacket(incoming) ?: continue
                                val idx = frame.index
                                if (idx < 0 || idx >= totalChunks) continue
                                if (idx % workerCount != worker) continue
                                var newly = false
                                synchronized(flags) {
                                    if (!flags[idx]) {
                                        flags[idx] = true
                                        newly = true
                                    }
                                }
                                if (newly) {
                                    val offset = idx.toLong() * chunkSize
                                    synchronized(rafLock) {
                                        raf.seek(offset)
                                        raf.write(incoming.data, frame.payloadOffset, frame.length)
                                    }
                                    receivedBytes.addAndGet(frame.length.toLong())
                                    receivedChunks.incrementAndGet()
                                }
                                lastPacketAt.set(System.currentTimeMillis())
                                val ack = encodeUdpExpAckPacket(idx)
                                socket.send(DatagramPacket(ack, ack.size, incoming.address, incoming.port))
                            } catch (_: SocketTimeoutException) {
                            }
                        }
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            }

            while (!done.get()) {
                if (isCancelled()) throw CancellationException("用户已中断")
                val now = System.currentTimeMillis()
                val bytes = receivedBytes.get()
                val chunks = receivedChunks.get()
                val elapsedSec = ((now - startedAt).coerceAtLeast(1L)) / 1000.0
                val speed = (bytes / elapsedSec).toLong()
                onProgress(
                    UdpExpProgress(
                        progress = (bytes.toFloat() / fileSize.toFloat()).coerceIn(0f, 1f),
                        stage = "接收中（$chunks/$totalChunks 块）",
                        fileName = fileName,
                        totalBytes = fileSize,
                        transferredBytes = bytes,
                        speedBytesPerSec = speed
                    )
                )
                if (chunks >= totalChunks) {
                    done.set(true)
                    break
                }
                if (now - lastPacketAt.get() >= UDP_EXP_RECV_IDLE_TIMEOUT_MS) {
                    throw IllegalStateException("接收超时：${UDP_EXP_RECV_IDLE_TIMEOUT_MS / 1000}s 未收到新数据")
                }
                delay(160)
            }

            done.set(true)
            workers.forEach { it.await() }
            ackKeepalive.cancel()
        }
    } finally {
        runCatching { raf.close() }
        runCatching { controlSocket.close() }
    }

    val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
    val avg = ((fileSize * 1000L) / elapsedMs).coerceAtLeast(0L)
    onProgress(UdpExpProgress(1f, "接收完成", fileName, fileSize, fileSize, avg))
    UdpExpReceiveResult(outFile = outFile, fileName = fileName, size = fileSize)
}

private suspend fun sendFileByUdpOneWayExperiment(
    context: Context,
    file: UdpExpSendFile,
    localPort: Int,
    remoteHost: String,
    remotePort: Int,
    requestedChunkSize: Int,
    requestedRepeatCount: Int,
    requestedGroupSize: Int,
    isCancelled: () -> Boolean = { false },
    onProgress: (UdpExpProgress) -> Unit
): String = withContext(Dispatchers.IO) {
    val tempFile = materializeUdpExpUriToTempFile(context, file.uri, file.name)
    val totalSize = tempFile.length().coerceAtLeast(0L)
    if (totalSize <= 0L) throw IllegalStateException("文件为空")

    val chunkSize = requestedChunkSize.coerceIn(512, 1300)
    val repeatCount = requestedRepeatCount.coerceIn(0, UDP_EXP_ONEWAY_MAX_REPEAT)
    val infiniteRepeat = repeatCount == 0
    val generationSize = requestedGroupSize.coerceIn(2, UDP_EXP_ONEWAY_MAX_GROUP_SIZE)
    val totalChunks = ceil(totalSize / chunkSize.toDouble()).toInt().coerceAtLeast(1)
    val totalGenerations = ceil(totalChunks / generationSize.toDouble()).toInt().coerceAtLeast(1)
    val sid = UUID.randomUUID().toString()
    val fileSha256 = sha256OfFile(tempFile)
    var sessionTag = sid.hashCode() xor totalChunks xor chunkSize
    if (sessionTag == 0) sessionTag = 1

    val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress("0.0.0.0", localPort))
    }
    val resolvedAddrs = runCatching { InetAddress.getAllByName(remoteHost).toList() }
        .getOrElse {
            runCatching { socket.close() }
            runCatching { tempFile.delete() }
            throw IllegalStateException("无法解析对端地址")
        }
    if (resolvedAddrs.isEmpty()) {
        runCatching { socket.close() }
        runCatching { tempFile.delete() }
        throw IllegalStateException("对端地址为空")
    }

    val metaPacket = encodeUdpExpJsonPacket(
        UDP_EXP_PKT_ONEWAY_META,
        JSONObject()
            .put("mode", "oneway_rlnc_v1")
            .put("sid", sid)
            .put("tag", sessionTag)
            .put("name", file.name)
            .put("size", totalSize)
            .put("sha256", fileSha256)
            .put("chunkSize", chunkSize)
            .put("totalChunks", totalChunks)
            .put("totalGenerations", totalGenerations)
            .put("generationSize", generationSize)
            .put("repeat", repeatCount)
            .put("createdAt", System.currentTimeMillis())
    )
    val endPacket = encodeUdpExpJsonPacket(
        UDP_EXP_PKT_ONEWAY_END,
        JSONObject()
            .put("sid", sid)
            .put("tag", sessionTag)
            .put("totalChunks", totalChunks)
            .put("endedAt", System.currentTimeMillis())
    )
    val sendToAll: (ByteArray) -> Unit = { data ->
        resolvedAddrs.forEach { addr ->
            runCatching { socket.send(DatagramPacket(data, data.size, addr, remotePort)) }
        }
    }

    fun codePacketsPerGeneration(symbolCount: Int): Int {
        if (symbolCount <= 1) return 2
        return maxOf(6, (symbolCount * 3) / 2)
    }

    fun loadGenerationSymbols(
        raf: RandomAccessFile,
        generation: Int
    ): Pair<Array<ByteArray>, IntArray> {
        val symbolCount = udpExpGenerationChunkCount(generation, totalChunks, generationSize)
        val symbols = Array(symbolCount) { ByteArray(chunkSize) }
        val lengths = IntArray(symbolCount)
        for (idx in 0 until symbolCount) {
            val global = generation * generationSize + idx
            val len = udpExpExpectedChunkLength(global, totalChunks, totalSize, chunkSize)
            lengths[idx] = len
            if (len <= 0) continue
            raf.seek(global.toLong() * chunkSize)
            raf.readFully(symbols[idx], 0, len)
        }
        return symbols to lengths
    }

    val roundUnits = (0 until totalGenerations).sumOf { generation ->
        val symbolCount = udpExpGenerationChunkCount(generation, totalChunks, generationSize)
        symbolCount + codePacketsPerGeneration(symbolCount)
    }.coerceAtLeast(1)
    val startedAt = System.currentTimeMillis()
    val totalUnits = if (infiniteRepeat) -1L else (repeatCount.toLong() * roundUnits.toLong()).coerceAtLeast(1L)
    var sentUnits = 0L
    var completedRounds = 0L
    var wireBytesSent = 0L
    var emittedAt = 0L
    val raf = RandomAccessFile(tempFile, "r")

    try {
        onProgress(
            UdpExpProgress(
                progress = 0f,
                stage = "单向模式：发送元信息广播...",
                fileName = file.name,
                totalBytes = totalSize,
                transferredBytes = 0L,
                speedBytesPerSec = 0L
            )
        )
        repeat(UDP_EXP_ONEWAY_META_BURST) { idx ->
            if (isCancelled()) throw CancellationException("用户已中断")
            sendToAll(metaPacket)
            wireBytesSent += metaPacket.size.toLong()
            if (idx % 4 == 3) delay(20L)
        }

        while (infiniteRepeat || completedRounds < repeatCount.toLong()) {
            val round = completedRounds.toInt()
            for (generation in 0 until totalGenerations) {
                val symbolCount = udpExpGenerationChunkCount(generation, totalChunks, generationSize)
                if (symbolCount <= 0) continue
                val (symbols, lengths) = loadGenerationSymbols(raf, generation)

                for (offset in 0 until symbolCount) {
                    if (isCancelled()) throw CancellationException("用户已中断")
                    val idx = (offset + round) % symbolCount
                    val len = lengths[idx]
                    if (len <= 0) continue
                    val sysPacket = encodeUdpExpOneWaySystemPacket(
                        sessionTag = sessionTag,
                        generation = generation,
                        indexInGeneration = idx,
                        payload = symbols[idx],
                        length = len
                    )
                    sendToAll(sysPacket)
                    wireBytesSent += sysPacket.size.toLong()
                    sentUnits++
                    if (sentUnits % UDP_EXP_ONEWAY_META_INTERVAL_PACKETS == 0L) sendToAll(metaPacket)
                    val now = System.currentTimeMillis()
                    if (sentUnits % UDP_EXP_ONEWAY_META_INTERVAL_PACKETS == 0L) {
                        wireBytesSent += metaPacket.size.toLong()
                    }
                    if (now - emittedAt >= 150L || (!infiniteRepeat && sentUnits >= totalUnits)) {
                        emittedAt = now
                        val progress = if (infiniteRepeat) {
                            if (totalChunks <= 1) 0.98f
                            else ((sentUnits % totalChunks.toLong()).toFloat() / totalChunks.toFloat()).coerceIn(0f, 0.98f)
                        } else {
                            (sentUnits.toDouble() / totalUnits.toDouble()).toFloat().coerceIn(0f, 1f)
                        }
                        val transferred = if (infiniteRepeat) {
                            ((sentUnits % totalChunks.toLong()).toDouble() / totalChunks.toDouble() * totalSize)
                                .toLong()
                                .coerceIn(0L, totalSize)
                        } else {
                            (totalSize * progress).toLong().coerceIn(0L, totalSize)
                        }
                        val elapsedSec = ((now - startedAt).coerceAtLeast(1L)) / 1000.0
                        val speed = (wireBytesSent / elapsedSec).toLong()
                        onProgress(
                            UdpExpProgress(
                                progress = progress,
                                stage = if (infiniteRepeat) {
                                    "单向发送中（无限轮次，已完成 $completedRounds 轮）"
                                } else {
                                    "单向发送中（第 ${round + 1}/$repeatCount 轮）"
                                },
                                fileName = file.name,
                                totalBytes = totalSize,
                                transferredBytes = transferred,
                                speedBytesPerSec = speed
                            )
                        )
                    }
                }

                val codePackets = codePacketsPerGeneration(symbolCount)
                for (codeIndex in 0 until codePackets) {
                    if (isCancelled()) throw CancellationException("用户已中断")
                    var seed = sessionTag xor (generation shl 8) xor (round shl 16) xor codeIndex
                    seed = nextXorShift32(seed)
                    val coeff = buildUdpExpRlncCoefficients(symbolCount, seed)
                    val payload = buildUdpExpOneWayCodePayload(
                        coeff = coeff,
                        symbols = symbols,
                        symbolCount = symbolCount,
                        symbolBytes = chunkSize
                    )
                    val codePacket = encodeUdpExpOneWayCodePacket(
                        sessionTag = sessionTag,
                        generation = generation,
                        seed = seed,
                        payload = payload,
                        length = chunkSize
                    )
                    sendToAll(codePacket)
                    wireBytesSent += codePacket.size.toLong()
                    sentUnits++
                    if (sentUnits % UDP_EXP_ONEWAY_META_INTERVAL_PACKETS == 0L) {
                        sendToAll(metaPacket)
                        wireBytesSent += metaPacket.size.toLong()
                    }
                }
            }
            sendToAll(metaPacket)
            wireBytesSent += metaPacket.size.toLong()
            completedRounds++
        }

        if (!infiniteRepeat) {
            repeat(UDP_EXP_ONEWAY_END_BURST) { idx ->
                sendToAll(endPacket)
                wireBytesSent += endPacket.size.toLong()
                if (idx % 4 == 3) delay(18L)
            }
        }
    } finally {
        if (isCancelled()) {
            repeat(UDP_EXP_ONEWAY_END_BURST) {
                sendToAll(endPacket)
            }
        }
        runCatching { raf.close() }
        runCatching { socket.close() }
        runCatching { tempFile.delete() }
    }

    val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
    val avg = ((totalSize * 1000L) / elapsedMs).coerceAtLeast(0L)
    onProgress(UdpExpProgress(1f, "单向发送完成", file.name, totalSize, totalSize, avg))
    return@withContext "发送完成：${file.name}（无握手 RLNC，轮次=${if (infiniteRepeat) "无限" else repeatCount.toString()}，代大小=$generationSize）"
}

private suspend fun receiveFileByUdpOneWayExperiment(
    context: Context,
    localPort: Int,
    isCancelled: () -> Boolean = { false },
    onProgress: (UdpExpProgress) -> Unit
): UdpExpReceiveResult = withContext(Dispatchers.IO) {
    val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress("0.0.0.0", localPort))
        soTimeout = 320
    }
    onProgress(UdpExpProgress(0f, "等待单向元信息...", "", 0L, 0L, 0L))
    val metaDeadline = System.currentTimeMillis() + UDP_EXP_ONEWAY_WAIT_META_TIMEOUT_MS
    var meta: UdpExpOneWayMeta? = null
    var senderAddress: InetAddress? = null
    var senderPort = -1
    var endSeen = false

    var outFile: File? = null
    var raf: RandomAccessFile? = null
    var chunkKnownFlags: BooleanArray? = null
    var generationSolvedFlags: BooleanArray? = null
    var generationDecoders: Array<UdpExpGenerationDecoder?>? = null
    var generationChunkLengths: Array<IntArray>? = null
    var solvedGenerationCount = 0

    val solvedChunks = AtomicInteger(0)
    val receivedBytes = AtomicLong(0L)
    val startedAt = System.currentTimeMillis()
    val lastPacketAt = AtomicLong(System.currentTimeMillis())
    val rafLock = Any()
    var lastEmitAt = 0L

    fun emitProgress(stage: String) {
        val currentMeta = meta ?: return
        val now = System.currentTimeMillis()
        val bytes = receivedBytes.get()
        val elapsedSec = ((now - startedAt).coerceAtLeast(1L)) / 1000.0
        val speed = (bytes / elapsedSec).toLong()
        onProgress(
            UdpExpProgress(
                progress = (bytes.toFloat() / currentMeta.fileSize.toFloat()).coerceIn(0f, 1f),
                stage = stage,
                fileName = currentMeta.fileName,
                totalBytes = currentMeta.fileSize,
                transferredBytes = bytes,
                speedBytesPerSec = speed
            )
        )
    }

    fun ensureSession(initMeta: UdpExpOneWayMeta) {
        if (meta != null) return
        val recvDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "udp-exp-downloads").apply { mkdirs() }
        recvDir.mkdirs()
        val target = uniqueUdpExpTargetFile(recvDir, initMeta.fileName)
        val fileRaf = RandomAccessFile(target, "rw")
        fileRaf.setLength(initMeta.fileSize)
        meta = initMeta
        outFile = target
        raf = fileRaf
        chunkKnownFlags = BooleanArray(initMeta.totalChunks)
        generationSolvedFlags = BooleanArray(initMeta.totalGenerations)
        generationDecoders = arrayOfNulls(initMeta.totalGenerations)
        generationChunkLengths = Array(initMeta.totalGenerations) { g ->
            val symbolCount = udpExpGenerationChunkCount(g, initMeta.totalChunks, initMeta.generationSize)
            IntArray(symbolCount) { idx ->
                val global = g * initMeta.generationSize + idx
                udpExpExpectedChunkLength(global, initMeta.totalChunks, initMeta.fileSize, initMeta.chunkSize)
            }
        }
        emitProgress("已获取元信息，开始接收数据...")
    }

    fun markChunkKnownIfNew(index: Int): Boolean {
        val localFlags = chunkKnownFlags ?: return false
        if (index !in localFlags.indices) return false
        synchronized(localFlags) {
            if (localFlags[index]) return false
            localFlags[index] = true
            return true
        }
    }

    fun writeChunk(index: Int, payload: ByteArray, payloadOffset: Int, len: Int): Boolean {
        val currentMeta = meta ?: return false
        if (index !in 0 until currentMeta.totalChunks) return false
        if (len <= 0) return false
        if (!markChunkKnownIfNew(index)) return false
        val fileRaf = raf ?: return false
        val expectedLen = udpExpExpectedChunkLength(index, currentMeta.totalChunks, currentMeta.fileSize, currentMeta.chunkSize)
        val actualLen = min(len, expectedLen).coerceAtLeast(0)
        if (actualLen <= 0) return false
        val offset = index.toLong() * currentMeta.chunkSize
        synchronized(rafLock) {
            fileRaf.seek(offset)
            fileRaf.write(payload, payloadOffset, actualLen)
        }
        receivedBytes.addAndGet(actualLen.toLong())
        solvedChunks.incrementAndGet()
        return true
    }

    fun decoderForGeneration(generation: Int): UdpExpGenerationDecoder? {
        val currentMeta = meta ?: return null
        if (generation !in 0 until currentMeta.totalGenerations) return null
        val localDecoders = generationDecoders ?: return null
        localDecoders[generation]?.let { return it }
        val symbolCount = udpExpGenerationChunkCount(generation, currentMeta.totalChunks, currentMeta.generationSize)
        if (symbolCount <= 0) return null
        val created = UdpExpGenerationDecoder(symbolCount = symbolCount, symbolBytes = currentMeta.chunkSize)
        localDecoders[generation] = created
        return created
    }

    fun flushGenerationIfSolved(generation: Int) {
        val currentMeta = meta ?: return
        val solvedFlags = generationSolvedFlags ?: return
        if (generation !in solvedFlags.indices || solvedFlags[generation]) return
        val decoder = decoderForGeneration(generation) ?: return
        val decoded = decoder.decodedSymbolsOrNull() ?: return
        val lengths = generationChunkLengths?.getOrNull(generation) ?: return
        for (idx in decoded.indices) {
            val global = generation * currentMeta.generationSize + idx
            val len = lengths.getOrElse(idx) { currentMeta.chunkSize }.coerceIn(0, currentMeta.chunkSize)
            if (len <= 0) continue
            writeChunk(global, decoded[idx], 0, len)
        }
        solvedFlags[generation] = true
        solvedGenerationCount++
    }

    fun addSystemEquation(frame: UdpExpOneWaySystemFrame, incoming: DatagramPacket) {
        val currentMeta = meta ?: return
        val decoder = decoderForGeneration(frame.generation) ?: return
        val symbolCount = udpExpGenerationChunkCount(frame.generation, currentMeta.totalChunks, currentMeta.generationSize)
        if (frame.indexInGeneration !in 0 until symbolCount) return
        val coeff = ByteArray(symbolCount)
        coeff[frame.indexInGeneration] = 1
        val payload = ByteArray(currentMeta.chunkSize)
        val len = min(frame.length, currentMeta.chunkSize).coerceAtLeast(0)
        if (len > 0) {
            System.arraycopy(incoming.data, frame.payloadOffset, payload, 0, len)
        }
        decoder.addEquation(coeff, payload)
        val global = frame.generation * currentMeta.generationSize + frame.indexInGeneration
        writeChunk(global, incoming.data, frame.payloadOffset, frame.length)
        flushGenerationIfSolved(frame.generation)
    }

    fun addCodeEquation(frame: UdpExpOneWayCodeFrame, incoming: DatagramPacket) {
        val currentMeta = meta ?: return
        val decoder = decoderForGeneration(frame.generation) ?: return
        val symbolCount = udpExpGenerationChunkCount(frame.generation, currentMeta.totalChunks, currentMeta.generationSize)
        val coeff = buildUdpExpRlncCoefficients(symbolCount, frame.seed)
        val payload = ByteArray(currentMeta.chunkSize)
        val len = min(frame.length, currentMeta.chunkSize).coerceAtLeast(0)
        if (len > 0) {
            System.arraycopy(incoming.data, frame.payloadOffset, payload, 0, len)
        }
        decoder.addEquation(coeff, payload)
        flushGenerationIfSolved(frame.generation)
    }

    try {
        while (true) {
            if (isCancelled()) throw CancellationException("用户已中断")
            try {
                val inBuf = ByteArray((UDP_EXP_ONEWAY_DEFAULT_CHUNK_SIZE + 256).coerceAtLeast(2048))
                val incoming = DatagramPacket(inBuf, inBuf.size)
                socket.receive(incoming)
                lastPacketAt.set(System.currentTimeMillis())
                val type = incoming.data[0].toInt() and 0xff
                when (type) {
                    UDP_EXP_PKT_ONEWAY_META -> {
                        val (_, json) = parseUdpExpJsonPacket(incoming)
                        val incomingMeta = parseUdpExpOneWayMeta(json) ?: continue
                        if (meta == null) {
                            ensureSession(incomingMeta)
                            senderAddress = incoming.address
                            senderPort = incoming.port
                        }
                    }

                    UDP_EXP_PKT_ONEWAY_DATA -> {
                        val currentMeta = meta ?: continue
                        val frame = parseUdpExpOneWaySystemPacket(incoming) ?: continue
                        if (frame.sessionTag != currentMeta.sessionTag) continue
                        addSystemEquation(frame, incoming)
                    }

                    UDP_EXP_PKT_ONEWAY_PARITY -> {
                        val currentMeta = meta ?: continue
                        val frame = parseUdpExpOneWayCodePacket(incoming) ?: continue
                        if (frame.sessionTag != currentMeta.sessionTag) continue
                        addCodeEquation(frame, incoming)
                    }

                    UDP_EXP_PKT_ONEWAY_END -> {
                        val currentMeta = meta ?: continue
                        val (_, json) = parseUdpExpJsonPacket(incoming)
                        val sid = json?.optString("sid")?.trim().orEmpty()
                        val tag = json?.optInt("tag", 0) ?: 0
                        if (sid == currentMeta.sid && tag == currentMeta.sessionTag) {
                            endSeen = true
                        }
                    }
                }
            } catch (_: SocketTimeoutException) {
            }

            val currentMeta = meta
            if (currentMeta == null) {
                if (System.currentTimeMillis() > metaDeadline) {
                    throw IllegalStateException("等待单向元信息超时")
                }
                continue
            }

            val done = solvedChunks.get()
            if (done >= currentMeta.totalChunks) break

            val now = System.currentTimeMillis()
            if (now - lastEmitAt >= 180L) {
                lastEmitAt = now
                val missing = (currentMeta.totalChunks - done).coerceAtLeast(0)
                val avgRank = run {
                    val decoders = generationDecoders ?: arrayOfNulls<UdpExpGenerationDecoder>(0)
                    val active = decoders.filterNotNull()
                    if (active.isEmpty()) 0.0 else active.sumOf { it.rank() }.toDouble() / active.size.toDouble()
                }
                val senderHint = if (senderAddress != null && senderPort > 0) {
                    " 来自 ${senderAddress!!.hostAddress}:$senderPort"
                } else {
                    ""
                }
                emitProgress(
                    "单向接收中（已解 $done/${currentMeta.totalChunks} 块，缺失 $missing，已解代 $solvedGenerationCount/${currentMeta.totalGenerations}，平均秩 ${"%.1f".format(avgRank)}）$senderHint"
                )
            }
            if (now - lastPacketAt.get() >= UDP_EXP_ONEWAY_RECV_IDLE_TIMEOUT_MS) {
                val missing = (currentMeta.totalChunks - done).coerceAtLeast(0)
                val suffix = if (endSeen) "（发送端已结束）" else ""
                throw IllegalStateException("单向接收超时，仍缺失 $missing 块$suffix")
            }
        }
    } finally {
        runCatching { raf?.close() }
        runCatching { socket.close() }
    }

    val resultMeta = meta ?: throw IllegalStateException("未建立会话")
    val resultFile = outFile ?: throw IllegalStateException("输出文件创建失败")
    if (resultMeta.fileSha256.isNotBlank()) {
        val localHash = sha256OfFile(resultFile)
        if (!localHash.equals(resultMeta.fileSha256, ignoreCase = true)) {
            throw IllegalStateException("文件校验失败：SHA-256 不匹配")
        }
    }
    val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
    val avg = ((resultMeta.fileSize * 1000L) / elapsedMs).coerceAtLeast(0L)
    onProgress(
        UdpExpProgress(
            progress = 1f,
            stage = "接收完成（RLNC 解码 + SHA-256 校验通过）",
            fileName = resultMeta.fileName,
            totalBytes = resultMeta.fileSize,
            transferredBytes = resultMeta.fileSize,
            speedBytesPerSec = avg
        )
    )
    UdpExpReceiveResult(outFile = resultFile, fileName = resultMeta.fileName, size = resultMeta.fileSize)
}

@Composable
fun UdpFileTransferExperimentScreen(onBack: () -> Unit) {
    var tabIndex by remember { mutableIntStateOf(0) }
    var transferMode by remember { mutableStateOf(UdpExpTransferMode.RELIABLE_ACK) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回传输实验室") }
        Text("UDP传文件（实验）", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "支持双向可靠传输（握手 + ACK）与单向冗余传输（无握手）。单向模式适用于仅允许认证设备单向发包的校园网络环境。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TabRow(selectedTabIndex = transferMode.ordinal) {
            UdpExpTransferMode.entries.forEach { mode ->
                Tab(
                    selected = transferMode == mode,
                    onClick = { transferMode = mode },
                    text = { Text(mode.label) }
                )
            }
        }
        Text(
            transferMode.summary,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TabRow(selectedTabIndex = tabIndex) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("发送端") })
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("接收端") })
        }
        if (tabIndex == 0) {
            UdpFileExperimentSendPane(mode = transferMode)
        } else {
            UdpFileExperimentReceivePane(mode = transferMode)
        }
    }
}

@Composable
private fun UdpFileExperimentSendPane(mode: UdpExpTransferMode) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedFile by remember { mutableStateOf<UdpExpSendFile?>(null) }
    var localPortInput by remember { mutableStateOf("12333") }
    var remoteHostInput by remember { mutableStateOf("") }
    var remotePortInput by remember { mutableStateOf("12333") }
    var threadInput by remember { mutableStateOf("8") }
    var oneWayChunkSizeInput by remember { mutableStateOf(UDP_EXP_ONEWAY_DEFAULT_CHUNK_SIZE.toString()) }
    var oneWayRepeatInput by remember { mutableStateOf(UDP_EXP_ONEWAY_DEFAULT_REPEAT.toString()) }
    var oneWayGroupSizeInput by remember { mutableStateOf(UDP_EXP_ONEWAY_DEFAULT_GROUP_SIZE.toString()) }
    var showScanner by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var status by remember(mode) {
        mutableStateOf(
            if (mode == UdpExpTransferMode.RELIABLE_ACK) {
                "请先选择文件，填写接收端地址后开始发送。"
            } else {
                "单向模式：先让接收端进入“开始接收”，再点击发送。"
            }
        )
    }
    var progress by remember { mutableFloatStateOf(0f) }
    var totalBytes by remember { mutableLongStateOf(0L) }
    var doneBytes by remember { mutableLongStateOf(0L) }
    var speedBytes by remember { mutableLongStateOf(0L) }
    val cancelSignal = remember { AtomicBoolean(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = resolveUdpExpSendFile(context, uri)
            if (picked == null) status = "选中文件无效，无法读取大小。"
            else {
                selectedFile = picked
                status = "已选择：${picked.name}（${udpExpFormatSize(picked.size)}）"
            }
        }
    }

    if (showScanner) {
        QRCodeScanner(
            onQRCodeScanned = { raw ->
                showScanner = false
                val peer = parseUdpExpPeerFromQr(raw)
                if (peer == null) status = "二维码内容无法识别。"
                else {
                    remoteHostInput = peer.host
                    remotePortInput = peer.port.toString()
                    status = "已识别接收端：${peer.host}:${peer.port}"
                }
            },
            onDismiss = { showScanner = false }
        )
    }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("发送配置", fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { picker.launch("*/*") }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                Text(if (selectedFile == null) "选择文件（单选）" else "更换文件（单选）")
            }
            selectedFile?.let {
                Text("文件：${it.name}（${udpExpFormatSize(it.size)}）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(value = localPortInput, onValueChange = { localPortInput = it.filter(Char::isDigit) }, label = { Text("本地基准端口") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !running)
            OutlinedTextField(value = remoteHostInput, onValueChange = { remoteHostInput = it.trim() }, label = { Text("接收端 IP/主机") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !running)
            OutlinedTextField(value = remotePortInput, onValueChange = { remotePortInput = it.filter(Char::isDigit) }, label = { Text("接收端基准端口") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !running)
            if (mode == UdpExpTransferMode.RELIABLE_ACK) {
                OutlinedTextField(
                    value = threadInput,
                    onValueChange = { threadInput = it.filter(Char::isDigit) },
                    label = { Text("线程数（1-64）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
            } else {
                OutlinedTextField(
                    value = oneWayChunkSizeInput,
                    onValueChange = { oneWayChunkSizeInput = it.filter(Char::isDigit) },
                    label = { Text("数据块大小（512-1300）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
                OutlinedTextField(
                    value = oneWayRepeatInput,
                    onValueChange = { oneWayRepeatInput = it.filter(Char::isDigit) },
                    label = { Text("重复轮次（0=无限，最大 4096）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
                OutlinedTextField(
                    value = oneWayGroupSizeInput,
                    onValueChange = { oneWayGroupSizeInput = it.filter(Char::isDigit) },
                    label = { Text("代大小 K（2-256）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
                Text(
                    "单向模式使用分代 RLNC（GF(256)）冗余编码，轮次设为 0 时会无限重复发送，需手动中断。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showScanner = true }, enabled = !running, modifier = Modifier.weight(1f)) { Text("扫码接收端") }
                Button(
                    onClick = {
                        val localPort = localPortInput.toIntOrNull()
                        val remotePort = remotePortInput.toIntOrNull()
                        val file = selectedFile
                        if (file == null) {
                            status = "请先选择文件。"
                            return@Button
                        }
                        if (localPort == null || remotePort == null || localPort !in 1..65535 || remotePort !in 1..65535 || remoteHostInput.isBlank()) {
                            status = "请填写有效端口和接收端地址。"
                            return@Button
                        }
                        running = true
                        cancelSignal.set(false)
                        progress = 0f
                        totalBytes = file.size
                        doneBytes = 0L
                        speedBytes = 0L
                        status = "开始发送..."
                        scope.launch {
                            runCatching {
                                if (mode == UdpExpTransferMode.RELIABLE_ACK) {
                                    val t = threadInput.toIntOrNull() ?: 8
                                    sendFileByUdpExperiment(
                                        context = context,
                                        file = file,
                                        localPort = localPort,
                                        remoteHost = remoteHostInput,
                                        remotePort = remotePort,
                                        requestedThreads = t,
                                        isCancelled = { cancelSignal.get() }
                                    ) { p ->
                                        progress = p.progress
                                        status = p.stage
                                        totalBytes = p.totalBytes
                                        doneBytes = p.transferredBytes
                                        speedBytes = p.speedBytesPerSec
                                    }
                                } else {
                                    val oneWayChunkSize = oneWayChunkSizeInput.toIntOrNull() ?: UDP_EXP_ONEWAY_DEFAULT_CHUNK_SIZE
                                    val oneWayRepeat = oneWayRepeatInput.toIntOrNull() ?: UDP_EXP_ONEWAY_DEFAULT_REPEAT
                                    val oneWayGroup = oneWayGroupSizeInput.toIntOrNull() ?: UDP_EXP_ONEWAY_DEFAULT_GROUP_SIZE
                                    sendFileByUdpOneWayExperiment(
                                        context = context,
                                        file = file,
                                        localPort = localPort,
                                        remoteHost = remoteHostInput,
                                        remotePort = remotePort,
                                        requestedChunkSize = oneWayChunkSize,
                                        requestedRepeatCount = oneWayRepeat,
                                        requestedGroupSize = oneWayGroup,
                                        isCancelled = { cancelSignal.get() }
                                    ) { p ->
                                        progress = p.progress
                                        status = p.stage
                                        totalBytes = p.totalBytes
                                        doneBytes = p.transferredBytes
                                        speedBytes = p.speedBytesPerSec
                                    }
                                }
                            }.onSuccess { status = it }
                                .onFailure { e -> status = if (e is CancellationException) "已取消发送" else "发送失败：${e.message ?: "unknown"}" }
                            running = false
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.weight(1f)
                ) { Text("开始发送") }
            }
            if (running) {
                OutlinedButton(onClick = { cancelSignal.set(true); status = "正在取消..." }, modifier = Modifier.fillMaxWidth()) { Text("中断发送") }
            }
            if (totalBytes > 0L) {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("进度：${udpExpFormatSize(doneBytes)} / ${udpExpFormatSize(totalBytes)}    速率：${udpExpFormatRate(speedBytes)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UdpFileExperimentReceivePane(mode: UdpExpTransferMode) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localIp by remember { mutableStateOf(NetworkUtils.getLocalIpAddress(context).orEmpty()) }
    var localPortInput by remember { mutableStateOf("12333") }
    var threadInput by remember { mutableStateOf("8") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var running by remember { mutableStateOf(false) }
    var status by remember(mode) {
        mutableStateOf(
            if (mode == UdpExpTransferMode.RELIABLE_ACK) {
                "请生成二维码给发送端，或让发送端手动输入你的 IP:端口。"
            } else {
                "单向模式：先点击开始接收，等待发送端持续发包。"
            }
        )
    }
    var progress by remember { mutableFloatStateOf(0f) }
    var totalBytes by remember { mutableLongStateOf(0L) }
    var doneBytes by remember { mutableLongStateOf(0L) }
    var speedBytes by remember { mutableLongStateOf(0L) }
    var savedPath by remember { mutableStateOf("") }
    val cancelSignal = remember { AtomicBoolean(false) }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("接收配置", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = localIp, onValueChange = { localIp = it.trim() }, label = { Text("本机 IPv4") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !running)
            OutlinedTextField(value = localPortInput, onValueChange = { localPortInput = it.filter(Char::isDigit) }, label = { Text("本地基准端口") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !running)
            if (mode == UdpExpTransferMode.RELIABLE_ACK) {
                OutlinedTextField(
                    value = threadInput,
                    onValueChange = { threadInput = it.filter(Char::isDigit) },
                    label = { Text("接收线程数（1-64）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !running
                )
            } else {
                Text(
                    "单向模式固定单端口接收（无 ACK、无握手），通过 RLNC 增量消元恢复缺失块。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        localIp = NetworkUtils.getLocalIpAddress(context).orEmpty()
                        status = if (localIp.isBlank()) "未获取到本机 IPv4，请手动填写。" else "已刷新本机 IPv4。"
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !running
                ) { Text("刷新本机IP") }
                OutlinedButton(
                    onClick = {
                        val p = localPortInput.toIntOrNull()
                        if (localIp.isBlank() || p !in 1..65535) {
                            status = "请先填写有效本机 IP 和端口。"
                            return@OutlinedButton
                        }
                        val payload = JSONObject()
                            .put("type", UDP_EXP_QR_TYPE)
                            .put("host", localIp)
                            .put("port", p)
                            .put(
                                "modeHint",
                                if (mode == UdpExpTransferMode.ONEWAY_REDUNDANT) "oneway" else "reliable"
                            )
                            .toString()
                        qrBitmap = QRCodeGenerator.generateQRCode(payload, 512)
                        status = if (qrBitmap == null) "二维码生成失败。" else "二维码已生成，请让发送端扫码。"
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !running
                ) { Text("生成二维码") }
            }
            qrBitmap?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Image(bitmap = it.asImageBitmap(), contentDescription = "udp_file_recv_qr", modifier = Modifier.fillMaxWidth().height(260.dp))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val localPort = localPortInput.toIntOrNull()
                        if (localPort == null || localPort !in 1..65535) {
                            status = "请填写有效端口。"
                            return@Button
                        }
                        running = true
                        cancelSignal.set(false)
                        progress = 0f
                        totalBytes = 0L
                        doneBytes = 0L
                        speedBytes = 0L
                        savedPath = ""
                        status = if (mode == UdpExpTransferMode.RELIABLE_ACK) "等待发送端连接..." else "等待单向元信息..."
                        scope.launch {
                            runCatching {
                                if (mode == UdpExpTransferMode.RELIABLE_ACK) {
                                    val threads = threadInput.toIntOrNull() ?: 8
                                    receiveFileByUdpExperiment(
                                        context = context,
                                        localPort = localPort,
                                        localThreads = threads,
                                        isCancelled = { cancelSignal.get() }
                                    ) { p ->
                                        progress = p.progress
                                        status = p.stage
                                        totalBytes = p.totalBytes
                                        doneBytes = p.transferredBytes
                                        speedBytes = p.speedBytesPerSec
                                    }
                                } else {
                                    receiveFileByUdpOneWayExperiment(
                                        context = context,
                                        localPort = localPort,
                                        isCancelled = { cancelSignal.get() }
                                    ) { p ->
                                        progress = p.progress
                                        status = p.stage
                                        totalBytes = p.totalBytes
                                        doneBytes = p.transferredBytes
                                        speedBytes = p.speedBytesPerSec
                                    }
                                }
                            }.onSuccess { result ->
                                status = "接收完成：${result.fileName}（${udpExpFormatSize(result.size)}）"
                                savedPath = result.outFile.absolutePath
                            }.onFailure { e ->
                                status = if (e is CancellationException) "已取消接收" else "接收失败：${e.message ?: "unknown"}"
                            }
                            running = false
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.weight(1f)
                ) { Text("开始接收") }
                OutlinedButton(
                    onClick = { cancelSignal.set(true); status = "正在取消..." },
                    enabled = running,
                    modifier = Modifier.weight(1f)
                ) { Text("中断接收") }
            }
            if (totalBytes > 0L) {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("进度：${udpExpFormatSize(doneBytes)} / ${udpExpFormatSize(totalBytes)}    速率：${udpExpFormatRate(speedBytes)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (savedPath.isNotBlank()) {
                Text("保存路径：$savedPath", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
