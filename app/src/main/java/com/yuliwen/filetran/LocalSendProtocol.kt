package com.yuliwen.filetran

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val LOCALSEND_PREFS = "filetran_localsend"
private const val LOCALSEND_FP_KEY = "fingerprint_v1"
private const val LOCALSEND_PIN_PREFS = "filetran_localsend_pin"
private const val LOCALSEND_PIN_KEY_PREFIX = "pin_"
private const val LOCALSEND_API_PREFIX = "/api/localsend/v2"
private const val LOCALSEND_MULTICAST_HOST = "224.0.0.167"
private const val LOCALSEND_PEER_TTL_MS = 45_000L
private const val LOCALSEND_ANNOUNCE_INTERVAL_MS = 20_000L
private const val LOCALSEND_BUFFER_SIZE = 16 * 1024

data class LocalSendPeer(
    val ip: String,
    val alias: String,
    val deviceModel: String,
    val deviceType: String,
    val fingerprint: String,
    val port: Int,
    val protocol: String,
    val download: Boolean,
    val lastSeenAt: Long = System.currentTimeMillis()
) {
    fun toNeighborPeer(): LanNeighborPeer {
        return LanNeighborPeer(
            ip = ip,
            name = alias.ifBlank { "LocalSend device" },
            hostName = deviceModel,
            peerProtocol = NeighborPeerProtocol.LOCALSEND,
            localSendPort = port,
            localSendProtocol = protocol.ifBlank { "http" },
            localSendFingerprint = fingerprint,
            lastSeenAt = lastSeenAt
        )
    }
}

enum class LocalSendIncomingDecision {
    ACCEPT,
    REJECT,
    BUSY
}

data class LocalSendIncomingRequest(
    val requestId: String,
    val senderAlias: String,
    val senderIp: String,
    val fileCount: Int,
    val totalSize: Long,
    val firstFileName: String
)

