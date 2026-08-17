package com.mouya.musichaptics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.nio.ByteBuffer
import java.nio.ByteOrder

import com.mouya.musichaptics.LinkHealthMonitor

class MainHook : XposedModule() {

    companion object {
        private const val TAG = "MusicHapticsX-Hook"
        private const val MAX_SANE_SAMPLE_RATE = 384000
        private const val MIN_SANE_SAMPLE_RATE = 8000
        private const val MAX_SANE_CHANNELS = 12
        private const val ACTION_LOG = "com.mouya.musichaptics.ACTION_LOG"
        private const val ACTION_REFRESH_CONFIG = "com.mouya.musichaptics.ACTION_REFRESH_CONFIG"
        private const val CONFIG_SYNC_PERMISSION = "com.mouya.musichaptics.permission.CONFIG_SYNC"
        private val CONFIG_PROVIDER_URI: Uri = Uri.parse("content://com.mouya.musichaptics.provider")

        private const val VISUALIZER_FALLBACK_DELAY_MS = 3000L
        private const val VISUALIZER_PRIORITY_WINDOW_MS = 500L

        private val SYSTEM_PACKAGE_BLOCKLIST = setOf(
            "android",
            "com.android.systemui",
            "com.android.phone"
        )

        // ─── Multi-track management ───
        private data class TrackInfo(
            val sampleRate: Int,
            val channelCount: Int,
            val isOffloaded: Boolean,
            val createdAt: Long,
            @Volatile var lastMeaningfulPcmAtMs: Long = 0L,
            @Volatile var isPlaying: Boolean = false
        )
        private val activeTracks = java.util.concurrent.ConcurrentHashMap<Int, TrackInfo>()
        private const val ACTIVE_PCM_GRACE_MS = 1500L

        private fun isMeaningfulPcm(pcm: ShortArray): Boolean {
            if (pcm.isEmpty()) return false
            var sumSquares = 0.0
            var peak = 0
            for (sample in pcm) {
                val value = sample.toInt()
                val magnitude = kotlin.math.abs(value)
                peak = maxOf(peak, magnitude)
                sumSquares += value.toDouble() * value.toDouble()
            }
            val rms = kotlin.math.sqrt(sumSquares / pcm.size)
            return peak >= 96 || rms >= 48.0
        }

        private fun shouldDecayAfterTrackControl(trackId: Int, terminal: Boolean): Boolean {
            val now = System.currentTimeMillis()
            activeTracks[trackId]?.let { track ->
                if (terminal) {
                    track.isPlaying = false
                    track.lastMeaningfulPcmAtMs = 0L
                }
            }
            return activeTracks.any { (id, track) ->
                id != trackId && track.isPlaying &&
                    now - track.lastMeaningfulPcmAtMs <= ACTIVE_PCM_GRACE_MS
            }.not()
        }

        private val hookThreadLocal = ThreadLocal<Boolean>()

        // ─── Log throttling ───
        @Volatile private var lastWriteLogMs = 0L
        private const val WRITE_LOG_INTERVAL_MS = 1000L
    }

    private var hapticEngine: HapticEngine? = null
    @Volatile private var hookedTargetPackage: String? = null
    private var platformThread: HandlerThread? = null
    private var platformHandler: Handler? = null
    private val initLock = Any()
    @Volatile private var configReceiverRegistered = false

    @Volatile private var nativeLibLoaded = false

    @Volatile private var lastWriteTimestamp: Long = 0L
    @Volatile private var visualizerActive: Boolean = false
    private var fallbackVisualizer: android.media.audiofx.Visualizer? = null
    @Volatile private var capturedAudioSessionId: Int = 0  // v3.13.1: For session-specific Visualizer
    @Volatile private var visualizerActivationAttempts: Int = 0
    @Volatile private var visualizerPermissionFailures: Int = 0  // v3.13.2: Track permission denials separately

    private fun sendUiLog(context: Context, msg: String) {
        try {
            val intent = Intent(ACTION_LOG).apply {
                setPackage("com.mouya.musichaptics")
                putExtra("log_msg", "[Hook] $msg")
            }
            context.sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    private fun writeHookLog(context: Context, msg: String) {
        try {
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val line = "[$stamp] $msg\n"
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "hook.log")
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/MusicHapticsX")
            }
            var uri: android.net.Uri? = null
            try {
                resolver.query(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(android.provider.MediaStore.MediaColumns._ID),
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    arrayOf("hook.log"),
                    null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                    }
                }
            } catch (_: Exception) {}
            if (uri == null) {
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    try { resolver.openOutputStream(uri)?.use {} } catch (_: Exception) {}
                    values.clear()
                    values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    try { resolver.update(uri, values, null, null) } catch (_: Exception) {}
                }
            }
            if (uri != null) {
                try { resolver.openOutputStream(uri, "wa")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(line) } } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun hookLog(msg: String) {
        Log.i(TAG, msg)
        getContextFromActivityThread()?.let { writeHookLog(it, msg) }
    }

    // ─── Pure-libxposed reflection helpers (no legacy de.robv dependency) ───
    private fun findClassSafe(name: String, loader: ClassLoader?): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (e: Throwable) { null }

