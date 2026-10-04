package com.spydr.spidy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class WakeWordDetector(
    private val context: Context,
    private val listener: Listener
) : RecognitionListener {

    interface Listener {
        fun onWakeWordDetected()
    }

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000.0f

        // Restricted grammar — only accepted wake words + unknown fallback
        private const val GRAMMAR_JSON =
            "[\"hey spider\", \"hey spidy\", \"ok spider\", \"ok spidy\", \"spider\", \"spidey\", \"spidy\", \"[unk]\"]"

        // Debounce: ignore triggers within this window after one fires
        private const val TRIGGER_DEBOUNCE_MS = 2000L

        // Auto-restart on error after this delay
        private const val ERROR_RESTART_DELAY_MS = 1500L

        // Max consecutive errors before giving up restart loop
        private const val MAX_CONSECUTIVE_ERRORS = 5
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null

    private val isListening = AtomicBoolean(false)
    private val hasTriggered = AtomicBoolean(false)
    private val isInitializing = AtomicBoolean(false)
    private val lastTriggerTime = AtomicLong(0L)
    private val consecutiveErrors = AtomicInteger(0)

    // ── Public API ────────────────────────────────────────────────────────────

    fun start() {
        if (isListening.get() || isInitializing.get()) return
        isInitializing.set(true)
        scope.launch { initializeAndStart() }
    }

    fun stop() {
        if (!isListening.getAndSet(false) && !isInitializing.get()) return
        shutdownSpeechService()
        hasTriggered.set(false)
        Log.d(TAG, "WakeWordDetector stopped.")
    }

    fun resetWakeWord() {
        stop()
    }

    fun destroy() {
        stop()
        scope.cancel()
        try { model?.close() } catch (e: Exception) { Log.e(TAG, "Error closing Vosk model", e) }
        model = null
    }

    // ── Initialization ────────────────────────────────────────────────────────

    private suspend fun initializeAndStart() {
        try {
            val modelPath = File(context.filesDir, "vosk-model-small-en-us-0.15")
            if (!modelPath.exists()) {
                Log.e(TAG, "Vosk model not found at ${modelPath.absolutePath}")
                isInitializing.set(false)
                return
            }

            withContext(Dispatchers.IO) {
                if (model == null) {
                    model = Model(modelPath.absolutePath)
                    Log.d(TAG, "Vosk model loaded.")
                }
                recognizer?.close()
                recognizer = Recognizer(model, SAMPLE_RATE, GRAMMAR_JSON)
            }

            withContext(Dispatchers.Main) {
                if (isListening.get()) {
                    isInitializing.set(false)
                    return@withContext
                }

                hasTriggered.set(false)
                val rec = recognizer
                if (rec != null) {
                    speechService = SpeechService(rec, SAMPLE_RATE).apply {
                        startListening(this@WakeWordDetector)
                    }
                    isListening.set(true)
                    consecutiveErrors.set(0)
                    Log.d(TAG, "Wake word listener active. Grammar: $GRAMMAR_JSON")
                }
                isInitializing.set(false)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Vosk init IO error", e)
            isInitializing.set(false)
        } catch (e: Exception) {
            Log.e(TAG, "Vosk init error", e)
            isInitializing.set(false)
        }
    }

    private fun shutdownSpeechService() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down SpeechService", e)
        } finally {
            speechService = null
            try { recognizer?.close() } catch (e: Exception) { Log.e(TAG, "Error closing Recognizer", e) }
            recognizer = null
        }
    }

    // ── RecognitionListener ───────────────────────────────────────────────────

    override fun onResult(hypothesis: String?) = checkWakeWord(hypothesis, "result")
    override fun onFinalResult(hypothesis: String?) = checkWakeWord(hypothesis, "final")
    override fun onPartialResult(hypothesis: String?) = checkWakeWord(hypothesis, "partial")

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Vosk recognition error", exception)
        val errors = consecutiveErrors.incrementAndGet()

        if (errors >= MAX_CONSECUTIVE_ERRORS) {
            Log.e(TAG, "Max consecutive errors ($MAX_CONSECUTIVE_ERRORS) reached. Stopping.")
            isListening.set(false)
            return
        }

        // Auto-restart after brief delay
        if (isListening.get()) {
            isListening.set(false)
            scope.launch {
                delay(ERROR_RESTART_DELAY_MS)
                Log.d(TAG, "Auto-restarting after error (attempt $errors).")
                start()
            }
        }
    }

    override fun onTimeout() {
        Log.d(TAG, "Vosk timeout — restarting listener.")
        // Timeout = silence timeout, restart to keep listening
        if (isListening.get()) {
            isListening.set(false)
            scope.launch {
                delay(300L)
                start()
            }
        }
    }

    // ── Wake word matching ────────────────────────────────────────────────────

    private fun checkWakeWord(hypothesisJson: String?, source: String) {
        if (hypothesisJson.isNullOrBlank() || !isListening.get() || hasTriggered.get()) return

        try {
            val json = JSONObject(hypothesisJson)
            val text = when {
                json.has("partial") -> json.getString("partial")
                json.has("text") -> json.getString("text")
                else -> ""
            }.lowercase().trim()

            if (text.isEmpty() || text == "[unk]") return

            if (WakeWordService.isWakeWordMatch(text)) {
                val state = SpidyStateManager.activeState
                if (state == AssistantState.PROCESSING || state == AssistantState.SPEAKING) {
                    Log.d(TAG, "Wake word ignored — busy state: $state")
                    return
                }

                // Debounce: suppress double-triggers within 2 seconds
                val now = System.currentTimeMillis()
                val last = lastTriggerTime.get()
                if (now - last < TRIGGER_DEBOUNCE_MS) {
                    Log.d(TAG, "Wake word debounced (${now - last}ms since last trigger)")
                    return
                }

                Log.d(TAG, "Wake word matched [$source]: '$text'")
                if (hasTriggered.compareAndSet(false, true)) {
                    lastTriggerTime.set(now)
                    consecutiveErrors.set(0)
                    listener.onWakeWordDetected()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hypothesis parse error: $hypothesisJson", e)
        }
    }
}
