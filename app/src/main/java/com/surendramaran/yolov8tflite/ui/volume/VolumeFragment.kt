package com.surendramaran.yolov8tflite.ui.volume

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.Geocoder
import android.location.LocationManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.surendramaran.yolov8tflite.R
import com.surendramaran.yolov8tflite.data.Constants
import com.surendramaran.yolov8tflite.data.DatabaseHelper
import com.surendramaran.yolov8tflite.data.SyncWorker
import com.surendramaran.yolov8tflite.databinding.DialogSettingsBinding
import com.surendramaran.yolov8tflite.databinding.FragmentVolumeBinding
import com.surendramaran.yolov8tflite.ml.BoundingBox
import com.surendramaran.yolov8tflite.ml.Detector
import com.surendramaran.yolov8tflite.ml.segmentation.AnalysisResult
import com.surendramaran.yolov8tflite.ml.segmentation.DrawImages
import com.surendramaran.yolov8tflite.ml.segmentation.InstanceSegmentation
import com.surendramaran.yolov8tflite.ml.segmentation.SegmentationResult
import com.surendramaran.yolov8tflite.ml.segmentation.Success
import com.surendramaran.yolov8tflite.ml.segmentation.ui.SettingsViewModel
import com.surendramaran.yolov8tflite.ml.segmentation.ui.ViewPagerAdapter
import com.surendramaran.yolov8tflite.ml.segmentation.utils.Utils
import com.surendramaran.yolov8tflite.ml.segmentation.utils.Utils.addCarouselEffect
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencv.android.OpenCVLoader
import org.opencv.aruco.Aruco
import org.opencv.aruco.DetectorParameters
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

class VolumeFragment : Fragment(), Detector.DetectorListener {

    private var _binding: FragmentVolumeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by activityViewModels()

    private var instanceSegmentation: InstanceSegmentation? = null // Fish Model
    private var coinSegmentation: InstanceSegmentation? = null     // Coin Model
    private var detector: Detector? = null
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var drawImages: DrawImages
    private lateinit var viewPagerAdapter: ViewPagerAdapter

    private var currentBitmap: Bitmap? = null
    private var currentScale: Float = 50.0f
    private var isMarkerDetected: Boolean = false

    private val segmentationMutex = Mutex()

