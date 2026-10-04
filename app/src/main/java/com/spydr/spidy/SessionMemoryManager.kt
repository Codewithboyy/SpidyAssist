package com.spydr.spidy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class SessionMemoryManager(context: Context) {

    companion object {
        private const val TAG = "SessionMemoryManager"
        private const val PREFS_NAME = "spidy_session_memory"
        private const val KEY_ACTIVE_SESSION = "active_session_id"
        private const val KEY_SESSION_PREFIX = "session_history_"
        private const val KEY_USER_CONTEXT = "user_context"
        private const val KEY_PERSISTENT_SUMMARY = "persistent_summary"
        private const val MAX_HISTORY_TURNS = 12       // 12 user+assistant pairs = 24 messages
        private const val SUMMARY_THRESHOLD = 20       // Summarize when history exceeds this
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Session ID resolution ─────────────────────────────────────────────────

    fun getActiveSessionId(): Long {
        val stateId = SpidyStateManager.activeSessionId
        if (stateId != 0L) {
            prefs.edit().putLong(KEY_ACTIVE_SESSION, stateId).apply()
            return stateId
        }
        var id = prefs.getLong(KEY_ACTIVE_SESSION, -1L)
        if (id <= 0L) id = createNewSession()
        return id
    }

    fun createNewSession(): Long {
        val id = System.currentTimeMillis()
        prefs.edit().putLong(KEY_ACTIVE_SESSION, id).apply()
        return id
    }

    // ── Message storage ───────────────────────────────────────────────────────

    fun addUserMessage(sessionId: Long = getActiveSessionId(), message: String) {
        if (message.isBlank()) return
        // Extract user name if they introduce themselves
        extractAndSaveUserName(message)
        addMessage(resolveSessionId(sessionId), "user", message)
    }

    fun addAssistantMessage(sessionId: Long = getActiveSessionId(), message: String) {
        if (message.isBlank()) return
        addMessage(resolveSessionId(sessionId), "assistant", message)
    }

    private fun addMessage(sessionId: Long, role: String, content: String) {
        val history = getHistoryArray(sessionId)
        history.put(JSONObject().apply {
            put("role", role)
            put("content", content)
        })

        // Trim to MAX_HISTORY_TURNS pairs (role pairs = 2 entries per turn)
        val maxEntries = MAX_HISTORY_TURNS * 2
        val trimmed = if (history.length() > maxEntries) {
            // Keep a summary anchor + recent turns
            val summary = buildSummaryAnchor(history, history.length() - maxEntries)
            val recent = JSONArray()
            if (summary.isNotEmpty()) {
                recent.put(JSONObject().apply {
                    put("role", "system")
                    put("content", "Earlier conversation summary: $summary")
                })
            }
            val start = history.length() - maxEntries
            for (i in start until history.length()) recent.put(history.getJSONObject(i))
            recent
        } else {
            history
        }

        saveHistoryArray(sessionId, trimmed)
        Log.d(TAG, "[$role] saved to session $sessionId. Total: ${trimmed.length()}")
    }

    // Build a one-sentence summary of older turns to preserve context without token bloat
    private fun buildSummaryAnchor(history: JSONArray, oldCount: Int): String {
        val topics = mutableListOf<String>()
        for (i in 0 until minOf(oldCount, history.length())) {
            val obj = history.optJSONObject(i) ?: continue
            if (obj.optString("role") == "user") {
                val content = obj.optString("content", "").take(60)
                if (content.isNotBlank()) topics.add(content)
            }
        }
        return if (topics.isEmpty()) "" else "User previously asked about: ${topics.take(4).joinToString("; ")}."
    }

    // ── User context (persistent across sessions) ─────────────────────────────

    fun getUserContext(): String = prefs.getString(KEY_USER_CONTEXT, "") ?: ""

    fun setUserContext(context: String) {
        prefs.edit().putString(KEY_USER_CONTEXT, context).apply()
    }

    private fun extractAndSaveUserName(message: String) {
        val namePatterns = listOf(
            Regex("my name is ([A-Z][a-z]+(?:\\s[A-Z][a-z]+)?)", RegexOption.IGNORE_CASE),
            Regex("i(?:'m| am) ([A-Z][a-z]+(?:\\s[A-Z][a-z]+)?)", RegexOption.IGNORE_CASE),
            Regex("call me ([A-Z][a-z]+)", RegexOption.IGNORE_CASE)
        )
        for (pattern in namePatterns) {
            val match = pattern.find(message)
            if (match != null) {
                val name = match.groupValues[1].trim()
                val existing = getUserContext()
                if (!existing.contains(name)) {
                    val updated = if (existing.isBlank()) "User's name is $name."
                                  else "$existing User's name is $name."
                    setUserContext(updated)
                    Log.d(TAG, "Saved user name: $name")
                }
                break
            }
        }
    }

    // ── History retrieval ─────────────────────────────────────────────────────

    fun getFormattedHistoryJson(sessionId: Long = getActiveSessionId()): JSONArray {
        return getHistoryArray(resolveSessionId(sessionId))
    }

    fun getHistoryForApiPayload(sessionId: Long = getActiveSessionId()): List<Map<String, String>> {
        val json = getFormattedHistoryJson(sessionId)
        return (0 until json.length()).mapNotNull { i ->
            val obj = json.optJSONObject(i) ?: return@mapNotNull null
            val role = obj.optString("role", "")
            val content = obj.optString("content", "")
            if (role.isNotEmpty() && content.isNotEmpty()) mapOf("role" to role, "content" to content)
            else null
        }
    }

    // ── Persistence helpers ───────────────────────────────────────────────────

    private fun resolveSessionId(sessionId: Long) =
        if (sessionId <= 0L) getActiveSessionId() else sessionId

    private fun getHistoryArray(sessionId: Long): JSONArray {
        val raw = prefs.getString(KEY_SESSION_PREFIX + sessionId, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (e: Exception) {
            Log.e(TAG, "Parse error for session $sessionId", e)
            JSONArray()
        }
    }

    private fun saveHistoryArray(sessionId: Long, array: JSONArray) {
        prefs.edit().putString(KEY_SESSION_PREFIX + sessionId, array.toString()).apply()
    }

    // ── Clearing ──────────────────────────────────────────────────────────────

    fun clearSession(sessionId: Long = getActiveSessionId()) {
        prefs.edit().remove(KEY_SESSION_PREFIX + resolveSessionId(sessionId)).apply()
        Log.d(TAG, "Cleared session $sessionId")
    }

    fun clearAllSessions() {
        // Keep user context and persistent summary — only wipe chat history
        val userCtx = getUserContext()
        prefs.edit().clear().apply()
        if (userCtx.isNotBlank()) setUserContext(userCtx)
        Log.d(TAG, "Cleared all sessions. Preserved user context.")
    }
}
