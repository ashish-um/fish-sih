package com.surendramaran.yolov8tflite.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.surendramaran.yolov8tflite.R
import com.surendramaran.yolov8tflite.data.DatabaseHelper

class HistoryFragment : Fragment() {

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private var currentType = DatabaseHelper.TYPE_DETECTION

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dbHelper = DatabaseHelper(requireContext())
        recyclerView = view.findViewById(R.id.historyRecyclerView)
        emptyText = view.findViewById(R.id.emptyStateText)
        val chipGroup = view.findViewById<ChipGroup>(R.id.historyFilterChips)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        chipGroup.setOnCheckedChangeListener { _, checkedId ->
            currentType = when (checkedId) {
                R.id.chipDetection -> DatabaseHelper.TYPE_DETECTION
                R.id.chipFreshness -> DatabaseHelper.TYPE_FRESHNESS
                R.id.chipVolume -> DatabaseHelper.TYPE_VOLUME
                else -> DatabaseHelper.TYPE_DETECTION
            }
            loadHistory()
        }

        // Initial load
        loadHistory()
    }

    private fun loadHistory() {
        val data = dbHelper.getHistoryByType(currentType)

        if (data.isEmpty()) {
            emptyText.text = when(currentType) {
                DatabaseHelper.TYPE_FRESHNESS -> getString(R.string.no_freshness_logs)
                DatabaseHelper.TYPE_VOLUME -> getString(R.string.no_volume_logs)
                else -> getString(R.string.no_detections_found)
            }
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE

            val adapter = HistoryAdapter(data) { item, imageView ->
                val bundle = bundleOf(
                    "imagePath" to item.imagePath,
                    "timestamp" to item.timestamp,
                    "fishCount" to item.title, // Passed as 'fishCount' key for compatibility
                    "details" to item.details,
                    "placeName" to item.placeName,
                    "lat" to item.lat,
                    "lng" to item.lng
                )

                val extras = FragmentNavigatorExtras(
                    imageView to item.imagePath
                )

                findNavController().navigate(
                    R.id.action_history_to_detail,
                    bundle,
                    null,
                    extras
                )
            }
            recyclerView.adapter = adapter
        }
    }
}