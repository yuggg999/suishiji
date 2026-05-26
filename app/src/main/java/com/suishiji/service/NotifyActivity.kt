package com.suishiji.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.suishiji.MainActivity
import com.suishiji.R
import com.suishiji.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotifyActivity : AppCompatActivity() {

    private fun log(msg: String) {
        Log.d("Suishiji", msg)
        LogManager.log("保存", msg)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = PaymentNotificationService.pendingConfirmData
        if (data == null) {
            finish()
            return
        }

        createNotificationChannel()
        showConfirmNotification(data)
        log("Activity 通知已发送")

        // 3秒后自动保存（如果没有被用户点击通知打开）
        android.os.Handler(mainLooper).postDelayed({
            autoSaveIfPending()
        }, 3000)

        finish()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "交易确认通知",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "自动识别支付后确认通知"
            enableVibration(true)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun showConfirmNotification(data: PaymentNotificationService.ConfirmData) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("from_notification", true)
            putExtra("amount", data.amountStr)
            putExtra("merchant", data.merchant)
            putExtra("source", data.source)
            putExtra("type", data.type)
            putExtra("category", data.category)
            putExtra("date", data.date)
            putExtra("latitude", data.latitude ?: 0.0)
            putExtra("longitude", data.longitude ?: 0.0)
            putExtra("address", data.address ?: "")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val typeStr = if (data.type == "income") "收入" else "支出"
        val title = "请确认识别内容"
        val text = "$typeStr ${data.amountStr}元 · ${data.category}"

        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_add)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(android.app.Notification.DEFAULT_ALL)
            .setPriority(android.app.Notification.PRIORITY_HIGH)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
        log("已发送确认通知 → $text")
    }

    private fun autoSaveIfPending() {
        val data = PaymentNotificationService.pendingConfirmData ?: return
        // 用户没有点击通知，3秒后自动保存
        CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val db = (applicationContext as com.suishiji.App).database
                val txDao = db.transactionDao()
                val tx = com.suishiji.db.Transaction(
                    amount = data.amount,
                    type = data.type,
                    category = data.category,
                    note = "${data.source}: ${data.merchant}",
                    date = data.date,
                    latitude = data.latitude,
                    longitude = data.longitude,
                    address = data.address
                )
                txDao.insert(tx)
                log("自动保存成功 ✓ ${data.amountStr}元")
                PaymentNotificationService.pendingConfirmData = null
            } catch (e: Exception) {
                log("自动保存失败 ✗ ${e.message}")
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "suishiji_confirm_notification"
        private const val NOTIFICATION_ID = 1002
    }
}
