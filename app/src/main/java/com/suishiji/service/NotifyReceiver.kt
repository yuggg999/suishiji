package com.suishiji.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.suishiji.App
import com.suishiji.MainActivity
import com.suishiji.R
import com.suishiji.db.Transaction
import com.suishiji.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotifyReceiver : BroadcastReceiver() {

    private fun log(msg: String) {
        Log.d("Suishiji", msg)
        LogManager.log("保存", msg)
    }

    override fun onReceive(context: Context, intent: Intent) {
        log("定时器触发，开始处理")

        val data = PaymentNotificationService.pendingConfirmData ?: run {
            log("无待确认数据，跳过")
            return
        }

        createNotificationChannel(context)
        showConfirmNotification(context, data)
        log("通知已发送")

        // 3秒后自动保存
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            autoSaveIfPending(context)
        }, 3000)
    }

    private fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "交易确认通知",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "自动识别支付后确认通知"
            enableVibration(true)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun showConfirmNotification(context: Context, data: PaymentNotificationService.ConfirmData) {
        val intent = Intent(context, MainActivity::class.java).apply {
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
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val typeStr = if (data.type == "income") "收入" else "支出"
        val title = "请确认识别内容"
        val text = "$typeStr ${data.amountStr}元 · ${data.category}"

        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_add)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(android.app.Notification.DEFAULT_ALL)
            .setPriority(android.app.Notification.PRIORITY_HIGH)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
        log("已发送确认通知 → $text")
    }

    private fun autoSaveIfPending(context: Context) {
        val data = PaymentNotificationService.consumePendingConfirmData() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = (context.applicationContext as App).database
                val txDao = db.transactionDao()
                val tx = Transaction(
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
            } catch (e: Exception) {
                log("自动保存失败 ✗ ${e.message}")
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "suishiji_confirm_notification"
        private const val NOTIFICATION_ID = 1002

        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, NotifyReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 100,
                pendingIntent
            )
            Log.d("Suishiji", "已设置定时器")
            LogManager.log("保存", "已设置定时器")
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, NotifyReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }
}
