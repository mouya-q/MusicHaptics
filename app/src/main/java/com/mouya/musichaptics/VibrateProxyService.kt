package com.mouya.musichaptics

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * VibrateProxyService — Runs in the module's own process (which has VIBRATE permission).
 * Hooked target apps that lack VIBRATE permission call this via raw Binder IPC.
 *
 * Protocol (no AIDL — uses raw Parcel transact codes):
 *   Code 1: performPredefined(int effectId)
 *   Code 2: performWaveform(long[] timings, int[] amplitudes)
 *   Code 3: performOneShot(long durationMs, int amplitude)
 *   Code 4: cancelVibration()
 *   Code 5: hasVibrator() → boolean
 */
class VibrateProxyService : Service() {

    companion object {
        private const val TAG = "VibrateProxy"
        // Binder transaction codes
        const val CODE_PERFORM_PREDEFINED = 1
        const val CODE_PERFORM_WAVEFORM = 2
        const val CODE_PERFORM_ONESHOT = 3
        const val CODE_CANCEL = 4
        const val CODE_HAS_VIBRATOR = 5
    }

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibrator resolution failed: ${e.message}")
            null
        }
    }

    private val hasVib: Boolean get() = vibrator?.hasVibrator() ?: false

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            try {
                when (code) {
                    CODE_PERFORM_PREDEFINED -> {
                        val effectId = data.readInt()
                        val vib = vibrator
                        if (vib != null && hasVib) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                vib.vibrate(VibrationEffect.createPredefined(effectId))
                            } else {
                                val (dur, amp) = when (effectId) {
                                    VibrationEffect.EFFECT_TICK -> 8L to 80
                                    VibrationEffect.EFFECT_CLICK -> 20L to 128
                                    VibrationEffect.EFFECT_HEAVY_CLICK -> 30L to 255
                                    else -> 10L to 100
                                }
                                vib.vibrate(VibrationEffect.createOneShot(dur, amp))
                            }
                        }
                        reply?.writeNoException()
                        return true
                    }
                    CODE_PERFORM_WAVEFORM -> {
                        val timings = data.createLongArray()
                        val amplitudes = data.createIntArray()
                        val vib = vibrator
                        if (vib != null && hasVib && timings != null && amplitudes != null && timings.isNotEmpty()) {
                            try {
                                vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                            } catch (e: Exception) {
                                Log.w(TAG, "performWaveform failed: ${e.message}")
                            }
                        }
                        reply?.writeNoException()
                        return true
                    }
                    CODE_PERFORM_ONESHOT -> {
                        val durationMs = data.readLong()
                        val amplitude = data.readInt()
                        val vib = vibrator
                        if (vib != null && hasVib) {
                            try {
                                vib.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
                            } catch (e: Exception) {
                                Log.w(TAG, "performOneShot failed: ${e.message}")
                            }
                        }
                        reply?.writeNoException()
                        return true
                    }
                    CODE_CANCEL -> {
                        try { vibrator?.cancel() } catch (_: Exception) {}
                        reply?.writeNoException()
                        return true
                    }
                    CODE_HAS_VIBRATOR -> {
                        reply?.writeNoException()
                        reply?.writeInt(if (hasVib) 1 else 0)
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transact error code=$code: ${e.message}")
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VibrateProxyService created | hasVibrator=$hasVib")
    }
}