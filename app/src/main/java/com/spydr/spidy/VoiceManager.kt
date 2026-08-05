package com.spydr.spidy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class VoiceManager(
    private val context: Context,
    private val callback: VoiceCallback
) : RecognitionListener {

    interface VoiceCallback {
        fun onListening()
        fun onCommand(text: String)
        fun onError(errorCode: Int)
    }

    private companion object {
        private const val TAG = "VoiceManager"
    }

    // Main thread handler to ensure SpeechRecognizer actions occur safely on the UI loop
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    init {
        // Force initialization on the main thread to ensure OS safety compliance
        mainHandler.post {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                    setRecognitionListener(this@VoiceManager)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to instantiate native SpeechRecognizer context on main loop", e)
            }
        }
    }

    fun startListening() {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.e(TAG, "Speech recognition engine hardware pipeline unavailable on this platform.")
                callback.onError(-1)
                return@post
            }

            if (isListening) return@post

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                
                // Fine-tuning modern recognition latency boundaries
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            }

            try {
                speechRecognizer?.startListening(intent)
                isListening = true
                callback.onListening()
            } catch (e: Exception) {
                Log.e(TAG, "Crashed starting recognition layer", e)
                isListening = false
                callback.onError(-2)
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            if (!isListening) return@post

            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error trying to stop continuous hardware tracking", e)
            }
            
            // REMOVED: WakeWordService.resetProcessing() from here.
            // This is now properly controlled via Assistant.kt's text utterance completion callback.
            isListening = false
        }
    }

    fun destroy() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning native recognition pipeline assets", e)
            }
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "Microphone layer open and ready for audio input stream.")
    }

    override fun onBeginningOfSpeech() {}

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        isListening = false
    }

    override fun onError(error: Int) {
        Log.w(TAG, "SpeechRecognizer triggered fallback pipeline error code: $error")
        isListening = false

        mainHandler.post {
            callback.onError(error)
            
            // Clean handling of explicit silence/timeout drop paths
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                Log.d(TAG, "User input empty or timed out. Handing system back to wake-word layer context.")
                // Notify the wake word service to resume listening if command input wasn't captured
                WakeWordService.resetProcessing()
            }
        }
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

        if (!matches.isNullOrEmpty()) {
            val command = matches[0]
            Log.d(TAG, "Speech parsed successfully: $command")
            callback.onCommand(command)
        } else {
            Log.w(TAG, "Speech completed with an empty result bundle context.")
            callback.onError(SpeechRecognizer.ERROR_NO_MATCH)
            WakeWordService.resetProcessing()
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {}

    override fun onEvent(eventType: Int, params: Bundle?) {}
}
