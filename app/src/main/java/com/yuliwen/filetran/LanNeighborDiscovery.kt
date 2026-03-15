package com.yuliwen.filetran

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val NEIGHBOR_SETTINGS_PREFS = "filetran_neighbor_settings"
private const val NEIGHBOR_HISTORY_PREFS = "filetran_neighbor_history"
private const val NEIGHBOR_HISTORY_KEY = "history_v1"
private const val KEY_NEIGHBOR_ENABLED = "enabled"
private const val KEY_DEFAULT_ACTION = "default_action"
private const val KEY_TIMEOUT_SECONDS = "timeout_seconds"
private const val KEY_QR_DEFAULT_PAGE = "qr_default_page"
private const val KEY_BACKGROUND_ENHANCED = "background_enhanced"
private const val KEY_BACKGROUND_AUTO_RECEIVE_NO_PROMPT = "background_auto_receive_no_prompt"
private const val KEY_LOCALSEND_V2_ENABLED = "localsend_v2_enabled"
private const val KEY_LOCALSEND_V2_ALIAS = "localsend_v2_alias"
private const val KEY_SHARE_RANDOM_PORT = "share_random_port"
private const val KEY_SHARE_FIXED_PORT = "share_fixed_port"

private const val DEFAULT_TIMEOUT_SECONDS = 10
private const val MAX_TIMEOUT_SECONDS = 25
private const val MAX_LOCALSEND_V2_ALIAS_LENGTH = 64
const val DEFAULT_SHARE_PORT = 12333

private const val DISCOVERY_PORT = 19443
private const val MAX_PACKET_SIZE = 16 * 1024
private const val MSG_DISCOVER = "filetran_neighbor_discover_v1"
private const val MSG_HELLO = "filetran_neighbor_hello_v1"
private const val MSG_QUICK_SEND = "filetran_quick_send_v1"
private const val MSG_QUICK_SEND_RESPONSE = "filetran_quick_send_resp_v1"
private const val MSG_QUICK_SEND_CANCEL = "filetran_quick_send_cancel_v1"
const val QUICK_TRANSFER_MODE_AUTO = "auto"
const val QUICK_TRANSFER_MODE_PULL = "pull"
const val QUICK_TRANSFER_MODE_REVERSE_PUSH = "reverse_push"

enum class NeighborDefaultAction(val code: Int) {
    REJECT(0),
    ACCEPT(1);

    companion object {
        fun fromCode(code: Int): NeighborDefaultAction {
            return entries.firstOrNull { it.code == code } ?: REJECT
        }
    }
}

data class NeighborDiscoverySettings(
    val enabled: Boolean = false,
    val defaultAction: NeighborDefaultAction = NeighborDefaultAction.REJECT,
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val qrDefaultPage: Int = 0,
    val backgroundEnhanced: Boolean = false,
    val backgroundAutoReceiveNoPrompt: Boolean = false,
    val localSendV2Enabled: Boolean = true,
    val deviceAlias: String = "",
    val shareRandomPort: Boolean = true,
    val shareFixedPort: Int = DEFAULT_SHARE_PORT
)

