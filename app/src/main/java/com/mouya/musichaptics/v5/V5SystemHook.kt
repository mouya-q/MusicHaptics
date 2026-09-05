package com.mouya.musichaptics.v5

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
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
import kotlin.math.sqrt

/**
 * v5.0: 系统级 Hook — 基于 14PRO 哲学
 * 
 * 核心变更：
 * 1. 不再 Hook 每个应用的 AudioTrack.write()
 * 2. 改为 Hook system_server 进程的 AudioFlinger/AudioPolicyService
 * 3. 使用 Visualizer 全局捕获（session 0）
 * 4. 白名单过滤
 * 5. 简化 DSP → V5HapticEngine
 * 
 * 参考：14PRO 的 dumpsys media_session 全局监听 + Visualizer 方案
 */
class V5SystemHook : IXposedHookLoadPackage {
    
    companion object {
        private const val TAG = "V5SystemHook"
        
        // 全局单例（跨进程共享）
        @Volatile private var v5Engine: V5HapticEngine? = null
        private val whitelistManager by lazy { WhitelistManager() }
        
        // 音频状态
        private val isPlaying = AtomicBoolean(false)
        private val lastPcmTime = AtomicLong(0L)
        private val activeSessions = ConcurrentHashMap<Int, SessionInfo>()
        
        data class SessionInfo(
            val packageName: String,
            val sessionId: Int,
            val sampleRate: Int,
            val channelCount: Int,
            var lastActivityMs: Long = System.currentTimeMillis()
        )
    }
    
    private var visualizer: Visualizer? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        if (lpparam == null) return
        
        val pkg = lpparam.packageName
        
        // 只 Hook system_server 和目标应用
        if (pkg == "android" || pkg == "system_server") {
            hookSystemServer(lpparam)
            return
        }
        
        // 白名单检查
        if (!whitelistManager.isPackageAllowed(pkg)) {
            return
        }
        
        // Hook 目标应用的 AudioTrack
        hookAudioTrack(lpparam)
    }
    
    /**
     * Hook system_server — 监听全局音频状态
     */
    private fun hookSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook AudioService 获取播放状态
            val audioServiceClass = XposedHelpers.findClass(
                "com.android.server.audio.AudioService", lpparam.classLoader
            )
            
            // Hook playAudioProvider 检测播放开始
            XposedBridge.hookAllMethods(audioServiceClass, "playAudioProvider", 
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        isPlaying.set(true)
                        Log.d(TAG, "[System] playAudioProvider called")
                    }
                })
            
            // Hook pauseAudioProvider 检测播放暂停
            XposedBridge.hookAllMethods(audioServiceClass, "pauseAudioProvider",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        isPlaying.set(false)
                        v5Engine?.setPlaying(false)
                        Log.d(TAG, "[System] pauseAudioProvider called")
                    }
                })
            
            Log.i(TAG, "[System] AudioService hooked in system_server")
        } catch (e: Exception) {
            Log.w(TAG, "[System] AudioService hook failed: ${e.message}")
        }
    }
    
    /**
     * Hook AudioTrack — 仅针对白名单应用
     */
    private fun hookAudioTrack(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        
        try {
            val audioTrackClass = XposedHelpers.findClass(
                "android.media.AudioTrack", lpparam.classLoader
            )
            
            // Hook write() 获取 PCM 数据
            XposedBridge.hookAllMethods(audioTrackClass, "write",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param == null || param.args.isEmpty()) return
                        
                        val pcmData = extractPcmData(param.args) ?: return
                        val sampleRate = getSampleRate(param.thisObject)
                        val channels = getChannelCount(param.thisObject)
                        
                        lastPcmTime.set(System.currentTimeMillis())
                        
                        // 初始化引擎
                        if (v5Engine == null) {
                            val ctx = getContext() ?: return
                            v5Engine = V5HapticEngine(ctx)
                        }
                        
                        v5Engine?.setPlaying(true)
                        v5Engine?.processAudioFrame(pcmData, sampleRate, channels)
                    }
                })
            
            // Hook play/pause/stop
            for (method in listOf("play", "pause", "stop", "release")) {
                XposedBridge.hookAllMethods(audioTrackClass, method,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam?) {
                            when (method) {
                                "play" -> {
                                    isPlaying.set(true)
                                    v5Engine?.setPlaying(true)
                                }
                                "pause", "stop", "release" -> {
                                    isPlaying.set(false)
                                    v5Engine?.setPlaying(false)
                                }
                            }
                        }
                    })
            }
            
            Log.i(TAG, "[Hook] AudioTrack hooked for $pkg")
        } catch (e: Exception) {
            Log.w(TAG, "[Hook] AudioTrack hook failed for $pkg: ${e.message}")
        }
    }
    
    /**
     * 从 Hook 参数提取 PCM 数据
     */
    private fun extractPcmData(args: Array<Any?>): ShortArray? {
        val buffer = args[0] ?: return null
        
        return when (buffer) {
            is ShortArray -> {
                if (buffer.isEmpty()) null else buffer
            }
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
    
    private fun getSampleRate(track: Any): Int {
        return try { XposedHelpers.callMethod(track, "getSampleRate") as Int } catch (e: Exception) { 44100 }
    }
    
    private fun getChannelCount(track: Any): Int {
        return try { XposedHelpers.callMethod(track, "getChannelCount") as Int } catch (e: Exception) { 2 }
    }
    
    private fun getContext(): Context? {
        return try {
            val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication") as? Context
        } catch (e: Exception) { null }
    }
}