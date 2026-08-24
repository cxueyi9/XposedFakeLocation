package com.noobexon.xposedfakelocation.xposed.utils

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.noobexon.xposedfakelocation.data.DEFAULT_ACCURACY
import com.noobexon.xposedfakelocation.data.DEFAULT_ALTITUDE
import com.noobexon.xposedfakelocation.data.DEFAULT_MEAN_SEA_LEVEL
import com.noobexon.xposedfakelocation.data.DEFAULT_MEAN_SEA_LEVEL_ACCURACY
import com.noobexon.xposedfakelocation.data.DEFAULT_RANDOMIZE_RADIUS
import com.noobexon.xposedfakelocation.data.DEFAULT_SPEED
import com.noobexon.xposedfakelocation.data.DEFAULT_SPEED_ACCURACY
import com.noobexon.xposedfakelocation.data.DEFAULT_VERTICAL_ACCURACY
import com.noobexon.xposedfakelocation.data.DEFAULT_WIFI_BSSID
import com.noobexon.xposedfakelocation.data.DEFAULT_WIFI_RSSI
import com.noobexon.xposedfakelocation.data.DEFAULT_WIFI_SSID
import com.noobexon.xposedfakelocation.data.KEY_ACCURACY
import com.noobexon.xposedfakelocation.data.KEY_ALTITUDE
import com.noobexon.xposedfakelocation.data.KEY_ENABLE_SYSTEM_HOOKS
import com.noobexon.xposedfakelocation.data.KEY_HIDE_FAKE_LOCATION_TOAST
import com.noobexon.xposedfakelocation.data.KEY_IS_PLAYING
import com.noobexon.xposedfakelocation.data.KEY_LAST_CLICKED_LOCATION
import com.noobexon.xposedfakelocation.data.KEY_MEAN_SEA_LEVEL
import com.noobexon.xposedfakelocation.data.KEY_MEAN_SEA_LEVEL_ACCURACY
import com.noobexon.xposedfakelocation.data.KEY_RANDOMIZE_RADIUS
import com.noobexon.xposedfakelocation.data.KEY_SPEED
import com.noobexon.xposedfakelocation.data.KEY_SPEED_ACCURACY
import com.noobexon.xposedfakelocation.data.KEY_TARGET_APPS
import com.noobexon.xposedfakelocation.data.KEY_USE_ACCURACY
import com.noobexon.xposedfakelocation.data.KEY_USE_ALTITUDE
import com.noobexon.xposedfakelocation.data.KEY_USE_MEAN_SEA_LEVEL
import com.noobexon.xposedfakelocation.data.KEY_USE_MEAN_SEA_LEVEL_ACCURACY
import com.noobexon.xposedfakelocation.data.KEY_USE_RANDOMIZE
import com.noobexon.xposedfakelocation.data.KEY_USE_SPEED
import com.noobexon.xposedfakelocation.data.KEY_USE_SPEED_ACCURACY
import com.noobexon.xposedfakelocation.data.KEY_USE_VERTICAL_ACCURACY
import com.noobexon.xposedfakelocation.data.KEY_VERTICAL_ACCURACY
import com.noobexon.xposedfakelocation.data.KEY_WIFI_BSSID
import com.noobexon.xposedfakelocation.data.KEY_WIFI_RSSI
import com.noobexon.xposedfakelocation.data.KEY_WIFI_SSID
import com.noobexon.xposedfakelocation.data.MAC_ADDRESS_REGEX
import com.noobexon.xposedfakelocation.data.MAX_WIFI_RSSI
import com.noobexon.xposedfakelocation.data.MIN_WIFI_RSSI
import com.noobexon.xposedfakelocation.data.model.LastClickedLocation
import com.noobexon.xposedfakelocation.data.normalizeWifiSsid
import com.noobexon.xposedfakelocation.xposed.utils.PreferencesUtil.gson
import com.noobexon.xposedfakelocation.xposed.utils.PreferencesUtil.init

/**
 * Hook-side accessor for the LSPosed remote [SharedPreferences] written by the manager app.
 */
object PreferencesUtil {
    private const val TAG = "[PreferencesUtil]"
    private val gson = Gson()

    @Volatile var logger: ((Int, String, String) -> Unit)? = null
    private fun log(msg: String, priority: Int = Log.INFO) = logger?.invoke(priority, TAG, msg)

