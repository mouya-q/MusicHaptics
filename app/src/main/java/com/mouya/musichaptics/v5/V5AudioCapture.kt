package com.mouya.musichaptics.v5

import android.content.Context
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * v5.0: 全局音频捕获 — 基于 14PRO 的 Visualizer 方案
 * 
 * 与 v4 的 AudioTrack.write() Hook 不同，v5 使用全局 Visualizer (session 0)
 * 捕获所有音频输出，无需 Hook 每个应用的 AudioTrack。
 * 
 * 优势：
 * 1. 全局覆盖 — 所有应用音频都被捕获
 * 2. 不依赖 AudioTrack.write() 的 Hook 稳定性
 * 3. 与 14PRO 相同的"监听全局输出"哲学
 * 4. 白名单在 DSP 层过滤，不在捕获层
 */
class V5AudioCapture(private val context: Context) {
    
    companion object {
        private const val TAG = "V5AudioCapture"
        private const val CAPTURE_SIZE = 1024
        private const val MIN_SIGNAL_THRESHOLD = 256
    }
    
    private var visualizer: Visualizer? = null
    private val isCapturing = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    
    // 音频数据回调
    var onAudioData: ((ShortArray, Int, Int) -> Unit)? = null
    
    /**
     * 启动全局音频捕获
     */
    fun start() {
        if (isCapturing.get()) return
        
        try {
            // 创建 Visualizer(0) — 全局输出混合
            visualizer = Visualizer(0).apply {
                captureSize = CAPTURE_SIZE
                
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRateHz: Int
                    ) {
                        waveform?.let { processWaveform(it, samplingRateHz) }
                    }
                    
                    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRateHz: Int) {
                        // v5 不使用 FFT，仅用时域波形
                    }
                }, Visualizer.getMaxCaptureRate(), true, false)
                
                enabled = true
            }
            
            isCapturing.set(true)
            Log.i(TAG, "[Capture] 全局音频捕获已启动 (session 0)")
        } catch (e: SecurityException) {
            Log.w(TAG, "[Capture] Visualizer 权限被拒绝: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "[Capture] Visualizer 创建失败: ${e.message}")
        }
    }
    
    /**
     * 停止捕获
     */
    fun stop() {
        if (!isCapturing.get()) return
        
        try {
            visualizer?.apply {
                enabled = false
                release()
            }
            visualizer = null
            isCapturing.set(false)
            Log.i(TAG, "[Capture] 全局音频捕获已停止")
        } catch (e: Exception) {
            Log.w(TAG, "[Capture] Visualizer 释放失败: ${e.message}")
        }
    }
    
    /**
     * 处理波形数据
     */
    private fun processWaveform(waveform: ByteArray, sampleRate: Int) {
        // 转换为 PCM ShortArray
        val pcm = ShortArray(waveform.size)
        var hasSignal = false
        
        for (i in waveform.indices) {
            val unsigned = waveform[i].toInt() and 0xFF
            val centered = unsigned - 128
            pcm[i] = (centered * 512).toShort()
            if (abs(pcm[i].toInt()) > MIN_SIGNAL_THRESHOLD) {
                hasSignal = true
            }
        }
        
        if (!hasSignal) return
        
        // 回调
        onAudioData?.invoke(pcm, sampleRate, 1)
    }
    
    /**
     * 检查是否正在捕获
     */
    fun isCapturing(): Boolean = isCapturing.get()
    
    /**
     * 释放资源
     */
    fun release() {
        stop()
    }
}