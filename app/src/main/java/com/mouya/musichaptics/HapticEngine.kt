package com.mouya.musichaptics

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.os.VibrationEffect
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

import com.mouya.musichaptics.LinkHealthMonitor
import com.mouya.musichaptics.LogBroadcaster
import com.mouya.musichaptics.NativeBridge
import android.os.Build

interface LogCallback {
    fun onLog(message: String)
}

class HapticEngine(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val TAG = "HapticDSPCore"

        private const val RING_BUFFER_CAPACITY = 131072

        private const val FRAME_BLOCK_SIZE = 256

        private const val MAXIMUM_CHANNELS = 8

        private const val AMBIENT_TEMPERATURE_CELSIUS = 25.0f
        private const val LIMITING_TEMPERATURE_CELSIUS = 80.0f
        private const val CRITICAL_TEMPERATURE_CELSIUS = 100.0f

        const val SUB_BASS_LOW = 20f
        const val SUB_BASS_HIGH = 80f
        const val MID_BASS_LOW = 80f
        const val MID_BASS_HIGH = 200f
        const val TEXTURE_LOW = 200f
        const val TEXTURE_HIGH = 800f

        val WAVE_SUB_BASS_IMPACT = floatArrayOf(1.0f, 0.95f, 0.85f, 0.70f, 0.50f, 0.30f, 0.15f, 0.05f)
        val WAVE_MID_TRANSIENT  = floatArrayOf(1.0f, 0.60f, 0.20f, 0.05f)
        val WAVE_MICRO_TEXTURE  = floatArrayOf(0.4f, 0.80f, 0.40f, 0.10f, 0.60f, 0.20f)
    }

    enum class HapticPreset(val id: Int, val description: String) {
        BALANCED(0, "标准平衡模式"),
        BASS_ENHANCED(1, "重低音增强 (Sub-Bass Emphasized)"),
        TEXTURE_FOCUS(2, "高频微震纹理 (Micro-Texture Focus)"),
        IMPACT_MAX(3, "极致冲击爆发 (Maximum Transient Attack)"),
        CUSTOM(4, "自定义调校 (Custom Parameters)")
    }

    private val nativeBridge = NativeBridge()

    private val directPcmBuffer: ByteBuffer = ByteBuffer.allocateDirect(FRAME_BLOCK_SIZE * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val floatPcmView: FloatBuffer = directPcmBuffer.asFloatBuffer()

    private val nativeTelemetryResult = FloatArray(10)

    val hapticEventGenerator = HapticEventGenerator(context, detectDeviceProfile())

    val hapticComposer = HapticComposer(context, detectDeviceProfile(), prefs)

    private val hapticSynthesizer = HapticSynthesizer(detectDeviceProfile())

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineJob = engineScope.coroutineContext[Job]!!

    var logCallback: LogCallback? = null

    private var sampleRate = 48000
    private var channels = 2

    private val audioRingBuffer = AudioFifoBuffer(RING_BUFFER_CAPACITY)
    private val processingFrame = FloatArray(FRAME_BLOCK_SIZE)

    private val isEngineEnabled = AtomicBoolean(true)
    private val frameIndexCounter = AtomicLong(0)
    private var lastParameterUpdateTime = 0L

    // === Continuous Waveform State ===
    @Volatile private var directDriveSmoothAmp = 0f  // Smoothed amplitude for telemetry display

    // v2.1: Native scheduler state — must be declared before init block
    @Volatile private var nativeSchedulerActive = false
    @Volatile private var nativeLastAudioTime = 0L  // Tracked from native callback for silence detection

    // ══════════════════════════════════════════════════════════════════
    // v1.8 Haptic Fusion: Semantic primitive bridge
    // The Composer produces HapticCommands at ~50Hz. The latest command
    // is stored here and consumed by runContinuousHapticLoop at 40ms intervals.
    // Primitive events (Impact/Pulse/Texture) are overlaid on top of the
    // C++ continuous waveform — not replacing it.
    // ══════════════════════════════════════════════════════════════════
    @Volatile private var pendingPrimitive: HapticPrimitive? = null
    @Volatile private var pendingSemanticLabel: String = "NONE"
    @Volatile private var pendingPrimitiveTime: Long = 0L

    val telemetryData = TelemetryMonitor()

    // Channels and DspFrameData removed — C++ 5-layer synthesizer handles all synthesis internally

    init {

        synchronizeParameters()

        // v2.1: Wire native scheduler callback — C++ thread becomes SOLE ring buffer consumer
        if (nativeBridge.isLoaded) {
            nativeBridge.onFrameCallback = { samples, count ->
                onNativeHapticFrame(samples, count)
            }
            val started = nativeBridge.startScheduler()
            nativeSchedulerActive = started
            Log.i(TAG, "Native Haptic Scheduler: ${if (started) "STARTED (10ms precision, sole consumer)" else "FAILED — falling back to coroutine"}")
        }

        // v2.1: Only launch coroutine loop if native scheduler is NOT active
        // This prevents double-consumption of the ring buffer
        if (!nativeSchedulerActive) {
            engineScope.launch {
                runContinuousHapticLoop()
            }
            Log.i(TAG, "Using coroutine-based haptic loop (fallback mode)")
        }

        val readyMsg = "[System Ready] v3.7 Haptic Engine: ${if (nativeBridge.isLoaded) "NATIVE ACTIVE" else "FALLBACK"} | Device: ${hapticEventGenerator.profile.name} | Actuator: ${hapticEventGenerator.profile.actuator.resonanceFreq.toInt()}Hz rise=${hapticEventGenerator.profile.actuator.riseTimeMs.toInt()}ms fall=${hapticEventGenerator.profile.actuator.fallTimeMs.toInt()}ms | C++ 4-Layer: Beat+Bass+Texture(Noise)+Melody | Semantic Bridge: ON | Fusion: Dual-Track (Semantic+Continuous) | Scheduler: ${if (nativeSchedulerActive) "NATIVE (10ms)" else "COROUTINE (40ms fallback)"}"
        Log.i(TAG, readyMsg)
        logCallback?.onLog(readyMsg)
        LogBroadcaster.sendLog(context, readyMsg)
    }

    /**
     * Continuous Haptic Loop — pulls continuous amplitude frames from C++ 5-layer
     * synthesizer and plays them via VibrationEffect.createWaveform.
     *
     * This replaces the old discrete pulse generation with a pull-based
     * continuous waveform playback model.
     *
     * Architecture:
     * - C++ HapticEngine.hpp processes audio and fills a ring buffer with continuous
     *   amplitude samples (0-255) from 5-layer synthesis (Beat/Bass/Texture/Melody/Emotion)
     * - C++ produces samples at 100Hz (10ms each), matching Kotlin playback timing exactly
     * - This loop pulls up to 4 samples per 40ms interval → 40ms of vibration per pull
     * - Uses VibrationEffect.createWaveform with repeat=-1 (one-shot, no looping)
     * - Thermal safety and user gain are applied in C++ layer
     */
    private suspend fun runContinuousHapticLoop() {
        val pullIntervalMs = 40L       // Frame pull interval — 40ms for low latency
        val sampleDurationMs = 10L     // Each amplitude sample → 10ms of vibration (matches C++ 100Hz)
        val maxSamplesPerPull = 4       // 4 samples × 10ms = 40ms playback per pull
        val frameBuffer = FloatArray(maxSamplesPerPull)

        // ── Multi-primitive fusion: HapticFeedbackEngine for discrete primitives ──
        val feedbackEngine = HapticFeedbackEngine.create(context)
        val isApi29Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        // ── Primitive trigger state ──
        var lastKickTime = 0L           // Debounce: minimum 80ms between KICK triggers
        var lastShortTickTime = 0L      // Debounce: minimum 40ms between short ticks
        val kickRefractoryMs = 80L
        val shortTickRefractoryMs = 40L

        // v1.8: Semantic primitive refractory — prevent double-firing
        var lastSemanticImpactTime = 0L
        val semanticImpactRefractoryMs = 60L   // Min 60ms between semantic impacts

        // ── Primitive classification thresholds (0-255 scale) ──
        val kickThreshold = 200         // >200 → KICK (EFFECT_HEAVY_CLICK)
        val longVibeThreshold = 100      // 100-200 → sustained vibration (createOneShot)
        // <100 → short tick (EFFECT_TICK or rapid createOneShot)

        var frameCounter = 0L
        var lastAudioInputTime = 0L
        val silenceTimeoutMs = 300L

        while (true) {
            val frameStartTime = SystemClock.elapsedRealtime()

            try {
                // Pull continuous amplitude frame from C++ 5-layer synthesizer
                val sampleCount = if (nativeBridge.isLoaded) {
                    nativeBridge.getHapticFrame(frameBuffer, maxSamplesPerPull)
                } else {
                    0
                }

                // Track audio activity for silence detection
                if (sampleCount > 0) {
                    val maxAmp = (0 until sampleCount).maxOfOrNull { frameBuffer[it] } ?: 0f
                    if (maxAmp > 1f) {
                        lastAudioInputTime = frameStartTime
                    }
                }

                val timeSinceAudio = frameStartTime - lastAudioInputTime
                val hasAudioActivity = timeSinceAudio < silenceTimeoutMs

                if (hasAudioActivity && sampleCount > 0) {
                    val maxAmp = (0 until sampleCount).maxOfOrNull { frameBuffer[it] } ?: 0f
                    if (maxAmp > 2) {
                        // ═══ v1.8 Haptic Fusion ═══
                        // Two-layer fusion: C++ continuous waveform + Composer semantic primitives
                        //
                        // Layer 1 (Continuous): C++ amplitude → waveform body (rumble/sustain)
                        // Layer 2 (Semantic):   Composer primitive → discrete impact (punch/texture)
                        //
                        // Fusion strategy:
                        // - If Composer has a pending semantic Impact primitive (KICK_DRUM/SUB_STRIKE/etc.),
                        //   fire it as a discrete predefined effect, and REDUCE waveform body to 40%
                        //   so the LRA isn't overloaded. This gives "punch + rumble" feel.
                        // - If Composer has a Texture primitive, fire TICK and keep waveform at 80%.
                        // - If no semantic primitive pending, fall back to amplitude-threshold
                        //   classification (legacy v1.7 behavior).

                        val vib = hapticEventGenerator.getVibratorInstance()
                        if (vib != null && hapticEventGenerator.hasVibrator) {

                            // ── Check for semantic primitive from Composer ──
                            val semanticPrim = pendingPrimitive
                            val semanticAge = frameStartTime - pendingPrimitiveTime
                            val semanticFresh = semanticPrim != null && semanticAge < 100L  // 100ms freshness window

                            var kickTriggered = false
                            var longVibeAmp = 0
                            var shortTickTriggered = false
                            var semanticFired = false

                            if (semanticFresh) {
                                // semanticPrim guaranteed non-null by semanticFresh check
                                val prim = semanticPrim!!
                                val timeSinceSemantic = frameStartTime - lastSemanticImpactTime
                                if (timeSinceSemantic >= semanticImpactRefractoryMs) {
                                    when (prim) {
                                        is HapticPrimitive.Impact -> {
                                            // Semantic impact: fire predefined KICK or IMPACT based on sharpness
                                            val style = if (prim.sharpness > 0.7f && prim.intensity > 150) {
                                                kickTriggered = true
                                                lastKickTime = frameStartTime
                                                HapticFeedbackEngine.HapticStyle.KICK
                                            } else {
                                                HapticFeedbackEngine.HapticStyle.IMPACT
                                            }
                                            feedbackEngine.perform(style)
                                            semanticFired = true
                                            lastSemanticImpactTime = frameStartTime
                                        }
                                        is HapticPrimitive.Pulse -> {
                                            // Rhythmic pulse: fire as KICK (predefined heavy click)
                                            // The repeat count is handled by natural beat recurrence
                                            feedbackEngine.perform(HapticFeedbackEngine.HapticStyle.KICK)
                                            kickTriggered = true
                                            lastKickTime = frameStartTime
                                            semanticFired = true
                                            lastSemanticImpactTime = frameStartTime
                                        }
                                        is HapticPrimitive.Texture -> {
                                            // Texture: fire TICK for micro-texture
                                            shortTickTriggered = true
                                            lastShortTickTime = frameStartTime
                                            semanticFired = true
                                            lastSemanticImpactTime = frameStartTime
                                        }
                                        is HapticPrimitive.Wave -> {
                                            // Wave: let continuous waveform handle it
                                        }
                                    }
                                    // Clear pending primitive after consumption
                                    pendingPrimitive = null
                                }
                            }

                            // ── Amplitude-threshold fallback (when no semantic primitive) ──
                            if (!semanticFired) {
                                for (i in 0 until sampleCount) {
                                    val amp = frameBuffer[i].toInt().coerceIn(0, 255)
                                    when {
                                        amp > kickThreshold && !kickTriggered -> {
                                            val timeSinceKick = frameStartTime - lastKickTime
                                            if (timeSinceKick >= kickRefractoryMs) {
                                                kickTriggered = true
                                                lastKickTime = frameStartTime
                                                feedbackEngine.perform(HapticFeedbackEngine.HapticStyle.KICK)
                                            }
                                        }
                                        amp >= longVibeThreshold -> {
                                            longVibeAmp = maxOf(longVibeAmp, amp)
                                        }
                                        amp in 1..99 -> {
                                            val timeSinceTick = frameStartTime - lastShortTickTime
                                            if (timeSinceTick >= shortTickRefractoryMs && !shortTickTriggered) {
                                                shortTickTriggered = true
                                                lastShortTickTime = frameStartTime
                                            }
                                        }
                                    }
                                }
                            }

                            // ── Waveform body: continuous layer with semantic-aware scaling ──
                            // When a semantic Impact/KICK fired, reduce body to 40% to let the
                            // discrete punch breathe. When Texture fired, keep at 80%.
                            // Otherwise full amplitude.
                            val bodyAmpScale = when {
                                kickTriggered -> 0.4f    // Heavy impact: reduce body significantly
                                shortTickTriggered -> 0.8f  // Texture: slight reduction
                                else -> 1.0f
                            }
                            if (longVibeAmp > 0 || !kickTriggered) {
                                val timings = LongArray(sampleCount) { sampleDurationMs }
                                val amplitudes = IntArray(sampleCount) { idx ->
                                    (frameBuffer[idx] * bodyAmpScale).toInt().coerceIn(0, 255)
                                }

                                val bodyMax = amplitudes.maxOrNull() ?: 0
                                if (bodyMax > 20) {
                                    try {
                                        val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                                        vib.vibrate(effect)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Waveform body playback failed: ${e.message}")
                                    }
                                }
                            }

                            // ── Fire short tick for texture ──
                            if (shortTickTriggered && !kickTriggered) {
                                try {
                                    if (isApi29Plus) {
                                        val tick = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                                        vib.vibrate(tick)
                                    } else {
                                        vib.vibrate(VibrationEffect.createOneShot(8, 80))
                                    }
                                } catch (_: Exception) {}
                            }

                            LinkHealthMonitor.heartbeatVibrateCall()
                            val normalizedMaxAmp = maxAmp / 255.0f
                            directDriveSmoothAmp = directDriveSmoothAmp * 0.5f + normalizedMaxAmp * 0.5f

                            if (frameCounter % 30L == 0L) {
                                val ampStr = (0 until sampleCount).joinToString(",") { frameBuffer[it].toInt().coerceIn(0, 255).toString() }
                                val fusionMode = if (semanticFired) "SEMANTIC(${pendingSemanticLabel})" else "AMPLITUDE"
                                val primitive = when {
                                    kickTriggered -> "KICK"
                                    shortTickTriggered -> "TICK"
                                    longVibeAmp > 0 -> "LONG"
                                    else -> "SILENCE"
                                }
                                Log.i(TAG, "▶ FUSION v3.7 | mode=$fusionMode prim=$primitive | samples=$sampleCount amps=[$ampStr] max=${frameBuffer.maxOrNull()?.toInt() ?: 0} smooth=$directDriveSmoothAmp bodyScale=$bodyAmpScale")
                            }
                        }
                    } else {
                        // Near-silence from C++ (amplitude ≤ 2): cancel ongoing vibration
                        hapticEventGenerator.cancel()
                        directDriveSmoothAmp *= 0.3f  // Fast decay
                    }
                } else if (timeSinceAudio >= silenceTimeoutMs) {
                    // Silence timeout — ensure vibrator stops
                    if (frameCounter % 30L == 0L) {
                        hapticEventGenerator.cancel()
                    }
                    directDriveSmoothAmp = 0f
                }

                LinkHealthMonitor.heartbeatTelemetry()

                if (frameCounter % 60L == 0L) {
                    synchronizeParameters()
                }

                // Periodic telemetry output (every ~600ms at 50ms intervals)
                if (frameCounter % 12L == 0L) {
                    val latency = SystemClock.elapsedRealtime() - frameStartTime
                    telemetryData.frameLatencyMs = latency

                    val subLevel = telemetryData.subBassOutputLevel
                    val midLevel = telemetryData.midBassOutputLevel
                    val texLevel = telemetryData.presenceOutputLevel
                    val f0 = telemetryData.fundamentalFrequencyHz
                    val temp = telemetryData.estimatedCoilTemperature
                    val thermalGain = telemetryData.thermalAttenuationFactor

                    val logMsg = String.format(
                        "DSP v3.7 [Fusion] | S:%.2f M:%.2f T:%.2f | F0:%.0fHz samples=%d smooth=%.2f Δ=%dms",
                        subLevel, midLevel, texLevel, f0, sampleCount, directDriveSmoothAmp, latency
                    )
                    logCallback?.onLog(logMsg)
                    LogBroadcaster.sendLog(context, logMsg)
                    telemetryData.dispatchedSubBassImpacts++

                    LogBroadcaster.sendTelemetry(
                        context = context,
                        sub = subLevel,
                        mid = midLevel,
                        pres = texLevel,
                        f0 = f0,
                        temp = temp,
                        atten = thermalGain,
                        latency = latency,
                        loFreq = telemetryData.lowPassCutoffHz,
                        hiFreq = telemetryData.highPassCutoffHz,
                        ampScale = telemetryData.userAmplitudeScale,
                        overruns = telemetryData.ringBufferOverruns,
                        subCount = telemetryData.dispatchedSubBassImpacts,
                        midCount = telemetryData.dispatchedMidBassTransients,
                        texCount = telemetryData.dispatchedMicroTextures,
                        keyStrikeActive = hapticComposer.lastKeyStrikeActive,
                        keyStrikeSemantic = hapticComposer.lastKeyStrikeSemantic,
                        semanticType = hapticComposer.lastSemanticType,
                        lraDisp = hapticComposer.lastDisplacement,
                        lraVel = hapticComposer.lastVelocity,
                        lraForce = hapticComposer.lastForce,
                        lraPhase = hapticComposer.lastPhase,
                        adsrEnv = hapticComposer.lastEnvelope,
                        thermalGain = hapticComposer.lastThermalGain,
                        personaName = hapticComposer.currentPersona.displayName,
                        primitiveType = hapticComposer.lastPrimitive?.typeName ?: "",
                        primitiveSemantic = hapticComposer.lastSemanticEvent?.label ?: "",
                        primitiveIntensity = hapticComposer.lastPrimitive?.let { when(it) { is HapticPrimitive.Impact -> it.intensity; is HapticPrimitive.Pulse -> it.intensity; is HapticPrimitive.Texture -> it.intensity; is HapticPrimitive.Wave -> 0 } } ?: 0,
                        primitiveDuration = hapticComposer.lastPrimitive?.let { when(it) { is HapticPrimitive.Impact -> it.durationMs; is HapticPrimitive.Pulse -> it.periodMs; is HapticPrimitive.Texture -> it.durationMs; is HapticPrimitive.Wave -> it.durationMs } } ?: 0,
                        gammaValue = hapticComposer.getEffectiveGamma()
                    )
                }

                frameCounter++
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Coroutine cancelled (e.g. release() called) — exit gracefully
                Log.i(TAG, "Continuous haptic loop cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Continuous haptic loop error: ${e.message}")
            }

            // Sleep until next pull interval
            val elapsed = SystemClock.elapsedRealtime() - frameStartTime
            val sleepMs = (pullIntervalMs - elapsed).coerceIn(1L, pullIntervalMs)
            try {
                kotlinx.coroutines.delay(sleepMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Continuous haptic loop cancelled during sleep")
                break
            }
        }
    }

// playWaveformSegment and generateDirectDriveSegment removed — replaced by runContinuousHapticLoop
    // which pulls continuous amplitude frames from C++ 5-layer synthesizer and plays them
    // directly via VibrationEffect.createWaveform, eliminating discrete on-off pulse generation.

    // updateSynthesizerTelemetry removed — C++ 5-layer engine handles all synthesis internally.
    // Telemetry is now updated directly in executeDspPipeline from nativeTelemetryResult.

    // ════════════════════════════════════════════════════════════════
    //  v2.1: Native Haptic Frame Callback
    //  Called from the C++ scheduler thread at 20ms intervals.
    //  Receives a batch of amplitude samples and immediately drives the vibrator.
    //  This bypasses coroutine delay entirely — the native thread uses
    //  clock_nanosleep(CLOCK_MONOTONIC) for precise 10ms timing.
    // ════════════════════════════════════════════════════════════════
    private val nativeFrameFeedbackEngine = lazy { HapticFeedbackEngine.create(context) }
    private val nativeFrameVib by lazy { hapticEventGenerator.getVibratorInstance() }
    private val nativeFrameIsApi29Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    private var nativeFrameLastKick = 0L
    private var nativeFrameLastTick = 0L
    private var nativeFrameLastSemantic = 0L
    private var nativeFrameCounter = 0L

    private fun onNativeHapticFrame(samples: FloatArray, count: Int) {
        if (count <= 0) return

        val now = SystemClock.elapsedRealtime()
        nativeFrameCounter++
        nativeLastAudioTime = now  // Track for silence detection

        val maxAmp = (0 until count).maxOfOrNull { samples[it] } ?: 0f
        if (maxAmp <= 2f) {
            // Near-silence: stop vibration
            if (nativeFrameCounter % 50L == 0L) hapticEventGenerator.cancel()
            directDriveSmoothAmp *= 0.3f  // Fast decay on silence
            return
        }

        val vib = nativeFrameVib
        if (vib == null || !hapticEventGenerator.hasVibrator) return

        // ── Semantic primitive check (same logic as runContinuousHapticLoop) ──
        val semanticPrim = pendingPrimitive
        val semanticAge = now - pendingPrimitiveTime
        val semanticFresh = semanticPrim != null && semanticAge < 100L

        var kickTriggered = false
        var shortTickTriggered = false
        var semanticFired = false

        if (semanticFresh) {
            val prim = semanticPrim!!
            val timeSinceSemantic = now - nativeFrameLastSemantic
            if (timeSinceSemantic >= 60L) {
                when (prim) {
                    is HapticPrimitive.Impact -> {
                        if (prim.sharpness > 0.7f && prim.intensity > 150) {
                            kickTriggered = true
                            nativeFrameLastKick = now
                            nativeFrameFeedbackEngine.value.perform(HapticFeedbackEngine.HapticStyle.KICK)
                        } else {
                            nativeFrameFeedbackEngine.value.perform(HapticFeedbackEngine.HapticStyle.IMPACT)
                        }
                        semanticFired = true
                        nativeFrameLastSemantic = now
                    }
                    is HapticPrimitive.Pulse -> {
                        nativeFrameFeedbackEngine.value.perform(HapticFeedbackEngine.HapticStyle.KICK)
                        kickTriggered = true
                        nativeFrameLastKick = now
                        semanticFired = true
                        nativeFrameLastSemantic = now
                    }
                    is HapticPrimitive.Texture -> {
                        shortTickTriggered = true
                        nativeFrameLastTick = now
                        semanticFired = true
                        nativeFrameLastSemantic = now
                    }
                    is HapticPrimitive.Wave -> { /* let waveform handle it */ }
                }
                pendingPrimitive = null
            }
        }

        // ── Amplitude-threshold fallback ──
        if (!semanticFired) {
            for (i in 0 until count) {
                val amp = samples[i].toInt().coerceIn(0, 255)
                when {
                    amp > 200 && (now - nativeFrameLastKick) >= 80L -> {
                        kickTriggered = true
                        nativeFrameLastKick = now
                        nativeFrameFeedbackEngine.value.perform(HapticFeedbackEngine.HapticStyle.KICK)
                    }
                    amp in 1..99 && (now - nativeFrameLastTick) >= 40L && !shortTickTriggered -> {
                        shortTickTriggered = true
                        nativeFrameLastTick = now
                    }
                }
            }
        }

        // ── Waveform body with semantic-aware scaling ──
        val bodyScale = when {
            kickTriggered -> 0.4f
            shortTickTriggered -> 0.8f
            else -> 1.0f
        }
        val timings = LongArray(count) { 10L }
        val amplitudes = IntArray(count) { idx ->
            (samples[idx] * bodyScale).toInt().coerceIn(0, 255)
        }
        val bodyMax = amplitudes.maxOrNull() ?: 0
        if (bodyMax > 20) {
            try {
                vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } catch (e: Exception) {
                Log.w(TAG, "Native frame playback failed: ${e.message}")
            }
        }

        // ── Short tick for texture ──
        if (shortTickTriggered && !kickTriggered) {
            try {
                if (nativeFrameIsApi29Plus) {
                    vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                } else {
                    vib.vibrate(VibrationEffect.createOneShot(8, 80))
                }
            } catch (_: Exception) {}
        }

        LinkHealthMonitor.heartbeatVibrateCall()

        // Smooth amplitude for telemetry display
        val normalizedMaxAmp = maxAmp / 255.0f
        directDriveSmoothAmp = directDriveSmoothAmp * 0.5f + normalizedMaxAmp * 0.5f

        // Periodic parameter sync (every ~2s at 20ms intervals ≈ 100 frames)
        if (nativeFrameCounter % 100L == 0L) {
            synchronizeParameters()
        }

        // Periodic telemetry output (every ~1s ≈ 50 frames)
        if (nativeFrameCounter % 50L == 0L) {
            val mode = if (semanticFired) "SEMANTIC" else "AMP"
            val prim = when {
                kickTriggered -> "KICK"
                shortTickTriggered -> "TICK"
                else -> "BODY"
            }
            Log.i(TAG, "▶ NATIVE v3.7 | mode=$mode prim=$prim cnt=$count max=${maxAmp.toInt()} scale=$bodyScale smooth=${"%.2f".format(directDriveSmoothAmp)}")

            // Send telemetry broadcast
            LogBroadcaster.sendTelemetry(
                context = context,
                sub = telemetryData.subBassOutputLevel,
                mid = telemetryData.midBassOutputLevel,
                pres = telemetryData.presenceOutputLevel,
                f0 = telemetryData.fundamentalFrequencyHz,
                temp = telemetryData.estimatedCoilTemperature,
                atten = telemetryData.thermalAttenuationFactor,
                latency = 20L,  // Native scheduler: 20ms fixed latency
                loFreq = telemetryData.lowPassCutoffHz,
                hiFreq = telemetryData.highPassCutoffHz,
                ampScale = telemetryData.userAmplitudeScale,
                overruns = telemetryData.ringBufferOverruns,
                subCount = telemetryData.dispatchedSubBassImpacts,
                midCount = telemetryData.dispatchedMidBassTransients,
                texCount = telemetryData.dispatchedMicroTextures,
                keyStrikeActive = hapticComposer.lastKeyStrikeActive,
                keyStrikeSemantic = hapticComposer.lastKeyStrikeSemantic,
                semanticType = hapticComposer.lastSemanticType,
                lraDisp = hapticComposer.lastDisplacement,
                lraVel = hapticComposer.lastVelocity,
                lraForce = hapticComposer.lastForce,
                lraPhase = hapticComposer.lastPhase,
                adsrEnv = hapticComposer.lastEnvelope,
                thermalGain = hapticComposer.lastThermalGain,
                personaName = hapticComposer.currentPersona.displayName,
                primitiveType = hapticComposer.lastPrimitive?.typeName ?: "",
                primitiveSemantic = hapticComposer.lastSemanticEvent?.label ?: "",
                primitiveIntensity = hapticComposer.lastPrimitive?.let { when(it) { is HapticPrimitive.Impact -> it.intensity; is HapticPrimitive.Pulse -> it.intensity; is HapticPrimitive.Texture -> it.intensity; is HapticPrimitive.Wave -> 0 } } ?: 0,
                primitiveDuration = hapticComposer.lastPrimitive?.let { when(it) { is HapticPrimitive.Impact -> it.durationMs; is HapticPrimitive.Pulse -> it.periodMs; is HapticPrimitive.Texture -> it.durationMs; is HapticPrimitive.Wave -> it.durationMs } } ?: 0,
                gammaValue = hapticComposer.getEffectiveGamma()
            )
        }
    }

    fun synchronizeParameters() {
        val masterState = try { prefs.getBoolean("master_switch", true) } catch (e: Exception) { true }
        isEngineEnabled.set(masterState)

        val inputGain = try { prefs.getFloat("haptic_gain", 1.0f) } catch (e: Exception) { 1.0f }
        val outputAmp = try { prefs.getFloat("haptic_amplitude", 2.0f) } catch (e: Exception) { 2.0f }
        val purityParam = try { prefs.getInt("haptic_bass_purity", 50) } catch (e: Exception) { 50 }
        val presetId = try { prefs.getInt("haptic_preset_id", HapticPreset.BALANCED.id) } catch (e: Exception) { HapticPreset.BALANCED.id }

        val lowCutoffFreq = 320.0f - (purityParam.coerceIn(0, 100).toFloat() * 2.65f)
        val highCutoffFreq = lowCutoffFreq * 2.2f

        nativeBridge.configure(
            sampleRate = sampleRate.toFloat(),
            lowCut = lowCutoffFreq,
            highCut = highCutoffFreq,
            amplitude = outputAmp,
            presetId = presetId
        )

        telemetryData.lowPassCutoffHz = lowCutoffFreq
        telemetryData.highPassCutoffHz = highCutoffFreq
        telemetryData.userAmplitudeScale = outputAmp

        val boostLevel = try { prefs.getFloat("haptic_boost_level", 1.0f) } catch (e: Exception) { 1.0f }
        hapticEventGenerator.boostLevel = boostLevel
        hapticEventGenerator.userAmplitudeScale = outputAmp.coerceIn(0.5f, 4.0f)

        val silenceTh = try { prefs.getFloat("silence_threshold", Float.NaN) } catch (e: Exception) { Float.NaN }
        hapticEventGenerator.injectedSilenceThreshold = if (silenceTh.isNaN()) null else silenceTh

        val energyTh = try { prefs.getFloat("energy_threshold", Float.NaN) } catch (e: Exception) { Float.NaN }
        hapticEventGenerator.injectedEnergyThreshold = if (energyTh.isNaN()) null else energyTh

        val minAmp = try { prefs.getInt("min_amplitude", -1) } catch (e: Exception) { -1 }
        hapticEventGenerator.injectedMinGuaranteedAmplitude = if (minAmp < 0) null else minAmp

        hapticEventGenerator.synchronizeProfile(prefs)
        hapticEventGenerator.synchronizePreset(prefs)

        hapticEventGenerator.logListener = { msg ->
            logCallback?.onLog(msg)
            LogBroadcaster.sendLog(context, msg)
        }

        val synthConfig = HapticSynthesizer.SynthConfig(
            synthesisRateHz = try { prefs.getInt("synth_rate_hz", HapticSynthesizer.SYNTHESIS_RATE_HZ) } catch (e: Exception) { HapticSynthesizer.SYNTHESIS_RATE_HZ },
            lraF0 = try { prefs.getFloat("synth_lra_f0", HapticSynthesizer.LRA_F0) } catch (e: Exception) { HapticSynthesizer.LRA_F0 },
            lraQ = try { prefs.getFloat("synth_lra_q", HapticSynthesizer.LRA_Q) } catch (e: Exception) { HapticSynthesizer.LRA_Q },
            attackTauImpact = try { prefs.getFloat("synth_attack_impact", HapticSynthesizer.ATTACK_TAU_IMPACT) } catch (e: Exception) { HapticSynthesizer.ATTACK_TAU_IMPACT },
            decayTauImpact = try { prefs.getFloat("synth_decay_impact", HapticSynthesizer.DECAY_TAU_IMPACT) } catch (e: Exception) { HapticSynthesizer.DECAY_TAU_IMPACT },
            attackTauContinuous = try { prefs.getFloat("synth_attack_continuous", HapticSynthesizer.ATTACK_TAU_CONTINUOUS) } catch (e: Exception) { HapticSynthesizer.ATTACK_TAU_CONTINUOUS },
            decayTauContinuous = try { prefs.getFloat("synth_decay_continuous", HapticSynthesizer.DECAY_TAU_CONTINUOUS) } catch (e: Exception) { HapticSynthesizer.DECAY_TAU_CONTINUOUS },
            releaseTau = try { prefs.getFloat("synth_release", HapticSynthesizer.RELEASE_TAU) } catch (e: Exception) { HapticSynthesizer.RELEASE_TAU },
            sustainLevel = try { prefs.getFloat("synth_sustain", HapticSynthesizer.SUSTAIN_LEVEL) } catch (e: Exception) { HapticSynthesizer.SUSTAIN_LEVEL },
            thermalWarn = try { prefs.getFloat("synth_thermal_warn", HapticSynthesizer.THERMAL_WARN) } catch (e: Exception) { HapticSynthesizer.THERMAL_WARN },
            thermalCrit = try { prefs.getFloat("synth_thermal_crit", HapticSynthesizer.THERMAL_CRIT) } catch (e: Exception) { HapticSynthesizer.THERMAL_CRIT },
            thermalRth = try { prefs.getFloat("synth_thermal_rth", HapticSynthesizer.THERMAL_RTH) } catch (e: Exception) { HapticSynthesizer.THERMAL_RTH },
            thermalCth = try { prefs.getFloat("synth_thermal_cth", HapticSynthesizer.THERMAL_CTH) } catch (e: Exception) { HapticSynthesizer.THERMAL_CTH },
            impactGain = try { prefs.getFloat("synth_impact_gain", 1.0f) } catch (e: Exception) { 1.0f },
            continuousGain = try { prefs.getFloat("synth_continuous_gain", 1.0f) } catch (e: Exception) { 1.0f },
            textureGain = try { prefs.getFloat("synth_texture_gain", 1.0f) } catch (e: Exception) { 1.0f },
            masterGain = try { prefs.getFloat("synth_master_gain", 1.0f) } catch (e: Exception) { 1.0f },
        )
        hapticSynthesizer.updateParameters(synthConfig)
    }

    fun reconfigure(newSampleRate: Int, newChannels: Int) {
        if (newSampleRate <= 0 || newChannels <= 0 || newChannels > MAXIMUM_CHANNELS) {
            Log.w(TAG, "Reconfiguration rejected: ${newSampleRate}Hz | $newChannels Ch")
            return
        }

        if (this.sampleRate == newSampleRate && this.channels == newChannels) return

        this.sampleRate = newSampleRate
        this.channels = newChannels

        audioRingBuffer.clear()
        synchronizeParameters()

        val logMessage = "System reconfigured to: ${sampleRate}Hz | $channels Channels (Native Engine Active)"
        Log.i(TAG, logMessage)
        logCallback?.onLog(logMessage)
        LogBroadcaster.sendLog(context, logMessage)
    }

    /**
     * Called when AudioTrack.pause() or stop() is detected via Hook.
     * Immediately forces all ADSR envelopes into release state and cancels
     * any ongoing vibration, preventing the "still vibrating after pause" bug.
     */
    fun onPlaybackPaused() {
        Log.i(TAG, "[PLAYBACK PAUSED] Forcing immediate haptic decay")
        LogBroadcaster.sendLog(context, "[PLAYBACK PAUSED] Forcing immediate haptic decay")
        
        nativeBridge.clearHapticBuffer()
        directDriveSmoothAmp = 0f
        pendingPrimitive = null  // v1.8: Clear semantic bridge
        pendingSemanticLabel = "NONE"
        hapticSynthesizer.forceDecay()
        audioRingBuffer.clear()
        hapticEventGenerator.cancel()
        LinkHealthMonitor.setPlayingState(false)
    }

    fun processAudioFrame(pcmData: ShortArray?) {
        if (pcmData == null || pcmData.isEmpty() || !isEngineEnabled.get()) {
            if (!isEngineEnabled.get()) {
                audioRingBuffer.clear()
                hapticEventGenerator.cancel()
            }
            return
        }

        LinkHealthMonitor.setPlayingState(true)

        LinkHealthMonitor.heartbeatAudioInput()

        if (frameIndexCounter.get() % 50L == 0L) {
            Log.d("HapticLink", "【节点 1】音频已输入 | 采样点数: ${pcmData.size} | channels: $channels | engineEnabled: ${isEngineEnabled.get()}")
        }

        val sampleLength = pcmData.size
        val targetMonoSamples = sampleLength / channels
        if (targetMonoSamples <= 0) return

        val normalizedBuffer = FloatArray(targetMonoSamples)
        var writerOffset = 0

        try {
            when (channels) {
                1 -> {
                    val scaleFactor = 1.0f / 32768.0f
                    for (i in pcmData.indices) {
                        normalizedBuffer[writerOffset++] = pcmData[i].toFloat() * scaleFactor
                    }
                }
                2 -> {
                    var i = 0

                    val scaleFactor = 1.0f / 65536.0f
                    while (i < sampleLength - 1) {
                        normalizedBuffer[writerOffset++] = (pcmData[i].toFloat() + pcmData[i + 1].toFloat()) * scaleFactor
                        i += 2
                    }
                }
                else -> {
                    var i = 0
                    val channelNormalizationFactor = 1.0f / (channels.toFloat() * 32768.0f)
                    while (i < sampleLength - channels + 1) {
                        var matrixAccumulator = 0.0f
                        for (c in 0 until channels) {
                            matrixAccumulator += pcmData[i + c].toFloat()
                        }
                        normalizedBuffer[writerOffset++] = matrixAccumulator * channelNormalizationFactor
                        i += channels
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PCM normalization exception: ${e.message}")
            return
        }

        audioRingBuffer.write(normalizedBuffer, writerOffset)

        var processingSafetyIterations = 0
        while (audioRingBuffer.read(processingFrame, FRAME_BLOCK_SIZE)) {
            if (processingSafetyIterations++ > 64) {
                telemetryData.ringBufferOverruns++
                break
            }
            try {
                executeDspPipeline(processingFrame)
            } catch (t: Throwable) {
                Log.e(TAG, "DSP pipeline crashed: ${t.message}")
                audioRingBuffer.clear()
                break
            }
        }
    }

    private fun executeDspPipeline(block: FloatArray) {
        val currentTimeMs = SystemClock.elapsedRealtime()

        if (currentTimeMs - lastParameterUpdateTime > 300) {
            synchronizeParameters()
            lastParameterUpdateTime = currentTimeMs
        }

        val currentFrameId = frameIndexCounter.incrementAndGet()

        floatPcmView.position(0)
        floatPcmView.put(block, 0, FRAME_BLOCK_SIZE)

        nativeBridge.processAudioDirect(directPcmBuffer, FRAME_BLOCK_SIZE * 4, nativeTelemetryResult)

        val finalSubIntensity = nativeTelemetryResult[0]
        val finalMidIntensity = nativeTelemetryResult[1]
        val finalPresenceIntensity = nativeTelemetryResult[2]
        val detectedFundamentalFreq = nativeTelemetryResult[3]
        val estimatedCoilTemperature = nativeTelemetryResult[4]
        val thermalSafetyGain = nativeTelemetryResult[5]
        // v1.7 Semantic Bridge: beat/onset telemetry from C++
        val beatStrength = nativeTelemetryResult[6]
        val onsetFlag = nativeTelemetryResult[7] > 0.5f
        val beatIntervalMs = nativeTelemetryResult[8]
        val beatConfidence = nativeTelemetryResult[9]

        LinkHealthMonitor.heartbeatDspOutput()

        if (currentFrameId % 20L == 0L) {
            Log.d("HapticLink", "【节点 2】Native 输出 | Sub: $finalSubIntensity | Mid: $finalMidIntensity | Texture: $finalPresenceIntensity | F0: ${detectedFundamentalFreq}Hz | Temp: ${estimatedCoilTemperature}°C | ThermalGain: $thermalSafetyGain | Beat: ${"%.2f".format(beatStrength)} onset=$onsetFlag IBI=${beatIntervalMs.toInt()}ms conf=${"%.2f".format(beatConfidence)}")
        }

        if (currentFrameId % 12L == 0L) {
            Log.d("HapticDebug", "Sub: $finalSubIntensity | Mid: $finalMidIntensity | Temp: ${estimatedCoilTemperature}°C | ThermalGain: $thermalSafetyGain | Pitch: ${detectedFundamentalFreq}Hz | Beat: ${"%.2f".format(beatStrength)} IBI=${beatIntervalMs.toInt()}ms")
        }

        telemetryData.subBassOutputLevel = finalSubIntensity
        telemetryData.midBassOutputLevel = finalMidIntensity
        telemetryData.presenceOutputLevel = finalPresenceIntensity
        telemetryData.fundamentalFrequencyHz = detectedFundamentalFreq
        telemetryData.estimatedCoilTemperature = estimatedCoilTemperature
        telemetryData.thermalAttenuationFactor = thermalSafetyGain

        // ══════════════════════════════════════════════════════════════════
        // v1.7 Semantic Bridge: Feed DSP output to HapticComposer
        // Composer runs at reduced rate (every 2nd frame = ~50Hz at 100Hz DSP)
        // It analyzes the music semantically but does NOT directly drive vibration.
        // Output is logged for verification — no haptic changes yet.
        // ══════════════════════════════════════════════════════════════════
        if (isEngineEnabled.get()) {
            if (thermalSafetyGain <= 0.01f) {
                nativeBridge.clearHapticBuffer()
                hapticEventGenerator.cancel()
                directDriveSmoothAmp = 0f
            }

            // Feed Composer every 2nd frame (~50Hz, matching human semantic resolution)
            if (currentFrameId % 2L == 0L) {
                try {
                    hapticComposer.processFrame(
                        subBass = finalSubIntensity,
                        midBass = finalMidIntensity,
                        texture = finalPresenceIntensity,
                        pitch = detectedFundamentalFreq,
                        timestamp = currentTimeMs
                    )

                    // Non-blocking poll for HapticCommand from Composer
                    // Drain channel: take latest, discard stale ones to prevent backlog
                    var latestCommand: HapticCommand? = null
                    while (true) {
                        val cmd = hapticComposer.hapticCommands.tryReceive().getOrNull() ?: break
                        latestCommand = cmd
                    }
                    val command = latestCommand
                    if (command != null) {
                        // v1.8: Store primitive for Fusion layer in runContinuousHapticLoop
                        // Only store non-null primitives — waveform body handles continuous
                        if (command.primitive != null) {
                            pendingPrimitive = command.primitive
                            pendingSemanticLabel = command.semanticEvent?.label ?: "UNKNOWN"
                            pendingPrimitiveTime = currentTimeMs
                        }

                        // Log semantic detection (reduced rate)
                        if (currentFrameId % 10L == 0L) {
                            val beatStr = if (command.isBeat) "BEAT" else "---"
                            val ksStr = if (command.isKeyStrike) "KS=${command.keyStrikeSemantic.name}" else ""
                            val primStr = command.primitive?.typeName ?: "none"
                            val semStr = command.semanticEvent?.label ?: "none"
                            Log.i("SemanticBridge", "▶ Composer | $beatStr $ksStr | Sem=$semStr | Prim=$primStr | I=${"%.2f".format(command.intensity)} | Persona=${hapticComposer.currentPersona.name} | Env=${"%.2f".format(command.adsrEnvelope)} | C++Beat=${"%.2f".format(beatStrength)} IBI=${beatIntervalMs.toInt()}ms")
                            LogBroadcaster.sendLog(context, "SemanticBridge | $beatStr $ksStr | Sem=$semStr | Prim=$primStr | Persona=${hapticComposer.currentPersona.name} | C++IBI=${beatIntervalMs.toInt()}ms conf=${"%.2f".format(beatConfidence)}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Composer processFrame error: ${e.message}")
                }
            }
        }
    }

    fun release() {

        // v2.1: Stop native scheduler first — pthread_join ensures clean exit
        if (nativeSchedulerActive) {
            nativeBridge.stopScheduler()
            nativeSchedulerActive = false
            Log.i(TAG, "Native Haptic Scheduler stopped (pthread_join complete).")
        }

        LinkHealthMonitor.setPlayingState(false)
        hapticEventGenerator.release()
        hapticComposer.release()
        hapticSynthesizer.reset()
        engineJob.cancel()
        audioRingBuffer.clear()
        nativeBridge.release()
        Log.i(TAG, "DSP Engine successfully shutdown.")
    }

    class TelemetryMonitor {
        @Volatile var lowPassCutoffHz = 0.0f
        @Volatile var highPassCutoffHz = 0.0f
        @Volatile var userAmplitudeScale = 1.0f
        @Volatile var fundamentalFrequencyHz = 0.0f
        @Volatile var estimatedCoilTemperature = AMBIENT_TEMPERATURE_CELSIUS
        @Volatile var thermalAttenuationFactor = 1.0f
        @Volatile var subBassOutputLevel = 0.0f
        @Volatile var midBassOutputLevel = 0.0f
        @Volatile var presenceOutputLevel = 0.0f
        @Volatile var ringBufferOverruns = 0L
        @Volatile var dispatchedSubBassImpacts = 0L
        @Volatile var dispatchedMidBassTransients = 0L
        @Volatile var dispatchedMicroTextures = 0L
        @Volatile var frameLatencyMs = 0L

        @Volatile var lraDisplacement = 0f
        @Volatile var lraVelocity = 0f
        @Volatile var lraForce = 0f
        @Volatile var lraPhase = 0f
        @Volatile var adsrEnvelope = 0f
        @Volatile var coilTemperature = 25f
        @Volatile var thermalGain = 1f
    }
}