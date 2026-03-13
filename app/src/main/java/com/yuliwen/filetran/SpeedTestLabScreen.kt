package com.yuliwen.filetran

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

const val SPEED_TEST_PREFS = "speed_test_lab"
const val SPEED_TEST_KEY_CONFIG = "speed_test_config_json"
const val SPEED_TEST_KEY_PRESETS = "speed_test_presets_json"
const val SPEED_TEST_KEY_SELECTED_PRESET = "speed_test_selected_preset"
const val SPEED_TEST_KEY_DEFAULT_APP_ENTRY = "speed_test_default_app_entry"
const val SPEED_TEST_KEY_BACKGROUND_TEST = "speed_test_background_test"
const val SPEED_TEST_KEY_SUPER_KEEP_ALIVE = "speed_test_super_keep_alive"
const val SPEED_TEST_KEY_STEALTH_MODE = "speed_test_stealth_mode"
private const val SPEED_TEST_MAX_DOWNLOAD_ENDPOINTS = 5
private const val SPEED_TEST_MAX_UPLOAD_ENDPOINTS = 5
private const val SPEED_TEST_MAX_THREADS = 64
private const val SPEED_TEST_MAX_EVENTS = 240
private const val SPEED_TEST_UPLOAD_REQUEST_BYTES = 8L * 1024L * 1024L
private const val SPEED_TEST_IO_BUFFER_BYTES = 64 * 1024
private const val SPEED_TEST_MB = 1024L * 1024L
private const val SPEED_TEST_FLOATING_LIMIT_DEFAULT_SECONDS = 600
private const val SPEED_TEST_FLOATING_LIMIT_DEFAULT_PERCENT = 80
private const val SPEED_TEST_FLOATING_PROBE_SECONDS = 10
private const val SPEED_TEST_ALTERNATING_DEFAULT_WINDOW_MS = 1000
private const val SPEED_TEST_ALTERNATING_MIN_WINDOW_MS = 50
private const val SPEED_TEST_ALTERNATING_DEFAULT_DOWNLOAD_SECONDS = 2
private const val SPEED_TEST_ALTERNATING_DEFAULT_UPLOAD_SECONDS = 1
private const val SPEED_TEST_ALTERNATING_RATIO_IDLE_LIMIT_DEFAULT_MBPS = 0.1
private const val SPEED_TEST_ALTERNATING_FIXED_IDLE_LIMIT_DEFAULT_MBPS = 0.5
private const val SPEED_TEST_SOFT_LIMIT_MIN_CHUNK_BYTES = 1024
private const val SPEED_TEST_MAX_ACTIVE_WORKERS_PER_DIRECTION = 128

private enum class SpeedTestDirection(val label: String) {
    DOWNLOAD("下载"),
    UPLOAD("上传")
}

private data class SpeedTestEndpointConfig(
    val url: String = "",
    val threads: Int = 1
)

private data class SpeedTestEndpointDraft(
    val url: String = "",
    val threadsText: String = "1"
)

private enum class SpeedLimitMode(val label: String) {
    OFF("不限速"),
    FIXED("固定"),
    FLOATING("浮动")
}

private data class SpeedLimitConfig(
    val mode: SpeedLimitMode = SpeedLimitMode.OFF,
    val fixedLimitBytesPerSecond: Long? = null,
    val floatingIntervalSeconds: Int = SPEED_TEST_FLOATING_LIMIT_DEFAULT_SECONDS,
    val floatingPercent: Int = SPEED_TEST_FLOATING_LIMIT_DEFAULT_PERCENT
)

private data class SpeedTestStopConfig(
    val durationSeconds: Int? = null,
    val dataLimitBytes: Long? = null,
    val autoSwitchEndpoints: Boolean = false
)

private enum class StopDurationUnit(val label: String, val seconds: Long) {
    SECOND("秒", 1L),
    MINUTE("分", 60L),
    HOUR("时", 3600L),
    DAY("天", 86_400L)
}

private enum class StopTrafficUnit(val label: String, val bytes: Long) {
    MB("MB", SPEED_TEST_MB),
    GB("GB", SPEED_TEST_MB * 1024L),
    TB("TB", SPEED_TEST_MB * 1024L * 1024L)
}

private enum class AlternatingTransferMode(val label: String) {
    RATIO("比例模式"),
    FIXED("固定模式")
}

private enum class AlternatingIdleStrategy(val label: String) {
    CUT_OFF("完全切断"),
    SOFT_LIMIT("速度压制")
}

private data class AlternatingTransferConfig(
    val enabled: Boolean = false,
    val mode: AlternatingTransferMode = AlternatingTransferMode.RATIO,
    val uploadPercent: Int = 50,
    val windowMs: Int = SPEED_TEST_ALTERNATING_DEFAULT_WINDOW_MS,
    val fixedDownloadSeconds: Int = SPEED_TEST_ALTERNATING_DEFAULT_DOWNLOAD_SECONDS,
    val fixedUploadSeconds: Int = SPEED_TEST_ALTERNATING_DEFAULT_UPLOAD_SECONDS,
    val ratioIdleLimitBytesPerSecond: Long = mbpsToBytesPerSecond(
        SPEED_TEST_ALTERNATING_RATIO_IDLE_LIMIT_DEFAULT_MBPS
    ),
    val fixedIdleStrategy: AlternatingIdleStrategy = AlternatingIdleStrategy.SOFT_LIMIT,
    val fixedIdleLimitBytesPerSecond: Long = mbpsToBytesPerSecond(
        SPEED_TEST_ALTERNATING_FIXED_IDLE_LIMIT_DEFAULT_MBPS
    )
)

private data class SpeedTestSessionStats(
    val visible: Boolean = false,
    val running: Boolean = false,
    val realtimeSpeedBytesPerSecond: Double = 0.0,
    val averageSpeedBytesPerSecond: Double = 0.0,
    val usedBytes: Long = 0L,
    val elapsedMs: Long = 0L
)

private data class SpeedTestChannelRate(
    val networkLabel: String,
    val realtimeSpeedBytesPerSecond: Double = 0.0,
    val averageSpeedBytesPerSecond: Double = 0.0,
    val usedBytes: Long = 0L
)

private data class SpeedTestConfig(
    val downloadEndpoints: List<SpeedTestEndpointConfig>,
    val uploadEndpoints: List<SpeedTestEndpointConfig>,
    val downloadStopConfig: SpeedTestStopConfig,
    val uploadStopConfig: SpeedTestStopConfig,
    val downloadSpeedLimitConfig: SpeedLimitConfig,
    val uploadSpeedLimitConfig: SpeedLimitConfig,
    val alternatingTransferConfig: AlternatingTransferConfig,
    val dualChannelEnabled: Boolean,
    val logEnabled: Boolean
)

private data class SpeedTestNamedConfig(
    val name: String,
    val config: SpeedTestConfig
)

private data class SpeedTestThreadSnapshot(
    val id: String,
    val direction: SpeedTestDirection,
    val endpointIndex: Int,
    val threadIndex: Int,
    val networkLabel: String,
    val url: String,
    val state: String,
    val bytesTransferred: Long = 0L,
    val responseCode: Int? = null,
    val errorCode: String? = null,
    val errorReason: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
)

private data class SpeedTestLogEvent(
    val direction: SpeedTestDirection,
    val workerLabel: String,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis()
)

private data class SpeedTestWorkerSpec(
    val endpointIndex: Int,
    val threadIndex: Int,
    val url: String,
    val networkKey: String,
    val networkLabel: String
) {
    val id: String = "${endpointIndex}_${threadIndex}_$networkKey"
    val label: String = "地址${endpointIndex + 1} / 线程$threadIndex / $networkLabel"
}

private data class SpeedTestCallbacks(
    val onRunningChanged: (Boolean) -> Unit,
    val onStatusChanged: (String) -> Unit,
    val onStatsChanged: (SpeedTestSessionStats) -> Unit,
    val onChannelRatesChanged: (List<SpeedTestChannelRate>) -> Unit,
    val onActiveEndpointsChanged: (List<SpeedTestEndpointConfig>) -> Unit,
    val onResetDirectionLogs: () -> Unit,
    val onThreadSnapshotChanged: (SpeedTestThreadSnapshot) -> Unit,
    val onLogEvent: (SpeedTestLogEvent) -> Unit
)

private class SharedSpeedLimiter {
    private val lock = Object()
    private var limitBytesPerSecond: Double? = null
    private var availableBytes = 0.0
    private var lastRefillNs = System.nanoTime()

    fun setLimit(limitBytesPerSecond: Double?) {
        synchronized(lock) {
            refillLocked()
            val previousLimit = this.limitBytesPerSecond
            this.limitBytesPerSecond = limitBytesPerSecond?.takeIf { it > 0.0 }
            availableBytes = if (this.limitBytesPerSecond == null) {
                0.0
            } else if (previousLimit == null) {
                this.limitBytesPerSecond ?: 0.0
            } else {
                minOf(
                    availableBytes,
                    this.limitBytesPerSecond ?: 0.0
                ).coerceAtLeast(0.0)
            }
            lastRefillNs = System.nanoTime()
            lock.notifyAll()
        }
    }