class LocalSendRuntime(
    private val context: Context,
    private val ownerTag: String = "runtime",
    private val onPeersChanged: (List<LanNeighborPeer>) -> Unit = {},
    private val onReceiveEvent: (LocalSendReceiveEvent) -> Unit = {},
    private val canAcceptIncoming: () -> Boolean = { true },
    private val requestIncomingDecision: (LocalSendIncomingRequest) -> LocalSendIncomingDecision = {
        LocalSendIncomingDecision.ACCEPT
    }
) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val peers = ConcurrentHashMap<String, LocalSendPeer>()
    private val sessions = ConcurrentHashMap<String, PendingSession>()
    private val announceNow = AtomicBoolean(false)
    private val selfFingerprint: String by lazy {
        LocalSendProtocol.buildDeviceInfoJson(appContext, LocalSendProtocol.LOCALSEND_PORT).optString("fingerprint")
    }

    @Volatile
    private var httpServer: LocalSendHttpServer? = null

    @Volatile
    private var httpPort: Int = LocalSendProtocol.LOCALSEND_PORT

    @Volatile
    private var multicastSocket: MulticastSocket? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var activeSessionId: String? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val server = LocalSendHttpServer(this, LocalSendProtocol.LOCALSEND_PORT)
        runCatching {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            httpPort = server.listeningPort
            httpServer = server
        }.onFailure {
            Log.e("LocalSendRuntime", "[$ownerTag] failed to start http server: ${it.message}", it)
            running.set(false)
            return
        }
        worker = Thread {
            runMulticastLoop()
        }.apply {
            name = "LocalSendRuntime-$ownerTag"
            isDaemon = true
            start()
        }
        announceNow()
        Log.i("LocalSendRuntime", "[$ownerTag] started httpPort=$httpPort")
    }

    fun stop() {
        running.set(false)
        runCatching { multicastSocket?.close() }
        multicastSocket = null
        runCatching { httpServer?.stop() }
        httpServer = null
        worker?.interrupt()
        worker = null
        sessions.clear()
        peers.clear()
        publishPeers()
        Log.i("LocalSendRuntime", "[$ownerTag] stopped")
    }

    fun isRunning(): Boolean {
        return running.get() && worker?.isAlive == true
    }

    fun announceNow() {
        announceNow.set(true)
    }

    fun snapshotPeers(maxAgeMs: Long = LOCALSEND_PEER_TTL_MS): List<LanNeighborPeer> {
        val now = System.currentTimeMillis()
        return peers.values
            .filter { now - it.lastSeenAt <= maxAgeMs }
            .sortedWith(compareBy<LocalSendPeer> { it.alias.lowercase() }.thenBy { it.ip })
            .map { it.toNeighborPeer() }
    }

    fun probePeer(targetIp: String): LanNeighborPeer? {
        if (!running.get()) return null
        val ip = targetIp.trim()
        if (ip.isBlank()) return null
        val httpPeer = LocalSendProtocol.sendRegisterAndParsePeer(
            context = appContext,
            targetIp = ip,
            targetPort = LocalSendProtocol.LOCALSEND_PORT,
            targetProtocol = "http",
            localPort = httpPort
        )
        val peer = httpPeer ?: LocalSendProtocol.sendRegisterAndParsePeer(
            context = appContext,
            targetIp = ip,
            targetPort = LocalSendProtocol.LOCALSEND_PORT,
            targetProtocol = "https",
            localPort = httpPort
        )
        if (peer != null) {
            upsertPeer(peer)
            return peer.toNeighborPeer()
        }
        return null
    }

    private fun runMulticastLoop() {
        var lastAnnounceAt = 0L
        val group = runCatching { InetAddress.getByName(LOCALSEND_MULTICAST_HOST) }.getOrNull() ?: return
        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket(LocalSendProtocol.LOCALSEND_PORT).apply {
                reuseAddress = true
                soTimeout = 1000
                joinGroup(group)
            }
            multicastSocket = socket
            val buffer = ByteArray(LOCALSEND_BUFFER_SIZE)
            while (running.get()) {
                val now = System.currentTimeMillis()
                if (announceNow.compareAndSet(true, false) || now - lastAnnounceAt >= LOCALSEND_ANNOUNCE_INTERVAL_MS) {
                    sendUdpAnnounce(socket, group, announce = true)
                    lastAnnounceAt = now
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    cleanupPeers()
                    continue
                } catch (e: IOException) {
                    if (running.get()) {
                        Log.w("LocalSendRuntime", "[$ownerTag] multicast receive failed: ${e.message}")
                    }
                    continue
                }
                handleUdpPacket(
                    message = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim(),
                    senderIp = packet.address?.hostAddress.orEmpty().trim(),
                    group = group,
                    socket = socket
                )
            }
        } catch (e: Exception) {
            if (running.get()) {
                Log.e("LocalSendRuntime", "[$ownerTag] multicast loop failed: ${e.message}", e)
            }
        } finally {
            runCatching { socket?.leaveGroup(group) }
            runCatching { socket?.close() }
            multicastSocket = null
        }
    }

    private fun handleUdpPacket(
        message: String,
        senderIp: String,
        group: InetAddress,
        socket: MulticastSocket
    ) {
        if (message.isBlank() || senderIp.isBlank()) return
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return
        val peer = LocalSendProtocol.parsePeer(json, senderIp) ?: return
        if (peer.fingerprint == selfFingerprint) return
        upsertPeer(peer)
        if (json.optBoolean("announce", false)) {
            sendUdpAnnounce(socket, group, announce = false)
            Thread {
                runCatching {
                    val responsePeer = LocalSendProtocol.sendRegisterAndParsePeer(
                        context = appContext,
                        targetIp = peer.ip,
                        targetPort = peer.port,
                        targetProtocol = peer.protocol,
                        localPort = httpPort
                    )
                    if (responsePeer != null) {
                        upsertPeer(responsePeer)
                    }
                }
            }.start()
        }
    }

    private fun sendUdpAnnounce(socket: MulticastSocket, group: InetAddress, announce: Boolean) {
        val payload = LocalSendProtocol.buildDeviceInfoJson(
            context = appContext,
            port = httpPort,
            protocol = "http",
            announce = announce
        ).toString().toByteArray(Charsets.UTF_8)
        runCatching {
            socket.send(DatagramPacket(payload, payload.size, group, LocalSendProtocol.LOCALSEND_PORT))
        }.onFailure {
            Log.w("LocalSendRuntime", "[$ownerTag] announce send failed: ${it.message}")
        }
    }

    private fun upsertPeer(peer: LocalSendPeer) {
        peers["${peer.fingerprint}|${peer.ip}"] = peer.copy(lastSeenAt = System.currentTimeMillis())
        publishPeers()
    }

    private fun cleanupPeers() {
        val now = System.currentTimeMillis()
        var changed = false
        val iter = peers.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (now - entry.value.lastSeenAt > LOCALSEND_PEER_TTL_MS) {
                iter.remove()
                changed = true
            }
        }
        if (changed) publishPeers()
    }

    private fun publishPeers() {
        onPeersChanged(snapshotPeers())
    }

    private data class PendingFile(
        val id: String,
        val fileName: String,
        val fileType: String,
        val size: Long,
        val token: String,
        var done: Boolean = false
    )

    private data class PendingSession(
        val sessionId: String,
        val senderAlias: String,
        val senderIp: String,
        val files: MutableMap<String, PendingFile>,
        val createdAt: Long = System.currentTimeMillis()
    )

    private class LocalSendHttpServer(
        private val runtime: LocalSendRuntime,
        port: Int
    ) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            return runtime.handleHttp(session)
        }
    }

    internal fun handleHttp(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!NeighborDiscoverySettingsStore.get(appContext).localSendV2Enabled) {
            return textResponse(NanoHTTPD.Response.Status.FORBIDDEN, "localsend_disabled")
        }
        val path = session.uri.orEmpty()
        val method = session.method
        return when {
            method == NanoHTTPD.Method.GET && path == "$LOCALSEND_API_PREFIX/info" -> {
                jsonResponse(
                    NanoHTTPD.Response.Status.OK,
                    LocalSendProtocol.buildDeviceInfoJson(
                        context = appContext,
                        port = httpPort,
                        protocol = "http",
                        announce = null
                    ).toString()
                )
            }

            method == NanoHTTPD.Method.POST && path == "$LOCALSEND_API_PREFIX/register" -> {
                handleRegister(session)
            }

            method == NanoHTTPD.Method.POST && path == "$LOCALSEND_API_PREFIX/prepare-upload" -> {
                handlePrepareUpload(session)
            }

            method == NanoHTTPD.Method.POST && path == "$LOCALSEND_API_PREFIX/upload" -> {
                handleUpload(session)
            }

            method == NanoHTTPD.Method.POST && path == "$LOCALSEND_API_PREFIX/cancel" -> {
                handleCancel(session)
            }

            else -> textResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Not Found")
        }
    }

    private fun handleRegister(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val body = parseJsonBody(session)
            ?: return textResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid_json")
        val senderIp = session.headers["remote-addr"].orEmpty().trim()
        val peer = LocalSendProtocol.parsePeer(body, senderIp)
        if (peer != null && peer.fingerprint != selfFingerprint) {
            upsertPeer(peer)
            if (body.optBoolean("announce", false)) {
                Thread {
                    runCatching {
                        val responsePeer = LocalSendProtocol.sendRegisterAndParsePeer(
                            context = appContext,
                            targetIp = peer.ip,
                            targetPort = peer.port,
                            targetProtocol = peer.protocol,
                            localPort = httpPort
                        )
                        if (responsePeer != null) {
                            upsertPeer(responsePeer)
                        }
                    }
                }.start()
            }
        }
        return jsonResponse(
            NanoHTTPD.Response.Status.OK,
            LocalSendProtocol.buildDeviceInfoJson(
                context = appContext,
                port = httpPort,
                protocol = "http",
                announce = null
            ).toString()
        )
    }

    private fun handlePrepareUpload(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!canAcceptIncoming()) {
            return textResponse(NanoHTTPD.Response.Status.CONFLICT, "receiver_busy")
        }
        val settings = NeighborDiscoverySettingsStore.get(appContext)
        if (!settings.enabled) {
            return textResponse(NanoHTTPD.Response.Status.FORBIDDEN, "receiver_disabled")
        }
        val body = parseJsonBody(session)
            ?: return textResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid_json")
        val info = body.optJSONObject("info") ?: JSONObject()
        val filesObj = body.optJSONObject("files") ?: JSONObject()
        if (filesObj.length() <= 0) {
            return textResponse(NanoHTTPD.Response.Status.NO_CONTENT, "")
        }
        val senderIp = session.headers["remote-addr"].orEmpty().trim()
        val senderAlias = info.optString("alias").trim().ifBlank { "LocalSend sender" }
        val preparedFiles = mutableListOf<PendingFile>()
        val keys = filesObj.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val file = filesObj.optJSONObject(id) ?: continue
            val fileName = file.optString("fileName").trim().ifBlank { "localsend_${System.currentTimeMillis()}" }
            val fileType = file.optString("fileType").trim().ifBlank { "application/octet-stream" }
            val size = file.optLong("size", -1L).coerceAtLeast(0L)
            val token = UUID.randomUUID().toString().replace("-", "")
            preparedFiles += PendingFile(
                id = id,
                fileName = fileName,
                fileType = fileType,
                size = size,
                token = token
            )
        }
        if (preparedFiles.isEmpty()) {
            return textResponse(NanoHTTPD.Response.Status.NO_CONTENT, "")
        }
        val sessionId = UUID.randomUUID().toString()
        val decision = runCatching {
            requestIncomingDecision(
                LocalSendIncomingRequest(
                    requestId = sessionId,
                    senderAlias = senderAlias,
                    senderIp = senderIp,
                    fileCount = preparedFiles.size,
                    totalSize = preparedFiles.sumOf { it.size.coerceAtLeast(0L) },
                    firstFileName = preparedFiles.first().fileName
                )
            )
        }.getOrElse { LocalSendIncomingDecision.REJECT }
        when (decision) {
            LocalSendIncomingDecision.BUSY -> {
                return textResponse(NanoHTTPD.Response.Status.CONFLICT, "receiver_busy")
            }
            LocalSendIncomingDecision.REJECT -> {
                return textResponse(NanoHTTPD.Response.Status.FORBIDDEN, "rejected")
            }
            LocalSendIncomingDecision.ACCEPT -> Unit
        }

        synchronized(this) {
            val active = activeSessionId
            if (!active.isNullOrBlank() && sessions.containsKey(active)) {
                return textResponse(NanoHTTPD.Response.Status.CONFLICT, "receiver_busy")
            }
            val tokenMap = JSONObject()
            val pendingFiles = mutableMapOf<String, PendingFile>()
            preparedFiles.forEach { pending ->
                pendingFiles[pending.id] = pending
                tokenMap.put(pending.id, pending.token)
                onReceiveEvent(
                    LocalSendReceiveEvent(
                        stage = LocalSendReceiveStage.STARTED,
                        sessionId = sessionId,
                        fileId = pending.id,
                        fileName = pending.fileName,
                        fileSize = pending.size,
                        senderAlias = senderAlias,
                        senderIp = senderIp
                    )
                )
            }
            sessions[sessionId] = PendingSession(
                sessionId = sessionId,
                senderAlias = senderAlias,
                senderIp = senderIp,
                files = pendingFiles
            )
            activeSessionId = sessionId
            return jsonResponse(
                NanoHTTPD.Response.Status.OK,
                JSONObject().put("sessionId", sessionId).put("files", tokenMap).toString()
            )
        }
    }

    private fun handleUpload(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val sessionId = queryParam(session, "sessionId")
        val fileId = queryParam(session, "fileId")
        val token = queryParam(session, "token")
        if (sessionId.isBlank() || fileId.isBlank() || token.isBlank()) {
            return textResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "missing_query")
        }
        val pendingSession = sessions[sessionId]
            ?: return textResponse(NanoHTTPD.Response.Status.NOT_FOUND, "session_not_found")
        val pendingFile = pendingSession.files[fileId]
            ?: return textResponse(NanoHTTPD.Response.Status.NOT_FOUND, "file_not_found")
        if (pendingFile.token != token) {
            return textResponse(NanoHTTPD.Response.Status.FORBIDDEN, "invalid_token")
        }
        val senderIp = session.headers["remote-addr"].orEmpty().trim()
        if (pendingSession.senderIp.isNotBlank() && senderIp.isNotBlank() && pendingSession.senderIp != senderIp) {
            return textResponse(NanoHTTPD.Response.Status.FORBIDDEN, "invalid_sender")
        }

        val downloadDir = FileDownloadManager.getDownloadDirectory(appContext).apply { mkdirs() }
        val outputFile = uniqueTargetFile(downloadDir, sanitizeFileName(pendingFile.fileName))
        val totalBytesHint = pendingFile.size.coerceAtLeast(0L)
        var lastProgressAt = 0L
        var lastProgressBytes = 0L
        val written = runCatching {
            outputFile.outputStream().use { output ->
                writeRequestBodyToOutput(session, output) { bytesWritten ->
                    val safeBytes = bytesWritten.coerceAtLeast(0L)
                    val now = System.currentTimeMillis()
                    val enoughTime = now - lastProgressAt >= 220L
                    val enoughBytes = safeBytes - lastProgressBytes >= 128L * 1024L
                    val reachingEnd = totalBytesHint > 0L && safeBytes >= totalBytesHint
                    if (enoughTime || enoughBytes || reachingEnd) {
                        lastProgressAt = now
                        lastProgressBytes = safeBytes
                        onReceiveEvent(
                            LocalSendReceiveEvent(
                                stage = LocalSendReceiveStage.PROGRESS,
                                sessionId = sessionId,
                                fileId = fileId,
                                fileName = pendingFile.fileName,
                                fileSize = totalBytesHint,
                                senderAlias = pendingSession.senderAlias,
                                senderIp = pendingSession.senderIp,
                                receivedBytes = safeBytes
                            )
                        )
                    }
                }
            }
        }.getOrElse {
            onReceiveEvent(
                LocalSendReceiveEvent(
                    stage = LocalSendReceiveStage.FAILED,
                    sessionId = sessionId,
                    fileId = fileId,
                    fileName = pendingFile.fileName,
                    fileSize = pendingFile.size,
                    senderAlias = pendingSession.senderAlias,
                    senderIp = pendingSession.senderIp,
                    message = "write_failed"
                )
            )
            return textResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "write_failed")
        }
        if (written <= 0L && pendingFile.size > 0L) {
            onReceiveEvent(
                LocalSendReceiveEvent(
                    stage = LocalSendReceiveStage.FAILED,
                    sessionId = sessionId,
                    fileId = fileId,
                    fileName = pendingFile.fileName,
                    fileSize = pendingFile.size,
                    senderAlias = pendingSession.senderAlias,
                    senderIp = pendingSession.senderIp,
                    message = "empty_body"
                )
            )
            runCatching { outputFile.delete() }
            return textResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "empty_body")
        }
        if (pendingFile.size > 0L && written != pendingFile.size) {
            onReceiveEvent(
                LocalSendReceiveEvent(
                    stage = LocalSendReceiveStage.FAILED,
                    sessionId = sessionId,
                    fileId = fileId,
                    fileName = pendingFile.fileName,
                    fileSize = pendingFile.size,
                    senderAlias = pendingSession.senderAlias,
                    senderIp = pendingSession.senderIp,
                    message = "size_mismatch"
                )
            )
            runCatching { outputFile.delete() }
            return textResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "size_mismatch")
        }
        onReceiveEvent(
            LocalSendReceiveEvent(
                stage = LocalSendReceiveStage.PROGRESS,
                sessionId = sessionId,
                fileId = fileId,
                fileName = pendingFile.fileName,
                fileSize = if (totalBytesHint > 0L) totalBytesHint else written.coerceAtLeast(0L),
                senderAlias = pendingSession.senderAlias,
                senderIp = pendingSession.senderIp,
                receivedBytes = written.coerceAtLeast(0L)
            )
        )

        pendingFile.done = true
        val finalSize = outputFile.length().coerceAtLeast(written).coerceAtLeast(pendingFile.size)
        runCatching {
            DownloadHistoryManager(appContext).addRecord(
                DownloadRecord(
                    fileName = outputFile.name,
                    filePath = outputFile.absolutePath,
                    fileSize = finalSize,
                    sourceUrl = "localsend://${pendingSession.senderIp.ifBlank { "unknown" }}"
                )
            )
        }

        onReceiveEvent(
            LocalSendReceiveEvent(
                stage = LocalSendReceiveStage.COMPLETED,
                sessionId = sessionId,
                fileId = fileId,
                fileName = outputFile.name,
                fileSize = finalSize,
                localPath = outputFile.absolutePath,
                senderAlias = pendingSession.senderAlias,
                senderIp = pendingSession.senderIp
            )
        )

        if (pendingSession.files.values.all { it.done }) {
            sessions.remove(sessionId)
            if (activeSessionId == sessionId) {
                activeSessionId = null
            }
        }
        return textResponse(NanoHTTPD.Response.Status.OK, "")
    }

    private fun handleCancel(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val sessionId = queryParam(session, "sessionId")
        if (sessionId.isBlank()) {
            return textResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "missing_session_id")
        }
        val removed = sessions.remove(sessionId)
        if (activeSessionId == sessionId) {
            activeSessionId = null
        }
        if (removed != null) {
            onReceiveEvent(
                LocalSendReceiveEvent(
                    stage = LocalSendReceiveStage.CANCELLED,
                    sessionId = sessionId,
                    senderAlias = removed.senderAlias,
                    senderIp = removed.senderIp,
                    message = "sender_cancelled"
                )
            )
        }
        return textResponse(NanoHTTPD.Response.Status.OK, "")
    }

    private fun parseJsonBody(session: NanoHTTPD.IHTTPSession): JSONObject? {
        val files = HashMap<String, String>()
        return runCatching {
            session.parseBody(files)
            val postDataRef = files["postData"]?.trim().orEmpty()
            val postData = when {
                postDataRef.isBlank() -> ""
                else -> {
                    val file = File(postDataRef)
                    if (file.exists() && file.isFile) {
                        file.readText(Charsets.UTF_8)
                    } else {
                        postDataRef
                    }
                }
            }
            if (postData.isBlank()) JSONObject() else JSONObject(postData)
        }.getOrNull()
    }

    private fun queryParam(session: NanoHTTPD.IHTTPSession, key: String): String {
        return session.parameters[key]?.firstOrNull()?.trim().orEmpty()
    }

    private fun writeRequestBodyToOutput(
        session: NanoHTTPD.IHTTPSession,
        output: OutputStream,
        onProgress: (Long) -> Unit = {}
    ): Long {
        val headers = session.headers
        val transferEncoding = headers["transfer-encoding"].orEmpty().lowercase()
        val input = session.inputStream
        return when {
            "chunked" in transferEncoding -> copyChunked(input, output, onProgress)
            else -> {
                val contentLength = headers["content-length"]?.trim()?.toLongOrNull()
                if (contentLength != null && contentLength >= 0L) {
                    copyFixedLength(input, output, contentLength, onProgress)
                } else {
                    copyUntilEof(input, output, onProgress)
                }
            }
        }
    }

    private fun copyFixedLength(
        input: java.io.InputStream,
        output: OutputStream,
        length: Long,
        onProgress: (Long) -> Unit = {}
    ): Long {
        if (length <= 0L) return 0L
        val buffer = ByteArray(64 * 1024)
        var remaining = length
        var total = 0L
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw IOException("unexpected_eof_body")
            output.write(buffer, 0, read)
            remaining -= read.toLong()
            total += read.toLong()
            onProgress(total)
        }
        output.flush()
        return total
    }

    private fun copyUntilEof(
        input: java.io.InputStream,
        output: OutputStream,
        onProgress: (Long) -> Unit = {}
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read.toLong()
            onProgress(total)
        }
        output.flush()
        return total
    }

    private fun copyChunked(
        input: java.io.InputStream,
        output: OutputStream,
        onProgress: (Long) -> Unit = {}
    ): Long {
        var total = 0L
        while (true) {
            val line = readAsciiLine(input)?.trim() ?: throw IOException("unexpected_eof_chunk_header")
            if (line.isEmpty()) continue
            val sizeHex = line.substringBefore(';').trim()
            val chunkSize = sizeHex.toLongOrNull(16) ?: throw IOException("invalid_chunk_size")
            if (chunkSize <= 0L) {
                // consume trailer headers
                while (true) {
                    val trailer = readAsciiLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            var remaining = chunkSize
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0L) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) throw IOException("unexpected_eof_chunk")
                output.write(buffer, 0, read)
                remaining -= read.toLong()
                total += read.toLong()
                onProgress(total)
            }
            val crlf = ByteArray(2)
            if (!readExact(input, crlf, 2) || crlf[0] != '\r'.code.toByte() || crlf[1] != '\n'.code.toByte()) {
                throw IOException("invalid_chunk_terminator")
            }
        }
        output.flush()
        return total
    }

    private fun readAsciiLine(input: java.io.InputStream): String? {
        val out = StringBuilder()
        var sawAny = false
        while (true) {
            val next = input.read()
            if (next < 0) return if (sawAny) out.toString() else null
            if (next == '\r'.code) {
                val lf = input.read()
                if (lf == '\n'.code) break
                if (lf >= 0) {
                    sawAny = true
                    out.append(next.toChar())
                    out.append(lf.toChar())
                } else {
                    sawAny = true
                    out.append(next.toChar())
                    break
                }
            } else if (next == '\n'.code) {
                break
            } else {
                sawAny = true
                out.append(next.toChar())
            }
        }
        return out.toString()
    }

    private fun readExact(input: java.io.InputStream, buffer: ByteArray, len: Int): Boolean {
        var offset = 0
        while (offset < len) {
            val read = input.read(buffer, offset, len - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }

    private fun jsonResponse(status: NanoHTTPD.Response.Status, body: String): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", body)
    }

    private fun textResponse(status: NanoHTTPD.Response.Status, body: String): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", body)
    }

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim().ifBlank { "localsend_${System.currentTimeMillis()}" }
        return trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun uniqueTargetFile(dir: File, fileName: String): File {
        val first = File(dir, fileName)
        if (!first.exists()) return first
        val base = fileName.substringBeforeLast(".", fileName)
        val ext = fileName.substringAfterLast(".", "").let { if (it.isBlank()) "" else ".${it}" }
        var index = 1
        while (true) {
            val candidate = File(dir, "${base}_$index$ext")
            if (!candidate.exists()) return candidate
            index += 1
        }
    }
}

