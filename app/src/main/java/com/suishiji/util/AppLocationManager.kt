package com.suishiji.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AppLocationManager {

    private var client: AMapLocationClient? = null
    private var lastResult: LocResult? = null
    private var lastResultTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var fastLocating = false
    private var gpsLocating = false

    var onAddressUpdate: ((LocResult) -> Unit)? = null

    private const val CACHE_VALID_MS = 60_000L

    private fun log(msg: String) {
        Log.d("Suishiji", "LocMgr: $msg")
    }

    fun init(context: Context) {
        if (client != null) return
        try {
            // 读取 manifest 中的 API Key
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
            val apiKey = appInfo.metaData?.getString("com.amap.api.v2.apikey")
            log("API Key: $apiKey")

            client = AMapLocationClient(context.applicationContext)
            log("AMap客户端初始化完成")
        } catch (e: Exception) {
            log("AMap初始化失败: ${e.message}")
        }
    }

    // ==================== 极速定位 ====================

    fun locateFast(context: Context, timeoutMs: Long = 5000L, callback: (LocResult?) -> Unit) {
        if (fastLocating) {
            if (lastResult != null) callback(lastResult)
            return
        }

        val cacheAge = System.currentTimeMillis() - lastResultTime
        if (lastResult != null && cacheAge < CACHE_VALID_MS) {
            log("内存缓存命中")
            callback(lastResult)
            return
        }

        fastLocating = true
        init(context)
        if (client == null) {
            fastLocating = false
            callback(null)
            return
        }

        val option = AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = true
            isOnceLocationLatest = false
            isNeedAddress = true
            isLocationCacheEnable = true
            isGpsFirst = false
            isWifiScan = true
            isWifiActiveScan = true
            httpTimeOut = timeoutMs
        }

        var delivered = false

        client?.stopLocation()
        client!!.setLocationOption(option)
        client!!.setLocationListener(AMapLocationListener { loc ->
            log("AMap回调: errorCode=${loc?.errorCode}, type=${loc?.locationType}, errorInfo=${loc?.errorInfo}, locationDetail=${loc?.locationDetail}")
            if (loc != null && loc.errorCode == 0 && !delivered) {
                delivered = true
                fastLocating = false
                val result = LocResult(loc.latitude, loc.longitude, loc.accuracy, loc.address)
                lastResult = result
                lastResultTime = System.currentTimeMillis()
                log("AMap成功: acc=${loc.accuracy}, addr=${loc.address}")
                client?.stopLocation()
                callback(result)
            }
        })

        client!!.startLocation()
        log("AMap开始(${timeoutMs}ms)")

        handler.postDelayed({
            if (!delivered) {
                delivered = true
                fastLocating = false
                client?.stopLocation()
                log("AMap超时")
                callback(lastResult)
            }
        }, timeoutMs)
    }

    // ==================== GPS 精确定位 (高德 SDK) ====================

    @SuppressLint("MissingPermission")
    fun locateGps(context: Context, timeoutMs: Long = 15000L, callback: LocationCallback) {
        if (gpsLocating) {
            if (lastResult != null) callback.onResult(lastResult!!)
            else callback.onError("正在定位中")
            return
        }

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            callback.onError("缺少定位权限")
            return
        }

        gpsLocating = true
        init(context)
        if (client == null) {
            gpsLocating = false
            callback.onError("定位服务初始化失败")
            return
        }
        log("高德GPS开始(${timeoutMs}ms)")

        val option = AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = false
            isNeedAddress = true
            isLocationCacheEnable = false
            isGpsFirst = true
            gpsFirstTimeout = 5000L
            interval = 2000L
        }

        var delivered = false
        var bestLoc: LocResult? = null

        client?.stopLocation()
        client!!.setLocationOption(option)
        client!!.setLocationListener(AMapLocationListener { loc ->
            if (loc == null || loc.errorCode != 0) return@AMapLocationListener
            val acc = loc.accuracy
            log("高德GPS回调: lat=${loc.latitude}, lng=${loc.longitude}, acc=${acc}, type=${loc.locationType}")

            if (loc.locationType != 1) return@AMapLocationListener

                    val locResult = LocResult(loc.latitude, loc.longitude, acc, loc.address)
            if (bestLoc == null || acc < bestLoc!!.accuracy) {
                bestLoc = locResult
            }

            handler.post {
                if (!delivered) {
                    delivered = true
                    log("高德GPS首次: acc=${acc}, addr=${loc.address ?: "无"}")
                    callback.onResult(locResult)
                } else {
                    log("高德GPS更优: acc=${acc}")
                    callback.onBetterResult(locResult)
                }
                if (acc < 10f) {
                    gpsLocating = false
                    client?.stopLocation()
                    lastResult = locResult
                    lastResultTime = System.currentTimeMillis()
                }
            }
        })

        client!!.startLocation()
        log("高德GPS回调已注册，等待定位...")

        handler.postDelayed({
            if (!delivered) {
                delivered = true
                gpsLocating = false
                client?.stopLocation()
                if (bestLoc != null) {
                    lastResult = bestLoc
                    lastResultTime = System.currentTimeMillis()
                    callback.onResult(bestLoc!!)
                } else {
                    callback.onError("GPS定位超时")
                }
            } else {
                gpsLocating = false
                client?.stopLocation()
            }
        }, timeoutMs)
    }

    fun searchPoi(context: Context, lat: Double, lng: Double, callback: (String?) -> Unit) {
        try {
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            val apiKey = appInfo.metaData?.getString("com.amap.api.v2.apikey")
            if (apiKey == null) {
                callback(null)
                return
            }
            Thread {
                try {
                    val urlStr = "https://restapi.amap.com/v3/place/around?location=$lng,$lat&key=$apiKey&radius=100&extensions=base"
                    val url = URL(urlStr)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    val response = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()

                    val json = JSONObject(response)
                    if (json.optString("status") == "1") {
                        val pois = json.optJSONArray("pois")
                        if (pois != null && pois.length() > 0) {
                            val name = pois.getJSONObject(0).optString("name")
                            log("POI搜索成功: $name")
                            callback(name)
                        } else {
                            log("POI搜索无结果")
                            callback(null)
                        }
                    } else {
                        log("POI搜索失败: ${json.optString("info")}")
                        callback(null)
                    }
                } catch (e: Exception) {
                    log("POI搜索异常: ${e.message}")
                    callback(null)
                }
            }.start()
        } catch (e: Exception) {
            log("POI搜索异常: ${e.message}")
            callback(null)
        }
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        client?.stopLocation()
        fastLocating = false
        gpsLocating = false
    }
}
