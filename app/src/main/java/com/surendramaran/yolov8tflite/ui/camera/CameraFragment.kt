package com.surendramaran.yolov8tflite.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.surendramaran.yolov8tflite.R
import com.surendramaran.yolov8tflite.data.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.data.Constants.MODEL_PATH
import com.surendramaran.yolov8tflite.data.DatabaseHelper
import com.surendramaran.yolov8tflite.data.SyncWorker
import com.surendramaran.yolov8tflite.databinding.FragmentCameraBinding
import com.surendramaran.yolov8tflite.ml.BoundingBox
import com.surendramaran.yolov8tflite.ml.Detector
import com.surendramaran.yolov8tflite.ml.segmentation.utils.Utils
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.math.max

class CameraFragment : Fragment(), Detector.DetectorListener {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null
    private var detectorEyes: Detector? = null

    private lateinit var cameraExecutor: ExecutorService
    private var isCameraRunning = true

    private lateinit var detectionAdapter: DetectionAdapter

    private lateinit var dbHelper: DatabaseHelper
    private var lastBitmap: Bitmap? = null
    private var lastResults: List<BoundingBox> = emptyList()
    private var lastEyeResults: List<BoundingBox> = emptyList()

    private var currentZoomRatio = 1.0f

    // Color Palette
    private val boxColors = listOf(
        Color.parseColor("#FF5722"), // Orange
        Color.parseColor("#2979FF"), // Blue
        Color.parseColor("#00C853"), // Green
        Color.parseColor("#FFD600"), // Yellow
        Color.parseColor("#AA00FF"), // Purple
        Color.parseColor("#E91E63"), // Pink
        Color.parseColor("#00BCD4"), // Cyan
        Color.parseColor("#3E2723")  // Brown
    )

