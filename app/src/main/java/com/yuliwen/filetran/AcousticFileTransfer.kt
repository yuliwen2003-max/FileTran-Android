package com.yuliwen.filetran

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object AcousticFileTransfer {
    private const val MAGIC = 0x4654
    private const val VERSION = 2
    private const val TYPE_DATA = 1
    private const val TYPE_PARITY = 2

    private const val PAYLOAD_BYTES = 128
    private const val GROUP_DATA_COUNT = 8
    private const val BASE_FRAME_REPEAT = 1
    private const val MAX_FILE_BYTES = 8 * 1024

    private const val SAMPLE_RATE = 44_100
    // Keep exact symbol duration and use the original robust FSK pair.
    private const val BIT_RATE = 300
    private const val BIT_SAMPLES = SAMPLE_RATE / BIT_RATE // 147
    private const val FREQ0 = 1200.0
    private const val FREQ1 = 2200.0
    private const val EDGE_RAMP_SAMPLES = BIT_SAMPLES / 10

    private const val HEADER_BYTES = 21
    private const val CRC_BYTES = 4
    private const val FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + CRC_BYTES
    private const val FRAME_BITS = FRAME_BYTES * 12

    private val SYNC_BITS = intArrayOf(
        1, 1, 0, 1, 1, 1, 0, 0,
        1, 0, 1, 0, 1, 0, 1, 0,
        0, 1, 0, 1, 0, 1, 0, 1,
        1, 1, 0, 0, 1, 1, 0, 1
    )

    data class EncodedAudio(
        val wavUri: Uri,
        val wavFile: File,
        val inputName: String,
        val inputSize: Int,
        val sessionId: Int,
        val totalDataFrames: Int,
        val dataFrames: Int,
        val parityFrames: Int,
        val isRetransmit: Boolean,
        val durationSec: Double,
        val txUnits: List<TxUnit>
    )

    data class TxUnit(
        val type: Int,
        val index: Int,
        val pass: Int
    )

    data class DecodedFile(
        val fileUri: Uri,
        val file: File,
        val fileName: String,
        val fileSize: Int,
        val correctedGroups: Int,
        val frameErrors: Int
    )

    data class ReceiveProgress(
        val sessionId: Int?,
        val totalDataFrames: Int,
        val receivedDataFrames: Int,
        val receivedDataIndices: List<Int>,
        val recoverableFrames: Int,
        val missingDataIndices: List<Int>,
        val frameErrors: Int,
        val hasAllData: Boolean
    ) {
        val effectiveFrames: Int
            get() = if (totalDataFrames <= 0) 0 else (receivedDataFrames + recoverableFrames).coerceAtMost(totalDataFrames)

        val dataProgressFraction: Float
            get() = if (totalDataFrames <= 0) 0f else effectiveFrames.toFloat() / totalDataFrames.toFloat()

        val progressFraction: Float
            get() = dataProgressFraction

        val isDecodeReady: Boolean
            get() = hasAllData && totalDataFrames > 0
    }

    data class RetransmitRequest(
        val sessionId: Int,
        val totalDataFrames: Int,
        val missingIndices: List<Int>
    )

    data class FileMeta(
        val fileName: String,
        val fileSize: Int,
        val totalDataFrames: Int
    )

    class DecodeFailure(message: String, val frameErrors: Int) : Exception(message)

    private data class ProtocolFrame(
        val type: Int,
        val sessionId: Int,
        val index: Int,
        val group: Int,
        val slot: Int,
        val payloadLen: Int,
        val totalDataFrames: Int,
        val fileSize: Int,
        val payload: ByteArray
    )

    private data class ParsedFrame(val frame: ProtocolFrame)

    fun inspectFileUri(context: Context, fileUri: Uri): FileMeta {
        val bytes = context.contentResolver.openInputStream(fileUri)?.use { readLimitedBytes(it, MAX_FILE_BYTES) }
            ?: throw IllegalArgumentException("Unable to read file")
        if (bytes.isEmpty()) throw IllegalArgumentException("File is empty")
        val fileName = runCatching { getFileName(context, fileUri) }.getOrElse { "acoustic.bin" }
        val totalData = ceil(bytes.size / PAYLOAD_BYTES.toDouble()).toInt()
        return FileMeta(fileName, bytes.size, totalData)
    }

    fun encodeFileUriToWave(
        context: Context,
        fileUri: Uri,
        sessionIdOverride: Int? = null,
        retransmitIndices: Set<Int>? = null,
        retransmitRepeat: Int = 5
    ): EncodedAudio {
        val bytes = context.contentResolver.openInputStream(fileUri)?.use { readLimitedBytes(it, MAX_FILE_BYTES) }
            ?: throw IllegalArgumentException("Unable to read file")
        if (bytes.isEmpty()) throw IllegalArgumentException("File is empty")
        val fileName = runCatching { getFileName(context, fileUri) }.getOrElse { "acoustic.bin" }
        return encodeBytesToWave(
            context = context,
            fileBytes = bytes,
            fileName = fileName,
            sessionIdOverride = sessionIdOverride,
            retransmitIndices = retransmitIndices,
            retransmitRepeat = retransmitRepeat
        )
    }

    fun encodeBytesToWave(
        context: Context,
        fileBytes: ByteArray,
        fileName: String,
        sessionIdOverride: Int? = null,
        retransmitIndices: Set<Int>? = null,
        retransmitRepeat: Int = 5
    ): EncodedAudio {
        val fullTotalData = ceil(fileBytes.size / PAYLOAD_BYTES.toDouble()).toInt()
        val selectedData = retransmitIndices?.filter { it in 0 until fullTotalData }?.toSet()
        if (selectedData != null && selectedData.isEmpty()) {
            throw IllegalArgumentException("No valid retransmit block selected")
        }
        if (selectedData != null && sessionIdOverride == null) {
            throw IllegalArgumentException("Retransmit requires target session ID")
        }
        val sessionId = sessionIdOverride ?: ((System.currentTimeMillis() and 0x7FFFFFFF).toInt())
        val selectedGroups = selectedData?.map { it / GROUP_DATA_COUNT }?.toSet()
        val frames = buildFrames(sessionId, fileBytes, selectedData, selectedGroups)
        val tx = buildTxBits(frames, selectedData != null, retransmitRepeat)
        val bits = tx.first
        val txUnits = tx.second
        val pcm = modulateBits(bits)
        val outDir = File(context.externalCacheDir ?: context.cacheDir, "acoustic").apply { mkdirs() }
        val outFile = File(outDir, "acoustic_${System.currentTimeMillis()}.wav")
        writeWavMono16(outFile, SAMPLE_RATE, pcm)
        val outUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        return EncodedAudio(
            wavUri = outUri,
            wavFile = outFile,
            inputName = fileName,
            inputSize = fileBytes.size,
            sessionId = sessionId,
            totalDataFrames = fullTotalData,
            dataFrames = frames.count { it.type == TYPE_DATA },
            parityFrames = frames.count { it.type == TYPE_PARITY },
            isRetransmit = selectedData != null,
            durationSec = pcm.size.toDouble() / SAMPLE_RATE.toDouble(),
            txUnits = txUnits
        )
    }

    fun decodeWaveUriToFile(context: Context, wavUri: Uri): DecodedFile {
        val wavBytes = context.contentResolver.openInputStream(wavUri)?.use { it.readBytes() }
            ?: throw DecodeFailure("Unable to read WAV", 0)
        val pcm = parseWavPcm16(wavBytes) ?: throw DecodeFailure("Only 16-bit PCM WAV 44.1kHz mono supported", 0)

        val bits = demodulateBestOffset(pcm)
        val (parsed, frameErrors) = parseFramesFromBits(bits)
        if (parsed.isEmpty()) throw DecodeFailure("No valid protocol frame found", frameErrors)
        val (name, payload, correctedGroups) = reassemble(parsed, frameErrors)

        val outDir = File(FileDownloadManager.getDownloadDirectory(context), "Acoustic").apply { mkdirs() }
        val safeName = sanitizeFileName(name)
        val outFile = File(outDir, safeName)
        FileOutputStream(outFile).use { it.write(payload) }
        val outUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        return DecodedFile(outUri, outFile, outFile.name, payload.size, correctedGroups, frameErrors)
    }

    fun estimateProgressFromRawPcmFile(rawPcmFile: File, preferredSessionId: Int? = null): ReceiveProgress {
        if (!rawPcmFile.exists() || rawPcmFile.length() <= 0L) {
            return ReceiveProgress(null, 0, 0, emptyList(), 0, emptyList(), 0, false)
        }
        val pcm = parseRawPcm16(rawPcmFile.readBytes())
        val bits = demodulateBestOffset(pcm)
        val (parsed, frameErrors) = parseFramesFromBits(bits)
        if (parsed.isEmpty()) return ReceiveProgress(null, 0, 0, emptyList(), 0, emptyList(), frameErrors, false)

        val allFrames = parsed.map { it.frame }
        val sessionId = when {
            preferredSessionId != null && allFrames.any { it.sessionId == preferredSessionId } -> preferredSessionId
            else -> parsed.groupingBy { it.frame.sessionId }.eachCount().maxByOrNull { it.value }?.key
        }
        if (sessionId == null) return ReceiveProgress(null, 0, 0, emptyList(), 0, emptyList(), frameErrors, false)
        val frames = allFrames.filter { it.sessionId == sessionId }
        val totalData = frames.maxOfOrNull { it.totalDataFrames } ?: 0
        if (totalData <= 0) {
            return ReceiveProgress(sessionId, 0, 0, emptyList(), 0, emptyList(), frameErrors, false)
        }

        val dataByIndex = HashSet<Int>()
        val parityByGroup = HashSet<Int>()
        for (f in frames) {
            when (f.type) {
                TYPE_DATA -> dataByIndex += f.index
                TYPE_PARITY -> parityByGroup += f.group
            }
        }

        var recoverable = 0
        val groupCount = ceil(totalData / GROUP_DATA_COUNT.toDouble()).toInt()
        for (g in 0 until groupCount) {
            if (!parityByGroup.contains(g)) continue
            val base = g * GROUP_DATA_COUNT
            var missing = 0
            var present = 0
            for (slot in 0 until GROUP_DATA_COUNT) {
                val idx = base + slot
                if (idx >= totalData) break
                if (dataByIndex.contains(idx)) present++ else missing++
            }
            if (missing == 1 && present >= 1) recoverable++
        }

        val missingIndices = ArrayList<Int>()
        for (idx in 0 until totalData) {
            if (!dataByIndex.contains(idx)) {
                val g = idx / GROUP_DATA_COUNT
                val base = g * GROUP_DATA_COUNT
                var missing = 0
                for (slot in 0 until GROUP_DATA_COUNT) {
                    val j = base + slot
                    if (j >= totalData) break
                    if (!dataByIndex.contains(j)) missing++
                }
                val recoverableByParity = parityByGroup.contains(g) && missing == 1
                if (!recoverableByParity) missingIndices += idx
            }
        }

        return ReceiveProgress(
            sessionId = sessionId,
            totalDataFrames = totalData,
            receivedDataFrames = dataByIndex.size.coerceAtMost(totalData),
            receivedDataIndices = dataByIndex.filter { it in 0 until totalData }.toList().sorted(),
            recoverableFrames = recoverable,
            missingDataIndices = missingIndices,
            frameErrors = frameErrors,
            hasAllData = (dataByIndex.size + recoverable) >= totalData
        )
    }

    fun buildRetransmitRequestCode(progress: ReceiveProgress): String? {
        val sid = progress.sessionId ?: return null
        if (progress.totalDataFrames <= 0) return null
        if (progress.missingDataIndices.isEmpty()) return null
        val missing = progress.missingDataIndices.distinct().sorted()
        return "R2:$sid:${progress.totalDataFrames}:${missing.joinToString(",")}"
    }

    fun parseRetransmitRequestCode(code: String): RetransmitRequest? {
        val text = code.trim()
        if (!text.startsWith("R2:")) return null
        val parts = text.split(":")
        if (parts.size != 4) return null
        val sid = parts[1].toIntOrNull() ?: return null
        val total = parts[2].toIntOrNull() ?: return null
        if (sid <= 0 || total <= 0) return null
        val miss = if (parts[3].isBlank()) emptyList() else {
            parts[3].split(",").mapNotNull { it.toIntOrNull() }.filter { it in 0 until total }.distinct().sorted()
        }
        if (miss.isEmpty()) return null
        return RetransmitRequest(sid, total, miss)
    }

    fun decodeWaveFileToFile(context: Context, wavFile: File): DecodedFile {
        val wavUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", wavFile)
        return decodeWaveUriToFile(context, wavUri)
    }

    fun wrapRawPcm16ToWav(rawPcmFile: File, wavFile: File) {
        val data = rawPcmFile.readBytes()
        if (data.isEmpty()) throw IllegalArgumentException("Recorded audio is empty")
        val sampleCount = data.size / 2
        val samples = ShortArray(sampleCount)
        var p = 0
        for (i in 0 until sampleCount) {
            val lo = data[p].toInt() and 0xFF
            val hi = data[p + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort()
            p += 2
        }
        writeWavMono16(wavFile, SAMPLE_RATE, samples)
    }

    private fun parseRawPcm16(data: ByteArray): ShortArray {
        val sampleCount = data.size / 2
        val out = ShortArray(sampleCount)
        var p = 0
        for (i in 0 until sampleCount) {
            val lo = data[p].toInt() and 0xFF
            val hi = data[p + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
            p += 2
        }
        return out
    }

    private fun buildFrames(
        sessionId: Int,
        fileBytes: ByteArray,
        selectedDataIndices: Set<Int>? = null,
        selectedParityGroups: Set<Int>? = null
    ): List<ProtocolFrame> {
        val totalData = ceil(fileBytes.size / PAYLOAD_BYTES.toDouble()).toInt()
        val dataFrames = ArrayList<ProtocolFrame>(totalData)
        for (i in 0 until totalData) {
            val start = i * PAYLOAD_BYTES
            val end = minOf(start + PAYLOAD_BYTES, fileBytes.size)
            val payload = ByteArray(PAYLOAD_BYTES)
            val len = end - start
            System.arraycopy(fileBytes, start, payload, 0, len)
            dataFrames += ProtocolFrame(
                type = TYPE_DATA,
                sessionId = sessionId,
                index = i,
                group = i / GROUP_DATA_COUNT,
                slot = i % GROUP_DATA_COUNT,
                payloadLen = len,
                totalDataFrames = totalData,
                fileSize = fileBytes.size,
                payload = payload
            )
        }

        val parityFrames = ArrayList<ProtocolFrame>()
        val groupCount = ceil(totalData / GROUP_DATA_COUNT.toDouble()).toInt()
        for (g in 0 until groupCount) {
            val parity = ByteArray(PAYLOAD_BYTES)
            for (slot in 0 until GROUP_DATA_COUNT) {
                val idx = g * GROUP_DATA_COUNT + slot
                if (idx >= totalData) break
                val p = dataFrames[idx].payload
                for (k in 0 until PAYLOAD_BYTES) parity[k] = (parity[k].toInt() xor p[k].toInt()).toByte()
            }
            parityFrames += ProtocolFrame(
                type = TYPE_PARITY,
                sessionId = sessionId,
                index = totalData + g,
                group = g,
                slot = GROUP_DATA_COUNT,
                payloadLen = PAYLOAD_BYTES,
                totalDataFrames = totalData,
                fileSize = fileBytes.size,
                payload = parity
            )
        }

        val out = ArrayList<ProtocolFrame>(dataFrames.size + parityFrames.size)
        if (selectedDataIndices == null) {
            out += dataFrames
        } else {
            selectedDataIndices.sorted().forEach { idx ->
                if (idx in 0 until dataFrames.size) out += dataFrames[idx]
            }
        }
        if (selectedParityGroups == null) {
            out += parityFrames
        } else {
            selectedParityGroups.sorted().forEach { g ->
                if (g in 0 until parityFrames.size) out += parityFrames[g]
            }
        }
        return out
    }

    private fun buildTxBits(frames: List<ProtocolFrame>, isRetransmit: Boolean, retransmitRepeat: Int): Pair<IntArray, List<TxUnit>> {
        val out = ArrayList<Int>(frames.size * (SYNC_BITS.size + FRAME_BITS) * 4 + 512)
        val units = ArrayList<TxUnit>(frames.size * 4)
        repeat(64) { out += 1 }
        repeat(64) { out += 0 }
        repeat(64) { out += if ((it and 1) == 0) 1 else 0 }

        val dataFrames = frames.filter { it.type == TYPE_DATA }
        val parityFrames = frames.filter { it.type == TYPE_PARITY }
        val totalData = dataFrames.size
        val dataPasses = when {
            isRetransmit -> retransmitRepeat.coerceIn(2, 12)
            totalData <= 16 -> BASE_FRAME_REPEAT
            totalData <= 48 -> 2
            else -> 3
        }
        val parityPasses = when {
            isRetransmit -> (retransmitRepeat - 1).coerceIn(2, 10)
            totalData <= 16 -> 1
            totalData <= 48 -> 2
            else -> 3
        }

        repeat(dataPasses) { pass ->
            appendFramePass(out, units, dataFrames, pass)
        }
        repeat(parityPasses) { pass ->
            appendFramePass(out, units, parityFrames, pass)
        }

        repeat(96) { out += 0 } // trailing guard silence
        return out.toIntArray() to units
    }

    private fun appendFramePass(out: ArrayList<Int>, units: ArrayList<TxUnit>, frames: List<ProtocolFrame>, pass: Int) {
        if (frames.isEmpty()) return
        val size = frames.size
        val start = (pass * 3) % size
        for (i in 0 until size) {
            val idx = (start + i) % size
            val raw = serializeFrame(frames[idx])
            val coded = encodeBytesHamming(raw)
            SYNC_BITS.forEach { out += it }
            coded.forEach { out += it }
            repeat(16) { out += 0 }
            units += TxUnit(type = frames[idx].type, index = frames[idx].index, pass = pass)
        }
    }

    private fun serializeFrame(frame: ProtocolFrame): ByteArray {
        val out = ByteArray(FRAME_BYTES)
        out[0] = ((MAGIC ushr 8) and 0xFF).toByte()
        out[1] = (MAGIC and 0xFF).toByte()
        out[2] = VERSION.toByte()
        out[3] = frame.type.toByte()
        writeIntBE(out, 4, frame.sessionId)
        writeU16BE(out, 8, frame.index)
        writeU16BE(out, 10, frame.group)
        out[12] = frame.slot.toByte()
        out[13] = frame.payloadLen.toByte()
        writeU16BE(out, 14, frame.totalDataFrames)
        writeIntBE(out, 16, frame.fileSize)
        out[20] = 0
        System.arraycopy(frame.payload, 0, out, HEADER_BYTES, PAYLOAD_BYTES)
        val crc = CRC32().apply { update(out, 0, FRAME_BYTES - CRC_BYTES) }.value.toInt()
        writeIntBE(out, FRAME_BYTES - CRC_BYTES, crc)
        return out
    }

    private fun parseFrame(raw: ByteArray): ProtocolFrame? {
        if (raw.size != FRAME_BYTES) return null
        val magic = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
        if (magic != MAGIC) return null
        if ((raw[2].toInt() and 0xFF) != VERSION) return null
        val crcExpected = readIntBE(raw, FRAME_BYTES - CRC_BYTES)
        val crcActual = CRC32().apply { update(raw, 0, FRAME_BYTES - CRC_BYTES) }.value.toInt()
        if (crcExpected != crcActual) return null
        val payloadLen = raw[13].toInt() and 0xFF
        if (payloadLen > PAYLOAD_BYTES) return null
        val payload = ByteArray(PAYLOAD_BYTES)
        System.arraycopy(raw, HEADER_BYTES, payload, 0, PAYLOAD_BYTES)
        return ProtocolFrame(
            type = raw[3].toInt() and 0xFF,
            sessionId = readIntBE(raw, 4),
            index = readU16BE(raw, 8),
            group = readU16BE(raw, 10),
            slot = raw[12].toInt() and 0xFF,
            payloadLen = payloadLen,
            totalDataFrames = readU16BE(raw, 14),
            fileSize = readIntBE(raw, 16),
            payload = payload
        )
    }

    private fun modulateBits(bits: IntArray): ShortArray {
        val out = ShortArray(bits.size * BIT_SAMPLES)
        var write = 0
        var phase = 0.0
        for (bit in bits) {
            val f = if (bit == 1) FREQ1 else FREQ0
            val step = 2.0 * PI * f / SAMPLE_RATE
            for (i in 0 until BIT_SAMPLES) {
                val env = edgeEnvelope(i, BIT_SAMPLES)
                out[write++] = (sin(phase) * Short.MAX_VALUE * 0.8 * env).roundToInt().toShort()
                phase += step
            }
        }
        return out
    }

    private fun edgeEnvelope(i: Int, total: Int): Double {
        if (EDGE_RAMP_SAMPLES <= 0 || total <= EDGE_RAMP_SAMPLES * 2) return 1.0
        return when {
            i < EDGE_RAMP_SAMPLES -> 0.5 - 0.5 * cos(Math.PI * i / EDGE_RAMP_SAMPLES)
            i >= total - EDGE_RAMP_SAMPLES -> {
                val x = (total - 1 - i).coerceAtLeast(0)
                0.5 - 0.5 * cos(Math.PI * x / EDGE_RAMP_SAMPLES)
            }
            else -> 1.0
        }.coerceIn(0.0, 1.0)
    }

    private fun demodulateBestOffset(pcm: ShortArray): IntArray {
        val step = maxOf(1, BIT_SAMPLES / 12)
        var bestBits = IntArray(0)
        var bestScore = Int.MIN_VALUE
        for (offset in 0 until BIT_SAMPLES step step) {
            val bits = demodulateBitsAtOffset(pcm, offset)
            val parsed = parseFramesFromBits(bits)
            val validCount = parsed.first.size
            val frameErrors = parsed.second
            val score = validCount * 100 - frameErrors * 5 + countSyncHits(bits)
            if (score > bestScore) {
                bestScore = score
                bestBits = bits
            }
        }
        return bestBits
    }

    private fun demodulateBitsAtOffset(pcm: ShortArray, offset: Int): IntArray {
        val count = ((pcm.size - offset) / BIT_SAMPLES).coerceAtLeast(0)
        val bits = IntArray(count)
        var pos = offset
        for (i in 0 until count) {
            val e0 = goertzelEnergy(pcm, pos, BIT_SAMPLES, FREQ0)
            val e1 = goertzelEnergy(pcm, pos, BIT_SAMPLES, FREQ1)
            bits[i] = if (e1 > e0) 1 else 0
            pos += BIT_SAMPLES
        }
        return bits
    }

    private fun goertzelEnergy(data: ShortArray, start: Int, len: Int, freq: Double): Double {
        val k = (0.5 + len * freq / SAMPLE_RATE).toInt()
        val omega = 2.0 * PI * k / len
        val coeff = 2.0 * cos(omega)
        var mean = 0.0
        for (i in 0 until len) {
            mean += data[start + i].toDouble()
        }
        mean /= len.toDouble()
        var q0 = 0.0
        var q1 = 0.0
        var q2 = 0.0
        for (i in 0 until len) {
            q0 = coeff * q1 - q2 + (data[start + i].toDouble() - mean)
            q2 = q1
            q1 = q0
        }
        return q1 * q1 + q2 * q2 - coeff * q1 * q2
    }

    private fun countSyncHits(bits: IntArray): Int {
        var hits = 0
        var i = 0
        while (i <= bits.size - SYNC_BITS.size) {
            if (matchSync(bits, i)) {
                hits++
                i += SYNC_BITS.size
            } else {
                i++
            }
        }
        return hits
    }

    private fun parseFramesFromBits(bits: IntArray): Pair<List<ParsedFrame>, Int> {
        val parsed = ArrayList<ParsedFrame>()
        var errors = 0
        var locked = false
        var lockedSession: Int? = null
        var i = 0
        while (i <= bits.size - SYNC_BITS.size - FRAME_BITS) {
            if (!matchSync(bits, i)) {
                i++
                continue
            }
            val baseStart = i + SYNC_BITS.size
            val frame = tryDecodeAroundSync(bits, baseStart, lockedSession)
            if (frame == null) {
                if (locked) errors++
                i++
                continue
            }
            if (lockedSession != null && frame.sessionId != lockedSession) {
                i++
                continue
            }
            locked = true
            if (lockedSession == null) lockedSession = frame.sessionId
            parsed += ParsedFrame(frame)
            i = baseStart + FRAME_BITS
        }
        if (parsed.isEmpty()) return parsed to errors
        val mainSession = parsed.groupingBy { it.frame.sessionId }.eachCount().maxByOrNull { it.value }?.key
        if (mainSession == null) return parsed to errors
        val filtered = parsed.filter { it.frame.sessionId == mainSession }
        return filtered to errors
    }

    private fun tryDecodeAroundSync(bits: IntArray, baseStart: Int, lockedSession: Int?): ProtocolFrame? {
        val candidates = intArrayOf(0, -1, 1, -2, 2)
        for (delta in candidates) {
            val start = baseStart + delta
            if (start < 0 || start + FRAME_BITS > bits.size) continue
            val coded = bits.copyOfRange(start, start + FRAME_BITS)
            val dec = decodeBytesHamming(coded) ?: continue
            val frame = parseFrame(dec.first) ?: continue
            if (lockedSession != null && frame.sessionId != lockedSession) continue
            return frame
        }
        return null
    }

    private fun reassemble(parsed: List<ParsedFrame>, frameErrors: Int): Triple<String, ByteArray, Int> {
        val session = parsed.groupingBy { it.frame.sessionId }.eachCount().maxByOrNull { it.value }?.key
            ?: throw DecodeFailure("Session detection failed", frameErrors)
        val frames = parsed.map { it.frame }.filter { it.sessionId == session }
        if (frames.isEmpty()) throw DecodeFailure("No frame for selected session", frameErrors)
        val totalData = frames.maxOfOrNull { it.totalDataFrames } ?: 0
        if (totalData <= 0) throw DecodeFailure("Invalid total data frame count", frameErrors)
        val sizeCount = HashMap<Int, Int>()
        for (f in frames) {
            if (f.fileSize > 0) {
                sizeCount[f.fileSize] = (sizeCount[f.fileSize] ?: 0) + 1
            }
        }
        val fileSize = sizeCount.maxByOrNull { it.value }?.key
            ?: frames.maxOfOrNull { it.fileSize }?.takeIf { it > 0 }
            ?: throw DecodeFailure("Missing file size metadata", frameErrors)

        val dataByIndex = HashMap<Int, ProtocolFrame>()
        val parityByGroup = HashMap<Int, ProtocolFrame>()
        for (f in frames) {
            when (f.type) {
                TYPE_DATA -> dataByIndex.putIfAbsent(f.index, f)
                TYPE_PARITY -> parityByGroup.putIfAbsent(f.group, f)
            }
        }

        var correctedGroups = 0
        val groupCount = ceil(totalData / GROUP_DATA_COUNT.toDouble()).toInt()
        for (g in 0 until groupCount) {
            val base = g * GROUP_DATA_COUNT
            val existing = ArrayList<Int>()
            val missing = ArrayList<Int>()
            for (slot in 0 until GROUP_DATA_COUNT) {
                val idx = base + slot
                if (idx >= totalData) break
                if (dataByIndex.containsKey(idx)) existing += idx else missing += idx
            }
            if (missing.size == 1) {
                val parity = parityByGroup[g] ?: continue
                val recovered = parity.payload.copyOf()
                for (idx in existing) {
                    val p = dataByIndex[idx]!!.payload
                    for (k in 0 until PAYLOAD_BYTES) recovered[k] = (recovered[k].toInt() xor p[k].toInt()).toByte()
                }
                val missIdx = missing[0]
                val payloadLen = if (missIdx == totalData - 1) {
                    val full = (totalData - 1) * PAYLOAD_BYTES
                    (fileSize - full).coerceIn(1, PAYLOAD_BYTES)
                } else PAYLOAD_BYTES
                dataByIndex[missIdx] = ProtocolFrame(
                    type = TYPE_DATA,
                    sessionId = session,
                    index = missIdx,
                    group = g,
                    slot = missIdx % GROUP_DATA_COUNT,
                    payloadLen = payloadLen,
                    totalDataFrames = totalData,
                    fileSize = fileSize,
                    payload = recovered
                )
                correctedGroups++
            }
        }

        for (i in 0 until totalData) {
            if (!dataByIndex.containsKey(i)) {
                throw DecodeFailure("Missing data frame $i after FEC", frameErrors)
            }
        }

        val fileName = "acoustic_recovered_${session and 0xFFFF}.bin"

        val out = ByteArrayOutputStream(totalData * PAYLOAD_BYTES)
        for (i in 0 until totalData) {
            val f = dataByIndex[i]!!
            out.write(f.payload, 0, f.payloadLen)
        }
        val merged = out.toByteArray()
        if (merged.size < fileSize) throw DecodeFailure("File size mismatch", frameErrors)
        val data = merged.copyOf(fileSize)
        return Triple(fileName, data, correctedGroups)
    }

    private fun encodeBytesHamming(bytes: ByteArray): IntArray {
        val out = IntArray(bytes.size * 12)
        var p = 0
        for (b in bytes) {
            val code = hammingEncodeByte(b.toInt() and 0xFF)
            for (i in 0 until 12) out[p++] = (code ushr i) and 1
        }
        return out
    }

    private fun decodeBytesHamming(bits: IntArray): Pair<ByteArray, Int>? {
        if (bits.size % 12 != 0) return null
        val out = ByteArray(bits.size / 12)
        var p = 0
        var corrected = 0
        for (i in out.indices) {
            var code = 0
            for (b in 0 until 12) code = code or ((bits[p++] and 1) shl b)
            val dec = hammingDecode12(code) ?: return null
            out[i] = dec.first.toByte()
            corrected += dec.second
        }
        return out to corrected
    }

    private fun hammingEncodeByte(value: Int): Int {
        val c = IntArray(13)
        val d = IntArray(9)
        for (i in 0 until 8) d[i + 1] = (value ushr i) and 1
        c[3] = d[1]
        c[5] = d[2]
        c[6] = d[3]
        c[7] = d[4]
        c[9] = d[5]
        c[10] = d[6]
        c[11] = d[7]
        c[12] = d[8]
        c[1] = c[3] xor c[5] xor c[7] xor c[9] xor c[11]
        c[2] = c[3] xor c[6] xor c[7] xor c[10] xor c[11]
        c[4] = c[5] xor c[6] xor c[7] xor c[12]
        c[8] = c[9] xor c[10] xor c[11] xor c[12]
        var out = 0
        for (i in 1..12) out = out or ((c[i] and 1) shl (i - 1))
        return out
    }

    private fun hammingDecode12(input: Int): Pair<Int, Int>? {
        val b = IntArray(13)
        for (i in 1..12) b[i] = (input ushr (i - 1)) and 1
        val s1 = b[1] xor b[3] xor b[5] xor b[7] xor b[9] xor b[11]
        val s2 = b[2] xor b[3] xor b[6] xor b[7] xor b[10] xor b[11]
        val s4 = b[4] xor b[5] xor b[6] xor b[7] xor b[12]
        val s8 = b[8] xor b[9] xor b[10] xor b[11] xor b[12]
        val syndrome = s1 or (s2 shl 1) or (s4 shl 2) or (s8 shl 3)
        var corrected = 0
        if (syndrome in 1..12) {
            b[syndrome] = b[syndrome] xor 1
            corrected = 1
        } else if (syndrome != 0) return null
        val out =
            (b[3] shl 0) or
            (b[5] shl 1) or
            (b[6] shl 2) or
            (b[7] shl 3) or
            (b[9] shl 4) or
            (b[10] shl 5) or
            (b[11] shl 6) or
            (b[12] shl 7)
        return out to corrected
    }

    private fun matchSync(bits: IntArray, index: Int): Boolean {
        if (index + SYNC_BITS.size > bits.size) return false
        var diff = 0
        for (i in SYNC_BITS.indices) {
            if (bits[index + i] != SYNC_BITS[i]) diff++
            if (diff > 2) return false
        }
        return true
    }

    private fun parseWavPcm16(wav: ByteArray): ShortArray? {
        if (wav.size < 44) return null
        if (!wav.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())) return null
        if (!wav.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())) return null

        var pos = 12
        var fmtFound = false
        var dataPos = -1
        var dataSize = 0
        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val size = readIntLE(wav, pos + 4)
            val body = pos + 8
            if (body + size > wav.size) break
            when (id) {
                "fmt " -> if (size >= 16) {
                    audioFormat = readU16LE(wav, body)
                    channels = readU16LE(wav, body + 2)
                    sampleRate = readIntLE(wav, body + 4)
                    bitsPerSample = readU16LE(wav, body + 14)
                    fmtFound = true
                }
                "data" -> {
                    dataPos = body
                    dataSize = size
                }
            }
            pos = body + size + (size and 1)
        }
        if (!fmtFound || dataPos < 0) return null
        if (audioFormat != 1 || channels != 1 || bitsPerSample != 16 || sampleRate != SAMPLE_RATE) return null
        if (dataPos + dataSize > wav.size) return null
        val samples = ShortArray(dataSize / 2)
        var p = dataPos
        for (i in samples.indices) {
            val lo = wav[p].toInt() and 0xFF
            val hi = wav[p + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort()
            p += 2
        }
        return samples
    }

    private fun writeWavMono16(file: File, sampleRate: Int, samples: ShortArray) {
        val dataBytes = samples.size * 2
        val byteRate = sampleRate * 2
        val chunkSize = 36 + dataBytes
        FileOutputStream(file).use { out ->
            out.write("RIFF".toByteArray())
            out.write(intToLe(chunkSize))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(intToLe(16))
            out.write(shortToLe(1))
            out.write(shortToLe(1))
            out.write(intToLe(sampleRate))
            out.write(intToLe(byteRate))
            out.write(shortToLe(2))
            out.write(shortToLe(16))
            out.write("data".toByteArray())
            out.write(intToLe(dataBytes))
            val buf = ByteArray(dataBytes)
            var p = 0
            for (s in samples) {
                val v = s.toInt()
                buf[p++] = (v and 0xFF).toByte()
                buf[p++] = ((v ushr 8) and 0xFF).toByte()
            }
            out.write(buf)
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return if (cleaned.isBlank()) "acoustic_recovered.bin" else cleaned
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var result = "acoustic.bin"
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) result = cursor.getString(idx)
                }
            }
        }
        return result
    }

    private fun readLimitedBytes(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 4096))
        val buf = ByteArray(4096)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > limit) {
                throw IllegalArgumentException("Acoustic mode only supports files <= ${MAX_FILE_BYTES / 1024}KB")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun writeU16BE(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 1] = (v and 0xFF).toByte()
    }

    private fun readU16BE(buf: ByteArray, off: Int): Int {
        return ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
    }

    private fun writeIntBE(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v ushr 24) and 0xFF).toByte()
        buf[off + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
    }

    private fun readIntBE(buf: ByteArray, off: Int): Int {
        return ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)
    }

    private fun readIntLE(buf: ByteArray, off: Int): Int {
        return (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun readU16LE(buf: ByteArray, off: Int): Int {
        return (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun intToLe(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )

    private fun shortToLe(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte()
    )
}
