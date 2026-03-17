package com.yuliwen.filetran

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

// ============================================================
// 常量
// ============================================================
private const val TAG = "MeshNetManager"
private const val MAGIC = 0x4D455348.toInt()   // "MESH" ASCII
private const val VERSION: Byte = 1
private const val GCM_TAG_BITS = 128
private const val GCM_IV_BYTES = 12
private const val MAX_PACKET_BYTES = 65536

// 数据包类型
const val PKT_HANDSHAKE_HELLO   : Byte = 0x01
const val PKT_HANDSHAKE_ACK     : Byte = 0x02
const val PKT_KEEPALIVE         : Byte = 0x10
const val PKT_TUNNEL_DATA       : Byte = 0x20
const val PKT_TUNNEL_ADDR_INFO  : Byte = 0x21
const val PKT_DISCONNECT        : Byte = 0x30

// ============================================================
// 密钥工具
// ============================================================
object MeshCrypto {

    /** 生成 EC 密钥对（P-256） */
    fun generateEcKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        return kpg.generateKeyPair()
    }

    /** ECDH 导出共享密钥，取 SHA-256 截断为 32 字节用于 AES-256 */
    fun deriveSharedKey(localPair: KeyPair, remotePubKey: java.security.PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(localPair.private)
        ka.doPhase(remotePubKey, true)
        val shared = ka.generateSecret()
        // SHA-256 派生
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(shared)
    }

    /** AES-256-GCM 加密，返回 IV(12) + CipherText + Tag(16) */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext)
        return iv + ct
    }

    /** AES-256-GCM 解密 */
    fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        require(data.size > GCM_IV_BYTES) { "数据过短" }
        val iv = data.copyOfRange(0, GCM_IV_BYTES)
        val ct = data.copyOfRange(GCM_IV_BYTES, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    /** 将 EC 公钥序列化为 X.509 编码字节 */
    fun encodePublicKey(pub: java.security.PublicKey): ByteArray = pub.encoded

    /** 从 X.509 编码字节恢复 EC 公钥 */
    fun decodePublicKey(bytes: ByteArray): java.security.PublicKey {
        val kf = java.security.KeyFactory.getInstance("EC")
        return kf.generatePublic(java.security.spec.X509EncodedKeySpec(bytes))
    }

    /** 生成随机预共享密码，Base64 格式，供用户分享 */
    fun generatePasscode(): String {
        val raw = ByteArray(18).also { SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
    }

    /** 将密码字符串派生为 32 字节用于身份验证校验 */
    fun derivePasscodeKey(passcode: String): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(passcode.toByteArray(Charsets.UTF_8))
    }
}

// ============================================================
// 帧读写（基于 DataInputStream/DataOutputStream）
// ============================================================
/**
 * 帧格式:
 *   magic(4) | version(1) | type(1) | length(4) | payload(length)
 */
object MeshFrameCodec {
    /** 将帧序列化为 ByteArray（供异步发送队列使用，不直接写 stream）*/
    fun buildFrame(type: Byte, payload: ByteArray): ByteArray {
        val buf = java.io.ByteArrayOutputStream(10 + payload.size)
        val dos = DataOutputStream(buf)
        dos.writeInt(MAGIC)
        dos.writeByte(VERSION.toInt())
        dos.writeByte(type.toInt())
        dos.writeInt(payload.size)
        if (payload.isNotEmpty()) dos.write(payload)
        dos.flush()
        return buf.toByteArray()
    }

