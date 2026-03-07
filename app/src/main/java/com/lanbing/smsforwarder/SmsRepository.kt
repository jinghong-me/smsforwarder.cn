package com.lanbing.smsforwarder

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

data class SmsConversation(
    val threadId: Long,
    val address: String,
    val snippet: String,
    val date: Long,
    val unreadCount: Int,
    val messageCount: Int
)

data class SmsMessage(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int, // 1=inbox, 2=sent
    val read: Boolean,
    val status: Int // -1=none, 0=complete, 32=pending, 64=failed
)

object SmsRepository {
    private const val TAG = "SmsRepository"
    private const val PAGE_SIZE = 50

    fun observeConversations(context: Context): Flow<List<SmsConversation>> = callbackFlow {
        val cr = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(queryConversations(cr))
            }
        }
        cr.registerContentObserver(Uri.parse("content://mms-sms/"), true, observer)
        // Initial load
        trySend(queryConversations(cr))
        awaitClose { cr.unregisterContentObserver(observer) }
    }.flowOn(Dispatchers.IO)

    fun observeMessages(context: Context, threadId: Long): Flow<List<SmsMessage>> = callbackFlow {
        val cr = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(queryMessages(cr, threadId))
            }
        }
        cr.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        trySend(queryMessages(cr, threadId))
        awaitClose { cr.unregisterContentObserver(observer) }
    }.flowOn(Dispatchers.IO)

    private fun queryConversations(cr: ContentResolver): List<SmsConversation> {
        val list = mutableListOf<SmsConversation>()
        val uri = Uri.parse("content://mms-sms/conversations?simple=true")
        val cursor = try {
            cr.query(uri, null, null, null, "date DESC")
        } catch (t: Throwable) {
            Log.w(TAG, "queryConversations failed", t)
            null
        } ?: return list
        cursor.use { c ->
            var count = 0
            while (c.moveToNext() && count < 100) {
                count++
                try {
                    val idIdx = c.getColumnIndex("_id")
                    if (idIdx < 0) continue
                    val threadId = c.getLong(idIdx)
                    val addrIdx = c.getColumnIndex("address").takeIf { it >= 0 }
                        ?: c.getColumnIndex("recipient_ids").takeIf { it >= 0 }
                    val address = if (addrIdx != null) c.getString(addrIdx) ?: "" else ""
                    val snippetIdx = c.getColumnIndex("snippet")
                    val snippet = if (snippetIdx >= 0) c.getString(snippetIdx) ?: "" else ""
                    val dateIdx = c.getColumnIndex("date")
                    val date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    val unreadIdx = c.getColumnIndex("unread_count")
                    val unread = if (unreadIdx >= 0) c.getInt(unreadIdx) else 0
                    val msgCountIdx = c.getColumnIndex("message_count")
                    val msgCount = if (msgCountIdx >= 0) c.getInt(msgCountIdx) else 0
                    list.add(SmsConversation(threadId, address, snippet, date, unread, msgCount))
                } catch (t: Throwable) {
                    Log.w(TAG, "row parse failed", t)
                }
            }
        }
        return list
    }

    private fun queryMessages(cr: ContentResolver, threadId: Long): List<SmsMessage> {
        val list = mutableListOf<SmsMessage>()
        val cursor = try {
            cr.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("_id", "thread_id", "address", "body", "date", "type", "read", "status"),
                "thread_id=?",
                arrayOf(threadId.toString()),
                "date ASC"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "queryMessages failed", t)
            null
        } ?: return list
        cursor.use { c ->
            // Limit page size to avoid OOM
            val maxRows = minOf(c.count, PAGE_SIZE)
            // If there are more than PAGE_SIZE rows, skip to the last PAGE_SIZE
            val skipRows = if (c.count > PAGE_SIZE) c.count - PAGE_SIZE else 0
            if (skipRows > 0) c.moveToPosition(skipRows - 1)
            var count = 0
            while (c.moveToNext() && count < maxRows) {
                count++
                try {
                    val idIdx = c.getColumnIndex("_id")
                    val threadIdx = c.getColumnIndex("thread_id")
                    val addrIdx = c.getColumnIndex("address")
                    val bodyIdx = c.getColumnIndex("body")
                    val dateIdx = c.getColumnIndex("date")
                    val typeIdx = c.getColumnIndex("type")
                    val readIdx = c.getColumnIndex("read")
                    val statusIdx = c.getColumnIndex("status")
                    if (idIdx < 0 || bodyIdx < 0 || dateIdx < 0) continue
                    list.add(
                        SmsMessage(
                            id = c.getLong(idIdx),
                            threadId = if (threadIdx >= 0) c.getLong(threadIdx) else threadId,
                            address = if (addrIdx >= 0) c.getString(addrIdx) ?: "" else "",
                            body = c.getString(bodyIdx) ?: "",
                            date = c.getLong(dateIdx),
                            type = if (typeIdx >= 0) c.getInt(typeIdx) else 1,
                            read = if (readIdx >= 0) c.getInt(readIdx) == 1 else false,
                            status = if (statusIdx >= 0) c.getInt(statusIdx) else -1
                        )
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "msg row parse failed", t)
                }
            }
        }
        return list
    }

    suspend fun writeInboxMessage(context: Context, address: String, body: String, date: Long, read: Boolean = false): Long {
        return withContext(Dispatchers.IO) {
            try {
                val cv = ContentValues().apply {
                    put(Telephony.Sms.Inbox.ADDRESS, address)
                    put(Telephony.Sms.Inbox.BODY, body)
                    put(Telephony.Sms.Inbox.DATE, date)
                    put(Telephony.Sms.Inbox.READ, if (read) 1 else 0)
                    put(Telephony.Sms.Inbox.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                }
                val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, cv)
                uri?.lastPathSegment?.toLongOrNull() ?: -1L
            } catch (t: Throwable) {
                Log.w(TAG, "writeInboxMessage failed", t)
                -1L
            }
        }
    }

    suspend fun writeSentMessage(context: Context, address: String, body: String, date: Long): Long {
        return withContext(Dispatchers.IO) {
            try {
                val cv = ContentValues().apply {
                    put(Telephony.Sms.Sent.ADDRESS, address)
                    put(Telephony.Sms.Sent.BODY, body)
                    put(Telephony.Sms.Sent.DATE, date)
                    put(Telephony.Sms.Sent.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                    put(Telephony.Sms.Sent.READ, 1)
                }
                val uri = context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, cv)
                uri?.lastPathSegment?.toLongOrNull() ?: -1L
            } catch (t: Throwable) {
                Log.w(TAG, "writeSentMessage failed", t)
                -1L
            }
        }
    }

    suspend fun markThreadRead(context: Context, threadId: Long) {
        withContext(Dispatchers.IO) {
            try {
                val cv = ContentValues().apply { put(Telephony.Sms.READ, 1) }
                context.contentResolver.update(
                    Telephony.Sms.CONTENT_URI, cv,
                    "thread_id=? AND read=0", arrayOf(threadId.toString())
                )
            } catch (t: Throwable) {
                Log.w(TAG, "markThreadRead failed", t)
            }
        }
    }
}
