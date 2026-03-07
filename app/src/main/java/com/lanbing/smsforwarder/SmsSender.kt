package com.lanbing.smsforwarder

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

object SmsSender {
    private const val TAG = "SmsSender"
    const val ACTION_SMS_SENT = "com.lanbing.smsforwarder.SMS_SENT"
    const val ACTION_SMS_DELIVERED = "com.lanbing.smsforwarder.SMS_DELIVERED"
    const val EXTRA_MESSAGE_ID = "message_id"

    // Use an incrementing counter for unique PendingIntent request codes, avoiding 16-bit truncation collisions
    private val requestCodeCounter = AtomicInteger(0)

    fun send(context: Context, toNumber: String, body: String, subscriptionId: Int? = null, messageId: Long = -1L) {
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val sentIntent = Intent(ACTION_SMS_SENT).apply {
            `package` = context.packageName
            putExtra(EXTRA_MESSAGE_ID, messageId)
        }
        val deliveredIntent = Intent(ACTION_SMS_DELIVERED).apply {
            `package` = context.packageName
            putExtra(EXTRA_MESSAGE_ID, messageId)
        }
        val sentCode = requestCodeCounter.getAndIncrement()
        val deliveredCode = requestCodeCounter.getAndIncrement()
        val sentPi = PendingIntent.getBroadcast(context, sentCode, sentIntent, piFlags)
        val deliveredPi = PendingIntent.getBroadcast(context, deliveredCode, deliveredIntent, piFlags)

        try {
            val manager = if (subscriptionId != null) {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getDefault()
            }
            val parts = manager.divideMessage(body)
            if (parts.size <= 1) {
                manager.sendTextMessage(toNumber, null, body, sentPi, deliveredPi)
            } else {
                // Each part needs a unique PendingIntent to properly track per-part delivery status
                val sentPis = ArrayList<PendingIntent>(parts.size)
                val deliveredPis = ArrayList<PendingIntent>(parts.size)
                repeat(parts.size) {
                    val partSentCode = requestCodeCounter.getAndIncrement()
                    val partDeliveredCode = requestCodeCounter.getAndIncrement()
                    sentPis.add(PendingIntent.getBroadcast(context, partSentCode, sentIntent, piFlags))
                    deliveredPis.add(PendingIntent.getBroadcast(context, partDeliveredCode, deliveredIntent, piFlags))
                }
                manager.sendMultipartTextMessage(toNumber, null, parts, sentPis, deliveredPis)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "send failed to $toNumber", t)
            throw t
        }
    }
}
