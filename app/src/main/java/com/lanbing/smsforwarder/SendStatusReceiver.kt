package com.lanbing.smsforwarder

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SendStatusReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SendStatusReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(SmsSender.EXTRA_MESSAGE_ID, -1L)
        when (intent.action) {
            SmsSender.ACTION_SMS_SENT -> {
                val success = resultCode == Activity.RESULT_OK
                Log.d(TAG, "SMS sent: messageId=$messageId success=$success")
                if (!success) {
                    LogStore.append(context, "短信发送失败 (messageId=$messageId resultCode=$resultCode)")
                }
            }
            SmsSender.ACTION_SMS_DELIVERED -> {
                Log.d(TAG, "SMS delivered: messageId=$messageId")
            }
        }
    }
}
