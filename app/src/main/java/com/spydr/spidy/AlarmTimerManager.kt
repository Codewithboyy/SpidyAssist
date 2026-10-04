package com.spydr.spidy

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log

class AlarmTimerManager(private val context: Context) {

    private companion object {
        private const val TAG = "AlarmTimerManager"
    }

    /**
     * Sets a countdown timer for a given duration in seconds.
     */
    fun setTimer(durationInSeconds: Int, label: String = "Spidy Timer"): String {
        if (durationInSeconds <= 0) return "Invalid timer duration."

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, durationInSeconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            val minutes = durationInSeconds / 60
            val seconds = durationInSeconds % 60
            val timeString = when {
                minutes > 0 && seconds > 0 -> "$minutes minutes and $seconds seconds"
                minutes > 0 -> "$minutes minutes"
                else -> "$seconds seconds"
            }
            "Timer set for $timeString."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start timer intent", e)
            "Unable to set timer on this device."
        }
    }

    /**
     * Sets an alarm for a specific hour (0-23) and minute (0-59).
     */
    fun setAlarm(hour: Int, minute: Int, message: String = "Spidy Alarm"): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            val amPmHour = if (hour % 12 == 0) 12 else hour % 12
            val period = if (hour >= 12) "PM" else "AM"
            val formattedMinute = String.format("%02d", minute)
            "Alarm set for $amPmHour:$formattedMinute $period."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start alarm intent", e)
            "Unable to set alarm on this device."
        }
    }
}
