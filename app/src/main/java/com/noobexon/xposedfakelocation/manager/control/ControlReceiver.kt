package com.noobexon.xposedfakelocation.manager.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.noobexon.xposedfakelocation.data.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ControlReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ControlReceiver"
        const val ACTION_START = "com.noobexon.xposedfakelocation.action.START"
        const val ACTION_STOP = "com.noobexon.xposedfakelocation.action.STOP"
        const val ACTION_SET_LOCATION = "com.noobexon.xposedfakelocation.action.SET_LOCATION"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_ACCURACY = "accuracy"
        const val EXTRA_START = "start"

        private const val LAT_MIN = -90.0
        private const val LAT_MAX = 90.0
        private const val LON_MIN = -180.0
        private const val LON_MAX = 180.0
        private const val ACCURACY_MAX_METERS = 100_000f
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val repository = PreferencesRepository(appContext)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_START -> handleStart(intent, repository)
                    ACTION_STOP -> repository.saveIsPlaying(false)
                    ACTION_SET_LOCATION -> handleSetLocation(intent, repository)
                    else -> Log.w(TAG, "Unknown action: $action")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling $action: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleStart(intent: Intent, repository: PreferencesRepository) {
        if (intent.hasExtra(EXTRA_LATITUDE) && intent.hasExtra(EXTRA_LONGITUDE)) {
            val coords = parseCoordinates(intent)
            if (coords != null) {
                repository.saveLastClickedLocation(coords.first, coords.second)
            }
        }
        repository.saveIsPlaying(true)
    }

    private suspend fun handleSetLocation(intent: Intent, repository: PreferencesRepository) {
        val coords = parseCoordinates(intent) ?: return
        repository.saveLastClickedLocation(coords.first, coords.second)

        if (intent.hasExtra(EXTRA_ACCURACY)) {
            val accuracy = intent.getFloatExtra(EXTRA_ACCURACY, Float.NaN)
            if (accuracy.isFinite() && accuracy >= 0f && accuracy <= ACCURACY_MAX_METERS) {
                repository.saveUseAccuracy(true)
                repository.saveAccuracy(accuracy.toDouble())
            } else {
                Log.w(TAG, "Ignoring out-of-range accuracy: $accuracy")
            }
        }

        if (intent.getBooleanExtra(EXTRA_START, false)) {
            repository.saveIsPlaying(true)
        }
    }

    /**
     * 解析坐标，支持 Double、Float 和 String 三种类型
     * 这样无论使用 --ed、--ef 还是 --es 都能正确读取
     */
    private fun parseCoordinates(intent: Intent): Pair<Double, Double>? {
        // 1. 优先尝试 Double 类型（对应 --ed）
        var lat = intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN)
        var lon = intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN)

        // 2. 如果 Double 读取失败，尝试 Float 类型（对应 --ef）
        if (!lat.isFinite() || !lon.isFinite()) {
            val latF = intent.getFloatExtra(EXTRA_LATITUDE, Float.NaN)
            val lonF = intent.getFloatExtra(EXTRA_LONGITUDE, Float.NaN)
            if (latF.isFinite() && lonF.isFinite()) {
                lat = latF.toDouble()
                lon = lonF.toDouble()
            }
        }

        // 3. 如果还是失败，尝试 String 类型（对应 --es，备选方案）
        if (!lat.isFinite() || !lon.isFinite()) {
            intent.getStringExtra(EXTRA_LATITUDE)?.let { latStr ->
                intent.getStringExtra(EXTRA_LONGITUDE)?.let { lonStr ->
                    try {
                        lat = latStr.toDouble()
                        lon = lonStr.toDouble()
                    } catch (_: NumberFormatException) {
                        // 解析失败，保持 NaN
                    }
                }
            }
        }

        // 4. 验证坐标是否有效
        if (!lat.isFinite() || !lon.isFinite()) {
            Log.w(TAG, "Rejecting non-finite latitude/longitude")
            return null
        }

        if (lat < LAT_MIN || lat > LAT_MAX || lon < LON_MIN || lon > LON_MAX) {
            Log.w(TAG, "Rejecting out-of-range coordinates lat=$lat lon=$lon")
            return null
        }

        return lat to lon
    }
}
