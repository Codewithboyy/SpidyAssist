package com.spydr.spidy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity(), VoiceManager.VoiceCallback {

    private companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var assistant: Assistant
    private lateinit var voiceManager: VoiceManager
    private lateinit var commandProcessor: CommandProcessor

    private val messages = mutableStateListOf<Message>()
    private val status = mutableStateOf("Ready")
    private var listening by mutableStateOf(false)
    
    private var lastCommand = ""
    private var lastCommandTime = 0L

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val audioGranted = result[Manifest.permission.RECORD_AUDIO] == true
            Log.d(TAG, "Audio permission granted status: $audioGranted")
            
            checkAndRequestBatteryExemption()
            
            if (audioGranted) {
                startWakeWordServiceSafely()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Edge-to-edge layout & hiding system navigation bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        
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
        
        // Soft background dimming
        window.setDimAmount(0.30f)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        
        commandProcessor = CommandProcessor(this)
        assistant = Assistant(this)
        voiceManager = VoiceManager(this, this)

        requestCoreSystemPermissions()
        handleIntent(intent)

        status.value = "Initializing..."
        
        CoroutineScope(Dispatchers.IO).launch {
            val modelDir = java.io.File(filesDir, "model")
            if (!modelDir.exists()) {
                AssetUtils.copyAssetFolder(this@MainActivity, "model", modelDir)
            }
            
            withContext(Dispatchers.Main) {
                status.value = "Ready"
                if (!listening) {
                    startWakeWordServiceSafely()
                }
            }
        }

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
                            voiceManager.startListening()
                        }
                    },
                    onStartService = {
                        if (ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            startWakeWordServiceSafely()
                        } else {
                            requestCoreSystemPermissions()
                        }
                    },
                    onDismiss = {
                        finish()
                    }
                )
            }
        }
    }

    private fun hideSystemNavigation() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
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
        if (startListening) {
            intent.removeExtra("START_LISTENING")
        
            try {
                WakeWordService.resetProcessing()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling service mic handoff", e)
            }
        
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                voiceManager.startListening()
            }, 300)
        }
    }

    private fun startWakeWordServiceSafely() {
        try {
            val intent = Intent(this, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize foreground sticky service setup", e)
        }
    }

    private fun requestCoreSystemPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
                    val intent = Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    return
                }
            }

            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prompt for special system workspace exemptions", e)
        }
    }

    override fun onListening() {
        listening = true
        status.value = "Listening..."
    }

    override fun onCommand(text: String) {
        val command = text.trim()

        if (command.isBlank()) {
            status.value = "Ready"
            WakeWordService.resetProcessing()
            return
        }
        
        val now = System.currentTimeMillis()
        if (command.equals(lastCommand, true) && now - lastCommandTime < 1500) {
            return
        }

        lastCommand = command
        lastCommandTime = now
        listening = false
        status.value = "Thinking..."

        messages.add(Message(command, true))

        lifecycleScope.launch {
            val response = commandProcessor.process(command)
            messages.add(Message(response, false))
            status.value = "Ready"

            assistant.handleResponse(response) {
                WakeWordService.resetProcessing()
            }
            
            voiceManager.stopListening()
        }
    }

    override fun onError(errorCode: Int) {
        listening = false
        status.value = "Ready"
        WakeWordService.resetProcessing()
    }

    override fun onDestroy() {
        voiceManager.destroy()
        super.onDestroy()
    }
}

data class Message(
    val text: String,
    val user: Boolean
)
