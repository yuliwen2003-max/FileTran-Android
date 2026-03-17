package com.yuliwen.filetran

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

// ---------------------------------------------------------------------------
// Curve25519 X25519 纯 Kotlin 实现（WireGuard 密钥对生成）
// 修复：bit 提取使用 (t/8) 和 (t%8)，避免运算符优先级 bug
// ---------------------------------------------------------------------------
internal object Curve25519 {

    fun generatePrivateKey(): ByteArray {
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        k[0]  = (k[0].toInt()  and 248).toByte()
        k[31] = (k[31].toInt() and 127 or 64).toByte()
        return k
    }

    fun publicKey(priv: ByteArray): ByteArray =
        x25519(clamp(priv), ByteArray(32).also { it[0] = 9 })

    fun dh(myPrivate: ByteArray, peerPublic: ByteArray): ByteArray =
        x25519(clamp(myPrivate), peerPublic.copyOf(32))

    private fun clamp(k: ByteArray): ByteArray = k.copyOf(32).also {
        it[0]  = (it[0].toInt()  and 248).toByte()
        it[31] = (it[31].toInt() and 127 or 64).toByte()
    }

    // RFC 7748 X25519 - Montgomery ladder
    // 所有值均为小端序 ByteArray（与 WireGuard 一致）
    private val P = longArrayOf(
        0xFFFFFFFFFFFFEDL, 0xFFFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFL,
        0xFFFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFL
    ) // 仅用于概念，实际用 BigInteger

    private fun x25519(k: ByteArray, u: ByteArray): ByteArray {
        // RFC 7748 §5 X25519 Montgomery ladder
        val p   = java.math.BigInteger.ONE.shiftLeft(255).subtract(java.math.BigInteger.valueOf(19))
        val a24 = java.math.BigInteger.valueOf(121665)

        fun decodeLe(b: ByteArray): java.math.BigInteger {
            val copy = b.copyOf(32); copy.reverse()
            return java.math.BigInteger(1, copy)
        }
        fun encodeLe(n: java.math.BigInteger): ByteArray {
            val out = ByteArray(32)
            val big = n.mod(p).toByteArray()   // 大端，可能有多个前导0
            var skip = 0; while (skip < big.size && big[skip] == 0.toByte()) skip++
            val len = big.size - skip
            System.arraycopy(big, skip, out, 32 - len, len)
            out.reverse()   // 大端 → 小端
            return out
        }

        // RFC 7748 §5：x25519 内部必须 clamp 标量
        val kk = k.copyOf(32)
        kk[0]  = (kk[0].toInt()  and 248).toByte()
        kk[31] = (kk[31].toInt() and 127 or 64).toByte()

        // mask off bit 255 of u-coordinate
        val uBytes = u.copyOf(32); uBytes[31] = (uBytes[31].toInt() and 0x7F).toByte()
        val x1 = decodeLe(uBytes)

        var x2 = java.math.BigInteger.ONE
        var z2 = java.math.BigInteger.ZERO
        var x3 = x1
        var z3 = java.math.BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = (kk[t ushr 3].toInt() ushr (t and 7)) and 1
            swap = swap xor kt
            if (swap != 0) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = kt

            val A  = x2.add(z2).mod(p)
            val AA = A.multiply(A).mod(p)
            val B  = x2.subtract(z2).mod(p)
            val BB = B.multiply(B).mod(p)
            val E  = AA.subtract(BB).mod(p)
            val C  = x3.add(z3).mod(p)
            val D  = x3.subtract(z3).mod(p)
            val DA = D.multiply(A).mod(p)
            val CB = C.multiply(B).mod(p)
            val x3n = DA.add(CB).mod(p).let { it.multiply(it).mod(p) }
            val z3n = x1.multiply(DA.subtract(CB).mod(p).let { it.multiply(it).mod(p) }).mod(p)
            x2 = AA.multiply(BB).mod(p)
            z2 = E.multiply(AA.add(a24.multiply(E).mod(p)).mod(p)).mod(p)
            x3 = x3n; z3 = z3n
        }
        if (swap != 0) {
            var tmp = x2; x2 = x3; x3 = tmp
            tmp = z2; z2 = z3; z3 = tmp
        }
        return encodeLe(x2.multiply(z2.modPow(p.subtract(java.math.BigInteger.TWO), p)).mod(p))
    }

} // end Curve25519

// ---------------------------------------------------------------------------
// WireGuard 密钥工具
// ---------------------------------------------------------------------------
object WireGuardKeyUtil {
    fun generatePrivateKey(): String =
        Base64.getEncoder().encodeToString(Curve25519.generatePrivateKey())

    fun publicKeyFromPrivate(b64: String): String =
        Base64.getEncoder().encodeToString(
            Curve25519.publicKey(Base64.getDecoder().decode(b64.trim()))
        )

    fun generatePresharedKey(): String =
        Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun isValid(s: String): Boolean = runCatching {
        Base64.getDecoder().decode(s.trim()).size == 32
    }.getOrDefault(false)
}

