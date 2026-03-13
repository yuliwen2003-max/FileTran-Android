package com.yuliwen.filetran

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ClipboardRecord(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val source: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun preview(maxLength: Int = 80): String {
        val normalized = content.replace("\n", " ").trim()
        return if (normalized.length <= maxLength) normalized else "${normalized.take(maxLength)}..."
    }
}

class ClipboardHistoryManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("clipboard_history", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_HISTORY = "clipboard_history_list"
        private const val MAX_ITEMS = 100
    }

    fun addRecord(content: String, source: String) {
        if (content.isBlank()) return
        val history = getHistory().toMutableList()
        history.removeAll { it.content == content }
        history.add(0, ClipboardRecord(content = content, source = source))
        if (history.size > MAX_ITEMS) {
            history.subList(MAX_ITEMS, history.size).clear()
        }
        saveHistory(history)
    }

    fun getHistory(): List<ClipboardRecord> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val type = object : TypeToken<List<ClipboardRecord>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun deleteRecord(id: String) {
        val history = getHistory().toMutableList()
        history.removeAll { it.id == id }
        saveHistory(history)
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun saveHistory(history: List<ClipboardRecord>) {
        prefs.edit().putString(KEY_HISTORY, gson.toJson(history)).apply()
    }
}
