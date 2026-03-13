package com.yuliwen.filetran

import android.content.Context
import android.net.Uri
import android.nfc.tech.IsoDep
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.CRC32
import kotlin.math.ceil
import kotlin.math.min

data class HceSessionInfo(
    val sessionId: Int,
    val fileName: String,
    val mimeType: String,
    val file: File,
    val payload: ByteArray,
    val totalBytes: Int,
    val chunkSize: Int,
    val chunkCount: Int,
    val crc32: Int
)

data class HceRemoteInfo(
    val sessionId: Int,
    val totalBytes: Int,
    val chunkSize: Int,
    val chunkCount: Int,
    val crc32: Int,
    val fileName: String,
    val mimeType: String
)

data class HceReceivedFile(
    val file: File,
    val mimeType: String
)

data class HceResumeState(
    val sessionId: Int,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Int,
    val chunkSize: Int,
    val chunkCount: Int,
    val tempFilePath: String,
    val nextChunkIndex: Int,
    val receivedBytes: Int
)

data class HceChunkSlice(
    val session: HceSessionInfo,
    val offset: Int,
    val length: Int
)

object HceTransferStore {
    private const val TAG = "FT-HCE-STORE"
    private const val MAX_FILE_BYTES = 20 * 1024 * 1024 // 20MB: practical for HCE transfer time
    private const val DEFAULT_CHUNK_BYTES = 220
    private const val MIN_CHUNK_BYTES = 64
    private var current: HceSessionInfo? = null

    @Synchronized
    fun getCurrent(): HceSessionInfo? = current

    @Synchronized
    fun clear() {
        Log.d(TAG, "clear session")
        runCatching { current?.file?.delete() }
        current = null
    }

    @Synchronized
    fun prepare(context: Context, uri: Uri, chunkSizeBytes: Int = DEFAULT_CHUNK_BYTES): HceSessionInfo {
        clear()
        val finalChunk = chunkSizeBytes.coerceAtLeast(MIN_CHUNK_BYTES)
        val meta = queryMeta(context, uri)
        val cacheFile = File(context.cacheDir, "hce_payload_${UUID.randomUUID()}.bin")
        val crc = CRC32()
        var total = 0L
        val memory = java.io.ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                total += n
                if (total > MAX_FILE_BYTES) {
                    throw IllegalArgumentException("文件过大，HCE 直传建议 <= ${MAX_FILE_BYTES / (1024 * 1024)}MB")
                }
                memory.write(buffer, 0, n)
                crc.update(buffer, 0, n)
            }
        } ?: throw IllegalStateException("无法读取文件")

        val payload = memory.toByteArray()
        FileOutputStream(cacheFile).use { it.write(payload) }
        val totalInt = total.toInt()
        val chunkCount = ceil(totalInt.toDouble() / finalChunk.toDouble()).toInt().coerceAtLeast(1)
        val session = HceSessionInfo(
            sessionId = (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
            fileName = meta.first,
            mimeType = meta.second,
            file = cacheFile,
            payload = payload,
            totalBytes = totalInt,
            chunkSize = finalChunk,
            chunkCount = chunkCount,
            crc32 = crc.value.toInt()
        )
        current = session
        Log.i(
            TAG,
            "prepare sessionId=${session.sessionId} file=${session.fileName} total=${session.totalBytes} chunks=${session.chunkCount}"
        )
        return session
    }

    @Synchronized
    fun readChunk(index: Int): HceChunkSlice {
        val session = current ?: throw IllegalStateException("发送端尚未准备文件")
        if (index < 0 || index >= session.chunkCount) {
            throw IllegalArgumentException("分块索引越界: $index")
        }
        val offset = index * session.chunkSize
        val length = min(session.chunkSize, session.totalBytes - offset)
        return HceChunkSlice(session = session, offset = offset, length = length)
    }

    private fun queryMeta(context: Context, uri: Uri): Pair<String, String> {
        var name = "hce_file_${System.currentTimeMillis()}"
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && nameIdx >= 0) {
                name = c.getString(nameIdx) ?: name
            }
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        return name to mime
    }
}

object HceApduProtocol {
    private const val TAG = "FT-HCE-READER"
    // App-unique AID to reduce routing collisions with other HCE services.
    val AID_BYTES: ByteArray = hexToBytes("F046494C455452414E31")
    private const val CLA: Byte = 0x80.toByte()
    private const val INS_INFO: Byte = 0x10
    private const val INS_CHUNK: Byte = 0x11
    private const val SW_OK: Int = 0x9000
    private const val SW_NO_SESSION_CODE: Int = 0x6A84

