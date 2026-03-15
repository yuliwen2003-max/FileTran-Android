package com.yuliwen.filetran

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

fun copyPlainTextToClipboard(context: Context, label: String, text: String): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    return true
}
