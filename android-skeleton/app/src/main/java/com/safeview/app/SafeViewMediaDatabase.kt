package com.safeview.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Stores only media identifiers, classification metadata, and policy outcomes. */
class SafeViewMediaDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "safeview_media.db",
    null,
    1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE media_scan (
                media_id TEXT PRIMARY KEY,
                media_type TEXT NOT NULL,
                category TEXT NOT NULL,
                confidence REAL NOT NULL,
                scanned_at INTEGER NOT NULL,
                policy_action TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE protection_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                source TEXT NOT NULL,
                package_name TEXT,
                category TEXT NOT NULL,
                confidence REAL NOT NULL,
                policy_action TEXT NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun upsertMedia(
        mediaId: String,
        mediaType: String,
        result: ClassificationResult,
        action: PolicyAction,
        scannedAt: Long = System.currentTimeMillis()
    ) {
        val values = ContentValues().apply {
            put("media_id", mediaId)
            put("media_type", mediaType)
            put("category", result.category.name)
            put("confidence", result.confidence)
            put("scanned_at", scannedAt)
            put("policy_action", action.name)
        }
        writableDatabase.insertWithOnConflict("media_scan", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun addEvent(
        source: String,
        packageName: String?,
        result: ClassificationResult,
        action: PolicyAction
    ) {
        val values = ContentValues().apply {
            put("created_at", System.currentTimeMillis())
            put("source", source)
            put("package_name", packageName)
            put("category", result.category.name)
            put("confidence", result.confidence)
            put("policy_action", action.name)
        }
        writableDatabase.insert("protection_event", null, values)
    }

    fun recentEvents(limit: Int = 50): List<String> {
        val output = mutableListOf<String>()
        readableDatabase.query(
            "protection_event",
            arrayOf("created_at", "source", "package_name", "category", "confidence", "policy_action"),
            null, null, null, null, "created_at DESC", limit.coerceIn(1, 100).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val source = cursor.getString(1)
                val packageName = cursor.getString(2).orEmpty()
                val category = cursor.getString(3)
                val confidence = cursor.getFloat(4)
                val action = cursor.getString(5)
                output += "$source${if (packageName.isBlank()) "" else " · $packageName"} — $action ($category ${(confidence * 100).toInt()}%)"
            }
        }
        return output
    }

    fun counts(): Map<String, Int> {
        val counts = mutableMapOf("media" to 0, "events" to 0, "blocked" to 0)
        readableDatabase.rawQuery("SELECT COUNT(*) FROM media_scan", null).use { if (it.moveToFirst()) counts["media"] = it.getInt(0) }
        readableDatabase.rawQuery("SELECT COUNT(*) FROM protection_event", null).use { if (it.moveToFirst()) counts["events"] = it.getInt(0) }
        readableDatabase.rawQuery("SELECT COUNT(*) FROM protection_event WHERE policy_action = 'BLOCK'", null).use { if (it.moveToFirst()) counts["blocked"] = it.getInt(0) }
        return counts
    }
}
