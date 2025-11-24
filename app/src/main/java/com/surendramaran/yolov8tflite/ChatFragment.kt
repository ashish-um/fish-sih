package com.surendramaran.yolov8tflite

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatFragment : Fragment(R.layout.fragment_chat) {

    private lateinit var llmHelper: LlmHelper
    private lateinit var modelManager: ModelManager
    private lateinit var chatAdapter: ChatAdapter

    private lateinit var rvChat: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button

    private lateinit var progressOverlay: View
    private lateinit var tvProgress: TextView
    private lateinit var btnLoadModel: Button
    private lateinit var progressBar: ProgressBar

    private val pickModelLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            loadModelFromUri(uri)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvChat = view.findViewById(R.id.rvChat)
        etMessage = view.findViewById(R.id.etMessage)
        btnSend = view.findViewById(R.id.btnSend)
        progressOverlay = view.findViewById(R.id.progressOverlay)
        tvProgress = view.findViewById(R.id.tvProgress)
        btnLoadModel = view.findViewById(R.id.btnLoadModel)
        progressBar = view.findViewById(R.id.progressBar)

        setupRecyclerView()
        modelManager = ModelManager(requireContext())

        checkAndInitModel()

        btnSend.setOnClickListener {
            val text = etMessage.text.toString()
            if (text.isNotEmpty()) {
                sendMessage(text)
                etMessage.text.clear()
            }
        }

        btnLoadModel.setOnClickListener {
            pickModelLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun checkAndInitModel() {
        if (modelManager.isModelReady()) {
            // FORCE HIDE the overlay
            progressOverlay.visibility = View.GONE
            tvProgress.text = ""
            initializeLlm()
        } else {
            progressOverlay.visibility = View.VISIBLE
            tvProgress.text = "Model not found.\nPlease select 'llama.task'."
            btnLoadModel.visibility = View.VISIBLE
            progressBar.visibility = View.GONE
        }
    }

    private fun loadModelFromUri(uri: Uri) {
        tvProgress.text = "Initializing copy..."
        btnLoadModel.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressOverlay.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val success = modelManager.copyModelFromUri(uri) { progress ->
                // Limit UI updates to avoid flooding the main thread
                launch(Dispatchers.Main) {
                    tvProgress.text = "Copying model: $progress%"
                }
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    tvProgress.text = "Copy Complete!"
                    // Slight delay to ensure UI queue clears before hiding
                    checkAndInitModel()
                } else {
                    tvProgress.text = "Failed to copy file. Try again."
                    btnLoadModel.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        rvChat.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        rvChat.adapter = chatAdapter
    }

    private fun initializeLlm() {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                llmHelper = LlmHelper(requireContext(), modelManager.getModelPath())
                llmHelper.initModel()

                launch(Dispatchers.Main) {
                    chatAdapter.addMessage("Fish AI Ready! Ask me anything.", false)
                    progressOverlay.visibility = View.GONE // Double ensure hidden
                }
            } catch (e: Throwable) {
                launch(Dispatchers.Main) {
                    progressOverlay.visibility = View.VISIBLE
                    tvProgress.text = "Error: RAM too low or Model invalid.\n${e.message}"
                    btnLoadModel.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun sendMessage(userText: String) {
        chatAdapter.addMessage(userText, true)
        chatAdapter.addMessage("Thinking...", false)
        rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)

        lifecycleScope.launch(Dispatchers.IO) {
            var fullResponse = ""
            try {
                llmHelper.generateResponse(userText).collect { response ->
                    fullResponse = response
                }
                withContext(Dispatchers.Main) {
                    chatAdapter.updateLastMessage(fullResponse)
                    rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    chatAdapter.updateLastMessage("Error: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { llmHelper.close() } catch (e: Exception) {}
    }
}