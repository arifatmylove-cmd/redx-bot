package com.redxbot.builder

import android.content.Context
import com.redxbot.network.ApiClient
import com.redxbot.network.GitHubClient
import kotlinx.coroutines.delay

/**
 * Orchestrates building a fully-customised AI app APK via GitHub Actions.
 *
 * Build pipeline:
 *  1.  Validate GitHub token
 *  2.  AI designs app spec  (name / colour / icon / persona)
 *  3.  Create GitHub repo
 *  4.  AI generates the workflow YAML (just like a human would write it)
 *  5.  AI generates custom Kotlin + XML source files for app-specific features
 *  6.  Clone RedxBot template → apply substitutions
 *  7.  Inject AI-generated files, extra permissions, extra Gradle deps
 *  8.  Push everything → Actions build triggered
 *  9.  Monitor → on failure: fetch log → AI identifies broken files →
 *      AI fixes them → repush → re-monitor  (up to MAX_FIX_ATTEMPTS rounds)
 * 10.  Report success + APK download URL
 */
class AppBuilderService(private val context: Context) {

    companion object {
        private const val TEMPLATE_OWNER  = "arifatmylove-cmd"
        private const val TEMPLATE_REPO   = "redx-bot"
        private const val TEMPLATE_BRANCH = "main"
        private const val MAX_FIX_ATTEMPTS = 5
        private const val POLL_MS          = 12_000L
        private const val MAX_POLLS        = 75          // ~15 min
        private const val TRIGGER_WAIT_MS  = 20_000L
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    suspend fun build(
        description:  String,
        githubOwner:  String,
        githubToken:  String,
        onProgress:   (String) -> Unit
    ): Result<String> {

        val api      = ApiClient(context)
        val gh       = GitHubClient(githubToken, githubOwner)

        // 1. Validate token
        onProgress("🔑 Validating GitHub token…")
        val tokenR = gh.validateToken()
        if (tokenR.isFailure)
            return Result.failure(Exception("GitHub token invalid: ${tokenR.exceptionOrNull()?.message}"))
        val resolvedOwner = tokenR.getOrNull()!!
        val ghNew = GitHubClient(githubToken, resolvedOwner)
        onProgress("✅ Authenticated as @$resolvedOwner")

        // 2. AI designs app spec
        onProgress("🤖 AI is designing your app…")
        val specR = api.generateAppSpec(description)
        if (specR.isFailure)
            return Result.failure(Exception("AI spec failed: ${specR.exceptionOrNull()?.message}"))
        val spec = specR.getOrNull()!!
        onProgress("✅ App: \"${spec.appName}\" | pkg: com.${spec.packageSuffix} | color: ${spec.primaryColor}")

        // 3. Create repo
        val repoName = spec.packageSuffix.take(20) + "-bot"
        onProgress("📁 Creating repo: $resolvedOwner/$repoName…")
        val repoR = ghNew.createRepo(repoName, spec.appDescription)
        if (repoR.isFailure)
            return Result.failure(Exception("Repo creation failed: ${repoR.exceptionOrNull()?.message}"))
        onProgress("✅ Repo created → github.com/$resolvedOwner/$repoName")

        // 4. AI generates workflow YAML
        val artifactName = "${spec.packageSuffix}-debug-apk"
        onProgress("⚙️  AI is writing the GitHub Actions workflow…")
        val workflowR = api.generateWorkflow(spec.appName, spec.packageSuffix, artifactName)
        val workflowYaml = if (workflowR.isSuccess) {
            onProgress("✅ Workflow written by AI")
            workflowR.getOrNull()!!
        } else {
            onProgress("⚠️  AI workflow failed — using proven fallback")
            fallbackWorkflow(spec.appName, artifactName)
        }

        // 5. AI generates custom feature files
        onProgress("🧠 AI is generating custom source code for your app features…")
        val codeR = api.generateAppCode(description, spec.appName, spec.packageSuffix, spec.primaryColor)
        val customCode = codeR.getOrNull() ?: AppCodeOutput()
        if (customCode.files.isNotEmpty()) {
            onProgress("✅ AI generated ${customCode.files.size} custom file(s): ${customCode.files.map { it.path.substringAfterLast("/") }}")
        } else {
            onProgress("ℹ️  No extra screens needed — base chat template is sufficient")
        }
        if (customCode.permissions.isNotEmpty())
            onProgress("🔐 Extra permissions: ${customCode.permissions.joinToString()}")
        if (customCode.gradleDependencies.isNotEmpty())
            onProgress("📦 Extra dependencies: ${customCode.gradleDependencies.joinToString()}")

        // 6. Fetch template tree
        onProgress("📥 Fetching RedxBot template…")
        val ghTemplate = GitHubClient(githubToken, TEMPLATE_OWNER)
        val treeR = ghTemplate.getRepoTree(TEMPLATE_OWNER, TEMPLATE_REPO, TEMPLATE_BRANCH)
        if (treeR.isFailure)
            return Result.failure(Exception("Template fetch failed: ${treeR.exceptionOrNull()?.message}"))
        val tree = treeR.getOrNull()!!
        onProgress("✅ ${tree.size} template files fetched")

        // 7. Push template files (customised) + AI files
        onProgress("📤 Pushing files to GitHub…")
        var pushed = 0
        for (entry in tree) {
            val src = entry.path
            // Skip workflow — replaced by AI version below
            if (src == ".github/workflows/build.yml") continue

            val contentR = ghTemplate.getRawContent(TEMPLATE_OWNER, TEMPLATE_REPO, TEMPLATE_BRANCH, src)
            if (contentR.isFailure) continue
            var content = applySubstitutions(contentR.getOrNull()!!, spec)

            // Inject extra permissions into AndroidManifest
            if (src == "app/src/main/AndroidManifest.xml" && customCode.permissions.isNotEmpty()) {
                content = injectPermissions(content, customCode.permissions)
            }
            // Inject extra activities into AndroidManifest
            if (src == "app/src/main/AndroidManifest.xml" && customCode.manifestActivities.isNotEmpty()) {
                content = injectActivities(content, customCode.manifestActivities)
            }
            // Inject extra Gradle dependencies into app/build.gradle
            if (src == "app/build.gradle" && customCode.gradleDependencies.isNotEmpty()) {
                content = injectGradleDeps(content, customCode.gradleDependencies)
            }
            // Inject menu items into MainActivity if needed
            if (src == "app/src/main/java/com/redxbot/MainActivity.kt" && customCode.menuItems.isNotEmpty()) {
                content = injectMenuItems(content, customCode.menuItems, spec.packageSuffix)
            }

            val dest = src.replace("com/redxbot/", "com/${spec.packageSuffix}/")
            val pr   = ghNew.pushFile(repoName, dest, content, "init: $dest")
            if (pr.isSuccess) {
                pushed++
                if (pushed % 10 == 0) onProgress("📤 $pushed / ${tree.size} files pushed…")
            }
        }

        // Push AI-generated custom files
        for (gf in customCode.files) {
            val pr = ghNew.pushFile(repoName, gf.path, gf.content, "feat: AI-generated ${gf.path.substringAfterLast("/")}")
            if (pr.isSuccess) {
                pushed++
                onProgress("🤖 Pushed AI file: ${gf.path.substringAfterLast("/")}")
            } else {
                onProgress("⚠️  Failed to push ${gf.path.substringAfterLast("/")}: ${pr.exceptionOrNull()?.message?.take(80)}")
            }
        }

        // Push AI-generated workflow
        ghNew.pushFile(repoName, ".github/workflows/build.yml", workflowYaml, "ci: AI-generated workflow")
        onProgress("✅ All $pushed files pushed — Actions build triggered!")

        // 8. Monitor + auto-fix loop
        return monitorAndFix(ghNew, api, repoName, resolvedOwner, spec, onProgress, null, 0)
    }

    // ── Monitor + AI error-fix recursive loop ─────────────────────────────────

    private suspend fun monitorAndFix(
        gh:            GitHubClient,
        api:           ApiClient,
        repoName:      String,
        resolvedOwner: String,
        spec:          AppSpec,
        onProgress:    (String) -> Unit,
        previousRunId: Long?,
        fixAttempt:    Int
    ): Result<String> {

        onProgress("⏳ Waiting for GitHub Actions to pick up the build…")
        delay(TRIGGER_WAIT_MS)

        // Wait for a new run (different ID from last failed one)
        var run: GitHubClient.WorkflowRun? = null
        repeat(24) {
            if (run != null) return@repeat
            val r = gh.getLatestRun(repoName)
            if (r.isSuccess) {
                val c = r.getOrNull()!!
                if (previousRunId == null || c.id != previousRunId) { run = c; return@repeat }
            }
            delay(5_000L)
        }
        if (run == null) return Result.failure(Exception("Actions did not start a build within 2 minutes"))
        onProgress("🔨 Build started (run #${run!!.id})")

        // Poll until completed
        for (poll in 1..MAX_POLLS) {
            delay(POLL_MS)
            val upd = gh.getRunById(repoName, run!!.id)
            if (upd.isFailure) { onProgress("⏳ Polling… (${poll * POLL_MS / 1000}s)"); continue }
            val r = upd.getOrNull()!!
            val elapsed = "${poll * POLL_MS / 1000}s"
            when (r.status) {
                "queued"      -> onProgress("🔁 Queued ($elapsed)…")
                "in_progress" -> onProgress("🔨 Building… ($elapsed)")
                "completed"   -> {
                    if (r.conclusion == "success") {
                        val url = "https://github.com/$resolvedOwner/$repoName/actions/runs/${r.id}"
                        onProgress("🎉 BUILD SUCCEEDED!")
                        onProgress("📦 Go to Actions → run #${r.id} → Artifacts → download ${spec.packageSuffix}-debug-apk")
                        onProgress("🔗 $url")
                        return Result.success(url)
                    }

                    // ── Build failed — AI auto-fix ────────────────────────────
                    onProgress("❌ Build failed (fix attempt ${fixAttempt + 1}/$MAX_FIX_ATTEMPTS)")
                    if (fixAttempt >= MAX_FIX_ATTEMPTS) {
                        val log = gh.getFullErrorLog(r.id, repoName)
                        onProgress("🛑 Max fix attempts reached.\nLast errors:\n$log")
                        return Result.failure(Exception(
                            "Build failed after $MAX_FIX_ATTEMPTS fix attempts.\nLogs: https://github.com/$resolvedOwner/$repoName/actions"
                        ))
                    }

                    onProgress("🔍 Fetching error log from GitHub…")
                    val errorLog = gh.getFullErrorLog(r.id, repoName)
                    onProgress("📋 Compiler output:\n${errorLog.take(1000)}")

                    val filesToFix = parseFailingFiles(errorLog, spec.packageSuffix)
                    if (filesToFix.isEmpty()) {
                        return Result.failure(Exception(
                            "Could not identify broken files from error log.\nCheck: https://github.com/$resolvedOwner/$repoName/actions"
                        ))
                    }
                    onProgress("🎯 Broken files (${filesToFix.size}): ${filesToFix.joinToString { it.substringAfterLast("/") }}")

                    var anyFixed = false
                    for (filePath in filesToFix) {
                        onProgress("🤖 AI is reading and fixing: ${filePath.substringAfterLast("/")}…")
                        val current = gh.getFileContent(repoName, filePath)
                        if (current == null) { onProgress("⚠️  Cannot read $filePath from repo"); continue }

                        val fixR = api.fixBuildError(errorLog, filePath, current, spec.packageSuffix, spec.appName)
                        if (fixR.isFailure) { onProgress("⚠️  AI fix failed: ${fixR.exceptionOrNull()?.message?.take(80)}"); continue }

                        val pushR = gh.pushFile(repoName, filePath, fixR.getOrNull()!!, "fix(ai): attempt ${fixAttempt + 1} — ${filePath.substringAfterLast("/")}")
                        if (pushR.isSuccess) {
                            onProgress("✅ AI fixed and pushed: ${filePath.substringAfterLast("/")}")
                            anyFixed = true
                        } else {
                            onProgress("⚠️  Push failed for ${filePath.substringAfterLast("/")}: ${pushR.exceptionOrNull()?.message?.take(60)}")
                        }
                    }

                    if (!anyFixed) return Result.failure(Exception(
                        "Auto-fix could not patch any file.\nCheck: https://github.com/$resolvedOwner/$repoName/actions"
                    ))

                    onProgress("🔄 Fix #${fixAttempt + 1} pushed — monitoring new build…")
                    return monitorAndFix(gh, api, repoName, resolvedOwner, spec, onProgress, r.id, fixAttempt + 1)
                }
            }
        }
        return Result.failure(Exception("Build timed out after ${MAX_POLLS * POLL_MS / 60_000} minutes"))
    }

    // ── Parse broken files from error log ─────────────────────────────────────

    private fun parseFailingFiles(errorLog: String, packageSuffix: String): List<String> {
        val found = mutableSetOf<String>()
        // e: file:///home/runner/work/<repo>/<repo>/app/src/...
        Regex("""e: file:///[^\s]+?/(app/src/main/java/[^\s:]+\.kt)""").findAll(errorLog).forEach { found.add(it.groupValues[1]) }
        Regex("""/home/runner/work/[^/]+/[^/]+/(app/src/main/java/[^\s:]+\.kt)""").findAll(errorLog).forEach { found.add(it.groupValues[1]) }
        Regex("""(app/src/main/java/[^\s:'"]+\.kt)""").findAll(errorLog).forEach { found.add(it.value) }
        if (errorLog.contains(".github/workflows/build.yml") || (errorLog.contains("workflow") && errorLog.contains("syntax")))
            found.add(".github/workflows/build.yml")
        return found
            .map { it.replace("com/redxbot/", "com/$packageSuffix/") }
            .distinct().take(4)
    }

    // ── Template string substitutions ─────────────────────────────────────────

    private fun applySubstitutions(content: String, spec: AppSpec): String {
        val safe = spec.appName.filter { it.isLetterOrDigit() }
        return content
            .replace("package com.redxbot", "package com.${spec.packageSuffix}")
            .replace("import com.redxbot",  "import com.${spec.packageSuffix}")
            .replace("com.redxbot",          "com.${spec.packageSuffix}")
            .replace("RedxBot", spec.appName).replace("Redx Bot", spec.appName)
            .replace("redx-bot", "${spec.packageSuffix}-bot").replace("redxbot", spec.packageSuffix)
            .replace("RedxBotPrefs", "${safe}Prefs")
            .replace(">R<", ">${spec.iconLetter}<")
            .replace("#7C4DFF", spec.primaryColor)
            .replace(ApiClient.DEFAULT_SYSTEM_PROMPT, spec.systemPrompt)
            .replace("redxbot-debug-apk", "${spec.packageSuffix}-debug-apk")
            .replace("Build RedxBot APK", "Build ${spec.appName} APK")
    }

    // ── Manifest / Gradle injection helpers ───────────────────────────────────

    private fun injectPermissions(manifest: String, perms: List<String>): String {
        val lines  = perms.joinToString("\n") { "    <uses-permission android:name=\"$it\" />" }
        return manifest.replace("<application", "$lines\n\n    <application")
    }

    private fun injectActivities(manifest: String, activities: List<String>): String {
        val lines = activities.joinToString("\n        ")
        return manifest.replace("    </application>", "        $lines\n    </application>")
    }

    private fun injectGradleDeps(gradle: String, deps: List<String>): String {
        val lines = deps.joinToString("\n    ") { "implementation '$it'" }
        return gradle.replace("}", "    $lines\n}", 1)
    }

    private fun injectMenuItems(mainActivity: String, items: List<GeneratedMenuItem>, pkg: String): String {
        // Inject menu additions before the last companion object/closing brace
        val menuAdditions = items.joinToString("\n        ") { item ->
            "menu.add(0, ${item.activityClass.replace(".", "_").uppercase()}_ID, 99, \"${item.title}\")"
        }
        val handlerAdditions = items.joinToString("\n            ") { item ->
            "${item.activityClass.replace(".", "_").uppercase()}_ID -> { startActivity(Intent(this, com.$pkg${item.activityClass}::class.java)); true }"
        }
        return mainActivity
            .replace("menu.add(0, MENU_CLEAR,    2, \"🗑 Clear Chat\")",
                "menu.add(0, MENU_CLEAR,    2, \"🗑 Clear Chat\")\n        $menuAdditions")
            .replace("else -> super.onOptionsItemSelected(item)",
                "$handlerAdditions\n            else -> super.onOptionsItemSelected(item)")
    }

    // ── Fallback workflow (if AI generation fails) ────────────────────────────

    private fun fallbackWorkflow(appName: String, artifactName: String) = """
name: Build $appName APK

on:
  push:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Cache Gradle
        uses: actions/cache@v3
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${'$'}{{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}

      - name: Build APK
        run: |
          chmod +x gradlew
          ./gradlew assembleDebug --no-daemon --stacktrace

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: $artifactName
          path: app/build/outputs/apk/debug/*.apk
""".trimIndent()
}
