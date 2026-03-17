package com.yuliwen.filetran

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MeshNetService : VpnService() {

    companion object {
        const val ACTION_START        = "com.yuliwen.filetran.MESH_START"
        const val ACTION_STOP         = "com.yuliwen.filetran.MESH_STOP"
        const val EXTRA_CONFIG        = "mesh_config_json"
        const val BROADCAST_STATE     = "com.yuliwen.filetran.MESH_STATE"
        const val EXTRA_RUNNING       = "running"
        const val EXTRA_MESSAGE       = "message"
        const val EXTRA_LOCAL_VPN_IP  = "local_vpn_ip"
        const val EXTRA_PEER_VPN_IP   = "peer_vpn_ip"
        const val EXTRA_RECONNECTING  = "reconnecting"

        private const val CHANNEL_ID  = "mesh_net_vpn"
        private const val NOTIF_ID    = 9901
        private const val TAG         = "MeshNetService"

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var localVpnIp: String = ""
            private set
        @Volatile var peerVpnIp: String = ""
            private set
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null   // 持久化 TUN 写入流，避免重复 open fd
    private var tunnel: MeshTunnel? = null
    private var serverSocket: ServerSocket? = null
    private var mainThread: Thread? = null
    private val stopping = AtomicBoolean(false)
    private val tearingDown = AtomicBoolean(false)
    // 自动重连
    private var reconnectEnabled = false
    private var lastCfg: MeshSessionConfig? = null
    private var reconnectAttempt = 0
    private val maxReconnectAttempts = 5
    private val reconnectDelayMs = longArrayOf(3000, 5000, 10000, 20000, 30000)
    // 保存已安装的 iptables/route 规则，用于断开时清理
    private val rootCleanupCmds = mutableListOf<String>()

    // 应用层 NAT：本端内网段列表 & 本机 wlan0/物理 IP（用于 SNAT）
    // key=对端VPN IP, value=本机物理IP（用于改写源地址）
    @Volatile private var myLanCidrList: List<String> = emptyList()
    // peerLanCidrList：对端内网段列表（服务端角色时=clientLanCidrs，客户端角色时=serverLanCidrs）
    @Volatile private var peerLanCidrList: List<String> = emptyList()
    // natMap: 对端VPN IP -> 本机物理IP（改写源地址用）
    private val natMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    // tcpProxyMap: "srcIp:srcPort" -> Socket（应用层 TCP 代理）
    private val tcpProxyMap = java.util.concurrent.ConcurrentHashMap<String, Socket>()
    // tcpPendingMap: "srcIp:srcPort" -> 待发数据队列（连接建立前缓冲）
    private val tcpPendingMap = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.LinkedBlockingQueue<ByteArray>>()
    // tcpSeqMap: "srcIp:srcPort" -> 客户端下一期望序列号（AtomicInteger，用于回包 ack 字段动态追踪）
    private val tcpSeqMap = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    // tcpServerSeqMap: "srcIp:srcPort" -> 服务端当前序列号（AtomicInteger，用于回 ACK 时的 seq 字段）
    private val tcpServerSeqMap = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    // 端口映射监听 ServerSocket 列表，断开时一并关闭
    private val portMappingServers = mutableListOf<ServerSocket>()

    // TUN 写入队列：所有写入 tunOut 的操作入队，由独立线程串行写出，
    // 避免 dispatchLoop/onPacketReceived 因 tunOut.write() 阻塞而积压数据包。
    // null 为毒丸信号，通知写入线程退出。
    private val tunWriteQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray?>(4096)

    /** 启动 TUN 写入线程，从 tunWriteQueue 取包写入 tunOut */
    private fun startTunWriteLoop() {
        thread(name = "MeshNet-tunwrite") {
            try {
                while (true) {
                    val pkt = tunWriteQueue.take() ?: break
                    val out = tunOut ?: continue
                    runCatching { out.write(pkt) }
                        .onFailure { Log.e(TAG, "[TUN写入] 失败: ${it.message}") }
                }
            } catch (_: InterruptedException) {}
            Log.d(TAG, "[TUN写入] 线程退出")
        }
    }

    /** 将包异步写入 TUN（入队，不阻塞调用线程） */
    private fun writeTun(pkt: ByteArray, len: Int) {
        val data = if (len == pkt.size) pkt else pkt.copyOf(len)
        if (!tunWriteQueue.offer(data)) {
            Log.w(TAG, "[TUN写入] 队列已满，丢弃 $len 字节")
        }
    }

    // -------------------------------------------------------------------------
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                reconnectEnabled = false
                doStop("用户主动断开")
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val json = intent.getStringExtra(EXTRA_CONFIG) ?: return START_NOT_STICKY
                val cfg  = parseMeshConfig(json)
                lastCfg  = cfg
                reconnectEnabled = true
                reconnectAttempt = 0
                startForegroundNotification("正在连接异地组网…")
                launchSession(cfg)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectEnabled = false
        doStop("服务已销毁")
    }

    /** 在新线程启动一次 session */
    private fun launchSession(cfg: MeshSessionConfig) {
        stopping.set(false)
        tearingDown.set(false)
        mainThread = thread(name = "MeshNet-main") { runSession(cfg) }
    }

    // =========================================================================
    // 主流程
    // =========================================================================
    private fun runSession(cfg: MeshSessionConfig) {
        stopping.set(false)
        rootCleanupCmds.clear()
        try {
            val handshake: MeshHandshakeResult = when (cfg.role) {
                MeshRole.SERVER -> runServer(cfg)
                MeshRole.CLIENT -> runClient(cfg)
            }

            localVpnIp = handshake.localVpnIp
            peerVpnIp  = handshake.peerVpnIp

            val tun = buildTun(handshake.localVpnIp, cfg.subnetMask, cfg.mtu, cfg, handshake.peerVpnIp)
                ?: throw Exception("无法建立 TUN 设备")
            tunFd  = tun
            tunOut = FileOutputStream(tun.fileDescriptor)

            Thread.sleep(400)
            setupRootRoutes(cfg, handshake)

            // 启动 TUN 写入线程（异步写入，避免 dispatchLoop 阻塞）
            startTunWriteLoop()

            // 启动端口映射监听
            startPortMappings(cfg)

            isRunning = true
            reconnectAttempt = 0   // 成功连接，重置重连计数
            val lanInfo = buildLanStatusInfo(cfg)
            broadcast(true, "已连接：本机 ${handshake.localVpnIp} ↔ 对端 ${handshake.peerVpnIp}$lanInfo")
            updateNotification("已连接 | ${handshake.localVpnIp} ↔ ${handshake.peerVpnIp}")

            val kaThr = startKeepalive(cfg.keepaliveIntervalSec)
            val tunIn = FileInputStream(tun.fileDescriptor)
            val buf   = ByteArray(65535)

            Log.i(TAG, "[runSession] 调用 tunnel.start()，角色=${cfg.role}")
            tunnel!!.start()
            Log.i(TAG, "[runSession] tunnel.start() 已返回，进入 TUN 转发循环")

            // TUN → 隧道转发循环
            // 注意：tunIn.read() 是阻塞调用，通过关闭 tunFd 来解除阻塞
            try {
                while (!stopping.get()) {
                    val n = tunIn.read(buf)
                    if (n > 0) {
                        if (n >= 20) {
                            val version = (buf[0].toInt() and 0xFF) shr 4
                            // 只转发 IPv4 包，IPv6 包静默丢弃（不转发到隧道）
                            if (version != 4) continue
                            val proto   = buf[9].toInt() and 0xFF
                            val srcIp   = "${buf[12].toInt() and 0xFF}.${buf[13].toInt() and 0xFF}.${buf[14].toInt() and 0xFF}.${buf[15].toInt() and 0xFF}"
                            val dstIp   = "${buf[16].toInt() and 0xFF}.${buf[17].toInt() and 0xFF}.${buf[18].toInt() and 0xFF}.${buf[19].toInt() and 0xFF}"
                            Log.d(TAG, "[TUN→隧道] v=$version proto=$proto src=$srcIp dst=$dstIp len=$n")
                            tunnel?.sendPacket(buf.copyOf(n))
                        } else {
                            Log.d(TAG, "[TUN→隧道] 短包 len=$n，丢弃")
                        }
                    }
                }
            } catch (e: Exception) {
                // tunFd 被关闭时会抛异常，属正常退出
                Log.d(TAG, "[TUN→隧道] read 退出: ${e.message}")
            }

            kaThr.interrupt()

        } catch (e: Exception) {
            if (!stopping.get()) {
                Log.e(TAG, "会话失败: ${e.message}", e)
                broadcast(false, "连接失败: ${e.message}")
            }
        } finally {
            val wasUserStop = stopping.get()
            teardown(if (wasUserStop) "已断开" else "连接中断")
            // 非主动断开 → 自动重连
            if (!wasUserStop && reconnectEnabled) {
                scheduleReconnect(cfg)
            }
        }
    }

    // =========================================================================
    // Root 路由 & iptables 内网互访
    // =========================================================================
    /**
     * 用 su 执行一批 shell 命令（顺序执行），返回 true 表示 su 本身启动成功。
     * 每条命令执行结果不保证，但会写入 Log。
     */
    private fun runAsRoot(vararg cmds: String): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmds.joinToString("\n")))
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()
            proc.waitFor()
            if (stdout.isNotBlank()) Log.d(TAG, "[root stdout] $stdout")
            if (stderr.isNotBlank()) Log.w(TAG, "[root stderr] $stderr")
            true
        } catch (e: Exception) {
            Log.w(TAG, "su 执行失败（设备可能无 root）: ${e.message}")
            false
        }
    }

    /**
     * 握手后配置内网互访：
     *   - 开启 IP 转发
     *   - 为对端内网段添加经 tun 接口的路由
     *   - iptables MASQUERADE 让 tun 发出的包能被内网回包
     */
    private fun setupRootRoutes(cfg: MeshSessionConfig, hs: MeshHandshakeResult) {
        // 判断本端拥有哪些内网段需要向对端公告，并为对端内网段添加路由
        val myLanCidrs   = when (cfg.role) {
            MeshRole.SERVER -> cfg.serverLanCidrs
            MeshRole.CLIENT -> cfg.clientLanCidrs
        }.split(",").map { it.trim() }.filter { it.isNotBlank() }

        val peerLanCidrs = when (cfg.role) {
            MeshRole.SERVER -> cfg.clientLanCidrs
            MeshRole.CLIENT -> cfg.serverLanCidrs
        }.split(",").map { it.trim() }.filter { it.isNotBlank() }

        if (myLanCidrs.isEmpty() && peerLanCidrs.isEmpty()) {
            Log.d(TAG, "未配置内网段，跳过 root 路由设置")
            return
        }

        // 保存本端/对端内网段，供应用层代理路由判断使用
        myLanCidrList = myLanCidrs
        peerLanCidrList = peerLanCidrs

        val cmds = mutableListOf<String>()
        val cleanup = mutableListOf<String>()

        // 1. 开启 IPv4 转发，关闭 rp_filter（允许非对称路由回包）
        cmds += "sysctl -w net.ipv4.ip_forward=1"
        cmds += "sysctl -w net.ipv4.conf.all.accept_local=1"
        cmds += "sysctl -w net.ipv4.conf.all.rp_filter=0"
        cmds += "sysctl -w net.ipv4.conf.tun0.rp_filter=0"

        val tunIface = "tun0"
        // 获取实际出口物理接口（WiFi 优先，其次 rmnet 移动数据）
        val wlanIface = getWlanIfaceName()
        Log.i(TAG, "[setupRootRoutes] 出口接口=$wlanIface")
        cmds += "sysctl -w net.ipv4.conf.$wlanIface.rp_filter=0"

        // 2. 为本端内网段在 local_network 路由表写路由（先删再加防止重复）
        // Android 策略路由规则：from all iif tun0 lookup local_network（优先级12000）
        // 从 tun0 进来的包必须在 local_network 表里找到出口，否则会走到 unreachable
        for (cidr in myLanCidrs) {
            val net  = cidr.substringBefore("/")
            val bits = cidr.substringAfter("/", "24").toIntOrNull() ?: 24
            // 先删再加，保证幂等
            cmds += "ip route del $net/$bits dev $wlanIface table local_network 2>/dev/null; ip route add $net/$bits dev $wlanIface table local_network 2>/dev/null || true"
            cleanup += "ip route del $net/$bits dev $wlanIface table local_network 2>/dev/null || true"
            Log.d(TAG, "[setupRootRoutes] local_network 路由 $net/$bits dev $wlanIface")
        }

        // 2b. 在 local_network 表中也加入 VPN 子网路由（指向 tun0）
        // 原因：内网服务器回包从 wlan0 进来，Android 策略路由查 local_network 表，
        // 必须在这里能找到 VPN 子网（如 192.168.100.0/24）经 tun0 的路由，
        // 否则回包找不到去 192.168.100.1 的出口，被丢弃。
        val vpnSubnetCidr = vpnSubnet(hs.localVpnIp, cfg.subnetMask)
        val vpnCidrBits   = cidrFromMask(cfg.subnetMask)
        cmds += "ip route del $vpnSubnetCidr/$vpnCidrBits dev $tunIface table local_network 2>/dev/null; ip route add $vpnSubnetCidr/$vpnCidrBits dev $tunIface table local_network 2>/dev/null || true"
        cleanup += "ip route del $vpnSubnetCidr/$vpnCidrBits dev $tunIface table local_network 2>/dev/null || true"
        Log.i(TAG, "[setupRootRoutes] local_network VPN子网路由 $vpnSubnetCidr/$vpnCidrBits dev $tunIface")

        // 3. 为对端内网段在主路由表添加路由（经 tun0 转发），并在 local_network 表也加一条
        // 这样无论包走哪个查表路径都能找到对端内网的路由
        for (cidr in peerLanCidrs) {
            val net  = cidr.substringBefore("/")
            val bits = cidr.substringAfter("/", "24").toIntOrNull() ?: 24
            // 主路由表：访问对端内网走 tun0
            cmds += "ip route del $net/$bits dev $tunIface 2>/dev/null; ip route add $net/$bits via ${hs.peerVpnIp} dev $tunIface 2>/dev/null || true"
            cleanup += "ip route del $net/$bits dev $tunIface 2>/dev/null || true"
            // local_network 表也加，防止从 tun0 来的回包查表时丢包
            cmds += "ip route del $net/$bits dev $tunIface table local_network 2>/dev/null; ip route add $net/$bits via ${hs.peerVpnIp} dev $tunIface table local_network 2>/dev/null || true"
            cleanup += "ip route del $net/$bits dev $tunIface table local_network 2>/dev/null || true"
            // MASQUERADE：访问对端内网的包从 tun0 出去，源地址伪装成本端 VPN IP
            cmds += "iptables -t nat -A POSTROUTING -d $cidr -o $tunIface -j MASQUERADE 2>/dev/null || true"
            cleanup += "iptables -t nat -D POSTROUTING -d $cidr -o $tunIface -j MASQUERADE 2>/dev/null || true"
        }

        // 4. 为本端内网段配置路由和 iptables，让对端访问本端内网的包能正确转发和回包
        for (cidr in myLanCidrs) {
            val myIpsInCidr = getLocalIpsInCidr(cidr)
            val net  = cidr.substringBefore("/")
            val bits = cidr.substringAfter("/", "24").toIntOrNull() ?: 24

            // 4a. 把本端内网真实 IP /32 绑定到 tun0
            // 作用：内核识别到 tun0 上有本端内网 IP，回包时能正确选择源地址
            for (ip in myIpsInCidr) {
                cmds += "ip addr del $ip/32 dev $tunIface 2>/dev/null; ip addr add $ip/32 dev $tunIface 2>/dev/null || true"
                cleanup += "ip addr del $ip/32 dev $tunIface 2>/dev/null || true"
                Log.d(TAG, "[setupRootRoutes] 绑定本端内网 IP $ip/32 到 $tunIface")
            }

            // 4b. FORWARD 放行：tun0 ↔ wlanIface 双向转发
            cmds += "iptables -A FORWARD -i $tunIface -o $wlanIface -d $cidr -j ACCEPT 2>/dev/null || true"
            cmds += "iptables -A FORWARD -i $wlanIface -o $tunIface -s $cidr -j ACCEPT 2>/dev/null || true"
            cleanup += "iptables -D FORWARD -i $tunIface -o $wlanIface -d $cidr -j ACCEPT 2>/dev/null || true"
            cleanup += "iptables -D FORWARD -i $wlanIface -o $tunIface -s $cidr -j ACCEPT 2>/dev/null || true"

            // 4c. MASQUERADE：从 tun0 转发到 wlanIface 的包（源地址是对端 VPN IP），
            // 伪装成本机物理 IP，这样内网服务器回包目标是本机物理 IP，能正确路由回来。
            // 内核 conntrack 会自动在回包进来时做 DNAT 还原，将目标地址改回对端 VPN IP，
            // 再经路由送入 tun0 回隧道。
            cmds += "iptables -t nat -A POSTROUTING -s $vpnSubnetCidr/$vpnCidrBits -o $wlanIface -d $cidr -j MASQUERADE 2>/dev/null || true"
            cleanup += "iptables -t nat -D POSTROUTING -s $vpnSubnetCidr/$vpnCidrBits -o $wlanIface -d $cidr -j MASQUERADE 2>/dev/null || true"
        }

        rootCleanupCmds.addAll(cleanup)

        val ok = runAsRoot(*cmds.toTypedArray())
        if (ok) {
            Log.i(TAG, "root 路由规则已应用")
            broadcast(true, "内网路由已配置\n本端内网: ${myLanCidrs.joinToString()}\n对端内网: ${peerLanCidrs.joinToString()}")
        } else {
            broadcast(true, "⚠️ root 路由配置失败（无 root 或 su 不可用），内网互访可能不通")
        }
    }

    /** 断开时清理 root 规则 */
    private fun cleanupRootRoutes() {
        if (rootCleanupCmds.isEmpty()) return
        // 异步执行，避免阻塞 teardown
        thread(name = "MeshNet-cleanup") {
            runAsRoot(*rootCleanupCmds.toTypedArray())
            rootCleanupCmds.clear()
            Log.i(TAG, "root 路由规则已清理")
        }
    }

    private fun buildLanStatusInfo(cfg: MeshSessionConfig): String {
        val serverLans = cfg.serverLanCidrs.split(",").filter { it.isNotBlank() }
        val clientLans = cfg.clientLanCidrs.split(",").filter { it.isNotBlank() }
        if (serverLans.isEmpty() && clientLans.isEmpty()) return ""
        val sb = StringBuilder("\n")
        if (serverLans.isNotEmpty()) sb.append("服务端内网: ${serverLans.joinToString()}\n")
        if (clientLans.isNotEmpty()) sb.append("客户端内网: ${clientLans.joinToString()}")
        return sb.toString()
    }

    // =========================================================================
    // Server / Client 握手
    // =========================================================================
    private fun runServer(cfg: MeshSessionConfig): MeshHandshakeResult {
        broadcast(false, "[服务端] 正在监听端口 ${cfg.listenPort}…")
        // 优先尝试 IPv6 双栈监听（绑定 :: 可同时接受 IPv4 和 IPv6 连接）
        // 若系统不支持则回退到 IPv4 监听
        val ss = try {
            val s = java.net.ServerSocket()
            s.reuseAddress = true
            s.soTimeout = 2000
            // 绑定到 IPv6 通配地址 :: 实现双栈
            s.bind(InetSocketAddress("::", cfg.listenPort))
            Log.i(TAG, "[服务端] IPv6 双栈监听 :::${cfg.listenPort}")
            s
        } catch (e: Exception) {
            Log.w(TAG, "[服务端] IPv6 双栈监听失败(${e.message})，回退 IPv4")
            val s = java.net.ServerSocket()
            s.reuseAddress = true
            s.soTimeout = 2000
            s.bind(InetSocketAddress("0.0.0.0", cfg.listenPort))
            Log.i(TAG, "[服务端] IPv4 监听 0.0.0.0:${cfg.listenPort}")
            s
        }
        serverSocket = ss
        broadcast(false, "[服务端] 等待客户端连接…")
        var sock: Socket? = null
        while (!stopping.get()) {
            try {
                sock = ss.accept()
                break
            } catch (_: java.net.SocketTimeoutException) {
                // 超时继续等待
            }
        }
        if (stopping.get() || sock == null) throw java.io.IOException("已停止等待")
        Log.i(TAG, "[服务端] 客户端已连接: ${sock.remoteSocketAddress}")
        broadcast(false, "[服务端] 客户端已接入: ${sock.remoteSocketAddress}")
        protect(sock)
        val out = java.io.DataOutputStream(sock.getOutputStream().buffered())
        val inp = java.io.DataInputStream(sock.getInputStream().buffered())
        Log.i(TAG, "[服务端] 开始握手…")
        val result = serverHandshake(out, inp, cfg.passcode, cfg.serverVpnIp, cfg.clientVpnIp)
        Log.i(TAG, "[服务端] 握手完成 localVpn=${result.localVpnIp} peerVpn=${result.peerVpnIp}")
        setupTunnel(sock, result.sharedKey, out, inp)
        return result
    }

    private fun runClient(cfg: MeshSessionConfig): MeshHandshakeResult {
        broadcast(false, "[客户端] 正在连接 ${cfg.serverHost}:${cfg.serverPort}…")
        Log.i(TAG, "[客户端] 连接 ${cfg.serverHost}:${cfg.serverPort}…")
        val sock = Socket()
        protect(sock)
        sock.connect(InetSocketAddress(cfg.serverHost, cfg.serverPort), 15_000)
        sock.soTimeout = 0
        Log.i(TAG, "[客户端] TCP 已连接: ${sock.localSocketAddress} → ${sock.remoteSocketAddress}")
        broadcast(false, "[客户端] TCP 已连接，握手中…")
        val out = java.io.DataOutputStream(sock.getOutputStream().buffered())
        val inp = java.io.DataInputStream(sock.getInputStream().buffered())
        Log.i(TAG, "[客户端] 开始握手…")
        val result = clientHandshake(out, inp, cfg.passcode)
        Log.i(TAG, "[客户端] 握手完成 localVpn=${result.localVpnIp} peerVpn=${result.peerVpnIp}")
        setupTunnel(sock, result.sharedKey, out, inp)
        return result
    }

    private fun setupTunnel(
        sock: Socket,
        key: ByteArray,
        existingOut: java.io.DataOutputStream,
        existingInp: java.io.DataInputStream
    ) {
        tunnel = MeshTunnel(
            socket = sock,
            sharedKey = key,
            existingOut = existingOut,
            existingInp = existingInp,
            onPacketReceived = { pkt ->
                if (pkt.size < 20) {
                    Log.w(TAG, "[TUN←隧道] 包太短(${pkt.size}字节)，丢弃")
                    return@MeshTunnel
                }
                val version    = (pkt[0].toInt() and 0xFF) shr 4
                // 只注入 IPv4 包到 TUN，其他版本丢弃
                if (version != 4) {
                    Log.d(TAG, "[TUN←隧道] 非 IPv4(v=$version)，丢弃")
                    return@MeshTunnel
                }
                val proto      = pkt[9].toInt() and 0xFF
                val srcIp      = "${pkt[12].toInt() and 0xFF}.${pkt[13].toInt() and 0xFF}.${pkt[14].toInt() and 0xFF}.${pkt[15].toInt() and 0xFF}"
                val dstIp      = "${pkt[16].toInt() and 0xFF}.${pkt[17].toInt() and 0xFF}.${pkt[18].toInt() and 0xFF}.${pkt[19].toInt() and 0xFF}"
                val ipTotalLen = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
                // 按 IP 头声明的长度截取写入，避免多余字节干扰内核
                val writeLen = if (ipTotalLen in 20..pkt.size) ipTotalLen else pkt.size
                Log.d(TAG, "[TUN←隧道] v=$version proto=$proto src=$srcIp dst=$dstIp ipLen=$ipTotalLen writeLen=$writeLen")
                // TCP SYN 包（仅纯 SYN，排除 SYN+ACK）：应用层 TCP 代理
                val tcpFlags = if (proto == 6 && pkt.size >= 40) {
                    val ihlTcp = (pkt[0].toInt() and 0x0F) * 4
                    pkt[ihlTcp + 13].toInt() and 0xFF
                } else 0
                val isTcpSyn = proto == 6 && pkt.size >= 40 &&
                    (tcpFlags and 0x02) != 0 && (tcpFlags and 0x10) == 0  // SYN=1, ACK=0

                // ICMP 代理：目标在本端或对端内网段，用应用层 ping 回复（无 root 也能工作）
                if (proto == 1 && pkt.size >= 24 &&
                    (isInAnyCidr(dstIp, myLanCidrList) || isInAnyCidr(dstIp, peerLanCidrList))) {
                    val icmpType = pkt[20].toInt() and 0xFF
                    if (icmpType == 8) { // Echo Request
                        thread(name = "MeshNet-icmp-proxy") {
                            try {
                                val addr = java.net.InetAddress.getByName(dstIp)
                                val reachable = addr.isReachable(2000)
                                // 构造 ICMP Echo Reply
                                val replyData = pkt.copyOf(writeLen)
                                replyData[12] = pkt[16]; replyData[13] = pkt[17]
                                replyData[14] = pkt[18]; replyData[15] = pkt[19]
                                replyData[16] = pkt[12]; replyData[17] = pkt[13]
                                replyData[18] = pkt[14]; replyData[19] = pkt[15]
                                replyData[20] = 0 // Echo Reply type
                                replyData[22] = 0; replyData[23] = 0 // checksum 清零
                                recalcIpChecksum(replyData)
                                // 计算 ICMP checksum
                                val ihlIcmp = (replyData[0].toInt() and 0x0F) * 4
                                var icmpSum = 0L
                                var ic = ihlIcmp
                                while (ic < writeLen - 1) {
                                    icmpSum += ((replyData[ic].toInt() and 0xFF) shl 8) or (replyData[ic+1].toInt() and 0xFF)
                                    ic += 2
                                }
                                if ((writeLen - ihlIcmp) % 2 != 0) icmpSum += (replyData[writeLen-1].toInt() and 0xFF) shl 8
                                while (icmpSum shr 16 != 0L) icmpSum = (icmpSum and 0xFFFF) + (icmpSum shr 16)
                                val icmpCk = icmpSum.inv() and 0xFFFF
                                replyData[ihlIcmp + 2] = (icmpCk shr 8).toByte()
                                replyData[ihlIcmp + 3] = (icmpCk and 0xFF).toByte()
                                if (reachable) {
                                    tunnel?.sendPacket(replyData)
                                    Log.d(TAG, "[ICMP代理] ping $dstIp 可达，已回复")
                                } else {
                                    Log.d(TAG, "[ICMP代理] ping $dstIp 不可达")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "[ICMP代理] 异常: ${e.message}")
                            }
                        }
                    }
                    return@MeshTunnel
                }

                if (isTcpSyn && (isInAnyCidr(dstIp, myLanCidrList) || isInAnyCidr(dstIp, peerLanCidrList))) {
                    val ihl2 = (pkt[0].toInt() and 0x0F) * 4
                    val dstPort = ((pkt[ihl2 + 2].toInt() and 0xFF) shl 8) or (pkt[ihl2 + 3].toInt() and 0xFF)
                    val srcPort = ((pkt[ihl2].toInt() and 0xFF) shl 8) or (pkt[ihl2 + 1].toInt() and 0xFF)
                    val origSrc = srcIp
                    val proxyKey = "$origSrc:$srcPort"
                    Log.d(TAG, "[TCP代理] 收到 SYN src=$origSrc:$srcPort dst=$dstIp:$dstPort")
                    // 若该 key 已有活跃代理连接，忽略重复 SYN（避免重复建立）
                    if (tcpProxyMap.containsKey(proxyKey)) {
                        Log.d(TAG, "[TCP代理] 已有活跃连接 $proxyKey，忽略重复 SYN")
                        return@MeshTunnel
                    }
                    // 用 pending 队列缓冲在连接建立前到来的数据包
                    val pendingQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(256)
                    tcpPendingMap[proxyKey] = pendingQueue
                    thread(name = "MeshNet-tcp-proxy") {
                        try {
                            val realSock = Socket()
                            realSock.connect(java.net.InetSocketAddress(dstIp, dstPort), 5000)
                            // 设置 SO_LINGER=0：关闭时发 RST 而不是 FIN，避免 TIME_WAIT
                            realSock.setSoLinger(true, 0)
                            Log.d(TAG, "[TCP代理] 已连接 $dstIp:$dstPort")

                            // 从 SYN 包里取客户端初始序列号
                            val ihl2inner = (pkt[0].toInt() and 0x0F) * 4
                            val clientInitSeq = ((pkt[ihl2inner+4].toInt() and 0xFF) shl 24) or
                                               ((pkt[ihl2inner+5].toInt() and 0xFF) shl 16) or
                                               ((pkt[ihl2inner+6].toInt() and 0xFF) shl 8)  or
                                                (pkt[ihl2inner+7].toInt() and 0xFF)
                            val serverSeq = 0x12345678.toInt()
                            // clientNextSeq 追踪客户端下一个期望的序列号（用于回包 ack 字段）
                            // 使用 AtomicInteger 保证 recv 线程和主线程的可见性
                            val clientNextSeq = java.util.concurrent.atomic.AtomicInteger(clientInitSeq + 1)
                            val synAck = buildTcpPacketWithSeq(
                                srcIp = dstIp, srcPort = dstPort,
                                dstIp = origSrc, dstPort = srcPort,
                                seq = serverSeq, ack = clientInitSeq + 1,
                                flags = 0x12,
                                data = ByteArray(0)
                            )
                            tunnel?.sendPacket(synAck)
                            Log.d(TAG, "[TCP代理] 已发送 SYN-ACK 回隧道 src=$dstIp:$dstPort dst=$origSrc:$srcPort")

                            // 正式注册到 map，移除 pending
                            // 同时把 clientNextSeq 也存进去，供主线程非SYN包处理时更新
                            tcpProxyMap[proxyKey] = realSock
                            tcpSeqMap[proxyKey] = clientNextSeq
                            val serverSeqAtomic = java.util.concurrent.atomic.AtomicInteger(serverSeq + 1)
                            tcpServerSeqMap[proxyKey] = serverSeqAtomic
                            tcpPendingMap.remove(proxyKey)

                            // 先把 pending 队列里积压的数据发出去
                            val realOut = realSock.getOutputStream()
                            while (true) {
                                val pending = pendingQueue.poll() ?: break
                                realOut.write(pending)
                                Log.d(TAG, "[TCP代理] 发送 pending 数据 ${pending.size} 字节到 $dstIp:$dstPort")
                            }

                            thread(name = "MeshNet-tcp-proxy-recv") {
                                try {
                                    val buf = ByteArray(4096)
                                    val inp = realSock.getInputStream()
                                    var seq = serverSeq + 1
                                    while (!realSock.isClosed) {
                                        val n = inp.read(buf)
                                        if (n <= 0) break
                                        val tcpPkt = buildTcpPacketWithSeq(
                                            srcIp = dstIp, srcPort = dstPort,
                                            dstIp = origSrc, dstPort = srcPort,
                                            seq = seq,
                                            // 使用动态追踪的客户端序列号作为 ack
                                            ack = clientNextSeq.get(),
                                            flags = 0x18,
                                            data = buf.copyOf(n)
                                        )
                                        seq += n
                                        serverSeqAtomic.set(seq)
                                        tunnel?.sendPacket(tcpPkt)
                                        Log.d(TAG, "[TCP代理] 转发 $n 字节回隧道")
                                    }
                                } catch (e: Exception) {
                                    Log.d(TAG, "[TCP代理] recv 结束: ${e.message}")
                                } finally {
                                    // 发送 FIN 包通知客户端连接已关闭
                                    runCatching {
                                        val finPkt = buildTcpPacketWithSeq(
                                            srcIp = dstIp, srcPort = dstPort,
                                            dstIp = origSrc, dstPort = srcPort,
                                            seq = serverSeqAtomic.get(),
                                            ack = clientNextSeq.get(),
                                            flags = 0x11, // FIN+ACK
                                            data = ByteArray(0)
                                        )
                                        tunnel?.sendPacket(finPkt)
                                        Log.d(TAG, "[TCP代理] 已发送 FIN 通知客户端关闭 $proxyKey")
                                    }
                                    tcpProxyMap.remove(proxyKey)
                                    tcpSeqMap.remove(proxyKey)
                                    tcpServerSeqMap.remove(proxyKey)
                                    runCatching { realSock.close() }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[TCP代理] 连接 $dstIp:$dstPort 失败: ${e.message}")
                            tcpPendingMap.remove(proxyKey)
                        }
                    }
                    return@MeshTunnel
                }

                // 非 SYN 的 TCP 包：转发到已建立的代理 socket 或 pending 队列
                val isTcp = proto == 6 && pkt.size >= 40 &&
                    (isInAnyCidr(dstIp, myLanCidrList) || isInAnyCidr(dstIp, peerLanCidrList))
                if (isTcp && !isTcpSyn) {
                    val ihl3 = (pkt[0].toInt() and 0x0F) * 4
                    val dstPort3 = ((pkt[ihl3 + 2].toInt() and 0xFF) shl 8) or (pkt[ihl3 + 3].toInt() and 0xFF)
                    val srcPort3 = ((pkt[ihl3].toInt() and 0xFF) shl 8) or (pkt[ihl3 + 1].toInt() and 0xFF)
                    val key3 = "$srcIp:$srcPort3"
                    // 正确读取 TCP 数据偏移（高4位 * 4 = TCP头实际长度，含选项）
                    val tcpDataOffset3 = ((pkt[ihl3 + 12].toInt() and 0xFF) ushr 4) * 4
                    val dataStart = ihl3 + tcpDataOffset3
                    val dataLen = writeLen - dataStart
                    // 从 TCP 头读取客户端序列号，更新 clientNextSeq（用于回包 ack 动态追踪）
                    val pktSeq = ((pkt[ihl3 + 4].toInt() and 0xFF) shl 24) or
                                 ((pkt[ihl3 + 5].toInt() and 0xFF) shl 16) or
                                 ((pkt[ihl3 + 6].toInt() and 0xFF) shl 8)  or
                                  (pkt[ihl3 + 7].toInt() and 0xFF)
                    if (dataLen > 0) {
                        // 更新序列号追踪：clientNextSeq = 客户端seq + 本包数据长度
                        tcpSeqMap[key3]?.set(pktSeq + dataLen)
                        val proxySock = tcpProxyMap[key3]
                        if (proxySock != null && !proxySock.isClosed) {
                            try {
                                proxySock.getOutputStream().write(pkt, dataStart, dataLen)
                                Log.d(TAG, "[TCP代理] 转发 $dataLen 字节到 $dstIp:$dstPort3 seq=$pktSeq")
                                // 立即回 ACK 给客户端，避免客户端因等待 ACK 而触发重传
                                val serverSeqForConn = tcpServerSeqMap[key3]
                                if (serverSeqForConn != null) {
                                    val ackPkt = buildTcpPacketWithSeq(
                                        srcIp = dstIp, srcPort = dstPort3,
                                        dstIp = srcIp, dstPort = srcPort3,
                                        seq = serverSeqForConn.get(),
                                        ack = pktSeq + dataLen,
                                        flags = 0x10, // ACK only
                                        data = ByteArray(0)
                                    )
                                    tunnel?.sendPacket(ackPkt)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "[TCP代理] 写入失败: ${e.message}")
                            }
                        } else {
                            // 连接尚未建立，写入 pending 队列缓冲
                            val pendingQ = tcpPendingMap[key3]
                            if (pendingQ != null) {
                                pendingQ.offer(pkt.copyOfRange(dataStart, dataStart + dataLen))
                                Log.d(TAG, "[TCP代理] 数据入 pending 队列 $dataLen 字节 key=$key3")
                            }
                        }
                    }
                    return@MeshTunnel
                }

                // 端口映射回包拦截：对端 TCP 代理回包目标是本端 VPN IP（如 192.168.100.2:srcPort），
                // 需要在写 TUN 之前检查 tcpProxyMap，找到对应的 MemProxySocket 并把数据转发给 client。
                // key 格式："$dstIp:$dstPort"（即本端虚拟源 IP:源端口，与 handlePortMappingConnection 里注册时一致）
                if (proto == 6 && pkt.size >= 40 && dstIp == localVpnIp) {
                    val ihlPm = (pkt[0].toInt() and 0x0F) * 4
                    val dstPortPm = ((pkt[ihlPm + 2].toInt() and 0xFF) shl 8) or (pkt[ihlPm + 3].toInt() and 0xFF)
                    val pmKey = "$dstIp:$dstPortPm"
                    val pmSock = tcpProxyMap[pmKey]
                    if (pmSock != null && !pmSock.isClosed) {
                        val tcpDataOffPm = ((pkt[ihlPm + 12].toInt() and 0xFF) ushr 4) * 4
                        val dataStartPm = ihlPm + tcpDataOffPm
                        val dataLenPm = writeLen - dataStartPm
                        // 更新服务端序列号追踪（用于后续发包的 ack 字段）
                        val pktSeqPm = ((pkt[ihlPm + 4].toInt() and 0xFF) shl 24) or
                                       ((pkt[ihlPm + 5].toInt() and 0xFF) shl 16) or
                                       ((pkt[ihlPm + 6].toInt() and 0xFF) shl 8)  or
                                        (pkt[ihlPm + 7].toInt() and 0xFF)
                        if (dataLenPm > 0) {
                            tcpServerSeqMap[pmKey]?.set(pktSeqPm + dataLenPm)
                            try {
                                pmSock.getOutputStream().write(pkt, dataStartPm, dataLenPm)
                                Log.d(TAG, "[端口映射←回包] 转发 $dataLenPm 字节给 client key=$pmKey")
                                // 回 ACK 给对端
                                val clientSeqPm = tcpSeqMap[pmKey]?.get() ?: 0
                                val srcPortPm = ((pkt[ihlPm].toInt() and 0xFF) shl 8) or (pkt[ihlPm + 1].toInt() and 0xFF)
                                val ackPm = buildTcpPacketWithSeq(
                                    srcIp = dstIp, srcPort = dstPortPm,
                                    dstIp = srcIp, dstPort = srcPortPm,
                                    seq = clientSeqPm,
                                    ack = pktSeqPm + dataLenPm,
                                    flags = 0x10, data = ByteArray(0)
                                )
                                tunnel?.sendPacket(ackPm)
                            } catch (e: Exception) {
                                Log.e(TAG, "[端口映射←回包] 写入 client 失败: ${e.message}")
                            }
                        }
                        return@MeshTunnel
                    }
                }

                // 不做应用层源地址 NAT：保持原始 VPN IP 作为源地址
                // 这样内网服务器回包目标是对端 VPN IP（如 192.168.100.1），
                // 内核查路由会经 tun0 回到隧道，而不是直接本地递交给物理接口

                val out = tunOut
                if (out == null) {
                    Log.e(TAG, "[TUN←隧道] tunOut 为 null，丢包")
                    return@MeshTunnel
                }
                writeTun(pkt, writeLen)
                Log.d(TAG, "[TUN←隧道] 已写入 TUN $writeLen 字节")
            },
            onDisconnected = { reason ->
                if (!stopping.get()) {
                    // 在独立线程触发断开，避免在接收线程里直接 teardown 造成死锁
                    thread(name = "MeshNet-disconnect") {
                        broadcast(false, reason)
                        // 关闭 tunFd 解除 tunIn.read() 阻塞，让 runSession finally 自然收尾
                        stopping.set(true)
                        runCatching { tunFd?.close() }
                    }
                }
            }
        )
    }

    // =========================================================================
    // TUN 建立
    // =========================================================================
    private fun buildTun(
        localIp: String, mask: String, mtu: Int, cfg: MeshSessionConfig,
        peerVpnIp: String
    ): ParcelFileDescriptor? {
        return runCatching {
            val cidr = cidrFromMask(mask)
            val b = Builder()
                .setSession("FileTran MeshNet")
                .addAddress(localIp, cidr)
                .setMtu(mtu)
                .setBlocking(true)

            // 添加 VPN 子网路由（如 192.168.100.0/24），覆盖对端 VPN IP
            // 注意：不能再单独 addRoute(peerVpnIp, 32)，否则 API 33+ 会因重叠路由抛异常
            val subnet = vpnSubnet(localIp, mask)
            Log.d(TAG, "[buildTun] 添加子网路由 $subnet/$cidr")
            b.addRoute(subnet, cidr)

            // 将对端内网段加入 TUN 路由（让访问对端内网的流量走隧道）
            val peerLanCidrs = when (cfg.role) {
                MeshRole.SERVER -> cfg.clientLanCidrs
                MeshRole.CLIENT -> cfg.serverLanCidrs
            }.split(",").map { it.trim() }.filter { it.isNotBlank() }

            for (lanCidr in peerLanCidrs) {
                val net  = lanCidr.substringBefore("/")
                val bits = lanCidr.substringAfter("/", "24").toIntOrNull() ?: 24
                Log.d(TAG, "[buildTun] 添加对端内网路由 $net/$bits")
                try { b.addRoute(net, bits) } catch (e: Exception) {
                    Log.e(TAG, "[buildTun] 添加内网路由失败 $lanCidr: ${e.message}")
                }
            }

            // 排除本 App 自身流量，防止隧道 TCP 连接被 VPN 接管形成回环
            b.addDisallowedApplication(packageName)

            val pfd = b.establish()
            Log.d(TAG, "[buildTun] TUN 建立${if (pfd != null) "成功 fd=${pfd.fd}" else "失败（null）"}")
            pfd
        }.getOrElse { e ->
            Log.e(TAG, "[buildTun] 异常: ${e.message}", e)
            null
        }
    }

    // =========================================================================
    // 端口映射
    // =========================================================================
    /**
     * 解析 cfg.portMappings 并为每条规则启动一个本地 ServerSocket 监听。
     * 每条规则格式：targetIp:targetPort:listenIp:listenPort
     * 含义：在 VPN 上监听 listenIp:listenPort，收到连接后直连本端内网 targetIp:targetPort，双向透传。
     * 对端只需访问 listenIp:listenPort（本端 VPN IP）即可访问本端内网服务。
     */
    private fun startPortMappings(cfg: MeshSessionConfig) {
        if (cfg.portMappings.isBlank()) return
        val rules = cfg.portMappings.split(",").mapNotNull { PortMapping.fromString(it) }
        if (rules.isEmpty()) return
        Log.i(TAG, "[端口映射] 启动 ${rules.size} 条规则")
        for (rule in rules) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.soTimeout = 0
                ss.bind(InetSocketAddress(rule.listenIp, rule.listenPort))
                synchronized(portMappingServers) { portMappingServers += ss }
                Log.i(TAG, "[端口映射] 监听 ${rule.listenIp}:${rule.listenPort} → 本端内网 ${rule.targetIp}:${rule.targetPort}")
                thread(name = "MeshNet-portmap-${rule.listenPort}") {
                    while (!ss.isClosed && !stopping.get()) {
                        try {
                            val client = ss.accept()
                            Log.d(TAG, "[端口映射] 新连接 ${client.remoteSocketAddress} → ${rule.targetIp}:${rule.targetPort}")
                            thread(name = "MeshNet-portmap-conn-${rule.listenPort}") {
                                handlePortMappingConnection(client, rule)
                            }
                        } catch (e: Exception) {
                            if (!ss.isClosed) Log.w(TAG, "[端口映射] accept 异常: ${e.message}")
                        }
                    }
                    Log.i(TAG, "[端口映射] 监听结束 ${rule.listenIp}:${rule.listenPort}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[端口映射] 启动失败 ${rule.listenIp}:${rule.listenPort}: ${e.message}")
            }
        }
    }

    /**
     * 处理一条端口映射连接。
     * 方案：直接构造虚拟 TCP IP 包写入 TUN（通过 tunOut），让内核经 VPN 路由送到对端。
     * 对端的 onPacketReceived 收到 SYN 后，通过 TCP 代理连接目标内网主机，双向透传数据。
     * 本端则监听对端回来的 TCP 包（通过 onPacketReceived 注入 TUN），再从 TUN 读出后转发给 client。
     *
     * 由于本 app 走真实网络（addDisallowedApplication），无法直连对端内网，
     * 所以用虚拟包走 TUN 让内核处理路由是唯一可靠方案。
     *
     * 实际上更简单：直接在本端起一个本地 ServerSocket，
     * 把 targetIp:targetPort 的流量通过隧道的 sendPacket 注入 TUN（虚拟 IP 包），
     * 对端收到后 TCP 代理连接目标，回包再经隧道回来写入本端 TUN，
     * 本端内核收到后路由给本地 ServerSocket 监听的端口——
     * 但这需要本端 TUN 上有虚拟 IP 绑定，复杂度高。
     *
     * 最简单可靠方案：直接用 tunnel.sendPacket 构造虚拟 SYN 发给对端，
     * 对端建立 TCP 代理后回 SYN-ACK，本端用一个内存管道和 client 双向透传。
     */
    private fun handlePortMappingConnection(client: Socket, rule: PortMapping) {
        Log.i(TAG, "[端口映射] 新连接 ${client.remoteSocketAddress} → ${rule.targetIp}:${rule.targetPort}")
        try {
            // 用本端 VPN IP 作为虚拟源 IP，随机源端口
            val localVpn = localVpnIp.ifBlank { "192.168.100.1" }
            val srcPort  = (30000 + (System.nanoTime() % 30000).toInt().coerceAtLeast(0))
            val proxyKey = "$localVpn:$srcPort"

            // 用内存管道双向桥接：client ↔ memPipe ↔ 隧道TCP代理
            // memPipe 的一端连接 client，另一端注册到 tcpProxyMap 供 onPacketReceived 写入
            val clientToProxy = java.util.concurrent.LinkedBlockingQueue<ByteArray>(256)
            val proxyToClient = java.util.concurrent.LinkedBlockingQueue<ByteArray>(256)

            // 注册 pending 队列
            val pendingQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(256)
            tcpPendingMap[proxyKey] = pendingQueue

            // 在独立线程里建立到对端目标的 Socket（通过隧道路由，需要 root 路由规则生效）
            // 如果 root 路由没有配置，这条连接会失败
            thread(name = "MeshNet-portmap-proxy") {
                try {
                    // 直接连接目标 IP:Port
                    // 由于配置了对端内网段的路由规则（setupRootRoutes），
                    // 且本 app 被 addDisallowedApplication 排除出 VPN，
                    // 本 app 的 Socket 会走物理网卡真实路由。
                    // 若有 root 路由（ip route add 对端内网段 via 对端VPN IP dev tun0），则能到达。
                    // 若无 root，则需要对端也配置了对应的端口映射转发。
                    val realSock = Socket()
                    realSock.setSoLinger(true, 0)
                    // 尝试通过隧道连接目标：构造虚拟 SYN 发给对端，让对端代理连接
                    // 这里改为直接发 SYN 给对端（tunnel.sendPacket），对端处理后回 SYN-ACK
                    val clientInitSeq = (System.nanoTime() and 0xFFFFFFFFL).toInt()
                    val serverSeq     = 0x56789ABC.toInt()
                    val clientNextSeq = java.util.concurrent.atomic.AtomicInteger(clientInitSeq + 1)
                    val serverSeqAtomic = java.util.concurrent.atomic.AtomicInteger(serverSeq + 1)

                    // 构造虚拟 SYN 包通过隧道发给对端，让对端 TCP 代理连接目标
                    val synPkt = buildTcpPacketWithSeq(
                        srcIp = localVpn, srcPort = srcPort,
                        dstIp = rule.targetIp, dstPort = rule.targetPort,
                        seq = clientInitSeq, ack = 0,
                        flags = 0x02, // SYN
                        data = ByteArray(0)
                    )
                    tunnel?.sendPacket(synPkt)
                    Log.i(TAG, "[端口映射] 已发 SYN → ${rule.targetIp}:${rule.targetPort} key=$proxyKey")

                    // 等待对端回 SYN-ACK（通过 onPacketReceived 写入 TUN，内核处理）
                    // 本端用一个管道 Socket 模拟：在本端本地监听一个端口
                    // 收到对端 SYN-ACK 后建立连接，然后双向透传
                    // 注册虚拟连接到 tcpProxyMap 以便接收来自对端的数据
                    // 使用 PipedInputStream/PipedOutputStream 作为内存管道
                    val pipedIn  = java.io.PipedInputStream(65536)
                    val pipedOut = java.io.PipedOutputStream(pipedIn)

                    // 创建一个内存 Socket 代理对象，把对端回来的数据写入 pipedOut，
                    // 把 client 发来的数据从 pipedIn 读出来转发给对端
                    // 用一个自定义 MemSocket 实现（内部持有两个流）
                    val memSocket = MemProxySocket(
                        onWrite = { buf, off, len ->
                            // 对端数据 → client
                            try { client.getOutputStream().write(buf, off, len) }
                            catch (_: Exception) {}
                        },
                        onClose = { runCatching { client.close() } }
                    )

                    tcpProxyMap[proxyKey] = memSocket.asSocket()
                    tcpSeqMap[proxyKey]   = clientNextSeq
                    tcpServerSeqMap[proxyKey] = serverSeqAtomic
                    tcpPendingMap.remove(proxyKey)

                    // client → 对端：读 client 数据，构造 TCP 数据包发给对端
                    try {
                        val buf = ByteArray(4096)
                        var seq = clientInitSeq + 1
                        while (!client.isClosed) {
                            val n = client.getInputStream().read(buf)
                            if (n <= 0) break
                            clientNextSeq.set(seq + n)
                            val dataPkt = buildTcpPacketWithSeq(
                                srcIp = localVpn, srcPort = srcPort,
                                dstIp = rule.targetIp, dstPort = rule.targetPort,
                                seq = seq, ack = serverSeqAtomic.get(),
                                flags = 0x18, // PSH+ACK
                                data = buf.copyOf(n)
                            )
                            tunnel?.sendPacket(dataPkt)
                            seq += n
                            Log.d(TAG, "[端口映射] client→隧道 $n 字节")
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "[端口映射] client 读取结束: ${e.message}")
                    } finally {
                        // 发送 FIN 给对端
                        runCatching {
                            val finPkt = buildTcpPacketWithSeq(
                                srcIp = localVpn, srcPort = srcPort,
                                dstIp = rule.targetIp, dstPort = rule.targetPort,
                                seq = clientNextSeq.get(), ack = serverSeqAtomic.get(),
                                flags = 0x11, data = ByteArray(0)
                            )
                            tunnel?.sendPacket(finPkt)
                        }
                        tcpProxyMap.remove(proxyKey)
                        tcpSeqMap.remove(proxyKey)
                        tcpServerSeqMap.remove(proxyKey)
                        runCatching { client.close() }
                        Log.i(TAG, "[端口映射] 连接关闭 $proxyKey")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[端口映射] proxy 线程异常: ${e.message}")
                    tcpPendingMap.remove(proxyKey)
                    runCatching { client.close() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[端口映射] 连接处理异常: ${e.message}")
            runCatching { client.close() }
        }
    }

    /**
     * 内存代理 Socket：用于端口映射中接收来自对端隧道的数据并转发给 client。
     * onWrite 回调在 onPacketReceived 收到对端数据时被调用。
     */
    inner class MemProxySocket(
        private val onWrite: (ByteArray, Int, Int) -> Unit,
        private val onClose: () -> Unit
    ) {
        @Volatile var closed = false
        private val pipedIn  = java.io.PipedInputStream(65536)
        private val pipedOut = java.io.PipedOutputStream(pipedIn)

        fun write(buf: ByteArray, off: Int, len: Int) {
            if (closed) return
            onWrite(buf, off, len)
        }

        fun close() {
            if (closed) return
            closed = true
            onClose()
            runCatching { pipedOut.close() }
        }

        /** 返回一个包装 Socket，使 tcpProxyMap 能存储并调用 getOutputStream().write() */
        fun asSocket(): Socket {
            return object : Socket() {
                override fun isClosed() = closed
                override fun getOutputStream() = object : java.io.OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) = this@MemProxySocket.write(b, off, len)
                    override fun write(b: ByteArray) = write(b, 0, b.size)
                }
                override fun close() = this@MemProxySocket.close()
            }
        }
    }

    // =========================================================================
    // Keepalive
    // =========================================================================
    private fun startKeepalive(intervalSec: Int): Thread {
        return thread(name = "MeshNet-keepalive") {
            try {
                while (!stopping.get() && !Thread.interrupted()) {
                    Thread.sleep(intervalSec * 1000L)
                    tunnel?.sendKeepalive()
                }
            } catch (_: InterruptedException) {}
        }
    }

    // =========================================================================
    // 清理
    // =========================================================================
    /**
     * 用户主动停止：设置 stopping，关闭 tunFd 解除 read 阻塞，等 runSession 自然退出。
     * 不在这里直接调 teardown，避免重复执行。
     */
    private fun doStop(reason: String) {
        stopping.set(true)
        reconnectEnabled = false
        // 关闭 tunFd 解除 tunIn.read() 阻塞
        runCatching { tunOut?.close() }
        runCatching { tunFd?.close() }
        // 关闭 TCP 隧道
        runCatching { tunnel?.close(reason) }
        // 关闭 ServerSocket（服务端 accept 阻塞）
        runCatching { serverSocket?.close() }
    }

    /**
     * runSession finally 里调用：清理资源、更新状态、广播。
     * 用 tearingDown 保证只执行一次。
     */
    private fun teardown(reason: String) {
        if (!tearingDown.compareAndSet(false, true)) return
        isRunning = false
        localVpnIp = ""
        peerVpnIp  = ""
        // 停止 TUN 写入线程
        tunWriteQueue.offer(null)
        cleanupRootRoutes()          // 异步，不阻塞
        // 关闭所有端口映射监听
        synchronized(portMappingServers) {
            portMappingServers.forEach { runCatching { it.close() } }
            portMappingServers.clear()
        }
        runCatching { tunnel?.close(reason) }
        runCatching { tunOut?.close() }
        runCatching { tunFd?.close() }
        runCatching { serverSocket?.close() }
        tunnel       = null
        tunOut       = null
        tunFd        = null
        serverSocket = null
        broadcast(false, reason)
        updateNotification("已断开")
        if (!reconnectEnabled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** 指数退避自动重连（仅客户端） */
    private fun scheduleReconnect(cfg: MeshSessionConfig) {
        if (!reconnectEnabled) return
        if (reconnectAttempt >= maxReconnectAttempts) {
            broadcast(false, "已达最大重连次数（$maxReconnectAttempts），停止重连")
            reconnectEnabled = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val delay = reconnectDelayMs.getOrElse(reconnectAttempt) { 30_000L }
        reconnectAttempt++
        val attempt = reconnectAttempt
        broadcast(false, "连接断开，${delay / 1000}s 后自动重连（第 $attempt 次）…", reconnecting = true)
        updateNotification("重连中… ($attempt/$maxReconnectAttempts)")
        thread(name = "MeshNet-reconnect") {
            try {
                Thread.sleep(delay)
                if (!reconnectEnabled) return@thread
                broadcast(false, "正在重连（第 $attempt 次）…", reconnecting = true)
                launchSession(cfg)
            } catch (_: InterruptedException) {}
        }
    }

    // =========================================================================
    // 广播
    // =========================================================================
    private fun broadcast(running: Boolean, message: String, reconnecting: Boolean = false) {
        val i = Intent(BROADCAST_STATE).apply {
            putExtra(EXTRA_RUNNING, running)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_LOCAL_VPN_IP, localVpnIp)
            putExtra(EXTRA_PEER_VPN_IP,  peerVpnIp)
            putExtra(EXTRA_RECONNECTING, reconnecting)
            `package` = packageName
        }
        sendBroadcast(i)
    }

    // =========================================================================
    // 通知
    // =========================================================================
    private fun startForegroundNotification(text: String) {
        createChannel()
        startForeground(NOTIF_ID, buildNotif(text))
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotif(text))
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("FileTran 异地组网")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "异地组网 VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }
}

// ============================================================
// Config JSON 序列化
// ============================================================
fun meshConfigToJson(cfg: MeshSessionConfig): String {
    val obj = org.json.JSONObject()
    obj.put("role",           cfg.role.name)
    obj.put("passcode",       cfg.passcode)
    obj.put("listenPort",     cfg.listenPort)
    obj.put("serverVpnIp",   cfg.serverVpnIp)
    obj.put("clientVpnIp",   cfg.clientVpnIp)
    obj.put("subnetMask",    cfg.subnetMask)
    obj.put("serverHost",    cfg.serverHost)
    obj.put("serverPort",    cfg.serverPort)
    obj.put("mtu",            cfg.mtu)
    obj.put("keepalive",     cfg.keepaliveIntervalSec)
    obj.put("serverLanCidrs", cfg.serverLanCidrs)
    obj.put("clientLanCidrs", cfg.clientLanCidrs)
    obj.put("publicIpv4",    cfg.publicIpv4)
    obj.put("publicIpv6",    cfg.publicIpv6)
    obj.put("portMappings",  cfg.portMappings)
    return obj.toString()
}

fun parseMeshConfig(json: String): MeshSessionConfig {
    val obj = org.json.JSONObject(json)
    return MeshSessionConfig(
        role             = MeshRole.valueOf(obj.getString("role")),
        passcode         = obj.getString("passcode"),
        listenPort       = obj.optInt("listenPort",   7890),
        serverVpnIp      = obj.optString("serverVpnIp",  "192.168.100.1"),
        clientVpnIp      = obj.optString("clientVpnIp",  "192.168.100.2"),
        subnetMask       = obj.optString("subnetMask",   "255.255.255.0"),
        serverHost       = obj.optString("serverHost",   ""),
        serverPort       = obj.optInt("serverPort",   7890),
        mtu              = obj.optInt("mtu",           1400),
        keepaliveIntervalSec = obj.optInt("keepalive", 20),
        serverLanCidrs   = obj.optString("serverLanCidrs", ""),
        clientLanCidrs   = obj.optString("clientLanCidrs", ""),
        publicIpv4       = obj.optString("publicIpv4",   ""),
        publicIpv6       = obj.optString("publicIpv6",   ""),
        portMappings     = obj.optString("portMappings", "")
    )
}

// ============================================================
// 网络工具
// ============================================================
private fun cidrFromMask(mask: String): Int {
    return when (mask.trim()) {
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
}

private fun vpnSubnet(ip: String, mask: String): String {
    return runCatching {
        val ipParts   = ip.split(".").map { it.toInt() }
        val maskParts = mask.split(".").map { it.toInt() }
        (0..3).joinToString(".") { (ipParts[it] and maskParts[it]).toString() }
    }.getOrDefault(ip)
}

/**
 * 构造带序列号/确认号的 TCP 数据包（用于应用层 TCP 代理）
 */
private fun buildTcpPacketWithSeq(
    srcIp: String, srcPort: Int,
    dstIp: String, dstPort: Int,
    seq: Int, ack: Int,
    flags: Int, data: ByteArray
): ByteArray = buildTcpPacket(srcIp, srcPort, dstIp, dstPort, data, flags, seq, ack)

/**
 * 构造一个简单的 TCP 数据包（用于应用层 TCP 代理回传数据）
 */
private fun buildTcpPacket(
    srcIp: String, srcPort: Int,
    dstIp: String, dstPort: Int,
    data: ByteArray, flags: Int,
    seq: Int = 0, ack: Int = 0
): ByteArray {
    val srcParts = srcIp.split(".").map { it.toInt() and 0xFF }
    val dstParts = dstIp.split(".").map { it.toInt() and 0xFF }
    val tcpLen = 20 + data.size
    val totalLen = 20 + tcpLen
    val pkt = ByteArray(totalLen)
    // IP 头
    pkt[0] = 0x45.toByte()         // version=4, ihl=5
    pkt[1] = 0
    pkt[2] = (totalLen shr 8).toByte()
    pkt[3] = (totalLen and 0xFF).toByte()
    pkt[4] = 0; pkt[5] = 0         // id
    pkt[6] = 0x40.toByte(); pkt[7] = 0  // flags=DF, frag=0
    pkt[8] = 64                    // TTL
    pkt[9] = 6                     // protocol=TCP
    pkt[10] = 0; pkt[11] = 0      // checksum placeholder
    for (i in 0..3) pkt[12 + i] = srcParts[i].toByte()
    for (i in 0..3) pkt[16 + i] = dstParts[i].toByte()
    recalcIpChecksum(pkt)
    // TCP 头
    pkt[20] = (srcPort shr 8).toByte()
    pkt[21] = (srcPort and 0xFF).toByte()
    pkt[22] = (dstPort shr 8).toByte()
    pkt[23] = (dstPort and 0xFF).toByte()
    // seq
    pkt[24]=(seq ushr 24).toByte(); pkt[25]=(seq ushr 16).toByte()
    pkt[26]=(seq ushr 8).toByte();  pkt[27]=(seq and 0xFF).toByte()
    // ack
    pkt[28]=(ack ushr 24).toByte(); pkt[29]=(ack ushr 16).toByte()
    pkt[30]=(ack ushr 8).toByte();  pkt[31]=(ack and 0xFF).toByte()
    pkt[32] = 0x50.toByte()        // data offset=5
    pkt[33] = flags.toByte()       // flags
    pkt[34] = 0xFF.toByte(); pkt[35] = 0xFF.toByte()  // window
    pkt[36] = 0; pkt[37] = 0      // checksum placeholder
    pkt[38] = 0; pkt[39] = 0      // urgent
    // data
    System.arraycopy(data, 0, pkt, 40, data.size)
    // TCP checksum（伪头部）
    var sum = 0L
    for (i in 0..3) sum += (srcParts[i] shl (if (i % 2 == 0) 8 else 0))
    for (i in 0..3) sum += (dstParts[i] shl (if (i % 2 == 0) 8 else 0))
    sum += 6 // protocol
    sum += tcpLen
    var i2 = 20
    while (i2 < totalLen - 1) {
        sum += ((pkt[i2].toInt() and 0xFF) shl 8) or (pkt[i2+1].toInt() and 0xFF)
        i2 += 2
    }
    if (tcpLen % 2 != 0) sum += (pkt[totalLen - 1].toInt() and 0xFF) shl 8
    while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
    val ck = sum.inv() and 0xFFFF
    pkt[36] = (ck shr 8).toByte()
    pkt[37] = (ck and 0xFF).toByte()
    return pkt
}

/**
 * 获取当前活跃的出口网络接口名称（优先 WiFi，其次以太网，最后 rmnet 移动数据，排除 tun/lo/dummy）
 */
private fun getWlanIfaceName(): String {
    return runCatching {
        val ifaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList()
            ?: return@runCatching "wlan0"
        // 第一优先：WiFi/以太网（有 IPv4，非 tun/lo/dummy/rmnet）
        ifaces.firstOrNull { iface ->
            iface.isUp && !iface.isLoopback &&
            !iface.name.startsWith("tun") &&
            !iface.name.startsWith("dummy") &&
            !iface.name.startsWith("rmnet") &&
            iface.inetAddresses.toList().any { it is java.net.Inet4Address && !it.isLoopbackAddress }
        }?.name
        // 第二优先：rmnet 移动数据接口（有 IPv4）
        ?: ifaces.firstOrNull { iface ->
            iface.isUp && !iface.isLoopback &&
            iface.name.startsWith("rmnet") &&
            !iface.name.startsWith("r_rmnet") &&
            iface.inetAddresses.toList().any { it is java.net.Inet4Address && !it.isLoopbackAddress }
        }?.name
        ?: "wlan0"
    }.getOrDefault("wlan0")
}

/**
 * 判断 IP 是否属于任意一个 CIDR 段
 */
private fun isInAnyCidr(ip: String, cidrs: List<String>): Boolean {
    val parts = ip.split(".").map { it.toIntOrNull() ?: return false }
    if (parts.size != 4) return false
    val ipInt = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    for (cidr in cidrs) {
        if (ipMatchesCidr(ipInt, cidr)) return true
    }
    return false
}

private fun ipMatchesCidr(ipInt: Int, cidr: String): Boolean {
    val slash = cidr.indexOf('/') .takeIf { it >= 0 } ?: return false
    val prefix = cidr.substring(slash + 1).toIntOrNull() ?: return false
    val netStrs = cidr.substring(0, slash).split(".")
    if (netStrs.size != 4) return false
    val n0 = netStrs[0].toIntOrNull() ?: return false
    val n1 = netStrs[1].toIntOrNull() ?: return false
    val n2 = netStrs[2].toIntOrNull() ?: return false
    val n3 = netStrs[3].toIntOrNull() ?: return false
    val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
    val network = (n0 shl 24) or (n1 shl 16) or (n2 shl 8) or n3
    return (ipInt and mask) == (network and mask)
}

/**
 * 改写 IPv4 包的源地址，并重新计算 IP 头校验和。
 * pkt 必须是可写的 ByteArray，长度 >= 20。
 */
private fun rewriteSrcIp(pkt: ByteArray, newSrcIp: String) {
    val parts = newSrcIp.split(".").map { it.toInt() and 0xFF }
    if (parts.size != 4) return
    pkt[12] = parts[0].toByte()
    pkt[13] = parts[1].toByte()
    pkt[14] = parts[2].toByte()
    pkt[15] = parts[3].toByte()
    recalcIpChecksum(pkt)
}

/**
 * 改写 IPv4 包的目标地址，并重新计算 IP 头校验和。
 */
private fun rewriteDstIp(pkt: ByteArray, newDstIp: String) {
    val parts = newDstIp.split(".").map { it.toInt() and 0xFF }
    if (parts.size != 4) return
    pkt[16] = parts[0].toByte()
    pkt[17] = parts[1].toByte()
    pkt[18] = parts[2].toByte()
    pkt[19] = parts[3].toByte()
    recalcIpChecksum(pkt)
}

/**
 * 重新计算 IPv4 头部校验和（offset 10-11），写回 pkt。
 */
private fun recalcIpChecksum(pkt: ByteArray) {
    val ihl = (pkt[0].toInt() and 0x0F) * 4
    if (pkt.size < ihl) return
    pkt[10] = 0
    pkt[11] = 0
    var sum = 0L
    for (i in 0 until ihl step 2) {
        val word = ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
        sum += word
    }
    while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
    val checksum = sum.inv() and 0xFFFF
    pkt[10] = (checksum shr 8).toByte()
    pkt[11] = (checksum and 0xFF).toByte()
}

/**
 * 探测本机所有网络接口中属于指定 CIDR 网段的 IPv4 地址。
 * 例如 cidr="10.0.0.0/8"，本机有 10.62.48.99，则返回 ["10.62.48.99"]。
 * 用于将本端内网真实 IP 绑定到 tun0，使内核回包时使用正确源地址。
 */
private fun getLocalIpsInCidr(cidr: String): List<String> {
    return runCatching {
        val slash   = cidr.indexOf('/')
        if (slash < 0) return@runCatching emptyList()
        val prefix  = cidr.substring(slash + 1).toIntOrNull() ?: return@runCatching emptyList()
        val netParts = cidr.substring(0, slash).split(".").map { it.toInt() }
        if (netParts.size != 4) return@runCatching emptyList()
        val mask    = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val network = (netParts[0] shl 24) or (netParts[1] shl 16) or (netParts[2] shl 8) or netParts[3]
        val netMasked = network and mask

        val result = mutableListOf<String>()
        val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return@runCatching emptyList()
        for (iface in ifaces.toList()) {
            if (!iface.isUp || iface.isLoopback || iface.name.startsWith("tun")) continue
            for (addr in iface.inetAddresses.toList()) {
                if (addr !is java.net.Inet4Address || addr.isLoopbackAddress) continue
                val ipBytes = addr.address
                val ipInt   = ((ipBytes[0].toInt() and 0xFF) shl 24) or
                              ((ipBytes[1].toInt() and 0xFF) shl 16) or
                              ((ipBytes[2].toInt() and 0xFF) shl 8)  or
                              (ipBytes[3].toInt() and 0xFF)
                if ((ipInt and mask) == netMasked) {
                    result += addr.hostAddress.orEmpty()
                    Log.d("MeshNetService", "[getLocalIpsInCidr] 发现本端内网 IP: ${addr.hostAddress} in $cidr")
                }
            }
        }
        result
    }.getOrDefault(emptyList())
}