    fun acquire(bytes: Int, shouldContinue: () -> Boolean) {
        if (bytes <= 0) return
        while (shouldContinue()) {
            synchronized(lock) {
                refillLocked()
                val limit = limitBytesPerSecond ?: return
                val requiredBytes = bytes.toDouble()
                if (availableBytes >= requiredBytes) {
                    availableBytes -= requiredBytes
                    return
                }
                val missingBytes = (requiredBytes - availableBytes).coerceAtLeast(0.0)
                val waitMs = ceil(missingBytes / limit * 1000.0).toLong().coerceAtLeast(1L)
                try {
                    lock.wait(waitMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private fun refillLocked() {
        val limit = limitBytesPerSecond ?: return
        val nowNs = System.nanoTime()
        val elapsedNs = (nowNs - lastRefillNs).coerceAtLeast(0L)
        if (elapsedNs > 0L) {
            availableBytes = minOf(
                limit,
                availableBytes + elapsedNs.toDouble() / 1_000_000_000.0 * limit
            )
            lastRefillNs = nowNs
        }
    }
}

private class AlternatingTransferGate {
    private enum class CoordinationMode {
        NONE,
        HARD_GATE,
        SHARED_BUDGET
    }

    private data class SharedBudgetConfig(
        val windowMs: Long,
        val totalBudgetBytesPerWindow: Long,
        val downloadPercent: Int,
        val uploadPercent: Int,
        val idleLimitBytesPerSecond: Double?
    )

    private val lock = Object()
    private var coordinationMode = CoordinationMode.NONE
    private var enabled = false
    private var allowDownload = true
    private var allowUpload = true
    private var downloadSoftLimitBytesPerSecond: Double? = null
    private var uploadSoftLimitBytesPerSecond: Double? = null
    private val downloadSoftLimiter = SharedSpeedLimiter()
    private val uploadSoftLimiter = SharedSpeedLimiter()
    private val downloadBudgetIdleLimiter = SharedSpeedLimiter()
    private val uploadBudgetIdleLimiter = SharedSpeedLimiter()
    private var sharedBudgetConfig: SharedBudgetConfig? = null
    private var currentWindowStartedAtMs = 0L
    private var downloadBudgetBytes = 0L
    private var uploadBudgetBytes = 0L
    private var downloadUsedBytes = 0L
    private var uploadUsedBytes = 0L

    fun update(enabled: Boolean, allowDownload: Boolean, allowUpload: Boolean) {
        downloadBudgetIdleLimiter.setLimit(null)
        uploadBudgetIdleLimiter.setLimit(null)
        synchronized(lock) {
            coordinationMode = if (enabled) {
                CoordinationMode.HARD_GATE
            } else {
                CoordinationMode.NONE
            }
            this.enabled = enabled
            this.allowDownload = allowDownload
            this.allowUpload = allowUpload
            clearSharedBudgetLocked()
            lock.notifyAll()
        }
    }

    fun setSoftLimits(downloadLimitBytesPerSecond: Double?, uploadLimitBytesPerSecond: Double?) {
        val normalizedDownloadLimit = downloadLimitBytesPerSecond?.takeIf { it > 0.0 }
        val normalizedUploadLimit = uploadLimitBytesPerSecond?.takeIf { it > 0.0 }
        var shouldUpdateDownload = false
        var shouldUpdateUpload = false
        synchronized(lock) {
            if (downloadSoftLimitBytesPerSecond != normalizedDownloadLimit) {
                downloadSoftLimitBytesPerSecond = normalizedDownloadLimit
                shouldUpdateDownload = true
            }
            if (uploadSoftLimitBytesPerSecond != normalizedUploadLimit) {
                uploadSoftLimitBytesPerSecond = normalizedUploadLimit
                shouldUpdateUpload = true
            }
        }
        if (shouldUpdateDownload) {
            downloadSoftLimiter.setLimit(normalizedDownloadLimit)
        }
        if (shouldUpdateUpload) {
            uploadSoftLimiter.setLimit(normalizedUploadLimit)
        }
    }

    fun configureSharedBudget(
        windowMs: Int,
        totalBudgetBytesPerWindow: Long,
        downloadPercent: Int,
        uploadPercent: Int,
        idleLimitBytesPerSecond: Double?
    ) {
        val config = SharedBudgetConfig(
            windowMs = windowMs.toLong().coerceAtLeast(1L),
            totalBudgetBytesPerWindow = totalBudgetBytesPerWindow.coerceAtLeast(1L),
            downloadPercent = downloadPercent.coerceIn(0, 100),
            uploadPercent = uploadPercent.coerceIn(0, 100),
            idleLimitBytesPerSecond = idleLimitBytesPerSecond?.takeIf { it > 0.0 }
        )
        setSoftLimits(downloadLimitBytesPerSecond = null, uploadLimitBytesPerSecond = null)
        downloadBudgetIdleLimiter.setLimit(config.idleLimitBytesPerSecond)
        uploadBudgetIdleLimiter.setLimit(config.idleLimitBytesPerSecond)
        synchronized(lock) {
            coordinationMode = CoordinationMode.SHARED_BUDGET
            enabled = false
            allowDownload = true
            allowUpload = true
            sharedBudgetConfig = config
            refreshSharedBudgetWindowLocked(System.currentTimeMillis())
            lock.notifyAll()
        }
    }

    fun reset() {
        downloadBudgetIdleLimiter.setLimit(null)
        uploadBudgetIdleLimiter.setLimit(null)
        setSoftLimits(downloadLimitBytesPerSecond = null, uploadLimitBytesPerSecond = null)
        synchronized(lock) {
            coordinationMode = CoordinationMode.NONE
            enabled = false
            allowDownload = true
            allowUpload = true
            clearSharedBudgetLocked()
            lock.notifyAll()
        }
    }

    fun awaitTurn(direction: SpeedTestDirection, shouldContinue: () -> Boolean) {
        while (shouldContinue()) {
            synchronized(lock) {
                if (coordinationMode != CoordinationMode.HARD_GATE || !enabled || isAllowed(direction)) {
                    return
                }
                try {
                    lock.wait(100L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    fun acquire(direction: SpeedTestDirection, bytes: Int, shouldContinue: () -> Boolean): Int {
        val requestedBytes = bytes.coerceAtLeast(1)
        return when (direction) {
            SpeedTestDirection.DOWNLOAD -> acquireInternal(direction, requestedBytes, shouldContinue, downloadSoftLimiter, downloadBudgetIdleLimiter)
            SpeedTestDirection.UPLOAD -> acquireInternal(direction, requestedBytes, shouldContinue, uploadSoftLimiter, uploadBudgetIdleLimiter)
        }
    }

    fun refund(direction: SpeedTestDirection, bytes: Int) {
        if (bytes <= 0) return
        synchronized(lock) {
            if (coordinationMode != CoordinationMode.SHARED_BUDGET) return
            when (direction) {
                SpeedTestDirection.DOWNLOAD -> {
                    downloadUsedBytes = (downloadUsedBytes - bytes.toLong()).coerceAtLeast(0L)
                }
                SpeedTestDirection.UPLOAD -> {
                    uploadUsedBytes = (uploadUsedBytes - bytes.toLong()).coerceAtLeast(0L)
                }
            }
            lock.notifyAll()
        }
    }

    fun recommendedChunkSize(direction: SpeedTestDirection, defaultSize: Int): Int {
        val normalizedDefaultSize = defaultSize.coerceAtLeast(1)
        synchronized(lock) {
            refreshSharedBudgetWindowLocked(System.currentTimeMillis())
            if (coordinationMode == CoordinationMode.SHARED_BUDGET) {
                val remainingBudget = remainingBudgetLocked(direction)
                if (remainingBudget > 0L) {
                    return minOf(
                        normalizedDefaultSize,
                        remainingBudget.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                    )
                }
                val idleLimit = sharedBudgetConfig?.idleLimitBytesPerSecond
                if (idleLimit != null) {
                    return suggestedChunkSizeForLimit(idleLimit, normalizedDefaultSize)
                }
            }
            val softLimit = when (direction) {
                SpeedTestDirection.DOWNLOAD -> downloadSoftLimitBytesPerSecond
                SpeedTestDirection.UPLOAD -> uploadSoftLimitBytesPerSecond
            } ?: return normalizedDefaultSize
            return suggestedChunkSizeForLimit(softLimit, normalizedDefaultSize)
        }
    }

    private fun isAllowed(direction: SpeedTestDirection): Boolean {
        return when (direction) {
            SpeedTestDirection.DOWNLOAD -> allowDownload
            SpeedTestDirection.UPLOAD -> allowUpload
        }
    }

    private fun acquireInternal(
        direction: SpeedTestDirection,
        requestedBytes: Int,
        shouldContinue: () -> Boolean,
        softLimiter: SharedSpeedLimiter,
        budgetIdleLimiter: SharedSpeedLimiter
    ): Int {
        while (shouldContinue()) {
            var shouldUseBudgetIdleLimiter = false
            synchronized(lock) {
                refreshSharedBudgetWindowLocked(System.currentTimeMillis())
                if (coordinationMode == CoordinationMode.SHARED_BUDGET) {
                    val remainingBudget = remainingBudgetLocked(direction)
                    if (remainingBudget > 0L) {
                        val grantedBytes = minOf(
                            requestedBytes.toLong(),
                            remainingBudget
                        ).coerceAtLeast(1L)
                        consumeBudgetLocked(direction, grantedBytes)
                        return grantedBytes.toInt()
                    }
                    if (remainingBudget <= 0L) {
                        if (sharedBudgetConfig?.idleLimitBytesPerSecond != null) {
                            shouldUseBudgetIdleLimiter = true
                        } else {
                            val waitMs = remainingWindowMsLocked(System.currentTimeMillis())
                            try {
                                lock.wait(waitMs.coerceAtLeast(1L))
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return 0
                            }
                        }
                    } else {
                        try {
                            lock.wait(10L)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return 0
                        }
                    }
                } else {
                    softLimiter.acquire(requestedBytes, shouldContinue)
                    return requestedBytes
                }
            }
            if (shouldUseBudgetIdleLimiter) {
                budgetIdleLimiter.acquire(requestedBytes, shouldContinue)
                return requestedBytes
            }
        }
        return 0
    }

    private fun refreshSharedBudgetWindowLocked(nowMs: Long) {
        if (coordinationMode != CoordinationMode.SHARED_BUDGET) return
        val config = sharedBudgetConfig ?: return
        if (currentWindowStartedAtMs == 0L || nowMs - currentWindowStartedAtMs >= config.windowMs) {
            currentWindowStartedAtMs = nowMs
            val totalBudget = config.totalBudgetBytesPerWindow.coerceAtLeast(1L)
            var nextDownloadBudget = (totalBudget * config.downloadPercent.toLong()) / 100L
            if (config.downloadPercent > 0 && nextDownloadBudget <= 0L) {
                nextDownloadBudget = 1L
            }
            nextDownloadBudget = nextDownloadBudget.coerceIn(0L, totalBudget)
            val nextUploadBudget = (totalBudget - nextDownloadBudget).coerceAtLeast(0L)
            downloadBudgetBytes = nextDownloadBudget
            uploadBudgetBytes = nextUploadBudget
            downloadUsedBytes = 0L
            uploadUsedBytes = 0L
            lock.notifyAll()
        }
    }

    private fun remainingBudgetLocked(direction: SpeedTestDirection): Long {
        return when (direction) {
            SpeedTestDirection.DOWNLOAD -> (downloadBudgetBytes - downloadUsedBytes).coerceAtLeast(0L)
            SpeedTestDirection.UPLOAD -> (uploadBudgetBytes - uploadUsedBytes).coerceAtLeast(0L)
        }
    }

    private fun consumeBudgetLocked(direction: SpeedTestDirection, bytes: Long) {
        when (direction) {
            SpeedTestDirection.DOWNLOAD -> downloadUsedBytes += bytes
            SpeedTestDirection.UPLOAD -> uploadUsedBytes += bytes
        }
    }

    private fun remainingWindowMsLocked(nowMs: Long): Long {
        val config = sharedBudgetConfig ?: return 10L
        if (currentWindowStartedAtMs == 0L) return 10L
        val elapsedMs = (nowMs - currentWindowStartedAtMs).coerceAtLeast(0L)
        return (config.windowMs - elapsedMs).coerceAtLeast(1L)
    }

    private fun clearSharedBudgetLocked() {
        sharedBudgetConfig = null
        currentWindowStartedAtMs = 0L
        downloadBudgetBytes = 0L
        uploadBudgetBytes = 0L
        downloadUsedBytes = 0L
        uploadUsedBytes = 0L
    }

    private fun suggestedChunkSizeForLimit(limitBytesPerSecond: Double, defaultSize: Int): Int {
        val suggested = (limitBytesPerSecond / 10.0).toInt()
        return suggested.coerceIn(
            SPEED_TEST_SOFT_LIMIT_MIN_CHUNK_BYTES,
            defaultSize.coerceAtLeast(SPEED_TEST_SOFT_LIMIT_MIN_CHUNK_BYTES)
        )
    }
}

private class SpeedTestRunner(
    private val direction: SpeedTestDirection,
    private val callbackScope: CoroutineScope,
    context: Context
) {
    private val appContext = context.applicationContext
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val totalBytes = AtomicLong(0L)
    private val running = AtomicBoolean(false)
    private val sessionToken = AtomicLong(0L)
    private val stopReason = AtomicReference<String?>(null)
    private var sessionJob: Job? = null
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private data class WorkerNetworkPlan(
        val key: String,
        val label: String,
        val client: OkHttpClient
    )

    private data class WifiCandidate(
        val network: Network,
        val bandKey: String,
        val bandLabel: String
    )

    private fun createClientForNetwork(network: Network): OkHttpClient {
        return baseClient.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(
                object : Dns {
                    override fun lookup(hostname: String): List<java.net.InetAddress> {
                        return runCatching { network.getAllByName(hostname).toList() }
                            .getOrElse { Dns.SYSTEM.lookup(hostname) }
                    }
                }
            )
            .build()
    }

    private fun resolveNetworkPlans(dualChannelEnabled: Boolean): List<WorkerNetworkPlan> {
        val defaultPlan = WorkerNetworkPlan(
            key = "default",
            label = "默认网络",
            client = baseClient
        )
        if (!dualChannelEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return listOf(defaultPlan)
        }
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
            ?: return listOf(defaultPlan)

        val wifiCandidates = mutableListOf<WifiCandidate>()
        var cellularNetwork: Network? = null
        connectivityManager.allNetworks.forEach { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@forEach
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@forEach
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val (bandKey, bandLabel) = resolveWifiBand(caps)
                wifiCandidates.add(
                    WifiCandidate(
                        network = network,
                        bandKey = bandKey,
                        bandLabel = bandLabel
                    )
                )
            }
            if (cellularNetwork == null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                cellularNetwork = network
            }
        }

        val selectedWifi = selectWifiCandidates(wifiCandidates)
        val wifiLabelCount = mutableMapOf<String, Int>()
        val plans = buildList {
            selectedWifi.forEachIndexed { index, candidate ->
                val count = (wifiLabelCount[candidate.bandLabel] ?: 0) + 1
                wifiLabelCount[candidate.bandLabel] = count
                val label = if (count > 1) "${candidate.bandLabel}#${count}" else candidate.bandLabel
                add(
                    WorkerNetworkPlan(
                        key = "wifi_${candidate.bandKey}_${index + 1}",
                        label = label,
                        client = createClientForNetwork(candidate.network)
                    )
                )
            }
            cellularNetwork?.let { network ->
                add(
                    WorkerNetworkPlan(
                        key = "cellular",
                        label = "数据",
                        client = createClientForNetwork(network)
                    )
                )
            }
        }

        if (plans.size < 2) {
            return listOf(defaultPlan)
        }

        return plans.take(3)
    }

    private fun resolveWifiBand(caps: NetworkCapabilities): Pair<String, String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = caps.transportInfo as? WifiInfo
            val frequency = info?.frequency ?: 0
            return when {
                frequency in 2400..2500 -> "24g" to "WiFi-2.4G"
                frequency in 4900..5900 -> "5g" to "WiFi-5G"
                frequency in 5925..7125 -> "6g" to "WiFi-6G"
                else -> "wifi" to "WiFi"
            }
        }
        return "wifi" to "WiFi"
    }

    private fun selectWifiCandidates(candidates: List<WifiCandidate>): List<WifiCandidate> {
        if (candidates.isEmpty()) return emptyList()
        if (candidates.size == 1) return candidates
        val selected = mutableListOf<WifiCandidate>()
        val band24 = candidates.firstOrNull { it.bandKey == "24g" }
        val bandHigh = candidates.firstOrNull { it.bandKey == "5g" || it.bandKey == "6g" }
        if (band24 != null) {
            selected.add(band24)
        }
        if (bandHigh != null && selected.none { it.network == bandHigh.network }) {
            selected.add(bandHigh)
        }
        if (selected.isEmpty()) {
            selected.add(candidates.first())
        }
        candidates.forEach { candidate ->
            if (selected.size >= 2) return@forEach
            if (selected.none { it.network == candidate.network }) {
                selected.add(candidate)
            }
        }
        return selected.distinctBy { it.network }.take(2)
    }

    fun start(
        endpoints: List<SpeedTestEndpointConfig>,
        stopConfig: SpeedTestStopConfig,
        speedLimitConfig: SpeedLimitConfig,
        dualChannelEnabled: Boolean,
        transferGate: AlternatingTransferGate,
        callbacks: SpeedTestCallbacks
    ) {
        val normalizedEndpoints = endpoints.mapNotNull { endpoint ->
            val url = endpoint.url.trim()
            if (url.isBlank()) {
                null
            } else {
                endpoint.copy(url = url, threads = endpoint.threads.coerceIn(1, SPEED_TEST_MAX_THREADS))
            }
        }
        if (normalizedEndpoints.isEmpty()) {
            callbacks.onRunningChanged(false)
            callbacks.onStatusChanged("请至少填写一个${direction.label}地址。")
            return
        }
        if (stopConfig.autoSwitchEndpoints &&
            stopConfig.durationSeconds == null &&
            stopConfig.dataLimitBytes == null
        ) {
            callbacks.onRunningChanged(false)
            callbacks.onStatusChanged("开启自动更换链接时，必须设置固定时长或流量上限。")
            return
        }
        if (speedLimitConfig.mode == SpeedLimitMode.FIXED &&
            (speedLimitConfig.fixedLimitBytesPerSecond == null || speedLimitConfig.fixedLimitBytesPerSecond <= 0L)
        ) {
            callbacks.onRunningChanged(false)
            callbacks.onStatusChanged("固定限速模式下，必须填写有效的速度上限。")
            return
        }

        val token = sessionToken.incrementAndGet()
        cancelActiveSession("重新启动测速。")
        callbacks.onResetDirectionLogs()
        totalBytes.set(0L)
        stopReason.set("测速已停止。")
        running.set(true)

        sessionJob = workerScope.launch {
            val startedAt = System.currentTimeMillis()
            val realtimeSpeedRef = AtomicReference(0.0)
            val speedLimiter = SharedSpeedLimiter()
            val networkPlans = resolveNetworkPlans(dualChannelEnabled)
            val clientsByKey = networkPlans.associate { it.key to it.client }
            val channelBytesByLabel = ConcurrentHashMap<String, AtomicLong>().apply {
                networkPlans.forEach { put(it.label, AtomicLong(0L)) }
            }
            val lastChannelBytes = mutableMapOf<String, Long>().apply {
                networkPlans.forEach { put(it.label, 0L) }
            }
            val multiChannelActive = dualChannelEnabled && networkPlans.size > 1
            val multiChannelFallback = dualChannelEnabled && !multiChannelActive
            emitUi(token) {
                callbacks.onRunningChanged(true)
                callbacks.onStatsChanged(SpeedTestSessionStats(visible = true, running = true))
                callbacks.onChannelRatesChanged(
                    networkPlans.map { plan ->
                        SpeedTestChannelRate(networkLabel = plan.label)
                    }
                )
                when {
                    multiChannelActive -> callbacks.onLogEvent(
                        SpeedTestLogEvent(
                            direction = direction,
                            workerLabel = "网络通道",
                            message = when (networkPlans.size) {
                                3 -> "三频并发已生效：双 WiFi + 数据。"
                                2 -> "多通道并发已生效：${networkPlans.joinToString(" + ") { it.label }}。"
                                else -> "多通道并发已生效。"
                            }
                        )
                    )

                    multiChannelFallback -> callbacks.onLogEvent(
                        SpeedTestLogEvent(
                            direction = direction,
                            workerLabel = "网络通道",
                            message = "多通道并发已开启，但当前未检测到可并发的多条网络通道，已自动回退为单通道。"
                        )
                    )
                }
            }

            val speedJob = callbackScope.launch(Dispatchers.Default) {
                var lastBytes = 0L
                var lastTick = startedAt
                while (isActive && running.get() && sessionToken.get() == token) {
                    delay(1000)
                    val now = System.currentTimeMillis()
                    val elapsedMs = (now - startedAt).coerceAtLeast(0L)
                    val currentBytes = totalBytes.get()
                    val deltaBytes = (currentBytes - lastBytes).coerceAtLeast(0L)
                    val deltaMs = (now - lastTick).coerceAtLeast(1L)
                    val realtimeSpeed = deltaBytes.toDouble() * 1000.0 / deltaMs.toDouble()
                    val averageSpeed = if (elapsedMs > 0L) {
                        currentBytes.toDouble() * 1000.0 / elapsedMs.toDouble()
                    } else {
                        0.0
                    }
                    realtimeSpeedRef.set(realtimeSpeed)
                    lastBytes = currentBytes
                    lastTick = now
                    emitUi(token) {
                        callbacks.onStatsChanged(
                            SpeedTestSessionStats(
                                visible = true,
                                running = true,
                                realtimeSpeedBytesPerSecond = realtimeSpeed,
                                averageSpeedBytesPerSecond = averageSpeed,
                                usedBytes = currentBytes,
                                elapsedMs = elapsedMs
                            )
                        )
                        callbacks.onChannelRatesChanged(
                            buildChannelRates(
                                channelBytesByLabel = channelBytesByLabel,
                                previousBytes = lastChannelBytes,
                                elapsedMs = elapsedMs,
                                deltaMs = deltaMs
                            )
                        )
                    }

                    val reason = resolveAutoStopReason(stopConfig, elapsedMs, currentBytes)
                    if (reason != null) {
                        stopReason.set(reason)
                        running.set(false)
                        activeCalls.values.forEach { it.cancel() }
                    }
                }
            }

            val limitControllerJob = callbackScope.launch(Dispatchers.Default) {
                runSpeedLimitController(
                    token = token,
                    speedLimitConfig = speedLimitConfig,
                    callbacks = callbacks,
                    limiter = speedLimiter,
                    realtimeSpeedRef = realtimeSpeedRef
                )
            }

            try {
                if (stopConfig.autoSwitchEndpoints) {
                    normalizedEndpoints.forEachIndexed { endpointIndex, endpoint ->
                        if (!running.get() || sessionToken.get() != token) return@forEachIndexed
                        runEndpointBatch(
                            endpoint = endpoint,
                            endpointIndex = endpointIndex,
                            totalEndpoints = normalizedEndpoints.size,
                            token = token,
                            stopConfig = stopConfig,
                            networkPlans = networkPlans,
                            dualChannelEnabled = dualChannelEnabled,
                            limiter = speedLimiter,
                            transferGate = transferGate,
                            channelBytesByLabel = channelBytesByLabel,
                            clientsByKey = clientsByKey,
                            callbacks = callbacks
                        )
                    }
                    if (running.get() && sessionToken.get() == token) {
                        stopReason.set("已完成所有${direction.label}链接测试，测速已停止。")
                        running.set(false)
                    }
                } else {
                    callbacks.onActiveEndpointsChanged(normalizedEndpoints)
                    val requestedWorkerSpecs = normalizedEndpoints.flatMapIndexed { endpointIndex, endpoint ->
                        buildWorkerSpecs(
                            endpointIndex = endpointIndex,
                            endpoint = endpoint,
                            networkPlans = networkPlans
                        )
                    }
                    val workerSpecs = applyWorkerCap(
                        workerSpecs = requestedWorkerSpecs,
                        token = token,
                        callbacks = callbacks
                    )
                    emitUi(token) {
                        val channelSuffix = when {
                            multiChannelActive -> "（并发通道：${networkPlans.joinToString("+") { it.label }}）"
                            multiChannelFallback -> "（多通道未满足，已回退单通道）"
                            else -> ""
                        }
                        callbacks.onStatusChanged("已启动 ${workerSpecs.size} 个${direction.label}线程$channelSuffix。")
                    }
                    val endpointActive = AtomicBoolean(true)
                    workerSpecs.map { spec ->
                        launch {
                            when (direction) {
                                SpeedTestDirection.DOWNLOAD -> runDownloadWorker(
                                    spec = spec,
                                    token = token,
                                    callbacks = callbacks,
                                    endpointActive = endpointActive,
                                    limiter = speedLimiter,
                                    transferGate = transferGate,
                                    channelBytesByLabel = channelBytesByLabel,
                                    clientsByKey = clientsByKey
                                )

                                SpeedTestDirection.UPLOAD -> runUploadWorker(
                                    spec = spec,
                                    token = token,
                                    callbacks = callbacks,
                                    endpointActive = endpointActive,
                                    limiter = speedLimiter,
                                    transferGate = transferGate,
                                    channelBytesByLabel = channelBytesByLabel,
                                    clientsByKey = clientsByKey
                                )
                            }
                        }
                    }.joinAll()
                }
            } finally {
                limitControllerJob.cancel()
                runCatching { limitControllerJob.join() }
                speedJob.cancel()
                runCatching { speedJob.join() }
                activeCalls.values.forEach { it.cancel() }
                activeCalls.clear()
                if (sessionToken.get() == token) {
                    running.set(false)
                    val finishedAt = System.currentTimeMillis()
                    val elapsedMs = (finishedAt - startedAt).coerceAtLeast(0L)
                    val usedBytes = totalBytes.get()
                    val averageSpeed = if (elapsedMs > 0L) {
                        usedBytes.toDouble() * 1000.0 / elapsedMs.toDouble()
                    } else {
                        0.0
                    }
                    emitUi(token) {
                        callbacks.onRunningChanged(false)
                        callbacks.onStatsChanged(
                            SpeedTestSessionStats(
                                visible = true,
                                running = false,
                                realtimeSpeedBytesPerSecond = 0.0,
                                averageSpeedBytesPerSecond = averageSpeed,
                                usedBytes = usedBytes,
                                elapsedMs = elapsedMs
                            )
                        )
                        callbacks.onChannelRatesChanged(
                            buildFinalChannelRates(
                                channelBytesByLabel = channelBytesByLabel,
                                elapsedMs = elapsedMs
                            )
                        )
                        callbacks.onStatusChanged(stopReason.get() ?: "测速已停止。")
                    }
                }
            }
        }
    }

    fun stop(reason: String) {
        stopReason.set(reason)
        cancelActiveSession(reason)
    }

    fun dispose() {
        cancelActiveSession("页面退出，测速已停止。")
        workerScope.cancel()
    }

    private fun cancelActiveSession(reason: String) {
        stopReason.set(reason)
        running.set(false)
        sessionJob?.cancel()
        sessionJob = null
        activeCalls.values.forEach { it.cancel() }
        activeCalls.clear()
    }

    private fun resolveAutoStopReason(
        stopConfig: SpeedTestStopConfig,
        elapsedMs: Long,
        usedBytes: Long
    ): String? {
        val durationReached = stopConfig.durationSeconds?.let { elapsedMs >= it * 1000L } == true
        val dataReached = stopConfig.dataLimitBytes?.let { usedBytes >= it } == true
        return when {
            durationReached && dataReached -> "已达到固定测速时长和流量上限，已自动停止。"
            durationReached -> "已达到固定测速时长，已自动停止。"
            dataReached -> "已达到流量上限，已自动停止。"
            else -> null
        }
    }

    private fun buildWorkerSpecs(
        endpointIndex: Int,
        endpoint: SpeedTestEndpointConfig,
        networkPlans: List<WorkerNetworkPlan>
    ): List<SpeedTestWorkerSpec> {
        val plans = if (networkPlans.isEmpty()) {
            listOf(WorkerNetworkPlan(key = "default", label = "默认网络", client = baseClient))
        } else {
            networkPlans
        }
        return (1..endpoint.threads).flatMap { threadIndex ->
            plans.map { plan ->
                SpeedTestWorkerSpec(
                    endpointIndex = endpointIndex,
                    threadIndex = threadIndex,
                    url = endpoint.url,
                    networkKey = plan.key,
                    networkLabel = plan.label
                )
            }
        }
    }

    private fun applyWorkerCap(
        workerSpecs: List<SpeedTestWorkerSpec>,
        token: Long,
        callbacks: SpeedTestCallbacks
    ): List<SpeedTestWorkerSpec> {
        if (workerSpecs.size <= SPEED_TEST_MAX_ACTIVE_WORKERS_PER_DIRECTION) {
            return workerSpecs
        }
        val capped = workerSpecs.take(SPEED_TEST_MAX_ACTIVE_WORKERS_PER_DIRECTION)
        logEvent(
            token = token,
            callbacks = callbacks,
            spec = capped.first(),
            message = "线程过多，已将单方向并发线程限制为 ${SPEED_TEST_MAX_ACTIVE_WORKERS_PER_DIRECTION}，避免统计异常归零。"
        )
        return capped
    }

    private suspend fun runEndpointBatch(
        endpoint: SpeedTestEndpointConfig,
        endpointIndex: Int,
        totalEndpoints: Int,
        token: Long,
        stopConfig: SpeedTestStopConfig,
        networkPlans: List<WorkerNetworkPlan>,
        dualChannelEnabled: Boolean,
        limiter: SharedSpeedLimiter,
        transferGate: AlternatingTransferGate,
        channelBytesByLabel: ConcurrentHashMap<String, AtomicLong>,
        clientsByKey: Map<String, OkHttpClient>,
        callbacks: SpeedTestCallbacks
    ) {
        val endpointActive = AtomicBoolean(true)
        val endpointStartedAt = System.currentTimeMillis()
        val endpointStartBytes = totalBytes.get()
        val workerSpecs = buildWorkerSpecs(
            endpointIndex = endpointIndex,
            endpoint = endpoint,
            networkPlans = networkPlans
        )
        val cappedWorkerSpecs = applyWorkerCap(
            workerSpecs = workerSpecs,
            token = token,
            callbacks = callbacks
        )
        val multiChannelActive = dualChannelEnabled && networkPlans.size > 1

        emitUi(token) {
            callbacks.onActiveEndpointsChanged(listOf(endpoint))
            val channelSuffix = if (multiChannelActive) {
                "（${networkPlans.joinToString("+") { it.label }}）"
            } else {
                ""
            }
            callbacks.onStatusChanged(
                "正在测试第 ${endpointIndex + 1} / $totalEndpoints 个${direction.label}链接，线程 ${cappedWorkerSpecs.size}$channelSuffix。"
            )
        }

        val switchMonitor = workerScope.launch {
            while (isActive && running.get() && endpointActive.get() && sessionToken.get() == token) {
                delay(400)
                val elapsedMs = (System.currentTimeMillis() - endpointStartedAt).coerceAtLeast(0L)
                val usedBytes = (totalBytes.get() - endpointStartBytes).coerceAtLeast(0L)
                val reason = resolveAutoStopReason(stopConfig, elapsedMs, usedBytes)
                if (reason != null) {
                    stopReason.set("当前链接已满足切换条件，正在切换下一个链接。")
                    endpointActive.set(false)
                    activeCalls.values.forEach { it.cancel() }
                }
            }
        }

        try {
                cappedWorkerSpecs.map { spec ->
                    workerScope.launch {
                        when (direction) {
                            SpeedTestDirection.DOWNLOAD -> runDownloadWorker(
                                spec = spec,
                                token = token,
                                callbacks = callbacks,
                                endpointActive = endpointActive,
                                limiter = limiter,
                                transferGate = transferGate,
                                channelBytesByLabel = channelBytesByLabel,
                                clientsByKey = clientsByKey
                            )

                            SpeedTestDirection.UPLOAD -> runUploadWorker(
                                spec = spec,
                                token = token,
                                callbacks = callbacks,
                                endpointActive = endpointActive,
                                limiter = limiter,
                                transferGate = transferGate,
                                channelBytesByLabel = channelBytesByLabel,
                                clientsByKey = clientsByKey
                            )
                        }
                    }
                }.joinAll()
        } finally {
            switchMonitor.cancel()
            runCatching { switchMonitor.join() }
        }
    }

    private suspend fun runDownloadWorker(
        spec: SpeedTestWorkerSpec,
        token: Long,
        callbacks: SpeedTestCallbacks,
        endpointActive: AtomicBoolean,
        limiter: SharedSpeedLimiter,
        transferGate: AlternatingTransferGate,
        channelBytesByLabel: ConcurrentHashMap<String, AtomicLong>,
        clientsByKey: Map<String, OkHttpClient>
    ) {
        val buffer = ByteArray(SPEED_TEST_IO_BUFFER_BYTES)
        var threadBytes = 0L
        publishSnapshot(
            spec = spec,
            token = token,
            callbacks = callbacks,
            state = "等待连接",
            bytesTransferred = threadBytes
        )

        while (currentCoroutineContext().isActive && running.get() && endpointActive.get() && sessionToken.get() == token) {
            var call: Call? = null
            try {
                transferGate.awaitTurn(direction) {
                    running.get() && endpointActive.get() && sessionToken.get() == token
                }
                if (!running.get() || !endpointActive.get() || sessionToken.get() != token) break
                publishSnapshot(
                    spec = spec,
                    token = token,
                    callbacks = callbacks,
                    state = "连接中",
                    bytesTransferred = threadBytes
                )
                logEvent(token, callbacks, spec, "开始请求 ${spec.url}")

                val request = Request.Builder()
                    .url(spec.url)
                    .get()
                    .header("Cache-Control", "no-cache")
                    .build()
                val requestClient = clientsByKey[spec.networkKey] ?: baseClient
                call = requestClient.newCall(request)
                activeCalls[spec.id] = call
                var shouldRetry = false
                call.execute().use { response ->
                    activeCalls.remove(spec.id)
                    if (!response.isSuccessful) {
                        publishSnapshot(
                            spec = spec,
                            token = token,
                            callbacks = callbacks,
                            state = "连接报错",
                            bytesTransferred = threadBytes,
                            responseCode = response.code,
                            errorCode = "HTTP_${response.code}",
                            errorReason = response.message.ifBlank { "HTTP 请求失败" }
                        )
                        logEvent(
                            token,
                            callbacks,
                            spec,
                            "HTTP ${response.code} ${response.message.ifBlank { "请求失败" }}"
                        )
                        shouldRetry = true
                        return@use
                    }

                    publishSnapshot(
                        spec = spec,
                        token = token,
                        callbacks = callbacks,
                        state = "下载中",
                        bytesTransferred = threadBytes,
                        responseCode = response.code
                    )

                    val inputStream = response.body?.byteStream() ?: throw IOException("响应体为空")
                    var lastUiUpdate = 0L
                    inputStream.use { input ->
                        while (currentCoroutineContext().isActive && running.get() && endpointActive.get() && sessionToken.get() == token) {
                            transferGate.awaitTurn(direction) {
                                running.get() && endpointActive.get() && sessionToken.get() == token
                            }
                            if (!running.get() || !endpointActive.get() || sessionToken.get() != token) break
                            val suggestedBytes = transferGate.recommendedChunkSize(direction, buffer.size)
                            val actualRequestedBytes = transferGate.acquire(direction, suggestedBytes) {
                                running.get() && endpointActive.get() && sessionToken.get() == token
                            }
                            if (actualRequestedBytes <= 0) break
                            limiter.acquire(actualRequestedBytes) {
                                running.get() && endpointActive.get() && sessionToken.get() == token
                            }
                            val readCount = input.read(buffer, 0, actualRequestedBytes)
                            if (readCount <= 0) break
                            if (readCount < actualRequestedBytes) {
                                transferGate.refund(direction, actualRequestedBytes - readCount)
                            }
                            threadBytes += readCount.toLong()
                            totalBytes.addAndGet(readCount.toLong())
                            addChannelBytes(channelBytesByLabel, spec.networkLabel, readCount.toLong())
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate >= 450L) {
                                lastUiUpdate = now
                                publishSnapshot(
                                    spec = spec,
                                    token = token,
                                    callbacks = callbacks,
                                    state = "下载中",
                                    bytesTransferred = threadBytes,
                                    responseCode = response.code
                                )
                            }
                        }
                    }

                    publishSnapshot(
                        spec = spec,
                        token = token,
                        callbacks = callbacks,
                        state = "连接结束，准备重连",
                        bytesTransferred = threadBytes,
                        responseCode = response.code
                    )
                }
                if (shouldRetry) {
                    delay(1200)
                    continue
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                break
            } catch (error: Exception) {
                if (!running.get() || sessionToken.get() != token) break
                val mapped = mapError(error)
                publishSnapshot(
                    spec = spec,
                    token = token,
                    callbacks = callbacks,
                    state = "连接报错",
                    bytesTransferred = threadBytes,
                    errorCode = mapped.first,
                    errorReason = mapped.second
                )
                logEvent(token, callbacks, spec, "${mapped.first}: ${mapped.second}")
                delay(1200)
            } finally {
                call?.cancel()
                activeCalls.remove(spec.id)
            }
        }

        publishSnapshot(
            spec = spec,
            token = token,
            callbacks = callbacks,
            state = "已停止",
            bytesTransferred = threadBytes
        )
    }

    private suspend fun runUploadWorker(
        spec: SpeedTestWorkerSpec,
        token: Long,
        callbacks: SpeedTestCallbacks,
        endpointActive: AtomicBoolean,
        limiter: SharedSpeedLimiter,
        transferGate: AlternatingTransferGate,
        channelBytesByLabel: ConcurrentHashMap<String, AtomicLong>,
        clientsByKey: Map<String, OkHttpClient>
    ) {
        var threadBytes = 0L
        publishSnapshot(
            spec = spec,
            token = token,
            callbacks = callbacks,
            state = "等待连接",
            bytesTransferred = threadBytes
        )

        while (currentCoroutineContext().isActive && running.get() && endpointActive.get() && sessionToken.get() == token) {
            var call: Call? = null
            try {
                transferGate.awaitTurn(direction) {
                    running.get() && endpointActive.get() && sessionToken.get() == token
                }
                if (!running.get() || !endpointActive.get() || sessionToken.get() != token) break
                publishSnapshot(
                    spec = spec,
                    token = token,
                    callbacks = callbacks,
                    state = "连接中",
                    bytesTransferred = threadBytes
                )
                logEvent(token, callbacks, spec, "开始上传到 ${spec.url}")

                val requestBody = RandomUploadRequestBody(
                    bytesToWrite = SPEED_TEST_UPLOAD_REQUEST_BYTES,
                    isRunning = { running.get() && endpointActive.get() && sessionToken.get() == token },
                    beforeBytesWrite = { chunkBytes ->
                        val suggestedChunkBytes = transferGate.recommendedChunkSize(direction, chunkBytes)
                        transferGate.awaitTurn(direction) {
                            running.get() && endpointActive.get() && sessionToken.get() == token
                        }
                        val allowedChunkBytes = transferGate.acquire(direction, suggestedChunkBytes) {
                            running.get() && endpointActive.get() && sessionToken.get() == token
                        }
                        if (allowedChunkBytes <= 0) {
                            throw IOException("上传已停止")
                        }
                        limiter.acquire(allowedChunkBytes) {
                            running.get() && endpointActive.get() && sessionToken.get() == token
                        }
                        allowedChunkBytes
                    },
                    onBytesWritten = { chunkBytes ->
                        threadBytes += chunkBytes
                        totalBytes.addAndGet(chunkBytes)
                        addChannelBytes(channelBytesByLabel, spec.networkLabel, chunkBytes)
                    },
                    onProgress = {
                        publishSnapshot(
                            spec = spec,
                            token = token,
                            callbacks = callbacks,
                            state = "上传中",
                            bytesTransferred = threadBytes
                        )
                    }
                )
                val request = Request.Builder()
                    .url(spec.url)
                    .post(requestBody)
                    .header("Cache-Control", "no-cache")
                    .build()

                val requestClient = clientsByKey[spec.networkKey] ?: baseClient
                call = requestClient.newCall(request)
                activeCalls[spec.id] = call
                var shouldRetry = false
                call.execute().use { response ->
                    activeCalls.remove(spec.id)
                    if (!response.isSuccessful) {
                        publishSnapshot(
                            spec = spec,
                            token = token,
                            callbacks = callbacks,
                            state = "连接报错",
                            bytesTransferred = threadBytes,
                            responseCode = response.code,
                            errorCode = "HTTP_${response.code}",
                            errorReason = response.message.ifBlank { "HTTP 请求失败" }
                        )
                        logEvent(
                            token,
                            callbacks,
                            spec,
                            "HTTP ${response.code} ${response.message.ifBlank { "上传失败" }}"
                        )
                        shouldRetry = true
                        return@use
                    }

                    publishSnapshot(
                        spec = spec,
                        token = token,
                        callbacks = callbacks,
                        state = "上传完成，继续下一轮",
                        bytesTransferred = threadBytes,
                        responseCode = response.code
                    )
                }
                if (shouldRetry) {
                    delay(1200)
                    continue
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                break
            } catch (error: Exception) {
                if (!running.get() || sessionToken.get() != token) break
                val mapped = mapError(error)
                publishSnapshot(
                    spec = spec,
                    token = token,
                    callbacks = callbacks,
                    state = "连接报错",
                    bytesTransferred = threadBytes,
                    errorCode = mapped.first,
                    errorReason = mapped.second
                )
                logEvent(token, callbacks, spec, "${mapped.first}: ${mapped.second}")
                delay(1200)
            } finally {
                call?.cancel()
                activeCalls.remove(spec.id)
            }
        }

        publishSnapshot(
            spec = spec,
            token = token,
            callbacks = callbacks,
            state = "已停止",
            bytesTransferred = threadBytes
        )
    }

    private suspend fun runSpeedLimitController(
        token: Long,
        speedLimitConfig: SpeedLimitConfig,
        callbacks: SpeedTestCallbacks,
        limiter: SharedSpeedLimiter,
        realtimeSpeedRef: AtomicReference<Double>
    ) {
        when (speedLimitConfig.mode) {
            SpeedLimitMode.OFF -> {
                limiter.setLimit(null)
            }
            SpeedLimitMode.FIXED -> {
                val limit = speedLimitConfig.fixedLimitBytesPerSecond?.toDouble()
                limiter.setLimit(limit)
                if (limit != null) {
                    logControllerEvent(
                        token = token,
                        callbacks = callbacks,
                        message = "固定限速已生效，目标上限 ${formatRate(limit)}。"
                    )
                }
            }
            SpeedLimitMode.FLOATING -> {
                val intervalSeconds = speedLimitConfig.floatingIntervalSeconds.coerceAtLeast(1)
                val percent = speedLimitConfig.floatingPercent.coerceIn(1, 100)
                val probeSeconds = minOf(SPEED_TEST_FLOATING_PROBE_SECONDS, intervalSeconds)
                while (currentCoroutineContext().isActive && running.get() && sessionToken.get() == token) {
                    limiter.setLimit(null)
                    logControllerEvent(
                        token = token,
                        callbacks = callbacks,
                        message = "浮动限速进入探测阶段，已解除限制 ${probeSeconds} 秒，用于测当前最大速度。"
                    )

                    var peakSpeed = 0.0
                    repeat(probeSeconds) {
                        if (!currentCoroutineContext().isActive || !running.get() || sessionToken.get() != token) return
                        delay(1000)
                        peakSpeed = maxOf(peakSpeed, realtimeSpeedRef.get())
                    }

                    if (!currentCoroutineContext().isActive || !running.get() || sessionToken.get() != token) return

                    val limitedSpeed = if (peakSpeed > 0.0) peakSpeed * percent / 100.0 else null
                    limiter.setLimit(limitedSpeed)
                    if (limitedSpeed != null) {
                        logControllerEvent(
                            token = token,
                            callbacks = callbacks,
                            message = "浮动限速已更新，当前最大速度 ${formatRate(peakSpeed)}，接下来按 ${percent}% 限制到 ${formatRate(limitedSpeed)}。"
                        )
                    } else {
                        logControllerEvent(
                            token = token,
                            callbacks = callbacks,
                            message = "本轮未探测到有效速度，暂时保持不限速，等待下一轮重新探测。"
                        )
                    }

                    repeat(intervalSeconds) {
                        if (!currentCoroutineContext().isActive || !running.get() || sessionToken.get() != token) return
                        delay(1000)
                    }
                }
            }
        }
    }

    private fun addChannelBytes(
        channelBytesByLabel: ConcurrentHashMap<String, AtomicLong>,
        networkLabel: String,
        bytes: Long
    ) {
        if (bytes <= 0L) return
        channelBytesByLabel.computeIfAbsent(networkLabel) { AtomicLong(0L) }.addAndGet(bytes)
    }

    private fun buildChannelRates(
        channelBytesByLabel: ConcurrentHashMap<String, AtomicLong>,
        previousBytes: MutableMap<String, Long>,
        elapsedMs: Long,
        deltaMs: Long
    ): List<SpeedTestChannelRate> {
        return channelBytesByLabel.entries.map { entry ->
            val label = entry.key
            val currentBytes = entry.value.get().coerceAtLeast(0L)
            val lastBytes = previousBytes[label] ?: 0L
            val deltaBytes = (currentBytes - lastBytes).coerceAtLeast(0L)
            previousBytes[label] = currentBytes
            SpeedTestChannelRate(
                networkLabel = label,
                realtimeSpeedBytesPerSecond = deltaBytes.toDouble() * 1000.0 / deltaMs.coerceAtLeast(1L).toDouble(),
                averageSpeedBytesPerSecond = if (elapsedMs > 0L) {
                    currentBytes.toDouble() * 1000.0 / elapsedMs.toDouble()
                } else {
                    0.0
                },
                usedBytes = currentBytes
            )
        }.sortedByDescending { it.realtimeSpeedBytesPerSecond }
    }

    private fun buildFinalChannelRates(
        channelBytesByLabel: ConcurrentHashMap<String, AtomicLong>,
        elapsedMs: Long
    ): List<SpeedTestChannelRate> {
        return channelBytesByLabel.entries.map { entry ->
            val currentBytes = entry.value.get().coerceAtLeast(0L)
            SpeedTestChannelRate(
                networkLabel = entry.key,
                realtimeSpeedBytesPerSecond = 0.0,
                averageSpeedBytesPerSecond = if (elapsedMs > 0L) {
                    currentBytes.toDouble() * 1000.0 / elapsedMs.toDouble()
                } else {
                    0.0
                },
                usedBytes = currentBytes
            )
        }.sortedByDescending { it.averageSpeedBytesPerSecond }
    }

    private fun publishSnapshot(
        spec: SpeedTestWorkerSpec,
        token: Long,
        callbacks: SpeedTestCallbacks,
        state: String,
        bytesTransferred: Long,
        responseCode: Int? = null,
        errorCode: String? = null,
        errorReason: String? = null
    ) {
        emitUi(token) {
            callbacks.onThreadSnapshotChanged(
                SpeedTestThreadSnapshot(
                    id = "${direction.name}_${spec.id}",
                    direction = direction,
                    endpointIndex = spec.endpointIndex,
                    threadIndex = spec.threadIndex,
                    networkLabel = spec.networkLabel,
                    url = spec.url,
                    state = state,
                    bytesTransferred = bytesTransferred,
                    responseCode = responseCode,
                    errorCode = errorCode,
                    errorReason = errorReason,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    private fun logEvent(
        token: Long,
        callbacks: SpeedTestCallbacks,
        spec: SpeedTestWorkerSpec,
        message: String
    ) {
        emitUi(token) {
            callbacks.onLogEvent(
                SpeedTestLogEvent(
                    direction = direction,
                    workerLabel = spec.label,
                    message = message
                )
            )
        }
    }

    private fun logControllerEvent(
        token: Long,
        callbacks: SpeedTestCallbacks,
        message: String
    ) {
        emitUi(token) {
            callbacks.onLogEvent(
                SpeedTestLogEvent(
                    direction = direction,
                    workerLabel = "限速控制",
                    message = message
                )
            )
        }
    }

    private fun emitUi(token: Long, block: suspend () -> Unit) {
        if (sessionToken.get() != token) return
        callbackScope.launch {
            if (sessionToken.get() == token) {
                block()
            }
        }
    }

    private fun mapError(error: Exception): Pair<String, String> {
        val code = when (error) {
            is UnknownHostException -> "UNKNOWN_HOST"
            is ConnectException -> "CONNECT_FAIL"
            is SocketTimeoutException -> "SOCKET_TIMEOUT"
            is SSLException -> "SSL_ERROR"
            is IOException -> "IO_ERROR"
            else -> error.javaClass.simpleName.ifBlank { "UNKNOWN_ERROR" }
        }
        val reason = error.message?.trim().takeUnless { it.isNullOrBlank() } ?: "未返回更多错误信息"
        return code to reason
    }
}

private class RandomUploadRequestBody(
    private val bytesToWrite: Long,
    private val isRunning: () -> Boolean,
    private val beforeBytesWrite: (Int) -> Int,
    private val onBytesWritten: (Long) -> Unit,
    private val onProgress: () -> Unit
) : RequestBody() {
    override fun contentType() = null

    override fun contentLength(): Long = bytesToWrite

    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(SPEED_TEST_IO_BUFFER_BYTES) { (it * 31).toByte() }
        var remaining = bytesToWrite
        var lastProgressAt = 0L
        while (remaining > 0L) {
            if (!isRunning()) throw IOException("上传已停止")
            val maxChunkSize = minOf(buffer.size.toLong(), remaining).toInt()
            val chunkSize = beforeBytesWrite(maxChunkSize).coerceIn(1, maxChunkSize)
            if (!isRunning()) throw IOException("上传已停止")
            sink.write(buffer, 0, chunkSize)
            sink.flush()
            remaining -= chunkSize.toLong()
            onBytesWritten(chunkSize.toLong())
            val now = System.currentTimeMillis()
            if (now - lastProgressAt >= 450L) {
                lastProgressAt = now
                onProgress()
            }
        }
        onProgress()
    }
}

@Composable
fun SpeedTestLabScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(SPEED_TEST_PREFS, Context.MODE_PRIVATE) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val initialConfig = remember { loadSpeedTestConfig(prefs) }
    val initialPresets = remember { loadSpeedTestPresets(prefs) }
    val initialSelectedPresetName = remember {
        prefs.getString(SPEED_TEST_KEY_SELECTED_PRESET, null)
            ?.takeIf { savedName -> initialPresets.any { it.name == savedName } }
    }
    val initialDefaultAppEntry = remember {
        prefs.getBoolean(SPEED_TEST_KEY_DEFAULT_APP_ENTRY, false)
    }
    val initialStealthModeEnabled = remember {
        prefs.getBoolean(SPEED_TEST_KEY_STEALTH_MODE, false)
    }
    val initialBackgroundTestEnabled = remember {
        prefs.getBoolean(SPEED_TEST_KEY_BACKGROUND_TEST, false)
    }
    val initialSuperKeepAliveEnabled = remember {
        prefs.getBoolean(SPEED_TEST_KEY_SUPER_KEEP_ALIVE, false)
    }

    var selectedPage by remember { mutableStateOf(2) }
    var savedConfig by remember { mutableStateOf(initialConfig) }
    var savedPresets by remember { mutableStateOf(initialPresets) }
    var selectedPresetName by remember { mutableStateOf(initialSelectedPresetName) }
    var defaultOpenAppToSpeedTest by remember { mutableStateOf(initialDefaultAppEntry) }
    var stealthModeEnabled by remember { mutableStateOf(initialStealthModeEnabled) }
    var backgroundTestEnabled by remember { mutableStateOf(initialBackgroundTestEnabled) }
    var superKeepAliveEnabled by remember { mutableStateOf(initialSuperKeepAliveEnabled) }
    var downloadDrafts by remember { mutableStateOf(initialConfig.downloadEndpoints.map { it.toDraft() }) }
    var uploadDrafts by remember { mutableStateOf(initialConfig.uploadEndpoints.map { it.toDraft() }) }
    var downloadDurationUnit by remember {
        mutableStateOf(pickDurationUnit(initialConfig.downloadStopConfig.durationSeconds))
    }
    var uploadDurationUnit by remember {
        mutableStateOf(pickDurationUnit(initialConfig.uploadStopConfig.durationSeconds))
    }
    var downloadDurationText by remember {
        mutableStateOf(
            initialConfig.downloadStopConfig.durationSeconds.toUnitValueText(downloadDurationUnit)
        )
    }
    var uploadDurationText by remember {
        mutableStateOf(
            initialConfig.uploadStopConfig.durationSeconds.toUnitValueText(uploadDurationUnit)
        )
    }
    var downloadTrafficLimitUnit by remember {
        mutableStateOf(pickTrafficUnit(initialConfig.downloadStopConfig.dataLimitBytes))
    }
    var uploadTrafficLimitUnit by remember {
        mutableStateOf(pickTrafficUnit(initialConfig.uploadStopConfig.dataLimitBytes))
    }
    var downloadTrafficLimitText by remember {
        mutableStateOf(
            initialConfig.downloadStopConfig.dataLimitBytes.toUnitValueText(downloadTrafficLimitUnit)
        )
    }
    var uploadTrafficLimitText by remember {
        mutableStateOf(
            initialConfig.uploadStopConfig.dataLimitBytes.toUnitValueText(uploadTrafficLimitUnit)
        )
    }
    var downloadAutoSwitchEnabled by remember {
        mutableStateOf(initialConfig.downloadStopConfig.autoSwitchEndpoints)
    }
    var uploadAutoSwitchEnabled by remember {
        mutableStateOf(initialConfig.uploadStopConfig.autoSwitchEndpoints)
    }
    var downloadSpeedLimitMode by remember {
        mutableStateOf(initialConfig.downloadSpeedLimitConfig.mode)
    }
    var uploadSpeedLimitMode by remember {
        mutableStateOf(initialConfig.uploadSpeedLimitConfig.mode)
    }
    var downloadFixedLimitText by remember {
        mutableStateOf(initialConfig.downloadSpeedLimitConfig.fixedLimitBytesPerSecond.toLimitMbpsText())
    }
    var uploadFixedLimitText by remember {
        mutableStateOf(initialConfig.uploadSpeedLimitConfig.fixedLimitBytesPerSecond.toLimitMbpsText())
    }
    var downloadFloatingIntervalText by remember {
        mutableStateOf(initialConfig.downloadSpeedLimitConfig.floatingIntervalSeconds.toString())
    }
    var uploadFloatingIntervalText by remember {
        mutableStateOf(initialConfig.uploadSpeedLimitConfig.floatingIntervalSeconds.toString())
    }
    var downloadFloatingPercentText by remember {
        mutableStateOf(initialConfig.downloadSpeedLimitConfig.floatingPercent.toString())
    }
    var uploadFloatingPercentText by remember {
        mutableStateOf(initialConfig.uploadSpeedLimitConfig.floatingPercent.toString())
    }
    var alternatingTransferEnabled by remember {
        mutableStateOf(initialConfig.alternatingTransferConfig.enabled)
    }
    var alternatingTransferMode by remember {
        mutableStateOf(initialConfig.alternatingTransferConfig.mode)
    }
    var alternatingUploadPercent by remember {
        mutableStateOf(initialConfig.alternatingTransferConfig.uploadPercent.toFloat())
    }
    var alternatingWindowMsText by remember {
        mutableStateOf(initialConfig.alternatingTransferConfig.windowMs.toString())
    }
    var alternatingFixedDownloadSecondsText by remember {
        mutableStateOf(initialConfig.alternatingTransferConfig.fixedDownloadSeconds.toString())
    }
    var alternatingFixedUploadSecondsText by remember {
        mutableStateOf(initialConfig.alternatingTransferConfig.fixedUploadSeconds.toString())
    }
    var alternatingRatioIdleLimitText by remember {
        mutableStateOf(initialConfig.alternatingTransferConfig.ratioIdleLimitBytesPerSecond.toLimitMbpsText())
    }
    var alternatingFixedIdleStrategy by remember {
        mutableStateOf(initialConfig.alternatingTransferConfig.fixedIdleStrategy)
    }
    var alternatingFixedIdleLimitText by remember {
        mutableStateOf(initialConfig.alternatingTransferConfig.fixedIdleLimitBytesPerSecond.toLimitMbpsText())
    }
    var alternatingPhaseText by remember { mutableStateOf("未启用") }
    var dualChannelEnabled by remember { mutableStateOf(initialConfig.dualChannelEnabled) }
    var logEnabled by remember { mutableStateOf(initialConfig.logEnabled) }
    var speedTestTitleTapCount by remember { mutableStateOf(0) }
    var showStealthCodeDialog by remember { mutableStateOf(false) }
    var stealthCodeInput by remember { mutableStateOf("") }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var savePresetNameInput by remember { mutableStateOf("") }
    var showCreatePresetDialog by remember { mutableStateOf(false) }
    var createPresetNameInput by remember { mutableStateOf("") }
    var showRenamePresetDialog by remember { mutableStateOf(false) }
    var renamePresetNameInput by remember { mutableStateOf("") }

    var downloadRunning by remember { mutableStateOf(false) }
    var uploadRunning by remember { mutableStateOf(false) }
    var downloadStats by remember { mutableStateOf(SpeedTestSessionStats()) }
    var uploadStats by remember { mutableStateOf(SpeedTestSessionStats()) }
    var downloadChannelRates by remember { mutableStateOf<List<SpeedTestChannelRate>>(emptyList()) }
    var uploadChannelRates by remember { mutableStateOf<List<SpeedTestChannelRate>>(emptyList()) }
    var downloadStatusText by remember { mutableStateOf("下载测速未开始。") }
    var uploadStatusText by remember { mutableStateOf("上传测速未开始。") }
    var configStatusText by remember {
        mutableStateOf("下载/上传最多各 5 个地址；每个地址线程上限 64。")
    }
    var activeDownloadEndpoints by remember { mutableStateOf<List<SpeedTestEndpointConfig>>(emptyList()) }
    var activeUploadEndpoints by remember { mutableStateOf<List<SpeedTestEndpointConfig>>(emptyList()) }

    val threadSnapshots = remember { mutableStateListOf<SpeedTestThreadSnapshot>() }
    val eventLogs = remember { mutableStateListOf<SpeedTestLogEvent>() }
    val overlayPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true
    }
    var notificationPermissionGranted by remember {
        mutableStateOf(isNotificationPermissionGranted(context))
    }
    var pendingEnableBackgroundTest by remember { mutableStateOf(false) }
    val requestNotificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted
        if (pendingEnableBackgroundTest) {
            pendingEnableBackgroundTest = false
            if (granted) {
                backgroundTestEnabled = true
                prefs.edit()
                    .putBoolean(SPEED_TEST_KEY_BACKGROUND_TEST, true)
                    .apply()
            } else {
                backgroundTestEnabled = false
                prefs.edit()
                    .putBoolean(SPEED_TEST_KEY_BACKGROUND_TEST, false)
                    .apply()
                configStatusText = "通知权限未授权，后台测试通知可能无法显示。"
            }
        }
    }
    val downloadRunner = remember { SpeedTestRunner(SpeedTestDirection.DOWNLOAD, scope, context) }
    val uploadRunner = remember { SpeedTestRunner(SpeedTestDirection.UPLOAD, scope, context) }
    val transferGate = remember { AlternatingTransferGate() }

    fun updateDraft(
        drafts: List<SpeedTestEndpointDraft>,
        index: Int,
        transform: (SpeedTestEndpointDraft) -> SpeedTestEndpointDraft
    ): List<SpeedTestEndpointDraft> {
        return drafts.toMutableList().also { list ->
            list[index] = transform(list[index])
        }
    }

    fun normalizeDrafts(
        drafts: List<SpeedTestEndpointDraft>,
        expectedSize: Int
    ): List<SpeedTestEndpointDraft> {
        return (0 until expectedSize).map { index ->
            val current = drafts.getOrNull(index) ?: SpeedTestEndpointDraft()
            val normalizedThreads = (current.threadsText.toIntOrNull() ?: 1).coerceIn(1, SPEED_TEST_MAX_THREADS)
            SpeedTestEndpointDraft(
                url = current.url.trim(),
                threadsText = normalizedThreads.toString()
            )
        }
    }

    fun normalizePositiveText(value: String): String {
        return value.filter { it.isDigit() }.trimStart('0')
    }

    fun normalizeDecimalText(value: String): String {
        val filtered = buildString {
            var dotUsed = false
            value.forEach { char ->
                when {
                    char.isDigit() -> append(char)
                    char == '.' && !dotUsed -> {
                        if (isEmpty()) append('0')
                        append(char)
                        dotUsed = true
                    }
                }
            }
        }
        return if (filtered.startsWith("00") && !filtered.startsWith("0.")) {
            filtered.trimStart('0').ifBlank { "0" }
        } else {
            filtered
        }
    }

    fun buildSpeedLimitConfig(
        mode: SpeedLimitMode,
        fixedLimitText: String,
        floatingIntervalText: String,
        floatingPercentText: String
    ): SpeedLimitConfig {
        val fixedBytes = fixedLimitText.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
            ?.let { mbps -> (mbps * 1_000_000.0 / 8.0).toLong() }
        return SpeedLimitConfig(
            mode = mode,
            fixedLimitBytesPerSecond = fixedBytes,
            floatingIntervalSeconds = floatingIntervalText.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: SPEED_TEST_FLOATING_LIMIT_DEFAULT_SECONDS,
            floatingPercent = floatingPercentText.toIntOrNull()
                ?.coerceIn(1, 100)
                ?: SPEED_TEST_FLOATING_LIMIT_DEFAULT_PERCENT
        )
    }

    fun buildAlternatingTransferConfig(): AlternatingTransferConfig {
        return AlternatingTransferConfig(
            enabled = alternatingTransferEnabled,
            mode = alternatingTransferMode,
            uploadPercent = alternatingUploadPercent.roundToInt().coerceIn(0, 100),
            windowMs = alternatingWindowMsText.toIntOrNull()
                ?.coerceAtLeast(SPEED_TEST_ALTERNATING_MIN_WINDOW_MS)
                ?: SPEED_TEST_ALTERNATING_DEFAULT_WINDOW_MS,
            fixedDownloadSeconds = alternatingFixedDownloadSecondsText.toIntOrNull()
                ?.coerceAtLeast(1)
                ?: SPEED_TEST_ALTERNATING_DEFAULT_DOWNLOAD_SECONDS,
            fixedUploadSeconds = alternatingFixedUploadSecondsText.toIntOrNull()
                ?.coerceAtLeast(1)
                ?: SPEED_TEST_ALTERNATING_DEFAULT_UPLOAD_SECONDS,
            ratioIdleLimitBytesPerSecond = alternatingRatioIdleLimitText.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?.let(::mbpsToBytesPerSecond)
                ?: mbpsToBytesPerSecond(SPEED_TEST_ALTERNATING_RATIO_IDLE_LIMIT_DEFAULT_MBPS),
            fixedIdleStrategy = alternatingFixedIdleStrategy,
            fixedIdleLimitBytesPerSecond = alternatingFixedIdleLimitText.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?.let(::mbpsToBytesPerSecond)
                ?: mbpsToBytesPerSecond(SPEED_TEST_ALTERNATING_FIXED_IDLE_LIMIT_DEFAULT_MBPS)
        )
    }

    fun findConfigConflict(config: SpeedTestConfig): String? {
        val alternatingEnabled = config.alternatingTransferConfig.enabled
        val usesFloatingLimit = config.downloadSpeedLimitConfig.mode == SpeedLimitMode.FLOATING ||
            config.uploadSpeedLimitConfig.mode == SpeedLimitMode.FLOATING
        return when {
            alternatingEnabled && usesFloatingLimit ->
                "交替传输与浮动限速互斥，只能选择其一开启。"
            else -> null
        }
    }

    fun currentConfig(): SpeedTestConfig {
        val normalizedDownloadDrafts = normalizeDrafts(downloadDrafts, SPEED_TEST_MAX_DOWNLOAD_ENDPOINTS)
        val normalizedUploadDrafts = normalizeDrafts(uploadDrafts, SPEED_TEST_MAX_UPLOAD_ENDPOINTS)
        val normalizedDownloadDuration = normalizePositiveText(downloadDurationText)
        val normalizedUploadDuration = normalizePositiveText(uploadDurationText)
        val normalizedDownloadTraffic = normalizePositiveText(downloadTrafficLimitText)
        val normalizedUploadTraffic = normalizePositiveText(uploadTrafficLimitText)
        val downloadDurationSeconds = normalizedDownloadDuration.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { value -> safeMultiply(value, downloadDurationUnit.seconds) }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()
        val uploadDurationSeconds = normalizedUploadDuration.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { value -> safeMultiply(value, uploadDurationUnit.seconds) }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()
        val downloadDataLimitBytes = normalizedDownloadTraffic.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { value -> safeMultiply(value, downloadTrafficLimitUnit.bytes) }
        val uploadDataLimitBytes = normalizedUploadTraffic.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { value -> safeMultiply(value, uploadTrafficLimitUnit.bytes) }
        return SpeedTestConfig(
            downloadEndpoints = normalizedDownloadDrafts.map { it.toConfig() },
            uploadEndpoints = normalizedUploadDrafts.map { it.toConfig() },
            downloadStopConfig = SpeedTestStopConfig(
                durationSeconds = downloadDurationSeconds,
                dataLimitBytes = downloadDataLimitBytes,
                autoSwitchEndpoints = downloadAutoSwitchEnabled
            ),
            uploadStopConfig = SpeedTestStopConfig(
                durationSeconds = uploadDurationSeconds,
                dataLimitBytes = uploadDataLimitBytes,
                autoSwitchEndpoints = uploadAutoSwitchEnabled
            ),
            downloadSpeedLimitConfig = buildSpeedLimitConfig(
                mode = downloadSpeedLimitMode,
                fixedLimitText = downloadFixedLimitText,
                floatingIntervalText = downloadFloatingIntervalText,
                floatingPercentText = downloadFloatingPercentText
            ),
            uploadSpeedLimitConfig = buildSpeedLimitConfig(
                mode = uploadSpeedLimitMode,
                fixedLimitText = uploadFixedLimitText,
                floatingIntervalText = uploadFloatingIntervalText,
                floatingPercentText = uploadFloatingPercentText
            ),
            alternatingTransferConfig = buildAlternatingTransferConfig(),
            dualChannelEnabled = dualChannelEnabled,
            logEnabled = logEnabled
        )
    }

    fun applyConfig(config: SpeedTestConfig) {
        downloadDrafts = normalizeDrafts(
            config.downloadEndpoints.map { it.toDraft() },
            SPEED_TEST_MAX_DOWNLOAD_ENDPOINTS
        )
        uploadDrafts = normalizeDrafts(
            config.uploadEndpoints.map { it.toDraft() },
            SPEED_TEST_MAX_UPLOAD_ENDPOINTS
        )
        downloadDurationUnit = pickDurationUnit(config.downloadStopConfig.durationSeconds)
        uploadDurationUnit = pickDurationUnit(config.uploadStopConfig.durationSeconds)
        downloadDurationText = config.downloadStopConfig.durationSeconds.toUnitValueText(downloadDurationUnit)
        uploadDurationText = config.uploadStopConfig.durationSeconds.toUnitValueText(uploadDurationUnit)
        downloadTrafficLimitUnit = pickTrafficUnit(config.downloadStopConfig.dataLimitBytes)
        uploadTrafficLimitUnit = pickTrafficUnit(config.uploadStopConfig.dataLimitBytes)
        downloadTrafficLimitText = config.downloadStopConfig.dataLimitBytes.toUnitValueText(downloadTrafficLimitUnit)
        uploadTrafficLimitText = config.uploadStopConfig.dataLimitBytes.toUnitValueText(uploadTrafficLimitUnit)
        downloadAutoSwitchEnabled = config.downloadStopConfig.autoSwitchEndpoints
        uploadAutoSwitchEnabled = config.uploadStopConfig.autoSwitchEndpoints
        downloadSpeedLimitMode = config.downloadSpeedLimitConfig.mode
        uploadSpeedLimitMode = config.uploadSpeedLimitConfig.mode
        downloadFixedLimitText = config.downloadSpeedLimitConfig.fixedLimitBytesPerSecond.toLimitMbpsText()
        uploadFixedLimitText = config.uploadSpeedLimitConfig.fixedLimitBytesPerSecond.toLimitMbpsText()
        downloadFloatingIntervalText = config.downloadSpeedLimitConfig.floatingIntervalSeconds.toString()
        uploadFloatingIntervalText = config.uploadSpeedLimitConfig.floatingIntervalSeconds.toString()
        downloadFloatingPercentText = config.downloadSpeedLimitConfig.floatingPercent.toString()
        uploadFloatingPercentText = config.uploadSpeedLimitConfig.floatingPercent.toString()
        alternatingTransferEnabled = config.alternatingTransferConfig.enabled
        alternatingTransferMode = config.alternatingTransferConfig.mode
        alternatingUploadPercent = config.alternatingTransferConfig.uploadPercent.toFloat()
        alternatingWindowMsText = config.alternatingTransferConfig.windowMs.toString()
        alternatingFixedDownloadSecondsText = config.alternatingTransferConfig.fixedDownloadSeconds.toString()
        alternatingFixedUploadSecondsText = config.alternatingTransferConfig.fixedUploadSeconds.toString()
        alternatingRatioIdleLimitText = config.alternatingTransferConfig.ratioIdleLimitBytesPerSecond.toLimitMbpsText()
        alternatingFixedIdleStrategy = config.alternatingTransferConfig.fixedIdleStrategy
        alternatingFixedIdleLimitText = config.alternatingTransferConfig.fixedIdleLimitBytesPerSecond.toLimitMbpsText()
        dualChannelEnabled = config.dualChannelEnabled
        logEnabled = config.logEnabled
    }

    fun persistConfig(config: SpeedTestConfig) {
        prefs.edit()
            .putString(SPEED_TEST_KEY_CONFIG, config.toJson().toString())
            .apply()
        savedConfig = config
    }

    fun persistPresetLibrary(presets: List<SpeedTestNamedConfig>, selectedName: String?) {
        prefs.edit()
            .putString(SPEED_TEST_KEY_PRESETS, presetsToJson(presets).toString())
            .putString(SPEED_TEST_KEY_SELECTED_PRESET, selectedName)
            .apply()
    }

    fun savePreset(name: String, config: SpeedTestConfig): String {
        val preset = SpeedTestNamedConfig(name = name, config = config)
        savedPresets = upsertPreset(savedPresets, preset)
        selectedPresetName = name
        persistPresetLibrary(savedPresets, selectedPresetName)
        return name
    }

    fun applyPreset(name: String) {
        val preset = savedPresets.firstOrNull { it.name == name } ?: return
        applyConfig(preset.config)
        persistConfig(preset.config)
        selectedPresetName = preset.name
        persistPresetLibrary(savedPresets, selectedPresetName)
        configStatusText = "已应用配置：${preset.name}"
    }

    val importPresetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val imported = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                InputStreamReader(input, Charsets.UTF_8).use { reader ->
                    parseImportedSpeedTestPreset(reader.readText())
                }
            }
        }.getOrNull()
        if (imported == null) {
            configStatusText = "导入失败，文件格式无效。"
            return@rememberLauncherForActivityResult
        }
        val mergedName = uniquePresetName(imported.name, savedPresets)
        val mergedPreset = imported.copy(name = mergedName)
        savedPresets = upsertPreset(savedPresets, mergedPreset)
        selectedPresetName = mergedPreset.name
        persistPresetLibrary(savedPresets, selectedPresetName)
        configStatusText = "已导入配置：${mergedPreset.name}"
    }
    val exportPresetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val exportPreset = selectedPresetName
            ?.let { name -> savedPresets.firstOrNull { it.name == name } }
            ?: SpeedTestNamedConfig(
                name = "当前配置",
                config = currentConfig()
            )
        val succeeded = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                    writer.write(exportPreset.toExportJson().toString(2))
                }
            } != null
        }.getOrDefault(false)
        configStatusText = if (succeeded) {
            "已导出配置：${exportPreset.name}"
        } else {
            "导出失败，无法写入目标文件。"
        }
    }

    fun clearDirectionLogs(direction: SpeedTestDirection) {
        val snapshotIndexes = threadSnapshots.mapIndexedNotNull { index, snapshot ->
            index.takeIf { snapshot.direction == direction }
        }.asReversed()
        snapshotIndexes.forEach { index -> threadSnapshots.removeAt(index) }

        val eventIndexes = eventLogs.mapIndexedNotNull { index, event ->
            index.takeIf { event.direction == direction }
        }.asReversed()
        eventIndexes.forEach { index -> eventLogs.removeAt(index) }
    }

    fun upsertSnapshot(snapshot: SpeedTestThreadSnapshot) {
        val index = threadSnapshots.indexOfFirst { it.id == snapshot.id }
        if (index >= 0) {
            threadSnapshots[index] = snapshot
        } else {
            threadSnapshots.add(snapshot)
        }
    }

    fun addEvent(event: SpeedTestLogEvent) {
        eventLogs.add(0, event)
        while (eventLogs.size > SPEED_TEST_MAX_EVENTS) {
            eventLogs.removeAt(eventLogs.lastIndex)
        }
    }

    fun buildCallbacks(direction: SpeedTestDirection): SpeedTestCallbacks {
        return SpeedTestCallbacks(
            onRunningChanged = { running ->
                if (direction == SpeedTestDirection.DOWNLOAD) {
                    downloadRunning = running
                } else {
                    uploadRunning = running
                }
            },
            onStatusChanged = { status ->
                if (direction == SpeedTestDirection.DOWNLOAD) {
                    downloadStatusText = status
                } else {
                    uploadStatusText = status
                }
            },
            onStatsChanged = { stats ->
                if (direction == SpeedTestDirection.DOWNLOAD) {
                    downloadStats = stats
                } else {
                    uploadStats = stats
                }
            },
            onChannelRatesChanged = { rates ->
                if (direction == SpeedTestDirection.DOWNLOAD) {
                    downloadChannelRates = rates
                } else {
                    uploadChannelRates = rates
                }
            },
            onActiveEndpointsChanged = { endpoints ->
                if (direction == SpeedTestDirection.DOWNLOAD) {
                    activeDownloadEndpoints = endpoints
                } else {
                    activeUploadEndpoints = endpoints
                }
            },
            onResetDirectionLogs = { clearDirectionLogs(direction) },
            onThreadSnapshotChanged = { snapshot -> upsertSnapshot(snapshot) },
            onLogEvent = { event -> addEvent(event) }
        )
    }

    fun startDownload(config: SpeedTestConfig) {
        val conflict = findConfigConflict(config)
        if (conflict != null) {
            configStatusText = conflict
            selectedPage = 2
            return
        }
        applyConfig(config)
        activeDownloadEndpoints = if (config.downloadStopConfig.autoSwitchEndpoints) {
            config.downloadEndpoints.firstOrNull { it.url.isNotBlank() }?.let(::listOf).orEmpty()
        } else {
            config.downloadEndpoints.filter { it.url.isNotBlank() }
        }
        selectedPage = 2
        downloadRunner.start(
            endpoints = config.downloadEndpoints,
            stopConfig = config.downloadStopConfig,
            speedLimitConfig = config.downloadSpeedLimitConfig,
            dualChannelEnabled = config.dualChannelEnabled,
            transferGate = transferGate,
            callbacks = buildCallbacks(SpeedTestDirection.DOWNLOAD)
        )
    }

    fun startUpload(config: SpeedTestConfig) {
        val conflict = findConfigConflict(config)
        if (conflict != null) {
            configStatusText = conflict
            selectedPage = 2
            return
        }
        applyConfig(config)
        activeUploadEndpoints = if (config.uploadStopConfig.autoSwitchEndpoints) {
            config.uploadEndpoints.firstOrNull { it.url.isNotBlank() }?.let(::listOf).orEmpty()
        } else {
            config.uploadEndpoints.filter { it.url.isNotBlank() }
        }
        selectedPage = 2
        uploadRunner.start(
            endpoints = config.uploadEndpoints,
            stopConfig = config.uploadStopConfig,
            speedLimitConfig = config.uploadSpeedLimitConfig,
            dualChannelEnabled = config.dualChannelEnabled,
            transferGate = transferGate,
            callbacks = buildCallbacks(SpeedTestDirection.UPLOAD)
        )
    }

    fun stopDownload(reason: String = "下载测速已停止。") {
        downloadRunner.stop(reason)
    }

    fun stopUpload(reason: String = "上传测速已停止。") {
        uploadRunner.stop(reason)
    }

    fun resetAll() {
        stopDownload()
        stopUpload()
        downloadRunning = false
        uploadRunning = false
        downloadStats = SpeedTestSessionStats()
        uploadStats = SpeedTestSessionStats()
        downloadChannelRates = emptyList()
        uploadChannelRates = emptyList()
        activeDownloadEndpoints = emptyList()
        activeUploadEndpoints = emptyList()
        transferGate.reset()
        alternatingPhaseText = "未启用"
        threadSnapshots.clear()
        eventLogs.clear()
        val defaults = defaultSpeedTestConfig()
        applyConfig(defaults)
        persistConfig(defaults)
        downloadStatusText = "下载测速未开始。"
        uploadStatusText = "上传测速未开始。"
        configStatusText = "已恢复默认配置。"
        selectedPage = 2
    }

    DisposableEffect(Unit) {
        onDispose {
            SpeedTestForegroundService.stop(context)
            transferGate.reset()
            downloadRunner.dispose()
            uploadRunner.dispose()
        }
    }

    LaunchedEffect(
        backgroundTestEnabled,
        superKeepAliveEnabled,
        downloadRunning,
        uploadRunning,
        downloadStats.realtimeSpeedBytesPerSecond,
        uploadStats.realtimeSpeedBytesPerSecond,
        downloadStats.usedBytes,
        uploadStats.usedBytes,
        downloadStats.elapsedMs,
        uploadStats.elapsedMs
    ) {
        val anyRunning = downloadRunning || uploadRunning
        if (backgroundTestEnabled && anyRunning) {
            SpeedTestForegroundService.start(
                context = context,
                downloadRunning = downloadRunning,
                uploadRunning = uploadRunning,
                downloadSpeedBytesPerSecond = downloadStats.realtimeSpeedBytesPerSecond,
                uploadSpeedBytesPerSecond = uploadStats.realtimeSpeedBytesPerSecond,
                totalUsedBytes = downloadStats.usedBytes + uploadStats.usedBytes,
                elapsedMs = maxOf(downloadStats.elapsedMs, uploadStats.elapsedMs),
                superKeepAliveEnabled = superKeepAliveEnabled
            )
        } else {
            SpeedTestForegroundService.stop(context)
        }
    }

    val alternatingWindowMs = alternatingWindowMsText.toIntOrNull()
        ?.coerceAtLeast(SPEED_TEST_ALTERNATING_MIN_WINDOW_MS)
        ?: SPEED_TEST_ALTERNATING_DEFAULT_WINDOW_MS
    val alternatingUploadPercentValue = alternatingUploadPercent.roundToInt().coerceIn(0, 100)
    val alternatingDownloadPercentValue = 100 - alternatingUploadPercentValue
    val alternatingFixedDownloadSecondsValue = alternatingFixedDownloadSecondsText.toIntOrNull()
        ?.coerceAtLeast(1)
        ?: SPEED_TEST_ALTERNATING_DEFAULT_DOWNLOAD_SECONDS
    val alternatingFixedUploadSecondsValue = alternatingFixedUploadSecondsText.toIntOrNull()
        ?.coerceAtLeast(1)
        ?: SPEED_TEST_ALTERNATING_DEFAULT_UPLOAD_SECONDS
    val alternatingRatioIdleLimitBytesPerSecond = alternatingRatioIdleLimitText.toDoubleOrNull()
        ?.takeIf { it > 0.0 }
        ?.let(::mbpsToBytesPerSecond)
        ?: mbpsToBytesPerSecond(SPEED_TEST_ALTERNATING_RATIO_IDLE_LIMIT_DEFAULT_MBPS)
    val alternatingFixedIdleLimitBytesPerSecond = alternatingFixedIdleLimitText.toDoubleOrNull()
        ?.takeIf { it > 0.0 }
        ?.let(::mbpsToBytesPerSecond)
        ?: mbpsToBytesPerSecond(SPEED_TEST_ALTERNATING_FIXED_IDLE_LIMIT_DEFAULT_MBPS)

    LaunchedEffect(
        alternatingTransferEnabled,
        alternatingTransferMode,
        alternatingUploadPercentValue,
        alternatingWindowMs,
        alternatingFixedDownloadSecondsValue,
        alternatingFixedUploadSecondsValue,
        alternatingRatioIdleLimitBytesPerSecond,
        alternatingFixedIdleStrategy,
        alternatingFixedIdleLimitBytesPerSecond,
        downloadRunning,
        uploadRunning,
        downloadSpeedLimitMode,
        uploadSpeedLimitMode
    ) {
        if (!alternatingTransferEnabled) {
            transferGate.reset()
            alternatingPhaseText = "未启用"
            return@LaunchedEffect
        }
        if (downloadSpeedLimitMode == SpeedLimitMode.FLOATING || uploadSpeedLimitMode == SpeedLimitMode.FLOATING) {
            transferGate.reset()
            alternatingPhaseText = "与浮动限速冲突，当前不生效"
            return@LaunchedEffect
        }
        if (!downloadRunning || !uploadRunning) {
            transferGate.reset()
            alternatingPhaseText = "等待上传和下载同时开始"
            return@LaunchedEffect
        }

        while (isActive && downloadRunning && uploadRunning && alternatingTransferEnabled) {
            when (alternatingTransferMode) {
                AlternatingTransferMode.RATIO -> {
                    when {
                        alternatingDownloadPercentValue <= 0 -> {
                            transferGate.setSoftLimits(
                                downloadLimitBytesPerSecond = null,
                                uploadLimitBytesPerSecond = null
                            )
                            transferGate.update(
                                enabled = true,
                                allowDownload = false,
                                allowUpload = true
                            )
                            alternatingPhaseText = "比例模式：仅上传"
                            delay(alternatingWindowMs.toLong())
                        }
                        alternatingUploadPercentValue <= 0 -> {
                            transferGate.setSoftLimits(
                                downloadLimitBytesPerSecond = null,
                                uploadLimitBytesPerSecond = null
                            )
                            transferGate.update(
                                enabled = true,
                                allowDownload = true,
                                allowUpload = false
                            )
                            alternatingPhaseText = "比例模式：仅下载"
                            delay(alternatingWindowMs.toLong())
                        }
                        else -> {
                            val estimatedCombinedBytesPerSecond = maxOf(
                                downloadStats.realtimeSpeedBytesPerSecond + uploadStats.realtimeSpeedBytesPerSecond,
                                downloadStats.averageSpeedBytesPerSecond + uploadStats.averageSpeedBytesPerSecond,
                                mbpsToBytesPerSecond(1.0).toDouble()
                            )
                            val totalBudgetBytesPerWindow =
                                (estimatedCombinedBytesPerSecond * alternatingWindowMs.toDouble() / 1000.0)
                                    .toLong()
                                    .coerceAtLeast(1L)
                            transferGate.configureSharedBudget(
                                windowMs = alternatingWindowMs,
                                totalBudgetBytesPerWindow = totalBudgetBytesPerWindow,
                                downloadPercent = alternatingDownloadPercentValue,
                                uploadPercent = alternatingUploadPercentValue,
                                idleLimitBytesPerSecond = alternatingRatioIdleLimitBytesPerSecond.toDouble()
                            )
                            alternatingPhaseText =
                                "比例模式：全线程共享总预算，下载 ${alternatingDownloadPercentValue}% / 上传 ${alternatingUploadPercentValue}%；超额方向压到 ${formatRate(alternatingRatioIdleLimitBytesPerSecond.toDouble())}"
                            delay(minOf(alternatingWindowMs.toLong(), 250L).coerceAtLeast(100L))
                        }
                    }
                }
                AlternatingTransferMode.FIXED -> {
                    val downloadSliceMs = alternatingFixedDownloadSecondsValue * 1000L
                    val uploadSliceMs = alternatingFixedUploadSecondsValue * 1000L
                    val useSoftLimit = alternatingFixedIdleStrategy == AlternatingIdleStrategy.SOFT_LIMIT
                    transferGate.setSoftLimits(
                        downloadLimitBytesPerSecond = null,
                        uploadLimitBytesPerSecond = if (useSoftLimit) alternatingFixedIdleLimitBytesPerSecond.toDouble() else null
                    )
                    transferGate.update(
                        enabled = !useSoftLimit,
                        allowDownload = true,
                        allowUpload = false
                    )
                    alternatingPhaseText = if (useSoftLimit) {
                        "固定模式：下载 ${alternatingFixedDownloadSecondsValue} 秒，上传压到 ${formatRate(alternatingFixedIdleLimitBytesPerSecond.toDouble())}"
                    } else {
                        "固定模式：下载 ${alternatingFixedDownloadSecondsValue} 秒，上传完全切断"
                    }
                    delay(downloadSliceMs)
                    if (!isActive || !downloadRunning || !uploadRunning || !alternatingTransferEnabled) break
                    transferGate.setSoftLimits(
                        downloadLimitBytesPerSecond = if (useSoftLimit) alternatingFixedIdleLimitBytesPerSecond.toDouble() else null,
                        uploadLimitBytesPerSecond = null
                    )
                    transferGate.update(
                        enabled = !useSoftLimit,
                        allowDownload = false,
                        allowUpload = true
                    )
                    alternatingPhaseText = if (useSoftLimit) {
                        "固定模式：上传 ${alternatingFixedUploadSecondsValue} 秒，下载压到 ${formatRate(alternatingFixedIdleLimitBytesPerSecond.toDouble())}"
                    } else {
                        "固定模式：上传 ${alternatingFixedUploadSecondsValue} 秒，下载完全切断"
                    }
                    delay(uploadSliceMs)
                }
            }
        }
        transferGate.reset()
        if (!downloadRunning || !uploadRunning) {
            alternatingPhaseText = "等待上传和下载同时开始"
        }
    }

    val orderedSnapshots = threadSnapshots.sortedWith(
        compareBy<SpeedTestThreadSnapshot>(
            { it.direction.ordinal },
            { it.endpointIndex },
            { it.threadIndex },
            { it.networkLabel }
        )
    )
    val visibleResultCards = buildList {
        if (downloadStats.visible) add(SpeedTestDirection.DOWNLOAD to downloadStats)
        if (uploadStats.visible) add(SpeedTestDirection.UPLOAD to uploadStats)
    }
    val downloadChannelRateMap = downloadChannelRates.associateBy { it.networkLabel }
    val uploadChannelRateMap = uploadChannelRates.associateBy { it.networkLabel }
    val channelContributionRows = (downloadChannelRateMap.keys + uploadChannelRateMap.keys)
        .distinct()
        .map { label ->
            val down = downloadChannelRateMap[label]
            val up = uploadChannelRateMap[label]
            Triple(
                label,
                down,
                up
            )
        }
        .sortedByDescending { (_, down, up) ->
            (down?.realtimeSpeedBytesPerSecond ?: 0.0) + (up?.realtimeSpeedBytesPerSecond ?: 0.0)
        }
    var statusConfigExpanded by remember { mutableStateOf(false) }
    var alternatingCardExpanded by remember { mutableStateOf(false) }
    var resultCardExpanded by remember { mutableStateOf(true) }
    var endpointCardExpanded by remember { mutableStateOf(false) }
    var logCardExpanded by remember { mutableStateOf(true) }
    var channelContributionExpanded by remember { mutableStateOf(false) }
    var presetManagerExpanded by remember { mutableStateOf(false) }
    val pageTitles = listOf("下载配置", "上传配置", "传输状态")
    val hasSavedDownload = savedConfig.downloadEndpoints.any { it.url.isNotBlank() }
    val hasSavedUpload = savedConfig.uploadEndpoints.any { it.url.isNotBlank() }
    val selectedPreset = selectedPresetName?.let { name -> savedPresets.firstOrNull { it.name == name } }
    val stealthTapInteractionSource = remember { MutableInteractionSource() }

    if (showStealthCodeDialog) {
        AlertDialog(
            onDismissRequest = {
                showStealthCodeDialog = false
                stealthCodeInput = ""
            },
            text = {
                OutlinedTextField(
                    value = stealthCodeInput,
                    onValueChange = { value ->
                        stealthCodeInput = value.filter { it.isDigit() }.take(6)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (stealthCodeInput.trim()) {
                            "147258" -> {
                                stealthModeEnabled = true
                                defaultOpenAppToSpeedTest = true
                                prefs.edit()
                                    .putBoolean(SPEED_TEST_KEY_STEALTH_MODE, true)
                                    .putBoolean(SPEED_TEST_KEY_DEFAULT_APP_ENTRY, true)
                                    .apply()
                            }
                            "852741" -> {
                                stealthModeEnabled = false
                                defaultOpenAppToSpeedTest = false
                                prefs.edit()
                                    .putBoolean(SPEED_TEST_KEY_STEALTH_MODE, false)
                                    .putBoolean(SPEED_TEST_KEY_DEFAULT_APP_ENTRY, false)
                                    .apply()
                            }
                        }
                        showStealthCodeDialog = false
                        stealthCodeInput = ""
                    }
                ) {
                    Text("确定")
                }
            }
        )
    }
    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = {
                showSavePresetDialog = false
                savePresetNameInput = ""
            },
            text = {
                OutlinedTextField(
                    value = savePresetNameInput,
                    onValueChange = { savePresetNameInput = it.take(40) },
                    label = { Text("配置名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val normalizedName = normalizePresetName(savePresetNameInput)
                        if (normalizedName.isBlank()) {
                            configStatusText = "配置名称不能为空。"
                            return@TextButton
                        }
                        val config = currentConfig()
                        val conflict = findConfigConflict(config)
                        if (conflict != null) {
                            configStatusText = conflict
                            return@TextButton
                        }
                        applyConfig(config)
                        persistConfig(config)
                        savePreset(normalizedName, config)
                        configStatusText = "已保存配置：$normalizedName"
                        showSavePresetDialog = false
                        savePresetNameInput = ""
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSavePresetDialog = false
                        savePresetNameInput = ""
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }
    if (showCreatePresetDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreatePresetDialog = false
                createPresetNameInput = ""
            },
            text = {
                OutlinedTextField(
                    value = createPresetNameInput,
                    onValueChange = { createPresetNameInput = it.take(40) },
                    label = { Text("配置名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val normalizedName = normalizePresetName(createPresetNameInput)
                        if (normalizedName.isBlank()) {
                            configStatusText = "配置名称不能为空。"
                            return@TextButton
                        }
                        if (savedPresets.any { it.name == normalizedName }) {
                            configStatusText = "已存在同名配置。"
                            return@TextButton
                        }
                        val config = defaultSpeedTestConfig()
                        applyConfig(config)
                        persistConfig(config)
                        savePreset(normalizedName, config)
                        configStatusText = "已新增配置：$normalizedName"
                        showCreatePresetDialog = false
                        createPresetNameInput = ""
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCreatePresetDialog = false
                        createPresetNameInput = ""
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }
    if (showRenamePresetDialog) {
        AlertDialog(
            onDismissRequest = {
                showRenamePresetDialog = false
                renamePresetNameInput = ""
            },
            text = {
                OutlinedTextField(
                    value = renamePresetNameInput,
                    onValueChange = { renamePresetNameInput = it.take(40) },
                    label = { Text("新名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val currentName = selectedPreset?.name ?: return@TextButton
                        val normalizedName = normalizePresetName(renamePresetNameInput)
                        if (normalizedName.isBlank()) {
                            configStatusText = "配置名称不能为空。"
                            return@TextButton
                        }
                        if (savedPresets.any { it.name == normalizedName && it.name != currentName }) {
                            configStatusText = "已存在同名配置。"
                            return@TextButton
                        }
                        val renamedPresets = savedPresets.map { preset ->
                            if (preset.name == currentName) preset.copy(name = normalizedName) else preset
                        }
                        savedPresets = renamedPresets
                        selectedPresetName = normalizedName
                        persistPresetLibrary(savedPresets, selectedPresetName)
                        configStatusText = "已重命名为：$normalizedName"
                        showRenamePresetDialog = false
                        renamePresetNameInput = ""
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRenamePresetDialog = false
                        renamePresetNameInput = ""
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }
    LaunchedEffect(downloadRunning, uploadRunning) {
        if (downloadRunning || uploadRunning) {
            resultCardExpanded = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!stealthModeEnabled) {
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("返回传输实验室")
            }
        }

        Text(
            "SpeedTest",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(
                interactionSource = stealthTapInteractionSource,
                indication = null
            ) {
                speedTestTitleTapCount += 1
                if (speedTestTitleTapCount >= 10) {
                    speedTestTitleTapCount = 0
                    showStealthCodeDialog = true
                    stealthCodeInput = ""
                }
            }
        )
        SpeedTestPresetManagerCard(
            expanded = presetManagerExpanded,
            presets = savedPresets,
            selectedPresetName = selectedPresetName,
            statusText = configStatusText,
            onExpandedChange = { presetManagerExpanded = it },
            onSelectedPresetNameChange = {
                selectedPresetName = it
                persistPresetLibrary(savedPresets, selectedPresetName)
            },
            onQuickSwitch = { name ->
                applyPreset(name)
            },
            onApply = {
                val name = selectedPresetName ?: return@SpeedTestPresetManagerCard
                applyPreset(name)
            },
            onSaveCurrent = {
                savePresetNameInput = selectedPreset?.name.orEmpty()
                showSavePresetDialog = true
            },
            onCreatePreset = {
                createPresetNameInput = ""
                showCreatePresetDialog = true
            },
            onCreateTemporary = {
                val config = defaultSpeedTestConfig()
                applyConfig(config)
                persistConfig(config)
                selectedPresetName = null
                persistPresetLibrary(savedPresets, selectedPresetName)
                configStatusText = "已切换到临时配置。"
            },
            onDelete = {
                val name = selectedPresetName ?: return@SpeedTestPresetManagerCard
                savedPresets = savedPresets.filterNot { it.name == name }
                selectedPresetName = savedPresets.firstOrNull()?.name
                persistPresetLibrary(savedPresets, selectedPresetName)
                configStatusText = "已删除配置：$name"
            },
            onRename = {
                val name = selectedPreset?.name ?: return@SpeedTestPresetManagerCard
                renamePresetNameInput = name
                showRenamePresetDialog = true
            },
            onImport = {
                importPresetLauncher.launch(arrayOf("application/json", "text/plain"))
            },
            onExport = {
                val fileName = sanitizeExportFileName(selectedPreset?.name ?: "speedtest_config")
                exportPresetLauncher.launch("$fileName.json")
            }
        )

        TabRow(selectedTabIndex = selectedPage) {
            pageTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedPage == index,
                    onClick = { selectedPage = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedPage) {
            0 -> {
                SpeedTestConfigCard(
                    title = "下载配置",
                    statusText = downloadStatusText,
                    running = downloadRunning,
                    drafts = downloadDrafts,
                    directionLabel = "下载",
                    durationText = downloadDurationText,
                    durationUnit = downloadDurationUnit,
                    trafficLimitText = downloadTrafficLimitText,
                    trafficLimitUnit = downloadTrafficLimitUnit,
                    autoSwitchEnabled = downloadAutoSwitchEnabled,
                    speedLimitMode = downloadSpeedLimitMode,
                    fixedLimitText = downloadFixedLimitText,
                    floatingIntervalText = downloadFloatingIntervalText,
                    floatingPercentText = downloadFloatingPercentText,
                    onDraftChange = { index, draft ->
                        downloadDrafts = updateDraft(downloadDrafts, index) { draft }
                    },
                    onDurationChange = { downloadDurationText = normalizePositiveText(it) },
                    onDurationUnitChange = { nextUnit ->
                        downloadDurationText = convertDurationValueText(
                            valueText = downloadDurationText,
                            fromUnit = downloadDurationUnit,
                            toUnit = nextUnit
                        )
                        downloadDurationUnit = nextUnit
                    },
                    onTrafficLimitChange = { downloadTrafficLimitText = normalizePositiveText(it) },
                    onTrafficLimitUnitChange = { nextUnit ->
                        downloadTrafficLimitText = convertTrafficValueText(
                            valueText = downloadTrafficLimitText,
                            fromUnit = downloadTrafficLimitUnit,
                            toUnit = nextUnit
                        )
                        downloadTrafficLimitUnit = nextUnit
                    },
                    onAutoSwitchChange = { downloadAutoSwitchEnabled = it },
                    onSpeedLimitModeChange = { downloadSpeedLimitMode = it },
                    onFixedLimitChange = { downloadFixedLimitText = normalizeDecimalText(it) },
                    onFloatingIntervalChange = { downloadFloatingIntervalText = normalizePositiveText(it) },
                    onFloatingPercentChange = { uploadValue ->
                        downloadFloatingPercentText = normalizePositiveText(uploadValue)
                    },
                    onStart = { startDownload(currentConfig()) },
                    onStop = { stopDownload() }
                )
            }
            1 -> {
                SpeedTestConfigCard(
                    title = "上传配置",
                    statusText = uploadStatusText,
                    running = uploadRunning,
                    drafts = uploadDrafts,
                    directionLabel = "上传",
                    durationText = uploadDurationText,
                    durationUnit = uploadDurationUnit,
                    trafficLimitText = uploadTrafficLimitText,
                    trafficLimitUnit = uploadTrafficLimitUnit,
                    autoSwitchEnabled = uploadAutoSwitchEnabled,
                    speedLimitMode = uploadSpeedLimitMode,
                    fixedLimitText = uploadFixedLimitText,
                    floatingIntervalText = uploadFloatingIntervalText,
                    floatingPercentText = uploadFloatingPercentText,
                    onDraftChange = { index, draft ->
                        uploadDrafts = updateDraft(uploadDrafts, index) { draft }
                    },
                    onDurationChange = { uploadDurationText = normalizePositiveText(it) },
                    onDurationUnitChange = { nextUnit ->
                        uploadDurationText = convertDurationValueText(
                            valueText = uploadDurationText,
                            fromUnit = uploadDurationUnit,
                            toUnit = nextUnit
                        )
                        uploadDurationUnit = nextUnit
                    },
                    onTrafficLimitChange = { uploadTrafficLimitText = normalizePositiveText(it) },
                    onTrafficLimitUnitChange = { nextUnit ->
                        uploadTrafficLimitText = convertTrafficValueText(
                            valueText = uploadTrafficLimitText,
                            fromUnit = uploadTrafficLimitUnit,
                            toUnit = nextUnit
                        )
                        uploadTrafficLimitUnit = nextUnit
                    },
                    onAutoSwitchChange = { uploadAutoSwitchEnabled = it },
                    onSpeedLimitModeChange = { uploadSpeedLimitMode = it },
                    onFixedLimitChange = { uploadFixedLimitText = normalizeDecimalText(it) },
                    onFloatingIntervalChange = { uploadFloatingIntervalText = normalizePositiveText(it) },
                    onFloatingPercentChange = { uploadFloatingPercentText = normalizePositiveText(it) },
                    onStart = { startUpload(currentConfig()) },
                    onStop = { stopUpload() }
                )
            }
            else -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("已保存配置一键开始", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            OutlinedButton(
                                onClick = { statusConfigExpanded = !statusConfigExpanded }
                            ) {
                                Text(if (statusConfigExpanded) "收起" else "展开")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    if (downloadRunning) {
                                        stopDownload()
                                    } else {
                                        startDownload(currentConfig())
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = hasSavedDownload || downloadRunning,
                                colors = if (downloadRunning) {
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                } else {
                                    ButtonDefaults.buttonColors()
                                }
                            ) {
                                Text(if (downloadRunning) "一键停止下载" else "一键开始下载")
                            }
                            Button(
                                onClick = {
                                    if (uploadRunning) {
                                        stopUpload()
                                    } else {
                                        startUpload(currentConfig())
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = hasSavedUpload || uploadRunning,
                                colors = if (uploadRunning) {
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                } else {
                                    ButtonDefaults.buttonColors()
                                }
                            ) {
                                Text(if (uploadRunning) "一键停止上传" else "一键开始上传")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val anyRunning = downloadRunning || uploadRunning
                            Button(
                                onClick = {
                                    if (anyRunning) {
                                        stopDownload()
                                        stopUpload()
                                    } else {
                                        val config = currentConfig()
                                        startDownload(config)
                                        startUpload(config)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = anyRunning || (hasSavedDownload && hasSavedUpload),
                                colors = if (anyRunning) {
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    )
                                } else {
                                    ButtonDefaults.buttonColors()
                                }
                            ) {
                                Text(if (anyRunning) "一键停止" else "一键开始上传+下载")
                            }
                        }
                        if (statusConfigExpanded) {
                            Text(
                                "如果你之前已经保存过配置，下次进入这里可以直接一键开始下载、上传，或同时开始两者。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("显示线程日志", modifier = Modifier.weight(1f))
                                Switch(
                                    checked = logEnabled,
                                    onCheckedChange = { checked -> logEnabled = checked }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val dualChannelLocked = downloadRunning || uploadRunning
                                Text("多通道并发（双WiFi + 数据）", modifier = Modifier.weight(1f))
                                Switch(
                                    checked = dualChannelEnabled,
                                    onCheckedChange = { checked ->
                                        dualChannelEnabled = checked
                                        Toast.makeText(
                                            context,
                                            "多通道并发设置将在下次开始测速时生效",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    enabled = !dualChannelLocked
                                )
                            }
                            if (downloadRunning || uploadRunning) {
                                Text(
                                    "测速进行中，多通道并发开关已锁定；停止后可修改。",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (dualChannelEnabled) {
                                Text(
                                    "启用后优先尝试三频并发（2.4G WiFi + 5G/6G WiFi + 蜂窝）；设备或系统不支持时会自动降级为双通道或单通道。",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("打开 App 时默认进入 SpeedTest", modifier = Modifier.weight(1f))
                                Switch(
                                    checked = defaultOpenAppToSpeedTest || stealthModeEnabled,
                                    onCheckedChange = { checked ->
                                        if (stealthModeEnabled) return@Switch
                                        defaultOpenAppToSpeedTest = checked
                                        prefs.edit()
                                            .putBoolean(SPEED_TEST_KEY_DEFAULT_APP_ENTRY, checked)
                                            .apply()
                                    },
                                    enabled = !stealthModeEnabled
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("允许后台测试（保活通知）", modifier = Modifier.weight(1f))
                                Switch(
                                    checked = backgroundTestEnabled,
                                    onCheckedChange = { checked ->
                                        if (checked && !isNotificationPermissionGranted(context)) {
                                            pendingEnableBackgroundTest = true
                                            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            return@Switch
                                        }
                                        backgroundTestEnabled = checked
                                        prefs.edit()
                                            .putBoolean(SPEED_TEST_KEY_BACKGROUND_TEST, checked)
                                            .apply()
                                    }
                                )
                            }
                            if (!notificationPermissionGranted) {
                                Text(
                                    "通知权限：未授权（部分设备下后台保活通知可能无法显示）",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(
                                    onClick = {
                                        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("申请通知权限")
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("超强保活模式（无声音乐 + 透明悬浮窗）", modifier = Modifier.weight(1f))
                                Switch(
                                    checked = superKeepAliveEnabled,
                                    onCheckedChange = { checked ->
                                        superKeepAliveEnabled = checked
                                        prefs.edit()
                                            .putBoolean(SPEED_TEST_KEY_SUPER_KEEP_ALIVE, checked)
                                            .apply()
                                        if (checked && !overlayPermissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            configStatusText = "超强保活已开启，但悬浮窗权限未授权，请先授权以启用透明悬浮窗。"
                                        }
                                    }
                                )
                            }
                            if (superKeepAliveEnabled && !overlayPermissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        ).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        runCatching { context.startActivity(intent) }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("授权透明悬浮窗权限")
                                }
                            }
                            Text(
                                "开启后，普通方式打开 App 会直接进入 SpeedTest，同时关闭底部主页面之间的左右滑动，避免误切换。通过系统分享进入时不受影响。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "开启后台测试后，进行中的测速任务会显示前台通知，并实时更新速度、测试时长和总流量；应用切到后台时也可继续运行。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (superKeepAliveEnabled) {
                                Text(
                                    "超强保活模式会启用无声音频循环与透明悬浮窗，保活能力更强，但会增加耗电。",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            AlternatingTransferCard(
                                expanded = alternatingCardExpanded,
                                enabled = alternatingTransferEnabled,
                                mode = alternatingTransferMode,
                                uploadPercent = alternatingUploadPercent,
                                windowMsText = alternatingWindowMsText,
                                fixedDownloadSecondsText = alternatingFixedDownloadSecondsText,
                                fixedUploadSecondsText = alternatingFixedUploadSecondsText,
                                ratioIdleLimitText = alternatingRatioIdleLimitText,
                                fixedIdleStrategy = alternatingFixedIdleStrategy,
                                fixedIdleLimitText = alternatingFixedIdleLimitText,
                                phaseText = alternatingPhaseText,
                                hasConflict = downloadSpeedLimitMode == SpeedLimitMode.FLOATING ||
                                    uploadSpeedLimitMode == SpeedLimitMode.FLOATING,
                                ready = downloadRunning && uploadRunning,
                                onEnabledChange = { alternatingTransferEnabled = it },
                                onModeChange = { alternatingTransferMode = it },
                                onUploadPercentChange = { alternatingUploadPercent = it },
                                onWindowMsChange = { alternatingWindowMsText = normalizePositiveText(it) },
                                onFixedDownloadSecondsChange = { alternatingFixedDownloadSecondsText = normalizePositiveText(it) },
                                onFixedUploadSecondsChange = { alternatingFixedUploadSecondsText = normalizePositiveText(it) },
                                onRatioIdleLimitChange = { alternatingRatioIdleLimitText = normalizeDecimalText(it) },
                                onFixedIdleStrategyChange = { alternatingFixedIdleStrategy = it },
                                onFixedIdleLimitChange = { alternatingFixedIdleLimitText = normalizeDecimalText(it) },
                                onExpandedChange = { alternatingCardExpanded = it }
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("测速结果卡片", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            OutlinedButton(
                                onClick = { resultCardExpanded = !resultCardExpanded }
                            ) {
                                Text(if (resultCardExpanded) "收起" else "展开")
                            }
                        }
                        if (resultCardExpanded) {
                            if (visibleResultCards.isEmpty()) {
                                Text(
                                    "开始下载或上传测速后，这里会显示对应项目的实时速度、平均速度、已用流量和耗时。若只测单项，仅显示该单项卡片。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (visibleResultCards.size == 1) {
                                val item = visibleResultCards.first()
                                SpeedSessionResultCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    title = "${item.first.label}测速",
                                    stats = item.second
                                )
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    visibleResultCards.forEach { item ->
                                        SpeedSessionResultCard(
                                            modifier = Modifier.fillMaxWidth(),
                                            title = "${item.first.label}测速",
                                            stats = item.second
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                "已折叠，点击“展开”查看下载/上传测速指标。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("当前传输链接与线程", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            OutlinedButton(
                                onClick = { endpointCardExpanded = !endpointCardExpanded }
                            ) {
                                Text(if (endpointCardExpanded) "收起" else "展开")
                            }
                        }
                        val activeTotal = activeDownloadEndpoints.size + activeUploadEndpoints.size
                        Text(
                            "当前活跃链接数量：$activeTotal（下载 ${activeDownloadEndpoints.size} / 上传 ${activeUploadEndpoints.size}）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (endpointCardExpanded) {
                            Text("下载链接", fontWeight = FontWeight.SemiBold)
                            if (activeDownloadEndpoints.isEmpty()) {
                                Text("当前没有正在测试的下载链接。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                activeDownloadEndpoints.forEachIndexed { index, endpoint ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text("下载地址 ${index + 1}", fontWeight = FontWeight.SemiBold)
                                            Text(endpoint.url, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text("线程数量: ${endpoint.threads}")
                                        }
                                    }
                                }
                            }
                            Text("上传链接", fontWeight = FontWeight.SemiBold)
                            if (activeUploadEndpoints.isEmpty()) {
                                Text("当前没有正在测试的上传链接。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                activeUploadEndpoints.forEachIndexed { index, endpoint ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text("上传地址 ${index + 1}", fontWeight = FontWeight.SemiBold)
                                            Text(endpoint.url, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text("线程数量: ${endpoint.threads}")
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(
                                "默认折叠，点击“展开”查看当前下载/上传链接与线程分配。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (dualChannelEnabled) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("通道贡献速率", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                OutlinedButton(
                                    onClick = { channelContributionExpanded = !channelContributionExpanded }
                                ) {
                                    Text(if (channelContributionExpanded) "收起" else "展开")
                                }
                            }
                            Text(
                                "当前识别到 ${channelContributionRows.size} 个通道。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!channelContributionExpanded) {
                                Text(
                                    "默认折叠，点击“展开”可查看各通道实时/平均速率与流量贡献。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (channelContributionRows.isEmpty()) {
                                Text(
                                    "尚未采集到通道速率数据。开始测速后，这里会展示各通道的下载/上传贡献速率，便于确认当前走了哪些通道。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                channelContributionRows.forEach { (label, down, up) ->
                                    val downRealtime = down?.realtimeSpeedBytesPerSecond ?: 0.0
                                    val upRealtime = up?.realtimeSpeedBytesPerSecond ?: 0.0
                                    val downAverage = down?.averageSpeedBytesPerSecond ?: 0.0
                                    val upAverage = up?.averageSpeedBytesPerSecond ?: 0.0
                                    val downUsed = down?.usedBytes ?: 0L
                                    val upUsed = up?.usedBytes ?: 0L
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(label, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "实时: 下 ${formatRate(downRealtime)} / 上 ${formatRate(upRealtime)} / 合计 ${formatRate(downRealtime + upRealtime)}",
                                                fontSize = 12.sp
                                            )
                                            Text(
                                                "平均: 下 ${formatRate(downAverage)} / 上 ${formatRate(upAverage)} / 合计 ${formatRate(downAverage + upAverage)}",
                                                fontSize = 12.sp
                                            )
                                            Text(
                                                "流量: 下 ${formatBytes(downUsed)} / 上 ${formatBytes(upUsed)} / 合计 ${formatBytes(downUsed + upUsed)}",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (logEnabled) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("线程日志", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                OutlinedButton(
                                    onClick = { logCardExpanded = !logCardExpanded }
                                ) {
                                    Text(if (logCardExpanded) "收起" else "展开")
                                }
                            }
                            if (logCardExpanded) {
                                if (orderedSnapshots.isEmpty()) {
                                    Text(
                                        "尚无线程活动。开始测速后，这里会展示每个线程的连接状态、错误原因和错误代码。",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    orderedSnapshots.forEach { snapshot ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    "${snapshot.direction.label} / 地址${snapshot.endpointIndex + 1} / 线程${snapshot.threadIndex} / ${snapshot.networkLabel}",
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    snapshot.url,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text("状态: ${snapshot.state}")
                                                Text("累计流量: ${formatBytes(snapshot.bytesTransferred)}")
                                                snapshot.responseCode?.let { Text("HTTP 状态码: $it") }
                                                snapshot.errorCode?.let { Text("错误代码: $it") }
                                                snapshot.errorReason?.let { Text("错误原因: $it") }
                                                Text(
                                                    "更新时间: ${timeFormat.format(Date(snapshot.updatedAtMs))}",
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }

                                if (eventLogs.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("最近事件", fontWeight = FontWeight.SemiBold)
                                    eventLogs.take(60).forEach { event ->
                                        Text(
                                            "[${timeFormat.format(Date(event.timestampMs))}] ${event.direction.label} ${event.workerLabel} - ${event.message}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    "已折叠，点击“展开”查看线程状态与事件日志。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlternatingTransferCard(
    expanded: Boolean,
    enabled: Boolean,
    mode: AlternatingTransferMode,
    uploadPercent: Float,
    windowMsText: String,
    fixedDownloadSecondsText: String,
    fixedUploadSecondsText: String,
    ratioIdleLimitText: String,
    fixedIdleStrategy: AlternatingIdleStrategy,
    fixedIdleLimitText: String,
    phaseText: String,
    hasConflict: Boolean,
    ready: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onModeChange: (AlternatingTransferMode) -> Unit,
    onUploadPercentChange: (Float) -> Unit,
    onWindowMsChange: (String) -> Unit,
    onFixedDownloadSecondsChange: (String) -> Unit,
    onFixedUploadSecondsChange: (String) -> Unit,
    onRatioIdleLimitChange: (String) -> Unit,
    onFixedIdleStrategyChange: (AlternatingIdleStrategy) -> Unit,
    onFixedIdleLimitChange: (String) -> Unit,
    onExpandedChange: (Boolean) -> Unit
) {
    val normalizedUploadPercent = uploadPercent.roundToInt().coerceIn(0, 100)
    val normalizedDownloadPercent = 100 - normalizedUploadPercent
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("交替传输", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = { onExpandedChange(!expanded) }
                ) {
                    Text(if (expanded) "收起" else "展开")
                }
            }
            if (!expanded) {
                Text(
                    "默认折叠，点击“展开”可配置比例/固定两种交替模式。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "上传与下载都已启动时，按时间片轮流调度。比例模式会在一个窗口内先给下载、再给上传，并把非当前时隙方向压到设定值；固定模式会按你设定的下载/上传时长循环切换。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("启用交替传输", modifier = Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = onEnabledChange
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AlternatingTransferMode.entries.forEach { item ->
                        if (mode == item) {
                            Button(onClick = { onModeChange(item) }) {
                                Text(item.label)
                            }
                        } else {
                            OutlinedButton(onClick = { onModeChange(item) }) {
                                Text(item.label)
                            }
                        }
                    }
                }
                if (mode == AlternatingTransferMode.RATIO) {
                    Text("下载 ${normalizedDownloadPercent}% / 上传 ${normalizedUploadPercent}%")
                    Slider(
                        value = uploadPercent.coerceIn(0f, 100f),
                        onValueChange = onUploadPercentChange,
                        valueRange = 0f..100f
                    )
                    OutlinedTextField(
                        value = windowMsText,
                        onValueChange = onWindowMsChange,
                        label = { Text("窗口时间（毫秒）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ratioIdleLimitText,
                        onValueChange = onRatioIdleLimitChange,
                        label = { Text("反向压制速度（Mbps）") },
                        supportingText = { Text("比例模式默认 0.1 Mbps，非当前时隙方向会被压到这个值。") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = fixedDownloadSecondsText,
                            onValueChange = onFixedDownloadSecondsChange,
                            label = { Text("下载时长（秒）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = fixedUploadSecondsText,
                            onValueChange = onFixedUploadSecondsChange,
                            label = { Text("上传时长（秒）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AlternatingIdleStrategy.entries.forEach { item ->
                            if (fixedIdleStrategy == item) {
                                Button(onClick = { onFixedIdleStrategyChange(item) }) {
                                    Text(item.label)
                                }
                            } else {
                                OutlinedButton(onClick = { onFixedIdleStrategyChange(item) }) {
                                    Text(item.label)
                                }
                            }
                        }
                    }
                    if (fixedIdleStrategy == AlternatingIdleStrategy.SOFT_LIMIT) {
                        OutlinedTextField(
                            value = fixedIdleLimitText,
                            onValueChange = onFixedIdleLimitChange,
                            label = { Text("反向压制速度（Mbps）") },
                            supportingText = { Text("固定模式默认压到 0.5 Mbps；切到完全切断时此项不生效。") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Text(
                    when {
                        hasConflict -> "交替传输与浮动限速互斥，请关闭其中一个功能。"
                        !ready -> "需要上传和下载都已开始，交替传输才会生效。"
                        enabled -> "当前阶段：$phaseText"
                        else -> "当前阶段：未启用"
                    },
                    fontSize = 12.sp,
                    color = if (hasConflict) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun SpeedSessionResultCard(
    modifier: Modifier = Modifier,
    title: String,
    stats: SpeedTestSessionStats
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            MetricValueLine("实时速度", formatRate(stats.realtimeSpeedBytesPerSecond))
            MetricValueLine("平均速度", formatRate(stats.averageSpeedBytesPerSecond))
            MetricValueLine("已用流量", formatBytes(stats.usedBytes))
            MetricValueLine("耗时", formatElapsed(stats.elapsedMs))
            Text(
                if (stats.running) "测速中" else "未运行",
                color = if (stats.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MetricValueLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ActiveEndpointCard(
    modifier: Modifier = Modifier,
    title: String,
    endpoints: List<SpeedTestEndpointConfig>,
    emptyText: String
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (endpoints.isEmpty()) {
                Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                endpoints.forEachIndexed { index, endpoint ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("地址 ${index + 1}", fontWeight = FontWeight.SemiBold)
                            Text(endpoint.url, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("线程数量: ${endpoint.threads}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadShortcutRow(
    currentThreadsText: String,
    onSelect: (String) -> Unit
) {
    val shortcuts = listOf("1", "4", "8", "16", "32")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        shortcuts.forEach { value ->
            if (currentThreadsText == value) {
                Button(onClick = { onSelect(value) }) {
                    Text(value)
                }
            } else {
                OutlinedButton(onClick = { onSelect(value) }) {
                    Text(value)
                }
            }
        }
    }
}

@Composable
private fun <T> UnitDropdownField(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label(selected),
                    modifier = Modifier.weight(1f)
                )
                Text(if (expanded) "▲" else "▼")
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SpeedTestConfigCard(
    title: String,
    statusText: String,
    running: Boolean,
    drafts: List<SpeedTestEndpointDraft>,
    directionLabel: String,
    durationText: String,
    durationUnit: StopDurationUnit,
    trafficLimitText: String,
    trafficLimitUnit: StopTrafficUnit,
    autoSwitchEnabled: Boolean,
    speedLimitMode: SpeedLimitMode,
    fixedLimitText: String,
    floatingIntervalText: String,
    floatingPercentText: String,
    onDraftChange: (Int, SpeedTestEndpointDraft) -> Unit,
    onDurationChange: (String) -> Unit,
    onDurationUnitChange: (StopDurationUnit) -> Unit,
    onTrafficLimitChange: (String) -> Unit,
    onTrafficLimitUnitChange: (StopTrafficUnit) -> Unit,
    onAutoSwitchChange: (Boolean) -> Unit,
    onSpeedLimitModeChange: (SpeedLimitMode) -> Unit,
    onFixedLimitChange: (String) -> Unit,
    onFloatingIntervalChange: (String) -> Unit,
    onFloatingPercentChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    var endpointsExpanded by remember(title) { mutableStateOf(true) }
    var endpointExpandedStates by remember(title) {
        mutableStateOf(List(drafts.size) { true })
    }
    val configuredEndpointCount = drafts.count { it.url.isNotBlank() }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(statusText, color = MaterialTheme.colorScheme.primary)
            Text(
                if (autoSwitchEnabled) {
                    "已开启满足条件自动更换链接。当前每次只测试一个${directionLabel}链接，达到固定时长或流量上限后自动切换下一个链接。"
                } else {
                    "固定时长或流量上限满足任一条件时，会自动停止${directionLabel}测速。留空表示不限。"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = durationText,
                    onValueChange = onDurationChange,
                    label = { Text("固定时长") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                UnitDropdownField(
                    options = StopDurationUnit.entries,
                    selected = durationUnit,
                    label = { it.label },
                    onSelect = onDurationUnitChange,
                    modifier = Modifier.width(110.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = trafficLimitText,
                    onValueChange = onTrafficLimitChange,
                    label = { Text("流量上限") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                UnitDropdownField(
                    options = StopTrafficUnit.entries,
                    selected = trafficLimitUnit,
                    label = { it.label },
                    onSelect = onTrafficLimitUnitChange,
                    modifier = Modifier.width(110.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("满足条件自动更换链接", modifier = Modifier.weight(1f))
                Switch(
                    checked = autoSwitchEnabled,
                    onCheckedChange = onAutoSwitchChange
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("速度限制", fontWeight = FontWeight.SemiBold)
                    Text(
                        when (speedLimitMode) {
                            SpeedLimitMode.OFF -> "当前不限速。"
                            SpeedLimitMode.FIXED -> "固定模式会把${directionLabel}速度限制在你设定的上限以内。"
                            SpeedLimitMode.FLOATING -> "浮动模式会定时解除限制探测最大速度，然后按设定比例进行限速。"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SpeedLimitMode.entries.forEach { mode ->
                            if (speedLimitMode == mode) {
                                Button(onClick = { onSpeedLimitModeChange(mode) }) {
                                    Text(mode.label)
                                }
                            } else {
                                OutlinedButton(onClick = { onSpeedLimitModeChange(mode) }) {
                                    Text(mode.label)
                                }
                            }
                        }
                    }

                    if (speedLimitMode == SpeedLimitMode.FIXED) {
                        OutlinedTextField(
                            value = fixedLimitText,
                            onValueChange = onFixedLimitChange,
                            label = { Text("固定上限（Mbps）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (speedLimitMode == SpeedLimitMode.FLOATING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = floatingIntervalText,
                                onValueChange = onFloatingIntervalChange,
                                label = { Text("探测周期（秒）") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = floatingPercentText,
                                onValueChange = onFloatingPercentChange,
                                label = { Text("限速比例（%）") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "地址配置（已填写 $configuredEndpointCount / ${drafts.size}）",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { endpointsExpanded = !endpointsExpanded }) {
                    Text(if (endpointsExpanded) "收起" else "展开")
                }
            }

            if (endpointsExpanded) {
                drafts.forEachIndexed { index, draft ->
                    val endpointExpanded = endpointExpandedStates.getOrElse(index) { true }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "地址 ${index + 1}",
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = {
                                        endpointExpandedStates = endpointExpandedStates.toMutableList().also { states ->
                                            if (index < states.size) states[index] = !endpointExpanded
                                        }
                                    }
                                ) {
                                    Text(if (endpointExpanded) "收起" else "展开")
                                }
                                OutlinedButton(
                                    onClick = {
                                        onDraftChange(index, SpeedTestEndpointDraft())
                                    }
                                ) {
                                    Text("重置")
                                }
                            }
                            if (endpointExpanded) {
                                OutlinedTextField(
                                    value = draft.url,
                                    onValueChange = { value ->
                                        onDraftChange(index, draft.copy(url = value))
                                    },
                                    label = { Text("${directionLabel}地址 URL") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = draft.threadsText,
                                    onValueChange = { value ->
                                        val digits = value.filter { it.isDigit() }.take(2)
                                        onDraftChange(index, draft.copy(threadsText = digits.ifBlank { "" }))
                                    },
                                    label = { Text("线程数（1~64）") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text("快捷线程", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                ThreadShortcutRow(
                                    currentThreadsText = draft.threadsText.ifBlank { "1" },
                                    onSelect = { threads -> onDraftChange(index, draft.copy(threadsText = threads)) }
                                )
                            } else {
                                Text(
                                    draft.url.takeIf { it.isNotBlank() } ?: "未配置地址",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "线程数：${draft.threadsText.ifBlank { "1" }}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    "地址列表已收起，点击展开后可继续编辑 5 个${directionLabel}地址和线程数。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onStart,
                    modifier = Modifier.weight(1f),
                    enabled = !running
                ) {
                    Text(if (running) "${directionLabel}中..." else "开始$directionLabel")
                }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    enabled = running
                ) {
                    Text("停止$directionLabel")
                }
            }
        }
    }
}

@Composable
private fun SpeedTestPresetManagerCard(
    expanded: Boolean,
    presets: List<SpeedTestNamedConfig>,
    selectedPresetName: String?,
    statusText: String,
    onExpandedChange: (Boolean) -> Unit,
    onSelectedPresetNameChange: (String?) -> Unit,
    onQuickSwitch: (String) -> Unit,
    onApply: () -> Unit,
    onSaveCurrent: () -> Unit,
    onCreatePreset: () -> Unit,
    onCreateTemporary: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    val options = if (presets.isEmpty()) listOf("当前配置") else presets.map { it.name }
    val selectedLabel = selectedPresetName ?: presets.firstOrNull()?.name ?: "当前配置"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(if (expanded) 10.dp else 0.dp)
        ) {
            if (expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("快速配置", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { onExpandedChange(false) }) {
                        Text("收起", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    UnitDropdownField(
                        options = options,
                        selected = selectedLabel,
                        label = { it },
                        onSelect = { name ->
                            onSelectedPresetNameChange(name.takeIf { it != "当前配置" || presets.isNotEmpty() })
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onApply,
                        enabled = selectedPresetName != null
                    ) {
                        Text("应用", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onSaveCurrent,
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Text("保存当前", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = onRename,
                        enabled = selectedPresetName != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("更名", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDelete,
                        enabled = selectedPresetName != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("删除", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick = onCreatePreset,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("新增配置", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onCreateTemporary,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("临时配置", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onImport,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("导入", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onExport,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("导出", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
                Text(statusText, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "当前配置",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    UnitDropdownField(
                        options = options,
                        selected = selectedLabel,
                        label = { it },
                        onSelect = { name ->
                            if (presets.isEmpty()) return@UnitDropdownField
                            onSelectedPresetNameChange(name)
                            onQuickSwitch(name)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { onExpandedChange(true) }) {
                        Text("展开", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun defaultSpeedTestConfig(): SpeedTestConfig {
    return SpeedTestConfig(
        downloadEndpoints = List(SPEED_TEST_MAX_DOWNLOAD_ENDPOINTS) { SpeedTestEndpointConfig() },
        uploadEndpoints = List(SPEED_TEST_MAX_UPLOAD_ENDPOINTS) { SpeedTestEndpointConfig() },
        downloadStopConfig = SpeedTestStopConfig(),
        uploadStopConfig = SpeedTestStopConfig(),
        downloadSpeedLimitConfig = SpeedLimitConfig(),
        uploadSpeedLimitConfig = SpeedLimitConfig(),
        alternatingTransferConfig = AlternatingTransferConfig(),
        dualChannelEnabled = false,
        logEnabled = false
    )
}

private fun loadSpeedTestConfig(
    prefs: android.content.SharedPreferences
): SpeedTestConfig {
    val rawJson = prefs.getString(SPEED_TEST_KEY_CONFIG, null)
    if (rawJson.isNullOrBlank()) return defaultSpeedTestConfig()
    return runCatching {
        val root = JSONObject(rawJson)
        val defaultConfig = defaultSpeedTestConfig()
        SpeedTestConfig(
            downloadEndpoints = parseEndpointArray(
                root.optJSONArray("downloadEndpoints"),
                defaultConfig.downloadEndpoints.size
            ),
            uploadEndpoints = parseEndpointArray(
                root.optJSONArray("uploadEndpoints"),
                defaultConfig.uploadEndpoints.size
            ),
            downloadStopConfig = parseStopConfig(root.optJSONObject("downloadStopConfig")),
            uploadStopConfig = parseStopConfig(root.optJSONObject("uploadStopConfig")),
            downloadSpeedLimitConfig = parseSpeedLimitConfig(root.optJSONObject("downloadSpeedLimitConfig")),
            uploadSpeedLimitConfig = parseSpeedLimitConfig(root.optJSONObject("uploadSpeedLimitConfig")),
            alternatingTransferConfig = parseAlternatingTransferConfig(root.optJSONObject("alternatingTransferConfig")),
            dualChannelEnabled = root.optBoolean("dualChannelEnabled", false),
            logEnabled = root.optBoolean("logEnabled", false)
        )
    }.getOrElse {
        defaultSpeedTestConfig()
    }
}

private fun loadSpeedTestPresets(
    prefs: android.content.SharedPreferences
): List<SpeedTestNamedConfig> {
    val rawJson = prefs.getString(SPEED_TEST_KEY_PRESETS, null)
    if (rawJson.isNullOrBlank()) return emptyList()
    return runCatching {
        val root = JSONArray(rawJson)
        buildList {
            for (index in 0 until root.length()) {
                val item = root.optJSONObject(index) ?: continue
                val name = normalizePresetName(item.optString("name"))
                val configObject = item.optJSONObject("config") ?: continue
                if (name.isBlank()) continue
                add(
                    SpeedTestNamedConfig(
                        name = name,
                        config = parseSpeedTestConfigFromJson(configObject)
                    )
                )
            }
        }.distinctBy { it.name }
    }.getOrDefault(emptyList())
}

private fun parseSpeedTestConfigFromJson(root: JSONObject): SpeedTestConfig {
    val defaultConfig = defaultSpeedTestConfig()
    return SpeedTestConfig(
        downloadEndpoints = parseEndpointArray(
            root.optJSONArray("downloadEndpoints"),
            defaultConfig.downloadEndpoints.size
        ),
        uploadEndpoints = parseEndpointArray(
            root.optJSONArray("uploadEndpoints"),
            defaultConfig.uploadEndpoints.size
        ),
        downloadStopConfig = parseStopConfig(root.optJSONObject("downloadStopConfig")),
        uploadStopConfig = parseStopConfig(root.optJSONObject("uploadStopConfig")),
        downloadSpeedLimitConfig = parseSpeedLimitConfig(root.optJSONObject("downloadSpeedLimitConfig")),
        uploadSpeedLimitConfig = parseSpeedLimitConfig(root.optJSONObject("uploadSpeedLimitConfig")),
        alternatingTransferConfig = parseAlternatingTransferConfig(root.optJSONObject("alternatingTransferConfig")),
        dualChannelEnabled = root.optBoolean("dualChannelEnabled", false),
        logEnabled = root.optBoolean("logEnabled", false)
    )
}

private fun presetsToJson(
    presets: List<SpeedTestNamedConfig>
): JSONArray {
    return JSONArray().apply {
        presets.forEach { preset ->
            put(
                JSONObject().apply {
                    put("name", preset.name)
                    put("config", preset.config.toJson())
                }
            )
        }
    }
}

private fun upsertPreset(
    presets: List<SpeedTestNamedConfig>,
    preset: SpeedTestNamedConfig
): List<SpeedTestNamedConfig> {
    val filtered = presets.filterNot { it.name == preset.name }
    return (listOf(preset) + filtered).sortedBy { it.name.lowercase(Locale.getDefault()) }
}

private fun normalizePresetName(name: String): String {
    return name.trim().replace(Regex("\\s+"), " ").take(40)
}

private fun uniquePresetName(
    baseName: String,
    presets: List<SpeedTestNamedConfig>
): String {
    val normalized = normalizePresetName(baseName).ifBlank { "导入配置" }
    if (presets.none { it.name == normalized }) return normalized
    var index = 2
    while (true) {
        val candidate = "$normalized-$index"
        if (presets.none { it.name == candidate }) {
            return candidate
        }
        index += 1
    }
}

private fun sanitizeExportFileName(name: String): String {
    return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "speedtest_config" }
}

private fun SpeedTestNamedConfig.toExportJson(): JSONObject {
    return JSONObject().apply {
        put("type", "speedtest_preset")
        put("version", 1)
        put("name", name)
        put("config", config.toJson())
    }
}

private fun parseImportedSpeedTestPreset(rawText: String): SpeedTestNamedConfig? {
    val root = JSONObject(rawText)
    return when {
        root.optString("type") == "speedtest_preset" -> {
            val name = normalizePresetName(root.optString("name")).ifBlank { "导入配置" }
            val config = root.optJSONObject("config") ?: return null
            SpeedTestNamedConfig(name = name, config = parseSpeedTestConfigFromJson(config))
        }
        root.has("downloadEndpoints") || root.has("uploadEndpoints") -> {
            SpeedTestNamedConfig(
                name = "导入配置",
                config = parseSpeedTestConfigFromJson(root)
            )
        }
        else -> null
    }
}

private fun parseEndpointArray(
    array: JSONArray?,
    expectedSize: Int
): List<SpeedTestEndpointConfig> {
    return (0 until expectedSize).map { index ->
        val item = array?.optJSONObject(index)
        SpeedTestEndpointConfig(
            url = item?.optString("url").orEmpty(),
            threads = item?.optInt("threads", 1)?.coerceIn(1, SPEED_TEST_MAX_THREADS) ?: 1
        )
    }
}

private fun parseStopConfig(item: JSONObject?): SpeedTestStopConfig {
    val duration = item?.optInt("durationSeconds", 0)?.takeIf { it > 0 }
    val dataLimitBytes = item?.optLong("dataLimitBytes", 0L)?.takeIf { it > 0L }
    return SpeedTestStopConfig(
        durationSeconds = duration,
        dataLimitBytes = dataLimitBytes,
        autoSwitchEndpoints = item?.optBoolean("autoSwitchEndpoints", false) == true
    )
}

private fun parseSpeedLimitConfig(item: JSONObject?): SpeedLimitConfig {
    val mode = item?.optString("mode")
        ?.let { raw -> SpeedLimitMode.entries.firstOrNull { it.name == raw } }
        ?: SpeedLimitMode.OFF
    return SpeedLimitConfig(
        mode = mode,
        fixedLimitBytesPerSecond = item?.optLong("fixedLimitBytesPerSecond", 0L)?.takeIf { it > 0L },
        floatingIntervalSeconds = item?.optInt(
            "floatingIntervalSeconds",
            SPEED_TEST_FLOATING_LIMIT_DEFAULT_SECONDS
        )?.coerceAtLeast(1) ?: SPEED_TEST_FLOATING_LIMIT_DEFAULT_SECONDS,
        floatingPercent = item?.optInt(
            "floatingPercent",
            SPEED_TEST_FLOATING_LIMIT_DEFAULT_PERCENT
        )?.coerceIn(1, 100) ?: SPEED_TEST_FLOATING_LIMIT_DEFAULT_PERCENT
    )
}

private fun parseAlternatingTransferConfig(item: JSONObject?): AlternatingTransferConfig {
    val mode = item?.optString("mode")
        ?.let { raw -> AlternatingTransferMode.entries.firstOrNull { it.name == raw } }
        ?: AlternatingTransferMode.RATIO
    val fixedIdleStrategy = item?.optString("fixedIdleStrategy")
        ?.let { raw -> AlternatingIdleStrategy.entries.firstOrNull { it.name == raw } }
        ?: AlternatingIdleStrategy.SOFT_LIMIT
    return AlternatingTransferConfig(
        enabled = item?.optBoolean("enabled", false) == true,
        mode = mode,
        uploadPercent = item?.optInt("uploadPercent", 50)?.coerceIn(0, 100) ?: 50,
        windowMs = item?.optInt("windowMs", SPEED_TEST_ALTERNATING_DEFAULT_WINDOW_MS)
            ?.coerceAtLeast(SPEED_TEST_ALTERNATING_MIN_WINDOW_MS)
            ?: SPEED_TEST_ALTERNATING_DEFAULT_WINDOW_MS,
        fixedDownloadSeconds = item?.optInt(
            "fixedDownloadSeconds",
            SPEED_TEST_ALTERNATING_DEFAULT_DOWNLOAD_SECONDS
        )?.coerceAtLeast(1) ?: SPEED_TEST_ALTERNATING_DEFAULT_DOWNLOAD_SECONDS,
        fixedUploadSeconds = item?.optInt(
            "fixedUploadSeconds",
            SPEED_TEST_ALTERNATING_DEFAULT_UPLOAD_SECONDS
        )?.coerceAtLeast(1) ?: SPEED_TEST_ALTERNATING_DEFAULT_UPLOAD_SECONDS,
        ratioIdleLimitBytesPerSecond = item?.optLong(
            "ratioIdleLimitBytesPerSecond",
            mbpsToBytesPerSecond(SPEED_TEST_ALTERNATING_RATIO_IDLE_LIMIT_DEFAULT_MBPS)
        )?.coerceAtLeast(0L) ?: mbpsToBytesPerSecond(SPEED_TEST_ALTERNATING_RATIO_IDLE_LIMIT_DEFAULT_MBPS),
        fixedIdleStrategy = fixedIdleStrategy,
        fixedIdleLimitBytesPerSecond = item?.optLong(
            "fixedIdleLimitBytesPerSecond",
            mbpsToBytesPerSecond(SPEED_TEST_ALTERNATING_FIXED_IDLE_LIMIT_DEFAULT_MBPS)
        )?.coerceAtLeast(0L) ?: mbpsToBytesPerSecond(SPEED_TEST_ALTERNATING_FIXED_IDLE_LIMIT_DEFAULT_MBPS)
    )
}

private fun SpeedTestConfig.toJson(): JSONObject {
    return JSONObject().apply {
        put("downloadEndpoints", JSONArray().apply {
            downloadEndpoints.forEach { endpoint ->
                put(JSONObject().apply {
                    put("url", endpoint.url)
                    put("threads", endpoint.threads)
                })
            }
        })
        put("uploadEndpoints", JSONArray().apply {
            uploadEndpoints.forEach { endpoint ->
                put(JSONObject().apply {
                    put("url", endpoint.url)
                    put("threads", endpoint.threads)
                })
            }
        })
        put("downloadStopConfig", downloadStopConfig.toJson())
        put("uploadStopConfig", uploadStopConfig.toJson())
        put("downloadSpeedLimitConfig", downloadSpeedLimitConfig.toJson())
        put("uploadSpeedLimitConfig", uploadSpeedLimitConfig.toJson())
        put("alternatingTransferConfig", alternatingTransferConfig.toJson())
        put("dualChannelEnabled", dualChannelEnabled)
        put("logEnabled", logEnabled)
    }
}

private fun SpeedTestStopConfig.toJson(): JSONObject {
    return JSONObject().apply {
        put("durationSeconds", durationSeconds ?: 0)
        put("dataLimitBytes", dataLimitBytes ?: 0L)
        put("autoSwitchEndpoints", autoSwitchEndpoints)
    }
}

private fun SpeedLimitConfig.toJson(): JSONObject {
    return JSONObject().apply {
        put("mode", mode.name)
        put("fixedLimitBytesPerSecond", fixedLimitBytesPerSecond ?: 0L)
        put("floatingIntervalSeconds", floatingIntervalSeconds)
        put("floatingPercent", floatingPercent)
    }
}

private fun AlternatingTransferConfig.toJson(): JSONObject {
    return JSONObject().apply {
        put("enabled", enabled)
        put("mode", mode.name)
        put("uploadPercent", uploadPercent)
        put("windowMs", windowMs)
        put("fixedDownloadSeconds", fixedDownloadSeconds)
        put("fixedUploadSeconds", fixedUploadSeconds)
        put("ratioIdleLimitBytesPerSecond", ratioIdleLimitBytesPerSecond)
        put("fixedIdleStrategy", fixedIdleStrategy.name)
        put("fixedIdleLimitBytesPerSecond", fixedIdleLimitBytesPerSecond)
    }
}

private fun SpeedTestEndpointConfig.toDraft(): SpeedTestEndpointDraft {
    return SpeedTestEndpointDraft(url = url, threadsText = threads.toString())
}

private fun SpeedTestEndpointDraft.toConfig(): SpeedTestEndpointConfig {
    return SpeedTestEndpointConfig(
        url = url.trim(),
        threads = (threadsText.toIntOrNull() ?: 1).coerceIn(1, SPEED_TEST_MAX_THREADS)
    )
}

private fun mbpsToBytesPerSecond(mbps: Double): Long {
    return (mbps.coerceAtLeast(0.0) * 1_000_000.0 / 8.0).toLong()
}

private fun isNotificationPermissionGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

private fun safeMultiply(value: Long, factor: Long): Long? {
    if (value <= 0L || factor <= 0L) return null
    if (value > Long.MAX_VALUE / factor) return null
    return value * factor
}

private fun ceilDiv(value: Long, divisor: Long): Long {
    if (value <= 0L || divisor <= 0L) return 0L
    return ((value - 1L) / divisor) + 1L
}

private fun pickDurationUnit(durationSeconds: Int?): StopDurationUnit {
    val raw = durationSeconds?.toLong()?.takeIf { it > 0L } ?: return StopDurationUnit.SECOND
    return StopDurationUnit.entries.lastOrNull { raw % it.seconds == 0L } ?: StopDurationUnit.SECOND
}

private fun pickTrafficUnit(dataLimitBytes: Long?): StopTrafficUnit {
    val raw = dataLimitBytes?.takeIf { it > 0L } ?: return StopTrafficUnit.MB
    return StopTrafficUnit.entries.lastOrNull { raw % it.bytes == 0L } ?: StopTrafficUnit.MB
}

private fun Int?.toUnitValueText(unit: StopDurationUnit): String {
    val raw = this?.toLong()?.takeIf { it > 0L } ?: return ""
    return (raw / unit.seconds).toString()
}

private fun Long?.toUnitValueText(unit: StopTrafficUnit): String {
    val raw = this?.takeIf { it > 0L } ?: return ""
    return (raw / unit.bytes).toString()
}

private fun convertDurationValueText(
    valueText: String,
    fromUnit: StopDurationUnit,
    toUnit: StopDurationUnit
): String {
    if (fromUnit == toUnit) return valueText
    val value = valueText.toLongOrNull()?.takeIf { it > 0L } ?: return valueText
    val seconds = safeMultiply(value, fromUnit.seconds) ?: return valueText
    return ceilDiv(seconds, toUnit.seconds).toString()
}

private fun convertTrafficValueText(
    valueText: String,
    fromUnit: StopTrafficUnit,
    toUnit: StopTrafficUnit
): String {
    if (fromUnit == toUnit) return valueText
    val value = valueText.toLongOrNull()?.takeIf { it > 0L } ?: return valueText
    val bytes = safeMultiply(value, fromUnit.bytes) ?: return valueText
    return ceilDiv(bytes, toUnit.bytes).toString()
}

private fun Long?.toLimitMbpsText(): String {
    if (this == null || this <= 0L) return ""
    val mbps = this.toDouble() * 8.0 / 1_000_000.0
    return if (mbps % 1.0 == 0.0) {
        mbps.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", mbps).trimEnd('0').trimEnd('.')
    }
}

private fun formatRate(bytesPerSecond: Double): String {
    if (bytesPerSecond <= 0.0) return "0 B/s"
    val bytesText = formatBytes(bytesPerSecond.toLong()) + "/s"
    val mbps = bytesPerSecond * 8.0 / 1_000_000.0
    return "$bytesText (${String.format(Locale.getDefault(), "%.2f", mbps)} Mbps)"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val unit = 1024.0
    val digitGroups = (ln(bytes.toDouble()) / ln(unit)).toInt().coerceAtMost(4)
    val value = bytes / unit.pow(digitGroups.toDouble())
    val suffix = arrayOf("B", "KB", "MB", "GB", "TB")[digitGroups]
    return String.format(Locale.getDefault(), "%.2f %s", value, suffix)
}

private fun formatElapsed(elapsedMs: Long): String {
    if (elapsedMs <= 0L) return "00:00"
    val totalSeconds = elapsedMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

