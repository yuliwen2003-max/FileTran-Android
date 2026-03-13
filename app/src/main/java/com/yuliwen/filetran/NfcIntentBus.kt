package com.yuliwen.filetran

import android.nfc.NdefMessage
import android.nfc.Tag
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

data class NfcIntentPayload(
    val tag: Tag?,
    val messages: List<NdefMessage>
)

object NfcIntentBus {
    private val _events = MutableSharedFlow<NfcIntentPayload>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<NfcIntentPayload> = _events

    fun publish(payload: NfcIntentPayload) {
        _events.tryEmit(payload)
    }
}
