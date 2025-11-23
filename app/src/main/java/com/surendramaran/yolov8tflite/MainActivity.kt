package com.surendramaran.yolov8tflite

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), Detector.DetectorListener {
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null

    private lateinit var cameraExecutor: ExecutorService
    private var isCameraRunning = true
    private var isImageMode = false

    private lateinit var detectionAdapter: DetectionAdapter

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImageSelection(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
        }

        setupRecyclerView()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        bindListeners()
    }

    private fun setupRecyclerView() {
        detectionAdapter = DetectionAdapter()
        binding.detectionList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = detectionAdapter
        }
    }

    private fun bindListeners() {
        binding.apply {
            isGpu.setOnCheckedChangeListener { buttonView, isChecked ->
                cameraExecutor.submit {
                    detector?.restart(isGpu = isChecked)
                }
                if (isChecked) {
                    buttonView.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.orange))
                } else {
                    buttonView.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.gray))
                }
            }

            fab.setOnClickListener {
                if (isImageMode) {
                    // Switch back to camera mode
                    switchToCameraMode()
                } else {
                    // Pause/Play camera
                    if (isCameraRunning) {
                        cameraProvider?.unbindAll()
                        binding.fab.setImageResource(android.R.drawable.ic_media_play)
                    } else {
                        bindCameraUseCases()
                        binding.fab.setImageResource(android.R.drawable.ic_media_pause)
                    }
                    isCameraRunning = !isCameraRunning
                }
            }

            fabGallery.setOnClickListener {
                pickImageLauncher.launch("image/*")
            }
        }
    }

    private fun handleImageSelection(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap != null) {
                switchToImageMode(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image", e)
        }
    }

    private fun switchToImageMode(bitmap: Bitmap) {
        isImageMode = true

        // Stop camera
        cameraProvider?.unbindAll()

        // Show image views, hide camera views
        binding.viewFinder.visibility = View.GONE
        binding.overlay.visibility = View.GONE
        binding.imageView.visibility = View.VISIBLE
        binding.overlayImage.visibility = View.VISIBLE

        // Update FAB icon to show back to camera option
        binding.fab.setImageResource(android.R.drawable.ic_menu_revert)

        // Display the image
        binding.imageView.setImageBitmap(bitmap)

        // Set image dimensions for overlay
        binding.overlayImage.setImageDimensions(bitmap.width, bitmap.height)

        // Run detection on the image
        cameraExecutor.execute {
            detector?.detect(bitmap)
        }
    }

    private fun switchToCameraMode() {
        isImageMode = false

        // Hide image views, show camera views
        binding.imageView.visibility = View.GONE
        binding.overlayImage.visibility = View.GONE
        binding.viewFinder.visibility = View.VISIBLE
        binding.overlay.visibility = View.VISIBLE

        // Reset overlay to camera mode
        binding.overlay.setCameraMode()

        // Update FAB icon
        binding.fab.setImageResource(android.R.drawable.ic_media_pause)

        // Restart camera
        isCameraRunning = true
        bindCameraUseCases()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider  = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview =  Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer =
                Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            detector?.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch(exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()){
            if (!isImageMode) {
                startCamera()
            }
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf (
            Manifest.permission.CAMERA
        ).apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            if (isImageMode) {
                binding.overlayImage.clear()
            } else {
                binding.overlay.clear()
            }
            binding.totalCountLabel.text = "Total Detected: 0"
            binding.noDetectionText.visibility = View.VISIBLE
            binding.detectionList.visibility = View.GONE
            detectionAdapter.updateDetections(emptyList())
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"

            if (isImageMode) {
                binding.overlayImage.apply {
                    setResults(boundingBoxes)
                    invalidate()
                }
            } else {
                binding.overlay.apply {
                    setResults(boundingBoxes)
                    invalidate()
                }
            }

            // Calculate fish counts
            val fishCounts = boundingBoxes
                .groupBy { it.clsName }
                .map { (name, boxes) -> DetectionItem(name, boxes.size) }
                .sortedByDescending { it.count }

            val totalCount = boundingBoxes.size

            binding.totalCountLabel.text = "Total Detected: $totalCount"

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