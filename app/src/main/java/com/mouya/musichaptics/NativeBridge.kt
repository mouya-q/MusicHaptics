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
                } catch (t: Throwable) {
                    Log.e("NativeBridge", "Load from module APK failed: ${t.javaClass.name}: ${t.message}", t)
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
            try {
                nativePtr = nativeCreateEngine()
            } catch (e: UnsatisfiedLinkError) {
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

    fun getOnsetFrames(outBuffer: FloatArray, maxFrames: Int): Int {
        if (nativePtr != 0L) {
            try {
                return nativeGetOnsetFrames(nativePtr, outBuffer, maxFrames)
            } catch (e: Exception) {
                Log.e("NativeBridge", "getOnsetFrames failed: ${e.message}")
            }
        }
        return 0
    }

    fun setDirectDriveNodes(nodes: String) {
        if (nativePtr != 0L) {
            try {
                val ok = nativeSetDirectDriveNodes(nodes)
                Log.i("NativeBridge", "setDirectDriveNodes: result=$ok nodes=$nodes")
            } catch (e: Exception) {
                Log.e("NativeBridge", "setDirectDriveNodes failed: ${e.message}")
            }
        }
    }

    fun setDirectDriveFd(enableFd: Int, amplitudeFd: Int, enablePath: String, amplitudePath: String): Boolean {
        if (nativePtr != 0L) {
            try {
                val ok = nativeSetDirectDriveFd(enableFd, amplitudeFd, enablePath, amplitudePath)
                Log.i("NativeBridge", "setDirectDriveFd: result=$ok enableFd=$enableFd ampFd=$amplitudeFd")
                return ok
            } catch (e: Exception) {
                Log.e("NativeBridge", "setDirectDriveFd failed: ${e.message}")
            }
        }
        return false
    }

    fun triggerDirectDriveStrike(durationMs: Int, amplitude: Int): Boolean {
        if (nativePtr != 0L) {
            try {
                return nativeTriggerDirectDriveStrike(durationMs, amplitude)
            } catch (e: Exception) {
                Log.e("NativeBridge", "triggerDirectDriveStrike failed: ${e.message}")
            }
        }
        return false
    }

    fun isDirectDriveAvailable(): Boolean {
        if (nativePtr != 0L) {
            try {
                return nativeIsDirectDriveAvailable()
            } catch (e: Exception) {
                Log.e("NativeBridge", "isDirectDriveAvailable failed: ${e.message}")
            }
        }
        return false
    }

    fun initRootPipe(pipeFd: Int, enablePath: String, amplitudePath: String): Boolean {
        if (nativePtr != 0L) {
            try {
                val ok = nativeInitRootPipe(pipeFd, enablePath, amplitudePath)
                Log.i("NativeBridge", "initRootPipe: result=$ok pipeFd=$pipeFd enablePath=$enablePath ampPath=$amplitudePath")
                return ok
            } catch (e: Exception) {
                Log.e("NativeBridge", "initRootPipe failed: ${e.message}")
            }
        }
        return false
    }

    fun isRootPipeAvailable(): Boolean {
        if (nativePtr != 0L) {
            try {
                return nativeIsRootPipeAvailable()
            } catch (e: Exception) {
                Log.e("NativeBridge", "isRootPipeAvailable failed: ${e.message}")
            }
        }
        return false
    }

    fun initUdpHaptic(port: Int): Boolean {
        // Seccomp blocks socket() in C++. Use initUdpHapticFromFd instead.
        Log.w("NativeBridge", "initUdpHaptic: socket() blocked by seccomp, use initUdpHapticFromFd")
        return false
    }

    fun initUdpHapticFromFd(fd: Int, port: Int): Boolean {
        if (nativePtr != 0L) {
            try {
                val ok = nativeInitUdpHapticFromFd(fd, port)
                Log.i("NativeBridge", "initUdpHapticFromFd: fd=$fd port=$port result=$ok")
                return ok
            } catch (e: Exception) {
                Log.e("NativeBridge", "initUdpHapticFromFd failed: ${e.message}")
            }
        }
        return false
    }

    fun testUdpHaptic(): Boolean {
        if (nativePtr != 0L) {
            try {
                return nativeTestUdpHaptic()
            } catch (e: Exception) {
                Log.e("NativeBridge", "testUdpHaptic failed: ${e.message}")
            }
        }
        return false
    }

    fun isUdpHapticReady(): Boolean {
        if (nativePtr != 0L) {
            try {
                return nativeIsUdpHapticReady()
            } catch (e: Exception) {
                Log.e("NativeBridge", "isUdpHapticReady failed: ${e.message}")
            }
        }
        return false
    }

    fun shutdownUdpHaptic() {
        if (nativePtr != 0L) {
            try {
                nativeShutdownUdpHaptic()
            } catch (e: Exception) {
                Log.e("NativeBridge", "shutdownUdpHaptic failed: ${e.message}")
            }
        }
    }

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
    private external fun nativeGetOnsetFrames(ptr: Long, outBuffer: FloatArray, maxFrames: Int): Int
    private external fun nativeSetDirectDriveNodes(nodes: String): Boolean
    private external fun nativeSetDirectDriveFd(enableFd: Int, amplitudeFd: Int, enablePath: String, amplitudePath: String): Boolean
    private external fun nativeTriggerDirectDriveStrike(durationMs: Int, amplitude: Int): Boolean
    private external fun nativeIsDirectDriveAvailable(): Boolean
    private external fun nativeInitRootPipe(pipeFd: Int, enablePath: String, amplitudePath: String): Boolean
    private external fun nativeIsRootPipeAvailable(): Boolean
    private external fun nativeInitUdpHaptic(port: Int): Boolean
    private external fun nativeInitUdpHapticFromFd(fd: Int, port: Int): Boolean
    private external fun nativeTestUdpHaptic(): Boolean
    private external fun nativeIsUdpHapticReady(): Boolean
    private external fun nativeShutdownUdpHaptic()
    private external fun nativeEnableJavaPipe(): Boolean
    private external fun nativeDisableJavaPipe()

    @Volatile var onFrameCallback: ((FloatArray, Int) -> Unit)? = null

    @Volatile private var _rootPipeCb: ((Int, Int) -> Unit)? = null

    /**
     * Beat-triggered vibration callback.
     * Called when the C++ scheduler detects a significant onset event (kick, snare, etc.).
     * The Kotlin side uses Android Vibrator API with predefined effects for reliable vibration.
     */
    @Volatile var beatTriggerCallback: ((String, Int) -> Unit)? = null

    fun enableRootPipe(cb: (Int, Int) -> Unit) {
        _rootPipeCb = cb
        if (nativePtr != 0L) {
            try {
                val ok = nativeEnableJavaPipe()
                Log.i("NativeBridge", "nativeEnableJavaPipe: $ok")
            } catch (e: Exception) {
                Log.e("NativeBridge", "nativeEnableJavaPipe failed: ${e.message}")
            }
        }
    }

    fun disableRootPipe() {
        try { nativeDisableJavaPipe() } catch (_: Exception) {}
        _rootPipeCb = null
    }

    fun onRootPipeTrigger(amplitude: Int, duration: Int) {
        _rootPipeCb?.invoke(amplitude, duration)
    }

    /** Called from C++ via JNI when a beat/onset is detected */
    fun onBeatTrigger(event: String, intensity: Int) {
        beatTriggerCallback?.invoke(event, intensity)
    }

    fun onNativeFrameReady(samples: FloatArray, count: Int) {
        onFrameCallback?.invoke(samples, count)
    }

    fun startScheduler(): Boolean {
        if (nativePtr != 0L) {
            try {
                return nativeStartScheduler(nativePtr)
            } catch (e: Throwable) {
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