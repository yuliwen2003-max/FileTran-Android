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
        const val ACTION_START       = "com.yuliwen.filetran.UDP_MESH_START"
        const val ACTION_STOP        = "com.yuliwen.filetran.UDP_MESH_STOP"
        const val EXTRA_CONFIG       = "udp_mesh_config_json"
        const val BROADCAST_STATE    = "com.yuliwen.filetran.UDP_MESH_STATE"
        const val EXTRA_RUNNING      = "running"
        const val EXTRA_MESSAGE      = "message"
        const val EXTRA_LOCAL_VPN_IP = "local_vpn_ip"
        const val EXTRA_PEER_VPN_IP  = "peer_vpn_ip"
        const val EXTRA_RECONNECTING = "reconnecting"
        const val EXTRA_TX_BYTES     = "tx_bytes"
        const val EXTRA_RX_BYTES     = "rx_bytes"
        const val EXTRA_TX_PACKETS   = "tx_packets"
        const val EXTRA_RX_PACKETS   = "rx_packets"
        const val EXTRA_HANDSHAKE_MS = "handshake_ms"

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

    // 自动重试状态
    private var retryCount = 0
    private var lastCfg: UdpMeshConfig? = null

    // Root 路由清理命令列表
    private val rootCleanupCmds = mutableListOf<String>()

    @Volatile private var myLanCidrList   : List<String> = emptyList()
    @Volatile private var peerLanCidrList : List<String> = emptyList()

    // -------------------------------------------------------------------------
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopping.set(true)
                // 立即广播断开，不等 teardown，解决卡住问题
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
        thread(name = "UdpMesh-vpn-main") { runSession(cfg) }
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
                    retryCount = 0  // 握手成功，重置重试计数
                    thread(name = "UdpMesh-tun-setup") {
                        setupTunAndRoutes(cfg, localIp, peerIp)
                    }
                },
                onDisconnected   = { reason ->
                    if (!stopping.get()) {
                        thread(name = "UdpMesh-disconnect") {
                            handleDisconnect(cfg, reason)
                        }
                    }
                },
                protectSocket    = { sock -> protect(sock) }
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

        // 客户端自动重试逻辑
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
            broadcast(false, reason)
            updateNotification("已断开")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

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
        broadcast(true, "已连接：本机 $localIp ↔ 对端 $peerIp")
        updateNotification("已连接 | $localIp ↔ $peerIp")

        // 心跳 + 定期广播流量统计
        thread(name = "UdpMesh-keepalive") {
            while (isRunning && !stopping.get()) {
                Thread.sleep(cfg.keepaliveIntervalSec * 1000L)
                tunnel?.sendKeepalive()
                // 每个心跳周期广播一次流量统计
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
        val wlanIface = udpGetWlanIfaceName(cfg.tunnelIface)
        val vpnSubnet = udpVpnSubnet(localIp, cfg.subnetMask)
        val vpnCidr   = udpCidrFromMask(cfg.subnetMask)

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
            cmds += "ip route del $net/$bits dev $wlanIface table local_network 2>/dev/null; ip route add $net/$bits dev $wlanIface table local_network 2>/dev/null || true"
            cleanup += "ip route del $net/$bits dev $wlanIface table local_network 2>/dev/null || true"
            cmds += "iptables -A FORWARD -i $tunIface -o $wlanIface -d $cidr -j ACCEPT 2>/dev/null || true"
            cmds += "iptables -A FORWARD -i $wlanIface -o $tunIface -s $cidr -j ACCEPT 2>/dev/null || true"
            cleanup += "iptables -D FORWARD -i $tunIface -o $wlanIface -d $cidr -j ACCEPT 2>/dev/null || true"
            cleanup += "iptables -D FORWARD -i $wlanIface -o $tunIface -s $cidr -j ACCEPT 2>/dev/null || true"
            cmds += "iptables -t nat -A POSTROUTING -s $vpnSubnet/$vpnCidr -o $wlanIface -d $cidr -j MASQUERADE 2>/dev/null || true"
            cleanup += "iptables -t nat -D POSTROUTING -s $vpnSubnet/$vpnCidr -o $wlanIface -d $cidr -j MASQUERADE 2>/dev/null || true"
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
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmds.joinToString("\n")))
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
// 获取出口网络接口名
// tunnelIface: 隧道绑定的接口名（如移动数据 rmnet），非空时内网接口优先选 wlan
// ============================================================
fun udpGetWlanIfaceName(tunnelIface: String = ""): String = runCatching {
    val ifaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: return@runCatching "wlan0"
    // 如果隧道走移动数据接口，内网接口优先选 wlan
    val preferWlan = tunnelIface.isNotBlank() &&
        (tunnelIface.startsWith("rmnet") || tunnelIface.startsWith("ccmni") || tunnelIface.startsWith("v4-"))
    if (preferWlan) {
        ifaces.firstOrNull { iface ->
            iface.isUp && !iface.isLoopback &&
            (iface.name.startsWith("wlan") || iface.name.startsWith("swlan")) &&
            iface.inetAddresses.toList().any { it is java.net.Inet4Address && !it.isLoopbackAddress }
        }?.name?.let { return@runCatching it }
    }
    ifaces.firstOrNull { iface ->
        iface.isUp && !iface.isLoopback &&
        !iface.name.startsWith("tun") &&
        !iface.name.startsWith("dummy") &&
        !iface.name.startsWith("rmnet") &&
        iface.inetAddresses.toList().any { it is java.net.Inet4Address && !it.isLoopbackAddress }
    }?.name
    ?: ifaces.firstOrNull { iface ->
        iface.isUp && !iface.isLoopback &&
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
