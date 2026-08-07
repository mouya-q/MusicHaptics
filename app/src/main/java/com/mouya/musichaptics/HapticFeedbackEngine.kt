package com.mouya.musichaptics

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 触觉反馈引擎 — 基于系统预定义效果
 *
 * 设计原则：
 * - 优先使用 VibrationEffect.createPredefined()（API 29+）
 *   这是厂商针对设备 LRA 硬件优化的系统级触觉，效果最接近 iOS Taptic Engine
 * - 复合序列（如"噔噔噔↘"）通过 Handler.postDelayed 依次触发独立预定义效果，
 *   而非依赖 createWaveform 的振幅包络（振幅控制在很多设备上不可靠或被忽略）
 * - API 28 fallback: 使用 createOneShot(不同时长) 模拟层级差异
 *
 * 不使用 createWaveform(timings, amplitudes) 的原因：
 * 1. hasAmplitudeControl() 在很多设备返回 false，amplitude 值被完全丢弃
 * 2. 即使返回 true，Android Vibrator API 的振幅控制为 ERM 设计，LRA 感知差异极小
 * 3. 预定义效果由厂商针对具体硬件调校，远优于手动振幅包络
 */
class HapticFeedbackEngine private constructor(
    private val vibrator: Vibrator?,
    private val isApi29Plus: Boolean
) {
    companion object {
        private const val TAG = "HapticFeedbackEngine"

        private fun resolveVibrator(context: Context): Vibrator? {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
            } catch (e: Exception) {
                Log.e(TAG, "Vibrator resolution failed: ${e.message}")
                null
            }
        }

        fun create(context: Context): HapticFeedbackEngine {
            val vib = resolveVibrator(context)
            return HapticFeedbackEngine(
                vibrator = vib,
                isApi29Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            )
        }
    }

    val hasVibrator: Boolean get() = vibrator?.hasVibrator() ?: false

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 触觉反馈风格
     *
     * LIGHT_TICK  → EFFECT_TICK        (滑块微调、细微交互)
     * SELECTION   → EFFECT_TICK        (切换选择)
     * IMPACT      → EFFECT_CLICK       (按钮点击、面板展开)
     * KICK        → EFFECT_HEAVY_CLICK (主开关、重要操作)
     * SUCCESS     → EFFECT_DOUBLE_CLICK(成功确认 — 系统级双击)
     * CRESCENDO   → TICK→CLICK→HEAVY→TICK 序列 ("噔噔噔↘" 递进)
     */
enum class HapticStyle {
         LIGHT_TICK,
         SELECTION,
         IMPACT,
         KICK,
         SUCCESS,
         WARNING,
         CRESCENDO
     }

    /**
     * 执行指定风格的触觉反馈
     */
    fun perform(style: HapticStyle) {
        val vib = vibrator ?: return
        if (!hasVibrator) return

        // Cancel any pending CRESCENDO callbacks to prevent overlapping vibrations
        handler.removeCallbacksAndMessages(null)

        if (style == HapticStyle.CRESCENDO) {
            performCrescendo(vib)
            return
        }

        try {
            vib.vibrate(buildEffect(style))
        } catch (e: Exception) {
            Log.w(TAG, "Haptic perform failed [${style.name}]: ${e.message}")
            try {
                vib.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (_: Exception) {}
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Predefined Effects (API 29+) — 厂商优化的系统级触觉
    // ════════════════════════════════════════════════════════════════

    private fun tryPredefined(effectId: Int): VibrationEffect? {
        if (!isApi29Plus) return null
        return try {
            VibrationEffect.createPredefined(effectId)
        } catch (_: Exception) {
            null
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Effect Builder
    // ════════════════════════════════════════════════════════════════

    private fun buildEffect(style: HapticStyle): VibrationEffect {
        return when (style) {
            HapticStyle.LIGHT_TICK ->
                tryPredefined(VibrationEffect.EFFECT_TICK)
                    ?: VibrationEffect.createOneShot(8, 80)

            HapticStyle.SELECTION ->
                tryPredefined(VibrationEffect.EFFECT_TICK)
                    ?: VibrationEffect.createOneShot(10, 120)

            HapticStyle.IMPACT ->
                tryPredefined(VibrationEffect.EFFECT_CLICK)
                    ?: VibrationEffect.createOneShot(15, 180)

            HapticStyle.KICK ->
                tryPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                    ?: VibrationEffect.createOneShot(20, 255)

            HapticStyle.SUCCESS ->
                tryPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                    ?: VibrationEffect.createOneShot(30, 255)

            HapticStyle.WARNING ->
                // Warning: double heavy click pattern
                tryPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                    ?: VibrationEffect.createOneShot(25, 200)

            HapticStyle.CRESCENDO ->
                // CRESCENDO 由 performCrescendo() 处理，不应走到这里
                tryPredefined(VibrationEffect.EFFECT_TICK)
                    ?: VibrationEffect.createOneShot(8, 80)
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  CRESCENDO — "噔噔噔↘" 递进序列
    //
    //  通过依次触发独立预定义效果实现，而非依赖 createWaveform 振幅包络。
    //  每个预定义效果都是系统针对 LRA 优化的独立击打，
    //  用 Handler.postDelayed 控制间隔，确保每个击打清晰可辨。
    // ════════════════════════════════════════════════════════════════

    private fun performCrescendo(vib: Vibrator) {
        if (isApi29Plus) {
            // API 29+: 预定义效果序列 — TICK → CLICK → HEAVY_CLICK → TICK
            val tick = tryPredefined(VibrationEffect.EFFECT_TICK)
            val click = tryPredefined(VibrationEffect.EFFECT_CLICK)
            val heavy = tryPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)

            if (tick != null && click != null && heavy != null) {
                try { vib.vibrate(tick) } catch (_: Exception) {}
                handler.postDelayed({ try { vib.vibrate(click) } catch (_: Exception) {} }, 45)
                handler.postDelayed({ try { vib.vibrate(heavy) } catch (_: Exception) {} }, 90)
                handler.postDelayed({ try { vib.vibrate(tick) } catch (_: Exception) {} }, 140)
                return
            }
        }

        // API 28 fallback: 用不同时长的 oneShot 模拟递进
        // 没有预定义效果，靠时长差异制造层级感
        try { vib.vibrate(VibrationEffect.createOneShot(10, 100)) } catch (_: Exception) {}
        handler.postDelayed({ try { vib.vibrate(VibrationEffect.createOneShot(15, 180)) } catch (_: Exception) {} }, 45)
        handler.postDelayed({ try { vib.vibrate(VibrationEffect.createOneShot(20, 255)) } catch (_: Exception) {} }, 90)
        handler.postDelayed({ try { vib.vibrate(VibrationEffect.createOneShot(8, 80)) } catch (_: Exception) {} }, 140)
    }
}