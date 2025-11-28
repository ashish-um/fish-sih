package com.surendramaran.yolov8tflite

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import com.surendramaran.yolov8tflite.Constants.SEG_MODEL_PATH
import com.surendramaran.yolov8tflite.databinding.DialogSettingsBinding
import com.surendramaran.yolov8tflite.databinding.FragmentVolumeBinding
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
import kotlin.math.sqrt
import kotlin.math.pow

class VolumeFragment : Fragment(), Detector.DetectorListener {
    private var _binding: FragmentVolumeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by activityViewModels()

    private var instanceSegmentation: InstanceSegmentation? = null
    private var detector: Detector? = null

    private lateinit var orientationLiveData: OrientationLiveData
    private lateinit var viewPagerAdapter: ViewPagerAdapter
    private lateinit var drawImages: DrawImages

    private var currentBitmap: Bitmap? = null
    private var currentScale: Float = 50.0f // Default pixels per cm
    private var isMarkerDetected: Boolean = false // Track detection status

    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let { uri ->
                val bitmap = Utils.getBitmapFromUri(requireContext(), uri) ?: return@let

                // 1. Run ArUco (Returns: Bitmap, Scale, isFound)
                val (markedBitmap, scale, found) = detectArUcoMarkers(bitmap)

                currentBitmap = markedBitmap
                currentScale = scale
                isMarkerDetected = found // Store status

                // 2. Run Species Detector
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

    private var currentPhotoUri: Uri? = null

    private val photoCapture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            currentPhotoUri?.let { uri -> startCrop(uri) }
        }
    }

    private val cameraManager: CameraManager by lazy {
        requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
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

        if (OpenCVLoader.initDebug()) {
            Log.d("VolumeFragment", "OpenCV loaded successfully")
        }

        instanceSegmentation = InstanceSegmentation(
            context = requireContext(),
            modelPath = SEG_MODEL_PATH,
            labelPath = null,
            smoothnessKernel = 5
        ) { error -> toast(error) }

        detector = Detector(
            context = requireContext(),
            modelPath = MODEL_PATH,
            labelPath = LABELS_PATH,
            detectorListener = this
        )

        drawImages = DrawImages(requireContext())
        bindListeners()
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        currentBitmap?.let {
            runInstanceSegmentation(it, boundingBoxes, currentScale)
        }
    }

    override fun onEmptyDetect() {
        currentBitmap?.let {
            runInstanceSegmentation(it, emptyList(), currentScale)
        }
    }

    // UPDATED: Returns Triple(Bitmap, Scale, IsFound)
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
        parameters.set_adaptiveThreshWinSizeMin(3)
        parameters.set_adaptiveThreshWinSizeMax(23)
        parameters.set_adaptiveThreshWinSizeStep(10)

        var detectedScale = 50.0f // Default fallback
        var markerFound = false

        try {
            Aruco.detectMarkers(grayMat, dictionary, corners, ids, parameters)
            if (ids.rows() > 0) {
                // Draw Marker
                Scalar(0.0, 255.0, 0.0).let { green ->
                    Aruco.drawDetectedMarkers(rgbMat, corners, ids, green)
                }

                // Calculate Scale
                val markerSizeCm = 5.0f
                val c = corners[0]
                val xDiff = c.get(0, 0)[0] - c.get(0, 1)[0]
                val yDiff = c.get(0, 0)[1] - c.get(0, 1)[1]
                val widthPx = sqrt(xDiff.pow(2) + yDiff.pow(2)).toFloat()

                detectedScale = widthPx / markerSizeCm
                markerFound = true

                Log.d("VolumeFragment", "Marker Found. Scale: $detectedScale px/cm")
            } else {
                Log.w("VolumeFragment", "No Marker Found. Using default: $detectedScale px/cm")
            }
        } catch (e: Exception) {
            Log.e("VolumeFragment", "ArUco Error", e)
        }

        val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        OpenCVUtils.matToBitmap(rgbMat, resultBitmap)

        mat.release(); rgbMat.release(); grayMat.release(); ids.release()
        corners.forEach { it.release() }

        return Triple(resultBitmap, detectedScale, markerFound)
    }

    private fun bindListeners() {
        binding.apply {
            btnCamera.setOnClickListener {
                val photoFile = Utils.createImageFile(requireContext())
                val photoUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", photoFile)
                currentPhotoUri = photoUri
                photoCapture.launch(photoUri)
            }
            btnGallery.setOnClickListener {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            ivSettings.setOnClickListener { showSettingsDialog() }
        }
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
        }

        // Pass 'isMarkerDetected' to DrawImages
        val analysisResults = drawImages.invoke(
            original = original,
            success = success,
            isSeparateOut = viewModel.isSeparateOutChecked,
            isMaskOut = viewModel.isMaskOutChecked,
            speciesBoxes = speciesBoxes,
            pixelsPerCm = scale,
            isMarkerDetected = isMarkerDetected // <--- PASSED HERE
        )

        requireActivity().runOnUiThread {
            viewPagerAdapter.updateImages(analysisResults)
        }
    }

    private fun clearOutput(error: String) {
        requireActivity().runOnUiThread {
            binding.tvInferenceTime.text = "--"
            viewPagerAdapter.updateImages(emptyList())
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
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
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        instanceSegmentation?.close()
        detector?.close()
    }
}