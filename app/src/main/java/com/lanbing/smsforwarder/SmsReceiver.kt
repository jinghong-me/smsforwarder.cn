package com.lanbing.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SmsReceiver:
 * - 读取 SharedPreferences 中的 channels / keyword_configs
 * - 对所有规则逐条匹配（空 keyword 表示匹配全部）
 * - 对每条匹配项并行发送（允许同一条短信被多次发送到相同/不同通道）
 * - webhook 类型使用 HTTP POST；SMS 类型使用 SmsManager.sendMultipartTextMessage 以保证长短信能正确拼接发送
 */

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        val client: OkHttpClient = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        // cached thread pool 支持并行发送
        private val executor = Executors.newCachedThreadPool()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val prefs = context.getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("enabled", false)
        if (!isEnabled) return

        val channels = loadChannels(prefs)
        val configs = loadConfigs(prefs)

        if (channels.isEmpty() || configs.isEmpty()) {
            LogStore.append(context, "未配置通道或关键词规则，已跳过转发")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val sb = StringBuilder()
        var sender = ""
        for (sms in messages) {
            sender = sms.displayOriginatingAddress ?: sender
            sb.append(sms.displayMessageBody)
        }
        // 归一化消息内容：去掉 CR，折叠连续空行，去首尾空白
        val fullMessage = normalizeContent(sb.toString())

        // 收集所有匹配项（允许重复）
        val matched = mutableListOf<Pair<Channel, KeywordConfig>>()
        configs.forEach { cfg ->
            val kw = cfg.keyword.trim()
            val match = if (kw.isEmpty()) true else fullMessage.contains(kw, ignoreCase = true)
            if (match) {
                val ch = channels.find { it.id == cfg.channelId }
                if (ch != null) matched.add(Pair(ch, cfg))
            }
        }

        if (matched.isEmpty()) return

        val pendingResult = goAsync()

        // 并行发送
        executor.execute {
            val latch = CountDownLatch(matched.size)
            try {
                matched.forEach { (ch, cfg) ->
                    executor.execute {
                        try {
                            when (ch.type) {
                                ChannelType.SMS -> {
                                    try {
                                        // 使用归一化后的内容发送 SMS
                                        sendSms(context, ch.target, fullMessage, ch.simSubscriptionId)
                                        LogStore.append(context, "短信转发成功 → ${ch.target} (规则: ${cfg.keyword})")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "sendSms failed to ${ch.target}", e)
                                        LogStore.append(context, "短信转发失败 → ${ch.target} (规则: ${cfg.keyword})")
                                    }
                                }
                                else -> {
                                    if (!isValidUrl(ch.target)) {
                                        LogStore.append(context, "通道 ${ch.name} webhook 格式无效: ${ch.target}")
                                    } else {
                                        var attempt = 0
                                        var success = false
                                        val maxAttempts = 2
                                        var backoff = 0L
                                        while (attempt < maxAttempts && !success) {
                                            if (backoff > 0) {
                                                try { Thread.sleep(backoff) } catch (_: InterruptedException) { }
                                            }
                                            try {
                                                // 传给 webhook 的内容使用归一化后的 fullMessage，并用单个换行分隔发送者与正文（避免产生空白行）
                                                success = sendToWebhook(ch.target, sender, fullMessage, ch.type)
                                            } catch (e: Exception) {
                                                Log.e(TAG, "send attempt ${attempt+1} failed to ${ch.target}", e)
                                            }
                                            attempt++
                                            if (!success) backoff = 1000L * attempt
                                        }
                                        if (success) {
                                            LogStore.append(context, "转发成功 — 来自: $sender -> ${ch.name} (规则: ${cfg.keyword})")
                                        } else {
                                            LogStore.append(context, "转发失败 — 来自: $sender -> ${ch.name} (规则: ${cfg.keyword})")
                                        }
                                    }
                                }
                            }
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                val completed = try {
                    latch.await(30, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    Log.w(TAG, "await interrupted", e)
                    false
                }
                if (!completed) {
                    LogStore.append(context, "部分转发任务超时（等待 30s 后返回）")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "unexpected error in parallel send worker", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    // 归一化：删除 CR，折叠连续空行为单个换行，trim 首尾空白。
    private fun normalizeContent(s: String): String {
        return s.replace("\r", "")
            .replace(Regex("\n{2,}"), "\n")
            .trim()
    }

    private fun sendToWebhook(webhookUrl: String, sender: String, content: String, type: ChannelType): Boolean {
        val json = JSONObject()
        json.put("msgtype", "text")
        val text = JSONObject()
        // 使用单个换行连接发送者与正文，并对正文再做一次归一化以防外部传入未处理的情况
        val normalized = normalizeContent(content)
        text.put("content", "来自: $sender\n${normalized}")
        json.put("text", text)

        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(webhookUrl)
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            return resp.isSuccessful
        }
    }

    private fun sendSms(context: Context, toNumber: String, content: String, simSubscriptionId: Int?) {
        // Use SmsManager.sendMultipartTextMessage for multipart messages to preserve concatenation and encoding.
        if (simSubscriptionId != null) {
            try {
                val smsManager = SmsManager.getSmsManagerForSubscriptionId(simSubscriptionId)
                val parts = smsManager.divideMessage(content)
                if (parts.size <= 1) {
                    // single part
                    smsManager.sendTextMessage(toNumber, null, content, null, null)
                } else {
                    // multipart send ensures correct concatenation on recipient side
                    smsManager.sendMultipartTextMessage(toNumber, null, parts, null, null)
                }
                return
            } catch (t: Throwable) {
                Log.w(TAG, "send via specified subId=$simSubscriptionId failed, falling back to default SmsManager", t)
                // fallthrough to default
            }
        }

        // Fallback to default SmsManager
        val defaultSms = SmsManager.getDefault()
        val partsDefault = defaultSms.divideMessage(content)
        if (partsDefault.size <= 1) {
            defaultSms.sendTextMessage(toNumber, null, content, null, null)
        } else {
            defaultSms.sendMultipartTextMessage(toNumber, null, partsDefault, null, null)
        }
    }

    private fun isValidUrl(s: String): Boolean {
        return try {
            val url = URL(s)
            (url.protocol == "http" || url.protocol == "https") && url.host.isNotBlank()
        } catch (e: MalformedURLException) {
            false
        }
    }

    private fun loadChannels(prefs: android.content.SharedPreferences): List<Channel> {
        val arrStr = prefs.getString("channels", "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(arrStr)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val typeStr = o.optString("type", "WECHAT")
                val type = try { ChannelType.valueOf(typeStr) } catch (t: Throwable) { ChannelType.WECHAT }
                val simId = if (o.has("simId") && !o.isNull("simId")) o.optInt("simId", -1).let { if (it == -1) null else it } else null
                Channel(o.getString("id"), o.getString("name"), type, o.getString("target"), simId)
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun loadConfigs(prefs: android.content.SharedPreferences): List<KeywordConfig> {
        val arrStr = prefs.getString("keyword_configs", "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(arrStr)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                KeywordConfig(o.getString("id"), o.getString("keyword"), o.getString("channelId"))
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }
}