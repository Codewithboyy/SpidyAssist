package com.spydr.spidy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent

class SystemControlManager(private val context: Context) {

    companion object {
        private const val TAG = "SystemControlManager"
        const val REQUEST_SCREEN_CAPTURE = 1001
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Evaluates local commands for system toggles, media playback, screen capture, and settings.
     */
    fun handleControlCommand(command: String): String? {
        val lower = command.lowercase().trim()

        return when {
            // Flashlight / Torch Controls
            lower.contains("turn on flashlight") || lower.contains("turn on torch") || lower.contains("flashlight on") || lower.contains("torch on") -> setFlashlight(true)
            lower.contains("turn off flashlight") || lower.contains("turn off torch") || lower.contains("flashlight off") || lower.contains("torch off") -> setFlashlight(false)

            // Volume & Audio Controls
            lower.contains("volume up") || lower.contains("increase volume") || lower.contains("louder") || lower.contains("turn up volume") -> adjustVolume(AudioManager.ADJUST_RAISE)
            lower.contains("volume down") || lower.contains("lower volume") || lower.contains("decrease volume") || lower.contains("quieter") || lower.contains("turn down volume") -> adjustVolume(AudioManager.ADJUST_LOWER)
            lower.contains("mute volume") || lower.contains("mute audio") || lower.contains("mute phone") || lower.contains("mute") -> setMute(true)
            lower.contains("unmute volume") || lower.contains("unmute audio") || lower.contains("unmute phone") || lower.contains("unmute") -> setMute(false)

            // Media Playback Controls
            lower.contains("pause music") || lower.contains("pause media") || lower.contains("pause playback") || lower.contains("pause") -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE, "Media paused.")
            lower.contains("play music") || lower.contains("resume music") || lower.contains("resume media") || lower.contains("play media") -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY, "Media playing.")
            lower.contains("next song") || lower.contains("next track") || lower.contains("skip song") || lower.contains("skip track") || lower.contains("next") -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT, "Skipped to next track.")
            lower.contains("previous song") || lower.contains("previous track") || lower.contains("go back song") || lower.contains("previous") -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Returned to previous track.")

            // Screen Capture Trigger
            lower.contains("take screenshot") || lower.contains("capture screen") || lower.contains("take a screenshot") || lower.contains("screenshot") -> triggerScreenCapture()

            // Settings Shortcuts
            lower.contains("wifi settings") || lower.contains("open wifi settings") || lower.contains("open wi-fi") || lower.contains("wifi") -> openSettings(Settings.ACTION_WIFI_SETTINGS, "Opening Wi-Fi settings.")
            lower.contains("bluetooth settings") || lower.contains("open bluetooth settings") || lower.contains("bluetooth") -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "Opening Bluetooth settings.")
            lower.contains("display settings") || lower.contains("open display settings") || lower.contains("brightness settings") -> openSettings(Settings.ACTION_DISPLAY_SETTINGS, "Opening display settings.")
            lower.contains("location settings") || lower.contains("open location settings") || lower.contains("gps settings") -> openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Opening location settings.")

            else -> null
        }
    }

    private fun setFlashlight(enabled: Boolean): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val flashCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()

            if (flashCameraId != null) {
                cameraManager.setTorchMode(flashCameraId, enabled)
                if (enabled) "Flashlight turned on." else "Flashlight turned off."
            } else {
                "No camera flash found on this device."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight", e)
            "I couldn't control the flashlight on this device."
        }
    }

    private fun adjustVolume(direction: Int): String {
        return try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            if (direction == AudioManager.ADJUST_RAISE) "Volume increased." else "Volume decreased."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adjust volume", e)
            "I couldn't adjust the volume."
        }
    }

    private fun setMute(mute: Boolean): String {
        return try {
            val direction = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            if (mute) "Audio muted." else "Audio unmuted."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set mute status", e)
            "I couldn't change mute status."
        }
    }

    private fun sendMediaKeyEvent(keyCode: Int, successMessage: String): String {
        return try {
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)
            successMessage
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch media key event $keyCode", e)
            "I couldn't control media playback."
        }
    }

    private fun triggerScreenCapture(): String {
        return try {
            if (context is Activity) {
                val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                context.startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
                "Requesting screen capture permission."
            } else {
                "Screen capture can only be launched from an active screen."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate screen capture", e)
            "Screen capture failed to launch."
        }
    }

    private fun openSettings(action: String, message: String): String {
        return try {
            val intent = Intent(action).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            message
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings for action: $action", e)
            "I couldn't open settings."
        }
    }
}