    private fun callMethodSafe(obj: Any, name: String, vararg args: Any?): Any? = try {
        val argTypes = args.map { it?.javaClass ?: java.lang.Integer.TYPE }.toTypedArray()
        obj.javaClass.getMethod(name, *argTypes).invoke(obj, *args)
    } catch (e: Throwable) { null }

    private fun callStaticSafe(clazz: Class<*>, name: String, vararg args: Any?): Any? = try {
        val argTypes = args.map { it?.javaClass ?: java.lang.Integer.TYPE }.toTypedArray()
        clazz.getMethod(name, *argTypes).invoke(null, *args)
    } catch (e: Throwable) { null }

    private fun hookMethods(clazz: Class<*>, name: String, hooker: Hooker) {
        for (m in clazz.declaredMethods) {
            if (m.name == name) {
                try { hook(m).intercept(hooker) } catch (_: Throwable) {}
            }
        }
    }

    private fun getContextFromActivityThread(): Context? {
        try {
            val activityThreadClass = findClassSafe("android.app.ActivityThread", null) ?: return null
            val currentThread = callStaticSafe(activityThreadClass!!, "currentActivityThread") ?: return null
            return callMethodSafe(currentThread!!, "getSystemContext") as? Context
        } catch (e: Exception) {
            return null
        }
    }

    private fun ensureNativeLibraryLoaded(param: PackageLoadedParam) {

        if (nativeLibLoaded) return
        synchronized(this) {
            if (nativeLibLoaded) return

            try {

                val moduleClassLoader = param.defaultClassLoader

                val libName = System.mapLibraryName("native-bridge")

                val resourceUrl = moduleClassLoader.getResource(libName)
                if (resourceUrl != null) {
                    val libPath = resourceUrl.path
                    if (libPath != null && java.io.File(libPath).exists()) {
                        System.load(libPath)
                        nativeLibLoaded = true
                        Log.i(TAG, "Native library loaded from: $libPath")
                        getContextFromActivityThread()?.let { sendUiLog(it, "Native library loaded from: $libPath") }
                        return
                    }
                }

                val pm = try {
                    val activityThreadClass = findClassSafe("android.app.ActivityThread", null)
                    val currentThread = callStaticSafe(activityThreadClass!!, "currentActivityThread")
                    val systemContext = callMethodSafe(currentThread!!, "getSystemContext") as? Context
                    systemContext?.packageManager
                } catch (e: Exception) { null }

                if (pm != null) {
                    try {
                        val moduleInfo = pm.getApplicationInfo("com.mouya.musichaptics", 0)
                        val nativeLibDir = moduleInfo.nativeLibraryDir
                        val libFile = java.io.File(nativeLibDir, libName)
                        if (libFile.exists()) {
                            System.load(libFile.absolutePath)
                            nativeLibLoaded = true
                            Log.i(TAG, "Native library loaded from module dir: ${libFile.absolutePath}")
                            getContextFromActivityThread()?.let { sendUiLog(it, "Native library loaded from module dir: ${libFile.absolutePath}") }
                            return
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load from module nativeLibraryDir: ${e.message}")
                        getContextFromActivityThread()?.let { sendUiLog(it, "Failed to load from module nativeLibraryDir: ${e.message}") }
                    }
                }

                System.loadLibrary("native-bridge")
                nativeLibLoaded = true
                Log.i(TAG, "Native library loaded via default loadLibrary")
                getContextFromActivityThread()?.let { sendUiLog(it, "Native library loaded via default loadLibrary") }

            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library in hooked process: ${e.message}")
                getContextFromActivityThread()?.let { sendUiLog(it, "Failed to load native library: ${e.message}") }
                nativeLibLoaded = false
            }
        }
    }


    private fun activateVisualizerFallback(sampleRate: Int, channels: Int) {
        if (visualizerActive) return
        val nonPermissionAttempts = visualizerActivationAttempts - visualizerPermissionFailures
        if (nonPermissionAttempts >= 5) return
        visualizerActivationAttempts++

        try {
            var viz: android.media.audiofx.Visualizer? = null

            try {
                viz = android.media.audiofx.Visualizer(0)
                Log.i(TAG, "[Visualizer] Created Visualizer(0) — global output mix")
            } catch (e: Exception) {
                Log.w(TAG, "[Visualizer] Strategy 1 failed (session 0): ${e.message}")
            }

            if (viz == null) {
                try {
                    viz = android.media.audiofx.Visualizer(android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
                    Log.i(TAG, "[Visualizer] Created Visualizer(GENERATE) — generated session")
                } catch (e: Exception) {
                    Log.w(TAG, "[Visualizer] Strategy 2 failed (GENERATE): ${e.message}")
                }
            }

            if (viz == null && capturedAudioSessionId != 0) {
                try {
                    viz = android.media.audiofx.Visualizer(capturedAudioSessionId)
                    Log.i(TAG, "[Visualizer] Created Visualizer(session=$capturedAudioSessionId) — targeted AudioTrack session")
                } catch (e: Exception) {
                    Log.w(TAG, "[Visualizer] Strategy 3 failed (captured session $capturedAudioSessionId): ${e.message}")
                }
            }

            if (viz == null) {
                try {
                    viz = android.media.audiofx.Visualizer(android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
                    Log.i(TAG, "[Visualizer] Created Visualizer(GENERATE fallback) — generated session")
                } catch (e: Exception) {
                    Log.w(TAG, "[Visualizer] Strategy 4 failed (GENERATE fallback): ${e.message}")
                }
            }

            if (viz == null) {
                Log.e(TAG, "[Visualizer] All creation strategies failed (attempt #$visualizerActivationAttempts)")
                getContextFromActivityThread()?.let { sendUiLog(it, "⚠ Visualizer creation failed — native audio apps won't have haptics") }
                return
            }

            val captureSize = try {
                val desired = 1024
                val ranges = android.media.audiofx.Visualizer.getCaptureSizeRange()
                if (ranges != null && ranges.size >= 2) {
                    desired.coerceIn(ranges[0], ranges[1])
                } else {
                    desired
                }
            } catch (_: Exception) { 1024 }

            try {
                viz.captureSize = captureSize
            } catch (e: Exception) {
                Log.w(TAG, "[Visualizer] Failed to set capture size: ${e.message}")
            }

            val captureRate = try {
                android.media.audiofx.Visualizer.getMaxCaptureRate()
            } catch (_: Exception) { 20000 }

            viz.setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: android.media.audiofx.Visualizer?, waveform: ByteArray?, samplingRateHz: Int) {
                    if (waveform == null || waveform.isEmpty()) return

                    val now = System.currentTimeMillis()
                    if (lastWriteTimestamp > 0 && now - lastWriteTimestamp < VISUALIZER_PRIORITY_WINDOW_MS) {
                        return
                    }

                    val pcm16 = ShortArray(waveform.size)
                    for (i in waveform.indices) {
                        val unsigned = waveform[i].toInt() and 0xFF
                        val centered = unsigned - 128
                        pcm16[i] = (centered * 512).toShort()
                    }

                    var hasSignal = false
                    for (s in pcm16) {
                        if (kotlin.math.abs(s.toInt()) > 256) { hasSignal = true; break }
                    }
                    if (!hasSignal) return

                    platformHandler?.post {
                        ensureEngineInitialized()
                        hapticEngine?.isVisualizerSource = true  // v3.13.1: Flag Visualizer data source
                        hapticEngine?.reconfigure(samplingRateHz, 1)
                        hapticEngine?.processAudioFrame(pcm16)
                    }
                }

                override fun onFftDataCapture(visualizer: android.media.audiofx.Visualizer?, fft: ByteArray?, samplingRateHz: Int) {
                }
            }, captureRate, true, false)

            viz.enabled = true
            fallbackVisualizer = viz
            visualizerActive = true
            visualizerActivationAttempts = 0  // Reset on success

            Log.i(TAG, "[Visualizer] ✅ Fallback activated — capturing audio output (${captureSize} bytes, rate=${captureRate}mHz, pkg=$hookedTargetPackage)")
            getContextFromActivityThread()?.let {
                sendUiLog(it, "✅ Visualizer fallback ACTIVE — capturing audio for native audio path")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "[Visualizer] SecurityException — RECORD_AUDIO/MODIFY_AUDIO_SETTINGS not granted: ${e.message}")
            getContextFromActivityThread()?.let { sendUiLog(it, "⚠ Visualizer needs audio permission — retrying later") }
            visualizerPermissionFailures++
            platformHandler?.postDelayed({ visualizerActivationAttempts = 0 }, 10000L)
        } catch (e: Exception) {
            Log.e(TAG, "[Visualizer] Activation failed: ${e.message}")
            getContextFromActivityThread()?.let { sendUiLog(it, "⚠ Visualizer activation failed: ${e.message}") }
        }
    }

