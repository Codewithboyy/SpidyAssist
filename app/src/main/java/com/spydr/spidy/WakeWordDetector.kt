package com.spydr.spidy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

class WakeWordDetector(
    private val context: Context,
    private val listener: Listener
) : RecognitionListener {

    interface Listener {
        fun onWakeWordDetected()
    }

    private companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000.0f
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    
    @Volatile
    private var wakeWordTriggered = false
    
    fun resetWakeWord() {
        wakeWordTriggered = false
    }

    fun start() {
        scope.launch {
            try {
                val activeRecognizer = prepareRecognizer() ?: return@launch
                shutdownSpeechServiceOnly()

                speechService = SpeechService(activeRecognizer, SAMPLE_RATE)
                speechService?.startListening(this@WakeWordDetector)
                Log.d(TAG, "Continuous wake word detection listening started.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech service context", e)
            }
        }
    }

    private suspend fun prepareRecognizer(): Recognizer? = withContext(Dispatchers.IO) {
        try {
            recognizer?.let { return@withContext it }

            val modelDir = File(context.filesDir, "model")
            if (!modelDir.exists()) {
                Log.d(TAG, "Model directory not found. Copying assets...")
                AssetUtils.copyAssetFolder(context, "model", modelDir)
            }

            if (model == null) {
                model = Model(modelDir.absolutePath)
            }
            
            // Keeps your expanded grammar rules for much better reliability
            val grammarJson = "[\"spider\", \"spyder\", \"speedy\", \"spid\", \"[unk]\"]"

            val createdRecognizer = Recognizer(model, SAMPLE_RATE, grammarJson)
            recognizer = createdRecognizer
            createdRecognizer
        } catch (e: Exception) {
            Log.e(TAG, "Model or Recognizer creation crashed globally", e)
            null
        }
    }

    private fun shutdownSpeechServiceOnly() {
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning transient speech service context", e)
        }
    }

    fun stop() {
        shutdownSpeechServiceOnly()
        scope.cancel()

        try {
            recognizer?.close()
            recognizer = null
            
            model?.close()
            model = null
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing local audio engine memory profiles", e)
        }
    }

    override fun onPartialResult(hypothesis: String?) {}

    override fun onResult(hypothesis: String?) {
        if (hypothesis.isNullOrEmpty() || wakeWordTriggered) return

        scope.launch(Dispatchers.Default) {
            try {
                val json = JSONObject(hypothesis)
                val text = json.optString("text", "").lowercase()

                // Match against any of our target vocabulary variations
                if (text == "spider" || text == "spyder" || text == "speedy" || text == "spid") {
                    wakeWordTriggered = true
                    withContext(Dispatchers.Main) {
                        listener.onWakeWordDetected()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error matching expanded wake word result framework", e)
            }
        }
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Vosk internal pipeline native error event triggered", exception)
        
        // BUG FIX: If the OS drops the background stream, don't crash.
        // Clean up the stale handles and launch the ear again automatically.
        scope.launch(Dispatchers.Main) {
            try {
                shutdownSpeechServiceOnly()
                // Small 1-second recovery window to allow system hardware states to normalize
                kotlinx.coroutines.delay(1000)
                start() 
                Log.d(TAG, "Successfully recovered background microphone engine context.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to self-heal background streaming loop", e)
            }
        }
    }

    override fun onTimeout() {}
}
