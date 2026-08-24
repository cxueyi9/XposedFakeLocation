package com.noobexon.xposedfakelocation.xposed.hooks

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.noobexon.xposedfakelocation.data.DEFAULT_ENABLE_WIFI_IDENTITY
import com.noobexon.xposedfakelocation.data.DEFAULT_WIFI_BSSID
import com.noobexon.xposedfakelocation.data.DEFAULT_WIFI_RSSI
import com.noobexon.xposedfakelocation.data.DEFAULT_WIFI_SSID
import com.noobexon.xposedfakelocation.data.KEY_ENABLE_WIFI_IDENTITY
import com.noobexon.xposedfakelocation.data.KEY_IS_PLAYING
import com.noobexon.xposedfakelocation.data.KEY_WIFI_BSSID
import com.noobexon.xposedfakelocation.data.KEY_WIFI_RSSI
import com.noobexon.xposedfakelocation.data.KEY_WIFI_SSID
import com.noobexon.xposedfakelocation.data.MAC_ADDRESS_REGEX
import com.noobexon.xposedfakelocation.data.MAX_WIFI_RSSI
import com.noobexon.xposedfakelocation.data.MIN_WIFI_RSSI
import com.noobexon.xposedfakelocation.data.REMOTE_PREFS_GROUP
import com.noobexon.xposedfakelocation.data.normalizeWifiSsid
import io.github.libxposed.api.XposedInterface

internal data class WifiIdentity(
    val ssid: String,
    val bssid: String,
    val rssi: Int
)

internal object WifiIdentityHookPolicy {
    private val gson = Gson()

    fun readActiveIdentity(): WifiIdentity? = runCatching {
        val preferences = getRemotePreferences()
        val isPlaying = preferences.getBoolean(KEY_IS_PLAYING, false)
        val wifiIdentityEnabled = preferences.getBoolean(
            KEY_ENABLE_WIFI_IDENTITY,
            DEFAULT_ENABLE_WIFI_IDENTITY
        )
        if (!(isPlaying && wifiIdentityEnabled)) {
            return@runCatching null
        }

        val ssid = normalizeWifiSsid(
            preferences.getString(KEY_WIFI_SSID, DEFAULT_WIFI_SSID)
        )
        val bssid = preferences.getString(KEY_WIFI_BSSID, DEFAULT_WIFI_BSSID)
            ?.trim()
            ?.takeIf(MAC_ADDRESS_REGEX::matches)
            ?: DEFAULT_WIFI_BSSID
        val rssi = preferences.getInt(KEY_WIFI_RSSI, DEFAULT_WIFI_RSSI)
            .coerceIn(MIN_WIFI_RSSI, MAX_WIFI_RSSI)

        WifiIdentity(ssid = ssid, bssid = bssid, rssi = rssi)
    }.getOrNull()

    // 静态方法，由调用者传入包名和用户ID判断
    fun shouldApply(packageName: String, userId: Int): Boolean {
        val prefs = getRemotePreferences()
        val isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false)
        val wifiIdentityEnabled = prefs.getBoolean(
            KEY_ENABLE_WIFI_IDENTITY,
            DEFAULT_ENABLE_WIFI_IDENTITY
        )
        if (!(isPlaying && wifiIdentityEnabled)) return false
        return PreferencesUtil.isPackageTargeted(packageName, userId)
    }

    private fun getRemotePreferences() =
        io.github.libxposed.service.XposedServiceHelper.getService()
            ?.getRemotePreferences(REMOTE_PREFS_GROUP)
            ?: throw IllegalStateException("Xposed service not available")
}