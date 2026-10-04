package com.spydr.spidy

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ChatHistoryRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("spidy_chat_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private companion object {
        private const val KEY_CHAT_HISTORY = "chat_history_list"
    }

    fun loadMessages(): MutableList<Message> {
        val json = prefs.getString(KEY_CHAT_HISTORY, null) ?: return mutableListOf()
        val type = object : TypeToken<List<Message>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveMessages(messages: List<Message>) {
        val json = gson.toJson(messages)
        prefs.edit().putString(KEY_CHAT_HISTORY, json).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_CHAT_HISTORY).apply()
    }
}
