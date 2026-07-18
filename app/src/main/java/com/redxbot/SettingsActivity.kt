package com.redxbot

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.redxbot.databinding.ActivitySettingsBinding
import com.redxbot.network.ApiClient

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var apiClient: ApiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        apiClient = ApiClient(this)

        // Load current values
        binding.etApiKey.setText(apiClient.getApiKey())
        binding.etSystemPrompt.setText(apiClient.getSystemPrompt())

        binding.tvGetApiKey.text = "Get a free API key at openrouter.ai — no credit card required.\nFree models include:\n• meta-llama/llama-3.1-8b-instruct:free (text)\n• meta-llama/llama-3.2-11b-vision-instruct:free (images)"

        binding.btnSave.setOnClickListener {
            val apiKey = binding.etApiKey.text.toString().trim()
            val systemPrompt = binding.etSystemPrompt.text.toString().trim()
            apiClient.saveSettings(
                apiKey = apiKey,
                systemPrompt = systemPrompt.ifEmpty { ApiClient.DEFAULT_SYSTEM_PROMPT }
            )
            Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnReset.setOnClickListener {
            binding.etSystemPrompt.setText(ApiClient.DEFAULT_SYSTEM_PROMPT)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
