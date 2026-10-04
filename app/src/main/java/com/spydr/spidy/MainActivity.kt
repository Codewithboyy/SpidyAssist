package com.spydr.spidy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), VoiceManager.VoiceCallback {

    private companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "spidy_main_prefs"
        private const val KEY_PERSISTENT_SESSION = "persistent_session_id"
        private const val KEY_LAST_ACTIVE = "last_active_time"

        // Session expires after 30 minutes of idle — feels like a fresh conversation
        private const val SESSION_IDLE_EXPIRY_MS = 30 * 60 * 1000L

        private val COMPLETED_THINK_REGEX = Regex("<think>[\\s\\S]*?</think>")
        private val STREAMING_THINK_REGEX = Regex("<think>[\\s\\S]*$")
    }

    private lateinit var assistant: Assistant
    private lateinit var voiceManager: VoiceManager
    private lateinit var commandProcessor: CommandProcessor

    private val messages = mutableStateListOf<Message>()
    private val status = mutableStateOf("Ready")
    private var listening by mutableStateOf(false)

    private var lastCommand = ""
    private var lastCommandTime = 0L

    // Persistent session — survives wake triggers and activity restarts
    private var persistentSessionId: Long = -1L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val audioGranted = result[Manifest.permission.RECORD_AUDIO] == true
            Log.d(TAG, "Audio permission granted: $audioGranted")
            checkAndRequestBatteryExemption()
            if (audioGranted) WakeWordService.startService(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
        hideSystemNavigation()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        window.setDimAmount(0.35f)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        commandProcessor = CommandProcessor(this)
        assistant = Assistant(this)
        voiceManager = VoiceManager(this, this)

        // Restore or create persistent session
        persistentSessionId = getOrCreatePersistentSession()
        SpidyStateManager.restoreSession(persistentSessionId)

        requestCoreSystemPermissions()
        WakeWordService.startService(this)
        handleIntent(intent)

        setContent {
            MaterialTheme {
                AssistantScreen(
                    messages = messages,
                    status = status.value,
                    listening = listening,
                    onMicClick = {
                        if (listening) {
                            voiceManager.stopListening()
                        } else {
                            // Reuse persistent session — don't create new one
                            val sessionId = getOrCreatePersistentSession()
                            SpidyStateManager.transitionTo(AssistantState.COMMAND_LISTENING, sessionId)
                            WakeWordService.stopListening(this)
                            voiceManager.startListening(sessionId)
                        }
                    },
                    onSendTextCommand = { typedQuery ->
                        if (typedQuery.isNotBlank()) {
                            val sessionId = getOrCreatePersistentSession()
                            WakeWordService.stopListening(this)
                            onCommand(typedQuery, sessionId)
                        }
                    },
                    onStartService = {
                        if (ContextCompat.checkSelfPermission(
                                this, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            WakeWordService.startService(this)
                        } else {
                            requestCoreSystemPermissions()
                        }
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }

    /**
     * Returns existing session if within idle window, else creates a new one.
     * This is the single source of truth for session ID across the app.
     */
    private fun getOrCreatePersistentSession(): Long {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getLong(KEY_PERSISTENT_SESSION, -1L)
        val lastActive = prefs.getLong(KEY_LAST_ACTIVE, 0L)
        val now = System.currentTimeMillis()

        return if (savedId != -1L && (now - lastActive) < SESSION_IDLE_EXPIRY_MS) {
            // Session still warm — reuse it, memory intact
            Log.d(TAG, "Reusing session $savedId (idle: ${(now - lastActive) / 1000}s)")
            persistentSessionId = savedId
            savedId
        } else {
            // Session expired or first launch — create fresh
            val newId = now
            prefs.edit()
                .putLong(KEY_PERSISTENT_SESSION, newId)
                .putLong(KEY_LAST_ACTIVE, now)
                .apply()
            Log.d(TAG, "New session created: $newId")
            persistentSessionId = newId
            newId
        }
    }

    private fun touchSession() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_ACTIVE, System.currentTimeMillis()).apply()
    }

    private fun hideSystemNavigation() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    override fun onResume() {
        super.onResume()
        hideSystemNavigation()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val startListening = intent.getBooleanExtra("START_LISTENING", false)
        val intentSessionId = intent.getLongExtra("SESSION_ID", -1L)

        if (startListening) {
            intent.removeExtra("START_LISTENING")
            intent.removeExtra("SESSION_ID")

            // Use persistent session — not the one from the wake word service (which creates new)
            val sessionId = getOrCreatePersistentSession()
            SpidyStateManager.transitionTo(AssistantState.COMMAND_LISTENING, sessionId)
            WakeWordService.stopListening(this)
            voiceManager.startListening(sessionId)
        }
    }

    private fun requestCoreSystemPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            @Suppress("DEPRECATION")
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun checkAndRequestBatteryExemption() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                    return
                }
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    ).apply { data = Uri.parse("package:$packageName") })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Battery exemption request failed", e)
        }
    }

    override fun onListening() {
        runOnUiThread {
            listening = true
            status.value = "Listening..."
        }
    }

    override fun onCommand(text: String, sessionId: Long) {
        if (!SpidyStateManager.isSessionValid(sessionId)) {
            Log.w(TAG, "Ignoring stale session command: $sessionId")
            return
        }

        val command = text.trim()
        if (command.isBlank()) {
            runOnUiThread { status.value = "Ready"; listening = false }
            WakeWordService.startService(this)
            return
        }

        val now = System.currentTimeMillis()
        if (command.equals(lastCommand, true) && now - lastCommandTime < 1500) {
            Log.w(TAG, "Duplicate command suppressed: '$command'")
            return
        }

        lastCommand = command
        lastCommandTime = now
        touchSession() // Keep session warm

        runOnUiThread {
            listening = false
            status.value = "Thinking..."
            messages.add(Message(text = command, user = true))
        }

        SpidyStateManager.transitionTo(AssistantState.PROCESSING, sessionId)

        lifecycleScope.launch(Dispatchers.Default) {
            val assistantMsgIndex = withContext(Dispatchers.Main) {
                val idx = messages.size
                messages.add(Message(text = "...", user = false))
                idx
            }

            var accumulated = ""

            val rawResponse = commandProcessor.processStream(command, sessionId) { token ->
                accumulated += token
                val clean = accumulated
                    .replace(COMPLETED_THINK_REGEX, "")
                    .replace(STREAMING_THINK_REGEX, "")
                    .trimStart()

                if (clean.isNotEmpty()) {
                    runOnUiThread {
                        if (assistantMsgIndex < messages.size) {
                            messages[assistantMsgIndex] = Message(text = clean, user = false)
                        }
                    }
                }
            }

            val finalText = rawResponse
                .replace(COMPLETED_THINK_REGEX, "")
                .replace(STREAMING_THINK_REGEX, "")
                .trim()
                .ifEmpty { "I couldn't process that." }

            withContext(Dispatchers.Main) {
                if (assistantMsgIndex < messages.size) {
                    messages[assistantMsgIndex] = Message(text = finalText, user = false)
                }
                status.value = "Speaking..."
            }

            SpidyStateManager.transitionTo(AssistantState.SPEAKING, sessionId)

            assistant.handleResponse(finalText, sessionId) { doneSession ->
                if (SpidyStateManager.isSessionValid(doneSession)) {
                    runOnUiThread {
                        status.value = "Ready"
                        listening = false
                        WakeWordService.startService(this@MainActivity)
                    }
                }
            }
        }
    }

    override fun onError(errorCode: Int, sessionId: Long) {
        if (!SpidyStateManager.isSessionValid(sessionId)) return
        Log.w(TAG, "Voice error $errorCode on session $sessionId")
        runOnUiThread { listening = false; status.value = "Ready" }
        WakeWordService.startService(this)
    }

    override fun onDestroy() {
        commandProcessor.releaseResources()
        voiceManager.destroy()
        assistant.destroy()
        super.onDestroy()
    }
}
