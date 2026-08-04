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

    private val nativeTelemetryResult = FloatArray(6)

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

    val telemetryData = TelemetryMonitor()

    // Channels and DspFrameData removed — C++ 5-layer synthesizer handles all synthesis internally

    init {

        synchronizeParameters()

        engineScope.launch {
            runContinuousHapticLoop()
        }

        val readyMsg = "[System Ready] Continuous Haptic Engine: ${if (nativeBridge.isLoaded) "ACTIVE" else "FALLBACK"} | Device: ${hapticEventGenerator.profile.name} | 5-Layer Composer: C++ Native | Mode: Continuous Waveform"
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
                        // ═══ Multi-Primitive Fusion ═══
                        // Classify each sample into a primitive type and trigger accordingly.
                        // Strategy: Mix discrete primitives (KICK, TICK) with continuous waveform body.
                        //
                        // 1. If any sample > kickThreshold → fire KICK (EFFECT_HEAVY_CLICK) via HapticFeedbackEngine
                        // 2. For sustained mid-range (100-200) → createOneShot as "body" vibration
                        // 3. For low-range (<100) fire short TICK primitives for texture
                        // 4. Fall back to waveform for the remainder to maintain continuity

                        val vib = hapticEventGenerator.getVibratorInstance()
                        if (vib != null && hapticEventGenerator.hasVibrator) {
                            var kickTriggered = false
                            var longVibeAmp = 0
                            var shortTickTriggered = false

                            // Classify samples
                            for (i in 0 until sampleCount) {
                                val amp = frameBuffer[i].toInt().coerceIn(0, 255)
                                when {
                                    amp > kickThreshold && !kickTriggered -> {
                                        // KICK: fire once per pull cycle (debounced)
                                        val timeSinceKick = frameStartTime - lastKickTime
                                        if (timeSinceKick >= kickRefractoryMs) {
                                            kickTriggered = true
                                            lastKickTime = frameStartTime
                                            feedbackEngine.perform(HapticFeedbackEngine.HapticStyle.KICK)
                                        }
                                    }
                                    amp >= longVibeThreshold -> {
                                        // Long vibration: accumulate for body
                                        longVibeAmp = maxOf(longVibeAmp, amp)
                                        // Continue — waveform body will handle this
                                    }
                                    amp in 1..99 -> {
                                        // Short tick: fire rapid TICK for texture
                                        val timeSinceTick = frameStartTime - lastShortTickTime
                                        if (timeSinceTick >= shortTickRefractoryMs && !shortTickTriggered) {
                                            shortTickTriggered = true
                                            lastShortTickTime = frameStartTime
                                        }
                                    }
                                }
                            }

                            // ── Waveform body: play a reduced-amplitude continuous waveform ──
                            // For the "body" of the vibration, use waveform at 60% of the original amplitude
                            // to avoid overwhelming the LRA when KICK is also firing.
                            // This gives the "rumble" feel while KICK provides the "punch".
                            if (!kickTriggered || longVibeAmp > 0) {
                                val bodyAmpScale = if (kickTriggered) 0.5f else 1.0f
                                val timings = LongArray(sampleCount) { sampleDurationMs }
                                val amplitudes = IntArray(sampleCount) { idx ->
                                    (frameBuffer[idx] * bodyAmpScale).toInt().coerceIn(0, 255)
                                }

                                // Only play waveform if there's meaningful amplitude in the body
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
                            // Update smoothed amplitude for telemetry display (normalized to 0-1.0)
                            val normalizedMaxAmp = maxAmp / 255.0f
                            directDriveSmoothAmp = directDriveSmoothAmp * 0.5f + normalizedMaxAmp * 0.5f

                            if (frameCounter % 30L == 0L) {
                                val ampStr = (0 until sampleCount).joinToString(",") { frameBuffer[it].toInt().coerceIn(0, 255).toString() }
                                val primitive = when {
                                    kickTriggered -> "KICK"
                                    shortTickTriggered -> "TICK"
                                    longVibeAmp > 0 -> "LONG"
                                    else -> "SILENCE"
                                }
                                Log.i(TAG, "▶ FUSION VIBRATE | samples=$sampleCount amps=[$ampStr] max=${frameBuffer.maxOrNull()?.toInt() ?: 0} smooth=$directDriveSmoothAmp primitive=$primitive")
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
                        "DSP [Fusion] | S:%.2f M:%.2f T:%.2f | F0:%.0fHz samples=%d smooth=%.2f Δ=%dms",
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
                        keyStrikeActive = false,
                        keyStrikeSemantic = "FUSION",
                        semanticType = "MULTI-PRIMITIVE",
                        lraDisp = directDriveSmoothAmp,
                        lraVel = 0f,
                        lraForce = directDriveSmoothAmp,
                        lraPhase = f0,
                        adsrEnv = directDriveSmoothAmp,
                        thermalGain = thermalGain,
                        personaName = "Fusion-MultiPrimitive",
                        primitiveType = "",
                        primitiveSemantic = "",
                        primitiveIntensity = 0,
                        primitiveDuration = 0,
                        gammaValue = 1f
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

        LinkHealthMonitor.heartbeatDspOutput()

        if (currentFrameId % 20L == 0L) {
            Log.d("HapticLink", "【节点 2】Native 输出 | Sub: $finalSubIntensity | Mid: $finalMidIntensity | Texture: $finalPresenceIntensity | F0: ${detectedFundamentalFreq}Hz | Temp: ${estimatedCoilTemperature}°C | ThermalGain: $thermalSafetyGain")
        }

        if (currentFrameId % 12L == 0L) {
            Log.d("HapticDebug", "Sub: $finalSubIntensity | Mid: $finalMidIntensity | Temp: ${estimatedCoilTemperature}°C | ThermalGain: $thermalSafetyGain | Pitch: ${detectedFundamentalFreq}Hz")
        }

        telemetryData.subBassOutputLevel = finalSubIntensity
        telemetryData.midBassOutputLevel = finalMidIntensity
        telemetryData.presenceOutputLevel = finalPresenceIntensity
        telemetryData.fundamentalFrequencyHz = detectedFundamentalFreq
        telemetryData.estimatedCoilTemperature = estimatedCoilTemperature
        telemetryData.thermalAttenuationFactor = thermalSafetyGain

        if (isEngineEnabled.get()) {
            if (thermalSafetyGain <= 0.01f) {
                // Thermal protection engaged — clear C++ haptic buffer and stop vibration
                nativeBridge.clearHapticBuffer()
                hapticEventGenerator.cancel()
                directDriveSmoothAmp = 0f
            }
            // Note: Continuous waveform playback is handled entirely by
            // runContinuousHapticLoop(), which pulls amplitude frames from
            // the C++ 5-layer synthesizer at 50ms intervals.
            // This pipeline only processes audio → updates telemetry data.
        }
    }

    fun release() {

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