data class LocalSendSendFile(
    val id: String,
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val size: Long
)

data class LocalSendSendProgress(
    val progress: Float,
    val sentBytes: Long,
    val totalBytes: Long,
    val message: String
)

enum class LocalSendReceiveStage {
    STARTED,
    PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class LocalSendReceiveEvent(
    val stage: LocalSendReceiveStage,
    val sessionId: String,
    val fileId: String = "",
    val fileName: String = "",
    val fileSize: Long = 0L,
    val receivedBytes: Long = 0L,
    val localPath: String = "",
    val senderAlias: String = "",
    val senderIp: String = "",
    val message: String = ""
)

object LocalSendRuntimeBridge {
    @Volatile
    private var runtime: LocalSendRuntime? = null

    private fun runningRuntime(): LocalSendRuntime? {
        val current = runtime ?: return null
        if (current.isRunning()) {
            return current
        }
        if (runtime === current) {
            runtime = null
        }
        return null
    }

    fun attach(newRuntime: LocalSendRuntime) {
        runtime = newRuntime
    }

    fun detach(target: LocalSendRuntime) {
        if (runtime === target) {
            runtime = null
        }
    }

    fun announceNow() {
        runningRuntime()?.announceNow()
    }

    fun snapshotPeers(): List<LanNeighborPeer> {
        return runningRuntime()?.snapshotPeers().orEmpty()
    }

