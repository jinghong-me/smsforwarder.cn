package com.lanbing.smsforwarder

import android.content.Context
import android.util.Log
import androidx.work.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.TimeUnit

class ForwardWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "ForwardWorker"
        const val KEY_ADDRESS = "address"
        const val KEY_BODY = "body"
        const val KEY_DATE = "date"
        const val KEY_MESSAGE_ID = "message_id"
        private const val PREFS_FORWARDED = "forwarded_ids"
        private const val MAX_CACHED_IDS = 500

        private val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun enqueue(context: Context, address: String, body: String, date: Long, messageId: Long = -1L) {
            val data = workDataOf(
                KEY_ADDRESS to address,
                KEY_BODY to body,
                KEY_DATE to date,
                KEY_MESSAGE_ID to messageId
            )
            val request = OneTimeWorkRequestBuilder<ForwardWorker>()
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }

    override suspend fun doWork(): Result {
        val address = inputData.getString(KEY_ADDRESS) ?: return Result.failure()
        val body = inputData.getString(KEY_BODY) ?: return Result.failure()
        val date = inputData.getLong(KEY_DATE, System.currentTimeMillis())
        val messageId = inputData.getLong(KEY_MESSAGE_ID, -1L)

        // Idempotency check
        if (messageId > 0 && isAlreadyForwarded(messageId)) {
            Log.d(TAG, "messageId=$messageId already forwarded, skipping")
            return Result.success()
        }

        val prefs = context.getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("enabled", false)
        if (!isEnabled) return Result.success()

        val channels = loadChannels(prefs)
        val configs = loadConfigs(prefs)

        if (channels.isEmpty() || configs.isEmpty()) {
            return Result.success()
        }

        val fullMessage = normalizeContent(body)
        var anyFailure = false

        configs.forEach { cfg ->
            val kw = cfg.keyword.trim()
            val match = if (kw.isEmpty()) true else fullMessage.contains(kw, ignoreCase = true)
            if (!match) return@forEach
            val ch = channels.find { it.id == cfg.channelId } ?: return@forEach
            when (ch.type) {
                ChannelType.SMS -> {
                    try {
                        SmsSender.send(context, ch.target, fullMessage, ch.simSubscriptionId)
                        LogStore.append(context, "短信转发成功 → ${maskPhone(ch.target)} (规则: ${cfg.keyword})")
                    } catch (e: Exception) {
                        Log.e(TAG, "SMS forward failed", e)
                        LogStore.append(context, "短信转发失败 → ${maskPhone(ch.target)} (规则: ${cfg.keyword})")
                        anyFailure = true
                    }
                }
                else -> {
                    if (!isValidHttpsUrl(ch.target)) {
                        LogStore.append(context, "通道 ${ch.name} webhook URL 无效或非HTTPS: 已跳过")
                    } else {
                        try {
                            val success = sendToWebhook(ch.target, address, fullMessage)
                            if (success) {
                                LogStore.append(context, "转发成功 — 来自: ${maskPhone(address)} -> ${ch.name}")
                            } else {
                                LogStore.append(context, "转发失败 — 来自: ${maskPhone(address)} -> ${ch.name}")
                                anyFailure = true
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Webhook forward failed", e)
                            LogStore.append(context, "转发异常 — ${ch.name}: ${e.javaClass.simpleName}")
                            anyFailure = true
                        }
                    }
                }
            }
        }

        if (anyFailure) return Result.retry()

        if (messageId > 0) markForwarded(messageId)

        return Result.success()
    }

    private fun isAlreadyForwarded(messageId: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS_FORWARDED, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet("ids", emptySet()) ?: emptySet()
        return messageId.toString() in ids
    }

    private fun markForwarded(messageId: Long) {
        val prefs = context.getSharedPreferences(PREFS_FORWARDED, Context.MODE_PRIVATE)
        val ids = (prefs.getStringSet("ids", emptySet()) ?: emptySet()).toMutableSet()
        ids.add(messageId.toString())
        val trimmed = if (ids.size > MAX_CACHED_IDS) ids.toList().takeLast(MAX_CACHED_IDS).toSet() else ids
        prefs.edit().putStringSet("ids", trimmed).apply()
    }

    private fun sendToWebhook(webhookUrl: String, sender: String, content: String): Boolean {
        val json = JSONObject()
        json.put("msgtype", "text")
        val text = JSONObject()
        text.put("content", "来自: ${maskPhone(sender)}\n${normalizeContent(content)}")
        json.put("text", text)
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(webhookUrl).post(body).build()
        client.newCall(req).execute().use { resp -> return resp.isSuccessful }
    }

    private fun isValidHttpsUrl(s: String): Boolean {
        return try {
            val url = URL(s)
            url.protocol == "https" && url.host.isNotBlank()
        } catch (e: MalformedURLException) {
            false
        }
    }

    private fun normalizeContent(s: String): String =
        s.replace("\r", "").replace(Regex("\n{2,}"), "\n").trim()

    private fun maskPhone(phone: String): String {
        val digits = phone.filter { it.isDigit() || it == '+' }
        return if (digits.length > 4) "${digits.take(digits.length - 4).map { if (it.isDigit()) '*' else it }.joinToString("")}${digits.takeLast(4)}" else phone
    }

    private fun loadChannels(prefs: android.content.SharedPreferences): List<Channel> {
        val arrStr = prefs.getString("channels", "[]") ?: "[]"
        return try {
            val arr = JSONArray(arrStr)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val typeStr = o.optString("type", "WECHAT")
                val type = try { ChannelType.valueOf(typeStr) } catch (t: Throwable) { ChannelType.WECHAT }
                val simId = if (o.has("simId") && !o.isNull("simId")) o.optInt("simId", -1).let { if (it == -1) null else it } else null
                Channel(o.getString("id"), o.getString("name"), type, o.getString("target"), simId)
            }
        } catch (t: Throwable) { emptyList() }
    }

    private fun loadConfigs(prefs: android.content.SharedPreferences): List<KeywordConfig> {
        val arrStr = prefs.getString("keyword_configs", "[]") ?: "[]"
        return try {
            val arr = JSONArray(arrStr)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                KeywordConfig(o.getString("id"), o.getString("keyword"), o.getString("channelId"))
            }
        } catch (t: Throwable) { emptyList() }
    }
}
