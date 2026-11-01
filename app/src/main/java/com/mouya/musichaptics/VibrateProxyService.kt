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
        private const val MAX_WAVEFORM_SEGMENT_MS = 250L
        private const val MAX_WAVEFORM_TOTAL_MS = 1000L
        private const val MAX_COMPOSITION_PRIMITIVES = 8
        private const val MAX_ONESHOT_MS = 500L
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
                            if (timings.size == amplitudes.size && timings.size <= MAX_WAVEFORM_SEGMENTS) {
                                try {
                                    var remaining = MAX_WAVEFORM_TOTAL_MS
                                    var count = 0
                                    val safeTimings = LongArray(timings.size)
                                    val safeAmplitudes = IntArray(amplitudes.size)
                                    for (i in timings.indices) {
                                        if (remaining <= 0L) break
                                        val duration = timings[i].coerceIn(1L, MAX_WAVEFORM_SEGMENT_MS).coerceAtMost(remaining)
                                        safeTimings[count] = duration
                                        remaining -= duration
                                        safeAmplitudes[count] = when (val amp = amplitudes[i]) {
                                            VibrationEffect.DEFAULT_AMPLITUDE -> VibrationEffect.DEFAULT_AMPLITUDE
                                            0 -> 0
                                            else -> amp.coerceIn(1, 255)
                                        }
                                        count++
                                    }
                                    if (count > 0) {
                                        vib.vibrate(VibrationEffect.createWaveform(
                                            safeTimings.copyOf(count), safeAmplitudes.copyOf(count), -1
                                        ))
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "performWaveform failed: ${e.message}")
                                }
                            } else {
                                Log.w(TAG, "Reject waveform from IPC: timings=${timings.size} amplitudes=${amplitudes.size}")
                            }
                        }
                        reply?.writeNoException()
                        return true
                    }
                    CODE_PERFORM_ONESHOT -> {
                        val durationMs = data.readLong().coerceIn(1L, MAX_ONESHOT_MS)
                        val rawAmplitude = data.readInt()
                        if (rawAmplitude == 0) {
                            reply?.writeNoException()
                            return true
                        }
                        val amplitude = if (rawAmplitude == VibrationEffect.DEFAULT_AMPLITUDE) {
                            VibrationEffect.DEFAULT_AMPLITUDE
                        } else rawAmplitude.coerceIn(1, 255)
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
                        val parts = ArrayList<Triple<Int, Float, Int>>(count)
                        // Read the complete bounded parcel before invoking the
                        // framework, so a rejected primitive still leaves enough
                        // information to reproduce its timing as a waveform.
                        repeat(count) {
                            val pid = data.readInt()
                            val rawScale = data.readFloat()
                            val scale = if (rawScale.isFinite()) rawScale.coerceIn(0f, 1f) else 0.5f
                            val delay = data.readInt().coerceIn(0, 500)
                            parts += Triple(pid, scale, delay)
                        }
                        val vib = vibrator
                        if (vib != null && hasVib && count > 0 &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                val composition = VibrationEffect.startComposition()
                                for ((pid, scale, delay) in parts) {
                                    composition.addPrimitive(pid, scale, delay)
                                }
                                vib.vibrate(composition.compose())
                            } catch (e: Exception) {
                                Log.w(TAG, "Composition failed; preserving rests with a timed waveform: ${e.message}")
                                try {
                                    val timings = ArrayList<Long>(parts.size * 2)
                                    val amplitudes = ArrayList<Int>(parts.size * 2)
                                    var remaining = MAX_WAVEFORM_TOTAL_MS
                                    for ((_, scale, delay) in parts) {
                                        if (remaining <= 0L) break
                                        val gap = delay.toLong().coerceAtMost(remaining)
                                        if (gap > 0L) {
                                            timings += gap
                                            amplitudes += 0
                                            remaining -= gap
                                        }
                                        if (remaining <= 0L) break
                                        val active = 15L.coerceAtMost(remaining)
                                        val amplitude = if (vib.hasAmplitudeControl()) {
                                            (scale * 255f).toInt().coerceIn(1, 255)
                                        } else VibrationEffect.DEFAULT_AMPLITUDE
                                        timings += active
                                        amplitudes += amplitude
                                        remaining -= active
                                    }
                                    if (timings.isNotEmpty()) {
                                        vib.vibrate(VibrationEffect.createWaveform(
                                            timings.toLongArray(), amplitudes.toIntArray(), -1
                                        ))
                                    }
                                } catch (fallback: Exception) {
                                    Log.w(TAG, "Timed Composition fallback failed: ${fallback.message}")
                                }
                            }
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