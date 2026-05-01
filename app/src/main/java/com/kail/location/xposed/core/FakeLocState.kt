package com.kail.location.xposed.core

import android.location.Location
import android.location.LocationManager
import com.kail.location.utils.KailLog
import com.kail.location.utils.ShellUtils
import java.io.File
import java.util.concurrent.atomic.AtomicReference

internal object FakeLocState {
    private const val TAG = "FakeLocState"
    
    private val enabledRef = AtomicReference(false)
    private val locationRef = AtomicReference<Location?>(null)
    private val speedRef = AtomicReference(0f)
    private val bearingRef = AtomicReference(0f)
    private val altitudeRef = AtomicReference(0.0)
    private val accuracyRef = AtomicReference(25.0f)
    private val stepEnabledRef = AtomicReference(false)
    private val stepCadenceSpmRef = AtomicReference(120f)
    private val gaitModeRef = AtomicReference(0)
    private val simSchemeRef = AtomicReference(0)
    private var nativeLibraryLoaded = false

    fun isEnabled(): Boolean = enabledRef.get()

    fun getAltitude(): Double = altitudeRef.get()

    fun getSpeed(): Float = speedRef.get()

    fun getBearing(): Float = bearingRef.get()

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

    fun getAccuracy(): Float = accuracyRef.get()

    fun setAccuracy(accuracy: Float) {
        accuracyRef.set(accuracy)
    }

