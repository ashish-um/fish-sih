package com.surendramaran.yolov8tflite.ui.profile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
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
import androidx.navigation.fragment.findNavController
import com.surendramaran.yolov8tflite.databinding.FragmentProfileBinding
import com.surendramaran.yolov8tflite.utils.UserUtils
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.FileOutputStream

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private var currentPhotoPath: String? = null
    private var tempImageUri: Uri? = null

    // 1. Permission Request Launcher
    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCamera()
        } else {
            Toast.makeText(context, "Camera permission required to take photos", Toast.LENGTH_SHORT).show()
        }
    }

    // 2. Crop Result Handler
    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let { saveImageLocally(it) }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            val error = UCrop.getError(result.data!!)
            Toast.makeText(context, "Crop error: ${error?.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 3. Gallery Picker
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { startCrop(it) }
    }

    // 4. Camera Launcher
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            tempImageUri?.let { uri -> startCrop(uri) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val isOnboarding = arguments?.getBoolean("isOnboarding") ?: false

        // Load existing data
        binding.etName.setText(UserUtils.getUserName(requireContext()))
        UserUtils.getPfpPath(requireContext())?.let { path ->
            val file = File(path)
            if (file.exists()) {
                binding.ivProfile.setImageURI(Uri.fromFile(file))
                currentPhotoPath = path
            }
        }

        val pickAction = { showImagePickerOptions() }
        binding.ivProfile.setOnClickListener { pickAction() }
        binding.fabEditPhoto.setOnClickListener { pickAction() }

        binding.btnSaveProfile.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            if (name.isNotEmpty()) {
                UserUtils.saveProfile(requireContext(), name, currentPhotoPath)
                Toast.makeText(context, "Profile Saved!", Toast.LENGTH_SHORT).show()

                if (isOnboarding) {
                    findNavController().navigate(
                        com.surendramaran.yolov8tflite.R.id.cameraFragment,
                        null,
                        androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(com.surendramaran.yolov8tflite.R.id.languageFragment, true)
                            .build()
                    )
                } else {
                    findNavController().popBackStack()
                }
            } else {
                binding.tilName.error = "Name is required"
            }
        }
    }

    private fun showImagePickerOptions() {
        val options = arrayOf("Take Photo", "Choose from Gallery")
        AlertDialog.Builder(requireContext())
            .setTitle("Change Profile Photo")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> checkPermissionAndOpenCamera()
                    1 -> photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun checkPermissionAndOpenCamera() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        try {
            val tmpFile = File.createTempFile("tmp_photo_", ".jpg", requireContext().cacheDir).apply {
                createNewFile()
                deleteOnExit()
            }
            tempImageUri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.provider",
                tmpFile
            )
            takePicture.launch(tempImageUri)
        } catch (e: Exception) {
            Toast.makeText(context, "Error starting camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCrop(sourceUri: Uri) {
        val destFileName = "crop_pfp_${System.currentTimeMillis()}.jpg"
        val destFile = File(requireContext().cacheDir, destFileName)
        val destUri = Uri.fromFile(destFile)

        val options = UCrop.Options()
        options.setCircleDimmedLayer(true)
        options.setShowCropGrid(false)
        options.setCompressionQuality(90)
        options.setToolbarTitle("Edit Photo")

        val uCrop = UCrop.of(sourceUri, destUri).withOptions(options)
        cropImage.launch(uCrop.getIntent(requireContext()))
    }

    private fun saveImageLocally(sourceUri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(sourceUri)
            val file = File(requireContext().filesDir, "user_pfp_final.jpg")
            val out = FileOutputStream(file)
            inputStream?.copyTo(out)
            inputStream?.close()
            out.close()

            currentPhotoPath = file.absolutePath
            binding.ivProfile.setImageDrawable(null)
            binding.ivProfile.setImageURI(Uri.fromFile(file))
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}