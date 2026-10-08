package com.ztdrop.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** The database is the source of truth for the native Android UI. */
class MessageStore(context: Context) : SQLiteOpenHelper(context, "ztdrop.sqlite3", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE friends(device_id TEXT PRIMARY KEY, device_name TEXT NOT NULL, status TEXT NOT NULL, remark TEXT NOT NULL DEFAULT '', pinned INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE messages(message_id TEXT PRIMARY KEY, peer_id TEXT NOT NULL, sender_id TEXT NOT NULL, created_at INTEGER NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX messages_peer_time ON messages(peer_id, created_at)")
        db.execSQL("CREATE TABLE message_media(message_id TEXT PRIMARY KEY, local_uri TEXT NOT NULL)")
        db.execSQL("CREATE TABLE cancelled_friend_requests(device_id TEXT PRIMARY KEY, attempts_remaining INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE friends ADD COLUMN remark TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE friends ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) db.execSQL("CREATE TABLE cancelled_friend_requests(device_id TEXT PRIMARY KEY, attempts_remaining INTEGER NOT NULL)")
    }

    // 附加索引不提高主库版本，旧正式 APK 仍可打开同一数据库。
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("CREATE TABLE IF NOT EXISTS message_media(message_id TEXT PRIMARY KEY, local_uri TEXT NOT NULL)")
    }

    @Synchronized
    fun friendStatus(id: String): String? {
        readableDatabase.rawQuery("SELECT status FROM friends WHERE device_id=?", arrayOf(id)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    @Synchronized
    fun upsertFriend(id: String, name: String, status: String) {
        writableDatabase.execSQL(
            "INSERT INTO friends(device_id, device_name, status) VALUES(?,?,?) ON CONFLICT(device_id) DO UPDATE SET device_name=excluded.device_name,status=excluded.status",
            arrayOf(id, name, status)
        )
    }

    @Synchronized
    fun removeFriend(id: String) {
        writableDatabase.execSQL("DELETE FROM friends WHERE device_id=?", arrayOf(id))
    }

    @Synchronized
    fun cancelFriendRequest(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM friends WHERE device_id=? AND status='pending_out'", arrayOf(id))
            db.execSQL("INSERT INTO cancelled_friend_requests(device_id,attempts_remaining) VALUES(?,10) ON CONFLICT(device_id) DO UPDATE SET attempts_remaining=10", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun clearCancelledRequest(id: String) {
        writableDatabase.execSQL("DELETE FROM cancelled_friend_requests WHERE device_id=?", arrayOf(id))
    }

    @Synchronized
    fun pendingCancellations(): List<String> {
        val result = ArrayList<String>()
        readableDatabase.rawQuery("SELECT device_id FROM cancelled_friend_requests WHERE attempts_remaining>0", null).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getString(0))
        }
        return result
    }

    @Synchronized
    fun markCancellationSent(id: String) {
        writableDatabase.execSQL("UPDATE cancelled_friend_requests SET attempts_remaining=attempts_remaining-1 WHERE device_id=?", arrayOf(id))
        writableDatabase.execSQL("DELETE FROM cancelled_friend_requests WHERE attempts_remaining<=0")
    }

    @Synchronized
    fun setRemark(id: String, remark: String) {
        writableDatabase.execSQL("UPDATE friends SET remark=? WHERE device_id=? AND status='accepted'", arrayOf(remark, id))
    }

    @Synchronized
    fun setPinned(id: String, pinned: Boolean) {
        writableDatabase.execSQL("UPDATE friends SET pinned=? WHERE device_id=? AND status='accepted'", arrayOf<Any>(if (pinned) 1 else 0, id))
    }

    @Synchronized
    fun clearMessages(id: String) {
        writableDatabase.execSQL("DELETE FROM messages WHERE peer_id=?", arrayOf(id))
    }

    @Synchronized
    fun deleteMessage(peerId: String, messageId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM message_media WHERE message_id IN (SELECT message_id FROM messages WHERE message_id=? AND peer_id=?)", arrayOf(messageId, peerId))
            db.execSQL("DELETE FROM messages WHERE message_id=? AND peer_id=?", arrayOf(messageId, peerId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun friends(online: Set<String>): JSONArray {
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT device_id,device_name,status,remark,pinned FROM friends ORDER BY device_name", null).use { cursor ->
            while (cursor.moveToNext()) result.put(JSONObject()
                .put("device_id", cursor.getString(0))
                .put("device_name", cursor.getString(1))
                .put("status", cursor.getString(2))
                .put("remark", cursor.getString(3))
                .put("pinned", cursor.getInt(4) != 0)
                .put("online", online.contains(cursor.getString(0))))
        }
        return result
    }

    @Synchronized
    fun insertMessage(message: JSONObject, peerId: String) {
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO messages(message_id,peer_id,sender_id,created_at,payload) VALUES(?,?,?,?,?)",
            arrayOf(message.getString("message_id"), peerId, message.getString("sender_id"), message.getLong("created_at"), message.toString())
        )
    }

    @Synchronized
    fun markDelivered(messageId: String, peerId: String, selfId: String) {
        readableDatabase.rawQuery(
            "SELECT payload FROM messages WHERE message_id=? AND peer_id=? AND sender_id=?",
            arrayOf(messageId, peerId, selfId)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val message = JSONObject(cursor.getString(0)).put("delivery_status", "delivered")
                writableDatabase.execSQL("UPDATE messages SET payload=? WHERE message_id=?", arrayOf(message.toString(), messageId))
            }
        }
    }

    @Synchronized
    fun setDownloadStatus(messageId: String, status: String) {
        require(status in setOf("completed", "failed"))
        readableDatabase.rawQuery("SELECT payload FROM messages WHERE message_id=?", arrayOf(messageId)).use { cursor ->
            if (cursor.moveToFirst()) {
                val message = JSONObject(cursor.getString(0)).put("download_status", status)
                writableDatabase.execSQL("UPDATE messages SET payload=? WHERE message_id=?",
                    arrayOf(message.toString(), messageId))
            }
        }
    }

    @Synchronized
    fun setLocalMedia(messageId: String, uri: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO message_media(message_id,local_uri) VALUES(?,?)", arrayOf(messageId, uri))
    }

    @Synchronized
    fun messages(peerId: String): JSONArray {
        val result = JSONArray()
        if (!validId(peerId)) return result
        readableDatabase.rawQuery(
            "SELECT payload FROM messages WHERE peer_id=? ORDER BY created_at DESC,rowid DESC LIMIT 100",
            arrayOf(peerId)
        ).use { cursor ->
            val rows = ArrayList<String>()
            while (cursor.moveToNext()) rows.add(cursor.getString(0))
            rows.asReversed().forEach {
                val message = JSONObject(it)
                message.remove("local_media_uri") // 不信任远端带来的本机 URI 字段。
                readableDatabase.rawQuery("SELECT local_uri FROM message_media WHERE message_id=?", arrayOf(message.optString("message_id"))).use { media ->
                    if (media.moveToFirst()) message.put("local_media_uri", media.getString(0))
                }
                result.put(message)
            }
        }
        return result
    }

    @Synchronized
    fun pendingMessages(selfId: String): List<JSONObject> {
        val result = ArrayList<JSONObject>()
        readableDatabase.rawQuery(
            "SELECT m.payload FROM messages m JOIN friends f ON m.peer_id=f.device_id WHERE m.sender_id=? AND f.status='accepted' ORDER BY m.created_at LIMIT 50",
            arrayOf(selfId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val message = JSONObject(cursor.getString(0))
                if (message.optString("delivery_status") == "pending") result.add(message)
            }
        }
        return result
    }

    @Synchronized
    fun pendingFriends(): List<String> {
        val result = ArrayList<String>()
        readableDatabase.rawQuery("SELECT device_id FROM friends WHERE status='pending_out'", null).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getString(0))
        }
        return result
    }
}

fun validId(value: String): Boolean = value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