    fun probePeer(targetIp: String): LanNeighborPeer? {
        return runningRuntime()?.probePeer(targetIp)
    }
}

object LocalSendProtocol {
    const val LOCALSEND_PORT = 53317
    private const val DEVICE_TYPE = "mobile"
    private const val PROTOCOL_VERSION = "2.1"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val httpsInsecureClient: OkHttpClient by lazy {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        val verifier = HostnameVerifier { _, _ -> true }
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(verifier)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private fun clientForScheme(scheme: String): OkHttpClient {
        return if (scheme.equals("https", ignoreCase = true)) {
            httpsInsecureClient
        } else {
            httpClient
        }
    }

    private fun looksLikeMojibake(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.contains('\uFFFD')) return true
        return text.contains("Ã") ||
            text.contains("Â") ||
            text.contains("æ") ||
            text.contains("å") ||
            text.contains("ç")
    }

    private fun looksLikeUnknownName(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return true
        val questionCount = trimmed.count { it == '?' || it == '？' || it == '\uFFFD' }
        if (questionCount <= 0) return false
        val visibleCount = trimmed.count { !it.isWhitespace() }
        if (visibleCount <= 0) return true
        val normalized = trimmed
            .replace("?", "")
            .replace("？", "")
            .replace("\uFFFD", "")
            .replace(Regex("[\\s._-]"), "")
        if (normalized.isBlank()) return true
        return questionCount * 2 >= visibleCount
    }

