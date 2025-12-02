package com.surendramaran.yolov8tflite.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.location.Geocoder
import android.location.LocationManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.surendramaran.yolov8tflite.R
import com.surendramaran.yolov8tflite.data.DatabaseHelper
import com.surendramaran.yolov8tflite.data.SyncWorker
import com.surendramaran.yolov8tflite.ml.BoundingBox
import com.surendramaran.yolov8tflite.ml.Detector
import com.surendramaran.yolov8tflite.ui.customview.OverlayView
import com.surendramaran.yolov8tflite.databinding.FragmentFreshnessBinding
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.surendramaran.yolov8tflite.ml.segmentation.utils.Utils

class FreshnessFragment : Fragment() {

    private var _binding: FragmentFreshnessBinding? = null
    private val binding get() = _binding!!
    private var detectorEyes: Detector? = null
    private var detectorGills: Detector? = null
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var cameraExecutor: ExecutorService

    // State Tracking
    private var isTargetingEyes = true // Tracks which button (Eyes vs Gills) was clicked
    private var tempImageUri: Uri? = null // For Camera Capture

    private var lastBitmapEyes: Bitmap? = null
    private var lastBitmapGills: Bitmap? = null
    private var lastEyesBoxes: List<BoundingBox> = emptyList()
    private var lastGillsBoxes: List<BoundingBox> = emptyList()
    private var eyesScore: Float? = null
    private var gillsScore: Float? = null

