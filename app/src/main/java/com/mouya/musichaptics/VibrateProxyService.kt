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

class VibrateProxyService : Service() {

    companion object {
        private const val TAG = "VibrateProxy"
        const val CODE_PERFORM_PREDEFINED = 1
        const val CODE_PERFORM_WAVEFORM = 2
        const val CODE_PERFORM_ONESHOT = 3
        const val CODE_CANCEL = 4
        const val CODE_HAS_VIBRATOR = 5

        const val CODE_PERFORM_COMPOSITION = 6

        // P0 hardening: IPC input bounds to prevent DoS via huge parcels.
        private const val MAX_WAVEFORM_SEGMENTS = 32
        private const val MAX_COMPOSITION_PRIMITIVES = 8
        private const val MAX_ONESHOT_MS = 5000L
        private const val MIN_IPC_INTERVAL_MS = 5L
    }

    @Volatile private var lastIpcMs: Long = 0L

    private fun allowIpcLocked(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastIpcMs < MIN_IPC_INTERVAL_MS) return false
        lastIpcMs = now
        return true
    }

    private fun isCallerAllowed(): Boolean {
        // Only the module itself or a whitelisted audio app may drive vibration.
        // Binder.getCallingUid() resolves to the real caller even when exported=true.
        return try {
            val uid = Binder.getCallingUid()
            if (uid == android.os.Process.myUid()) return true
            val pm = packageManager
            val pkgs = pm.getPackagesForUid(uid) ?: return false
            val wm = WhitelistManager()
            pkgs.any { it == BuildConfig.APPLICATION_ID || wm.isPackageAllowed(it) }
        } catch (_: Exception) {
            false
        }
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
                // P0: exported service must not serve arbitrary callers.
                // CODE_HAS_VIBRATOR stays open for capability probe; all
                // actuating codes require whitelist + rate-limit.
                if (code != CODE_HAS_VIBRATOR) {
                    if (!isCallerAllowed()) {
                        Log.w(TAG, "Rejected IPC code=$code uid=${Binder.getCallingUid()}")
                        reply?.writeNoException()
                        return true
                    }
                    if (!allowIpcLocked()) {
                        reply?.writeNoException()
                        return true
                    }
                }
                when (code) {
                    CODE_PERFORM_PREDEFINED -> {
                        val effectId = data.readInt()
                        val vib = vibrator
                        if (vib != null && hasVib) {
                            val known = effectId == VibrationEffect.EFFECT_TICK ||
                                effectId == VibrationEffect.EFFECT_CLICK ||
                                effectId == VibrationEffect.EFFECT_HEAVY_CLICK
                            val (dur, amp) = when (effectId) {
                                VibrationEffect.EFFECT_TICK -> 8L to 80
                                VibrationEffect.EFFECT_CLICK -> 20L to 128
                                VibrationEffect.EFFECT_HEAVY_CLICK -> 30L to 255
                                else -> 10L to 100
                            }
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && known) {
                                    vib.vibrate(VibrationEffect.createPredefined(effectId))
                                } else {
                                    vib.vibrate(VibrationEffect.createOneShot(dur, amp))
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "predefined effect $effectId rejected; using one-shot fallback: ${e.message}")
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
                            // P0: bound parcel arrays; clamp each segment.
                            val n = minOf(timings.size, amplitudes.size, MAX_WAVEFORM_SEGMENTS)
                            if (n > 0) {
                                try {
                                    val t = LongArray(n) { timings[it].coerceIn(0L, 500L) }
                                    val a = IntArray(n) { amplitudes[it].coerceIn(1, 255) }
                                    vib.vibrate(VibrationEffect.createWaveform(t, a, -1))
                                } catch (e: Exception) {
                                    Log.w(TAG, "performWaveform failed: ${e.message}")
                                }
                            }
                        }
                        reply?.writeNoException()
                        return true
                    }
                    CODE_PERFORM_ONESHOT -> {
                        val durationMs = data.readLong().coerceIn(1L, MAX_ONESHOT_MS)
                        val amplitude = data.readInt().coerceIn(1, 255)
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
                    CODE_PERFORM_COMPOSITION -> {
                        val count = data.readInt().coerceIn(0, MAX_COMPOSITION_PRIMITIVES)
                        val vib = vibrator
                        if (vib != null && hasVib && count > 0 &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                val composition = VibrationEffect.startComposition()
                                for (i in 0 until count) {
                                    val pid = data.readInt()
                                    val scale = data.readFloat().coerceIn(0f, 1f)
                                    val delay = data.readInt().coerceIn(0, 1000)
                                    composition.addPrimitive(pid, scale, delay)
                                }
                                vib.vibrate(composition.compose())
                            } catch (e: Exception) {
                                Log.w(TAG, "Composition failed, fallback: ${e.message}")
                                try {
                                    vib.vibrate(VibrationEffect.createOneShot(15L, 128))
                                } catch (_: Exception) {}
                            }
                        } else {
                            // Drain parcel to keep Binder alignment even when rejected.
                            try {
                                repeat(count) {
                                    data.readInt(); data.readFloat(); data.readInt()
                                }
                            } catch (_: Exception) {}
                        }
                        reply?.writeNoException()
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