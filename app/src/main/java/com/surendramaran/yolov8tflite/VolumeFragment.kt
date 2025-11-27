package com.surendramaran.yolov8tflite

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.camera2.CameraCharacteristics
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
import com.surendramaran.yolov8tflite.Constants.SEG_MODEL_PATH
import com.surendramaran.yolov8tflite.databinding.DialogSettingsBinding
import com.surendramaran.yolov8tflite.databinding.FragmentVolumeBinding
import com.surendramaran.yolov8tflite.segmentation.DrawImages
import com.surendramaran.yolov8tflite.segmentation.InstanceSegmentation
import com.surendramaran.yolov8tflite.segmentation.Success
import com.surendramaran.yolov8tflite.segmentation.ui.SettingsViewModel
import com.surendramaran.yolov8tflite.segmentation.ui.ViewPagerAdapter
import com.surendramaran.yolov8tflite.segmentation.ui.ZoomPageTransformation
import com.surendramaran.yolov8tflite.segmentation.utils.OrientationLiveData
import com.surendramaran.yolov8tflite.segmentation.utils.Utils
import com.surendramaran.yolov8tflite.segmentation.utils.Utils.addCarouselEffect
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class VolumeFragment : Fragment() {
    private var _binding: FragmentVolumeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by activityViewModels()

    private var instanceSegmentation: InstanceSegmentation? = null
    private lateinit var orientationLiveData: OrientationLiveData
    private lateinit var viewPagerAdapter: ViewPagerAdapter
    private lateinit var drawImages: DrawImages

    // 1. CROP RESULT LAUNCHER
    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let { uri ->
                // Load the CROPPED image
                val bitmap = Utils.getBitmapFromUri(requireContext(), uri) ?: return@let
                runInstanceSegmentation(bitmap)
            }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            val error = UCrop.getError(result.data!!)
            toast("Crop error: ${error?.message}")
        }
    }

    // 2. GALLERY PICKER -> Goes to Crop
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { startCrop(it) }
    }

    private var currentPhotoUri: Uri? = null

    // 3. CAMERA CAPTURE -> Goes to Crop
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

        instanceSegmentation = InstanceSegmentation(
            context = requireContext(),
            modelPath = SEG_MODEL_PATH,
            labelPath = null,
            smoothnessKernel = 5
        ) { error -> toast(error) }

        drawImages = DrawImages(requireContext())

        try {
            val cameraId = Utils.getCameraId(cameraManager)
            if (cameraId != null) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                orientationLiveData = OrientationLiveData(requireContext(), characteristics).apply {
                    observe(viewLifecycleOwner) { orientation ->
                        Log.d("VolumeFragment", "Orientation: $orientation")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VolumeFragment", "Camera Error", e)
        }

        bindListeners()
    }

    private fun bindListeners() {
        binding.apply {
            btnCamera.setOnClickListener {
                val photoFile = Utils.createImageFile(requireContext())
                val photoUri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.provider",
                    photoFile
                )
                currentPhotoUri = photoUri
                photoCapture.launch(photoUri)
            }

            btnGallery.setOnClickListener {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }

            ivSettings.setOnClickListener {
                showSettingsDialog()
            }
        }
    }

    // 4. HELPER: Start UCrop
    private fun startCrop(sourceUri: Uri) {
        try {
            // Create a file to save the cropped image
            val destName = "crop_${System.currentTimeMillis()}.jpg"
            val destFile = File(requireContext().cacheDir, destName)
            val destUri = Uri.fromFile(destFile)

            val options = UCrop.Options()
            options.setToolbarTitle("Crop Fish")
            options.setFreeStyleCropEnabled(true) // Let user choose aspect ratio
            options.setCompressionQuality(90)

            val uCrop = UCrop.of(sourceUri, destUri).withOptions(options)

            // Launch UCrop using the ActivityResultLauncher
            cropImage.launch(uCrop.getIntent(requireContext()))

        } catch (e: Exception) {
            Log.e("VolumeFragment", "Crop Error", e)
            toast("Failed to start crop")
        }
    }

    private fun runInstanceSegmentation(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.Default) {
            instanceSegmentation?.invoke(
                frame = bitmap,
                smoothEdges = viewModel.isSmoothEdges,
                onSuccess = { processSuccessResult(bitmap, it) },
                onFailure = { clearOutput(it) }
            )
        }
    }

    private fun processSuccessResult(original: Bitmap, success: Success) {
        requireActivity().runOnUiThread {
            binding.tvInferenceTime.text = "Inference: ${success.interfaceTime}ms"
        }

        val images = drawImages.invoke(
            original = original,
            success = success,
            isSeparateOut = viewModel.isSeparateOutChecked,
            isMaskOut = viewModel.isMaskOutChecked
        )

        requireActivity().runOnUiThread {
            viewPagerAdapter.updateImages(images)
        }
    }

    private fun clearOutput(error: String) {
        requireActivity().runOnUiThread {
            binding.tvInferenceTime.text = "--"
            viewPagerAdapter.updateImages(mutableListOf())
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
    }
}