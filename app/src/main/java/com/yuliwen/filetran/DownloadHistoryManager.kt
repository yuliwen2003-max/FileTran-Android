package com.yuliwen.filetran

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

data class DownloadRecord(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val downloadTime: Long = System.currentTimeMillis(),
    val sourceUrl: String
) {
    fun getFormattedSize(): String {
        return when {
            fileSize < 1024 -> "$fileSize B"
            fileSize < 1024 * 1024 -> "${fileSize / 1024} KB"
            fileSize < 1024 * 1024 * 1024 -> "${fileSize / (1024 * 1024)} MB"
            else -> "${fileSize / (1024 * 1024 * 1024)} GB"
        }
    }
    
    fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(downloadTime))
    }
}

class DownloadHistoryManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("download_history", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val KEY_HISTORY = "history_list"
    }
    
    fun addRecord(record: DownloadRecord) {
        val history = getHistory().toMutableList()
        history.add(0, record) // 添加到列表开头
        
        // 只保留最近100条记录
        if (history.size > 100) {
            history.removeAt(history.size - 1)
        }
        
        saveHistory(history)
    }
    
    fun getHistory(): List<DownloadRecord> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val type = object : TypeToken<List<DownloadRecord>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
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
    
    private fun saveHistory(history: List<DownloadRecord>) {
        val json = gson.toJson(history)
        prefs.edit().putString(KEY_HISTORY, json).apply()
    }
}


