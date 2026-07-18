package com.redxbot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.redxbot.adapter.ChatAdapter
import com.redxbot.databinding.ActivityMainBinding
import com.redxbot.model.Message
import com.redxbot.network.ApiClient
import com.redxbot.storage.ChatStorage
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ChatAdapter
    private lateinit var apiClient: ApiClient
    private lateinit var chatStorage: ChatStorage
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
            if (granted) openImagePicker()
            else Toast.makeText(this, "Storage permission needed to attach images", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiClient   = ApiClient(this)
        chatStorage = ChatStorage(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "RedxBot"

        val markwon = Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(this))
            .build()

        adapter = ChatAdapter(markwon)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).also { it.stackFromEnd = true }
            this.adapter = this@MainActivity.adapter
        }

        // ── Load chat history ──────────────────────────────────────────────────
        val saved = chatStorage.load()
        chatMessages.addAll(saved)
        saved.forEach { adapter.addMessage(it) }
        if (chatMessages.isNotEmpty()) scrollToBottom()

        // ── Wire up buttons ────────────────────────────────────────────────────
        binding.btnSend.setOnClickListener   { sendMessage() }
        binding.btnAttach.setOnClickListener { requestImagePermission() }
        binding.btnClearImage.setOnClickListener {
            selectedImageUri = null
            binding.ivSelectedImage.visibility  = View.GONE
            binding.btnClearImage.visibility    = View.GONE
        }
        binding.etMessage.setOnEditorActionListener { _, _, _ -> sendMessage(); true }
    }

    // ── Toolbar menu ───────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_BUILDER,  0, "🔨 Build App")
        menu.add(0, MENU_SETTINGS, 1, "⚙️ Settings")
        menu.add(0, MENU_CLEAR,    2, "🗑 Clear Chat")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_BUILDER -> {
                startActivity(Intent(this, AppBuilderActivity::class.java))
                true
            }
            MENU_SETTINGS -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            MENU_CLEAR -> {
                AlertDialog.Builder(this)
                    .setTitle("Clear Chat")
                    .setMessage("Delete all messages in this conversation?")
                    .setPositiveButton("Clear") { _, _ ->
                        chatMessages.clear()
                        adapter.clearAll()
                        chatStorage.clear()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val MENU_BUILDER  = 1001
        private const val MENU_CLEAR    = 1002
        private const val MENU_SETTINGS = 1003
    }

    // ── Send message ───────────────────────────────────────────────────────────

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty() && selectedImageUri == null) return

        val apiKey = apiClient.getApiKey()
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Add your OpenRouter API key in Settings ⚙️", Toast.LENGTH_LONG).show()
            return
        }

        val userMessage = Message(
            role     = Message.Role.USER,
            content  = text,
            imageUri = selectedImageUri?.toString()
        )
        chatMessages.add(userMessage)
        adapter.addMessage(userMessage)
        binding.etMessage.setText("")
        val imageUriForApi = selectedImageUri
        selectedImageUri = null
        binding.ivSelectedImage.visibility = View.GONE
        binding.btnClearImage.visibility   = View.GONE

        val loading = Message(role = Message.Role.ASSISTANT, content = "", isLoading = true)
        chatMessages.add(loading)
        adapter.addMessage(loading)
        scrollToBottom()

        binding.btnSend.isEnabled   = false
        binding.btnAttach.isEnabled = false

        lifecycleScope.launch {
            val result = apiClient.chat(chatMessages.dropLast(1), imageUriForApi)

            if (chatMessages.isNotEmpty() && chatMessages.last().isLoading)
                chatMessages.removeAt(chatMessages.size - 1)

            result.onSuccess { content ->
                val aiMsg = Message(role = Message.Role.ASSISTANT, content = content)
                chatMessages.add(aiMsg)
                adapter.updateLastMessage(content)
                chatStorage.save(chatMessages) // persist after each exchange
            }.onFailure { error ->
                adapter.removeLastIfLoading()
                val msg = error.message ?: ""
                val errorText = when {
                    apiKey.isEmpty() ->
                        "No API key — go to ⚙️ Settings"
                    msg.contains("401") ->
                        "API key rejected — check it in ⚙️ Settings"
                    msg.contains("404") ->
                        "Model unavailable right now — try again in a moment"
                    msg.contains("429") ->
                        "Rate limit hit — the app tried all available models. Wait a moment."
                    msg.contains("timeout", ignoreCase = true) ->
                        "Request timed out — check your connection"
                    else -> "Error: $msg"
                }
                val errMsg = Message(role = Message.Role.ASSISTANT, content = "⚠️ $errorText")
                chatMessages.add(errMsg)
                adapter.addMessage(errMsg)
                chatStorage.save(chatMessages)
            }

            binding.btnSend.isEnabled   = true
            binding.btnAttach.isEnabled = true
            scrollToBottom()
        }
    }

    // ── Image picker ───────────────────────────────────────────────────────────

    private fun requestImagePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED ->
                openImagePicker()
            else -> permissionLauncher.launch(permission)
        }
    }

    private fun openImagePicker() { imagePickerLauncher.launch("image/*") }

    private fun scrollToBottom() {
        if (adapter.itemCount > 0)
            binding.recyclerView.smoothScrollToPosition(adapter.itemCount - 1)
    }
}
