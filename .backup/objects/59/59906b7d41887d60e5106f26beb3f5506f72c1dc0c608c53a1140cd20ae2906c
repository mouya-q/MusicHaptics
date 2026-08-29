package com.mouya.musichaptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class RichTapAdapter {
    companion object {
        private const val TAG = "RichTapAdapter"

        @Volatile private var sAvailable: Boolean? = null
        @Volatile private var sVibrator: Vibrator? = null
        @Volatile private var sHasAmplitudeControl: Boolean = false
        @Volatile private var sForceDefault: Boolean = false

        fun init(context: Context): Boolean {
            sAvailable?.let { return it }

            return try {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                sVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }

                val vib = sVibrator
                if (vib == null || !vib.hasVibrator()) {
                    sAvailable = false
                    Log.w(TAG, "No vibrator available")
                    return false
                }

                sHasAmplitudeControl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try { vib.hasAmplitudeControl() } catch (_: Exception) { false }
                } else false

                val mfr = Build.MANUFACTURER.lowercase()
                val device = Build.DEVICE.lowercase()
                if (mfr == "xiaomi" && (device.contains("umi") || device.contains("cmi") || device.contains("thyme") || device.contains("g2"))) {
                    sForceDefault = true
                    Log.w(TAG, "Xiaomi HAL quirk detected — forcing DEFAULT_AMPLITUDE")
                }

                sAvailable = true
                Log.i(TAG, "RichTap initialized: ampCtrl=$sHasAmplitudeControl forceDefault=$sForceDefault")
                true
            } catch (e: Exception) {
                sAvailable = false
                Log.w(TAG, "RichTap init failed: ${e.message}")
                false
            }
        }

        fun triggerPulse(event: String, intensity: Int, mode: String = "balanced", boost: Boolean = false): Boolean {
            val vib = sVibrator ?: return false

            return try {
                val dur = when (event.uppercase()) {
                    "KICK", "BODY" -> 50L
                    "SNARE" -> 35L
                    "TICK", "HIHAT" -> 15L
                    else -> 30L
                }
                val amp = if (sForceDefault || !sHasAmplitudeControl) {
                    VibrationEffect.DEFAULT_AMPLITUDE
                } else {
                    intensity.coerceIn(1, 255)
                }
                vib.vibrate(VibrationEffect.createOneShot(dur, amp))
                Log.i(TAG, "triggerPulse: $event dur=${dur}ms amp=$amp")
                true
            } catch (e: Exception) {
                Log.w(TAG, "triggerPulse failed: ${e.message}")
                false
            }
        }

        fun getMinIntervalMs(mode: String): Long = when (mode) {
            "pure" -> 260L
            "crisp" -> 60L
            "immersive" -> 80L
            "bass" -> 100L
            "soft" -> 90L
            else -> 75L
        }

        fun ensureSystemProps() {
            try {
                Runtime.getRuntime().exec("resetprop sys.haptic.dynamiceffect true")
                Runtime.getRuntime().exec("resetprop sys.haptic.media true")
                Runtime.getRuntime().exec("resetprop sys.haptic.dynamiceffect.richtap true")
                Log.i(TAG, "System props set via resetprop")
            } catch (e: Exception) {
                Log.w(TAG, "ensureSystemProps failed: ${e.message}")
            }
        }
    }
}