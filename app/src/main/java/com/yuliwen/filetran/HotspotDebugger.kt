package com.yuliwen.filetran

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

data class HotspotDebugInfo(
    val hasWriteSettingsPermission: Boolean,
    val wifiEnabled: Boolean,
    val hotspotEnabled: Boolean,
    val androidVersion: Int,
    val deviceManufacturer: String,
    val deviceModel: String,
    val availableMethods: List<String>,
    val lastError: String?,
    val attemptLog: List<String>
)

object HotspotDebugger {
    private val TAG = "HotspotDebugger"
    private val attemptLog = mutableListOf<String>()
    
    fun getDebugInfo(context: Context): HotspotDebugInfo {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        return HotspotDebugInfo(
            hasWriteSettingsPermission = HotspotManager.canWriteSettings(context),
            wifiEnabled = wifiManager.isWifiEnabled,
            hotspotEnabled = HotspotManager.isHotspotEnabled(context),
            androidVersion = Build.VERSION.SDK_INT,
            deviceManufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            availableMethods = getAvailableMethods(wifiManager),
            lastError = attemptLog.lastOrNull(),
            attemptLog = attemptLog.toList()
        )
    }
    
    fun clearLog() {
        attemptLog.clear()
    }
    
    fun addLog(message: String) {
        val logMessage = "[${System.currentTimeMillis()}] $message"
        attemptLog.add(logMessage)
        Log.d(TAG, logMessage)
    }
    
    private fun getAvailableMethods(wifiManager: WifiManager): List<String> {
        val methods = mutableListOf<String>()
        
        try {
            wifiManager.javaClass.methods.forEach { method ->
                if (method.name.contains("Ap", ignoreCase = true) || 
                    method.name.contains("Hotspot", ignoreCase = true) ||
                    method.name.contains("Tether", ignoreCase = true)) {
                    methods.add("${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
                }
            }
        } catch (e: Exception) {
            methods.add("Error getting methods: ${e.message}")
        }
        
        return methods
    }
    
    fun testAllMethods(context: Context, config: HotspotConfig): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        // 方法1: setWifiApEnabled (传统方法)
        results["setWifiApEnabled"] = try {
            addLog("尝试方法1: setWifiApEnabled")
            val wifiConfig = createWifiConfiguration(config)
            val method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType
            )
            val result = method.invoke(wifiManager, wifiConfig, true) as Boolean
            addLog("方法1结果: $result")
            result
        } catch (e: Exception) {
            addLog("方法1失败: ${e.message}")
            false
        }
        
        // 方法2: startTethering (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            results["startTethering"] = try {
                addLog("尝试方法2: startTethering")
                val result = startTetheringMethod(context)
                addLog("方法2结果: $result")
                result
            } catch (e: Exception) {
                addLog("方法2失败: ${e.message}")
                false
            }
        }
        
        // 方法3: startSoftAp
        results["startSoftAp"] = try {
            addLog("尝试方法3: startSoftAp")
            val wifiConfig = createWifiConfiguration(config)
            val method = wifiManager.javaClass.getMethod(
                "startSoftAp",
                WifiConfiguration::class.java
            )
            val result = method.invoke(wifiManager, wifiConfig) as Boolean
            addLog("方法3结果: $result")
            result
        } catch (e: Exception) {
            addLog("方法3失败: ${e.message}")
            false
        }
        
        return results
    }
    
    @RequiresApi(Build.VERSION_CODES.O)
    private fun startTetheringMethod(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            val method = connectivityManager.javaClass.getDeclaredMethod(
                "startTethering",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
            )
            
            // 创建回调
            val callback = java.lang.reflect.Proxy.newProxyInstance(
                context.classLoader,
                arrayOf(Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback"))
            ) { _, callbackMethod, args ->
                addLog("Tethering callback: ${callbackMethod.name}")
                null
            }
            
            method.invoke(connectivityManager, 0, false, callback)
            true
        } catch (e: Exception) {
            addLog("startTethering异常: ${e.message}")
            false
        }
    }
    
    private fun createWifiConfiguration(config: HotspotConfig): WifiConfiguration {
        val wifiConfig = WifiConfiguration()
        wifiConfig.SSID = config.ssid
        wifiConfig.preSharedKey = config.password
        
        wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
        wifiConfig.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
        
        try {
            val bandField = wifiConfig.javaClass.getDeclaredField("apBand")
            bandField.isAccessible = true
            when (config.band) {
                WifiBand.BAND_2GHZ -> bandField.setInt(wifiConfig, 0)
                WifiBand.BAND_5GHZ -> bandField.setInt(wifiConfig, 1)
            }
        } catch (e: Exception) {
            addLog("设置频段失败: ${e.message}")
        }
        
        return wifiConfig
    }
}


