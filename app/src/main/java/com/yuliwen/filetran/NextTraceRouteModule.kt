package com.yuliwen.filetran

import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.CNAMERecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Lookup
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.PTRRecord
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.SimpleResolver
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.coroutines.resume

private const val NEXTTRACE_TAG = "NextTraceRoute"
private const val IPV4_IDENTIFIER = "IPv4"
private const val IPV6_IDENTIFIER = "IPv6"
private const val HOSTNAME_IDENTIFIER = "Hostname"
private const val ERROR_IDENTIFIER = "ERR"

internal data class NextTraceRouteConfig(
    val dnsMode: String = "udp", // udp, tcp, doh
    val dnsServer: String = "1.1.1.1",
    val dohServer: String = "https://1.1.1.1/dns-query",
    val apiHostNamePow: String = "origin-fallback.nxtrace.org",
    val apiDnsNamePow: String = "api.nxtrace.org",
    val apiHostName: String = "origin-fallback.nxtrace.org",
    val apiDnsName: String = "api.nxtrace.org",
    val pingTimeoutSec: Int = 1,
    val traceProbeCount: Int = 1,
    val hopRttProbeCount: Int = 3
)

private data class NextTraceHop(
    val ttl: Int,
    val ip: String = "*",
    var rtt: String = "*",
    var ptr: String = "*",
    var asn: String = "*",
    var whois: String = "*",
    var location: String = "*",
    var domain: String = "*"
)

private data class NextTraceGeo(
    val asn: String,
    val whois: String,
    val location: String,
    val domain: String
)

private data class NextTraceTokenResult(
    val token: String,
    val apiPowIp: String
)

private data class NextTraceApiState(
    val token: String? = null,
    val wsApiIp: String? = null
)

/**
 * NextTrace style traceroute module (ported workflow):
 * 1) TTL ping path discovery
 * 2) Per-hop RTT + PTR enrichment
 * 3) NextTrace API POW token + WebSocket geo enrichment
 */
internal object NextTraceRouteModule {
    suspend fun run(
        rawHost: String,
        maxHops: Int,
        config: NextTraceRouteConfig = NextTraceRouteConfig()
    ): String = withContext(Dispatchers.IO) {
        val host = rawHost.trim()
        if (host.isBlank()) return@withContext "目标主机为空。"

        val target = pickTarget(host, config) ?: return@withContext "TraceRoute 失败：无法解析主机 $host"
        val targetType = identifyInput(target)
        if (targetType != IPV4_IDENTIFIER && targetType != IPV6_IDENTIFIER) {
            return@withContext "TraceRoute 失败：目标地址类型无效：$target"
        }

        val hops = mutableListOf<NextTraceHop>()
        var reached = false
        val boundedHops = maxHops.coerceIn(5, 64)
        for (ttl in 1..boundedHops) {
            val pingOutput = nativePingHandler(
                ip = target,
                count = config.traceProbeCount.toString(),
                ttl = ttl.toString(),
                timeout = config.pingTimeoutSec.toString()
            )
            val hopIp = when (targetType) {
                IPV4_IDENTIFIER -> nativeGetHopIPv4(pingOutput)
                IPV6_IDENTIFIER -> nativeGetHopIPv6(pingOutput)
                else -> "*"
            }.ifBlank { "*" }
            hops += NextTraceHop(ttl = ttl, ip = hopIp)
            if (hopIp == target) {
                reached = true
                break
            }
        }

        // Enrich each discovered hop with RTT + reverse DNS.
        hops.filter { it.ip != "*" }.forEach { hop ->
            val hopPing = nativePingHandler(
                ip = hop.ip,
                count = config.hopRttProbeCount.toString(),
                ttl = "",
                timeout = config.pingTimeoutSec.toString()
            )
            hop.rtt = extractRttValues(hopPing).ifBlank { "*" }
            hop.ptr = reverseDns(hop.ip, config).ifBlank { "*" }
        }

        // NextTrace API enrichment (POW + WebSocket geo lookup).
        val apiState = runCatching { enrichWithNextTraceApi(hops, config) }
            .onFailure { Log.w(NEXTTRACE_TAG, "NextTrace API enrichment failed", it) }
            .getOrElse { NextTraceApiState() }

        buildString {
            appendLine("TraceRoute (NextTrace移植): $host ($target)")
            appendLine("dnsMode=${config.dnsMode.lowercase(Locale.ROOT)} dns=${config.dnsServer} doh=${config.dohServer}")
            appendLine("nexttraceApiToken=${if (apiState.token.isNullOrBlank()) "unavailable" else "ok"} wsApiIp=${apiState.wsApiIp ?: "*"}")
            appendLine("Hop  IP  RTT  PTR  ASN  Whois  Location")
            hops.forEach { hop ->
                val hopIp = hop.ip
                val asnDisplay = if (hop.asn == "*" || hop.asn.isBlank()) "*" else "AS${hop.asn}"
                appendLine(
                    "%2d  %s  %s  %s  %s  %s  %s".format(
                        hop.ttl,
                        hopIp,
                        hop.rtt,
                        hop.ptr,
                        asnDisplay,
                        hop.whois,
                        if (hop.domain == "*" || hop.domain.isBlank()) hop.location else "${hop.location} ${hop.domain}".trim()
                    )
                )
            }
            if (reached) {
                append("destination reached.")
            } else {
                append("未在 $boundedHops 跳内探测到目标可达。")
            }
        }
    }

