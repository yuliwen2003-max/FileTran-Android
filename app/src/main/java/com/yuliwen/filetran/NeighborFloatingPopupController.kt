package com.yuliwen.filetran

import android.content.Context

internal class NeighborFloatingPopupController(
    context: Context
) {
    @Suppress("unused")
    private val appContext = context.applicationContext

    fun showInvite(data: NeighborSheetInvite): Boolean = false

    fun showProgress(data: NeighborSheetProgress): Boolean = false

    fun showDone(data: NeighborSheetDone): Boolean = false

    fun hide() = Unit

    fun hideImmediate() = Unit
}
