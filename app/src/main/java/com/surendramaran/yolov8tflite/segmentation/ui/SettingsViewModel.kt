package com.surendramaran.yolov8tflite.segmentation.ui

import androidx.lifecycle.ViewModel

class SettingsViewModel : ViewModel() {
    var isSeparateOutChecked = true
    var isSmoothEdges = false
    var isMaskOutChecked = false
    var useCoinReference = true
}