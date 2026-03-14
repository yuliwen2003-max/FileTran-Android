package com.yuliwen.filetran

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.yuliwen.filetran.ui.theme.FileTranTheme
import kotlin.math.roundToInt

class NeighborSheetActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setFinishOnTouchOutside(false)
        overridePendingTransition(0, 0)

        setContent {
            FileTranTheme {
                val state by NeighborSheetBridge.state.collectAsState()
                BackHandler(enabled = state !is NeighborSheetUiState.Hidden) {}

                if (state is NeighborSheetUiState.Hidden) {
                    LaunchedEffect(Unit) {
                        finishSilently()
                    }
                    return@FileTranTheme
                }

                val sheetState = rememberModalBottomSheetState(
                    skipPartiallyExpanded = true,
                    confirmValueChange = { it != SheetValue.Hidden }
                )
                ModalBottomSheet(
                    onDismissRequest = {},
                    sheetState = sheetState
                ) {
                    when (val current = state) {
                        is NeighborSheetUiState.Invite -> InviteContent(current.data)
                        is NeighborSheetUiState.Progress -> ProgressContent(current.data)
                        is NeighborSheetUiState.Done -> DoneContent(current.data)
                        NeighborSheetUiState.Hidden -> Unit
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)
    }

    private fun finishSilently() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
        overridePendingTransition(0, 0)
    }

    @Composable
    private fun InviteContent(data: NeighborSheetInvite) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("文件接收请求", fontSize = 20.sp)
            Text(
                "发送方：${data.senderName}" + if (data.senderIp.isBlank()) "" else " (${data.senderIp})",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("文件名：${data.fileName}")
            Text("文件大小：${if (data.fileSize >= 0L) formatBytes(data.fileSize) else "未知"}")
            Text(
                "${data.secondsLeft}s 后自动${if (data.defaultAccept) "接收" else "拒绝"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { sendAction(NeighborDiscoveryBackgroundService.ACTION_INVITE_REJECT, data.requestId) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("拒绝")
                }
                Button(
                    onClick = { sendAction(NeighborDiscoveryBackgroundService.ACTION_INVITE_ACCEPT, data.requestId) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("允许接收")
                }
            }
            OutlinedButton(
                onClick = { sendAction(NeighborDiscoveryBackgroundService.ACTION_INVITE_HIDE, data.requestId) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("隐藏到通知")
            }
        }
    }

    @Composable
    private fun ProgressContent(data: NeighborSheetProgress) {
        val percent = (data.progress.coerceIn(0f, 1f) * 100f).roundToInt()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("接收中", fontSize = 20.sp)
            Text("文件名：${data.fileName}")
            Text(
                "$percent% · ${formatBytes(data.downloadedBytes)} / ${formatBytes(data.totalBytes)} · ${formatBytes(data.speedBytesPerSecond)}/s",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { sendAction(NeighborDiscoveryBackgroundService.ACTION_RECEIVE_CANCEL, data.requestId) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消接收")
                }
                Button(
                    onClick = { sendAction(NeighborDiscoveryBackgroundService.ACTION_RECEIVE_HIDE, data.requestId) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("隐藏")
                }
            }
        }
    }

    @Composable
    private fun DoneContent(data: NeighborSheetDone) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("接收完成", fontSize = 20.sp)
            Text("${data.fileName} · ${formatBytes(data.fileSize)}")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        sendAction(
                            NeighborDiscoveryBackgroundService.ACTION_DONE_OPEN_DEFAULT,
                            filePath = data.filePath
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("默认打开")
                }
                Button(
                    onClick = {
                        sendAction(
                            NeighborDiscoveryBackgroundService.ACTION_DONE_OPEN_CHOOSER,
                            filePath = data.filePath
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("选择方式")
                }
            }
            if (data.canImportGallery) {
                OutlinedButton(
                    onClick = {
                        sendAction(
                            NeighborDiscoveryBackgroundService.ACTION_DONE_IMPORT_GALLERY,
                            filePath = data.filePath
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("导入图库")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { sendAction(NeighborDiscoveryBackgroundService.ACTION_DONE_OPEN_DOWNLOADS) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("打开文件夹")
                }
                OutlinedButton(
                    onClick = { sendAction(NeighborDiscoveryBackgroundService.ACTION_DONE_DISMISS) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("关闭")
                }
            }
        }
    }

    private fun sendAction(action: String, requestId: String? = null, filePath: String? = null) {
        val intent = Intent(this, NeighborDiscoveryBackgroundService::class.java).apply {
            this.action = action
            if (!requestId.isNullOrBlank()) {
                putExtra(NeighborDiscoveryBackgroundService.EXTRA_REQUEST_ID, requestId)
            }
            if (!filePath.isNullOrBlank()) {
                putExtra(NeighborDiscoveryBackgroundService.EXTRA_FILE_PATH, filePath)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun formatBytes(value: Long): String {
        val v = value.coerceAtLeast(0L)
        return when {
            v < 1024L -> "${v}B"
            v < 1024L * 1024L -> "${v / 1024L}KB"
            v < 1024L * 1024L * 1024L -> "${v / (1024L * 1024L)}MB"
            else -> "${v / (1024L * 1024L * 1024L)}GB"
        }
    }
}

