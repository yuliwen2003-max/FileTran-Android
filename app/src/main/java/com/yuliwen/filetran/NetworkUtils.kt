package com.yuliwen.filetran

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet6Address
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.UnknownHostException
import java.util.*
import kotlin.random.Random
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

data class StunMappedEndpoint(
    val address: String,
    val port: Int,
    val stunServer: String
)

data class StunServerProbeStatus(
    val server: String,
    val transport: StunTransportType,
    val attemptedPorts: List<Int>,
    val mappedEndpoint: StunMappedEndpoint?,
    val note: String
)

data class StunProbeBatchResult(
    val transport: StunTransportType,
    val preferIpv6: Boolean,
    val localPort: Int,
    val publicIpByIpip: String?,
    val endpoints: List<StunMappedEndpoint>,
    val matchedByIpip: List<StunMappedEndpoint>,
    val preferredEndpoint: StunMappedEndpoint?,
    val serverStatuses: List<StunServerProbeStatus> = emptyList()
) {
    val allMismatch: Boolean
        get() = publicIpByIpip != null && endpoints.isNotEmpty() && matchedByIpip.isEmpty()

    val failedServerStatuses: List<StunServerProbeStatus>
        get() = serverStatuses.filter { it.mappedEndpoint == null }

    val hasPortMismatch: Boolean
        get() = endpoints.map { it.port }.distinct().size > 1

    val uniquePorts: List<Int>
        get() = endpoints.map { it.port }.distinct()
}

data class NatProtocolDetectResult(
    val natType: String,
    val mappingBehavior: String,
    val filteringBehavior: String,
    val mappedEndpoint: StunMappedEndpoint?,
    val note: String = ""
)

data class NatTypeDetectResult(
    val udp: NatProtocolDetectResult,
    val tcp: NatProtocolDetectResult
)

enum class StunTransportType {
    UDP, TCP, TLS
}

data class StunServerDefinition(
    val server: String,
    val ip: String = "",
    val port: Int = 3478
)

data class StunServerSettings(
    val ipv4Tcp: List<StunServerDefinition>,
    val ipv4Udp: List<StunServerDefinition>,
    val ipv6Tcp: List<StunServerDefinition>,
    val ipv6Udp: List<StunServerDefinition>
)

object NetworkUtils {
    private data class ConfiguredStunTarget(
        val queryHost: String,
        val displayName: String,
        val port: Int
    )

    private const val IPV6_API_HOST = "test6.ustc.edu.cn"
    private const val IPV6_API_URL = "https://test6.ustc.edu.cn/backend/getIP.php"
    private const val STUN_SETTINGS_PREFS = "filetran_stun_settings"
    private const val STUN_SETTINGS_KEY = "stun_settings_v1"
    private const val STUN_SERVER_LIST_LIMIT = 5
    private val DEFAULT_STUN_SETTINGS = StunServerSettings(
        ipv4Tcp = listOf(
            StunServerDefinition(server = "stun.hot-chilli.net"),
            StunServerDefinition(server = "stun.nextcloud.com"),
            StunServerDefinition(server = "turn.cloudflare.com")
        ),
        ipv4Udp = listOf(
            StunServerDefinition(server = "stun.miwifi.com"),
            StunServerDefinition(server = "stun.chat.bilibili.com"),
            StunServerDefinition(server = "stun.hot-chilli.net")
        ),
        ipv6Tcp = listOf(
            StunServerDefinition(server = "stun.hot-chilli.net"),
            StunServerDefinition(server = "stun.nextcloud.com"),
            StunServerDefinition(server = "turn.cloudflare.com")
        ),
        ipv6Udp = listOf(
            StunServerDefinition(server = "stun.hot-chilli.net"),
            StunServerDefinition(server = "stun.nextcloud.com"),
            StunServerDefinition(server = "turn.cloudflare.com")
        )
    )
    @Volatile
    private var currentStunSettings: StunServerSettings = DEFAULT_STUN_SETTINGS
    @Volatile
    private var stunSettingsInitialized = false

    private const val IPIP_V4_HTTPS = "https://v4.ipip.net"
    private const val IPIP_V4_HTTP = "http://v4.ipip.net"
    private const val IPIP_V6_HTTPS = "https://v6.ipip.net"
    private const val IPIP_V6_HTTP = "http://v6.ipip.net"
    private const val STUN_MAGIC_COOKIE = 0x2112A442
    private const val DNS_FALLBACK_IPV6_RESOLVER = "223.5.5.5"
    private const val ATTR_MAPPED_ADDRESS = 0x0001
    private const val ATTR_CHANGE_REQUEST = 0x0003
    private const val ATTR_CHANGED_ADDRESS = 0x0005
    private const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
    private const val ATTR_OTHER_ADDRESS = 0x802c

