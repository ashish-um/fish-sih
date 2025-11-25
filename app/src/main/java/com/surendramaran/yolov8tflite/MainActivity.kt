package com.surendramaran.yolov8tflite

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        // 1. Setup default behavior first
        binding.bottomNavigation.setupWithNavController(navController)

        // 2. Override the listener to add Animations AND Reset State on Tab Switch
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (item.itemId != navController.currentDestination?.id) {
                val builder = NavOptions.Builder()
                    .setLaunchSingleTop(true)
                    .setRestoreState(false) // UPDATED: Changed to false (Always reset the tab)
                    .setEnterAnim(R.anim.fade_in)
                    .setExitAnim(R.anim.fade_out)
                    .setPopEnterAnim(R.anim.fade_in)
                    .setPopExitAnim(R.anim.fade_out)

                // Standard Bottom Navigation behavior: Pop to start destination
                builder.setPopUpTo(
                    navController.graph.startDestinationId,
                    false, // inclusive
                    false   // saveState -> UPDATED: Changed to false (Don't save stack)
                )

                navController.navigate(item.itemId, null, builder.build())
            }
            true
        }
    }
}