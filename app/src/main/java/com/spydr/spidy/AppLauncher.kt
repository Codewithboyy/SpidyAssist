package com.spydr.spidy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Locale

class AppLauncher(private val context: Context) {

    private val appMap = HashMap<String, String>()

    init {
        cacheApps()
    }

    private fun cacheApps() {
        val pm = context.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in installed) {
            if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                val label = pm.getApplicationLabel(app).toString().lowercase(Locale.ROOT)
                appMap[label] = app.packageName
            }
        }
    }

    fun launchApp(rawQuery: String): String {
        val pm = context.packageManager
        val cleanQuery = rawQuery.lowercase(Locale.ROOT)
            .replace("open", "")
            .replace("launch", "")
            .replace("start", "")
            .replace("app", "")
            .trim()

        // Ignore single/two-letter noise or conversational words
        if (cleanQuery.length < 3 || cleanQuery in listOf("hi", "hello", "hey", "yes", "no")) {
            return "App \"$cleanQuery\" not found"
        }

        // Direct HashMap lookup O(1)
        var targetPackage = appMap[cleanQuery]

        // Fallback fuzzy search with exact boundary checks
        if (targetPackage == null) {
            for ((label, pkg) in appMap) {
                if (label == cleanQuery || label.startsWith("$cleanQuery ")) {
                    targetPackage = pkg
                    break
                }
            }
        }

        return if (targetPackage != null) {
            val intent = pm.getLaunchIntentForPackage(targetPackage)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            if (intent != null) {
                context.startActivity(intent)
                "Opening ${cleanQuery.replaceFirstChar { it.uppercase() }}"
            } else {
                "Unable to start $cleanQuery"
            }
        } else {
            "App \"$cleanQuery\" not found"
        }
    }
}
