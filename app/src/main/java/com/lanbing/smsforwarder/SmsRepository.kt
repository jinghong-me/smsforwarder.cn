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
            cr.query(uri, null, null, null, "date DESC LIMIT 100")
        } catch (t: Throwable) {
            Log.w(TAG, "queryConversations failed", t)
            null
        } ?: return list
        cursor.use { c ->
            while (c.moveToNext()) {
                try {
                    val threadId = c.getLong(c.getColumnIndexOrThrow("_id"))
                    val address = c.getString(c.getColumnIndex("address").takeIf { it >= 0 } ?: c.getColumnIndex("recipient_ids")) ?: ""
                    val snippet = c.getString(c.getColumnIndex("snippet").takeIf { it >= 0 } ?: -1) ?: ""
                    val date = c.getLong(c.getColumnIndex("date").takeIf { it >= 0 } ?: -1)
                    val unread = c.getInt(c.getColumnIndex("unread_count").takeIf { it >= 0 } ?: -1)
                    val msgCount = c.getInt(c.getColumnIndex("message_count").takeIf { it >= 0 } ?: -1)
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
                "date ASC LIMIT $PAGE_SIZE"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "queryMessages failed", t)
            null
        } ?: return list
        cursor.use { c ->
            while (c.moveToNext()) {
                try {
                    list.add(
                        SmsMessage(
                            id = c.getLong(c.getColumnIndexOrThrow("_id")),
                            threadId = c.getLong(c.getColumnIndexOrThrow("thread_id")),
                            address = c.getString(c.getColumnIndexOrThrow("address")) ?: "",
                            body = c.getString(c.getColumnIndexOrThrow("body")) ?: "",
                            date = c.getLong(c.getColumnIndexOrThrow("date")),
                            type = c.getInt(c.getColumnIndexOrThrow("type")),
                            read = c.getInt(c.getColumnIndexOrThrow("read")) == 1,
                            status = c.getInt(c.getColumnIndexOrThrow("status"))
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
