package com.yuliwen.filetran

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * SSTV encoder with a small set of robust analog modes implemented.
 */
object Robot36SstvEncoder {
    private const val SAMPLE_RATE = 44_100
    private const val BASE_AMPLITUDE = 0.72

    private const val FREQ_SYNC = 1200.0
    private const val FREQ_PORCH = 1500.0
    private const val FREQ_LEADER = 1900.0

    private const val LEADER_MS = 300.0
    private const val BREAK_MS = 10.0
    private const val VIS_BIT_MS = 30.0
    private const val FREQ_WHITE = 2300.0
    enum class SyncPlacement { LINE_START, LINE_END }
    enum class Family { RGB_GBR, RGB_GBR_SCOTTIE, RGB_RGB, ROBOT36, ROBOT72, PD, HFFAX }

    enum class Mode(
        val displayName: String,
        val width: Int,
        val height: Int,
        val visCode: Int,
        val family: Family,
        val lineSyncMs: Double,
        val sepMs: Double,
        val channelMs: Double,
        val syncPlacement: SyncPlacement
    ) {
        MARTIN_M1("Martin M1", 320, 256, 44, Family.RGB_GBR, 4.862, 0.572, 146.432, SyncPlacement.LINE_START),
        MARTIN_M2("Martin M2", 320, 256, 40, Family.RGB_GBR, 4.862, 0.572, 73.216, SyncPlacement.LINE_START),
        SCOTTIE_S1("Scottie S1", 320, 256, 60, Family.RGB_GBR_SCOTTIE, 9.0, 1.5, 138.240, SyncPlacement.LINE_END),
        SCOTTIE_S2("Scottie S2", 320, 256, 56, Family.RGB_GBR_SCOTTIE, 9.0, 1.5, 88.064, SyncPlacement.LINE_END),
        SCOTTIE_DX("Scottie DX", 320, 256, 76, Family.RGB_GBR_SCOTTIE, 9.0, 1.5, 345.6, SyncPlacement.LINE_END),
        WRAASE_SC2_180("WSC2-180", 320, 256, 55, Family.RGB_RGB, 5.5225, 0.5, 235.0, SyncPlacement.LINE_START),
        ROBOT_36("Robot 36", 320, 240, 8, Family.ROBOT36, 9.0, 3.0, 88.0, SyncPlacement.LINE_START),
        ROBOT_72("Robot 72", 320, 240, 12, Family.ROBOT72, 9.0, 3.0, 138.0, SyncPlacement.LINE_START),
        PD_50("PD50", 320, 256, 93, Family.PD, 20.0, 2.08, 91.52, SyncPlacement.LINE_START),
        PD_90("PD90", 320, 256, 99, Family.PD, 20.0, 2.08, 170.24, SyncPlacement.LINE_START),
        PD_120("PD120", 640, 496, 95, Family.PD, 20.0, 2.08, 121.6, SyncPlacement.LINE_START),
        PD_160("PD160", 512, 400, 98, Family.PD, 20.0, 2.08, 195.584, SyncPlacement.LINE_START),
        PD_180("PD180", 640, 496, 96, Family.PD, 20.0, 2.08, 183.04, SyncPlacement.LINE_START),
        PD_240("PD240", 640, 496, 97, Family.PD, 20.0, 2.08, 244.48, SyncPlacement.LINE_START),
        PD_590("PD590", 800, 616, 94, Family.PD, 20.0, 2.08, 228.8, SyncPlacement.LINE_START),
        HF_FAX("HF Fax", 640, 1200, -1, Family.HFFAX, 0.0, 0.0, 500.0, SyncPlacement.LINE_START)
    }

    enum class ScaleMode {
        CROP_CENTER,
        FIT_BLACK_BARS
    }

    enum class QualityPreset(
        val displayName: String,
        val smoothingRadius: Int,
        val amplitudeScale: Double,
        val edgeRampMs: Double,
        val useLinearScan: Boolean,
        val useCumulativeTiming: Boolean
    ) {
        STRICT("严格原始", 0, 1.0, 0.0, false, false),
        CLARITY("清晰优先", 1, 0.95, 2.0, true, true),
        STANDARD("标准", 0, 1.0, 0.5, true, true)
    }

