package com.yuliwen.filetran

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilePresent
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadHistoryScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val downloadHistoryManager = remember { DownloadHistoryManager(context) }
    val clipboardHistoryManager = remember { ClipboardHistoryManager(context) }

    var downloadHistory by remember { mutableStateOf(downloadHistoryManager.getHistory()) }
    var clipboardHistory by remember { mutableStateOf(clipboardHistoryManager.getHistory()) }
    var selectedTab by remember { mutableStateOf(0) }
    var showDeleteDialog by remember { mutableStateOf<DownloadRecord?>(null) }
    var selectedDownloadRecord by remember { mutableStateOf<DownloadRecord?>(null) }
    var selectedClipboardRecord by remember { mutableStateOf<ClipboardRecord?>(null) }
    var showClearCurrentConfirm by remember { mutableStateOf(false) }
    var showOpenSourceNotice by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        HistoryHeader(
            downloadCount = downloadHistory.size,
            clipboardCount = clipboardHistory.size
        )

        Spacer(modifier = Modifier.height(12.dp))

        ScrollableTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("下载历史") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("剪贴板历史") }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            TextButton(
                onClick = {
                    val targetDir = if (selectedTab == 0) {
                        FileDownloadManager.getDownloadDirectory(context)
                    } else {
                        context.filesDir
                    }
                    openDirectoryInManager(context, targetDir)
                }
            ) {
                Text("打开当前目录")
            }

            TextButton(onClick = { showClearCurrentConfirm = true }) {
                Text("清空当前")
            }

            TextButton(onClick = { showOpenSourceNotice = true }) {
                Text("开源声明")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (selectedTab == 0) {
                if (downloadHistory.isEmpty()) {
                    EmptyHistory("暂无下载记录")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(downloadHistory) { record ->
                            DownloadHistoryItem(
                                record = record,
                                onOpenActions = { selectedDownloadRecord = record },
                                onDelete = { showDeleteDialog = record }
                            )
                        }
                    }
                }
            } else {
                if (clipboardHistory.isEmpty()) {
                    EmptyHistory("暂无剪贴板记录")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(clipboardHistory) { record ->
                            ClipboardHistoryItem(
                                record = record,
                                onOpen = { selectedClipboardRecord = record },
                                onDelete = {
                                    clipboardHistoryManager.deleteRecord(record.id)
                                    clipboardHistory = clipboardHistoryManager.getHistory()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearCurrentConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCurrentConfirm = false },
            title = { Text("确认清空") },
            text = { Text("确定要清空当前列表吗？该操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (selectedTab == 0) {
                            downloadHistoryManager.clearHistory()
                            downloadHistory = emptyList()
                        } else {
                            clipboardHistoryManager.clearHistory()
                            clipboardHistory = emptyList()
                        }
                        showClearCurrentConfirm = false
                    }
                ) {
                    Text("确认清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCurrentConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    selectedDownloadRecord?.let { record ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedDownloadRecord = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = record.fileName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${record.getFormattedSize()}  ${record.getFormattedTime()}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedButton(
                    onClick = {
                        openFileDefault(context, record.filePath)
                        selectedDownloadRecord = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("默认方式打开")
                }

                OutlinedButton(
                    onClick = {
                        openFileWithChooser(context, record.filePath)
                        selectedDownloadRecord = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("选择其他应用打开")
                }

                OutlinedButton(
                    onClick = {
                        shareFile(context, record.filePath)
                        selectedDownloadRecord = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text("发送给其他应用")
                }

                TextButton(
                    onClick = { selectedDownloadRecord = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("关闭")
                }
            }
        }
    }

    selectedClipboardRecord?.let { record ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedClipboardRecord = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("剪贴板内容", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("来源: ${record.source}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(record.content)
                OutlinedButton(
                    onClick = { copyToClipboard(context, record.content) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text("复制")
                }
                TextButton(
                    onClick = { selectedClipboardRecord = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("关闭")
                }
            }
        }
    }

    showDeleteDialog?.let { record ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除记录") },
            text = { Text("是否同时删除对应文件？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        FileDownloadManager.deleteFile(record.filePath)
                        downloadHistoryManager.deleteRecord(record.id)
                        downloadHistory = downloadHistoryManager.getHistory()
                        showDeleteDialog = null
                    }
                ) {
                    Text("删除文件+记录")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            downloadHistoryManager.deleteRecord(record.id)
                            downloadHistory = downloadHistoryManager.getHistory()
                            showDeleteDialog = null
                        }
                    ) {
                        Text("仅删记录")
                    }
                    TextButton(onClick = { showDeleteDialog = null }) {
                        Text("取消")
                    }
                }
            }
        )
    }

    if (showOpenSourceNotice) {
        OpenSourceNoticeSheet(onDismiss = { showOpenSourceNotice = false })
    }
}

@Composable
private fun HistoryHeader(downloadCount: Int, clipboardCount: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color(0xFFEAF2FF), Color(0xFFF2F8EE))
                )
            ),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("历史中心", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatChip("下载", downloadCount)
                StatChip("剪贴板", clipboardCount)
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyHistory(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun DownloadHistoryItem(
    record: DownloadRecord,
    onOpenActions: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenActions() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.FilePresent,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.fileName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${record.getFormattedSize()}  ${record.getFormattedTime()}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onOpenActions) {
                Icon(Icons.Default.MoreHoriz, contentDescription = "actions")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ClipboardHistoryItem(
    record: ClipboardRecord,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.TextSnippet,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.preview(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${record.source}  ${record.getFormattedTime()}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("history_text", text))
}

private fun openDirectoryInManager(context: android.content.Context, directory: File) {
    runCatching { if (!directory.exists()) directory.mkdirs() }
    val absolutePath = directory.absolutePath

    val opened = runCatching {
        val externalRoot = Environment.getExternalStorageDirectory().absolutePath
        if (!absolutePath.startsWith(externalRoot)) return@runCatching false

        val relativePath = absolutePath
            .removePrefix(externalRoot)
            .trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
        val docId = if (relativePath.isBlank()) "primary:" else "primary:$relativePath"
        val documentUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    if (!opened) {
        runCatching {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    Toast.makeText(context, "目录: $absolutePath", Toast.LENGTH_SHORT).show()
}

private fun openFileDefault(context: android.content.Context, filePath: String) {
    val file = File(filePath)
    if (!file.exists()) {
        Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    if (file.extension.equals("apk", ignoreCase = true)) {
        if (!ensureCanInstallApk(context)) return
        val apkIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(apkIntent) }
            .onFailure { Toast.makeText(context, "无法打开 APK", Toast.LENGTH_SHORT).show() }
        return
    }

    val mimeType = getMimeType(file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "无法默认打开，请尝试“选择其他应用打开”", Toast.LENGTH_SHORT).show() }
}

private fun openFileWithChooser(context: android.content.Context, filePath: String) {
    val file = File(filePath)
    if (!file.exists()) {
        Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    val mimeType = if (file.extension.equals("apk", ignoreCase = true)) {
        if (!ensureCanInstallApk(context)) return
        "application/vnd.android.package-archive"
    } else {
        getMimeType(file)
    }

    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(viewIntent, "选择应用打开").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
        .onFailure { Toast.makeText(context, "没有可用应用", Toast.LENGTH_SHORT).show() }
}

private fun shareFile(context: android.content.Context, filePath: String) {
    val file = File(filePath)
    if (!file.exists()) {
        Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
        return
    }

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = getMimeType(file)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(shareIntent, "发送到").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
        .onFailure { Toast.makeText(context, "无法分享该文件", Toast.LENGTH_SHORT).show() }
}

private fun ensureCanInstallApk(context: android.content.Context): Boolean {
    val canInstall = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        context.packageManager.canRequestPackageInstalls()
    } else {
        true
    }
    if (!canInstall) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Toast.makeText(context, "请先允许安装未知应用", Toast.LENGTH_SHORT).show()
        return false
    }
    return true
}

private fun getMimeType(file: File): String {
    return MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase())
        ?: "*/*"
}