    private val SW_OK_BYTES = byteArrayOf(0x90.toByte(), 0x00.toByte())
    private val SW_NO_SESSION = byteArrayOf(0x6A.toByte(), 0x84.toByte())
    private val SW_BAD_CMD = byteArrayOf(0x6D.toByte(), 0x00.toByte())
    private val SW_BAD_STATE = byteArrayOf(0x69.toByte(), 0x85.toByte())
    private val SW_WRONG_PARAM = byteArrayOf(0x6B.toByte(), 0x00.toByte())

    val SELECT_APDU: ByteArray = buildSelectAidApdu(AID_BYTES)

    fun isSelectAid(apdu: ByteArray): Boolean {
        if (apdu.size < 5) return false
        if (apdu[0] != 0x00.toByte() || apdu[1] != 0xA4.toByte()) return false
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return false
        val aid = apdu.copyOfRange(5, 5 + lc)
        return aid.contentEquals(AID_BYTES)
    }

    fun buildInfoCommand(): ByteArray = byteArrayOf(CLA, INS_INFO, 0x00, 0x00, 0x00)

    fun buildChunkCommand(index: Int): ByteArray {
        val body = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(index).array()
        return byteArrayOf(CLA, INS_CHUNK, 0x00, 0x00, body.size.toByte()) + body
    }

    fun success(payload: ByteArray): ByteArray = payload + SW_OK_BYTES
    fun noSession(): ByteArray = SW_NO_SESSION
    fun badCommand(): ByteArray = SW_BAD_CMD
    fun badState(): ByteArray = SW_BAD_STATE
    fun wrongParam(): ByteArray = SW_WRONG_PARAM

    fun parseResponse(response: ByteArray): ByteArray {
        val (payload, sw) = splitResponse(response)
        if (sw != SW_OK) {
            val msg = when (sw) {
                0x6A82 -> "发送端未就绪或未路由到本应用（请在发送端点击“启动 HCE 发送”并保持前台亮屏）"
                SW_NO_SESSION_CODE -> "发送端尚未准备好会话（请重新点击“启动 HCE 发送”）"
                0x6985 -> "发送端服务状态不可用，请重新启动发送"
                0x6B00 -> "APDU 参数错误，双方版本可能不一致"
                0x6D00 -> "发送端不支持该命令，双方版本可能不一致"
                else -> "SW=0x${sw.toString(16)}"
            }
            throw IllegalStateException("APDU 响应失败：$msg")
        }
        return payload
    }

    fun encodeInfoPayload(info: HceSessionInfo): ByteArray {
        val nameBytes = info.fileName.toByteArray(StandardCharsets.UTF_8).take(96).toByteArray()
        val mimeBytes = info.mimeType.toByteArray(StandardCharsets.UTF_8).take(64).toByteArray()
        val buf = ByteBuffer.allocate(
            1 + 4 + 4 + 2 + 4 + 2 + 2 + 4 + nameBytes.size + mimeBytes.size
        ).order(ByteOrder.BIG_ENDIAN)
        buf.put(1) // version
        buf.putInt(info.sessionId)
        buf.putInt(info.totalBytes)
        buf.putShort(info.chunkSize.toShort())
        buf.putInt(info.chunkCount)
        buf.putShort(nameBytes.size.toShort())
        buf.putShort(mimeBytes.size.toShort())
        buf.putInt(info.crc32)
        buf.put(nameBytes)
        buf.put(mimeBytes)
        return buf.array()
    }

    fun decodeInfoPayload(payload: ByteArray): HceRemoteInfo {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get().toInt() and 0xFF
        if (version != 1) throw IllegalStateException("不支持的协议版本: $version")
        val sessionId = buf.int
        val totalBytes = buf.int
        val chunkSize = buf.short.toInt() and 0xFFFF
        val chunkCount = buf.int
        val nameLen = buf.short.toInt() and 0xFFFF
        val mimeLen = buf.short.toInt() and 0xFFFF
        val crc32 = buf.int
        if (nameLen < 0 || mimeLen < 0 || nameLen + mimeLen > buf.remaining()) {
            throw IllegalStateException("INFO 数据损坏")
        }
        val nameBytes = ByteArray(nameLen)
        buf.get(nameBytes)
        val mimeBytes = ByteArray(mimeLen)
        buf.get(mimeBytes)
        return HceRemoteInfo(
            sessionId = sessionId,
            totalBytes = totalBytes,
            chunkSize = chunkSize,
            chunkCount = chunkCount,
            crc32 = crc32,
            fileName = String(nameBytes, StandardCharsets.UTF_8),
            mimeType = String(mimeBytes, StandardCharsets.UTF_8)
        )
    }

