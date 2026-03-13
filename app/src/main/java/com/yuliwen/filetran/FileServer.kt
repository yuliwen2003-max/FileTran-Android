package com.yuliwen.filetran

import android.content.Context
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import java.io.FileInputStream
import java.io.InputStream

class FileServer(
    private val context: Context,
    private val port: Int,
    private val fileUri: Uri,
    private val fileName: String,
    private val mimeType: String
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(fileUri)
            if (inputStream != null) {
                val fileSize = inputStream.available().toLong()
                inputStream.close()
                
                val finalInputStream = context.contentResolver.openInputStream(fileUri)
                newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    finalInputStream,
                    fileSize
                ).apply {
                    addHeader("Content-Disposition", "attachment; filename=\"$fileName\"")
                    addHeader("Accept-Ranges", "bytes")
                }
            } else {
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "文件未找到"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "服务器错误: ${e.message}"
            )
        }
    }
}




