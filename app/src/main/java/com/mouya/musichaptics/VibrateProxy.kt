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
import com.mouya.musichaptics.haptic.DeviceTuningRegistry
import java.util.Locale

class VibrateProxy(private val context: Context) {

    companion object {
        private const val TAG = "VibrateProxy"
        private const val MAX_WAVEFORM_SEGMENTS = 32
        private const val MAX_WAVEFORM_DURATION_MS = 1_000L
        private const val MAX_WAVEFORM_SEGMENT_MS = 250L
        private const val MAX_COMPOSITION_PARTS = 8

        @Volatile var verboseLogging: Boolean = BuildConfig.DEBUG
    }

    @Volatile private var remoteBinder: IBinder? = null
    @Volatile private var bound = false
    @Volatile private var useProxy = false
    private var directVibrator: Vibrator? = null
    private var hasDirectVibrator = false
    
    private var secondaryVibrator: Vibrator? = null
    private var hasSecondaryVibrator = false

    @Volatile private var activeProfile: DeviceProfile = DeviceProfile.DEFAULT

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

    @Volatile var primitiveLowTickSupported = false
        private set
    @Volatile var primitiveSpinSupported = false
        private set
    @Volatile var primitiveQuickRiseSupported = false
        private set
    @Volatile var primitiveSlowRiseSupported = false
        private set
    
    @Volatile var colorOSHapticAvailable = false
        private set
    @Volatile var hyperOSHapticAvailable = false
        private set

    @Volatile var primitiveClickSupported = false
        private set
    @Volatile var primitiveTickSupported = false
        private set
    @Volatile var primitiveHeavyClickSupported = false
        private set
    @Volatile var hasAmplitudeControl = false
        private set

    
    
    
    @Volatile var forceDefaultAmplitude = false
        private set

    
    @Volatile var forceDefaultAutoDetected = false
        private set

    
    fun setForceDefaultAmplitude(enabled: Boolean) {
        if (forceDefaultAmplitude == enabled) return
        forceDefaultAmplitude = enabled
        Log.i(TAG, "forceDefaultAmplitude → $enabled (autoDetected=$forceDefaultAutoDetected)")
    }

