package com.kail.location.xposed

import android.location.Location
import android.location.LocationManager
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicReference

internal object FakeLocState {
    private const val TAG = "FakeLocState"
    
    private val enabledRef = AtomicReference(false)
    private val locationRef = AtomicReference<Location?>(null)
    private val speedRef = AtomicReference(0f)
    private val bearingRef = AtomicReference(0f)
    private val altitudeRef = AtomicReference(0.0)
    private val stepEnabledRef = AtomicReference(false)
    private val stepCadenceSpmRef = AtomicReference(120f)
    private val gaitModeRef = AtomicReference(0)
    private var nativeLibraryLoaded = false

    fun isEnabled(): Boolean = enabledRef.get()

    fun setEnabled(enabled: Boolean) {
        enabledRef.set(enabled)
    }

    fun setSpeed(speed: Float) {
        speedRef.set(speed)
    }

    fun setBearing(bearing: Float) {
        bearingRef.set(bearing)
    }

    fun setAltitude(altitude: Double) {
        altitudeRef.set(altitude)
    }

    fun setStepEnabled(enabled: Boolean) {
        stepEnabledRef.set(enabled)
        // Also update native gait params
        if (nativeLibraryLoaded) {
            try {
                nativeSetGaitParams(
                    stepCadenceSpmRef.get(),
                    gaitModeRef.get(),
                    enabled
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set gait params: ${e.message}")
            }
        }
    }

    fun isStepEnabled(): Boolean = stepEnabledRef.get()

    fun setStepCadenceSpm(stepsPerMinute: Float) {
        stepCadenceSpmRef.set(stepsPerMinute)
        // Also update native gait params
        if (nativeLibraryLoaded) {
            try {
                nativeSetGaitParams(
                    stepsPerMinute,
                    gaitModeRef.get(),
                    stepEnabledRef.get()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set gait params: ${e.message}")
            }
        }
    }

    fun getStepCadenceSpm(): Float = stepCadenceSpmRef.get()

    fun setGaitMode(mode: Int) {
        gaitModeRef.set(mode)
        // Also update native gait params
        if (nativeLibraryLoaded) {
            try {
                nativeSetGaitParams(
                    stepCadenceSpmRef.get(),
                    mode,
                    stepEnabledRef.get()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set gait params: ${e.message}")
            }
        }
    }

    fun getGaitMode(): Int = gaitModeRef.get()

    /**
     * Set gait parameters for native hook
     */
    fun setGaitParams(spm: Float, mode: Int, enable: Boolean) {
        android.util.Log.i("NativeHook", "setGaitParams called: spm=$spm, mode=$mode, enable=$enable")
        stepCadenceSpmRef.set(spm)
        gaitModeRef.set(mode)
        stepEnabledRef.set(enable)
        
        if (nativeLibraryLoaded) {
            try {
                nativeSetGaitParams(spm, mode, enable)
                android.util.Log.i("NativeHook", "nativeSetGaitParams succeeded")
                Log.i(TAG, "Native gait params set: spm=$spm, mode=$mode, enable=$enable")
            } catch (e: Exception) {
                android.util.Log.e("NativeHook", "nativeSetGaitParams failed: ${e.message}")
                Log.e(TAG, "Failed to set native gait params: ${e.message}")
            }
        } else {
            android.util.Log.w("NativeHook", "nativeLibraryLoaded is false, cannot set params")
        }
    }

    /**
     * Load native library into system_server process
     */
    fun loadNativeLibrary(path: String): Pair<Boolean, String> {
        return try {
            val file = File(path)
            if (!file.exists()) {
                android.util.Log.e("NativeHook", "File not found: $path")
                Pair(false, "File not found: $path")
            } else {
                // Try to load the library using system server's loader
                // This is done via reflection or direct System.load
                android.util.Log.i("NativeHook", "Loading library from: $path")
                System.load(path)
                nativeLibraryLoaded = true
                android.util.Log.i("NativeHook", "Library loaded, calling nativeInitHook...")
                Log.i(TAG, "Native library loaded successfully: $path")
                
                // Initialize hook and sensor simulator
                try {
                    nativeInitHook()
                    android.util.Log.i("NativeHook", "nativeInitHook completed")
                } catch (e: Exception) {
                    android.util.Log.e("NativeHook", "nativeInitHook failed: ${e.message}")
                }
                
                // Set current params
                val spm = stepCadenceSpmRef.get()
                val mode = gaitModeRef.get()
                val enabled = stepEnabledRef.get()
                android.util.Log.i("NativeHook", "Calling nativeSetGaitParams: spm=$spm, mode=$mode, enable=$enabled")
                
                nativeSetGaitParams(
                    spm,
                    mode,
                    enabled
                )
                
                android.util.Log.i("NativeHook", "nativeSetGaitParams called successfully")
                Pair(true, "Library loaded: $path")
            }
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("NativeHook", "UnsatisfiedLinkError: ${e.message}")
            Log.e(TAG, "Failed to load native library: ${e.message}")
            Pair(false, "Load failed: ${e.message}")
        } catch (e: Exception) {
            android.util.Log.e("NativeHook", "Exception: ${e.message}")
            Log.e(TAG, "Error loading native library: ${e.message}")
            Pair(false, "Error: ${e.message}")
        }
    }

    /**
     * Reload config from file
     */
    fun reloadNativeConfig(): Boolean {
        return try {
            if (nativeLibraryLoaded) {
                nativeReloadConfig()
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reload config: ${e.message}")
            false
        }
    }

    fun updateLocation(lat: Double, lon: Double) {
        val loc = Location(LocationManager.GPS_PROVIDER)
        loc.latitude = lat
        loc.longitude = lon
        loc.altitude = altitudeRef.get()
        loc.time = System.currentTimeMillis()
        loc.speed = speedRef.get()
        loc.bearing = bearingRef.get()
        locationRef.set(loc)
    }

    fun injectInto(origin: Location?): Location? {
        if (!isEnabled()) return origin
        val current = locationRef.get() ?: return origin
        val out = Location(origin ?: current)
        out.latitude = current.latitude
        out.longitude = current.longitude
        out.altitude = current.altitude
        out.time = System.currentTimeMillis()
        out.speed = speedRef.get()
        out.bearing = bearingRef.get()
        return out
    }

    // Native methods (implemented in C++)
    private external fun nativeSetGaitParams(spm: Float, mode: Int, enable: Boolean)
    private external fun nativeReloadConfig(): Boolean
    private external fun nativeInitHook()
}
