package com.mouya.musichaptics.v5

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * v5.0: 重构振动引擎 — 基于 14PRO 哲学
 * 
 * 核心变更：
 * 1. 移除复杂 C++ 五通道 DSP，改用简单 onset + 包络跟随
 * 2. 使用 V5RichTapDriver 底层驱动（纯 Java，无 C++ 依赖）
 * 3. 自适应增益（adaptivePeak/adaptiveFloor）
 * 4. 多模式参数（balanced/bass/crisp/soft/immersive/pure）
 * 5. 单消费者，无竞争
 * 
 * 参考：14PRO_MusicHaptic_KSU_WebUI 的 MusicHapticDaemon 实现
 */
class V5HapticEngine(private val context: Context) {
    
    companion object {
        private const val TAG = "V5HapticEngine"
        
        // 振动模式（与 14PRO 对齐）
        const val MODE_BALANCED = "balanced"
        const val MODE_BASS = "bass"
        const val MODE_CRISP = "crisp"
        const val MODE_SOFT = "soft"
        const val MODE_IMMERSIVE = "immersive"
        const val MODE_PURE = "pure"
        
        // 自适应参数
        const val ADAPTIVE_PEAK_INIT = 28.0
        const val ADAPTIVE_FLOOR_INIT = 2.0
        const val ADAPTIVE_ATTACK = 0.15
        const val ADAPTIVE_RELEASE = 0.05
        
        // Onset 检测参数
        const val ONSET_THRESHOLD = 0.08
    }
    
    // ── 状态 ──
    private val isPlaying = AtomicBoolean(false)
    private val lastPulseTime = AtomicLong(0L)
    private val onsetReference = AtomicReference(0.0)
    private val adaptivePeak = AtomicReference(ADAPTIVE_PEAK_INIT)
    private val adaptiveFloor = AtomicReference(ADAPTIVE_FLOOR_INIT)
    private val lowPass = AtomicReference(0.0)
    private val pulseCount = AtomicLong(0L)
    
    // ── 驱动 ──
    private val driver = V5RichTapDriver(context)
    
    // ── 参数 ──
    @Volatile var mode: String = MODE_BALANCED
        set(value) {
            if (value in setOf(MODE_BALANCED, MODE_BASS, MODE_CRISP, MODE_SOFT, MODE_IMMERSIVE, MODE_PURE)) {
                field = value
                Log.i(TAG, "[Mode] 切换到: $mode")
            }
        }
    
    @Volatile var intensity: Int = 65  // 10-100
    @Volatile var boost: Boolean = false
    @Volatile var threshold: Int = 10  // 1-100
    
    /**
     * 处理音频帧 — 简化版 onset + 包络
     */
    fun processAudioFrame(pcmData: ShortArray, sampleRate: Int, channels: Int) {
        if (pcmData.isEmpty()) return
        
        // 1. 计算 RMS 能量
        val rms = computeRms(pcmData)
        if (rms < 0.001) return
        
        // 2. 低通滤波
        val currentLowPass = lowPass.get()
        val filteredRms = currentLowPass + ADAPTIVE_RELEASE * (rms - currentLowPass)
        lowPass.set(filteredRms)
        
        // 3. Onset 检测
        val ref = onsetReference.get()
        val onset = max(0.0, filteredRms - ref)
        onsetReference.set(filteredRms)
        
        // 4. 自适应增益
        val peak = adaptivePeak.get()
        val floor = adaptiveFloor.get()
        
        adaptivePeak.set(if (filteredRms > peak) {
            peak + ADAPTIVE_ATTACK * (filteredRms - peak)
        } else {
            peak + ADAPTIVE_RELEASE * (filteredRms - peak)
        })
        
        adaptiveFloor.set(if (filteredRms < floor) {
            floor + ADAPTIVE_ATTACK * (filteredRms - floor)
        } else {
            floor + ADAPTIVE_RELEASE * (filteredRms - floor)
        })
        
        // 5. 归一化 onset
        val range = adaptivePeak.get() - adaptiveFloor.get()
        if (range < 0.01) return
        
        val normalizedOnset = (onset - adaptiveFloor.get()) / range
        
        // 6. 触发判断
        if (normalizedOnset > ONSET_THRESHOLD) {
            triggerVibration(normalizedOnset.toFloat())
        }
    }
    
    /**
     * 触发振动
     */
    private fun triggerVibration(envelope: Float) {
        val now = System.currentTimeMillis()
        
        val refractoryMs = driver.getRefractoryMs(mode)
        if (now - lastPulseTime.get() < refractoryMs) return
        
        lastPulseTime.set(now)
        
        val amp = max(0.08f, min(if (boost) 0.98f else 0.84f, 
            (envelope * intensity / 100.0f) * (if (boost) 1.3f else 1.0f)))
        
        driver.triggerPulse(amp, mode, boost)
        
        pulseCount.incrementAndGet()
        if (pulseCount.get() <= 10 || pulseCount.get() % 25 == 0L) {
            Log.i(TAG, "[Pulse] amp=${"%.2f".format(amp)} mode=$mode count=${pulseCount.get()}")
        }
    }
    
    /**
     * 计算 RMS 能量
     */
    private fun computeRms(pcm: ShortArray): Double {
        var sumSquares = 0.0
        for (sample in pcm) {
            val v = sample.toInt()
            sumSquares += v.toDouble() * v.toDouble()
        }
        return Math.sqrt(sumSquares / pcm.size) / 32768.0
    }
    
    fun reset() {
        isPlaying.set(false)
        lastPulseTime.set(0L)
        onsetReference.set(0.0)
        adaptivePeak.set(ADAPTIVE_PEAK_INIT)
        adaptiveFloor.set(ADAPTIVE_FLOOR_INIT)
        lowPass.set(0.0)
        pulseCount.set(0L)
    }
    
    fun setPlaying(playing: Boolean) {
        isPlaying.set(playing)
        if (!playing) reset()
    }
}