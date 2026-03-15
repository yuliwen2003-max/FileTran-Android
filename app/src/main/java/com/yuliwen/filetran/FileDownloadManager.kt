package com.yuliwen.filetran

import android.content.Context
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashSet

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speed: Long, // bytes per second
    val progress: Float,
    val fileName: String = ""
) {
    fun getFormattedSpeed(): String {
        return when {
            speed < 1024 -> "$speed B/s"
            speed < 1024 * 1024 -> "${speed / 1024} KB/s"
            else -> "${speed / (1024 * 1024)} MB/s"
        }
    }

    fun getProgressPercentage(): Int = (progress * 100).toInt()
}

object FileDownloadManager {
    private const val APP_FOLDER = "FileTran"
    private const val INTERNAL_DOWNLOADS = "Downloads"

    class DownloadControl {
        @Volatile
        var paused: Boolean = false

        @Volatile
        var cancelled: Boolean = false
    }

    fun getDownloadDirectory(context: Context? = null): File {
        if (context == null) {
            val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            return File(publicDownloads, APP_FOLDER)
        }

        val candidates = buildDirectoryCandidates(context)
        for (dir in candidates) {
            if (isDirectoryWritable(dir)) return dir
        }

        // Always return an app-writable fallback instead of an unwritable public path.
        return fallbackDirectory(context).apply { mkdirs() }
    }

    fun downloadFile(
        url: String,
        context: Context,
        suggestedFileName: String? = null,
        control: DownloadControl? = null
    ): Flow<DownloadProgress> = flow {
        var connection: HttpURLConnection? = null

        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                connect()
            }

            val rawName = getFileNameFromConnection(connection, suggestedFileName)
            val actualFileName = sanitizeFileName(rawName)
            val totalBytes = connection.contentLength.toLong()
            val inputStream = connection.inputStream

            var targetFile = createUniqueFile(getDownloadDirectory(context), actualFileName)
            var outputStream = runCatching { FileOutputStream(targetFile) }.getOrNull()

            if (outputStream == null) {
                val backupDir = fallbackDirectory(context).apply { mkdirs() }
                targetFile = createUniqueFile(backupDir, actualFileName)
                outputStream = runCatching { FileOutputStream(targetFile) }.getOrNull()
            }

            if (outputStream == null) {
                throw IOException("文件不可写，请检查存储权限")
            }

