package com.surendramaran.yolov8tflite

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var networkHelper: NetworkHelper
    private lateinit var statusBanner: TextView

    // UI State
    private var isNetworkConnected = false
    private var isSyncing = false

    // Transient State (Result Message)
    private var syncResultMessage: String? = null
    private var syncResultSuccess: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        statusBanner = findViewById(R.id.networkStatusBanner)
        networkHelper = NetworkHelper(this)

        // 1. Network Observer
        networkHelper.observe(this) { isConnected ->
            isNetworkConnected = isConnected
            if (isConnected) {
                scheduleDataSync()
            }
            updateStatusBanner()
        }

        // 2. WorkManager Observer (Sync Progress & Result)
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData("HistoryUploadWork")
            .observe(this) { workInfos ->
                if (workInfos.isNullOrEmpty()) return@observe

                // FIX: Prioritize finding a RUNNING job.
                // If nothing is running, fall back to the LAST job in the list (the most recent one).
                // This prevents the UI from getting stuck on an old "SUCCEEDED" job [0].
                val workInfo = workInfos.find { it.state == WorkInfo.State.RUNNING } ?: workInfos.last()

                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        isSyncing = true
                        // This pulls the text set in SyncWorker (e.g., "Uploading Image 1 for Rohu...")
                        syncResultMessage = workInfo.progress.getString("status") ?: "Syncing data..."
                        syncResultSuccess = null
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        // Only show success if we were previously syncing or if it's the very latest update
                        isSyncing = false
                        syncResultMessage = "Data Synced Successfully"
                        syncResultSuccess = true

                        // Clear message after 3 seconds
                        clearResultAfterDelay()
                    }
                    WorkInfo.State.FAILED -> {
                        isSyncing = false
                        val error = workInfo.outputData.getString("error_message") ?: "Sync Failed"
                        syncResultMessage = "Sync Failed: $error"
                        syncResultSuccess = false

                        // Clear message after 4 seconds
                        clearResultAfterDelay(4000)
                    }
                    else -> {
                        isSyncing = false
                    }
                }
                updateStatusBanner()
            }

        // 3. Navigation Logic
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNavigation.setupWithNavController(navController)
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (item.itemId != navController.currentDestination?.id) {
                val builder = NavOptions.Builder()
                    .setLaunchSingleTop(true)
                    .setRestoreState(false)
                    .setEnterAnim(R.anim.fade_in)
                    .setExitAnim(R.anim.fade_out)
                    .setPopEnterAnim(R.anim.fade_in)
                    .setPopExitAnim(R.anim.fade_out)
                builder.setPopUpTo(navController.graph.startDestinationId, false, false)
                navController.navigate(item.itemId, null, builder.build())
            }
            true
        }
    }

    private fun clearResultAfterDelay(delay: Long = 3000) {
        binding.root.postDelayed({
            // Only clear if we aren't currently syncing (prevent clearing active progress)
            if (!isSyncing) {
                syncResultMessage = null
                syncResultSuccess = null
                updateStatusBanner()
            }
        }, delay)
    }

    private fun updateStatusBanner() {
        if (!isNetworkConnected) {
            // Priority 1: No Internet
            statusBanner.text = "Offline Mode"
            statusBanner.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            statusBanner.visibility = View.VISIBLE

        } else if (isSyncing) {
            // Priority 2: Active Sync - shows the detailed message from SyncWorker
            statusBanner.text = syncResultMessage ?: "Syncing..."
            statusBanner.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
            statusBanner.visibility = View.VISIBLE

        } else if (syncResultMessage != null) {
            // Priority 3: Just Finished (Success/Fail Message)
            statusBanner.text = syncResultMessage
            val color = if (syncResultSuccess == true) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            statusBanner.setBackgroundColor(ContextCompat.getColor(this, color))
            statusBanner.visibility = View.VISIBLE

        } else {
            // Priority 4: Online Idle
            statusBanner.text = "Online"
            statusBanner.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            statusBanner.visibility = View.VISIBLE
        }
    }

    private fun scheduleDataSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequest.Builder(SyncWorker::class.java)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            "HistoryUploadWork",
            ExistingWorkPolicy.APPEND,
            syncRequest
        )
    }
}