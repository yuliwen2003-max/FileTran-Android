package com.yuliwen.filetran

import android.util.Base64
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

// ============================================================
// 鍗忚甯搁噺
// ============================================================
private const val UDP_TAG = "UdpMeshNet"
private val UDP_MAGIC_V1: Byte = 0x55
private val UDP_MAGIC_V2: Byte = 0x4D

const val UDP_PKT_HELLO      : Byte = 0x01
const val UDP_PKT_HELLO_ACK  : Byte = 0x02
const val UDP_PKT_HELLO_DONE : Byte = 0x03
const val UDP_PKT_DATA       : Byte = 0x10
const val UDP_PKT_KEEPALIVE  : Byte = 0x11
const val UDP_PKT_DISCONNECT : Byte = 0x12
/** 棰勭暀锛歎DP 鎵撴礊璇锋眰锛堝悗缁墦娲炴ā鍧楁墿灞曪級 */
const val UDP_PKT_PUNCH_REQ  : Byte = 0x20
/** 棰勭暀锛歎DP 鎵撴礊鍝嶅簲 */
const val UDP_PKT_PUNCH_ACK  : Byte = 0x21

private const val GCM_IV_LEN   = 12
private const val GCM_TAG_BITS = 128

// ============================================================
// 甯х紪瑙ｇ爜
// 鏍煎紡: magic1(1)|magic2(1)|type(1)|seq(3)|payloadLen(2)|payload
// ============================================================
object UdpFrameCodec {
    const val HEADER_SIZE = 8

    fun encode(type: Byte, seq: Int, payload: ByteArray): ByteArray {
        val buf = ByteArray(HEADER_SIZE + payload.size)
        buf[0] = UDP_MAGIC_V1
        buf[1] = UDP_MAGIC_V2
        buf[2] = type
        buf[3] = ((seq shr 16) and 0xFF).toByte()
        buf[4] = ((seq shr 8)  and 0xFF).toByte()
        buf[5] = (seq          and 0xFF).toByte()
        buf[6] = ((payload.size shr 8) and 0xFF).toByte()
        buf[7] = (payload.size         and 0xFF).toByte()
        System.arraycopy(payload, 0, buf, HEADER_SIZE, payload.size)
        return buf
    }

    /** 瑙ｆ瀽甯э紝澶辫触杩斿洖 null */
    fun decode(raw: ByteArray, len: Int): Triple<Byte, Int, ByteArray>? {
        if (len < HEADER_SIZE) return null
        if (raw[0] != UDP_MAGIC_V1 || raw[1] != UDP_MAGIC_V2) return null
        val type = raw[2]
        val seq  = ((raw[3].toInt() and 0xFF) shl 16) or
                   ((raw[4].toInt() and 0xFF) shl 8)  or
                    (raw[5].toInt() and 0xFF)
        val payloadLen = ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF)
        if (HEADER_SIZE + payloadLen > len) return null
        return Triple(type, seq, raw.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLen))
    }
}

