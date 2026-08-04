package com.mouya.musichaptics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * VibrateProxy — Client-side raw Binder proxy for vibration.
 *
 * When the hooked target app lacks android.permission.VIBRATE, all
 * vibration calls are forwarded via IPC to VibrateProxyService (which
 * runs in the module's own process with VIBRATE permission).
 *
 * Uses raw Parcel transact — no AIDL dependency.
 */
class VibrateProxy(private val context: Context) {

    companion object {
        private const val TAG = "VibrateProxy"
    }

    @Volatile private var remoteBinder: IBinder? = null
    @Volatile private var bound = false
    @Volatile private var useProxy = false
    private var directVibrator: Vibrator? = null
    private var hasDirectVibrator = false

    // v2.1.2: Hard pause gate — blocks ALL vibration output at the proxy level
    // This is the final defense against race conditions where HapticEngine's
    // hapticPaused flag is checked but vibration call still executes before pause takes effect
    @Volatile var paused = false
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remoteBinder = service
            Log.i(TAG, "Proxy service connected — IPC vibration path active")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            remoteBinder = null
            Log.w(TAG, "Proxy service disconnected")
        }
    }

    // v2.1.2: Cached primitive support flags — checked once at init
    @Volatile var primitiveClickSupported = false
        private set
    @Volatile var primitiveTickSupported = false
        private set
    @Volatile var primitiveHeavyClickSupported = false
        private set
    @Volatile var hasAmplitudeControl = false
        private set

    fun init(): Boolean {
        val pkgName = try { context.packageName } catch (e: Exception) { "unknown" }
        val hasPermission = try {
            val pm = context.packageManager
            pm.checkPermission("android.permission.VIBRATE", pkgName) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) { false }

        Log.i(TAG, "═══ VIBRATE PROXY INIT ═══")
        Log.i(TAG, "context.pkg=$pkgName hasVIBRATE=$hasPermission mfr=${Build.MANUFACTURER} model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")

        if (hasPermission) {
            useProxy = false
            directVibrator = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
            } catch (e: Exception) {
                Log.e(TAG, "Vibrator resolution failed: ${e.message}")
                null
            }
            hasDirectVibrator = directVibrator?.hasVibrator() ?: false
            hasAmplitudeControl = if (directVibrator != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { directVibrator!!.hasAmplitudeControl() } catch (_: Exception) { false }
            } else false

            // v2.1.2: Check primitive support — critical for OnePlus/Samsung devices
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && directVibrator != null) {
                try {
                    val vib = directVibrator!!
                    primitiveClickSupported = try {
                        vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
                    } catch (_: Exception) { false }
                    primitiveTickSupported = try {
                        vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
                    } catch (_: Exception) { false }
                    primitiveHeavyClickSupported = try {
                        vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)
                    } catch (_: Exception) { false }
                } catch (_: Exception) {}
            }

            Log.i(TAG, "Direct path: hasVibrator=$hasDirectVibrator hasAmpCtrl=$hasAmplitudeControl")
            Log.i(TAG, "Primitive support: CLICK=$primitiveClickSupported TICK=$primitiveTickSupported THUD=$primitiveHeavyClickSupported")

            // v2.1.2: Diagnostic test vibration — confirms Vibrator HAL works on this device
            if (hasDirectVibrator && directVibrator != null) {
                try {
                    val testEffect = VibrationEffect.createOneShot(100, 200)
                    directVibrator!!.vibrate(testEffect)
                    Log.i(TAG, "✅ DIAGNOSTIC: test vibration sent (100ms, amp=200)")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ DIAGNOSTIC: test vibration FAILED: ${e.message}")
                }
            } else {
                Log.e(TAG, "❌ DIAGNOSTIC: no vibrator available!")
            }

            Log.i(TAG, "═══ END VIBRATE PROXY INIT ═══")
            return hasDirectVibrator
        } else {
            useProxy = true
            Log.i(TAG, "Target lacks VIBRATE — binding to VibrateProxyService")
            Log.i(TAG, "═══ END VIBRATE PROXY INIT ═══")
            return bind()
        }
    }

    private fun bind(): Boolean {
        return try {
            val intent = Intent().apply {
                component = ComponentName("com.mouya.musichaptics", "com.mouya.musichaptics.VibrateProxyService")
            }
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            bound = true
            Log.i(TAG, "bindService called")
            true
        } catch (e: Exception) {
            Log.e(TAG, "bind failed: ${e.message}")
            false
        }
    }

    val hasVibrator: Boolean
        get() = if (useProxy) {
            val b = remoteBinder ?: return false
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    b.transact(VibrateProxyService.CODE_HAS_VIBRATOR, data, reply, 0)
                    reply.readException()
                    reply.readInt() == 1
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (e: Exception) { false }
        } else hasDirectVibrator

    // v2.1.2: Hard pause control — called by HapticEngine.onPlaybackPaused()
    // Immediately blocks all vibration output and cancels ongoing vibration
    fun setPaused() {
        paused = true
        // Cancel immediately at the Vibrator level
        if (useProxy) {
            val b = remoteBinder
            if (b != null) {
                try {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        b.transact(VibrateProxyService.CODE_CANCEL, data, reply, 0)
                        reply.readException()
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                } catch (_: Exception) {}
            }
        } else {
            try { directVibrator?.cancel() } catch (_: Exception) {}
        }
    }

    // v2.1.2: Resume vibration output — called when new audio arrives
    fun setResumed() {
        paused = false
    }

    fun performPredefined(effectId: Int) {
        if (paused) return
        if (useProxy) {
            val b = remoteBinder ?: return
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInt(effectId)
                    b.transact(VibrateProxyService.CODE_PERFORM_PREDEFINED, data, reply, 0)
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (e: Exception) { Log.w(TAG, "IPC performPredefined: ${e.message}") }
        } else {
            val vib = directVibrator
            if (vib != null && hasDirectVibrator) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // v2.1.2: Try createPredefined first — system-optimized for LRA hardware
                        // If it throws (some OEMs), fallback to createOneShot
                        try {
                            vib.vibrate(VibrationEffect.createPredefined(effectId))
                        } catch (e: Exception) {
                            // Predefined not supported on this device — fallback
                            val (dur, amp) = when (effectId) {
                                VibrationEffect.EFFECT_TICK -> 8L to 80
                                VibrationEffect.EFFECT_CLICK -> 20L to 128
                                VibrationEffect.EFFECT_HEAVY_CLICK -> 30L to 255
                                else -> 10L to 100
                            }
                            val finalAmp = if (hasAmplitudeControl) amp else VibrationEffect.DEFAULT_AMPLITUDE
                            vib.vibrate(VibrationEffect.createOneShot(dur, finalAmp))
                        }
                    } else {
                        val (dur, amp) = when (effectId) {
                            VibrationEffect.EFFECT_TICK -> 8L to 80
                            VibrationEffect.EFFECT_CLICK -> 20L to 128
                            VibrationEffect.EFFECT_HEAVY_CLICK -> 30L to 255
                            else -> 10L to 100
                        }
                        vib.vibrate(VibrationEffect.createOneShot(dur, amp))
                    }
                } catch (e: Exception) { Log.w(TAG, "Direct performPredefined: ${e.message}") }
            }
        }
    }

    fun performWaveform(timings: LongArray, amplitudes: IntArray) {
        if (paused) return
        if (timings.isEmpty() || amplitudes.isEmpty()) return
        if (useProxy) {
            val b = remoteBinder ?: return
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeLongArray(timings)
                    data.writeIntArray(amplitudes)
                    b.transact(VibrateProxyService.CODE_PERFORM_WAVEFORM, data, reply, 0)
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (e: Exception) { Log.w(TAG, "IPC performWaveform: ${e.message}") }
        } else {
            val vib = directVibrator
            if (vib != null && hasDirectVibrator) {
                try {
                    vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } catch (e: Exception) { Log.w(TAG, "Direct performWaveform: ${e.message}") }
            }
        }
    }

    fun performOneShot(durationMs: Long, amplitude: Int) {
        if (paused) return
        if (useProxy) {
            val b = remoteBinder ?: return
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeLong(durationMs)
                    data.writeInt(amplitude)
                    b.transact(VibrateProxyService.CODE_PERFORM_ONESHOT, data, reply, 0)
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (e: Exception) { Log.w(TAG, "IPC performOneShot: ${e.message}") }
        } else {
            val vib = directVibrator
            if (vib != null && hasDirectVibrator) {
                try { vib.vibrate(VibrationEffect.createOneShot(durationMs, amplitude)) }
                catch (e: Exception) { Log.w(TAG, "Direct performOneShot: ${e.message}") }
            }
        }
    }

    fun cancel() {
        if (useProxy) {
            val b = remoteBinder ?: return
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    b.transact(VibrateProxyService.CODE_CANCEL, data, reply, 0)
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (_: Exception) {}
        } else {
            try { directVibrator?.cancel() } catch (_: Exception) {}
        }
    }

    fun unbind() {
        if (useProxy && bound) {
            try { context.unbindService(connection) } catch (_: Exception) {}
            bound = false
            remoteBinder = null
        }
    }

    val isProxyActive: Boolean get() = useProxy && remoteBinder != null
}