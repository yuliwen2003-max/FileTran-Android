package com.yuliwen.filetran

import android.content.Context
import android.content.SharedPreferences

class HotspotPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hotspot_prefs", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_SSID = "hotspot_ssid"
        private const val KEY_PASSWORD = "hotspot_password"
        private const val KEY_BAND = "hotspot_band"
        private const val KEY_USE_CUSTOM = "use_custom_config"
    }
    
    fun saveHotspotConfig(ssid: String, password: String, band: WifiBand) {
        prefs.edit().apply {
            putString(KEY_SSID, ssid)
            putString(KEY_PASSWORD, password)
            putString(KEY_BAND, band.name)
            putBoolean(KEY_USE_CUSTOM, true)
            apply()
        }
    }
    
    fun getHotspotConfig(): HotspotConfig? {
        if (!prefs.getBoolean(KEY_USE_CUSTOM, false)) {
            return null
        }
        
        val ssid = prefs.getString(KEY_SSID, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        val bandName = prefs.getString(KEY_BAND, WifiBand.BAND_2GHZ.name)
        val band = try {
            WifiBand.valueOf(bandName ?: WifiBand.BAND_2GHZ.name)
        } catch (e: Exception) {
            WifiBand.BAND_2GHZ
        }
        
        return HotspotConfig(ssid, password, band)
    }
    
    fun resetToRandom() {
        prefs.edit().apply {
            putBoolean(KEY_USE_CUSTOM, false)
            apply()
        }
    }
    
    fun hasCustomConfig(): Boolean {
        return prefs.getBoolean(KEY_USE_CUSTOM, false)
    }
}