    // 1. Gallery Launcher
    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { startCrop(it) }
    }

    // 2. Camera Launcher
    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            tempImageUri?.let { startCrop(it) }
        }
    }

    // 3. Crop Launcher & Result Handler
    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let { processFinalImage(it) }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            Toast.makeText(context, getString(R.string.crop_error), Toast.LENGTH_SHORT).show()
        }
    }

    // 4. Permission Launcher
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) launchCamera()
        else Toast.makeText(context, getString(R.string.camera_permission_needed), Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFreshnessBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dbHelper = DatabaseHelper(requireContext())
        cameraExecutor = Executors.newSingleThreadExecutor()

        initDetectors()
        setupButtons()
        setupInstructionVideos()
    }

    private fun initDetectors() {
        cameraExecutor.execute {
            context?.let { safeContext ->
                // 1. EYES DETECTOR
                detectorEyes = Detector(safeContext, "eyes_model.tflite", "eyes_labels.txt", object : Detector.DetectorListener {
                    override fun onEmptyDetect() {
                        lastEyesBoxes = emptyList()
                        updateOverlay(true, emptyList())
                    }
                    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
                        // FIX: Filter to keep ONLY the single best detection
                        val bestBox = boundingBoxes.maxByOrNull { it.cnf }
                        val filteredList = if (bestBox != null) listOf(bestBox) else emptyList()

                        // Update global state with filtered list
                        lastEyesBoxes = filteredList
                        updateOverlay(true, filteredList)
                    }
                })

                // 2. GILLS DETECTOR
                detectorGills = Detector(safeContext, "gills_model.tflite", "gills_labels.txt", object : Detector.DetectorListener {
                    override fun onEmptyDetect() {
                        lastGillsBoxes = emptyList()
                        updateOverlay(false, emptyList())
                    }
                    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
                        // FIX: Filter to keep ONLY the single best detection
                        val bestBox = boundingBoxes.maxByOrNull { it.cnf }
                        val filteredList = if (bestBox != null) listOf(bestBox) else emptyList()

                        // Update global state with filtered list
                        lastGillsBoxes = filteredList
                        updateOverlay(false, filteredList)
                    }
                })
            }
        }
    }

    private fun setupInstructionVideos() {
        try {
            // Setup Eyes Video
            val eyesUri = Uri.parse("android.resource://" + requireContext().packageName + "/" + R.raw.eyes_instruction)
            binding.videoInstructionsEyes.setVideoURI(eyesUri)
            binding.videoInstructionsEyes.setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                if (lastBitmapEyes == null) binding.videoInstructionsEyes.start()
            }

            // Setup Gills Video
            val gillsUri = Uri.parse("android.resource://" + requireContext().packageName + "/" + R.raw.gills_instruction)
            binding.videoInstructionsGills.setVideoURI(gillsUri)
            binding.videoInstructionsGills.setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                if (lastBitmapGills == null) binding.videoInstructionsGills.start()
            }

        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onResume() {
        super.onResume()
        if (lastBitmapEyes == null) binding.videoInstructionsEyes.start()
        if (lastBitmapGills == null) binding.videoInstructionsGills.start()
    }

    private fun setupButtons() {
        // Eyes Controls
        binding.btnCameraEyes.setOnClickListener {
            isTargetingEyes = true
            checkPermissionAndLaunchCamera()
        }
        binding.btnGalleryEyes.setOnClickListener {
            isTargetingEyes = true
            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        // Gills Controls
        binding.btnCameraGills.setOnClickListener {
            isTargetingEyes = false
            checkPermissionAndLaunchCamera()
        }
        binding.btnGalleryGills.setOnClickListener {
            isTargetingEyes = false
            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        // Save Controls
        binding.btnSaveResult.setOnClickListener { binding.saveDialog.visibility = View.VISIBLE }
        binding.btnDialogDiscard.setOnClickListener { binding.saveDialog.visibility = View.GONE }
        binding.btnDialogSave.setOnClickListener { saveFreshnessLog(); binding.saveDialog.visibility = View.GONE }
    }

    private fun checkPermissionAndLaunchCamera() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        try {
            val tmpFile = File.createTempFile("freshness_temp_", ".jpg", requireContext().cacheDir).apply {
                createNewFile()
                deleteOnExit()
            }
            tempImageUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", tmpFile)
            takePictureLauncher.launch(tempImageUri)
        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.error_starting_camera), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCrop(sourceUri: Uri) {
        val destFileName = "crop_${System.currentTimeMillis()}.jpg"
        val destFile = File(requireContext().cacheDir, destFileName)
        val options = UCrop.Options().apply {
            setCompressionQuality(90)
            setToolbarTitle(if (isTargetingEyes) getString(R.string.crop_eyes) else getString(R.string.crop_gills))
            setFreeStyleCropEnabled(true)
        }
        cropImage.launch(UCrop.of(sourceUri, Uri.fromFile(destFile)).withOptions(options).getIntent(requireContext()))
    }

    private fun processFinalImage(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            var bitmap = BitmapFactory.decodeStream(inputStream)

            // FIX: Rotate image using Utils to correct orientation
            bitmap = Utils.rotateImageIfRequired(requireContext(), bitmap, uri)

            if (isTargetingEyes) {
                // SAFETY CHECK
                if (detectorEyes == null) {
                    Toast.makeText(context, "AI is initializing, please wait...", Toast.LENGTH_SHORT).show()
                    return
                }
                lastBitmapEyes = bitmap
                binding.imgEyes.setImageBitmap(bitmap)
                binding.imgEyes.visibility = View.VISIBLE
                binding.videoInstructionsEyes.visibility = View.GONE
                binding.overlayEyes.setImageDimensions(bitmap.width, bitmap.height)

                // Show Loading
                binding.pbEyesLoading.visibility = View.VISIBLE

                cameraExecutor.execute { detectorEyes?.detect(bitmap) }
            } else {
                if (detectorGills == null) {
                    Toast.makeText(context, "AI is initializing, please wait...", Toast.LENGTH_SHORT).show()
                    return
                }
                lastBitmapGills = bitmap
                binding.imgGills.setImageBitmap(bitmap)
                binding.imgGills.visibility = View.VISIBLE
                binding.videoInstructionsGills.visibility = View.GONE
                binding.overlayGills.setImageDimensions(bitmap.width, bitmap.height)

                // Show Loading
                binding.pbGillsLoading.visibility = View.VISIBLE

                cameraExecutor.execute { detectorGills?.detect(bitmap) }
            }
        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.error_loading_gallery_image), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateOverlay(isEyes: Boolean, boxes: List<BoundingBox>) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread

            val overlay = if (isEyes) binding.overlayEyes else binding.overlayGills
            val txtResult = if (isEyes) binding.txtResultEyes else binding.txtResultGills

            // Hide Loading
            val progressBar = if (isEyes) binding.pbEyesLoading else binding.pbGillsLoading
            progressBar.visibility = View.GONE

            // FIX: Filter to keep only the single best detection
            val bestBox = boxes.maxByOrNull { it.cnf }
            val singleBoxList = if (bestBox != null) listOf(bestBox) else emptyList()

            // Update overlay with ONLY the single best box
            overlay.setResults(singleBoxList)
            overlay.invalidate()

            if (bestBox != null) {
                val label = bestBox.clsName
                val conf = (bestBox.cnf * 100).toInt()
                txtResult.text = getString(R.string.detected_label, label, conf)

                // Simple Logic: If class contains "non" or "spoil", it's bad.
                val isNonFresh = label.lowercase().contains("non") || label.lowercase().contains("spoil")

                // Score: 1.0 (Super Fresh) to 0.0 (Rotten)
                val score = if (isNonFresh) 0.5f - (bestBox.cnf / 2.0f) else 0.5f + (bestBox.cnf / 2.0f)

                if (isEyes) eyesScore = score else gillsScore = score
                calculateFinalVerdict()
            } else {
                txtResult.text = getString(R.string.no_detection)
            }
        }
    }

    private fun calculateFinalVerdict() {
        val eScore = eyesScore
        val gScore = gillsScore

        if (eScore != null || gScore != null) {
            binding.btnSaveResult.visibility = View.VISIBLE

            val count = if (eScore != null && gScore != null) 2 else 1
            val sum = (eScore ?: 0f) + (gScore ?: 0f)
            val avg = sum / count
            val percent = (avg * 100).toInt()

            if (avg > 0.5) {
                binding.txtFinalResult.text = getString(R.string.fresh_percentage, percent)
                binding.cardFinalVerdict.setCardBackgroundColor(Color.parseColor("#2E7D32"))
            } else {
                binding.txtFinalResult.text = getString(R.string.not_fresh_percentage, percent)
                binding.cardFinalVerdict.setCardBackgroundColor(Color.parseColor("#C62828"))
            }
        }
    }

    private fun saveFreshnessLog() {
        val paths = mutableListOf<String>()
        val descriptions = mutableListOf<String>()
        val bitmapsWithBoxes = mutableListOf<Bitmap>()

        // 1. Prepare Eyes Data
        lastBitmapEyes?.let { bmp ->
            val drawnBmp = drawBoundingBoxes(bmp, lastEyesBoxes)
            bitmapsWithBoxes.add(drawnBmp)
            val score = eyesScore
            val status = if (score != null) { if (score > 0.5) getString(R.string.fresh) else getString(R.string.not_fresh) } else getString(R.string.not_analyzed)
            descriptions.add(getString(R.string.part_eyes, status))
        }

        // 2. Prepare Gills Data
        lastBitmapGills?.let { bmp ->
            val drawnBmp = drawBoundingBoxes(bmp, lastGillsBoxes)
            bitmapsWithBoxes.add(drawnBmp)
            val score = gillsScore
            val status = if (score != null) { if (score > 0.5) getString(R.string.fresh) else getString(R.string.not_fresh) } else getString(R.string.not_analyzed)
            descriptions.add(getString(R.string.part_gills, status))
        }

        if (bitmapsWithBoxes.isEmpty()) return

        try {
            // 3. Save Images to Internal Storage
            bitmapsWithBoxes.forEachIndexed { index, bitmap ->
                val filename = "fresh_${System.currentTimeMillis()}_$index.jpg"
                val file = File(requireContext().filesDir, filename)
                val out = FileOutputStream(file)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
                out.close()
                paths.add(file.absolutePath)
            }
            val combinedPaths = paths.joinToString("|")
            val combinedDetails = descriptions.joinToString(";;;")
            val title = binding.txtFinalResult.text.toString()

            // 4. Fetch Location (NEW LOGIC)
            var currentLat = 0.0
            var currentLng = 0.0
            var placeName = getString(R.string.location_not_available)

            try {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                    if (lastKnownLocation != null) {
                        currentLat = lastKnownLocation.latitude
                        currentLng = lastKnownLocation.longitude

                        // Get Place Name from Coordinates
                        val geocoder = Geocoder(requireContext(), Locale.getDefault())
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocation(currentLat, currentLng, 1)
                        if (!addresses.isNullOrEmpty()) {
                            placeName = addresses[0].locality ?: addresses[0].getAddressLine(0)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace() // Log error but continue saving
            }

            // 5. Insert into Database
            dbHelper.insertLog(
                System.currentTimeMillis(),
                combinedPaths,
                title,
                combinedDetails,
                currentLat,
                currentLng,
                placeName,
                DatabaseHelper.TYPE_FRESHNESS
            )

            Toast.makeText(context, getString(R.string.saved), Toast.LENGTH_SHORT).show()

            // 6. Trigger Cloud Sync
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val syncRequest = OneTimeWorkRequest.Builder(SyncWorker::class.java).setConstraints(constraints).build()
            WorkManager.getInstance(requireContext()).enqueueUniqueWork("HistoryUploadWork", ExistingWorkPolicy.APPEND, syncRequest)

        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.error_saving, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun drawBoundingBoxes(bitmap: Bitmap, boxes: List<BoundingBox>): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val boxPaint = Paint().apply { color = ContextCompat.getColor(requireContext(), R.color.bounding_box_color); style = Paint.Style.STROKE; strokeWidth = 8f }
        val textPaint = Paint().apply { color = Color.WHITE; textSize = 40f; style = Paint.Style.FILL }
        boxes.forEach { box ->
            val left = box.x1 * mutableBitmap.width; val top = box.y1 * mutableBitmap.height; val right = box.x2 * mutableBitmap.width; val bottom = box.y2 * mutableBitmap.height
            canvas.drawRect(left, top, right, bottom, boxPaint)
            canvas.drawText("${box.clsName}", left, top, textPaint)
        }
        return mutableBitmap
    }

    private fun checkPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) true
        else { requestPermissionLauncher.launch(Manifest.permission.CAMERA); false }
    }

    override fun onDestroyView() {
        // Removed: isSafeToDetect = false
        super.onDestroyView()

        // Safely shut down background threads
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.execute {
                detectorEyes?.close()
                detectorEyes = null
                detectorGills?.close()
                detectorGills = null
            }
            cameraExecutor.shutdown()
        }

        _binding = null
    }
    data class Quad(val btn: com.google.android.material.floatingactionbutton.FloatingActionButton, val preview: androidx.camera.view.PreviewView, val img: android.widget.ImageView, val overlay: OverlayView)
}