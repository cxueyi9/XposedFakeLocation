package com.noobexon.xposedfakelocation.xposed.hooks

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.util.Log
import com.noobexon.xposedfakelocation.xposed.utils.PreferencesUtil
import io.github.libxposed.api.XposedInterface

class AppWifiHooks(
    private val module: XposedInterface,
    private val classLoader: ClassLoader,
    private val packageName: String
) {
    private val tag = "[AppWifiHooks]"

    fun init() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            module.log(Log.WARN, tag, "App-side Wi-Fi hooks require Android 11 or newer.")
            return
        }
        hookConnectionInfo()
        hookScanResults()
        module.log(Log.INFO, tag, "Instantiated app-side Wi-Fi hooks successfully")
    }

    private fun hookConnectionInfo() {
        runCatching {
            val wifiManagerClass = Class.forName("android.net.wifi.WifiManager", false, classLoader)
            val method = wifiManagerClass.getDeclaredMethod("getConnectionInfo")
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                val userId = android.os.Process.myUid() / 100000
                if (PreferencesUtil.getIsPlaying() == true && 
                    PreferencesUtil.isPackageTargeted(packageName, userId)) {
                    val identity = WifiIdentityHookPolicy.readActiveIdentity()
                    if (identity != null) {
                        module.log(Log.INFO, tag, "Replaced Wi-Fi connection info (app-side) while spoofing.")
                        return@intercept createFakeWifiInfo(identity)
                    }
                }
                result
            }
            module.log(Log.INFO, tag, "Hooked WifiManager#getConnectionInfo.")
        }.onFailure {
            module.log(Log.ERROR, tag, "Failed hooking WifiManager#getConnectionInfo: ${it.message}")
        }
    }

    private fun hookScanResults() {
        runCatching {
            val wifiManagerClass = Class.forName("android.net.wifi.WifiManager", false, classLoader)
            val method = wifiManagerClass.getDeclaredMethod("getScanResults")
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                val userId = android.os.Process.myUid() / 100000
                if (PreferencesUtil.getIsPlaying() == true &&
                    PreferencesUtil.isPackageTargeted(packageName, userId)) {
                    module.log(Log.INFO, tag, "Cleared Wi-Fi scan results (app-side) while spoofing.")
                    emptyList<ScanResult>()
                } else {
                    result
                }
            }
            module.log(Log.INFO, tag, "Hooked WifiManager#getScanResults.")
        }.onFailure {
            module.log(Log.ERROR, tag, "Failed hooking WifiManager#getScanResults: ${it.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createFakeWifiInfo(identity: WifiIdentity): WifiInfo {
        val builder = WifiInfo.Builder()
            .setBssid(identity.bssid)
            .setSsid(identity.ssid.toByteArray())
            .setRssi(identity.rssi)
            .setNetworkId(0)
        return builder.build()
    }
}