    fun encodeToWavFile(
        source: Bitmap,
        output: File,
        mode: Mode = Mode.MARTIN_M1,
        scaleMode: ScaleMode = ScaleMode.CROP_CENTER,
        qualityPreset: QualityPreset = QualityPreset.CLARITY
    ): File {
        val prepared = if (mode.family == Family.HFFAX) {
            prepareHfFaxBitmap(source, mode.width)
        } else {
            prepareBitmap(source, mode.width, mode.height, scaleMode)
        }
        val pcm = encodeToPcm16(prepared, mode, qualityPreset)
        if (prepared !== source) prepared.recycle()
        writeWavMono16(output, SAMPLE_RATE, pcm)
        return output
    }

    private fun prepareHfFaxBitmap(source: Bitmap, targetWidth: Int): Bitmap {
        val srcW = source.width.coerceAtLeast(1)
        val srcH = source.height.coerceAtLeast(1)
        val ratio = srcH.toDouble() / srcW.toDouble()
        val targetHeight = (targetWidth * ratio).roundToInt().coerceIn(64, 12000)
        if (source.width == targetWidth && source.height == targetHeight) return source
        val out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val dst = Rect(0, 0, targetWidth, targetHeight)
        canvas.drawBitmap(source, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun encodeToPcm16(source: Bitmap, mode: Mode, qualityPreset: QualityPreset): ShortArray {
        val writer = PcmWriter(
            amplitude = BASE_AMPLITUDE * qualityPreset.amplitudeScale,
            edgeRampMs = qualityPreset.edgeRampMs,
            useLinearScan = qualityPreset.useLinearScan,
            useCumulativeTiming = qualityPreset.useCumulativeTiming
        )
        if (mode.family != Family.HFFAX) {
            writeLeaderAndVis(writer, mode.visCode)
        }
        writeImageData(source, writer, mode, qualityPreset.smoothingRadius)
        return writer.toShortArray()
    }

    private fun prepareBitmap(source: Bitmap, width: Int, height: Int, scaleMode: ScaleMode): Bitmap {
        if (source.width == width && source.height == height && scaleMode == ScaleMode.FIT_BLACK_BARS) {
            return source
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()
        val dstW = width.toFloat()
        val dstH = height.toFloat()
        val srcAspect = srcW / srcH
        val dstAspect = dstW / dstH

        val dstRect = when (scaleMode) {
            ScaleMode.FIT_BLACK_BARS -> {
                if (srcAspect > dstAspect) {
                    val h = dstW / srcAspect
                    RectF(0f, (dstH - h) / 2f, dstW, (dstH + h) / 2f)
                } else {
                    val w = dstH * srcAspect
                    RectF((dstW - w) / 2f, 0f, (dstW + w) / 2f, dstH)
                }
            }
            ScaleMode.CROP_CENTER -> RectF(0f, 0f, dstW, dstH)
        }

        val srcRect = if (scaleMode == ScaleMode.CROP_CENTER && srcAspect != dstAspect) {
            if (srcAspect > dstAspect) {
                val cropW = srcH * dstAspect
                val left = (srcW - cropW) / 2f
                Rect(left.roundToInt(), 0, (left + cropW).roundToInt(), srcH.roundToInt())
            } else {
                val cropH = srcW / dstAspect
                val top = (srcH - cropH) / 2f
                Rect(0, top.roundToInt(), srcW.roundToInt(), (top + cropH).roundToInt())
            }
        } else {
            Rect(0, 0, srcW.roundToInt(), srcH.roundToInt())
        }

        canvas.drawBitmap(source, srcRect, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun writeLeaderAndVis(writer: PcmWriter, visCode: Int) {
        writer.writeTone(FREQ_LEADER, LEADER_MS)
        writer.writeTone(FREQ_SYNC, BREAK_MS)
        writer.writeTone(FREQ_LEADER, LEADER_MS)
        writer.writeVisCode(visCode)
    }

    private fun writeImageData(bitmap: Bitmap, writer: PcmWriter, mode: Mode, smoothingRadius: Int) {
        when (mode.family) {
            Family.RGB_GBR, Family.RGB_GBR_SCOTTIE, Family.RGB_RGB ->
                writeRgbFamily(bitmap, writer, mode, smoothingRadius)
            Family.ROBOT36 -> writeRobot36(bitmap, writer, mode, smoothingRadius)
            Family.ROBOT72 -> writeRobot72(bitmap, writer, mode, smoothingRadius)
            Family.PD -> writePd(bitmap, writer, mode, smoothingRadius)
            Family.HFFAX -> writeHfFax(bitmap, writer, mode, smoothingRadius)
        }
    }

    private fun writeRgbFamily(bitmap: Bitmap, writer: PcmWriter, mode: Mode, smoothingRadius: Int) {
        val row = IntArray(mode.width)
        val green = IntArray(mode.width)
        val blue = IntArray(mode.width)
        val red = IntArray(mode.width)

        for (y in 0 until mode.height) {
            bitmap.getPixels(row, 0, mode.width, 0, y, mode.width, 1)
            for (x in 0 until mode.width) {
                val pixel = row[x]
                red[x] = (pixel shr 16) and 0xFF
                green[x] = (pixel shr 8) and 0xFF
                blue[x] = pixel and 0xFF
            }
            if (smoothingRadius > 0) {
                smoothChannel(green, smoothingRadius)
                smoothChannel(blue, smoothingRadius)
                smoothChannel(red, smoothingRadius)
            }

            if (mode.syncPlacement == SyncPlacement.LINE_START) {
                writer.writeTone(FREQ_SYNC, mode.lineSyncMs)
                writer.writeTone(FREQ_PORCH, mode.sepMs)
            } else {
                writer.writeTone(FREQ_PORCH, mode.sepMs)
            }

            when (mode.family) {
                Family.RGB_GBR, Family.RGB_GBR_SCOTTIE -> {
                    writer.writeScan(green, mode.channelMs)
                    writer.writeTone(FREQ_PORCH, mode.sepMs)
                    writer.writeScan(blue, mode.channelMs)
                    writer.writeTone(FREQ_PORCH, mode.sepMs)
                    writer.writeScan(red, mode.channelMs)
                }
                Family.RGB_RGB -> {
                    writer.writeScan(red, mode.channelMs)
                    writer.writeScan(green, mode.channelMs)
                    writer.writeScan(blue, mode.channelMs)
                }
                else -> Unit
            }

            if (mode.syncPlacement == SyncPlacement.LINE_END) {
                writer.writeTone(FREQ_SYNC, mode.lineSyncMs)
            }
        }
    }

    private fun writeRobot36(bitmap: Bitmap, writer: PcmWriter, mode: Mode, smoothingRadius: Int) {
        val width = mode.width
        val row = IntArray(width)
        val yLine = IntArray(width)
        val cLine = IntArray(width)
        for (y in 0 until mode.height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val rgb = rgbToYuv(row[x])
                yLine[x] = rgb[0]
                cLine[x] = if ((y and 1) == 0) rgb[2] else rgb[1] // even: V, odd: U
            }
            if (smoothingRadius > 0) {
                smoothChannel(yLine, smoothingRadius)
                smoothChannel(cLine, smoothingRadius)
            }
            writer.writeTone(FREQ_SYNC, mode.lineSyncMs)
            writer.writeTone(FREQ_PORCH, mode.sepMs) // 3 ms
            writer.writeScan(yLine, mode.channelMs) // 88 ms
            writer.writeTone(if ((y and 1) == 0) FREQ_PORCH else FREQ_WHITE, 4.5) // separator parity
            writer.writeTone(FREQ_PORCH, 1.5)
            writer.writeScan(cLine, 44.0)
        }
    }

    private fun writeRobot72(bitmap: Bitmap, writer: PcmWriter, mode: Mode, smoothingRadius: Int) {
        val width = mode.width
        val row = IntArray(width)
        val yLine = IntArray(width)
        val uLine = IntArray(width)
        val vLine = IntArray(width)
        for (y in 0 until mode.height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val yuv = rgbToYuv(row[x])
                yLine[x] = yuv[0]
                uLine[x] = yuv[1]
                vLine[x] = yuv[2]
            }
            if (smoothingRadius > 0) {
                smoothChannel(yLine, smoothingRadius)
                smoothChannel(uLine, smoothingRadius)
                smoothChannel(vLine, smoothingRadius)
            }
            writer.writeTone(FREQ_SYNC, mode.lineSyncMs)
            writer.writeTone(FREQ_PORCH, mode.sepMs)
            writer.writeScan(yLine, mode.channelMs) // 138 ms
            writer.writeTone(FREQ_PORCH, 4.5)
            writer.writeTone(FREQ_PORCH, 1.5)
            writer.writeScan(vLine, 69.0)
            writer.writeTone(FREQ_PORCH, 4.5)
            writer.writeTone(FREQ_PORCH, 1.5)
            writer.writeScan(uLine, 69.0)
        }
    }

    private fun writePd(bitmap: Bitmap, writer: PcmWriter, mode: Mode, smoothingRadius: Int) {
        val width = mode.width
        val rowEven = IntArray(width)
        val rowOdd = IntArray(width)
        val yEven = IntArray(width)
        val yOdd = IntArray(width)
        val uAvg = IntArray(width)
        val vAvg = IntArray(width)
        var y = 0
        while (y < mode.height) {
            bitmap.getPixels(rowEven, 0, width, 0, y, width, 1)
            val nextY = (y + 1).coerceAtMost(mode.height - 1)
            bitmap.getPixels(rowOdd, 0, width, 0, nextY, width, 1)
            for (x in 0 until width) {
                val yuvEven = rgbToYuv(rowEven[x])
                val yuvOdd = rgbToYuv(rowOdd[x])
                yEven[x] = yuvEven[0]
                yOdd[x] = yuvOdd[0]
                uAvg[x] = ((yuvEven[1] + yuvOdd[1]) / 2).coerceIn(0, 255)
                vAvg[x] = ((yuvEven[2] + yuvOdd[2]) / 2).coerceIn(0, 255)
            }
            if (smoothingRadius > 0) {
                smoothChannel(yEven, smoothingRadius)
                smoothChannel(yOdd, smoothingRadius)
                smoothChannel(uAvg, smoothingRadius)
                smoothChannel(vAvg, smoothingRadius)
            }
            writer.writeTone(FREQ_SYNC, mode.lineSyncMs) // 20 ms
            writer.writeTone(FREQ_PORCH, mode.sepMs) // 2.08 ms
            writer.writeScan(yEven, mode.channelMs)
            writer.writeScan(vAvg, mode.channelMs)
            writer.writeScan(uAvg, mode.channelMs)
            writer.writeScan(yOdd, mode.channelMs)
            y += 2
        }
    }

    private fun writeHfFax(bitmap: Bitmap, writer: PcmWriter, mode: Mode, smoothingRadius: Int) {
        val width = bitmap.width
        val height = bitmap.height
        val row = IntArray(width)
        val gray = IntArray(width)
        val whiteMarginPx = (width * 0.08).roundToInt().coerceAtLeast(24)
        val blackGuardPx = (width * 0.015).roundToInt().coerceAtLeast(6)

        // Warm-up phasing lines: alternate white/black bars so decoder has stable level reference.
        repeat(10) { line ->
            for (x in 0 until width) {
                gray[x] = when {
                    x < whiteMarginPx -> 255
                    x < whiteMarginPx + blackGuardPx -> 0
                    ((x / 24) + line) % 2 == 0 -> 230
                    else -> 20
                }
            }
            writer.writeScan(gray, mode.channelMs)
        }

        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val p = row[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                gray[x] = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255)
            }
            if (smoothingRadius > 0) {
                smoothChannel(gray, smoothingRadius)
            }
            for (x in 0 until whiteMarginPx) gray[x] = 255
            for (x in whiteMarginPx until (whiteMarginPx + blackGuardPx).coerceAtMost(width)) gray[x] = 0
            writer.writeScan(gray, mode.channelMs) // 0.5s/line => 120 lines per minute
        }
    }

    private fun rgbToYuv(pixel: Int): IntArray {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val y = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255)
        val u = (128.0 + (b - y) / 2.032).roundToInt().coerceIn(0, 255)
        val v = (128.0 + (r - y) / 1.14).roundToInt().coerceIn(0, 255)
        return intArrayOf(y, u, v)
    }

