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
import java.util.Locale

class VibrateProxy(private val context: Context) {

    companion object {
        private const val TAG = "VibrateProxy"
    }

    @Volatile private var remoteBinder: IBinder? = null
    @Volatile private var bound = false
    @Volatile private var useProxy = false
    private var directVibrator: Vibrator? = null
    private var hasDirectVibrator = false
    
    private var secondaryVibrator: Vibrator? = null
    private var hasSecondaryVibrator = false

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
        Log.i(TAG, "v4.10: forceDefaultAmplitude → $enabled (autoDetected=$forceDefaultAutoDetected)")
    }

    fun init(): Boolean {
        val pkgName = try { context.packageName } catch (e: Exception) { "unknown" }
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

            
            val mfr = Build.MANUFACTURER.lowercase()
            colorOSHapticAvailable = mfr == "oneplus" || mfr == "oppo"
            hyperOSHapticAvailable = mfr == "xiaomi"
            val isLenovoHaptic = mfr == "lenovo"

            
            
            val device = Build.DEVICE.lowercase(Locale.ROOT)
            if (mfr == "xiaomi" && (device.contains("umi") || device.contains("cmi") || device.contains("thyme"))) {
                forceDefaultAmplitude = true
                forceDefaultAutoDetected = true
                Log.w(TAG, "v4.8: Xiaomi 10 series detected — forcing DEFAULT_AMPLITUDE (known custom ROM amp scaling issue)")
            }

            
            
            try {
                val cfg = context.getSharedPreferences("haptics_config", Context.MODE_PRIVATE)
                if (cfg.contains("force_default_amplitude")) {
                    val userChoice = cfg.getBoolean("force_default_amplitude", forceDefaultAmplitude)
                    if (userChoice != forceDefaultAmplitude) {
                        forceDefaultAmplitude = userChoice
                        Log.i(TAG, "v4.10: force_default_amplitude pref overrides detection → $userChoice")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "v4.10: force_default_amplitude pref read failed: ${e.message}")
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

    fun performWaveform(timings: LongArray, amplitudes: IntArray) {
        if (paused) {
            android.util.Log.w(TAG, "performWaveform SKIPPED: paused=true")
            return
        }
        if (timings.isEmpty() || amplitudes.isEmpty()) {
            android.util.Log.w(TAG, "performWaveform SKIPPED: empty arrays")
            return
        }

        
        if (!hasAmplitudeControl) {
            Log.w(TAG, "performWaveform: no amplitude control, fallback path, proxy=$useProxy hasAmpCtrl=$hasAmplitudeControl, primitives CLICK=$primitiveClickSupported THUD=$primitiveHeavyClickSupported")
            val maxAmp = amplitudes.maxOrNull() ?: 0
            if (primitiveHeavyClickSupported) {
                performComposition(listOf(Triple(VibrationEffect.Composition.PRIMITIVE_THUD, (maxAmp / 255f).coerceIn(0f, 1f), 0)))
            } else if (primitiveClickSupported) {
                performComposition(listOf(Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, (maxAmp / 255f).coerceIn(0f, 1f), 0)))
            } else {
                
                val totalDuration = timings.sum()
                Log.i(TAG, "performWaveform: no amp ctrl, no primitives → performOneShot($totalDuration, DEFAULT)")
                performOneShot(totalDuration.coerceAtMost(100L), VibrationEffect.DEFAULT_AMPLITUDE)
            }
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
                    Log.i(TAG, "performWaveform: vibrate(waveform, ${timings.size} segments) useProxy=$useProxy ampCtrl=$hasAmplitudeControl")
                    vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } catch (e: Exception) { Log.e(TAG, "Direct performWaveform FAILED: ${e.message}", e) }
            } else {
                Log.e(TAG, "performWaveform SKIPPED: vib=$vib hasDirect=$hasDirectVibrator")
            }
        }
    }

    fun performOneShot(durationMs: Long, amplitude: Int) {
        if (paused) {
            android.util.Log.w(TAG, "performOneShot SKIPPED: paused=true")
            return
        }
        
        
        
        
        if (forceDefaultAmplitude && directVibrator != null && hasDirectVibrator) {
            try {
                val dur = durationMs.coerceAtLeast(1L)
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
                try {
                    Log.i(TAG, "performOneShot: vibrate(duration=$durationMs, amp=$amplitude) useProxy=$useProxy")
                    vib.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
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
            android.util.Log.w(TAG, "performStereoEnvelope SKIPPED: paused=true")
            return
        }
        if (primarySegments.isEmpty()) return

        
        if (!hasSecondaryVibrator) {
            performEnvelope(primarySegments)
            return
        }

        val forceDef = forceDefaultAmplitude
        val useAmpCtrl = hasAmplitudeControl && !forceDef

        
        val pTimings = LongArray(primarySegments.size) { primarySegments[it].first }
        val pAmps = IntArray(primarySegments.size) {
            if (useAmpCtrl) primarySegments[it].second else VibrationEffect.DEFAULT_AMPLITUDE
        }
        try {
            directVibrator?.vibrate(VibrationEffect.createWaveform(pTimings, pAmps, -1))
        } catch (e: Exception) {
            Log.e(TAG, "Stereo primary FAILED: ${e.message}")
        }

        
        if (secondarySegments.isNotEmpty()) {
            if (delayMs > 0) {
                
                val sTimings = longArrayOf(delayMs) + LongArray(secondarySegments.size) { secondarySegments[it].first }
                val sAmps = intArrayOf(0) + IntArray(secondarySegments.size) {
                    if (useAmpCtrl) secondarySegments[it].second else VibrationEffect.DEFAULT_AMPLITUDE
                }
                try {
                    secondaryVibrator?.vibrate(VibrationEffect.createWaveform(sTimings, sAmps, -1))
                } catch (e: Exception) {
                    Log.e(TAG, "Stereo secondary FAILED: ${e.message}")
                }
            } else {
                val sTimings = LongArray(secondarySegments.size) { secondarySegments[it].first }
                val sAmps = IntArray(secondarySegments.size) {
                    if (useAmpCtrl) secondarySegments[it].second else VibrationEffect.DEFAULT_AMPLITUDE
                }
                try {
                    secondaryVibrator?.vibrate(VibrationEffect.createWaveform(sTimings, sAmps, -1))
                } catch (e: Exception) {
                    Log.e(TAG, "Stereo secondary FAILED: ${e.message}")
                }
            }
        }
    }

    val hasStereoVibrator: Boolean get() = hasSecondaryVibrator

    
    fun performEnvelope(segments: List<Pair<Long, Int>>) {
        if (paused) {
            android.util.Log.w(TAG, "performEnvelope SKIPPED: paused=true")
            return
        }
        if (segments.isEmpty()) return

        val forceDef = forceDefaultAmplitude
        val useAmpCtrl = hasAmplitudeControl && !forceDef

        if (forceDef && directVibrator != null && hasDirectVibrator) {
            
            
            try {
                val timings = LongArray(segments.size) { segments[it].first.coerceAtLeast(1L) }
                val amplitudes = IntArray(segments.size) { VibrationEffect.DEFAULT_AMPLITUDE }
                Log.i(TAG, "performEnvelope(forceDef): ${timings.size} segments total=${timings.sum()}ms DEFAULT_AMPLITUDE")
                directVibrator!!.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                return
            } catch (e: Exception) {
                Log.e(TAG, "performEnvelope (forceDefault) FAILED: ${e.message}")
            }
        }

        if (!useAmpCtrl) {
            
            val totalDur = segments.sumOf { it.first }.coerceAtMost(100L)
            val maxAmp = segments.maxOfOrNull { it.second } ?: VibrationEffect.DEFAULT_AMPLITUDE
            Log.i(TAG, "performEnvelope(noAmpCtrl): fallback oneShot(${totalDur}ms, amp=$maxAmp)")
            performOneShot(totalDur, maxAmp)
            return
        }

        
        if (useProxy) {
            val b = remoteBinder ?: return
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    val timings = LongArray(segments.size) { segments[it].first.coerceAtLeast(1L) }
                    val amplitudes = IntArray(segments.size) { segments[it].second.coerceIn(1, 255) }
                    data.writeLongArray(timings)
                    data.writeIntArray(amplitudes)
                    b.transact(VibrateProxyService.CODE_PERFORM_WAVEFORM, data, reply, 0)
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (e: Exception) { Log.w(TAG, "IPC performEnvelope: ${e.message}") }
        } else {
            val vib = directVibrator
            if (vib != null && hasDirectVibrator) {
                try {
                    val timings = LongArray(segments.size) { segments[it].first.coerceAtLeast(1L) }
                    val amplitudes = IntArray(segments.size) { segments[it].second.coerceIn(1, 255) }
                    Log.i(TAG, "performEnvelope: ${timings.size} segments total=${timings.sum()}ms ampCtrl=$hasAmplitudeControl")
                    vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } catch (e: Exception) {
                    Log.e(TAG, "performEnvelope FAILED: ${e.message}", e)
                    
                    val totalDur = segments.sumOf { it.first }.coerceAtMost(100L)
                    val maxAmp = segments.maxOfOrNull { it.second } ?: 200
                    performOneShot(totalDur, maxAmp)
                }
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

    fun performComposition(
        primitives: List<Triple<Int, Float, Int>>
    ) {
        if (paused) return
        if (primitives.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
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
                    val avgScale = primitives.map { it.second }.avg()
                    performOneShot(20L, (avgScale * 255).toInt().coerceIn(1, 255))
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