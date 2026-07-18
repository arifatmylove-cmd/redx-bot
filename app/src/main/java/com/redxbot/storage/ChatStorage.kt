package com.redxbot.storage

import android.content.Context
import com.redxbot.model.Message
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ChatStorage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "RedxBotPrefs"
        private const val KEY_HISTORY = "chat_history"
        private const val MAX = 200
    }

    fun save(messages: List<Message>) {
        val arr = JSONArray()
        messages.takeLast(MAX).filter { !it.isLoading }.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("role", m.role.name)
                put("content", m.content)
                if (m.imageUri != null) put("img", m.imageUri)
            })
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).commit()
    }

    fun load(): MutableList<Message> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapTo(mutableListOf()) { i ->
                val o = arr.getJSONObject(i)
                Message(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    role = if (o.getString("role") == "USER") Message.Role.USER else Message.Role.ASSISTANT,
                    content = o.getString("content"),
                    imageUri = o.optString("img").takeIf { it.isNotEmpty() }
                )
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_HISTORY).commit()
    }
}
