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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
 * - 失败消息持久化到文件
 */

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"

        val client: OkHttpClient = OkHttpClient.Builder()
            .callTimeout(Constants.CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(Constants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(Constants.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        // 固定线程池避免线程爆炸
        private val executor = Executors.newFixedThreadPool(Constants.THREAD_POOL_SIZE)

        // 消息去重缓存：key = sender+content_hash, value = timestamp
        private val recentMessages = ConcurrentHashMap<String, Long>()
        private var lastCleanupTime = 0L
        private const val CLEANUP_INTERVAL_MS = 60000L // 1分钟清理一次

        // 失败消息队列，等待网络恢复时重试
        private val failedMessages = mutableListOf<FailedMessage>()
        private val failedMessageLock = Object()

        data class FailedMessage(
            val channelId: String,
            val channelName: String,
            val channelType: String,
            val channelTarget: String,
            val sender: String,
            val content: String,
            val timestamp: Long,
            val retryCount: Int = 0
        ) {
            fun toJSONObject(): JSONObject {
                val obj = JSONObject()
                obj.put("channelId", channelId)
                obj.put("channelName", channelName)
                obj.put("channelType", channelType)
                obj.put("channelTarget", channelTarget)
                obj.put("sender", sender)
                obj.put("content", content)
                obj.put("timestamp", timestamp)
                obj.put("retryCount", retryCount)
                return obj
            }

            companion object {
                fun fromJSONObject(obj: JSONObject): FailedMessage {
                    return FailedMessage(
                        channelId = obj.getString("channelId"),
                        channelName = obj.getString("channelName"),
                        channelType = obj.getString("channelType"),
                        channelTarget = obj.getString("channelTarget"),
                        sender = obj.getString("sender"),
                        content = obj.getString("content"),
                        timestamp = obj.getLong("timestamp"),
                        retryCount = obj.getInt("retryCount")
                    )
                }

                fun fromChannel(channel: Channel, sender: String, content: String, timestamp: Long, retryCount: Int = 0): FailedMessage {
                    return FailedMessage(
                        channelId = channel.id,
                        channelName = channel.name,
                        channelType = channel.type.name,
                        channelTarget = channel.target,
                        sender = sender,
                        content = content,
                        timestamp = timestamp,
                        retryCount = retryCount
                    )
                }
            }

            fun toChannel(): Channel {
                val type = try { ChannelType.valueOf(channelType) } catch (t: Throwable) { ChannelType.WECHAT }
                return Channel(channelId, channelName, type, channelTarget)
            }
        }

        private fun failedMessagesFile(context: Context): File {
            return File(context.filesDir, Constants.FAILED_MESSAGES_FILE)
        }

        private fun saveFailedMessages(context: Context) {
            synchronized(failedMessageLock) {
                try {
                    val file = failedMessagesFile(context)
                    val arr = JSONArray()
                    failedMessages.take(Constants.MAX_FAILED_MESSAGES).forEach { arr.put(it.toJSONObject()) }
                    file.writeText(arr.toString())
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to save failed messages", t)
                }
            }
        }

        private fun loadFailedMessages(context: Context) {
            synchronized(failedMessageLock) {
                try {
                    val file = failedMessagesFile(context)
                    if (!file.exists()) return
                    val arr = JSONArray(file.readText())
                    failedMessages.clear()
                    for (i in 0 until arr.length()) {
                        failedMessages.add(FailedMessage.fromJSONObject(arr.getJSONObject(i)))
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to load failed messages", t)
                }
            }
        }

        private fun cleanupRecentMessages() {
            val now = System.currentTimeMillis()
            if (now - lastCleanupTime < CLEANUP_INTERVAL_MS) return
            lastCleanupTime = now
            recentMessages.entries.removeIf { (now - it.value) > Constants.DUPLICATE_WINDOW_MS * 2 }
        }

        // 供 NetworkChangeReceiver 调用，重试失败的消息
        @JvmStatic
        fun retryFailedMessages(context: Context) {
            loadFailedMessages(context)
            synchronized(failedMessageLock) {
                if (failedMessages.isEmpty()) return

                val toRetry = failedMessages.filter { it.retryCount < Constants.MAX_RETRY_ATTEMPTS }
                failedMessages.clear()

                toRetry.forEach { failed ->
                    executor.execute {
                        try {
                            val receiver = SmsReceiver()
                            val channel = failed.toChannel()
                            val success = receiver.sendToWebhook(failed.channelTarget, failed.sender, failed.content, channel.type)
                            if (success) {
                                LogStore.append(context, "重试转发成功 -> ${failed.channelName}")
                            } else {
                                if (failed.retryCount + 1 < Constants.MAX_RETRY_ATTEMPTS) {
                                    failedMessages.add(failed.copy(retryCount = failed.retryCount + 1))
                                } else {
                                    LogStore.append(context, "重试转发失败（已达最大次数）-> ${failed.channelName}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "retry failed", e)
                        }
                    }
                }
                saveFailedMessages(context)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(Constants.PREF_ENABLED, false)
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
            cleanupRecentMessages()
            val lastTime = recentMessages[messageKey]
            if (lastTime != null && (now - lastTime) < Constants.DUPLICATE_WINDOW_MS) {
                Log.d(TAG, "跳过重复消息: sender=$sender")
                return
            }
            recentMessages[messageKey] = now
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

        // 加载持久化的失败消息
        loadFailedMessages(context)

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
                                var backoff = 0L
                                while (attempt < Constants.MAX_RETRY_ATTEMPTS && !success) {
                                    if (backoff > 0) {
                                        try { Thread.sleep(backoff) } catch (_: InterruptedException) { }
                                    }
                                    try {
                                        success = sendToWebhook(ch.target, sender, fullMessage, ch.type)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "send attempt ${attempt+1} failed to ${ch.target}", e)
                                    }
                                    attempt++
                                    if (!success) backoff = Constants.INITIAL_RETRY_BACKOFF_MS * attempt
                                }
                                if (success) {
                                    LogStore.append(context, "转发成功 — 来自: $sender -> ${ch.name} (规则: ${cfg.keyword})")
                                } else {
                                    LogStore.append(context, "转发失败 — 来自: $sender -> ${ch.name} (规则: ${cfg.keyword})")
                                    // 添加到失败队列等待网络恢复时重试
                                    synchronized(failedMessageLock) {
                                        if (failedMessages.size < Constants.MAX_FAILED_MESSAGES) {
                                            failedMessages.add(FailedMessage.fromChannel(ch, sender, fullMessage, now))
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
                    latch.await(Constants.BROADCAST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    Log.w(TAG, "await interrupted", e)
                    false
                }
                if (!completed) {
                    LogStore.append(context, "部分转发任务超时（等待 ${Constants.BROADCAST_TIMEOUT_SECONDS}s 后返回）")
                }

                // 保存失败消息
                saveFailedMessages(context)
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

    internal fun sendToWebhook(webhookUrl: String, sender: String, content: String, type: ChannelType): Boolean {
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

    /**
     * 从短信内容中提取验证码
     * 匹配常见验证码格式：4-8位数字，可能带有"验证码"、"校验码"等关键词
     */
    private fun extractVerificationCode(content: String): String? {
        // 常见的验证码关键词
        val keywords = listOf("验证码", "校验码", "动态码", "验证 code", "verification code", "verify code")
        val hasKeyword = keywords.any { content.contains(it, ignoreCase = true) }

        // 优先匹配：关键词后紧跟的 4-8 位数字
        if (hasKeyword) {
            // 模式1：关键词后面直接跟数字（如"验证码是123456"）
            val pattern1 = Regex("""(?:验证码|校验码|动态码|验证|verification|verify)[^\d]*(\d{4,8})""", RegexOption.IGNORE_CASE)
            pattern1.find(content)?.let { return it.groupValues[1] }

            // 模式2：关键词附近的数字（关键词后20字符内）
            val pattern2 = Regex("""(?:验证码|校验码|动态码|验证|verification|verify).{0,30}?(\d{4,8})""", RegexOption.IGNORE_CASE)
            pattern2.find(content)?.let { return it.groupValues[1] }
        }

        // 匹配独立的4-8位数字（作为备选）
        val pattern3 = Regex("""\b(\d{4,8})\b""")
        val matches = pattern3.findAll(content).map { it.groupValues[1] }.toList()

        // 如果有多个匹配，返回最长的那个（更可能是验证码）
        if (matches.isNotEmpty()) {
            return matches.maxByOrNull { it.length }
        }

        return null
    }

    /**
     * 构建消息内容，突出显示验证码
     */
    private fun buildMessageWithHighlightedCode(sender: String, content: String): String {
        val code = extractVerificationCode(content)
        return if (code != null) {
            "验证码: $code\n来自: $sender\n$content"
        } else {
            "来自: $sender\n$content"
        }
    }

    private fun buildWechatMessage(sender: String, content: String): JSONObject {
        val json = JSONObject()
        json.put("msgtype", "text")
        val text = JSONObject()
        text.put("content", buildMessageWithHighlightedCode(sender, content))
        json.put("text", text)
        return json
    }

    private fun buildDingtalkMessage(sender: String, content: String): JSONObject {
        val json = JSONObject()
        json.put("msgtype", "text")
        val text = JSONObject()
        text.put("content", buildMessageWithHighlightedCode(sender, content))
        json.put("text", text)
        return json
    }

    private fun buildFeishuMessage(sender: String, content: String): JSONObject {
        val json = JSONObject()
        json.put("msg_type", "text")
        val text = JSONObject()
        text.put("text", buildMessageWithHighlightedCode(sender, content))
        json.put("content", text)
        return json
    }

    private fun buildGenericMessage(sender: String, content: String): JSONObject {
        val json = JSONObject()
        json.put("sender", sender)
        json.put("content", content)
        json.put("verificationCode", extractVerificationCode(content))
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
        val arrStr = prefs.getString(Constants.PREF_CHANNELS, "[]") ?: "[]"
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
        val arrStr = prefs.getString(Constants.PREF_KEYWORD_CONFIGS, "[]") ?: "[]"
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
