package com.yuliwen.filetran.airgap

class AirGapSender {
    private var initialized = false
    private var initError: String? = null

    init {
        runCatching {
            System.loadLibrary("airgap_decoder")
            initialized = nativeSenderInit()
            if (!initialized) {
                initError = "nativeSenderInit returned false"
            }
        }.onFailure {
            initialized = false
            initError = "${it::class.java.simpleName}: ${it.message}"
        }
    }

    fun isReady(): Boolean = initialized
    fun getInitError(): String? = initError

    fun prepare(fileName: String, data: ByteArray, mode: Int = 68, compression: Int = 3): Boolean {
        if (!initialized) return false
        return nativePrepare(fileName, data, mode, compression)
    }

    // Returns int array: [width, height, argb...,]
    fun nextFrame(): IntArray? {
        if (!initialized) return null
        return nativeNextFrame().takeIf { it.size > 2 }
    }

    fun shutdown() {
        if (initialized) {
            nativeSenderShutdown()
            initialized = false
        }
    }

    private external fun nativeSenderInit(): Boolean
    private external fun nativePrepare(fileName: String, data: ByteArray, mode: Int, compression: Int): Boolean
    private external fun nativeNextFrame(): IntArray
    private external fun nativeSenderShutdown()
}
