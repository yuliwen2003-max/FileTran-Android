package com.yuliwen.filetran

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HceFileTransferService : HostApduService() {
    companion object {
        private const val TAG = "FT-HCE-SERVICE"
    }

    private var selected = false

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val apdu = commandApdu ?: return HceApduProtocol.badCommand()
        Log.d(TAG, "processCommandApdu len=${apdu.size} selected=$selected")
        if (HceApduProtocol.isSelectAid(apdu)) {
            selected = true
            Log.i(TAG, "SELECT AID matched, service selected")
            return HceApduProtocol.success(byteArrayOf(0x46, 0x54, 0x31)) // "FT1"
        }
        if (!selected) {
            Log.w(TAG, "APDU received before SELECT")
            return HceApduProtocol.badState()
        }
        if (apdu.size < 5) return HceApduProtocol.badCommand()
        val ins = apdu[1]
        return try {
            when (ins) {
                0x10.toByte() -> {
                    val session = HceTransferStore.getCurrent() ?: return HceApduProtocol.noSession()
                    Log.d(TAG, "INFO requested sessionId=${session.sessionId} chunks=${session.chunkCount}")
                    HceApduProtocol.success(HceApduProtocol.encodeInfoPayload(session))
                }
                0x11.toByte() -> {
                    val lc = apdu[4].toInt() and 0xFF
                    if (lc != 4 || apdu.size < 9) return HceApduProtocol.wrongParam()
                    val index = ByteBuffer.wrap(apdu, 5, 4).order(ByteOrder.BIG_ENDIAN).int
                    val slice = HceTransferStore.readChunk(index)
                    val session = slice.session
                    if (index == 0 || index == session.chunkCount - 1 || index % 100 == 0) {
                        Log.d(TAG, "CHUNK requested idx=$index/${session.chunkCount - 1} bytes=${slice.length}")
                    }
                    HceApduProtocol.success(
                        HceApduProtocol.encodeChunkPayload(
                            sessionId = session.sessionId,
                            index = index,
                            chunkCount = session.chunkCount,
                            source = session.payload,
                            offset = slice.offset,
                            length = slice.length
                        )
                    )
                }
                else -> HceApduProtocol.badCommand()
            }
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "APDU illegal argument")
            HceApduProtocol.wrongParam()
        } catch (_: IllegalStateException) {
            Log.w(TAG, "APDU no active session")
            HceApduProtocol.noSession()
        } catch (_: Exception) {
            Log.e(TAG, "APDU unexpected error")
            HceApduProtocol.badState()
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.i(TAG, "onDeactivated reason=$reason")
        selected = false
    }
}