    fun getLocalIpAddress(context: Context): String? {
        try {
            // First try getting IPv4 from Wi-Fi interface.
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress
            
            if (ipInt != 0) {
                return String.format(
                    Locale.getDefault(),
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
            
            // Fallback: iterate active network interfaces.
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is InetAddress) {
                        val hostAddress = address.hostAddress
                        if (hostAddress != null && hostAddress.indexOf(':') < 0) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return null
    }

    fun getLocalGlobalIpv6Address(): String? {
        fetchPublicIpv6FromApi()?.let { return it }
        return getLocalGlobalIpv6AddressFromInterfaces()
    }

    fun getInterfaceGlobalIpv6Address(): String? {
        return getLocalGlobalIpv6AddressFromInterfaces()
    }

    private fun fetchPublicIpv6FromApi(): String? {
        return try {
            val url = URL(IPV6_API_URL)
            val connection = (url.openConnection() as? HttpsURLConnection) ?: return null
            connection.sslSocketFactory = Ipv6OnlySslSocketFactory(
                delegate = HttpsURLConnection.getDefaultSSLSocketFactory(),
                targetHost = IPV6_API_HOST
            )
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                connection.disconnect()
                return null
            }
            val body = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            connection.disconnect()

            val ip = JSONObject(body).optString("processedString").trim().substringBefore('%')
            if (ip.contains(":")) ip else null
        } catch (_: Exception) {
            null
        }
    }

    private fun getLocalGlobalIpv6AddressFromInterfaces(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address.isLoopbackAddress) continue
                    val host = address.hostAddress ?: continue
                    if (!host.contains(":")) continue
                    if (host.startsWith("fe80", ignoreCase = true)) continue
                    val normalized = host.substringBefore('%')
                    if (normalized == "::1") continue
                    return normalized
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun findRandomAvailablePort(): Int {
        return try {
            ServerSocket(0).use { socket ->
                socket.localPort
            }
        } catch (_: Exception) {
            12333
        }
    }

    fun formatHostForUrl(host: String): String {
        val clean = host.trim()
        if (clean.isEmpty()) return clean
        if (clean.contains(":")) {
            if (clean.startsWith("[") && clean.endsWith("]")) return clean
            return "[$clean]"
        }
        return clean
    }

    fun initializeStunServerSettings(context: Context) {
        if (stunSettingsInitialized) return
        currentStunSettings = loadStunSettingsFromPrefs(context)
        stunSettingsInitialized = true
    }

    fun getStunServerSettings(context: Context? = null): StunServerSettings {
        if (!stunSettingsInitialized && context != null) {
            initializeStunServerSettings(context)
        }
        return currentStunSettings
    }

    fun saveStunServerSettings(context: Context, settings: StunServerSettings) {
        val normalized = normalizeStunSettings(settings)
        currentStunSettings = normalized
        stunSettingsInitialized = true
        val json = JSONObject().apply {
            put("ipv4Tcp", toStunJsonArray(normalized.ipv4Tcp))
            put("ipv4Udp", toStunJsonArray(normalized.ipv4Udp))
            put("ipv6Tcp", toStunJsonArray(normalized.ipv6Tcp))
            put("ipv6Udp", toStunJsonArray(normalized.ipv6Udp))
        }
        context.getSharedPreferences(STUN_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(STUN_SETTINGS_KEY, json.toString())
            .apply()
    }

    fun resetStunServerSettings(context: Context) {
        saveStunServerSettings(context, DEFAULT_STUN_SETTINGS)
    }

    suspend fun probeCustomStunServer(
        definition: StunServerDefinition,
        preferIpv6: Boolean,
        transport: StunTransportType,
        localPort: Int = findRandomAvailablePort()
    ): StunMappedEndpoint? {
        val target = toConfiguredTarget(definition) ?: return null
        return when (if (transport == StunTransportType.TLS) StunTransportType.TCP else transport) {
            StunTransportType.UDP -> queryStunServerUdp(
                server = target.queryHost,
                stunPort = target.port,
                localPort = localPort,
                preferIpv6 = preferIpv6,
                serverLabel = target.displayName
            )
            StunTransportType.TCP -> queryStunServerTcp(
                server = target.queryHost,
                stunPort = target.port,
                localPort = localPort,
                preferIpv6 = preferIpv6,
                serverLabel = target.displayName
            )
            StunTransportType.TLS -> null
        }
    }

    fun probeStunMappedEndpoint(localPort: Int, preferIpv6: Boolean): StunMappedEndpoint? {
        return probeStunMappedEndpointBatch(
            localPort = localPort,
            preferIpv6 = preferIpv6,
            transport = StunTransportType.TCP
        ).preferredEndpoint
    }

    fun probeStunMappedEndpointBatch(
        localPort: Int,
        preferIpv6: Boolean,
        transport: StunTransportType,
        verifyWithIpip: Boolean = true
    ): StunProbeBatchResult {
        val normalizedTransport = if (transport == StunTransportType.TLS) {
            StunTransportType.TCP
        } else {
            transport
        }
        val targets = configuredStunTargets(normalizedTransport, preferIpv6)

        val serverStatuses = mutableListOf<StunServerProbeStatus>()
        val endpoints = mutableListOf<StunMappedEndpoint>()
        targets.forEach { target ->
            val endpoint = when (normalizedTransport) {
                StunTransportType.UDP -> queryStunServerUdp(
                    server = target.queryHost,
                    stunPort = target.port,
                    localPort = localPort,
                    preferIpv6 = preferIpv6,
                    serverLabel = target.displayName
                )
                StunTransportType.TCP -> queryStunServerTcp(
                    server = target.queryHost,
                    stunPort = target.port,
                    localPort = localPort,
                    preferIpv6 = preferIpv6,
                    serverLabel = target.displayName
                )
                StunTransportType.TLS -> null
            }
            serverStatuses += StunServerProbeStatus(
                server = target.displayName,
                transport = normalizedTransport,
                attemptedPorts = listOf(target.port),
                mappedEndpoint = endpoint,
                note = if (endpoint != null) {
                    "探测成功（端口 ${target.port}）"
                } else {
                    "探测失败（端口 ${target.port}）"
                }
            )
            if (endpoint != null) {
                endpoints += endpoint
            }
        }

        val publicIp = if (verifyWithIpip) fetchPublicIpByIpip(preferIpv6) else null
        val normalizedPublicIp = normalizeIpLiteral(publicIp)
        val matched = if (normalizedPublicIp == null) {
            emptyList()
        } else {
            endpoints.filter { normalizeIpLiteral(it.address) == normalizedPublicIp }
        }
        val preferred = endpoints.firstOrNull()
        return StunProbeBatchResult(
            transport = normalizedTransport,
            preferIpv6 = preferIpv6,
            localPort = localPort,
            publicIpByIpip = normalizedPublicIp,
            endpoints = endpoints,
            matchedByIpip = matched,
            preferredEndpoint = preferred,
            serverStatuses = serverStatuses
        )
    }

    fun configuredStunServers(
        transport: StunTransportType,
        preferIpv6: Boolean
    ): List<String> {
        val normalizedTransport = if (transport == StunTransportType.TLS) {
            StunTransportType.TCP
        } else {
            transport
        }
        return configuredStunTargets(normalizedTransport, preferIpv6).map { it.displayName }
    }

    suspend fun probeStunMappedEndpointBatchWithProgress(
        localPort: Int,
        preferIpv6: Boolean,
        transport: StunTransportType,
        verifyWithIpip: Boolean = true,
        shouldStop: () -> Boolean = { false },
        onServerStatus: suspend (
            checkedServers: Int,
            totalServers: Int,
            status: StunServerProbeStatus,
            successCount: Int
        ) -> Unit = { _, _, _, _ -> }
    ): StunProbeBatchResult {
        val normalizedTransport = if (transport == StunTransportType.TLS) {
            StunTransportType.TCP
        } else {
            transport
        }
        val targets = configuredStunTargets(normalizedTransport, preferIpv6)
        val totalServers = targets.size

        val serverStatuses = mutableListOf<StunServerProbeStatus>()
        val endpoints = mutableListOf<StunMappedEndpoint>()

        for ((index, target) in targets.withIndex()) {
            val endpoint = when (normalizedTransport) {
                StunTransportType.UDP -> queryStunServerUdp(
                    server = target.queryHost,
                    stunPort = target.port,
                    localPort = localPort,
                    preferIpv6 = preferIpv6,
                    serverLabel = target.displayName
                )
                StunTransportType.TCP -> queryStunServerTcp(
                    server = target.queryHost,
                    stunPort = target.port,
                    localPort = localPort,
                    preferIpv6 = preferIpv6,
                    serverLabel = target.displayName
                )
                StunTransportType.TLS -> null
            }
            if (endpoint != null) {
                endpoints += endpoint
            }
            val status = StunServerProbeStatus(
                server = target.displayName,
                transport = normalizedTransport,
                attemptedPorts = listOf(target.port),
                mappedEndpoint = endpoint,
                note = if (endpoint != null) {
                    "探测成功（端口 ${target.port}）"
                } else {
                    "探测失败（端口 ${target.port}）"
                }
            )
            serverStatuses += status
            onServerStatus(index + 1, totalServers, status, endpoints.size)

            if (shouldStop()) {
                val remaining = targets.drop(index + 1)
                remaining.forEach { skipped ->
                    serverStatuses += StunServerProbeStatus(
                        server = skipped.displayName,
                        transport = normalizedTransport,
                        attemptedPorts = listOf(skipped.port),
                        mappedEndpoint = null,
                        note = "未探测（已提前结束）"
                    )
                }
                break
            }
        }

        val publicIp = if (verifyWithIpip) fetchPublicIpByIpip(preferIpv6) else null
        val normalizedPublicIp = normalizeIpLiteral(publicIp)
        val matched = if (normalizedPublicIp == null) {
            emptyList()
        } else {
            endpoints.filter { normalizeIpLiteral(it.address) == normalizedPublicIp }
        }
        val preferred = endpoints.firstOrNull()
        return StunProbeBatchResult(
            transport = normalizedTransport,
            preferIpv6 = preferIpv6,
            localPort = localPort,
            publicIpByIpip = normalizedPublicIp,
            endpoints = endpoints,
            matchedByIpip = matched,
            preferredEndpoint = preferred,
            serverStatuses = serverStatuses
        )
    }

    fun probeStunMappedEndpointTcpAt(
        localPort: Int,
        preferIpv6: Boolean,
        stunPort: Int
    ): StunMappedEndpoint? {
        configuredStunTargets(StunTransportType.TCP, preferIpv6).forEach { target ->
            val endpoint = queryStunServerTcp(
                server = target.queryHost,
                stunPort = stunPort,
                localPort = localPort,
                preferIpv6 = preferIpv6,
                serverLabel = target.displayName
            )
            if (endpoint != null) return endpoint
        }
        return null
    }

    fun probeStunMappedEndpointUdp(localPort: Int, preferIpv6: Boolean): StunMappedEndpoint? {
        return probeStunMappedEndpointBatch(
            localPort = localPort,
            preferIpv6 = preferIpv6,
            transport = StunTransportType.UDP
        ).preferredEndpoint
    }

    fun probeStunMappedEndpointUdpAt(
        localPort: Int,
        preferIpv6: Boolean,
        stunPort: Int
    ): StunMappedEndpoint? {
        configuredStunTargets(StunTransportType.UDP, preferIpv6).forEach { target ->
            val endpoint = queryStunServerUdp(
                server = target.queryHost,
                stunPort = stunPort,
                localPort = localPort,
                preferIpv6 = preferIpv6,
                serverLabel = target.displayName
            )
            if (endpoint != null) return endpoint
        }
        return null
    }

    fun probeStunMappedEndpointUdpByServer(
        server: String,
        stunPort: Int,
        localPort: Int,
        preferIpv6: Boolean
    ): StunMappedEndpoint? {
        if (server.isBlank()) return null
        return queryStunServerUdp(server, stunPort, localPort, preferIpv6, serverLabel = server)
    }

    fun detectNatType3478(localPort: Int, preferIpv6: Boolean): NatTypeDetectResult {
        val udp = detectUdpNat5389(localPort, preferIpv6)
        val tcp = detectTcpNat5389(localPort, preferIpv6)
        return NatTypeDetectResult(udp = udp, tcp = tcp)
    }

    fun detectStunBehavior(
        localPort: Int,
        preferIpv6: Boolean,
        server: String,
        stunPort: Int,
        transport: StunTransportType
    ): NatProtocolDetectResult {
        return when (transport) {
            StunTransportType.UDP -> detectUdpNat5389(localPort, preferIpv6, server, stunPort)
            StunTransportType.TCP -> detectTcpNat5389(localPort, preferIpv6, server, stunPort, useTls = false)
            StunTransportType.TLS -> detectTcpNat5389(localPort, preferIpv6, server, stunPort, useTls = true)
        }
    }

    private fun queryStunServerTcp(
        server: String,
        stunPort: Int,
        localPort: Int,
        preferIpv6: Boolean,
        serverLabel: String = server
    ): StunMappedEndpoint? {
        return try {
            val targets = resolveStunAddresses(server, preferIpv6)
            if (targets.isEmpty()) return null
            for (target in targets) {
                val endpoint = runCatching {
                    Socket().use { socket ->
                        socket.reuseAddress = true
                        socket.soTimeout = 2500
                        socket.bind(InetSocketAddress(localPort))
                        socket.connect(InetSocketAddress(target, stunPort), 2500)

                        val txId = ByteArray(12).also { Random.Default.nextBytes(it) }
                        val request = buildStunBindingRequest(txId)
                        socket.getOutputStream().write(request)
                        socket.getOutputStream().flush()

                        val packet = readStunMessageFromTcp(socket.getInputStream()) ?: return@use null
                        parseStunMappedAddress(packet, txId)?.let { (ip, port) ->
                            StunMappedEndpoint(
                                address = ip.substringBefore('%'),
                                port = port,
                                stunServer = "$serverLabel:$stunPort/tcp"
                            )
                        }
                    }
                }.getOrNull()
                if (endpoint != null) return endpoint
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun detectTcpNat5389(
        localPort: Int,
        preferIpv6: Boolean,
        server: String = configuredStunTargets(StunTransportType.TCP, preferIpv6).firstOrNull()?.queryHost.orEmpty(),
        stunPort: Int = 3478,
        useTls: Boolean = false
    ): NatProtocolDetectResult {
        if (server.isBlank()) return NatProtocolDetectResult(
            natType = "未知",
            mappingBehavior = "Fail",
            filteringBehavior = "None",
            mappedEndpoint = null,
            note = "未配置 TCP STUN 服务器"
        )
        val target = resolveStunTarget(server, stunPort, preferIpv6) ?: return NatProtocolDetectResult(
            natType = "未知",
            mappingBehavior = "Fail",
            filteringBehavior = "None",
            mappedEndpoint = null,
            note = "无法解析 TCP STUN 服务器地址"
        )
        val requestFn: (InetSocketAddress, ByteArray) -> ParsedStunResponse? = { remote, req ->
            if (useTls) requestStunTls(localPort, remote, req) else requestStunTcp(localPort, remote, req)
        }
        val base = requestFn(target, buildStunBindingRequest(ByteArray(12).also { Random.Default.nextBytes(it) }))
        if (base == null || base.mapped == null) {
            return NatProtocolDetectResult(
                natType = "未知",
                mappingBehavior = "Fail",
                filteringBehavior = "None",
                mappedEndpoint = null,
                note = if (useTls) "TLS STUN 绑定测试失败" else "TCP STUN 绑定测试失败"
            )
        }

        val mappedBase = base.mapped
        val localBase = base.local
        val mappedEndpoint = StunMappedEndpoint(
            address = mappedBase.address.hostAddress?.substringBefore('%') ?: "",
            port = mappedBase.port,
            stunServer = "$server:$stunPort/${if (useTls) "tls" else "tcp"}"
        )
        val other = base.other
        if (other == null || (other.address == target.address && other.port == target.port)) {
            return NatProtocolDetectResult(
                natType = "未知",
                mappingBehavior = "UnsupportedServer",
                filteringBehavior = "None",
                mappedEndpoint = mappedEndpoint,
                note = "服务器未返回有效 OTHER-ADDRESS/CHANGED-ADDRESS"
            )
        }

        val map2 = requestFn(InetSocketAddress(other.address, target.port), buildStunBindingRequest(ByteArray(12).also { Random.Default.nextBytes(it) }))
        if (map2?.mapped == null) {
            return NatProtocolDetectResult(
                natType = "未知",
                mappingBehavior = "Fail",
                filteringBehavior = "None",
                mappedEndpoint = mappedEndpoint,
                note = "TCP 映射测试 II 失败"
            )
        }

        val mapping = if (sameEndpoint(mappedBase, localBase)) {
            "Direct"
        } else if (sameEndpoint(map2.mapped, mappedBase)) {
            "EndpointIndependent"
        } else {
            val map3 = requestFn(other, buildStunBindingRequest(ByteArray(12).also { Random.Default.nextBytes(it) }))
            if (map3?.mapped == null) {
                "Fail"
            } else if (sameEndpoint(map3.mapped, map2.mapped)) {
                "AddressDependent"
            } else {
                "AddressAndPortDependent"
            }
        }
        val publicIpMatch = isPublicIpByLocalMatch(mappedBase, localBase, preferIpv6)
        val finalMapping = if (mapping != "Direct" && publicIpMatch) "PublicIpMatch" else mapping

        val natType = when (finalMapping) {
            "PublicIpMatch" -> "公网（IP 匹配，非 NAT1）"
            "Direct" -> "NAT1 (Full Cone NAT)"
            "EndpointIndependent" -> "NAT2（Address-Restricted Cone NAT，近似）"
            "AddressDependent" -> "NAT3（Port-Restricted Cone NAT，近似）"
            "AddressAndPortDependent" -> "NAT4 (Symmetric NAT)"
            else -> "未知"
        }
        return NatProtocolDetectResult(
            natType = natType,
            mappingBehavior = finalMapping,
            filteringBehavior = "None",
            mappedEndpoint = mappedEndpoint,
            note = if (finalMapping == "PublicIpMatch") "本机网卡 IP 与 STUN 映射 IP 一致，判定为公网（与 NAT1 区分）。" else ""
        )
    }

    private fun queryStunServerUdp(
        server: String,
        stunPort: Int,
        localPort: Int,
        preferIpv6: Boolean,
        serverLabel: String = server
    ): StunMappedEndpoint? {
        return try {
            val targets = resolveStunAddresses(server, preferIpv6)
            if (targets.isEmpty()) return null

            for (target in targets) {
                val endpoint = runCatching {
                    DatagramSocket(null).use { socket ->
                        socket.reuseAddress = true
                        socket.soTimeout = 2500
                        socket.bind(InetSocketAddress(localPort))

                        val txId = ByteArray(12).also { Random.Default.nextBytes(it) }
                        val request = buildStunBindingRequest(txId)
                        val requestPacket = DatagramPacket(
                            request,
                            request.size,
                            InetSocketAddress(target, stunPort)
                        )
                        socket.send(requestPacket)

                        val responseBuffer = ByteArray(2048)
                        val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                        socket.receive(responsePacket)
                        val packet = responsePacket.data.copyOfRange(
                            responsePacket.offset,
                            responsePacket.offset + responsePacket.length
                        )
                        parseStunMappedAddress(packet, txId)?.let { (ip, port) ->
                            StunMappedEndpoint(
                                address = ip.substringBefore('%'),
                                port = port,
                                stunServer = "$serverLabel:$stunPort/udp"
                            )
                        }
                    }
                }.getOrNull()
                if (endpoint != null) return endpoint
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun detectUdpNat5389(
        localPort: Int,
        preferIpv6: Boolean,
        server: String = configuredStunTargets(StunTransportType.UDP, preferIpv6).firstOrNull()?.queryHost.orEmpty(),
        stunPort: Int = 3478
    ): NatProtocolDetectResult {
        if (server.isBlank()) return NatProtocolDetectResult(
            natType = "未知",
            mappingBehavior = "Fail",
            filteringBehavior = "Fail",
            mappedEndpoint = null,
            note = "未配置 UDP STUN 服务器"
        )
        val target = resolveStunTarget(server, stunPort, preferIpv6) ?: return NatProtocolDetectResult(
            natType = "未知",
            mappingBehavior = "Fail",
            filteringBehavior = "Fail",
            mappedEndpoint = null,
            note = "无法解析 UDP STUN 服务器地址"
        )

        return runCatching {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.soTimeout = 2500
                socket.bind(InetSocketAddress(localPort))

                val base = requestStunUdp(socket, target, null)
                if (base == null || base.mapped == null) {
                    return@use NatProtocolDetectResult(
                        natType = "未知",
                        mappingBehavior = "Fail",
                        filteringBehavior = "Fail",
                        mappedEndpoint = null,
                        note = "UDP STUN 绑定测试失败"
                    )
                }

                val mappedBase = base.mapped
                val localBase = base.local
                val mappedEndpoint = StunMappedEndpoint(
                    address = mappedBase.address.hostAddress?.substringBefore('%') ?: "",
                    port = mappedBase.port,
                    stunServer = "$server:$stunPort/udp"
                )
                val other = base.other
                if (other == null || (other.address == target.address && other.port == target.port)) {
                    return@use NatProtocolDetectResult(
                        natType = "未知",
                        mappingBehavior = "UnsupportedServer",
                        filteringBehavior = "UnsupportedServer",
                        mappedEndpoint = mappedEndpoint,
                        note = "服务器未返回有效 OTHER-ADDRESS/CHANGED-ADDRESS"
                    )
                }

                val map2 = requestStunUdp(socket, InetSocketAddress(other.address, target.port), null)
                val mapping = if (sameEndpoint(mappedBase, localBase)) {
                    "Direct"
                } else if (map2?.mapped == null) {
                    "Fail"
                } else if (sameEndpoint(map2.mapped, mappedBase)) {
                    "EndpointIndependent"
                } else {
                    val map3 = requestStunUdp(socket, other, null)
                    if (map3?.mapped == null) "Fail"
                    else if (sameEndpoint(map3.mapped, map2.mapped)) "AddressDependent"
                    else "AddressAndPortDependent"
                }

                val filter2 = requestStunUdp(socket, target, buildChangeRequest(changeIp = true, changePort = true))
                val filtering = if (filter2 != null && filter2.remote.address == other.address && filter2.remote.port == other.port) {
                    "EndpointIndependent"
                } else {
                    val filter3 = requestStunUdp(socket, target, buildChangeRequest(changeIp = false, changePort = true))
                    if (filter3 == null) "AddressAndPortDependent"
                    else if (filter3.remote.address == target.address && filter3.remote.port != target.port) "AddressDependent"
                    else "UnsupportedServer"
                }

                val publicIpMatch = isPublicIpByLocalMatch(mappedBase, localBase, preferIpv6)
                val finalMapping = if (mapping != "Direct" && publicIpMatch) "PublicIpMatch" else mapping
                val natType = when {
                    finalMapping == "PublicIpMatch" -> "公网（IP 匹配，非 NAT1）"
                    finalMapping == "AddressAndPortDependent" -> "NAT4 (Symmetric NAT)"
                    finalMapping == "EndpointIndependent" && filtering == "EndpointIndependent" -> "NAT1（Full Cone NAT，推断）"
                    finalMapping == "EndpointIndependent" && filtering == "AddressDependent" -> "NAT2 (Address-Restricted Cone NAT)"
                    finalMapping == "EndpointIndependent" && filtering == "AddressAndPortDependent" -> "NAT3 (Port-Restricted Cone NAT)"
                    finalMapping == "AddressDependent" -> "NAT3（Port-Restricted Cone NAT，近似）"
                    else -> "未知"
                }
                NatProtocolDetectResult(
                    natType = natType,
                    mappingBehavior = finalMapping,
                    filteringBehavior = filtering,
                    mappedEndpoint = mappedEndpoint,
                    note = if (finalMapping == "PublicIpMatch") "本机网卡 IP 与 STUN 映射 IP 一致，判定为公网（与 NAT1 区分）。" else ""
                )
            }
        }.getOrElse {
            NatProtocolDetectResult(
                natType = "未知",
                mappingBehavior = "Fail",
                filteringBehavior = "Fail",
                mappedEndpoint = null,
                note = it.message ?: "unknown"
            )
        }
    }

    private fun stunServers(transport: StunTransportType, preferIpv6: Boolean): List<String> {
        return configuredStunTargets(transport, preferIpv6).map { it.queryHost }
    }

    private fun configuredStunTargets(
        transport: StunTransportType,
        preferIpv6: Boolean
    ): List<ConfiguredStunTarget> {
        val normalizedTransport = if (transport == StunTransportType.TLS) {
            StunTransportType.TCP
        } else {
            transport
        }
        val source = when (normalizedTransport) {
            StunTransportType.UDP -> if (preferIpv6) currentStunSettings.ipv6Udp else currentStunSettings.ipv4Udp
            StunTransportType.TCP, StunTransportType.TLS -> if (preferIpv6) currentStunSettings.ipv6Tcp else currentStunSettings.ipv4Tcp
        }
        val parsed = source
            .asSequence()
            .mapNotNull { toConfiguredTarget(it) }
            .distinctBy { "${it.queryHost}:${it.port}" }
            .take(STUN_SERVER_LIST_LIMIT)
            .toList()
        return if (parsed.isNotEmpty()) parsed else {
            val defaults = when (normalizedTransport) {
                StunTransportType.UDP -> if (preferIpv6) DEFAULT_STUN_SETTINGS.ipv6Udp else DEFAULT_STUN_SETTINGS.ipv4Udp
                StunTransportType.TCP, StunTransportType.TLS -> if (preferIpv6) DEFAULT_STUN_SETTINGS.ipv6Tcp else DEFAULT_STUN_SETTINGS.ipv4Tcp
            }
            defaults
                .asSequence()
                .mapNotNull { toConfiguredTarget(it) }
                .distinctBy { "${it.queryHost}:${it.port}" }
                .take(STUN_SERVER_LIST_LIMIT)
                .toList()
        }
    }

    private fun toConfiguredTarget(definition: StunServerDefinition): ConfiguredStunTarget? {
        val server = definition.server.trim()
        val ip = definition.ip.trim()
        val queryHost = if (ip.isNotBlank()) ip else server
        if (queryHost.isBlank()) return null
        val port = definition.port.takeIf { it in 1..65535 } ?: 3478
        val displayName = when {
            server.isBlank() -> queryHost
            ip.isBlank() -> server
            else -> "$server/$ip"
        }
        return ConfiguredStunTarget(
            queryHost = queryHost,
            displayName = displayName,
            port = port
        )
    }

    private fun normalizeStunSettings(settings: StunServerSettings): StunServerSettings {
        fun normalizeList(list: List<StunServerDefinition>): List<StunServerDefinition> {
            val seen = linkedSetOf<String>()
            return list.asSequence()
                .map {
                    StunServerDefinition(
                        server = it.server.trim(),
                        ip = it.ip.trim(),
                        port = it.port.takeIf { port -> port in 1..65535 } ?: 3478
                    )
                }
                .filter { it.server.isNotBlank() || it.ip.isNotBlank() }
                .filter {
                    val key = "${it.server.lowercase(Locale.ROOT)}|${it.ip.lowercase(Locale.ROOT)}|${it.port}"
                    if (seen.contains(key)) {
                        false
                    } else {
                        seen += key
                        true
                    }
                }
                .take(STUN_SERVER_LIST_LIMIT)
                .toList()
        }
        return StunServerSettings(
            ipv4Tcp = normalizeList(settings.ipv4Tcp),
            ipv4Udp = normalizeList(settings.ipv4Udp),
            ipv6Tcp = normalizeList(settings.ipv6Tcp),
            ipv6Udp = normalizeList(settings.ipv6Udp)
        )
    }

    private fun loadStunSettingsFromPrefs(context: Context): StunServerSettings {
        val prefs = context.getSharedPreferences(STUN_SETTINGS_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(STUN_SETTINGS_KEY, null) ?: return DEFAULT_STUN_SETTINGS
        val parsed = runCatching {
            val root = JSONObject(raw)
            StunServerSettings(
                ipv4Tcp = parseStunServerDefinitions(root.optJSONArray("ipv4Tcp"), DEFAULT_STUN_SETTINGS.ipv4Tcp),
                ipv4Udp = parseStunServerDefinitions(root.optJSONArray("ipv4Udp"), DEFAULT_STUN_SETTINGS.ipv4Udp),
                ipv6Tcp = parseStunServerDefinitions(root.optJSONArray("ipv6Tcp"), DEFAULT_STUN_SETTINGS.ipv6Tcp),
                ipv6Udp = parseStunServerDefinitions(root.optJSONArray("ipv6Udp"), DEFAULT_STUN_SETTINGS.ipv6Udp)
            )
        }.getOrDefault(DEFAULT_STUN_SETTINGS)
        return normalizeStunSettings(parsed)
    }

    private fun parseStunServerDefinitions(
        array: JSONArray?,
        fallback: List<StunServerDefinition>
    ): List<StunServerDefinition> {
        if (array == null) return fallback
        val list = mutableListOf<StunServerDefinition>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            list += StunServerDefinition(
                server = item.optString("server").trim(),
                ip = item.optString("ip").trim(),
                port = item.optInt("port", 3478)
            )
        }
        return if (list.isEmpty()) fallback else list
    }

    private fun toStunJsonArray(list: List<StunServerDefinition>): JSONArray {
        return JSONArray().apply {
            list.forEach { item ->
                put(
                    JSONObject().apply {
                        put("server", item.server)
                        put("ip", item.ip)
                        put("port", item.port)
                    }
                )
            }
        }
    }

    private fun fetchPublicIpByIpip(preferIpv6: Boolean): String? {
        val urls = if (preferIpv6) {
            listOf(IPIP_V6_HTTPS, IPIP_V6_HTTP)
        } else {
            listOf(IPIP_V4_HTTPS, IPIP_V4_HTTP)
        }
        urls.forEach { url ->
            val ip = runCatching {
                val conn = URL(url).openConnection()
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
                val body = BufferedReader(InputStreamReader(conn.getInputStream())).use { it.readText() }
                parseIpipBody(body, preferIpv6)
            }.getOrNull()
            if (!ip.isNullOrBlank()) return ip
        }
        return null
    }

    private fun parseIpipBody(body: String, preferIpv6: Boolean): String? {
        if (body.isBlank()) return null
        return if (!preferIpv6) {
            val ipv4Regex = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
            ipv4Regex.find(body)
                ?.value
                ?.takeIf { token ->
                    token.split('.').all { it.toIntOrNull() in 0..255 }
                }
        } else {
            val candidates = Regex("""[\[\]0-9A-Fa-f:%]+""")
                .findAll(body)
                .map { it.value.trim().removePrefix("[").removeSuffix("]") }
            for (token in candidates) {
                val normalized = token.substringBefore('%').trim()
                if (!normalized.contains(":")) continue
                val parsed = runCatching { InetAddress.getByName(normalized) }.getOrNull()
                if (parsed is Inet6Address) {
                    return parsed.hostAddress?.substringBefore('%')?.lowercase(Locale.US) ?: normalized.lowercase(Locale.US)
                }
            }
            null
        }
    }

    private fun normalizeIpLiteral(raw: String?): String? {
        val normalized = raw
            ?.trim()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.substringBefore('%')
            ?.ifBlank { null }
            ?: return null
        val parsed = runCatching { InetAddress.getByName(normalized) }.getOrNull()
        return if (parsed != null) {
            parsed.hostAddress?.substringBefore('%')?.lowercase(Locale.US)
        } else {
            normalized.lowercase(Locale.US)
        }
    }

    private fun resolveStunTarget(server: String, port: Int, preferIpv6: Boolean): InetSocketAddress? {
        val target = resolveStunAddresses(server, preferIpv6).firstOrNull() ?: return null
        return InetSocketAddress(target, port)
    }

    private fun resolveStunAddresses(server: String, preferIpv6: Boolean): List<InetAddress> {
        val systemResolved = runCatching { InetAddress.getAllByName(server).toList() }.getOrDefault(emptyList())
        val filtered = systemResolved
            .filter { if (preferIpv6) it is Inet6Address else it is Inet4Address }
            .sortedByDescending { (it is Inet6Address) == preferIpv6 }
        if (filtered.isNotEmpty()) return filtered
        if (!preferIpv6) return emptyList()

        val fallback = queryAaaaByDnsServer(server, DNS_FALLBACK_IPV6_RESOLVER)
        return fallback.sortedByDescending { it is Inet6Address }
    }

    private fun queryAaaaByDnsServer(host: String, dnsServer: String): List<InetAddress> {
        if (host.isBlank()) return emptyList()
        if (host.contains(":")) {
            val direct = runCatching { InetAddress.getByName(host) }.getOrNull()
            return if (direct is Inet6Address) listOf(direct) else emptyList()
        }

        val txId = Random.nextInt(0, 65536)
        val query = buildDnsQueryAaaa(host, txId) ?: return emptyList()
        return runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 2500
                socket.send(DatagramPacket(query, query.size, InetSocketAddress(dnsServer, 53)))
                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                parseDnsAaaaAnswers(buffer.copyOf(packet.length), txId)
            }
        }.getOrDefault(emptyList())
    }

    private fun buildDnsQueryAaaa(host: String, txId: Int): ByteArray? {
        val labels = host.trim().split('.').filter { it.isNotBlank() }
        if (labels.isEmpty()) return null
        val qname = ArrayList<Byte>(host.length + 8)
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty() || bytes.size > 63) return null
            qname += bytes.size.toByte()
            bytes.forEach { qname += it }
        }
        qname += 0

        val out = ByteArray(12 + qname.size + 4)
        out[0] = ((txId ushr 8) and 0xff).toByte()
        out[1] = (txId and 0xff).toByte()
        out[2] = 0x01 // recursion desired
        out[3] = 0x00
        out[4] = 0x00
        out[5] = 0x01 // QDCOUNT = 1
        // ANCOUNT/NSCOUNT/ARCOUNT keep 0
        var off = 12
        qname.forEach { out[off++] = it }
        out[off++] = 0x00
        out[off++] = 0x1c // AAAA
        out[off++] = 0x00
        out[off] = 0x01 // IN
        return out
    }

    private fun parseDnsAaaaAnswers(packet: ByteArray, txId: Int): List<InetAddress> {
        if (packet.size < 12) return emptyList()
        val id = ((packet[0].toInt() and 0xff) shl 8) or (packet[1].toInt() and 0xff)
        if (id != txId) return emptyList()
        val qdCount = ((packet[4].toInt() and 0xff) shl 8) or (packet[5].toInt() and 0xff)
        val anCount = ((packet[6].toInt() and 0xff) shl 8) or (packet[7].toInt() and 0xff)

        var offset = 12
        repeat(qdCount) {
            offset = skipDnsName(packet, offset)
            if (offset < 0 || offset + 4 > packet.size) return emptyList()
            offset += 4
        }

        val out = mutableListOf<InetAddress>()
        repeat(anCount) {
            offset = skipDnsName(packet, offset)
            if (offset < 0 || offset + 10 > packet.size) return@repeat
            val type = ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)
            val clazz = ((packet[offset + 2].toInt() and 0xff) shl 8) or (packet[offset + 3].toInt() and 0xff)
            val rdLen = ((packet[offset + 8].toInt() and 0xff) shl 8) or (packet[offset + 9].toInt() and 0xff)
            val rdata = offset + 10
            if (rdata + rdLen > packet.size) return@repeat
            if (type == 0x001c && clazz == 0x0001 && rdLen == 16) {
                val ip = runCatching { InetAddress.getByAddress(packet.copyOfRange(rdata, rdata + 16)) }.getOrNull()
                if (ip is Inet6Address) out += ip
            }
            offset = rdata + rdLen
        }
        return out.distinctBy { it.hostAddress }
    }

    private fun skipDnsName(packet: ByteArray, start: Int): Int {
        var p = start
        var guard = 0
        while (p < packet.size && guard < packet.size) {
            val len = packet[p].toInt() and 0xff
            if (len == 0) return p + 1
            if ((len and 0xC0) == 0xC0) {
                if (p + 1 >= packet.size) return -1
                return p + 2
            }
            p += 1 + len
            guard++
        }
        return -1
    }

    private fun readStunMessageFromTcp(input: InputStream): ByteArray? {
        val header = ByteArray(20)
        if (!readFully(input, header, 0, header.size)) return null
        val msgLen = ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
        if (msgLen < 0 || msgLen > 64 * 1024) return null
        val payload = ByteArray(msgLen)
        if (!readFully(input, payload, 0, msgLen)) return null
        return header + payload
    }

    private data class ParsedStunResponse(
        val remote: InetSocketAddress,
        val local: InetSocketAddress?,
        val mapped: InetSocketAddress?,
        val other: InetSocketAddress?
    )

    private fun requestStunTcp(localPort: Int, remote: InetSocketAddress, request: ByteArray): ParsedStunResponse? {
        return runCatching<ParsedStunResponse?> {
            Socket().use { socket ->
                socket.reuseAddress = true
                socket.soTimeout = 2500
                socket.bind(InetSocketAddress(localPort))
                socket.connect(remote, 2500)
                socket.getOutputStream().write(request)
                socket.getOutputStream().flush()
                val packet = readStunMessageFromTcp(socket.getInputStream()) ?: return@use null
                val local = (socket.localAddress?.let { InetSocketAddress(it, socket.localPort) })
                parseStunResponse(packet)
                    ?.copy(remote = remote, local = local)
            }
        }.getOrNull()
    }

    private fun requestStunTls(localPort: Int, remote: InetSocketAddress, request: ByteArray): ParsedStunResponse? {
        return runCatching<ParsedStunResponse?> {
            val raw = Socket().apply {
                reuseAddress = true
                soTimeout = 2500
                bind(InetSocketAddress(localPort))
                connect(remote, 2500)
            }
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = (sslFactory.createSocket(
                raw,
                remote.hostString,
                remote.port,
                true
            ) as javax.net.ssl.SSLSocket).apply {
                soTimeout = 2500
                startHandshake()
            }
            ssl.use { socket ->
                socket.getOutputStream().write(request)
                socket.getOutputStream().flush()
                val packet = readStunMessageFromTcp(socket.getInputStream()) ?: return@use null
                val local = socket.localAddress?.let { InetSocketAddress(it, socket.localPort) }
                parseStunResponse(packet)?.copy(remote = remote, local = local)
            }
        }.getOrNull()
    }

    private fun requestStunUdp(
        socket: DatagramSocket,
        remote: InetSocketAddress,
        changeRequest: ByteArray?
    ): ParsedStunResponse? {
        val txId = ByteArray(12).also { Random.Default.nextBytes(it) }
        val request = buildStunBindingRequest(txId, changeRequest)
        return runCatching<ParsedStunResponse?> {
            socket.send(DatagramPacket(request, request.size, remote))
            val responseBuffer = ByteArray(2048)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            while (true) {
                socket.receive(responsePacket)
                val packet = responsePacket.data.copyOfRange(
                    responsePacket.offset,
                    responsePacket.offset + responsePacket.length
                )
                val parsed = parseStunResponse(packet, txId)
                if (parsed != null) {
                    val local = socket.localAddress?.let { InetSocketAddress(it, socket.localPort) }
                    return@runCatching parsed.copy(
                        remote = InetSocketAddress(responsePacket.address, responsePacket.port),
                        local = local
                    )
                }
            }
            null
        }.getOrNull()
    }

    private fun readFully(input: InputStream, buffer: ByteArray, off: Int, len: Int): Boolean {
        var offset = off
        var remaining = len
        while (remaining > 0) {
            val read = input.read(buffer, offset, remaining)
            if (read <= 0) return false
            offset += read
            remaining -= read
        }
        return true
    }

    private fun buildStunBindingRequest(txId: ByteArray, attr: ByteArray? = null): ByteArray {
        val total = 20 + (attr?.size ?: 0)
        val req = ByteArray(total)
        req[0] = 0x00
        req[1] = 0x01
        val len = attr?.size ?: 0
        req[2] = ((len shr 8) and 0xff).toByte()
        req[3] = (len and 0xff).toByte()
        req[4] = 0x21
        req[5] = 0x12
        req[6] = 0xA4.toByte()
        req[7] = 0x42
        System.arraycopy(txId, 0, req, 8, 12)
        if (attr != null) {
            System.arraycopy(attr, 0, req, 20, attr.size)
        }
        return req
    }

    private fun buildChangeRequest(changeIp: Boolean, changePort: Boolean): ByteArray {
        val value = (if (changeIp) 0x04 else 0x00) or (if (changePort) 0x02 else 0x00)
        return byteArrayOf(
            ((ATTR_CHANGE_REQUEST shr 8) and 0xff).toByte(),
            (ATTR_CHANGE_REQUEST and 0xff).toByte(),
            0x00, 0x04,
            0x00, 0x00, 0x00, (value and 0xff).toByte()
        )
    }

    private fun parseStunMappedAddress(packet: ByteArray, txId: ByteArray): Pair<String, Int>? {
        return parseStunResponse(packet, txId)?.mapped?.let {
            (it.address.hostAddress ?: "").substringBefore('%') to it.port
        }
    }

    private fun parseStunResponse(packet: ByteArray, txId: ByteArray? = null): ParsedStunResponse? {
        if (packet.size < 20) return null
        val msgType = ((packet[0].toInt() and 0xff) shl 8) or (packet[1].toInt() and 0xff)
        if (msgType != 0x0101) return null
        val msgLen = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
        if (packet.size < 20 + msgLen) return null
        if (txId != null && txId.size == 12) {
            for (i in 0 until 12) {
                if (packet[8 + i] != txId[i]) return null
            }
        }

        val cookie = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
        var offset = 20
        var mapped: InetSocketAddress? = null
        var other: InetSocketAddress? = null
        while (offset + 4 <= packet.size) {
            val attrType = ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)
            val attrLen = ((packet[offset + 2].toInt() and 0xff) shl 8) or (packet[offset + 3].toInt() and 0xff)
            val valueStart = offset + 4
            val valueEnd = valueStart + attrLen
            if (valueEnd > packet.size) break

            if (attrType == ATTR_XOR_MAPPED_ADDRESS || attrType == ATTR_MAPPED_ADDRESS) {
                parseAddressAttribute(packet, valueStart, attrLen, attrType == ATTR_XOR_MAPPED_ADDRESS, txId, cookie)?.let {
                    mapped = it
                }
            } else if (attrType == ATTR_OTHER_ADDRESS || attrType == ATTR_CHANGED_ADDRESS) {
                parseAddressAttribute(packet, valueStart, attrLen, false, txId, cookie)?.let {
                    other = it
                }
            }
            val padded = (attrLen + 3) and 0xFFFC
            offset = valueStart + padded
        }
        return ParsedStunResponse(
            remote = InetSocketAddress(0),
            local = null,
            mapped = mapped,
            other = other
        )
    }

    private fun parseAddressAttribute(
        packet: ByteArray,
        valueStart: Int,
        attrLen: Int,
        xor: Boolean,
        txId: ByteArray?,
        cookie: ByteArray
    ): InetSocketAddress? {
        if (attrLen < 8 || valueStart + attrLen > packet.size) return null
        val family = packet[valueStart + 1].toInt() and 0xff
        val xPort = ((packet[valueStart + 2].toInt() and 0xff) shl 8) or (packet[valueStart + 3].toInt() and 0xff)
        val port = if (xor) xPort xor (STUN_MAGIC_COOKIE ushr 16) else xPort
        val addrBytes = when (family) {
            0x01 -> 4
            0x02 -> 16
            else -> 0
        }
        if (addrBytes == 0 || valueStart + 4 + addrBytes > packet.size) return null
        val raw = packet.copyOfRange(valueStart + 4, valueStart + 4 + addrBytes)
        val decoded = if (xor) {
            val tx = txId ?: return null
            ByteArray(addrBytes).apply {
                for (i in 0 until addrBytes) {
                    val mask = if (i < 4) cookie[i] else tx[i - 4]
                    this[i] = (raw[i].toInt() xor mask.toInt()).toByte()
                }
            }
        } else {
            raw
        }
        val ip = InetAddress.getByAddress(decoded)
        return InetSocketAddress(ip, port)
    }

    private fun normalizeIpForCompare(address: InetAddress?): String? {
        val host = address?.hostAddress?.substringBefore('%')?.lowercase(Locale.US) ?: return null
        if (host.isBlank() || host == "0.0.0.0" || host == "::" || host == "::1") return null
        return host
    }

    private fun collectLocalInterfaceIps(preferIpv6: Boolean): Set<String> {
        return runCatching {
            val out = mutableSetOf<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching out
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress) continue
                    if (preferIpv6 && address !is Inet6Address) continue
                    if (!preferIpv6 && address !is Inet4Address) continue
                    normalizeIpForCompare(address)?.let { out += it }
                }
            }
            out
        }.getOrDefault(emptySet())
    }

    private fun isPublicIpByLocalMatch(
        mapped: InetSocketAddress?,
        local: InetSocketAddress?,
        preferIpv6: Boolean
    ): Boolean {
        val mappedIp = normalizeIpForCompare(mapped?.address) ?: return false
        val localIps = collectLocalInterfaceIps(preferIpv6).toMutableSet()
        normalizeIpForCompare(local?.address)?.let { localIps += it }
        return mappedIp in localIps
    }

    private fun sameEndpoint(a: InetSocketAddress?, b: InetSocketAddress?): Boolean {
        if (a == null || b == null) return false
        return a.address == b.address && a.port == b.port
    }

    private class Ipv6OnlySslSocketFactory(
        private val delegate: SSLSocketFactory,
        private val targetHost: String
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(host: String?, port: Int): Socket {
            if (!shouldForce(host)) return delegate.createSocket(host, port)
            return createSocketOverIpv6(host!!, port)
        }

        override fun createSocket(
            host: String?,
            port: Int,
            localHost: InetAddress?,
            localPort: Int
        ): Socket {
            if (!shouldForce(host)) return delegate.createSocket(host, port, localHost, localPort)
            return createSocketOverIpv6(host!!, port, localHost, localPort)
        }

        override fun createSocket(host: InetAddress?, port: Int): Socket {
            return delegate.createSocket(host, port)
        }

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int
        ): Socket {
            return delegate.createSocket(address, port, localAddress, localPort)
        }

        override fun createSocket(
            s: Socket?,
            host: String?,
            port: Int,
            autoClose: Boolean
        ): Socket {
            if (!shouldForce(host)) return delegate.createSocket(s, host, port, autoClose)
            return createSocketOverIpv6(host!!, port)
        }

        private fun shouldForce(host: String?): Boolean {
            return !host.isNullOrBlank() && host.equals(targetHost, ignoreCase = true)
        }

        private fun createSocketOverIpv6(
            host: String,
            port: Int,
            localHost: InetAddress? = null,
            localPort: Int = 0
        ): Socket {
            val ipv6Address = InetAddress.getAllByName(host).firstOrNull { it is Inet6Address }
                ?: throw UnknownHostException("No IPv6 address for $host")
            val rawSocket = Socket()
            if (localHost != null) {
                rawSocket.bind(InetSocketAddress(localHost, localPort))
            }
            rawSocket.connect(InetSocketAddress(ipv6Address, port), 5000)
            return delegate.createSocket(rawSocket, host, port, true)
        }
    }

}


