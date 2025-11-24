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

    // --- 1. GALLERY LAUNCHER (Sends to Crop) ---
    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            startCrop(it)
        }
    }

    // --- 2. CROP LAUNCHER (Receives Cropped Image) ---
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
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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
                cameraExecutor.submit {
                    detector?.restart(isGpu = isChecked)
                }
                if (isChecked) {
                    buttonView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.orange))
                } else {
                    buttonView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.gray))
                }
            }

            // --- GALLERY BUTTON CLICK ---
            btnGallery.setOnClickListener {
                galleryLauncher.launch("image/*")
            }

            // --- PAUSE/PLAY BUTTON ---
            fab.setOnClickListener {
                // If we are showing a static gallery image, the "Play" button should restart the camera
                if (binding.imagePreview.visibility == View.VISIBLE) {
                    restartCameraPreview()
                } else {
                    // Normal Camera Toggle
                    if (isCameraRunning) {
                        cameraProvider?.unbindAll()
                        binding.fab.setImageResource(android.R.drawable.ic_media_play)
                        isCameraRunning = false
                    } else {
                        startCamera()
                        binding.fab.setImageResource(android.R.drawable.ic_media_pause)
                        isCameraRunning = true
                    }
                }
            }
        }
    }

    // --- HELPER: START CROP ---
    private fun startCrop(sourceUri: Uri) {
        try {
            // Create unique temp file
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

    // --- LOGIC TO HANDLE GALLERY IMAGE ---
    private fun processGalleryImage(uri: Uri) {
        try {
            // 1. Stop Camera
            cameraProvider?.unbindAll()
            isCameraRunning = false
            binding.fab.setImageResource(android.R.drawable.ic_media_play)

            // 2. Load Bitmap
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)

            // 3. Show Image View & Hide Camera View
            binding.viewFinder.visibility = View.INVISIBLE
            binding.imagePreview.visibility = View.VISIBLE
            binding.imagePreview.setImageBitmap(bitmap)

            // 4. Run Detection on Background Thread
            cameraExecutor.execute {
                detector?.detect(bitmap)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading gallery image", e)
        }
    }

    // --- LOGIC TO RESTART CAMERA ---
    private fun restartCameraPreview() {
        binding.imagePreview.visibility = View.GONE
        binding.viewFinder.visibility = View.VISIBLE
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

    // --- INTERFACE IMPLEMENTATION ---
    override fun onEmptyDetect() {
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
        activity?.runOnUiThread {
            if (_binding != null) {
                binding.inferenceTime.text = "${inferenceTime}ms"
                binding.overlay.apply {
                    setResults(boundingBoxes)
                    invalidate()
                }

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

    companion object {
        private const val TAG = "Camera"
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).toTypedArray()
    }
}