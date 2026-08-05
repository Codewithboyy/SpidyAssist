package com.spydr.spidy

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object AssetUtils {

    private const val TAG = "AssetUtils"

    /**
     * Recursively copies an entire assets subdirectory folder layout into target app file storage.
     */
    fun copyAssetFolder(
        context: Context,
        assetFolder: String,
        destination: File
    ): Boolean {
        try {
            if (!destination.exists() && !destination.mkdirs()) {
                Log.e(TAG, "Failed to create destination directories tree at: ${destination.absolutePath}")
                return false
            }

            val files = context.assets.list(assetFolder) ?: return false

            for (file in files) {
                val assetPath = if (assetFolder.isEmpty()) file else "$assetFolder/$file"

                // A cleaner optimization pattern: Attempt to open the resource path directly as a file.
                // If it fails with an IOException, it is structural directory layout metadata.
                // This eliminates the redundant, highly expensive double-listing asset.list() overhead loop.
                if (isAssetDirectory(context, assetPath)) {
                    val nextDestination = File(destination, file)
                    if (!copyAssetFolder(context, assetPath, nextDestination)) {
                        return false
                    }
                } else {
                    val targetOutFile = File(destination, file)
                    copyAssetFile(context, assetPath, targetOutFile)
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Terminal failure while recursively copying asset directory: $assetFolder", e)
            return false
        }
    }

    private fun isAssetDirectory(context: Context, assetPath: String): Boolean {
        return try {
            // If the stream cleanly opens, it is a readable file data container descriptor element
            context.assets.open(assetPath).close()
            false
        } catch (e: IOException) {
            // An IOException on open implies it's a structural namespace folder directory node descriptor layout
            true
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
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write raw data payload stream chunk onto target storage path file: ${outFile.name}", e)
            throw e
        }
    }
}