    private fun smoothChannel(values: IntArray, radius: Int) {
        if (radius <= 0) return
        val src = values.copyOf()
        for (i in values.indices) {
            var sum = 0
            var count = 0
            val from = (i - radius).coerceAtLeast(0)
            val to = (i + radius).coerceAtMost(values.lastIndex)
            for (k in from..to) {
                sum += src[k]
                count++
            }
            values[i] = (sum / count).coerceIn(0, 255)
        }
    }

    private fun writeWavMono16(file: File, sampleRate: Int, samples: ShortArray) {
        val dataBytes = samples.size * 2
        val byteRate = sampleRate * 2
        val chunkSize = 36 + dataBytes

        FileOutputStream(file).use { out ->
            out.write(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
            out.write(intToLe(chunkSize))
            out.write(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))

            out.write(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
            out.write(intToLe(16))
            out.write(shortToLe(1))
            out.write(shortToLe(1))
            out.write(intToLe(sampleRate))
            out.write(intToLe(byteRate))
            out.write(shortToLe(2))
            out.write(shortToLe(16))

            out.write(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
            out.write(intToLe(dataBytes))

            val bytes = ByteArray(dataBytes)
            var i = 0
            var j = 0
            while (i < samples.size) {
                val sample = samples[i].toInt()
                bytes[j] = (sample and 0xFF).toByte()
                bytes[j + 1] = ((sample ushr 8) and 0xFF).toByte()
                i++
                j += 2
            }
            out.write(bytes)
        }
    }

    private fun intToLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte()
    )

    private fun shortToLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte()
    )

