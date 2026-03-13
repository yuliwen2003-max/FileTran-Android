package com.yuliwen.filetran

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class SpeedTestForegroundService : Service() {
    private var overlayView: View? = null
    private var silentAudioTrack: AudioTrack? = null
    private var mediaSession: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            disableSuperKeepAlive()
            stopSelf()
            return START_NOT_STICKY
        }

        val downloadRunning = intent?.getBooleanExtra(EXTRA_DOWNLOAD_RUNNING, false) == true
        val uploadRunning = intent?.getBooleanExtra(EXTRA_UPLOAD_RUNNING, false) == true
        val downloadSpeedBytesPerSecond = intent?.getDoubleExtra(EXTRA_DOWNLOAD_SPEED_BPS, 0.0) ?: 0.0
        val uploadSpeedBytesPerSecond = intent?.getDoubleExtra(EXTRA_UPLOAD_SPEED_BPS, 0.0) ?: 0.0
        val totalUsedBytes = intent?.getLongExtra(EXTRA_TOTAL_USED_BYTES, 0L) ?: 0L
        val elapsedMs = intent?.getLongExtra(EXTRA_ELAPSED_MS, 0L) ?: 0L
        val superKeepAliveEnabled = intent?.getBooleanExtra(EXTRA_SUPER_KEEP_ALIVE, false) == true

        if (superKeepAliveEnabled) {
            enableSuperKeepAlive()
            postMediaNotification()
        } else {
            disableSuperKeepAlive()
        }

        val notification = buildForegroundNotification(
            statusText = buildStatusText(downloadRunning = downloadRunning, uploadRunning = uploadRunning),
            speedText = buildSpeedText(
                downloadSpeedBytesPerSecond = downloadSpeedBytesPerSecond,
                uploadSpeedBytesPerSecond = uploadSpeedBytesPerSecond
            ),
            usageText = "总流量 ${formatBytes(totalUsedBytes)} · 耗时 ${formatElapsed(elapsedMs)}",
            superKeepAliveEnabled = superKeepAliveEnabled
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(FOREGROUND_NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        disableSuperKeepAlive()
        super.onDestroy()
    }

    private fun buildForegroundNotification(
        statusText: String,
        speedText: String,
        usageText: String,
        superKeepAliveEnabled: Boolean
    ): Notification {
        ensureNotificationChannels()
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("SpeedTest 后台测试进行中")
            .setContentText("$statusText · $speedText")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$statusText\n$speedText\n$usageText\n${
                        if (superKeepAliveEnabled) {
                            "模式：超强保活（无声音频 + 透明悬浮窗 + 媒体通知）"
                        } else {
                            "模式：标准后台保活"
                        }
                    }"
                )
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(FOREGROUND_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "SpeedTest 后台测试",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于保持 SpeedTest 在后台持续进行合法网络性能测试。"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        if (manager.getNotificationChannel(MEDIA_CHANNEL_ID) == null) {
            val mediaChannel = NotificationChannel(
                MEDIA_CHANNEL_ID,
                "SpeedTest 媒体保活",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于显示无声保活音频的媒体通知。"
                setShowBadge(false)
            }
            manager.createNotificationChannel(mediaChannel)
        }
    }

    private fun enableSuperKeepAlive() {
        startSilentAudioLoopIfNeeded()
        showTransparentOverlayIfPossible()
        startMediaSessionIfNeeded()
    }

    private fun disableSuperKeepAlive() {
        stopSilentAudioLoop()
        removeTransparentOverlay()
        stopMediaSession()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(MEDIA_NOTIFICATION_ID)
    }

    private fun startSilentAudioLoopIfNeeded() {
        if (silentAudioTrack != null) return
        val sampleRate = 8000
        val frameCount = sampleRate / 2
        val silenceData = ByteArray(frameCount * 2)
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(silenceData.size)
            .build()
        audioTrack.setVolume(0f)
        audioTrack.write(silenceData, 0, silenceData.size)
        audioTrack.setLoopPoints(0, frameCount, -1)
        audioTrack.play()
        silentAudioTrack = audioTrack
    }

    private fun stopSilentAudioLoop() {
        runCatching { silentAudioTrack?.stop() }
        runCatching { silentAudioTrack?.release() }
        silentAudioTrack = null
    }

    private fun showTransparentOverlayIfPossible() {
        if (overlayView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
        }
        val view = View(this)
        runCatching {
            windowManager.addView(view, params)
            overlayView = view
        }
    }

    private fun removeTransparentOverlay() {
        val view = overlayView ?: return
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { windowManager.removeView(view) }
        overlayView = null
    }

    private fun startMediaSessionIfNeeded() {
        if (mediaSession != null) return
        val session = MediaSession(this, "SpeedTestSilentAudioSession")
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "SpeedTest 保活静音音频")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "FileTran")
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP
                )
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        session.isActive = true
        mediaSession = session
    }

    private fun stopMediaSession() {
        val session = mediaSession ?: return
        runCatching {
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                    .build()
            )
        }
        runCatching { session.isActive = false }
        runCatching { session.release() }
        mediaSession = null
    }

    private fun postMediaNotification() {
        if (!canPostNotifications()) return
        val session = mediaSession ?: return
        ensureNotificationChannels()
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val launchPendingIntent = PendingIntent.getActivity(
            this,
            1,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, SpeedTestForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, MEDIA_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        val mediaNotification = builder
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("SpeedTest 保活音频")
            .setContentText("静音音频播放中，用于提升后台存活率")
            .setContentIntent(launchPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "停止后台测试",
                    stopPendingIntent
                ).build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(MEDIA_NOTIFICATION_ID, mediaNotification) }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val FOREGROUND_CHANNEL_ID = "speed_test_background_channel"
        private const val MEDIA_CHANNEL_ID = "speed_test_media_channel"
        private const val FOREGROUND_NOTIFICATION_ID = 32011
        private const val MEDIA_NOTIFICATION_ID = 32013
        private const val ACTION_START = "com.yuliwen.filetran.speedtest.START"
        private const val ACTION_STOP = "com.yuliwen.filetran.speedtest.STOP"
        private const val EXTRA_DOWNLOAD_RUNNING = "extra_download_running"
        private const val EXTRA_UPLOAD_RUNNING = "extra_upload_running"
        private const val EXTRA_DOWNLOAD_SPEED_BPS = "extra_download_speed_bps"
        private const val EXTRA_UPLOAD_SPEED_BPS = "extra_upload_speed_bps"
        private const val EXTRA_TOTAL_USED_BYTES = "extra_total_used_bytes"
        private const val EXTRA_ELAPSED_MS = "extra_elapsed_ms"
        private const val EXTRA_SUPER_KEEP_ALIVE = "extra_super_keep_alive"

        fun start(
            context: Context,
            downloadRunning: Boolean,
            uploadRunning: Boolean,
            downloadSpeedBytesPerSecond: Double,
            uploadSpeedBytesPerSecond: Double,
            totalUsedBytes: Long,
            elapsedMs: Long,
            superKeepAliveEnabled: Boolean
        ) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, SpeedTestForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DOWNLOAD_RUNNING, downloadRunning)
                putExtra(EXTRA_UPLOAD_RUNNING, uploadRunning)
                putExtra(EXTRA_DOWNLOAD_SPEED_BPS, downloadSpeedBytesPerSecond)
                putExtra(EXTRA_UPLOAD_SPEED_BPS, uploadSpeedBytesPerSecond)
                putExtra(EXTRA_TOTAL_USED_BYTES, totalUsedBytes)
                putExtra(EXTRA_ELAPSED_MS, elapsedMs)
                putExtra(EXTRA_SUPER_KEEP_ALIVE, superKeepAliveEnabled)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, SpeedTestForegroundService::class.java))
        }

        private fun buildStatusText(downloadRunning: Boolean, uploadRunning: Boolean): String {
            return when {
                downloadRunning && uploadRunning -> "下载测试与上传测试正在进行。"
                downloadRunning -> "下载测试正在进行。"
                uploadRunning -> "上传测试正在进行。"
                else -> "测试准备中。"
            }
        }

        private fun buildSpeedText(
            downloadSpeedBytesPerSecond: Double,
            uploadSpeedBytesPerSecond: Double
        ): String {
            return "下行 ${formatSpeed(downloadSpeedBytesPerSecond)} · 上行 ${formatSpeed(uploadSpeedBytesPerSecond)}"
        }

        private fun formatSpeed(bytesPerSecond: Double): String {
            val mbps = bytesPerSecond.coerceAtLeast(0.0) * 8.0 / 1_000_000.0
            return String.format("%.2f Mbps", mbps)
        }

        private fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val kb = 1024.0
            val mb = kb * 1024.0
            val gb = mb * 1024.0
            return when {
                bytes >= gb -> String.format("%.2f GB", bytes / gb)
                bytes >= mb -> String.format("%.2f MB", bytes / mb)
                bytes >= kb -> String.format("%.2f KB", bytes / kb)
                else -> "$bytes B"
            }
        }

        private fun formatElapsed(elapsedMs: Long): String {
            val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                String.format("%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }
    }
}
