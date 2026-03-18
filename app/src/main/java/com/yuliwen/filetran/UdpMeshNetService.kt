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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class UdpMeshNetService : VpnService() {

    companion object {
        const val ACTION_START         = "com.yuliwen.filetran.UDP_MESH_START"
        const val ACTION_STOP          = "com.yuliwen.filetran.UDP_MESH_STOP"
        const val EXTRA_CONFIG         = "udp_mesh_config_json"
        const val BROADCAST_STATE      = "com.yuliwen.filetran.UDP_MESH_STATE"
        const val EXTRA_RUNNING        = "running"
        const val EXTRA_MESSAGE        = "message"
        const val EXTRA_LOCAL_VPN_IP   = "local_vpn_ip"
        const val EXTRA_PEER_VPN_IP    = "peer_vpn_ip"
        const val EXTRA_RECONNECTING   = "reconnecting"
        const val EXTRA_TX_BYTES       = "tx_bytes"
        const val EXTRA_RX_BYTES       = "rx_bytes"
        const val EXTRA_TX_PACKETS     = "tx_packets"
        const val EXTRA_RX_PACKETS     = "rx_packets"
        const val EXTRA_HANDSHAKE_MS   = "handshake_ms"
        const val EXTRA_MY_LAN_CIDRS   = "my_lan_cidrs"
        const val EXTRA_PEER_LAN_CIDRS = "peer_lan_cidrs"

        private const val CHANNEL_ID = "udp_mesh_vpn"
        private const val NOTIF_ID   = 9902
        private const val TAG        = "UdpMeshService"

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var localVpnIp: String = ""
            private set
        @Volatile var peerVpnIp: String = ""
            private set
    }

    private var tunFd    : ParcelFileDescriptor? = null
    private var tunOut   : FileOutputStream? = null
    private var tunnel   : UdpMeshTunnel? = null
    private val stopping = AtomicBoolean(false)
    private val tunWriteQueue = LinkedBlockingQueue<ByteArray>(4096)

    private var retryCount = 0
    private var lastCfg: UdpMeshConfig? = null
    private val rootCleanupCmds = mutableListOf<String>()

    @Volatile private var myLanCidrList   : List<String> = emptyList()
    @Volatile private var peerLanCidrList : List<String> = emptyList()

    // -------------------------------------------------------------------------
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopping.set(true)
                isRunning = false
                broadcast(false, "已断开")
                updateNotification("已断开")
                thread(name = "UdpMesh-stop") {
                    runCatching { tunnel?.stop("用户主动断开") }
                    runCatching { tunOut?.close() }
                    runCatching { tunFd?.close() }
                    cleanupRootRoutes()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val json = intent.getStringExtra(EXTRA_CONFIG) ?: return START_NOT_STICKY
                val cfg  = parseUdpMeshConfig(json)
                lastCfg  = cfg
                retryCount = 0
                startForegroundNotification("正在连接 UDP 组网…")
                launchSession(cfg)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopping.set(true)
        runCatching { tunnel?.stop("服务已销毁") }
        runCatching { tunOut?.close() }
        runCatching { tunFd?.close() }
    }

    // =========================================================================
    // 会话启动
    // =========================================================================
    private fun launchSession(cfg: UdpMeshConfig) {
        stopping.set(false)
        // NAT 打洞就绪时，监听端口强制使用 NAT_PORT（10002），与 STUN 探测时绑定的端口一致
        val effectiveCfg = if (NatPunchResult.isReady && cfg.role == UdpMeshRole.SERVER)
            cfg.copy(listenPort = UdpNatPunch.NAT_PORT)
        else cfg
        // VPN 启动前先停止心跳，释放 10002 端口，避免与 VPN socket 冲突抢包
        NatPunchResult.stopHeartbeat()
        // 稍等端口完全释放后再启动 VPN socket
        thread(name = "UdpMesh-vpn-main") {
            Thread.sleep(300)
            runSession(effectiveCfg)
        }
    }

    private fun runSession(cfg: UdpMeshConfig) {
        rootCleanupCmds.clear()
        try {
            broadcast(false, "正在启动 UDP 隧道…")

            tunnel = UdpMeshTunnel(
                cfg              = cfg,
                onPacketReceived = { pkt -> onTunnelPacket(pkt) },
                onStateChange    = { msg ->
                    Log.d(TAG, "[状态] $msg")
                    broadcast(isRunning, msg)
                },
                onHandshakeDone  = { localIp, peerIp ->
                    localVpnIp = localIp
                    peerVpnIp  = peerIp
                    retryCount = 0
                    // VPN 隧道握手成功，NAT 映射已不再需要心跳维持
                    NatPunchResult.stopHeartbeat()
                    thread(name = "UdpMesh-tun-setup") {
                        setupTunAndRoutes(cfg, localIp, peerIp)
                    }
                },
                onDisconnected   = { reason ->
                    if (!stopping.get() && cfg.role == UdpMeshRole.CLIENT) {
                        thread(name = "UdpMesh-disconnect") {
                            handleDisconnect(cfg, reason)
                        }
                    } else if (cfg.role == UdpMeshRole.SERVER) {
                        Log.i(TAG, "[服务端] 客户端断开，重新启动监听")
                        if (!stopping.get()) {
                            thread(name = "UdpMesh-restart") {
                                Thread.sleep(1000)
                                launchSession(cfg)
                            }
                        }
                    }
                },
                protectSocket    = { sock -> protect(sock) },
                bindSocketToIface = { sock, ifaceName ->
                    // 用 ConnectivityManager 找到接口对应的 Network 并绑定，
                    // 强制 socket 从指定网卡出去，解决双接口 ACK 走错问题
                    try {
                        val cm = getSystemService(android.net.ConnectivityManager::class.java)
                        val targetNetwork = cm?.allNetworks?.firstOrNull { network ->
                            cm.getLinkProperties(network)?.interfaceName == ifaceName
                        }
                        if (targetNetwork != null) {
                            targetNetwork.bindSocket(sock)
                            Log.i(TAG, "[bindSocketToIface] socket 已绑定到网络接口 $ifaceName")
                        } else {
                            Log.w(TAG, "[bindSocketToIface] 未找到接口 $ifaceName 对应的 Network")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[bindSocketToIface] 绑定失败: ${e.message}")
                    }
                }
            )
            tunnel!!.start()

        } catch (e: Exception) {
            Log.e(TAG, "会话启动失败: ${e.message}", e)
            broadcast(false, "启动失败: ${e.message}")
            if (!stopping.get()) {
                thread(name = "UdpMesh-disconnect") {
                    handleDisconnect(cfg, "启动失败: ${e.message}")
                }
            }
        }
    }

    // =========================================================================
    // 断开处理（含自动重试）
    // =========================================================================
    private fun handleDisconnect(cfg: UdpMeshConfig, reason: String) {
        // 清理当前会话
        isRunning = false
        localVpnIp = ""
        peerVpnIp  = ""
        cleanupRootRoutes()
        runCatching { tunnel?.stop(reason) }
        runCatching { tunOut?.close() }
        runCatching { tunFd?.close() }
        tunnel = null
        tunOut = null
        tunFd  = null
        tunWriteQueue.clear()

        // 仅客户端执行自动重试逻辑，服务端不重试
        val shouldRetry = !stopping.get()
            && cfg.role == UdpMeshRole.CLIENT
            && cfg.maxRetryCount != 0
            && (cfg.maxRetryCount < 0 || retryCount < cfg.maxRetryCount)

        if (shouldRetry) {
            retryCount++
            val maxDesc = if (cfg.maxRetryCount < 0) "∞" else cfg.maxRetryCount.toString()
            val msg = "连接断开，${cfg.retryIntervalSec}s 后重试（$retryCount/$maxDesc）\n原因: $reason"
            broadcast(false, msg, reconnecting = true)
            updateNotification("重连中 $retryCount/$maxDesc…")
            Log.d(TAG, "[自动重试] $msg")
            Thread.sleep(cfg.retryIntervalSec * 1000L)
            if (!stopping.get()) {
                broadcast(false, "正在重连…（第 $retryCount 次）", reconnecting = true)
                launchSession(cfg)
            }
        } else {
            // 服务端或客户端不重试时，直接断开
            broadcast(false, reason)
            updateNotification("已断开")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // =========================================================================
    // =========================================================================
    // 握手完成后建立 TUN + 路由
    // =========================================================================
    private fun setupTunAndRoutes(cfg: UdpMeshConfig, localIp: String, peerIp: String) {
        val tun = buildTun(localIp, cfg.subnetMask, cfg.mtu, cfg)
        if (tun == null) {
            broadcast(false, "TUN 设备建立失败")
            tunnel?.stop("TUN 建立失败")
            return
        }
        tunFd  = tun
        tunOut = FileOutputStream(tun.fileDescriptor)

        startTunWriteLoop()
        Thread.sleep(300)
        setupRootRoutes(cfg, localIp, peerIp)

        isRunning = true
        
        // 构建互通网段信息
        val msg = buildString {
            append("已连接：本机 $localIp ↔ 对端 $peerIp")
            if (myLanCidrList.isNotEmpty() || peerLanCidrList.isNotEmpty()) {
                append("\n互通网段：")
                if (myLanCidrList.isNotEmpty()) append("\n本端: ${myLanCidrList.joinToString()}")
                if (peerLanCidrList.isNotEmpty()) append("\n对端: ${peerLanCidrList.joinToString()}")
            }
        }
        broadcast(true, msg)
        updateNotification("已连接 | $localIp ↔ $peerIp")

        // 心跳 + 定期广播流量统计
        // NAT 模式下每 5 秒发送一次心跳以维持映射，普通模式按配置间隔
        thread(name = "UdpMesh-keepalive") {
            val heartbeatInterval = (cfg.keepaliveIntervalSec * 1000L).toLong()
            while (isRunning && !stopping.get()) {
                Thread.sleep(heartbeatInterval)
                tunnel?.sendKeepalive()
                if (isRunning) broadcastStats()
            }
        }

        // TUN → 隧道转发循环
        val tunIn = FileInputStream(tun.fileDescriptor)
        val buf   = ByteArray(65535)
        try {
            while (!stopping.get()) {
                val n = tunIn.read(buf)
                if (n > 0 && n >= 20) {
                    val version = (buf[0].toInt() and 0xFF) shr 4
                    val dstIp = if (version == 4 && n >= 20)
                        "${buf[16].toInt() and 0xFF}.${buf[17].toInt() and 0xFF}.${buf[18].toInt() and 0xFF}.${buf[19].toInt() and 0xFF}"
                    else ""
                    Log.d(TAG, "TUN读包 n=$n ver=$version dst=$dstIp")
                    if (version == 4 || (version == 6 && n >= 40)) {
                        tunnel?.sendTunPacket(buf.copyOf(n))
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "TUN 读取退出: ${e.message}")
        }
    }

    // =========================================================================
    // 从隧道收到 IP 包 → 写入 TUN
    // =========================================================================
    private fun onTunnelPacket(pkt: ByteArray) {
        if (pkt.size < 20) return
        val version = (pkt[0].toInt() and 0xFF) shr 4
        val data = when (version) {
            4 -> {
                val ipTotalLen = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
                val writeLen = if (ipTotalLen in 20..pkt.size) ipTotalLen else pkt.size
                if (writeLen == pkt.size) pkt else pkt.copyOf(writeLen)
            }
            6 -> {
                if (pkt.size < 40) return
                val payloadLen = ((pkt[4].toInt() and 0xFF) shl 8) or (pkt[5].toInt() and 0xFF)
                val totalLen = 40 + payloadLen
                if (totalLen in 40..pkt.size) pkt.copyOf(totalLen) else pkt
            }
            else -> return
        }
        if (!tunWriteQueue.offer(data)) {
            Log.w(TAG, "TUN 写入队列满，丢包")
        }
    }

    private fun startTunWriteLoop() {
        thread(name = "UdpMesh-tunwrite") {
            try {
                while (!stopping.get()) {
                    val pkt = tunWriteQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    val out = tunOut ?: continue
                    runCatching { out.write(pkt) }
                        .onFailure { Log.e(TAG, "TUN 写入失败: ${it.message}") }
                }
            } catch (_: InterruptedException) {}
            Log.d(TAG, "TUN 写入线程退出")
        }
    }

    // =========================================================================
    // TUN 设备建立
    // =========================================================================
    private fun buildTun(
        localIp: String, mask: String, mtu: Int, cfg: UdpMeshConfig
    ): ParcelFileDescriptor? = runCatching {
        val cidr = udpCidrFromMask(mask)
        val b = Builder()
            .setSession("FileTran UDP MeshNet")
            .addAddress(localIp, cidr)
            .setMtu(mtu)
            .setBlocking(true)

        val subnet = udpVpnSubnet(localIp, mask)
        b.addRoute(subnet, cidr)

        val peerLanCidrs = when (cfg.role) {
            UdpMeshRole.SERVER -> cfg.clientLanCidrs
            UdpMeshRole.CLIENT -> cfg.serverLanCidrs
        }.split(",").map { it.trim() }.filter { it.isNotBlank() }

        for (lanCidr in peerLanCidrs) {
            val net  = lanCidr.substringBefore("/")
            val bits = lanCidr.substringAfter("/", "24").toIntOrNull() ?: 24
            runCatching { b.addRoute(net, bits) }
        }

        b.addDisallowedApplication(packageName)

        val pfd = b.establish()
        Log.d(TAG, "TUN 建立${if (pfd != null) "成功 fd=${pfd.fd}" else "失败"}")
        pfd
    }.getOrElse { e ->
        Log.e(TAG, "buildTun 异常: ${e.message}", e)
        null
    }

    // =========================================================================
    // Root 路由
    // =========================================================================
    private fun setupRootRoutes(cfg: UdpMeshConfig, localIp: String, peerIp: String) {
        val myLanCidrs = when (cfg.role) {
            UdpMeshRole.SERVER -> cfg.serverLanCidrs
            UdpMeshRole.CLIENT -> cfg.clientLanCidrs
        }.split(",").map { it.trim() }.filter { it.isNotBlank() }

        val peerLanCidrs = when (cfg.role) {
            UdpMeshRole.SERVER -> cfg.clientLanCidrs
            UdpMeshRole.CLIENT -> cfg.serverLanCidrs
        }.split(",").map { it.trim() }.filter { it.isNotBlank() }

        myLanCidrList   = myLanCidrs
        peerLanCidrList = peerLanCidrs

        val cmds    = mutableListOf<String>()
        val cleanup = mutableListOf<String>()
        val tunIface  = "tun0"
        // lanIface 非空时直接用，否则自动选（排除 tunnelIface）
        val wlanIface = udpGetWlanIfaceName(tunnelIface = cfg.tunnelIface, lanIface = cfg.lanIface)
        val vpnSubnet = udpVpnSubnet(localIp, cfg.subnetMask)
        val vpnCidr   = udpCidrFromMask(cfg.subnetMask)
        // 获取 wlanIface 的网关，用于 table local_network 路由
        // 仅查 default via，不做 fallback 推算：
        //   - 普通 WiFi 客户端接口有默认路由，可拿到网关
        //   - 热点 AP / USB 共享接口本身就是网关，无 default 路由，直接用 scope link（via=null）即可
        val wlanGateway = runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "ip route show table all dev $wlanIface"))
            val lines = proc.inputStream.bufferedReader().readLines()
            proc.waitFor()
            lines.firstOrNull { it.trimStart().startsWith("default") }
                ?.let { Regex("via\\s+([\\d.]+)").find(it)?.groupValues?.get(1) }
        }.getOrNull()
        Log.d(TAG, "[setupRootRoutes] wlanIface=$wlanIface gateway=$wlanGateway")

        cmds += "sysctl -w net.ipv4.ip_forward=1"
        cmds += "sysctl -w net.ipv4.conf.all.rp_filter=0"
        cmds += "sysctl -w net.ipv4.conf.all.forwarding=1"
        cmds += "sysctl -w net.ipv4.conf.$tunIface.rp_filter=0"
        cmds += "sysctl -w net.ipv4.conf.$wlanIface.rp_filter=0"
        cmds += "sysctl -w net.ipv4.conf.$tunIface.forwarding=1"
        cmds += "sysctl -w net.ipv4.conf.$wlanIface.forwarding=1"

        cmds += "ip route del $vpnSubnet/$vpnCidr dev $tunIface table local_network 2>/dev/null; ip route add $vpnSubnet/$vpnCidr dev $tunIface table local_network 2>/dev/null || true"
        cleanup += "ip route del $vpnSubnet/$vpnCidr dev $tunIface table local_network 2>/dev/null || true"

        for (cidr in peerLanCidrs) {
            val net  = cidr.substringBefore("/")
            val bits = cidr.substringAfter("/", "24").toIntOrNull() ?: 24
            cmds += "ip route del $net/$bits 2>/dev/null; ip route add $net/$bits via $peerIp dev $tunIface 2>/dev/null || true"
            cleanup += "ip route del $net/$bits 2>/dev/null || true"
            cmds += "ip route del $net/$bits dev $tunIface table local_network 2>/dev/null; ip route add $net/$bits via $peerIp dev $tunIface table local_network 2>/dev/null || true"
            cleanup += "ip route del $net/$bits dev $tunIface table local_network 2>/dev/null || true"
            cmds += "iptables -t nat -A POSTROUTING -d $cidr -o $tunIface -j MASQUERADE 2>/dev/null || true"
            cleanup += "iptables -t nat -D POSTROUTING -d $cidr -o $tunIface -j MASQUERADE 2>/dev/null || true"
        }

        for (cidr in myLanCidrs) {
            val net  = cidr.substringBefore("/")
            val bits = cidr.substringAfter("/", "24").toIntOrNull() ?: 24
            val myIpsInCidr = udpGetLocalIpsInCidr(cidr)
            for (ip in myIpsInCidr) {
                cmds += "ip addr del $ip/32 dev $tunIface 2>/dev/null; ip addr add $ip/32 dev $tunIface 2>/dev/null || true"
                cleanup += "ip addr del $ip/32 dev $tunIface 2>/dev/null || true"
                Log.d(TAG, "[setupRootRoutes] 绑定本端内网 IP $ip/32 到 $tunIface")
            }
            // local_network 表：本端内网网段走 wlanIface（带网关，避免 scope link）
            // 这条路由用于：从 tun0 收到对端发来的、目标为本端内网的包，路由到 wlan 出口转发给内网设备
            cmds += "ip route del $net/$bits dev $wlanIface table local_network 2>/dev/null; ip route add $net/$bits ${if (wlanGateway != null) "via $wlanGateway " else ""}dev $wlanIface table local_network 2>/dev/null || true"
            cleanup += "ip route del $net/$bits dev $wlanIface table local_network 2>/dev/null || true"
            // 让来自本端内网设备（热点/USB共享）的包查 local_network 表
            // 优先级 19500，插在 Android 默认 local_network 规则（20000）之前
            // local_network 表里有 peerLanCidrs via $peerIp dev tun0，内网设备访问对端内网的包会走 tun0
            cmds += "ip rule del from $net/$bits iif $wlanIface lookup local_network priority 19500 2>/dev/null; ip rule add from $net/$bits iif $wlanIface lookup local_network priority 19500 2>/dev/null || true"
            cleanup += "ip rule del from $net/$bits iif $wlanIface lookup local_network priority 19500 2>/dev/null || true"
            cmds += "iptables -A FORWARD -i $tunIface -o $wlanIface -d $cidr -j ACCEPT 2>/dev/null || true"
            cmds += "iptables -A FORWARD -i $wlanIface -o $tunIface -s $cidr -j ACCEPT 2>/dev/null || true"
            cleanup += "iptables -D FORWARD -i $tunIface -o $wlanIface -d $cidr -j ACCEPT 2>/dev/null || true"
            cleanup += "iptables -D FORWARD -i $wlanIface -o $tunIface -s $cidr -j ACCEPT 2>/dev/null || true"
            cmds += "iptables -t nat -A POSTROUTING -s $vpnSubnet/$vpnCidr -o $wlanIface -d $cidr -j MASQUERADE 2>/dev/null || true"
            cleanup += "iptables -t nat -D POSTROUTING -s $vpnSubnet/$vpnCidr -o $wlanIface -d $cidr -j MASQUERADE 2>/dev/null || true"
            // 来自内网设备、出向 tun0 的包也需要 MASQUERADE，让对端看到的源 IP 是本机 VPN IP
            cmds += "iptables -t nat -A POSTROUTING -s $net/$bits -o $tunIface -j MASQUERADE 2>/dev/null || true"
            cleanup += "iptables -t nat -D POSTROUTING -s $net/$bits -o $tunIface -j MASQUERADE 2>/dev/null || true"
        }

        // 放行 tetherctrl_FORWARD 链
        // 先删除可能残留的旧规则，再插入，确保幂等且排在 DROP 之前
        cmds += "iptables -D tetherctrl_FORWARD -i $tunIface -o $wlanIface -j ACCEPT 2>/dev/null || true"
        cmds += "iptables -D tetherctrl_FORWARD -i $wlanIface -o $tunIface -j ACCEPT 2>/dev/null || true"
        cmds += "iptables -t nat -D POSTROUTING -o $wlanIface -j MASQUERADE 2>/dev/null || true"
        cmds += "iptables -I tetherctrl_FORWARD 1 -i $tunIface -o $wlanIface -j ACCEPT 2>/dev/null || true"
        cmds += "iptables -I tetherctrl_FORWARD 1 -i $wlanIface -o $tunIface -j ACCEPT 2>/dev/null || true"
        cmds += "iptables -t nat -I POSTROUTING 1 -o $wlanIface -j MASQUERADE 2>/dev/null || true"
        cleanup += "iptables -D tetherctrl_FORWARD -i $tunIface -o $wlanIface -j ACCEPT 2>/dev/null || true"
        cleanup += "iptables -D tetherctrl_FORWARD -i $wlanIface -o $tunIface -j ACCEPT 2>/dev/null || true"
        cleanup += "iptables -t nat -D POSTROUTING -o $wlanIface -j MASQUERADE 2>/dev/null || true"

        rootCleanupCmds.addAll(cleanup)
        val ok = runAsRoot(*cmds.toTypedArray())
        if (ok) {
            val msg = buildString {
                append("内网路由已配置")
                if (myLanCidrs.isNotEmpty()) append("\n本端: ${myLanCidrs.joinToString()}")
                if (peerLanCidrs.isNotEmpty()) append("\n对端: ${peerLanCidrs.joinToString()}")
            }
            broadcast(true, msg)
        } else {
            broadcast(true, "⚠ root 路由配置失败，内网互访可能不通")
        }
    }

    private fun cleanupRootRoutes() {
        if (rootCleanupCmds.isEmpty()) return
        thread(name = "UdpMesh-cleanup") {
            runAsRoot(*rootCleanupCmds.toTypedArray())
            rootCleanupCmds.clear()
        }
    }

    private fun runAsRoot(vararg cmds: String): Boolean = try {
        // iptables 在 SELinux Enforcing 下尝试通过 nsenter 进入 init network namespace 执行
        // 若 nsenter 不可用（Permission denied），回退到直接 su -c iptables
        val nsenterAvailable: Boolean by lazy {
            runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "nsenter -t 1 -n -- true"))
                val err = p.errorStream.bufferedReader().readText()
                p.waitFor()
                !err.contains("Permission denied") && !err.contains("No such")
            }.getOrDefault(false)
        }
        val processedCmds = cmds.map { cmd ->
            val isIptables = cmd.trimStart().startsWith("iptables") || cmd.trimStart().startsWith("ip6tables")
            if (isIptables && nsenterAvailable) "nsenter -t 1 -n -- $cmd" else cmd
        }
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", processedCmds.joinToString("\n")))
        val out  = proc.inputStream.bufferedReader().readText()
        val err  = proc.errorStream.bufferedReader().readText()
        proc.waitFor()
        if (out.isNotBlank()) Log.d(TAG, "[root] $out")
        if (err.isNotBlank()) Log.w(TAG, "[root err] $err")
        true
    } catch (e: Exception) {
        Log.w(TAG, "su 失败: ${e.message}")
        false
    }

    // =========================================================================
    // 广播
    // =========================================================================
    private fun broadcast(running: Boolean, message: String, reconnecting: Boolean = false) {
        val t = tunnel
        val i = Intent(BROADCAST_STATE).apply {
            putExtra(EXTRA_RUNNING,      running)
            putExtra(EXTRA_MESSAGE,      message)
            putExtra(EXTRA_LOCAL_VPN_IP, localVpnIp)
            putExtra(EXTRA_PEER_VPN_IP,  peerVpnIp)
            putExtra(EXTRA_RECONNECTING, reconnecting)
            putExtra(EXTRA_TX_BYTES,     t?.txBytes   ?: 0L)
            putExtra(EXTRA_RX_BYTES,     t?.rxBytes   ?: 0L)
            putExtra(EXTRA_TX_PACKETS,   t?.txPackets ?: 0L)
            putExtra(EXTRA_RX_PACKETS,   t?.rxPackets ?: 0L)
            putExtra(EXTRA_HANDSHAKE_MS, t?.lastHandshakeTimeMs ?: 0L)
            putExtra(EXTRA_MY_LAN_CIDRS, myLanCidrList.joinToString(","))
            putExtra(EXTRA_PEER_LAN_CIDRS, peerLanCidrList.joinToString(","))
            `package` = packageName
        }
        sendBroadcast(i)
    }

    private fun broadcastStats() {
        if (!isRunning) return
        broadcast(true, "")
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
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("FileTran UDP 组网")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "UDP 组网 VPN", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }
}

