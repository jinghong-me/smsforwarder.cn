package com.lanbing.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SmsReceiver:
 * - 读取 SharedPreferences 中的 channels / keyword_configs
 * - 对所有规则逐条匹配（空 keyword 表示匹配全部）
 * - 对每条匹配项并行发送（允许同一条短信被多次发送到相同/不同通道）
 * - 支持 webhook 类型：企业微信、钉钉、飞书、通用 Webhook
 * - 添加消息去重机制和失败重试队列
 */

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private const val DUPLICATE_WINDOW_MS = 5000L // 5秒内相同内容视为重复

        val client: OkHttpClient = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        // 固定线程池避免线程爆炸
        private val executor = Executors.newFixedThreadPool(4)

        // 消息去重缓存：key = sender+content_hash, value = timestamp
        private val recentMessages = ConcurrentHashMap<String, Long>()

        // 失败消息队列，等待网络恢复时重试
        private val failedMessages = mutableListOf<FailedMessage>()
        private val failedMessageLock = Object()

        data class FailedMessage(
            val channel: Channel,
            val sender: String,
            val content: String,
            val timestamp: Long,
            val retryCount: Int = 0
        )
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
        val fullMessage = normalizeContent(sb.toString())

        // 消息去重检查
        val messageKey = "${sender}_${fullMessage.hashCode()}"
        val now = System.currentTimeMillis()
        synchronized(recentMessages) {
            val lastTime = recentMessages[messageKey]
            if (lastTime != null && (now - lastTime) < DUPLICATE_WINDOW_MS) {
                Log.d(TAG, "跳过重复消息: sender=$sender")
                return
            }
            recentMessages[messageKey] = now
            // 清理过期条目
            recentMessages.entries.removeIf { (now - it.value) > DUPLICATE_WINDOW_MS * 2 }
        }

        // 收集所有匹配项
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
            val latch = java.util.concurrent.CountDownLatch(matched.size)
            try {
                matched.forEach { (ch, cfg) ->
                    executor.execute {
                        try {
                            if (!isValidUrl(ch.target)) {
                                LogStore.append(context, "通道 ${ch.name} webhook 格式无效: ${ch.target}")
                            } else {
                                var attempt = 0
                                var success = false
                                val maxAttempts = 3
                                var backoff = 0L
                                while (attempt < maxAttempts && !success) {
                                    if (backoff > 0) {
                                        try { Thread.sleep(backoff) } catch (_: InterruptedException) { }
                                    }
                                    try {
                                        success = sendToWebhook(ch.target, sender, fullMessage, ch.type)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "send attempt ${attempt+1} failed to ${ch.target}", e)
                                    }
                                    attempt++
                                    if (!success) backoff = 2000L * attempt
                                }
                                if (success) {
                                    LogStore.append(context, "转发成功 — 来自: $sender -> ${ch.name} (规则: ${cfg.keyword})")
                                } else {
                                    LogStore.append(context, "转发失败 — 来自: $sender -> ${ch.name} (规则: ${cfg.keyword})")
                                    // 添加到失败队列等待网络恢复时重试
                                    synchronized(failedMessageLock) {
                                        if (failedMessages.size < 100) { // 限制队列大小
                                            failedMessages.add(FailedMessage(ch, sender, fullMessage, now))
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
                    latch.await(45, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    Log.w(TAG, "await interrupted", e)
                    false
                }
                if (!completed) {
                    LogStore.append(context, "部分转发任务超时（等待 45s 后返回）")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "unexpected error in parallel send worker", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    // 归一化：删除 CR，折叠连续空行为单个换行，trim 首尾空白
    private fun normalizeContent(s: String): String {
        return s.replace("\r", "")
            .replace(Regex("\n{2,}"), "\n")
            .trim()
    }

    private fun sendToWebhook(webhookUrl: String, sender: String, content: String, type: ChannelType): Boolean {
        val json = when (type) {
            ChannelType.FEISHU -> buildFeishuMessage(sender, content)
            ChannelType.WECHAT -> buildWechatMessage(sender, content)
            ChannelType.DINGTALK -> buildDingtalkMessage(sender, content)
            ChannelType.GENERIC_WEBHOOK -> buildGenericMessage(sender, content)
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

    private fun buildWechatMessage(sender: String, content: String): JSONObject {
        val json = JSONObject()
        json.put("msgtype", "text")
        val text = JSONObject()
        text.put("content", "来自: $sender\n$content")
        json.put("text", text)
        return json
    }

    private fun buildDingtalkMessage(sender: String, content: String): JSONObject {
        val json = JSONObject()
        json.put("msgtype", "text")
        val text = JSONObject()
        text.put("content", "【短信转发】\n来自: $sender\n$content")
        json.put("text", text)
        return json
    }

    private fun buildFeishuMessage(sender: String, content: String): JSONObject {
        val json = JSONObject()
        json.put("msg_type", "text")
        val text = JSONObject()
        text.put("text", "【短信转发】\n来自: $sender\n$content")
        json.put("content", text)
        return json
    }

    private fun buildGenericMessage(sender: String, content: String): JSONObject {
        val json = JSONObject()
        json.put("sender", sender)
        json.put("content", content)
        json.put("timestamp", System.currentTimeMillis())
        return json
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
                Channel(o.getString("id"), o.getString("name"), type, o.getString("target"))
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

    // 供 NetworkChangeReceiver 调用，重试失败的消息
    fun retryFailedMessages(context: Context) {
        synchronized(failedMessageLock) {
            if (failedMessages.isEmpty()) return

            val toRetry = failedMessages.filter { it.retryCount < 3 }
            failedMessages.clear()

            toRetry.forEach { failed ->
                executor.execute {
                    try {
                        val success = sendToWebhook(failed.channel.target, failed.sender, failed.content, failed.channel.type)
                        if (success) {
                            LogStore.append(context, "重试转发成功 -> ${failed.channel.name}")
                        } else {
                            if (failed.retryCount + 1 < 3) {
                                failedMessages.add(failed.copy(retryCount = failed.retryCount + 1))
                            } else {
                                LogStore.append(context, "重试转发失败（已达最大次数）-> ${failed.channel.name}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "retry failed", e)
                    }
                }
            }
        }
    }
}
