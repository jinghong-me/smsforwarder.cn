package com.lanbing.smsforwarder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object SmsNotificationHelper {
    const val CHANNEL_ID_SMS = "sms_incoming"
    private val notifIdCounter = java.util.concurrent.atomic.AtomicInteger(2000)

    fun createIncomingChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID_SMS,
                "新短信",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableLights(true)
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun notifyIncoming(context: Context, address: String, body: String, threadId: Long = -1L) {
        createIncomingChannel(context)
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            `package` = context.packageName
            if (threadId > 0) putExtra("thread_id", threadId)
        }
        val pi = PendingIntent.getActivity(context, threadId.toInt() and 0xFFFF, mainIntent, piFlags)

        val displayAddress = maskPhoneNumber(address)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SMS)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle("新短信: $displayAddress")
            .setContentText(body.take(100))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notifIdCounter.getAndIncrement(), notification)
        } catch (_: SecurityException) { }
    }

    private fun maskPhoneNumber(phone: String): String {
        val digits = phone.filter { it.isDigit() || it == '+' }
        return if (digits.length > 4) "****${digits.takeLast(4)}" else phone
    }
}