    // Map to keep track of colors assigned to species to ensure consistency
    private val speciesColorMap = mutableMapOf<String, Int>()

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { startCrop(it) }
    }

    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let { processGalleryImage(it) }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            val error = UCrop.getError(result.data!!)
            Toast.makeText(context, getString(R.string.crop_error_with_message, error?.message), Toast.LENGTH_SHORT).show()
        }
    }

    private val eyesListener = object : Detector.DetectorListener {
        override fun onEmptyDetect() {
            lastEyeResults = emptyList()
            activity?.runOnUiThread {
                if (_binding != null) {
                    binding.eyesCountLabel.text = getString(R.string.eyes_count_default)
                    binding.eyesCountLabel.visibility = View.VISIBLE
                    binding.overlay.setEyeResults(emptyList())
                    binding.loadingProgress.visibility = View.GONE
                    updateTotalCount()
                }
            }
        }

        override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
            lastEyeResults = boundingBoxes
            activity?.runOnUiThread {
                if (_binding != null) {
                    binding.eyesCountLabel.text = getString(R.string.eyes_count_label, boundingBoxes.size)
                    binding.eyesCountLabel.visibility = View.VISIBLE
                    binding.overlay.setEyeResults(boundingBoxes)
                    binding.loadingProgress.visibility = View.GONE
                    updateTotalCount()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dbHelper = DatabaseHelper(requireContext())
        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            detector = Detector(requireContext(), MODEL_PATH, LABELS_PATH, this)
            detectorEyes = Detector(requireContext(), "eyes_identify.tflite", "eyes_labels.txt", eyesListener)
        }

        setupRecyclerView()

        if (allPermissionsGranted()) {
            binding.viewFinder.post { startCamera() }
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }

        bindListeners()
    }

    override fun onResume() {
        super.onResume()
        if (!isCameraRunning && binding.imagePreview.visibility == View.GONE) {
            restartCameraPreview()
        }
    }

    private fun setupRecyclerView() {
        detectionAdapter = DetectionAdapter()
        binding.detectionList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = detectionAdapter
        }
    }

    private fun bindListeners() {
        binding.apply {
            val scaleGestureDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val zoomState = camera?.cameraInfo?.zoomState?.value ?: return false
                    val currentRatio = zoomState.zoomRatio
                    val delta = detector.scaleFactor
                    val newZoomRatio = currentRatio * delta
                    camera?.cameraControl?.setZoomRatio(newZoomRatio)
                    currentZoomRatio = newZoomRatio
                    val roundedZoom = String.format("%.1fx", newZoomRatio)
                    zoomLevel.text = roundedZoom
                    zoomLevel.visibility = View.VISIBLE
                    zoomLevel.removeCallbacks { zoomLevel.visibility = View.GONE }
                    zoomLevel.postDelayed({ if (_binding != null) zoomLevel.visibility = View.GONE }, 2000)
                    return true
                }
            })

            viewFinder.setOnTouchListener { _, event ->
                scaleGestureDetector.onTouchEvent(event)
                return@setOnTouchListener true
            }

            btnDialogSave.setOnClickListener {
                saveCurrentDetection()
                saveDialog.visibility = View.GONE
            }

            btnDialogDiscard.setOnClickListener {
                showPausedState()
            }

            btnGallery.setOnClickListener {
                galleryLauncher.launch("image/*")
            }

            fab.setOnClickListener {
                if (binding.imagePreview.visibility == View.VISIBLE) {
                    restartCameraPreview()
                } else if (isCameraRunning) {
                    isCameraRunning = false
                    cameraProvider?.unbindAll()
                    fab.setImageResource(android.R.drawable.ic_media_play)

                    lastBitmap?.let { bmp ->
                        binding.imagePreview.setImageBitmap(bmp)
                        binding.imagePreview.visibility = View.VISIBLE
                        binding.viewFinder.visibility = View.INVISIBLE
                        binding.overlay.setImageDimensions(bmp.width, bmp.height)
                        binding.loadingProgress.visibility = View.VISIBLE

                        // --- FIXED: Clear previous results immediately ---
                        clearDetections()
                        // -----------------------------------------------

                        cameraExecutor.execute {
                            detector?.detect(bmp)
                            detectorEyes?.detect(bmp)
                        }
                    }
                    binding.saveDialog.visibility = View.VISIBLE
                } else {
                    restartCameraPreview()
                }
            }
        }
    }

    private fun showPausedState() {
        binding.imagePreview.visibility = View.VISIBLE
        binding.viewFinder.visibility = View.INVISIBLE
        binding.saveDialog.visibility = View.GONE
        binding.fab.setImageResource(android.R.drawable.ic_media_play)
        lastBitmap?.let { binding.overlay.setImageDimensions(it.width, it.height) }
        isCameraRunning = false
    }

    private fun startCrop(sourceUri: Uri) {
        try {
            val destFile = File(requireContext().cacheDir, "cropped_cam_${System.currentTimeMillis()}.jpg")
            val destUri = Uri.fromFile(destFile)
            val options = UCrop.Options()
            options.setToolbarTitle(getString(R.string.crop_for_ai))
            options.setFreeStyleCropEnabled(true)
            val uCrop = UCrop.of(sourceUri, destUri).withOptions(options)
            cropImage.launch(uCrop.getIntent(requireContext()))
        } catch (e: Exception) {
            Log.e(TAG, getString(R.string.error_starting_crop), e)
        }
    }

    private fun processGalleryImage(uri: Uri) {
        try {
            cameraProvider?.unbindAll()
            isCameraRunning = false
            binding.fab.setImageResource(android.R.drawable.ic_media_play)

            val inputStream = requireContext().contentResolver.openInputStream(uri)
            var bitmap = BitmapFactory.decodeStream(inputStream)

            if (bitmap != null) {
                bitmap = Utils.rotateImageIfRequired(requireContext(), bitmap, uri)
                bitmap = Utils.resizeBitmap(bitmap, 640)

                lastBitmap = bitmap

                binding.viewFinder.visibility = View.INVISIBLE
                binding.imagePreview.visibility = View.VISIBLE
                binding.imagePreview.setImageBitmap(bitmap)
                binding.overlay.setImageDimensions(bitmap.width, bitmap.height)
                binding.saveDialog.visibility = View.VISIBLE
                binding.loadingProgress.visibility = View.VISIBLE

                // --- FIXED: Clear previous results immediately ---
                clearDetections()
                // -----------------------------------------------

                cameraExecutor.execute {
                    detector?.detect(bitmap)
                    detectorEyes?.detect(bitmap)
                }
            } else {
                Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, getString(R.string.error_loading_gallery_image), e)
            Toast.makeText(context, getString(R.string.error_loading_gallery_image), Toast.LENGTH_SHORT).show()
        }
    }

    // Helper to clear everything from UI and State
    private fun clearDetections() {
        lastResults = emptyList()
        lastEyeResults = emptyList()
        binding.overlay.clear()
        detectionAdapter.updateDetections(emptyList())
        binding.totalCountLabel.text = getString(R.string.total_detected, 0)
        binding.eyesCountLabel.visibility = View.GONE
        binding.noDetectionText.visibility = View.GONE
    }

    private fun restartCameraPreview() {
        binding.imagePreview.visibility = View.GONE
        binding.viewFinder.visibility = View.VISIBLE
        binding.saveDialog.visibility = View.GONE
        binding.zoomLevel.visibility = View.GONE
        binding.eyesCountLabel.visibility = View.GONE
        binding.loadingProgress.visibility = View.GONE

        binding.fab.setImageResource(R.drawable.ic_camera)
        binding.overlay.setCameraMode()
        binding.overlay.clear()

        // Clear state
        lastResults = emptyList()
        lastEyeResults = emptyList()
        detectionAdapter.updateDetections(emptyList())
        binding.totalCountLabel.text = getString(R.string.total_detected, 0)
        binding.noDetectionText.visibility = View.GONE

        startCamera()
        isCameraRunning = true
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException(getString(R.string.camera_init_failed))
        val rotation = view?.display?.rotation ?: Surface.ROTATION_0
        val viewWidth = binding.viewFinder.width
        val viewHeight = binding.viewFinder.height
        val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setTargetRotation(rotation).build()
        imageAnalyzer = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setTargetRotation(rotation).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
            }
            val rotatedBitmap = Bitmap.createBitmap(bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)
            val croppedBitmap = cropBitmapToView(rotatedBitmap, viewWidth, viewHeight)
            lastBitmap = croppedBitmap

            if (isCameraRunning) {
                detector?.detect(croppedBitmap)
            }
        }
        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, imageAnalyzer)
            camera?.cameraControl?.setZoomRatio(currentZoomRatio)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, getString(R.string.use_case_binding_failed), exc)
        }
    }

    private fun cropBitmapToView(bitmap: Bitmap, viewWidth: Int, viewHeight: Int): Bitmap {
        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height
        if (viewWidth == 0 || viewHeight == 0) return bitmap
        val bitmapRatio = bitmapWidth.toFloat() / bitmapHeight
        val viewRatio = viewWidth.toFloat() / viewHeight
        var cropX = 0; var cropY = 0; var cropWidth = bitmapWidth; var cropHeight = bitmapHeight
        if (bitmapRatio > viewRatio) {
            cropHeight = bitmapHeight; cropWidth = (bitmapHeight * viewRatio).toInt(); cropX = (bitmapWidth - cropWidth) / 2
        } else {
            cropWidth = bitmapWidth; cropHeight = (bitmapWidth / viewRatio).toInt(); cropY = (bitmapHeight - cropHeight) / 2
        }
        if (cropWidth <= 0) cropWidth = 1; if (cropHeight <= 0) cropHeight = 1; if (cropX < 0) cropX = 0; if (cropY < 0) cropY = 0
        return Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight)
    }

    private fun saveCurrentDetection() {
        val bitmapToSave = lastBitmap ?: return
        val resultsToSave = lastResults
        val eyesToSave = lastEyeResults

        // Show a loading indicator if you have one, or a toast
        Toast.makeText(context, "Acquiring GPS...", Toast.LENGTH_SHORT).show()

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
            val cancellationTokenSource = CancellationTokenSource()

            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
                .addOnSuccessListener { location ->
                    var currentLat = 0.0
                    var currentLng = 0.0
                    var placeName = getString(R.string.location_not_available)

                    if (location != null) {
                        currentLat = location.latitude
                        currentLng = location.longitude
                        try {
                            val geocoder = Geocoder(requireContext(), Locale.getDefault())
                            @Suppress("DEPRECATION")
                            val addresses = geocoder.getFromLocation(currentLat, currentLng, 1)
                            if (!addresses.isNullOrEmpty()) {
                                placeName = addresses[0].locality ?: addresses[0].getAddressLine(0)
                            } else {
                                placeName = getString(R.string.lat_lng_location, currentLat, currentLng)
                            }
                        } catch (e: Exception) {
                            placeName = getString(R.string.lat_lng_location, currentLat, currentLng)
                        }
                    }

                    // --- PROCEED TO SAVE WITH FRESH LOCATION ---
                    saveDetectionToDb(bitmapToSave, resultsToSave, eyesToSave, currentLat, currentLng, placeName)
                }
                .addOnFailureListener {
                    // Fallback if GPS fails
                    saveDetectionToDb(bitmapToSave, resultsToSave, eyesToSave, 0.0, 0.0, getString(R.string.location_not_available))
                }
        } else {
            // Permission not granted, save without location
            saveDetectionToDb(bitmapToSave, resultsToSave, eyesToSave, 0.0, 0.0, getString(R.string.location_not_available))
        }
    }

    // Helper function to handle the actual file/DB saving (Extracted from your original code)
    private fun saveDetectionToDb(bitmapToSave: Bitmap, resultsToSave: List<BoundingBox>, eyesToSave: List<BoundingBox>, lat: Double, lng: Double, placeName: String) {
        try {
            val mutableBitmap = bitmapToSave.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableBitmap)
            val boxPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 8f }
            val eyePaint = Paint().apply { color = ContextCompat.getColor(requireContext(), R.color.overlay_red); style = Paint.Style.STROKE; strokeWidth = 8f }
            val textPaint = Paint().apply { color = Color.WHITE; textSize = 40f; style = Paint.Style.FILL }
            val textBgPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }

            resultsToSave.forEachIndexed { index, box ->
                // Ensure species specific color consistency
                val color = speciesColorMap.getOrPut(box.clsName) {
                    boxColors[speciesColorMap.size % boxColors.size]
                }
                boxPaint.color = color
                val left = box.x1 * mutableBitmap.width; val top = box.y1 * mutableBitmap.height
                val right = box.x2 * mutableBitmap.width; val bottom = box.y2 * mutableBitmap.height
                canvas.drawRect(left, top, right, bottom, boxPaint)
                val text = "${box.clsName} ${String.format("%.2f", box.cnf)}"
                val bounds = Rect(); textPaint.getTextBounds(text, 0, text.length, bounds)
                canvas.drawRect(left, top, left + bounds.width() + 16, top + bounds.height() + 16, textBgPaint)
                canvas.drawText(text, left, top + bounds.height(), textPaint)
            }

            eyesToSave.forEach { box ->
                val left = box.x1 * mutableBitmap.width; val top = box.y1 * mutableBitmap.height
                val right = box.x2 * mutableBitmap.width; val bottom = box.y2 * mutableBitmap.height
                canvas.drawRect(left, top, right, bottom, eyePaint)
            }

            val filename = "fish_detect_${System.currentTimeMillis()}.jpg"
            val file = File(requireContext().filesDir, filename)
            val out = FileOutputStream(file)
            mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush(); out.close()

            val fishCountList = resultsToSave.map { "${it.clsName} ${(it.cnf * 100).toInt()}%" }.toMutableList()
            if (eyesToSave.isNotEmpty()) fishCountList.add("Eyes: ${eyesToSave.size}")
            val countsString = fishCountList.joinToString(", ")
            val details = "Total: ${resultsToSave.size}, Eyes: ${eyesToSave.size}, Conf: ${resultsToSave.map { String.format("%.2f", it.cnf) }}"

            dbHelper.insertDetection(
                timestamp = System.currentTimeMillis(),
                imagePath = file.absolutePath,
                fishCount = countsString.ifEmpty { getString(R.string.none) },
                details = details,
                lat = lat,
                lng = lng,
                placeName = placeName
            )

            Toast.makeText(context, getString(R.string.saved_at_place, placeName), Toast.LENGTH_SHORT).show()
            triggerBackgroundSync()
        } catch (e: Exception) {
            Log.e("CameraFragment", getString(R.string.error_saving_detection), e)
            Toast.makeText(context, getString(R.string.error_saving, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerBackgroundSync() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val syncRequest = OneTimeWorkRequest.Builder(SyncWorker::class.java).setConstraints(constraints).setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(requireContext()).enqueueUniqueWork("HistoryUploadWork", ExistingWorkPolicy.APPEND, syncRequest)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) binding.viewFinder.post { startCamera() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        detectorEyes?.close()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }

    private fun updateTotalCount() {
        if (_binding == null) return
        val fishCount = lastResults.size
        val eyeCount = lastEyeResults.size
        val total = max(fishCount, eyeCount)
        binding.totalCountLabel.text = getString(R.string.total_detected, total)
    }

    override fun onEmptyDetect() {
        lastResults = emptyList()
        activity?.runOnUiThread {
            if (_binding != null) {
                binding.overlay.setResults(emptyList(), emptyList())
                updateTotalCount()
                binding.noDetectionText.visibility = View.VISIBLE
                binding.detectionList.visibility = View.GONE
                detectionAdapter.updateDetections(emptyList())
            }
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        lastResults = boundingBoxes
        activity?.runOnUiThread {
            if (_binding != null) {
                binding.inferenceTime.text = getString(R.string.inference_time_ms, inferenceTime)

                // 1. Group by Species Name
                val groupedList = boundingBoxes.groupBy { it.clsName }

                // 2. Prepare Colors for Overlay (Order must match original boundingBoxes to map correctly)
                val overlayColors = boundingBoxes.map { box ->
                    speciesColorMap.getOrPut(box.clsName) {
                        boxColors[speciesColorMap.size % boxColors.size]
                    }
                }

                // 3. Update Overlay with boxes and their specific colors
                binding.overlay.setResults(boundingBoxes, overlayColors)

                // 4. Create Aggregated List for Adapter
                val detectionItems = groupedList.map { (species, boxes) ->
                    val count = boxes.size
                    val avgConf = boxes.map { it.cnf }.average().toFloat()
                    val color = speciesColorMap[species] ?: Color.WHITE

                    DetectionItem(
                        fishName = species,
                        count = count,
                        avgConfidence = avgConf,
                        color = color
                    )
                }

                // 5. Update UI
                updateTotalCount()
                if (detectionItems.isEmpty()) {
                    binding.noDetectionText.visibility = View.VISIBLE
                    binding.detectionList.visibility = View.GONE
                } else {
                    binding.noDetectionText.visibility = View.GONE
                    binding.detectionList.visibility = View.VISIBLE
                    detectionAdapter.updateDetections(detectionItems)
                }
            }
        }
    }

    companion object {
        private const val TAG = "Camera"
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).toTypedArray()
    }
}