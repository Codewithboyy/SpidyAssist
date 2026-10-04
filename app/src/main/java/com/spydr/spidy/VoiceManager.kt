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

class VoiceManager(
    private val context: Context,
    private val callback: VoiceCallback
) {

    interface VoiceCallback {
        fun onListening()
        fun onPartialSpeech(partialText: String, sessionId: Long) {}
        fun onCommand(command: String, sessionId: Long)
        fun onError(errorCode: Int, sessionId: Long)
    }

    companion object {
        private const val TAG = "VoiceManager"
        private const val MIC_HANDOFF_DELAY_MS = 250L
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingHandoffRunnable: Runnable? = null

    fun startListening(sessionId: Long) {
        val currentState = SpidyStateManager.activeState
        if (currentState != AssistantState.COMMAND_LISTENING && currentState != AssistantState.WAKE_WORD_DETECTED) {
            Log.w(TAG, "Cannot start listening, current state is $currentState")
            return
        }

        SpidyStateManager.transitionTo(AssistantState.COMMAND_LISTENING, sessionId)

        cancelPendingHandoff()

        val handoffRunnable = Runnable {
            if (!SpidyStateManager.isSessionValid(sessionId)) {
                Log.w(TAG, "Session $sessionId expired prior to starting SpeechRecognizer.")
                return@Runnable
            }

            try {
                safelyDestroyRecognizer()

                val targetContext = context.applicationContext ?: context
                if (SpeechRecognizer.isRecognitionAvailable(targetContext)) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(targetContext).apply {
                        setRecognitionListener(createListener(sessionId))
                    }

                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

                        // --- DYNAMIC SPEECH DURATION CONFIGURATION ---
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)

                        putExtra(
                            RecognizerIntent.EXTRA_CALLING_PACKAGE,
                            targetContext.packageName
                        )
                    }

                    speechRecognizer?.startListening(intent)
                    Log.d(TAG, "SpeechRecognizer started listening with extended thresholds for session $sessionId")
                } else {
                    Log.e(TAG, "Speech Recognition is unavailable on this device.")
                    dispatchOnError(SpeechRecognizer.ERROR_CLIENT, sessionId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize SpeechRecognizer", e)
                dispatchOnError(SpeechRecognizer.ERROR_CLIENT, sessionId)
            }
        }

        pendingHandoffRunnable = handoffRunnable
        mainHandler.postDelayed(handoffRunnable, MIC_HANDOFF_DELAY_MS)
    }

    fun stopListening() {
        cancelPendingHandoff()
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping SpeechRecognizer", e)
            }
        }
    }

    fun cancelListening() {
        cancelPendingHandoff()
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error canceling SpeechRecognizer", e)
            }
        }
    }

    private fun cancelPendingHandoff() {
        pendingHandoffRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingHandoffRunnable = null
    }

    private fun dispatchOnError(errorCode: Int, sessionId: Long) {
        mainHandler.post {
            safelyDestroyRecognizer()
            if (SpidyStateManager.isSessionValid(sessionId)) {
                callback.onError(errorCode, sessionId)
            }
        }
    }

    private fun createListener(sessionId: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "SpeechRecognizer ready for speech.")
            mainHandler.post {
                if (SpidyStateManager.isSessionValid(sessionId)) {
                    callback.onListening()
                }
            }
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "User began speaking.")
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            Log.d(TAG, "User finished speaking, processing input.")
        }

        override fun onError(error: Int) {
            Log.e(TAG, "SpeechRecognizer error code: $error for session: $sessionId")
            cancelPendingHandoff()
            dispatchOnError(error, sessionId)
        }

        override fun onResults(results: Bundle?) {
            cancelPendingHandoff()

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

            mainHandler.post {
                if (!SpidyStateManager.isSessionValid(sessionId)) {
                    Log.w(TAG, "Received results for stale session $sessionId")
                    safelyDestroyRecognizer()
                    return@post
                }

                safelyDestroyRecognizer()

                if (!matches.isNullOrEmpty()) {
                    val rawCommand = matches[0]
                    val cleanedCommand = WakeWordService.stripWakeWord(rawCommand)

                    Log.d(TAG, "Raw speech recognized: '$rawCommand' -> Processed command: '$cleanedCommand'")
                    callback.onCommand(cleanedCommand, sessionId)
                } else {
                    callback.onError(SpeechRecognizer.ERROR_NO_MATCH, sessionId)
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val liveText = WakeWordService.stripWakeWord(matches[0])
                if (liveText.isNotEmpty()) {
                    mainHandler.post {
                        if (SpidyStateManager.isSessionValid(sessionId)) {
                            callback.onPartialSpeech(liveText, sessionId)
                        }
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun safelyDestroyRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error safely tearing down SpeechRecognizer", e)
        } finally {
            speechRecognizer = null
        }
    }

    fun destroy() {
        cancelPendingHandoff()
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            safelyDestroyRecognizer()
        }
    }
}
