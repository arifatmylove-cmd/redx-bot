package com.redxbot.builder

import android.content.Context
import com.redxbot.network.ApiClient
import com.redxbot.network.GitHubClient
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Orchestrates building a custom AI chatbot APK via GitHub Actions.
 *
 * The generated app is a customized clone of the RedxBot codebase: same proven
 * compile-time structure, but with the user's chosen name, colours and AI persona
 * baked in. Every generated app is itself a full AI chatbot.
 */
class AppBuilderService(private val context: Context) {

    companion object {
        private const val TEMPLATE_OWNER = "arifatmylove-cmd"
        private const val TEMPLATE_REPO  = "redx-bot"
        private const val TEMPLATE_BRANCH = "main"
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    suspend fun build(
        description: String,
        githubOwner: String,
        githubToken: String,
        onProgress: (String) -> Unit
    ): Result<String> {

        val gh = GitHubClient(githubToken, githubOwner)

        // ── 1. Validate token ─────────────────────────────────────────────────
        onProgress("🔑 Validating GitHub token…")
        val tokenCheck = gh.validateToken()
        if (tokenCheck.isFailure)
            return Result.failure(Exception("GitHub token error: ${tokenCheck.exceptionOrNull()?.message}"))
        val resolvedOwner = tokenCheck.getOrNull()!!
        onProgress("✅ Authenticated as @$resolvedOwner")

        // ── 2. Generate AppSpec via AI ────────────────────────────────────────
        onProgress("🤖 Asking AI to design your app…")
        val apiClient = ApiClient(context)
        val specResult = apiClient.generateAppSpec(description)
        if (specResult.isFailure)
            return Result.failure(Exception("AI spec generation failed: ${specResult.exceptionOrNull()?.message}"))
        val spec = specResult.getOrNull()!!
        onProgress("✅ App designed: \"${spec.appName}\" (${spec.primaryColor})")

        // ── 3. Create GitHub repo ─────────────────────────────────────────────
        val repoName = spec.packageSuffix + "-bot"
        onProgress("📁 Creating GitHub repo: $repoName…")
        val repoResult = gh.createRepo(repoName, spec.appDescription)
        if (repoResult.isFailure)
            return Result.failure(Exception("Repo creation failed: ${repoResult.exceptionOrNull()?.message}"))
        onProgress("✅ Repo ready: github.com/$resolvedOwner/$repoName")

        // ── 4. Fetch template file tree ───────────────────────────────────────
        onProgress("📥 Fetching template project files…")
        val ghTemplate = GitHubClient(githubToken, TEMPLATE_OWNER)
        val treeResult = ghTemplate.getRepoTree(TEMPLATE_OWNER, TEMPLATE_REPO, TEMPLATE_BRANCH)
        if (treeResult.isFailure)
            return Result.failure(Exception("Could not read template: ${treeResult.exceptionOrNull()?.message}"))
        val tree = treeResult.getOrNull()!!
        onProgress("✅ Found ${tree.size} template files")

        // ── 5. Push customised files ──────────────────────────────────────────
        var pushed = 0
        val ghNew = GitHubClient(githubToken, resolvedOwner)
        for (entry in tree) {
            val srcPath = entry.path

            // Fetch raw content
            val contentResult = ghTemplate.getRawContent(TEMPLATE_OWNER, TEMPLATE_REPO, TEMPLATE_BRANCH, srcPath)
            if (contentResult.isFailure) {
                onProgress("⚠️  Skipping $srcPath (fetch error)")
                continue
            }
            val rawContent = contentResult.getOrNull()!!

            // Customise content
            val customContent = applySubstitutions(rawContent, spec)

            // Determine destination path (rename Java package directories)
            val destPath = srcPath.replace("com/redxbot/", "com/${spec.packageSuffix}/")

            val pushResult = ghNew.pushFile(
                repo = repoName,
                path = destPath,
                content = customContent,
                message = "add: $destPath"
            )
            if (pushResult.isFailure) {
                onProgress("⚠️  Failed to push $destPath: ${pushResult.exceptionOrNull()?.message}")
            } else {
                pushed++
                if (pushed % 5 == 0 || pushed == tree.size)
                    onProgress("📤 Pushed $pushed / ${tree.size} files…")
            }
        }
        onProgress("✅ All $pushed files pushed — build triggered!")

        // ── 6. Monitor GitHub Actions workflow ────────────────────────────────
        onProgress("⏳ Waiting for GitHub Actions to start…")
        delay(15_000) // wait for the trigger to register

        var attempt = 0
        while (attempt < 60) {
            attempt++
            delay(10_000)

            val runResult = ghNew.getLatestRun(repoName)
            if (runResult.isFailure) {
                onProgress("⏳ Waiting for runner (${attempt * 10}s)…")
                continue
            }
            val run = runResult.getOrNull()!!
            when (run.status) {
                "queued"      -> onProgress("🔁 Build queued (${attempt * 10}s)…")
                "in_progress" -> onProgress("🔨 Building APK (${attempt * 10}s)…")
                "completed"   -> {
                    return if (run.conclusion == "success") {
                        val url = "https://github.com/$resolvedOwner/$repoName/actions"
                        onProgress("🎉 Build SUCCEEDED!")
                        onProgress("📦 Download your APK at:\n$url")
                        Result.success(url)
                    } else {
                        val errors = ghNew.getErrorLines(run.id, repoName)
                        onProgress("❌ Build FAILED. Errors:\n$errors")
                        Result.failure(Exception("Build failed — check Actions logs at https://github.com/$resolvedOwner/$repoName/actions"))
                    }
                }
            }
        }
        return Result.failure(Exception("Build timed out after 10 minutes"))
    }

    // ── Substitutions ─────────────────────────────────────────────────────────

    private fun applySubstitutions(content: String, spec: AppSpec): String {
        val safeName = spec.appName.filter { it.isLetterOrDigit() }
        return content
            // Package references (code & build files)
            .replace("package com.redxbot", "package com.${spec.packageSuffix}")
            .replace("import com.redxbot", "import com.${spec.packageSuffix}")
            .replace("com.redxbot", "com.${spec.packageSuffix}")
            // Human-readable name
            .replace("RedxBot", spec.appName)
            .replace("Redx Bot", spec.appName)
            .replace("redx-bot", spec.packageSuffix + "-bot")
            .replace("redxbot", spec.packageSuffix)
            // SharedPreferences key so data doesn't bleed between apps
            .replace("RedxBotPrefs", "${safeName}Prefs")
            // Icon letter (in ic_launcher_foreground.xml)
            .replace(">R<", ">${spec.iconLetter}<")
            // Brand colour
            .replace("#7C4DFF", spec.primaryColor)
            // System prompt
            .replace(ApiClient.DEFAULT_SYSTEM_PROMPT, spec.systemPrompt)
            // Workflow artifact name
            .replace("redxbot-debug-apk", "${spec.packageSuffix}-debug-apk")
            .replace("Build RedxBot APK", "Build ${spec.appName} APK")
    }
}
