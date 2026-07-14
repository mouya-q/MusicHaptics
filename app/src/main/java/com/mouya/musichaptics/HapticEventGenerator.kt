package com.mouya.musichaptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.*

/**
 * iPhone Music Haptics 风格的触觉事件生成器。
 *
 * 将 DSP 管线输出的三频段强度 + 音高信息映射为 Android VibrationEffect，
 * 不再直接操作 AW8697 RTP sysfs。Android VibratorService 会自动通过
 * Vibrator HAL → AW8697 kernel driver → LRA 完成硬件驱动。
 *
 * 设计目标：
 * - 低频（sub-bass）→ 深沉、持续的低频振动（类似 iPhone Taptic Engine 的"重击"）
 * - 中频（mid-bass）→ 有节奏的中频敲击（类似鼓点反馈）
 * - 高频（presence）→ 细腻的"纹理"微振动（类似弦乐/擦片质感）
 * - 音高（pitch）→ 动态调整振动事件的时间间隔
 */
class HapticEventGenerator(context: Context) {

    companion object {
        private const val TAG = "HapticEventGen"

        /** 最小振动幅度 (VibrationEffect 的 amplitude 范围 1-255，0=不振动) */
        private const val MIN_AMPLITUDE = 1

        /** 最大振动幅度 */
        private const val MAX_AMPLITUDE = 255

        /** 静默阈值：低于此强度的信号将被忽略 */
        private const val SILENCE_THRESHOLD = 0.02f

        /** Composition 中单个基元的最大时长（ms），防止单个事件过长导致延迟堆积 */
        private const val MAX_PRIMITIVE_DURATION_MS = 50

        /** 事件生成间隔上限（ms），对应最低可听频率 ~35 Hz */
        private const val MAX_EVENT_INTERVAL_MS = 28

        /** 事件生成间隔下限（ms），对应 ~300 Hz */
        private const val MIN_EVENT_INTERVAL_MS = 3

        /** 低频带权重（重击感） */
        private const val SUB_WEIGHT = 0.7f

        /** 中频带权重（节奏感） */
        private const val MID_WEIGHT = 0.4f

        /** 高频带权重（纹理感） */
        private const val PRESENCE_WEIGHT = 0.25f
    }

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    /** 是否有硬件振动器 */
    val hasVibrator: Boolean = vibrator.hasVibrator()

    /** 是否支持振幅控制 */
    private val hasAmplitudeControl: Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                vibrator.hasAmplitudeControl()
            } catch (e: Exception) {
                false
            }
        } else false

    /** 上一帧的时间戳，用于节流 */
    private var lastEventTimeMs = 0L

    /** 事件计数器，用于周期性日志 */
    private var eventCounter = 0L

    /** 主开关 */
    @Volatile
    var isEnabled = true

    /**
     * 将 DSP 输出转化为触觉事件并播放。
     *
     * @param sub       Sub-bass 频段强度 (0.0 .. ~2.0，经 DRC 后)
     * @param mid       Mid-bass 频段强度
     * @param presence  Presence 频段强度
     * @param pitch     检测到的基频（Hz），用于动态调整事件间隔
     * @param currentTimeMs 当前时间戳 (SystemClock.elapsedRealtime)
     */
    fun generateAndPlay(
        sub: Float,
        mid: Float,
        presence: Float,
        pitch: Float,
        currentTimeMs: Long
    ) {
        if (!isEnabled || !hasVibrator) return

        // 加权合并为总强度
        val blendedIntensity = (sub * SUB_WEIGHT) + (mid * MID_WEIGHT) + (presence * PRESENCE_WEIGHT)

        // 静默跳过
        if (blendedIntensity < SILENCE_THRESHOLD) return

        // 基于音高计算事件间隔（动态速率）
        val eventIntervalMs = if (pitch > 0f && pitch < 500f) {
            (1000f / pitch).coerceIn(
                MIN_EVENT_INTERVAL_MS.toFloat(),
                MAX_EVENT_INTERVAL_MS.toFloat()
            ).toLong()
        } else {
            MAX_EVENT_INTERVAL_MS.toLong()
        }

        // 节流：避免在短时间内生成过多事件
        if (currentTimeMs - lastEventTimeMs < eventIntervalMs) return
        lastEventTimeMs = currentTimeMs

        // 将强度映射为振动幅度 (1-255)
        val targetAmplitude = if (hasAmplitudeControl) {
            (blendedIntensity * MAX_AMPLITUDE).toInt().coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE)
        } else {
            // 无振幅控制时回退到默认强度（255 = 全振幅）
            VibrationEffect.DEFAULT_AMPLITUDE
        }

        // 根据频段特征选择振动模式
        val isSubDominant = (sub * SUB_WEIGHT) > (mid * MID_WEIGHT) &&
                (sub * SUB_WEIGHT) > (presence * PRESENCE_WEIGHT)
        val isPresenceDominant = (presence * PRESENCE_WEIGHT) > (sub * SUB_WEIGHT) &&
                (presence * PRESENCE_WEIGHT) > (mid * MID_WEIGHT)

        try {
            when {
                // 高频主导 → 细腻纹理：极短脉冲
                isPresenceDominant && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    val effect = VibrationEffect.createOneShot(
                        MAX_PRIMITIVE_DURATION_MS.coerceAtMost(15).toLong(),
                        targetAmplitude
                    )
                    vibrator.vibrate(effect)
                }
                // 低频主导 → 沉重打击：较长但可控的脉冲
                isSubDominant && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    val durationMs = (blendedIntensity * MAX_PRIMITIVE_DURATION_MS).toLong()
                        .coerceIn(10, MAX_PRIMITIVE_DURATION_MS.toLong())
                    val effect = VibrationEffect.createOneShot(durationMs, targetAmplitude)
                    vibrator.vibrate(effect)
                }
                // 中频主导 / 混合 → 标准一击
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    val effect = VibrationEffect.createOneShot(
                        MAX_PRIMITIVE_DURATION_MS.coerceAtMost(25).toLong(),
                        targetAmplitude
                    )
                    vibrator.vibrate(effect)
                }
                // API < 26 回退：仅时长振动
                else -> {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(MAX_PRIMITIVE_DURATION_MS.coerceAtMost(25).toLong())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration effect failed: ${e.message}")
        }

        // 周期性日志
        eventCounter++
        if (eventCounter % 60L == 0L) {
            Log.d(
                TAG,
                "Haptic event #$eventCounter | blend=$blendedIntensity | amp=$targetAmplitude | " +
                "pitch=$pitch Hz | interval=${eventIntervalMs}ms | " +
                "sub-dom=$isSubDominant pres-dom=$isPresenceDominant"
            )
        }
    }

    /**
     * 立即停止所有振动并重置状态。
     */
    fun cancel() {
        try {
            vibrator.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Cancel vibration failed: ${e.message}")
        }
        lastEventTimeMs = 0L
    }

    /**
     * 释放资源。
     */
    fun release() {
        cancel()
        eventCounter = 0L
    }
}
