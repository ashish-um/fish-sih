package com.surendramaran.yolov8tflite

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// Simple data model for the history list
data class HistoryItem(
    val id: Int,
    val timestamp: Long,
    val imagePath: String,
    val fishCount: String,
    val details: String
)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = ("CREATE TABLE " + TABLE_NAME + "("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_TIMESTAMP + " INTEGER,"
                + COLUMN_IMAGE_PATH + " TEXT,"
                + COLUMN_FISH_COUNT + " TEXT,"
                + COLUMN_DETAILS + " TEXT" + ")")
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun insertDetection(timestamp: Long, imagePath: String, fishCount: String, details: String): Long {
        val db = this.writableDatabase
        val values = ContentValues()
        values.put(COLUMN_TIMESTAMP, timestamp)
        values.put(COLUMN_IMAGE_PATH, imagePath)
        values.put(COLUMN_FISH_COUNT, fishCount)
        values.put(COLUMN_DETAILS, details)
        return db.insert(TABLE_NAME, null, values)
    }

    // NEW: Fetch all detections sorted by newest first
    fun getAllDetections(): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_NAME ORDER BY $COLUMN_TIMESTAMP DESC", null)

        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID))
                val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
                val imagePath = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_IMAGE_PATH))
                val fishCount = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_FISH_COUNT))
                val details = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DETAILS))

                list.add(HistoryItem(id, timestamp, imagePath, fishCount, details))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    companion object {
        private const val DATABASE_VERSION = 1
        private const val DATABASE_NAME = "FishDetectionDB"
        const val TABLE_NAME = "detections"
        const val COLUMN_ID = "id"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_IMAGE_PATH = "image_path"
        const val COLUMN_FISH_COUNT = "fish_count"
        const val COLUMN_DETAILS = "details"
    }
}