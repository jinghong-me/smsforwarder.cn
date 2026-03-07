package com.lanbing.smsforwarder

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * RespondViaSmsActivity — required for default SMS app role (RESPOND_VIA_MESSAGE intent).
 * This stub satisfies the role requirement. In practice, users should use the main UI.
 */
class RespondViaSmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("RespondViaSmsActivity", "respond via SMS intent received")
        finish()
    }
}