    private fun deactivateVisualizerFallback() {
        if (!visualizerActive) return
        try {
            fallbackVisualizer?.let {
                it.enabled = false
                it.release()
            }
        } catch (_: Exception) {}
        fallbackVisualizer = null
        visualizerActive = false
        Log.i(TAG, "[Visualizer] Fallback deactivated")
        getContextFromActivityThread()?.let { sendUiLog(it, "Visualizer fallback deactivated") }
    }

    private fun ensureEngineInitialized() {
        if (hapticEngine != null) return

        try {
            val activityThreadClass = findClassSafe(
                "android.app.ActivityThread", null
            )
            val currentThread = callStaticSafe(
                activityThreadClass!!, "currentActivityThread"
            )

            if (currentThread == null) {
                Log.w(TAG, "ActivityThread.currentThread returned null.")
                sendUiLog(getContextFromActivityThread()!!, "ActivityThread.currentThread returned null.")
                return
            }

            var context = callStaticSafe(
                activityThreadClass!!, "currentApplication"
            ) as? Context

            if (context == null) {
                context = try {
                    callMethodSafe(currentThread!!, "getSystemContext") as? Context
                } catch (e: Exception) { null }
            }

            if (context == null) {
                context = try {
                    val amClass = findClassSafe(
                        "android.app.ActivityManager", null
                    )
                    val am = callStaticSafe(amClass!!, "getService")
                    callMethodSafe(am!!, "getContext") as? Context
                } catch (e: Exception) { null }
            }

            if (context == null) {
                Log.e(TAG, "[FATAL] All three context resolution strategies failed.")
                getContextFromActivityThread()?.let { sendUiLog(it, "[FATAL] All three context resolution strategies failed.") }
                return
            }

            getContextFromActivityThread()?.let { sendUiLog(it, "Context resolved: ${context.packageName}") }

            val ourPrefs = try {
                val localPrefs = context.getSharedPreferences("haptic_settings", Context.MODE_PRIVATE)
                val snapshot = context.contentResolver.call(
                    Uri.parse("content://com.mouya.musichaptics.provider"),
                    "get_prefs", null,
                    android.os.Bundle().apply { putString("target_package", hookedTargetPackage ?: context.packageName) }
                )
                if (snapshot != null) {
                    val editor = localPrefs.edit()
                    for (key in snapshot.keySet()) {
                        when (val value = snapshot.get(key)) {
                            is Boolean -> editor.putBoolean(key, value)
                            is Float -> editor.putFloat(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is String -> editor.putString(key, value)
                        }
                    }
                    editor.commit()
                    Log.i(TAG, "Loaded ${snapshot.keySet().size} preference(s) from module provider")
                } else {
                    Log.w(TAG, "Module provider returned no prefs; using accessible local snapshot")
                }
                localPrefs
            } catch (e: Exception) {
                Log.w(TAG, "Cross-process preference snapshot failed: ${e.message}")
                getContextFromActivityThread()?.let { sendUiLog(it, "Preference snapshot failed: ${e.message}") }
                try {
                    context.getSharedPreferences("haptic_settings", Context.MODE_PRIVATE)
                } catch (e2: Exception) {
                    Log.e(TAG, "Fallback prefs also failed: ${e2.message}")
                    getContextFromActivityThread()?.let { sendUiLog(it, "Fallback prefs also failed: ${e2.message}") }
                    null
                }
            }

            if (ourPrefs != null) {
                getContextFromActivityThread()?.let { sendUiLog(it, "Creating HapticEngine with current settings...") }
                val targetPackage = hookedTargetPackage ?: context.packageName
                hapticEngine = HapticEngine(context, ourPrefs, targetPackage)
                registerConfigRefreshReceiver(context)
                val hasVibrator = hapticEngine?.hapticEventGenerator?.hasVibrator ?: false
                val profileName = hapticEngine?.hapticEventGenerator?.profile?.name ?: "unknown"
                Log.i(TAG, "Haptic engine deployed successfully via Android Haptic API. hasVibrator=$hasVibrator profile=$profileName")
                sendUiLog(
                    context,
                    "Engine ready → vibrator: $hasVibrator profile: $profileName"
                )

                val master = ourPrefs.getBoolean("master_switch", true)
                val gain = ourPrefs.getFloat("haptic_gain", 1.0f)
                val amp = ourPrefs.getFloat("haptic_amplitude", 1.0f)
                val boost = ourPrefs.getFloat("haptic_boost_level", 1.0f)
                val purity = ourPrefs.getInt("haptic_bass_purity", 50)
                Log.i(TAG, "Effective prefs: master=$master gain=$gain amp=$amp boost=$boost purity=$purity")
                getContextFromActivityThread()?.let { sendUiLog(it, "Effective prefs: master=$master gain=$gain amp=$amp boost=$boost purity=$purity") }

                LinkHealthMonitor.heartbeatHookReady()
            } else {
                getContextFromActivityThread()?.let { sendUiLog(it, "Failed to get SharedPreferences") }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Context resolution cascade failed: ${t.message}")
            getContextFromActivityThread()?.let { sendUiLog(it, "Context resolution cascade failed: ${t.message}") }
        }
    }

    private fun registerConfigRefreshReceiver(context: Context) {
        if (configReceiverRegistered) return
        try {
            val filter = IntentFilter(ACTION_REFRESH_CONFIG)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Log.i(TAG, "Config refresh broadcast received — reloading preferences")
                    val engine = hapticEngine
                    if (engine != null) {
                        engine.refreshFromProvider()
                    } else {
                        ensureEngineInitialized()
                    }
                }
            }, filter, CONFIG_SYNC_PERMISSION, null)
            configReceiverRegistered = true
            Log.i(TAG, "Config refresh receiver registered")
        } catch (e: Exception) {
            Log.w(TAG, "Config refresh receiver registration failed: ${e.message}")
        }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        hookLog("onModuleLoaded: process=" + param.processName + " systemServer=" + param.isSystemServer)
        if (param.isSystemServer) {
            Log.i(TAG, "System server process — haptics hook skipped")
            return
        }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {

                val pkg = param.packageName
        hookLog("onPackageLoaded: pkg=" + pkg + " classLoader=" + (param.defaultClassLoader != null))
        if (pkg in SYSTEM_PACKAGE_BLOCKLIST) {
            return
        }

        if (pkg == "com.mouya.musichaptics") {
            Log.d(TAG, "Skipping self-package [$pkg] — no self-hook allowed.")
            return
        }

        if (param.defaultClassLoader == null) {
            Log.w(TAG, "No ClassLoader found for process [$pkg]; skipping.")
            return
        }

        if (pkg.isBlank() || pkg.contains(" ")) {
            Log.e(TAG, "Invalid package name with special characters: [$pkg]")
            return
        }

        hookedTargetPackage = pkg

        synchronized(initLock) {
            if (platformThread == null || platformThread?.isAlive == false) {
                Log.i(TAG, "Initializing dedicated platform worker thread...")
                platformThread = HandlerThread(
                    "MusicHaptics-Platform-Worker",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND
                ).apply {
                    start()
                    platformHandler = Handler(looper)
                }
            }
        }

        try {

            val audioTrackClass = findClassSafe("android.media.AudioTrack", param.defaultClassLoader)
            if (audioTrackClass == null) {
                hookLog("ERROR: AudioTrack class not found in $pkg")
                Log.w(TAG, "AudioTrack class not found in process [$pkg]; skipping.")
                return
            }

            ensureNativeLibraryLoaded(param!!)

            try {
                val activityThreadClass = findClassSafe("android.app.ActivityThread", null)
                val currentThread = callStaticSafe(activityThreadClass!!, "currentActivityThread")
                val systemContext = callMethodSafe(currentThread!!, "getSystemContext") as? Context
                if (systemContext != null) NativeBridge.preloadLibrary(systemContext)
            } catch (e: Exception) {
                Log.w(TAG, "NativeBridge.preloadLibrary failed: ${e.message}")
            }

            Log.i(TAG, "[HOOK ACTIVE] Package=$pkg AudioTrack found=${audioTrackClass.simpleName ?: "unknown"}")
            hookLog("HOOK ACTIVE: pkg=$pkg audioTrack=${audioTrackClass.simpleName} ctors=${audioTrackClass.declaredConstructors.size}")
            getContextFromActivityThread()?.let { sendUiLog(it, "[HOOK ACTIVE] Package=$pkg AudioTrack found=${audioTrackClass.simpleName ?: "unknown"}") }

            val audioMethods = try {
                val methods = audioTrackClass.declaredMethods ?: audioTrackClass.methods
                methods.map { it.name }.distinct()
            } catch (_: Exception) { emptyList<String>() }
            Log.d(TAG, "AudioTrack methods visible: $audioMethods")
            hookLog("AudioTrack methods: ${audioMethods.size}")

            for (ctor in audioTrackClass.declaredConstructors) {
                try {
                    hook(ctor).intercept(object : Hooker {
                        @Throws(Throwable::class)
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                            val track = chain.thisObject ?: return result
                            hookLog("AudioTrack ctor hooked: $track")
                            val trackIdentity = System.identityHashCode(track)

                            val sr = try {
                                callMethodSafe(track, "getSampleRate") as Int
                            } catch (e: Exception) { 44100 }

                            val ch = try {
                                callMethodSafe(track, "getChannelCount") as Int
                            } catch (e: Exception) { 2 }

                            if (sr !in MIN_SANE_SAMPLE_RATE..MAX_SANE_SAMPLE_RATE) {
                                Log.w(TAG, "Unreasonable sample rate: ${sr}Hz — rejected.")
                                return result
                            }

                            if (ch <= 0 || ch > MAX_SANE_CHANNELS) {
                                Log.w(TAG, "Unreasonable channel count: $ch — rejected.")
                                return result
                            }

                            val isOffloaded = try {
                                val getOffloaded = audioTrackClass.getMethod("isOffloadedPlayback")
                                getOffloaded.invoke(track) as Boolean
                            } catch (_: Exception) { false }

                            val isDirect = try {
                                val getDirect = audioTrackClass.getMethod("isDirect")
                                getDirect.invoke(track) as Boolean
                            } catch (_: Exception) { false }

                            val mode = try {
                                callMethodSafe(track, "getStreamType") as Int
                            } catch (_: Exception) { -1 }

                    activeTracks[trackIdentity] = TrackInfo(sr, ch, isOffloaded, System.currentTimeMillis())

                    Log.i(TAG, "AudioTrack created in [$pkg] — sr=${sr}Hz ch=$ch offloaded=$isOffloaded direct=$isDirect streamType=$mode tracks=${activeTracks.size}")
                    getContextFromActivityThread()?.let { sendUiLog(it, "AudioTrack[$trackIdentity] sr=${sr}Hz ch=$ch off=$isOffloaded direct=$isDirect tracks=${activeTracks.size}") }

                    try {
                        val sessionId = callMethodSafe(track, "getAudioSessionId") as Int
                        if (sessionId > 0) {
                            capturedAudioSessionId = sessionId
                            Log.i(TAG, "[Visualizer] 🎯 Captured AudioTrack sessionId=$sessionId for [$pkg]")
                            getContextFromActivityThread()?.let { sendUiLog(it, "🎯 Visualizer target sessionId=$sessionId captured") }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[Visualizer] Failed to capture sessionId: ${e.message}")
                    }

                    if (isOffloaded) {
                        Log.w(TAG, "⚠ OFFLOADED AudioTrack detected — PCM write() may NOT be called. Haptics limited for this track.")
                        getContextFromActivityThread()?.let { sendUiLog(it, "⚠ Offloaded track (hardware-decoded) — activating Visualizer fallback") }
                        platformHandler?.postDelayed({
                            if (lastWriteTimestamp == 0L || System.currentTimeMillis() - lastWriteTimestamp > VISUALIZER_FALLBACK_DELAY_MS) {
                                activateVisualizerFallback(sr, ch)
                            }
                        }, VISUALIZER_FALLBACK_DELAY_MS)
                    }

                    try {
                        hookMethods(audioTrackClass, "play", object : Hooker {
                            @Throws(Throwable::class)
                            override fun intercept(chain: Chain): Any? {
                                val result = chain.proceed()
                                if (chain.thisObject != null) {
                                    Log.d(TAG, "AudioTrack.play() called in [$pkg] — streaming audio path active")
                                }
                                return result
                            }
                        })
                    } catch (_: Throwable) { }

                    platformHandler?.post {
                        ensureEngineInitialized()
                        hapticEngine?.reconfigure(sr, ch)
                    }
                            return result
                        }
                    })
                } catch (e: Throwable) {
                    hookLog("ctor hook FAILED: " + e)
                }
            }

            hookMethods(audioTrackClass, "write", object : Hooker {
                @Throws(Throwable::class)
override fun intercept(chain: Chain): Any? {

                    if (chain.thisObject == null || chain.args.isEmpty()) return chain.proceed()

                    // ── Reentrancy guard ──
                    if (hookThreadLocal.get() == true) return chain.proceed()
                    hookThreadLocal.set(true)
                    try {
                    val rawBuffer = chain.args[0] ?: return chain.proceed()
                    val argCount = chain.args.size
                    val sampleRate = try {
                        callMethodSafe(chain.thisObject, "getSampleRate") as Int
                    } catch (e: Exception) { 44100 }
                    val channelCount = try {
                        callMethodSafe(chain.thisObject, "getChannelCount") as Int
                    } catch (e: Exception) { 2 }

                    if (sampleRate < MIN_SANE_SAMPLE_RATE || sampleRate > MAX_SANE_SAMPLE_RATE ||
                        channelCount <= 0) return chain.proceed()

                    val pcmResult: ShortArray? = when (rawBuffer) {
                        is ShortArray -> {
                            val arrayLen = rawBuffer.size
                            if (arrayLen == 0) null else {
                                val offset = if (argCount > 1 && chain.args[1] is Int) chain.args[1] as Int else 0
                                if (offset < 0 || offset >= arrayLen) null else {
                                    val size = if (argCount > 2 && chain.args[2] is Int) chain.args[2] as Int else arrayLen - offset
                                    if (size <= 0 || offset + size > arrayLen) null else rawBuffer.sliceArray(offset until (offset + size))
                                }
                            }
                        }
                        is ByteArray -> {
                            val arrayLen = rawBuffer.size
                            if (arrayLen < 2) null else {
                                val offset = if (argCount > 1 && chain.args[1] is Int) chain.args[1] as Int else 0
                                if (offset < 0 || offset >= arrayLen) null else {
                                    val size = if (argCount > 2 && chain.args[2] is Int) chain.args[2] as Int else arrayLen - offset
                                    if (size < 2 || offset + size > arrayLen) null else {
                                        val validSize = size - (size % 2)
                                        if (validSize <= 0) null else {
                                            try {
                                                ShortArray(validSize / 2).also {
                                                    ByteBuffer.wrap(rawBuffer, offset, validSize)
                                                        .order(ByteOrder.nativeOrder())
                                                        .asShortBuffer().get(it)
                                                }
                                            } catch (e: Exception) { null }
                                        }
                                    }
                                }
                            }
                        }
                        is ByteBuffer -> {
                            val remaining = rawBuffer.remaining()
                            if (remaining < 2) null else {
                                val size = if (argCount > 1 && chain.args[1] is Int) chain.args[1] as Int else remaining
                                if (size < 2 || size > remaining) null else {
                                    val validSize = size - (size % 2)
                                    if (validSize <= 0) null else {
                                        try {
                                            val dup = rawBuffer.duplicate()
                                            dup.order(ByteOrder.LITTLE_ENDIAN)
                                            ShortArray(validSize / 2).also { dup.asShortBuffer().get(it) }
                                        } catch (e: Exception) { null }
                                    }
                                }
                            }
                        }
                        is FloatArray -> {
                            val arrayLen = rawBuffer.size
                            if (arrayLen == 0) null else {
                                val offset = if (argCount > 1 && chain.args[1] is Int) chain.args[1] as Int else 0
                                if (offset < 0 || offset >= arrayLen) null else {
                                    val size = if (argCount > 2 && chain.args[2] is Int) chain.args[2] as Int else arrayLen - offset
                                    if (size <= 0 || offset + size > arrayLen) null else {
                                        val outShort = ShortArray(size)
                                        for (i in 0 until size) {
                                            val idx = offset + i
                                            var fSample = rawBuffer[idx]
                                            if (fSample.isNaN() || fSample.isInfinite()) fSample = 0f
                                            outShort[i] = (fSample * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                                        }
                                        outShort
                                    }
                                }
                            }
                        }
                        else -> null
                    }
                    if (pcmResult != null && pcmResult.isNotEmpty()) {
                        if (!isMeaningfulPcm(pcmResult)) return chain.proceed()

                        val now = System.currentTimeMillis()
                        lastWriteTimestamp = now  // v3.13: Track for Visualizer fallback decision

                        val trackId = System.identityHashCode(chain.thisObject)
                        activeTracks[trackId]?.apply {
                            lastMeaningfulPcmAtMs = now
                            isPlaying = true
                        } ?: run {
                            activeTracks[trackId] = TrackInfo(
                                sampleRate = sampleRate,
                                channelCount = channelCount,
                                isOffloaded = false,
                                createdAt = now,
                                lastMeaningfulPcmAtMs = now,
                                isPlaying = true
                            )
                        }
                        if (now - lastWriteLogMs > WRITE_LOG_INTERVAL_MS) {
                            lastWriteLogMs = now
                            getContextFromActivityThread()?.let {
                                sendUiLog(it, "write() PCM: ${pcmResult.size} samples, sr=$sampleRate ch=$channelCount track=$trackId")
                            }
                        }
                        platformHandler?.post {
                            ensureEngineInitialized()
                            hapticEngine?.isVisualizerSource = false  // v3.13.1: Real PCM path, not Visualizer
                            hapticEngine?.reconfigure(sampleRate, channelCount)
                            hapticEngine?.processAudioFrame(pcmResult)
                        }
                    }
                    } finally {
                        hookThreadLocal.set(false)
                    }
                    return chain.proceed()
                }
            })

            for (methodName in listOf("pause", "stop")) {
                try {
                    hookMethods(audioTrackClass, methodName, object : Hooker {
                        @Throws(Throwable::class)
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                            if (chain.thisObject == null) return result
                            val trackIdentity = System.identityHashCode(chain.thisObject)
                            if (!shouldDecayAfterTrackControl(trackIdentity, terminal = true)) {
                                Log.d(TAG, "[PLAYBACK CONTROL] AudioTrack.$methodName() on auxiliary track=$trackIdentity — active PCM track retained")
return result
                            }
                            Log.i(TAG, "[PLAYBACK CONTROL] AudioTrack.$methodName() on [$pkg] — no active PCM tracks, forcing haptic decay")
                            getContextFromActivityThread()?.let {
                                sendUiLog(it, "[CONTROL] AudioTrack.$methodName() — last PCM track stopped")
                            }
                            deactivateVisualizerFallback()  // v3.13: Stop Visualizer on pause
                            platformHandler?.post {
                                ensureEngineInitialized()
                                hapticEngine?.onPlaybackPaused()
                            }
                        return result
                    }
                    })
                    Log.i(TAG, "Hooked AudioTrack.$methodName() in [$pkg]")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to hook AudioTrack.$methodName(): ${e.message}")
                }
            }

            for (methodName in listOf("release", "flush")) {
                try {
                    hookMethods(audioTrackClass, methodName, object : Hooker {
                        @Throws(Throwable::class)
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                            if (chain.thisObject == null) return result
                            val trackIdentity = System.identityHashCode(chain.thisObject)
                            val terminal = methodName == "release"
                            val shouldDecay = shouldDecayAfterTrackControl(trackIdentity, terminal)
                            if (terminal) {
                                activeTracks.remove(trackIdentity)
                                Log.d(TAG, "AudioTrack.release() [$pkg] — track removed, remaining=${activeTracks.size}")
                            }
                            if (!shouldDecay) {
                                Log.d(TAG, "AudioTrack.$methodName() on auxiliary track=$trackIdentity — active PCM track retained")
return result
                            }
                            platformHandler?.post {
                                ensureEngineInitialized()
                                hapticEngine?.onPlaybackPaused()
                            }
                        return result
                    }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to hook AudioTrack.$methodName(): ${e.message}")
                }
            }

            for (volMethod in listOf("setVolume", "setStereoVolume")) {
                try {
                    hookMethods(audioTrackClass, volMethod, object : Hooker {
                        @Throws(Throwable::class)
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                            if (chain.args.isEmpty()) return result
                            val vol = try { (chain.args[0] as Float).coerceIn(0f, 1f) } catch (_: Exception) { 1f }
                            Log.d(TAG, "AudioTrack.$volMethod() → $vol in [$pkg]")
                        return result
                    }
                    })
                } catch (_: Exception) { }
            }

            try {
                hookMethods(audioTrackClass, "attachAuxEffect", object : Hooker {
                    @Throws(Throwable::class)
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                        if (chain.args.isEmpty()) return result
                        val effectId = chain.args[0] as Int
                        Log.d(TAG, "AudioTrack.attachAuxEffect($effectId) in [$pkg]")
                        return result
                    }
                })
            } catch (_: Exception) { }

            try {
                hookMethods(audioTrackClass, "setAuxEffectSendLevel", object : Hooker {
                    @Throws(Throwable::class)
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                        if (chain.args.isEmpty()) return result
                        val level = chain.args[0] as Float
                        Log.d(TAG, "AudioTrack.setAuxEffectSendLevel($level) in [$pkg]")
                        return result
                    }
                })
            } catch (_: Exception) { }