    private suspend fun enrichWithNextTraceApi(
        hops: List<NextTraceHop>,
        config: NextTraceRouteConfig
    ): NextTraceApiState {
        val targetIps = hops.map { it.ip }.filter { it != "*" }.distinct()
        if (targetIps.isEmpty()) return NextTraceApiState()

        val tokenResult = requestApiToken(config) ?: return NextTraceApiState()
        val wsApiIp = selectWorkingWsApiIp(config, tokenResult.token) ?: tokenResult.apiPowIp
        if (wsApiIp.isBlank()) return NextTraceApiState(token = tokenResult.token)

        targetIps.forEach { ip ->
            val geo = queryGeoViaWs(
                ip = ip,
                token = tokenResult.token,
                apiHostName = config.apiHostName,
                apiIp = wsApiIp
            ) ?: return@forEach
            hops.filter { it.ip == ip }.forEach { hop ->
                hop.asn = geo.asn.ifBlank { "*" }
                hop.whois = geo.whois.ifBlank { "*" }
                hop.location = geo.location.ifBlank { "*" }
                hop.domain = geo.domain.ifBlank { "*" }
            }
        }
        return NextTraceApiState(token = tokenResult.token, wsApiIp = wsApiIp)
    }

    private suspend fun requestApiToken(config: NextTraceRouteConfig): NextTraceTokenResult? {
        val powCandidates = resolveCandidates(config.apiDnsNamePow, config)
        if (powCandidates.isEmpty()) return null
        for (candidateIp in powCandidates) {
            val customDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return listOf(InetAddress.getByName(candidateIp))
                }
            }
            val client = OkHttpClient.Builder()
                .dns(customDns)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()
            try {
                val challengeReq = Request.Builder()
                    .url("https://${config.apiHostNamePow}/v3/challenge/request_challenge")
                    .addHeader("Host", config.apiHostNamePow)
                    .addHeader("User-Agent", "NextTrace v5.1.4/android FileTran")
                    .get()
                    .build()
                val challengeBody = client.newCall(challengeReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.string()
                } ?: continue

                val challengeJson = runCatching { JsonParser.parseString(challengeBody).asJsonObject }.getOrNull()
                    ?: continue
                val challengeObj = challengeJson.getAsJsonObject("challenge") ?: continue
                val challenge = challengeObj.getAsJsonPrimitive("challenge")?.asString?.toBigIntegerOrNull()
                    ?: continue
                val requestId = challengeObj.getAsJsonPrimitive("request_id")?.asString ?: continue
                val requestTime = challengeJson.getAsJsonPrimitive("request_time")?.asString ?: continue
                val factors = powHandler(challenge)
                if (factors.size != 2) continue

                val payload = JsonObject().apply {
                    add("challenge", JsonObject().apply {
                        addProperty("request_id", requestId)
                        addProperty("challenge", challenge.toString())
                    })
                    add("answer", JsonArray().apply {
                        add(factors[0].toString())
                        add(factors[1].toString())
                    })
                    addProperty("request_time", requestTime)
                }
                val submitReq = Request.Builder()
                    .url("https://${config.apiHostNamePow}/v3/challenge/submit_answer")
                    .addHeader("Host", config.apiHostNamePow)
                    .addHeader("User-Agent", "NextTrace v5.1.4/android FileTran")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val token = client.newCall(submitReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty()
                    val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                    json?.getAsJsonPrimitive("token")?.asString
                } ?: continue

                if (token.isNotBlank()) {
                    return NextTraceTokenResult(token = token, apiPowIp = candidateIp)
                }
            } catch (e: Exception) {
                Log.w(NEXTTRACE_TAG, "requestApiToken candidate failed: $candidateIp", e)
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
        return null
    }

