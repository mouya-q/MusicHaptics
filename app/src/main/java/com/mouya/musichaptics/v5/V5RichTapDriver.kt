package com.mouya.musichaptics.v5

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * v5.0: RichTap 驱动适配器 — 桥接 V5HapticEngine 与底层振动 API
 * 
 * 与 v4 的 RichTapAdapter 兼容，但更简洁：
 * 1. 优先使用 Android 原生 VibrationEffect（与 14PRO 的 DynamicEffect 哲学对齐）
 * 2. 保留 RichTap 的脉冲触发逻辑
 * 3. 支持多种模式参数
 * 4. 无 C++ DSP 依赖，纯 Java 层振动
 */
class V5RichTapDriver(private val context: Context) {
    
    companion object {
        private const val TAG = "V5RichTapDriver"
    }
    
    private val vibrator: Vibrator? = try {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Exception) { null }
    
    private val hasVibrator = vibrator?.hasVibrator() ?: false
    private val hasAmplitudeControl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        try { vibrator?.hasAmplitudeControl() ?: false } catch (e: Exception) { false }
    } else false
    
    fun triggerPulse(amplitude: Float, mode: String, boost: Boolean) {
        if (!hasVibrator || vibrator == null) return
        
        val params = getModeParams(mode, boost)
        val dur = params.durationMs
        val amp = if (hasAmplitudeControl) {
            (amplitude * 255).toInt().coerceIn(1, 255)
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }
        
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(dur, amp))
            Log.d(TAG, "[Pulse] amp=$amp dur=${dur}ms mode=$mode")
        } catch (e: Exception) {
            Log.w(TAG, "[Pulse] 失败: ${e.message}")
        }
    }
    
    fun getRefractoryMs(mode: String): Long = when (mode) {
        "pure" -> 260L
        "crisp" -> 98L
        "immersive" -> 108L
        "bass" -> 128L
        "soft" -> 142L
        else -> 118L  // balanced
    }
    
    private fun getModeParams(mode: String, boost: Boolean): PulseParams {
        val multiplier = if (boost) 1.3f else 1.0f
        return when (mode) {
            "pure" -> PulseParams((260L * multiplier).toLong(), 0.8f)
            "crisp" -> PulseParams((98L * multiplier).toLong(), 0.7f)
            "immersive" -> PulseParams((108L * multiplier).toLong(), 0.9f)
            "bass" -> PulseParams((128L * multiplier).toLong(), 0.85f)
            "soft" -> PulseParams((142L * multiplier).toLong(), 0.6f)
            else -> PulseParams((118L * multiplier).toLong(), 0.75f)  // balanced
        }
    }
    
    data class PulseParams(val durationMs: Long, val amplitudeMultiplier: Float)
}