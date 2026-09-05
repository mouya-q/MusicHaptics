package com.mouya.musichaptics.v5

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * v5.0: MainHook — 基于 14PRO 哲学的系统级重构
 * 
 * 架构变更：
 * 1. 不再 Hook 每个应用的 AudioTrack.write()
 * 2. 改为 Hook system_server (android) 全局音频状态 + Visualizer(0) 全局捕获
 * 3. 白名单过滤（仅白名单应用触发）
 * 4. 简化 DSP → V5HapticEngine（单消费者，无竞争）
 * 5. 移除 C++ 五通道 DSP 依赖
 * 
 * 参考：14PRO MusicHapticDaemon 架构
 */
class V5MainHook : IXposedHookLoadPackage {
    
    companion object {
        private const val TAG = "V5MainHook"
        
        // 全局单例（跨进程）
        @Volatile private var v5Engine: V5HapticEngine? = null
        @Volatile private var audioCapture: V5AudioCapture? = null
        private val whitelistManager by lazy { WhitelistManager() }
        
        // 状态
        private val isPlaying = AtomicBoolean(false)
        private val lastPcmTime = AtomicLong(0L)
        private val activeTracks = ConcurrentHashMap<Int, TrackInfo>()
        
        // 配置
        private const val ACTION_LOG = "com.mouya.musichaptics.ACTION_LOG"
        private const val ACTION_REFRESH_CONFIG = "com.mouya.musichaptics.ACTION_REFRESH_CONFIG"
        private const val CONFIG_SYNC_PERMISSION = "com.mouya.musichaptics.permission.CONFIG_SYNC"
        private const val VISUALIZER_FALLBACK_DELAY_MS = 3000L
        
        data class TrackInfo(
            val sampleRate: Int,
            val channelCount: Int,
            val isOffloaded: Boolean,
            var lastActivityMs: Long = System.currentTimeMillis()
        )
    }
    
    private var platformThread: HandlerThread? = null
    private var platformHandler: Handler? = null
    private var configReceiver: BroadcastReceiver? = null
    @Volatile private var hookedPackage: String? = null
    @Volatile private var visualizerActive = false
    
    private fun sendUiLog(context: Context, msg: String) {
        try {
            val intent = Intent(ACTION_LOG).apply {
                setPackage("com.mouya.musichaptics")
                putExtra("log_msg", "[V5] $msg")
            }
            context.sendBroadcast(intent)
        } catch (_: Exception) {}
    }
    
    private fun getContext(): Context? {
        return try {
            val atClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            XposedHelpers.callStaticMethod(atClass, "currentApplication") as? Context
        } catch (e: Exception) { null }
    }
    
    private fun ensureEngine(): V5HapticEngine? {
        if (v5Engine != null) return v5Engine
        val ctx = getContext() ?: return null
        v5Engine = V5HapticEngine(ctx).also {
            Log.i(TAG, "[Engine] V5HapticEngine 初始化完成")
            sendUiLog(ctx, "[V5] 引擎初始化完成")
        }
        return v5Engine
    }
    
    private fun ensureCapture(): V5AudioCapture? {
        if (audioCapture != null) return audioCapture
        val ctx = getContext() ?: return null
        audioCapture = V5AudioCapture(ctx).apply {
            onAudioData = { pcm, sr, ch ->
                ensureEngine()?.processAudioFrame(pcm, sr, ch)
            }
        }
        return audioCapture
    }
    
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        if (lpparam == null) return
        
        val pkg = lpparam.packageName
        
        // 跳过自身
        if (pkg == "com.mouya.musichaptics") return
        
        // ── 系统级 Hook：监听全局音频状态 ──
        if (pkg == "android" || pkg == "system_server") {
            hookSystemAudio(lpparam)
            return
        }
        
        // ── 白名单过滤 ──
        if (!whitelistManager.isPackageAllowed(pkg)) {
            Log.d(TAG, "[Whitelist] 跳过: $pkg")
            return
        }
        
