package com.yuliwen.filetran.airgap

class AirGapDecoder(
    outputDir: String
) {
    private var initialized = false
    private var initError: String? = null

    init {
        runCatching {
            System.loadLibrary("airgap_decoder")
            initialized = nativeInit(outputDir)
            if (!initialized) {
                initError = "nativeInit returned false (dir=$outputDir)"
            }
        }.onFailure {
            initialized = false
            initError = "${it::class.java.simpleName}: ${it.message}"
        }
    }

    fun isReady(): Boolean = initialized
    fun getInitError(): String? = initError

    fun processImageGray(data: ByteArray, width: Int, height: Int, mode: Int): String? {
        if (!initialized) return null
        return processImageJNI(data, width, height, 1, mode).takeIf { it.isNotBlank() }
    }

    fun processImageRgba(data: ByteArray, width: Int, height: Int, mode: Int): String? {
        if (!initialized) return null
        return processImageJNI(data, width, height, 4, mode).takeIf { it.isNotBlank() }
    }

    fun shutdown() {
        if (initialized) {
            shutdownJNI()
            initialized = false
        }
    }

    private external fun nativeInit(outputDir: String): Boolean
    private external fun processImageJNI(data: ByteArray, width: Int, height: Int, channels: Int, mode: Int): String
    private external fun shutdownJNI()
}
