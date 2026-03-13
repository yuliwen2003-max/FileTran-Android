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
const val APP_KEEP_ALIVE_PREFS = "app_keep_alive"
const val APP_KEEP_ALIVE_KEY_ENABLED = "app_keep_alive_enabled"
const val APP_KEEP_ALIVE_KEY_SUPER_MODE = "app_keep_alive_super_mode"

class AppKeepAliveService : Service() {
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
        if (shouldSuspendForClipboardSync()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            disableSuperKeepAlive()
            stopSelf()
            return START_NOT_STICKY
        }

        val superModeEnabled = intent?.getBooleanExtra(
            EXTRA_SUPER_MODE_ENABLED,
            readSuperModeFromPrefs(this)
        ) == true

        if (superModeEnabled) {
            enableSuperKeepAlive()
            postMediaNotification()
        } else {
            disableSuperKeepAlive()
        }

        val notification = buildForegroundNotification(superModeEnabled)
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

    private fun buildForegroundNotification(superModeEnabled: Boolean): Notification {
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
        val modeText = if (superModeEnabled) {
            "模式：超强保活（无声音频 + 透明悬浮窗 + 媒体通知）"
        } else {
            "模式：标准保活（前台通知）"
        }
        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("FileTran 全局保活已开启")
            .setContentText(modeText)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "服务场景（例如 LibreSpeed）可持续在后台运行。\n$modeText"
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
                "全局后台保活",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于保持 FileTran 的后台服务持续运行。"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        if (manager.getNotificationChannel(MEDIA_CHANNEL_ID) == null) {
            val mediaChannel = NotificationChannel(
                MEDIA_CHANNEL_ID,
                "全局保活媒体通知",
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
        val session = MediaSession(this, "AppKeepAliveSilentAudioSession")
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "FileTran 保活静音音频")
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
            11,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, AppKeepAliveService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            12,
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
            .setContentTitle("FileTran 保活音频")
            .setContentText("静音音频播放中，用于提升后台存活率")
            .setContentIntent(launchPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "停止全局保活",
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
        private const val FOREGROUND_CHANNEL_ID = "app_keep_alive_channel"
        private const val MEDIA_CHANNEL_ID = "app_keep_alive_media_channel"
        private const val FOREGROUND_NOTIFICATION_ID = 32012
        private const val MEDIA_NOTIFICATION_ID = 32014
        private const val ACTION_START = "com.yuliwen.filetran.keepalive.START"
        private const val ACTION_STOP = "com.yuliwen.filetran.keepalive.STOP"
        private const val EXTRA_SUPER_MODE_ENABLED = "extra_super_mode_enabled"

        fun start(context: Context, superModeEnabled: Boolean) {
            if (ClipboardSyncRuntime.stateFlow.value.running) return
            val appContext = context.applicationContext
            val intent = Intent(appContext, AppKeepAliveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SUPER_MODE_ENABLED, superModeEnabled)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, AppKeepAliveService::class.java))
        }

        fun pauseForClipboardSync(context: Context) {
            stop(context)
        }

        fun resumeAfterClipboardSync(context: Context) {
            syncFromPrefs(context)
        }

        fun syncFromPrefs(context: Context) {
            val prefs = context.getSharedPreferences(APP_KEEP_ALIVE_PREFS, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean(APP_KEEP_ALIVE_KEY_ENABLED, false)
            val superMode = prefs.getBoolean(APP_KEEP_ALIVE_KEY_SUPER_MODE, false)
            if (enabled) {
                start(context, superMode)
            } else {
                stop(context)
            }
        }

        private fun readSuperModeFromPrefs(context: Context): Boolean {
            val prefs = context.getSharedPreferences(APP_KEEP_ALIVE_PREFS, Context.MODE_PRIVATE)
            return prefs.getBoolean(APP_KEEP_ALIVE_KEY_SUPER_MODE, false)
        }
    }

    private fun shouldSuspendForClipboardSync(): Boolean {
        return ClipboardSyncRuntime.stateFlow.value.running
    }
}