        // ── 白名单应用：Hook AudioTrack ──
        hookAudioTrack(lpparam)
    }
    
    /**
     * Hook system_server — 监听全局音频播放状态
     */
    private fun hookSystemAudio(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook AudioService 检测播放状态变化
            val audioServiceClass = XposedHelpers.findClass(
                "com.android.server.audio.AudioService", lpparam.classLoader
            )
            
            // 检测播放开始
            XposedBridge.hookAllMethods(audioServiceClass, "playAudioProvider",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        isPlaying.set(true)
                        ensureEngine()?.setPlaying(true)
                        Log.d(TAG, "[System] 播放开始")
                    }
                })
            
            // 检测播放暂停
            XposedBridge.hookAllMethods(audioServiceClass, "pauseAudioProvider",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        isPlaying.set(false)
                        ensureEngine()?.setPlaying(false)
                        Log.d(TAG, "[System] 播放暂停")
                    }
                })
            
            Log.i(TAG, "[System] AudioService Hook 成功")
        } catch (e: Exception) {
            Log.w(TAG, "[System] AudioService Hook 失败: ${e.message}")
        }
    }
    
    /**
     * Hook AudioTrack — 仅白名单应用
     */
    private fun hookAudioTrack(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        hookedPackage = pkg
        
        // 初始化平台线程
        if (platformThread == null || platformThread?.isAlive == false) {
            platformThread = HandlerThread("V5-Haptic-Worker", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply {
                start()
                platformHandler = Handler(looper)
            }
        }
        
        try {
            val audioTrackClass = XposedHelpers.findClass("android.media.AudioTrack", lpparam.classLoader)
            
            // Hook 构造器
            XposedBridge.hookAllConstructors(audioTrackClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    if (param?.thisObject == null) return
                    val trackId = System.identityHashCode(param.thisObject)
                    val sr = try { XposedHelpers.callMethod(param.thisObject, "getSampleRate") as Int } catch (e: Exception) { 44100 }
                    val ch = try { XposedHelpers.callMethod(param.thisObject, "getChannelCount") as Int } catch (e: Exception) { 2 }
                    val offloaded = try {
                        val m = audioTrackClass.getMethod("isOffloadedPlayback")
                        m.invoke(param.thisObject) as Boolean
                    } catch (e: Exception) { false }
                    
                    activeTracks[trackId] = TrackInfo(sr, ch, offloaded)
                    Log.i(TAG, "[Track] 创建: $pkg sr=$sr ch=$ch offloaded=$offloaded")
                    
                    // 如果是 offloaded，启动 Visualizer 备用
                    if (offloaded) {
                        platformHandler?.postDelayed({
                            if (!visualizerActive) {
                                activateVisualizerFallback(sr, ch)
                            }
                        }, VISUALIZER_FALLBACK_DELAY_MS)
                    }
                }
            })
            
            // Hook write() — 获取 PCM 数据
            XposedBridge.hookAllMethods(audioTrackClass, "write",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param == null || param.args.isEmpty()) return
                        
                        val pcm = extractPcm(param.args) ?: return
                        val sr = try { XposedHelpers.callMethod(param.thisObject, "getSampleRate") as Int } catch (e: Exception) { 44100 }
                        val ch = try { XposedHelpers.callMethod(param.thisObject, "getChannelCount") as Int } catch (e: Exception) { 2 }
                        
                        lastPcmTime.set(System.currentTimeMillis())
                        
                        val trackId = System.identityHashCode(param.thisObject)
                        activeTracks[trackId]?.lastActivityMs = System.currentTimeMillis()
                        
                        ensureEngine()?.setPlaying(true)
                        ensureEngine()?.processAudioFrame(pcm, sr, ch)
                    }
                })
            
            // Hook play/pause/stop/release
            for (method in listOf("play", "pause", "stop", "release")) {
                XposedBridge.hookAllMethods(audioTrackClass, method,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam?) {
                            when (method) {
                                "play" -> {
                                    isPlaying.set(true)
                                    ensureEngine()?.setPlaying(true)
                                }
                                "pause", "stop", "release" -> {
                                    val trackId = System.identityHashCode(param.thisObject)
                                    activeTracks.remove(trackId)
                                    if (activeTracks.isEmpty()) {
                                        isPlaying.set(false)
                                        ensureEngine()?.setPlaying(false)
                                    }
                                }
                            }
                        }
                    })
            }
            
            // Hook MediaPlayer
            try {
                val mediaPlayerClass = XposedHelpers.findClass("android.media.MediaPlayer", lpparam.classLoader)
                for (mpMethod in listOf("start", "pause", "stop", "release")) {
                    XposedBridge.hookAllMethods(mediaPlayerClass, mpMethod,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam?) {
                                when (mpMethod) {
                                    "start" -> {
                                        isPlaying.set(true)
                                        ensureEngine()?.setPlaying(true)
                                        platformHandler?.postDelayed({
                                            if (!visualizerActive) activateVisualizerFallback(44100, 2)
                                        }, VISUALIZER_FALLBACK_DELAY_MS)
                                    }
                                    "pause", "stop", "release" -> {
                                        isPlaying.set(false)
                                        ensureEngine()?.setPlaying(false)
                                    }
                                }
                            }
                        })
                }
            } catch (_: ClassNotFoundException) {}
            
            Log.i(TAG, "[Hook] AudioTrack Hook 成功: $pkg")
            getContext()?.let { sendUiLog(it, "[V5] Hook 成功: $pkg") }
            
        } catch (e: Exception) {
            Log.e(TAG, "[Hook] 失败: $pkg - ${e.message}")
        }
    }
    
    /**
     * 从 Hook 参数提取 PCM 数据
     */
    private fun extractPcm(args: Array<Any?>): ShortArray? {
        val buffer = args[0] ?: return null
        return when (buffer) {
            is ShortArray -> buffer
            is ByteArray -> {
                if (buffer.size < 2) null else {
                    ShortArray(buffer.size / 2).also {
                        ByteBuffer.wrap(buffer).order(ByteOrder.nativeOrder()).asShortBuffer().get(it)
                    }
                }
            }
            is ByteBuffer -> {
                val remaining = buffer.remaining()
                if (remaining < 2) null else {
                    ShortArray(remaining / 2).also {
                        buffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer().get(it)
                    }
                }
            }
            else -> null
        }
    }
    
    /**
     * 激活 Visualizer 备用捕获
     */
    private fun activateVisualizerFallback(sampleRate: Int, channels: Int) {
        if (visualizerActive) return
        try {
            val capture = ensureCapture() ?: return
            capture.start()
            visualizerActive = true
            Log.i(TAG, "[Capture] Visualizer 备用已激活")
        } catch (e: Exception) {
            Log.w(TAG, "[Capture] Visualizer 激活失败: ${e.message}")
        }
    }
}