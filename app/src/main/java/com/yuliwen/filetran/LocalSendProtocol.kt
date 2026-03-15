package com.yuliwen.filetran

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.Call
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
import java.nio.charset.Charset
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
private const val LOCALSEND_DETAIL_REFRESH_INTERVAL_MS = 12_000L
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

class LocalSendSendControl {
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var activeCall: Call? = null

    fun isCancelled(): Boolean = cancelled.get()

    fun cancel(force: Boolean = true) {
        cancelled.set(true)
        if (force) {
            synchronized(this) {
                activeCall?.cancel()
            }
        }
    }

    internal fun bind(call: Call) {
        synchronized(this) {
            activeCall = call
            if (cancelled.get()) {
                call.cancel()
            }
        }
    }

    internal fun release(call: Call) {
        synchronized(this) {
            if (activeCall === call) {
                activeCall = null
            }
        }
    }
}

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
    private val peerDetailRefreshAt = ConcurrentHashMap<String, Long>()
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
        peerDetailRefreshAt.clear()
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
                    payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
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
        payload: ByteArray,
        senderIp: String,
        group: InetAddress,
        socket: MulticastSocket
    ) {
        if (payload.isEmpty() || senderIp.isBlank()) return
        val json = LocalSendProtocol.parseJsonObject(payload) ?: return
        val peer = LocalSendProtocol.parsePeer(json, senderIp) ?: return
        if (peer.fingerprint == selfFingerprint) return
        upsertPeer(peer)
        val storedPeer = peers["${peer.fingerprint}|${peer.ip}"] ?: peer
        if (json.optBoolean("announce", false)) {
            sendUdpAnnounce(socket, group, announce = false)
        }
        maybeRefreshPeerDetails(storedPeer, force = json.optBoolean("announce", false))
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
        val key = "${peer.fingerprint}|${peer.ip}"
        val existing = peers[key]
        val merged = LocalSendProtocol.mergePeer(existing, peer)
            .copy(lastSeenAt = System.currentTimeMillis())
        peers[key] = merged
        publishPeers()
    }

    private fun maybeRefreshPeerDetails(peer: LocalSendPeer, force: Boolean = false) {
        if (!force && !LocalSendProtocol.needsPeerDetailsRefresh(peer)) return
        val key = "${peer.fingerprint}|${peer.ip}"
        val now = System.currentTimeMillis()
        val previous = peerDetailRefreshAt[key]
        if (!force && previous != null && now - previous < LOCALSEND_DETAIL_REFRESH_INTERVAL_MS) {
            return
        }
        peerDetailRefreshAt[key] = now
        Thread {
            runCatching {
                val refreshedPeer = LocalSendProtocol.refreshPeerDetails(
                    context = appContext,
                    currentPeer = peer,
                    localPort = httpPort
                ) ?: return@runCatching
                upsertPeer(refreshedPeer)
                if (refreshedPeer.alias != peer.alias || refreshedPeer.deviceModel != peer.deviceModel) {
                    Log.i(
                        "LocalSendRuntime",
                        "[$ownerTag] refreshed peer ip=${peer.ip} alias=${refreshedPeer.alias} model=${refreshedPeer.deviceModel}"
                    )
                }
            }
        }.apply {
            name = "LocalSendPeerRefresh-$ownerTag"
            isDaemon = true
            start()
        }
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
        val previewText: String? = null,
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

            method == NanoHTTPD.Method.GET && path == "$LOCALSEND_API_PREFIX/info" -> {
                handleInfo(session)
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
            val storedPeer = peers["${peer.fingerprint}|${peer.ip}"] ?: peer
            maybeRefreshPeerDetails(storedPeer, force = body.optBoolean("announce", false))
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

    private fun handleInfo(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val requesterFingerprint = queryParam(session, "fingerprint")
        if (requesterFingerprint.isNotBlank() && requesterFingerprint == selfFingerprint) {
            return textResponse(NanoHTTPD.Response.Status.PRECONDITION_FAILED, "Self-discovered")
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
        val senderAlias = LocalSendProtocol.normalizeDisplayText(
            raw = info.optString("alias"),
            fallback = "LocalSend sender"
        ).takeUnless { LocalSendProtocol.looksLikeUnknownName(it) } ?: "LocalSend sender"
        val preparedFiles = mutableListOf<PendingFile>()
        val keys = filesObj.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val file = filesObj.optJSONObject(id) ?: continue
            val fileName = file.optString("fileName").trim().ifBlank { "localsend_${System.currentTimeMillis()}" }
            val fileType = file.optString("fileType").trim().ifBlank { "application/octet-stream" }
            val size = file.optLong("size", -1L).coerceAtLeast(0L)
            val previewText = file.optString("preview").trim().ifBlank { null }
            val token = UUID.randomUUID().toString().replace("-", "")
            preparedFiles += PendingFile(
                id = id,
                fileName = fileName,
                fileType = fileType,
                size = size,
                token = token,
                previewText = previewText
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

        val inlineMessage = detectInlineMessage(preparedFiles)
        if (inlineMessage != null) {
            onReceiveEvent(
                LocalSendReceiveEvent(
                    stage = LocalSendReceiveStage.MESSAGE_RECEIVED,
                    sessionId = sessionId,
                    fileId = inlineMessage.id,
                    fileName = inlineMessage.fileName,
                    fileSize = inlineMessage.size,
                    senderAlias = senderAlias,
                    senderIp = senderIp,
                    mimeType = inlineMessage.fileType,
                    textContent = inlineMessage.previewText.orEmpty()
                )
            )
            return textResponse(NanoHTTPD.Response.Status.NO_CONTENT, "")
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
                        senderIp = senderIp,
                        mimeType = pending.fileType
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
        val outputFile = buildTargetFile(downloadDir, pendingFile.fileName)
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
                                mimeType = pendingFile.fileType,
                                receivedBytes = safeBytes
                            )
                        )
                    }
                }
            }
        }.getOrElse {
            return failPendingTransfer(
                sessionId = sessionId,
                pendingSession = pendingSession,
                pendingFile = pendingFile,
                status = NanoHTTPD.Response.Status.INTERNAL_ERROR,
                code = classifyUploadFailure(it),
                partialFile = outputFile
            )
        }
        if (written <= 0L && pendingFile.size > 0L) {
            return failPendingTransfer(
                sessionId = sessionId,
                pendingSession = pendingSession,
                pendingFile = pendingFile,
                status = NanoHTTPD.Response.Status.BAD_REQUEST,
                code = "empty_body",
                partialFile = outputFile
            )
        }
        if (pendingFile.size > 0L && written != pendingFile.size) {
            return failPendingTransfer(
                sessionId = sessionId,
                pendingSession = pendingSession,
                pendingFile = pendingFile,
                status = NanoHTTPD.Response.Status.BAD_REQUEST,
                code = "size_mismatch",
                partialFile = outputFile
            )
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
                mimeType = pendingFile.fileType,
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
                senderIp = pendingSession.senderIp,
                mimeType = pendingFile.fileType
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

    private fun detectInlineMessage(files: List<PendingFile>): PendingFile? {
        val first = files.singleOrNull() ?: return null
        val preview = first.previewText?.trim().orEmpty()
        if (preview.isBlank()) return null
        return first.takeIf { it.fileType.lowercase().startsWith("text/") }
    }

    private fun classifyUploadFailure(error: Throwable): String {
        val text = buildString {
            append(error.message.orEmpty())
            generateSequence(error.cause) { it.cause }
                .forEach {
                    if (it.message?.isNotBlank() == true) {
                        append(' ')
                        append(it.message)
                    }
                }
        }.lowercase()
        return when {
            error is java.net.SocketTimeoutException -> "network_interrupted"
            "unexpected_eof" in text -> "network_interrupted"
            "connection reset" in text -> "network_interrupted"
            "broken pipe" in text -> "network_interrupted"
            "socket closed" in text -> "network_interrupted"
            "stream closed" in text -> "network_interrupted"
            "timeout" in text -> "network_interrupted"
            else -> "write_failed"
        }
    }

    private fun failPendingTransfer(
        sessionId: String,
        pendingSession: PendingSession,
        pendingFile: PendingFile,
        status: NanoHTTPD.Response.Status,
        code: String,
        partialFile: File? = null
    ): NanoHTTPD.Response {
        runCatching { partialFile?.delete() }
        sessions.remove(sessionId)
        if (activeSessionId == sessionId) {
            activeSessionId = null
        }
        onReceiveEvent(
            LocalSendReceiveEvent(
                stage = LocalSendReceiveStage.FAILED,
                sessionId = sessionId,
                fileId = pendingFile.id,
                fileName = pendingFile.fileName,
                fileSize = pendingFile.size,
                senderAlias = pendingSession.senderAlias,
                senderIp = pendingSession.senderIp,
                mimeType = pendingFile.fileType,
                message = code
            )
        )
        return textResponse(status, code)
    }

    private fun parseJsonBody(session: NanoHTTPD.IHTTPSession): JSONObject? {
        val files = HashMap<String, String>()
        return runCatching {
            session.parseBody(files)
            val postDataRef = files["postData"]?.trim().orEmpty()
            when {
                postDataRef.isBlank() -> JSONObject()
                else -> {
                    val file = File(postDataRef)
                    if (file.exists() && file.isFile) {
                        LocalSendProtocol.parseJsonObject(file.readBytes())
                            ?: JSONObject(file.readText(Charsets.UTF_8))
                    } else {
                        JSONObject(postDataRef)
                    }
                }
            }
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

    private fun sanitizePathSegment(name: String): String {
        val trimmed = name.trim().ifBlank { "localsend_${System.currentTimeMillis()}" }
        return trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun sanitizeRelativePath(name: String): List<String> {
        val normalized = name.replace('\\', '/')
        val parts = normalized.split('/')
            .mapNotNull { segment ->
                val trimmed = segment.trim()
                when {
                    trimmed.isBlank() -> null
                    trimmed == "." -> null
                    trimmed == ".." -> null
                    else -> sanitizePathSegment(trimmed)
                }
            }
        return if (parts.isEmpty()) {
            listOf(sanitizePathSegment(name))
        } else {
            parts
        }
    }

    private fun buildTargetFile(rootDir: File, relativeName: String): File {
        val pathParts = sanitizeRelativePath(relativeName)
        val parentDir = pathParts.dropLast(1).fold(rootDir) { current, part ->
            File(current, part).apply { mkdirs() }
        }
        return uniqueTargetFile(parentDir, pathParts.last())
    }

    private fun uniqueTargetFile(dir: File, fileName: String): File {
        val clean = sanitizePathSegment(fileName)
        val first = File(dir, clean)
        if (!first.exists()) return first
        val base = clean.substringBeforeLast(".", clean)
        val ext = clean.substringAfterLast(".", "").let { if (it.isBlank()) "" else ".${it}" }
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
    val uri: Uri? = null,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val inlineBytes: ByteArray? = null,
    val previewText: String? = null
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
    MESSAGE_RECEIVED,
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
    val mimeType: String = "",
    val textContent: String = "",
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
    private val WINDOWS_1252: Charset = runCatching {
        Charset.forName("windows-1252")
    }.getOrElse {
        Charsets.ISO_8859_1
    }
    private val JSON_CHARSETS: List<Charset> = buildList {
        add(Charsets.UTF_8)
        listOf(
            "GB18030",
            "GBK",
            "UTF-16",
            "UTF-16LE",
            "UTF-16BE",
            WINDOWS_1252.name(),
            Charsets.ISO_8859_1.name()
        ).forEach { name ->
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: return@forEach
            if (none { it.name().equals(charset.name(), ignoreCase = true) }) {
                add(charset)
            }
        }
    }

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

    private fun decodeJsonPayload(payload: ByteArray, charset: Charset): String? {
        val decoded = runCatching { String(payload, charset) }.getOrNull() ?: return null
        val normalized = decoded
            .removePrefix("\uFEFF")
            .trim()
        if (normalized.isBlank()) return null
        return normalized
    }

    private fun scoreDecodedDisplayText(
        raw: String,
        blankPenalty: Int,
        unknownPenalty: Int,
        normalizationPenalty: Int
    ): Int {
        val trimmed = raw
            .replace("\u0000", "")
            .trim()
            .replace(Regex("\\s+"), " ")
        if (trimmed.isBlank()) return blankPenalty

        var score = mojibakeScore(trimmed) * 4
        if (looksLikeMojibake(trimmed)) score += 24
        if (looksLikeUnknownName(trimmed)) score += unknownPenalty

        val normalized = normalizeDisplayText(trimmed, "")
        if (normalized.isBlank()) return score + blankPenalty
        if (normalized != trimmed) score += normalizationPenalty
        score += mojibakeScore(normalized)
        if (looksLikeUnknownName(normalized)) score += unknownPenalty / 2
        return score
    }

    private fun scoreDecodedJson(json: JSONObject): Int {
        var score = 0
        score += scoreDecodedDisplayText(
            raw = json.optString("alias"),
            blankPenalty = 120,
            unknownPenalty = 36,
            normalizationPenalty = 12
        )
        score += scoreDecodedDisplayText(
            raw = json.optString("deviceModel"),
            blankPenalty = 24,
            unknownPenalty = 8,
            normalizationPenalty = 4
        )
        if (json.optString("fingerprint").trim().isBlank()) {
            score += 6
        }
        val protocol = json.optString("protocol").trim().lowercase()
        if (protocol.isNotBlank() && protocol != "http" && protocol != "https") {
            score += 4
        }
        return score
    }

    private fun scorePeerAlias(alias: String, deviceModel: String): Int {
        val trimmedAlias = alias
            .replace("\u0000", "")
            .trim()
            .replace(Regex("\\s+"), " ")
        if (trimmedAlias.isBlank()) return 10_000

        var score = mojibakeScore(trimmedAlias) * 8
        if (looksLikeUnknownName(trimmedAlias)) score += 240
        if (looksLikeMojibake(trimmedAlias)) score += 40

        val trimmedModel = deviceModel
            .replace("\u0000", "")
            .trim()
            .replace(Regex("\\s+"), " ")
        if (trimmedModel.isNotBlank() && trimmedAlias.equals(trimmedModel, ignoreCase = true)) {
            score += 90
        }
        return score
    }

    private fun scorePeerModel(deviceModel: String): Int {
        val trimmed = deviceModel
            .replace("\u0000", "")
            .trim()
            .replace(Regex("\\s+"), " ")
        if (trimmed.isBlank()) return 10_000

        var score = mojibakeScore(trimmed) * 6
        if (looksLikeUnknownName(trimmed)) score += 60
        if (looksLikeMojibake(trimmed)) score += 20
        return score
    }

    internal fun mergePeer(existing: LocalSendPeer?, incoming: LocalSendPeer): LocalSendPeer {
        if (existing == null) return incoming

        val incomingAliasScore = scorePeerAlias(incoming.alias, incoming.deviceModel)
        val existingAliasScore = scorePeerAlias(existing.alias, existing.deviceModel)
        val preferIncomingAlias = incomingAliasScore <= existingAliasScore

        val incomingModelScore = scorePeerModel(incoming.deviceModel)
        val existingModelScore = scorePeerModel(existing.deviceModel)
        val preferIncomingModel = incomingModelScore <= existingModelScore

        return incoming.copy(
            alias = if (preferIncomingAlias) incoming.alias else existing.alias,
            deviceModel = if (preferIncomingModel) incoming.deviceModel else existing.deviceModel,
            deviceType = incoming.deviceType.ifBlank { existing.deviceType },
            fingerprint = incoming.fingerprint.ifBlank { existing.fingerprint },
            protocol = incoming.protocol.ifBlank { existing.protocol },
            port = incoming.port.takeIf { it in 1..65535 } ?: existing.port,
            download = incoming.download,
            lastSeenAt = maxOf(existing.lastSeenAt, incoming.lastSeenAt)
        )
    }

    internal fun needsPeerDetailsRefresh(peer: LocalSendPeer): Boolean {
        val alias = peer.alias
            .replace("\u0000", "")
            .trim()
            .replace(Regex("\\s+"), " ")
        if (alias.isBlank()) return true
        if (looksLikeUnknownName(alias)) return true

        val model = peer.deviceModel
            .replace("\u0000", "")
            .trim()
            .replace(Regex("\\s+"), " ")
        if (model.isNotBlank() && alias.equals(model, ignoreCase = true)) return true

        return false
    }

    private fun schemeCandidates(preferredScheme: String): List<String> {
        val primary = if (preferredScheme.equals("https", true)) "https" else "http"
        val secondary = if (primary == "https") "http" else "https"
        return listOf(primary, secondary)
    }

    private fun samePeerDetails(first: LocalSendPeer, second: LocalSendPeer): Boolean {
        return first.alias == second.alias &&
            first.deviceModel == second.deviceModel &&
            first.deviceType == second.deviceType &&
            first.port == second.port &&
            first.protocol == second.protocol &&
            first.download == second.download
    }

    internal fun parseJsonObject(payload: ByteArray): JSONObject? {
        if (payload.isEmpty()) return null

        data class Candidate(
            val json: JSONObject,
            val charset: Charset,
            val score: Int,
            val priority: Int
        )

        var best: Candidate? = null
        JSON_CHARSETS.forEachIndexed { index, charset ->
            val decoded = decodeJsonPayload(payload, charset) ?: return@forEachIndexed
            val json = runCatching { JSONObject(decoded) }.getOrNull() ?: return@forEachIndexed
            val score = scoreDecodedJson(json)
            val current = best
            if (
                current == null ||
                score < current.score ||
                (score == current.score && index < current.priority)
            ) {
                best = Candidate(
                    json = json,
                    charset = charset,
                    score = score,
                    priority = index
                )
            }
        }

        val candidate = best ?: return null
        if (!candidate.charset.name().equals(Charsets.UTF_8.name(), ignoreCase = true)) {
            runCatching {
                Log.i(
                    "LocalSendRuntime",
                    "parseJsonObject selected charset=${candidate.charset.name()} " +
                        "alias=${summarizeDisplayTextForLog(candidate.json.optString("alias"))} " +
                        "deviceModel=${summarizeDisplayTextForLog(candidate.json.optString("deviceModel"))}"
                )
            }
        }
        return candidate.json
    }

    internal fun looksLikeUnknownName(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return true
        val questionCount = trimmed.count { it == '?' || it == '\uFF1F' || it == '\uFFFD' }
        if (questionCount <= 0) return false
        val visibleCount = trimmed.count { !it.isWhitespace() }
        if (visibleCount <= 0) return true
        val normalized = trimmed
            .replace("?", "")
            .replace("\uFF1F", "")
            .replace("\uFFFD", "")
            .replace(Regex("[\\s._-]"), "")
        if (normalized.isBlank()) return true
        return questionCount * 2 >= visibleCount
    }

    private fun looksLikeMojibake(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.contains('\uFFFD')) return true
        var latin1Count = 0
        var c1Count = 0
        var mojibakeMarkerCount = 0
        text.forEach { ch ->
            when {
                ch.code in 0x0080..0x009F -> c1Count += 1
                ch.code in 0x00C0..0x00FF -> latin1Count += 1
            }
            if (
                ch == '\u00C2' ||
                ch == '\u00C3' ||
                ch == '\u00C5' ||
                ch == '\u00C6' ||
                ch == '\u00D0' ||
                ch == '\u00D1' ||
                ch == '\u00E2' ||
                ch == '\u00E3' ||
                ch == '\u00E5' ||
                ch == '\u00E6'
            ) {
                mojibakeMarkerCount += 1
            }
        }
        return c1Count > 0 || mojibakeMarkerCount >= 1 || latin1Count >= 3
    }

    private fun mojibakeScore(text: String): Int {
        if (text.isBlank()) return 0
        var score = 0
        text.forEach { ch ->
            when {
                ch == '\uFFFD' -> score += 12
                ch == '?' || ch == '\uFF1F' -> score += 2
                ch.code in 0x0080..0x009F -> score += 8
                ch.code in 0x00C0..0x00FF -> score += 1
            }
            if (
                ch == '\u00C2' ||
                ch == '\u00C3' ||
                ch == '\u00C5' ||
                ch == '\u00C6' ||
                ch == '\u00D0' ||
                ch == '\u00D1' ||
                ch == '\u00E2' ||
                ch == '\u00E3' ||
                ch == '\u00E5' ||
                ch == '\u00E6'
            ) {
                score += 3
            }
        }
        return score
    }

    private fun tryReDecodeAsUtf8(text: String, sourceCharset: Charset): String? {
        if (!sourceCharset.newEncoder().canEncode(text)) return null
        val repaired = runCatching {
            String(text.toByteArray(sourceCharset), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val normalized = repaired
            .replace("\u0000", "")
            .trim()
            .replace(Regex("\\s+"), " ")
        if (normalized.isBlank() || normalized == text) return null
        return normalized
    }

    internal fun normalizeDisplayText(raw: String, fallback: String): String {
        val trimmed = raw
            .replace("\u0000", "")
            .trim()
            .replace(Regex("\\s+"), " ")
        if (trimmed.isBlank()) return fallback
        if (!looksLikeMojibake(trimmed)) return trimmed

        var best = trimmed
        var bestScore = mojibakeScore(trimmed)

        listOf(Charsets.ISO_8859_1, WINDOWS_1252).forEach { sourceCharset ->
            var candidate = tryReDecodeAsUtf8(trimmed, sourceCharset)
            var depth = 0
            while (candidate != null && depth < 2) {
                val candidateScore = mojibakeScore(candidate)
                if (candidateScore < bestScore) {
                    best = candidate
                    bestScore = candidateScore
                }
                candidate = if (looksLikeMojibake(candidate)) {
                    tryReDecodeAsUtf8(candidate, sourceCharset)
                } else {
                    null
                }
                depth += 1
            }
        }

        return best.ifBlank { fallback }
    }

    private fun summarizeDisplayTextForLog(text: String): String {
        val normalized = text.replace("\u0000", "\\0")
        val visible = if (normalized.length > 32) {
            normalized.take(32) + "..."
        } else {
            normalized
        }
        val codepoints = text.take(12).toCharArray().joinToString(" ") { ch -> "U+%04X".format(ch.code) }
        return "\"$visible\" len=${text.length}${if (codepoints.isBlank()) "" else " $codepoints"}"
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
            .put("alias", buildLocalSendV2Name(context))
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
        val rawDeviceModel = json.optString("deviceModel")
        val rawAlias = json.optString("alias")
        val deviceModel = normalizeDisplayText(
            raw = rawDeviceModel,
            fallback = ""
        )
        val aliasCandidate = normalizeDisplayText(
            raw = rawAlias,
            fallback = ""
        )
        val alias = when {
            aliasCandidate.isBlank() -> deviceModel.ifBlank { "LocalSend device" }
            looksLikeUnknownName(aliasCandidate) -> deviceModel.takeIf { !looksLikeUnknownName(it) && it.isNotBlank() }
                ?: "LocalSend device"
            else -> aliasCandidate
        }
        if (!alias.equals(aliasCandidate) && aliasCandidate.isNotBlank()) {
            runCatching {
                Log.i(
                    "LocalSendRuntime",
                    "parsePeer fallback alias ip=$ip rawAlias=${summarizeDisplayTextForLog(rawAlias)} " +
                        "alias=${summarizeDisplayTextForLog(aliasCandidate)} rawModel=${summarizeDisplayTextForLog(rawDeviceModel)} " +
                        "model=${summarizeDisplayTextForLog(deviceModel)} final=${summarizeDisplayTextForLog(alias)}"
                )
            }
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
                response.body?.bytes()
            }
        }.getOrNull() ?: return null
        val json = parseJsonObject(body) ?: return null
        return parsePeer(
            json = json,
            fallbackIp = ip,
            fallbackPort = targetPort.coerceIn(1, 65535),
            fallbackProtocol = scheme
        )
    }

    fun fetchInfoAndParsePeer(
        context: Context,
        targetIp: String,
        targetPort: Int,
        targetProtocol: String
    ): LocalSendPeer? {
        val ip = targetIp.trim()
        if (ip.isBlank()) return null
        val scheme = if (targetProtocol.equals("https", true)) "https" else "http"
        val localFingerprint = getOrCreateFingerprint(context)
        val url = buildString {
            append(scheme)
            append("://")
            append(NetworkUtils.formatHostForUrl(ip))
            append(":")
            append(targetPort.coerceIn(1, 65535))
            append(LOCALSEND_API_PREFIX)
            append("/info?fingerprint=")
            append(URLEncoder.encode(localFingerprint, Charsets.UTF_8.name()))
        }
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        val body = runCatching {
            clientForScheme(scheme).newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.bytes()
            }
        }.getOrNull() ?: return null
        val json = parseJsonObject(body) ?: return null
        return parsePeer(
            json = json,
            fallbackIp = ip,
            fallbackPort = targetPort.coerceIn(1, 65535),
            fallbackProtocol = scheme
        )
    }

    internal fun refreshPeerDetails(
        context: Context,
        currentPeer: LocalSendPeer,
        localPort: Int
    ): LocalSendPeer? {
        var best = currentPeer
        var improved = false

        schemeCandidates(currentPeer.protocol).forEach { scheme ->
            val registerPeer = sendRegisterAndParsePeer(
                context = context,
                targetIp = currentPeer.ip,
                targetPort = currentPeer.port,
                targetProtocol = scheme,
                localPort = localPort
            )
            if (registerPeer != null) {
                val merged = mergePeer(best, registerPeer)
                if (!samePeerDetails(best, merged)) {
                    best = merged
                    improved = true
                }
                if (!needsPeerDetailsRefresh(best)) return best
            }

            val infoPeer = fetchInfoAndParsePeer(
                context = context,
                targetIp = currentPeer.ip,
                targetPort = currentPeer.port,
                targetProtocol = scheme
            )
            if (infoPeer != null) {
                val merged = mergePeer(best, infoPeer)
                if (!samePeerDetails(best, merged)) {
                    best = merged
                    improved = true
                }
                if (!needsPeerDetailsRefresh(best)) return best
            }
        }

        return best.takeIf { improved }
    }

    suspend fun sendFilesToPeer(
        context: Context,
        peer: LanNeighborPeer,
        files: List<LocalSendSendFile>,
        pin: String? = null,
        control: LocalSendSendControl? = null,
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

        if (isSendCancelled(control, isCancelled)) {
            return Result.failure(IOException("SEND_CANCELLED"))
        }

        val prepared = prepareUpload(
            context = context,
            client = client,
            scheme = scheme,
            host = host,
            port = port,
            files = files,
            pin = pin,
            control = control
        ).getOrElse { return Result.failure(it) }

        if (!prepared.uploadRequired) {
            onProgress(
                LocalSendSendProgress(
                    progress = 1f,
                    sentBytes = totalBytes,
                    totalBytes = totalBytes,
                    message = "LocalSend content delivered"
                )
            )
            return Result.success(Unit)
        }

        var finishedBytes = 0L
        files.forEachIndexed { index, file ->
            if (isSendCancelled(control, isCancelled)) return Result.failure(IOException("SEND_CANCELLED"))
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
                control = control,
                isCancelled = { isSendCancelled(control, isCancelled) },
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
        val tokens: Map<String, String>,
        val uploadRequired: Boolean
    )

    private fun isSendCancelled(control: LocalSendSendControl?, isCancelled: () -> Boolean): Boolean {
        return isCancelled() || control?.isCancelled() == true
    }

    private fun <T> executeTrackedCall(
        client: OkHttpClient,
        request: Request,
        control: LocalSendSendControl?,
        block: (okhttp3.Response) -> T
    ): T {
        val call = client.newCall(request)
        control?.bind(call)
        try {
            return call.execute().use(block)
        } finally {
            control?.release(call)
        }
    }

    internal fun classifyPrepareUploadFailure(statusCode: Int, responseText: String): IOException {
        val trimmed = responseText.trim()
        val normalized = trimmed.lowercase()
        val code = when {
            statusCode == 401 -> "AUTH_REQUIRED"
            statusCode == 409 && normalized.contains("receiver_busy") -> "RECEIVER_BUSY"
            statusCode == 403 && normalized.contains("receiver_disabled") -> "RECEIVER_DISABLED"
            statusCode == 403 && normalized.contains("rejected") -> "REJECTED"
            else -> null
        }
        return if (code != null) {
            IOException(code)
        } else {
            IOException("prepare_upload_failed:$statusCode:${trimmed.take(200)}")
        }
    }

    private fun prepareUpload(
        context: Context,
        client: OkHttpClient,
        scheme: String,
        host: String,
        port: Int,
        files: List<LocalSendSendFile>,
        pin: String?,
        control: LocalSendSendControl?
    ): Result<PreparedUpload> {
        val filesJson = JSONObject()
        files.forEach { file ->
            val fileJson = JSONObject()
                .put("id", file.id)
                .put("fileName", file.fileName)
                .put("size", file.size.coerceAtLeast(0L))
                .put("fileType", file.mimeType.ifBlank { "application/octet-stream" })
            file.previewText?.takeIf { it.isNotBlank() }?.let { preview ->
                fileJson.put("preview", preview)
            }
            filesJson.put(file.id, fileJson)
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
            executeTrackedCall(client, request, control) { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    throw classifyPrepareUploadFailure(response.code, text)
                }
                if (response.code == 204) {
                    return@executeTrackedCall PreparedUpload(
                        sessionId = "",
                        tokens = emptyMap(),
                        uploadRequired = false
                    )
                }
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) {
                    return@executeTrackedCall PreparedUpload(
                        sessionId = "",
                        tokens = emptyMap(),
                        uploadRequired = false
                    )
                }
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
                PreparedUpload(
                    sessionId = sessionId,
                    tokens = tokens,
                    uploadRequired = tokens.isNotEmpty()
                )
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
        control: LocalSendSendControl?,
        isCancelled: () -> Boolean,
        onProgress: (LocalSendSendProgress) -> Unit
    ): Result<Unit> {
        val url = "$scheme://$host:$port$LOCALSEND_API_PREFIX/upload" +
            "?sessionId=$sessionId&fileId=${file.id}&token=$token"
        val progressCallback: (Long) -> Unit = { current ->
            val sent = (completedBytesBefore + current).coerceAtMost(totalBytes)
            onProgress(
                LocalSendSendProgress(
                    progress = (sent.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f),
                    sentBytes = sent,
                    totalBytes = totalBytes,
                    message = "Uploading ${file.fileName}"
                )
            )
        }
        val reqBody = if (file.inlineBytes != null) {
            ByteArrayProgressRequestBody(
                bytes = file.inlineBytes,
                mimeType = file.mimeType,
                onBytes = progressCallback,
                isCancelled = isCancelled
            )
        } else {
            ContentUriRequestBody(
                context = context,
                uri = file.uri ?: throw IOException("missing_uri:${file.id}"),
                mimeType = file.mimeType,
                length = file.size,
                onBytes = progressCallback,
                isCancelled = isCancelled
            )
        }
        val request = Request.Builder()
            .url(url)
            .post(reqBody)
            .build()
        return runCatching {
            executeTrackedCall(client, request, control) { response ->
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

    private class ByteArrayProgressRequestBody(
        private val bytes: ByteArray,
        private val mimeType: String,
        private val onBytes: (Long) -> Unit,
        private val isCancelled: () -> Boolean
    ) : RequestBody() {
        override fun contentType() = mimeType.ifBlank { "application/octet-stream" }.toMediaTypeOrNull()

        override fun contentLength(): Long = bytes.size.toLong()

        override fun writeTo(sink: BufferedSink) {
            val chunkSize = 64 * 1024
            var offset = 0
            while (offset < bytes.size) {
                if (isCancelled()) throw IOException("SEND_CANCELLED")
                val next = minOf(offset + chunkSize, bytes.size)
                sink.write(bytes, offset, next - offset)
                offset = next
                onBytes(offset.toLong())
            }
        }
    }
}

