package com.surendramaran.yolov8tflite

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryItem(
    val id: Int,
    val timestamp: Long,
    val imagePath: String,
    val title: String, // Generic title
    val details: String,
    val lat: Double,
    val lng: Double,
    val placeName: String,
    val type: Int // 0: Detection, 1: Freshness, 2: Volume
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
                + COLUMN_TYPE + " INTEGER DEFAULT 0" + ")")
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
        return db.insert(TABLE_NAME, null, values)
    }

    fun insertDetection(timestamp: Long, imagePath: String, fishCount: String, details: String, lat: Double, lng: Double, placeName: String): Long {
        return insertLog(timestamp, imagePath, fishCount, details, lat, lng, placeName, TYPE_DETECTION)
    }

    fun getHistoryByType(type: Int): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_NAME WHERE $COLUMN_TYPE = ? ORDER BY $COLUMN_TIMESTAMP DESC", arrayOf(type.toString()))

        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID))
                val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
                val imagePath = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_IMAGE_PATH))

                // Handle rename legacy compatibility
                val titleIndex = if (cursor.getColumnIndex(COLUMN_TITLE) != -1) cursor.getColumnIndex(COLUMN_TITLE) else cursor.getColumnIndex("fish_count")
                val title = cursor.getString(titleIndex)

                val details = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DETAILS))
                val lat = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LAT))
                val lng = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LNG))

                val placeNameIndex = cursor.getColumnIndex(COLUMN_PLACE_NAME)
                val placeName = if (placeNameIndex != -1) cursor.getString(placeNameIndex) else "Unknown Location"

                val typeIndex = cursor.getColumnIndex(COLUMN_TYPE)
                val itemType = if(typeIndex != -1) cursor.getInt(typeIndex) else 0

                list.add(HistoryItem(id, timestamp, imagePath, title, details, lat, lng, placeName, itemType))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun getAllDetections(): List<HistoryItem> {
        // Generic fetch all if needed
        val list = mutableListOf<HistoryItem>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_NAME ORDER BY $COLUMN_TIMESTAMP DESC", null)
        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID))
                val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
                val imagePath = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_IMAGE_PATH))
                val titleIndex = if (cursor.getColumnIndex(COLUMN_TITLE) != -1) cursor.getColumnIndex(COLUMN_TITLE) else cursor.getColumnIndex("fish_count")
                val title = cursor.getString(titleIndex)
                val details = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DETAILS))
                val lat = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LAT))
                val lng = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LNG))
                val placeNameIndex = cursor.getColumnIndex(COLUMN_PLACE_NAME)
                val placeName = if (placeNameIndex != -1) cursor.getString(placeNameIndex) else "Unknown Location"
                val typeIndex = cursor.getColumnIndex(COLUMN_TYPE)
                val itemType = if(typeIndex != -1) cursor.getInt(typeIndex) else 0

                list.add(HistoryItem(id, timestamp, imagePath, title, details, lat, lng, placeName, itemType))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    companion object {
        private const val DATABASE_VERSION = 4
        private const val DATABASE_NAME = "FishDetectionDB"
        const val TABLE_NAME = "detections"
        const val COLUMN_ID = "id"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_IMAGE_PATH = "image_path"
        const val COLUMN_TITLE = "title" // Replaces fish_count
        const val COLUMN_DETAILS = "details"
        const val COLUMN_LAT = "latitude"
        const val COLUMN_LNG = "longitude"
        const val COLUMN_PLACE_NAME = "place_name"
        const val COLUMN_TYPE = "type"

        const val TYPE_DETECTION = 0
        const val TYPE_FRESHNESS = 1
        const val TYPE_VOLUME = 2
    }
}