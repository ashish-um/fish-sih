package com.surendramaran.yolov8tflite

import android.graphics.BitmapFactory
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.ChangeImageTransform
import android.transition.ChangeTransform
import android.transition.Fade
import android.transition.TransitionSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryDetailFragment : Fragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val animationDuration = 300L

        // 1. The "Hero" Image Animation
        val sharedTransition = TransitionSet().apply {
            addTransition(ChangeBounds())
            addTransition(ChangeTransform())
            addTransition(ChangeImageTransform())
            duration = animationDuration
        }
        sharedElementEnterTransition = sharedTransition
        sharedElementReturnTransition = sharedTransition

        // 2. The Fade Animation for text/background
        enterTransition = Fade().apply { duration = animationDuration }
        returnTransition = Fade().apply { duration = animationDuration }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_history_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val imagePath = arguments?.getString("imagePath")
        val timestamp = arguments?.getLong("timestamp") ?: 0L
        val fishCount = arguments?.getString("fishCount")
        val details = arguments?.getString("details")

        val imageView: ImageView = view.findViewById(R.id.detailImage)
        val dateView: TextView = view.findViewById(R.id.detailDate)
        val countsView: TextView = view.findViewById(R.id.detailCounts)
        val rawView: TextView = view.findViewById(R.id.detailRaw)
        val btnBack: ImageButton = view.findViewById(R.id.btnBack)

        // CRITICAL: Ensure this matches the transitionName in the adapter
        imageView.transitionName = imagePath

        val sdf = SimpleDateFormat("MMMM dd, yyyy • hh:mm a", Locale.getDefault())
        dateView.text = sdf.format(Date(timestamp))
        countsView.text = fishCount
        rawView.text = details

        if (!imagePath.isNullOrEmpty()) {
            val imgFile = File(imagePath)
            if (imgFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
                imageView.setImageBitmap(bitmap)
            }
        }

        btnBack.setOnClickListener {
            // This triggers the reverse animation automatically
            findNavController().popBackStack()
        }
    }
}