            try {
                hookMethods(audioTrackClass, "setPerformanceMode", object : Hooker {
                    @Throws(Throwable::class)
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                        if (chain.args.isEmpty()) return result
                        val mode = chain.args[0] as Int
                        val modeStr = when (mode) {
                            1 -> "PERFORMANCE_MODE_NONE"
                            2 -> "PERFORMANCE_MODE_POWER_SAVING"
                            3 -> "PERFORMANCE_MODE_LOW_LATENCY"
                            else -> "UNKNOWN($mode)"
                        }
                        Log.d(TAG, "AudioTrack.setPerformanceMode($modeStr) in [$pkg]")
                        return result
                    }
                })
            } catch (_: Exception) { }

            try {
                val soundPoolClass = findClassSafe("android.media.SoundPool", param.defaultClassLoader)
                if (soundPoolClass != null) {
                    hookMethods(soundPoolClass, "play", object : Hooker {
                        @Throws(Throwable::class)
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                            Log.d(TAG, "SoundPool.play() called in [$pkg] — scheduling Visualizer fallback")
                            platformHandler?.postDelayed({
                                if (lastWriteTimestamp == 0L || System.currentTimeMillis() - lastWriteTimestamp > VISUALIZER_FALLBACK_DELAY_MS) {
                                    activateVisualizerFallback(44100, 2)
                                }
                            }, VISUALIZER_FALLBACK_DELAY_MS)
                            return result
                        }
                    })
                    Log.i(TAG, "Hooked SoundPool.play() in [$pkg]")
                }
            } catch (_: Exception) { }

            try {
                val mediaPlayerClass = findClassSafe("android.media.MediaPlayer", param.defaultClassLoader)
                if (mediaPlayerClass != null) {
                    for (mpMethod in listOf("start", "pause", "stop", "release")) {
                        try {
                            hookMethods(mediaPlayerClass, mpMethod, object : Hooker {
                                @Throws(Throwable::class)
                                override fun intercept(chain: Chain): Any? {
                                    val result = chain.proceed()
                                    Log.d(TAG, "MediaPlayer.$mpMethod() in [$pkg]")
                                    return result
                                }
                            })
                        } catch (_: Throwable) { }
                    }
                    Log.i(TAG, "Hooked MediaPlayer lifecycle in [$pkg]")
                }
            } catch (_: Exception) { }

            try {
                for (exoClassName in listOf("com.google.android.exoplayer2.ExoPlayer", "androidx.media3.exoplayer.ExoPlayer")) {
                    val exoClass = findClassSafe(exoClassName, param.defaultClassLoader) ?: continue
                    for (exoMethod in listOf("play", "setPlayWhenReady")) {
                        try {
                            hookMethods(exoClass, exoMethod, object : Hooker {
                                @Throws(Throwable::class)
                                override fun intercept(chain: Chain): Any? {
                                    val result = chain.proceed()
                                    Log.d(TAG, "ExoPlayer.$exoMethod() in [$pkg] — activating haptic path")
                                    platformHandler?.post { ensureEngineInitialized(); hapticEngine?.reconfigure(44100, 2) }
                                    return result
                                }
                            })
                        } catch (_: Throwable) { }
                    }
                }
                Log.i(TAG, "Hooked ExoPlayer in [$pkg]")
            } catch (_: Exception) { }

            try {
                val aaudioClass = findClassSafe("android.media.AudioStream", param.defaultClassLoader)
                if (aaudioClass != null) {
                    Log.i(TAG, "[AAudio] android.media.AudioStream class found in [$pkg] — AAudio API present")
                    getContextFromActivityThread()?.let { sendUiLog(it, "AAudio (AudioStream) class detected") }
                }
            } catch (_: Exception) { }

            try {
                val visualizerClass = findClassSafe("android.media.audiofx.Visualizer", param.defaultClassLoader)
                val visualizerBypass = ThreadLocal<Boolean>()
                if (visualizerClass != null) {
                    for (ctor in visualizerClass.declaredConstructors) {
                        try {
                            hook(ctor).intercept(object : Hooker {
                                @Throws(Throwable::class)
                                override fun intercept(chain: Chain): Any? {
                                    if (chain.args.isNotEmpty() && chain.args[0] is Int) {
                                        val sessionId = chain.args[0] as Int
                                        if (sessionId == 0 || sessionId == android.media.AudioManager.AUDIO_SESSION_ID_GENERATE) {
                                            visualizerBypass.set(true)
                                            Log.i(TAG, "[Visualizer] Marking session=$sessionId for permission bypass in [$pkg]")
                                        }
                                    }
                                    val result = try { chain.proceed() } catch (se: SecurityException) {
                                        Log.i(TAG, "[Visualizer] Suppressed SecurityException for session in [$pkg]")
                                        null
                                    }
                                    visualizerBypass.remove()
                                    return result
                                }
                            })
                        } catch (_: Throwable) { }
                    }
                    Log.i(TAG, "Hooked Visualizer constructors in [$pkg]")
                }
            } catch (_: Exception) { }

val proactiveVisualizerCheck = object : Runnable {
                override fun run() {
                    try {
                        if (!visualizerActive) {
                            val now = System.currentTimeMillis()
                            val idleMs = if (lastWriteTimestamp == 0L) -1L else now - lastWriteTimestamp
                            if (lastWriteTimestamp == 0L || idleMs > VISUALIZER_FALLBACK_DELAY_MS) {
                                Log.i(TAG, "[Visualizer] 🔍 Proactive check: no AudioTrack.write() for ${if (idleMs < 0) "EVER" else "${idleMs}ms"} — activating fallback for [$pkg]")
                                activateVisualizerFallback(22050, 1)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[Visualizer] Proactive check error: ${e.message}")
                    }
                    platformHandler?.postDelayed(this, 3000L)
                }
            }
            platformHandler?.postDelayed(proactiveVisualizerCheck, 5000L)
            Log.i(TAG, "[Visualizer] Proactive monitor scheduled (5s initial, 3s interval) for [$pkg]")
            getContextFromActivityThread()?.let { sendUiLog(it, "🔍 Visualizer proactive monitor active — auto-starts for native audio apps") }

        } catch (t: Throwable) {
            Log.e(TAG, "Hook installation failed with unexpected error: ${t.message}")
        }
    }
}