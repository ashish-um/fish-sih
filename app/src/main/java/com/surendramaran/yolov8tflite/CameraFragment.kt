package com.surendramaran.yolov8tflite

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
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import com.surendramaran.yolov8tflite.databinding.FragmentCameraBinding
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment(), Detector.DetectorListener {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null

    private lateinit var cameraExecutor: ExecutorService
    private var isCameraRunning = true

    private lateinit var detectionAdapter: DetectionAdapter

    private lateinit var dbHelper: DatabaseHelper
    private var lastBitmap: Bitmap? = null
    private var lastResults: List<BoundingBox> = emptyList()

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { startCrop(it) }
    }

    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let { processGalleryImage(it) }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            val error = UCrop.getError(result.data!!)
            Toast.makeText(context, "Crop error: ${error?.message}", Toast.LENGTH_SHORT).show()
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
        }

        setupRecyclerView()

        if (allPermissionsGranted()) {
            startCamera()
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
            isGpu.setOnCheckedChangeListener { buttonView, isChecked ->
                cameraExecutor.submit { detector?.restart(isGpu = isChecked) }
                buttonView.setBackgroundColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (isChecked) R.color.orange else R.color.gray
                    )
                )
            }

            // NEW: Dialog Button Listeners
            btnDialogSave.setOnClickListener {
                saveCurrentDetection()
                // Hide dialog after saving
                saveDialog.visibility = View.GONE
            }

            btnDialogDiscard.setOnClickListener {
                // Discard capture and restart camera
                restartCameraPreview()
            }

            btnGallery.setOnClickListener {
                galleryLauncher.launch("image/*")
            }

            fab.setOnClickListener {
                if (binding.imagePreview.visibility == View.VISIBLE) {
                    restartCameraPreview()
                } else {
                    if (isCameraRunning) {
                        isCameraRunning = false
                        cameraProvider?.unbindAll()
                        binding.fab.setImageResource(android.R.drawable.ic_media_play)

                        lastBitmap?.let { bmp ->
                            binding.imagePreview.setImageBitmap(bmp)
                            binding.imagePreview.visibility = View.VISIBLE
                            binding.viewFinder.visibility = View.INVISIBLE
                            binding.overlay.setImageDimensions(bmp.width, bmp.height)
                        }
                        // UPDATED: Show Dialog instead of Save Button
                        binding.saveDialog.visibility = View.VISIBLE
                    } else {
                        restartCameraPreview()
                    }
                }
            }
        }
    }

    private fun startCrop(sourceUri: Uri) {
        try {
            val destFile = File(requireContext().cacheDir, "cropped_cam_${System.currentTimeMillis()}.jpg")
            val destUri = Uri.fromFile(destFile)
            val options = UCrop.Options()
            options.setToolbarTitle("Crop for AI")
            options.setFreeStyleCropEnabled(true)
            val uCrop = UCrop.of(sourceUri, destUri).withOptions(options)
            cropImage.launch(uCrop.getIntent(requireContext()))
        } catch (e: Exception) {
            Log.e(TAG, "Error starting crop", e)
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

            // UPDATED: Show Dialog
            binding.saveDialog.visibility = View.VISIBLE

            cameraExecutor.execute { detector?.detect(bitmap) }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading gallery image", e)
        }
    }

    private fun restartCameraPreview() {
        binding.imagePreview.visibility = View.GONE
        binding.viewFinder.visibility = View.VISIBLE
        // UPDATED: Hide Dialog
        binding.saveDialog.visibility = View.GONE

        binding.overlay.setCameraMode()
        binding.fab.setImageResource(android.R.drawable.ic_media_pause)
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
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val rotation = view?.display?.rotation ?: android.view.Surface.ROTATION_0

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
            )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) {
                    postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true
            )

            lastBitmap = rotatedBitmap
            detector?.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                viewLifecycleOwner, cameraSelector, preview, imageAnalyzer
            )
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun saveCurrentDetection() {
        val bitmapToSave = lastBitmap ?: return
        val resultsToSave = lastResults

        // 1. Get Location
        var currentLat = 0.0
        var currentLng = 0.0
        var placeName = "Location not available"

        try {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                if (lastKnownLocation != null) {
                    currentLat = lastKnownLocation.latitude
                    currentLng = lastKnownLocation.longitude

                    // 2. Reverse Geocode to get Name
                    try {
                        val geocoder = Geocoder(requireContext(), Locale.getDefault())
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocation(currentLat, currentLng, 1)
                        if (!addresses.isNullOrEmpty()) {
                            val address = addresses[0]
                            val locality = address.locality ?: address.subAdminArea ?: ""
                            val state = address.adminArea ?: ""
                            placeName = if (locality.isNotEmpty()) "$locality, $state" else state
                            if (placeName.isEmpty() || placeName == ", ") {
                                placeName = address.getAddressLine(0)
                            }
                        } else {
                            placeName = "Lat: %.4f, Lng: %.4f".format(currentLat, currentLng)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Geocoder failed", e)
                        placeName = "Lat: %.4f, Lng: %.4f".format(currentLat, currentLng)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location error", e)
        }

        try {
            // 3. Draw Bounding Boxes
            val mutableBitmap = bitmapToSave.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableBitmap)

            val boxPaint = Paint().apply {
                color = ContextCompat.getColor(requireContext(), R.color.bounding_box_color)
                style = Paint.Style.STROKE
                strokeWidth = 8f
            }
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 40f
                style = Paint.Style.FILL
            }
            val textBgPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }

            resultsToSave.forEach { box ->
                val left = box.x1 * mutableBitmap.width
                val top = box.y1 * mutableBitmap.height
                val right = box.x2 * mutableBitmap.width
                val bottom = box.y2 * mutableBitmap.height

                canvas.drawRect(left, top, right, bottom, boxPaint)
                val text = "${box.clsName} ${String.format("%.2f", box.cnf)}"
                val bounds = Rect()
                textPaint.getTextBounds(text, 0, text.length, bounds)

                canvas.drawRect(left, top, left + bounds.width() + 16, top + bounds.height() + 16, textBgPaint)
                canvas.drawText(text, left, top + bounds.height(), textPaint)
            }

            // 4. Save Image
            val filename = "fish_detect_${System.currentTimeMillis()}.jpg"
            val file = File(requireContext().filesDir, filename)
            val out = FileOutputStream(file)
            mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush()
            out.close()

            // 5. Insert to DB with Place Name
            val fishCounts = resultsToSave.groupBy { it.clsName }.map { "${it.key}: ${it.value.size}" }.joinToString(", ")
            val details = "Total: ${resultsToSave.size}, Conf: ${resultsToSave.map { String.format("%.2f", it.cnf) }}"

            dbHelper.insertDetection(
                timestamp = System.currentTimeMillis(),
                imagePath = file.absolutePath,
                fishCount = fishCounts.ifEmpty { "None" },
                details = details,
                lat = currentLat,
                lng = currentLng,
                placeName = placeName
            )

            val msg = if (currentLat != 0.0) "Saved at $placeName!" else "Saved (No GPS)"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "Error saving detection", e)
            Toast.makeText(context, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCamera()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
    }

    override fun onEmptyDetect() {
        lastResults = emptyList()
        activity?.runOnUiThread {
            if (_binding != null) {
                binding.overlay.clear()
                binding.totalCountLabel.text = "Total Detected: 0"
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
                binding.inferenceTime.text = "${inferenceTime}ms"
                binding.overlay.apply {
                    setResults(boundingBoxes)
                    invalidate()
                }
                val fishCounts = boundingBoxes.groupBy { it.clsName }
                    .map { (name, boxes) -> DetectionItem(name, boxes.size) }
                    .sortedByDescending { it.count }

                binding.totalCountLabel.text = "Total Detected: ${boundingBoxes.size}"
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
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).toTypedArray()
    }
}