    @Volatile private var preferences: SharedPreferences? = null

    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        log("Remote pref changed: $key")
    }

    fun init(prefs: SharedPreferences) {
        preferences = prefs
        prefs.registerOnSharedPreferenceChangeListener(changeListener)
        log("Initialized with remote preferences")
    }

    /** @return `true` if spoofing is currently active, `false`/`null` if not set. */
    fun getIsPlaying(): Boolean? = getPreference(KEY_IS_PLAYING)
    fun getLastClickedLocation(): LastClickedLocation? = getPreference(KEY_LAST_CLICKED_LOCATION)
    fun getUseAccuracy(): Boolean? = getPreference(KEY_USE_ACCURACY)
    fun getAccuracy(): Double? = getPreference(KEY_ACCURACY)
    fun getUseAltitude(): Boolean? = getPreference(KEY_USE_ALTITUDE)
    fun getAltitude(): Double? = getPreference(KEY_ALTITUDE)
    fun getUseRandomize(): Boolean? = getPreference(KEY_USE_RANDOMIZE)
    fun getRandomizeRadius(): Double? = getPreference(KEY_RANDOMIZE_RADIUS)
    fun getUseVerticalAccuracy(): Boolean? = getPreference(KEY_USE_VERTICAL_ACCURACY)
    fun getVerticalAccuracy(): Float? = getPreference(KEY_VERTICAL_ACCURACY)
    fun getUseMeanSeaLevel(): Boolean? = getPreference(KEY_USE_MEAN_SEA_LEVEL)
    fun getMeanSeaLevel(): Double? = getPreference(KEY_MEAN_SEA_LEVEL)
    fun getUseMeanSeaLevelAccuracy(): Boolean? = getPreference(KEY_USE_MEAN_SEA_LEVEL_ACCURACY)
    fun getMeanSeaLevelAccuracy(): Float? = getPreference(KEY_MEAN_SEA_LEVEL_ACCURACY)
    fun getUseSpeed(): Boolean? = getPreference(KEY_USE_SPEED)
    fun getSpeed(): Float? = getPreference(KEY_SPEED)
    fun getUseSpeedAccuracy(): Boolean? = getPreference(KEY_USE_SPEED_ACCURACY)
    fun getSpeedAccuracy(): Float? = getPreference(KEY_SPEED_ACCURACY)
    fun getHideFakeLocationToast(): Boolean? = getPreference(KEY_HIDE_FAKE_LOCATION_TOAST)
    fun getEnableSystemHooks(): Boolean = preferences?.getBoolean(KEY_ENABLE_SYSTEM_HOOKS, false) ?: false

    fun getWifiSsid(): String =
        normalizeWifiSsid(preferences?.getString(KEY_WIFI_SSID, DEFAULT_WIFI_SSID))

    fun getWifiBssid(): String =
        preferences?.getString(KEY_WIFI_BSSID, DEFAULT_WIFI_BSSID)?.trim()?.takeIf(MAC_ADDRESS_REGEX::matches)
            ?: DEFAULT_WIFI_BSSID

    fun getWifiRssi(): Int =
        preferences?.getInt(KEY_WIFI_RSSI, DEFAULT_WIFI_RSSI)?.coerceIn(MIN_WIFI_RSSI, MAX_WIFI_RSSI)
            ?: DEFAULT_WIFI_RSSI

    /**
     * 返回目标应用列表，每个条目格式为 "包名|用户ID"
     */
    fun getTargetAppsWithUser(): Set<String> {
        val prefs = preferences ?: return emptySet()
        val json = prefs.getString(KEY_TARGET_APPS, null) ?: return emptySet()
        return runCatching {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>?>(json, type)?.toSet() ?: emptySet()
        }.onFailure { log("Error parsing $KEY_TARGET_APPS JSON: ${it.message}", Log.ERROR) }
            .getOrDefault(emptySet())
    }

fun getEnableWifiIdentity(): Boolean = preferences?.getBoolean(KEY_ENABLE_WIFI_IDENTITY, DEFAULT_ENABLE_WIFI_IDENTITY) ?: false

    /**
     * 判断给定的包名和用户ID是否在目标列表中
     */
    fun isPackageTargeted(packageName: String, userId: Int): Boolean {
        return getTargetAppsWithUser().contains("$packageName|$userId")
    }

    // ========== 保留原有 getTargetApps 方法以兼容，但已弃用 ==========
    @Deprecated("Use getTargetAppsWithUser() or isPackageTargeted() instead")
    fun getTargetApps(): Set<String> {
        // 仅仅返回包名，丢失用户ID，不建议使用
        return getTargetAppsWithUser().map { it.substringBefore('|') }.toSet()
    }

    /**
     * Generic preference reader.
     */
    private inline fun <reified T> getPreference(key: String): T? {
        val preferences = preferences ?: return null
        return when (T::class) {
            Double::class -> {
                val defaultValue = when (key) {
                    KEY_ACCURACY -> java.lang.Double.doubleToRawLongBits(DEFAULT_ACCURACY)
                    KEY_ALTITUDE -> java.lang.Double.doubleToRawLongBits(DEFAULT_ALTITUDE)
                    KEY_RANDOMIZE_RADIUS -> java.lang.Double.doubleToRawLongBits(DEFAULT_RANDOMIZE_RADIUS)
                    KEY_MEAN_SEA_LEVEL -> java.lang.Double.doubleToRawLongBits(DEFAULT_MEAN_SEA_LEVEL)
                    else -> -1L
                }
                val bits = preferences.getLong(key, defaultValue)
                java.lang.Double.longBitsToDouble(bits) as? T
            }
            Float::class -> {
                val defaultValue = when (key) {
                    KEY_VERTICAL_ACCURACY -> DEFAULT_VERTICAL_ACCURACY
                    KEY_MEAN_SEA_LEVEL_ACCURACY -> DEFAULT_MEAN_SEA_LEVEL_ACCURACY
                    KEY_SPEED -> DEFAULT_SPEED
                    KEY_SPEED_ACCURACY -> DEFAULT_SPEED_ACCURACY
                    else -> -1f
                }
                preferences.getFloat(key, defaultValue) as? T
            }
            Boolean::class -> preferences.getBoolean(key, false) as? T
            else -> {
                val json = preferences.getString(key, null) ?: return null.also { log("$key not found in preferences.") }
                runCatching { gson.fromJson(json, T::class.java).also { log("Retrieved $key: $it") } }
                    .onFailure { log("Error parsing $key JSON: ${it.message}") }
                    .getOrNull()
            }
        }
    }
}