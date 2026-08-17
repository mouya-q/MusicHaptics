package com.mouya.musichaptics

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

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

enum class HapticStyle {
          LIGHT_TICK,
          SELECTION,
          IMPACT,
          KICK,
          SUCCESS,
          WARNING,
          CRESCENDO,
          CONTINUOUS_HUM,  // v3.14: 滑块拖拽连续触感
          SOFT_TAP,  // v3.14: 减弱动态模式
          NONE  // v3.14: 无触觉
      }

    fun perform(style: HapticStyle) {
        // v3.14: NONE style — no-op
        if (style == HapticStyle.NONE) return

        val vib = vibrator ?: return
        if (!hasVibrator) return

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


    private fun tryPredefined(effectId: Int): VibrationEffect? {
        if (!isApi29Plus) return null
        return try {
            VibrationEffect.createPredefined(effectId)
        } catch (_: Exception) {
            null
        }
    }


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
                tryPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                    ?: VibrationEffect.createOneShot(25, 200)

            HapticStyle.CRESCENDO ->
                tryPredefined(VibrationEffect.EFFECT_TICK)
                    ?: VibrationEffect.createOneShot(8, 80)

            HapticStyle.CONTINUOUS_HUM ->
                tryPredefined(VibrationEffect.EFFECT_TICK)
                    ?: VibrationEffect.createOneShot(12, 50)

            HapticStyle.SOFT_TAP ->
                tryPredefined(VibrationEffect.EFFECT_TICK)
                    ?: VibrationEffect.createOneShot(5, 40)

            HapticStyle.NONE ->
                VibrationEffect.createOneShot(1, 1)
        }
    }


    private fun performCrescendo(vib: Vibrator) {
        if (isApi29Plus) {
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

        try { vib.vibrate(VibrationEffect.createOneShot(10, 100)) } catch (_: Exception) {}
        handler.postDelayed({ try { vib.vibrate(VibrationEffect.createOneShot(15, 180)) } catch (_: Exception) {} }, 45)
        handler.postDelayed({ try { vib.vibrate(VibrationEffect.createOneShot(20, 255)) } catch (_: Exception) {} }, 90)
        handler.postDelayed({ try { vib.vibrate(VibrationEffect.createOneShot(8, 80)) } catch (_: Exception) {} }, 140)
    }
}