    fun init(profile: DeviceProfile = DeviceProfile.DEFAULT): Boolean {
        activeProfile = profile
        val profileTuning = DeviceTuningRegistry.current(profile)
        val pkgName = try { context.packageName } catch (e: Exception) { "unknown" }
        val hasPermission = try {
            context.checkSelfPermission("android.permission.VIBRATE") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) { false }

        Log.i(TAG, "═══ VIBRATE PROXY INIT ═══")
        Log.i(TAG, "context.pkg=$pkgName hasVIBRATE=$hasPermission mfr=${Build.MANUFACTURER} model=${Build.MODEL} profile=${activeProfile.name} tuning=${profileTuning.profileId} sdk=${Build.VERSION.SDK_INT}")

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

            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                    if (vm != null) {
                        val vibratorIds = vm.vibratorIds
                        if (vibratorIds.size >= 2) {
                            
                            secondaryVibrator = vm.getVibrator(vibratorIds[1])
                            hasSecondaryVibrator = secondaryVibrator?.hasVibrator() ?: false
                            Log.i(TAG, "Stereo haptics: found ${vibratorIds.size} vibrators, secondary=${hasSecondaryVibrator}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Stereo haptics detection failed: ${e.message}")
                }
            }

            hasAmplitudeControl = if (directVibrator != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { directVibrator!!.hasAmplitudeControl() } catch (_: Exception) { false }
            } else false

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

            
            val mfr = Build.MANUFACTURER.lowercase(Locale.ROOT)
            val brand = Build.BRAND.lowercase(Locale.ROOT)
            colorOSHapticAvailable = mfr == "oneplus" || mfr == "oppo" || brand == "oneplus" || brand == "oppo"
            hyperOSHapticAvailable = mfr == "xiaomi" || mfr == "redmi" || mfr == "poco" ||
                brand == "xiaomi" || brand == "redmi" || brand == "poco"
            val isLenovoHaptic = mfr == "lenovo"

            
            
            val device = Build.DEVICE.lowercase(Locale.ROOT)
            if (profileTuning.preferDefaultAmplitude) {
                forceDefaultAmplitude = true
                forceDefaultAutoDetected = true
                Log.w(TAG, "Profile ${profileTuning.profileId} prefers DEFAULT_AMPLITUDE compatibility mode: ${profileTuning.reason}")
            }

            
            
            try {
                val cfg = context.getSharedPreferences("haptics_config", Context.MODE_PRIVATE)
                if (cfg.contains("force_default_amplitude")) {
                    val userChoice = cfg.getBoolean("force_default_amplitude", forceDefaultAmplitude)
                    if (userChoice != forceDefaultAmplitude) {
                        forceDefaultAmplitude = userChoice
                        Log.i(TAG, "force_default_amplitude pref overrides detection → $userChoice")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "force_default_amplitude pref read failed: ${e.message}")
            }

            Log.i(TAG, "Direct path: hasVibrator=$hasDirectVibrator hasAmpCtrl=$hasAmplitudeControl")
            Log.i(TAG, "Primitive support: CLICK=$primitiveClickSupported TICK=$primitiveTickSupported THUD=$primitiveHeavyClickSupported")
            Log.i(TAG, "Extended primitives: LOW_TICK=$primitiveLowTickSupported SPIN=$primitiveSpinSupported QUICK_RISE=$primitiveQuickRiseSupported SLOW_RISE=$primitiveSlowRiseSupported")
            Log.i(TAG, "Vendor haptic: ColorOS=$colorOSHapticAvailable HyperOS=$hyperOSHapticAvailable Lenovo=$isLenovoHaptic")

            if (!hasDirectVibrator) Log.e(TAG, "No direct vibrator available")

            Log.i(TAG, "═══ END VIBRATE PROXY INIT ═══")
            return hasDirectVibrator
        } else {
            useProxy = true
            
            hasAmplitudeControl = false 
            primitiveClickSupported = false
            primitiveHeavyClickSupported = false
            primitiveTickSupported = false
            Log.i(TAG, "Target lacks VIBRATE — binding to VibrateProxyService (proxy) hasAmpCtrl=$hasAmplitudeControl")
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
            true
        } else hasDirectVibrator

    fun setPaused() {
        paused = true
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
                        try {
                            vib.vibrate(VibrationEffect.createPredefined(effectId))
                        } catch (e: Exception) {
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
    



    fun performDynamicEffect(amplitude: Float, sharpness: Float, durationSec: Float, attackSec: Float): Boolean {
        if (paused) return false
        if (Build.VERSION.SDK_INT < 34) return false
        
        val mfr = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val brand = Build.BRAND.lowercase(Locale.ROOT)
        if (mfr !in setOf("xiaomi", "redmi") && brand !in setOf("xiaomi", "redmi")) return false
        try {
            val dynCls = Class.forName("android.os.DynamicEffect")
            val playerCls = Class.forName("android.os.HapticPlayer")
            val primCls = Class.forName("android.os.DynamicEffect\$PrimitiveEffect")
            val paramCls = Class.forName("android.os.DynamicEffect\$Parameter")

            val createDyn = dynCls.getMethod("create")
            val createCont = dynCls.getMethod("createContinuous", Float::class.java, Float::class.java, Float::class.java)
            val createParam = dynCls.getMethod("createParameter", Int::class.java, FloatArray::class.java, FloatArray::class.java)
            val addPrim = dynCls.getMethod("addPrimitive", Float::class.java, primCls)
            val addParam = primCls.getMethod("addParameter", paramCls)

            val dynEffect = createDyn.invoke(null)
            val contEffect = createCont.invoke(null, amplitude.coerceIn(0f, 1f), sharpness.coerceIn(0f, 1f), durationSec.coerceAtLeast(0.001f))

            val times = floatArrayOf(0f, attackSec.coerceAtLeast(0f), 0.62f * durationSec, durationSec)
            val amps = floatArrayOf(0f, amplitude.coerceIn(0f, 1f), 0.76f * amplitude.coerceIn(0f, 1f), 0f)
            val param = createParam.invoke(null, 0, times, amps)
            addParam.invoke(contEffect, param)
            addPrim.invoke(dynEffect, 0f, contEffect)

            val playerCtor = playerCls.getConstructor(dynCls)
            val player = playerCtor.newInstance(dynEffect)
            playerCls.getMethod("start").invoke(player)
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "DynamicEffect unavailable: ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
    }

    private data class SafeWaveform(val timings: LongArray, val amplitudes: IntArray)

    private fun sanitizeWaveform(timings: LongArray, amplitudes: IntArray): SafeWaveform? {
        if (timings.isEmpty() || amplitudes.isEmpty()) return null
        if (timings.size != amplitudes.size) {
            Log.w(TAG, "Reject waveform: timings=${timings.size} amplitudes=${amplitudes.size}")
            return null
        }
        if (timings.size > MAX_WAVEFORM_SEGMENTS) {
            Log.w(TAG, "Reject waveform: ${timings.size} segments exceeds $MAX_WAVEFORM_SEGMENTS")
            return null
        }

        var remaining = MAX_WAVEFORM_DURATION_MS
        var count = 0
        val safeTimings = LongArray(minOf(timings.size, MAX_WAVEFORM_SEGMENTS))
        val safeAmplitudes = IntArray(safeTimings.size)
        for (i in safeTimings.indices) {
            if (remaining <= 0L) break
            val duration = timings[i].coerceIn(1L, MAX_WAVEFORM_SEGMENT_MS).coerceAtMost(remaining)
            safeTimings[count] = duration
            remaining -= duration
            safeAmplitudes[count] = when (val amplitude = amplitudes[i]) {
                VibrationEffect.DEFAULT_AMPLITUDE -> VibrationEffect.DEFAULT_AMPLITUDE
                0 -> 0 // Keep explicit zero-amplitude intervals as intentional pauses.
                else -> amplitude.coerceIn(1, 255)
            }
            count++
        }
        if (count == 0) return null
        return SafeWaveform(safeTimings.copyOf(count), safeAmplitudes.copyOf(count))
    }

    private fun primitiveForAmplitude(amplitude: Int): Int? {
        val order = when {
            amplitude >= 184 -> listOf(
                VibrationEffect.Composition.PRIMITIVE_THUD to primitiveHeavyClickSupported,
                VibrationEffect.Composition.PRIMITIVE_CLICK to primitiveClickSupported,
                VibrationEffect.Composition.PRIMITIVE_TICK to primitiveTickSupported,
            )
            amplitude >= 82 -> listOf(
                VibrationEffect.Composition.PRIMITIVE_CLICK to primitiveClickSupported,
                VibrationEffect.Composition.PRIMITIVE_TICK to primitiveTickSupported,
                VibrationEffect.Composition.PRIMITIVE_THUD to primitiveHeavyClickSupported,
            )
            else -> listOf(
                VibrationEffect.Composition.PRIMITIVE_TICK to primitiveTickSupported,
                VibrationEffect.Composition.PRIMITIVE_CLICK to primitiveClickSupported,
                VibrationEffect.Composition.PRIMITIVE_THUD to primitiveHeavyClickSupported,
            )
        }
        return order.firstOrNull { it.second }?.first
    }

    private fun hardwareAmplitudes(amplitudes: IntArray): IntArray = IntArray(amplitudes.size) { i ->
        val value = amplitudes[i]
        when {
            value == 0 -> 0
            forceDefaultAmplitude || !hasAmplitudeControl || value == VibrationEffect.DEFAULT_AMPLITUDE ->
                VibrationEffect.DEFAULT_AMPLITUDE
            else -> value.coerceIn(1, 255)
        }
    }

    fun performWaveform(timings: LongArray, amplitudes: IntArray) {
        if (paused) {
            Log.w(TAG, "performWaveform SKIPPED: paused=true")
            return
        }

        val safe = sanitizeWaveform(timings, amplitudes) ?: run {
            Log.w(TAG, "performWaveform SKIPPED: invalid or empty envelope")
            return
        }
        val safeTimings = safe.timings
        val safeAmplitudes = if (forceDefaultAmplitude) hardwareAmplitudes(safe.amplitudes) else safe.amplitudes

        if (!hasAmplitudeControl && !forceDefaultAmplitude) {
            val compositionSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                (primitiveHeavyClickSupported || primitiveClickSupported || primitiveTickSupported)
            if (compositionSupported) {
                val parts = mutableListOf<Triple<Int, Float, Int>>()
                var pendingDelay = 0L
                for (i in safeTimings.indices) {
                    val rawAmplitude = safeAmplitudes[i]
                    val amplitude = if (rawAmplitude == VibrationEffect.DEFAULT_AMPLITUDE) 200 else rawAmplitude
                    if (amplitude > 0) {
                        val primitive = primitiveForAmplitude(amplitude)
                        if (primitive != null) {
                            val scale = (amplitude / 255f).coerceIn(0.08f, 1f)
                            parts += Triple(primitive, scale, pendingDelay.coerceAtMost(500L).toInt())
                        }
                        pendingDelay = safeTimings[i]
                    } else {
                        // Zero amplitudes are gaps: fold them into the delay
                        // before the next supported primitive instead of buzzing.
                        pendingDelay = (pendingDelay + safeTimings[i]).coerceAtMost(500L)
                    }
                }
                if (parts.isNotEmpty() && parts.size <= MAX_COMPOSITION_PARTS) {
                    performComposition(parts)
                    return
                }
                // A long envelope may exceed the Composition primitive budget.
                // Fall through to the bounded waveform path so no segments or
                // intentional pauses are silently discarded.
            }

            // No amplitude control and no Composition support: still use the
            // framework waveform. Non-zero amplitudes become DEFAULT_AMPLITUDE,
            // but explicit zero segments remain silent. Collapsing the envelope
            // to one-shot here destroyed every intentional rest interval.
            for (i in safeAmplitudes.indices) {
                if (safeAmplitudes[i] != 0) {
                    safeAmplitudes[i] = VibrationEffect.DEFAULT_AMPLITUDE
                }
            }
        }

        if (useProxy) {
            val binder = remoteBinder
            if (binder == null) {
                Log.w(TAG, "performWaveform SKIPPED: remoteBinder is null (IPC not connected)")
                return
            }
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeLongArray(safeTimings)
                    data.writeIntArray(safeAmplitudes)
                    binder.transact(VibrateProxyService.CODE_PERFORM_WAVEFORM, data, reply, 0)
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "IPC performWaveform: ${e.message}")
            }
        } else {
            val vibrator = directVibrator
            if (vibrator != null && hasDirectVibrator) {
                try {
                    if (verboseLogging) Log.i(
                        TAG,
                        "performWaveform: ${safeTimings.size} segments total=${safeTimings.sum()}ms useProxy=$useProxy ampCtrl=$hasAmplitudeControl default=$forceDefaultAmplitude"
                    )
                    vibrator.vibrate(VibrationEffect.createWaveform(safeTimings, safeAmplitudes, -1))
                } catch (e: Exception) {
                    Log.e(TAG, "Direct performWaveform FAILED: ${e.message}", e)
                }
            } else {
                Log.e(TAG, "performWaveform SKIPPED: vibrator=$vibrator hasDirect=$hasDirectVibrator")
            }
        }
    }

    fun performOneShot(durationMs: Long, amplitude: Int) {
        if (amplitude == 0) return
        if (paused) {
            android.util.Log.w(TAG, "performOneShot SKIPPED: paused=true")
            return
        }

        val safeDuration = durationMs.coerceIn(1L, 500L)
        val safeAmplitude = if (forceDefaultAmplitude || amplitude == VibrationEffect.DEFAULT_AMPLITUDE) {
            VibrationEffect.DEFAULT_AMPLITUDE
        } else amplitude.coerceIn(1, 255)
        if (forceDefaultAmplitude && directVibrator != null && hasDirectVibrator) {
            try {
                val dur = safeDuration
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    directVibrator!!.vibrate(VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    directVibrator!!.vibrate(dur)
                }
                return
            } catch (e: Exception) {
                Log.e(TAG, "performOneShot (forceDefault) FAILED: ${e.message}")
            }
        }
        if (useProxy) {
            val b = remoteBinder ?: return
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeLong(safeDuration)
                    data.writeInt(safeAmplitude)
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
                try {
                    if (verboseLogging) Log.i(TAG, "performOneShot: vibrate(duration=$safeDuration, amp=$safeAmplitude) useProxy=$useProxy")
                    vib.vibrate(VibrationEffect.createOneShot(safeDuration, safeAmplitude))
                } catch (e: Exception) {
                    Log.e(TAG, "Direct performOneShot FAILED: ${e.message}", e)
                }
            } else {
                Log.e(TAG, "performOneShot SKIPPED: vib=$vib hasDirect=$hasDirectVibrator")
            }
        }
    }

    
    fun performStereoEnvelope(
        primarySegments: List<Pair<Long, Int>>,
        secondarySegments: List<Pair<Long, Int>> = emptyList(),
        delayMs: Long = 0L
    ) {
        if (paused) {
            Log.w(TAG, "performStereoEnvelope SKIPPED: paused=true")
            return
        }
        if (primarySegments.isEmpty()) return
        if (!hasSecondaryVibrator) {
            performEnvelope(primarySegments)
            return
        }

        val primary = sanitizeWaveform(
            LongArray(primarySegments.size) { primarySegments[it].first },
            IntArray(primarySegments.size) { primarySegments[it].second },
        ) ?: return
        try {
            val primaryAmplitudes = hardwareAmplitudes(primary.amplitudes)
            directVibrator?.vibrate(VibrationEffect.createWaveform(primary.timings, primaryAmplitudes, -1))
        } catch (e: Exception) {
            Log.e(TAG, "Stereo primary FAILED: ${e.message}")
        }

        if (secondarySegments.isNotEmpty()) {
            val rawTimings = if (delayMs > 0L) {
                longArrayOf(delayMs.coerceAtMost(500L)) + LongArray(secondarySegments.size) { secondarySegments[it].first }
            } else {
                LongArray(secondarySegments.size) { secondarySegments[it].first }
            }
            val rawAmplitudes = if (delayMs > 0L) {
                intArrayOf(0) + IntArray(secondarySegments.size) { secondarySegments[it].second }
            } else {
                IntArray(secondarySegments.size) { secondarySegments[it].second }
            }
            val secondary = sanitizeWaveform(rawTimings, rawAmplitudes) ?: return
            try {
                val secondaryAmplitudes = hardwareAmplitudes(secondary.amplitudes)
                secondaryVibrator?.vibrate(
                    VibrationEffect.createWaveform(secondary.timings, secondaryAmplitudes, -1)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Stereo secondary FAILED: ${e.message}")
            }
        }
    }

    val hasStereoVibrator: Boolean get() = hasSecondaryVibrator

    
    fun performEnvelope(segments: List<Pair<Long, Int>>) {
        if (paused) {
            Log.w(TAG, "performEnvelope SKIPPED: paused=true")
            return
        }
        if (segments.isEmpty()) return
        performWaveform(
            LongArray(segments.size) { segments[it].first },
            IntArray(segments.size) { segments[it].second },
        )
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

    private fun buildPrimitiveFallback(primitives: List<Triple<Int, Float, Int>>): SafeWaveform? {
        if (primitives.isEmpty()) return null
        val timings = ArrayList<Long>(primitives.size * 2)
        val amplitudes = ArrayList<Int>(primitives.size * 2)
        var remaining = MAX_WAVEFORM_DURATION_MS
        for ((_, rawScale, rawDelay) in primitives.take(MAX_COMPOSITION_PARTS)) {
            if (remaining <= 0L) break
            val delay = rawDelay.coerceIn(0, 500).toLong().coerceAtMost(remaining)
            if (delay > 0L) {
                timings += delay
                amplitudes += 0
                remaining -= delay
            }
            if (remaining <= 0L) break
            val activeMs = 15L.coerceAtMost(remaining)
            val scale = if (rawScale.isFinite()) rawScale.coerceIn(0f, 1f) else 0.5f
            val amplitude = if (hasAmplitudeControl && !forceDefaultAmplitude) {
                (scale * 255f).toInt().coerceIn(1, 255)
            } else {
                VibrationEffect.DEFAULT_AMPLITUDE
            }
            timings += activeMs
            amplitudes += amplitude
            remaining -= activeMs
        }
        if (timings.isEmpty()) return null
        return SafeWaveform(timings.toLongArray(), amplitudes.toIntArray())
    }

    private fun vibratePrimitiveFallback(vibrator: Vibrator, primitives: List<Triple<Int, Float, Int>>) {
        val safe = buildPrimitiveFallback(primitives) ?: return
        // Call the framework directly here. Routing this through performWaveform
        // would select Composition again on devices that advertise a primitive
        // but reject it at runtime, creating a recursive failure loop.
        vibrator.vibrate(VibrationEffect.createWaveform(safe.timings, safe.amplitudes, -1))
    }

    fun performComposition(
        primitives: List<Triple<Int, Float, Int>>
    ) {
        if (paused || primitives.isEmpty()) return
        val safePrimitives = primitives.take(MAX_COMPOSITION_PARTS).map { (pid, scale, delay) ->
            Triple(pid, if (scale.isFinite()) scale.coerceIn(0f, 1f) else 0.5f, delay.coerceIn(0, 500))
        }
        if (primitives.size > MAX_COMPOSITION_PARTS) {
            Log.w(TAG, "Composition exceeds $MAX_COMPOSITION_PARTS primitives; preserving rhythm with a timed waveform")
            val fallback = buildPrimitiveFallback(primitives)
            if (fallback != null) performWaveform(fallback.timings, fallback.amplitudes)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Preserve primitive delays as actual silent waveform segments on
            // Android 9/10. Calling several one-shots back-to-back replaces or
            // overlaps prior effects and loses the rhythm's rests.
            val fallback = buildPrimitiveFallback(safePrimitives)
            if (fallback != null) performWaveform(fallback.timings, fallback.amplitudes)
            return
        }

        if (useProxy) {
            val b = remoteBinder
            if (b == null) {
                Log.w(TAG, "Composition skipped: proxy binder unavailable")
                return
            }
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInt(safePrimitives.size)
                    safePrimitives.forEach { (pid, scale, delay) ->
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
            } catch (e: Exception) {
                Log.w(TAG, "IPC performComposition: ${e.message}")
                // The service normally performs its own timed fallback. If the
                // Binder itself is gone, attempt the same rhythm through the
                // local vibrator when one is available.
                val vibrator = directVibrator
                if (vibrator != null && hasDirectVibrator) {
                    runCatching { vibratePrimitiveFallback(vibrator, safePrimitives) }
                        .onFailure { Log.w(TAG, "Local composition fallback failed: ${it.message}") }
                }
            }
        } else {
            val vib = directVibrator
            if (vib != null && hasDirectVibrator) {
                try {
                    val composition = VibrationEffect.startComposition()
                    for ((pid, scale, delay) in safePrimitives) {
                        composition.addPrimitive(pid, scale, delay)
                    }
                    vib.vibrate(composition.compose())
                } catch (e: Exception) {
                    Log.w(TAG, "Composition failed; preserving rests with a timed waveform: ${e.message}")
                    runCatching { vibratePrimitiveFallback(vib, safePrimitives) }
                        .onFailure { Log.w(TAG, "Timed composition fallback failed: ${it.message}") }
                }
            }
        }
    }

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