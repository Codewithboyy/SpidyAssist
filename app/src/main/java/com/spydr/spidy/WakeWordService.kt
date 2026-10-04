package com.spydr.spidy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class WakeWordService : Service(), WakeWordDetector.Listener {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "spider_wakeword"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_LISTENING = "com.spydr.spidy.ACTION_START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.spydr.spidy.ACTION_STOP_LISTENING"

        /**
         * Priority-ordered list of accepted wake word phrases.
         * Long multi-word phrases are listed first so exact prefix matches 
         * aren't clipped early by shorter tokens like "spidy".
         */
        val ACCEPTED_WAKE_WORDS = listOf(
            "hey spider",
            "hey spidy",
            "ok spider",
            "ok spidy",
            "spider",
            "spidey",
            "spidy"
        )

        /**
         * Verifies whether transcript text contains any accepted wake-word variant.
         */
        fun isWakeWordMatch(transcript: String): Boolean {
            val lower = transcript.lowercase().trim()
            return ACCEPTED_WAKE_WORDS.any { word -> lower.contains(word) }
        }

        /**
         * Strips the wake word prefix cleanly from raw prompt text before processing.
         */
        fun stripWakeWord(rawText: String): String {
            var cleaned = rawText.lowercase().trim().replace(Regex("[^a-z0-9\\s]"), "")
            for (word in ACCEPTED_WAKE_WORDS) {
                if (cleaned.startsWith(word)) {
                    cleaned = cleaned.removePrefix(word).trim()
                    break
                }
            }
            return cleaned.ifEmpty { rawText }
        }

        fun startService(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).apply {
                action = ACTION_START_LISTENING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopListening(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).apply {
                action = ACTION_STOP_LISTENING
            }
            context.startService(intent)
        }
    }

    private var wakeWordDetector: WakeWordDetector? = null
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var isStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundServiceSafely()
        createInvisibleOverlayWindow()

        wakeWordDetector = WakeWordDetector(
            context = this,
            listener = this
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForegroundServiceSafely()

        when (intent?.action) {
            ACTION_STOP_LISTENING -> pauseListening()
            ACTION_START_LISTENING, null -> {
                if (!isStarted) {
                    isStarted = true
                    resumeListening()
                } else {
                    Log.d(TAG, "WakeWordService already running. Resuming listener.")
                    resumeListening()
                }
            }
        }

        return START_STICKY
    }

    private fun createInvisibleOverlayWindow() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView = View(this)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                1, 1,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            windowManager?.addView(overlayView, params)
            Log.d(TAG, "Invisible view window overlay injected.")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot create window overlay layer. SYSTEM_ALERT_WINDOW required.", e)
        }
    }

    private fun startForegroundServiceSafely() {
        try {
            val notification = createNotification("Listening for 'Spidy' or 'Spider'...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Microphone FGS type restricted by OS, falling back to standard foreground service", e)
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            stopSelf()
        }
    }

    fun pauseListening() {
        Log.d(TAG, "Pausing wake-word listener...")
        wakeWordDetector?.stop()
    }

    fun resumeListening() {
        Log.d(TAG, "Resuming wake-word listener...")
        val sessionId = SpidyStateManager.activeSessionId // reuse session — memory preserved
        SpidyStateManager.transitionTo(AssistantState.WAKE_WORD_LISTENING, sessionId)
        wakeWordDetector?.resetWakeWord()
        wakeWordDetector?.start()
    }

    override fun onWakeWordDetected() {
        val currentState = SpidyStateManager.activeState
        Log.d(TAG, "Wake word trigger received in state: $currentState")

        // Block re-triggering only if system is busy performing network queries or speaking out loud
        if (currentState == AssistantState.PROCESSING || currentState == AssistantState.SPEAKING) {
            Log.w(TAG, "Spidy is currently $currentState. Suppressing wake trigger.")
            return
        }

        Log.d(TAG, "Wake word match confirmed ('Spidy'/'Spider'). Opening Assistant...")

        val sessionId = SpidyStateManager.activeSessionId // reuse session — memory preserved
        SpidyStateManager.transitionTo(AssistantState.COMMAND_LISTENING, sessionId)

        // Release hardware microphone binding from Vosk before speech recognition triggers
        pauseListening()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT

            putExtra("START_LISTENING", true)
            putExtra("SESSION_ID", sessionId)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            pendingIntent.send()
            Log.d(TAG, "Triggered MainActivity launch via PendingIntent.")
        } catch (e: Exception) {
            Log.e(TAG, "PendingIntent launch failed, launching Activity directly", e)
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        isStarted = false
        val currentSession = SpidyStateManager.activeSessionId
        SpidyStateManager.transitionTo(AssistantState.IDLE, currentSession)

        try {
            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay view on destroy", e)
        }

        wakeWordDetector?.destroy()
        wakeWordDetector = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Spidy Voice Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Always-on voice wake word tracking"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Spidy Assistant")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
