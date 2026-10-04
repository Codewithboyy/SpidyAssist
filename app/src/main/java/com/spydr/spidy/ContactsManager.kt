package com.spydr.spidy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.core.content.ContextCompat

class ContactsManager(private val context: Context) {

    private companion object {
        private const val TAG = "ContactsManager"
    }

    // ── Phone number lookup ───────────────────────────────────────────────────

    fun getPhoneNumber(name: String): String? {
        if (name.isBlank()) return null
        if (!hasContactsPermission()) {
            Log.w(TAG, "READ_CONTACTS permission not granted.")
            return null
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )

        // 1. Exact match
        queryPhoneNumber(
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?",
            arrayOf(name.trim())
        )?.let { return it }

        // 2. First-name match (spoken name like "call John")
        queryPhoneNumber(
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("${name.trim()}%")
        )?.let { return it }

        // 3. Substring fallback
        return queryPhoneNumber(
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%${name.trim()}%")
        )
    }

    private fun queryPhoneNumber(
        projection: Array<String>,
        selection: String,
        selectionArgs: Array<String>
    ): String? {
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                // Prefer super-primary, then mobile type (type=2), then anything
                "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, " +
                "${ContactsContract.CommonDataKinds.Phone.TYPE} ASC"
            )?.use { cursor ->
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numIdx != -1 && cursor.moveToFirst()) {
                    cursor.getString(numIdx)?.takeIf { it.isNotBlank() }
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact query error", e)
            null
        }
    }

    // ── Phone call ────────────────────────────────────────────────────────────

    fun callContact(name: String): Boolean {
        val phone = getPhoneNumber(name) ?: run {
            Log.w(TAG, "No contact found for '$name'.")
            return false
        }

        val action = if (hasCallPermission()) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return try {
            val intent = Intent(action, Uri.parse("tel:${Uri.encode(phone)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                Log.d(TAG, "Call initiated to $name ($phone)")
                true
            } else {
                Log.e(TAG, "No app to handle call intent.")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Call intent failed", e)
            false
        }
    }

    // ── SMS ───────────────────────────────────────────────────────────────────

    fun openSms(name: String, message: String = ""): String {
        val phone = getPhoneNumber(name)

        return if (phone != null) {
            val uri = Uri.parse("smsto:${Uri.encode(phone)}")
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                if (message.isNotBlank()) putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                if (message.isNotBlank()) "Sending message to $name."
                else "Opening SMS to $name."
            } catch (e: Exception) {
                Log.e(TAG, "SMS intent failed", e)
                "Couldn't open the messaging app."
            }
        } else {
            // No number — open generic SMS composer
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_MESSAGING)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "I couldn't find $name in your contacts. Opening messages."
            } catch (e: Exception) {
                "I couldn't find $name in your contacts."
            }
        }
    }

    // ── Send SMS directly (with SEND_SMS permission) ──────────────────────────

    fun sendSmsDirectly(name: String, message: String): String {
        if (!hasSmsPermission()) return openSms(name, message)

        val phone = getPhoneNumber(name) ?: return "I couldn't find $name in your contacts."

        return try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(android.telephony.SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                android.telephony.SmsManager.getDefault()
            }
            smsManager?.sendTextMessage(phone, null, message, null, null)
            "Message sent to $name."
        } catch (e: Exception) {
            Log.e(TAG, "Direct SMS failed", e)
            openSms(name, message)
        }
    }

    // ── Contact search (for display) ──────────────────────────────────────────

    fun findContactName(input: String): String? {
        if (!hasContactsPermission()) return null
        return try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
                arrayOf("%${input.trim()}%"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    if (idx != -1) cursor.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact name search error", e)
            null
        }
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun hasContactsPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasCallPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasSmsPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED
}
