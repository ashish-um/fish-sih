package com.surendramaran.yolov8tflite.data

data class ModelRequirements(
    val minRamGB: Int,
    val recommendedRamGB: Int
)

data class LLMModel(
    val name: String,
    val description: String,
    val url: String,
    val category: String,
    val sizeBytes: Long,
    val source: String,
    val supportsVision: Boolean,
    val supportsGpu: Boolean,
    val requirements: ModelRequirements,
    val contextWindowSize: Int,
    val modelFormat: String
) {
    // Helper to generate a safe filename from the model name
    fun getFileName(): String {
        // E.g., "Llama-3.2 1B (INT8)" -> "llama-3.2-1b-int8.task"
        return name.lowercase()
            .replace(" ", "-")
            .replace("(", "")
            .replace(")", "")
            .replace(",", "") + ".$modelFormat"
    }
}