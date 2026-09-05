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
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.nio.ByteBuffer
import java.nio.ByteOrder

import com.mouya.musichaptics.LinkHealthMonitor
import com.mouya.musichaptics.phira.PhiraController

class MainHook : IXposedHookLoadPackage {

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

        // v4.23: Whitelist manager instance (initialized lazily with context)
        @Volatile private var whitelistManager: WhitelistManager? = null
        private fun getWhitelistManager(context: Context): WhitelistManager {
            if (whitelistManager == null) {
                synchronized(this) {
                    if (whitelistManager == null) {
                        whitelistManager = WhitelistManager(context)
                        whitelistManager?.initDefaultWhitelist()
                    }
                }
            }
            return whitelistManager!!
        }

        
        private const val PHIRA_PACKAGE = "org.flos.phira"

        private val SYSTEM_PACKAGE_BLOCKLIST = setOf(
            "android",
            "com.android.systemui",
            "com.android.phone"
        )

        
        private data class TrackInfo(
            val sampleRate: Int,
            val channelCount: Int,
            val isOffloaded: Boolean,
            var createdAt: Long,
            var sessionId: Int = 0,
            @Volatile var lastMeaningfulPcmAtMs: Long = 0L,
            @Volatile var isPlaying: Boolean = false
        )
        private val activeTracks = java.util.concurrent.ConcurrentHashMap<Int, TrackInfo>()
        private const val ACTIVE_PCM_GRACE_MS = 1500L

        private fun getDominantSessionId(): Int {
            val now = System.currentTimeMillis()
            val activeTrack = activeTracks.values.firstOrNull { it.isPlaying && (now - it.lastMeaningfulPcmAtMs < 1000) }
            return activeTrack?.sessionId ?: 0
        }

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

    
    @Volatile private var phiraController: PhiraController? = null
    private var fallbackVisualizer: android.media.audiofx.Visualizer? = null
    
    @Volatile private var visualizerActivationAttempts: Int = 0
    @Volatile private var visualizerPermissionFailures: Int = 0  

