package com.yuliwen.filetran

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotspotDebugDialog(
    config: HotspotConfig,
    onDismiss: () -> Unit,
    onTestAll: () -> Unit
) {
    var debugInfo by remember { mutableStateOf<HotspotDebugInfo?>(null) }
    var testResults by remember { mutableStateOf<Map<String, Boolean>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val info = HotspotDebugger.getDebugInfo(context)
            withContext(Dispatchers.Main) {
                debugInfo = info
            }
        }
    }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 标题
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "热点调试信息",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Divider()
                
                // 滚动内容
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    debugInfo?.let { info ->
                        // 系统信息
                        DebugSection("系统信息") {
                            DebugItem("Android版本", "API ${info.androidVersion}")
                            DebugItem("设备制造商", info.deviceManufacturer)
                            DebugItem("设备型号", info.deviceModel)
                        }
                        
                        // 权限状态
                        DebugSection("权限状态") {
                            DebugItem(
                                "修改系统设置",
                                if (info.hasWriteSettingsPermission) "✓ 已授权" else "✗ 未授权",
                                info.hasWriteSettingsPermission
                            )
                            DebugItem(
                                "WiFi状态",
                                if (info.wifiEnabled) "已开启" else "已关闭"
                            )
                            DebugItem(
                                "热点状态",
                                if (info.hotspotEnabled) "✓ 已开启" else "✗ 未开启",
                                info.hotspotEnabled
                            )
                        }
                        
                        // 可用方法
                        DebugSection("可用的热点相关方法") {
                            if (info.availableMethods.isEmpty()) {
                                Text(
                                    text = "未找到相关方法",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                info.availableMethods.forEach { method ->
                                    Text(
                                        text = "• $method",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        
                        // 测试结果
                        testResults?.let { results ->
                            DebugSection("方法测试结果") {
                                results.forEach { (method, success) ->
                                    DebugItem(
                                        method,
                                        if (success) "✓ 成功" else "✗ 失败",
                                        success
                                    )
                                }
                            }
                        }
                        
                        // 操作日志
                        if (info.attemptLog.isNotEmpty()) {
                            DebugSection("操作日志") {
                                info.attemptLog.takeLast(20).forEach { log ->
                                    Text(
                                        text = log,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
                
                Divider()
                
                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                HotspotDebugger.clearLog()
                                val info = HotspotDebugger.getDebugInfo(context)
                                withContext(Dispatchers.Main) {
                                    debugInfo = info
                                    testResults = null
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("刷新", fontSize = 14.sp)
                    }
                    
                    Button(
                        onClick = {
                            isLoading = true
                            scope.launch(Dispatchers.IO) {
                                HotspotDebugger.clearLog()
                                val results = HotspotDebugger.testAllMethods(context, config)
                                val info = HotspotDebugger.getDebugInfo(context)
                                withContext(Dispatchers.Main) {
                                    testResults = results
                                    debugInfo = info
                                    isLoading = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("测试所有方法", fontSize = 14.sp)
                        }
                    }
                    
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("关闭", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun DebugItem(
    label: String,
    value: String,
    isSuccess: Boolean? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = when (isSuccess) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}


