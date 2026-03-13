package com.yuliwen.filetran

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder

class ReverseUploadServer(
    private val context: Context,
    port: Int
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return when (session.method) {
            Method.GET -> {
                val payload = JSONObject()
                    .put("type", "filetran_reverse_upload_v1")
                    .put("path", "/upload")
                    .toString()
                newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", payload)
            }

            Method.POST, Method.PUT -> {
                val path = session.uri.orEmpty()
                if (path != "/upload") {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
                }
                handleUpload(session)
            }

            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method Not Allowed")
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        return runCatching {
            val encodedName = session.headers["x-file-name"].orEmpty()
            val fileName = runCatching { URLDecoder.decode(encodedName, "UTF-8") }.getOrNull()
                ?.trim()
                .orEmpty()
                .ifBlank { "upload_${System.currentTimeMillis()}" }
            val mimeType = session.headers["x-file-mime"].orEmpty().ifBlank { "application/octet-stream" }

            val downloadDir = FileDownloadManager.getDownloadDirectory(context).apply { mkdirs() }
            val outFile = uniqueTargetFile(downloadDir, sanitizeFileName(fileName))

            session.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
            if (!outFile.exists() || outFile.length() <= 0L) {
                throw IllegalStateException("上传数据为空")
            }

            runCatching {
                DownloadHistoryManager(context).addRecord(
                    DownloadRecord(
                        fileName = outFile.name,
                        filePath = outFile.absolutePath,
                        fileSize = outFile.length(),
                        sourceUrl = "push://${session.remoteIpAddress ?: "unknown"}"
                    )
                )
            }

            val body = JSONObject()
                .put("ok", true)
                .put("fileName", outFile.name)
                .put("size", outFile.length())
                .put("mimeType", mimeType)
                .toString()
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)
        }.getOrElse { e ->
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain; charset=utf-8",
                "UPLOAD_FAILED ${e.message ?: "unknown"}"
            )
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "upload_${System.currentTimeMillis()}" }
    }

    private fun uniqueTargetFile(dir: File, fileName: String): File {
        val initial = File(dir, fileName)
        if (!initial.exists()) return initial
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var index = 1
        while (true) {
            val candidate = File(dir, "${base}_$index$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }
}