// ============================================================
// 鍔犲瘑鏍稿績锛圓ES-256-GCM + ECDH-P256锛?
// ============================================================
object UdpMeshCrypto {

    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        return kpg.generateKeyPair()
    }

    fun deriveSharedKey(local: KeyPair, remotePub: java.security.PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(local.private)
        ka.doPhase(remotePub, true)
        return java.security.MessageDigest.getInstance("SHA-256").digest(ka.generateSecret())
    }

    fun encodePublicKey(pub: java.security.PublicKey): ByteArray = pub.encoded

    fun decodePublicKey(bytes: ByteArray): java.security.PublicKey =
        java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(bytes))

    /** AES-256-GCM 鍔犲瘑锛岃繑鍥?IV(12)+CipherText+Tag(16) */
    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        require(data.size > GCM_IV_LEN) { "\u4e3b\u52a8\u65ad\u5f00" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, data.copyOfRange(0, GCM_IV_LEN))
        )
        return cipher.doFinal(data.copyOfRange(GCM_IV_LEN, data.size))
    }

    fun passcodeDigest(passcode: String): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(passcode.toByteArray(Charsets.UTF_8))

    fun generatePasscode(): String {
        val raw = ByteArray(18).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP)
            .replace("=", "").take(24)
    }

    /** 构建最小 QR JSON（v=2 标识 UDP 模式，区别 TCP v=1）*/
    fun buildQrPayload(cfg: UdpMeshConfig): String = org.json.JSONObject().apply {
        put("v",    2)
        put("pc",   cfg.passcode)
        put("h4",   cfg.serverPublicIpv4)
        put("h6",   cfg.serverPublicIpv6)
        put("port", cfg.listenPort)
        put("vs",   cfg.serverVpnIp)
        put("vc",   cfg.clientVpnIp)
        put("mask", cfg.subnetMask)
        put("mtu",  cfg.mtu)
        put("sl",   cfg.serverLanCidrs)
        put("cl",   cfg.clientLanCidrs)
        if (cfg.stunMappedPort > 0) put("smp", cfg.stunMappedPort)
    }.toString()

    fun parseQrPayload(json: String): UdpMeshConfig? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optInt("v", 0) != 2) return null
        UdpMeshConfig(
            role             = UdpMeshRole.CLIENT,
            passcode         = o.getString("pc"),
            serverHost       = o.optString("h4", "").ifBlank { o.optString("h6", "") },
            listenPort       = o.optInt("port", 7891),
            serverVpnIp      = o.optString("vs",   "192.168.200.1"),
            clientVpnIp      = o.optString("vc",   "192.168.200.2"),
            subnetMask       = o.optString("mask", "255.255.255.0"),
            mtu              = o.optInt("mtu",     1380),
            serverLanCidrs   = o.optString("sl",   ""),
            clientLanCidrs   = o.optString("cl",   ""),
            serverPublicIpv4 = o.optString("h4",   ""),
            serverPublicIpv6 = o.optString("h6",   ""),
            stunMappedPort   = o.optInt("smp",  0)
        )
    }.getOrNull()
}

// ============================================================
// 浼氳瘽閰嶇疆
// ============================================================
enum class UdpMeshRole { SERVER, CLIENT }

data class UdpMeshConfig(
    val role                : UdpMeshRole = UdpMeshRole.SERVER,
    val passcode            : String      = "",
    val listenPort          : Int         = 7891,
    val serverVpnIp         : String      = "192.168.200.1",
    val clientVpnIp         : String      = "192.168.200.2",
    val subnetMask          : String      = "255.255.255.0",
    val serverHost          : String      = "",
    val mtu                 : Int         = 1380,
    val keepaliveIntervalSec: Int         = 15,
    val serverLanCidrs      : String      = "",
    val clientLanCidrs      : String      = "",
    val serverPublicIpv4    : String      = "",
    val serverPublicIpv6    : String      = "",
    /** 预留：打洞中继信令服务器，留空=直连模式 */
    val stunServer          : String      = "",
    val stunPort            : Int         = 3478,
    /** 自动重试间隔（秒），仅客户端有效 */
    val retryIntervalSec    : Int         = 10,
    /** 最大重试次数，-1 表示无限重试，0 表示不重试 */
    val maxRetryCount       : Int         = 0,
    /** STUN 探测得到的公网映射端口（0=未探测），服务端用于打洞模式 */
    val stunMappedPort      : Int         = 0,
    /** 隧道走的网络接口（留空=系统默认），用于移动数据IPv6+WiFi内网场景 */
    val tunnelIface         : String      = ""
)

fun udpMeshConfigToJson(cfg: UdpMeshConfig): String = org.json.JSONObject().apply {
    put("role",            cfg.role.name)
    put("passcode",        cfg.passcode)
    put("listenPort",      cfg.listenPort)
    put("serverVpnIp",     cfg.serverVpnIp)
    put("clientVpnIp",     cfg.clientVpnIp)
    put("subnetMask",      cfg.subnetMask)
    put("serverHost",      cfg.serverHost)
    put("mtu",             cfg.mtu)
    put("keepalive",       cfg.keepaliveIntervalSec)
    put("serverLanCidrs",  cfg.serverLanCidrs)
    put("clientLanCidrs",  cfg.clientLanCidrs)
    put("serverPublicIpv4",cfg.serverPublicIpv4)
    put("serverPublicIpv6",cfg.serverPublicIpv6)
    put("stunServer",      cfg.stunServer)
    put("stunPort",        cfg.stunPort)
    put("retryIntervalSec",cfg.retryIntervalSec)
    put("maxRetryCount",   cfg.maxRetryCount)
    put("stunMappedPort",  cfg.stunMappedPort)
    put("tunnelIface",     cfg.tunnelIface)
}.toString()

