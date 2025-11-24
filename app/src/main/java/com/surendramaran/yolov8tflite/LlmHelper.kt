package com.surendramaran.yolov8tflite

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

class LlmHelper(
    private val context: Context,
    private val modelPath: String
) {
    private var llmInference: LlmInference? = null

    fun initModel() {
        val file = File(modelPath)
        if (!file.exists()) {
            throw RuntimeException("Model file not found at: $modelPath")
        }

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(1024)
            .build()

        llmInference = LlmInference.createFromOptions(context, options)
    }

    fun generateResponse(prompt: String): Flow<String> = callbackFlow {
        if (llmInference == null) {
            trySend("Error: AI Engine is not ready.")
            close()
            return@callbackFlow
        }

        val formattedPrompt = "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\n$prompt<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"

        try {
            // Move the blocking inference call to the IO thread
            val result = withContext(Dispatchers.IO) {
                llmInference!!.generateResponse(formattedPrompt)
            }
            trySend(result)
        } catch (e: Exception) {
            trySend("Error: ${e.message}")
        }

        close()
        awaitClose { }
    }

    fun close() {
        llmInference = null
    }
}