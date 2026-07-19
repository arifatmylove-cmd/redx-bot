package com.redxbot.storage

import android.content.Context
import com.redxbot.model.Message
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Stores multiple independent chat sessions in SharedPreferences.
 *
 * Key layout:
 *   "conv_index"   → JSON array [{id, title, updatedAt}, ...]  (sorted newest-first)
 *   "conv_<id>"    → JSON array of Message objects for that session
 */
class ConversationStorage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME    = "RedxBotPrefs"
        private const val KEY_INDEX = "conv_index"
        private const val MAX_MSGS  = 500
        private const val MAX_CONVS = 100
    }

    data class ConvMeta(val id: String, val title: String, val updatedAt: Long)

    // ── Index ─────────────────────────────────────────────────────────────────

    fun listConversations(): List<ConvMeta> {
        val raw = prefs.getString(KEY_INDEX, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ConvMeta(o.getString("id"), o.optString("title", "New Chat"), o.optLong("updatedAt"))
            }.sortedByDescending { it.updatedAt }
        } catch (e: Exception) { emptyList() }
    }

    fun createConversation(): String {
        val id   = UUID.randomUUID().toString()
        val list = listConversations().toMutableList()
        list.add(0, ConvMeta(id, "New Chat", System.currentTimeMillis()))
        while (list.size > MAX_CONVS) {
            prefs.edit().remove("conv_${list.removeLast().id}").commit()
        }
        saveIndex(list)
        return id
    }

    fun deleteConversation(id: String) {
        saveIndex(listConversations().filter { it.id != id })
        prefs.edit().remove("conv_$id").commit()
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    fun loadMessages(id: String): MutableList<Message> {
        val raw = prefs.getString("conv_$id", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapTo(mutableListOf()) { i ->
                val o = arr.getJSONObject(i)
                Message(
                    id       = o.optString("id", UUID.randomUUID().toString()),
                    role     = if (o.getString("role") == "USER") Message.Role.USER else Message.Role.ASSISTANT,
                    content  = o.getString("content"),
                    imageUri = o.optString("img").takeIf { it.isNotEmpty() }
                )
            }
        } catch (e: Exception) { mutableListOf() }
    }

    fun saveMessages(id: String, messages: List<Message>) {
        val arr = JSONArray()
        messages.takeLast(MAX_MSGS).filter { !it.isLoading }.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id); put("role", m.role.name); put("content", m.content)
                if (m.imageUri != null) put("img", m.imageUri)
            })
        }
        prefs.edit().putString("conv_$id", arr.toString()).commit()

        val title = messages.firstOrNull { it.role == Message.Role.USER && it.content.isNotBlank() }
            ?.content?.take(50)?.trimEnd() ?: "New Chat"
        val list = listConversations().toMutableList()
        list.removeAll { it.id == id }
        list.add(0, ConvMeta(id, title, System.currentTimeMillis()))
        saveIndex(list)
    }

    fun clearMessages(id: String) {
        prefs.edit().remove("conv_$id").commit()
        val list = listConversations().toMutableList()
        val idx  = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(title = "New Chat", updatedAt = System.currentTimeMillis())
            saveIndex(list)
        }
    }

    private fun saveIndex(list: List<ConvMeta>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().apply { put("id", m.id); put("title", m.title); put("updatedAt", m.updatedAt) })
        }
        prefs.edit().putString(KEY_INDEX, arr.toString()).commit()
    }
}