            inputStream.use { input ->
                outputStream.use { output ->
                    val buffer = ByteArray(8192)
                    var downloadedBytes = 0L
                    var bytesRead: Int
                    var lastUpdateTime = System.currentTimeMillis()
                    var lastDownloadedBytes = 0L

                    emit(DownloadProgress(0, totalBytes, 0, 0f, targetFile.name))

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (control?.cancelled == true) {
                            throw IOException("DOWNLOAD_CANCELLED")
                        }
                        while (control?.paused == true && control.cancelled != true) {
                            Thread.sleep(120)
                        }
                        if (control?.cancelled == true) {
                            throw IOException("DOWNLOAD_CANCELLED")
                        }

                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastUpdateTime
                        if (elapsed >= 200) {
                            val bytesDiff = downloadedBytes - lastDownloadedBytes
                            val speed = (bytesDiff * 1000L / elapsed.coerceAtLeast(1L)).coerceAtLeast(0L)
                            val progress = if (totalBytes > 0L) {
                                downloadedBytes.toFloat() / totalBytes
                            } else {
                                0f
                            }
                            emit(DownloadProgress(downloadedBytes, totalBytes, speed, progress, targetFile.name))
                            lastUpdateTime = now
                            lastDownloadedBytes = downloadedBytes
                        }
                    }

                    output.flush()
                    emit(DownloadProgress(downloadedBytes, totalBytes, 0, 1.0f, targetFile.name))
                }
            }
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun getFileNameFromConnection(connection: HttpURLConnection, suggestedFileName: String?): String {
        val contentDisposition = connection.getHeaderField("Content-Disposition")
        if (!contentDisposition.isNullOrBlank()) {
            val fileNameMatch = Regex("filename=\"?([^\"]+)\"?").find(contentDisposition)
            val extracted = fileNameMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (extracted.isNotBlank()) return extracted
        }

        if (!suggestedFileName.isNullOrBlank()) {
            return suggestedFileName
        }

        val path = connection.url.path
        val fromUrl = path.substringAfterLast('/').trim()
        if (fromUrl.isNotBlank() && fromUrl.contains('.')) {
            return fromUrl
        }

        val contentType = connection.contentType
        val extension = when {
            contentType?.contains("image/jpeg") == true -> ".jpg"
            contentType?.contains("image/png") == true -> ".png"
            contentType?.contains("image/gif") == true -> ".gif"
            contentType?.contains("video/mp4") == true -> ".mp4"
            contentType?.contains("audio/mpeg") == true -> ".mp3"
            contentType?.contains("application/pdf") == true -> ".pdf"
            contentType?.contains("application/zip") == true -> ".zip"
            contentType?.contains("text/plain") == true -> ".txt"
            else -> ""
        }
        return "file_${System.currentTimeMillis()}$extension"
    }

    @Deprecated("Use downloadFile with connection header detection")
    fun getFileNameFromUrl(url: String): String {
        return try {
            val path = URL(url).path
            val fileName = path.substringAfterLast('/')
            if (fileName.isNotBlank() && fileName.contains('.')) {
                fileName
            } else {
                "file_${System.currentTimeMillis()}"
            }
        } catch (_: Exception) {
            "file_${System.currentTimeMillis()}"
        }
    }

    fun isFileExists(fileName: String, context: Context): Boolean {
        return findExistingFile(fileName, context)?.exists() == true
    }

    fun getFilePath(fileName: String, context: Context): String {
        val found = findExistingFile(fileName, context)
        if (found != null) return found.absolutePath
        return File(getDownloadDirectory(context), sanitizeFileName(fileName)).absolutePath
    }

    fun deleteFile(filePath: String): Boolean {
        return runCatching { File(filePath).delete() }.getOrDefault(false)
    }

    private fun buildDirectoryCandidates(context: Context): List<File> {
        val unique = LinkedHashSet<String>()
        val result = mutableListOf<File>()

        fun add(path: File?) {
            if (path == null) return
            val key = path.absolutePath
            if (unique.add(key)) result.add(path)
        }

        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        add(File(publicDownloads, APP_FOLDER))
        add(publicDownloads)

        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let {
            add(File(it, APP_FOLDER))
            add(it)
        }
        context.getExternalFilesDir(null)?.let {
            add(File(it, INTERNAL_DOWNLOADS))
            add(File(it, "$INTERNAL_DOWNLOADS/$APP_FOLDER"))
        }

        add(File(context.filesDir, "$INTERNAL_DOWNLOADS/$APP_FOLDER"))
        add(File(context.filesDir, INTERNAL_DOWNLOADS))
        add(File(context.cacheDir, "$INTERNAL_DOWNLOADS/$APP_FOLDER"))
        return result
    }

    private fun fallbackDirectory(context: Context): File {
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let {
            return File(it, APP_FOLDER)
        }
        return File(context.filesDir, "$INTERNAL_DOWNLOADS/$APP_FOLDER")
    }

    private fun isDirectoryWritable(dir: File): Boolean {
        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) return false
            val probe = File(dir, ".probe_${System.nanoTime()}")
            FileOutputStream(probe).use { it.write(0) }
            probe.delete()
            true
        }.getOrDefault(false)
    }

    private fun createUniqueFile(directory: File, fileName: String): File {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val safeName = sanitizeFileName(fileName)
        var candidate = File(directory, safeName)
        var counter = 1
        val base = safeName.substringBeforeLast(".", safeName)
        val ext = if (safeName.contains(".")) ".${safeName.substringAfterLast(".")}" else ""
        while (candidate.exists()) {
            candidate = File(directory, "${base}_$counter$ext")
            counter++
        }
        return candidate
    }

    private fun findExistingFile(fileName: String, context: Context): File? {
        val safeName = sanitizeFileName(fileName)
        for (dir in buildDirectoryCandidates(context)) {
            val file = File(dir, safeName)
            if (file.exists()) return file
        }
        return null
    }

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim().ifBlank { "file_${System.currentTimeMillis()}" }
        val normalized = trimmed
            .replace("\\", "_")
            .replace("/", "_")
            .replace(":", "_")
            .replace("*", "_")
            .replace("?", "_")
            .replace("\"", "_")
            .replace("<", "_")
            .replace(">", "_")
            .replace("|", "_")
        return normalized.ifBlank { "file_${System.currentTimeMillis()}" }
    }
}
