package com.yuliwen.filetran

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlin.random.Random

object HotspotManager {
    @Volatile
    private var localOnlyReservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Volatile
    private var activeConfig: HotspotConfig? = null

    @Volatile
    private var lastStartStatus: String = ""

    fun generateRandomSSID(): String {
        return "FileTran_${Random.nextInt(1000, 9999)}"
    }

    fun generateRandomPassword(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        return buildString {
            repeat(12) {
                append(chars[Random.nextInt(chars.length)])
            }
        }
    }

    fun generateWifiQRCode(ssid: String, password: String, isHidden: Boolean = false): String {
        return "WIFI:T:WPA;S:$ssid;P:$password;H:$isHidden;;"
    }

    fun canWriteSettings(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }
    }

    fun requestWriteSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openHotspotSettings(context: Context): Boolean {
        val hotspotIntents = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent("android.settings.WIFI_AP_SETTINGS"),
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
            Intent().setClassName("com.android.settings", "com.android.settings.SubSettings").apply {
                putExtra(":settings:show_fragment", "com.android.settings.TetherSettings")
            }
        )

        hotspotIntents.forEach { intent ->
            if (tryStartActivity(context, intent)) return true
        }

        val fallback = listOf(
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_WIFI_SETTINGS)
        )
        fallback.forEach { intent ->
            if (tryStartActivity(context, intent)) return true
        }
        return false
    }

    private fun tryStartActivity(context: Context, intent: Intent): Boolean {
        return try {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            val resolved = intent.resolveActivity(context.packageManager) != null
            if (!resolved) return false
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun startHotspot(context: Context, config: HotspotConfig): Boolean {
        activeConfig = config

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            lastStartStatus = "当前系统不支持应用内直接开启热点"
            return false
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return try {
            lastStartStatus = "正在开启热点..."
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    localOnlyReservation?.close()
                    localOnlyReservation = reservation
                    activeConfig = resolveRuntimeConfig(reservation, config)
                    lastStartStatus = "热点已开启（应用内）"
                }

                override fun onStopped() {
                    localOnlyReservation = null
                    lastStartStatus = "热点已关闭"
                }

                override fun onFailed(reason: Int) {
                    localOnlyReservation = null
                    activeConfig = config
                    lastStartStatus = "热点开启失败，错误码: $reason"
                }
            }, Handler(Looper.getMainLooper()))
            true
        } catch (_: SecurityException) {
            lastStartStatus = "权限不足，无法在应用内直接开启热点"
            false
        } catch (_: Throwable) {
            lastStartStatus = "当前设备不支持应用内直接开启热点"
            false
        }
    }

    fun stopHotspot(context: Context): Boolean {
        localOnlyReservation?.close()
        localOnlyReservation = null
        lastStartStatus = "热点已关闭"
        return true
    }

    fun isHotspotEnabled(context: Context): Boolean {
        if (localOnlyReservation != null) return true
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifiManager) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    fun getActiveHotspotConfig(): HotspotConfig? = activeConfig

    fun getLastStartStatus(): String = lastStartStatus

    private fun resolveRuntimeConfig(
        reservation: WifiManager.LocalOnlyHotspotReservation,
        fallback: HotspotConfig
    ): HotspotConfig {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cfg = reservation.softApConfiguration
            val ssid = cfg?.ssid?.takeIf { it.isNotBlank() } ?: fallback.ssid
            val pwd = cfg?.passphrase?.takeIf { it.isNotBlank() } ?: fallback.password
            HotspotConfig(ssid, pwd, fallback.band)
        } else {
            fallback
        }
    }
}

data class HotspotConfig(
    val ssid: String,
    val password: String,
    val band: WifiBand
)

enum class WifiBand(val displayName: String) {
    BAND_2GHZ("2.4 GHz"),
    BAND_5GHZ("5 GHz")
}