    fun setStepEnabled(enabled: Boolean) {
        stepEnabledRef.set(enabled)
        // Also update native gait params
        if (nativeLibraryLoaded) {
            try {
                nativeSetGaitParams(
                    stepCadenceSpmRef.get(),
                    gaitModeRef.get(),
                    simSchemeRef.get(),
                    enabled
                )
            } catch (e: Exception) {
                KailLog.e(null, TAG, "Failed to set gait params: ${e.message}")
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
                    simSchemeRef.get(),
                    stepEnabledRef.get()
                )
            } catch (e: Exception) {
                KailLog.e(null, TAG, "Failed to set gait params: ${e.message}")
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
                    simSchemeRef.get(),
                    stepEnabledRef.get()
                )
            } catch (e: Exception) {
                KailLog.e(null, TAG, "Failed to set gait params: ${e.message}")
            }
        }
    }

    fun getGaitMode(): Int = gaitModeRef.get()

    fun setSimScheme(scheme: Int) {
        simSchemeRef.set(scheme)
        if (nativeLibraryLoaded) {
            try {
                nativeSetGaitParams(
                    stepCadenceSpmRef.get(),
                    gaitModeRef.get(),
                    scheme,
                    stepEnabledRef.get()
                )
            } catch (e: Exception) {
                KailLog.e(null, TAG, "Failed to set gait params: ${e.message}")
            }
        }
    }

    fun getSimScheme(): Int = simSchemeRef.get()

    /**
     * Set gait parameters for native hook
     */
    fun setGaitParams(spm: Float, mode: Int, scheme: Int, enable: Boolean) {
        KailLog.i(null, "NativeHook", "setGaitParams called: spm=$spm, mode=$mode, scheme=$scheme, enable=$enable")
        stepCadenceSpmRef.set(spm)
        gaitModeRef.set(mode)
        simSchemeRef.set(scheme)
        stepEnabledRef.set(enable)
        
        if (nativeLibraryLoaded) {
            try {
                nativeSetGaitParams(spm, mode, scheme, enable)
                KailLog.i(null, "NativeHook", "nativeSetGaitParams succeeded")
                KailLog.i(null, TAG, "Native gait params set: spm=$spm, mode=$mode, scheme=$scheme, enable=$enable")
            } catch (e: Exception) {
                KailLog.e(null, "NativeHook", "nativeSetGaitParams failed: ${e.message}")
                KailLog.e(null, TAG, "Failed to set native gait params: ${e.message}")
            }
        } else {
            KailLog.w(null, "NativeHook", "nativeLibraryLoaded is false, cannot set params")
        }
    }

    /**
     * Load native library into system_server process
     */
    fun loadNativeLibrary(path: String, writeOffset: String = "", convertOffset: String = ""): Pair<Boolean, String> {
        KailLog.i(null, TAG, ">>> loadNativeLibrary called: path=$path, writeOffset=$writeOffset, convertOffset=$convertOffset")
        
        val finalWriteOffset = writeOffset
        val finalConvertOffset = convertOffset
        
        KailLog.i(null, TAG, ">>> finalWriteOffset=$finalWriteOffset, finalConvertOffset=$finalConvertOffset")
        
        return try {
            val file = File(path)
            if (!file.exists()) {
                KailLog.e(null, TAG, ">>> File not found: $path")
                Pair(false, "File not found: $path")
            } else {
                KailLog.i(null, TAG, ">>> Loading library: $path")
                System.load(path)
                nativeLibraryLoaded = true
                KailLog.i(null, TAG, ">>> Library loaded, nativeLibraryLoaded=$nativeLibraryLoaded")

                KailLog.i(null, TAG, ">>> Processing pendingWriteOffset...")
                pendingWriteOffset?.let {
                    KailLog.i(null, TAG, ">>> Setting pending write offset: $it")
                    setWriteOffset(it)
                    pendingWriteOffset = null
                }

                KailLog.i(null, TAG, ">>> Processing pendingConvertOffset...")
                pendingConvertOffset?.let {
                    KailLog.i(null, TAG, ">>> Setting pending convert offset: $it")
                    setConvertOffset(it)
                    pendingConvertOffset = null
                }

                if (writeOffset.isNotEmpty()) {
                    KailLog.i(null, TAG, ">>> Setting write offset from parameter: $writeOffset")
                    setWriteOffset(writeOffset)
                }

                if (convertOffset.isNotEmpty()) {
                    KailLog.i(null, TAG, ">>> Setting convert offset from parameter: $convertOffset")
                    setConvertOffset(convertOffset)
                }

                KailLog.i(null, TAG, ">>> Calling nativeInitHook()...")
                try {
                    nativeInitHook()
                    KailLog.i(null, TAG, ">>> nativeInitHook succeeded")
                } catch (e: Exception) {
                    KailLog.e(null, TAG, ">>> nativeInitHook failed: ${e.message}")
                }

                val spm = stepCadenceSpmRef.get()
                val mode = gaitModeRef.get()
                val scheme = simSchemeRef.get()
                val enabled = stepEnabledRef.get()
                KailLog.i(null, TAG, ">>> Setting gait params: spm=$spm, mode=$mode, scheme=$scheme, enabled=$enabled")

                nativeSetGaitParams(
                    spm,
                    mode,
                    scheme,
                    enabled
                )

                KailLog.i(null, TAG, ">>> loadNativeLibrary succeeded")
                Pair(true, "Library loaded: $path")
            }
        } catch (e: UnsatisfiedLinkError) {
                KailLog.e(null, TAG, ">>> UnsatisfiedLinkError: ${e.message}")
                KailLog.e(null, TAG, ">>> Stack: ${e.stackTraceToString()}")
                Pair(false, "Load failed: ${e.message}")
        } catch (e: Exception) {
                KailLog.e(null, TAG, ">>> Exception: ${e.message}")
                KailLog.e(null, TAG, ">>> Stack: ${e.stackTraceToString()}")
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
            KailLog.e(null, TAG, "Failed to reload config: ${e.message}")
            false
        }
    }

    fun getLatitude(): Double = locationRef.get()?.latitude ?: 0.0
    fun getLongitude(): Double = locationRef.get()?.longitude ?: 0.0

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

    fun setRouteSimulation(active: Boolean, spm: Float = 120f, mode: Int = 0) {
        if (nativeLibraryLoaded) {
            try {
                nativeSetRouteSimulation(active, spm, mode)
            } catch (e: Exception) {
            }
        }
    }

    private var pendingWriteOffset: String? = null
    private var pendingConvertOffset: String? = null

    fun setWriteOffset(offsetString: String) {
        KailLog.i(null, TAG, ">>> setWriteOffset called: $offsetString")
        try {
            val offset = offsetString.toLongOrNull() ?: run {
                if (offsetString.startsWith("0x", ignoreCase = true)) {
                    offsetString.substring(2).toLongOrNull(16)
                } else {
                    null
                }
            }
            KailLog.i(null, TAG, ">>> parsed offset: $offset")
            if (offset != null) {
                if (nativeLibraryLoaded) {
                    KailLog.i(null, TAG, ">>> calling nativeSetWriteOffset($offset)")
                    nativeSetWriteOffset(offset)
                    KailLog.i(null, TAG, ">>> nativeSetWriteOffset succeeded")
                } else {
                    KailLog.w(null, TAG, ">>> nativeLibraryLoaded=false, saving to pending")
                    pendingWriteOffset = offsetString
                }
            }
        } catch (e: Exception) {
            KailLog.e(null, TAG, ">>> setWriteOffset exception: ${e.message}")
        }
    }

    fun setConvertOffset(offsetString: String) {
        KailLog.i(null, TAG, ">>> setConvertOffset called: $offsetString")
        try {
            val offset = offsetString.toLongOrNull() ?: run {
                if (offsetString.startsWith("0x", ignoreCase = true)) {
                    offsetString.substring(2).toLongOrNull(16)
                } else {
                    null
                }
            }
            KailLog.i(null, TAG, ">>> parsed offset: $offset")
            if (offset != null) {
                if (nativeLibraryLoaded) {
                    KailLog.i(null, TAG, ">>> calling nativeSetConvertOffset($offset)")
                    nativeSetConvertOffset(offset)
                    KailLog.i(null, TAG, ">>> nativeSetConvertOffset succeeded")
                } else {
                    KailLog.w(null, TAG, ">>> nativeLibraryLoaded=false, saving to pending")
                    pendingConvertOffset = offsetString
                }
            }
        } catch (e: Exception) {
            KailLog.e(null, TAG, ">>> setConvertOffset exception: ${e.message}")
        }
    }

    fun setMocking(mocking: Boolean) {
        if (nativeLibraryLoaded) {
            try {
                nativeSetMocking(if (mocking) 1 else 0)
            } catch (e: Exception) {
            }
        }
    }

    fun setAuthorized(authorized: Boolean) {
        if (nativeLibraryLoaded) {
            try {
                nativeSetAuthorized(if (authorized) 1 else 0)
            } catch (e: Exception) {
            }
        }
    }



    // Native methods (implemented in C++)
    private external fun nativeSetWriteOffset(offset: Long)
    private external fun nativeSetConvertOffset(offset: Long)
    private external fun nativeSetMocking(mocking: Int)
    private external fun nativeSetAuthorized(authorized: Int)
    private external fun nativeSetRouteSimulation(active: Boolean, spm: Float, mode: Int)
    private external fun nativeSetGaitParams(spm: Float, mode: Int, scheme: Int, enable: Boolean)
    private external fun nativeReloadConfig(): Boolean
    private external fun nativeInitHook()
}