object NeighborDiscoverySettingsStore {
    fun get(context: Context): NeighborDiscoverySettings {
        val prefs = context.getSharedPreferences(NEIGHBOR_SETTINGS_PREFS, Context.MODE_PRIVATE)
        val timeout = prefs.getInt(KEY_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS).coerceIn(0, MAX_TIMEOUT_SECONDS)
        val page = prefs.getInt(KEY_QR_DEFAULT_PAGE, 0).coerceIn(0, 1)
        return NeighborDiscoverySettings(
            enabled = prefs.getBoolean(KEY_NEIGHBOR_ENABLED, false),
            defaultAction = NeighborDefaultAction.fromCode(
                prefs.getInt(KEY_DEFAULT_ACTION, NeighborDefaultAction.REJECT.code)
            ),
            timeoutSeconds = timeout,
            qrDefaultPage = page,
            backgroundEnhanced = prefs.getBoolean(KEY_BACKGROUND_ENHANCED, false),
            backgroundAutoReceiveNoPrompt = prefs.getBoolean(KEY_BACKGROUND_AUTO_RECEIVE_NO_PROMPT, false),
            localSendV2Enabled = prefs.getBoolean(KEY_LOCALSEND_V2_ENABLED, true),
            deviceAlias = sanitizeDeviceAlias(
                prefs.getString(KEY_LOCALSEND_V2_ALIAS, "").orEmpty()
            ),
            shareRandomPort = prefs.getBoolean(KEY_SHARE_RANDOM_PORT, true),
            shareFixedPort = sanitizeSharePort(
                prefs.getInt(KEY_SHARE_FIXED_PORT, DEFAULT_SHARE_PORT)
            )
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NEIGHBOR_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NEIGHBOR_ENABLED, enabled)
            .apply()
    }

    fun setDefaultAction(context: Context, action: NeighborDefaultAction) {
        context.getSharedPreferences(NEIGHBOR_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DEFAULT_ACTION, action.code)
            .apply()
    }

    fun setTimeoutSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(NEIGHBOR_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_TIMEOUT_SECONDS, seconds.coerceIn(0, MAX_TIMEOUT_SECONDS))
            .apply()
    }

    fun setQrDefaultPage(context: Context, page: Int) {
        context.getSharedPreferences(NEIGHBOR_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_QR_DEFAULT_PAGE, page.coerceIn(0, 1))
            .apply()
    }

    fun setBackgroundEnhanced(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NEIGHBOR_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BACKGROUND_ENHANCED, enabled)
            .apply()
    }

    fun setBackgroundAutoReceiveNoPrompt(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NEIGHBOR_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BACKGROUND_AUTO_RECEIVE_NO_PROMPT, enabled)
            .apply()
    }

    fun setLocalSendV2Enabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NEIGHBOR_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LOCALSEND_V2_ENABLED, enabled)
            .apply()
    }

    fun setDeviceAlias(context: Context, alias: String) {
        context.getSharedPreferences(NEIGHBOR_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCALSEND_V2_ALIAS, sanitizeDeviceAlias(alias))
            .apply()
    }

    fun setLocalSendV2Alias(context: Context, alias: String) {
        setDeviceAlias(context, alias)
    }

    fun setShareRandomPort(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NEIGHBOR_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHARE_RANDOM_PORT, enabled)
            .apply()
    }

    fun setShareFixedPort(context: Context, port: Int) {
        context.getSharedPreferences(NEIGHBOR_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SHARE_FIXED_PORT, sanitizeSharePort(port))
            .apply()
    }

    fun registerChangeListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ): SharedPreferences {
        val prefs = context.getSharedPreferences(NEIGHBOR_SETTINGS_PREFS, Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return prefs
    }

    private fun sanitizeDeviceAlias(raw: String): String {
        val cleaned = buildString(raw.length) {
            raw.forEach { ch ->
                when {
                    ch == '\u0000' -> Unit
                    ch.isISOControl() -> append(' ')
                    else -> append(ch)
                }
            }
        }
        return cleaned
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_LOCALSEND_V2_ALIAS_LENGTH)
    }

    private fun sanitizeSharePort(port: Int): Int {
        return port.coerceIn(1024, 65535)
    }
}

data class NeighborHistoryEntry(
    val ip: String,
    val name: String,
    val hostName: String,
    val lastUsedAt: Long
)

object NeighborHistoryStore {
    private const val MAX_HISTORY = 40

