package com.suishiji.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

data class LocResult(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val address: String?
)

interface LocationCallback {
    fun onResult(result: LocResult)
    fun onBetterResult(result: LocResult)
    fun onUpdate(result: LocResult)
    fun onError(message: String)
}

class PreciseLocator(private val context: Context) {

    private var callback: LocationCallback? = null
    private var stopped = false
    private val handler = Handler(Looper.getMainLooper())
    private var nativeLocationListener: LocationListener? = null

    fun locate(callback: LocationCallback) {
        this.callback = callback
        stopped = false

        AppLocationManager.locateFast(context, timeoutMs = 8000L, callback = { result ->
            if (stopped) return@locateFast
            if (result != null) callback.onResult(result)
            else callback.onError("定位失败")
        })

        AppLocationManager.onAddressUpdate = { result ->
            if (!stopped) callback.onUpdate(result)
        }
    }

    @SuppressLint("MissingPermission")
    fun locateGps(callback: LocationCallback) {
        this.callback = callback
        stopped = false

        AppLocationManager.locateGps(context, timeoutMs = 15000L, callback = object : com.suishiji.util.LocationCallback {
            override fun onResult(result: LocResult) {
                if (!stopped) callback.onResult(result)
            }
            override fun onBetterResult(result: LocResult) {
                if (!stopped) callback.onBetterResult(result)
            }
            override fun onUpdate(result: LocResult) {
                if (!stopped) callback.onUpdate(result)
            }
            override fun onError(message: String) {
                if (!stopped) callback.onError(message)
            }
        })
    }

    private fun stopNativeLocation(lm: LocationManager) {
        nativeLocationListener?.let {
            try { lm.removeUpdates(it) } catch (_: Exception) {}
        }
        nativeLocationListener = null
    }

    fun stop() {
        stopped = true
        callback = null
        AppLocationManager.onAddressUpdate = null
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            stopNativeLocation(lm)
        } catch (_: Exception) {}
    }
}