    private var lastAnalysisResult: List<AnalysisResult>? = null
    private var currentPhotoUri: Uri? = null

    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let { uri ->
                val bitmap = Utils.getBitmapFromUri(requireContext(), uri) ?: return@let

                // Hide instruction video immediately when we have an image
                binding.instructionVideoView.visibility = View.GONE

                currentBitmap = bitmap
                currentScale = 50.0f
                isMarkerDetected = false

                if (viewModel.useCoinReference) {
                    detector?.detect(bitmap) // Starts chain
                } else {
                    val (markedBitmap, scale, found) = detectArUcoMarkers(bitmap)
                    currentBitmap = markedBitmap
                    currentScale = scale
                    isMarkerDetected = found
                    detector?.detect(markedBitmap)
                }
            }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            val error = UCrop.getError(result.data!!)
            toast("Crop error: ${error?.message}")
        }
    }

    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { startCrop(it) }
    }

    private val photoCapture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            currentPhotoUri?.let { uri -> startCrop(uri) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVolumeBinding.inflate(inflater, container, false)
        viewPagerAdapter = ViewPagerAdapter(mutableListOf())
        binding.viewpager.adapter = viewPagerAdapter
        binding.viewpager.addCarouselEffect()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dbHelper = DatabaseHelper(requireContext())
        OpenCVLoader.initDebug()

        // 1. Initialize Fish Seg Model (Type "Fish")
        instanceSegmentation = InstanceSegmentation(
            requireContext(),
            Constants.SEG_MODEL_PATH,
            null,
            "Fish",
            5
        ) { toast("Fish Error: $it") }

        // 2. Initialize Coin Seg Model (Type "Coin")
        coinSegmentation = InstanceSegmentation(
            requireContext(),
            Constants.COIN_MODEL_PATH,
            null,
            "Coin",
            5
        ) { toast("Coin Error: $it") }

        detector = Detector(requireContext(), Constants.MODEL_PATH, Constants.LABELS_PATH, this)
        drawImages = DrawImages(requireContext())

        setupListeners()
        setupInstructionVideo() // Initialize the video player
    }

    // NEW FUNCTION: Setup video to play and loop
    private fun setupInstructionVideo() {
        try {
            // Only play if we don't have an analysis yet
            if (currentBitmap == null) {
                val videoView = binding.instructionVideoView
                videoView.visibility = View.VISIBLE

                // Assuming the file is at app/src/main/res/raw/instruction_video.mp4
                val videoPath = "android.resource://" + requireContext().packageName + "/" + R.raw.instruction_video
                videoView.setVideoURI(Uri.parse(videoPath))

                videoView.setOnPreparedListener { mediaPlayer ->
                    mediaPlayer.isLooping = true
                    mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                }

                videoView.start()
            } else {
                binding.instructionVideoView.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.e("VolumeFragment", "Error setting up video", e)
        }
    }

    // NEW FUNCTION: Resume video on return if no image is selected
    override fun onResume() {
        super.onResume()
        if (currentBitmap == null) {
            binding.instructionVideoView.start()
        }
    }

    private fun setupListeners() {
        binding.btnCamera.setOnClickListener {
            val photoFile = Utils.createImageFile(requireContext())
            currentPhotoUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", photoFile)
            photoCapture.launch(currentPhotoUri)
        }
        binding.btnGallery.setOnClickListener {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnSave.setOnClickListener {
            if (currentBitmap != null) binding.saveDialog.visibility = View.VISIBLE
            else toast("No analysis to save")
        }
        binding.btnDialogDiscard.setOnClickListener { binding.saveDialog.visibility = View.GONE }
        binding.btnDialogSave.setOnClickListener {
            saveVolumeLog()
            binding.saveDialog.visibility = View.GONE
        }
        binding.ivSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun detectArUcoMarkers(bitmap: Bitmap): Triple<Bitmap, Float, Boolean> {
        val mat = Mat()
        org.opencv.android.Utils.bitmapToMat(bitmap, mat)
        val rgbMat = Mat()
        Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_RGBA2RGB)
        val grayMat = Mat()
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGBA2GRAY)
        val dictionary = Aruco.getPredefinedDictionary(Aruco.DICT_4X4_50)
        val corners = ArrayList<Mat>()
        val ids = Mat()
        val parameters = DetectorParameters.create()
        var detectedScale = 50.0f
        var markerFound = false

        try {
            Aruco.detectMarkers(grayMat, dictionary, corners, ids, parameters)
            if (ids.rows() > 0) {
                Scalar(0.0, 255.0, 0.0).let { green ->
                    Aruco.drawDetectedMarkers(rgbMat, corners, ids, green)
                }
                val c = corners[0]
                val xDiff = c.get(0, 0)[0] - c.get(0, 1)[0]
                val yDiff = c.get(0, 0)[1] - c.get(0, 1)[1]
                val widthPx = sqrt(xDiff.pow(2) + yDiff.pow(2)).toFloat()
                detectedScale = widthPx / 5.0f
                markerFound = true
            }
        } catch (e: Exception) { Log.e("VolumeFragment", "ArUco Error", e) }

        val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        org.opencv.android.Utils.matToBitmap(rgbMat, resultBitmap)
        mat.release(); rgbMat.release(); grayMat.release(); ids.release()
        corners.forEach { it.release() }
        return Triple(resultBitmap, detectedScale, markerFound)
    }

    private fun startCrop(sourceUri: Uri) {
        val destFile = File(requireContext().cacheDir, "crop_${System.currentTimeMillis()}.jpg")
        val options = UCrop.Options().apply {
            setToolbarTitle("Crop Fish")
            setFreeStyleCropEnabled(true)
            setCompressionQuality(90)
        }
        cropImage.launch(UCrop.of(sourceUri, Uri.fromFile(destFile)).withOptions(options).getIntent(requireContext()))
    }

    private fun runInstanceSegmentation(bitmap: Bitmap, speciesBoxes: List<BoundingBox>, defaultScale: Float) {
        requireActivity().runOnUiThread {
            binding.pbLoading.visibility = View.VISIBLE
            binding.tvNoFish.visibility = View.GONE
            binding.instructionVideoView.visibility = View.GONE // Ensure video is hidden
            binding.btnSave.visibility = View.GONE
            viewPagerAdapter.updateImages(emptyList())
        }

        lifecycleScope.launch(Dispatchers.Default) {
            segmentationMutex.withLock {
                var coinResults: List<SegmentationResult> = emptyList()
                var activeScale = defaultScale
                var fishSuccess: Success? = null

                if (viewModel.useCoinReference && coinSegmentation != null) {
                    try {
                        coinSegmentation?.invoke(
                            frame = bitmap,
                            smoothEdges = false,
                            onSuccess = { success ->
                                coinResults = success.results
                                if (coinResults.isNotEmpty()) {
                                    val coinBox = coinResults.first().box
                                    // 10 Rupee Coin = 27mm = 2.7cm
                                    val widthPx = coinBox.w * bitmap.width
                                    val heightPx = coinBox.h * bitmap.height
                                    val diameterPx = max(widthPx, heightPx)

                                    activeScale = diameterPx / 2.7f
                                    isMarkerDetected = true
                                }
                            },
                            onFailure = { Log.e("VolFrag", "Coin model failed: $it") }
                        )
                    } catch (e: Exception) { Log.e("VolFrag", "Coin model crashed", e) }
                }

                if (instanceSegmentation != null) {
                    try {
                        instanceSegmentation?.invoke(
                            frame = bitmap,
                            smoothEdges = viewModel.isSmoothEdges,
                            onSuccess = { success -> fishSuccess = success },
                            onFailure = { Log.e("VolFrag", "Fish model failed: $it") }
                        )
                    } catch (e: Exception) { Log.e("VolFrag", "Fish model crashed", e) }
                }

                val finalFishSuccess = fishSuccess ?: Success(0, 0, 0, emptyList())
                finalizeAndDraw(bitmap, finalFishSuccess, coinResults, speciesBoxes, activeScale)
            }
        }
    }

    private fun finalizeAndDraw(
        original: Bitmap,
        fishSuccess: Success,
        coinResults: List<SegmentationResult>,
        speciesBoxes: List<BoundingBox>,
        scale: Float
    ) {
        requireActivity().runOnUiThread {
            binding.pbLoading.visibility = View.GONE
            binding.tvInferenceTime.text = "Inference: ${fishSuccess.interfaceTime}ms"

            if (fishSuccess.results.isEmpty() && coinResults.isEmpty()) {
                // Keep video hidden if we are showing "No Fish" text, or show video again?
                // Usually better to show text "No Fish Detected" rather than reloading video.
                binding.instructionVideoView.visibility = View.GONE
                binding.tvNoFish.visibility = View.VISIBLE
                binding.tvNoFish.text = "No Fish or Coin Detected"
                binding.btnSave.visibility = View.GONE
                viewPagerAdapter.updateImages(emptyList())
                lastAnalysisResult = null
            } else {
                binding.instructionVideoView.visibility = View.GONE
                binding.tvNoFish.visibility = View.GONE
                binding.btnSave.visibility = View.VISIBLE

                val analysisResults = drawImages.invoke(
                    original = original,
                    success = fishSuccess,
                    coinResults = coinResults,
                    isSeparateOut = viewModel.isSeparateOutChecked,
                    isMaskOut = viewModel.isMaskOutChecked,
                    speciesBoxes = speciesBoxes,
                    pixelsPerCm = scale,
                    isMarkerDetected = isMarkerDetected
                )
                lastAnalysisResult = analysisResults
                viewPagerAdapter.updateImages(analysisResults)
            }
        }
    }

    private fun saveVolumeLog() {
        val bitmapsToSave = if (!lastAnalysisResult.isNullOrEmpty()) {
            lastAnalysisResult!!.map { result ->
                if (result.overlay != null) {
                    val combined = result.original.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = Canvas(combined)
                    canvas.drawBitmap(result.overlay, 0f, 0f, null)
                    combined
                } else {
                    result.original
                }
            }
        } else if (currentBitmap != null) {
            listOf(currentBitmap!!)
        } else {
            return
        }

        val descriptions = if (!lastAnalysisResult.isNullOrEmpty()) {
            lastAnalysisResult!!.map { it.description }
        } else {
            listOf("Raw Image\nMarker: ${if (isMarkerDetected) "Yes" else "No"}")
        }

        val paths = mutableListOf<String>()
        try {
            bitmapsToSave.forEachIndexed { index, bitmap ->
                val filename = "vol_${System.currentTimeMillis()}_$index.jpg"
                val file = File(requireContext().filesDir, filename)
                val out = FileOutputStream(file)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
                out.close()
                paths.add(file.absolutePath)
            }

            val combinedPaths = paths.joinToString("|")
            val combinedDetails = descriptions.joinToString(";;;")
            val title = if (isMarkerDetected) "Volume (Accurate)" else "Volume (Est.)"

            saveToDb(combinedPaths, title, combinedDetails)
        } catch (e: Exception) {
            toast("Save failed: ${e.message}")
        }
    }

    private fun saveToDb(imagePath: String, title: String, details: String) {
        try {
            var currentLat = 0.0; var currentLng = 0.0; var placeName = "Location not available"
            try {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    if (lastKnownLocation != null) {
                        currentLat = lastKnownLocation.latitude
                        currentLng = lastKnownLocation.longitude
                        val geocoder = Geocoder(requireContext(), Locale.getDefault())
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocation(currentLat, currentLng, 1)
                        if (!addresses.isNullOrEmpty()) {
                            placeName = addresses[0].locality ?: addresses[0].getAddressLine(0)
                        }
                    }
                }
            } catch (e: Exception) {}

            dbHelper.insertLog(System.currentTimeMillis(), imagePath, title, details, currentLat, currentLng, placeName, DatabaseHelper.Companion.TYPE_VOLUME)
            toast("Volume Log Saved!")
            triggerBackgroundSync()
        } catch (e: Exception) { toast("Error saving: ${e.message}") }
    }

    private fun triggerBackgroundSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val syncRequest = OneTimeWorkRequest.Builder(SyncWorker::class.java)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(requireContext()).enqueueUniqueWork(
            "HistoryUploadWork",
            ExistingWorkPolicy.APPEND,
            syncRequest
        )
    }

    private fun showSettingsDialog() {
        val dialog = Dialog(requireContext())
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialogBinding.apply {
            cbSeparateOut.isChecked = viewModel.isSeparateOutChecked
            cbMaskOut.isChecked = viewModel.isMaskOutChecked
            cbSmoothEdges.isChecked = viewModel.isSmoothEdges
            cbCoinReference.isChecked = viewModel.useCoinReference

            cbSeparateOut.setOnCheckedChangeListener { _, isChecked -> viewModel.isSeparateOutChecked = isChecked }
            cbMaskOut.setOnCheckedChangeListener { _, isChecked -> viewModel.isMaskOutChecked = isChecked }
            cbSmoothEdges.setOnCheckedChangeListener { _, isChecked -> viewModel.isSmoothEdges = isChecked }

            cbCoinReference.setOnCheckedChangeListener { _, isChecked ->
                viewModel.useCoinReference = isChecked
                if (currentBitmap != null && isChecked) {
                    detector?.detect(currentBitmap!!)
                }
            }
        }
        dialog.show()
    }

    private fun toast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        currentBitmap?.let { runInstanceSegmentation(it, boundingBoxes, currentScale) }
    }

    override fun onEmptyDetect() {
        currentBitmap?.let { runInstanceSegmentation(it, emptyList(), currentScale) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val seg = instanceSegmentation
        val coinSeg = coinSegmentation
        val det = detector
        _binding = null
        instanceSegmentation = null
        coinSegmentation = null
        detector = null
        lifecycleScope.launch(Dispatchers.IO) {
            segmentationMutex.withLock {
                seg?.close()
                coinSeg?.close()
                det?.close()
            }
        }
    }
}