package com.surendramaran.yolov8tflite

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.surendramaran.yolov8tflite.databinding.FragmentFreshnessBinding
import com.yalantis.ucrop.UCrop
import java.io.File

class FreshnessFragment : Fragment() {

    private var _binding: FragmentFreshnessBinding? = null
    private val binding get() = _binding!!

    private var classifierEyes: FreshnessClassifier? = null
    private var classifierGills: FreshnessClassifier? = null
    private var eyesFreshnessScore: Float? = null
    private var gillsFreshnessScore: Float? = null
    private var isUploadingEyes = true

    private var tempImageUri: Uri? = null

    // 1. Gallery Launcher -> Sends to Crop
    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { startCrop(it) }
    }

    // 2. Camera Launcher -> Sends to Crop
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempImageUri != null) {
            startCrop(tempImageUri!!)
        }
    }

    // 3. Crop Launcher -> Sends to Processing
    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let { processImage(it) }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            val error = UCrop.getError(result.data!!)
            Toast.makeText(context, "Crop Error: ${error?.message}", Toast.LENGTH_SHORT).show()
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
        setupClassifiers()

        binding.btnUploadEyes.setOnClickListener {
            isUploadingEyes = true
            getContent.launch("image/*")
        }
        binding.btnCameraEyes.setOnClickListener {
            isUploadingEyes = true
            launchCamera()
        }

        binding.btnUploadGills.setOnClickListener {
            isUploadingEyes = false
            getContent.launch("image/*")
        }
        binding.btnCameraGills.setOnClickListener {
            isUploadingEyes = false
            launchCamera()
        }
    }

    private fun launchCamera() {
        try {
            val file = File.createTempFile("temp_cam_image", ".jpg", requireContext().cacheDir)
            tempImageUri = FileProvider.getUriForFile(
                requireContext(),
                "com.surendramaran.yolov8tflite.provider",
                file
            )
            takePicture.launch(tempImageUri)
        } catch (e: Exception) {
            Toast.makeText(context, "Camera Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCrop(sourceUri: Uri) {
        try {
            // Create a destination file for the cropped image
            val destFile = File(requireContext().cacheDir, "cropped_image_${System.currentTimeMillis()}.jpg")
            val destUri = Uri.fromFile(destFile)

            val options = UCrop.Options()
            options.setToolbarTitle("Crop Image")
            options.setFreeStyleCropEnabled(true) // Allow any aspect ratio

            // Create the Intent using the builder logic but launching with our registered launcher
            val uCrop = UCrop.of(sourceUri, destUri).withOptions(options)

            // We need to use the Intent directly with our ActivityResultLauncher
            val intent = uCrop.getIntent(requireContext())
            cropImage.launch(intent)

        } catch (e: Exception) {
            Toast.makeText(context, "Could not start crop: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processImage(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            options.inSampleSize = calculateInSampleSize(options, 1024, 1024)
            options.inJustDecodeBounds = false

            val scaledStream = requireContext().contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(scaledStream, null, options)
            scaledStream?.close()

            if (bitmap != null) {
                if (isUploadingEyes) {
                    binding.imgEyes.setImageBitmap(bitmap)
                    binding.imgEyes.setPadding(0,0,0,0) // REMOVE PADDING so image fills view
                    binding.imgEyes.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER

                    binding.txtResultEyes.text = "Analyzing..."
                    classifierEyes?.classify(bitmap)
                } else {
                    binding.imgGills.setImageBitmap(bitmap)
                    binding.imgGills.setPadding(0,0,0,0) // REMOVE PADDING
                    binding.imgGills.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER

                    binding.txtResultGills.text = "Analyzing..."
                    classifierGills?.classify(bitmap)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error processing image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupClassifiers() {
        try {
            classifierEyes = FreshnessClassifier(
                requireContext(), "eyes_model.tflite", "eyes_labels.txt",
                object : FreshnessClassifier.FreshnessListener {
                    override fun onResult(className: String, confidence: Float) {
                        handleResult(true, className, confidence)
                    }
                }
            )
            classifierGills = FreshnessClassifier(
                requireContext(), "gills_model.tflite", "gills_labels.txt",
                object : FreshnessClassifier.FreshnessListener {
                    override fun onResult(className: String, confidence: Float) {
                        handleResult(false, className, confidence)
                    }
                }
            )
        } catch (e: Exception) { }
    }

    private fun handleResult(isEyes: Boolean, className: String, confidence: Float) {
        activity?.runOnUiThread {
            val percentage = (confidence * 100).toInt()
            val text = "$className ($percentage%)"
            val normalizedScore = if (className.lowercase().contains("non")) 1.0f - confidence else confidence

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

    private fun calculateFinalVerdict() {
        val score1 = eyesFreshnessScore
        val score2 = gillsFreshnessScore

        if (score1 != null && score2 != null) {
            val averageScore = (score1 + score2) / 2
            val finalPercent = (averageScore * 100).toInt()

            // Define colors using standard Android Color class
            val colorGreen = android.graphics.Color.parseColor("#2E7D32") // Darker Green for better contrast
            val colorRed = android.graphics.Color.parseColor("#C62828")   // Darker Red

            if (averageScore > 0.5) {
                binding.txtFinalResult.text = "FISH IS FRESH\n(Overall Score: $finalPercent%)"
                // Change Background to Green, Text stays White
                binding.cardFinalVerdict.setCardBackgroundColor(colorGreen)
            } else {
                binding.txtFinalResult.text = "FISH IS NOT FRESH\n(Overall Score: $finalPercent%)"
                // Change Background to Red, Text stays White
                binding.cardFinalVerdict.setCardBackgroundColor(colorRed)
            }

            // Ensure text is always white
            binding.txtFinalResult.setTextColor(android.graphics.Color.WHITE)

        } else {
            binding.txtFinalResult.text = "Upload both images"
            binding.cardFinalVerdict.setCardBackgroundColor(android.graphics.Color.parseColor("#757575")) // Gray
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