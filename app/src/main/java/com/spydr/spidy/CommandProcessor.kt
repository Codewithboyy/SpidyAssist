package com.spydr.spidy

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CommandProcessor(
    private val context: Context
) {
    private companion object {
        private const val TAG = "CommandProcessor"
    }

    private val launcher = AppLauncher(context)
    private val contactsManager = ContactsManager(context)

    fun process(command: String): String {
        val originalText = command.trim()
        val text = originalText.lowercase(Locale.getDefault())

        return when {
            // Conversational AI Intent Categories
            text.matches(Regex(".*\\b(hello|hi|hey|greetings)\\b.*")) -> {
                listOf(
                    "Hello! How can I assist you today?",
                    "Hi there! What can I do for you?",
                    "Hey! Spidy assist active and ready."
                ).random()
            }

            text.contains("who are you") || text.contains("your name") -> {
                "I am Spidy, your offline voice assistant assistant engineered in Kotlin Compose."
            }

            text.contains("how are you") || text.contains("how's it going") -> {
                "Systems are fully optimized, operational, and listening for your command parameters."
            }

            // Core Functional Commands
            text.startsWith("open ") -> {
                val app = text.substring(5).trim()
                if (launcher.openApp(app)) "Opening $app." else "I couldn't find $app on your device."
            }
            
            text.startsWith("call ") -> {
                val person = originalText.substring(5).trim()
                if (contactsManager.callContact(person)) "Calling $person." else "I couldn't find $person in your contacts."
            }

            text.startsWith("search ") || text.startsWith("search for ") -> {
                val query = if (text.startsWith("search for ")) originalText.substring(11).trim() else originalText.substring(7).trim()
                executeWebSearch(query)
                "Searching for $query."
            }

            text.contains("time") -> {
                val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                "The time is $time."
            }

            text.contains("date") || text.contains("today") -> {
                val date = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(Date())
                "Today is $date."
            }

            text.contains("battery") -> {
                try {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    "The battery is at $level percent."
                } catch (e: Exception) {
                    "I am unable to check the battery status right now."
                }
            }

            text.contains("flashlight on") || text.contains("turn on flashlight") || text.contains("torch on") -> {
                if (toggleFlash(true)) "Flashlight turned on." else "I couldn't access your flash device hardware."
            }

            text.contains("flashlight off") || text.contains("turn off flashlight") || text.contains("torch off") -> {
                if (toggleFlash(false)) "Flashlight turned off." else "I couldn't toggle the flashlight off."
            }

            text.startsWith("set alarm") || text.contains("open alarm") -> {
                try {
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "Opening your alarm clock settings."
                } catch (e: Exception) {
                    "I couldn't open the alarm clock application."
                }
            }

            // Smart Fallback
            else -> {
                executeWebSearch(originalText)
                "Searching the web for: $originalText"
            }
        }
    }

    private fun executeWebSearch(query: String) {
        try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val webUri = Uri.parse("https://google.com{Uri.encode(query)}")
                val fallbackIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (inner: Exception) {
                Log.e(TAG, "No search provider found", inner)
            }
        }
    }

    private fun toggleFlash(enable: Boolean): Boolean {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId != null) {
                manager.setTorchMode(cameraId, enable)
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }
}
