/*
 * 短信转发助手
 *
 * 著作权人：华昊科技有限公司
 * 开发者：王士辉
 *
 * Copyright (c) 2026 华昊科技有限公司. All rights reserved.
 * 联系邮箱：huahao@email.cn
 */

package com.lanbing.smsforwarder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.app.PendingIntent
import android.provider.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SmsForegroundService : Service() {

    companion object {
        const val ACTION_UPDATE = "com.lanbing.smsforwarder.ACTION_LOG_UPDATED"
        const val ACTION_STOP = "com.lanbing.smsforwarder.ACTION_STOP_SERVICE"
        private const val TAG = "SmsForegroundService"
        private const val TAG_BATTERY = "BatteryReceiver"
        private var lastNotificationUpdateTime = 0L
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                val action = intent?.action
                if (action == ACTION_STOP) {
                    stopSelf()
                    LogStore.append(applicationContext, "收到通知停止服务请求，服务已停止")
                    return
                }
                updateNotification()
            } catch (t: Throwable) {
                Log.w(TAG, "updateNotification failed", t)
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent == null || context == null) return
                val action = intent.action
                if (action != Intent.ACTION_BATTERY_CHANGED) return

                val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                val batteryEnabled = prefs.getBoolean(Constants.PREF_BATTERY_REMINDER_ENABLED, false)
                if (!batteryEnabled) {
                    Log.d(TAG_BATTERY, "电量提醒未开启，已跳过")
                    return
                }

                val lowThreshold = prefs.getInt(Constants.PREF_LOW_BATTERY_THRESHOLD, Constants.DEFAULT_LOW_BATTERY_THRESHOLD)
                val highThreshold = prefs.getInt(Constants.PREF_HIGH_BATTERY_THRESHOLD, Constants.DEFAULT_HIGH_BATTERY_THRESHOLD)

                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level == -1 || scale == -1) {
                    Log.w(TAG_BATTERY, "无法获取电量信息")
                    return
                }

                val batteryPercent = (level * 100 / scale)
                val lastLowRemind = prefs.getInt(Constants.PREF_LAST_LOW_BATTERY_REMIND_LEVEL, -1)
                val lastHighRemind = prefs.getInt(Constants.PREF_LAST_HIGH_BATTERY_REMIND_LEVEL, -1)

                // 获取 SIM 卡手机号信息
                val phoneInfo = getSimPhoneInfo(context, prefs)

                // 低电量提醒：电量低于阈值，且上次提醒的电量高于当前阈值（避免重复提醒）
                if (batteryPercent <= lowThreshold) {
                    if (lastLowRemind == -1 || lastLowRemind > lowThreshold) {
                        var message = "【电量提醒】当前电量：$batteryPercent%，电量较低，请及时充电"
                        if (phoneInfo.isNotEmpty()) {
                            message += "\n设备：$phoneInfo"
                        }
                        sendBatteryReminder(context, message)
                        prefs.edit().putInt(Constants.PREF_LAST_LOW_BATTERY_REMIND_LEVEL, batteryPercent).apply()
                        LogStore.append(context, "电量提醒：低电量 $batteryPercent%")
                    }
                } else {
                    // 电量高于阈值时，重置低电量提醒记录
                    if (lastLowRemind != -1) {
                        prefs.edit().remove(Constants.PREF_LAST_LOW_BATTERY_REMIND_LEVEL).apply()
                    }
                }

                // 高电量提醒：电量高于阈值，且上次提醒的电量低于当前阈值（避免重复提醒）
                if (batteryPercent >= highThreshold) {
                    if (lastHighRemind == -1 || lastHighRemind < highThreshold) {
                        var message = "【电量提醒】当前电量：$batteryPercent%，电量充足"
                        if (phoneInfo.isNotEmpty()) {
                            message += "\n设备：$phoneInfo"
                        }
                        sendBatteryReminder(context, message)
                        prefs.edit().putInt(Constants.PREF_LAST_HIGH_BATTERY_REMIND_LEVEL, batteryPercent).apply()
                        LogStore.append(context, "电量提醒：高电量 $batteryPercent%")
                    }
                } else {
                    // 电量低于阈值时，重置高电量提醒记录
                    if (lastHighRemind != -1) {
                        prefs.edit().remove(Constants.PREF_LAST_HIGH_BATTERY_REMIND_LEVEL).apply()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG_BATTERY, "处理电量变化失败", t)
            }
        }
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun sendBatteryReminder(context: Context, message: String) {
        try {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val channels = loadChannels(prefs)
            if (channels.isEmpty()) {
                LogStore.append(context, "电量提醒：未配置通道，已跳过")
                return
            }

            channels.forEach { channel ->
                try {
                    val jsonObject = when (channel.type) {
                        ChannelType.WECHAT -> buildWechatMessage(message)
                        ChannelType.DINGTALK -> buildDingtalkMessage(message)
                        ChannelType.FEISHU -> buildFeishuMessage(message)
                        ChannelType.GENERIC_WEBHOOK -> buildWebhookMessage(message)
                    }
                    val body = jsonObject.toString().toRequestBody(JSON)
                    val request = Request.Builder()
                        .url(channel.target)
                        .post(body)
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            LogStore.append(context, "电量提醒发送成功 -> ${channel.name}")
                        } else {
                            LogStore.append(context, "电量提醒发送失败 -> ${channel.name}: ${response.code}")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG_BATTERY, "发送到 ${channel.name} 失败", t)
                    LogStore.append(context, "电量提醒发送失败 -> ${channel.name}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG_BATTERY, "发送电量提醒失败", t)
        }
    }

    private fun loadChannels(prefs: android.content.SharedPreferences): List<Channel> {
        val arrStr = prefs.getString(Constants.PREF_CHANNELS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(arrStr)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val typeStr = o.optString("type", "WECHAT")
                val type = try { ChannelType.valueOf(typeStr) } catch (t: Throwable) { ChannelType.WECHAT }
                Channel(o.getString("id"), o.getString("name"), type, o.getString("target"))
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun buildWechatMessage(message: String): JSONObject {
        val json = JSONObject()
        val text = JSONObject()
        text.put("content", message)
        json.put("msgtype", "text")
        json.put("text", text)
        return json
    }

    private fun buildDingtalkMessage(message: String): JSONObject {
        val json = JSONObject()
        val text = JSONObject()
        text.put("content", message)
        json.put("msgtype", "text")
        json.put("text", text)
        return json
    }

    private fun buildFeishuMessage(message: String): JSONObject {
        val json = JSONObject()
        val text = JSONObject()
        text.put("text", message)
        json.put("msg_type", "text")
        json.put("content", text)
        return json
    }

    private fun buildWebhookMessage(message: String): JSONObject {
        val json = JSONObject()
        json.put("message", message)
        return json
    }

    private fun getSimPhoneInfo(context: Context, prefs: android.content.SharedPreferences): String {
        val phoneNumbers = mutableListOf<String>()
        
        // 优先使用自定义的 SIM 卡号码
        val customSim1Phone = prefs.getString(Constants.PREF_CUSTOM_SIM1_PHONE, null)
        val customSim2Phone = prefs.getString(Constants.PREF_CUSTOM_SIM2_PHONE, null)
        
        if (!customSim1Phone.isNullOrBlank()) {
            phoneNumbers.add(customSim1Phone)
        }
        if (!customSim2Phone.isNullOrBlank()) {
            phoneNumbers.add(customSim2Phone)
        }
        
        // 如果有自定义号码，直接返回
        if (phoneNumbers.isNotEmpty()) {
            return phoneNumbers.joinToString(" / ")
        }
        
        // 尝试自动获取 SIM 卡号码
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return ""
        }
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subscriptionManager = SubscriptionManager.from(context)
                val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
                if (activeSubscriptions != null) {
                    activeSubscriptions.forEach { subInfo ->
                        try {
                            if (subInfo != null && !subInfo.number.isNullOrBlank()) {
                                phoneNumbers.add(subInfo.number)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG_BATTERY, "获取 SIM 卡号码失败", e)
                        }
                    }
                }
            }
            
            // 如果没有从 SubscriptionManager 获取到，尝试从 TelephonyManager 获取
            if (phoneNumbers.isEmpty()) {
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                if (telephonyManager != null) {
                    val number = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        telephonyManager.line1Number
                    } else {
                        @Suppress("DEPRECATION")
                        telephonyManager.line1Number
                    }
                    if (!number.isNullOrBlank()) {
                        phoneNumbers.add(number)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG_BATTERY, "获取 SIM 卡信息失败", e)
        }
        
        return if (phoneNumbers.isNotEmpty()) {
            phoneNumbers.joinToString(" / ")
        } else {
            ""
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_UPDATE)
                addAction(ACTION_STOP)
            }
            registerReceiver(updateReceiver, filter)
        } catch (t: Throwable) {
            Log.w(TAG, "registerReceiver failed", t)
        }
        try {
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            registerReceiver(batteryReceiver, batteryFilter)
            Log.d(TAG_BATTERY, "电量监听器已注册")
        } catch (t: Throwable) {
            Log.w(TAG_BATTERY, "注册电量监听器失败", t)
        }
    }

    private fun createChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm != null) {
                    val importance = NotificationManager.IMPORTANCE_HIGH
                    val channel = NotificationChannel(
                        Constants.NOTIFICATION_CHANNEL_ID,
                        Constants.NOTIFICATION_CHANNEL_NAME,
                        importance
                    )
                    channel.setShowBadge(false)
                    channel.lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                    nm.createNotificationChannel(channel)
                } else {
                    Log.w(TAG, "NotificationManager is null when creating channel")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "createChannel failed", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 检查通知权限
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Log.w(TAG, "Notification permission not granted, cannot start foreground service")
            LogStore.append(applicationContext, "错误：缺少通知权限，无法启动前台服务")
            stopSelf()
            return START_NOT_STICKY
        }

        val notification: Notification = try {
            buildNotification()
        } catch (t: Throwable) {
            Log.w(TAG, "buildNotification failed, use fallback", t)
            // fallback: 直接使用编译时资源，确保 smallIcon 不会回退到系统占位
            NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
                .setContentTitle("短信转发助手")
                .setContentText("服务正在运行")
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setOngoing(true)
                .build()
        }

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val type = getRemoteMessagingForegroundServiceType()
                if (type != 0) {
                    startForeground(Constants.NOTIFICATION_ID, notification, type)
                } else {
                    Log.w(TAG, "FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING not found via reflection, calling startForeground without type")
                    startForeground(Constants.NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(Constants.NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed, stopping service", t)
            LogStore.append(applicationContext, "ERROR: startForeground failed: ${t.javaClass.simpleName} ${t.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm?.getNotificationChannel(Constants.NOTIFICATION_CHANNEL_ID) else null
            val chInfo = if (channel != null) "channel(${channel.id}): importance=${channel.importance} name=${channel.name}" else "channel:null"
            val notifAllowed = NotificationManagerCompat.from(this).areNotificationsEnabled()
            LogStore.append(applicationContext, "DEBUG: notifAllowed=$notifAllowed ; $chInfo")
        } catch (t: Throwable) {
            LogStore.append(applicationContext, "DEBUG: 检查 channel 失败: ${t.message}")
        }

        try {
            val nm2 = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm2?.notify(Constants.NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "extra notify failed", t)
        }
        return START_STICKY
    }

    private fun getRemoteMessagingForegroundServiceType(): Int {
        return try {
            val cls = Class.forName("android.app.ServiceInfo")
            val field = cls.getField("FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING")
            (field.getInt(null))
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING via reflection: ${t.message}")
            0
        }
    }

    /**
     * 保留 resolveSmallIcon 作为极小概率回退（仍优先使用编译时资源），但通知构建处已直接使用 R.drawable.ic_stat_notification
     */
    private fun resolveSmallIcon(): Int {
        val statDrawable = resources.getIdentifier("ic_stat_notification", "drawable", packageName)
        if (statDrawable != 0) return statDrawable
        val statMipmap = resources.getIdentifier("ic_stat_notification", "mipmap", packageName)
        if (statMipmap != 0) return statMipmap

        val appIcon = applicationInfo.icon
        if (appIcon != 0) return appIcon
        return android.R.drawable.ic_dialog_info
    }

    private fun buildNotification(): Notification {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(Constants.PREF_ENABLED, false)
        val status = if (enabled) "已启用" else "已禁用"
        val latest = LogStore.latest(this)

        val builder = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("短信转发助手 - $status")
            .setContentText(latest)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)

        // 尝试设置彩色大图标（在展开的通知/设置中会显示），优先使用 mipmap/ic_launcher 或 drawable/ic_launcher
        try {
            val largeId = resources.getIdentifier("ic_launcher", "mipmap", packageName).takeIf { it != 0 }
                ?: resources.getIdentifier("ic_launcher", "drawable", packageName).takeIf { it != 0 }
            if (largeId != null && largeId != 0) {
                val bmp = BitmapFactory.decodeResource(resources, largeId)
                if (bmp != null) builder.setLargeIcon(bmp)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "setLargeIcon failed: ${t.message}")
        }

        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            `package` = packageName
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, mainIntent, piFlags)
        builder.setContentIntent(pendingIntent)

        val stopIntent = Intent(ACTION_STOP).apply { `package` = packageName }
        val stopPending = PendingIntent.getBroadcast(this, 1, stopIntent, piFlags)
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止服务", stopPending)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, Constants.NOTIFICATION_CHANNEL_ID)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                `package` = packageName
            }
            val pi = PendingIntent.getActivity(this, 2, intent, piFlags)
            builder.addAction(android.R.drawable.ic_menu_manage, "通知设置", pi)
        } else {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                `package` = packageName
            }
            val pi = PendingIntent.getActivity(this, 3, intent, piFlags)
            builder.addAction(android.R.drawable.ic_menu_manage, "应用设置", pi)
        }

        return builder.build()
    }

    private fun updateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateTime < Constants.NOTIFICATION_UPDATE_THROTTLE_MS) {
            Log.d(TAG, "Skipping notification update due to throttling")
            return
        }
        lastNotificationUpdateTime = now
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(Constants.NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "updateNotification failed", t)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(updateReceiver) } catch (e: Exception) { /* ignore */ }
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) { /* ignore */ }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}