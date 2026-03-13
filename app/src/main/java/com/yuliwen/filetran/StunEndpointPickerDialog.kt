package com.yuliwen.filetran

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun StunEndpointPickerDialog(
    visible: Boolean,
    title: String,
    result: StunProbeBatchResult?,
    selected: StunMappedEndpoint?,
    onSelect: (StunMappedEndpoint) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible || result == null) return

    val rows = if (result.serverStatuses.isNotEmpty()) {
        result.serverStatuses
    } else {
        result.endpoints.map { endpoint ->
            StunServerProbeStatus(
                server = endpoint.stunServer,
                transport = result.transport,
                attemptedPorts = emptyList(),
                mappedEndpoint = endpoint,
                note = "探测成功"
            )
        }
    }
    val selectableRowIndexes = rows.mapIndexedNotNull { index, row ->
        if (row.mappedEndpoint != null) index else null
    }
    fun indexOfEndpoint(target: StunMappedEndpoint?): Int? {
        if (target == null) return null
        val idx = rows.indexOfFirst { row ->
            val ep = row.mappedEndpoint
            ep != null &&
                ep.address == target.address &&
                ep.port == target.port &&
                ep.stunServer == target.stunServer
        }
        return idx.takeIf { it >= 0 }
    }
    var pendingSelectionIndex by remember(result, selected) {
        val defaultIndex =
            indexOfEndpoint(selected)
                ?: indexOfEndpoint(result.preferredEndpoint)
                ?: selectableRowIndexes.firstOrNull()
        mutableStateOf(defaultIndex)
    }
    val effectiveSelectionIndex = pendingSelectionIndex?.takeIf { idx ->
        rows.getOrNull(idx)?.mappedEndpoint != null
    }
    val pendingSelection = effectiveSelectionIndex?.let { rows[it].mappedEndpoint }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                result.publicIpByIpip?.let {
                    Text(
                        text = "ipip 公网 IP：$it",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (result.allMismatch) {
                    Text(
                        text = "所有 STUN 结果都与 ipip 不一致，请手动选择。",
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (result.matchedByIpip.isNotEmpty()) {
                    Text(
                        text = "已检测到 ipip 匹配项，默认优先首个结果。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (result.hasPortMismatch) {
                    Text(
                        text = "警告：不同 STUN 返回端口不一致（${result.uniquePorts.joinToString("/") }），当前网络可能为 NAT4，传输可能失败。你可以继续。",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                rows.forEachIndexed { index, row ->
                    val endpoint = row.mappedEndpoint
                    val isSelected = endpoint != null && effectiveSelectionIndex == index
                    val isMatched = endpoint != null && result.matchedByIpip.any {
                        it.address == endpoint.address && it.port == endpoint.port
                    }
                    val line = if (endpoint == null) {
                        "${index + 1}. ${row.server} -> 探测失败"
                    } else {
                        buildString {
                            append("${index + 1}. ${row.server} -> ${endpoint.address}:${endpoint.port}")
                            if (isMatched) append("  [匹配 ipip]")
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (endpoint != null) {
                                    Modifier.clickable { pendingSelectionIndex = index }
                                } else {
                                    Modifier
                                }
                            ),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            color = when {
                                endpoint == null -> MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                                isSelected -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outlineVariant
                            }
                        ),
                        color = when {
                            endpoint == null -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.20f)
                            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }
                    ) {
                        Text(
                            text = line,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (endpoint == null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pendingSelection != null,
                onClick = {
                    pendingSelection?.let { onSelect(it) }
                    onDismiss()
                }
            ) {
                Text(if (pendingSelection != null) "继续并使用所选地址" else "无可用地址")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
