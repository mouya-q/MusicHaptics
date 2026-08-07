package com.mouya.musichaptics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import java.nio.ByteBuffer

class NativeBridge {
    private var nativePtr: Long = 0

    companion object {
        @Volatile private var libraryPreloaded = false
        private const val LIB_NAME = "native-bridge"

        private val loadLock = Any()

        fun preloadLibrary(context: Context) {
            synchronized(loadLock) {
                if (libraryPreloaded) return
                try {

                    val pm = context.packageManager
                    val moduleInfo = pm.getApplicationInfo("com.mouya.musichaptics", 0)
                    val nativeLibDir = moduleInfo.nativeLibraryDir
                    val libFile = java.io.File(nativeLibDir, System.mapLibraryName(LIB_NAME))
                    if (libFile.exists()) {
                        System.load(libFile.absolutePath)
                        libraryPreloaded = true
                        Log.i("NativeBridge", "Library loaded from module APK: ${libFile.absolutePath}")
                        return
                    }
                } catch (e: Exception) {
                    Log.w("NativeBridge", "Load from module APK failed: ${e.message}")
                }

                try {
                    System.loadLibrary(LIB_NAME)
                    libraryPreloaded = true
                    Log.i("NativeBridge", "Library loaded via System.loadLibrary")
                } catch (e: UnsatisfiedLinkError) {
                    Log.e("NativeBridge", "System.loadLibrary failed: ${e.message}")
                }
            }
        }

        fun isLibraryPreloaded(): Boolean = libraryPreloaded
    }

    init {
        try {
            // First, see if the engine can be created (meaning the library is already loaded
            // by MainHook using moduleClassLoader.getResource() or other means).
            try {
                nativePtr = nativeCreateEngine()
            } catch (e: UnsatisfiedLinkError) {
                // If not, fall back to explicit loading
                if (!libraryPreloaded) {
                    synchronized(loadLock) {
                        if (!libraryPreloaded) {
                            System.loadLibrary(LIB_NAME)
                            libraryPreloaded = true
                        }
                    }
                }
                nativePtr = nativeCreateEngine()
            }

            if (nativePtr == 0L) {
                Log.w("NativeBridge", "Native engine init returned 0 — running in degraded mode")
            } else {
                Log.i("NativeBridge", "Native engine created successfully, ptr=$nativePtr")
            }
        } catch (e: UnsatisfiedLinkError) {
            // Graceful degradation: native lib not yet extracted/available.
            // App will still launch; DSP pipeline simply produces silence.
            Log.w("NativeBridge", "Native library not available yet (will retry on next launch): ${e.message}")
            nativePtr = 0L
        } catch (e: Exception) {
            Log.w("NativeBridge", "Native engine init failed (degraded mode): ${e.message}")
            nativePtr = 0L
        }
    }

    val isLoaded: Boolean
        get() = nativePtr != 0L

    fun configure(sampleRate: Float, lowCut: Float, highCut: Float, amplitude: Float, presetId: Int) {
        if (nativePtr != 0L) {
            nativeConfigure(nativePtr, sampleRate, lowCut, highCut, amplitude, presetId)
        }
    }

    fun processAudioDirect(buffer: ByteBuffer, size: Int, outTelemetry: FloatArray) {
        if (nativePtr != 0L) {
            try {

                if (!buffer.isDirect || size <= 0 || size > buffer.capacity()) {
                    Log.e("NativeBridge", "Invalid buffer params: buffer=${buffer.isDirect} size=$size capacity=${buffer.capacity()}")
                    return
                }
                nativeProcessAudioDirect(nativePtr, buffer, size, outTelemetry)
            } catch (e: Exception) {
                Log.e("NativeBridge", "processAudioDirect crashed: ${e.message}", e)

            }
        }
    }

    /**
     * Pull continuous haptic amplitude samples from the C++ ring buffer.
     * Each sample is a float in range [0, 255] representing the composed
     * five-layer haptic amplitude at that point in time.
     *
     * @param outBuffer pre-allocated FloatArray to receive amplitude samples
     * @param maxCount maximum number of samples to pull
     * @return number of samples actually copied into outBuffer
     */
    fun getHapticFrame(outBuffer: FloatArray, maxCount: Int): Int {
        if (nativePtr != 0L) {
            try {
                return nativeGetHapticFrame(nativePtr, outBuffer, maxCount)
            } catch (e: Exception) {
                Log.e("NativeBridge", "getHapticFrame failed: ${e.message}")
            }
        }
        return 0
    }

    fun getSemanticFrames(outFrames: FloatArray, maxFrames: Int): Int {
        if (nativePtr != 0L) {
            try {
                return nativeGetSemanticFrames(nativePtr, outFrames, maxFrames)
            } catch (e: Exception) {
                Log.e("NativeBridge", "getSemanticFrames failed: ${e.message}")
            }
        }
        return 0
    }

    /**
     * Clear the C++ haptic ring buffer and reset all envelope states.
     * Call on playback pause/stop to prevent stale vibrations.
     */
    fun clearHapticBuffer() {
        if (nativePtr != 0L) {
            try {
                nativeClearHapticBuffer(nativePtr)
            } catch (e: Exception) {
                Log.e("NativeBridge", "clearHapticBuffer failed: ${e.message}")
            }
        }
    }

    fun release() {
        if (nativePtr != 0L) {
            try {
                nativeDestroyEngine(nativePtr)
            } catch (e: Exception) {
                Log.e("NativeBridge", "nativeDestroyEngine failed: ${e.message}")
            }
            nativePtr = 0L
        }
    }

    private external fun nativeCreateEngine(): Long
    private external fun nativeDestroyEngine(ptr: Long)
    private external fun nativeConfigure(ptr: Long, sampleRate: Float, lowCut: Float, highCut: Float, amplitude: Float, presetId: Int)
    private external fun nativeProcessAudioDirect(ptr: Long, directBuffer: ByteBuffer, size: Int, outTelemetry: FloatArray)
    private external fun nativeGetSemanticFrames(ptr: Long, outFrames: FloatArray, maxFrames: Int): Int
    private external fun nativeGetHapticFrame(ptr: Long, outBuffer: FloatArray, maxCount: Int): Int
    private external fun nativeClearHapticBuffer(ptr: Long)
    private external fun nativeStartScheduler(ptr: Long): Boolean
    private external fun nativeStopScheduler()

    /**
     * v2.1: Native callback — called from the C++ scheduler thread.
     * Receives a batch of amplitude samples (20ms = 2 samples).
     * Override or set [onFrameCallback] to handle these in HapticEngine.
     */
    @Volatile var onFrameCallback: ((FloatArray, Int) -> Unit)? = null

    fun onNativeFrameReady(samples: FloatArray, count: Int) {
        onFrameCallback?.invoke(samples, count)
    }

    fun startScheduler(): Boolean {
        if (nativePtr != 0L) {
            try {
                return nativeStartScheduler(nativePtr)
            } catch (e: Throwable) {
                // UnsatisfiedLinkError extends Error, not Exception — must catch Throwable
                Log.e("NativeBridge", "startScheduler failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return false
    }

    fun stopScheduler() {
        try {
            nativeStopScheduler()
        } catch (e: Throwable) {
            Log.e("NativeBridge", "stopScheduler failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}