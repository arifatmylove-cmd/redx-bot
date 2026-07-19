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
import com.redxbot.storage.ConversationStorage
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONV_ID = "conv_id"
        private const val MENU_BUILDER  = 1001
        private const val MENU_CLEAR    = 1002
        private const val MENU_SETTINGS = 1003
    }

    private lateinit var binding:         ActivityMainBinding
    private lateinit var adapter:         ChatAdapter
    private lateinit var apiClient:       ApiClient
    private lateinit var storage:         ConversationStorage
    private lateinit var conversationId:  String
    private val chatMessages = mutableListOf<Message>()
    private var selectedImageUri: Uri? = null

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                selectedImageUri = it
                binding.ivSelectedImage.visibility = View.VISIBLE
                binding.ivSelectedImage.setImageURI(it)
                binding.btnClearImage.visibility   = View.VISIBLE
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) imagePickerLauncher.launch("image/*")
            else Toast.makeText(this, "Storage permission needed to attach images", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding    = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiClient = ApiClient(this)
        storage   = ConversationStorage(this)

        conversationId = intent.getStringExtra(EXTRA_CONV_ID)
            ?: storage.createConversation()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "RedxBot"

        val markwon = Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(this))
            .build()

        adapter = ChatAdapter(markwon)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).also { it.stackFromEnd = true }
            this.adapter  = this@MainActivity.adapter
        }

        val saved = storage.loadMessages(conversationId)
        chatMessages.addAll(saved)
        saved.forEach { adapter.addMessage(it) }
        if (chatMessages.isNotEmpty()) scrollToBottom()
        updateTitle()

        binding.btnSend.setOnClickListener   { sendMessage() }
        binding.btnAttach.setOnClickListener { requestImagePermission() }
        binding.btnClearImage.setOnClickListener {
            selectedImageUri = null
            binding.ivSelectedImage.visibility = View.GONE
            binding.btnClearImage.visibility   = View.GONE
        }
        binding.etMessage.setOnEditorActionListener { _, _, _ -> sendMessage(); true }
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_BUILDER,  0, "🔨 Build App")
        menu.add(0, MENU_SETTINGS, 1, "⚙️ Settings")
        menu.add(0, MENU_CLEAR,    2, "🗑 Clear Chat")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        MENU_BUILDER  -> { startActivity(Intent(this, AppBuilderActivity::class.java)); true }
        MENU_SETTINGS -> { startActivity(Intent(this, SettingsActivity::class.java));  true }
        MENU_CLEAR    -> {
            AlertDialog.Builder(this)
                .setTitle("Clear Chat")
                .setMessage("Delete all messages?")
                .setPositiveButton("Clear") { _, _ ->
                    chatMessages.clear(); adapter.clearAll()
                    storage.clearMessages(conversationId)
                    supportActionBar?.title = "RedxBot"
                }
                .setNegativeButton("Cancel", null).show(); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty() && selectedImageUri == null) return
        if (apiClient.getApiKey().isEmpty()) {
            Toast.makeText(this, "Add your OpenRouter API key in ⚙️ Settings", Toast.LENGTH_LONG).show()
            return
        }

        val userMsg = Message(role = Message.Role.USER, content = text, imageUri = selectedImageUri?.toString())
        chatMessages.add(userMsg); adapter.addMessage(userMsg)
        binding.etMessage.setText("")
        val imgUri = selectedImageUri; selectedImageUri = null
        binding.ivSelectedImage.visibility = View.GONE
        binding.btnClearImage.visibility   = View.GONE
        if (chatMessages.count { it.role == Message.Role.USER } == 1) updateTitle()

        val loading = Message(role = Message.Role.ASSISTANT, content = "", isLoading = true)
        chatMessages.add(loading); adapter.addMessage(loading); scrollToBottom()
        binding.btnSend.isEnabled = false; binding.btnAttach.isEnabled = false

        lifecycleScope.launch {
            val result = apiClient.chat(chatMessages.dropLast(1), imgUri)
            if (chatMessages.isNotEmpty() && chatMessages.last().isLoading)
                chatMessages.removeAt(chatMessages.size - 1)

            result.onSuccess { content ->
                chatMessages.add(Message(role = Message.Role.ASSISTANT, content = content))
                adapter.updateLastMessage(content)
                storage.saveMessages(conversationId, chatMessages)
            }.onFailure { e ->
                adapter.removeLastIfLoading()
                val msg  = e.message ?: ""
                val text2 = when {
                    msg.contains("401")                         -> "API key rejected — check ⚙️ Settings"
                    msg.contains("404")                         -> "Model unavailable — try again shortly"
                    msg.contains("429")                         -> "Rate limited — all models busy, wait a moment"
                    msg.contains("timeout", ignoreCase = true)  -> "Timed out — check connection"
                    else                                        -> "Error: $msg"
                }
                val errMsg = Message(role = Message.Role.ASSISTANT, content = "⚠️ $text2")
                chatMessages.add(errMsg); adapter.addMessage(errMsg)
                storage.saveMessages(conversationId, chatMessages)
            }
            binding.btnSend.isEnabled = true; binding.btnAttach.isEnabled = true; scrollToBottom()
        }
    }

    private fun updateTitle() {
        supportActionBar?.title = chatMessages
            .firstOrNull { it.role == Message.Role.USER && it.content.isNotBlank() }
            ?.content?.take(30) ?: "RedxBot"
    }

    private fun requestImagePermission() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED)
            imagePickerLauncher.launch("image/*")
        else permissionLauncher.launch(perm)
    }

    private fun scrollToBottom() {
        if (adapter.itemCount > 0) binding.recyclerView.smoothScrollToPosition(adapter.itemCount - 1)
    }
}
