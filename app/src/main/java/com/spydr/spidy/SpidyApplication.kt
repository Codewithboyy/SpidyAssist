package com.spydr.spidy

import android.app.Application
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class SpidyApplication : Application() {

    companion object {
        private const val TAG = "SpidyApplication"

        @Volatile
        private var _instance: SpidyApplication? = null

        val instance: SpidyApplication
            get() = _instance ?: synchronized(this) {
                _instance ?: throw IllegalStateException("SpidyApplication context not fully initialized yet.")
            }
    }

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        synchronized(SpidyApplication::class.java) {
            _instance = this
        }

        Log.d(TAG, "Spidy Application global context initialized successfully.")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Device low memory warning encountered. Trimming background cached allocations.")
    }
}