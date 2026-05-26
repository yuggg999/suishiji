package com.suishiji

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.amap.api.location.AMapLocationClient
import com.suishiji.db.AppDatabase
import com.suishiji.util.AppLocationManager
import com.suishiji.util.LogManager

class App : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        LogManager.init(this)
        val darkMode = getSharedPreferences("suishiji", 0).getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (darkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)

        // 读取并设置 API Key
        val appInfo = packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA)
        val apiKey = appInfo.metaData?.getString("com.amap.api.v2.apikey")
        if (!apiKey.isNullOrBlank()) {
            AMapLocationClient.setApiKey(apiKey)
        }

        AppLocationManager.init(this)
    }
}
