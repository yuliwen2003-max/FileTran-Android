package com.yuliwen.filetran

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * WireGuard VPN Service
 *
 * 修复要点：
 * 1. 收发使用同一个 DatagramSocket，但通过队列解耦（send 在主线程入队，专用发送线程出队发送）
 * 2. tun 读取用专用 Java Thread（非协程），避免阻塞协程线程池
 * 3. UDP 接收用专用 Java Thread，soTimeout=500ms 轮询
 * 4. 加密数据包发送不与接收竞争 socket
 */
class WireGuardVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.yuliwen.filetran.WG_START"
        const val ACTION_STOP  = "com.yuliwen.filetran.WG_STOP"
        const val EXTRA_CONFIG_ID = "wg_config_id"
        const val NOTIF_CHANNEL   = "wg_vpn_channel"
        const val NOTIF_ID        = 9010
        const val BROADCAST_STATE = "com.yuliwen.filetran.WG_STATE"
        const val EXTRA_STATE     = "state"
        const val EXTRA_MESSAGE   = "message"
        const val EXTRA_HANDSHAKE = "handshake"

        private const val MSG_HANDSHAKE_INIT = 0x01
        private const val MSG_HANDSHAKE_RESP = 0x02
        private const val MSG_DATA           = 0x04
        private const val MSG_KEEPALIVE      = 0x08
        private const val TAG = "WgVpnService"

        @Volatile var isRunning = false
            private set
        @Volatile var currentConfigId: String? = null
            private set
    }

    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunFd: ParcelFileDescriptor? = null
    // 单一 DatagramSocket，所有收发共用，通过同步保证线程安全
    private var udpSock: DatagramSocket? = null
    private val stopping = AtomicBoolean(false)
    private val txBytes  = AtomicLong(0)
    private val rxBytes  = AtomicLong(0)
    private var lastHandshakeMs = 0L
    private var sessionReady    = false
    // 自动重连：由 keepalive 协程设置，handlePacket 每次收到包时调用更新时间戳
    @Volatile private var lastRxTimeRef: (() -> Unit)? = null

    // 会话密钥
    @Volatile private var sendKey: ByteArray? = null
    @Volatile private var recvKey: ByteArray? = null
    private val sendCounter = AtomicLong(0)

    // 服务端：记录客户端真实地址
    @Volatile private var clientAddr: InetSocketAddress? = null

    // 发送队列（解耦 tun 读取线程与 socket 发送）
    private val sendQueue = LinkedBlockingQueue<DatagramPacket>(512)

    // 工作线程引用（用于停止）
    private var tunReadThread: Thread? = null
    private var udpRecvThread: Thread? = null
    private var udpSendThread: Thread? = null

    // -----------------------------------------------------------------------
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP  -> { stopVpn(); return START_NOT_STICKY }
            ACTION_START -> {
                DhTest.run()
                val id = intent.getStringExtra(EXTRA_CONFIG_ID) ?: run {
                    broadcastState("error", "缺少配置 ID"); stopSelf()
                    return START_NOT_STICKY
                }
                val cfg = WireGuardConfigStore.loadAll(this)
                    .firstOrNull { it.id == id } ?: run {
                    broadcastState("error", "找不到配置"); stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIF_ID, buildNotification("WireGuard 连接中…"))
                scope.launch { runVpn(cfg) }
            }
        }
        return START_STICKY
    }

    // -----------------------------------------------------------------------
    // 主流程
    // -----------------------------------------------------------------------
    private suspend fun runVpn(cfg: WgConfig) {
        stopping.set(false)
        txBytes.set(0); rxBytes.set(0)
        sessionReady = false; clientAddr = null
        sendKey = null; recvKey = null; sendCounter.set(0)
        sendQueue.clear()
        try {
            // 1. 建立 tun
            val pfd = buildTun(cfg) ?: run {
                broadcastState("error", "无法建立 VPN 隧道")
                stopSelf(); return
            }
            tunFd = pfd

            // 2. 建立 UDP socket（单一，protect 后收发共用）
            val sock = DatagramSocket(if (cfg.iface.listenPort > 0) cfg.iface.listenPort else 0)
            protect(sock)                // 关键：让 VPN 流量不回环
            sock.soTimeout = 500
            udpSock = sock
            Log.i(TAG, "UDP socket bound to port ${sock.localPort}")

            isRunning = true; currentConfigId = cfg.id
            updateNotification("WireGuard 已连接: ${cfg.name}")
            broadcastState("connected", "已连接到 ${cfg.name}")

            val isClient = cfg.peers.any { it.endpoint.isNotBlank() }

            // 3. 客户端：先派生密钥，再握手
            if (isClient) {
                val peer = cfg.peers.first { it.endpoint.isNotBlank() }
                val peerAddr = resolvePeer(peer.endpoint)
                if (peerAddr != null) {
                    deriveClientKeys(cfg, peer)
                    doHandshake(sock, peerAddr, cfg)
                } else {
                    broadcastState("error", "无法解析服务端: ${peer.endpoint}")
                }
            } else {
                broadcastState("connected", "服务端就绪，监听端口 ${sock.localPort}")
                // 服务端：sessionReady 由收到握手包后置 true，tun 读取线程等待即可
                // 若配置了 natEnabled，同步执行 root iptables（在 IO 线程，不阻塞协程主逻辑）
                if (cfg.iface.natEnabled) {
                    val subnet = toNetworkCidr(cfg.iface.address)
                    val outIface = detectOutIface()
                    Log.i(TAG, "NAT requested: subnet=$subnet outIface=$outIface")
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        enableNat(subnet, outIface)
                    }
                }
            }

            val tunIn  = FileInputStream(pfd.fileDescriptor)
            val tunOut = FileOutputStream(pfd.fileDescriptor)

            // 4. tun 读取线程：IP包 → 加密 → 放入发送队列
            // 服务端侧：sessionReady 在收到握手后才为 true，届时 clientAddr 也已记录
            tunReadThread = Thread({
                val buf = ByteArray(65535)
                var tunReadCount = 0L
                while (!stopping.get()) {
                    try {
                        val len = tunIn.read(buf)
                        if (len <= 0) continue
                        tunReadCount++
                        if (tunReadCount <= 5 || tunReadCount % 100 == 0L)
                            Log.e(TAG, "tun read #$tunReadCount len=$len sessionReady=$sessionReady sendKey=${sendKey != null}")
                        if (!sessionReady) { Log.v(TAG, "tun: not ready, drop"); continue }
                        val dest = getDestAddr(cfg, isClient)
                        if (dest == null) { Log.v(TAG, "tun: no dest addr, drop"); continue }
                        val pkt = buildDataPacket(buf, len, dest)
                        if (pkt == null) { Log.w(TAG, "tun: buildDataPacket null (sendKey=${sendKey != null})"); continue }
                        if (!sendQueue.offer(pkt, 100, TimeUnit.MILLISECONDS)) {
                            Log.w(TAG, "sendQueue full, dropping")
                        } else {
                            txBytes.addAndGet(len.toLong())
                            if (tunReadCount <= 5) Log.e(TAG, "tun: enqueued to $dest")
                        }
                    } catch (e: Exception) {
                        if (!stopping.get()) Log.w(TAG, "tunRead: ${e.message}")
                    }
                }
            }, "wg-tun-read").also { it.isDaemon = true; it.start() }

            // 5. UDP 发送线程：从队列取包 → socket.send
            udpSendThread = Thread({
                var sendCount = 0L
                while (!stopping.get()) {
                    try {
                        val pkt = sendQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        sock.send(pkt)
                        sendCount++
                        if (sendCount <= 5 || sendCount % 100 == 0L)
                            Log.e(TAG, "udpSend #$sendCount to ${pkt.address}:${pkt.port} len=${pkt.length}")
                    } catch (e: Exception) {
                        if (!stopping.get()) Log.w(TAG, "udpSend: ${e.message}")
                    }
                }
            }, "wg-udp-send").also { it.isDaemon = true; it.start() }

            // 6. UDP 接收线程：socket.receive → 解密 → 写 tun
            udpRecvThread = Thread({
                val buf  = ByteArray(65535)
                val dpkt = DatagramPacket(buf, buf.size)
                var recvCount = 0L
                while (!stopping.get()) {
                    try {
                        dpkt.length = buf.size
                        sock.receive(dpkt)
                        recvCount++
                        if (recvCount <= 10 || recvCount % 100 == 0L)
                            Log.e(TAG, "udpRecv #$recvCount from ${dpkt.address}:${dpkt.port} len=${dpkt.length} type=${buf[0].toInt() and 0xFF}")
                        val from = InetSocketAddress(dpkt.address, dpkt.port)
                        handlePacket(buf, dpkt.length, tunOut, sock, from, cfg)
                    } catch (e: SocketTimeoutException) {
                        // 正常超时
                    } catch (e: Exception) {
                        if (!stopping.get()) Log.w(TAG, "udpRecv: ${e.message}")
                    }
                }
            }, "wg-udp-recv").also { it.isDaemon = true; it.start() }

            // 7. keepalive + 状态广播 + 自动重连协程
            val hbJob = scope.launch {
                val ka = cfg.peers.firstOrNull()?.persistentKeepalive?.takeIf { it > 0 } ?: 25
                var lastKa = System.currentTimeMillis()
                // 自动重连参数（仅客户端）
                val reconnectIntervalMs = 15_000L   // 15 秒无响应则重握手
                val maxReconnectAttempts = 0         // 0 = 无限重试
                var reconnectCount = 0
                var lastRxTime = System.currentTimeMillis()  // 记录最后一次收到包的时间

                // 让 handlePacket 能更新 lastRxTime
                lastRxTimeRef = { lastRxTime = System.currentTimeMillis() }

                while (isActive && !stopping.get()) {
                    delay(5_000)
                    val now = System.currentTimeMillis()

                    // --- keepalive 发送 ---
                    if (sessionReady) {
                        val dest = getDestAddr(cfg, isClient)
                        if (dest != null && (now - lastKa) >= ka * 1000L) {
                            enqueueKeepalive(dest); lastKa = now
                        }
                    }

                    // --- 客户端自动重连检测 ---
                    if (isClient) {
                        val silenceMs = now - lastRxTime
                        if (silenceMs >= reconnectIntervalMs) {
                            // 超过阈值未收到任何包，触发重握手
                            reconnectCount++
                            sessionReady = false
                            broadcastState("connected",
                                "⟳ 检测到连接中断（${silenceMs / 1000}s 无响应），第 $reconnectCount 次重连…")
                            Log.w(TAG, "Reconnect #$reconnectCount after ${silenceMs}ms silence")
                            val peer = cfg.peers.firstOrNull { it.endpoint.isNotBlank() }
                            val peerAddr = peer?.let { resolvePeer(it.endpoint) }
                            if (peerAddr != null) {
                                withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    deriveClientKeys(cfg, peer)
                                    doHandshake(sock, peerAddr, cfg)
                                }
                                if (sessionReady) {
                                    lastRxTime = System.currentTimeMillis()
                                    lastKa     = System.currentTimeMillis()
                                    broadcastState("connected", "✅ 第 $reconnectCount 次重连成功")
                                    Log.i(TAG, "Reconnect #$reconnectCount OK")
                                } else {
                                    // 重握手失败，等下一轮继续
                                    lastRxTime = System.currentTimeMillis()  // 重置计时，避免连续轰炸
                                    broadcastState("connected",
                                        "重连握手超时，${reconnectIntervalMs / 1000}s 后再试（已重连 $reconnectCount 次）")
                                }
                            } else {
                                lastRxTime = System.currentTimeMillis()
                                broadcastState("connected", "重连失败：无法解析服务端地址，稍后重试…")
                            }
                        }
                    }

                    // --- 服务端：检测客户端心跳超时，重置 session 等待重连 ---
                    if (!isClient && sessionReady) {
                        val silenceMs = now - lastRxTime
                        if (silenceMs >= reconnectIntervalMs * 2) {  // 服务端容忍时间更长（30s）
                            Log.w(TAG, "Server: client silent for ${silenceMs}ms, reset session")
                            sessionReady = false; clientAddr = null
                            lastRxTime = System.currentTimeMillis()
                            broadcastState("connected",
                                "客户端 ${silenceMs / 1000}s 未响应，已重置会话，等待重连…")
                        }
                    }

                    broadcastHandshakeInfo(cfg, isClient)
                }
            }

            while (!stopping.get()) delay(300)
            hbJob.cancel()

        } catch (e: Exception) {
            Log.e(TAG, "VPN error", e)
            broadcastState("error", "VPN 错误: ${e.message}")
        } finally {
            cleanup()
        }
    }

    // -----------------------------------------------------------------------
    // 握手（客户端主动，最多重试3次）
    // -----------------------------------------------------------------------
    private fun doHandshake(sock: DatagramSocket, peerAddr: InetSocketAddress, cfg: WgConfig) {
        val myPub = runCatching {
            Base64.getDecoder().decode(WireGuardKeyUtil.publicKeyFromPrivate(cfg.iface.privateKey))
        }.getOrDefault(ByteArray(32))
        val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val pkt = ByteArray(44)
        pkt[0] = MSG_HANDSHAKE_INIT.toByte()
        System.arraycopy(myPub, 0, pkt, 4, 32)
        System.arraycopy(nonce, 0, pkt, 36, 8)

        val rbuf = ByteArray(256)
        val rpkt = DatagramPacket(rbuf, rbuf.size)
        val oldTimeout = sock.soTimeout
        sock.soTimeout = 500
        var handshakeDone = false

        for (attempt in 0 until 5) {  // 最多5次，每次等2秒，共10秒
            if (handshakeDone) break
            runCatching {
                sock.send(DatagramPacket(pkt, pkt.size, peerAddr.address, peerAddr.port))
                Log.i(TAG, "HandshakeInit sent attempt ${attempt + 1} to $peerAddr")
                val deadline = System.currentTimeMillis() + 2000L
                while (System.currentTimeMillis() < deadline && !handshakeDone) {
                    try {
                        rpkt.length = rbuf.size
                        sock.receive(rpkt)
                        val type = rbuf[0].toInt() and 0xFF
                        Log.i(TAG, "Handshake recv type=$type from ${rpkt.address}:${rpkt.port}")
                        if (type == MSG_HANDSHAKE_RESP) {
                            sessionReady = true
                            lastHandshakeMs = System.currentTimeMillis()
                            lastRxTimeRef?.invoke()  // 握手成功，重置接收计时
                            handshakeDone = true
                            broadcastState("connected", "握手成功，加密隧道已建立")
                            Log.i(TAG, "HandshakeResp OK from ${rpkt.address}:${rpkt.port}")
                        } else if (type == MSG_HANDSHAKE_INIT) {
                            // 服务端主动发来重握手请求，忽略继续等 RESP
                            Log.i(TAG, "Got INIT during handshake wait, ignoring")
                        }
                    } catch (e: SocketTimeoutException) { /* 继续等 */ }
                }
            }.onFailure { Log.w(TAG, "Handshake attempt ${attempt+1}: ${it.message}") }
        }
        sock.soTimeout = oldTimeout
        if (!handshakeDone) {
            // 握手5次无响应：可能对端未启动，保持 sessionReady=false，等服务端发包触发
            Log.w(TAG, "Handshake: no resp after 5 attempts, sessionReady remains false")
            broadcastState("connected", "等待服务端响应握手…")
        }
    }

    // -----------------------------------------------------------------------
    // 处理入站 UDP 包
    // -----------------------------------------------------------------------
    private fun handlePacket(raw: ByteArray, len: Int, tunOut: FileOutputStream,
                             sock: DatagramSocket, from: InetSocketAddress, cfg: WgConfig) {
        if (len < 1) return
        lastRxTimeRef?.invoke()   // 每次收到任何包都更新接收时间戳，用于重连检测
        when (raw[0].toInt() and 0xFF) {
            MSG_HANDSHAKE_INIT -> {
                if (len < 44) return
                Log.i(TAG, "HandshakeInit from $from")
                clientAddr = from
                val peerPubBytes = raw.copyOfRange(4, 36)
                val peerPubB64   = Base64.getEncoder().encodeToString(peerPubBytes).trim()
                val matchedPeer  = cfg.peers.firstOrNull { it.publicKey.trim() == peerPubB64 }
                Log.e(TAG, "Server: incoming pubkey=$peerPubB64 matchedPeer=${matchedPeer != null} knownPeers=${cfg.peers.map { it.publicKey.trim() }}")
                // 派生服务端密钥（方向与客户端相反）
                runCatching {
                    val priv   = Base64.getDecoder().decode(cfg.iface.privateKey.trim())
                    val shared = Curve25519.dh(priv, peerPubBytes)
                    Log.e(TAG, "Server shared[0..3]: ${shared.take(4).map { it.toInt() and 0xFF }}")
                    Log.e(TAG, "Server peerPub[0..3]: ${peerPubBytes.take(4).map { it.toInt() and 0xFF }}")
                    Log.e(TAG, "Server matchedPeer=${matchedPeer != null} peerPubB64=$peerPubB64")
                    val psk    = if (matchedPeer?.presharedKey?.isNotBlank() == true)
                        Base64.getDecoder().decode(matchedPeer.presharedKey.trim())
                    else ByteArray(32)
                    Log.e(TAG, "Server psk[0..3]: ${psk.take(4).map { it.toInt() and 0xFF }}")
                    val prk = hmacSha256(psk, shared)
                    // 服务端 recv 对应客户端 send（c2s），send 对应客户端 recv（s2c）
                    recvKey = hkdfExpand(prk, "filetran-wg-c2s".toByteArray(), 32)
                    sendKey = hkdfExpand(prk, "filetran-wg-s2c".toByteArray(), 32)
                    Log.e(TAG, "Server recvKey[0..3]: ${recvKey!!.take(4).map { it.toInt() and 0xFF }}")
                    Log.e(TAG, "Server sendKey[0..3]: ${sendKey!!.take(4).map { it.toInt() and 0xFF }}")
                    sendCounter.set(0)
                    Log.i(TAG, "Server keys derived for $peerPubB64")
                }.onFailure { Log.e(TAG, "Server key derivation: ${it.message}") }
                // 回复 HandshakeResp
                val myPub = runCatching {
                    Base64.getDecoder().decode(WireGuardKeyUtil.publicKeyFromPrivate(cfg.iface.privateKey))
                }.getOrDefault(ByteArray(32))
                val resp = ByteArray(36)
                resp[0] = MSG_HANDSHAKE_RESP.toByte()
                System.arraycopy(myPub, 0, resp, 4, 32)
                runCatching { sock.send(DatagramPacket(resp, resp.size, from.address, from.port)) }
                sessionReady = true
                lastHandshakeMs = System.currentTimeMillis()
                broadcastState("connected", "客户端已连接: $from")
            }
            MSG_HANDSHAKE_RESP -> {
                sessionReady = true
                lastHandshakeMs = System.currentTimeMillis()
                Log.i(TAG, "HandshakeResp from $from, sessionReady=true, sendKey=${sendKey != null} recvKey=${recvKey != null}")
                broadcastState("connected", "握手完成，隧道已建立")
            }
            MSG_KEEPALIVE -> {
                lastHandshakeMs = System.currentTimeMillis()
                clientAddr = from
            }
            MSG_DATA -> {
                val rk = recvKey ?: run {
                    // recvKey 为 null：服务端尚未收到握手包，向客户端发送特殊握手请求触发重握手
                    Log.w(TAG, "DATA but recvKey=null from $from, sending handshake request")
                    val reqPkt = byteArrayOf(MSG_HANDSHAKE_INIT.toByte(), 0, 0, 0)
                    runCatching { sock.send(DatagramPacket(reqPkt, reqPkt.size, from.address, from.port)) }
                    return
                }
                if (len < 28) { Log.w(TAG, "DATA too short: $len"); return }
                runCatching {
                    val bb = ByteBuffer.wrap(raw, 0, len).order(ByteOrder.LITTLE_ENDIAN)
                    val header = ByteArray(12); bb.get(header)
                    val payloadLen = bb.int
                    Log.e(TAG, "MSG_DATA payloadLen=$payloadLen remaining=${bb.remaining()}")
                    if (payloadLen <= 0 || payloadLen > 65000) { Log.w(TAG, "invalid payloadLen=$payloadLen"); return }
                    if (bb.remaining() < 12 + payloadLen) { Log.w(TAG, "truncated: need ${12+payloadLen} have ${bb.remaining()}"); return }
                    val nonce = ByteArray(12); bb.get(nonce)
                    val ctext = ByteArray(payloadLen); bb.get(ctext)
                    val plain = aesGcmDecrypt(rk, nonce, ctext, header) ?: run {
                        Log.w(TAG, "AES-GCM decrypt FAILED from $from (key mismatch?)"); return
                    }
                    Log.e(TAG, "MSG_DATA decrypted OK, writing ${plain.size} bytes to tun")
                    tunOut.write(plain)
                    rxBytes.addAndGet(plain.size.toLong())
                    if (clientAddr == null) clientAddr = from
                }.onFailure { Log.w(TAG, "MSG_DATA error: ${it.message}") }
            }
            else -> Log.v(TAG, "Unknown type: ${raw[0].toInt() and 0xFF}")
        }
    }

    // -----------------------------------------------------------------------
    // 构建加密数据包（返回 DatagramPacket）
    // -----------------------------------------------------------------------
    private fun buildDataPacket(data: ByteArray, len: Int,
                                 dest: InetSocketAddress): DatagramPacket? {
        val sk = sendKey ?: return null
        return runCatching {
            val counter = sendCounter.getAndIncrement()
            val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0).putLong(counter).array()
            val plain = data.copyOf(len)
            val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).also {
                it.put(MSG_DATA.toByte())
                it.put(0); it.put(0); it.put(0)
                it.putLong(counter)
            }.array()
            val cipher = aesGcmEncrypt(sk, nonce, plain, header)
            val out = ByteBuffer.allocate(12 + 4 + 12 + cipher.size).order(ByteOrder.LITTLE_ENDIAN)
            out.put(header); out.putInt(cipher.size); out.put(nonce); out.put(cipher)
            DatagramPacket(out.array(), out.capacity(), dest.address, dest.port)
        }.getOrNull()
    }

    private fun enqueueKeepalive(dest: InetSocketAddress) {
        val pkt = byteArrayOf(MSG_KEEPALIVE.toByte(), 0, 0, 0)
        sendQueue.offer(DatagramPacket(pkt, pkt.size, dest.address, dest.port))
    }

    private fun getDestAddr(cfg: WgConfig, isClient: Boolean): InetSocketAddress? =
        if (isClient) resolvePeer(cfg.peers.firstOrNull { it.endpoint.isNotBlank() }?.endpoint)
        else clientAddr

    // -----------------------------------------------------------------------
    // 建立 tun
    // -----------------------------------------------------------------------
    private fun buildTun(cfg: WgConfig): ParcelFileDescriptor? {
        val b = Builder()
        b.setSession(cfg.name)
        b.setMtu(cfg.iface.mtu.takeIf { it > 0 } ?: 1420)
        var hasAddr = false
        cfg.iface.address.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { cidr ->
            val s = cidr.lastIndexOf('/')
            if (s > 0) runCatching {
                b.addAddress(cidr.substring(0, s).trim(), cidr.substring(s+1).toIntOrNull() ?: 24)
                hasAddr = true
            }
        }
        if (!hasAddr) return null
        // DNS：优先用配置里的，若为空则添加公共 DNS 保证域名解析正常
        val dnsServers = cfg.iface.dns.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (dnsServers.isNotEmpty()) {
            dnsServers.forEach { runCatching { b.addDnsServer(it) } }
        } else {
            // 未配置 DNS 时自动添加公共 DNS，避免域名解析走默认网络失败
            runCatching { b.addDnsServer("8.8.8.8") }
            runCatching { b.addDnsServer("8.8.4.4") }
            runCatching { b.addDnsServer("2001:4860:4860::8888") }
        }
        var hasRoute = false
        cfg.peers.forEach { peer ->
            peer.allowedIPs.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { cidr ->
                val s = cidr.lastIndexOf('/')
                if (s > 0) runCatching {
                    b.addRoute(cidr.substring(0, s).trim(), cidr.substring(s+1).toIntOrNull() ?: 32)
                    hasRoute = true
                }
            }
        }
        if (!hasRoute) { runCatching { b.addRoute("0.0.0.0", 0) }; runCatching { b.addRoute("::", 0) } }
        b.addDisallowedApplication(packageName)
        return runCatching { b.establish() }.getOrNull()
    }

    // -----------------------------------------------------------------------
    // 密钥派生（客户端）
    // -----------------------------------------------------------------------
    private fun deriveClientKeys(cfg: WgConfig, peer: WgPeer) {
        runCatching {
            val priv   = Base64.getDecoder().decode(cfg.iface.privateKey.trim())
            val pub    = Base64.getDecoder().decode(peer.publicKey.trim())
            val shared = Curve25519.dh(priv, pub)
            Log.e(TAG, "Client shared[0..3]: ${shared.take(4).map { it.toInt() and 0xFF }}")
            Log.e(TAG, "Client peerPub[0..3]: ${pub.take(4).map { it.toInt() and 0xFF }}")
            val psk    = if (peer.presharedKey.isNotBlank())
                Base64.getDecoder().decode(peer.presharedKey.trim()) else ByteArray(32)
            Log.e(TAG, "Client psk[0..3]: ${psk.take(4).map { it.toInt() and 0xFF }}")
            val prk = hmacSha256(psk, shared)
            // 客户端 send 对应服务端 recv，标签必须与服务端相反
            sendKey = hkdfExpand(prk, "filetran-wg-c2s".toByteArray(), 32)
            recvKey = hkdfExpand(prk, "filetran-wg-s2c".toByteArray(), 32)
            Log.e(TAG, "Client sendKey[0..3]: ${sendKey!!.take(4).map { it.toInt() and 0xFF }}")
            Log.e(TAG, "Client recvKey[0..3]: ${recvKey!!.take(4).map { it.toInt() and 0xFF }}")
            sendCounter.set(0)
            Log.i(TAG, "Client keys derived OK")
        }.onFailure {
            Log.e(TAG, "deriveClientKeys failed: ${it.message}")
            val d = MessageDigest.getInstance("SHA-256")
            val priv = runCatching { Base64.getDecoder().decode(cfg.iface.privateKey.trim()) }.getOrDefault(ByteArray(32))
            sendKey = d.digest(priv + "send".toByteArray())
            d.reset()
            recvKey = d.digest(priv + "recv".toByteArray())
            sendCounter.set(0)
        }
    }

    // -----------------------------------------------------------------------
    // 网络工具
    // -----------------------------------------------------------------------
    private fun resolvePeer(endpoint: String?): InetSocketAddress? {
        if (endpoint.isNullOrBlank()) return null
        return runCatching {
            if (endpoint.startsWith("[")) {
                val e = endpoint.indexOf("]")
                InetSocketAddress(InetAddress.getByName(endpoint.substring(1, e)), endpoint.substring(e+2).toInt())
            } else {
                val c = endpoint.lastIndexOf(':')
                InetSocketAddress(InetAddress.getByName(endpoint.substring(0, c)), endpoint.substring(c+1).toInt())
            }
        }.getOrNull()
    }

    // -----------------------------------------------------------------------
    // AES-256-GCM
    // -----------------------------------------------------------------------
    private fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad); return c.doFinal(plain)
    }

    private fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, cipher: ByteArray, aad: ByteArray): ByteArray? =
        runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            c.updateAAD(aad); c.doFinal(cipher)
        }.getOrNull()

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256")); return m.doFinal(data)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, len: Int): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(len); var off = 0; var t = ByteArray(0); var n = 1
        while (off < len) {
            m.reset(); m.update(t); m.update(info); m.update(n.toByte())
            t = m.doFinal()
            val cp = minOf(t.size, len - off)
            System.arraycopy(t, 0, out, off, cp); off += cp; n++
        }
        return out
    }

    // -----------------------------------------------------------------------
    // 状态广播
    // -----------------------------------------------------------------------
    private fun broadcastHandshakeInfo(cfg: WgConfig, isClient: Boolean) {
        val elapsed = (System.currentTimeMillis() - lastHandshakeMs) / 1000
        val peer = cfg.peers.firstOrNull()
        val info = buildString {
            appendLine("对端公钥：${peer?.publicKey?.take(16)?.let { "$it…" } ?: "(无)"}")
            if (lastHandshakeMs > 0)
                appendLine("最近握手：${if (elapsed < 5) "刚刚" else "${elapsed}秒前"}")
            else appendLine("最近握手：等待中…")
            if (isClient)
                appendLine("服务端：${peer?.endpoint?.ifBlank { "(未设置)" } ?: "(无)"}")
            else {
                if (clientAddr != null) appendLine("客户端：$clientAddr")
                else appendLine("客户端：等待连接…")
                appendLine("本机端口：${udpSock?.localPort ?: cfg.iface.listenPort}")
            }
            append("上行：${txBytes.get() / 1024} KB  下行：${rxBytes.get() / 1024} KB")
        }
        val msg = when {
            !sessionReady && !isClient -> "服务端就绪，等待客户端连接…"
            !sessionReady -> "握手中…"
            isClient -> "已连接到 ${cfg.name}"
            clientAddr != null -> "客户端已连接"
            else -> "服务端就绪，等待客户端…"
        }
        broadcastState("connected", msg, info)
    }

    private fun broadcastState(state: String, message: String, handshake: String = "") {
        sendBroadcast(Intent(BROADCAST_STATE).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_HANDSHAKE, handshake)
            setPackage(packageName)
        })
    }

    // -----------------------------------------------------------------------
    // Root NAT 管理
    // -----------------------------------------------------------------------
    // 记录当前激活的 NAT 配置，用于停止时精确清理
    @Volatile private var natVpnSubnet: String = ""
    @Volatile private var natOutIface:  String = ""

    /**
     * 将接口地址 CIDR（如 10.0.0.1/24）转换为网络地址 CIDR（如 10.0.0.0/24）
     */
    private fun toNetworkCidr(ifaceAddr: String): String {
        val slash = ifaceAddr.lastIndexOf('/')
        if (slash < 0) return ifaceAddr
        val prefix = ifaceAddr.substring(slash + 1).toIntOrNull() ?: 24
        val parts  = ifaceAddr.substring(0, slash).split(".")
        if (parts.size != 4) return ifaceAddr
        val ip   = parts.map { it.toIntOrNull() ?: 0 }
        val mask = if (prefix >= 32) -1 else (-1 shl (32 - prefix))
        val net  = listOf(
            ip[0].and(mask.ushr(24).and(0xFF)),
            ip[1].and(mask.ushr(16).and(0xFF)),
            ip[2].and(mask.ushr(8).and(0xFF)),
            ip[3].and(mask.and(0xFF))
        ).joinToString(".")
        return "$net/$prefix"
    }

    /**
     * 通过 su 执行 shell 命令，返回 (exitCode, stdout+stderr)
     */
    private fun runAsRoot(vararg cmds: String): Pair<Int, String> {
        val script = cmds.joinToString("\n")
        return runCatching {
            val proc = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            Pair(code, out)
        }.getOrElse { Pair(-1, it.message ?: "exception") }
    }

    /**
     * 探测默认出口网卡（排除 tun/wlan0 虚拟接口候选列表）
     * 优先取默认路由对应的网卡，回退到第一个 UP 且非 lo/tun 的接口
     */
    private fun detectOutIface(): String {
        // 尝试从默认路由获取
        val (_, route) = runAsRoot("ip route show default")
        val m = Regex("dev\\s+(\\S+)").find(route)
        if (m != null) return m.groupValues[1]
        // 回退：遍历网络接口
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .firstOrNull { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
                ?.name ?: "wlan0"
        }.getOrDefault("wlan0")
    }

    /**
     * 启用 NAT：
     * 1. 开启 IPv4 转发
     * 2. 添加 iptables MASQUERADE（幂等：先删后加）
     * 3. 添加 FORWARD 放行规则
     * 全程同步执行，每步均广播进度提示。
     */
    private fun enableNat(vpnSubnet: String, outIface: String) {
        broadcastState("connected", "[NAT] 正在配置…\nsubnet=$vpnSubnet  out=$outIface")
        Log.i(TAG, "enableNat: subnet=$vpnSubnet out=$outIface")

        // Step 1: 开启 IP 转发
        broadcastState("connected", "[NAT] 步骤 1/3：开启 IP 转发…")
        val (r1, o1) = runAsRoot("sysctl -w net.ipv4.ip_forward=1")
        Log.i(TAG, "ip_forward: code=$r1 out=$o1")
        if (r1 != 0) {
            broadcastState("connected", "[NAT] ❌ IP 转发开启失败（需要 root）\n$o1")
            return
        }

        // Step 2: iptables MASQUERADE（幂等：先删后加）
        broadcastState("connected", "[NAT] 步骤 2/3：配置 MASQUERADE 规则…")
        val (r2, o2) = runAsRoot(
            "iptables -t nat -D POSTROUTING -s $vpnSubnet -o $outIface -j MASQUERADE 2>/dev/null || true",
            "iptables -t nat -A POSTROUTING -s $vpnSubnet -o $outIface -j MASQUERADE"
        )
        Log.i(TAG, "MASQUERADE: code=$r2 out=$o2")
        if (r2 != 0) {
            broadcastState("connected", "[NAT] ❌ MASQUERADE 规则添加失败\n$o2")
            return
        }

        // Step 3: FORWARD 放行
        broadcastState("connected", "[NAT] 步骤 3/3：配置 FORWARD 放行规则…")
        val (r3, o3) = runAsRoot(
            "iptables -D FORWARD -s $vpnSubnet -j ACCEPT 2>/dev/null || true",
            "iptables -D FORWARD -d $vpnSubnet -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || true",
            "iptables -A FORWARD -s $vpnSubnet -j ACCEPT",
            "iptables -A FORWARD -d $vpnSubnet -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT"
        )
        Log.i(TAG, "FORWARD: code=$r3 out=$o3")
        if (r3 != 0) {
            broadcastState("connected", "[NAT] ❌ FORWARD 规则添加失败\n$o3")
            return
        }

        natVpnSubnet = vpnSubnet
        natOutIface  = outIface
        broadcastState("connected", "[NAT] ✅ 配置成功\nsubnet=$vpnSubnet  out=$outIface\nIP 转发已开启，MASQUERADE 已生效")
        Log.i(TAG, "NAT enabled OK")
    }

    /**
     * 停止时清理 NAT 规则，避免残留
     */
    private fun disableNat() {
        val subnet = natVpnSubnet; val iface = natOutIface
        if (subnet.isBlank() || iface.isBlank()) return
        broadcastState("disconnected", "[NAT] 正在清理 iptables 规则…\nsubnet=$subnet  out=$iface")
        Log.i(TAG, "disableNat: subnet=$subnet iface=$iface")
        val (code, out) = runAsRoot(
            "iptables -t nat -D POSTROUTING -s $subnet -o $iface -j MASQUERADE 2>/dev/null || true",
            "iptables -D FORWARD -s $subnet -j ACCEPT 2>/dev/null || true",
            "iptables -D FORWARD -d $subnet -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || true"
            // IP 转发保持开启（其他应用可能依赖），仅清理 iptables 规则
        )
        natVpnSubnet = ""; natOutIface = ""
        if (code == 0) {
            Log.i(TAG, "NAT disabled OK")
            broadcastState("disconnected", "[NAT] ✅ iptables 规则已清理，IP 转发保持开启")
        } else {
            Log.e(TAG, "NAT disable failed: $out")
            broadcastState("disconnected", "[NAT] ⚠️ 规则清理失败（code=$code）\n$out")
        }
    }

    // -----------------------------------------------------------------------
    // 停止 / 清理
    // -----------------------------------------------------------------------
    private fun stopVpn() { stopping.set(true) }

    private fun cleanup() {
        stopping.set(true)
        lastRxTimeRef = null   // 清除重连回调，防止泄漏
        tunReadThread?.interrupt(); udpSendThread?.interrupt(); udpRecvThread?.interrupt()
        runCatching { udpSock?.close() };  udpSock = null
        runCatching { tunFd?.close() };    tunFd   = null
        sendKey = null; recvKey = null
        // 同步清理 iptables 规则（在 IO 线程执行，完成后再广播断开）
        if (natVpnSubnet.isNotBlank()) {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                disableNat()
            }
        }
        isRunning = false; currentConfigId = null; sessionReady = false
        broadcastState("disconnected", "VPN 已断开")
        updateNotification("WireGuard 已断开")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    override fun onDestroy() {
        stopping.set(true); scope.cancel(); cleanup(); super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // 通知
    // -----------------------------------------------------------------------
    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, WireGuardVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mainPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("FileTran WireGuard")
            .setContentText(text)
            .setContentIntent(mainPi)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(
                    this, android.R.drawable.ic_menu_close_clear_cancel),
                "断开", stopPi).build())
            .setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(NOTIF_CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(
                    NOTIF_CHANNEL, "WireGuard VPN", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