    fun load(context: Context): List<NeighborHistoryEntry> {
        val raw = context.getSharedPreferences(NEIGHBOR_HISTORY_PREFS, Context.MODE_PRIVATE)
            .getString(NEIGHBOR_HISTORY_KEY, null)
            .orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val ip = obj.optString("ip").trim()
                    if (ip.isBlank()) continue
                    add(
                        NeighborHistoryEntry(
                            ip = ip,
                            name = obj.optString("name").trim(),
                            hostName = obj.optString("hostName").trim(),
                            lastUsedAt = obj.optLong("lastUsedAt", 0L)
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    fun upsert(context: Context, entry: NeighborHistoryEntry) {
        val merged = load(context)
            .filterNot { it.ip.equals(entry.ip, ignoreCase = true) }
            .toMutableList()
            .also { it.add(0, entry) }
            .take(MAX_HISTORY)
        save(context, merged)
    }

    fun delete(context: Context, ip: String) {
        val kept = load(context).filterNot { it.ip.equals(ip.trim(), ignoreCase = true) }
        save(context, kept)
    }

    private fun save(context: Context, entries: List<NeighborHistoryEntry>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject()
                    .put("ip", e.ip)
                    .put("name", e.name)
                    .put("hostName", e.hostName)
                    .put("lastUsedAt", e.lastUsedAt)
            )
        }
        context.getSharedPreferences(NEIGHBOR_HISTORY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(NEIGHBOR_HISTORY_KEY, array.toString())
            .apply()
    }
}

data class LanNeighborPeer(
    val ip: String,
    val name: String,
    val hostName: String,
    val quickSendPort: Int = DISCOVERY_PORT,
    val peerProtocol: NeighborPeerProtocol = NeighborPeerProtocol.FILETRAN,
    val localSendPort: Int = LocalSendProtocol.LOCALSEND_PORT,
    val localSendProtocol: String = "http",
    val localSendFingerprint: String = "",
    val lastSeenAt: Long = System.currentTimeMillis()
)

enum class NeighborPeerProtocol {
    FILETRAN,
    LOCALSEND
}

data class QuickSendInvite(
    val requestId: String = UUID.randomUUID().toString(),
    val senderName: String,
    val senderHostName: String,
    val senderIp: String,
    val fileName: String,
    val fileSize: Long,
    val downloadUrl: String,
    val preferredTransferMode: String = QUICK_TRANSFER_MODE_AUTO,
    val sentAt: Long = System.currentTimeMillis(),
    val replyPort: Int = 0,
    val responseIp: String = ""
)

data class QuickSendInviteResponse(
    val requestId: String,
    val accepted: Boolean,
    val message: String,
    val transferMode: String = QUICK_TRANSFER_MODE_PULL,
    val reverseEndpoint: String = ""
)

fun buildLocalNeighborName(): String {
    val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
    val model = Build.MODEL?.trim().orEmpty()
    return when {
        model.isBlank() -> "FileTran"
        manufacturer.isBlank() -> model
        model.startsWith(manufacturer, ignoreCase = true) -> model
        else -> "$manufacturer $model"
    }
}

fun buildLocalNeighborName(context: Context): String {
    val customAlias = NeighborDiscoverySettingsStore.get(context).deviceAlias
    return customAlias.ifBlank { buildLocalNeighborName() }
}

fun buildLocalSendV2Name(context: Context): String {
    return buildLocalNeighborName(context)
}

fun buildLocalHostName(): String {
    return runCatching { InetAddress.getLocalHost().hostName.orEmpty().trim() }.getOrDefault("")
}

fun buildLocalIpv4Prefix(context: Context): String {
    val local = NetworkUtils.getLocalIpAddress(context).orEmpty()
    val parts = local.split('.')
    return if (parts.size == 4) {
        "${parts[0]}.${parts[1]}.${parts[2]}."
    } else {
        ""
    }
}

class LanNeighborRuntime(
    private val context: Context,
    private val onQuickSendInvite: (QuickSendInvite) -> Unit,
    private val onQuickSendCancel: (String) -> Unit = {},
    private val ownerTag: String = "runtime"
) {
    private val running = AtomicBoolean(false)
    @Volatile
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", DISCOVERY_PORT))
                    soTimeout = 1200
                }
                Log.i("LanNeighborRuntime", "[$ownerTag] started on 0.0.0.0:$DISCOVERY_PORT")
                val buffer = ByteArray(MAX_PACKET_SIZE)
                while (running.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: Exception) {
                        continue
                    }
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                    if (text.isBlank()) continue
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    val settings = NeighborDiscoverySettingsStore.get(context)
                    if (!settings.enabled) continue
                    when (json.optString("type")) {
                        MSG_DISCOVER -> {
                            val replyPort = json.optInt("replyPort", packet.port)
                            if (replyPort !in 1..65535) continue
                            val ip = NetworkUtils.getLocalIpAddress(context).orEmpty()
                            val resp = JSONObject()
                                .put("type", MSG_HELLO)
                                .put("name", buildLocalNeighborName(context))
                                .put("hostName", buildLocalHostName())
                                .put("ip", ip)
                                .put("quickSendPort", DISCOVERY_PORT)
                                .toString()
                            val data = resp.toByteArray(Charsets.UTF_8)
                            runCatching {
                                socket.send(DatagramPacket(data, data.size, packet.address, replyPort))
                            }
                        }

                        MSG_QUICK_SEND -> {
                            val invite = parseQuickInvite(json)?.copy(
                                responseIp = packet.address?.hostAddress.orEmpty().trim()
                            ) ?: continue
                            onQuickSendInvite(invite)
                        }
                        MSG_QUICK_SEND_CANCEL -> {
                            val requestId = json.optString("requestId").trim()
                            if (requestId.isNotBlank()) {
                                onQuickSendCancel(requestId)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("LanNeighborRuntime", "[$ownerTag] runtime failed: ${e.message}", e)
            } finally {
                running.set(false)
                runCatching { socket?.close() }
                Log.i("LanNeighborRuntime", "[$ownerTag] stopped")
            }
        }.apply {
            name = "LanNeighborRuntime-$ownerTag"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    fun isRunning(): Boolean {
        return running.get() && (worker?.isAlive == true)
    }
}

fun sendQuickSendInviteCancel(targetIp: String, requestId: String): Boolean {
    val ip = targetIp.trim()
    val reqId = requestId.trim()
    if (ip.isBlank() || reqId.isBlank()) return false
    val payload = JSONObject()
        .put("type", MSG_QUICK_SEND_CANCEL)
        .put("requestId", reqId)
        .toString()
        .toByteArray(Charsets.UTF_8)
    return runCatching {
        DatagramSocket().use { socket ->
            val addr = InetAddress.getByName(ip)
            repeat(3) {
                socket.send(DatagramPacket(payload, payload.size, addr, DISCOVERY_PORT))
                Thread.sleep(100)
            }
        }
        true
    }.getOrDefault(false)
}

fun discoverLanNeighbors(context: Context, targetIp: String? = null, timeoutMs: Int = 1200): List<LanNeighborPeer> {
    val peers = LinkedHashMap<String, LanNeighborPeer>()
    val endAt = System.currentTimeMillis() + timeoutMs.coerceAtLeast(300)
    val localIp = NetworkUtils.getLocalIpAddress(context).orEmpty()
    DatagramSocket().use { socket ->
        socket.broadcast = true
        socket.soTimeout = 180
        val discover = JSONObject()
            .put("type", MSG_DISCOVER)
            .put("replyPort", socket.localPort)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val targets = buildList {
            val manual = targetIp?.trim().orEmpty()
            if (manual.isNotBlank()) {
                add(manual)
            } else {
                add("255.255.255.255")
                val prefix = buildLocalIpv4Prefix(context)
                if (prefix.isNotBlank()) add(prefix + "255")
            }
        }

        targets.forEach { host ->
            runCatching {
                val address = InetAddress.getByName(host)
                socket.send(DatagramPacket(discover, discover.size, address, DISCOVERY_PORT))
            }
        }

        val buffer = ByteArray(MAX_PACKET_SIZE)
        while (System.currentTimeMillis() < endAt) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                continue
            }
            val json = runCatching {
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                JSONObject(text)
            }.getOrNull() ?: continue
            if (json.optString("type") != MSG_HELLO) continue
            val ip = packet.address?.hostAddress?.trim().orEmpty()
            if (ip.isBlank() || ip == localIp) continue
            peers[ip] = LanNeighborPeer(
                ip = ip,
                name = json.optString("name").ifBlank { "FileTran 邻居" },
                hostName = json.optString("hostName").orEmpty(),
                quickSendPort = json.optInt("quickSendPort", DISCOVERY_PORT).coerceIn(1, 65535),
                lastSeenAt = System.currentTimeMillis()
            )
        }
    }
    return peers.values.sortedWith(compareBy<LanNeighborPeer> { it.name.lowercase() }.thenBy { it.ip })
}

fun sendQuickSendInvite(targetIp: String, invite: QuickSendInvite): Boolean {
    val ip = targetIp.trim()
    if (ip.isBlank()) return false
    val payload = JSONObject()
        .put("type", MSG_QUICK_SEND)
        .put("requestId", invite.requestId)
        .put("senderName", invite.senderName)
        .put("senderHostName", invite.senderHostName)
        .put("senderIp", invite.senderIp)
        .put("fileName", invite.fileName)
        .put("fileSize", invite.fileSize)
        .put("downloadUrl", invite.downloadUrl)
        .put("preferredTransferMode", invite.preferredTransferMode)
        .put("sentAt", invite.sentAt)
        .put("replyPort", invite.replyPort)
        .toString()
        .toByteArray(Charsets.UTF_8)
    return runCatching {
        DatagramSocket().use { socket ->
            val addr = InetAddress.getByName(ip)
            socket.send(DatagramPacket(payload, payload.size, addr, DISCOVERY_PORT))
        }
        true
    }.getOrDefault(false)
}

fun sendQuickSendInviteAndAwaitResponse(
    targetIp: String,
    invite: QuickSendInvite,
    timeoutMs: Int = 10_000,
    onProgress: (Float) -> Unit = {},
    isCancelled: () -> Boolean = { false }
): QuickSendInviteResponse? {
    val ip = targetIp.trim()
    if (ip.isBlank()) return null
    val socket = DatagramSocket()
    return runCatching {
        if (isCancelled()) return@runCatching null
        socket.soTimeout = 250
        val payloadInvite = invite.copy(replyPort = socket.localPort)
        val payload = JSONObject()
            .put("type", MSG_QUICK_SEND)
            .put("requestId", payloadInvite.requestId)
            .put("senderName", payloadInvite.senderName)
            .put("senderHostName", payloadInvite.senderHostName)
            .put("senderIp", payloadInvite.senderIp)
            .put("fileName", payloadInvite.fileName)
            .put("fileSize", payloadInvite.fileSize)
            .put("downloadUrl", payloadInvite.downloadUrl)
            .put("preferredTransferMode", payloadInvite.preferredTransferMode)
            .put("sentAt", payloadInvite.sentAt)
            .put("replyPort", payloadInvite.replyPort)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val addr = InetAddress.getByName(ip)
        socket.send(DatagramPacket(payload, payload.size, addr, DISCOVERY_PORT))
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs.coerceAtLeast(1000)
        val buffer = ByteArray(MAX_PACKET_SIZE)
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) return@runCatching null
            val elapsed = System.currentTimeMillis() - start
            onProgress((elapsed.toFloat() / timeoutMs.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f))
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                continue
            }
            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
            val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
            if (json.optString("type") != MSG_QUICK_SEND_RESPONSE) continue
            if (json.optString("requestId") != payloadInvite.requestId) continue
            return@runCatching QuickSendInviteResponse(
                requestId = payloadInvite.requestId,
                accepted = json.optBoolean("accepted", false),
                message = json.optString("message").ifBlank {
                    if (json.optBoolean("accepted", false)) "对方已接收" else "对方已拒绝"
                },
                transferMode = json.optString("transferMode").ifBlank { QUICK_TRANSFER_MODE_PULL },
                reverseEndpoint = json.optString("reverseEndpoint").trim()
            )
        }
        onProgress(1f)
        null
    }.getOrNull().also {
        runCatching { socket.close() }
    }
}

