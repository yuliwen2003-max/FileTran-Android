package com.yuliwen.filetran

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class NeighborSheetInvite(
    val requestId: String,
    val senderName: String,
    val senderIp: String,
    val fileName: String,
    val fileSize: Long,
    val secondsLeft: Int,
    val defaultAccept: Boolean
)

internal data class NeighborSheetProgress(
    val requestId: String,
    val fileName: String,
    val progress: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSecond: Long
)

internal data class NeighborSheetDone(
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val canImportGallery: Boolean = false
)

internal sealed interface NeighborSheetUiState {
    data object Hidden : NeighborSheetUiState
    data class Invite(val data: NeighborSheetInvite) : NeighborSheetUiState
    data class Progress(val data: NeighborSheetProgress) : NeighborSheetUiState
    data class Done(val data: NeighborSheetDone) : NeighborSheetUiState
}

internal object NeighborSheetBridge {
    private val _state = MutableStateFlow<NeighborSheetUiState>(NeighborSheetUiState.Hidden)
    val state: StateFlow<NeighborSheetUiState> = _state.asStateFlow()

    fun showInvite(data: NeighborSheetInvite) {
        _state.value = NeighborSheetUiState.Invite(data)
    }

    fun showProgress(data: NeighborSheetProgress) {
        _state.value = NeighborSheetUiState.Progress(data)
    }

    fun showDone(data: NeighborSheetDone) {
        _state.value = NeighborSheetUiState.Done(data)
    }

    fun hide() {
        _state.value = NeighborSheetUiState.Hidden
    }
}