fun parseUdpMeshConfig(json: String): UdpMeshConfig {
    val o = org.json.JSONObject(json)
    return UdpMeshConfig(
        role             = UdpMeshRole.valueOf(o.getString("role")),
        passcode         = o.getString("passcode"),
        listenPort       = o.optInt("listenPort",        7891),
        serverVpnIp      = o.optString("serverVpnIp",    "192.168.200.1"),
        clientVpnIp      = o.optString("clientVpnIp",    "192.168.200.2"),
        subnetMask       = o.optString("subnetMask",      "255.255.255.0"),
        serverHost       = o.optString("serverHost",      ""),
        mtu              = o.optInt("mtu",               1380),
        keepaliveIntervalSec = o.optInt("keepalive",     15),
        serverLanCidrs   = o.optString("serverLanCidrs", ""),
        clientLanCidrs   = o.optString("clientLanCidrs", ""),
        serverPublicIpv4 = o.optString("serverPublicIpv4",""),
        serverPublicIpv6 = o.optString("serverPublicIpv6",""),
        stunServer       = o.optString("stunServer",      ""),
        stunPort         = o.optInt("stunPort",           3478),
        retryIntervalSec = o.optInt("retryIntervalSec",  10),
        maxRetryCount    = o.optInt("maxRetryCount",      0),
        stunMappedPort   = o.optInt("stunMappedPort",     0),
        tunnelIface      = o.optString("tunnelIface",     "")
    )
}

