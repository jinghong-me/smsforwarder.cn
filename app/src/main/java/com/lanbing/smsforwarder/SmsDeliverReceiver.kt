package com.lanbing.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives SMS when this app is set as the default SMS app (SMS_DELIVER action).
 * Responsibilities:
 * 1. Write message to the Telephony Provider inbox
 * 2. Show incoming notification
 * 3. Enqueue ForwardWorker (no network I/O here)
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsDeliverReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "getMessagesFromIntent failed", t)
            return
        }
        if (messages.isNullOrEmpty()) return

        val sb = StringBuilder()
        var sender = ""
        for (sms in messages) {
            if (sms.displayOriginatingAddress?.isNotBlank() == true) sender = sms.displayOriginatingAddress
            sb.append(sms.displayMessageBody ?: "")
        }
        val body = sb.toString().trim()
        val date = messages.first().timestampMillis

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val msgId = SmsRepository.writeInboxMessage(context, sender, body, date)
                SmsNotificationHelper.notifyIncoming(context, sender, body, -1L)
                ForwardWorker.enqueue(context, sender, body, date, msgId)
                LogStore.append(context, "收到短信并写入收件箱 (来自: ${maskPhone(sender)}, id=$msgId)")
            } catch (t: Throwable) {
                Log.e(TAG, "processing failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun maskPhone(phone: String): String {
        val digits = phone.filter { it.isDigit() || it == '+' }
        return if (digits.length > 4) "****${digits.takeLast(4)}" else phone
    }
}
