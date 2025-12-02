package com.surendramaran.yolov8tflite.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryItem(
    val id: Int,
    val timestamp: Long,
    val imagePath: String,
    val title: String,
    val details: String,
    val lat: Double,
    val lng: Double,
    val placeName: String,
    val type: Int,
    val isSynced: Int = 0 // New field
)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = ("CREATE TABLE " + TABLE_NAME + "("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_TIMESTAMP + " INTEGER,"
                + COLUMN_IMAGE_PATH + " TEXT,"
                + COLUMN_TITLE + " TEXT,"
                + COLUMN_DETAILS + " TEXT,"
                + COLUMN_LAT + " REAL,"
                + COLUMN_LNG + " REAL,"
                + COLUMN_PLACE_NAME + " TEXT,"
                + COLUMN_TYPE + " INTEGER DEFAULT 0,"
                + COLUMN_SYNCED + " INTEGER DEFAULT 0" + ")")
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun insertLog(timestamp: Long, imagePath: String, title: String, details: String, lat: Double, lng: Double, placeName: String, type: Int): Long {
        val db = this.writableDatabase
        val values = ContentValues()
        values.put(COLUMN_TIMESTAMP, timestamp)
        values.put(COLUMN_IMAGE_PATH, imagePath)
        values.put(COLUMN_TITLE, title)
        values.put(COLUMN_DETAILS, details)
        values.put(COLUMN_LAT, lat)
        values.put(COLUMN_LNG, lng)
        values.put(COLUMN_PLACE_NAME, placeName)
        values.put(COLUMN_TYPE, type)
        values.put(COLUMN_SYNCED, 0) // Not synced initially
        return db.insert(TABLE_NAME, null, values)
    }

    fun insertDetection(timestamp: Long, imagePath: String, fishCount: String, details: String, lat: Double, lng: Double, placeName: String): Long {
        return insertLog(timestamp, imagePath, fishCount, details, lat, lng, placeName, TYPE_DETECTION)
    }

    // Helper to get pending uploads
    fun getUnsyncedLogs(): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_NAME WHERE $COLUMN_SYNCED = 0", null)

        if (cursor.moveToFirst()) {
            do {
                list.add(extractItem(cursor))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun markAsSynced(id: Int) {
        val db = this.writableDatabase
        val values = ContentValues()
        values.put(COLUMN_SYNCED, 1)
        db.update(TABLE_NAME, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    fun getHistoryByType(type: Int): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_NAME WHERE $COLUMN_TYPE = ? ORDER BY $COLUMN_TIMESTAMP DESC", arrayOf(type.toString()))
        if (cursor.moveToFirst()) {
            do { list.add(extractItem(cursor)) } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun getAllDetections(): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_NAME ORDER BY $COLUMN_TIMESTAMP DESC", null)
        if (cursor.moveToFirst()) {
            do { list.add(extractItem(cursor)) } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    private fun extractItem(cursor: Cursor): HistoryItem {
        val id = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID))
        val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
        val imagePath = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_IMAGE_PATH))

        val titleIndex = if (cursor.getColumnIndex(COLUMN_TITLE) != -1) cursor.getColumnIndex(COLUMN_TITLE) else cursor.getColumnIndex("fish_count")
        val title = cursor.getString(titleIndex) ?: ""

        val details = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DETAILS))
        val lat = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LAT))
        val lng = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LNG))

        val placeNameIndex = cursor.getColumnIndex(COLUMN_PLACE_NAME)
        val placeName = if (placeNameIndex != -1) cursor.getString(placeNameIndex) else "Unknown"

        val typeIndex = cursor.getColumnIndex(COLUMN_TYPE)
        val itemType = if(typeIndex != -1) cursor.getInt(typeIndex) else 0

        return HistoryItem(id, timestamp, imagePath, title, details, lat, lng, placeName, itemType)
    }

    companion object {
        private const val DATABASE_VERSION = 5 // Version Bump
        private const val DATABASE_NAME = "FishDetectionDB"
        const val TABLE_NAME = "detections"
        const val COLUMN_ID = "id"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_IMAGE_PATH = "image_path"
        const val COLUMN_TITLE = "title"
        const val COLUMN_DETAILS = "details"
        const val COLUMN_LAT = "latitude"
        const val COLUMN_LNG = "longitude"
        const val COLUMN_PLACE_NAME = "place_name"
        const val COLUMN_TYPE = "type"
        const val COLUMN_SYNCED = "is_synced"

        const val TYPE_DETECTION = 0
        const val TYPE_FRESHNESS = 1
        const val TYPE_VOLUME = 2
    }
}