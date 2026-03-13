package com.yuliwen.filetran

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class ReversePushReceiveStage {
    RECEIVING, COMPLETED, FAILED, CANCELLED
}

data class ReversePushReceiveProgress(
    val stage: ReversePushReceiveStage,
    val fileName: String,
    val mimeType: String,
    val receivedBytes: Long,
    val totalBytes: Long,
    val localPath: String? = null,
    val message: String? = null
)

class ReversePushTcpServer(
    private val context: Context,
    private val port: Int,
    private val ipv6Mode: Boolean,
    private val onProgress: (ReversePushReceiveProgress) -> Unit = {}
) {
    private val logTag = "ReversePushRx"
    private val running = AtomicBoolean(false)
    private val cancelCurrent = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var activeClient: Socket? = null

    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newCachedThreadPool()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket()
        val bindAddress = if (ipv6Mode) InetAddress.getByName("::") else InetAddress.getByName("0.0.0.0")
        socket.bind(InetSocketAddress(bindAddress, port))
        Log.i(logTag, "server started bind=${bindAddress.hostAddress}:$port ipv6Mode=$ipv6Mode")
        serverSocket = socket
        acceptExecutor.execute {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                Log.i(logTag, "client accepted remote=${client.inetAddress?.hostAddress}:${client.port}")
                clientExecutor.execute { handleClient(client) }
            }
        }
    }

    fun stop() {
        running.set(false)
        cancelCurrent.set(true)
        runCatching { activeClient?.close() }
        runCatching { serverSocket?.close() }
        Log.i(logTag, "server stopped")
        serverSocket = null
        activeClient = null
        runCatching { acceptExecutor.shutdownNow() }
        runCatching { clientExecutor.shutdownNow() }
    }

    fun cancelCurrentTransfer() {
        cancelCurrent.set(true)
        runCatching { activeClient?.close() }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            activeClient = socket
            cancelCurrent.set(false)
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 16 * 1024))
            runCatching {
                val magic = input.readInt()
                if (magic != MAGIC) throw IllegalStateException("Bad magic")
                val version = input.readUnsignedByte()
                if (version != 1) throw IllegalStateException("Bad version")

                val fileName = input.readSizedUtf(maxBytes = 8 * 1024)
                    .ifBlank { "push_${System.currentTimeMillis()}" }
                val mimeType = input.readSizedUtf(maxBytes = 8 * 1024)
                    .ifBlank { "application/octet-stream" }
                val totalSize = input.readLong()
                if (totalSize < 0L) throw IllegalStateException("Bad size")
                Log.i(logTag, "recv header file=$fileName size=$totalSize mime=$mimeType")
                onProgress(
                    ReversePushReceiveProgress(
                        stage = ReversePushReceiveStage.RECEIVING,
                        fileName = fileName,
                        mimeType = mimeType,
                        receivedBytes = 0L,
                        totalBytes = totalSize,
                        localPath = null
                    )
                )

                val downloadDir = FileDownloadManager.getDownloadDirectory(context).apply { mkdirs() }
                val outFile = uniqueTargetFile(downloadDir, sanitizeFileName(fileName))
                FileOutputStream(outFile).use { fos ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = totalSize
                    var received = 0L
                    while (remaining > 0) {
                        if (cancelCurrent.get()) throw IllegalStateException("TRANSFER_CANCELLED")
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read <= 0) throw IllegalStateException("Unexpected EOF")
                        fos.write(buffer, 0, read)
                        received += read.toLong()
                        onProgress(
                            ReversePushReceiveProgress(
                                stage = ReversePushReceiveStage.RECEIVING,
                                fileName = fileName,
                                mimeType = mimeType,
                                receivedBytes = received,
                                totalBytes = totalSize,
                                localPath = null
                            )
                        )
                        remaining -= read.toLong()
                    }
                    fos.flush()
                }

                runCatching {
                    DownloadHistoryManager(context).addRecord(
                        DownloadRecord(
                            fileName = outFile.name,
                            filePath = outFile.absolutePath,
                            fileSize = outFile.length(),
                            sourceUrl = "push-tcp://${socket.inetAddress.hostAddress ?: "unknown"}"
                        )
                    )
                }

                output.writeByte(1)
                output.writeSizedUtf("OK:${outFile.name}:${mimeType}")
                output.flush()
                Log.i(logTag, "recv completed file=${outFile.name} size=${outFile.length()}")
                onProgress(
                    ReversePushReceiveProgress(
                        stage = ReversePushReceiveStage.COMPLETED,
                        fileName = outFile.name,
                        mimeType = mimeType,
                        receivedBytes = outFile.length(),
                        totalBytes = outFile.length(),
                        localPath = outFile.absolutePath,
                        message = "接收完成"
                    )
                )
            }.onFailure { e ->
                Log.e(logTag, "recv failed", e)
                val cancelled = e.message == "TRANSFER_CANCELLED" || cancelCurrent.get()
                runCatching {
                    output.writeByte(0)
                    output.writeSizedUtf("ERR:${if (cancelled) "TRANSFER_CANCELLED" else e.message ?: "unknown"}")
                    output.flush()
                }
                onProgress(
                    ReversePushReceiveProgress(
                        stage = if (cancelled) ReversePushReceiveStage.CANCELLED else ReversePushReceiveStage.FAILED,
                        fileName = "",
                        mimeType = "",
                        receivedBytes = 0L,
                        totalBytes = 0L,
                        localPath = null,
                        message = if (cancelled) "已取消当前接收" else "接收失败：${e.message ?: "unknown"}"
                    )
                )
            }.also {
                activeClient = null
                cancelCurrent.set(false)
            }
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "push_${System.currentTimeMillis()}" }
    }

    private fun uniqueTargetFile(dir: File, fileName: String): File {
        val initial = File(dir, fileName)
        if (!initial.exists()) return initial
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".${it}" }
        var index = 1
        while (true) {
            val candidate = File(dir, "${base}_${index}${ext}")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun DataInputStream.readSizedUtf(maxBytes: Int): String {
        val length = readInt()
        if (length < 0 || length > maxBytes) throw IllegalStateException("Bad text length")
        if (length == 0) return ""
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun DataOutputStream.writeSizedUtf(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    companion object {
        private const val MAGIC = 0x4654524E // FTRN
    }
}
