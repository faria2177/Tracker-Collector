package com.trackercollector.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Record(
    val id: Long = 0,
    val serial: String,
    val name: String,
    val designation: String,
    val mobile: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Local storage. The mobile number is UNIQUE, so the same person is never saved twice,
 * even when the same sheet is scanned again.
 */
class RecordDb(context: Context) : SQLiteOpenHelper(context, "tracker.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE records(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                serial TEXT NOT NULL DEFAULT '',
                name TEXT NOT NULL DEFAULT '',
                designation TEXT NOT NULL DEFAULT '',
                mobile TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun all(): List<Record> = readableDatabase.rawQuery(
        "SELECT id, serial, name, designation, mobile, created_at FROM records ORDER BY id", null
    ).use { c ->
        val list = ArrayList<Record>(c.count)
        while (c.moveToNext()) list += Record(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5))
        list
    }

    fun mobileExists(mobile: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM records WHERE mobile = ? LIMIT 1", arrayOf(mobile)
    ).use { it.moveToFirst() }

    /** Returns false when the mobile number already exists (duplicate). */
    fun insert(r: Record): Boolean {
        val id = writableDatabase.insertWithOnConflict("records", null, values(r), SQLiteDatabase.CONFLICT_IGNORE)
        return id != -1L
    }

    /** Returns false when the new mobile number belongs to another record. */
    fun update(r: Record): Boolean = try {
        writableDatabase.update("records", values(r), "id = ?", arrayOf(r.id.toString())) > 0
    } catch (e: android.database.sqlite.SQLiteConstraintException) {
        false
    }

    fun delete(id: Long) = writableDatabase.delete("records", "id = ?", arrayOf(id.toString()))

    fun clear() = writableDatabase.delete("records", null, null)

    private fun values(r: Record) = ContentValues().apply {
        put("serial", r.serial)
        put("name", r.name)
        put("designation", r.designation)
        put("mobile", r.mobile)
        put("created_at", r.createdAt)
    }

    companion object {
        /** Normalises any Bangladeshi number to 01XXXXXXXXX, or null if invalid. */
        fun normalizeMobile(raw: String): String? {
            val d = raw.filter { it.isDigit() }
            val m = Regex("(?:88)?(01[3-9]\\d{8})$").find(d) ?: return null
            return m.groupValues[1]
        }
    }
}
