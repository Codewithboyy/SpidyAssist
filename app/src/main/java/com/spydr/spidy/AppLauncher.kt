package com.spydr.spidy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import java.util.Locale

class AppLauncher(private val context: Context) {

    private companion object {
        private const val TAG = "AppLauncher"

        // Well-known package names for direct deep-link targets
        private const val PKG_WHATSAPP         = "com.whatsapp"
        private const val PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b"
        private const val PKG_YOUTUBE          = "com.google.android.youtube"
        private const val PKG_MAPS             = "com.google.android.apps.maps"
        private const val PKG_CHROME           = "com.android.chrome"
        private const val PKG_GMAIL            = "com.google.android.gm"
        private const val PKG_CAMERA           = "com.android.camera2"
        private const val PKG_SPOTIFY          = "com.spotify.music"
        private const val PKG_INSTAGRAM        = "com.instagram.android"
        private const val PKG_TELEGRAM         = "org.telegram.messenger"

        // Alias map for common spoken names → package
        private val KNOWN_ALIASES = mapOf(
            "youtube"    to PKG_YOUTUBE,
            "maps"       to PKG_MAPS,
            "google maps" to PKG_MAPS,
            "chrome"     to PKG_CHROME,
            "gmail"      to PKG_GMAIL,
            "mail"       to PKG_GMAIL,
            "camera"     to PKG_CAMERA,
            "spotify"    to PKG_SPOTIFY,
            "instagram"  to PKG_INSTAGRAM,
            "telegram"   to PKG_TELEGRAM,
            "whatsapp"   to PKG_WHATSAPP,
        )
    }

    private val appMap = HashMap<String, String>()

    init { cacheApps() }

    fun cacheApps() {
        appMap.clear()
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        pm.queryIntentActivities(intent, 0).forEach { info ->
            val label = info.loadLabel(pm).toString().lowercase(Locale.ROOT).trim()
            appMap[label] = info.activityInfo.packageName
        }
        Log.d(TAG, "Cached ${appMap.size} apps.")
    }

    // ── Standard app launch ───────────────────────────────────────────────────

    fun launchApp(rawQuery: String): String {
        val pm = context.packageManager
        val clean = rawQuery.lowercase(Locale.ROOT)
            .replace(Regex("^(open|launch|start|run)\\s+"), "")
            .replace(Regex("\\s+app$"), "")
            .trim()

        if (clean.length < 2 || clean in listOf("hi", "hello", "hey", "yes", "no")) {
            return "App \"$clean\" not found."
        }

        // 1. Known alias lookup
        val aliasPkg = KNOWN_ALIASES[clean]
        if (aliasPkg != null) {
            val result = launchPackage(aliasPkg, clean, pm)
            if (result != null) return result
        }

        // 2. Direct map match
        var pkg = appMap[clean]

        // 3. Prefix / substring search
        if (pkg == null) {
            pkg = appMap.entries.firstOrNull { (label, _) ->
                label == clean || label.startsWith(clean) || clean.contains(label)
            }?.value
        }

        // 4. Refresh cache and retry
        if (pkg == null) {
            cacheApps()
            pkg = appMap.entries.firstOrNull { (label, _) ->
                label == clean || label.startsWith(clean) || label.contains(clean)
            }?.value
        }

        if (pkg != null) {
            val result = launchPackage(pkg, clean, pm)
            if (result != null) return result
        }

        return "App \"$clean\" not found."
    }

    private fun launchPackage(pkg: String, name: String, pm: PackageManager): String? {
        val intent = pm.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        } ?: return null
        return try {
            context.startActivity(intent)
            val displayName = name.replaceFirstChar { it.uppercase() }
            "Opening $displayName."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $pkg", e)
            null
        }
    }

    // ── WhatsApp deep link ────────────────────────────────────────────────────

    fun openWhatsApp(contactName: String): String {
        // Try to get phone number from contacts, then deep-link
        val contactsManager = ContactsManager(context)
        val phone = contactsManager.getPhoneNumber(contactName)

        return if (phone != null) {
            val normalized = phone.replace(Regex("[^\\d+]"), "")
            val uri = Uri.parse("https://wa.me/$normalized")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(PKG_WHATSAPP)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                "Opening WhatsApp chat with $contactName."
            } catch (e: Exception) {
                // WhatsApp not installed or number not on WA — open app directly
                launchApp("whatsapp")
            }
        } else {
            // No number found — open WhatsApp and let user pick
            val result = launchApp("whatsapp")
            if (result.contains("not found", ignoreCase = true)) {
                "WhatsApp isn't installed on this device."
            } else {
                "I couldn't find $contactName in your contacts. Opening WhatsApp."
            }
        }
    }

    // ── YouTube search ────────────────────────────────────────────────────────

    fun searchYoutube(query: String): String {
        val uri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(PKG_YOUTUBE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Searching YouTube for $query."
        } catch (e: Exception) {
            // Fall back to browser
            val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            "Opening YouTube search for $query."
        }
    }

    // ── Google Maps navigation ────────────────────────────────────────────────

    fun navigateTo(destination: String): String {
        val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(PKG_MAPS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Starting navigation to $destination."
        } catch (e: Exception) {
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://maps.google.com/?q=${Uri.encode(destination)}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(browserIntent)
            "Opening maps for $destination."
        }
    }

    // ── Web search ────────────────────────────────────────────────────────────

    fun webSearch(query: String): String {
        val uri = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Searching the web for $query."
        } catch (e: Exception) {
            Log.e(TAG, "Web search failed", e)
            "Couldn't open the browser."
        }
    }
}
