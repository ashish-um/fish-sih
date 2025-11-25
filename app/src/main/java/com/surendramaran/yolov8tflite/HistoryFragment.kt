package com.surendramaran.yolov8tflite

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class HistoryFragment : Fragment() {

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private var isViewJustCreated = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Postpone transition for the shared element animation
        postponeEnterTransition()

        dbHelper = DatabaseHelper(requireContext())
        recyclerView = view.findViewById(R.id.historyRecyclerView)
        emptyText = view.findViewById(R.id.emptyStateText)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // FIXED: Load history immediately here to avoid deadlock.
        // postponing requires startPostponedEnterTransition to be called,
        // which happens inside loadHistory -> waitForTransition.
        loadHistory()
        isViewJustCreated = true
    }

    override fun onResume() {
        super.onResume()
        // Reload history only if we didn't just load it in onViewCreated.
        // This ensures the list is fresh when returning to this tab.
        if (!isViewJustCreated) {
            loadHistory()
        }
        isViewJustCreated = false
    }

    private fun loadHistory() {
        val data = dbHelper.getAllDetections()

        if (data.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            // Important: Must start transition even if empty
            startPostponedEnterTransition()
        } else {
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE

            val adapter = HistoryAdapter(data) { item, imageView ->
                val bundle = bundleOf(
                    "imagePath" to item.imagePath,
                    "timestamp" to item.timestamp,
                    "fishCount" to item.fishCount,
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
            waitForTransition()
        }
    }

    private fun waitForTransition() {
        // Wait for RecyclerView to lay out items before starting the transition
        recyclerView.doOnPreDraw {
            startPostponedEnterTransition()
        }
    }
}