    private class PcmWriter(
        private val amplitude: Double,
        edgeRampMs: Double,
        private val useLinearScan: Boolean,
        private val useCumulativeTiming: Boolean
    ) {
        private var buffer = ShortArray(1_000_000)
        private var size = 0
        private var phase = 0.0
        private var emittedSamples = 0
        private var targetSamples = 0.0
        private val edgeRampSamples = ((edgeRampMs / 1000.0) * SAMPLE_RATE).roundToInt().coerceAtLeast(0)

        private fun append(sample: Short) {
            if (size >= buffer.size) {
                buffer = buffer.copyOf(buffer.size * 2)
            }
            buffer[size++] = sample
        }

        fun writeTone(freqHz: Double, durationMs: Double) {
            val count = samplesForDuration(durationMs)
            val phaseStep = 2.0 * PI * freqHz / SAMPLE_RATE
            repeat(count) { idx ->
                val gain = envelopeGain(idx, count)
                append((sin(phase) * Short.MAX_VALUE * amplitude * gain).roundToInt().toShort())
                phase += phaseStep
            }
        }

        fun writeScan(values: IntArray, durationMs: Double) {
            val totalSamples = samplesForDuration(durationMs)
            val width = values.size
            for (i in 0 until totalSamples) {
                val level = if (useLinearScan) {
                    val pos = if (totalSamples <= 1) 0.0 else i.toDouble() * (width - 1).toDouble() / (totalSamples - 1).toDouble()
                    val x0 = floor(pos).toInt().coerceIn(0, width - 1)
                    val x1 = (x0 + 1).coerceAtMost(width - 1)
                    val frac = pos - x0
                    ((1.0 - frac) * values[x0] + frac * values[x1]).coerceIn(0.0, 255.0)
                } else {
                    val x = floor(i.toDouble() * width / totalSamples).toInt().coerceIn(0, width - 1)
                    values[x].toDouble()
                }
                val freq = 1500.0 + (level / 255.0) * 800.0
                val phaseStep = 2.0 * PI * freq / SAMPLE_RATE
                val gain = envelopeGain(i, totalSamples)
                append((sin(phase) * Short.MAX_VALUE * amplitude * gain).roundToInt().toShort())
                phase += phaseStep
            }
        }

        fun writeVisCode(visCode: Int) {
            writeTone(1200.0, VIS_BIT_MS) // start
            var ones = 0
            for (bit in 0 until 7) {
                val bitValue = (visCode shr bit) and 0x1
                if (bitValue == 1) ones++
                writeTone(if (bitValue == 1) 1100.0 else 1300.0, VIS_BIT_MS)
            }
            val parityBit = ones % 2
            writeTone(if (parityBit == 1) 1100.0 else 1300.0, VIS_BIT_MS)
            writeTone(1200.0, VIS_BIT_MS) // stop
        }

        fun toShortArray(): ShortArray {
            return buffer.copyOf(size)
        }

        private fun allocateSamples(ms: Double): Int {
            targetSamples += (ms / 1000.0) * SAMPLE_RATE
            val roundedTarget = targetSamples.roundToInt()
            val count = (roundedTarget - emittedSamples).coerceAtLeast(1)
            emittedSamples += count
            return count
        }

        private fun perSegmentSamples(ms: Double): Int {
            return ((ms / 1000.0) * SAMPLE_RATE).roundToInt().coerceAtLeast(1)
        }

        private fun samplesForDuration(ms: Double): Int {
            return if (useCumulativeTiming) allocateSamples(ms) else perSegmentSamples(ms)
        }

        private fun envelopeGain(index: Int, total: Int): Double {
            if (edgeRampSamples <= 0 || total <= 2 * edgeRampSamples) return 1.0
            return when {
                index < edgeRampSamples -> index.toDouble() / edgeRampSamples.toDouble()
                index >= total - edgeRampSamples -> (total - 1 - index).toDouble() / edgeRampSamples.toDouble()
                else -> 1.0
            }.coerceIn(0.0, 1.0)
        }
    }
}
