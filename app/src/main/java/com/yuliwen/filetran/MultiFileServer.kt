package com.yuliwen.filetran

import android.content.Context
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

data class MultiShareFile(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val size: Long
)

class MultiFileServer(
    private val context: Context,
    private val port: Int,
    private val files: List<MultiShareFile>
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.orEmpty()
        return when {
            path == "/manifest" -> serveManifest()
            path.startsWith("/file/") -> serveFile(path.removePrefix("/file/"))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    private fun serveManifest(): Response {
        val arr = JSONArray()
        files.forEachIndexed { index, file ->
            arr.put(
                JSONObject().apply {
                    put("id", index)
                    put("name", file.fileName)
                    put("mimeType", file.mimeType)
                    put("size", file.size.coerceAtLeast(0L))
                    put("path", "/file/$index")
                }
            )
        }
        val payload = JSONObject().apply {
            put("type", "filetran_manifest_v1")
            put("count", files.size)
            put("files", arr)
        }.toString()
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", payload)
    }

    private fun serveFile(idRaw: String): Response {
        val id = idRaw.toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid id")
        val file = files.getOrNull(id)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        val stream = context.contentResolver.openInputStream(file.uri)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")

        return newChunkedResponse(Response.Status.OK, file.mimeType.ifBlank { "*/*" }, stream).apply {
            addHeader("Content-Disposition", "attachment; filename=\"${file.fileName}\"")
            addHeader("Accept-Ranges", "bytes")
        }
    }
}

