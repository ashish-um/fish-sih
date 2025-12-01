package com.surendramaran.yolov8tflite

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cloudinary.Cloudinary
import com.cloudinary.utils.ObjectUtils
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val dbHelper = DatabaseHelper(context)
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    private val CLOUD_NAME = "dvvvv5mwt"
    private val API_KEY = "712361699253361"
    private val API_SECRET = "SaKXxjE_ekI28s_C61Ns1-DtymA"

    override suspend fun doWork(): Result {
        return try {
            val unsyncedList = dbHelper.getUnsyncedLogs()
            if (unsyncedList.isEmpty()) return Result.success()

            // Configure Cloudinary Locally (Bypasses MediaManager singleton issues)
            val cloudinary = Cloudinary(ObjectUtils.asMap(
                "cloud_name", CLOUD_NAME,
                "api_key", API_KEY,
                "api_secret", API_SECRET,
                "secure", true
            ))

            for ((index, item) in unsyncedList.withIndex()) {
                val progress = "Syncing ${index + 1}/${unsyncedList.size}: ${item.title}"
                setProgress(workDataOf("status" to progress, "is_syncing" to true))

                uploadLogItem(cloudinary, item)

                dbHelper.markAsSynced(item.id)
            }

            setProgress(workDataOf("status" to "Sync Complete", "is_syncing" to false))
            Result.success()

        } catch (e: Exception) {
            Log.e("SyncWorker", "Sync failed: ${e.message}", e)
            // Return specific error to UI
            Result.failure(workDataOf("error_message" to (e.message ?: "Unknown error")))
        }
    }

    private suspend fun uploadLogItem(cloudinary: Cloudinary, item: HistoryItem) {
        val imagePaths = item.imagePath.split("|").filter { it.isNotEmpty() }
        val imageUrls = mutableListOf<String>()

        // 1. Upload Images
        for ((imgIndex, path) in imagePaths.withIndex()) {
            val file = File(path)
            if (file.exists()) {
                setProgress(workDataOf(
                    "status" to "Uploading Image ${imgIndex + 1} for ${item.title}...",
                    "is_syncing" to true
                ))

                // Use the local cloudinary instance
                val url = uploadImage(cloudinary, file)
                imageUrls.add(url)
            }
        }

        // 2. Upload Data to Firestore
        setProgress(workDataOf("status" to "Saving Data...", "is_syncing" to true))

        val logData = hashMapOf(
            "timestamp" to item.timestamp,
            "title" to item.title,
            "details" to item.details,
            "type" to item.type,
            "location" to hashMapOf(
                "lat" to item.lat,
                "lng" to item.lng,
                "name" to item.placeName
            ),
            "image_urls" to imageUrls
        )

        firestore.collection("logs").add(logData).await()
    }

    private suspend fun uploadImage(cloudinary: Cloudinary, file: File): String = withContext(Dispatchers.IO) {
        val params = ObjectUtils.asMap(
            "folder", "fish_app_history",
            "resource_type", "image"
        )
        // Synchronous upload (Blocking I/O is fine inside withContext(IO))
        val result = cloudinary.uploader().upload(file, params)
        return@withContext result["secure_url"] as String
    }
}