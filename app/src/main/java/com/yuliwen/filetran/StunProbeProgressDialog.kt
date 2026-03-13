package com.yuliwen.filetran

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp

@Composable
fun StunProbeProgressDialog(
    visible: Boolean,
    checked: Int,
    total: Int,
    successCount: Int,
    currentServer: String,
    onFinishNow: () -> Unit,
    onDisableNatAndContinue: () -> Unit
) {
    if (!visible) return
    val safeTotal = total.coerceAtLeast(1)
    val safeChecked = checked.coerceIn(0, safeTotal)

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        title = {
            Text("正在探测 STUN（$safeChecked/$safeTotal）")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "请稍候，正在逐个验证 STUN 地址。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "已探测到可用地址：$successCount",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (currentServer.isNotBlank()) {
                    Text(
                        text = "当前服务器：$currentServer",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = successCount > 0,
                onClick = onFinishNow
            ) {
                Text("立即结束探测")
            }
        },
        dismissButton = {
            TextButton(onClick = onDisableNatAndContinue) {
                Text("关闭 NAT 并继续")
            }
        }
    )
}
