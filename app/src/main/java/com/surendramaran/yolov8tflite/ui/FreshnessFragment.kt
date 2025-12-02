package com.surendramaran.yolov8tflite.ui

import android.Manifest
import android.R
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
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.surendramaran.yolov8tflite.data.DatabaseHelper
import com.surendramaran.yolov8tflite.data.SyncWorker
import com.surendramaran.yolov8tflite.databinding.FragmentFreshnessBinding
import com.surendramaran.yolov8tflite.ml.BoundingBox
import com.surendramaran.yolov8tflite.ml.Detector
import com.surendramaran.yolov8tflite.ui.customview.OverlayView
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FreshnessFragment : Fragment() {

    // ... existing members ...
    private var _binding: FragmentFreshnessBinding? = null
    private val binding get() = _binding!!
    private var detectorEyes: Detector? = null
    private var detectorGills: Detector? = null
    private lateinit var dbHelper: DatabaseHelper
    private var isEyesCameraActive = false
    private var isGillsCameraActive = false
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var isSafeToDetect = true
    private var lastBitmapEyes: Bitmap? = null
    private var lastBitmapGills: Bitmap? = null
    private var lastEyesBoxes: List<BoundingBox> = emptyList()
    private var lastGillsBoxes: List<BoundingBox> = emptyList()
    private var eyesScore: Float? = null
    private var gillsScore: Float? = null
    private var isGalleryForEyes = true

    // ... Activity Launchers (galleryLauncher, cropImage, requestPermissionLauncher) ...
    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { startCrop(it) } }
    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let { processGalleryImage(it) }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            Toast.makeText(context, "Crop Error", Toast.LENGTH_SHORT).show()
        }
    }
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted && _binding != null) binding.viewFinderEyes.post { startCamera(isEyes = true) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFreshnessBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dbHelper = DatabaseHelper(requireContext())
        isSafeToDetect = true
        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            context?.let { safeContext ->
                detectorEyes = Detector(
                    safeContext,
                    "eyes_model.tflite",
                    "eyes_labels.txt",
                    object : Detector.DetectorListener {
                        override fun onEmptyDetect() {
                            lastEyesBoxes = emptyList(); if (isSafeToDetect) updateOverlay(
                                true,
                                emptyList()
                            )
                        }

                        override fun onDetect(
                            boundingBoxes: List<BoundingBox>,
                            inferenceTime: Long
                        ) {
                            lastEyesBoxes = boundingBoxes; if (isSafeToDetect) updateOverlay(
                                true,
                                boundingBoxes
                            )
                        }
                    })
                detectorGills = Detector(
                    safeContext,
                    "gills_model.tflite",
                    "gills_labels.txt",
                    object : Detector.DetectorListener {
                        override fun onEmptyDetect() {
                            lastGillsBoxes = emptyList(); if (isSafeToDetect) updateOverlay(
                                false,
                                emptyList()
                            )
                        }

                        override fun onDetect(
                            boundingBoxes: List<BoundingBox>,
                            inferenceTime: Long
                        ) {
                            lastGillsBoxes = boundingBoxes; if (isSafeToDetect) updateOverlay(
                                false,
                                boundingBoxes
                            )
                        }
                    })
            }
        }
        setupButtons()
        if (checkPermission()) binding.viewFinderEyes.post { if (_binding != null) startCamera(isEyes = true) }
    }

    private fun setupButtons() {
        binding.btnPlayPauseEyes.setOnClickListener { if (checkPermission()) { if (isEyesCameraActive) pauseCamera(true) else startCamera(true) } }
        binding.btnGalleryEyes.setOnClickListener { isGalleryForEyes = true; galleryLauncher.launch("image/*") }
        binding.btnPlayPauseGills.setOnClickListener { if (checkPermission()) { if (isGillsCameraActive) pauseCamera(false) else startCamera(false) } }
        binding.btnGalleryGills.setOnClickListener { isGalleryForEyes = false; galleryLauncher.launch("image/*") }
        binding.btnSaveResult.setOnClickListener { binding.saveDialog.visibility = View.VISIBLE }
        binding.btnDialogDiscard.setOnClickListener { binding.saveDialog.visibility = View.GONE }
        binding.btnDialogSave.setOnClickListener { saveFreshnessLog(); binding.saveDialog.visibility = View.GONE }
    }

    // ... Camera & UI methods (startCamera, stopCamera, pauseCamera, bindCameraUseCases, cropBitmapToView, updateOverlay, processGalleryImage, startCrop, calculateFinalVerdict) ...
    // Keeping previous implementations as they were mostly correct and are boiler-plate heavy.
    // NOTE: Ensure you include the methods from previous successful builds.
    // I'll include `calculateFinalVerdict` and `startCamera` structure.

    private fun startCamera(isEyes: Boolean) {
        if (_binding == null) return
        stopCamera()
        val (btn, preview, img, overlay) = if (isEyes) Quad(binding.btnPlayPauseEyes, binding.viewFinderEyes, binding.imgEyes, binding.overlayEyes)
        else Quad(binding.btnPlayPauseGills, binding.viewFinderGills, binding.imgGills, binding.overlayGills)
        btn.setImageResource(R.drawable.ic_media_pause)
        preview.visibility = View.VISIBLE
        img.visibility = View.GONE
        overlay.setCameraMode()
        if (isEyes) isEyesCameraActive = true else isGillsCameraActive = true
        val context = context ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            if (!isAdded || _binding == null) return@addListener
            try {
                cameraProvider = cameraProviderFuture.get()
                val previewView = if (isEyes) binding.viewFinderEyes else binding.viewFinderGills
                bindCameraUseCases(isEyes, previewView.width, previewView.height)
            } catch (e: Exception) {}
        }, ContextCompat.getMainExecutor(context))
    }

    private fun stopCamera() {
        isEyesCameraActive = false; isGillsCameraActive = false
        try { cameraProvider?.unbindAll() } catch (e: Exception) {}
        binding.btnPlayPauseEyes.setImageResource(R.drawable.ic_media_play)
        binding.btnPlayPauseGills.setImageResource(R.drawable.ic_media_play)
    }

    private fun pauseCamera(isEyes: Boolean) {
        stopCamera()
        val (btn, preview, img, overlay) = if (isEyes) Quad(binding.btnPlayPauseEyes, binding.viewFinderEyes, binding.imgEyes, binding.overlayEyes)
        else Quad(binding.btnPlayPauseGills, binding.viewFinderGills, binding.imgGills, binding.overlayGills)
        btn.setImageResource(R.drawable.ic_media_play)
        preview.visibility = View.INVISIBLE
        img.visibility = View.VISIBLE
        val bmp = if (isEyes) lastBitmapEyes else lastBitmapGills
        if (bmp != null) { img.setImageBitmap(bmp); overlay.setImageDimensions(bmp.width, bmp.height) }
    }

    private fun bindCameraUseCases(isEyes: Boolean, viewWidth: Int, viewHeight: Int) {
        val cameraProvider = cameraProvider ?: return
        if (_binding == null) return
        val previewView = if (isEyes) binding.viewFinderEyes else binding.viewFinderGills
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setTargetRotation(rotation).build()
        val imageAnalyzer = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setBackpressureStrategy(
            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setTargetRotation(rotation).setOutputImageFormat(
            ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
        imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->
            if (!isSafeToDetect) { imageProxy.close(); return@setAnalyzer }
            val bitmapBuffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
            val rotatedBitmap = Bitmap.createBitmap(bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)
            val croppedBitmap = cropBitmapToView(rotatedBitmap, viewWidth, viewHeight)
            if (isEyes) { lastBitmapEyes = croppedBitmap; detectorEyes?.detect(croppedBitmap) } else { lastBitmapGills = croppedBitmap; detectorGills?.detect(croppedBitmap) }
        }
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
        preview.setSurfaceProvider(previewView.surfaceProvider)
    }

    private fun cropBitmapToView(bitmap: Bitmap, viewWidth: Int, viewHeight: Int): Bitmap {
        if (viewWidth == 0 || viewHeight == 0) return bitmap
        val bitmapRatio = bitmap.width.toFloat() / bitmap.height
        val viewRatio = viewWidth.toFloat() / viewHeight
        var cropX = 0; var cropY = 0; var cropWidth = bitmap.width; var cropHeight = bitmap.height
        if (bitmapRatio > viewRatio) { cropHeight = bitmap.height; cropWidth = (bitmap.height * viewRatio).toInt(); cropX = (bitmap.width - cropWidth) / 2 }
        else { cropWidth = bitmap.width; cropHeight = (bitmap.width / viewRatio).toInt(); cropY = (bitmap.height - cropHeight) / 2 }
        if (cropWidth <= 0) cropWidth = 1; if (cropHeight <= 0) cropHeight = 1
        return Bitmap.createBitmap(bitmap, cropX.coerceAtLeast(0), cropY.coerceAtLeast(0), cropWidth, cropHeight)
    }

    private fun updateOverlay(isEyes: Boolean, boxes: List<BoundingBox>) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            val overlay = if (isEyes) binding.overlayEyes else binding.overlayGills
            val txtResult = if (isEyes) binding.txtResultEyes else binding.txtResultGills
            overlay.setResults(boxes); overlay.invalidate()
            if (boxes.isNotEmpty()) {
                val topBox = boxes.maxByOrNull { it.cnf }
                if (topBox != null) {
                    val label = topBox.clsName
                    val conf = (topBox.cnf * 100).toInt()
                    txtResult.text = "Detected: $label ($conf%)"
                    val isNonFresh = label.lowercase().contains("non") || label.lowercase().contains("spoil")
                    val score = if (isNonFresh) 0.5f - (topBox.cnf / 2.0f) else 0.5f + (topBox.cnf / 2.0f)
                    if (isEyes) eyesScore = score else gillsScore = score
                    calculateFinalVerdict()
                }
            } else { txtResult.text = "No detection" }
        }
    }

    private fun processGalleryImage(uri: Uri) {
        if (_binding == null) return
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            stopCamera()
            if (isGalleryForEyes) { lastBitmapEyes = bitmap; binding.imgEyes.setImageBitmap(bitmap); binding.imgEyes.visibility = View.VISIBLE; binding.viewFinderEyes.visibility = View.INVISIBLE; binding.overlayEyes.setImageDimensions(bitmap.width, bitmap.height); cameraExecutor.execute { detectorEyes?.detect(bitmap) } }
            else { lastBitmapGills = bitmap; binding.imgGills.setImageBitmap(bitmap); binding.imgGills.visibility = View.VISIBLE; binding.viewFinderGills.visibility = View.INVISIBLE; binding.overlayGills.setImageDimensions(bitmap.width, bitmap.height); cameraExecutor.execute { detectorGills?.detect(bitmap) } }
        } catch (e: Exception) {}
    }

    private fun startCrop(sourceUri: Uri) {
        val destFile = File(requireContext().cacheDir, "crop_${System.currentTimeMillis()}.jpg")
        val options = UCrop.Options().apply { setFreeStyleCropEnabled(true) }
        cropImage.launch(UCrop.of(sourceUri, Uri.fromFile(destFile)).withOptions(options).getIntent(requireContext()))
    }

    private fun calculateFinalVerdict() {
        if (_binding == null) return
        val eScore = eyesScore; val gScore = gillsScore
        if (eScore != null || gScore != null) {
            binding.btnSaveResult.visibility = View.VISIBLE
            val count = if (eScore != null && gScore != null) 2 else 1
            val sum = (eScore ?: 0f) + (gScore ?: 0f)
            val avg = sum / count
            val percent = (avg * 100).toInt()
            if (avg > 0.5) { binding.txtFinalResult.text = "FRESH ($percent%)"; binding.cardFinalVerdict.setCardBackgroundColor(
                Color.parseColor("#2E7D32")) }
            else { binding.txtFinalResult.text = "NOT FRESH ($percent%)"; binding.cardFinalVerdict.setCardBackgroundColor(
                Color.parseColor("#C62828")) }
        }
    }

    private fun saveFreshnessLog() {
        val paths = mutableListOf<String>()
        val descriptions = mutableListOf<String>()
        val bitmapsWithBoxes = mutableListOf<Bitmap>()

        lastBitmapEyes?.let { bmp ->
            val drawnBmp = drawBoundingBoxes(bmp, lastEyesBoxes)
            bitmapsWithBoxes.add(drawnBmp)
            val score = eyesScore
            val status = if (score != null) { if (score > 0.5) "Fresh (${(score * 100).toInt()}%)" else "Not Fresh (${(score * 100).toInt()}%)" } else "Not Analyzed"
            descriptions.add("Part: EYES\nStatus: $status")
        }

        lastBitmapGills?.let { bmp ->
            val drawnBmp = drawBoundingBoxes(bmp, lastGillsBoxes)
            bitmapsWithBoxes.add(drawnBmp)
            val score = gillsScore
            val status = if (score != null) { if (score > 0.5) "Fresh (${(score * 100).toInt()}%)" else "Not Fresh (${(score * 100).toInt()}%)" } else "Not Analyzed"
            descriptions.add("Part: GILLS\nStatus: $status")
        }

        if (bitmapsWithBoxes.isEmpty()) { Toast.makeText(context, "No images to save", Toast.LENGTH_SHORT).show(); return }

        try {
            bitmapsWithBoxes.forEachIndexed { index, bitmap ->
                val filename = "fresh_${System.currentTimeMillis()}_$index.jpg"
                val file = File(requireContext().filesDir, filename)
                val out = FileOutputStream(file)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out); out.flush(); out.close()
                paths.add(file.absolutePath)
            }
            val combinedPaths = paths.joinToString("|")
            val combinedDetails = descriptions.joinToString(";;;")
            val eScore = eyesScore; val gScore = gillsScore
            val count = if (eScore != null && gScore != null) 2 else 1
            val sum = (eScore ?: 0f) + (gScore ?: 0f)
            val avg = sum / count
            val percent = (avg * 100).toInt()
            val verdict = if (avg > 0.5) "FRESH" else "NOT FRESH"
            val title = "$verdict ($percent%)"

            var currentLat = 0.0; var currentLng = 0.0; var placeName = "Location not available"
            try {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: locationManager.getLastKnownLocation(
                        LocationManager.NETWORK_PROVIDER)
                    if (lastKnownLocation != null) {
                        currentLat = lastKnownLocation.latitude; currentLng = lastKnownLocation.longitude
                        val geocoder = Geocoder(requireContext(), Locale.getDefault())
                        @Suppress("DEPRECATION") val addresses = geocoder.getFromLocation(currentLat, currentLng, 1)
                        placeName = if (!addresses.isNullOrEmpty()) addresses[0].locality ?: addresses[0].getAddressLine(0) else "Lat: %.4f, Lng: %.4f".format(currentLat, currentLng)
                    }
                }
            } catch (e: Exception) {}

            dbHelper.insertLog(System.currentTimeMillis(), combinedPaths, title, combinedDetails, currentLat, currentLng, placeName, DatabaseHelper.Companion.TYPE_FRESHNESS)
            Toast.makeText(context, "Saved!", Toast.LENGTH_SHORT).show()

            // TRIGGER SYNC
            triggerBackgroundSync()

        } catch (e: Exception) { Toast.makeText(context, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    // Helper to trigger sync
    private fun triggerBackgroundSync() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val syncRequest = OneTimeWorkRequest.Builder(SyncWorker::class.java).setConstraints(constraints).setBackoffCriteria(
            BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(requireContext()).enqueueUniqueWork("HistoryUploadWork", ExistingWorkPolicy.APPEND, syncRequest)
    }

    private fun drawBoundingBoxes(bitmap: Bitmap, boxes: List<BoundingBox>): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val boxPaint = Paint().apply { color = ContextCompat.getColor(requireContext(), com.surendramaran.yolov8tflite.R.color.bounding_box_color); style = Paint.Style.STROKE; strokeWidth = 8f }
        val textPaint = Paint().apply { color = Color.WHITE; textSize = 40f; style = Paint.Style.FILL }
        val textBgPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        boxes.forEach { box ->
            val left = box.x1 * mutableBitmap.width; val top = box.y1 * mutableBitmap.height; val right = box.x2 * mutableBitmap.width; val bottom = box.y2 * mutableBitmap.height
            canvas.drawRect(left, top, right, bottom, boxPaint)
            val text = "${box.clsName} ${(box.cnf * 100).toInt()}%"
            val bounds = Rect(); textPaint.getTextBounds(text, 0, text.length, bounds)
            canvas.drawRect(left, top, left + bounds.width() + 16, top + bounds.height() + 16, textBgPaint)
            canvas.drawText(text, left, top + bounds.height(), textPaint)
        }
        return mutableBitmap
    }

    private fun checkPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) true
        else { requestPermissionLauncher.launch(Manifest.permission.CAMERA); false }
    }

    override fun onDestroyView() {
        isSafeToDetect = false
        stopCamera()
        super.onDestroyView()
        cameraExecutor.execute { detectorEyes?.close(); detectorEyes = null; detectorGills?.close(); detectorGills = null }
        cameraExecutor.shutdown()
        _binding = null
    }
    data class Quad(val btn: FloatingActionButton, val preview: PreviewView, val img: ImageView, val overlay: OverlayView)
}