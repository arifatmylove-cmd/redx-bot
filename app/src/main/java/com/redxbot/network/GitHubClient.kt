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

    private fun authRequest(url: String) = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Accept", "application/vnd.github+json")

    // ── Repo ──────────────────────────────────────────────────────────────────

    suspend fun createRepo(name: String, description: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("name", name)
                put("description", description)
                put("private", false)
                put("auto_init", false)
            }.toString().toRequestBody("application/json".toMediaType())

            val req = authRequest("https://api.github.com/user/repos").post(body).build()
            val resp = http.newCall(req).execute()
            val rb = resp.body?.string() ?: ""
            // 422 = already exists — treat as OK
            if (!resp.isSuccessful && resp.code != 422)
                return@withContext Result.failure(Exception("Create repo failed (${resp.code}): $rb"))
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── File content ──────────────────────────────────────────────────────────

    /** Get the SHA of a file in the repo (returns null if file doesn't exist). */
    private fun getFileSha(repo: String, path: String): String? = try {
        val req = authRequest("https://api.github.com/repos/$owner/$repo/contents/$path").get().build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) null
        else JSONObject(resp.body?.string() ?: "{}").optString("sha").takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    suspend fun pushFile(repo: String, path: String, content: String, message: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                pushEncoded(repo, path, encoded, message)
            } catch (e: Exception) { Result.failure(e) }
        }

    suspend fun pushBinary(repo: String, path: String, bytes: ByteArray, message: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                pushEncoded(repo, path, encoded, message)
            } catch (e: Exception) { Result.failure(e) }
        }

    private fun pushEncoded(repo: String, path: String, b64: String, message: String): Result<Unit> {
        val sha = getFileSha(repo, path)
        val bodyObj = JSONObject().apply {
            put("message", message)
            put("content", b64)
            if (sha != null) put("sha", sha)
        }
        val req = authRequest("https://api.github.com/repos/$owner/$repo/contents/$path")
            .put(bodyObj.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = http.newCall(req).execute()
        return if (!resp.isSuccessful) {
            val rb = resp.body?.string() ?: ""
            Result.failure(Exception("Push $path failed (${resp.code}): $rb"))
        } else Result.success(Unit)
    }

    // ── Source repo browsing (public repo — no auth needed) ──────────────────

    data class TreeEntry(val path: String, val sha: String, val type: String)

    suspend fun getRepoTree(repoOwner: String, repoName: String, branch: String = "main"): Result<List<TreeEntry>> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://api.github.com/repos/$repoOwner/$repoName/git/trees/$branch?recursive=1"
                val req = authRequest(url).get().build()
                val resp = http.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("Tree failed: $body"))
                val tree = JSONObject(body).getJSONArray("tree")
                val entries = (0 until tree.length()).mapNotNull { i ->
                    val obj = tree.getJSONObject(i)
                    if (obj.getString("type") == "blob")
                        TreeEntry(obj.getString("path"), obj.getString("sha"), obj.getString("type"))
                    else null
                }
                Result.success(entries)
            } catch (e: Exception) { Result.failure(e) }
        }

    suspend fun getRawContent(repoOwner: String, repoName: String, branch: String, path: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://raw.githubusercontent.com/$repoOwner/$repoName/$branch/$path"
                val req = Request.Builder().url(url).get().build()
                val resp = http.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) Result.failure(Exception("Fetch $path failed (${resp.code})"))
                else Result.success(body)
            } catch (e: Exception) { Result.failure(e) }
        }

    // ── Workflow monitoring ───────────────────────────────────────────────────

    data class WorkflowRun(val id: Long, val status: String, val conclusion: String?)

    suspend fun getLatestRun(repo: String): Result<WorkflowRun> = withContext(Dispatchers.IO) {
        try {
            val req = authRequest("https://api.github.com/repos/$owner/$repo/actions/runs?per_page=1").get().build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            val runs = JSONObject(body).getJSONArray("workflow_runs")
            if (runs.length() == 0) return@withContext Result.failure(Exception("No runs yet"))
            val r = runs.getJSONObject(0)
            Result.success(WorkflowRun(r.getLong("id"), r.getString("status"), r.optString("conclusion").takeIf { it.isNotEmpty() }))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getErrorLines(runId: Long, repo: String): String = withContext(Dispatchers.IO) {
        try {
            val jobsReq = authRequest("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/jobs").get().build()
            val jobsBody = http.newCall(jobsReq).execute().body?.string() ?: return@withContext ""
            val jobId = JSONObject(jobsBody).getJSONArray("jobs").getJSONObject(0).getLong("id")
            val logsReq = authRequest("https://api.github.com/repos/$owner/$repo/actions/jobs/$jobId/logs").get().build()
            val logs = http.newCall(logsReq).execute().body?.string() ?: ""
            logs.lines().filter { l ->
                l.contains("error", ignoreCase = true) ||
                l.contains("FAILED", ignoreCase = true) ||
                l.contains("Exception")
            }.takeLast(20).joinToString("\n")
        } catch (e: Exception) { "Could not fetch logs: ${e.message}" }
    }

    // ── Validate token ────────────────────────────────────────────────────────

    suspend fun validateToken(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val req = authRequest("https://api.github.com/user").get().build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) return@withContext Result.failure(Exception("Invalid token (${resp.code})"))
            val login = JSONObject(body).optString("login", "")
            if (login.isEmpty()) Result.failure(Exception("Could not get GitHub username"))
            else Result.success(login)
        } catch (e: Exception) { Result.failure(e) }
    }
}