    fun encodeChunkPayload(sessionId: Int, index: Int, chunkCount: Int, data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + 4 + 4 + 2 + data.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(sessionId)
        buf.putInt(index)
        buf.putInt(chunkCount)
        buf.putShort(data.size.toShort())
        buf.put(data)
        return buf.array()
    }

    fun encodeChunkPayload(
        sessionId: Int,
        index: Int,
        chunkCount: Int,
        source: ByteArray,
        offset: Int,
        length: Int
    ): ByteArray {
        val buf = ByteBuffer.allocate(4 + 4 + 4 + 2 + length).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(sessionId)
        buf.putInt(index)
        buf.putInt(chunkCount)
        buf.putShort(length.toShort())
        buf.put(source, offset, length)
        return buf.array()
    }

    fun decodeChunkPayload(payload: ByteArray): ChunkFrame {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        if (buf.remaining() < 14) throw IllegalStateException("CHUNK 帧过短")
        val sessionId = buf.int
        val index = buf.int
        val chunkCount = buf.int
        val dataLen = buf.short.toInt() and 0xFFFF
        if (dataLen > buf.remaining()) throw IllegalStateException("CHUNK 数据长度异常")
        val data = ByteArray(dataLen)
        buf.get(data)
        return ChunkFrame(sessionId, index, chunkCount, data)
    }

    data class ChunkFrame(
        val sessionId: Int,
        val index: Int,
        val chunkCount: Int,
        val data: ByteArray
    )

    fun receiveFileFromIsoDep(
        context: Context,
        isoDep: IsoDep,
        verifyIntegrity: Boolean = true,
        resumeState: HceResumeState? = null,
        onCheckpoint: ((HceResumeState) -> Unit)? = null,
        onProgress: (doneChunks: Int, totalChunks: Int, doneBytes: Int, totalBytes: Int) -> Unit
    ): HceReceivedFile {
        Log.d(TAG, "IsoDep connect start")
        isoDep.connect()
        isoDep.timeout = 12000
        Log.d(TAG, "IsoDep maxTransceiveLength=${isoDep.maxTransceiveLength} timeout=${isoDep.timeout}")
        try {
            val (selectPayload, selectSw) = selectWithRetry(isoDep)
            if (selectPayload.isNotEmpty() && !selectPayload.contentEquals(byteArrayOf(0x46, 0x54, 0x31))) {
                // Some ROM/HCE stacks may return extra or transformed bytes while still routing correctly.
            }
            val infoPayload = fetchInfoPayloadWithRetry(isoDep)
            val info = decodeInfoPayload(infoPayload)
            Log.i(
                TAG,
                "INFO ok sessionId=${info.sessionId} file=${info.fileName} total=${info.totalBytes} chunks=${info.chunkCount}"
            )
            val canResume = resumeState?.let { state ->
                state.sessionId == info.sessionId &&
                    state.totalBytes == info.totalBytes &&
                    state.chunkCount == info.chunkCount &&
                    state.chunkSize == info.chunkSize &&
                    File(state.tempFilePath).exists()
            } == true
            val target = if (canResume) File(resumeState!!.tempFilePath) else createUniqueTargetFile(context, info.fileName)
            val crc = CRC32()
            var receivedBytes = if (canResume) {
                min(info.totalBytes, resumeState!!.nextChunkIndex * info.chunkSize)
            } else 0
            val startIndex = if (canResume) resumeState!!.nextChunkIndex.coerceIn(0, info.chunkCount) else 0
            if (startIndex > 0) {
                onProgress(startIndex, info.chunkCount, receivedBytes, info.totalBytes)
            }
            RandomAccessFile(target, "rw").use { out ->
                out.seek(receivedBytes.toLong())
                for (index in startIndex until info.chunkCount) {
                    val frame = decodeChunkPayload(
                        parseResponse(isoDep.transceive(buildChunkCommand(index)))
                    )
                    if (verifyIntegrity && (frame.sessionId != info.sessionId || frame.index != index)) {
                        throw IllegalStateException("分块校验失败，index=$index")
                    }
                    out.write(frame.data, 0, frame.data.size)
                    if (verifyIntegrity) crc.update(frame.data)
                    receivedBytes += frame.data.size
                    onProgress(index + 1, info.chunkCount, receivedBytes, info.totalBytes)
                    onCheckpoint?.invoke(
                        HceResumeState(
                            sessionId = info.sessionId,
                            fileName = info.fileName,
                            mimeType = info.mimeType,
                            totalBytes = info.totalBytes,
                            chunkSize = info.chunkSize,
                            chunkCount = info.chunkCount,
                            tempFilePath = target.absolutePath,
                            nextChunkIndex = index + 1,
                            receivedBytes = receivedBytes
                        )
                    )
                    if (index == 0 || index == info.chunkCount - 1 || index % 100 == 0) {
                        Log.d(TAG, "CHUNK recv idx=$index/${info.chunkCount - 1} bytes=${frame.data.size}")
                    }
                }
            }
            if (verifyIntegrity && crc.value.toInt() != info.crc32) {
                runCatching { target.delete() }
                throw IllegalStateException("CRC 校验失败，文件可能损坏")
            }
            Log.i(TAG, "receive success file=${target.absolutePath}")
            return HceReceivedFile(file = target, mimeType = info.mimeType)
        } finally {
            Log.d(TAG, "IsoDep close")
            runCatching { isoDep.close() }
        }
    }