// ---------------------------------------------------------------------------
// 数据模型
// ---------------------------------------------------------------------------
data class WgPeer(
    val publicKey: String        = "",
    val presharedKey: String     = "",
    val endpoint: String         = "",
    val allowedIPs: String       = "0.0.0.0/0,::/0",
    val persistentKeepalive: Int = 25,
    val label: String            = ""
)

data class WgInterface(
    val privateKey: String = "",
    val address: String    = "10.0.0.1/24",
    val listenPort: Int    = 51820,
    val dns: String        = "",
    val mtu: Int           = 1420,
    val natEnabled: Boolean = false  // root 设备：开启后自动配置 iptables MASQUERADE + ip_forward
)

data class WgConfig(
    val id: String             = java.util.UUID.randomUUID().toString(),
    val name: String           = "FileTran VPN",
    val iface: WgInterface     = WgInterface(),
    val peers: List<WgPeer>    = emptyList(),
    val linkedClientId: String = ""   // 仅服务端配置：关联的客户端配置 ID
) {
    /** 生成标准 WireGuard wg-quick INI 格式配置文本 */
    fun toWgQuickConf(): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${iface.privateKey}")
        if (iface.address.isNotBlank())  appendLine("Address = ${iface.address}")
        if (iface.listenPort > 0)        appendLine("ListenPort = ${iface.listenPort}")
        if (iface.dns.isNotBlank())      appendLine("DNS = ${iface.dns}")
        if (iface.mtu > 0)              appendLine("MTU = ${iface.mtu}")
        // 服务端：若有监听端口，生成 Linux NAT 所需的 PostUp/PostDown 规则（仅在 Linux wg-quick 下生效）
        // Android 端无法执行 iptables，需在 Linux 服务端手动启用 IP 转发后使用此配置
        if (iface.listenPort > 0) {
            appendLine("# 以下 PostUp/PostDown 仅在 Linux wg-quick 环境下生效，Android 端忽略")
            appendLine("# PostUp = sysctl -w net.ipv4.ip_forward=1; iptables -t nat -A POSTROUTING -s ${iface.address.substringBeforeLast('/')}0/${iface.address.substringAfterLast('/')} -j MASQUERADE")
            appendLine("# PostDown = iptables -t nat -D POSTROUTING -s ${iface.address.substringBeforeLast('/')}0/${iface.address.substringAfterLast('/')} -j MASQUERADE")
        }
        for (peer in peers) {
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${peer.publicKey}")
            if (peer.presharedKey.isNotBlank()) appendLine("PresharedKey = ${peer.presharedKey}")
            if (peer.endpoint.isNotBlank())     appendLine("Endpoint = ${peer.endpoint}")
            if (peer.allowedIPs.isNotBlank())   appendLine("AllowedIPs = ${peer.allowedIPs}")
            if (peer.persistentKeepalive > 0)   appendLine("PersistentKeepalive = ${peer.persistentKeepalive}")
        }
    }

    /**
     * 序列化为完整 wg-quick conf 文本，用于二维码分享。
     * 二维码内容直接就是 wg-quick 标准 INI 文本，与官方 WireGuard App 完全互通。
     * 注意：包含私钥，仅在设备本机使用，不要公开分享。
     */
    fun toShareConf(): String = toWgQuickConf()

    companion object {
        /**
         * 从二维码扫描内容解析 WgConfig。
         * 支持两种格式：
         * 1. 标准 wg-quick INI 文本（与官方 WireGuard App 互通）
         * 2. 旧版 filetran_wg_share_v1 JSON（仅含公钥，向下兼容）
         */
        fun fromScanContent(raw: String, id: String = java.util.UUID.randomUUID().toString(), name: String = "[C] 扫码导入"): WgConfig? {
            val trimmed = raw.trim()
            return if (trimmed.startsWith("[")) {
                // 标准 wg-quick INI 格式
                parseWgQuickConfStatic(trimmed, id, name)
            } else if (trimmed.startsWith("{")) {
                // 旧版 JSON 格式（向下兼容）
                runCatching {
                    val obj = JSONObject(trimmed)
                    if (obj.optString("type") == "filetran_wg_share_v1") {
                        // 旧格式：仅有公钥，无法直接建立完整配置，返回 null 让上层提示手动填写
                        null
                    } else null
                }.getOrNull()
            } else null
        }

        /** 从旧版 JSON 分享码中提取对端公钥（向下兼容） */
        fun peerFromShareJson(json: String, endpoint: String = ""): WgPeer? = runCatching {
            val obj = JSONObject(json)
            if (obj.optString("type") != "filetran_wg_share_v1") return null
            WgPeer(
                publicKey           = obj.optString("pubkey"),
                endpoint            = endpoint.ifBlank { "" },
                allowedIPs          = obj.optString("allowed_ips").ifBlank { "0.0.0.0/0,::/0" },
                persistentKeepalive = 25,
                label               = obj.optString("name")
            )
        }.getOrNull()

        fun fromJson(json: String): WgConfig? = runCatching {
            val obj = JSONObject(json)
            val ifaceObj = obj.optJSONObject("iface") ?: return null
            val peersArr = obj.optJSONArray("peers") ?: JSONArray()
            WgConfig(
                id             = obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                name           = obj.optString("name").ifBlank { "FileTran VPN" },
                linkedClientId = obj.optString("linkedClientId"),
                iface = WgInterface(
                    privateKey = ifaceObj.optString("privateKey"),
                    address    = ifaceObj.optString("address"),
                    listenPort = ifaceObj.optInt("listenPort", 0),
                    dns        = ifaceObj.optString("dns"),
                    mtu        = ifaceObj.optInt("mtu", 1420),
                    natEnabled = ifaceObj.optBoolean("natEnabled", false)
                ),
                peers = (0 until peersArr.length()).map { i ->
                    val p = peersArr.getJSONObject(i)
                    WgPeer(
                        publicKey           = p.optString("publicKey"),
                        presharedKey        = p.optString("presharedKey"),
                        endpoint            = p.optString("endpoint"),
                        allowedIPs          = p.optString("allowedIPs").ifBlank { "0.0.0.0/0,::/0" },
                        persistentKeepalive = p.optInt("persistentKeepalive", 25),
                        label               = p.optString("label")
                    )
                }
            )
        }.getOrNull()

        private fun parseWgQuickConfStatic(text: String, id: String, name: String): WgConfig? = runCatching {
            var privateKey = ""; var address = ""; var listenPort = 0; var dns = ""; var mtu = 1420
            var peerPubKey = ""; var peerPsk = ""; var endpoint = ""; var allowedIPs = ""; var keepalive = 25
            var inPeer = false
            for (rawLine in text.lines()) {
                val line = rawLine.trim()
                if (line.startsWith("[")) { inPeer = line.equals("[Peer]", ignoreCase = true); continue }
                if (line.isBlank() || line.startsWith("#")) continue
                val eqIdx = line.indexOf('=')
                if (eqIdx < 0) continue
                val key = line.substring(0, eqIdx).trim()
                val value = line.substring(eqIdx + 1).trim()
                if (!inPeer) when (key.lowercase()) {
                    "privatekey"  -> privateKey = value
                    "address"     -> address    = value
                    "listenport"  -> listenPort = value.toIntOrNull() ?: 0
                    "dns"         -> dns        = value
                    "mtu"         -> mtu        = value.toIntOrNull() ?: 1420
                } else when (key.lowercase()) {
                    "publickey"           -> peerPubKey = value
                    "presharedkey"        -> peerPsk     = value
                    "endpoint"            -> endpoint    = value
                    "allowedips"          -> allowedIPs  = value
                    "persistentkeepalive" -> keepalive   = value.toIntOrNull() ?: 25
                }
            }
            if (privateKey.isBlank()) return null
            WgConfig(
                id = id, name = name,
                iface = WgInterface(
                    privateKey = privateKey, address = address,
                    listenPort = listenPort, dns = dns, mtu = mtu
                ),
                peers = if (peerPubKey.isNotBlank()) listOf(WgPeer(
                    publicKey = peerPubKey, presharedKey = peerPsk, endpoint = endpoint,
                    allowedIPs = allowedIPs.ifBlank { "0.0.0.0/0,::/0" },
                    persistentKeepalive = keepalive
                )) else emptyList()
            )
        }.getOrNull()
    }

    fun toJson(): String = JSONObject().apply {
        put("id", id); put("name", name)
        if (linkedClientId.isNotBlank()) put("linkedClientId", linkedClientId)
        put("iface", JSONObject().apply {
            put("privateKey", iface.privateKey); put("address", iface.address)
            put("listenPort", iface.listenPort); put("dns", iface.dns); put("mtu", iface.mtu)
            put("natEnabled", iface.natEnabled)
        })
        put("peers", JSONArray().apply {
            peers.forEach { p ->
                put(JSONObject().apply {
                    put("publicKey", p.publicKey); put("presharedKey", p.presharedKey)
                    put("endpoint", p.endpoint);   put("allowedIPs", p.allowedIPs)
                    put("persistentKeepalive", p.persistentKeepalive); put("label", p.label)
                })
            }
        })
    }.toString()
}

// ---------------------------------------------------------------------------
// WireGuard 配置持久化
// ---------------------------------------------------------------------------
object WireGuardConfigStore {
    private const val PREFS = "wg_configs_v1"
    private const val KEY   = "configs_json"

    fun loadAll(ctx: Context): List<WgConfig> = runCatching {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { WgConfig.fromJson(arr.getString(it)) }
    }.getOrDefault(emptyList())

    fun saveAll(ctx: Context, configs: List<WgConfig>) {
        val arr = JSONArray(configs.map { it.toJson() })
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun save(ctx: Context, config: WgConfig) {
        val list = loadAll(ctx).toMutableList()
        val idx  = list.indexOfFirst { it.id == config.id }
        if (idx >= 0) list[idx] = config else list.add(config)
        saveAll(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        saveAll(ctx, loadAll(ctx).filter { it.id != id })
    }
}
