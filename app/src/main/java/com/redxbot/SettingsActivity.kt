package com.redxbot

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.redxbot.databinding.ActivitySettingsBinding
import com.redxbot.network.ApiClient
import com.redxbot.storage.ChatStorage

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var apiClient: ApiClient
    private lateinit var chatStorage: ChatStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        apiClient   = ApiClient(this)
        chatStorage = ChatStorage(this)

        val prefs = getSharedPreferences(ApiClient.PREFS_NAME, Context.MODE_PRIVATE)

        // ── Populate fields ────────────────────────────────────────────────────
        binding.etApiKey.setText(apiClient.getApiKey())
        binding.etSystemPrompt.setText(apiClient.getSystemPrompt())
        binding.etGithubUsername.setText(prefs.getString(ApiClient.PREF_GITHUB_USERNAME, ""))
        binding.etGithubToken.setText(prefs.getString(ApiClient.PREF_GITHUB_TOKEN, ""))

        binding.tvGetApiKey.text =
            "Free key at openrouter.ai — no credit card required.\n" +
            "Model used: google/gemma-4-26b-a4b-it (also tries uncensored Dolphin & Hermes first)"

        // ── Save ───────────────────────────────────────────────────────────────
        binding.btnSave.setOnClickListener {
            val apiKey       = binding.etApiKey.text.toString().trim()
            val systemPrompt = binding.etSystemPrompt.text.toString().trim()
            val ghUsername   = binding.etGithubUsername.text.toString().trim()
            val ghToken      = binding.etGithubToken.text.toString().trim()

            apiClient.saveSettings(
                apiKey       = apiKey,
                systemPrompt = systemPrompt.ifEmpty { ApiClient.DEFAULT_SYSTEM_PROMPT }
            )
            prefs.edit()
                .putString(ApiClient.PREF_GITHUB_USERNAME, ghUsername)
                .putString(ApiClient.PREF_GITHUB_TOKEN, ghToken)
                .commit()

            Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnReset.setOnClickListener {
            binding.etSystemPrompt.setText(ApiClient.DEFAULT_SYSTEM_PROMPT)
        }

        // ── Clear chat history ─────────────────────────────────────────────────
        binding.btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Chat History")
                .setMessage("This will permanently delete all saved messages. Continue?")
                .setPositiveButton("Clear") { _, _ ->
                    chatStorage.clear()
                    Toast.makeText(this, "Chat history cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
