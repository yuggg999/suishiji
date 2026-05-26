package com.suishiji.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.suishiji.MainActivity
import com.suishiji.util.LogManager

class NotificationActionReceiver : BroadcastReceiver() {

    private fun log(msg: String) {
        Log.d("Suishiji", "ActionReceiver: $msg")
        LogManager.log("操作", msg)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        log("收到操作 → $action")

        val data = PaymentNotificationService.pendingConfirmData

        when (action) {
            ACTION_SAVE -> {
                if (data != null) {
                    log("用户点击保存 → 跳转 ${data.amountStr}元")
                    val mainIntent = Intent(context, MainActivity::class.java).apply {
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
                    context.startActivity(mainIntent)
                }
            }
            ACTION_DISCARD, ACTION_DISCARD_REFUND -> {
                log("用户放弃保存 → ${data?.amountStr}元")
            }
        }

        PaymentNotificationService.pendingConfirmData = null
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(PaymentNotificationService.DUPLICATE_NOTIFICATION_ID)
        manager.cancel(PaymentNotificationService.REFUND_NOTIFICATION_ID)
    }

    companion object {
        const val ACTION_SAVE = "com.suishiji.ACTION_SAVE_DUPLICATE"
        const val ACTION_DISCARD = "com.suishiji.ACTION_DISCARD_DUPLICATE"
        const val ACTION_DISCARD_REFUND = "com.suishiji.ACTION_DISCARD_REFUND"
    }
}