    private suspend fun selectWorkingWsApiIp(config: NextTraceRouteConfig, token: String): String? {
        val wsCandidates = resolveCandidates(config.apiDnsName, config)
        if (wsCandidates.isEmpty()) return null
        wsCandidates.forEach { ip ->
            val ok = queryGeoViaWs(
                ip = "1.1.1.1",
                token = token,
                apiHostName = config.apiHostName,
                apiIp = ip
            ) != null
            if (ok) return ip
        }
        return null
    }

    private suspend fun queryGeoViaWs(
        ip: String,
        token: String,
        apiHostName: String,
        apiIp: String
    ): NextTraceGeo? = withTimeoutOrNull(6_000L) {
        suspendCancellableCoroutine { cont ->
            val customDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return listOf(InetAddress.getByName(apiIp))
                }
            }
            val client = OkHttpClient.Builder()
                .dns(customDns)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .pingInterval(5, TimeUnit.SECONDS)
                .build()
            val done = AtomicBoolean(false)
            var socket: WebSocket? = null

            fun finish(value: NextTraceGeo?) {
                if (done.compareAndSet(false, true)) {
                    runCatching { socket?.close(1000, "done") }
                    client.dispatcher.executorService.shutdown()
                    client.connectionPool.evictAll()
                    if (cont.isActive) cont.resume(value)
                }
            }

            val req = Request.Builder()
                .url("wss://$apiHostName/v3/ipGeoWs")
                .addHeader("Host", apiHostName)
                .addHeader("User-Agent", "NextTrace v5.1.4/android FileTran")
                .addHeader("Authorization", "Bearer $token")
                .build()

