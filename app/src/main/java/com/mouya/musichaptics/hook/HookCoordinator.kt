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
        Log.i(TAG, "[$targetPackage] hooks registered (context not yet bound)")
        contextProvider()?.let { LogBroadcaster.sendLog(it, "Hook ready: $targetPackage") }
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
    }

    private fun hookConstructors(clazz: Class<*>) {
        for (constructor in clazz.declaredConstructors) {
            XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val track = param.thisObject as? AudioTrack ?: return
                    val state = tracks.register(track)
                    handler.post { engineOrNull()?.reconfigure(state.sampleRate, state.channels) }
                }
            })
        }
    }

    private fun hookWriteMethods(clazz: Class<*>) {
        for (method in clazz.declaredMethods) {
            if (method.name != "write" || method.parameterTypes.isEmpty()) continue
            if (!isSupportedWriteSignature(method)) continue
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val state = tracks.state(param.thisObject as? AudioTrack ?: return) ?: return
                    state.initialByteBufferPosition = (param.args.firstOrNull() as? ByteBuffer)?.position() ?: -1
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
                    val target = engineOrNull() ?: return
                    target.markHookAudioArrival(now)
                    when (val input = param.args.firstOrNull()) {
                        is ShortArray -> {
                            val offset = (param.args.getOrNull(1) as? Int) ?: 0
                            val samples = result.coerceAtMost((input.size - offset).coerceAtLeast(0))
                            target.processPcm16FromHook(input, offset, samples, state.channels)
                        }
                        is FloatArray -> {
                            val offset = (param.args.getOrNull(1) as? Int) ?: 0
                            target.processPcmFloatFromHook(input, offset, result, state.channels)
                        }
                        is ByteArray -> {
                            val offset = (param.args.getOrNull(1) as? Int) ?: 0
                            target.processPcmBytesFromHook(input, offset, result, state.channels)
                        }
                        is ByteBuffer -> {
                            val start = state.initialByteBufferPosition.takeIf { it >= 0 } ?: 0
                            target.processPcmBufferFromHook(input, start, result, state.channels)
                        }
                    }

                    if (now - lastWriteLogAtMs >= MAX_WRITE_LOG_INTERVAL_MS) {
                        lastWriteLogAtMs = now
                        Log.d(TAG, "[$targetPackage] AudioTrack.write result=$result sr=${state.sampleRate} ch=${state.channels}")
                    }
                }
            })
        }
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
            if (tracks.isOnlyTrackStopped(track)) handler.post { engineOrNull()?.onPlaybackPaused() }
        }
        hook("stop") { track, state ->
            state.playing = false
            if (tracks.isOnlyTrackStopped(track)) handler.post { engineOrNull()?.onPlaybackPaused() }
        }
        hook("flush") { _, state -> state.lastPcmAtMs = 0L }
        hook("release") { track, _ ->
            tracks.remove(track)
            handler.post {
                if (engineOrNull() != null && SystemClock.elapsedRealtime() - lastAudioWriteAtMs > 500L) {
                    engineOrNull()?.onPlaybackPaused()
                }
            }
        }
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
                            "pause", "stop", "release" -> handler.post { engineOrNull()?.onPlaybackPaused() }
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
        val first = params[0]
        val ints = params.drop(1).all { it == intType || it == Int::class.java }
        if (!ints) return false
        return when (first) {
            ByteArray::class.java, ShortArray::class.java -> params.size in 3..4
            FloatArray::class.java -> params.size == 4
            ByteBuffer::class.java -> params.size == 3
            else -> false
        }
    }

}