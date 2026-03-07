package com.lanbing.smsforwarder

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object LogStore {
    private const val LOG_FILE = "sms_forwarder_logs.txt"
    private const val MAX_ENTRIES = 200
    private const val MAX_LINE_LENGTH = 2000
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val lock = Any()

    private fun logFile(context: Context): File {
        val dir = context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, LOG_FILE)
    }

    fun append(context: Context, text: String) {
        try {
            val file = logFile(context)
            val time = sdf.format(Date())
            // Sanitize: redact phone numbers (keep last 4 digits), truncate body if present
            val sanitized = sanitize(text)
            val line = "[$time] ${if (sanitized.length > MAX_LINE_LENGTH) sanitized.take(MAX_LINE_LENGTH) + "…(截断)" else sanitized}"
            synchronized(lock) {
                val existing = if (file.exists()) file.readText() else ""
                val newContent = line + "\n" + existing
                val lines = newContent.lines().filter { it.isNotBlank() }
                val limited = if (lines.size > MAX_ENTRIES) lines.take(MAX_ENTRIES) else lines
                file.writeText(limited.joinToString("\n"))
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    // Redact phone numbers in log lines: mask all but last 4 digits of 7-15 digit sequences
    private fun sanitize(text: String): String {
        return text.replace(Regex("""\+?\d{7,15}""")) { match ->
            val digits = match.value
            val last4 = digits.takeLast(4)
            "${digits.dropLast(4).map { if (it.isDigit()) '*' else it }.joinToString("")}$last4"
        }
    }

    fun readAll(context: Context): List<String> {
        try {
            val file = logFile(context)
            if (!file.exists()) return emptyList()
            synchronized(lock) {
                val lines = mutableListOf<String>()
                BufferedReader(FileReader(file)).use { br ->
                    var line: String? = br.readLine()
                    while (line != null) {
                        if (line.isNotBlank()) lines.add(line)
                        line = br.readLine()
                    }
                }
                return lines
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            return emptyList()
        }
    }

    fun clear(context: Context) {
        try {
            val file = logFile(context)
            synchronized(lock) {
                if (file.exists()) file.writeText("")
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    fun latest(context: Context): String {
        val list = readAll(context)
        return if (list.isEmpty()) "暂无日志" else list.first()
    }
}