// ============================================================
// UDP 闅ч亾鏍稿績
//
// 鎻℃墜锛堟槑鏂?UDP 3-way锛?
//   C->S: HELLO     = passcodeDigest(32) + clientPubKey(91)
//   S->C: HELLO_ACK = serverPubKey(91)  + serverVpnIp(4) + clientVpnIp(4)
//   C->S: HELLO_DONE= 绌?
// 鎻℃墜鍚庢墍鏈夋暟鎹寘 AES-256-GCM 鍔犲瘑銆?
// PUNCH_REQ/PUNCH_ACK 棰勭暀缁欏悗缁墦娲炴ā鍧椼€?
// ============================================================
class UdpMeshTunnel(
    private val cfg             : UdpMeshConfig,
    private val onPacketReceived: (ByteArray) -> Unit,
    private val onStateChange   : (String) -> Unit,
    private val onHandshakeDone : (localVpnIp: String, peerVpnIp: String) -> Unit,
    private val onDisconnected  : (String) -> Unit,
    /** VpnService.protect(socket) 回调，防止隧道 Socket 被 VPN 自身拦截 */
    private val protectSocket   : ((java.net.DatagramSocket) -> Unit)? = null
) {
    @Volatile var isRunning = false
        private set
    @Volatile var localVpnIp = ""
        private set
    @Volatile var peerVpnIp  = ""
        private set

    // 流量统计
    @Volatile var txBytes   : Long = 0L; private set
    @Volatile var rxBytes   : Long = 0L; private set
    @Volatile var txPackets : Long = 0L; private set
    @Volatile var rxPackets : Long = 0L; private set
    @Volatile var lastHandshakeTimeMs: Long = 0L; private set

    private var udpSocket: DatagramSocket? = null
    @Volatile private var peerAddr : InetSocketAddress? = null
    @Volatile private var sharedKey: ByteArray? = null
    private val sendQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(4096)
    @Volatile private var seqCtr = 0
    private fun nextSeq() = (seqCtr++) and 0xFFFFFF

    // ----------------------------------------------------------------
    fun start() {
        isRunning = true
        thread(name = "UdpMesh-session") { runSession() }
    }

    fun stop(reason: String = "\u4e3b\u52a8\u65ad\u5f00") {
        if (!isRunning) return
        isRunning = false
        runCatching {
            val frame = UdpFrameCodec.encode(UDP_PKT_DISCONNECT, 0, reason.toByteArray())
            sendRawUdp(frame)
        }
        runCatching { udpSocket?.close() }
    }

    fun sendTunPacket(rawIpPacket: ByteArray) {
        val key = sharedKey ?: return
        runCatching {
            val enc   = UdpMeshCrypto.encrypt(key, rawIpPacket)
            val frame = UdpFrameCodec.encode(UDP_PKT_DATA, nextSeq(), enc)
            if (!sendQueue.offer(frame)) Log.w(UDP_TAG, "发送队列满，丢包")
            else { txBytes += rawIpPacket.size.toLong(); txPackets++ }
        }.onFailure { Log.w(UDP_TAG, "sendTunPacket: ${it.message}") }
    }

    fun sendKeepalive() {
        if (!isRunning || sharedKey == null) return
        runCatching {
            sendQueue.offer(UdpFrameCodec.encode(UDP_PKT_KEEPALIVE, nextSeq(), ByteArray(0)))
        }
    }

    // ----------------------------------------------------------------
    // 搴曞眰 UDP 鍙戦€佸伐鍏?
    // ----------------------------------------------------------------
    private fun sendRawUdp(frame: ByteArray) {
        val ep   = peerAddr ?: return
        val sock = udpSocket ?: return
        runCatching { sendUdpTo(sock, frame, ep) }
    }

    private fun sendUdpTo(sock: DatagramSocket, data: ByteArray, ep: InetSocketAddress) {
        sock.send(DatagramPacket(data, data.size, ep.address, ep.port))
    }

    // ----------------------------------------------------------------
    // 主会话
    // ----------------------------------------------------------------
    private fun runSession() {
        try {
            val sock = buildSocket()
            udpSocket = sock
            // 保护隧道 Socket，防止被 VPN 自身拦截（对所有模式都有效）
            protectSocket?.invoke(sock)
            Log.i(UDP_TAG, "[Socket] protect 已调用 fd=${sock.localPort}")
            onStateChange("UDP Socket 就绪，握手中…")
            val ok = when (cfg.role) {
                UdpMeshRole.SERVER -> doServerHandshake(sock)
                UdpMeshRole.CLIENT -> doClientHandshake(sock)
            }
            if (!ok || !isRunning) {
                if (isRunning) onDisconnected("握手失败或超时")
                return
            }
            thread(name = "UdpMesh-send") { sendLoop(sock) }
            receiveLoop(sock)
        } catch (e: Exception) {
            if (isRunning) {
                Log.e(UDP_TAG, "会话异常: ${e.message}", e)
                onDisconnected("错误: ${e.message}")
            }
        } finally {
            isRunning = false
            runCatching { udpSocket?.close() }
        }
    }

    // ----------------------------------------------------------------
    // Socket 建立（IPv6 双栈优先，自动回退 IPv4）
    // ----------------------------------------------------------------
    private fun buildSocket(): DatagramSocket = when (cfg.role) {
        UdpMeshRole.SERVER -> {
            // stunMappedPort > 0 时绑定打洞映射端口，否则绑定配置端口
            val bindPort = if (cfg.stunMappedPort > 0) cfg.stunMappedPort else cfg.listenPort
            val sock = try {
                DatagramSocket(null).also {
                    it.reuseAddress = true
                    it.bind(InetSocketAddress("::", bindPort))
                    Log.i(UDP_TAG, "[服务端] IPv6 双栈绑定 :::$bindPort")
                }
            } catch (e: Exception) {
                Log.w(UDP_TAG, "IPv6 双栈失败(${e.message})，回退 IPv4")
                DatagramSocket(null).also {
                    it.reuseAddress = true
                    it.bind(InetSocketAddress("0.0.0.0", bindPort))
                    Log.i(UDP_TAG, "[服务端] IPv4 绑定 0.0.0.0:$bindPort")
                }
            }
            sock.soTimeout = 500
            sock
        }
        UdpMeshRole.CLIENT -> {
            // tunnelIface 非空时绑定指定接口（如移动数据网卡），实现隧道走移动网络
            val sock = if (cfg.tunnelIface.isNotBlank()) {
                try {
                    val iface = java.net.NetworkInterface.getByName(cfg.tunnelIface)
                    val addr = iface?.inetAddresses?.toList()
                        ?.firstOrNull { it is java.net.Inet6Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                        ?: iface?.inetAddresses?.toList()
                        ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
                    if (addr != null) {
                        DatagramSocket(null).also {
                            it.reuseAddress = true
                            it.bind(InetSocketAddress(addr, 0))
                            Log.i(UDP_TAG, "[客户端] 绑定接口 ${cfg.tunnelIface} addr=${addr.hostAddress} port=${it.localPort}")
                        }
                    } else {
                        Log.w(UDP_TAG, "[客户端] 接口 ${cfg.tunnelIface} 无可用地址，回退默认")
                        DatagramSocket()
                    }
                } catch (e: Exception) {
                    Log.w(UDP_TAG, "[客户端] 绑定接口 ${cfg.tunnelIface} 失败: ${e.message}，回退默认")
                    DatagramSocket()
                }
            } else {
                DatagramSocket()
            }
            sock.soTimeout = 500
            Log.i(UDP_TAG, "[客户端] 本地端口 ${sock.localPort}")
            sock
        }
    }

    // ----------------------------------------------------------------
    // 服务端握手：等 HELLO -> 校验密码 -> 发 HELLO_ACK -> 等 HELLO_DONE
    // ----------------------------------------------------------------
    private fun doServerHandshake(sock: DatagramSocket): Boolean {
        onStateChange("[服务端] 等待客户端 HELLO…")
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        var helloPayload: ByteArray? = null
        var clientEp: InetSocketAddress? = null
        val deadline = System.currentTimeMillis() + 120_000L
        while (isRunning && System.currentTimeMillis() < deadline) {
            try {
                pkt.length = buf.size
                sock.receive(pkt)
                val (type, _, payload) = UdpFrameCodec.decode(buf, pkt.length) ?: continue
                if (type != UDP_PKT_HELLO) continue
                helloPayload = payload
                clientEp = InetSocketAddress(pkt.address, pkt.port)
                Log.i(UDP_TAG, "[服务端] 收到 HELLO from $clientEp")
                break
            } catch (_: SocketTimeoutException) {}
        }
        if (helloPayload == null || clientEp == null || !isRunning) return false
        if (helloPayload.size < 32) { onStateChange("[服务端] HELLO 载荷过短"); return false }
        val theirDigest = helloPayload.copyOfRange(0, 32)
        val myDigest    = UdpMeshCrypto.passcodeDigest(cfg.passcode)
        if (!theirDigest.contentEquals(myDigest)) {
            onStateChange("[服务端] 密码不匹配，拒绝连接")
            return false
        }
        val clientPubKeyBytes = helloPayload.copyOfRange(32, helloPayload.size)
        val clientPubKey = runCatching { UdpMeshCrypto.decodePublicKey(clientPubKeyBytes) }
            .getOrElse { onStateChange("[服务端] 客户端公鑰解析失败"); return false }
        val serverKp         = UdpMeshCrypto.generateKeyPair()
        val sharedKeyDerived = UdpMeshCrypto.deriveSharedKey(serverKp, clientPubKey)
        val serverPubKeyBytes = UdpMeshCrypto.encodePublicKey(serverKp.public)
        peerAddr = clientEp
        val serverIpBytes = udpIpToBytes(cfg.serverVpnIp)
        val clientIpBytes = udpIpToBytes(cfg.clientVpnIp)
        val ackPayload = serverPubKeyBytes + serverIpBytes + clientIpBytes
        val ackFrame   = UdpFrameCodec.encode(UDP_PKT_HELLO_ACK, 0, ackPayload)
        repeat(3) { sendUdpTo(sock, ackFrame, clientEp) }
        onStateChange("[服务端] 已发 HELLO_ACK，等待 HELLO_DONE…")
        val doneDeadline = System.currentTimeMillis() + 20_000L  // 延长到20秒
        var gotDone = false
        while (isRunning && System.currentTimeMillis() < doneDeadline) {
            try {
                pkt.length = buf.size
                sock.receive(pkt)
                val (type, _, _) = UdpFrameCodec.decode(buf, pkt.length) ?: continue
                val fromEp = InetSocketAddress(pkt.address, pkt.port)
                // 允许同 IP 不同端口（NAT 重映射场景）
                if (fromEp.address != clientEp.address) continue
                if (type == UDP_PKT_HELLO_DONE) {
                    Log.i(UDP_TAG, "[服务端] 握手完成")
                    gotDone = true
                    // 更新实际客户端端口（可能因 NAT 重映射而变化）
                    peerAddr = fromEp
                    break
                }
                if (type == UDP_PKT_HELLO) repeat(2) { sendUdpTo(sock, ackFrame, fromEp) }
            } catch (_: SocketTimeoutException) {}
        }
        if (!isRunning) return false
        // 即使超时也继续（已发 ACK，对端可能已在数据阶段），记录警告
        if (!gotDone) Log.w(UDP_TAG, "[服务端] 等待 HELLO_DONE 超时，继续握手流程")
        sharedKey  = sharedKeyDerived
        localVpnIp = cfg.serverVpnIp
        peerVpnIp  = cfg.clientVpnIp
        lastHandshakeTimeMs = System.currentTimeMillis()
        onHandshakeDone(localVpnIp, peerVpnIp)
        onStateChange("已连接：本机 $localVpnIp ↔ 对端 $peerVpnIp")
        return true
    }

    // ----------------------------------------------------------------
    // 客户端握手：发 HELLO -> 等 HELLO_ACK -> 发 HELLO_DONE
    // ----------------------------------------------------------------
    private fun doClientHandshake(sock: DatagramSocket): Boolean {
        val serverIp = runCatching { InetAddress.getByName(cfg.serverHost.trim()) }
            .getOrElse { onDisconnected("无法解析服务端地址: ${cfg.serverHost}"); return false }
        val serverEp = InetSocketAddress(serverIp, cfg.listenPort)
        peerAddr = serverEp
        val clientKp          = UdpMeshCrypto.generateKeyPair()
        val clientPubKeyBytes  = UdpMeshCrypto.encodePublicKey(clientKp.public)
        val passcodeDigest     = UdpMeshCrypto.passcodeDigest(cfg.passcode)
        val helloPayload       = passcodeDigest + clientPubKeyBytes
        val helloFrame         = UdpFrameCodec.encode(UDP_PKT_HELLO, 0, helloPayload)
        onStateChange("[客户端] 发送 HELLO 到 $serverEp…")
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        var ackPayload: ByteArray? = null
        repeat(8) { attempt ->
            if (!isRunning || ackPayload != null) return@repeat
            sendUdpTo(sock, helloFrame, serverEp)
            val waitUntil = System.currentTimeMillis() + 2000L
            while (isRunning && System.currentTimeMillis() < waitUntil) {
                try {
                    pkt.length = buf.size
                    sock.receive(pkt)
                    val (type, _, payload) = UdpFrameCodec.decode(buf, pkt.length) ?: continue
                    if (type == UDP_PKT_HELLO_ACK) { ackPayload = payload; break }
                } catch (_: SocketTimeoutException) {}
            }
        }
        if (ackPayload == null || !isRunning) { onDisconnected("[客户端] 握手超时"); return false }
        if (ackPayload!!.size < 9) { onDisconnected("[客户端] HELLO_ACK 载荷过短"); return false }
        val serverPubKeyBytes = ackPayload!!.copyOfRange(0, ackPayload!!.size - 8)
        val serverVpnIpBytes  = ackPayload!!.copyOfRange(ackPayload!!.size - 8, ackPayload!!.size - 4)
        val clientVpnIpBytes  = ackPayload!!.copyOfRange(ackPayload!!.size - 4, ackPayload!!.size)
        val serverPubKey = runCatching { UdpMeshCrypto.decodePublicKey(serverPubKeyBytes) }
            .getOrElse { onDisconnected("[客户端] 服务端公鑰解析失败"); return false }
        val sharedKeyDerived    = UdpMeshCrypto.deriveSharedKey(clientKp, serverPubKey)
        val assignedServerVpnIp = udpBytesToIp(serverVpnIpBytes)
        val assignedClientVpnIp = udpBytesToIp(clientVpnIpBytes)
        val doneFrame = UdpFrameCodec.encode(UDP_PKT_HELLO_DONE, 0, ByteArray(0))
        repeat(5) { sendUdpTo(sock, doneFrame, serverEp) }  // 多发几次提高可靠性
        sharedKey  = sharedKeyDerived
        localVpnIp = assignedClientVpnIp
        peerVpnIp  = assignedServerVpnIp
        lastHandshakeTimeMs = System.currentTimeMillis()
        onHandshakeDone(localVpnIp, peerVpnIp)
        onStateChange("已连接：本机 $localVpnIp ↔ 对端 $peerVpnIp")
        return true
    }

    // ----------------------------------------------------------------
    // 发送循环
    // ----------------------------------------------------------------
    private fun sendLoop(sock: DatagramSocket) {
        try {
            while (isRunning) {
                val frame = sendQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if (!isRunning) break
                val ep = peerAddr ?: continue
                runCatching { sendUdpTo(sock, frame, ep) }
                    .onFailure { Log.w(UDP_TAG, "sendLoop 失败: ${it.message}") }
            }
        } catch (_: InterruptedException) {}
        Log.d(UDP_TAG, "sendLoop 退出")
    }

    // ----------------------------------------------------------------
    // 接收循环
    // ----------------------------------------------------------------
    private fun receiveLoop(sock: DatagramSocket) {
        val buf = ByteArray(65536 + 64)
        val pkt = DatagramPacket(buf, buf.size)
        Log.i(UDP_TAG, "receiveLoop 启动")
        while (isRunning) {
            try {
                pkt.length = buf.size
                sock.receive(pkt)
                val (type, seq, payload) = UdpFrameCodec.decode(buf, pkt.length) ?: continue
                val fromEp = InetSocketAddress(pkt.address, pkt.port)
                when (type) {
                    UDP_PKT_DATA -> {
                        val key = sharedKey ?: continue
                        runCatching {
                            val plain = UdpMeshCrypto.decrypt(key, payload)
                            rxBytes += plain.size; rxPackets++
                            onPacketReceived(plain)
                        }.onFailure { Log.w(UDP_TAG, "解密失败 seq=$seq: ${it.message}") }
                    }
                    UDP_PKT_KEEPALIVE -> {
                        if (peerAddr != fromEp) {
                            Log.i(UDP_TAG, "对端地址更新: $peerAddr -> $fromEp")
                            peerAddr = fromEp
                        }
                    }
                    UDP_PKT_DISCONNECT -> {
                        val reason = payload.toString(Charsets.UTF_8)
                        Log.i(UDP_TAG, "对端断开: $reason")
                        isRunning = false
                        onDisconnected("对端断开: $reason")
                        return
                    }
                    UDP_PKT_PUNCH_REQ -> {
                        Log.d(UDP_TAG, "[预留] 收到 PUNCH_REQ from $fromEp")
                        val ackFrame = UdpFrameCodec.encode(UDP_PKT_PUNCH_ACK, 0, ByteArray(0))
                        runCatching { sendUdpTo(sock, ackFrame, fromEp) }
                    }
                    UDP_PKT_PUNCH_ACK -> {
                        Log.d(UDP_TAG, "[预留] 收到 PUNCH_ACK from $fromEp")
                    }
                    else -> Log.w(UDP_TAG, "未知帧类型: $type")
                }
            } catch (_: SocketTimeoutException) {
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(UDP_TAG, "receiveLoop 异常: ${e.message}")
                    onDisconnected("接收错误: ${e.message}")
                }
                break
            }
        }
        Log.d(UDP_TAG, "receiveLoop 退出")
    }
}

