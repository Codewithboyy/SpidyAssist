package com.spydr.spidy

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class Assistant(
    private val context: Context
) : TextToSpeech.OnInitListener {

    private companion object {
        private const val TAG = "Assistant"
        private const val UTTERANCE_ID = "SPIDY_REPLY"
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var onSpeechCompleteCallback: (() -> Unit)? = null

    init {
        // Initialize local TextToSpeech instance to keep architecture encapsulated
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Default language locale is not supported on this device.")
            } else {
                isTtsReady = true
                setupUtteranceListener()
            }
        } else {
            Log.e(TAG, "Failed to initialize TextToSpeech framework service engine.")
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "Assistant started speaking: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID) {
                    // Fire the callback on a safe handling execution lane
                    onSpeechCompleteCallback?.invoke()
                    onSpeechCompleteCallback = null
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS Error encountered on tracking pipeline frame: $utteranceId")
                onSpeechCompleteCallback?.invoke()
                onSpeechCompleteCallback = null
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS Error code: $errorCode executed on utterance payload stream: $utteranceId")
                onSpeechCompleteCallback?.invoke()
                onSpeechCompleteCallback = null
            }
        })
    }

    /**
     * Entry point used by MainActivity to announce responses and reset wake-word tracking
     * exactly when the voice utterance finishes speaking.
     */
    fun handleResponse(text: String, onComplete: () -> Unit) {
        if (text.isBlank()) {
            onComplete()
            return
        }
        
        this.onSpeechCompleteCallback = onComplete
        speak(text)
    }

    private fun speak(text: String) {
        if (!isTtsReady) {
            Log.w(TAG, "TextToSpeech engine is not initialized yet. Dropping text: $text")
            onSpeechCompleteCallback?.invoke()
            onSpeechCompleteCallback = null
            return
        }

        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            UTTERANCE_ID
        )
    }

    /**
     * Call this inside MainActivity's onDestroy hook to safely release audio hardware resources.
     */
    fun destroy() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing local audio engine hardware threads during context shutdown", e)
        }
    }
}
