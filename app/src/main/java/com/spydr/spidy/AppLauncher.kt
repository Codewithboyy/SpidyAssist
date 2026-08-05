package com.spydr.spidy

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Locale

class AppLauncher(
    private val context: Context
) {
    private companion object {
        private const val TAG = "AppLauncher"
        
        // Clean explicit target directory mapping. 
        // This is 100% safe and will never trigger Play Protect security flags.
        private val SAFE_APP_MAP = mapOf(
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "whatsapp" to "com.whatsapp",
            "facebook" to "com.facebook.katana",
            "instagram" to "com.instagram.android",
            "gmail" to "com.google.android.gm",
            "settings" to "android.settings.SETTINGS" // Special system action mapping
        )
    }

    fun openApp(appName: String): Boolean {
        if (appName.isBlank()) return false

        val cleanName = appName.trim().lowercase(Locale.getDefault())
        val targetPackage = SAFE_APP_MAP[cleanName]

        if (targetPackage == null) {
            Log.d(TAG, "App alias '$cleanName' not registered in safe layout map.")
            return false
        }

        try {
            val pm = context.packageManager

            // Special handler for opening system settings safely
            if (targetPackage == "android.settings.SETTINGS") {
                val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            }

            // Standard explicit package launcher execution route
            val launchIntent = pm.getLaunchIntentForPackage(targetPackage)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (launchIntent != null) {
                context.startActivity(launchIntent)
                Log.d(TAG, "Successfully initialized target package securely: $targetPackage")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch application structure safely: $targetPackage", e)
        }

        return false
    }
}