    private fun mojibakeScore(text: String): Int {
        if (text.isBlank()) return 0
        var score = 0
        if (text.contains('\uFFFD')) score += 8
        listOf("Ã", "Â", "æ", "å", "ç").forEach { token ->
            score += text.split(token).size - 1
        }
        return score
    }

    private fun normalizeDisplayText(raw: String, fallback: String): String {
        val trimmed = raw.trim().replace("\u0000", "")
        if (trimmed.isBlank()) return fallback
        if (!looksLikeMojibake(trimmed)) return trimmed
        val repaired = runCatching {
            String(trimmed.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8).trim()
        }.getOrDefault(trimmed)
        if (repaired.isBlank()) return fallback
        return if (mojibakeScore(repaired) < mojibakeScore(trimmed)) repaired else trimmed
    }

    private fun pinKeyForPeer(peer: LanNeighborPeer): String {
        val raw = peer.localSendFingerprint.ifBlank {
            "${peer.ip.lowercase()}|${peer.localSendPort}|${peer.localSendProtocol.lowercase()}"
        }
        val hash = UUID.nameUUIDFromBytes(raw.toByteArray(Charsets.UTF_8)).toString()
        return LOCALSEND_PIN_KEY_PREFIX + hash
    }

    fun getSavedPin(context: Context, peer: LanNeighborPeer): String? {
        val prefs = context.getSharedPreferences(LOCALSEND_PIN_PREFS, Context.MODE_PRIVATE)
        return prefs.getString(pinKeyForPeer(peer), null)?.trim()?.takeIf { it.isNotBlank() }
    }