fun resolvePreferredIpv4ForTarget(targetIp: String, fallback: String = ""): String {
    val target = targetIp.trim()
    val fallbackIp = fallback.trim()
    val routed = runCatching {
        DatagramSocket().use { socket ->
            socket.connect(InetAddress.getByName(target), DISCOVERY_PORT)
            val local = socket.localAddress
            if (local is Inet4Address) local.hostAddress.orEmpty().trim() else ""
        }
    }.getOrDefault("")
    return when {
        routed.isPrivateIpv4() -> routed
        fallbackIp.isPrivateIpv4() -> fallbackIp
        routed.isNotBlank() -> routed
        else -> fallbackIp
    }
}

fun sendQuickSendInviteResponse(
    invite: QuickSendInvite,
    accepted: Boolean,
    message: String = "",
    transferMode: String = QUICK_TRANSFER_MODE_PULL,
    reverseEndpoint: String = ""
): Boolean {
    if (invite.replyPort !in 1..65535) return false
    val targets = listOf(invite.responseIp.trim(), invite.senderIp.trim())
        .filter { it.isNotBlank() }
        .distinct()
    if (targets.isEmpty()) return false
    val payload = JSONObject()
        .put("type", MSG_QUICK_SEND_RESPONSE)
        .put("requestId", invite.requestId)
        .put("accepted", accepted)
        .put("message", message)
        .put("transferMode", transferMode)
        .put("reverseEndpoint", reverseEndpoint)
        .toString()
        .toByteArray(Charsets.UTF_8)
    return runCatching {
        DatagramSocket().use { socket ->
            repeat(3) {
                targets.forEach { target ->
                    val addr = InetAddress.getByName(target)
                    socket.send(DatagramPacket(payload, payload.size, addr, invite.replyPort))
                }
                Thread.sleep(120)
            }
        }
        true
    }.getOrDefault(false)
}