    private fun sendUiLog(context: Context, msg: String) {
        try {
            val intent = Intent(ACTION_LOG).apply {
                setPackage("com.mouya.musichaptics")
                putExtra("log_msg", "[Hook] $msg")
            }
            context.sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    private fun getContextFromActivityThread(): Context? {
        try {
            val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            
            val app = XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication")
            if (app is Context) return app
            
            val currentThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
            return XposedHelpers.callMethod(currentThread, "getSystemContext") as Context
        } catch (e: Exception) {
            return null
        }
    }

    private fun ensureNativeLibraryLoaded(lpparam: LoadPackageParam) {

        if (nativeLibLoaded) return
        synchronized(this) {
            if (nativeLibLoaded) return

            try {

                val moduleClassLoader = lpparam.classLoader

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
                    val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
                    val currentThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
                    val systemContext = XposedHelpers.callMethod(currentThread, "getSystemContext") as Context
                    systemContext.packageManager
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

            if (viz == null) {
                val dominantSessionId = getDominantSessionId()
                if (dominantSessionId != 0) {
                    try {
                        viz = android.media.audiofx.Visualizer(dominantSessionId)
                        Log.i(TAG, "[Visualizer] Created Visualizer(session=$dominantSessionId) — targeted dominant track session")
                    } catch (e: Exception) {
                        Log.w(TAG, "[Visualizer] Strategy 3 failed (dominant session $dominantSessionId): ${e.message}")
                    }
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
                        hapticEngine?.isVisualizerSource = true  
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
            visualizerActivationAttempts = 0  

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
            val activityThreadClass = XposedHelpers.findClass(
                "android.app.ActivityThread", null
            )
            val currentThread = XposedHelpers.callStaticMethod(
                activityThreadClass, "currentActivityThread"
            )

            if (currentThread == null) {
                Log.w(TAG, "ActivityThread.currentThread returned null.")
                sendUiLog(getContextFromActivityThread()!!, "ActivityThread.currentThread returned null.")
                return
            }

            
            
            var context = XposedHelpers.callStaticMethod(
                activityThreadClass, "currentApplication"
            ) as? Context

            if (context == null) {
                context = try {
                    XposedHelpers.callMethod(currentThread, "getSystemContext") as? Context
                } catch (e: Exception) { null }
            }

            if (context == null) {
                context = try {
                    val amClass = XposedHelpers.findClass(
                        "android.app.ActivityManager", null
                    )
                    val am = XposedHelpers.callStaticMethod(amClass, "getService")
                    XposedHelpers.callMethod(am, "getContext") as? Context
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
                    hapticEngine = null  
                    ensureEngineInitialized()
                }
            }, filter, CONFIG_SYNC_PERMISSION, null)
            configReceiverRegistered = true
            Log.i(TAG, "Config refresh receiver registered")
        } catch (e: Exception) {
            Log.w(TAG, "Config refresh receiver registration failed: ${e.message}")
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam?) {

        if (lpparam == null) {
            Log.e(TAG, "LoadPackageParam is null; aborting hook.")
            return
        }

        val pkg = lpparam.packageName
        if (pkg in SYSTEM_PACKAGE_BLOCKLIST) {
            return
        }

        // v4.23: Whitelist support — filter which apps trigger haptics
        if (pkg == "com.mouya.musichaptics") {
            Log.d(TAG, "Skipping self-package [$pkg] — no self-hook allowed.")
            return
        }

        // v4.23: Check whitelist before hooking — skip non-whitelisted apps
        val ctx = getContextFromActivityThread()
        if (ctx != null && !getWhitelistManager(ctx).isPackageAllowed(pkg)) {
            Log.i(TAG, "[Whitelist] Skipping non-whitelisted app: $pkg")
            return
        }

        if (lpparam.classLoader == null) {
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

        
        
        
        
        if (pkg == PHIRA_PACKAGE) {
            Log.i(TAG, "[Phira] Detected Phira process — activating chart-driven haptic path")
            ensureNativeLibraryLoaded(lpparam!!)

            
            
            
            val phiraInitStarted = booleanArrayOf(false)
            try {
                val appClass = XposedHelpers.findClass("android.app.Application", lpparam.classLoader)
                XposedBridge.hookAllMethods(appClass, "onCreate", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        if (phiraInitStarted[0]) return
                        phiraInitStarted[0] = true
                        Log.i(TAG, "[Phira] Application.onCreate — initializing chart-driven haptic path")
                        platformHandler?.post {
                            
                            ensureEngineInitialized()
                            val engine = hapticEngine
                            if (engine == null) {
                                Log.e(TAG, "[Phira] HapticEngine init failed — chart-driven path unavailable")
                                getContextFromActivityThread()?.let { sendUiLog(it, "[Phira] Engine init failed — haptics unavailable") }
                                return@post
                            }
                            val ctx = getContextFromActivityThread()
                            if (ctx == null) {
                                Log.e(TAG, "[Phira] Context unavailable — cannot start PhiraController")
                                return@post
                            }
                            try {
                                phiraController = PhiraController(ctx, engine).also { it.start() }
                                Log.i(TAG, "[Phira] PhiraController started successfully")
                                sendUiLog(ctx, "[Phira] ✅ Chart-driven haptic path active — play any chart to feel it")
                            } catch (t: Throwable) {
                                Log.e(TAG, "[Phira] PhiraController start failed: ${t.message}")
                                sendUiLog(ctx, "[Phira] Controller start failed: ${t.message}")
                            }
                        }
                    }
                })
                Log.i(TAG, "[Phira] Application.onCreate hook deployed — waiting for Application ready")
            } catch (e: Exception) {
                Log.w(TAG, "[Phira] Application.onCreate hook failed: ${e.message} — falling back to delayed init")
                platformHandler?.postDelayed({
                    ensureEngineInitialized()
                    val engine = hapticEngine
                    if (engine != null) {
                        val ctx = getContextFromActivityThread()
                        if (ctx != null) {
                            try {
                                phiraController = PhiraController(ctx, engine).also { it.start() }
                                Log.i(TAG, "[Phira] PhiraController started via fallback delayed init")
                                sendUiLog(ctx, "[Phira] ✅ Chart-driven haptic path active (delayed init)")
                            } catch (t: Throwable) {
                                Log.e(TAG, "[Phira] Fallback init failed: ${t.message}")
                            }
                        }
                    }
                }, 3000L)
            }
        }

        try {

            val audioTrackClass = try {
                XposedHelpers.findClass("android.media.AudioTrack", lpparam.classLoader)
            } catch (ex: ClassNotFoundException) {
                Log.w(TAG, "AudioTrack class not found in process [$pkg]; skipping.")
                return
            } catch (t: Throwable) {
                Log.e(TAG, "Unexpected error locating AudioTrack: ${t.message}")
                return
            }

            ensureNativeLibraryLoaded(lpparam!!)

            try {
                val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
                val currentThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
                val systemContext = XposedHelpers.callMethod(currentThread, "getSystemContext") as Context
                NativeBridge.preloadLibrary(systemContext)
            } catch (e: Exception) {
                Log.w(TAG, "NativeBridge.preloadLibrary failed: ${e.message}")
            }

            Log.i(TAG, "[HOOK ACTIVE] Package=$pkg AudioTrack found=${audioTrackClass.simpleName ?: "unknown"}")
            getContextFromActivityThread()?.let { sendUiLog(it, "[HOOK ACTIVE] Package=$pkg AudioTrack found=${audioTrackClass.simpleName ?: "unknown"}") }

            val audioMethods = try {
                val methods = audioTrackClass.declaredMethods ?: audioTrackClass.methods
                methods.map { it.name }.distinct()
            } catch (_: Exception) { emptyList<String>() }
            Log.d(TAG, "AudioTrack methods visible: $audioMethods")

            XposedBridge.hookAllConstructors(audioTrackClass, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun afterHookedMethod(param: MethodHookParam?) {

                    if (param == null || param.thisObject == null) return
                    val track = param.thisObject
                    val trackIdentity = System.identityHashCode(track)

                    val sr = try {
                        XposedHelpers.callMethod(track, "getSampleRate") as Int
                    } catch (e: Exception) { 44100 }

                    val ch = try {
                        XposedHelpers.callMethod(track, "getChannelCount") as Int
                    } catch (e: Exception) { 2 }

                    if (sr !in MIN_SANE_SAMPLE_RATE..MAX_SANE_SAMPLE_RATE) {
                        Log.w(TAG, "Unreasonable sample rate: ${sr}Hz — rejected.")
                        return
                    }

                    if (ch <= 0 || ch > MAX_SANE_CHANNELS) {
                        Log.w(TAG, "Unreasonable channel count: $ch — rejected.")
                        return
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
                        XposedHelpers.callMethod(track, "getStreamType") as Int
                    } catch (_: Exception) { -1 }

                    activeTracks[trackIdentity] = TrackInfo(sr, ch, isOffloaded, System.currentTimeMillis())
                    
                    try {
                        val sessionId = XposedHelpers.callMethod(track, "getAudioSessionId") as Int
                        if (sessionId > 0) {
                            activeTracks[trackIdentity]?.sessionId = sessionId
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[AudioTrack] Failed to get session id: ${e.message}")
                    }

                    Log.i(TAG, "AudioTrack created in [$pkg] — sr=${sr}Hz ch=$ch offloaded=$isOffloaded direct=$isDirect streamType=$mode tracks=${activeTracks.size}")
                    getContextFromActivityThread()?.let { sendUiLog(it, "AudioTrack[$trackIdentity] sr=${sr}Hz ch=$ch off=$isOffloaded direct=$isDirect tracks=${activeTracks.size}") }

                    try {
                        val sessionId = XposedHelpers.callMethod(track, "getAudioSessionId") as Int
                        if (sessionId > 0) {
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
                        XposedBridge.hookMethod(
                            XposedHelpers.findMethodBestMatch(audioTrackClass, "play", null),
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam?) {
                                    if (param?.thisObject == null) return
                                    Log.d(TAG, "AudioTrack.play() called in [$pkg] — streaming audio path active")
                                }
                            }
                        )
                    } catch (_: Throwable) { }

                    platformHandler?.post {
                        ensureEngineInitialized()
                        hapticEngine?.reconfigure(sr, ch)
                    }
                }
            })

            XposedBridge.hookAllMethods(audioTrackClass, "write", object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam?) {

                    if (param == null || param.thisObject == null || param.args.isEmpty()) {
                        return
                    }

                    
                    if (hookThreadLocal.get() == true) return
                    hookThreadLocal.set(true)
                    try {
                    val rawBuffer = param.args[0] ?: return
                    val argCount = param.args.size
                    val sampleRate = try {
                        XposedHelpers.callMethod(param.thisObject, "getSampleRate") as Int
                    } catch (e: Exception) { 44100 }
                    val channelCount = try {
                        XposedHelpers.callMethod(param.thisObject, "getChannelCount") as Int
                    } catch (e: Exception) { 2 }

                    if (sampleRate < MIN_SANE_SAMPLE_RATE || sampleRate > MAX_SANE_SAMPLE_RATE ||
                        channelCount <= 0) return

                    val pcmResult: ShortArray? = when (rawBuffer) {
                        is ShortArray -> {
                            val arrayLen = rawBuffer.size
                            if (arrayLen == 0) null else {
                                val offset = if (argCount > 1 && param.args[1] is Int) param.args[1] as Int else 0
                                if (offset < 0 || offset >= arrayLen) null else {
                                    val size = if (argCount > 2 && param.args[2] is Int) param.args[2] as Int else arrayLen - offset
                                    if (size <= 0 || offset + size > arrayLen) null else rawBuffer.sliceArray(offset until (offset + size))
                                }
                            }
                        }
                        is ByteArray -> {
                            val arrayLen = rawBuffer.size
                            if (arrayLen < 2) null else {
                                val offset = if (argCount > 1 && param.args[1] is Int) param.args[1] as Int else 0
                                if (offset < 0 || offset >= arrayLen) null else {
                                    val size = if (argCount > 2 && param.args[2] is Int) param.args[2] as Int else arrayLen - offset
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
                                val size = if (argCount > 1 && param.args[1] is Int) param.args[1] as Int else remaining
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
                                val offset = if (argCount > 1 && param.args[1] is Int) param.args[1] as Int else 0
                                if (offset < 0 || offset >= arrayLen) null else {
                                    val size = if (argCount > 2 && param.args[2] is Int) param.args[2] as Int else arrayLen - offset
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
                        } else -> null
                    }
                    if (pcmResult != null && pcmResult.isNotEmpty()) {
                        if (!isMeaningfulPcm(pcmResult)) return

                        val now = System.currentTimeMillis()
                        lastWriteTimestamp = now  

                        val trackId = System.identityHashCode(param.thisObject)
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
                        
                        ensureEngineInitialized()
                        hapticEngine?.isVisualizerSource = false  
                        hapticEngine?.reconfigure(sampleRate, channelCount)
                        hapticEngine?.processAudioFrame(pcmResult)
                    }
                    } finally {
                        hookThreadLocal.set(false)
                    }
                }
            })

            for (methodName in listOf("pause", "stop")) {
                try {
                    XposedBridge.hookAllMethods(audioTrackClass, methodName, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam?) {
                            if (param?.thisObject == null) return
                            val trackIdentity = System.identityHashCode(param.thisObject)
                            if (!shouldDecayAfterTrackControl(trackIdentity, terminal = true)) {
                                Log.d(TAG, "[PLAYBACK CONTROL] AudioTrack.$methodName() on auxiliary track=$trackIdentity — active PCM track retained")
                                return
                            }
                            Log.i(TAG, "[PLAYBACK CONTROL] AudioTrack.$methodName() on [$pkg] — no active PCM tracks, forcing haptic decay")
                            getContextFromActivityThread()?.let {
                                sendUiLog(it, "[CONTROL] AudioTrack.$methodName() — last PCM track stopped")
                            }
                            deactivateVisualizerFallback()  
                            platformHandler?.post {
                                ensureEngineInitialized()
                                hapticEngine?.onPlaybackPaused()
                            }
                        }
                    })
                    Log.i(TAG, "Hooked AudioTrack.$methodName() in [$pkg]")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to hook AudioTrack.$methodName(): ${e.message}")
                }
            }

            for (methodName in listOf("release", "flush")) {
                try {
                    XposedBridge.hookAllMethods(audioTrackClass, methodName, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam?) {
                            if (param?.thisObject == null) return
                            val trackIdentity = System.identityHashCode(param.thisObject)
                            val terminal = methodName == "release"
                            val shouldDecay = shouldDecayAfterTrackControl(trackIdentity, terminal)
                            if (terminal) {
                                activeTracks.remove(trackIdentity)
                                Log.d(TAG, "AudioTrack.release() [$pkg] — track removed, remaining=${activeTracks.size}")
                            }
                            if (!shouldDecay) {
                                Log.d(TAG, "AudioTrack.$methodName() on auxiliary track=$trackIdentity — active PCM track retained")
                                return
                            }
                            platformHandler?.post {
                                ensureEngineInitialized()
                                hapticEngine?.onPlaybackPaused()
                            }
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to hook AudioTrack.$methodName(): ${e.message}")
                }
            }

            for (volMethod in listOf("setVolume", "setStereoVolume")) {
                try {
                    XposedBridge.hookAllMethods(audioTrackClass, volMethod, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam?) {
                            if (param?.args == null || param.args.isEmpty()) return
                            val vol = try { (param.args[0] as Float).coerceIn(0f, 1f) } catch (_: Exception) { 1f }
                            Log.d(TAG, "AudioTrack.$volMethod() → $vol in [$pkg]")
                        }
                    })
                } catch (_: Exception) { }
            }

            try {
                XposedBridge.hookAllMethods(audioTrackClass, "attachAuxEffect", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        if (param?.args == null || param.args.isEmpty()) return
                        val effectId = param.args[0] as Int
                        Log.d(TAG, "AudioTrack.attachAuxEffect($effectId) in [$pkg]")
                    }
                })
            } catch (_: Exception) { }

            try {
                XposedBridge.hookAllMethods(audioTrackClass, "setAuxEffectSendLevel", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        if (param?.args == null || param.args.isEmpty()) return
                        val level = param.args[0] as Float
                        Log.d(TAG, "AudioTrack.setAuxEffectSendLevel($level) in [$pkg]")
                    }
                })
            } catch (_: Exception) { }

            try {
                XposedBridge.hookAllMethods(audioTrackClass, "setPerformanceMode", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        if (param?.args == null || param.args.isEmpty()) return
                        val mode = param.args[0] as Int
                        val modeStr = when (mode) {
                            1 -> "PERFORMANCE_MODE_NONE"
                            2 -> "PERFORMANCE_MODE_POWER_SAVING"
                            3 -> "PERFORMANCE_MODE_LOW_LATENCY"
                            else -> "UNKNOWN($mode)"
                        }
                        Log.d(TAG, "AudioTrack.setPerformanceMode($modeStr) in [$pkg]")
                    }
                })
            } catch (_: Exception) { }

            try {
                val soundPoolClass = XposedHelpers.findClass("android.media.SoundPool", lpparam.classLoader)
                XposedBridge.hookAllMethods(soundPoolClass, "play", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        if (param?.args == null || param.args.size < 2) return
                        Log.d(TAG, "SoundPool.play() called in [$pkg] — scheduling Visualizer fallback")
                        platformHandler?.postDelayed({
                            if (lastWriteTimestamp == 0L || System.currentTimeMillis() - lastWriteTimestamp > VISUALIZER_FALLBACK_DELAY_MS) {
                                activateVisualizerFallback(44100, 2)
                            }
                        }, VISUALIZER_FALLBACK_DELAY_MS)
                    }
                })
                Log.i(TAG, "Hooked SoundPool.play() in [$pkg]")
            } catch (_: Exception) {
            }

            try {
                val mediaPlayerClass = XposedHelpers.findClass("android.media.MediaPlayer", lpparam.classLoader)
                for (mpMethod in listOf("start", "pause", "stop", "release")) {
                    try {
                        XposedBridge.hookAllMethods(mediaPlayerClass, mpMethod, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam?) {
                                if (param?.thisObject == null) return
                                when (mpMethod) {
                                    "start" -> {
                                        Log.i(TAG, "[MediaPlayer] start() in [$pkg] — native audio path activated")
                                        getContextFromActivityThread()?.let { sendUiLog(it, "MediaPlayer.start() — activating Visualizer fallback for native audio") }
                                        platformHandler?.postDelayed({
                                            if (lastWriteTimestamp == 0L || System.currentTimeMillis() - lastWriteTimestamp > VISUALIZER_FALLBACK_DELAY_MS) {
                                                activateVisualizerFallback(44100, 2)
                                            }
                                        }, VISUALIZER_FALLBACK_DELAY_MS)
                                    }
                                    "pause", "stop", "release" -> {
                                        getContextFromActivityThread()?.let {
                                            sendUiLog(it, "[MediaPlayer] $mpMethod() — haptic decay")
                                        }
                                        deactivateVisualizerFallback()  
                                        platformHandler?.post {
                                            ensureEngineInitialized()
                                            hapticEngine?.onPlaybackPaused()
                                        }
                                    }
                                }
                            }
                        })
                        Log.i(TAG, "Hooked MediaPlayer.$mpMethod() in [$pkg]")
                    } catch (_: Exception) { }
                }
            } catch (_: ClassNotFoundException) {
            }

            val exoPlayerClasses = listOf(
                "com.google.android.exoplayer2.ExoPlayer",
                "com.google.android.exoplayer2.SimpleExoPlayer",
                "com.google.android.exoplayer2.Player"
            )
            for (exoClassName in exoPlayerClasses) {
                try {
                    val exoClass = XposedHelpers.findClass(exoClassName, lpparam.classLoader)
                    for (exoMethod in listOf("pause", "stop", "release")) {
                        try {
                            XposedBridge.hookAllMethods(exoClass, exoMethod, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam?) {
                                    if (param?.thisObject == null) return
                                    getContextFromActivityThread()?.let {
                                        sendUiLog(it, "[ExoPlayer] $exoMethod() — haptic decay")
                                    }
                                    deactivateVisualizerFallback()  
                                    platformHandler?.post {
                                        ensureEngineInitialized()
                                        hapticEngine?.onPlaybackPaused()
                                    }
                                }
                            })
                            Log.i(TAG, "Hooked $exoClassName.$exoMethod() in [$pkg]")
                        } catch (_: Exception) { }
                    }
                    try {
                        XposedBridge.hookAllMethods(exoClass, "setPlayWhenReady", object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam?) {
                                if (param?.args == null || param.args.isEmpty()) return
                                val playWhenReady = param.args[0] as Boolean
                                if (playWhenReady) {
                                    Log.i(TAG, "[ExoPlayer] setPlayWhenReady(true) in [$pkg] — scheduling Visualizer fallback")
                                    platformHandler?.postDelayed({
                                        if (lastWriteTimestamp == 0L || System.currentTimeMillis() - lastWriteTimestamp > VISUALIZER_FALLBACK_DELAY_MS) {
                                            activateVisualizerFallback(44100, 2)
                                        }
                                    }, VISUALIZER_FALLBACK_DELAY_MS)
                                }
                            }
                        })
                    } catch (_: Exception) { }
                } catch (_: ClassNotFoundException) {
                } catch (_: Exception) {
                }
            }

            try {
                val aaudioClass = XposedHelpers.findClass("android.media.AudioStream", lpparam.classLoader)
                Log.i(TAG, "[AAudio] android.media.AudioStream class found in [$pkg] — AAudio API present")
                getContextFromActivityThread()?.let { sendUiLog(it, "AAudio (AudioStream) class detected") }
            } catch (_: Exception) {
            }

            try {
                val visualizerClass = XposedHelpers.findClass("android.media.audiofx.Visualizer", lpparam.classLoader)
                XposedBridge.hookAllConstructors(visualizerClass, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param == null || param.args.isEmpty()) return
                        val sessionId = param.args[0] as Int
                        if (sessionId == 0 || sessionId == android.media.AudioManager.AUDIO_SESSION_ID_GENERATE) {
                            param.setObjectExtra("bypassPermission", true)
                            Log.i(TAG, "[Visualizer] 🔓 Marking session=$sessionId for permission bypass in [$pkg]")
                        }
                    }
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        if (param == null || param.thisObject == null) return
                        val sessionId = if (param.args.isNotEmpty()) param.args[0] as Int else -1
                        val shouldBypass = param.getObjectExtra("bypassPermission") as? Boolean == true
                        if (shouldBypass) {
                            if (param.hasThrowable()) {
                                val throwable = param.throwable
                                if (throwable is SecurityException) {
                                    param.setThrowable(null)  
                                    Log.i(TAG, "[Visualizer] ✅ Suppressed SecurityException for session=$sessionId in [$pkg]")
                                }
                            }
                            Log.i(TAG, "[Visualizer] ✅ Visualizer constructed for session=$sessionId in [$pkg] (permission bypass active)")
                        } else {
                            Log.i(TAG, "[Visualizer] Visualizer constructed for session=$sessionId in [$pkg]")
                        }
                    }
                })
                Log.i(TAG, "[Visualizer] ✅ Permission bypass hook deployed for [$pkg]")
            } catch (e: Exception) {
                Log.w(TAG, "[Visualizer] Permission bypass hook failed: ${e.message}")
            }

            Log.i(TAG, "✅ All hook defense lines deployed for process [$pkg]. Active methods: write, constructors, pause, stop, release, flush, volume, effects, performanceMode, SoundPool, MediaPlayer(start/pause/stop/release), ExoPlayer(pause/stop/release)")

            
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