    fun savePin(context: Context, peer: LanNeighborPeer, pin: String?) {
        val prefs = context.getSharedPreferences(LOCALSEND_PIN_PREFS, Context.MODE_PRIVATE)
        val key = pinKeyForPeer(peer)
        val normalized = pin?.trim().orEmpty()
        if (normalized.isBlank()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, normalized).apply()
        }
    }

    fun buildDeviceInfoJson(
        context: Context,
        port: Int,
        protocol: String = "http",
        announce: Boolean? = null
    ): JSONObject {
        val obj = JSONObject()
            .put("alias", buildLocalNeighborName())
            .put("version", PROTOCOL_VERSION)
            .put("deviceModel", Build.MODEL?.trim().orEmpty().ifBlank { "Android" })
            .put("deviceType", DEVICE_TYPE)
            .put("fingerprint", getOrCreateFingerprint(context))
            .put("port", port.coerceIn(1, 65535))
            .put("protocol", if (protocol.equals("https", true)) "https" else "http")
            .put("download", false)
        if (announce != null) {
            obj.put("announce", announce)
        }
        return obj
    }

    fun parsePeer(
        json: JSONObject,
        fallbackIp: String,
        fallbackPort: Int = LOCALSEND_PORT,
        fallbackProtocol: String = "http"
    ): LocalSendPeer? {
        val ip = fallbackIp.trim()
        if (ip.isBlank()) return null
        val deviceModel = normalizeDisplayText(
            raw = json.optString("deviceModel"),
            fallback = ""
        )
        val aliasCandidate = normalizeDisplayText(
            raw = json.optString("alias"),
            fallback = ""
        )
        val alias = when {
            aliasCandidate.isBlank() -> deviceModel.ifBlank { "LocalSend device" }
            looksLikeUnknownName(aliasCandidate) -> deviceModel.takeIf { !looksLikeUnknownName(it) && it.isNotBlank() }
                ?: "LocalSend device"
            else -> aliasCandidate
        }
        val protocol = json.optString("protocol")
            .trim()
            .ifBlank { fallbackProtocol.trim() }
            .lowercase()
            .let { if (it == "https") "https" else "http" }
        val port = json.optInt("port", fallbackPort).coerceIn(1, 65535)
        val fingerprint = json.optString("fingerprint")
            .trim()
            .ifBlank { "${ip}:${port}:${protocol}" }
        return LocalSendPeer(
            ip = ip,
            alias = alias,
            deviceModel = if (looksLikeUnknownName(deviceModel)) "" else deviceModel,
            deviceType = json.optString("deviceType").trim(),
            fingerprint = fingerprint,
            port = port,
            protocol = protocol,
            download = json.optBoolean("download", false),
            lastSeenAt = System.currentTimeMillis()
        )
    }