// ============================================================
// VPN 启停（供 UI 调用）
// ============================================================
fun startUdpMeshVpn(context: android.content.Context, cfg: UdpMeshConfig) {
    val i = Intent(context, UdpMeshNetService::class.java).apply {
        action = UdpMeshNetService.ACTION_START
        putExtra(UdpMeshNetService.EXTRA_CONFIG, udpMeshConfigToJson(cfg))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
    else context.startService(i)
}

fun stopUdpMeshVpn(context: android.content.Context) {
    context.startService(
        Intent(context, UdpMeshNetService::class.java).apply {
            action = UdpMeshNetService.ACTION_STOP
        }
    )
}

// ============================================================
// 获取内网（LAN）出口接口名
// lanIface:    用户显式指定的内网接口，非空时直接使用（最高优先级）
// tunnelIface: 隧道出口接口名（如有 IPv6 的 wlan1 / rmnet_data0）
//              指定后内网接口会排除隧道接口，优先选另一个 wlan
// ============================================================
fun udpGetWlanIfaceName(tunnelIface: String = "", lanIface: String = ""): String = runCatching {
    // 用户直接指定了内网接口，直接返回（无需自动探测）
    if (lanIface.isNotBlank()) return@runCatching lanIface

    val ifaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: return@runCatching "wlan0"
    // 隧道接口非空时，内网接口必须排除隧道接口，避免两者冲突
    val excludeIface = tunnelIface.trim()

    // 优先：wlan 类接口（且不是隧道接口）有 IPv4
    ifaces.firstOrNull { iface ->
        iface.isUp && !iface.isLoopback &&
        iface.name != excludeIface &&
        (iface.name.startsWith("wlan") || iface.name.startsWith("swlan") || iface.name.startsWith("eth")) &&
        iface.inetAddresses.toList().any { it is java.net.Inet4Address && !it.isLoopbackAddress }
    }?.name
    // 次选：任意非 tun/dummy/rmnet/隧道接口有 IPv4
    ?: ifaces.firstOrNull { iface ->
        iface.isUp && !iface.isLoopback &&
        iface.name != excludeIface &&
        !iface.name.startsWith("tun") &&
        !iface.name.startsWith("dummy") &&
        !iface.name.startsWith("rmnet") &&
        iface.inetAddresses.toList().any { it is java.net.Inet4Address && !it.isLoopbackAddress }
    }?.name
    // 兜底：rmnet（移动数据）
    ?: ifaces.firstOrNull { iface ->
        iface.isUp && !iface.isLoopback &&
        iface.name != excludeIface &&
        iface.name.startsWith("rmnet") &&
        iface.inetAddresses.toList().any { it is java.net.Inet4Address && !it.isLoopbackAddress }
    }?.name
    ?: "wlan0"
}.getOrDefault("wlan0")

fun udpGetLocalIpsInCidr(cidr: String): List<String> = runCatching {
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
            val ipInt = ((ipBytes[0].toInt() and 0xFF) shl 24) or
                        ((ipBytes[1].toInt() and 0xFF) shl 16) or
                        ((ipBytes[2].toInt() and 0xFF) shl 8)  or
                         (ipBytes[3].toInt() and 0xFF)
            if ((ipInt and mask) == netMasked) result += addr.hostAddress.orEmpty()
        }
    }
    result
}.getOrDefault(emptyList())
