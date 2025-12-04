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
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    private var currentZoomRatio = 1.0f

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

    // Listener specifically for the Eyes Model
    private val eyesListener = object : Detector.DetectorListener {
        override fun onEmptyDetect() {
            activity?.runOnUiThread {
                if (_binding != null) {
                    binding.eyesCountLabel.text = "Eyes: 0"
                    binding.eyesCountLabel.visibility = View.VISIBLE
                    // Clear only eye boxes
                    binding.overlay.setEyeResults(emptyList())
                }
            }
        }

        override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
            activity?.runOnUiThread {
                if (_binding != null) {
                    binding.eyesCountLabel.text = "Eyes: ${boundingBoxes.size}"
                    binding.eyesCountLabel.visibility = View.VISIBLE
                    // Draw eye boxes
                    binding.overlay.setEyeResults(boundingBoxes)
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
            // UPDATED: Using "eyes_identify.tflite" as requested
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
            val bitmap = BitmapFactory.decodeStream(inputStream)
            lastBitmap = bitmap

            binding.viewFinder.visibility = View.INVISIBLE
            binding.imagePreview.visibility = View.VISIBLE
            binding.imagePreview.setImageBitmap(bitmap)
            binding.overlay.setImageDimensions(bitmap.width, bitmap.height)
            binding.saveDialog.visibility = View.VISIBLE

            cameraExecutor.execute {
                detector?.detect(bitmap)
                detectorEyes?.detect(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, getString(R.string.error_loading_gallery_image), e)
        }
    }

    private fun restartCameraPreview() {
        binding.imagePreview.visibility = View.GONE
        binding.viewFinder.visibility = View.VISIBLE
        binding.saveDialog.visibility = View.GONE
        binding.zoomLevel.visibility = View.GONE
        binding.eyesCountLabel.visibility = View.GONE

        binding.fab.setImageResource(R.drawable.ic_camera)
        binding.overlay.setCameraMode()
        binding.overlay.clear()
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
        var currentLat = 0.0; var currentLng = 0.0; var placeName = getString(R.string.location_not_available)

        try {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (lastKnownLocation != null) {
                    currentLat = lastKnownLocation.latitude; currentLng = lastKnownLocation.longitude
                    try {
                        val geocoder = Geocoder(requireContext(), Locale.getDefault())
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocation(currentLat, currentLng, 1)
                        if (!addresses.isNullOrEmpty()) {
                            placeName = addresses[0].locality ?: addresses[0].getAddressLine(0)
                        } else {
                            placeName = getString(R.string.lat_lng_location, currentLat, currentLng)
                        }
                    } catch (e: Exception) { placeName = getString(R.string.lat_lng_location, currentLat, currentLng) }
                }
            }
        } catch (e: Exception) { Log.e(TAG, getString(R.string.location_error), e) }

        try {
            val mutableBitmap = bitmapToSave.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableBitmap)
            val boxPaint = Paint().apply { color = ContextCompat.getColor(requireContext(), R.color.bounding_box_color); style = Paint.Style.STROKE; strokeWidth = 8f }
            val textPaint = Paint().apply { color = Color.WHITE; textSize = 40f; style = Paint.Style.FILL }
            val textBgPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }

            resultsToSave.forEach { box ->
                val left = box.x1 * mutableBitmap.width; val top = box.y1 * mutableBitmap.height
                val right = box.x2 * mutableBitmap.width; val bottom = box.y2 * mutableBitmap.height
                canvas.drawRect(left, top, right, bottom, boxPaint)
                val text = "${box.clsName} ${String.format("%.2f", box.cnf)}"
                val bounds = Rect(); textPaint.getTextBounds(text, 0, text.length, bounds)
                canvas.drawRect(left, top, left + bounds.width() + 16, top + bounds.height() + 16, textBgPaint)
                canvas.drawText(text, left, top + bounds.height(), textPaint)
            }

            val filename = "fish_detect_${System.currentTimeMillis()}.jpg"
            val file = File(requireContext().filesDir, filename)
            val out = FileOutputStream(file)
            mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush(); out.close()

            val fishCounts = resultsToSave.groupBy { it.clsName }.map { "${it.key}: ${it.value.size}" }.joinToString(", ")
            val details = "Total: ${resultsToSave.size}, Conf: ${resultsToSave.map { String.format("%.2f", it.cnf) }}"

            dbHelper.insertDetection(System.currentTimeMillis(), file.absolutePath, fishCounts.ifEmpty { getString(R.string.none) }, details, currentLat, currentLng, placeName)
            Toast.makeText(context, getString(R.string.saved_at_place, placeName), Toast.LENGTH_SHORT).show()
            triggerBackgroundSync()
        } catch (e: Exception) {
            Log.e(TAG, getString(R.string.error_saving_detection), e)
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

    override fun onEmptyDetect() {
        lastResults = emptyList()
        activity?.runOnUiThread {
            if (_binding != null) {
                binding.overlay.setResults(emptyList())
                binding.totalCountLabel.text = getString(R.string.total_detected, 0)
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
                binding.overlay.setResults(boundingBoxes)

                val fishCounts = boundingBoxes.groupBy { it.clsName }.map { (name, boxes) ->
                    val avgConf = boxes.map { it.cnf }.average().toFloat()
                    DetectionItem(name, boxes.size, avgConf)
                }.sortedByDescending { it.count }

                binding.totalCountLabel.text = getString(R.string.total_detected, boundingBoxes.size)
                if (fishCounts.isEmpty()) {
                    binding.noDetectionText.visibility = View.VISIBLE
                    binding.detectionList.visibility = View.GONE
                } else {
                    binding.noDetectionText.visibility = View.GONE
                    binding.detectionList.visibility = View.VISIBLE
                    detectionAdapter.updateDetections(fishCounts)
                }
            }
        }
    }

    companion object {
        private const val TAG = "Camera"
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).toTypedArray()
    }
}