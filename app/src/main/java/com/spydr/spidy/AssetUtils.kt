package com.spydr.spidy

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object AssetUtils {

    private const val TAG = "AssetUtils"

    /**
     * Recursively copies an assets folder (e.g., Vosk acoustic model) into target storage.
     * Skips existing files to prevent unnecessary disk reads/writes on every app startup.
     *
     * @param context Application context
     * @param assetFolder Relative path inside app assets (e.g., "model-en-us")
     * @param destination Destination directory in app internal/external storage
     * @return True if the copy completed successfully
     */
    fun copyAssetFolder(
        context: Context,
        assetFolder: String,
        destination: File
    ): Boolean {
        try {
            if (!destination.exists() && !destination.mkdirs()) {
                Log.e(TAG, "Failed to create destination directories at: ${destination.absolutePath}")
                return false
            }

            val files = context.assets.list(assetFolder) ?: return false

            for (file in files) {
                val assetPath = if (assetFolder.isEmpty()) file else "$assetFolder/$file"
                val targetFile = File(destination, file)

                if (isAssetDirectory(context, assetPath)) {
                    if (!copyAssetFolder(context, assetPath, targetFile)) {
                        return false
                    }
                } else {
                    // Only copy if the target file does not exist or size differs
                    if (!targetFile.exists() || targetFile.length() != getAssetFileSize(context, assetPath)) {
                        copyAssetFile(context, assetPath, targetFile)
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failure while copying asset directory: $assetFolder", e)
            return false
        }
    }

    /**
     * Accurately determines if an asset path is a directory by checking child entries.
     */
    private fun isAssetDirectory(context: Context, assetPath: String): Boolean {
        return try {
            val children = context.assets.list(assetPath)
            !children.isNullOrEmpty()
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Gets asset file length to check if re-copying is required.
     */
    private fun getAssetFileSize(context: Context, assetPath: String): Long {
        return try {
            context.assets.openFd(assetPath).use { fd ->
                fd.length
            }
        } catch (e: IOException) {
            // Fallback for compressed assets where openFd() isn't supported
            try {
                context.assets.open(assetPath).use { stream ->
                    stream.available().toLong()
                }
            } catch (e2: Exception) {
                -1L
            }
        }
    }

    private fun copyAssetFile(
        context: Context,
        assetName: String,
        outFile: File
    ) {
        try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            Log.d(TAG, "Successfully copied asset: $assetName -> ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset file: $assetName", e)
            throw e
        }
    }
}
