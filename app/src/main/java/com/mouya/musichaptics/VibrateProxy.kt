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

    // v3.10.20: ColorOS/HyperOS deep adaptation — extended primitive support flags
    @Volatile var primitiveLowTickSupported = false
        private set
    @Volatile var primitiveSpinSupported = false
        private set
    @Volatile var primitiveQuickRiseSupported = false
        private set
    @Volatile var primitiveSlowRiseSupported = false
        private set
    // v3.10.20: Vendor-specific haptic API capability
    @Volatile var colorOSHapticAvailable = false
        private set
    @Volatile var hyperOSHapticAvailable = false
        private set

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
        // Do NOT ask PackageManager about context.packageName here. In an Xposed
        // process we commonly receive ActivityThread's system Context whose
        // package is "android". That package has VIBRATE, while the actual
        // hooked app UID may not. The old check therefore selected DIRECT and
        // every direct vibrate() was rejected by the system. Permission must
        // be evaluated for this process UID.
        val hasPermission = try {
            context.checkSelfPermission("android.permission.VIBRATE") ==
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

                    // v3.10.20: ColorOS/HyperOS deep adaptation — probe extended primitives
                    // PRIMITIVE_LOW_TICK (API 30): subtle texture, critical for "细腻" feel
                    // PRIMITIVE_SPIN (API 30): smooth ramp, good for sustained notes
                    // PRIMITIVE_QUICK_RISE / SLOW_RISE (API 30): attack envelope primitives
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        primitiveLowTickSupported = try {
                            vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
                        } catch (_: Exception) { false }
                        primitiveSpinSupported = try {
                            vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_SPIN)
                        } catch (_: Exception) { false }
                        primitiveQuickRiseSupported = try {
                            vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE)
                        } catch (_: Exception) { false }
                        primitiveSlowRiseSupported = try {
                            vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE)
                        } catch (_: Exception) { false }
                    }
                } catch (_: Exception) {}
            }

            // v3.10.20: Detect vendor-specific haptic platforms
            val mfr = Build.MANUFACTURER.lowercase()
            colorOSHapticAvailable = mfr == "oneplus" || mfr == "oppo"
            hyperOSHapticAvailable = mfr == "xiaomi"
            // Lenovo ZUI also has haptic extensions
            val isLenovoHaptic = mfr == "lenovo"

            Log.i(TAG, "Direct path: hasVibrator=$hasDirectVibrator hasAmpCtrl=$hasAmplitudeControl")
            Log.i(TAG, "Primitive support: CLICK=$primitiveClickSupported TICK=$primitiveTickSupported THUD=$primitiveHeavyClickSupported")
            Log.i(TAG, "Extended primitives: LOW_TICK=$primitiveLowTickSupported SPIN=$primitiveSpinSupported QUICK_RISE=$primitiveQuickRiseSupported SLOW_RISE=$primitiveSlowRiseSupported")
            Log.i(TAG, "Vendor haptic: ColorOS=$colorOSHapticAvailable HyperOS=$hyperOSHapticAvailable Lenovo=$isLenovoHaptic")

            // Do not vibrate during hook initialization: target apps can construct audio
            // objects before playback and an unsolicited test pulse is undesirable.
            if (!hasDirectVibrator) Log.e(TAG, "No direct vibrator available")

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
            // Optimistic: the IPC binding is asynchronous — remoteBinder may
            // still be null when the engine first checks.  Returning false here
            // would cause the engine to skip ALL vibration permanently, even
            // though the binder connects a few milliseconds later.
            true
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
        if (paused) {
            android.util.Log.w(TAG, "performWaveform SKIPPED: paused=true")
            return
        }
        if (timings.isEmpty() || amplitudes.isEmpty()) {
            android.util.Log.w(TAG, "performWaveform SKIPPED: empty arrays")
            return
        }
        if (useProxy) {
            val b = remoteBinder
            if (b == null) {
                android.util.Log.w(TAG, "performWaveform SKIPPED: remoteBinder is null (IPC not connected)")
                return
            }
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
            } else {
                android.util.Log.w(TAG, "performWaveform SKIPPED: no direct vibrator available")
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

    // v3.10.20: ColorOS/HyperOS Composition API — perform a composition of primitives
    // This allows fine-grained haptic texture by combining multiple primitives with
    // individual scale and delay, leveraging OEM-tuned system haptic effects.
    //
    // @param primitives: list of (primitiveId, scale, delayMs) tuples
    // On devices without Composition support, falls back to waveform.
    fun performComposition(
        primitives: List<Triple<Int, Float, Int>>
    ) {
        if (paused) return
        if (primitives.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Pre-R fallback: convert to one-shot sequence
            primitives.forEach { (_, scale, _) ->
                performOneShot(15L, (scale * 255).toInt().coerceIn(1, 255))
            }
            return
        }

        if (useProxy) {
            val b = remoteBinder ?: return
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    // Serialize: count, then (primitiveId, scale, delayMs) per entry
                    data.writeInt(primitives.size)
                    primitives.forEach { (pid, scale, delay) ->
                        data.writeInt(pid)
                        data.writeFloat(scale)
                        data.writeInt(delay)
                    }
                    b.transact(VibrateProxyService.CODE_PERFORM_COMPOSITION, data, reply, 0)
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (e: Exception) { Log.w(TAG, "IPC performComposition: ${e.message}") }
        } else {
            val vib = directVibrator
            if (vib != null && hasDirectVibrator) {
                try {
                    val composition = VibrationEffect.startComposition()
                    var totalDelay = 0
                    for ((pid, scale, delay) in primitives) {
                        composition.addPrimitive(pid, scale.coerceIn(0f, 1f), delay.coerceAtLeast(0))
                        totalDelay += delay
                    }
                    vib.vibrate(composition.compose())
                } catch (e: Exception) {
                    Log.w(TAG, "Composition failed, fallback to one-shot: ${e.message}")
                    // Fallback: fire a single one-shot with average intensity
                    val avgScale = primitives.map { it.second }.avg()
                    performOneShot(20L, (avgScale * 255).toInt().coerceIn(1, 255))
                }
            }
        }
    }

    // v3.10.20: ColorOS optimized haptic — uses LOW_TICK for subtle texture
    // when the device supports it, providing the "细腻爽感" experience.
    fun performTextureTick(intensity: Float) {
        if (paused) return
        val scale = intensity.coerceIn(0f, 1f)
        if (primitiveLowTickSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performComposition(listOf(Triple(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale, 0)))
        } else if (primitiveTickSupported) {
            performComposition(listOf(Triple(VibrationEffect.Composition.PRIMITIVE_TICK, scale, 0)))
        } else {
            performOneShot(8L, (scale * 128).toInt().coerceIn(1, 255))
        }
    }

    // v3.10.20: ColorOS impact haptic — uses THUD/CLICK for strong transients
    fun performImpact(intensity: Float) {
        if (paused) return
        val scale = intensity.coerceIn(0f, 1f)
        if (primitiveHeavyClickSupported) {
            performComposition(listOf(Triple(VibrationEffect.Composition.PRIMITIVE_THUD, scale, 0)))
        } else if (primitiveClickSupported) {
            performComposition(listOf(Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, scale, 0)))
        } else {
            performOneShot(20L, (scale * 255).toInt().coerceIn(1, 255))
        }
    }

    // v3.10.20: Rise effect — for note onset with natural attack envelope
    fun performRise(intensity: Float, fast: Boolean = true) {
        if (paused) return
        val scale = intensity.coerceIn(0f, 1f)
        val primitive = if (fast) {
            if (primitiveQuickRiseSupported) VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
            else null
        } else {
            if (primitiveSlowRiseSupported) VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
            else null
        }
        if (primitive != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performComposition(listOf(Triple(primitive, scale, 0)))
        } else {
            performOneShot(12L, (scale * 200).toInt().coerceIn(1, 255))
        }
    }

    private fun List<Float>.avg(): Float = if (isEmpty()) 0.5f else sum() / size

    fun unbind() {
        if (useProxy && bound) {
            try { context.unbindService(connection) } catch (_: Exception) {}
            bound = false
            remoteBinder = null
        }
    }

    val isProxyActive: Boolean get() = useProxy && remoteBinder != null
}