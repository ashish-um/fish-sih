package com.surendramaran.yolov8tflite.segmentation

import android.graphics.Bitmap

data class AnalysisResult(
    val original: Bitmap,
    val overlay: Bitmap?,
    val description: String
)