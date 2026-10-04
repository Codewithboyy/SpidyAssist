package com.spydr.spidy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class CommandProcessor(private val context: Context) {

    companion object {
        private const val TAG = "CommandProcessor"
        private const val GROQ_API_KEY = BuildConfig.GROQ_API_KEY
        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions"

        private const val PRIMARY_MODEL = "openai/gpt-oss-120b"
        private const val FALLBACK_MODEL = "qwen/qwen3.8-27b"

        // Tuned for TTS: concise, no markdown, natural voice output
        private val SYSTEM_PROMPT = """
            You are Spidy, a fast, smart, and friendly voice assistant running natively on Android.
            Your developer is SpiderDev (Mitesh Chaudhari).

            STRICT RULES — follow every one, every time:
            1. IDENTITY: You are Spidy only. Never mention Groq, Meta, OpenAI, or any AI provider.
            2. LENGTH: Answer in 1 to 3 sentences max. Be direct and natural.
            3. FORMAT: Zero markdown. No asterisks, hashtags, bullets, dashes, tables, or code fences. Output goes straight to TTS.
            4. MATH: Solve arithmetic and unit conversions directly. State the answer first, then explain briefly if helpful.
            5. MEMORY: Use prior conversation turns to give context-aware answers. Remember the user's name if they tell you.
            6. TONE: Warm, confident, conversational. Never robotic or overly formal.
            7. TASKS: If told to do something, confirm briefly in natural language. Do not over-explain.
            8. THINKING: Never output <think> tags or internal reasoning text.
            9. UNKNOWN: If you don't know something, say so honestly in one sentence.
        """.trimIndent()
    }

    private val contactsManager = ContactsManager(context)
    private val appLauncher = AppLauncher(context)
    private val systemControlManager = SystemControlManager(context)
    private val alarmTimerManager = AlarmTimerManager(context)
    val sessionMemoryManager = SessionMemoryManager(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun processStream(
        prompt: String,
        sessionId: Long = sessionMemoryManager.getActiveSessionId(),
        onToken: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {

        val targetSessionId = if (sessionId == -1L) sessionMemoryManager.getActiveSessionId() else sessionId
        val sanitizedPrompt = WakeWordService.stripWakeWord(prompt).trim()

        if (sanitizedPrompt.isBlank()) {
            val empty = "I didn't catch that. Could you say it again?"
            withContext(Dispatchers.Main) { onToken(empty) }
            return@withContext empty
        }

        // Step 1: Local command routing — fast, no network needed
        val localResponse = handleLocalCommands(sanitizedPrompt, targetSessionId)
        if (localResponse != null) {
            sessionMemoryManager.addUserMessage(targetSessionId, sanitizedPrompt)
            sessionMemoryManager.addAssistantMessage(targetSessionId, localResponse)
            withContext(Dispatchers.Main) { onToken(localResponse) }
            return@withContext localResponse
        }

        // Step 2: API key guard
        if (GROQ_API_KEY.isBlank() || GROQ_API_KEY == "YOUR_GROQ_API_KEY_HERE") {
            val err = "API key not set. Please add it to local.properties."
            withContext(Dispatchers.Main) { onToken(err) }
            return@withContext err
        }

        // Step 3: Save user message then stream AI response
        sessionMemoryManager.addUserMessage(targetSessionId, sanitizedPrompt)

        val result = executeStreamCall(PRIMARY_MODEL, targetSessionId, onToken)
        if (result != null) {
            val clean = sanitizeResponse(result)
            sessionMemoryManager.addAssistantMessage(targetSessionId, clean)
            return@withContext clean
        }

        Log.w(TAG, "Primary model failed. Trying fallback.")
        val fallback = executeStreamCall(FALLBACK_MODEL, targetSessionId, onToken)
        if (fallback != null) {
            val clean = sanitizeResponse(fallback)
            sessionMemoryManager.addAssistantMessage(targetSessionId, clean)
            return@withContext clean
        }

        val networkErr = "I couldn't reach the server. Please check your connection and try again."
        withContext(Dispatchers.Main) { onToken(networkErr) }
        return@withContext networkErr
    }

    private fun handleLocalCommands(prompt: String, sessionId: Long): String? {
        val lower = prompt.lowercase().trim()

        // ── Memory commands ──────────────────────────────────────────────
        if (lower.matches(Regex(".*(clear|reset|forget|wipe).*(memory|history|chat|everything).*"))) {
            sessionMemoryManager.clearAllSessions()
            return "Done. I've cleared everything I remember from our conversations."
        }

        // ── Date / Time queries ──────────────────────────────────────────
        if (lower.matches(Regex(".*(what('s| is) the (time|date|day)|current time|today's date|what day is it).*"))) {
            val now = Date()
            val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
            val date = SimpleDateFormat("EEEE, MMMM d yyyy", Locale.getDefault()).format(now)
            return "It's $time on $date."
        }

        // ── Quick math ───────────────────────────────────────────────────
        val mathResult = tryEvalSimpleMath(lower)
        if (mathResult != null) return mathResult

        // ── System / media controls ──────────────────────────────────────
        val systemResult = systemControlManager.handleControlCommand(lower)
        if (systemResult != null) return systemResult

        // ── Timers ───────────────────────────────────────────────────────
        val timerPatterns = listOf(
            Pattern.compile("(set |start )?(a )?timer (for )?(\\d+)\\s*(second|sec|minute|min|hour|hr)"),
            Pattern.compile("(\\d+)\\s*(second|sec|minute|min|hour|hr) timer")
        )
        for (pattern in timerPatterns) {
            val m = pattern.matcher(lower)
            if (m.find()) {
                val amountStr = m.group(m.groupCount() - 1) ?: m.group(4)
                val unit = m.group(m.groupCount()) ?: m.group(5) ?: "minute"
                val amount = amountStr?.toIntOrNull() ?: continue
                val seconds = when {
                    unit.startsWith("sec") -> amount
                    unit.startsWith("hr") || unit.startsWith("hour") -> amount * 3600
                    else -> amount * 60
                }
                return alarmTimerManager.setTimer(seconds)
            }
        }

        // ── Alarms ───────────────────────────────────────────────────────
        val alarmMatcher = Pattern.compile(
            "set (an )?alarm (for )?(\\d{1,2})(:|\\s)?(\\d{2})?(\\s*([ap]m))?"
        ).matcher(lower)
        if (alarmMatcher.find()) {
            var hour = alarmMatcher.group(3)?.toIntOrNull() ?: 8
            val minute = alarmMatcher.group(5)?.toIntOrNull() ?: 0
            val ampm = alarmMatcher.group(7)?.lowercase()
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            return alarmTimerManager.setAlarm(hour, minute)
        }

        // ── WhatsApp messaging ───────────────────────────────────────────
        if (lower.matches(Regex(".*(send|message|text|whatsapp).*(on whatsapp|via whatsapp|whatsapp message).*|.*whatsapp.*(send|message|text).*"))) {
            return handleWhatsApp(lower)
        }

        // ── SMS ──────────────────────────────────────────────────────────
        if (lower.matches(Regex(".*(send|text|sms).*(message|sms|text).*to.*|.*text.*to.*"))) {
            return handleSms(lower)
        }

        // ── Phone calls ──────────────────────────────────────────────────
        if (lower.matches(Regex("^(call|dial|ring|phone)\\s+.+"))) {
            val name = lower
                .removePrefix("call").removePrefix("dial")
                .removePrefix("ring").removePrefix("phone")
                .trim()
            if (name.isNotEmpty()) {
                val initiated = contactsManager.callContact(name)
                return if (initiated) "Calling $name now."
                else "I couldn't find $name in your contacts."
            }
        }

        // ── YouTube search ───────────────────────────────────────────────
        val ytMatch = Regex("(?:search|play|find|look up)\\s+(.+?)\\s+(?:on youtube|in youtube|youtube)").find(lower)
            ?: if (lower.contains("youtube") && lower.contains("search")) Regex("youtube\\s+(?:search|for)?\\s+(.+)").find(lower) else null
        if (ytMatch != null) {
            val query = ytMatch.groupValues[1].trim()
            if (query.isNotBlank()) return appLauncher.searchYoutube(query)
        }

        // ── Maps / navigation ────────────────────────────────────────────
        val navMatch = Regex("(?:navigate|directions|take me|go|drive)\\s+to\\s+(.+)").find(lower)
        if (navMatch != null) {
            val dest = navMatch.groupValues[1].trim()
            if (dest.isNotBlank()) return appLauncher.navigateTo(dest)
        }

        // ── Web search ───────────────────────────────────────────────────
        val webMatch = Regex("(?:search|google|look up|find)\\s+(?:for\\s+)?(.+?)(?:\\s+on (?:google|the web|browser))?$").find(lower)
        if (webMatch != null && (lower.contains("google") || lower.contains("search the web") || lower.contains("look up"))) {
            val query = webMatch.groupValues[1].trim()
            if (query.isNotBlank()) return appLauncher.webSearch(query)
        }

        // ── App launch ───────────────────────────────────────────────────
        if (lower.matches(Regex("^(open|launch|start|run)\\s+.+"))) {
            val result = appLauncher.launchApp(prompt)
            if (!result.contains("not found", ignoreCase = true)) return result
        }

        return null
    }

    // ── Simple inline math evaluator ─────────────────────────────────────────
    private fun tryEvalSimpleMath(input: String): String? {
        // Match: "what is 24 * 7" / "calculate 100 / 4" / "12 plus 8"
        val mathPattern = Pattern.compile(
            "(?:what(?:'s| is)|calculate|compute|how much is)?\\s*(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/x×÷]|plus|minus|times|divided by|over)\\s*(-?\\d+(?:\\.\\d+)?)"
        )
        val m = mathPattern.matcher(input.lowercase())
        if (!m.find()) return null

        val a = m.group(1)?.toDoubleOrNull() ?: return null
        val op = m.group(2)?.trim() ?: return null
        val b = m.group(3)?.toDoubleOrNull() ?: return null

        val result = when (op) {
            "+", "plus" -> a + b
            "-", "minus" -> a - b
            "*", "x", "×", "times" -> a * b
            "/", "÷", "divided by", "over" -> if (b != 0.0) a / b else return "You can't divide by zero."
            else -> return null
        }

        val formatted = if (result == result.toLong().toDouble()) result.toLong().toString()
                        else "%.4f".format(result).trimEnd('0').trimEnd('.')
        return "$formatted."
    }

    // ── WhatsApp intent handler ───────────────────────────────────────────────
    private fun handleWhatsApp(input: String): String {
        // Extract contact name — basic heuristic
        val nameMatch = Regex("(?:to|message)\\s+([a-zA-Z]+(?:\\s[a-zA-Z]+)?)\\s+(?:on|via|saying|that|:)?").find(input)
        val name = nameMatch?.groupValues?.getOrNull(1) ?: ""
        return if (name.isNotEmpty()) {
            // appLauncher handles deep link to WhatsApp with contact name
            appLauncher.openWhatsApp(name)
        } else {
            appLauncher.launchApp("WhatsApp")
        }
    }

    // ── SMS intent handler ───────────────────────────────────────────────────
    private fun handleSms(input: String): String {
        val nameMatch = Regex("(?:text|sms|message|send)\\s+(?:a (?:text|message) to\\s+|to\\s+)?([a-zA-Z]+(?:\\s[a-zA-Z]+)?)").find(input)
        val name = nameMatch?.groupValues?.getOrNull(1) ?: ""
        return if (name.isNotEmpty()) {
            contactsManager.openSms(name)
        } else {
            "Who would you like to send a message to?"
        }
    }

    suspend fun process(prompt: String, sessionId: Long = -1L): String {
        return processStream(prompt, sessionId) {}
    }

    private fun getFormattedSystemPrompt(): String {
        val currentDate = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date())
        val userContext = sessionMemoryManager.getUserContext()
        val contextBlock = if (userContext.isNotBlank()) "\n\nUser context: $userContext" else ""
        return "$SYSTEM_PROMPT\n\nCurrent date and time: $currentDate.$contextBlock"
    }

    // Strip <think> blocks and leading/trailing whitespace
    private fun sanitizeResponse(input: String): String {
        return input
            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("\\*{1,3}"), "")     // remove markdown bold/italic
            .replace(Regex("#{1,6}\\s"), "")     // remove markdown headers
            .replace(Regex("^[-•·]\\s+", RegexOption.MULTILINE), "") // remove bullets
            .trim()
    }

    private suspend fun executeStreamCall(
        modelId: String,
        sessionId: Long,
        onToken: (String) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val fullResponse = StringBuilder()

        try {
            val history = sessionMemoryManager.getFormattedHistoryJson(sessionId)
            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", getFormattedSystemPrompt())
                })
                for (i in 0 until history.length()) {
                    val msg = history.getJSONObject(i)
                    val content = msg.optString("content", "")
                    if (content.isNotBlank()) {
                        put(JSONObject().apply {
                            put("role", msg.getString("role"))
                            put("content", sanitizeResponse(content))
                        })
                    }
                }
            }

            val body = JSONObject().apply {
                put("model", modelId)
                put("temperature", 0.65)
                put("max_tokens", 512)  // Tighter — forces concise TTS-friendly answers
                put("stream", true)
                put("messages", messagesArray)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(GROQ_API_URL)
                .addHeader("Authorization", "Bearer $GROQ_API_KEY")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "API error [$modelId] ${response.code}: ${response.body?.string()}")
                    return@withContext null
                }

                val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return@use))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val raw = line?.trim() ?: continue
                    if (!raw.startsWith("data:")) continue
                    val data = raw.substring(5).trim()
                    if (data == "[DONE]") break

                    try {
                        val chunk = JSONObject(data)
                        val token = chunk.optJSONArray("choices")
                            ?.getJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content") ?: continue

                        if (token.isNotEmpty()) {
                            fullResponse.append(token)
                            withContext(Dispatchers.Main.immediate) { onToken(token) }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Chunk parse error: $data", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stream error [$modelId]", e)
            return@withContext null
        }

        return@withContext if (fullResponse.isNotEmpty()) fullResponse.toString() else null
    }

    fun releaseResources() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
