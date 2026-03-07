package com.lanbing.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * MMS receiver stub — required for default SMS app role.
 * Full MMS support is not implemented in this version (see README for extension points).
 */
class MmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // MMS not fully implemented in this version — stub satisfies default SMS app role requirement.
        Log.d(TAG, "MMS received (not implemented): action=${intent.action}")
    }
}
