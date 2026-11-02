package com.mouya.musichaptics.hook

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.mouya.musichaptics.HapticEngine
import com.mouya.musichaptics.LogBroadcaster
import com.mouya.musichaptics.WhitelistManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class HookCoordinator(
    private val contextProvider: () -> Context?,
    private val targetPackage: String,
    private val whitelist: WhitelistManager
) {
    companion object {
        private const val TAG = "MusicHapticsX-Hook"
        private const val VISUALIZER_DELAY_MS = 1800L
        private const val PRIORITY_WINDOW_MS = 650L
        private const val MAX_WRITE_LOG_INTERVAL_MS = 5000L
        private const val RELEASE_PAUSE_MS = 800L

        private val installedClassLoaders = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())
    }

    private val installed = AtomicBoolean(false)
    private val tracks = TrackRegistry()
    private val thread = HandlerThread("MusicHapticsX-Hook", android.os.Process.THREAD_PRIORITY_DISPLAY)
    private lateinit var handler: Handler
    @Volatile private var engine: HapticEngine? = null
    @Volatile private var attachedContext: Context? = null
    private val engineLock = Any()
    @Volatile private var packageAllowed = false

    private val whitelistRefresh = object : Runnable {
        override fun run() {
            val wasAllowed = packageAllowed
            val allowed = whitelist.isPackageAllowed(targetPackage)
            packageAllowed = allowed
            if (wasAllowed && !allowed) {
                handler.post { engineOrNull()?.onPlaybackPaused() }
            }
            if (thread.isAlive) handler.postDelayed(this, 1000L)
        }
    }
    private var visualizer: Visualizer? = null
    private var lastAudioWriteAtMs = 0L
    private var lastWriteLogAtMs = 0L
    
    @Volatile private var configRefreshReceiver: ConfigRefreshReceiver? = null

    fun install(lpparam: LoadPackageParam) {
        if (lpparam.packageName != targetPackage) return
        if (!whitelist.isPackageAllowed(targetPackage)) {
            Log.i(TAG, "[$targetPackage] skipped by whitelist")
            return
        }
        if (!installed.compareAndSet(false, true)) return
        synchronized(installedClassLoaders) {
            if (installedClassLoaders.containsKey(lpparam.classLoader)) {
                Log.d(TAG, "[$targetPackage] hook already registered for classloader")
                return
            }
            installedClassLoaders[lpparam.classLoader] = true
        }

        thread.start()
        handler = Handler(thread.looper)
        packageAllowed = true

        hookApplicationAttach(lpparam.classLoader)
        hookAudioTrack(lpparam.classLoader)
        hookMediaPlayers(lpparam.classLoader)
        handler.post { initializeEngine() }
        handler.post(whitelistRefresh)

        
        
        registerConfigRefreshWhenPossible()
        val regMsg = "[$targetPackage] hooks registered (context not yet bound)"
        Log.i(TAG, regMsg)
        LogBroadcaster.queue(regMsg)
        contextProvider()?.let { ctx ->
            LogBroadcaster.sendLog(ctx, "Hook ready: $targetPackage")
        }
    }

    private fun initializeEngine(): HapticEngine? {
        engine?.let { return it }
        synchronized(engineLock) {
            engine?.let { return it }
            val context = attachedContext ?: contextProvider() ?: return null
            return try {
                HapticEngine(context.applicationContext, HookConfigPreferences(context.applicationContext, targetPackage), targetPackage)
                    .also { engine = it }
            } catch (t: Throwable) {
                Log.e(TAG, "Engine init failed for $targetPackage", t)
                null
            }
        }
    }

    private fun engineOrNull(): HapticEngine? = engine

    



    private fun registerConfigRefreshWhenPossible() {
        if (configRefreshReceiver != null) return
        val ctx = attachedContext ?: return
        val receiver = ConfigRefreshReceiver.register(ctx) {
            handler.post {
                engineOrNull()?.synchronizeParameters()
                Log.i(TAG, "[cfg] $targetPackage parameters re-synchronized")
            }
        }
        if (receiver != null) configRefreshReceiver = receiver
    }


    private fun hookApplicationAttach(classLoader: ClassLoader) {
        val applicationClass = runCatching { XposedHelpers.findClass("android.app.Application", classLoader) }
            .getOrElse { Application::class.java }

        
        
        
        
        
        
        
        
        val attached = java.util.concurrent.atomic.AtomicBoolean(false)

        fun adopt(context: Context?) {
            val appContext = context?.applicationContext ?: return
            if (!attached.compareAndSet(false, true)) return
            attachedContext = appContext
            Log.i(TAG, "[$targetPackage] context acquired via ${context!!.javaClass.simpleName}")
            LogBroadcaster.flush(appContext)
            registerConfigRefreshWhenPossible()
            handler.post {
                preloadNative(appContext)
                initializeEngine()
            }
        }

        fun hookFirst(name: String, vararg types: Class<*>) {
            val method = runCatching { applicationClass.getDeclaredMethod(name, *types) }.getOrNull()
            if (method == null) {
                Log.d(TAG, "[$targetPackage] Application.$name(${types.joinToString { it.simpleName }}) not present on this ROM")
                return
            }
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        adopt(param.args.firstOrNull { it is Context } as? Context)
                    }
                })
            }.onSuccess {
                Log.i(TAG, "[$targetPackage] hooked Application.$name")
            }.onFailure {
                Log.w(TAG, "[$targetPackage] failed to hook Application.$name: ${it.message}")
            }
        }

        hookFirst("attach", Context::class.java)
        runCatching {
            hookFirst("attach", Context::class.java, Class.forName("android.app.ActivityThread"))
        }.onFailure {
            Log.d(TAG, "[$targetPackage] ActivityThread class unavailable: ${it.message}")
        }
        hookFirst("attachForCreate", Context::class.java)
        hookFirst("attachBaseContext", Context::class.java)

        
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (attached.get()) return
                val app = runCatching {
                    val at = Class.forName("android.app.ActivityThread")
                    val current = at.getDeclaredMethod("currentActivityThread").invoke(null)
                    at.getDeclaredMethod("getApplication").invoke(current) as? Context
                }.getOrNull()
                if (app != null) {
                    adopt(app)
                } else if (thread.isAlive) {
                    handler.postDelayed(this, 500L)
                }
            }
        }, 500L)
    }

    private fun preloadNative(context: Context) {
        runCatching { com.mouya.musichaptics.NativeBridge.preloadLibrary(context.applicationContext) }
            .onFailure { Log.w(TAG, "Native preload failed for $targetPackage: ${it.message}") }
    }

    private fun hookAudioTrack(classLoader: ClassLoader) {
        val audioTrackClass = runCatching {
            XposedHelpers.findClass("android.media.AudioTrack", classLoader)
        }.getOrElse {
            AudioTrack::class.java
        }

        hookConstructors(audioTrackClass)
        hookWriteMethods(audioTrackClass)
        hookLifecycle(audioTrackClass)
        LogBroadcaster.queue("[Hook] AudioTrack hooks installed for $targetPackage")
    }

    private fun hookConstructors(clazz: Class<*>) {
        for (constructor in clazz.declaredConstructors) {
            XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val track = param.thisObject as? AudioTrack ?: return
                    // Register metadata only. The native DSP is configured from
                    // the sample rate attached to each actual PCM write; changing
                    // a global analyser here lets a newly-created, still-silent
                    // AudioTrack overwrite the format of the currently playing one.
                    tracks.register(track)
                }
            })
        }
    }

    private fun hookWriteMethods(clazz: Class<*>) {
        var hookedCount = 0
        for (method in clazz.declaredMethods) {
            if (method.name != "write" || method.parameterTypes.isEmpty()) continue
            if (!isSupportedWriteSignature(method)) continue
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val track = param.thisObject as? AudioTrack ?: return
                    val state = tracks.state(track) ?: return
                    val input = param.args.firstOrNull() as? ByteBuffer ?: run {
                        state.initialByteBufferPosition = -1
                        state.pendingByteSnapshot = null
                        return
                    }
                    // Snapshot the ByteBuffer *before* write() consumes it.
                    //
                    // AudioTrack.write advances the buffer position past the
                    // consumed region, so reading it afterwards yields
                    // remaining()==0 and an empty snapshot. That silent empty
                    // copy is why PCM never reached the DSP even though
                    // "AudioTrack.write result=..." was logged: the hook fired,
                    // but every sample was discarded before hand-off.
                    state.initialByteBufferPosition = input.position()
                    state.pendingByteSnapshot = try {
                        val dup = input.duplicate()
                        val bytes = ByteArray(dup.remaining())
                        dup.get(bytes)
                        bytes
                    } catch (_: Exception) { null }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val track = param.thisObject as? AudioTrack ?: return
                    val state = tracks.state(track) ?: return
                    val result = (param.result as? Int)?.coerceAtLeast(0) ?: 0
                    if (result <= 0) return

                    val now = SystemClock.elapsedRealtime()
                    state.lastPcmAtMs = now
                    state.playing = true
                    state.lastWriteBytes = result
                    lastAudioWriteAtMs = now

                    if (!packageAllowed) return
                    // P1: never run DSP on the host audio thread. Snapshot args
                    // and hand off to the Hook HandlerThread; AudioIngress queues.
                    val snapshot: Any? = when (val input = param.args.firstOrNull()) {
                        is ShortArray -> input.clone()
                        is FloatArray -> input.clone()
                        is ByteArray -> input.clone()
                        // ByteBuffer: use the copy taken in beforeHookedMethod.
                        // Re-reading it here is useless because write() has
                        // already advanced the position to its limit.
                        is ByteBuffer -> state.pendingByteSnapshot
                        else -> null
                    } ?: return
                    val offsetArg = (param.args.getOrNull(1) as? Int) ?: 0
                    // When will the first frame of THIS write be heard? Computed on the
                    // host audio thread, right after write() returned, because that is
                    // where the track's timestamp and queue depth are meaningful.
                    val writtenFrames = when (param.args.firstOrNull()) {
                        is FloatArray, is ShortArray -> result / state.channels.coerceAtLeast(1)
                        is ByteArray, is ByteBuffer -> {
                            val bytes = when (state.encoding) {
                                android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
                                android.media.AudioFormat.ENCODING_PCM_16BIT -> 2
                                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                                android.media.AudioFormat.ENCODING_PCM_32BIT,
                                android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
                                else -> 0
                            }
                            if (bytes > 0) result / (bytes * state.channels.coerceAtLeast(1)) else 0
                        }
                        else -> 0
                    }
                    val audibleNs = runCatching { tracks.audibleNsForWrite(track, state, writtenFrames) }
                        .getOrDefault(0L)
                    handler.post {
                        val target = engineOrNull() ?: return@post
                        target.markHookAudioArrival(now)
                        when (snapshot) {
                            is ShortArray -> {
                                val samples = result.coerceAtMost((snapshot.size - offsetArg).coerceAtLeast(0))
                                // A ShortArray is always 16-bit PCM by construction.
                                target.processPcm16FromHook(
                                    snapshot, offsetArg, samples, state.channels, state.sampleRate,
                                    audibleNs = audibleNs
                                )
                            }
                            is FloatArray -> {
                                // AudioTrack.write(float[]) expresses offset and
                                // transfer count in FLOAT SAMPLES, while the DSP
                                // ingress API consumes FRAME offsets/counts.
                                // Convert explicitly; passing these through made
                                // partial writes and non-zero offsets read the
                                // wrong half of interleaved stereo buffers.
                                val channels = state.channels.coerceIn(1, 8)
                                val startSample = offsetArg.coerceIn(0, snapshot.size)
                                val transferred = result.coerceAtLeast(0)
                                    .coerceAtMost(snapshot.size - startSample)
                                val alignedStart = ((startSample + channels - 1) / channels) * channels
                                val skipped = alignedStart - startSample
                                val usableSamples = (transferred - skipped).coerceAtLeast(0)
                                val frameCount = usableSamples / channels
                                if (frameCount > 0) {
                                    target.processPcmFloatFromHook(
                                        snapshot, alignedStart / channels, frameCount,
                                        channels, state.sampleRate,
                                        audibleNs = audibleNs
                                    )
                                }
                            }
                            is ByteArray -> {
                                // The snapshot always starts at the buffer's
                                // original position, so the offset into it is 0
                                // for ByteBuffer writes and offsetArg otherwise.
                                if (param.args.firstOrNull() is ByteBuffer) {
                                    target.processPcmBytesFromHook(
                                        snapshot, 0,
                                        snapshot.size.coerceAtMost(result),
                                        state.channels, state.encoding, state.sampleRate,
                                        audibleNs = audibleNs
                                    )
                                } else {
                                    target.processPcmBytesFromHook(
                                        snapshot, offsetArg, result,
                                        state.channels, state.encoding, state.sampleRate,
                                        audibleNs = audibleNs
                                    )
                                }
                            }
                        }
                    }

                    if (now - lastWriteLogAtMs >= MAX_WRITE_LOG_INTERVAL_MS) {
                        lastWriteLogAtMs = now
                        val inputType = param.args.firstOrNull()?.javaClass?.simpleName ?: "unknown"
                        val resultUnit = when (param.args.firstOrNull()) {
                            is FloatArray, is ShortArray -> "samples"
                            is ByteArray, is ByteBuffer -> "bytes"
                            else -> "units"
                        }
                        val bytesPerSample = when (state.encoding) {
                            android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
                            android.media.AudioFormat.ENCODING_PCM_16BIT -> 2
                            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                            android.media.AudioFormat.ENCODING_PCM_32BIT,
                            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
                            else -> 0
                        }
                        val acceptedFrames = when (param.args.firstOrNull()) {
                            is FloatArray, is ShortArray -> result / state.channels.coerceAtLeast(1)
                            is ByteArray, is ByteBuffer -> if (bytesPerSample > 0) result / (bytesPerSample * state.channels.coerceAtLeast(1)) else 0
                            else -> 0
                        }
                        val durationMs = if (state.sampleRate > 0) acceptedFrames * 1000L / state.sampleRate else 0L
                        val msg = "[$targetPackage] AudioTrack.write result=$result $resultUnit frames=$acceptedFrames (~${durationMs}ms) input=$inputType sr=${state.sampleRate} ch=${state.channels} enc=${encodingName(state.encoding)}"
                        Log.i(TAG, msg)
                        LogBroadcaster.queue(msg)
                    }
                }
            })
            hookedCount++
        }
        Log.i(TAG, "[$targetPackage] AudioTrack.write: hooked $hookedCount methods")
        if (hookedCount == 0) {
            Log.w(TAG, "[$targetPackage] WARNING: no AudioTrack.write methods hooked!")
        }
        LogBroadcaster.queue("[Hook] AudioTrack.write: hooked $hookedCount methods for $targetPackage")
    }

    private fun hookLifecycle(clazz: Class<*>) {
        fun hook(name: String, after: (AudioTrack, TrackRegistry.TrackState) -> Unit) {
            for (method in clazz.declaredMethods.filter { it.name == name && it.parameterTypes.isEmpty() }) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val track = param.thisObject as? AudioTrack ?: return
                        tracks.state(track)?.let { state -> after(track, state) }
                    }
                })
            }
        }

        hook("play") { _, state ->
            state.playing = true
            handler.post { engineOrNull()?.onPlaybackStarted() }
        }
        hook("pause") { track, state ->
            state.playing = false
            // Pause is not proof of silence: players routinely pause()/flush()
            // the AudioTrack while re-buffering, seeking, or swapping tracks, and
            // reporting every one of those as a playback pause latched the engine
            // into "[PLAYBACK TRULY PAUSED]" while music was still audible (the
            // v5.5.0 trace shows six consecutive pause reports). Only report when
            // no other track keeps playing AND the PCM stream has actually gone
            // quiet, which markCandidateStopped verifies before latching.
            if (tracks.isOnlyTrackStopped(track) && audioWritesHaveStalled()) {
                handler.post { engineOrNull()?.onPlaybackPaused() }
            }
        }
        hook("stop") { track, state ->
            state.playing = false
            if (tracks.isOnlyTrackStopped(track) && audioWritesHaveStalled()) {
                handler.post { engineOrNull()?.onPlaybackPaused() }
            }
        }
        hook("flush") { _, state ->
            state.lastPcmAtMs = 0L
            // flush() rebases the track's frame counters and discards queued audio:
            // restart our written-frame count and drop haptics scheduled for it.
            state.writtenFrames = 0L
            handler.post { engineOrNull()?.onTrackFlushed() }
        }
        hook("release") { track, _ ->
            tracks.remove(track)
            handler.post {
                if (engineOrNull() != null && !audioWritesHaveStalled()) {
                    // Another AudioTrack is still writing: this release was a
                    // track swap, not a real stop.
                    return@post
                }
                engineOrNull()?.onPlaybackPaused()
            }
        }
    }

    /**
     * Whether PCM writes have actually stopped, as opposed to a lifecycle event
     * having been reported on a track whose audio is still audible.
     *
     * [lastAudioWriteAtMs] is updated by the AudioTrack.write hook on every
     * successful write, so a recent timestamp means audio is still flowing and
     * any pause/stop/release arriving now is a transient (re-buffer, seek,
     * track swap) rather than a user-visible stop.
     */
    private fun audioWritesHaveStalled(): Boolean {
        val last = lastAudioWriteAtMs
        // 0 means "no write has been observed since the coordinator started".
        // Treating that as "stalled" made the very first pause/stop/release of
        // a session latch the engine off before any audio had been analysed --
        // visible in the trace as a burst of pause reports right after the
        // AudioTrack was rebuilt at a new sample rate. Unknown is not stopped.
        if (last == 0L) return false
        return SystemClock.elapsedRealtime() - last > RELEASE_PAUSE_MS
    }

    /** Human-readable encoding label so the trace shows what was decoded. */
    private fun encodingName(encoding: Int): String = when (encoding) {
        android.media.AudioFormat.ENCODING_PCM_16BIT -> "pcm16"
        android.media.AudioFormat.ENCODING_PCM_FLOAT -> "float"
        android.media.AudioFormat.ENCODING_PCM_8BIT -> "pcm8"
        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> "pcm24"
        android.media.AudioFormat.ENCODING_PCM_32BIT -> "pcm32"
        else -> "enc$encoding"
    }

    private fun hookMediaPlayers(classLoader: ClassLoader) {
        val candidates = listOf("android.media.MediaPlayer", "android.media.SoundPool")
        for (name in candidates) {
            val clazz = runCatching { XposedHelpers.findClass(name, classLoader) }.getOrNull() ?: continue
            for (method in clazz.declaredMethods) {
                if (method.name !in setOf("start", "play", "pause", "stop", "release")) continue
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        when (method.name) {
                            "start", "play" -> scheduleVisualizerFallback()
                            "pause", "stop", "release" -> {
                                // Same guard as AudioTrack lifecycle: only report
                                // a pause when PCM writes have actually stopped.
                                // MediaPlayer.release() fires on every track change.
                                if (audioWritesHaveStalled()) {
                                    handler.post { engineOrNull()?.onPlaybackPaused() }
                                }
                            }
                        }
                    }
                })
            }
        }
    }

    private fun scheduleVisualizerFallback() {
        handler.postDelayed({
            val idle = SystemClock.elapsedRealtime() - lastAudioWriteAtMs
            val enabled = engineOrNull()?.visualizerFallbackEnabled() ?: true
            if (enabled && idle >= VISUALIZER_DELAY_MS) activateVisualizer()
        }, VISUALIZER_DELAY_MS)
    }

    private fun activateVisualizer() {
        if (visualizer != null) return
        val context = attachedContext ?: contextProvider() ?: return
        val session = tracks.activeSession().takeIf { it != 0 } ?: AudioManager.AUDIO_SESSION_ID_GENERATE
        val viz = try {
            Visualizer(session)
        } catch (t: Throwable) {
            Log.w(TAG, "Visualizer unavailable for $targetPackage: ${t.message}")
            return
        }

        try {
            val range = Visualizer.getCaptureSizeRange()
            viz.captureSize = 512.coerceIn(range[0], range[1])
            val rate = Visualizer.getMaxCaptureRate().coerceAtMost(12000)
            viz.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(
                    visualizer: Visualizer?,
                    waveform: ByteArray?,
                    samplingRateHz: Int
                ) {
                    if (waveform == null || waveform.isEmpty()) return
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAudioWriteAtMs < PRIORITY_WINDOW_MS) return
                    engineOrNull()?.let { engine ->
                        engine.isVisualizerSource = true
                        engine.reconfigure(samplingRateHz.coerceAtLeast(8000), 1)
                        engine.processPcmBytesFromHook(waveform, 0, waveform.size, 1)
                    }
                }
                override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRateHz: Int) = Unit
            }, rate, true, false)
            viz.enabled = true
            visualizer = viz
            LogBroadcaster.sendLog(context, "Visualizer fallback active: $targetPackage")
        } catch (t: Throwable) {
            runCatching { viz.release() }
            Log.w(TAG, "Visualizer setup failed: ${t.message}")
        }
    }

    internal fun engine(): HapticEngine? = engineOrNull() ?: initializeEngine()

    fun shutdown() {
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
        engine?.release()
        engine = null
        runCatching { handler.removeCallbacks(whitelistRefresh) }
        runCatching { thread.quitSafely() }
    }

    private fun isSupportedWriteSignature(method: Method): Boolean {
        val params = method.parameterTypes
        if (params.isEmpty()) return false
        val intType = Int::class.javaPrimitiveType
        val longType = Long::class.javaPrimitiveType
        val first = params[0]
        // Android 12+ adds write(ByteBuffer, int, int, long) where the last
        // argument is a timestamp in nanoseconds. The previous filter only
        // accepted Int-typed trailing parameters, so every modern write call
        // was silently skipped and PCM never reached the DSP.
        val rest = params.drop(1)
        val intsOrLongs = rest.all { it == intType || it == Int::class.java ||
            it == longType || it == Long::class.java }
        if (!intsOrLongs) return false
        return when (first) {
            ByteArray::class.java, ShortArray::class.java -> params.size in 3..4
            FloatArray::class.java -> params.size in 3..4
            ByteBuffer::class.java -> params.size in 3..4
            else -> false
        }
    }

}