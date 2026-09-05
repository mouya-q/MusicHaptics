package com.mouya.musichaptics.v5

import android.content.Context
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.DynamicEffect
import android.os.HapticPlayer
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
 * 2. 使用 Android 原生 DynamicEffect + HapticPlayer（RichTap 底层）
 * 3. 自适应增益（adaptivePeak/adaptiveFloor）
 * 4. 多模式参数（balanced/bass/crisp/soft/immersive/pure）
 * 5. 系统级守护进程模式（root）
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
        const val ONSET_REFRACTORY_MS = 120  // 模式不同值不同
    }
    
    // ── 状态 ──
    private val isPlaying = AtomicBoolean(false)
    private val lastPulseTime = AtomicLong(0L)
    private val onsetReference = AtomicReference(0.0)
    private val previousOnset = AtomicReference(0.0)
    private val adaptivePeak = AtomicReference(ADAPTIVE_PEAK_INIT)
    private val adaptiveFloor = AtomicReference(ADAPTIVE_FLOOR_INIT)
    private val lowPass = AtomicReference(0.0)
    private val pulseCount = AtomicLong(0L)
    
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
    @Volatile var sensitivity: Int = 100  // 10-300
    
    // ── 包络参数（由模式决定）──
    private val modeParams: Map<String, ModeParams> = mapOf(
        MODE_BALANCED to ModeParams(118L, 0.32f, 0.058f, 0.002f),
        MODE_BASS to ModeParams(128L, 0.35f, 0.065f, 0.003f),
        MODE_CRISP to ModeParams(98L, 0.28f, 0.045f, 0.001f),
        MODE_SOFT to ModeParams(142L, 0.25f, 0.070f, 0.004f),
        MODE_IMMERSIVE to ModeParams(108L, 0.38f, 0.080f, 0.002f),
        MODE_PURE to ModeParams(260L, 0.20f, 0.030f, 0.001f)
    )
    
    data class ModeParams(
        val refractoryMs: Long,   // 最小触发间隔
        val sharpness: Float,     // 波形锐度 0-1
        val duration: Float,      // 持续时间秒
        val attack: Float         // 攻击时间秒
    )
    
    /**
     * 处理音频帧 — 简化版 onset + 包络
     * 
     * 与 v4 的复杂五通道不同，这里只做：
     * 1. 计算整体能量（RMS）
     * 2. 低通滤波
     * 3. Onset 检测（能量突变）
     * 4. 自适应增益调整
     * 5. 触发振动
     */
    fun processAudioFrame(pcmData: ShortArray, sampleRate: Int, channels: Int) {
        if (pcmData.isEmpty()) return
        
        // 1. 计算 RMS 能量
        val rms = computeRms(pcmData)
        if (rms < 0.001) return  // 静音门限
        
        // 2. 低通滤波（平滑包络）
        val currentLowPass = lowPass.get()
        val filteredRms = currentLowPass + ADAPTIVE_RELEASE * (rms - currentLowPass)
        lowPass.set(filteredRms)
        
        // 3. Onset 检测（能量突变）
        val ref = onsetReference.get()
        val onset = max(0.0, filteredRms - ref)
        onsetReference.set(filteredRms)
        
        // 4. 自适应增益
        val peak = adaptivePeak.get()
        val floor = adaptiveFloor.get()
        
        // 更新自适应峰值（慢升快降）
        if (filteredRms > peak) {
            adaptivePeak.set(peak + ADAPTIVE_ATTACK * (filteredRms - peak))
        } else {
            adaptivePeak.set(peak + ADAPTIVE_RELEASE * (filteredRms - peak))
        }
        
        // 更新自适应底限
        if (filteredRms < floor) {
            adaptiveFloor.set(floor + ADAPTIVE_ATTACK * (filteredRms - floor))
        } else {
            adaptiveFloor.set(floor + ADAPTIVE_RELEASE * (filteredRms - floor))
        }
        
        // 5. 归一化 onset（相对于自适应范围）
        val range = peak - floor
        if (range < 0.01) return
        
        val normalizedOnset = (onset - floor) / range
        
        // 6. 触发判断
        if (normalizedOnset > ONSET_THRESHOLD) {
            triggerVibration(normalizedOnset.toFloat())
        }
    }
    
    /**
     * 触发振动 — 使用 Android 原生 DynamicEffect + HapticPlayer
     * 
     * 这是 14PRO 的核心：不使用自定义振动波形，
     * 而是调用 Android 原生触觉 API（底层由 RichTap 驱动）
     */
    private fun triggerVibration(envelope: Float) {
        val now = System.currentTimeMillis()
        val params = modeParams[mode] ?: modeParams[MODE_BALANCED]!!
        
        // 间隔检查
        val elapsed = now - lastPulseTime.get()
        if (elapsed < params.refractoryMs) return
        
        lastPulseTime.set(now)
        
        // 计算振幅（与 14PRO 公式对齐）
        val amp = max(0.08f, min(if (boost) 0.98f else 0.84f, 
            (envelope * intensity / 100.0f) * (if (boost) 1.3f else 1.0f)))
        
        try {
            // 使用 Android 原生 DynamicEffect API
            val effect = DynamicEffect.create()
            val duration = params.duration * (if (boost) 1.1f else 1.0f)
            
            // 创建连续波形（与 14PRO 的 createContinuous 对齐）
            val continuous = DynamicEffect.createContinuous(amp, params.sharpness, duration)
            continuous.addParameter(DynamicEffect.createParameter(0, 
                floatArrayOf(0.0f, params.attack, 0.62f * duration, duration),
                floatArrayOf(0.0f, amp, 0.76f * amp, 0.0f)))
            
            effect.addPrimitive(0.0f, continuous)
            
            // 使用 HapticPlayer 播放
            HapticPlayer(effect).start()
            
            pulseCount.incrementAndGet()
            if (pulseCount.get() <= 10 || pulseCount.get() % 25 == 0) {
                Log.i(TAG, "[Pulse] amp=${"%.2f".format(amp)} sharp=${params.sharpness} dur=${"%.3f".format(duration)} mode=$mode count=${pulseCount.get()}")
            }
        } catch (e: Exception) {
            // 降级：使用 transient
            try {
                val effect2 = DynamicEffect.create()
                effect2.addPrimitive(0.0f, DynamicEffect.createTransient(amp, 0.48f))
                HapticPlayer(effect2).start()
            } catch (e2: Exception) {
                Log.w(TAG, "[Pulse] 振动失败: ${e2.message}")
            }
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
    
    /**
     * 重置状态
     */
    fun reset() {
        isPlaying.set(false)
        lastPulseTime.set(0L)
        onsetReference.set(0.0)
        previousOnset.set(0.0)
        adaptivePeak.set(ADAPTIVE_PEAK_INIT)
        adaptiveFloor.set(ADAPTIVE_FLOOR_INIT)
        lowPass.set(0.0)
        pulseCount.set(0L)
    }
    
    /**
     * 设置播放状态
     */
    fun setPlaying(playing: Boolean) {
        isPlaying.set(playing)
        if (!playing) {
            reset()
        }
    }
}