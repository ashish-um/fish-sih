package com.surendramaran.yolov8tflite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.surendramaran.yolov8tflite.databinding.FragmentFreshnessBinding
import java.io.InputStream

class FreshnessFragment : Fragment() {

    private var _binding: FragmentFreshnessBinding? = null
    private val binding get() = _binding!!

    // Two Separate Classifiers
    private var classifierEyes: FreshnessClassifier? = null
    private var classifierGills: FreshnessClassifier? = null

    // Store scores (0.0 to 1.0, where 1.0 is extremely fresh)
    private var eyesFreshnessScore: Float? = null
    private var gillsFreshnessScore: Float? = null

    // Track which button was clicked
    private var isUploadingEyes = true

    // Image Picker
    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                // 1. Load Scaled Image (Prevent Crash)
                val inputStream = requireContext().contentResolver.openInputStream(it)
                val options = BitmapFactory.Options()
                options.inJustDecodeBounds = true
                BitmapFactory.decodeStream(inputStream, null, options)
                inputStream?.close()

                options.inSampleSize = calculateInSampleSize(options, 1024, 1024)
                options.inJustDecodeBounds = false

                val scaledStream = requireContext().contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(scaledStream, null, options)
                scaledStream?.close()

                // 2. Route to correct logic (Eyes vs Gills)
                if (bitmap != null) {
                    if (isUploadingEyes) {
                        binding.imgEyes.setImageBitmap(bitmap)
                        binding.txtResultEyes.text = "Analyzing..."
                        classifierEyes?.classify(bitmap)
                    } else {
                        binding.imgGills.setImageBitmap(bitmap)
                        binding.txtResultGills.text = "Analyzing..."
                        classifierGills?.classify(bitmap)
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFreshnessBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize Eyes Classifier
        try {
            classifierEyes = FreshnessClassifier(
                requireContext(),
                "eyes_model.tflite",   // Make sure this file exists
                "eyes_labels.txt",
                object : FreshnessClassifier.FreshnessListener {
                    override fun onResult(className: String, confidence: Float) {
                        handleResult(true, className, confidence)
                    }
                }
            )
        } catch (e: Exception) {
            binding.txtResultEyes.text = "Error loading Eyes Model"
        }

        // Initialize Gills Classifier
        try {
            classifierGills = FreshnessClassifier(
                requireContext(),
                "gills_model.tflite",  // Make sure this file exists
                "gills_labels.txt",
                object : FreshnessClassifier.FreshnessListener {
                    override fun onResult(className: String, confidence: Float) {
                        handleResult(false, className, confidence)
                    }
                }
            )
        } catch (e: Exception) {
            binding.txtResultGills.text = "Error loading Gills Model"
        }

        // Button Listeners
        binding.btnUploadEyes.setOnClickListener {
            isUploadingEyes = true
            getContent.launch("image/*")
        }

        binding.btnUploadGills.setOnClickListener {
            isUploadingEyes = false
            getContent.launch("image/*")
        }
    }

    // Handle individual results and update UI
    private fun handleResult(isEyes: Boolean, className: String, confidence: Float) {
        activity?.runOnUiThread {
            val percentage = (confidence * 100).toInt()
            val text = "$className ($percentage%)"

            // Calculate "Freshness Score" for final logic
            // If class is "Fresh", score is high. If "Non-Fresh", score is low.
            val normalizedScore = if (className.lowercase().contains("non")) {
                1.0f - confidence // High confidence in "Non-Fresh" means low freshness
            } else {
                confidence // High confidence in "Fresh" means high freshness
            }

            if (isEyes) {
                binding.txtResultEyes.text = text
                eyesFreshnessScore = normalizedScore
                colorText(binding.txtResultEyes, className)
            } else {
                binding.txtResultGills.text = text
                gillsFreshnessScore = normalizedScore
                colorText(binding.txtResultGills, className)
            }

            calculateFinalVerdict()
        }
    }

    // Combine scores to decide
    private fun calculateFinalVerdict() {
        val score1 = eyesFreshnessScore
        val score2 = gillsFreshnessScore

        if (score1 != null && score2 != null) {
            // Average the two scores
            val averageScore = (score1 + score2) / 2
            val finalPercent = (averageScore * 100).toInt()

            if (averageScore > 0.5) {
                binding.txtFinalResult.text = "FISH IS FRESH\n(Overall Score: $finalPercent%)"
                binding.txtFinalResult.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
            } else {
                binding.txtFinalResult.text = "FISH IS NOT FRESH\n(Overall Score: $finalPercent%)"
                binding.txtFinalResult.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
            }
        } else {
            // If one is missing, prompt user
            binding.txtFinalResult.text = "Please upload both images."
            binding.txtFinalResult.setTextColor(resources.getColor(android.R.color.black, null))
        }
    }

    private fun colorText(view: android.widget.TextView, className: String) {
        if (className.lowercase().contains("non")) {
            view.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
        } else {
            view.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    override fun onDestroyView() {
        super.onDestroyView()
        classifierEyes?.close()
        classifierGills?.close()
        _binding = null
    }
}