    private fun selectWithRetry(isoDep: IsoDep): Pair<ByteArray, Int> {
        repeat(10) { attempt ->
            val (payload, sw) = splitResponse(isoDep.transceive(SELECT_APDU))
            Log.d(TAG, "SELECT attempt=${attempt + 1} sw=0x${sw.toString(16)} payloadLen=${payload.size}")
            if (sw == SW_OK) return payload to sw
            if (attempt < 9) Thread.sleep(120)
        }
        val (payload, sw) = splitResponse(isoDep.transceive(SELECT_APDU))
        throw IllegalStateException("未路由到本应用 HCE 服务（SELECT 失败，SW=0x${sw.toString(16)}）")
    }

    private fun fetchInfoPayloadWithRetry(isoDep: IsoDep): ByteArray {
        repeat(12) { attempt ->
            val (payload, sw) = splitResponse(isoDep.transceive(buildInfoCommand()))
            Log.d(TAG, "INFO attempt=${attempt + 1} sw=0x${sw.toString(16)} payloadLen=${payload.size}")
            if (sw == SW_OK) return payload
            if (sw != SW_NO_SESSION_CODE) {
                val swHex = sw.toString(16)
                throw IllegalStateException("INFO 命令失败，SW=0x$swHex（非会话未就绪）")
            }
            if (attempt < 11) Thread.sleep(150)
        }
        throw IllegalStateException("发送端尚未准备好会话（INFO 重试超时）")
    }

    private fun splitResponse(response: ByteArray): Pair<ByteArray, Int> {
        if (response.size < 2) throw IllegalStateException("APDU 响应过短")
        val sw = ((response[response.size - 2].toInt() and 0xFF) shl 8) or
            (response[response.size - 1].toInt() and 0xFF)
        return response.copyOfRange(0, response.size - 2) to sw
    }

    private fun buildSelectAidApdu(aid: ByteArray): ByteArray {
        val header = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte())
        return header + aid + byteArrayOf(0x00)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex 长度必须为偶数" }
        return ByteArray(hex.length / 2) { i ->
            val pos = i * 2
            hex.substring(pos, pos + 2).toInt(16).toByte()
        }
    }

    private fun createUniqueTargetFile(context: Context, rawName: String): File {
        val safe = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val dir = FileDownloadManager.getDownloadDirectory(context)
        var target = File(dir, safe)
        var index = 1
        while (target.exists()) {
            val base = safe.substringBeforeLast('.')
            val ext = safe.substringAfterLast('.', "")
            target = if (ext.isBlank()) {
                File(dir, "${base}_$index")
            } else {
                File(dir, "${base}_$index.$ext")
            }
            index++
        }
        return target
    }
}
