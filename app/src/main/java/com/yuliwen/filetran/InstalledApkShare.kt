package com.yuliwen.filetran

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

const val APK_SHARE_MIME = "application/vnd.android.package-archive"

data class InstalledApkEntry(
    val packageName: String,
    val appName: String,
    val sourceApkPath: String,
    val versionName: String,
    val isSystemApp: Boolean
)

data class ExtractedApkShare(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long
)

private fun sanitizeApkName(raw: String): String {
    return raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_').ifBlank { "app" }
}

private fun resolveVersionName(pm: PackageManager, pkg: String): String {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0).versionName
        }
    }.getOrNull()?.ifBlank { null } ?: "unknown"
}

private fun uniqueShareFile(outDir: File, desiredName: String): File {
    val base = desiredName.substringBeforeLast('.', desiredName)
    val ext = desiredName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
    var candidate = File(outDir, desiredName)
    var idx = 1
    while (candidate.exists()) {
        candidate = File(outDir, "${base}_$idx$ext")
        idx++
    }
    return candidate
}

suspend fun loadInstalledApkEntries(context: Context): List<InstalledApkEntry> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val apps = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }
    }.getOrDefault(emptyList())

    apps.mapNotNull { app ->
        val source = app.sourceDir.orEmpty()
        if (source.isBlank()) return@mapNotNull null
        val sourceFile = File(source)
        if (!sourceFile.exists() || !sourceFile.isFile) return@mapNotNull null
        val appName = runCatching { app.loadLabel(pm)?.toString().orEmpty() }.getOrDefault("").ifBlank { app.packageName }
        val version = resolveVersionName(pm, app.packageName)
        val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        InstalledApkEntry(
            packageName = app.packageName,
            appName = appName,
            sourceApkPath = source,
            versionName = version,
            isSystemApp = isSystem
        )
    }.sortedWith(
        compareBy<InstalledApkEntry> { it.appName.lowercase() }.thenBy { it.packageName.lowercase() }
    )
}

suspend fun extractInstalledApkShares(
    context: Context,
    entries: List<InstalledApkEntry>
): List<ExtractedApkShare> = withContext(Dispatchers.IO) {
    if (entries.isEmpty()) return@withContext emptyList()
    val outDir = File(context.externalCacheDir ?: context.cacheDir, "apk_share").apply { mkdirs() }
    entries.mapNotNull { entry ->
        runCatching {
            val source = File(entry.sourceApkPath)
            if (!source.exists() || !source.isFile) return@runCatching null
            val safeApp = sanitizeApkName(entry.appName)
            val safePkg = sanitizeApkName(entry.packageName)
            val safeVer = sanitizeApkName(entry.versionName)
            val desiredName = "${safeApp}_${safePkg}_v${safeVer}.apk"
            val outFile = uniqueShareFile(outDir, desiredName)
            FileInputStream(source).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
            ExtractedApkShare(
                uri = uri,
                fileName = outFile.name,
                mimeType = APK_SHARE_MIME,
                sizeBytes = outFile.length()
            )
        }.getOrNull()
    }
}

suspend fun extractCurrentAppApkShare(context: Context): ExtractedApkShare? = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val appInfo = context.applicationInfo
    val source = File(appInfo.sourceDir.orEmpty())
    if (!source.exists() || !source.isFile) return@withContext null
    val appName = runCatching { appInfo.loadLabel(pm)?.toString().orEmpty() }.getOrDefault("").ifBlank { "FileTran" }
    val version = resolveVersionName(pm, context.packageName)
    val entry = InstalledApkEntry(
        packageName = context.packageName,
        appName = appName,
        sourceApkPath = source.absolutePath,
        versionName = version,
        isSystemApp = false
    )
    extractInstalledApkShares(context, listOf(entry)).firstOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledApkPickerSheet(
    visible: Boolean,
    context: Context,
    onDismiss: () -> Unit,
    onConfirm: (List<InstalledApkEntry>) -> Unit
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var allApps by remember { mutableStateOf<List<InstalledApkEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var selectedPkgs by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        loading = true
        loadError = null
        selectedPkgs = emptySet()
        val loaded = runCatching { loadInstalledApkEntries(context) }
        loaded.onSuccess { list ->
            allApps = list
            loading = false
        }.onFailure { e ->
            allApps = emptyList()
            loading = false
            loadError = e.message ?: "加载失败"
        }
    }

    val keyword = query.trim().lowercase()
    val filtered = remember(allApps, keyword) {
        if (keyword.isBlank()) allApps else allApps.filter {
            it.appName.lowercase().contains(keyword) || it.packageName.lowercase().contains(keyword)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("提取其他应用 APK", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "支持搜索应用名或包名，可多选；包含系统应用与用户应用。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索（应用名/包名）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (loading) {
                Text("正在读取本机应用列表...")
            } else if (!loadError.isNullOrBlank()) {
                Text("读取失败：$loadError", color = MaterialTheme.colorScheme.error)
            } else {
                Text(
                    "匹配 ${filtered.size} 个，已选 ${selectedPkgs.size} 个",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        val checked = selectedPkgs.contains(app.packageName)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedPkgs = if (checked) {
                                        selectedPkgs - app.packageName
                                    } else {
                                        selectedPkgs + app.packageName
                                    }
                                },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = app.appName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = app.packageName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${if (app.isSystemApp) "系统应用" else "用户应用"} · v${app.versionName}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selectedPkgs = if (checked) {
                                            selectedPkgs - app.packageName
                                        } else {
                                            selectedPkgs + app.packageName
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        val chosen = allApps.filter { selectedPkgs.contains(it.packageName) }
                        onConfirm(chosen)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !loading && selectedPkgs.isNotEmpty()
                ) {
                    Text("提取并加入")
                }
            }
        }
    }
}
