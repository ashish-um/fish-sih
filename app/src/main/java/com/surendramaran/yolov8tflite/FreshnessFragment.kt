package com.surendramaran.yolov8tflite

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.surendramaran.yolov8tflite.databinding.FragmentFreshnessBinding
import com.yalantis.ucrop.UCrop
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FreshnessFragment : Fragment() {

    private var _binding: FragmentFreshnessBinding? = null
    private val binding get() = _binding!!

    // Detectors
    private var detectorEyes: Detector? = null
    private var detectorGills: Detector? = null

    // State
    private var isEyesCameraActive = false
    private var isGillsCameraActive = false
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    // Safety Flag
    @Volatile
    private var isSafeToDetect = true

    // Last Captured Bitmaps
    private var lastBitmapEyes: Bitmap? = null
    private var lastBitmapGills: Bitmap? = null

    // Scoring
    private var eyesScore: Float? = null
    private var gillsScore: Float? = null

    private var isGalleryForEyes = true

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { startCrop(it) }
    }

    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let { processGalleryImage(it) }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            val error = UCrop.getError(result.data!!)
            Toast.makeText(context, "Crop Error: ${error?.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (_binding != null) {
                binding.viewFinderEyes.post { startCamera(isEyes = true) }
            }
        } else {
            Toast.makeText(context, "Camera permission required.", Toast.LENGTH_SHORT).show()
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

        isSafeToDetect = true
        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            context?.let { safeContext ->
                detectorEyes = Detector(safeContext, "eyes_model.tflite", "eyes_labels.txt", object : Detector.DetectorListener {
                    override fun onEmptyDetect() {
                        if (isSafeToDetect) updateOverlay(isEyes = true, emptyList())
                    }
                    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
                        if (isSafeToDetect) updateOverlay(isEyes = true, boundingBoxes)
                    }
                })
                detectorGills = Detector(safeContext, "gills_model.tflite", "gills_labels.txt", object : Detector.DetectorListener {
                    override fun onEmptyDetect() {
                        if (isSafeToDetect) updateOverlay(isEyes = false, emptyList())
                    }
                    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
                        if (isSafeToDetect) updateOverlay(isEyes = false, boundingBoxes)
                    }
                })
            }
        }

        setupButtons()

        if (checkPermission()) {
            binding.viewFinderEyes.post {
                if (_binding != null) startCamera(isEyes = true)
            }
        }
    }

    private fun setupButtons() {
        binding.btnPlayPauseEyes.setOnClickListener {
            if (checkPermission()) {
                if (isEyesCameraActive) pauseCamera(isEyes = true) else startCamera(isEyes = true)
            }
        }
        binding.btnGalleryEyes.setOnClickListener {
            isGalleryForEyes = true
            galleryLauncher.launch("image/*")
        }

        binding.btnPlayPauseGills.setOnClickListener {
            if (checkPermission()) {
                if (isGillsCameraActive) pauseCamera(isEyes = false) else startCamera(isEyes = false)
            }
        }
        binding.btnGalleryGills.setOnClickListener {
            isGalleryForEyes = false
            galleryLauncher.launch("image/*")
        }
    }

    private fun startCamera(isEyes: Boolean) {
        if (_binding == null) return

        stopCamera()

        val (btn, preview, img, overlay) = if (isEyes) {
            Quad(binding.btnPlayPauseEyes, binding.viewFinderEyes, binding.imgEyes, binding.overlayEyes)
        } else {
            Quad(binding.btnPlayPauseGills, binding.viewFinderGills, binding.imgGills, binding.overlayGills)
        }

        btn.setImageResource(android.R.drawable.ic_media_pause)
        preview.visibility = View.VISIBLE
        img.visibility = View.GONE
        overlay.setCameraMode()

        if (isEyes) isEyesCameraActive = true else isGillsCameraActive = true

        val viewWidth = preview.width
        val viewHeight = preview.height

        val context = context ?: return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            if (!isAdded || _binding == null) return@addListener

            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(isEyes, viewWidth, viewHeight)
            } catch (e: Exception) {
                Log.e("Freshness", "Camera provider error", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun pauseCamera(isEyes: Boolean) {
        if (_binding == null) return

        stopCamera()

        val (btn, preview, img, overlay) = if (isEyes) {
            Quad(binding.btnPlayPauseEyes, binding.viewFinderEyes, binding.imgEyes, binding.overlayEyes)
        } else {
            Quad(binding.btnPlayPauseGills, binding.viewFinderGills, binding.imgGills, binding.overlayGills)
        }

        btn.setImageResource(android.R.drawable.ic_media_play)
        preview.visibility = View.INVISIBLE
        img.visibility = View.VISIBLE

        val bmp = if (isEyes) lastBitmapEyes else lastBitmapGills
        if (bmp != null) {
            img.setImageBitmap(bmp)
            overlay.setImageDimensions(bmp.width, bmp.height)
        }
    }

    private fun stopCamera() {
        if (_binding == null) return

        isEyesCameraActive = false
        isGillsCameraActive = false

        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e("Freshness", "Error unbinding camera", e)
        }

        binding.btnPlayPauseEyes.setImageResource(android.R.drawable.ic_media_play)
        binding.btnPlayPauseGills.setImageResource(android.R.drawable.ic_media_play)
    }

    private fun bindCameraUseCases(isEyes: Boolean, viewWidth: Int, viewHeight: Int) {
        val cameraProvider = cameraProvider ?: return
        if (_binding == null) return

        val previewView = if (isEyes) binding.viewFinderEyes else binding.viewFinderGills
        val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        val imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->
            if (!isSafeToDetect) {
                imageProxy.close()
                return@setAnalyzer
            }

            val bitmapBuffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            }
            val rotatedBitmap = Bitmap.createBitmap(bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)

            val croppedBitmap = cropBitmapToView(rotatedBitmap, viewWidth, viewHeight)

            if (isEyes) {
                lastBitmapEyes = croppedBitmap
                detectorEyes?.detect(croppedBitmap)
            } else {
                lastBitmapGills = croppedBitmap
                detectorGills?.detect(croppedBitmap)
            }
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            preview.setSurfaceProvider(previewView.surfaceProvider)
        } catch (e: Exception) {
            Log.e("Freshness", "Binding failed", e)
        }
    }

    private fun cropBitmapToView(bitmap: Bitmap, viewWidth: Int, viewHeight: Int): Bitmap {
        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height

        if (viewWidth == 0 || viewHeight == 0) return bitmap

        val bitmapRatio = bitmapWidth.toFloat() / bitmapHeight
        val viewRatio = viewWidth.toFloat() / viewHeight

        var cropX = 0
        var cropY = 0
        var cropWidth = bitmapWidth
        var cropHeight = bitmapHeight

        if (bitmapRatio > viewRatio) {
            cropHeight = bitmapHeight
            cropWidth = (bitmapHeight * viewRatio).toInt()
            cropX = (bitmapWidth - cropWidth) / 2
        } else {
            cropWidth = bitmapWidth
            cropHeight = (bitmapWidth / viewRatio).toInt()
            cropY = (bitmapHeight - cropHeight) / 2
        }

        if (cropWidth <= 0) cropWidth = 1
        if (cropHeight <= 0) cropHeight = 1
        if (cropX < 0) cropX = 0
        if (cropY < 0) cropY = 0

        return Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight)
    }

    private fun updateOverlay(isEyes: Boolean, boxes: List<BoundingBox>) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread

            val overlay = if (isEyes) binding.overlayEyes else binding.overlayGills
            val txtResult = if (isEyes) binding.txtResultEyes else binding.txtResultGills

            overlay.setResults(boxes)
            overlay.invalidate()

            if (boxes.isNotEmpty()) {
                val topBox = boxes.maxByOrNull { it.cnf }
                if (topBox != null) {
                    val label = topBox.clsName
                    val conf = (topBox.cnf * 100).toInt()
                    txtResult.text = "Detected: $label ($conf%)"

                    val isNonFresh = label.lowercase().contains("non") ||
                            label.lowercase().contains("spoil") ||
                            label.lowercase().contains("bad")

                    val score = if (isNonFresh) {
                        0.5f - (topBox.cnf / 2.0f)
                    } else {
                        0.5f + (topBox.cnf / 2.0f)
                    }

                    if (isEyes) {
                        eyesScore = score
                    } else {
                        gillsScore = score
                    }
                    calculateFinalVerdict()
                }
            } else {
                txtResult.text = "No detection"
            }
        }
    }

    private fun processGalleryImage(uri: Uri) {
        if (_binding == null) return

        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)

            stopCamera()

            if (isGalleryForEyes) {
                lastBitmapEyes = bitmap
                binding.imgEyes.setImageBitmap(bitmap)
                binding.imgEyes.visibility = View.VISIBLE
                binding.viewFinderEyes.visibility = View.INVISIBLE
                binding.overlayEyes.setImageDimensions(bitmap.width, bitmap.height)
                cameraExecutor.execute { detectorEyes?.detect(bitmap) }
            } else {
                lastBitmapGills = bitmap
                binding.imgGills.setImageBitmap(bitmap)
                binding.imgGills.visibility = View.VISIBLE
                binding.viewFinderGills.visibility = View.INVISIBLE
                binding.overlayGills.setImageDimensions(bitmap.width, bitmap.height)
                cameraExecutor.execute { detectorGills?.detect(bitmap) }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error loading image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCrop(sourceUri: Uri) {
        try {
            val destFile = File(requireContext().cacheDir, "crop_${System.currentTimeMillis()}.jpg")
            val destUri = Uri.fromFile(destFile)
            val options = UCrop.Options()
            options.setToolbarTitle("Crop Image")
            options.setFreeStyleCropEnabled(true)
            val uCrop = UCrop.of(sourceUri, destUri).withOptions(options)
            cropImage.launch(uCrop.getIntent(requireContext()))
        } catch (e: Exception) { }
    }

    private fun calculateFinalVerdict() {
        if (_binding == null) return

        val eScore = eyesScore
        val gScore = gillsScore

        if (eScore != null && gScore != null) {
            val avg = (eScore + gScore) / 2
            val percent = (avg * 100).toInt()
            if (avg > 0.5) {
                binding.txtFinalResult.text = "FRESH ($percent%)"
                binding.cardFinalVerdict.setCardBackgroundColor(android.graphics.Color.parseColor("#2E7D32"))
            } else {
                binding.txtFinalResult.text = "NOT FRESH ($percent%)"
                binding.cardFinalVerdict.setCardBackgroundColor(android.graphics.Color.parseColor("#C62828"))
            }
        }
    }

    private fun checkPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            false
        }
    }

    // FIX: Properly manage lifecycle to prevent native crash
    override fun onDestroyView() {
        isSafeToDetect = false
        stopCamera()
        super.onDestroyView()

        // Close detectors ON THE EXECUTOR to ensure no active inference is interrupted
        cameraExecutor.execute {
            detectorEyes?.close()
            detectorEyes = null
            detectorGills?.close()
            detectorGills = null
        }
        cameraExecutor.shutdown()

        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cleanup handled in onDestroyView
    }

    data class Quad(
        val btn: com.google.android.material.floatingactionbutton.FloatingActionButton,
        val preview: androidx.camera.view.PreviewView,
        val img: android.widget.ImageView,
        val overlay: OverlayView
    )
}