package com.noobexon.xposedfakelocation.xposed.hooks

import com.noobexon.xposedfakelocation.data.DEFAULT_WIFI_BSSID
import com.noobexon.xposedfakelocation.data.DEFAULT_WIFI_RSSI
import com.noobexon.xposedfakelocation.data.DEFAULT_WIFI_SSID
import com.noobexon.xposedfakelocation.data.MAC_ADDRESS_REGEX
import com.noobexon.xposedfakelocation.data.MAX_WIFI_RSSI
import com.noobexon.xposedfakelocation.data.MIN_WIFI_RSSI
import com.noobexon.xposedfakelocation.data.normalizeWifiSsid
import com.noobexon.xposedfakelocation.xposed.utils.PreferencesUtil

internal data class WifiIdentity(
    val ssid: String,
    val bssid: String,
    val rssi: Int
)

internal object WifiIdentityHookPolicy {
    fun readActiveIdentity(): WifiIdentity? {
        val isPlaying = PreferencesUtil.getIsPlaying() ?: false
        val wifiEnabled = PreferencesUtil.getEnableWifiIdentity()
        if (!isPlaying || !wifiEnabled) return null

        val ssid = normalizeWifiSsid(PreferencesUtil.getWifiSsid())
        val bssid = PreferencesUtil.getWifiBssid()
        val rssi = PreferencesUtil.getWifiRssi()

        return WifiIdentity(ssid = ssid, bssid = bssid, rssi = rssi)
    }
}
