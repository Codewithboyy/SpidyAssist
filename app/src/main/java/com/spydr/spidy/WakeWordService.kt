package com.spydr.spidy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.app.PendingIntent
import android.content.Context // REQUIRED IMPORT FOR WAKELOCK MANAGEMENT
import android.os.Build
import android.os.IBinder
import android.os.PowerManager // REQUIRED IMPORT FOR POWER CONFIGURATION
import android.util.Log
import androidx.core.app.NotificationCompat

class WakeWordService : Service(), WakeWordDetector.Listener {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "spider_wakeword"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isProcessing = false

        private var instance: WakeWordService? = null

        fun resetProcessing() {
            isProcessing = false
            instance?.resumeListening()
        }
    }
    
    // STEP 2 VARIABLE LOCATION: Track lock allocation across runtime instances
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var wakeWordDetector: WakeWordDetector
    
    // TRACKING VARIABLE: Keep a reference to the invisible view window overlay layer
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        // STEP 2 HOOK LOCATION: Acquire lock at the immediate entry point of onCreate
        // This ensures the CPU cannot drop context lines while the engine starts
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Spidy:BackgroundMicLock").apply {
                // Safe 10-minute structural auto-timeout release to protect device diagnostics
                acquire(10 * 60 * 1000L) 
            }
            Log.d(TAG, "Continuous tracking background WakeLock successfully acquired.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mount background wake lock context rules", e)
        }

        createNotificationChannel()
        startForegroundServiceSafely()
        
        // WORKAROUND: Force an invisible system overlay to keep microphone permission unfrozen on home screen
        createInvisibleOverlayWindow()

        wakeWordDetector = WakeWordDetector(
            context = this,
            listener = this
        )
        wakeWordDetector.start()
    }
    
    private fun createInvisibleOverlayWindow() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView = View(this)

            // Setup a 1x1 transparent layout frame configuration
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                1, 1, // 1-pixel frame size
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            windowManager?.addView(overlayView, params)
            Log.d(TAG, "Invisible view window overlay injected. Home screen mic safe.")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot create window overlay layer. Check Draw Over Other Apps permission.", e)
        }
    }
    
    private fun startForegroundServiceSafely() {
        try {
            val notification = createNotification("Listening for \"Spider\"")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service due to OS background execution limits", e)
            stopSelf()
        }
    }
    
    fun pauseListening() {
        wakeWordDetector.stop()
    }

    fun resumeListening() {
        wakeWordDetector.resetWakeWord()
        wakeWordDetector.start()
    }

    override fun onWakeWordDetected() {
        if (isProcessing) return

        isProcessing = true
        pauseListening()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP

            putExtra("START_LISTENING", true)
        }
        startActivity(intent)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Clear any previous startup configurations and force a high priority refresh
        createNotificationChannel()
        startForegroundServiceSafely()
        
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null

        // STEP 2 CLEANUP LOCATION: Release your lock claim cleanly 
        // to prevent background battery warning warnings on target systems
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Background WakeLock successfully released.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing wake lock lifecycle allocation during destroy", e)
        }
        
        // CLEANUP Overlay view resource tracking structures cleanly to avoid leaks
        try {
            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing invisible view frame on destroy", e)
        }

        wakeWordDetector.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Spidy Assistant Persistent Monitor",
                // CRITICAL CHANGE: Changed IMPORTANCE_LOW to IMPORTANCE_HIGH.
                // This lets the OS know that dropping this service interrupts user expectations.
                NotificationManager.IMPORTANCE_HIGH 
            ).apply {
                description = "Always-on voice wake word tracking loop"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            channel.description = "Wake word detection"
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
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
