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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * BatteryReceiver: 监听电量变化，通过 webhook 发送电量提醒
 * - 低电量提醒（默认低于10%）
 * - 高电量提醒（默认高于90%）
 * - 避免重复频繁提醒
 */
class BatteryReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BatteryReceiver"
        private val client = OkHttpClient.Builder()
            .callTimeout(Constants.CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(Constants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(Constants.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BATTERY_CHANGED) return

        try {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean(Constants.PREF_BATTERY_REMINDER_ENABLED, false)
            if (!enabled) return

            val channels = loadChannels(prefs)
            if (channels.isEmpty()) {
                LogStore.append(context, "电量提醒：未配置通道，已跳过")
                return
            }

            val lowThreshold = prefs.getInt(Constants.PREF_LOW_BATTERY_THRESHOLD, Constants.DEFAULT_LOW_BATTERY_THRESHOLD)
            val highThreshold = prefs.getInt(Constants.PREF_HIGH_BATTERY_THRESHOLD, Constants.DEFAULT_HIGH_BATTERY_THRESHOLD)

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level == -1 || scale == -1) return

            val batteryPercent = (level * 100 / scale)
            val lastLowRemind = prefs.getInt(Constants.PREF_LAST_LOW_BATTERY_REMIND_LEVEL, -1)
            val lastHighRemind = prefs.getInt(Constants.PREF_LAST_HIGH_BATTERY_REMIND_LEVEL, -1)

            // 低电量提醒：电量低于阈值，且上次提醒的电量高于当前阈值（避免重复提醒）
            if (batteryPercent <= lowThreshold) {
                if (lastLowRemind == -1 || lastLowRemind > lowThreshold) {
                    val message = "【电量提醒】当前电量：$batteryPercent%，电量较低，请及时充电"
                    sendBatteryReminder(context, channels, message)
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
                    val message = "【电量提醒】当前电量：$batteryPercent%，电量充足"
                    sendBatteryReminder(context, channels, message)
                    prefs.edit().putInt(Constants.PREF_LAST_HIGH_BATTERY_REMIND_LEVEL, batteryPercent).apply()
                    LogStore.append(context, "电量提醒：高电量 $batteryPercent%")
                }
            } else {
                // 电量低于阈值时，重置高电量提醒记录
                if (lastHighRemind != -1) {
                    prefs.edit().remove(Constants.PREF_LAST_HIGH_BATTERY_REMIND_LEVEL).apply()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "电量提醒处理失败", e)
        }
    }

    private fun sendBatteryReminder(context: Context, channels: List<Channel>, message: String) {
        channels.forEach { channel ->
            Thread {
                try {
                    val success = sendToWebhook(channel.target, message, channel.type)
                    if (success) {
                        LogStore.append(context, "电量提醒发送成功 -> ${channel.name}")
                    } else {
                        LogStore.append(context, "电量提醒发送失败 -> ${channel.name}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "发送电量提醒失败 -> ${channel.name}", e)
                    LogStore.append(context, "电量提醒发送异常 -> ${channel.name}: ${e.message}")
                }
            }.start()
        }
    }

    private fun sendToWebhook(webhookUrl: String, message: String, type: ChannelType): Boolean {
        val json = when (type) {
            ChannelType.FEISHU -> buildFeishuMessage(message)
            ChannelType.WECHAT -> buildWechatMessage(message)
            ChannelType.DINGTALK -> buildDingtalkMessage(message)
            ChannelType.GENERIC_WEBHOOK -> buildGenericMessage(message)
        }

        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(webhookUrl)
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            return resp.isSuccessful
        }
    }

    private fun buildFeishuMessage(message: String): JSONObject {
        val json = JSONObject()
        val content = JSONObject()
        val text = JSONObject()
        text.put("text", message)
        content.put("text", text)
        json.put("msg_type", "text")
        json.put("content", content)
        return json
    }

    private fun buildWechatMessage(message: String): JSONObject {
        val json = JSONObject()
        json.put("msgtype", "text")
        val text = JSONObject()
        text.put("content", message)
        json.put("text", text)
        return json
    }

    private fun buildDingtalkMessage(message: String): JSONObject {
        val json = JSONObject()
        json.put("msgtype", "text")
        val text = JSONObject()
        text.put("content", message)
        json.put("text", text)
        return json
    }

    private fun buildGenericMessage(message: String): JSONObject {
        val json = JSONObject()
        json.put("content", message)
        return json
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
}
