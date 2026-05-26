package com.suishiji.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

class PermissionGuideManager(private val activity: Activity) {

    private val prefs by lazy { activity.getSharedPreferences("suishiji", Context.MODE_PRIVATE) }

    fun checkAndGuide() {
        if (!hasOverlayPermission()) {
            showOverlayPermissionDialog()
            return
        }
        if (!hasForegroundLocation()) {
            showLocationAccuracyDialog()
            return
        }
        if (!hasBackgroundLocation()) {
            showBackgroundLocationDialog()
            return
        }
        if (!isBatteryUnrestricted()) {
            showBatteryDialog()
            return
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(activity)
        } else true
    }

    private fun hasForegroundLocation(): Boolean {
        return Settings.Secure.getInt(
            activity.contentResolver,
            Settings.Secure.LOCATION_MODE,
            Settings.Secure.LOCATION_MODE_OFF
        ) == 3
    }

    private fun hasBackgroundLocation(): Boolean {
        return androidx.core.app.ActivityCompat.checkSelfPermission(
            activity, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isBatteryUnrestricted(): Boolean {
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(activity.packageName)
    }

    private fun showOverlayPermissionDialog() {
        if (prefs.getBoolean("guide_overlay", false)) return
        prefs.edit().putBoolean("guide_overlay", true).apply()
        AlertDialog.Builder(activity)
            .setTitle("允许显示悬浮窗")
            .setMessage("为了提示信息不被其他窗口遮挡，请允许随时记显示悬浮窗。\n\n即将跳转到设置页面，请开启\"显示悬浮窗\"开关。")
            .setCancelable(false)
            .setPositiveButton("去开启") { _, _ ->
                activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                })
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLocationAccuracyDialog() {
        if (prefs.getBoolean("guide_location_accuracy", false)) return
        prefs.edit().putBoolean("guide_location_accuracy", true).apply()
        AlertDialog.Builder(activity)
            .setTitle("开启高精度定位")
            .setMessage("为了更准确地记录交易地点，请将定位模式设为\"高精度\"。\n\n即将跳转到定位设置页面，请选择\"高精度\"选项。")
            .setCancelable(false)
            .setPositiveButton("去开启") { _, _ ->
                try {
                    activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) {
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", activity.packageName, null)
                    })
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBackgroundLocationDialog() {
        if (prefs.getBoolean("guide_bg_location", false)) return
        prefs.edit().putBoolean("guide_bg_location", true).apply()
        AlertDialog.Builder(activity)
            .setTitle("开启后台定位权限")
            .setMessage("为了在后台记录交易地点，请在设置中将位置权限设为\"始终允许\"。\n\n即将跳转到权限设置页面。")
            .setCancelable(false)
            .setPositiveButton("去开启") { _, _ ->
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                })
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBatteryDialog() {
        if (prefs.getBoolean("guide_battery", false)) return
        prefs.edit().putBoolean("guide_battery", true).apply()
        AlertDialog.Builder(activity)
            .setTitle("关闭电池优化")
            .setMessage("为了保证后台正常运行，请将随时记加入电池优化白名单。\n\n即将跳转到电池优化设置页面。")
            .setCancelable(false)
            .setPositiveButton("去开启") { _, _ ->
                activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                })
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
