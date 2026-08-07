package com.mouya.musichaptics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

        /**
         * AudioTrack.write() is also used by silent auxiliary tracks. Keep control
         * decisions tied to tracks which recently carried non-trivial PCM, rather
         * than allowing an auxiliary pause/flush to reset the real music stream.
         */
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

        // ─── Reentrancy guard: prevents double-processing when native_write is called from write() ───
        private val hookThreadLocal = ThreadLocal<Boolean>()

        // ─── Log throttling ───
        @Volatile private var lastWriteLogMs = 0L
        private const val WRITE_LOG_INTERVAL_MS = 1000L

        private fun sendUiLog(context: Context, msg: String) {
            try {
                val intent = Intent(ACTION_LOG).apply {
                    setPackage("com.mouya.musichaptics")
                    putExtra("log_msg", "[Hook] $msg")
                }
                context.sendBroadcast(intent)
            } catch (_: Exception) {}
        }
    }

    private var hapticEngine: HapticEngine? = null
    // MainHook is instantiated in each hooked process. Preserve that process's
    // package because the engine may otherwise receive a system Context.
    @Volatile private var hookedTargetPackage: String? = null
    private var platformThread: HandlerThread? = null
    private var platformHandler: Handler? = null
    private val initLock = Any()
    @Volatile private var configReceiverRegistered = false

    @Volatile private var nativeLibLoaded = false

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

    private fun getContextFromActivityThread(): Context? {
        try {
            val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            val currentThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
            return XposedHelpers.callMethod(currentThread, "getSystemContext") as Context
        } catch (e: Exception) {
            return null
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

        if (pkg == "com.mouya.musichaptics") {
            Log.d(TAG, "Skipping self-package [$pkg] — no self-hook allowed.")
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

        // Persist the LoadPackageParam value for deferred platform-worker callbacks.
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

            // ════════════════════════════════════════════════════════════════
            // 1. Hook AudioTrack constructors — detect ALL track creation paths
            // ════════════════════════════════════════════════════════════════
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

                    // Detect offloaded playback (hardware-decoded audio, no PCM write())
                    val isOffloaded = try {
                        val getOffloaded = audioTrackClass.getMethod("isOffloadedPlayback")
                        getOffloaded.invoke(track) as Boolean
                    } catch (_: Exception) { false }

                    // Detect direct (low-latency) tracks
                    val isDirect = try {
                        val getDirect = audioTrackClass.getMethod("isDirect")
                        getDirect.invoke(track) as Boolean
                    } catch (_: Exception) { false }

                    // Detect streaming mode
                    val mode = try {
                        XposedHelpers.callMethod(track, "getStreamType") as Int
                    } catch (_: Exception) { -1 }

                    activeTracks[trackIdentity] = TrackInfo(sr, ch, isOffloaded, System.currentTimeMillis())

                    Log.i(TAG, "AudioTrack created in [$pkg] — sr=${sr}Hz ch=$ch offloaded=$isOffloaded direct=$isDirect streamType=$mode tracks=${activeTracks.size}")
                    getContextFromActivityThread()?.let { sendUiLog(it, "AudioTrack[$trackIdentity] sr=${sr}Hz ch=$ch off=$isOffloaded direct=$isDirect tracks=${activeTracks.size}") }

                    if (isOffloaded) {
                        Log.w(TAG, "⚠ OFFLOADED AudioTrack detected — PCM write() may NOT be called. Haptics limited for this track.")
                        getContextFromActivityThread()?.let { sendUiLog(it, "⚠ Offloaded track (hardware-decoded) — limited haptic support") }
                    }

                    // Hook play() per-track (not globally — avoids duplicate hooks across constructors)
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
                    } catch (_: Exception) { }

                    platformHandler?.post {
                        ensureEngineInitialized()
                        hapticEngine?.reconfigure(sr, ch)
                    }
                }
            })

                        // ════════════════════════════════════════════════════════════════
            // 2. Hook AudioTrack.write() — ALL overloads (ShortArray, ByteArray, ByteBuffer, FloatArray)
            //    with reentrancy guard and log throttling
            // ════════════════════════════════════════════════════════════════
            XposedBridge.hookAllMethods(audioTrackClass, "write", object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam?) {

                    if (param == null || param.thisObject == null || param.args.isEmpty()) {
                        return
                    }

                    // ── Reentrancy guard ──
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
                        }
                        else -> null
                    }
                    if (pcmResult != null && pcmResult.isNotEmpty()) {
                        // Do the inexpensive activity classification before posting.
                        // This prevents silent auxiliary tracks from repeatedly
                        // reconfiguring or clearing the PCM/DSP path.
                        if (!isMeaningfulPcm(pcmResult)) return

                        val now = System.currentTimeMillis()
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
                        // Throttled logging — avoid logcat flooding
                        if (now - lastWriteLogMs > WRITE_LOG_INTERVAL_MS) {
                            lastWriteLogMs = now
                            getContextFromActivityThread()?.let {
                                sendUiLog(it, "write() PCM: ${pcmResult.size} samples, sr=$sampleRate ch=$channelCount track=$trackId")
                            }
                        }
                        platformHandler?.post {
                            ensureEngineInitialized()
                            hapticEngine?.reconfigure(sampleRate, channelCount)
                            hapticEngine?.processAudioFrame(pcmResult)
                        }
                    }
                    } finally {
                        hookThreadLocal.set(false)
                    }
                }
            })

            // ════════════════════════════════════════════════════════════════
            // 3. Hook AudioTrack.pause() & stop() — immediate haptic shutdown
            // ════════════════════════════════════════════════════════════════
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

            // ════════════════════════════════════════════════════════════════
            // 4. Hook AudioTrack.release() & flush() — track cleanup
            // ════════════════════════════════════════════════════════════════
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

            // ════════════════════════════════════════════════════════════════
            // 5. Hook AudioTrack.setVolume() & setStereoVolume() — volume tracking
            // ════════════════════════════════════════════════════════════════
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

            // ════════════════════════════════════════════════════════════════
            // 6. Hook AudioTrack.attachAuxEffect() & setAuxEffectSendLevel() — effect chain
            // ════════════════════════════════════════════════════════════════
            for (effMethod in listOf("attachAuxEffect", "setAuxEffectSendLevel")) {
                try {
                    XposedBridge.hookAllMethods(audioTrackClass, effMethod, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam?) {
                            Log.d(TAG, "AudioTrack.$effMethod() called in [$pkg] — effect chain detected")
                        }
                    })
                } catch (_: Exception) { }
            }

            // ════════════════════════════════════════════════════════════════
            // 7. Hook AudioTrack.setPerformanceMode() — low-latency detection
            // ════════════════════════════════════════════════════════════════
            try {
                XposedBridge.hookAllMethods(audioTrackClass, "setPerformanceMode", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        val mode = param?.args?.firstOrNull()
                        Log.i(TAG, "AudioTrack.setPerformanceMode($mode) in [$pkg]")
                        getContextFromActivityThread()?.let { sendUiLog(it, "PerformanceMode=$mode — low-latency track") }
                    }
                })
            } catch (_: Exception) { }

            // ════════════════════════════════════════════════════════════════
            // 8. SoundPool hook — catch games & apps using SoundPool for audio
            // ════════════════════════════════════════════════════════════════
            try {
                val soundPoolClass = XposedHelpers.findClass("android.media.SoundPool", lpparam.classLoader)
                XposedBridge.hookAllMethods(soundPoolClass, "play", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        val now = System.currentTimeMillis()
                        if (now - lastWriteLogMs > WRITE_LOG_INTERVAL_MS) {
                            lastWriteLogMs = now
                            Log.d(TAG, "[SoundPool] play() detected in [$pkg] — SoundPool audio path (limited haptic support)")
                            getContextFromActivityThread()?.let { sendUiLog(it, "SoundPool.play() detected — short audio clips") }
                        }
                    }
                })
                Log.i(TAG, "SoundPool hook deployed in [$pkg]")
            } catch (_: Exception) {
                Log.d(TAG, "SoundPool class not available in [$pkg]")
            }

            // ════════════════════════════════════════════════════════════════
            // 9. MediaPlayer hook — detect playback state for apps using MediaPlayer
            //    Hook pause/stop to trigger haptic shutdown (fixes "still vibrating
            //    after pause" for apps that use MediaPlayer instead of AudioTrack)
            // ════════════════════════════════════════════════════════════════
            try {
                val mediaPlayerClass = XposedHelpers.findClass("android.media.MediaPlayer", lpparam.classLoader)
                XposedBridge.hookAllMethods(mediaPlayerClass, "start", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        Log.i(TAG, "[MediaPlayer] start() in [$pkg] — native audio path activated")
                        getContextFromActivityThread()?.let { sendUiLog(it, "MediaPlayer.start() — native audio, haptics via AudioTrack path only") }
                    }
                })
                // Hook pause/stop for MediaPlayer — trigger haptic shutdown
                for (mpMethod in listOf("pause", "stop")) {
                    try {
                        XposedBridge.hookAllMethods(mediaPlayerClass, mpMethod, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam?) {
                                Log.i(TAG, "[MediaPlayer] $mpMethod() in [$pkg] — forcing haptic decay")
                                getContextFromActivityThread()?.let {
                                    sendUiLog(it, "[MediaPlayer] $mpMethod() — haptic decay")
                                }
                                platformHandler?.post {
                                    ensureEngineInitialized()
                                    hapticEngine?.onPlaybackPaused()
                                }
                            }
                        })
                        Log.i(TAG, "Hooked MediaPlayer.$mpMethod() in [$pkg]")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to hook MediaPlayer.$mpMethod(): ${e.message}")
                    }
                }
                // Hook release for MediaPlayer cleanup
                try {
                    XposedBridge.hookAllMethods(mediaPlayerClass, "release", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam?) {
                            Log.d(TAG, "[MediaPlayer] release() in [$pkg] — haptic cleanup")
                            platformHandler?.post {
                                ensureEngineInitialized()
                                hapticEngine?.onPlaybackPaused()
                            }
                        }
                    })
                } catch (_: Exception) {}
                Log.i(TAG, "MediaPlayer hook (start/pause/stop/release) deployed in [$pkg]")
            } catch (_: Exception) {
                Log.d(TAG, "MediaPlayer class not available in [$pkg]")
            }

            // ════════════════════════════════════════════════════════════════
            // 9b. ExoPlayer hook — B站, YouTube, etc. use ExoPlayer for playback
            //     ExoPlayer wraps AudioTrack internally, so PCM data flows through
            //     our AudioTrack hooks. But pause/stop on ExoPlayer doesn't always
            //     propagate to AudioTrack.pause() immediately, causing stale
            //     vibrations. Hook these methods to force haptic shutdown.
            //
            //     We attempt both the Google ExoPlayer interface and the
            //     androidx Media3 ExoPlayer interface for maximum coverage.
            // ════════════════════════════════════════════════════════════════
            val exoPlayerClassNames = listOf(
                "com.google.android.exoplayer2.ExoPlayer",
                "androidx.media3.exoplayer.ExoPlayer",
                "com.google.android.exoplayer2.SimpleExoPlayer",
                "androidx.media3.exoplayer.SimpleExoPlayer"
            )
            for (exoClassName in exoPlayerClassNames) {
                try {
                    val exoClass = XposedHelpers.findClass(exoClassName, lpparam.classLoader)
                    for (exoMethod in listOf("pause", "stop", "release")) {
                        try {
                            XposedBridge.hookAllMethods(exoClass, exoMethod, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam?) {
                                    Log.i(TAG, "[ExoPlayer:$exoClassName] $exoMethod() in [$pkg] — forcing haptic decay")
                                    getContextFromActivityThread()?.let {
                                        sendUiLog(it, "[ExoPlayer] $exoMethod() — haptic decay")
                                    }
                                    platformHandler?.post {
                                        ensureEngineInitialized()
                                        hapticEngine?.onPlaybackPaused()
                                    }
                                }
                            })
                            Log.i(TAG, "Hooked $exoClassName.$exoMethod() in [$pkg]")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to hook $exoClassName.$exoMethod(): ${e.message}")
                        }
                    }
                } catch (_: ClassNotFoundException) {
                    // This ExoPlayer variant not present in this app — try next
                } catch (_: Exception) {
                    // NOP — continue trying other variants
                }
            }

            // ════════════════════════════════════════════════════════════════
            // 10. AAudio hook (API 26+) — modern low-latency audio API
            //     AAudio streams through AudioTrack internally on most devices,
            //     so AudioTrack hooks should still catch the data. This is diagnostic.
            // ════════════════════════════════════════════════════════════════
            try {
                val aaudioClass = XposedHelpers.findClass("android.media.AudioStream", lpparam.classLoader)
                Log.i(TAG, "[AAudio] android.media.AudioStream class found in [$pkg] — AAudio API present")
                getContextFromActivityThread()?.let { sendUiLog(it, "AAudio (AudioStream) class detected") }
            } catch (_: Exception) {
                // AAudio not available — normal on older Android
            }

            Log.i(TAG, "✅ All hook defense lines deployed for process [$pkg]. Active methods: write, constructors, pause, stop, release, flush, volume, effects, performanceMode, SoundPool, MediaPlayer(start/pause/stop/release), ExoPlayer(pause/stop/release)")

            // ════════════════════════════════════════════════════════════════
            // 11. MediaCodec hook — apps that use MediaCodec directly (e.g.
            //     酷我音乐, some DRM-protected streams) bypass AudioTrack
            //     entirely. We hook getOutputBuffers/queueOutputBuffer to
            //     detect when decoded PCM frames are being rendered.
            // ════════════════════════════════════════════════════════════════
            try {
                val mediaCodecClass = XposedHelpers.findClass("android.media.MediaCodec", lpparam.classLoader)
                // Hook queueOutputBuffer — the point where decoded frames are submitted to the renderer
                for (qoeMethod in listOf("queueOutputBuffer", "queueSecureOutputBuffer")) {
                    try {
                        XposedBridge.hookAllMethods(mediaCodecClass, qoeMethod, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam?) {
                                Log.d(TAG, "[MediaCodec] $qoeMethod() in [$pkg] — decoded frame submitted")
                                platformHandler?.post {
                                    ensureEngineInitialized()
                                    // MediaCodec path: we can't get raw PCM here, but we know
                                    // audio is actively playing → keep haptics alive
                                    hapticEngine?.reconfigure(44100, 2)
                                }
                            }
                        })
                        Log.i(TAG, "Hooked MediaCodec.$qoeMethod() in [$pkg]")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to hook MediaCodec.$qoeMethod(): ${e.message}")
                    }
                }
                // Hook start() — playback started
                try {
                    XposedBridge.hookAllMethods(mediaCodecClass, "start", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam?) {
                            Log.i(TAG, "[MediaCodec] start() in [$pkg] — codec active")
                            platformHandler?.post {
                                ensureEngineInitialized()
                                hapticEngine?.reconfigure(44100, 2)
                            }
                        }
                    })
                } catch (_: Exception) {}
                // Hook stop()/release() — playback stopped
                for (mcMethod in listOf("stop", "release")) {
                    try {
                        XposedBridge.hookAllMethods(mediaCodecClass, mcMethod, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam?) {
                                Log.i(TAG, "[MediaCodec] $mcMethod() in [$pkg] — codec stopped, forcing haptic decay")
                                platformHandler?.post {
                                    ensureEngineInitialized()
                                    hapticEngine?.onPlaybackPaused()
                                }
                            }
                        })
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to hook MediaCodec.$mcMethod(): ${e.message}")
                    }
                }
                Log.i(TAG, "MediaCodec hook deployed in [$pkg]")
            } catch (_: ClassNotFoundException) {
                // MediaCodec not used by this app — normal
            } catch (e: Exception) {
                Log.w(TAG, "MediaCodec hook error in [$pkg]: ${e.message}")
            }

            // ════════════════════════════════════════════════════════════════
            // 12. MediaPlayer2 hook (Android 10+) — modern media API
            //     Some apps (including 酷我音乐) use MediaPlayer2 instead of
            //     the legacy MediaPlayer.
            // ════════════════════════════════════════════════════════════════
            try {
                val mp2Class = XposedHelpers.findClass("android.media.MediaPlayer2", lpparam.classLoader)
                for (mp2Method in listOf("pause", "stop", "release", "reset")) {
                    try {
                        XposedBridge.hookAllMethods(mp2Class, mp2Method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam?) {
                                Log.i(TAG, "[MediaPlayer2] $mp2Method() in [$pkg] — forcing haptic decay")
                                platformHandler?.post {
                                    ensureEngineInitialized()
                                    hapticEngine?.onPlaybackPaused()
                                }
                            }
                        })
                        Log.i(TAG, "Hooked MediaPlayer2.$mp2Method() in [$pkg]")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to hook MediaPlayer2.$mp2Method(): ${e.message}")
                    }
                }
            } catch (_: ClassNotFoundException) {
                // MediaPlayer2 not used by this app
            } catch (e: Exception) {
                Log.w(TAG, "MediaPlayer2 hook error in [$pkg]: ${e.message}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Hook installation failed with unexpected error: ${t.message}")
        }
    }

    private fun copyProviderSettings(context: Context): android.content.SharedPreferences? {
        return try {
            val localPrefs = context.getSharedPreferences("haptic_settings", Context.MODE_PRIVATE)
            val snapshot = context.contentResolver.call(
                CONFIG_PROVIDER_URI, "get_prefs", null,
                android.os.Bundle().apply { putString("target_package", hookedTargetPackage ?: context.packageName) }
            )
                ?: return localPrefs
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
            localPrefs
        } catch (e: Exception) {
            Log.w(TAG, "Config refresh snapshot failed: ${e.message}")
            null
        }
    }

    private fun registerConfigRefreshReceiver(context: Context) {
        if (configReceiverRegistered) return
        synchronized(initLock) {
            if (configReceiverRegistered) return
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action != ACTION_REFRESH_CONFIG) return
                    platformHandler?.post {
                        val refreshed = copyProviderSettings(context)
                        if (refreshed != null) {
                            hapticEngine?.refreshSettings()
                            sendUiLog(context, "Settings refresh applied in ${hookedTargetPackage ?: context.packageName}")
                        }
                    }
                }
            }
            try {
                val filter = android.content.IntentFilter(ACTION_REFRESH_CONFIG)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, CONFIG_SYNC_PERMISSION, platformHandler, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter, CONFIG_SYNC_PERMISSION, platformHandler)
                }
                configReceiverRegistered = true
                Log.i(TAG, "Secure config-refresh receiver registered")
            } catch (e: Exception) {
                Log.w(TAG, "Config refresh receiver registration failed: ${e.message}")
            }
        }
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

            // SharedPreferences obtained through createPackageContext is a per-process
            // cache. It does not reliably observe writes made by the module process on
            // modern Android. Fetch the exported provider snapshot before constructing
            // the engine; the resulting local prefs are refreshed again on every scoped
            // app restart, which is exactly the UI's documented apply boundary.
            val ourPrefs = try {
                // Use storage owned by the hooked app: it is writable under that
                // process UID. The module provider below is the authoritative source.
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
                // Do not derive this from Context: in an Xposed process it can be the system
                // context ("android"), which would bypass per-player calibration.
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
}