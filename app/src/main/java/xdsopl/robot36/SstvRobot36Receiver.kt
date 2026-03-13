package xdsopl.robot36

import android.graphics.Bitmap

class SstvRobot36Receiver(
    sampleRate: Int = 44_100
) {
    data class ProcessResult(
        val newLines: Boolean,
        val modeName: String?,
        val modeDetected: Boolean
    )

    private val scopeBuffer = PixelBuffer(640, 2 * 1280)
    private val imageBuffer = PixelBuffer(800, 616)
    private val decoder = Decoder(scopeBuffer, imageBuffer, RAW_MODE_NAME, sampleRate)
    private var manualModeName: String? = null

    fun setMode(modeName: String?) {
        if (modeName.isNullOrBlank() || modeName == AUTO_MODE_NAME) {
            manualModeName = null
            decoder.setMode("AUTO")
            return
        }
        manualModeName = modeName
        decoder.setMode(modeName)
    }

    fun currentModeNameOrNull(): String? {
        if (!isModeDetected()) return null
        return decoder.currentMode.name
    }

    fun isModeDetected(): Boolean {
        return manualModeName != null || imageBuffer.line >= 0
    }

    fun processPcm16(data: ShortArray, length: Int): ProcessResult {
        val use = length.coerceIn(0, data.size)
        if (use <= 0) return ProcessResult(false, currentModeNameOrNull(), isModeDetected())
        val mono = FloatArray(use)
        for (i in 0 until use) {
            mono[i] = data[i] / 32768.0f
        }
        val newLines = decoder.process(mono, 0)
        return ProcessResult(newLines, currentModeNameOrNull(), isModeDetected())
    }

    fun consumeImageIfReady(): Bitmap? {
        if (imageBuffer.line < imageBuffer.height) return null
        val raw = Bitmap.createBitmap(
            imageBuffer.pixels,
            imageBuffer.width,
            imageBuffer.height,
            Bitmap.Config.ARGB_8888
        )
        imageBuffer.line = -1
        return decoder.currentMode.postProcessScopeImage(raw)
    }

    fun snapshotImageProgress(): Bitmap? {
        val line = imageBuffer.line
        if (line <= 0 || imageBuffer.width <= 0 || imageBuffer.height <= 0) return null
        val raw = Bitmap.createBitmap(
            imageBuffer.pixels,
            imageBuffer.width,
            imageBuffer.height,
            Bitmap.Config.ARGB_8888
        )
        return if (line >= imageBuffer.height) {
            decoder.currentMode.postProcessScopeImage(raw)
        } else {
            raw
        }
    }

    fun progressFraction(): Float {
        if (imageBuffer.height <= 0) return 0f
        return (imageBuffer.line.toFloat() / imageBuffer.height.toFloat()).coerceIn(0f, 1f)
    }

    fun snapshotScopeImage(): Bitmap? {
        val width = scopeBuffer.width
        val fullHeight = scopeBuffer.height
        val half = fullHeight / 2
        if (width <= 0 || half <= 0 || scopeBuffer.pixels.isEmpty()) return null
        val stride = width
        val line = scopeBuffer.line.coerceIn(0, half - 1)
        val offset = stride * line
        return runCatching {
            Bitmap.createBitmap(scopeBuffer.pixels, offset, stride, width, half, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    fun snapshotScopeImagePostProcessed(): Bitmap? {
        val scope = snapshotScopeImage() ?: return null
        return decoder.currentMode.postProcessScopeImage(scope)
    }

    companion object {
        const val AUTO_MODE_NAME = "自动"
        const val RAW_MODE_NAME = "Raw"

        val modeOptions: List<String> = listOf(
            AUTO_MODE_NAME,
            RAW_MODE_NAME,
            "HF Fax",
            "Robot 36 Color",
            "Robot 72 Color",
            "PD 50",
            "PD 90",
            "PD 120",
            "PD 160",
            "PD 180",
            "PD 240",
            "PD 290",
            "Martin 1",
            "Martin 2",
            "Scottie 1",
            "Scottie 2",
            "Scottie DX",
            "Wraase SC2-180"
        )
    }
}
