package com.redxbot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.redxbot.builder.AppBuilderService
import com.redxbot.databinding.ActivityAppBuilderBinding
import com.redxbot.network.ApiClient
import kotlinx.coroutines.launch

class AppBuilderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppBuilderBinding
    private val log = StringBuilder()
    private var resultUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppBuilderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "🔨 App Builder"

        // Pre-fill GitHub credentials from settings
        val prefs = getSharedPreferences(ApiClient.PREFS_NAME, Context.MODE_PRIVATE)
        binding.etGithubUsername.setText(prefs.getString(ApiClient.PREF_GITHUB_USERNAME, ""))
        binding.etGithubToken.setText(prefs.getString(ApiClient.PREF_GITHUB_TOKEN, ""))

        binding.btnBuild.setOnClickListener { startBuild() }
        binding.btnOpenActions.setOnClickListener {
            resultUrl?.let { url ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        binding.btnCopyUrl.setOnClickListener {
            resultUrl?.let { url ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("APK URL", url))
                Toast.makeText(this, "URL copied!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startBuild() {
        val description = binding.etDescription.text.toString().trim()
        val username    = binding.etGithubUsername.text.toString().trim()
        val token       = binding.etGithubToken.text.toString().trim()

        if (description.isEmpty()) { binding.etDescription.error = "Describe your app"; return }
        if (username.isEmpty())    { binding.etGithubUsername.error = "Enter GitHub username"; return }
        if (token.isEmpty())       { binding.etGithubToken.error = "Enter GitHub token"; return }

        // Save credentials for next time
        getSharedPreferences(ApiClient.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(ApiClient.PREF_GITHUB_USERNAME, username)
            .putString(ApiClient.PREF_GITHUB_TOKEN, token)
            .commit()

        // Switch to progress view
        binding.formContainer.visibility = View.GONE
        binding.progressContainer.visibility = View.VISIBLE
        binding.btnBuild.isEnabled = false
        log.clear()
        binding.tvLog.text = ""

        lifecycleScope.launch {
            val service = AppBuilderService(this@AppBuilderActivity)
            val result = service.build(
                description   = description,
                githubOwner   = username,
                githubToken   = token,
                onProgress    = { msg ->
                    runOnUiThread {
                        log.appendLine(msg)
                        binding.tvLog.text = log.toString()
                        // Auto-scroll
                        binding.scrollLog.post {
                            binding.scrollLog.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }
            )

            runOnUiThread {
                if (result.isSuccess) {
                    resultUrl = result.getOrNull()
                    binding.resultCard.visibility = View.VISIBLE
                    binding.tvResultTitle.text = "✅ APK Ready!"
                    binding.tvResultMessage.text =
                        "Your app compiled successfully.\nOpen the Actions tab on GitHub to download the APK artifact."
                    binding.tvResultMessage.setTextColor(ContextCompat.getColor(this@AppBuilderActivity, R.color.accent))
                    binding.btnOpenActions.visibility = View.VISIBLE
                    binding.btnCopyUrl.visibility = View.VISIBLE
                } else {
                    binding.resultCard.visibility = View.VISIBLE
                    binding.tvResultTitle.text = "❌ Build Failed"
                    binding.tvResultMessage.text = result.exceptionOrNull()?.message ?: "Unknown error"
                    binding.tvResultMessage.setTextColor(ContextCompat.getColor(this@AppBuilderActivity, R.color.error))
                    // Allow retry — go back to form
                    binding.btnRetry.visibility = View.VISIBLE
                    binding.btnRetry.setOnClickListener {
                        binding.formContainer.visibility = View.VISIBLE
                        binding.progressContainer.visibility = View.GONE
                        binding.resultCard.visibility = View.GONE
                        binding.btnBuild.isEnabled = true
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
