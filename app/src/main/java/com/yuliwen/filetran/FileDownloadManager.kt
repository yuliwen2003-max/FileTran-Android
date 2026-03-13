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

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speed: Long, // bytes per second
    val progress: Float,
    val fileName: String = "" // 实际保存的文件名
) {
    fun getFormattedSpeed(): String {
        return when {
            speed < 1024 -> "$speed B/s"
            speed < 1024 * 1024 -> "${speed / 1024} KB/s"
            else -> "${speed / (1024 * 1024)} MB/s"
        }
    }
    
    fun getProgressPercentage(): Int {
        return (progress * 100).toInt()
    }
}

object FileDownloadManager {
    class DownloadControl {
        @Volatile
        var paused: Boolean = false
        @Volatile
        var cancelled: Boolean = false
    }
    
    /**
     * 获取下载目录 - 兼容低版本Android和特殊ROM（如EMUI）
     */
    fun getDownloadDirectory(context: Context? = null): File {
        // 尝试多个路径，按优先级排序
        val possiblePaths = mutableListOf<File>()
        
        try {
            // 1. 标准公共下载目录
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let {
                    possiblePaths.add(File(it, "FileTran"))
                    possiblePaths.add(it) // 备用：直接使用Download目录
                }
            } else {
                val primaryDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (primaryDir != null) {
                    possiblePaths.add(File(primaryDir, "FileTran"))
                    possiblePaths.add(primaryDir)
                }
                
                // 备用路径
                val downloadDir = File(Environment.getExternalStorageDirectory(), "Download")
                possiblePaths.add(File(downloadDir, "FileTran"))
                possiblePaths.add(downloadDir)
            }
            
            // 2. 应用专属外部存储目录（不需要权限，适用于EMUI等特殊ROM）
            context?.let { ctx ->
                ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let {
                    possiblePaths.add(it)
                }
                ctx.getExternalFilesDir(null)?.let {
                    possiblePaths.add(File(it, "Downloads"))
                }
            }
            
            // 3. 内部存储（最后的备选）
            context?.let { ctx ->
                possiblePaths.add(File(ctx.filesDir, "Downloads"))
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // 测试每个路径，找到第一个可用的
        for (dir in possiblePaths) {
            try {
                // 确保目录存在
                if (!dir.exists()) {
                    val created = dir.mkdirs()
                    if (!created) continue
                }
                
                // 测试写权限
                val testFile = File(dir, ".test_${System.currentTimeMillis()}")
                try {
                    testFile.createNewFile()
                    testFile.delete()
                    // 成功！使用这个目录
                    return dir
                } catch (e: Exception) {
                    // 没有写权限，尝试下一个
                    continue
                }
            } catch (e: Exception) {
                continue
            }
        }
        
        // 如果所有路径都失败，返回一个默认路径（可能会失败，但至少有个路径）
        return File(Environment.getExternalStorageDirectory(), "Download")
    }
    
    /**
     * 下载文件并返回进度Flow，同时返回实际文件名
     */
    fun downloadFile(
        url: String,
        context: Context,
        suggestedFileName: String? = null,
        control: DownloadControl? = null
    ): Flow<DownloadProgress> = flow {
        var connection: HttpURLConnection? = null
        var outputStream: FileOutputStream? = null
        
        try {
            val urlConnection = URL(url)
            connection = urlConnection.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.connect()
            
            // 从响应头获取文件名
            val actualFileName = getFileNameFromConnection(connection, suggestedFileName)
            
            val totalBytes = connection.contentLength.toLong()
            val inputStream = connection.inputStream
            
            val downloadDir = getDownloadDirectory(context)
            var file = File(downloadDir, actualFileName)
            
            // 如果文件已存在，添加序号
            var counter = 1
            while (file.exists()) {
                val nameWithoutExt = actualFileName.substringBeforeLast(".")
                val ext = if (actualFileName.contains(".")) ".${actualFileName.substringAfterLast(".")}" else ""
                file = File(downloadDir, "${nameWithoutExt}_${counter}${ext}")
                counter++
            }
            
            // 尝试创建文件
            try {
                file.createNewFile()
            } catch (e: Exception) {
                e.printStackTrace()
                throw Exception("无法创建文件，请检查存储权限: ${e.message}")
            }
            
            // 检查文件是否可写
            if (!file.canWrite()) {
                throw Exception("文件不可写，请检查存储权限")
            }
            
            outputStream = FileOutputStream(file)
            
            val buffer = ByteArray(8192)
            var downloadedBytes = 0L
            var bytesRead: Int
            var lastUpdateTime = System.currentTimeMillis()
            var lastDownloadedBytes = 0L
            
            // 发送初始进度（包含文件名）
            emit(DownloadProgress(0, totalBytes, 0, 0f, file.name))
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (control?.cancelled == true) {
                    throw IOException("DOWNLOAD_CANCELLED")
                }
                while (control?.paused == true && control.cancelled != true) {
                    Thread.sleep(120)
                }
                if (control?.cancelled == true) {
                    throw IOException("DOWNLOAD_CANCELLED")
                }
                outputStream.write(buffer, 0, bytesRead)
                outputStream.flush() // 确保数据写入
                downloadedBytes += bytesRead
                
                val currentTime = System.currentTimeMillis()
                val timeDiff = currentTime - lastUpdateTime
                
                // 每200ms更新一次进度
                if (timeDiff >= 200) {
                    val bytesDiff = downloadedBytes - lastDownloadedBytes
                    val speed = (bytesDiff * 1000 / timeDiff)
                    val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                    
                    emit(DownloadProgress(downloadedBytes, totalBytes, speed, progress, file.name))
                    
                    lastUpdateTime = currentTime
                    lastDownloadedBytes = downloadedBytes
                }
            }
            
            // 发送最终进度
            emit(DownloadProgress(downloadedBytes, totalBytes, 0, 1.0f, file.name))
            
            inputStream.close()
            outputStream.close()
            
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        } finally {
            connection?.disconnect()
            outputStream?.close()
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * 从HTTP连接获取文件名
     */
    private fun getFileNameFromConnection(connection: HttpURLConnection, suggestedFileName: String?): String {
        // 1. 尝试从Content-Disposition头获取
        val contentDisposition = connection.getHeaderField("Content-Disposition")
        if (contentDisposition != null) {
            val fileNameMatch = Regex("filename=\"?([^\"]+)\"?").find(contentDisposition)
            if (fileNameMatch != null) {
                return fileNameMatch.groupValues[1]
            }
        }
        
        // 2. 使用建议的文件名
        if (!suggestedFileName.isNullOrBlank()) {
            return suggestedFileName
        }
        
        // 3. 从URL路径获取
        val path = connection.url.path
        val fileName = path.substringAfterLast('/')
        if (fileName.isNotBlank() && fileName.contains('.')) {
            return fileName
        }
        
        // 4. 根据Content-Type生成文件名
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
    
    /**
     * 从URL获取文件名（已废弃，保留用于兼容）
     */
    @Deprecated("Use downloadFile with connection header detection")
    fun getFileNameFromUrl(url: String): String {
        return try {
            val urlObj = URL(url)
            val path = urlObj.path
            val fileName = path.substringAfterLast('/')
            if (fileName.isNotBlank() && fileName.contains('.')) fileName else "file_${System.currentTimeMillis()}"
        } catch (e: Exception) {
            "file_${System.currentTimeMillis()}"
        }
    }
    
    /**
     * 检查文件是否存在
     */
    fun isFileExists(fileName: String, context: Context): Boolean {
        val downloadDir = getDownloadDirectory(context)
        val file = File(downloadDir, fileName)
        return file.exists()
    }
    
    /**
     * 获取文件路径
     */
    fun getFilePath(fileName: String, context: Context): String {
        val downloadDir = getDownloadDirectory(context)
        return File(downloadDir, fileName).absolutePath
    }
    
    /**
     * 删除文件
     */
    fun deleteFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            file.delete()
        } catch (e: Exception) {
            false
        }
    }
}
