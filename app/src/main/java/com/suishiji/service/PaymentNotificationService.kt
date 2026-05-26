package com.suishiji.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.suishiji.App
import com.suishiji.MainActivity
import com.suishiji.R
import com.suishiji.util.AppLocationManager
import com.suishiji.util.LocResult
import com.suishiji.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PaymentNotificationService : AccessibilityService() {

    private var lastEventTime = 0L
    private var lastAmount = ""
    private var lastTime = 0L

    private fun log(msg: String) {
        Log.d("Suishiji", msg)
        LogManager.log("A11y", msg)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        createNotificationChannel()
        AppLocationManager.init(applicationContext)
        log("无障碍服务已连接  ✓")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in setOf("com.tencent.mm", "com.eg.android.AlipayGphone")) return

        val now = System.currentTimeMillis()

        when (event.eventType) {
            // 支付成功页面识别
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowChanged(pkg, now)
            }
            // 退款兜底：页面内容变化时检测
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleContentChanged(pkg, now)
            }
        }
    }

    // ==================== 支付成功页面识别 ====================

    private fun handleWindowChanged(pkg: String, now: Long) {
        // 去重：500ms 内不重复处理
        if (now - lastEventTime < 500) return
        lastEventTime = now

        val root = rootInActiveWindow ?: return
        val allTexts = getAllTexts(root)
        val pageText = allTexts.joinToString(" ")
        root.recycle()

        log("检测到页面 → $pageText")

        when (pkg) {
            "com.eg.android.AlipayGphone" -> handleAlipayPay(allTexts, pageText, now)
            "com.tencent.mm" -> handleWechatPay(allTexts, pageText, now)
        }
    }

    private fun handleAlipayPay(allTexts: List<String>, pageText: String, now: Long) {
        if (pageText.contains("更改付款方式")) return
        val homeIndicators = arrayOf("扫一扫", "收付款", "出行", "蚂蚁森林", "蚂蚁庄园")
        if (homeIndicators.any { pageText.contains(it) }) return

        val hasSuccess = pageText.contains("支付成功") || pageText.contains("付款成功") ||
                (pageText.contains("完成") && pageText.contains("交易方式"))
        if (!hasSuccess) return

        log("识别到支付宝支付成功  ✓")

        val amount = extractAmount(allTexts) ?: return
        val merchant = extractMerchant(allTexts) ?: ""
        processPayment(amount, merchant, "支付宝", now)
    }

    private fun handleWechatPay(allTexts: List<String>, pageText: String, now: Long) {
        val isPaySuccess = pageText.contains("支付成功") || pageText.contains("支付完成") ||
                pageText.contains("付款成功") || pageText.contains("已支付") ||
                pageText.contains("交易完成")
        if (!isPaySuccess) return
        log("识别到微信支付完成  ✓")

        val amount = extractAmount(allTexts) ?: return
        val merchant = extractMerchant(allTexts) ?: ""
        processPayment(amount, merchant, "微信", now)
    }

    // ==================== 内容变化（无障碍服务不再处理退款，由 NotificationListenerService 处理） ====================

    private fun handleContentChanged(pkg: String, now: Long) {
        // 退款识别已移至 RefundNotificationListener
    }

    // ==================== 支付处理 ====================

    private fun processPayment(amountStr: String, merchant: String, source: String, now: Long) {
        val amount = amountStr.toDoubleOrNull() ?: return
        log("解析结果 → $source ${amountStr}元 · $merchant")

        if (amountStr == lastAmount && now - lastTime < 60000) {
            log("检测到相同金额 ${amountStr}元，弹出确认通知")
            showDuplicateConfirmNotification(amountStr, merchant, source, "expense", false, now)
            return
        }
        lastAmount = amountStr
        lastTime = now

        AppLocationManager.locateFast(applicationContext, timeoutMs = 3000L) { location: LocResult? ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = (applicationContext as App).database
                    val catDao = db.categoryDao()

                    var category = "其它"
                    if (merchant.isNotBlank()) {
                        val allCats = catDao.getByType(false)
                        for (cat in allCats) {
                            if (cat.keywords.isNotBlank()) {
                                for (kw in cat.keywords.split(",")) {
                                    if (kw.isNotBlank() && merchant.contains(kw, ignoreCase = true)) {
                                        category = cat.name
                                        break
                                    }
                                }
                            }
                            if (category != "其它") break
                        }
                    }

                    pendingConfirmData = ConfirmData(
                        amountStr = amountStr,
                        amount = amount,
                        merchant = merchant,
                        source = source,
                        type = "expense",
                        category = category,
                        date = now,
                        latitude = location?.latitude,
                        longitude = location?.longitude,
                        address = location?.address
                    )

                    withContext(Dispatchers.Main) {
                        val intent = Intent(applicationContext, MainActivity::class.java).apply {
                            putExtra("from_notification", true)
                            putExtra("amount", amountStr)
                            putExtra("merchant", merchant)
                            putExtra("source", source)
                            putExtra("type", "expense")
                            putExtra("category", category)
                            putExtra("date", now)
                            putExtra("latitude", location?.latitude ?: 0.0)
                            putExtra("longitude", location?.longitude ?: 0.0)
                            putExtra("address", location?.address ?: "")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        val pendingIntent = PendingIntent.getActivity(
                            applicationContext, 0, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )

                        val notifText = "支出 ${amountStr}元 · $category"
                        val customView = android.widget.RemoteViews(packageName, R.layout.notification_payment).apply {
                            setTextViewText(R.id.notif_title, "随时记 · 支付识别")
                            setTextViewText(R.id.notif_content, notifText)
                        }

                        val notification = Notification.Builder(applicationContext, CONFIRM_CHANNEL_ID)
                            .setCustomContentView(customView)
                            .setCustomBigContentView(customView)
                            .setSmallIcon(R.drawable.ic_add)
                            .setContentIntent(pendingIntent)
                            .setAutoCancel(true)
                            .setDefaults(Notification.DEFAULT_ALL)
                            .setPriority(Notification.PRIORITY_HIGH)
                            .setCategory(Notification.CATEGORY_MESSAGE)
                            .setVisibility(Notification.VISIBILITY_PUBLIC)
                            .build()

                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.notify(NOTIFICATION_ID, notification)
                        log("已发送确认通知 → $notifText  ✓")
                    }

                    // 3秒后自动保存
                    kotlinx.coroutines.delay(3000)
                    val data = pendingConfirmData ?: return@launch
                    val tx = com.suishiji.db.Transaction(
                        amount = data.amount,
                        type = data.type,
                        category = data.category,
                        note = if (data.merchant.isNotBlank()) data.merchant else data.source,
                        date = data.date,
                        latitude = data.latitude,
                        longitude = data.longitude,
                        address = data.address
                    )
                    lastSavedId = db.transactionDao().insert(tx)
                    pendingConfirmData = null
                    log("自动保存成功 ✓ ${data.amountStr}元")
                } catch (e: Exception) {
                    log("支付识别失败 ✗ ${e.message}")
                }
            }
        }
    }

    // ==================== 金额提取 ====================

    private fun extractAmount(texts: List<String>): String? {
        val discountKw = setOf("优惠", "红包", "立减", "折扣", "已省", "已优惠", "减")
        val paymentKw = setOf("实付", "实际", "付款金额", "合计", "共", "总计", "交易金额", "金额")
        val symbolRegex = Regex("""[¥￥]\s*(\d+\.?\d*)""")
        val yuanRegex = Regex("""(\d+\.?\d*)\s*元""")

        // 1. 优先匹配含支付关键词的文本中的金额（实付、合计等 -> 最终实付金额）
        for (text in texts) {
            if (paymentKw.any { text.contains(it) }) {
                val match = symbolRegex.find(text)
                if (match != null) {
                    val amount = match.groupValues[1]
                    if (amount.toDoubleOrNull() != null && amount.toDouble() > 0) return amount
                }
                val yuanMatch = yuanRegex.find(text)
                if (yuanMatch != null) {
                    val amount = yuanMatch.groupValues[1]
                    if (amount.toDoubleOrNull() != null && amount.toDouble() > 0) return amount
                }
            }
        }

        // 2. 其次匹配不含折扣关键词的 ¥￥ 金额
        for (text in texts) {
            if (!discountKw.any { text.contains(it) }) {
                val match = symbolRegex.find(text)
                if (match != null) {
                    val amount = match.groupValues[1]
                    if (amount.toDoubleOrNull() != null && amount.toDouble() > 0) return amount
                }
            }
        }

        // 3. 匹配不含折扣关键词的 元 金额
        for (text in texts) {
            if (!discountKw.any { text.contains(it) }) {
                val match = yuanRegex.find(text)
                if (match != null) {
                    val amount = match.groupValues[1]
                    if (amount.toDoubleOrNull() != null && amount.toDouble() > 0) return amount
                }
            }
        }

        // 4. 最后匹配纯数字金额（排除手机号等）
        val pureRegex = Regex("""\b(\d+\.?\d*)\b""")
        for (text in texts) {
            val match = pureRegex.find(text)
            if (match != null) {
                val amount = match.groupValues[1]
                val amountDouble = amount.toDoubleOrNull()
                // 排除：手机号（11位）、过大金额（>10000）、过小金额（<0.01）、含折扣关键词的文本
                if (amountDouble != null && amountDouble > 0.01 && amountDouble < 10000 && amount.length <= 8) {
                    return amount
                }
            }
        }

        return null
    }

    // ==================== 商家提取 ====================

    private fun extractMerchant(texts: List<String>): String? {
        val exclude = setOf("支付成功", "支付完成", "已支付", "确认收货", "查看账单",
            "完成", "返回", "微信支付", "支付宝", "交易", "账单", "详情", "付款成功",
            "优惠", "红包", "立减", "折扣", "已省", "已优惠")
        for (text in texts) {
            val cleaned = text.trim()
            if (cleaned.length in 2..20
                && !cleaned.matches(Regex("""[\d.¥￥元\s]+"""))
                && cleaned !in exclude
                && !cleaned.contains("支付成功")
                && !cleaned.contains("支付完成")
                && !cleaned.contains("付款成功")
                && !cleaned.contains("退款")) {
                return cleaned
            }
        }
        return null
    }

    // ==================== 节点树工具 ====================

    private fun getAllTexts(node: AccessibilityNodeInfo): List<String> {
        val result = mutableListOf<String>()
        val t = node.text?.toString()
        if (!t.isNullOrBlank()) result.add(t)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            result.addAll(getAllTexts(child))
        }
        return result
    }

    // ==================== 确认通知 ====================

    private fun showDuplicateConfirmNotification(
        amountStr: String, merchant: String, source: String,
        type: String, isIncome: Boolean, date: Long
    ) {
        val typeStr = if (type == "income") "收入" else "支出"
        val title = "相同金额通知"
        val text = "$typeStr ${amountStr}元 · $merchant"

        val category = if (isIncome) "其它收入" else "其它"
        // 不设置 pendingConfirmData，避免覆盖首次事件的自动保存数据
        // 用户点击通知后走 MainActivity → AddRecordDialog，不会自动保存

        val saveIntent = Intent(applicationContext, MainActivity::class.java).apply {
            putExtra("from_notification", true)
            putExtra("amount", amountStr)
            putExtra("merchant", merchant)
            putExtra("source", source)
            putExtra("type", type)
            putExtra("category", category)
            putExtra("date", date)
            putExtra("latitude", 0.0)
            putExtra("longitude", 0.0)
            putExtra("address", "")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val savePendingIntent = PendingIntent.getActivity(
            applicationContext, 1, saveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val discardIntent = Intent(applicationContext, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_DISCARD
        }
        val discardPendingIntent = PendingIntent.getBroadcast(
            applicationContext, 2, discardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(applicationContext, CONFIRM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_add)
            .setContentTitle(title)
            .setContentText(text)
            .addAction(Notification.Action.Builder(null, "保存", savePendingIntent).build())
            .addAction(Notification.Action.Builder(null, "放弃", discardPendingIntent).build())
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(DUPLICATE_NOTIFICATION_ID, notification)
        log("已发送相同金额确认通知 → $text")
    }

    private fun createNotificationChannel() {
        val bgChannel = NotificationChannel(
            BACKGROUND_CHANNEL_ID,
            "随时记后台服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "用于后台识别支付页面"
        }
        val confirmChannel = NotificationChannel(
            CONFIRM_CHANNEL_ID,
            "交易确认通知",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "自动识别支付后确认通知"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
            enableLights(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(bgChannel)
        manager.createNotificationChannel(confirmChannel)
    }

    override fun onInterrupt() {}

    data class ConfirmData(
        val amountStr: String,
        val amount: Double,
        val merchant: String,
        val source: String,
        val type: String,
        val category: String,
        val date: Long,
        val latitude: Double?,
        val longitude: Double?,
        val address: String?
    )

    companion object {
        var instance: PaymentNotificationService? = null
            private set
        var pendingConfirmData: ConfirmData? = null
        var lastSavedId: Long = 0
        private const val BACKGROUND_CHANNEL_ID = "suishiji_background_service"
        const val CONFIRM_CHANNEL_ID = "suishiji_confirm_notification"
        const val NOTIFICATION_ID = 1001
        const val DUPLICATE_NOTIFICATION_ID = 1003
        const val REFUND_NOTIFICATION_ID = 1004
    }
}