// ============================================================
// IP 工具函数
// ============================================================
fun udpIpToBytes(ip: String): ByteArray {
    val parts = ip.trim().split(".")
    require(parts.size == 4) { "无效 IPv4: $ip" }
    return ByteArray(4) { i -> parts[i].toInt().and(0xFF).toByte() }
}

fun udpBytesToIp(bytes: ByteArray): String {
    require(bytes.size == 4)
    return bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
}

fun udpCidrFromMask(mask: String): Int = when (mask.trim()) {
    "255.255.255.0"   -> 24
    "255.255.0.0"     -> 16
    "255.0.0.0"       -> 8
    "255.255.255.128" -> 25
    "255.255.255.192" -> 26
    "255.255.255.252" -> 30
    else -> runCatching {
        var bits = 0
        var n = mask.split(".").fold(0L) { acc, s -> (acc shl 8) or s.toLong() }
        while (n != 0L) { if (n and 0x80000000L != 0L) bits++ else break; n = n shl 1 }
        bits
    }.getOrDefault(24)
}

fun udpVpnSubnet(ip: String, mask: String): String = runCatching {
    val ipParts   = ip.split(".").map { it.toInt() }
    val maskParts = mask.split(".").map { it.toInt() }
    (0..3).joinToString(".") { (ipParts[it] and maskParts[it]).toString() }
}.getOrDefault(ip)
