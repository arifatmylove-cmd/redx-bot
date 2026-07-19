package com.redxbot.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GitHubClient(val token: String, val owner: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun auth(url: String) = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Accept", "application/vnd.github+json")
        .addHeader("X-GitHub-Api-Version", "2022-11-28")

    // ── Repo ──────────────────────────────────────────────────────────────────

    suspend fun createRepo(name: String, description: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("name", name); put("description", description)
                put("private", false); put("auto_init", false)
            }.toString().toRequestBody("application/json".toMediaType())
            val resp = http.newCall(auth("https://api.github.com/user/repos").post(body).build()).execute()
            val rb   = resp.body?.string() ?: ""
            if (!resp.isSuccessful && resp.code != 422) Result.failure(Exception("Create repo failed (${resp.code}): $rb"))
            else Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── File operations ───────────────────────────────────────────────────────

    private fun getFileSha(repo: String, path: String): String? = try {
        val resp = http.newCall(auth("https://api.github.com/repos/$owner/$repo/contents/$path").get().build()).execute()
        if (!resp.isSuccessful) null
        else JSONObject(resp.body?.string() ?: "{}").optString("sha").takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    suspend fun pushFile(repo: String, path: String, content: String, message: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                pushEncoded(repo, path, Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP), message)
            } catch (e: Exception) { Result.failure(e) }
        }

    suspend fun pushBinary(repo: String, path: String, bytes: ByteArray, message: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try { pushEncoded(repo, path, Base64.encodeToString(bytes, Base64.NO_WRAP), message) }
            catch (e: Exception) { Result.failure(e) }
        }

    private fun pushEncoded(repo: String, path: String, b64: String, message: String): Result<Unit> {
        val sha = getFileSha(repo, path)
        val obj = JSONObject().apply { put("message", message); put("content", b64); if (sha != null) put("sha", sha) }
        val resp = http.newCall(
            auth("https://api.github.com/repos/$owner/$repo/contents/$path")
                .put(obj.toString().toRequestBody("application/json".toMediaType())).build()
        ).execute()
        return if (!resp.isSuccessful) Result.failure(Exception("Push $path (${resp.code}): ${resp.body?.string()?.take(200)}"))
        else Result.success(Unit)
    }

    /**
     * Read a file from the target repo and return its decoded text.
     * Used by the auto-fix loop to fetch the current broken file content.
     */
    suspend fun getFileContent(repo: String, path: String): String? = withContext(Dispatchers.IO) {
        try {
            val resp = http.newCall(auth("https://api.github.com/repos/$owner/$repo/contents/$path").get().build()).execute()
            if (!resp.isSuccessful) return@withContext null
            val encoded = JSONObject(resp.body?.string() ?: "{}").getString("content")
                .replace("\n", "").replace(" ", "")
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    // ── Template browsing ─────────────────────────────────────────────────────

    data class TreeEntry(val path: String, val sha: String, val type: String)

    suspend fun getRepoTree(repoOwner: String, repoName: String, branch: String = "main"): Result<List<TreeEntry>> =
        withContext(Dispatchers.IO) {
            try {
                val url  = "https://api.github.com/repos/$repoOwner/$repoName/git/trees/$branch?recursive=1"
                val resp = http.newCall(auth(url).get().build()).execute()
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("Tree failed: $body"))
                val tree = JSONObject(body).getJSONArray("tree")
                Result.success((0 until tree.length()).mapNotNull { i ->
                    val o = tree.getJSONObject(i)
                    if (o.getString("type") == "blob") TreeEntry(o.getString("path"), o.getString("sha"), "blob") else null
                })
            } catch (e: Exception) { Result.failure(e) }
        }

    suspend fun getRawContent(repoOwner: String, repoName: String, branch: String, path: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val url  = "https://raw.githubusercontent.com/$repoOwner/$repoName/$branch/$path"
                val resp = http.newCall(Request.Builder().url(url).get().build()).execute()
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) Result.failure(Exception("Fetch $path (${resp.code})"))
                else Result.success(body)
            } catch (e: Exception) { Result.failure(e) }
        }

    // ── Workflow monitoring ───────────────────────────────────────────────────

    data class WorkflowRun(val id: Long, val status: String, val conclusion: String?)

    suspend fun getLatestRun(repo: String): Result<WorkflowRun> = withContext(Dispatchers.IO) {
        try {
            val resp = http.newCall(auth("https://api.github.com/repos/$owner/$repo/actions/runs?per_page=1").get().build()).execute()
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) return@withContext Result.failure(Exception("Runs error: $body"))
            val runs = JSONObject(body).getJSONArray("workflow_runs")
            if (runs.length() == 0) return@withContext Result.failure(Exception("No runs yet"))
            val r = runs.getJSONObject(0)
            Result.success(WorkflowRun(r.getLong("id"), r.getString("status"), r.optString("conclusion").takeIf { it.isNotEmpty() }))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getRunById(repo: String, runId: Long): Result<WorkflowRun> = withContext(Dispatchers.IO) {
        try {
            val resp = http.newCall(auth("https://api.github.com/repos/$owner/$repo/actions/runs/$runId").get().build()).execute()
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) return@withContext Result.failure(Exception("Run error: $body"))
            val r = JSONObject(body)
            Result.success(WorkflowRun(r.getLong("id"), r.getString("status"), r.optString("conclusion").takeIf { it.isNotEmpty() }))
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Fetches the job log and returns lines relevant to compilation errors.
     */
    suspend fun getFullErrorLog(runId: Long, repo: String): String = withContext(Dispatchers.IO) {
        try {
            val jobsBody = http.newCall(auth("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/jobs").get().build())
                .execute().body?.string() ?: return@withContext "No jobs found"
            val jobId = JSONObject(jobsBody).getJSONArray("jobs").getJSONObject(0).getLong("id")
            val logs  = http.newCall(auth("https://api.github.com/repos/$owner/$repo/actions/jobs/$jobId/logs").get().build())
                .execute().body?.string() ?: ""
            logs.lines().filter { l ->
                l.contains("error", true) || l.contains("FAILED") || l.contains("Exception") ||
                l.contains("e: ") || l.contains(": error:") || l.contains("Unresolved reference") ||
                l.contains("None of the following") || l.contains("warning:")
            }.take(80).joinToString("\n")
        } catch (e: Exception) { "Log fetch failed: ${e.message}" }
    }

    // ── Token validation ──────────────────────────────────────────────────────

    suspend fun validateToken(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val resp = http.newCall(auth("https://api.github.com/user").get().build()).execute()
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) return@withContext Result.failure(Exception("Invalid token (${resp.code})"))
            val login = JSONObject(body).optString("login", "")
            if (login.isEmpty()) Result.failure(Exception("Could not read GitHub username"))
            else Result.success(login)
        } catch (e: Exception) { Result.failure(e) }
    }
}
