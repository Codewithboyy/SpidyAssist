package com.spydr.spidy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

class ContactsManager(
    private val context: Context
) {
    private companion object {
        private const val TAG = "ContactsManager"
    }

    /**
     * Looks up and returns the phone number string for a given contact name.
     */
    fun getPhoneNumber(name: String): String? {
        if (name.isBlank()) return null

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot access phone book: READ_CONTACTS permission was not granted by user.")
            return null
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )

        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )

            cursor?.use { validCursor ->
                val numberIndex = validCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numberIndex != -1 && validCursor.moveToFirst()) {
                    val phone = validCursor.getString(numberIndex)
                    if (!phone.isNullOrBlank()) {
                        return phone
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encountered database exception querying contact phone number", e)
        }

        return null
    }

    /**
     * Attempts to find a contact matching the provided string parameter and opens the device dialer.
     */
    fun callContact(name: String): Boolean {
        if (name.isBlank()) return false
        
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot access phone book: READ_CONTACTS permission was not granted by user.")
            return false
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )

            cursor?.use { validCursor ->
                val nameIndex = validCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = validCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                if (nameIndex != -1 && numberIndex != -1 && validCursor.moveToFirst()) {
                    val phone = validCursor.getString(numberIndex)
                    val matchedName = validCursor.getString(nameIndex)

                    if (!phone.isNullOrBlank()) {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        Log.d(TAG, "Successfully matched and routed call target intent to: $matchedName")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encountered systemic database exception querying contacts content mapping data", e)
        }

        return false
    }
}
