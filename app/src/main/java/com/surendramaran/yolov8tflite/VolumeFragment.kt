package com.surendramaran.yolov8tflite

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
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import com.surendramaran.yolov8tflite.Constants.SEG_MODEL_PATH
import com.surendramaran.yolov8tflite.databinding.DialogSettingsBinding
import com.surendramaran.yolov8tflite.databinding.FragmentVolumeBinding
import com.surendramaran.yolov8tflite.segmentation.AnalysisResult
import com.surendramaran.yolov8tflite.segmentation.DrawImages
import com.surendramaran.yolov8tflite.segmentation.InstanceSegmentation
import com.surendramaran.yolov8tflite.segmentation.Success
import com.surendramaran.yolov8tflite.segmentation.ui.SettingsViewModel
import com.surendramaran.yolov8tflite.segmentation.ui.ViewPagerAdapter
import com.surendramaran.yolov8tflite.segmentation.utils.OrientationLiveData
import com.surendramaran.yolov8tflite.segmentation.utils.Utils
import com.surendramaran.yolov8tflite.segmentation.utils.Utils.addCarouselEffect
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils as OpenCVUtils
import org.opencv.aruco.Aruco
import org.opencv.aruco.DetectorParameters
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.sqrt
import kotlin.math.pow

class VolumeFragment : Fragment(), Detector.DetectorListener {
    private var _binding: FragmentVolumeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by activityViewModels()

    private var instanceSegmentation: InstanceSegmentation? = null
    private var detector: Detector? = null
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var drawImages: DrawImages
    private lateinit var viewPagerAdapter: ViewPagerAdapter

    private var currentBitmap: Bitmap? = null
    private var currentScale: Float = 50.0f
    private var isMarkerDetected: Boolean = false

    // Holds the result list for saving
    private var lastAnalysisResult: List<AnalysisResult>? = null
    private var currentPhotoUri: Uri? = null

    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let { uri ->
                val bitmap = Utils.getBitmapFromUri(requireContext(), uri) ?: return@let
                val (markedBitmap, scale, found) = detectArUcoMarkers(bitmap)
                currentBitmap = markedBitmap
                currentScale = scale
                isMarkerDetected = found
                detector?.detect(markedBitmap)
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

        instanceSegmentation = InstanceSegmentation(requireContext(), SEG_MODEL_PATH, null, 5) { toast(it) }
        detector = Detector(requireContext(), MODEL_PATH, LABELS_PATH, this)
        drawImages = DrawImages(requireContext())

        setupListeners()
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
        OpenCVUtils.bitmapToMat(bitmap, mat)
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
        OpenCVUtils.matToBitmap(rgbMat, resultBitmap)
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

    private fun runInstanceSegmentation(bitmap: Bitmap, speciesBoxes: List<BoundingBox>, scale: Float) {
        lifecycleScope.launch(Dispatchers.Default) {
            instanceSegmentation?.invoke(
                frame = bitmap,
                smoothEdges = viewModel.isSmoothEdges,
                onSuccess = { processSuccessResult(bitmap, it, speciesBoxes, scale) },
                onFailure = { clearOutput(it) }
            )
        }
    }

    private fun processSuccessResult(original: Bitmap, success: Success, speciesBoxes: List<BoundingBox>, scale: Float) {
        requireActivity().runOnUiThread {
            binding.tvInferenceTime.text = "Inference: ${success.interfaceTime}ms"

            if (success.results.isEmpty()) {
                // Case: No Fish Found
                binding.tvNoFish.visibility = View.VISIBLE
                binding.btnSave.visibility = View.GONE
                viewPagerAdapter.updateImages(emptyList())
                lastAnalysisResult = null
            } else {
                // Case: Fish Detected
                binding.tvNoFish.visibility = View.GONE
                binding.btnSave.visibility = View.VISIBLE

                val analysisResults = drawImages.invoke(
                    original = original,
                    success = success,
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

        // Collect specific descriptions
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
            val combinedDetails = descriptions.joinToString(";;;") // Unique delimiter

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

            dbHelper.insertLog(System.currentTimeMillis(), imagePath, title, details, currentLat, currentLng, placeName, DatabaseHelper.TYPE_VOLUME)
            toast("Volume Log Saved!")
        } catch (e: Exception) { toast("Error saving: ${e.message}") }
    }

    private fun clearOutput(error: String) {
        requireActivity().runOnUiThread {
            binding.tvInferenceTime.text = "--"
            binding.tvNoFish.visibility = View.GONE
            binding.btnSave.visibility = View.GONE
            viewPagerAdapter.updateImages(emptyList())
            toast(error)
        }
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
            cbSeparateOut.setOnCheckedChangeListener { _, isChecked -> viewModel.isSeparateOutChecked = isChecked }
            cbMaskOut.setOnCheckedChangeListener { _, isChecked -> viewModel.isMaskOutChecked = isChecked }
            cbSmoothEdges.setOnCheckedChangeListener { _, isChecked -> viewModel.isSmoothEdges = isChecked }
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
        _binding = null
        instanceSegmentation?.close()
        detector?.close()
    }
}