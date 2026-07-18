package com.redxbot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.redxbot.adapter.ChatAdapter
import com.redxbot.databinding.ActivityMainBinding
import com.redxbot.model.Message
import com.redxbot.network.ApiClient
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ChatAdapter
    private lateinit var apiClient: ApiClient
    private val chatMessages = mutableListOf<Message>()
    private var selectedImageUri: Uri? = null

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                selectedImageUri = it
                binding.ivSelectedImage.visibility = View.VISIBLE
                binding.ivSelectedImage.setImageURI(it)
                binding.btnClearImage.visibility = View.VISIBLE
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openImagePicker() else {
                Toast.makeText(this, "Storage permission required to attach images", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiClient = ApiClient(this)

        val markwon = Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(this))
            .build()

        adapter = ChatAdapter(markwon)

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).also { it.stackFromEnd = true }
            this.adapter = this@MainActivity.adapter
        }

        binding.btnSend.setOnClickListener { sendMessage() }
        binding.btnAttach.setOnClickListener { requestImagePermission() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnClearImage.setOnClickListener {
            selectedImageUri = null
            binding.ivSelectedImage.visibility = View.GONE
            binding.btnClearImage.visibility = View.GONE
        }
        binding.etMessage.setOnEditorActionListener { _, _, _ ->
            sendMessage(); true
        }
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty() && selectedImageUri == null) return

        val userMessage = Message(
            role = Message.Role.USER,
            content = text,
            imageUri = selectedImageUri?.toString()
        )
        chatMessages.add(userMessage)
        adapter.addMessage(userMessage)

        binding.etMessage.setText("")
        val imageUriForApi = selectedImageUri
        selectedImageUri = null
        binding.ivSelectedImage.visibility = View.GONE
        binding.btnClearImage.visibility = View.GONE

        // Add loading indicator
        val loadingMessage = Message(
            role = Message.Role.ASSISTANT,
            content = "",
            isLoading = true
        )
        chatMessages.add(loadingMessage)
        adapter.addMessage(loadingMessage)
        scrollToBottom()

        binding.btnSend.isEnabled = false
        binding.btnAttach.isEnabled = false

        lifecycleScope.launch {
            val result = apiClient.chat(chatMessages.dropLast(1), imageUriForApi)

            // Remove loading message from our internal list too
            if (chatMessages.isNotEmpty() && chatMessages.last().isLoading) {
                chatMessages.removeAt(chatMessages.size - 1)
            }

            result.onSuccess { content ->
                val aiMessage = Message(
                    role = Message.Role.ASSISTANT,
                    content = content
                )
                chatMessages.add(aiMessage)
                adapter.updateLastMessage(content)
            }.onFailure { error ->
                adapter.removeLastIfLoading()
                val msg = error.message ?: ""
                val errorMsg = when {
                    apiClient.getApiKey().isEmpty() ->
                        "No API key set. Go to ⚙️ Settings and paste your OpenRouter API key (free at openrouter.ai)"
                    msg.contains("401") ->
                        "API key rejected. Check it in ⚙️ Settings — get a free key at openrouter.ai"
                    msg.contains("402") ->
                        "This model requires credits. The free quota may be exhausted — try again later."
                    msg.contains("404") ->
                        "Model unavailable. The app will auto-update models. Try again in a moment."
                    msg.contains("429") ->
                        "Rate limit hit. Please wait a moment and try again."
                    msg.contains("timeout", ignoreCase = true) ->
                        "Request timed out. Check your internet connection."
                    else -> "Error: $msg"
                }
                val errorMessage = Message(
                    role = Message.Role.ASSISTANT,
                    content = "⚠️ $errorMsg"
                )
                chatMessages.add(errorMessage)
                adapter.addMessage(errorMessage)
            }

            binding.btnSend.isEnabled = true
            binding.btnAttach.isEnabled = true
            scrollToBottom()
        }
    }

    private fun requestImagePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED ->
                openImagePicker()
            else -> permissionLauncher.launch(permission)
        }
    }

    private fun openImagePicker() {
        imagePickerLauncher.launch("image/*")
    }

    private fun scrollToBottom() {
        if (adapter.itemCount > 0) {
            binding.recyclerView.smoothScrollToPosition(adapter.itemCount - 1)
        }
    }
}
