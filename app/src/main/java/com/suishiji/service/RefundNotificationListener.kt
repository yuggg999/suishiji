package com.suishiji.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.suishiji.App
import com.suishiji.util.AppLocationManager
import com.suishiji.util.LocResult
import com.suishiji.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

class RefundNotificationListener : NotificationListenerService() {

    private val recentNotifTexts = mutableListOf<String>()

    private fun log(msg: String) {
        Log.d("Suishiji", msg)
        LogManager.log("Notify", msg)
    }

    private fun isDuplicate(fullContent: String): Boolean {
        if (fullContent in recentNotifTexts) return true
        recentNotifTexts.add(fullContent)
        if (recentNotifTexts.size > 20) {
            recentNotifTexts.removeAt(0)
        }
        return false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return

        // 只处理支付宝和微信通知
        if (pkg != "com.eg.android.AlipayGphone" && pkg != "com.tencent.mm") return

        // 获取通知完整内容
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val fullContent = "$title $text $bigText".trim()

        // 内容去重：同一通知文本只处理一次
        if (isDuplicate(fullContent)) return

        log("通知来源: $pkg · $title · $text")

        when {
            // 支付宝交易提醒：匹配"元的支出"（刷脸/指纹支付后页面无文字，靠这个识别）
            pkg == "com.eg.android.AlipayGphone" && fullContent.contains("元") && fullContent.contains("支出") -> {
                val amount = extractAmount(fullContent)
                log("检测到支付宝支付通知 → $fullContent")
                if (amount != null) {
                    processPaymentNotif(amount, "支付宝")
                }
            }
            // 支付宝退款：匹配"收到一笔XX元退款"
            pkg == "com.eg.android.AlipayGphone" && fullContent.contains("收到一笔") && fullContent.contains("元退款") -> {
                val amount = extractAmount(fullContent)
                log("检测到支付宝退款 → $fullContent")
                if (amount != null) {
                    processAlipayRefund(amount)
                }
            }
            // 微信退款：匹配"退款到账"、"退款成功"、"已退款"
            pkg == "com.tencent.mm" && (fullContent.contains("退款到账") || fullContent.contains("退款成功") || fullContent.contains("已退款")) -> {
                val amount = extractAmount(fullContent)
                log("检测到微信退款 → $fullContent")
                processWechatRefund(amount, fullContent)
            }
            // 微信支付通知：匹配"消费"或"支出"
            pkg == "com.tencent.mm" && (fullContent.contains("消费") || fullContent.contains("支出")) -> {
                val amount = extractAmount(fullContent)
                log("检测到微信支付通知 → $fullContent")
                if (amount != null) {
                    processPaymentNotif(amount, "微信")
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 通知被移除时不做处理
    }

    private fun extractAmount(text: String): String? {
        // 优先匹配带 ¥￥ 符号的金额
        val symbolRegex = Regex("""[¥￥]\s*(\d+\.?\d*)""")
        val symbolMatch = symbolRegex.find(text)
        if (symbolMatch != null) {
            val amount = symbolMatch.groupValues[1]
            if (amount.toDoubleOrNull() != null && amount.toDouble() > 0) return amount
        }

        // 其次匹配带 元 字的金额
        val yuanRegex = Regex("""(\d+\.?\d*)\s*元""")
        val yuanMatch = yuanRegex.find(text)
        if (yuanMatch != null) {
            val amount = yuanMatch.groupValues[1]
            if (amount.toDoubleOrNull() != null && amount.toDouble() > 0) return amount
        }

        return null
    }

    // ==================== 支付宝退款：3秒自动保存 ====================

    private fun processAlipayRefund(amountStr: String) {
        val amount = amountStr.toDoubleOrNull() ?: return
        log("开始处理支付宝退款 ${amountStr}元")

        // 先弹通知，3秒后自动保存
        showRefundManualNotification(amountStr, "支付宝")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val locJob = async {
                    suspendCancellableCoroutine<LocResult?> { cont ->
                        AppLocationManager.locateFast(applicationContext, timeoutMs = 3000L) { loc ->
                            cont.resume(loc) {}
                        }
                    }
                }

                delay(3000)

                val data = PaymentNotificationService.pendingConfirmData
                if (data == null) {
                    log("用户已操作，跳过自动保存")
                    locJob.cancel()
                    return@launch
                }

                val loc = try { locJob.await() } catch (_: Exception) { null }

                val db = (applicationContext as App).database
                val txDao = db.transactionDao()
                val matchedTx = txDao.findExpenseByAmount(amount, "支付宝")

                val note = if (matchedTx != null) {
                    "退款: 支付宝 · 原订单${matchedTx.category} ${matchedTx.amount}元"
                } else {
                    "退款: 支付宝"
                }

                val refund = com.suishiji.db.Transaction(
                    amount = amount,
                    type = "income",
                    category = "退款",
                    note = note,
                    date = System.currentTimeMillis(),
                    latitude = loc?.latitude,
                    longitude = loc?.longitude,
                    address = loc?.address
                )
                val refundId = txDao.insert(refund)
                PaymentNotificationService.pendingConfirmData = null
                PaymentNotificationService.lastSavedId = refundId
                log("支付宝退款已自动记录 ✓ ${amount}元")
            } catch (e: Exception) {
                log("支付宝退款处理失败 ✗ ${e.message}")
            }
        }
    }

    // ==================== 微信退款：弹通知跳转手动添加 ====================

    private fun processWechatRefund(amountStr: String?, fullText: String) {
        val amount = amountStr ?: ""
        log("微信退款 ${amount}元，弹通知 + 3秒自动保存")
        showRefundManualNotification(amount, "微信")

        if (amount.isEmpty()) return
        val amountD = amount.toDoubleOrNull() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val locJob = async {
                    suspendCancellableCoroutine<LocResult?> { cont ->
                        AppLocationManager.locateFast(applicationContext, timeoutMs = 3000L) { loc ->
                            cont.resume(loc) {}
                        }
                    }
                }

                delay(3000)
                val data = PaymentNotificationService.pendingConfirmData
                if (data == null) {
                    log("用户已操作，跳过自动保存")
                    locJob.cancel()
                    return@launch
                }

                val loc = try { locJob.await() } catch (_: Exception) { null }

                val db = (applicationContext as App).database
                val refund = com.suishiji.db.Transaction(
                    amount = amountD,
                    type = "income",
                    category = "退款",
                    note = "退款: 微信",
                    date = System.currentTimeMillis(),
                    latitude = loc?.latitude,
                    longitude = loc?.longitude,
                    address = loc?.address
                )
                val refundId = db.transactionDao().insert(refund)
                PaymentNotificationService.pendingConfirmData = null
                PaymentNotificationService.lastSavedId = refundId
                log("微信退款已自动记录 ✓ ${amount}元")
            } catch (e: Exception) {
                log("微信退款处理失败 ✗ ${e.message}")
            }
        }
    }

    // ==================== 支付通知：3秒自动保存（刷脸/指纹支付后页面无文字时使用） ====================

    private fun processPaymentNotif(amountStr: String, source: String) {
        val amount = amountStr.toDoubleOrNull() ?: return
        val now = System.currentTimeMillis()
        log("开始处理${source}支付通知 ${amountStr}元，3秒后自动保存")

        // 先弹通知（位置后面获取，不影响通知弹出速度）
        PaymentNotificationService.pendingConfirmData = PaymentNotificationService.ConfirmData(
            amountStr = amountStr,
            amount = amount,
            merchant = "",
            source = source,
            type = "expense",
            category = "",
            date = now,
            latitude = null,
            longitude = null,
            address = null
        )

        val intent = Intent(applicationContext, com.suishiji.MainActivity::class.java).apply {
            putExtra("from_notification", true)
            putExtra("amount", amountStr)
            putExtra("merchant", "")
            putExtra("source", source)
            putExtra("type", "expense")
            putExtra("category", "")
            putExtra("date", now)
            putExtra("latitude", 0.0)
            putExtra("longitude", 0.0)
            putExtra("address", "")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 7, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notifText = "支出 ${amountStr}元"
        val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = Notification.Builder(applicationContext, PaymentNotificationService.CONFIRM_CHANNEL_ID)
            .setSmallIcon(com.suishiji.R.drawable.ic_add)
            .setContentTitle("随时记 · 支付识别")
            .setContentText(notifText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

        manager.notify(PaymentNotificationService.NOTIFICATION_ID, notification)
        log("已发送确认通知（通知监听） → $notifText  ✓")

        // 异步获取位置 + 3秒后自动保存
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 并行：获取位置
                val locJob = async {
                    suspendCancellableCoroutine<LocResult?> { cont ->
                        AppLocationManager.locateFast(applicationContext, timeoutMs = 3000L) { loc ->
                            cont.resume(loc) {}
                        }
                    }
                }

                delay(3000)

                val data = PaymentNotificationService.pendingConfirmData
                if (data == null) {
                    log("用户已操作，跳过自动保存")
                    locJob.cancel()
                    return@launch
                }

                // 等位置（如果还没返回）
                val loc = try { locJob.await() } catch (_: Exception) { null }

                val db = (applicationContext as App).database
                val tx = com.suishiji.db.Transaction(
                    amount = data.amount,
                    type = data.type,
                    category = data.category,
                    note = data.source,
                    date = data.date,
                    latitude = loc?.latitude,
                    longitude = loc?.longitude,
                    address = loc?.address
                )
                val id = db.transactionDao().insert(tx)
                PaymentNotificationService.lastSavedId = id
                PaymentNotificationService.pendingConfirmData = null
                log("自动保存成功（通知监听） ✓ ${amountStr}元")
            } catch (e: Exception) {
                log("自动保存失败（通知监听） ✗ ${e.message}")
            }
        }
    }

    // ==================== 通知：退款已记录 ====================

    private fun showRefundSavedNotification(amountStr: String, source: String, refundId: Long) {
        val title = "退款已记录"
        val text = "$source 退款 $amountStr 元已自动记录"

        val intent = android.content.Intent(applicationContext, com.suishiji.MainActivity::class.java).apply {
            putExtra("from_notification", true)
            putExtra("is_refund", true)
            putExtra("edit_id", refundId)
            putExtra("amount", amountStr)
            putExtra("source", source)
            putExtra("type", "income")
            putExtra("category", "退款")
            putExtra("date", System.currentTimeMillis())
            putExtra("latitude", 0.0)
            putExtra("longitude", 0.0)
            putExtra("address", "")
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            applicationContext, 5, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = android.app.Notification.Builder(applicationContext, PaymentNotificationService.CONFIRM_CHANNEL_ID)
            .setSmallIcon(com.suishiji.R.drawable.ic_add)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(android.app.Notification.DEFAULT_ALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

        val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(PaymentNotificationService.REFUND_NOTIFICATION_ID, notification)
        log("已发送退款记录通知 → $text  ✓")
    }

    // ==================== 通知：请点击添加 ====================

    private fun showRefundManualNotification(amountStr: String, source: String) {
        val title = "退款通知"
        val text = if (amountStr.isNotEmpty()) "$source 退款 $amountStr 元，请点击添加" else "$source 退款，请点击添加"

        // 存储待处理数据
        PaymentNotificationService.pendingConfirmData = PaymentNotificationService.ConfirmData(
            amountStr = amountStr,
            amount = amountStr.toDoubleOrNull() ?: 0.0,
            merchant = "",
            source = source,
            type = "income",
            category = "退款",
            date = System.currentTimeMillis(),
            latitude = null,
            longitude = null,
            address = null
        )

        val intent = android.content.Intent(applicationContext, com.suishiji.MainActivity::class.java).apply {
            putExtra("from_notification", true)
            putExtra("is_refund", true)
            putExtra("amount", amountStr)
            putExtra("source", source)
            putExtra("type", "income")
            putExtra("category", "退款")
            putExtra("date", System.currentTimeMillis())
            putExtra("latitude", 0.0)
            putExtra("longitude", 0.0)
            putExtra("address", "")
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            applicationContext, 6, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = android.app.Notification.Builder(applicationContext, PaymentNotificationService.CONFIRM_CHANNEL_ID)
            .setSmallIcon(com.suishiji.R.drawable.ic_add)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(android.app.Notification.DEFAULT_ALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

        val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(PaymentNotificationService.REFUND_NOTIFICATION_ID, notification)
        log("已发送手动添加通知 → $text")
    }

    companion object {
        const val TAG = "RefundNotificationListener"
    }
}