    fun writeFrame(out: DataOutputStream, type: Byte, payload: ByteArray) {
        out.writeInt(MAGIC)
        out.writeByte(VERSION.toInt())
        out.writeByte(type.toInt())
        out.writeInt(payload.size)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    /** 返回 Pair<type, payload>，若连接断开抛 IOException */
    fun readFrame(inp: DataInputStream): Pair<Byte, ByteArray> {
        val magic = inp.readInt()
        if (magic != MAGIC) throw IOException("无效魔数: 0x${magic.toString(16)}")
        val ver = inp.readByte()
        if (ver != VERSION) throw IOException("不支持的版本: $ver")
        val type = inp.readByte()
        val len  = inp.readInt()
        if (len < 0 || len > MAX_PACKET_BYTES) throw IOException("帧长度非法: $len")
        val payload = if (len > 0) {
            val buf = ByteArray(len)
            inp.readFully(buf)
            buf
        } else ByteArray(0)
        return Pair(type, payload)
    }
}

// ============================================================
// 握手协议
// HELLO payload:  passcodeKey(32) | ecPubKey(91)
// ACK   payload:  ecPubKey(91) | assignedVpnIp(4) | peerVpnIp(4)
// ============================================================
data class MeshHandshakeResult(
    val sharedKey: ByteArray,
    val localVpnIp: String,
    val peerVpnIp : String
)

fun serverHandshake(
    out: DataOutputStream,
    inp: DataInputStream,
    passcode: String,
    serverVpnIp: String,
    clientVpnIp: String
): MeshHandshakeResult {
    // 读取客户端 HELLO
    val (type, payload) = MeshFrameCodec.readFrame(inp)
    if (type != PKT_HANDSHAKE_HELLO) throw IOException("期望 HELLO，收到 $type")
    if (payload.size < 32 + 1) throw IOException("HELLO 载荷过短")

    val theirPasscodeKey = payload.copyOfRange(0, 32)
    val myPasscodeKey    = MeshCrypto.derivePasscodeKey(passcode)
    if (!theirPasscodeKey.contentEquals(myPasscodeKey)) throw IOException("密码不匹配，拒绝连接")

    val clientPubKeyBytes = payload.copyOfRange(32, payload.size)
    val clientPubKey      = MeshCrypto.decodePublicKey(clientPubKeyBytes)

    // 生成服务端密钥对
    val serverKp     = MeshCrypto.generateEcKeyPair()
    val serverPubKey = MeshCrypto.encodePublicKey(serverKp.public)

    // 派生共享密钥
    val sharedKey = MeshCrypto.deriveSharedKey(serverKp, clientPubKey)

    // 发送 ACK: serverPubKey | serverVpnIp(4字节) | clientVpnIp(4字节)
    val serverIpBytes = ipToBytes(serverVpnIp)
    val clientIpBytes = ipToBytes(clientVpnIp)
    val ackPayload = serverPubKey + serverIpBytes + clientIpBytes
    MeshFrameCodec.writeFrame(out, PKT_HANDSHAKE_ACK, ackPayload)

    return MeshHandshakeResult(sharedKey, serverVpnIp, clientVpnIp)
}

fun clientHandshake(
    out: DataOutputStream,
    inp: DataInputStream,
    passcode: String
): MeshHandshakeResult {
    // 生成客户端密钥对
    val clientKp      = MeshCrypto.generateEcKeyPair()
    val clientPubKey  = MeshCrypto.encodePublicKey(clientKp.public)
    val passcodeKey   = MeshCrypto.derivePasscodeKey(passcode)

    // 发送 HELLO: passcodeKey(32) | ecPubKey
    val helloPayload = passcodeKey + clientPubKey
    MeshFrameCodec.writeFrame(out, PKT_HANDSHAKE_HELLO, helloPayload)

    // 读取 ACK
    val (type, payload) = MeshFrameCodec.readFrame(inp)
    if (type != PKT_HANDSHAKE_ACK) throw IOException("期望 ACK，收到 $type")
    if (payload.size < 91 + 8) throw IOException("ACK 载荷过短")

    val serverPubKeyBytes = payload.copyOfRange(0, payload.size - 8)
    val serverPubKey      = MeshCrypto.decodePublicKey(serverPubKeyBytes)
    val sharedKey         = MeshCrypto.deriveSharedKey(clientKp, serverPubKey)

    val serverVpnIp = bytesToIp(payload.copyOfRange(payload.size - 8, payload.size - 4))
    val clientVpnIp = bytesToIp(payload.copyOfRange(payload.size - 4, payload.size))

    return MeshHandshakeResult(sharedKey, clientVpnIp, serverVpnIp)
}

// ============================================================
// IP 工具
// ============================================================
fun ipToBytes(ip: String): ByteArray {
    val parts = ip.trim().split(".")
    require(parts.size == 4) { "无效 IPv4: $ip" }
    return ByteArray(4) { i -> parts[i].toInt().toByte() }
}

fun bytesToIp(bytes: ByteArray): String {
    require(bytes.size == 4)
    return bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
}

// ============================================================
// 隧道核心
// ============================================================
class MeshTunnel(
    private val socket: Socket,
    private val sharedKey: ByteArray,
    private val onPacketReceived: (ByteArray) -> Unit,
    private val onDisconnected: (String) -> Unit,
    // 复用握手阶段已创建的流，避免双重 BufferedStream 导致数据错位
    existingOut: DataOutputStream? = null,
    existingInp: DataInputStream? = null
) {
    private val out = existingOut ?: DataOutputStream(socket.getOutputStream().buffered())
    private val inp = existingInp ?: DataInputStream(socket.getInputStream().buffered())
    @Volatile private var running = false

    // 发送队列：所有发送操作入队，由独立的 sendLoop 线程串行写出，
    // 避免 receiveLoop 线程在 sendPacket 中阻塞（网络慢时 socket 写阻塞会卡死接收）。
    // null 作为毒丸信号通知 sendLoop 退出。
    private val sendQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray?>(2048)

    // 接收派发队列：receiveLoop 解密后入队，由独立 dispatchLoop 线程调用 onPacketReceived，
    // 避免 onPacketReceived 中的阻塞写操作（tunOut / proxySock）卡死接收线程。
    // null 为毒丸信号。
    private val recvQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray?>(2048)

    fun start() {
        running = true
        thread(name = "MeshTunnel-send") { sendLoop() }
        thread(name = "MeshTunnel-dispatch") { dispatchLoop() }
        thread(name = "MeshTunnel-recv") { receiveLoop() }
    }

    /** 派发循环：从队列取已解密包，调用 onPacketReceived，不阻塞接收线程 */
    private fun dispatchLoop() {
        try {
            while (true) {
                val pkt = recvQueue.take() ?: break
                try { onPacketReceived(pkt) }
                catch (e: Exception) { Log.w(TAG, "[隧道派发] onPacketReceived 异常: ${e.message}") }
            }
        } catch (_: InterruptedException) {}
        Log.d(TAG, "[隧道派发] 退出")
    }

    /** 发送循环：从队列取帧并写出，保证发送线程安全且不阻塞接收线程 */
    private fun sendLoop() {
        try {
            while (true) {
                val frame = sendQueue.take() ?: break   // null = 毒丸，退出
                try { out.write(frame); out.flush() }
                catch (e: Exception) { Log.w(TAG, "[隧道发送循环] 写出失败: ${e.message}"); break }
            }
        } catch (_: InterruptedException) {}
        Log.d(TAG, "[隧道发送循环] 退出")
    }

    /** 将一个已编码帧放入发送队列（非阻塞，队列满则丢弃并警告） */
    private fun enqueue(frame: ByteArray) {
        if (!sendQueue.offer(frame)) {
            Log.w(TAG, "[隧道发送] 队列已满，丢弃 ${frame.size} 字节帧")
        }
    }

    /** 发送 TUN 包（加密后封装为 TUNNEL_DATA 帧，异步入队） */
    fun sendPacket(rawIpPacket: ByteArray) {
        if (!running) return
        try {
            if (rawIpPacket.size >= 20) {
                val proto  = rawIpPacket[9].toInt() and 0xFF
                val srcIp  = "${rawIpPacket[12].toInt() and 0xFF}.${rawIpPacket[13].toInt() and 0xFF}.${rawIpPacket[14].toInt() and 0xFF}.${rawIpPacket[15].toInt() and 0xFF}"
                val dstIp  = "${rawIpPacket[16].toInt() and 0xFF}.${rawIpPacket[17].toInt() and 0xFF}.${rawIpPacket[18].toInt() and 0xFF}.${rawIpPacket[19].toInt() and 0xFF}"
                Log.d(TAG, "[隧道→发送] proto=$proto src=$srcIp dst=$dstIp len=${rawIpPacket.size}")
            }
            val encrypted = MeshCrypto.encrypt(sharedKey, rawIpPacket)
            val frame = MeshFrameCodec.buildFrame(PKT_TUNNEL_DATA, encrypted)
            enqueue(frame)
            Log.d(TAG, "[隧道→发送] 加密后 ${encrypted.size} 字节已入队")
        } catch (e: Exception) {
            Log.w(TAG, "[隧道→发送] 失败: ${e.message}")
        }
    }

    /** 发送 keepalive（异步入队） */
    fun sendKeepalive() {
        if (!running) return
        runCatching { enqueue(MeshFrameCodec.buildFrame(PKT_KEEPALIVE, ByteArray(0))) }
    }

    fun close(reason: String = "主动断开") {
        running = false
        runCatching { enqueue(MeshFrameCodec.buildFrame(PKT_DISCONNECT, reason.toByteArray())) }
        // 投入毒丸让 sendLoop 和 dispatchLoop 退出，给少量时间让 DISCONNECT 帧写出
        sendQueue.offer(null)
        recvQueue.offer(null)
        Thread.sleep(50)
        runCatching { socket.close() }
    }

    private fun receiveLoop() {
        Log.d(TAG, "[隧道←接收] receiveLoop 启动")
        var frameCount = 0L
        try {
            while (running) {
                val (type, payload) = MeshFrameCodec.readFrame(inp)
                frameCount++
                when (type) {
                    PKT_TUNNEL_DATA -> {
                        Log.d(TAG, "[隧道←接收] 收到 TUNNEL_DATA 帧 encLen=${payload.size} total=$frameCount")
                        val decrypted = MeshCrypto.decrypt(sharedKey, payload)
                        Log.d(TAG, "[隧道←接收] 解密后 ${decrypted.size} 字节，入派发队列")
                        if (!recvQueue.offer(decrypted)) {
                            Log.w(TAG, "[隧道←接收] 派发队列已满，丢弃包")
                        }
                    }
                    PKT_KEEPALIVE -> {
                        Log.d(TAG, "[隧道←接收] 收到 KEEPALIVE total=$frameCount")
                    }
                    PKT_DISCONNECT -> {
                        val msg = payload.toString(Charsets.UTF_8)
                        Log.i(TAG, "[隧道←接收] 对端主动断开: $msg")
                        running = false
                        onDisconnected("对端断开: $msg")
                        return
                    }
                    else -> Log.w(TAG, "[隧道←接收] 未知帧类型: $type")
                }
            }
        } catch (e: IOException) {
            if (running) {
                Log.e(TAG, "[隧道←接收] IOException(收了${frameCount}帧后): ${e.message}")
                running = false
                onDisconnected("连接中断: ${e.message}")
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "[隧道←接收] Exception(收了${frameCount}帧后): ${e.message}", e)
                running = false
                onDisconnected("错误: ${e.message}")
            }
        } finally {
            Log.d(TAG, "[隧道←接收] receiveLoop 退出，共收 $frameCount 帧")
        }
    }
}