private fun parseQuickInvite(json: JSONObject): QuickSendInvite? {
    val requestId = json.optString("requestId").ifBlank { UUID.randomUUID().toString() }
    val senderName = json.optString("senderName").ifBlank { "FileTran 用户" }
    val senderHostName = json.optString("senderHostName").orEmpty()
    val senderIp = json.optString("senderIp").orEmpty()
    val fileName = json.optString("fileName").ifBlank { "unknown" }
    val fileSize = json.optLong("fileSize", -1L)
    val url = json.optString("downloadUrl").trim()
    val preferredTransferMode = json.optString("preferredTransferMode")
        .ifBlank { QUICK_TRANSFER_MODE_AUTO }
        .lowercase()
    if (url.isBlank()) return null
    return QuickSendInvite(
        requestId = requestId,
        senderName = senderName,
        senderHostName = senderHostName,
        senderIp = senderIp,
        fileName = fileName,
        fileSize = fileSize,
        downloadUrl = url,
        preferredTransferMode = preferredTransferMode,
        sentAt = json.optLong("sentAt", System.currentTimeMillis()),
        replyPort = json.optInt("replyPort", 0)
    )
}

private fun String.isPrivateIpv4(): Boolean {
    val ip = trim()
    val parts = ip.split('.')
    if (parts.size != 4) return false
    val nums = parts.map { it.toIntOrNull() ?: return false }
    return when {
        nums[0] == 10 -> true
        nums[0] == 172 && nums[1] in 16..31 -> true
        nums[0] == 192 && nums[1] == 168 -> true
        nums[0] == 169 && nums[1] == 254 -> true
        else -> false
    }
}