    fun sendRegisterAndParsePeer(
        context: Context,
        targetIp: String,
        targetPort: Int,
        targetProtocol: String,
        localPort: Int
    ): LocalSendPeer? {
        val ip = targetIp.trim()
        if (ip.isBlank()) return null
        val scheme = if (targetProtocol.equals("https", true)) "https" else "http"
        val url = "$scheme://${NetworkUtils.formatHostForUrl(ip)}:${targetPort.coerceIn(1, 65535)}$LOCALSEND_API_PREFIX/register"
        val payload = buildDeviceInfoJson(
            context = context,
            port = localPort,
            protocol = "http",
            announce = false
        ).toString()
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val body = runCatching {
            clientForScheme(scheme).newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string().orEmpty()
            }
        }.getOrNull() ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return parsePeer(
            json = json,
            fallbackIp = ip,
            fallbackPort = targetPort.coerceIn(1, 65535),
            fallbackProtocol = scheme
        )
    }

    suspend fun sendFilesToPeer(
        context: Context,
        peer: LanNeighborPeer,
        files: List<LocalSendSendFile>,
        pin: String? = null,
        isCancelled: () -> Boolean = { false },
        onProgress: (LocalSendSendProgress) -> Unit = {}
    ): Result<Unit> {
        val targetIp = peer.ip.trim()
        if (targetIp.isBlank()) return Result.failure(IllegalArgumentException("target_ip_empty"))
        if (files.isEmpty()) return Result.failure(IllegalArgumentException("files_empty"))

        val scheme = if (peer.localSendProtocol.equals("https", true)) "https" else "http"
        val host = NetworkUtils.formatHostForUrl(targetIp)
        val port = peer.localSendPort.coerceIn(1, 65535)
        val totalBytes = files.sumOf { it.size.coerceAtLeast(0L) }.coerceAtLeast(1L)
        val client = if (scheme == "https") {
            httpsInsecureClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        } else {
            httpClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        onProgress(
            LocalSendSendProgress(
                progress = 0f,
                sentBytes = 0L,
                totalBytes = totalBytes,
                message = "Preparing LocalSend session..."
            )
        )

        val prepared = prepareUpload(
            context = context,
            client = client,
            scheme = scheme,
            host = host,
            port = port,
            files = files,
            pin = pin
        ).getOrElse { return Result.failure(it) }

        var finishedBytes = 0L
        files.forEachIndexed { index, file ->
            if (isCancelled()) return Result.failure(IOException("SEND_CANCELLED"))
            val token = prepared.tokens[file.id]
                ?: return Result.failure(IOException("missing_token:${file.id}"))
            onProgress(
                LocalSendSendProgress(
                    progress = (finishedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f),
                    sentBytes = finishedBytes,
                    totalBytes = totalBytes,
                    message = "Uploading ${index + 1}/${files.size}: ${file.fileName}"
                )
            )
            val result = uploadSingleFile(
                context = context,
                client = client,
                scheme = scheme,
                host = host,
                port = port,
                sessionId = prepared.sessionId,
                file = file,
                token = token,
                completedBytesBefore = finishedBytes,
                totalBytes = totalBytes,
                isCancelled = isCancelled,
                onProgress = onProgress
            )
            if (result.isFailure) {
                runCatching {
                    sendCancel(client, scheme, host, port, prepared.sessionId)
                }
                return Result.failure(result.exceptionOrNull() ?: IOException("upload_failed"))
            }
            finishedBytes += file.size.coerceAtLeast(0L)
        }

        onProgress(
            LocalSendSendProgress(
                progress = 1f,
                sentBytes = finishedBytes,
                totalBytes = totalBytes,
                message = "Upload completed"
            )
        )
        return Result.success(Unit)
    }

    private data class PreparedUpload(
        val sessionId: String,
        val tokens: Map<String, String>
    )

    private fun prepareUpload(
        context: Context,
        client: OkHttpClient,
        scheme: String,
        host: String,
        port: Int,
        files: List<LocalSendSendFile>,
        pin: String?
    ): Result<PreparedUpload> {
        val filesJson = JSONObject()
        files.forEach { file ->
            filesJson.put(
                file.id,
                JSONObject()
                    .put("id", file.id)
                    .put("fileName", file.fileName)
                    .put("size", file.size.coerceAtLeast(0L))
                    .put("fileType", file.mimeType.ifBlank { "application/octet-stream" })
            )
        }
        val payload = JSONObject()
            .put("info", buildDeviceInfoJson(context, LOCALSEND_PORT, protocol = "http", announce = false))
            .put("files", filesJson)
            .toString()
        val pinQuery = pin?.trim().orEmpty()
        val encodedPin = if (pinQuery.isNotBlank()) {
            URLEncoder.encode(pinQuery, Charsets.UTF_8.name())
        } else {
            ""
        }
        val url = if (encodedPin.isNotBlank()) {
            "$scheme://$host:$port$LOCALSEND_API_PREFIX/prepare-upload?pin=$encodedPin"
        } else {
            "$scheme://$host:$port$LOCALSEND_API_PREFIX/prepare-upload"
        }
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    throw IOException("prepare_upload_failed:${response.code}:${text.take(200)}")
                }
                val text = response.body?.string().orEmpty()
                val json = JSONObject(text)
                val sessionId = json.optString("sessionId").trim()
                if (sessionId.isBlank()) throw IOException("missing_session_id")
                val tokenObj = json.optJSONObject("files") ?: JSONObject()
                val tokens = buildMap {
                    val keys = tokenObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val token = tokenObj.optString(key).trim()
                        if (token.isNotBlank()) put(key, token)
                    }
                }
                PreparedUpload(sessionId = sessionId, tokens = tokens)
            }
        }
    }

    private fun uploadSingleFile(
        context: Context,
        client: OkHttpClient,
        scheme: String,
        host: String,
        port: Int,
        sessionId: String,
        file: LocalSendSendFile,
        token: String,
        completedBytesBefore: Long,
        totalBytes: Long,
        isCancelled: () -> Boolean,
        onProgress: (LocalSendSendProgress) -> Unit
    ): Result<Unit> {
        val url = "$scheme://$host:$port$LOCALSEND_API_PREFIX/upload" +
            "?sessionId=$sessionId&fileId=${file.id}&token=$token"
        val reqBody = ContentUriRequestBody(
            context = context,
            uri = file.uri,
            mimeType = file.mimeType,
            length = file.size,
            onBytes = { current ->
                val sent = (completedBytesBefore + current).coerceAtMost(totalBytes)
                onProgress(
                    LocalSendSendProgress(
                        progress = (sent.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f),
                        sentBytes = sent,
                        totalBytes = totalBytes,
                        message = "Uploading ${file.fileName}"
                    )
                )
            },
            isCancelled = isCancelled
        )
        val request = Request.Builder()
            .url(url)
            .post(reqBody)
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    throw IOException("upload_failed:${response.code}:${text.take(200)}")
                }
            }
        }
    }

    private fun sendCancel(
        client: OkHttpClient,
        scheme: String,
        host: String,
        port: Int,
        sessionId: String
    ) {
        val url = "$scheme://$host:$port$LOCALSEND_API_PREFIX/cancel?sessionId=$sessionId"
        val request = Request.Builder()
            .url(url)
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        runCatching { client.newCall(request).execute().close() }
    }

    private fun getOrCreateFingerprint(context: Context): String {
        val prefs = context.getSharedPreferences(LOCALSEND_PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(LOCALSEND_FP_KEY, null)?.trim().orEmpty()
        if (existing.isNotBlank()) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(LOCALSEND_FP_KEY, generated).apply()
        return generated
    }

    private class ContentUriRequestBody(
        private val context: Context,
        private val uri: Uri,
        private val mimeType: String,
        private val length: Long,
        private val onBytes: (Long) -> Unit,
        private val isCancelled: () -> Boolean
    ) : RequestBody() {
        override fun contentType() = mimeType.ifBlank { "application/octet-stream" }.toMediaTypeOrNull()

        override fun contentLength(): Long = length.coerceAtLeast(0L)

        override fun writeTo(sink: BufferedSink) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    if (isCancelled()) throw IOException("SEND_CANCELLED")
                    val read = input.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    sent += read.toLong()
                    onBytes(sent)
                }
            } ?: throw IOException("cannot_open_input_stream")
        }
    }
}