// ============================================================
// 端口映射规则
// 格式：listenIp:listenPort:targetIp:targetPort
// 含义：在本端 VPN IP listenIp:listenPort 监听，收到连接后转发到对端内网 targetIp:targetPort
// 例：192.168.100.2:5000:10.62.48.99:8989
//   → 本端监听 192.168.100.2:5000，服务端访问该地址即可到达客户端内网 10.62.48.99:8989
// ============================================================
data class PortMapping(
    val targetIp  : String,   // 对端内网目标 IP
    val targetPort: Int,      // 对端内网目标端口
    val listenIp  : String,   // VPN 上监听的 IP（通常是本端 VPN IP）
    val listenPort: Int       // VPN 上监听的端口
) {
    override fun toString() = "$listenIp:$listenPort:$targetIp:$targetPort"
    companion object {
        fun fromString(s: String): PortMapping? {
            val parts = s.trim().split(":")
            if (parts.size != 4) return null
            val lPort = parts[1].toIntOrNull() ?: return null
            val tPort = parts[3].toIntOrNull() ?: return null
            return PortMapping(parts[2], tPort, parts[0], lPort)
        }
    }
}

// ============================================================
// 会话状态
// ============================================================
enum class MeshRole { SERVER, CLIENT }

data class MeshSessionConfig(
    val role        : MeshRole,
    val passcode    : String,
    // Server 端参数
    val listenPort  : Int    = 7890,
    val serverVpnIp : String = "192.168.100.1",
    val clientVpnIp : String = "192.168.100.2",
    val subnetMask  : String = "255.255.255.0",
    // Client 端参数
    val serverHost  : String = "",
    val serverPort  : Int    = 7890,
    // 公共
    val mtu         : Int    = 1400,
    val keepaliveIntervalSec: Int = 20,
    // 内网互访（两端各自的局域网段，逗号分隔多段，如 "192.168.1.0/24"）
    val serverLanCidrs: String = "",   // 服务端内网段，客户端可访问
    val clientLanCidrs: String = "",   // 客户端内网段，服务端可访问
    // 服务端公网 IP（供生成二维码时内嵌）
    val publicIpv4  : String = "",
    val publicIpv6  : String = "",
    // 端口映射规则（逗号分隔，每条格式 listenIp:listenPort:targetIp:targetPort）
    val portMappings: String = ""
)

