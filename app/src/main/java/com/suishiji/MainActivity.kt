package com.suishiji

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.suishiji.databinding.ActivityMainBinding
import com.suishiji.ui.HomeFragment
import com.suishiji.util.PermissionGuideManager

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionGuide: PermissionGuideManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingNotificationIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNav.setupWithNavController(navController)

        permissionGuide = PermissionGuideManager(this)

        checkAccessibilityPermission()
        checkNotificationListenerPermission()
        requestLocationPermissions()
        handleNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        permissionGuide.checkAndGuide()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("from_notification", false) != true) return

        val amount = intent.getStringExtra("amount") ?: return
        val merchant = intent.getStringExtra("merchant") ?: ""
        val source = intent.getStringExtra("source") ?: ""
        val type = intent.getStringExtra("type") ?: "expense"
        val category = intent.getStringExtra("category") ?: ""
        val date = intent.getLongExtra("date", System.currentTimeMillis())
        val latitude = intent.getDoubleExtra("latitude", 0.0).takeIf { it != 0.0 }
        val longitude = intent.getDoubleExtra("longitude", 0.0).takeIf { it != 0.0 }
        val address = intent.getStringExtra("address")?.takeIf { it.isNotBlank() }

        // 退款通知点击保存 → 移除通知
        if (intent.getBooleanExtra("is_refund", false)) {
            val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.cancel(com.suishiji.service.PaymentNotificationService.REFUND_NOTIFICATION_ID)
        }

        Log.d("Suishiji", "handleNotificationIntent: amount=$amount source=$source merchant=$merchant")
        // 延迟处理，等待 Fragment 创建完成
        pendingNotificationIntent = intent
        mainHandler.postDelayed({
            pendingNotificationIntent = null
            Log.d("Suishiji", "延迟处理: fragments=${supportFragmentManager.fragments.size}")
            for (f in supportFragmentManager.fragments) {
                Log.d("Suishiji", "  fragment: ${f.javaClass.simpleName}")
            }
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            if (navHostFragment == null) {
                Log.d("Suishiji", "NavHostFragment 未找到")
                return@postDelayed
            }
            Log.d("Suishiji", "NavHostFragment 子fragment数: ${navHostFragment.childFragmentManager.fragments.size}")
            for (f in navHostFragment.childFragmentManager.fragments) {
                Log.d("Suishiji", "  child: ${f.javaClass.simpleName}")
            }
            val homeFragment = navHostFragment.childFragmentManager.fragments
                .filterIsInstance<HomeFragment>()
                .firstOrNull()
            if (homeFragment == null) {
                Log.d("Suishiji", "HomeFragment 未找到")
                return@postDelayed
            }

            // 用户点击了通知，取消自动保存
            com.suishiji.service.PaymentNotificationService.consumePendingConfirmData()

            // 优先检查自动保存的 ID（解决竞态问题）
            var editId = com.suishiji.service.PaymentNotificationService.lastSavedId
            com.suishiji.service.PaymentNotificationService.lastSavedId = 0
            if (editId == 0L) {
                editId = intent.getLongExtra("edit_id", 0)
            }

            Log.d("Suishiji", "打开通知弹窗: $amount $source $merchant, editId=$editId")
            if (editId > 0) {
                // 已自动保存，打开编辑弹窗
                homeFragment.openForEdit(editId, amount, merchant, source, type, category, date, latitude, longitude, address)
            } else {
                homeFragment.openFromNotification(
                    amount, merchant, source, type, category, date,
                    latitude, longitude, address
                )
            }
        }, 500)
    }

    private fun checkAccessibilityPermission() {
        val prefs = getSharedPreferences("suishiji", MODE_PRIVATE)
        if (prefs.getBoolean("a11y_asked", false)) return

        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val serviceComponent = "$packageName/com.suishiji.service.PaymentNotificationService"
        if (enabledServices == null || !enabledServices.contains(serviceComponent)) {
            prefs.edit().putBoolean("a11y_asked", true).apply()
            AlertDialog.Builder(this)
                .setTitle("支付识别")
                .setMessage("开启无障碍服务后，可以自动识别支付宝/微信支付成功页面，自动记账")
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("暂不", null)
                .show()
        }
    }

    private fun checkNotificationListenerPermission() {
        val prefs = getSharedPreferences("suishiji", MODE_PRIVATE)
        if (prefs.getBoolean("notif_listener_asked", false)) return

        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val listenerComponent = "$packageName/com.suishiji.service.RefundNotificationListener"
        if (enabledListeners == null || !enabledListeners.contains(listenerComponent)) {
            prefs.edit().putBoolean("notif_listener_asked", true).apply()
            AlertDialog.Builder(this)
                .setTitle("退款识别")
                .setMessage("开启通知使用权后，可以自动识别支付宝/微信退款通知，自动记账")
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                }
                .setNegativeButton("暂不", null)
                .show()
        }
    }

    private fun requestLocationPermissions() {
        val prefs = getSharedPreferences("suishiji", MODE_PRIVATE)
        if (prefs.getBoolean("location_asked", false)) return

        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // Android 10+ requires background location permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        // Android 13+ requires notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            prefs.edit().putBoolean("location_asked", true).apply()
            AlertDialog.Builder(this)
                .setTitle("位置权限")
                .setMessage("开启位置权限后，可以自动记录交易地点，方便您查看消费轨迹")
                .setPositiveButton("去开启") { _, _ ->
                    ActivityCompat.requestPermissions(this, needed.toTypedArray(), LOCATION_PERMISSION_REQUEST)
                }
                .setNegativeButton("暂不", null)
                .show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            // If fine location granted but not background, guide user to settings
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val bgGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (fineGranted && !bgGranted) {
                    AlertDialog.Builder(this)
                        .setTitle("后台位置权限")
                        .setMessage("为了在后台记录交易地点，请在设置中选择\"始终允许\"位置权限")
                        .setPositiveButton("去设置") { _, _ ->
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", packageName, null)
                            })
                        }
                        .setNegativeButton("暂不", null)
                        .show()
                }
            }
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1002
    }
}
