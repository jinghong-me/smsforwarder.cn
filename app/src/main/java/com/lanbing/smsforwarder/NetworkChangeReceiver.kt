/*
 * 短信转发助手
 * 版本：V2.7.2
 *
 * 著作权人：华昊科技有限公司
 * 开发者：王士辉
 *
 * Copyright (c) 2026 华昊科技有限公司. All rights reserved.
 * 联系邮箱：huahao@email.cn
 */

package com.lanbing.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log

/**
 * NetworkChangeReceiver: 监听网络状态变化，网络恢复时触发失败消息重试
 */
class NetworkChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NetworkChangeReceiver"
        private var lastRetryTime = 0L
        private var lastNetworkState = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) return

        val isAvailable = isNetworkAvailable(context)
        val now = System.currentTimeMillis()

        // 防抖处理：只在网络从不可用变为可用，且距离上次重试超过 NETWORK_DEBOUNCE_MS 时才重试
        if (isAvailable && !lastNetworkState && (now - lastRetryTime > Constants.NETWORK_DEBOUNCE_MS)) {
            Log.d(TAG, "网络已恢复，触发失败消息重试")
            lastRetryTime = now
            SmsReceiver.retryFailedMessages(context)
        }

        lastNetworkState = isAvailable
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo?.isConnected == true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error checking network availability", t)
            false
        }
    }
}
