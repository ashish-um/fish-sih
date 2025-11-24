package com.surendramaran.yolov8tflite.data

object ModelData {
    // We only list the Llama 3.2 1B model here as requested,
    // but you can paste the full list from your snippet if you want more options.
    val defaultModel = LLMModel(
        name = "Llama-3.2 1B (INT8)",
        description = "Meta's Llama 3.2 1B model with INT8 quantization. Optimized for on-device inference.",
        url = "https://huggingface.co/vimal-yuvabe/llama-3.2-1b-tflite/resolve/main/llama-3.2-1b-q8.task?download=true",
        category = "text",
        sizeBytes = 2160086757L, // ~2.01GB
        source = "Meta via vimal-yuvabe",
        supportsVision = false,
        supportsGpu = false, // Note: This model must run on CPU
        requirements = ModelRequirements(minRamGB = 3, recommendedRamGB = 4),
        contextWindowSize = 4096,
        modelFormat = "task"
    )
}