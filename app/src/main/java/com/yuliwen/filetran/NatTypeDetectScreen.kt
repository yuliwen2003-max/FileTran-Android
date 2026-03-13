package com.yuliwen.filetran

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class IpFamilyResult(
    val udp: NatProtocolDetectResult,
    val tcp: NatProtocolDetectResult,
    val tls: NatProtocolDetectResult
)

private data class StunTarget(
    val host: String,
    val port: Int,
    val label: String
)

private fun pickStunTarget(list: List<StunServerDefinition>): StunTarget? {
    list.forEach { item ->
        val server = item.server.trim()
        val ip = item.ip.trim()
        val host = if (ip.isNotBlank()) ip else server
        if (host.isBlank()) return@forEach
        val port = item.port.takeIf { it in 1..65535 } ?: 3478
        val label = when {
            server.isBlank() -> host
            ip.isBlank() -> server
            else -> "$server/$ip"
        }
        return StunTarget(host = host, port = port, label = label)
    }
    return null
}

@Composable
fun NatTypeDetectScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localPortInput by remember { mutableStateOf("20080") }
    var detecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("点击开始探测映射行为与过滤行为。") }

    var ipv4 by remember { mutableStateOf<IpFamilyResult?>(null) }
    var ipv6 by remember { mutableStateOf<IpFamilyResult?>(null) }

    fun mappingText(mapping: String): String {
        return when (mapping) {
            "Direct" -> "$mapping（公网直连）"
            "PublicIpMatch" -> "$mapping（本机网卡 IP 与 STUN 映射 IP 一致）"
            else -> mapping
        }
    }

    @Composable
    fun ProtocolCard(title: String, result: NatProtocolDetectResult, showFiltering: Boolean) {
        val endpoint = result.mappedEndpoint?.let { "${it.address}:${it.port}" } ?: "-"
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text("NAT 类型：${result.natType}")
                Text("映射行为：${mappingText(result.mappingBehavior)}")
                if (showFiltering) {
                    Text("过滤行为：${result.filteringBehavior}")
                }
                Text("映射端点：$endpoint")
                if (result.note.isNotBlank()) {
                    Text("备注：${result.note}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    fun FamilyCard(title: String, result: IpFamilyResult?) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (result == null) {
                    Text("尚未探测", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    ProtocolCard("UDP", result.udp, showFiltering = true)
                    ProtocolCard("TCP", result.tcp, showFiltering = false)
                    ProtocolCard("TLS", result.tls, showFiltering = false)
                }
            }
        }
    }

    suspend fun startDetect() {
        val localPort = localPortInput.toIntOrNull()
        if (localPort == null || localPort !in 1..65535) {
            status = "请输入有效本地端口。"
            return
        }
        detecting = true
        status = "探测中...（IPv4/IPv6 × UDP/TCP/TLS）"

        val settings = NetworkUtils.getStunServerSettings(context)
        val ipv4UdpTarget = pickStunTarget(settings.ipv4Udp)
        val ipv4TcpTarget = pickStunTarget(settings.ipv4Tcp)
        val ipv6UdpTarget = pickStunTarget(settings.ipv6Udp)
        val ipv6TcpTarget = pickStunTarget(settings.ipv6Tcp)

        fun missingResult(note: String): NatProtocolDetectResult {
            return NatProtocolDetectResult(
                natType = "未知",
                mappingBehavior = "Fail",
                filteringBehavior = "Fail",
                mappedEndpoint = null,
                note = note
            )
        }

        val ipv4Udp = withContext(Dispatchers.IO) {
            ipv4UdpTarget?.let {
                NetworkUtils.detectStunBehavior(localPort, false, it.host, it.port, StunTransportType.UDP)
            } ?: missingResult("未配置 IPv4 UDP STUN 服务器")
        }
        val ipv4Tcp = withContext(Dispatchers.IO) {
            ipv4TcpTarget?.let {
                NetworkUtils.detectStunBehavior(localPort, false, it.host, it.port, StunTransportType.TCP)
            } ?: missingResult("未配置 IPv4 TCP STUN 服务器")
        }
        val ipv4Tls = withContext(Dispatchers.IO) {
            ipv4TcpTarget?.let {
                NetworkUtils.detectStunBehavior(localPort, false, it.host, it.port, StunTransportType.TLS)
            } ?: missingResult("未配置 IPv4 TLS STUN 服务器")
        }
        val ipv6Udp = withContext(Dispatchers.IO) {
            ipv6UdpTarget?.let {
                NetworkUtils.detectStunBehavior(localPort, true, it.host, it.port, StunTransportType.UDP)
            } ?: missingResult("未配置 IPv6 UDP STUN 服务器")
        }
        val ipv6Tcp = withContext(Dispatchers.IO) {
            ipv6TcpTarget?.let {
                NetworkUtils.detectStunBehavior(localPort, true, it.host, it.port, StunTransportType.TCP)
            } ?: missingResult("未配置 IPv6 TCP STUN 服务器")
        }
        val ipv6Tls = withContext(Dispatchers.IO) {
            ipv6TcpTarget?.let {
                NetworkUtils.detectStunBehavior(localPort, true, it.host, it.port, StunTransportType.TLS)
            } ?: missingResult("未配置 IPv6 TLS STUN 服务器")
        }

        ipv4 = IpFamilyResult(ipv4Udp, ipv4Tcp, ipv4Tls)
        ipv6 = IpFamilyResult(ipv6Udp, ipv6Tcp, ipv6Tls)

        status = buildString {
            append("探测完成。")
            append("\nIPv4 UDP：${ipv4UdpTarget?.let { "${it.label}:${it.port}" } ?: "未配置"}")
            append("\nIPv4 TCP/TLS：${ipv4TcpTarget?.let { "${it.label}:${it.port}" } ?: "未配置"}")
            append("\nIPv6 UDP：${ipv6UdpTarget?.let { "${it.label}:${it.port}" } ?: "未配置"}")
            append("\nIPv6 TCP/TLS：${ipv6TcpTarget?.let { "${it.label}:${it.port}" } ?: "未配置"}")
        }
        detecting = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
            enabled = !detecting
        ) {
            Text("返回传输实验室")
        }
        Text("NAT类型探测", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "默认使用“关于”页面中对应类型的首个 STUN 服务器（TLS 复用 TCP 列表）。TCP/TLS 仅检测映射行为；Direct 或 PublicIpMatch 均表示公网，其中 PublicIpMatch 用于与 NAT1 区分。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = localPortInput,
                        onValueChange = { localPortInput = it.filter(Char::isDigit) },
                        label = { Text("本地端口") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !detecting
                    )
                    Button(
                        onClick = { scope.launch { startDetect() } },
                        enabled = !detecting,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (detecting) "探测中..." else "开始探测")
                    }
                }
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        FamilyCard("IPv4", ipv4)
        FamilyCard("IPv6", ipv6)
    }
}
