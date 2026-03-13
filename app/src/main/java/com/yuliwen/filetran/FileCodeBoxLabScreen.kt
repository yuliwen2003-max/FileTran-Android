package com.yuliwen.filetran

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.LinkedHashSet

private const val FILE_CODE_BOX_PREFS = "file_code_box_lab"
private const val FILE_CODE_BOX_KEY_WORKSPACE_MODE = "workspace_mode"
private const val FILE_CODE_BOX_KEY_WORKSPACE_TREE_URI = "workspace_tree_uri"
private const val FILE_CODE_BOX_WORKSPACE_APP = 0
private const val FILE_CODE_BOX_WORKSPACE_TREE = 1

private data class ResolvedCabinetWorkspace(
    val workspace: FileCabinetWorkspace,
    val description: String
)

@Composable
fun FileCodeBoxLabScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(FILE_CODE_BOX_PREFS, Context.MODE_PRIVATE)
    }

    var serverMode by rememberSaveable { mutableIntStateOf(0) } // 0: IPv4, 1: IPv6
    var portInput by rememberSaveable { mutableStateOf("12345") }
    var advertiseHost by rememberSaveable { mutableStateOf("") }
    var natStunEnabled by rememberSaveable { mutableStateOf(false) }
    var natKeepAliveEnabled by rememberSaveable { mutableStateOf(false) }
    var natKeepAliveIntervalInput by rememberSaveable { mutableStateOf("25") }
    var workspaceMode by rememberSaveable {
        mutableIntStateOf(prefs.getInt(FILE_CODE_BOX_KEY_WORKSPACE_MODE, FILE_CODE_BOX_WORKSPACE_APP))
    }
    var workspaceTreeUriText by rememberSaveable {
        mutableStateOf(prefs.getString(FILE_CODE_BOX_KEY_WORKSPACE_TREE_URI, "").orEmpty())
    }

    var statusText by remember { mutableStateOf("未创建文件快递柜服务器。") }
    var starting by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }

    var localIpv4List by remember { mutableStateOf<List<String>>(emptyList()) }
    var localIpv6List by remember { mutableStateOf<List<String>>(emptyList()) }

    var server by remember { mutableStateOf<FileCodeBoxServer?>(null) }
    var runningHost by remember { mutableStateOf<String?>(null) }
    var runningPort by remember { mutableStateOf<Int?>(null) }
    var runningLocalUrl by remember { mutableStateOf<String?>(null) }
    var endpointUrl by remember { mutableStateOf<String?>(null) }
    var natStunStatus by remember { mutableStateOf<String?>(null) }
    var runningWorkspaceDescription by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var stunProbeResult by remember { mutableStateOf<StunProbeBatchResult?>(null) }
    var showStunPicker by remember { mutableStateOf(false) }
    var natKeepAliveStatus by remember { mutableStateOf<String?>(null) }

    fun persistWorkspaceSelection(mode: Int, treeUriText: String) {
        prefs.edit()
            .putInt(FILE_CODE_BOX_KEY_WORKSPACE_MODE, mode)
            .putString(FILE_CODE_BOX_KEY_WORKSPACE_TREE_URI, treeUriText)
            .apply()
    }

    val pickTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            statusText = "已取消目录选择。"
            return@rememberLauncherForActivityResult
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }

        val root = DocumentFile.fromTreeUri(context, uri)
        if (root == null || !root.canRead() || !root.canWrite()) {
            statusText = "目录授权失败，请重新选择可读写目录。"
            return@rememberLauncherForActivityResult
        }

        workspaceMode = FILE_CODE_BOX_WORKSPACE_TREE
        workspaceTreeUriText = uri.toString()
        persistWorkspaceSelection(workspaceMode, workspaceTreeUriText)
        statusText = "工作目录已切换到：${root.name ?: uri}"
    }

    fun stopServer(reason: String) {
        runCatching { server?.stop() }
        server = null
        runningHost = null
        runningPort = null
        runningLocalUrl = null
        endpointUrl = null
        natStunStatus = null
        qrBitmap = null
        runningWorkspaceDescription = null
        stunProbeResult = null
        showStunPicker = false
        natKeepAliveStatus = null
        starting = false
        resetting = false
        statusText = reason
    }

    fun applyMappedEndpoint(mapped: StunMappedEndpoint?, batch: StunProbeBatchResult?, localHost: String, port: Int) {
        stunProbeResult = batch?.takeIf { it.endpoints.isNotEmpty() }
        val publicHost = mapped?.address?.trim()?.takeIf { it.isNotBlank() } ?: localHost
        val publicPort = mapped?.port?.takeIf { it in 1..65535 } ?: port
        val publicFormattedHost = NetworkUtils.formatHostForUrl(publicHost)
        val publicUrl = "http://$publicFormattedHost:$publicPort/"
        runningHost = publicHost
        runningPort = publicPort
        endpointUrl = publicUrl
        qrBitmap = QRCodeGenerator.generateQRCode(publicUrl, 520)
        natStunStatus = when {
            !natStunEnabled -> "未开启 NAT STUN，当前使用本地地址。"
            mapped != null && (batch?.allMismatch == true) -> {
                showStunPicker = true
                "已获取 STUN 映射，但均与 ipip 不一致，请手动选择地址。"
            }
            mapped != null && (batch?.matchedByIpip?.isNotEmpty() == true) -> {
                "已获取 STUN 映射（存在 ipip 匹配项，默认使用首个 STUN 结果）：${mapped.address}:${mapped.port}"
            }
            mapped != null -> "已通过 STUN 获取外网映射：${mapped.address}:${mapped.port}（${mapped.stunServer}）"
            else -> "未获取到 STUN 映射，已回退为局域网地址。"
        }
    }

    fun refreshLocalCandidates(fillHost: Boolean = false) {
        localIpv4List = collectCabinetLocalIpv4Candidates(context)
        localIpv6List = collectCabinetLocalIpv6Candidates()
        val currentCandidates = if (serverMode == 0) localIpv4List else localIpv6List
        if (fillHost || advertiseHost.isBlank()) {
            advertiseHost = currentCandidates.firstOrNull().orEmpty()
        }
        statusText = if (currentCandidates.isEmpty()) {
            if (serverMode == 0) {
                "未检测到可用 IPv4 地址，请手动填写。"
            } else {
                "未检测到可用 IPv6 地址，请手动填写。"
            }
        } else {
            if (serverMode == 0) {
                "已刷新本机 IPv4 地址。"
            } else {
                "已刷新本机 IPv6 地址。"
            }
        }
    }

    fun resolveWorkspaceForRun(): ResolvedCabinetWorkspace? {
        if (workspaceMode == FILE_CODE_BOX_WORKSPACE_APP) {
            val appDir = defaultFileCodeBoxDir(context)
            return ResolvedCabinetWorkspace(
                workspace = DirectoryFileCabinetWorkspace(appDir),
                description = appDir.absolutePath
            )
        }

        val treeUri = workspaceTreeUriText.takeIf { it.isNotBlank() }?.let { Uri.parse(it) } ?: return null
        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null || !root.canRead() || !root.canWrite()) return null

        return ResolvedCabinetWorkspace(
            workspace = SafTreeFileCabinetWorkspace(context, treeUri),
            description = "${root.name ?: "SAF目录"} ($treeUri)"
        )
    }

    fun startServer() {
        if (starting || resetting) return
        val port = portInput.trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            statusText = "端口无效（1~65535）。"
            return
        }

        val workspace = resolveWorkspaceForRun()
        if (workspace == null) {
            statusText = "工作目录不可用，请重新选择目录或切换到应用私有目录。"
            return
        }

        val manualHost = advertiseHost.trim()
        runCatching { server?.stop() }
        server = null
        starting = true
        statusText = if (serverMode == 0) {
            "正在启动 IPv4 文件快递柜..."
        } else {
            "正在启动 IPv6 文件快递柜..."
        }

        scope.launch {
            val fallbackHost = withContext(Dispatchers.IO) {
                val candidates = if (serverMode == 0) {
                    collectCabinetLocalIpv4Candidates(context)
                } else {
                    collectCabinetLocalIpv6Candidates()
                }
                candidates.firstOrNull().orEmpty()
            }
            val localHost = if (manualHost.isNotBlank()) manualHost else fallbackHost
            if (localHost.isBlank()) {
                starting = false
                statusText = if (serverMode == 0) {
                    "未获取到 IPv4 地址，无法启动。"
                } else {
                    "未获取到 IPv6 地址，无法启动。"
                }
                return@launch
            }

            val batch = if (natStunEnabled) {
                withContext(Dispatchers.IO) {
                    NetworkUtils.probeStunMappedEndpointBatch(
                        localPort = port,
                        preferIpv6 = serverMode == 1,
                        transport = StunTransportType.TCP
                    )
                }
            } else {
                null
            }
            val mapped = batch?.preferredEndpoint

            val localFormattedHost = NetworkUtils.formatHostForUrl(localHost)
            val localUrl = "http://$localFormattedHost:$port/"

            val newServer = FileCodeBoxServer(context, port, workspace.workspace)
            val startResult = withContext(Dispatchers.IO) {
                runCatching { newServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            }

            startResult.onSuccess {
                server = newServer
                runningLocalUrl = localUrl
                runningWorkspaceDescription = workspace.description
                applyMappedEndpoint(mapped, batch, localHost, port)
                natKeepAliveStatus = null
                statusText = if (serverMode == 0) {
                    "文件快递柜已启动（IPv4）。"
                } else {
                    "文件快递柜已启动（IPv6）。"
                }
            }.onFailure { e ->
                runCatching { newServer.stop() }
                natStunStatus = null
                statusText = "启动失败: ${e.message ?: "unknown"}"
            }
            starting = false
        }
    }

    fun resetServerData() {
        val runningServer = server
        if (runningServer == null) {
            statusText = "服务器未运行，无法重置。"
            return
        }
        if (starting || resetting) return

        resetting = true
        statusText = "正在重置服务器数据..."

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { runningServer.resetAllData() }
            }
            result.onSuccess { deletedCount ->
                statusText = "重置完成：提取码/文本已清空，已删除 $deletedCount 个文件。"
            }.onFailure { error ->
                statusText = "重置失败: ${error.message ?: "unknown"}"
            }
            resetting = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopServer("页面退出，文件快递柜已停止。")
        }
    }

    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(serverMode) {
        refreshLocalCandidates(fillHost = true)
    }
    LaunchedEffect(server, natStunEnabled, natKeepAliveEnabled, natKeepAliveIntervalInput, portInput, serverMode, advertiseHost) {
        if (server == null || !natStunEnabled || !natKeepAliveEnabled) {
            if (!natKeepAliveEnabled) natKeepAliveStatus = null
            return@LaunchedEffect
        }
        val port = portInput.trim().toIntOrNull()
        val intervalSeconds = natKeepAliveIntervalInput.trim().toLongOrNull()
        val localHost = advertiseHost.trim().ifBlank {
            val candidates = if (serverMode == 0) localIpv4List else localIpv6List
            candidates.firstOrNull().orEmpty()
        }
        if (port == null || port !in 1..65535 || intervalSeconds == null || intervalSeconds < 5L || localHost.isBlank()) {
            natKeepAliveStatus = "状态保持未启动，请检查端口、地址和间隔。"
            return@LaunchedEffect
        }
        while (isActive && server != null && natStunEnabled && natKeepAliveEnabled) {
            val batch = withContext(Dispatchers.IO) {
                NetworkUtils.probeStunMappedEndpointBatch(
                    localPort = port,
                    preferIpv6 = serverMode == 1,
                    transport = StunTransportType.TCP
                )
            }
            val mapped = batch.preferredEndpoint
            if (mapped != null) {
                applyMappedEndpoint(mapped, batch, localHost, port)
                natKeepAliveStatus = "状态保持已发送，最近映射：${mapped.address}:${mapped.port}"
            } else {
                natKeepAliveStatus = "状态保持发送失败，未获取到新的映射。"
            }
            delay(intervalSeconds * 1000L)
        }
    }

    StunEndpointPickerDialog(
        visible = showStunPicker,
        title = "选择 FileCodeBox STUN 地址",
        result = stunProbeResult,
        selected = stunProbeResult
            ?.endpoints
            ?.firstOrNull { it.address == runningHost && it.port == runningPort },
        onSelect = { chosen ->
            val chosenHost = chosen.address.trim()
            if (chosenHost.isBlank() || chosen.port !in 1..65535) return@StunEndpointPickerDialog
            applyMappedEndpoint(chosen, stunProbeResult, advertiseHost.ifBlank { chosenHost }, runningPort ?: chosen.port)
            natStunStatus = "已手动选择 STUN 映射：$chosenHost:${chosen.port}（${chosen.stunServer}）"
            statusText = "已切换展示地址为：$chosenHost:${chosen.port}"
        },
        onDismiss = { showStunPicker = false }
    )

    val currentCandidates = if (serverMode == 0) localIpv4List else localIpv6List
    val appWorkspaceDir = remember { defaultFileCodeBoxDir(context).absolutePath }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回传输实验室")
        }

        Text("文件快递柜（FileCodeBox）", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "小型文件服务器：支持上传、列表、下载；支持 IPv4/IPv6 直连与 STUN 外网探测。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("访问模式", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (serverMode == 0) {
                        Button(onClick = {}, modifier = Modifier.weight(1f), enabled = !starting) { Text("IPv4") }
                    } else {
                        OutlinedButton(
                            onClick = {
                                serverMode = 0
                                refreshLocalCandidates(fillHost = true)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !starting
                        ) { Text("IPv4") }
                    }
                    if (serverMode == 1) {
                        Button(onClick = {}, modifier = Modifier.weight(1f), enabled = !starting) { Text("IPv6") }
                    } else {
                        OutlinedButton(
                            onClick = {
                                serverMode = 1
                                refreshLocalCandidates(fillHost = true)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !starting
                        ) { Text("IPv6") }
                    }
                }

                OutlinedTextField(
                    value = portInput,
                    onValueChange = { raw -> portInput = raw.filter { it.isDigit() } },
                    label = { Text("监听端口") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !starting
                )
                OutlinedTextField(
                    value = advertiseHost,
                    onValueChange = { advertiseHost = it.trim() },
                    label = {
                        Text(
                            if (serverMode == 0) {
                                "展示 IPv4（二维码使用此地址）"
                            } else {
                                "展示 IPv6（二维码使用此地址）"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !starting
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("NAT STUN 外网探测")
                    Switch(
                        checked = natStunEnabled,
                        onCheckedChange = { natStunEnabled = it },
                        enabled = !starting
                    )
                }
                Text(
                    "开启后会尝试探测公网映射的 IP + 端口，并用于二维码展示。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (natStunEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("状态保持")
                        Switch(
                            checked = natKeepAliveEnabled,
                            onCheckedChange = { natKeepAliveEnabled = it },
                            enabled = !starting && server != null
                        )
                    }
                    OutlinedTextField(
                        value = natKeepAliveIntervalInput,
                        onValueChange = { natKeepAliveIntervalInput = it.filter(Char::isDigit) },
                        label = { Text("状态保持间隔（秒）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !starting && server != null
                    )
                    natKeepAliveStatus?.let {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (stunProbeResult?.endpoints?.isNotEmpty() == true) {
                    OutlinedButton(
                        onClick = { showStunPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !starting
                    ) {
                        Text("选择 STUN 地址")
                    }
                }

                OutlinedButton(
                    onClick = { refreshLocalCandidates(fillHost = true) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !starting
                ) {
                    Text(if (serverMode == 0) "刷新本机 IPv4" else "刷新本机 IPv6")
                }

                if (currentCandidates.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        currentCandidates.take(6).forEach { host ->
                            if (host == advertiseHost) {
                                Button(onClick = { advertiseHost = host }, enabled = !starting) { Text(host) }
                            } else {
                                OutlinedButton(onClick = { advertiseHost = host }, enabled = !starting) { Text(host) }
                            }
                        }
                    }
                }

                Text("工作目录", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (workspaceMode == FILE_CODE_BOX_WORKSPACE_APP) {
                        Button(onClick = {}, modifier = Modifier.weight(1f), enabled = !starting) { Text("应用私有目录") }
                    } else {
                        OutlinedButton(
                            onClick = {
                                workspaceMode = FILE_CODE_BOX_WORKSPACE_APP
                                persistWorkspaceSelection(workspaceMode, workspaceTreeUriText)
                                statusText = "已切换到应用私有目录。"
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !starting
                        ) { Text("应用私有目录") }
                    }

                    if (workspaceMode == FILE_CODE_BOX_WORKSPACE_TREE) {
                        Button(onClick = {}, modifier = Modifier.weight(1f), enabled = !starting) { Text("自定义目录(SAF)") }
                    } else {
                        OutlinedButton(
                            onClick = {
                                workspaceMode = FILE_CODE_BOX_WORKSPACE_TREE
                                persistWorkspaceSelection(workspaceMode, workspaceTreeUriText)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !starting
                        ) { Text("自定义目录(SAF)") }
                    }
                }

                if (workspaceMode == FILE_CODE_BOX_WORKSPACE_APP) {
                    Text(
                        "当前目录：$appWorkspaceDir",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "推荐：兼容性最高，无需额外授权。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedButton(
                        onClick = {
                            val initialUri = workspaceTreeUriText.takeIf { it.isNotBlank() }?.let(Uri::parse)
                            pickTreeLauncher.launch(initialUri)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !starting
                    ) {
                        Text(if (workspaceTreeUriText.isBlank()) "选择工作目录" else "重新选择工作目录")
                    }
                    Text(
                        if (workspaceTreeUriText.isBlank()) {
                            "尚未选择 SAF 目录。"
                        } else {
                            "已选择：$workspaceTreeUriText"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { startServer() },
                        modifier = Modifier.weight(1f),
                        enabled = !starting && !resetting
                    ) {
                        Text(
                            if (starting) {
                                "启动中..."
                            } else if (resetting) {
                                "重置中..."
                            } else if (server == null) {
                                "创建服务器"
                            } else {
                                "重启服务器"
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = { stopServer("文件快递柜已停止。") },
                        modifier = Modifier.weight(1f),
                        enabled = server != null && !starting && !resetting
                    ) {
                        Text("停止服务器")
                    }
                }

                OutlinedButton(
                    onClick = { resetServerData() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = server != null && !starting && !resetting
                ) {
                    Text(
                        if (resetting) {
                            "重置中..."
                        } else {
                            "重置服务器数据（清空提取码/文本/文件）"
                        }
                    )
                }

                OutlinedButton(
                    onClick = { openCabinetInBrowser(context, "https://github.com/vastsa/FileCodeBox") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !starting
                ) {
                    Text("打开 FileCodeBox 项目地址")
                }
            }
        }

        Text("状态: $statusText", color = MaterialTheme.colorScheme.primary)

        if (endpointUrl != null && runningHost != null && runningPort != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("服务器信息", fontWeight = FontWeight.SemiBold)
                    Text("外部 IP: $runningHost")
                    Text("外部端口: $runningPort")
                    runningLocalUrl?.let { Text("本机监听: $it") }
                    Text("访问地址: $endpointUrl")
                    runningWorkspaceDescription?.let { Text("工作目录: $it") }
                    natStunStatus?.let {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { endpointUrl?.let { copyCabinetToClipboard(context, it) } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("复制地址")
                        }
                        OutlinedButton(
                            onClick = { endpointUrl?.let { openCabinetInBrowser(context, it) } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("浏览器打开")
                        }
                    }
                    qrBitmap?.let { bitmap ->
                        Spacer(Modifier.height(4.dp))
                        Text("扫码访问", fontWeight = FontWeight.SemiBold)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "file_code_box_qr",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun defaultFileCodeBoxDir(context: Context): File {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    return File(base, "FileCodeBoxWorkspace").apply { mkdirs() }
}

private fun collectCabinetLocalIpv4Candidates(context: Context): List<String> {
    val out = LinkedHashSet<String>()
    NetworkUtils.getLocalIpAddress(context)?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
        while (interfaces.hasMoreElements()) {
            val itf = interfaces.nextElement()
            if (!itf.isUp || itf.isLoopback) continue
            val addresses = itf.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    out += address.hostAddress.orEmpty()
                }
            }
        }
    }
    return out.filter { it.isNotBlank() }
}

private fun collectCabinetLocalIpv6Candidates(): List<String> {
    val out = LinkedHashSet<String>()
    NetworkUtils.getLocalGlobalIpv6Address()?.trim()?.substringBefore('%')?.takeIf { it.isNotBlank() }?.let(out::add)
    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
        while (interfaces.hasMoreElements()) {
            val itf = interfaces.nextElement()
            if (!itf.isUp || itf.isLoopback) continue
            val addresses = itf.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet6Address && !address.isLoopbackAddress) {
                    val ip = address.hostAddress.orEmpty().substringBefore('%')
                    if (ip.isBlank() || ip.equals("::1", ignoreCase = true)) continue
                    if (ip.startsWith("fe80", ignoreCase = true)) continue
                    out += ip
                }
            }
        }
    }
    return out.filter { it.isNotBlank() }
}

private fun copyCabinetToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("file_code_box_url", text))
}

private fun openCabinetInBrowser(context: Context, url: String) {
    val target = runCatching { Uri.parse(url) }.getOrNull() ?: return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, target).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