            socket = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(ip)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                        ?: return finish(null)
                    val asn = json.getAsJsonPrimitive("asnumber")?.asString.orEmpty()
                    val whois = json.getAsJsonPrimitive("whois")?.asString.orEmpty()
                    val country = json.getAsJsonPrimitive("country_en")?.asString
                        ?: json.getAsJsonPrimitive("country")?.asString.orEmpty()
                    val prov = json.getAsJsonPrimitive("prov_en")?.asString
                        ?: json.getAsJsonPrimitive("prov")?.asString.orEmpty()
                    val city = json.getAsJsonPrimitive("city_en")?.asString
                        ?: json.getAsJsonPrimitive("city")?.asString.orEmpty()
                    val domain = json.getAsJsonPrimitive("domain")?.asString.orEmpty()
                    val location = listOf(country, prov, city).filter { it.isNotBlank() }.joinToString(" ")
                    finish(
                        NextTraceGeo(
                            asn = asn,
                            whois = whois,
                            location = location,
                            domain = domain
                        )
                    )
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(NEXTTRACE_TAG, "queryGeoViaWs failed ip=$ip apiIp=$apiIp", t)
                    finish(null)
                }
            })

            cont.invokeOnCancellation {
                if (done.compareAndSet(false, true)) {
                    runCatching { socket?.cancel() }
                    client.dispatcher.executorService.shutdown()
                    client.connectionPool.evictAll()
                }
            }
        }
    }

    private fun pickTarget(host: String, config: NextTraceRouteConfig): String? {
        val type = identifyInput(host)
        if (type == IPV4_IDENTIFIER || type == IPV6_IDENTIFIER) return host
        if (type != HOSTNAME_IDENTIFIER) return null

        val all = resolveCandidates(host, config)
        if (all.isEmpty()) return null
        val preferIpv6 = host.contains(":")
        return if (preferIpv6) {
            all.firstOrNull { identifyInput(it) == IPV6_IDENTIFIER } ?: all.first()
        } else {
            all.firstOrNull { identifyInput(it) == IPV4_IDENTIFIER } ?: all.first()
        }
    }

    private fun resolveCandidates(name: String, config: NextTraceRouteConfig): List<String> {
        val type = identifyInput(name)
        if (type == IPV4_IDENTIFIER || type == IPV6_IDENTIFIER) return listOf(name)
        if (type != HOSTNAME_IDENTIFIER) return emptyList()

        val addresses = linkedSetOf<String>()
        val recordsA = dnsQuery(name, org.xbill.DNS.Type.A, config)
        val recordsAAAA = dnsQuery(name, org.xbill.DNS.Type.AAAA, config)
        recordsA.filterIsInstance<ARecord>().forEach { record ->
            record.address.hostAddress?.takeIf { it.isNotBlank() }?.let { addresses.add(it) }
        }
        recordsAAAA.filterIsInstance<AAAARecord>().forEach { record ->
            record.address.hostAddress?.takeIf { it.isNotBlank() }?.let { addresses.add(it) }
        }

        // Follow CNAME once, same as NextTraceroute's resolver behavior.
        val cnameTargets = dnsQuery(name, org.xbill.DNS.Type.CNAME, config)
            .filterIsInstance<CNAMERecord>()
            .map { it.target.toString().trimEnd('.') }
            .distinct()
        cnameTargets.forEach { cname ->
            dnsQuery(cname, org.xbill.DNS.Type.A, config).filterIsInstance<ARecord>()
                .forEach { record ->
                    record.address.hostAddress?.takeIf { it.isNotBlank() }?.let { addresses.add(it) }
                }
            dnsQuery(cname, org.xbill.DNS.Type.AAAA, config).filterIsInstance<AAAARecord>()
                .forEach { record ->
                    record.address.hostAddress?.takeIf { it.isNotBlank() }?.let { addresses.add(it) }
                }
        }
        return addresses.toList()
    }

    private fun dnsQuery(name: String, type: Int, config: NextTraceRouteConfig): List<Record> {
        return if (config.dnsMode.equals("doh", ignoreCase = true)) {
            dnsQueryByDoH(name, type, config)
        } else {
            dnsQueryByUdpOrTcp(name, type, config)
        }
    }

    private fun dnsQueryByUdpOrTcp(name: String, type: Int, config: NextTraceRouteConfig): List<Record> {
        return runCatching {
            val lookup = Lookup(name, type)
            val resolver = SimpleResolver(config.dnsServer).apply {
                tcp = config.dnsMode.equals("tcp", ignoreCase = true)
            }
            lookup.setResolver(resolver)
            lookup.run()?.toList().orEmpty()
        }.getOrElse {
            Log.w(NEXTTRACE_TAG, "dnsQueryByUdpOrTcp failed name=$name type=$type", it)
            emptyList()
        }
    }

    private fun dnsQueryByDoH(name: String, type: Int, config: NextTraceRouteConfig): List<Record> {
        return runCatching {
            val fqdn = if (name.endsWith(".")) name else "$name."
            val queryName = Name.fromString(fqdn)
            val record = Record.newRecord(queryName, type, DClass.IN)
            val message = Message.newQuery(record)
            val encoded = Base64.encodeToString(
                message.toWire(),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
            val req = Request.Builder()
                .url("${config.dohServer}?dns=$encoded")
                .addHeader("Accept", "application/dns-message")
                .get()
                .build()
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<Record>()
                val body = resp.body?.bytes() ?: return@use emptyList<Record>()
                Message(body).getSection(Section.ANSWER).toList()
            }.also {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }.getOrElse {
            Log.w(NEXTTRACE_TAG, "dnsQueryByDoH failed name=$name type=$type", it)
            emptyList()
        }
    }

    private fun reverseDns(ip: String, config: NextTraceRouteConfig): String {
        val ipType = identifyInput(ip)
        if (ipType != IPV4_IDENTIFIER && ipType != IPV6_IDENTIFIER) return "*"
        val queryName = when (ipType) {
            IPV4_IDENTIFIER -> {
                val bytes = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return "*"
                buildString {
                    bytes.reversed().forEach { b ->
                        append((b.toInt() and 0xFF).toString())
                        append('.')
                    }
                    append("in-addr.arpa.")
                }
            }
            IPV6_IDENTIFIER -> {
                val bytes = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return "*"
                val hex = "0123456789abcdef"
                buildString {
                    bytes.reversed().forEach { b ->
                        append(hex[b.toInt() and 0x0F])
                        append('.')
                        append(hex[(b.toInt() ushr 4) and 0x0F])
                        append('.')
                    }
                    append("ip6.arpa.")
                }
            }
            else -> return "*"
        }

        val records = dnsQuery(queryName, org.xbill.DNS.Type.PTR, config)
        val ptr = records.filterIsInstance<PTRRecord>().firstOrNull()?.target?.toString().orEmpty()
        return ptr.trimEnd('.').ifBlank { "*" }
    }

    private fun extractRttValues(inputString: String): String {
        val regex = "(?i)^.*rtt.*=\\s*(.*)$".toRegex(setOf(RegexOption.MULTILINE))
        val matchResult = regex.find(inputString)
        return matchResult?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { "*" }
    }

    private fun nativeGetHopIPv4(inputText: String): String {
        val linePattern = "(?i).*exceeded.*".toRegex()
        val unreachablePattern = "(?i).*unreachable.*".toRegex()
        val fromPattern = "(?i)from\\s+([\\d.]+)".toRegex()
        val lines = inputText.lines().filter { linePattern.containsMatchIn(it) }
        for (line in lines) {
            val match = fromPattern.find(line)
            if (match != null) return match.groupValues[1].trim()
        }
        val notUnreachableLines = inputText.lines().filter { !unreachablePattern.containsMatchIn(it) }
        for (line in notUnreachableLines) {
            val match = fromPattern.find(line)
            if (match != null) return match.groupValues[1].trim()
        }
        return "*"
    }

    private fun nativeGetHopIPv6(inputText: String): String {
        val linePattern = "(?i).*exceeded.*".toRegex()
        val unreachablePattern = "(?i).*unreachable.*".toRegex()
        val fromPattern = ("(?i)from\\s+([^\\s]*)\\s").toRegex()
        val lines = inputText.lines().filter { linePattern.containsMatchIn(it) }
        for (line in lines) {
            val match = fromPattern.find(line)
            if (match != null) return match.groupValues[1].trim().trimEnd(':')
        }
        val notUnreachableLines = inputText.lines().filter { !unreachablePattern.containsMatchIn(it) }
        for (line in notUnreachableLines) {
            val match = fromPattern.find(line)
            if (match != null) return match.groupValues[1].trim().trimEnd(':')
        }
        return "*"
    }

    private fun nativePingHandler(ip: String, count: String, ttl: String, timeout: String): String {
        return try {
            val ipType = identifyInput(ip)
            val command = when (ipType) {
                IPV4_IDENTIFIER -> if (ttl.isEmpty()) {
                    "ping -n -c $count -W $timeout $ip"
                } else {
                    "ping -n -c $count -W $timeout -t $ttl $ip"
                }
                IPV6_IDENTIFIER -> if (ttl.isEmpty()) {
                    "ping6 -n -c $count -W $timeout $ip"
                } else {
                    "ping6 -n -c $count -W $timeout -t $ttl $ip"
                }
                else -> return ERROR_IDENTIFIER
            }
            val process = Runtime.getRuntime().exec(command)
            process.waitFor()
            val stdReader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            val stdOutput = StringBuilder()
            val errOutput = StringBuilder()

            var lineStd: String?
            while (stdReader.readLine().also { lineStd = it } != null) {
                stdOutput.append(lineStd).append('\n')
            }
            var lineErr: String?
            while (errReader.readLine().also { lineErr = it } != null) {
                errOutput.append(lineErr).append('\n')
            }
            stdOutput.append(errOutput)
            stdOutput.toString()
        } catch (e: Exception) {
            Log.w(NEXTTRACE_TAG, "nativePingHandler failed", e)
            ERROR_IDENTIFIER
        }
    }

    fun identifyInput(input: String): String {
        val ipv4Pattern = Pattern.compile(
            "^(?!255\\.255\\.255\\.255$)([1-9]|[1-9]\\d|1\\d{2}|2[0-4]\\d|25[0-5])(\\.(\\d|[1-9]\\d|1\\d{2}|2[0-4]\\d|25[0-5])){3}$"
        )
        val ipv6Pattern1 = Pattern.compile("^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$")
        val ipv6Pattern2 = Pattern.compile("^(([0-9A-Fa-f]{1,4}(:[0-9A-Fa-f]{1,4})*)?)::((([0-9A-Fa-f]{1,4}:)*[0-9A-Fa-f]{1,4})?)$")
        val hostnamePattern = Pattern.compile(
            "^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z][A-Za-z0-9]*)$"
        )
        if (ipv4Pattern.matcher(input).matches()) return IPV4_IDENTIFIER
        if (ipv6Pattern1.matcher(input).matches() || ipv6Pattern2.matcher(input).matches()) return IPV6_IDENTIFIER
        if (hostnamePattern.matcher(input).matches()) return HOSTNAME_IDENTIFIER
        return ERROR_IDENTIFIER
    }

    private fun rho(challenge: BigInteger): BigInteger {
        val two = BigInteger.valueOf(2L)
        if (challenge.mod(two) == BigInteger.ZERO) return two

        var x = challenge
        var y = challenge
        val c = BigInteger.ONE
        var g = BigInteger.ONE
        while (g == BigInteger.ONE) {
            x = x.multiply(x).add(c).mod(challenge)
            y = y.multiply(y).add(c).mod(challenge)
            y = y.multiply(y).add(c).mod(challenge)
            g = x.subtract(y).abs().gcd(challenge)
        }
        return g
    }

    // Pollard's Rho.
    private fun powHandler(challenge: BigInteger): MutableList<BigInteger> {
        if (challenge == BigInteger.ONE) return mutableListOf()
        val factor = rho(challenge)
        if (factor == challenge) return mutableListOf(challenge)
        return (powHandler(factor) + powHandler(challenge.divide(factor))).sorted().toMutableList()
    }
}
