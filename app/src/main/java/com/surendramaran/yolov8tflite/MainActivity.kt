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
import androidx.work.*
import com.surendramaran.yolov8tflite.data.SyncWorker
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import com.surendramaran.yolov8tflite.utils.NetworkHelper
import com.surendramaran.yolov8tflite.utils.UserUtils
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var networkHelper: NetworkHelper
    private lateinit var statusBanner: TextView

    // UI State
    private var isNetworkConnected = false
    private var isSyncing = false
    private var syncResultMessage: String? = null
    private var syncResultSuccess: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        statusBanner = findViewById(R.id.networkStatusBanner)
        networkHelper = NetworkHelper(this)

        // --- NAVIGATION SETUP ---
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)

        // 1. CONDITIONAL START DESTINATION
        if (!UserUtils.isProfileSet(this)) {
            navGraph.setStartDestination(R.id.languageFragment)
        } else {
            navGraph.setStartDestination(R.id.cameraFragment)
        }
        navController.graph = navGraph

        // 2. Hide Bottom Nav on Onboarding Screens
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.languageFragment || destination.id == R.id.profileFragment) {
                binding.bottomNavigation.visibility = View.GONE
            } else {
                binding.bottomNavigation.visibility = View.VISIBLE
            }
        }

        binding.bottomNavigation.setupWithNavController(navController)
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (item.itemId != navController.currentDestination?.id) {

                // CHANGE: Do NOT restore state for MoreFragment to force a reset to the main More screen.
                // For all other tabs, keep standard behavior (restore state).
                val shouldRestore = item.itemId != R.id.moreFragment

                val builder = NavOptions.Builder()
                    .setLaunchSingleTop(true)
                    .setRestoreState(shouldRestore) // Modified here
                    .setEnterAnim(R.anim.fade_in)
                    .setExitAnim(R.anim.fade_out)
                    .setPopEnterAnim(R.anim.fade_in)
                    .setPopExitAnim(R.anim.fade_out)

                // Save state of the start destination when switching away
                builder.setPopUpTo(
                    navController.graph.startDestinationId,
                    false,
                    true // SAVE STATE
                )

                navController.navigate(item.itemId, null, builder.build())
            }
            true
        }

        // --- NETWORK & SYNC LOGIC ---
        networkHelper.observe(this) { isConnected ->
            isNetworkConnected = isConnected
            if (isConnected) {
                scheduleDataSync()
            }
            updateStatusBanner()
        }

        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData("HistoryUploadWork")
            .observe(this) { workInfos ->
                if (workInfos.isNullOrEmpty()) return@observe
                val workInfo = workInfos.find { it.state == WorkInfo.State.RUNNING } ?: workInfos.last()

                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        isSyncing = true
                        syncResultMessage = workInfo.progress.getString("status") ?: "Syncing data..."
                        syncResultSuccess = null
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        isSyncing = false
                        syncResultMessage = "Data Synced Successfully"
                        syncResultSuccess = true
                        clearResultAfterDelay()
                    }
                    WorkInfo.State.FAILED -> {
                        isSyncing = false
                        val error = workInfo.outputData.getString("error_message") ?: "Sync Failed"
                        syncResultMessage = "Sync Failed: $error"
                        syncResultSuccess = false
                        clearResultAfterDelay(4000)
                    }
                    else -> { isSyncing = false }
                }
                updateStatusBanner()
            }
    }

    private fun clearResultAfterDelay(delay: Long = 3000) {
        binding.root.postDelayed({
            if (!isSyncing) {
                syncResultMessage = null
                syncResultSuccess = null
                updateStatusBanner()
            }
        }, delay)
    }

    private fun updateStatusBanner() {
        if (!isNetworkConnected) {
            statusBanner.text = "Offline Mode"
            statusBanner.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            statusBanner.visibility = View.VISIBLE
        } else if (isSyncing) {
            statusBanner.text = syncResultMessage ?: "Syncing..."
            statusBanner.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
            statusBanner.visibility = View.VISIBLE
        } else if (syncResultMessage != null) {
            statusBanner.text = syncResultMessage
            val color = if (syncResultSuccess == true) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            statusBanner.setBackgroundColor(ContextCompat.getColor(this, color))
            statusBanner.visibility = View.VISIBLE
        } else {
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