package com.han2.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ConversationStore(context: Context) {
    private val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun status(conversationId: String): ConversationStatus =
        ConversationStatus.fromWire(preferences.getString("status_$conversationId", null))

    fun saveStatus(conversationId: String, status: ConversationStatus) {
        val current = this.status(conversationId)
        if (status.rank >= current.rank) {
            preferences.edit().putString("status_$conversationId", status.name.lowercase()).apply()
        }
    }

    fun recentMessages(conversationId: String): List<ContextMessage> {
        val array = runCatching { JSONArray(preferences.getString("messages_$conversationId", "[]")) }
            .getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let {
                ContextMessage(it.optString("speaker", "unknown"), it.optString("text").take(300))
            }
        }.takeLast(5)
    }

    fun append(conversationId: String, message: ContextMessage) {
        val messages = (recentMessages(conversationId) + message.copy(text = message.text.take(300)))
            .takeLast(5)
        val limited = mutableListOf<ContextMessage>()
        var total = 0
        for (item in messages.asReversed()) {
            if (total + item.text.length > 1000) continue
            limited.add(0, item)
            total += item.text.length
        }
        val array = JSONArray().apply {
            limited.forEach { put(JSONObject().put("speaker", it.speaker).put("text", it.text)) }
        }
        preferences.edit().putString("messages_$conversationId", array.toString()).apply()
    }

    companion object { private const val NAME = "conversation_risk_store" }
}
