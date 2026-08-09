package com.spydr.spidy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class CommandProcessor(private val context: Context) {

    private val appLauncher = AppLauncher(context)
    private val contactsManager = ContactsManager(context)

    private var llmInference: LlmInference? = null

    init {
        // Initialize local on-device AI model asynchronously
        initLocalAI()
    }

    private fun initLocalAI() {
        try {
            val modelFile = File(context.filesDir, "gemma-2b-it.bin")
            if (!modelFile.exists()) {
                // Copy asset model file to internal storage on first launch
                context.assets.open("gemma-2b-it.bin").use { inputStream ->
                    FileOutputStream(modelFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(128) // Concise responses optimized for speech
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val callPattern = Pattern.compile("^(call|dial)\\s+(.+)", Pattern.CASE_INSENSITIVE)
    private val appPattern = Pattern.compile("^(open|launch|start)\\s+(.+)", Pattern.CASE_INSENSITIVE)
    private val msgPattern = Pattern.compile("^(send message to|text|msg)\\s+(.+)", Pattern.CASE_INSENSITIVE)
    private val searchPattern = Pattern.compile("^(search|google|find)\\s+(.+)", Pattern.CASE_INSENSITIVE)

    suspend fun process(command: String): String = withContext(Dispatchers.IO) {
        val clean = command.trim()
        val lower = clean.lowercase(Locale.ROOT)

        if (lower.isEmpty()) return@withContext ""

        // --- 1. Fast Conversational Checks ---
        if (lower in listOf("hello", "hi", "hey", "hello spidy", "hi spidy", "hey spidy")) {
            return@withContext "Hello! How can I assist you today?"
        }

        if ("who are you" in lower || "your name" in lower) {
            return@withContext "I am Spidy, your local on-device voice assistant."
        }

        // --- 2. Direct Call Action ---
        val callMatcher = callPattern.matcher(clean)
        if (callMatcher.find()) {
            val target = callMatcher.group(2)?.trim() ?: ""
            if (target.isEmpty()) return@withContext "Who would you like to call?"

            val phone = contactsManager.getPhoneNumber(target) ?: target
            val hasCallPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            return@withContext if (hasCallPermission) {
                try {
                    val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(phone)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(callIntent)
                    "Calling $target..."
                } catch (e: Exception) {
                    openDialer(phone, target)
                }
            } else {
                openDialer(phone, target)
            }
        }

        // --- 3. App Launching ---
        val appMatcher = appPattern.matcher(clean)
        if (appMatcher.find()) {
            val appName = appMatcher.group(2) ?: clean
            return@withContext appLauncher.launchApp(appName)
        }

        // --- 4. Messaging ---
        val msgMatcher = msgPattern.matcher(clean)
        if (msgMatcher.find()) {
            val rest = msgMatcher.group(2) ?: ""
            val parts = rest.split(Regex("\\s+(message|text|saying)\\s+"), limit = 2)
            val contact = parts[0].trim()
            val body = if (parts.size > 1) parts[1].trim() else ""
            val phone = contactsManager.getPhoneNumber(contact) ?: contact

            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
                putExtra("sms_body", body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(smsIntent)
            return@withContext "Messaging $contact"
        }

        // --- 5. Hardware Controls & System Info ---
        if ("flashlight" in lower || "torch" in lower) {
            return@withContext toggleFlashlight(!lower.contains("off"))
        }

        if ("volume" in lower) {
            return@withContext if ("up" in lower || "increase" in lower) {
                adjustVolume(AudioManager.ADJUST_RAISE)
                "Volume increased"
            } else {
                adjustVolume(AudioManager.ADJUST_LOWER)
                "Volume decreased"
            }
        }

        if ("time" in lower) {
            return@withContext "Current time: ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())}"
        }

        if ("date" in lower || "today" in lower) {
            return@withContext "Today is ${SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())}"
        }

        // --- 6. Explicit Web Search ---
        val searchMatcher = searchPattern.matcher(clean)
        if (searchMatcher.find()) {
            val query = searchMatcher.group(2) ?: ""
            val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(searchIntent)
            return@withContext "Searching Google for $query"
        }

        // --- 7. Local On-Device AI Fallback ---
        return@withContext generateLocalResponse(command)
    }

    private fun generateLocalResponse(prompt: String): String {
        val engine = llmInference
            ?: return "Local AI model is initializing, please try again in a moment."

        return try {
            val formattedPrompt = "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"
            engine.generateResponse(formattedPrompt) ?: "I couldn't process that offline."
        } catch (e: Exception) {
            "Error running local AI process."
        }
    }

    private fun openDialer(phone: String, target: String): String {
        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(dialIntent)
        return "Opening dialer for $target"
    }

    private fun toggleFlashlight(status: Boolean): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, status)
            if (status) "Flashlight turned on" else "Flashlight turned off"
        } catch (e: Exception) {
            "Flashlight unavailable"
        }
    }

    private fun adjustVolume(direction: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }
}
