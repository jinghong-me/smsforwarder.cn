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

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val startOnBoot = prefs.getBoolean(Constants.PREF_START_ON_BOOT, false)
            val enabled = prefs.getBoolean(Constants.PREF_ENABLED, false)
            
            if (startOnBoot && enabled) {
                // 检查通知权限
                if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                    Log.w(TAG, "Notification permission not granted, cannot start service on boot")
                    LogStore.append(context, "开机启动失败：缺少通知权限")
                    return
                }

                // 检查短信权限
                val smsPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }

                if (!smsPermission) {
                    Log.w(TAG, "SMS permission not granted, cannot start service on boot")
                    LogStore.append(context, "开机启动失败：缺少短信权限")
                    return
                }

                try {
                    val svcIntent = Intent(context, SmsForegroundService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, svcIntent)
                    } else {
                        context.startService(svcIntent)
                    }
                    LogStore.append(context, "设备开机：根据设置已启动前台服务")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to start service on boot", t)
                    LogStore.append(context, "开机启动服务失败: ${t.javaClass.simpleName}")
                }
            } else {
                Log.d(TAG, "开机未启动服务: startOnBoot=$startOnBoot enabled=$enabled")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onReceive failed", t)
        }
    }
}