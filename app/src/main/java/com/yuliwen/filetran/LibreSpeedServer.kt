package com.yuliwen.filetran

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.InputStream
import java.security.SecureRandom

private const val LIBRESPEED_CHUNK_BYTES = 1024 * 1024
private const val LIBRESPEED_CHUNK_DEFAULT = 4
private const val LIBRESPEED_CHUNK_MAX = 1024

class LibreSpeedServer(
    private val context: Context,
    port: Int
) : NanoHTTPD(port) {

    private val randomChunk = ByteArray(LIBRESPEED_CHUNK_BYTES).also { SecureRandom().nextBytes(it) }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.orEmpty()
        return when (path) {
            "/", "/index.html" -> serveAsset("librespeed/index.html", "text/html; charset=utf-8")
            "/speedtest.js" -> serveAsset("librespeed/speedtest.js", "application/javascript; charset=utf-8")
            "/speedtest_worker.js" -> serveAsset("librespeed/speedtest_worker.js", "application/javascript; charset=utf-8")
            "/backend/empty.php" -> handleEmpty(session)
            "/backend/garbage.php" -> handleGarbage(session)
            "/backend/getIP.php" -> handleGetIp(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Not Found")
        }
    }

    private fun serveAsset(assetPath: String, mimeType: String): Response {
        return runCatching {
            val stream = context.assets.open(assetPath)
            newChunkedResponse(Response.Status.OK, mimeType, stream).apply {
                applyNoCacheHeaders(this)
            }
        }.getOrElse { e ->
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain; charset=utf-8",
                "ASSET_ERROR ${e.message ?: "unknown"}"
            )
        }
    }

    private fun handleEmpty(session: IHTTPSession): Response {
        if (session.method == Method.POST || session.method == Method.PUT) {
            drainRequestBody(session)
        }
        val response = newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "")
        applyNoCacheHeaders(response)
        applyCorsHeadersIfNeeded(response, session)
        response.addHeader("Connection", "keep-alive")
        return response
    }

    private fun handleGarbage(session: IHTTPSession): Response {
        val chunks = session.parameters["ckSize"]?.firstOrNull()
            ?.toIntOrNull()
            ?.coerceIn(1, LIBRESPEED_CHUNK_MAX)
            ?: LIBRESPEED_CHUNK_DEFAULT
        val totalBytes = chunks.toLong() * LIBRESPEED_CHUNK_BYTES.toLong()
        val payload = RepeatingChunkInputStream(randomChunk, chunks)

        val response = newFixedLengthResponse(
            Response.Status.OK,
            "application/octet-stream",
            payload,
            totalBytes
        )
        response.addHeader("Content-Description", "File Transfer")
        response.addHeader("Content-Disposition", "attachment; filename=random.dat")
        response.addHeader("Content-Transfer-Encoding", "binary")
        applyNoCacheHeaders(response)
        applyCorsHeadersIfNeeded(response, session)
        return response
    }

    private fun handleGetIp(session: IHTTPSession): Response {
        val ip = normalizeIp(session.remoteIpAddress.orEmpty())
        val ipTag = classifyIpTag(ip)
        val processed = if (ipTag == null) ip else "$ip - $ipTag"
        val body = JSONObject()
            .put("processedString", processed)
            .put("rawIspInfo", "")
            .toString()

        val response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)
        applyNoCacheHeaders(response)
        applyCorsHeadersIfNeeded(response, session)
        return response
    }

    private fun applyNoCacheHeaders(response: Response) {
        response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0, s-maxage=0")
        response.addHeader("Pragma", "no-cache")
    }

    private fun applyCorsHeadersIfNeeded(response: Response, session: IHTTPSession) {
        if (!session.parameters.containsKey("cors")) return
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Encoding, Content-Type")
    }

    private fun drainRequestBody(session: IHTTPSession) {
        val remainingTotal = session.headers["content-length"]?.toLongOrNull()
        if (remainingTotal == null) {
            runCatching { session.parseBody(mutableMapOf()) }
            return
        }
        var remaining = remainingTotal
        val buffer = ByteArray(16 * 1024)
        val stream = session.inputStream
        while (remaining > 0) {
            val readSize = minOf(remaining, buffer.size.toLong()).toInt()
            val read = stream.read(buffer, 0, readSize)
            if (read <= 0) return
            remaining -= read
        }
    }

    private fun normalizeIp(raw: String): String {
        val clean = raw.trim().removePrefix("::ffff:")
        return if (clean.isBlank()) "0.0.0.0" else clean
    }

    private fun classifyIpTag(ip: String): String? {
        return when {
            ip == "::1" -> "localhost IPv6 access"
            ip.startsWith("fe80:", ignoreCase = true) -> "link-local IPv6 access"
            ip.startsWith("127.") -> "localhost IPv4 access"
            ip.startsWith("10.") -> "private IPv4 access"
            ip.startsWith("192.168.") -> "private IPv4 access"
            ip.startsWith("169.254.") -> "link-local IPv4 access"
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(ip) -> "private IPv4 access"
            else -> null
        }
    }
}

private class RepeatingChunkInputStream(
    private val chunk: ByteArray,
    private val chunkCount: Int
) : InputStream() {
    private var currentChunk = 0
    private var offsetInChunk = 0

    override fun read(): Int {
        if (currentChunk >= chunkCount) return -1
        val value = chunk[offsetInChunk].toInt() and 0xFF
        offsetInChunk++
        if (offsetInChunk >= chunk.size) {
            offsetInChunk = 0
            currentChunk++
        }
        return value
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (currentChunk >= chunkCount) return -1

        var remaining = len
        var written = 0
        while (remaining > 0 && currentChunk < chunkCount) {
            val available = chunk.size - offsetInChunk
            val copySize = minOf(remaining, available)
            System.arraycopy(chunk, offsetInChunk, buffer, off + written, copySize)
            offsetInChunk += copySize
            written += copySize
            remaining -= copySize
            if (offsetInChunk >= chunk.size) {
                offsetInChunk = 0
                currentChunk++
            }
        }
        return if (written == 0) -1 else written
    }
}
