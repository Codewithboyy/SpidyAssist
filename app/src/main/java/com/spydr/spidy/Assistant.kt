package com.spydr.spidy

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class Assistant(
    private val context: Context
) : TextToSpeech.OnInitListener {

    private companion object {
        private const val TAG = "Assistant"
        private const val UTTERANCE_PREFIX = "SPIDY_REPLY_"
        private const val UTTERANCE_CHUNK_PREFIX = "SPIDY_CHUNK_"

        // Tuned voice parameters for natural, pleasant speech
        private const val SPEECH_RATE = 1.0f       // Slightly slower than default (1.0) — cleaner articulation
        private const val SPEECH_PITCH = 1.0f      // Slightly higher — sounds warmer, less robotic
        private const val MS_PER_CHAR_ESTIMATE = 85L
        private const val MIN_TIMEOUT_MS = 4000L

        // Sentence boundary pattern for chunked TTS streaming
        private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?])\\s+")
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // Chunk queue for sentence-by-sentence streaming playback
    private val chunkQueue = ArrayDeque<String>()
    private var isSpeakingChunk = false

    // Pending support for calls made before TTS init
    private var pendingText: String? = null
    private var pendingSessionId: Long = -1L

    private var activeSessionId: Long = -1L
    private var onSpeechCompleteCallback: ((Long) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechTimeoutRunnable: Runnable? = null

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val defaultLocale = Locale.getDefault()
            val langResult = tts?.setLanguage(defaultLocale)

            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Locale $defaultLocale not supported. Falling back to US English.")
                tts?.setLanguage(Locale.US)
            }

            // Apply tuned voice parameters
            tts?.setSpeechRate(SPEECH_RATE)
            tts?.setPitch(SPEECH_PITCH)

            // Select highest quality available voice
            selectBestVoice()

            isTtsReady = true
            setupUtteranceListener()

            val textToSpeak = pendingText
            val sessionIdToSpeak = pendingSessionId
            if (!textToSpeak.isNullOrBlank() && sessionIdToSpeak != -1L) {
                pendingText = null
                pendingSessionId = -1L
                speak(textToSpeak, sessionIdToSpeak)
            }
        } else {
            Log.e(TAG, "TTS engine failed to initialize.")
            isTtsReady = false
            val queuedSessionId = pendingSessionId
            if (queuedSessionId != -1L) {
                pendingText = null
                pendingSessionId = -1L
                handleFinalCompletion("$UTTERANCE_PREFIX$queuedSessionId")
            }
        }
    }

    /**
     * Selects the highest quality voice available on the device.
     * Prefers local/installed higher-quality voices over network-dependent ones,
     * falling back gracefully if none found.
     */
    private fun selectBestVoice() {
        try {
            val voices = tts?.voices ?: return
            val locale = tts?.voice?.locale ?: Locale.US

            // Filter for matching language and installed voices
            val best = voices
                .filter { it.locale.language == locale.language }
                .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .sortedWith(compareBy(
                    // Prefer local high-quality voices over network-required ones for low latency
                    { if (it.isNetworkConnectionRequired) 1 else 0 },
                    { it.latency },
                    { it.quality * -1 } // Higher quality first
                ))
                .firstOrNull()

            if (best != null) {
                tts?.voice = best
                Log.d(TAG, "Selected voice: ${best.name} quality=${best.quality} latency=${best.latency}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Voice selection failed, using default.", e)
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "Speaking: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId?.startsWith(UTTERANCE_CHUNK_PREFIX) == true) {
                    // Chunk done — play next chunk if queued
                    mainHandler.post { playNextChunk() }
                } else {
                    handleFinalCompletion(utteranceId)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error: $utteranceId")
                if (utteranceId?.startsWith(UTTERANCE_CHUNK_PREFIX) == true) {
                    mainHandler.post { playNextChunk() }
                } else {
                    handleFinalCompletion(utteranceId)
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error code $errorCode: $utteranceId")
                if (utteranceId?.startsWith(UTTERANCE_CHUNK_PREFIX) == true) {
                    mainHandler.post { playNextChunk() }
                } else {
                    handleFinalCompletion(utteranceId)
                }
            }
        })
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .build()

            audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun handleFinalCompletion(utteranceId: String?) {
        cancelTimeout()
        abandonAudioFocus()
        isSpeakingChunk = false
        chunkQueue.clear()

        if (utteranceId == null || !utteranceId.startsWith(UTTERANCE_PREFIX)) return
        val utteranceSessionId = utteranceId.removePrefix(UTTERANCE_PREFIX).toLongOrNull() ?: return

        mainHandler.post {
            val callback = onSpeechCompleteCallback
            onSpeechCompleteCallback = null

            if (SpidyStateManager.isSessionValid(utteranceSessionId)) {
                callback?.invoke(utteranceSessionId)
            } else {
                Log.w(TAG, "Dropping stale TTS callback for session: $utteranceSessionId")
            }
        }
    }

    fun handleResponse(text: String, sessionId: Long, onComplete: (Long) -> Unit) {
        if (text.isBlank()) {
            onComplete(sessionId)
            return
        }

        activeSessionId = sessionId
        onSpeechCompleteCallback = onComplete

        if (!isTtsReady) {
            Log.d(TAG, "TTS not ready. Queuing for session $sessionId")
            pendingText = text
            pendingSessionId = sessionId
            return
        }

        speak(text, sessionId)
    }

    private fun speak(text: String, sessionId: Long) {
        if (!SpidyStateManager.isSessionValid(sessionId)) {
            Log.w(TAG, "Session $sessionId invalid. Aborting speak.")
            val callback = onSpeechCompleteCallback
            onSpeechCompleteCallback = null
            callback?.invoke(sessionId)
            return
        }

        requestAudioFocus()
        chunkQueue.clear()
        isSpeakingChunk = false

        // Split into sentences for more natural chunked delivery
        val sentences = text.split(SENTENCE_SPLIT_REGEX).filter { it.isNotBlank() }

        scheduleSafetyTimeout(text, "$UTTERANCE_PREFIX$sessionId")

        if (sentences.size <= 1) {
            // Short text — speak as single utterance
            val utteranceId = "$UTTERANCE_PREFIX$sessionId"
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "TTS speak failed for session $sessionId")
                handleFinalCompletion(utteranceId)
            }
        } else {
            // Queue all chunks except last
            for (i in 0 until sentences.size - 1) {
                chunkQueue.add(sentences[i])
            }
            // Last sentence uses final utterance ID so completion fires correctly
            val lastSentence = sentences.last()

            // Speak first chunk immediately, rest chain via onDone
            val firstChunk = chunkQueue.removeFirst()
            isSpeakingChunk = true

            tts?.speak(firstChunk, TextToSpeech.QUEUE_FLUSH, Bundle(), "${UTTERANCE_CHUNK_PREFIX}0")

            // Store last sentence to be spoken after all chunks drain
            chunkQueue.add("\u0000FINAL\u0000$sessionId\u0000$lastSentence")
        }
    }

    private fun playNextChunk() {
        if (chunkQueue.isEmpty()) return

        val next = chunkQueue.removeFirst()

        // Sentinel: last sentence marker
        if (next.startsWith("\u0000FINAL\u0000")) {
            val parts = next.split("\u0000")
            val sessionId = parts.getOrNull(2)?.toLongOrNull() ?: return
            val finalText = parts.getOrNull(3) ?: return
            val utteranceId = "$UTTERANCE_PREFIX$sessionId"
            isSpeakingChunk = false
            tts?.speak(finalText, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
        } else {
            val chunkId = "${UTTERANCE_CHUNK_PREFIX}${System.currentTimeMillis()}"
            tts?.speak(next, TextToSpeech.QUEUE_FLUSH, Bundle(), chunkId)
        }
    }

    private fun scheduleSafetyTimeout(text: String, utteranceId: String) {
        cancelTimeout()
        val estimatedDuration = maxOf(MIN_TIMEOUT_MS, text.length * MS_PER_CHAR_ESTIMATE)

        val timeoutTask = Runnable {
            Log.w(TAG, "TTS timeout after ${estimatedDuration}ms. Forcing completion.")
            chunkQueue.clear()
            isSpeakingChunk = false
            handleFinalCompletion(utteranceId)
        }

        speechTimeoutRunnable = timeoutTask
        mainHandler.postDelayed(timeoutTask, estimatedDuration)
    }

    private fun cancelTimeout() {
        speechTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        speechTimeoutRunnable = null
    }

    fun stop() {
        cancelTimeout()
        chunkQueue.clear()
        isSpeakingChunk = false
        pendingText = null
        val interruptedSession = activeSessionId
        pendingSessionId = -1L

        val callback = onSpeechCompleteCallback
        onSpeechCompleteCallback = null

        abandonAudioFocus()
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }

        if (interruptedSession != -1L) {
            callback?.invoke(interruptedSession)
        }
    }

    fun destroy() {
        cancelTimeout()
        chunkQueue.clear()
        pendingText = null
        pendingSessionId = -1L
        onSpeechCompleteCallback = null
        abandonAudioFocus()
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        }
    }
}
