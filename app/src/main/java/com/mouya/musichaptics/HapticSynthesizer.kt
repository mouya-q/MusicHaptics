package com.mouya.musichaptics

import android.util.Log
import kotlin.math.*

class HapticSynthesizer(
    private val profile: DeviceProfile
) {
    companion object {
        private const val TAG = "HapticSynthesizer"

        const val SYNTHESIS_RATE_HZ = 60
        const val FRAME_DURATION_MS = 1000L / SYNTHESIS_RATE_HZ

        const val LRA_F0 = 190f
        const val LRA_Q = 15f
        const val LRA_W0 = 2f * Math.PI.toFloat() * LRA_F0
        const val LRA_ZETA = 1f / (2f * LRA_Q)

        // v3.10.19: Tuned ADSR for high-Q LRA (OnePlus 15: Q=16, 3ms rise, 4.5ms fall).
        // Impact attack tau increased to match the LRA's mechanical rise time.
        // Decay tau increased to let the LRA settle naturally rather than
        // being force-cut (which causes mechanical ringing on high-Q actuators).
        const val ATTACK_TAU_IMPACT = 0.0025f     // was 0.001 — now matches 3ms rise
        const val DECAY_TAU_IMPACT = 0.028f       // was 0.020 — smoother decay
        const val ATTACK_TAU_CONTINUOUS = 0.012f  // was 0.008 — gentler continuous onset
        const val DECAY_TAU_CONTINUOUS = 0.10f    // was 0.08 — longer sustain tail
        const val RELEASE_TAU = 0.065f            // was 0.05 — smoother release
        const val SUSTAIN_LEVEL = 0.72f

        const val THERMAL_WARN = 70f
        const val THERMAL_CRIT = 90f
        const val THERMAL_RTH = 25f
        const val THERMAL_CTH = 1.2f
    }

    data class SynthConfig(
        val synthesisRateHz: Int = SYNTHESIS_RATE_HZ,
        val lraF0: Float = LRA_F0,
        val lraQ: Float = LRA_Q,
        val attackTauImpact: Float = ATTACK_TAU_IMPACT,
        val decayTauImpact: Float = DECAY_TAU_IMPACT,
        val attackTauContinuous: Float = ATTACK_TAU_CONTINUOUS,
        val decayTauContinuous: Float = DECAY_TAU_CONTINUOUS,
        val releaseTau: Float = RELEASE_TAU,
        val sustainLevel: Float = SUSTAIN_LEVEL,
        val thermalWarn: Float = THERMAL_WARN,
        val thermalCrit: Float = THERMAL_CRIT,
        val thermalRth: Float = THERMAL_RTH,
        val thermalCth: Float = THERMAL_CTH,
        val impactGain: Float = 1.0f,
        val continuousGain: Float = 1.0f,
        val textureGain: Float = 1.0f,
        val masterGain: Float = 1.0f,
    )

    @Volatile private var currentConfig = SynthConfig()

    // v2.0: LRA physical params from ActuatorProfile (per-device, not hardcoded)
    private val actuatorF0: Float = profile.actuator.resonanceFreq
    private val actuatorDamping: Float = profile.actuator.dampingRatio
    private val actuatorW0: Float = profile.actuator.angularFreq
    private val actuatorRiseScale: Float = profile.actuator.riseScale
    private val actuatorFallScale: Float = profile.actuator.fallScale

    fun updateParameters(config: SynthConfig) {
        currentConfig = config
        Log.i(TAG, "SynthConfig updated | lraF0=${config.lraF0}Hz actuator=${actuatorF0}Hz rise=${profile.actuator.riseTimeMs}ms fall=${profile.actuator.fallTimeMs}ms")
    }

    private var impactAdsr = AdsrState()
    private var continuousAdsr = AdsrState()
    private var textureAdsr = AdsrState()

    private var lraPhase = 0f
    private var lraDisplacement = 0f
    private var lraVelocity = 0f
    private var lraLastTimeMs = 0L

    private var coilTemp = 25f
    private var thermalGain = 1f

    private val pendingImpacts = mutableListOf<PendingImpact>()
    private val pendingTextures = mutableListOf<PendingTexture>()

    private var continuousFreq = 0f  // v1.9: init from actuator in init block
    private var continuousAmp = 0f
    private var continuousTargetAmp = 0f

    private var textureNoisePhase = 0f
    private var textureNextEventTime = 0L

    init {
        continuousFreq = actuatorF0 * 0.9f
    }

    @Volatile var lastDisplacement = 0f
    @Volatile var lastVelocity = 0f
    @Volatile var lastForce = 0f
    @Volatile var lastPhase = 0f
    @Volatile var lastEnvelope = 0f
    @Volatile var lastTemperature = 25f
    @Volatile var lastThermalGain = 1f
    @Volatile var lastTotalDrive = 0f
    @Volatile var lastImpactEnvelope = 0f
    @Volatile var lastContinuousEnvelope = 0f
    @Volatile var lastTextureEnvelope = 0f

    fun forceDecay() {
        impactAdsr.state = 4
        continuousAdsr.state = 4
        textureAdsr.state = 4
        pendingImpacts.clear()
        pendingTextures.clear()
        continuousTargetAmp = 0f
    }

    private data class AdsrState(
        var value: Float = 0f,
        var state: Int = 0,
        var targetDrive: Float = 0f,
        var attackTau: Float = ATTACK_TAU_IMPACT,
        var decayTau: Float = DECAY_TAU_IMPACT
    )

    private data class PendingImpact(
        val amplitude: Float,
        val frequency: Float,
        val sharpness: Float,
        val semantic: KeyStrikeSemantic,
        val startTimeMs: Long,
        val durationMs: Long
    )

    private data class PendingTexture(
        val amplitude: Float,
        val frequency: Float,
        val density: Float,
        val startTimeMs: Long,
        val durationMs: Long
    )

    fun synthesizeFrame(
        subBass: Float,
        midBass: Float,
        texture: Float,
        pitch: Float,
        events: List<HapticSynthesizerEvent>,
        timestampMs: Long
    ): WaveformSegment {

        val dt = if (lraLastTimeMs > 0) (timestampMs - lraLastTimeMs) / 1000f else (currentConfig.synthesisRateHz.toFloat().let { 1000f / it })
        lraLastTimeMs = timestampMs

        processInputEvents(events, timestampMs, subBass, midBass, texture, pitch)

        updateContinuousTarget(subBass, pitch)

        updateTextureTarget(texture, pitch, timestampMs)

        // v2.0: Actuator-aware ADSR — asymmetric rise/fall scaling
        val impactAttack = currentConfig.attackTauImpact * actuatorRiseScale
        val impactDecay = currentConfig.decayTauImpact * actuatorFallScale
        val contAttack = currentConfig.attackTauContinuous * actuatorRiseScale
        val contDecay = currentConfig.decayTauContinuous * actuatorFallScale

        advanceAdsr(impactAdsr, dt, impactAttack, impactDecay)
        advanceAdsr(continuousAdsr, dt, contAttack, contDecay)
        advanceAdsr(textureAdsr, dt, impactAttack, impactDecay)

        updateThermalModel(dt)

        val totalDrive = computeTotalDrive()
        solveLraPhysics(totalDrive, dt)
        lastTotalDrive = totalDrive

        val segment = generateWaveformSegment(dt)

        updateTelemetry()

        return segment
    }

private fun processInputEvents(
    events: List<HapticSynthesizerEvent>,
    timestampMs: Long,
    subBass: Float,
    midBass: Float,
    texture: Float,
    pitch: Float
) {
    for (event in events) {
        when (event.type) {
            HapticSynthesizerEvent.Type.IMPACT -> {

                val params = resolveImpactParams(event.semantic, subBass, midBass, texture, pitch)
                pendingImpacts.add(PendingImpact(
                    amplitude = params.amplitude,
                    frequency = params.frequency,
                    sharpness = params.sharpness,
                    semantic = event.semantic,
                    startTimeMs = timestampMs,
                    durationMs = params.durationMs
                ))

                impactAdsr.state = 1
                impactAdsr.value = 0f
                impactAdsr.targetDrive = params.amplitude
            }

            HapticSynthesizerEvent.Type.TEXTURE_BURST -> {

                val density = (texture * 2f).coerceIn(0.1f, 1f)
                pendingTextures.add(PendingTexture(
                    amplitude = texture.coerceIn(0.1f, 1f),
                    frequency = (actuatorF0 * 1.5f).coerceIn(300f, 800f),
                    density = density,
                    startTimeMs = timestampMs,
                    durationMs = 80L
                ))
                textureAdsr.state = 1
                textureAdsr.value = 0f
                textureAdsr.targetDrive = texture
            }

            HapticSynthesizerEvent.Type.CONTINUOUS_ON -> {

                continuousAdsr.state = 1
                continuousAdsr.value = 0f
            }

            HapticSynthesizerEvent.Type.CONTINUOUS_OFF -> {

                if (continuousAdsr.state != 0) {
                    continuousAdsr.state = 4
                }
            }
        }
    }

    val cutoff = timestampMs - 500L
    pendingImpacts.removeAll { it.startTimeMs < cutoff }
    pendingTextures.removeAll { it.startTimeMs < cutoff }
}

    private fun resolveImpactParams(
        semantic: KeyStrikeSemantic,
        subBass: Float,
        midBass: Float,
        texture: Float,
        pitch: Float
    ): ImpactParams {
        return when (semantic) {
            KeyStrikeSemantic.SUB_STRIKE -> {
                ImpactParams(1.0f, actuatorF0 * 0.8f, 0.3f, (180L * actuatorRiseScale).toLong())
            }
            KeyStrikeSemantic.KICK_DRUM -> {
                ImpactParams(0.9f, actuatorF0 * 1.05f, 0.6f, (80L * actuatorRiseScale).toLong())
            }
            KeyStrikeSemantic.SNARE_ACCENT -> {
                ImpactParams(0.85f, actuatorF0 * 1.3f, 0.9f, (120L * actuatorRiseScale).toLong())
            }
            KeyStrikeSemantic.RHYTHM_PATTERN -> {
                ImpactParams(0.8f, actuatorF0, 0.5f, (100L * actuatorRiseScale).toLong())
            }
            KeyStrikeSemantic.BASS_GHOST -> {
                ImpactParams(0.4f, actuatorF0 * 0.7f, 0.1f, (200L * actuatorRiseScale).toLong())
            }
            else -> {
                val freq = if (subBass > midBass) actuatorF0 * 0.85f else actuatorF0 * 1.1f
                val amp = maxOf(subBass, midBass, texture).coerceIn(0.3f, 1f)
                ImpactParams(amp, freq, 0.5f, (100L * actuatorRiseScale).toLong())
            }
        }
    }

    private fun updateContinuousTarget(subBass: Float, pitch: Float) {

        continuousTargetAmp = (subBass * 1.2f).coerceIn(0f, 1f)

        if (pitch > 0f && pitch < 200f) {
            continuousFreq = pitch.coerceIn(60f, 180f)
        } else {
            continuousFreq = actuatorF0 * 0.9f
        }
    }

    private fun updateTextureTarget(textureEnergy: Float, pitch: Float, timestampMs: Long) {
        if (textureEnergy > 0.02f) {

            val intervalMs = (100f / (textureEnergy * 10f + 1f)).toLong().coerceIn(10L, 200L)
            textureNextEventTime = timestampMs + intervalMs
        }
    }

    private fun advanceAdsr(adsr: AdsrState, dt: Float, attackTau: Float, decayTau: Float) {
        when (adsr.state) {
            0 -> {  }
            1 -> {
                val alpha = 1f - exp(-dt / attackTau)
                adsr.value += (1f - adsr.value) * alpha
                if (adsr.value >= 0.98f) {
                    adsr.value = 1f
                    adsr.state = 2
                }
            }
            2 -> {
                val alpha = exp(-dt / decayTau)
                adsr.value = currentConfig.sustainLevel + (adsr.value - currentConfig.sustainLevel) * alpha
                if (abs(adsr.value - currentConfig.sustainLevel) < 0.01f) {
                    adsr.value = currentConfig.sustainLevel
                    adsr.state = 3
                }
            }
            3 -> {
                adsr.value = adsr.value * 0.9f + adsr.targetDrive * currentConfig.sustainLevel * 0.1f
                if (adsr.targetDrive < 0.02f) {
                    adsr.state = 4
                }
            }
            4 -> {
                val alpha = exp(-dt / currentConfig.releaseTau)
                adsr.value *= alpha
                if (adsr.value <= 0.001f) {
                    adsr.value = 0f
                    adsr.state = 0
                }
            }
        }
    }

    private fun updateThermalModel(dt: Float) {

        val power = (impactAdsr.value + continuousAdsr.value + textureAdsr.value).coerceIn(0f, 3f)
        val powerSquared = power * power
        val deltaTemp = (powerSquared * currentConfig.thermalRth - (coilTemp - 25f)) * dt / currentConfig.thermalCth
        coilTemp += deltaTemp
        coilTemp = coilTemp.coerceIn(25f, 120f)

        thermalGain = when {
            coilTemp <= currentConfig.thermalWarn -> 1f
            coilTemp >= currentConfig.thermalCrit -> 0f
            else -> {
                val t = (coilTemp - currentConfig.thermalWarn) / (currentConfig.thermalCrit - currentConfig.thermalWarn)
                (1f - t).coerceIn(0f, 1f)
            }
        }
    }

    private fun computeTotalDrive(): Float {
        var drive = 0f

        for (impact in pendingImpacts) {
            val ageMs = System.currentTimeMillis() - impact.startTimeMs
            if (ageMs < impact.durationMs) {
                val progress = ageMs / impact.durationMs.toFloat()

                val env = (1f - progress).coerceIn(0f, 1f) * exp(-progress * 8f)
                drive += impact.amplitude * env * impactAdsr.value * currentConfig.impactGain
            }
        }

        drive += continuousAdsr.value * continuousTargetAmp * currentConfig.continuousGain

        if (textureAdsr.value > 0f) {
            // Texture drive contribution raised from 0.3 → 0.55 for fuller texture presence
            textureNoisePhase += 0.3f
            val noise = sin(textureNoisePhase * 7.3f) * 0.5f + sin(textureNoisePhase * 11.7f) * 0.5f
            drive += textureAdsr.value * (0.5f + noise * 0.5f) * 0.55f * currentConfig.textureGain
        }

        return (drive * thermalGain * currentConfig.masterGain).coerceIn(0f, 2.5f)
    }

    private fun solveLraPhysics(drive: Float, dt: Float) {
        // v1.9: Use per-device actuator physical model instead of config defaults
        val w = actuatorW0
        val zeta = actuatorDamping

        val acceleration = drive - 2f * zeta * w * lraVelocity - w * w * lraDisplacement

        lraVelocity += acceleration * dt
        lraDisplacement += lraVelocity * dt

        lraPhase += w * dt
        lraPhase = lraPhase % (2f * Math.PI.toFloat())

        val maxDisp = 1.5f
        if (abs(lraDisplacement) > maxDisp) {
            lraDisplacement = maxDisp * sign(lraDisplacement)
            lraVelocity *= 0.5f
        }
    }

    private fun generateWaveformSegment(dt: Float): WaveformSegment {

        val drive = lastTotalDrive
        val impactEnv = impactAdsr.value
        val continuousEnv = continuousAdsr.value
        val textureEnv = textureAdsr.value
        val envelope = maxOf(impactEnv, continuousEnv, textureEnv)

        val frameDurationMs = (dt * 1000f).toLong().coerceIn(1L, 50L)

        if (drive < 0.005f && envelope < 0.005f) {
            return WaveformSegment(
                timings = longArrayOf(frameDurationMs),
                amplitudes = intArrayOf(0),
                repeat = -1
            )
        }

        val instantaneousFreq = actuatorF0 + sin(lraPhase * 0.5f) * 10f
        val periodMs = (1000f / instantaneousFreq).toLong().coerceAtLeast(1L)
        val cycles = (instantaneousFreq * dt).toInt().coerceAtLeast(1)

        val timings = LongArray(cycles)
        val amps = IntArray(cycles)

        for (i in 0 until cycles) {
            val phaseInCycle = (i.toFloat() / cycles) * 2f * Math.PI.toFloat()

            // Impact: sharp attack-decay profile — strong punch at cycle start, fast fall-off
            val impactAmp = if (impactEnv > 0.01f) {
                val impactShape = exp(-phaseInCycle * 1.2f) * (1f - cos(phaseInCycle * 0.5f)) * 0.5f
                (impactEnv * impactShape * 380f).toInt().coerceIn(1, 255)
            } else 0

            // Continuous: smooth sinusoidal drone with slow swell
            val continuousAmp = if (continuousEnv > 0.01f) {
                val swell = (sin(phaseInCycle) * 0.30f + 0.70f)
                val pulseMod = 1f + sin(lraPhase + phaseInCycle * 0.3f) * 0.12f
                (continuousEnv * swell * pulseMod * 255f).toInt().coerceIn(1, 255)
            } else 0

            // Texture: high-frequency flutter with random-ish micro-bursts
            val textureAmp = if (textureEnv > 0.01f) {
                val flutter = sin(phaseInCycle * 4f) * cos(phaseInCycle * 7f + textureNoisePhase * 3f)
                val burst = abs(sin(phaseInCycle * 2f + textureNoisePhase * 5f))
                (textureEnv * (0.35f + flutter * 0.25f + burst * 0.55f) * 220f).toInt().coerceIn(1, 255)
            } else 0

            // Composite: max-dominance blending for distinct layered feel
            val compositeAmp = maxOf(impactAmp, continuousAmp, textureAmp)

            timings[i] = periodMs
            amps[i] = compositeAmp.coerceIn(0, 255)
        }

        return WaveformSegment(
            timings = timings,
            amplitudes = amps,
            repeat = -1
        )
    }

    private fun updateTelemetry() {
        lastDisplacement = lraDisplacement
        lastVelocity = lraVelocity
        lastForce = lraDisplacement * actuatorW0 * actuatorW0
        lastPhase = lraPhase
        lastEnvelope = maxOf(impactAdsr.value, continuousAdsr.value, textureAdsr.value)
        lastImpactEnvelope = impactAdsr.value
        lastContinuousEnvelope = continuousAdsr.value
        lastTextureEnvelope = textureAdsr.value
        lastTemperature = coilTemp
        lastThermalGain = thermalGain
    }

    fun reset() {
        impactAdsr = AdsrState()
        continuousAdsr = AdsrState()
        textureAdsr = AdsrState()
        lraPhase = 0f
        lraDisplacement = 0f
        lraVelocity = 0f
        lraLastTimeMs = 0L
        coilTemp = 25f
        thermalGain = 1f
        pendingImpacts.clear()
        pendingTextures.clear()
        continuousAmp = 0f
        continuousTargetAmp = 0f
        textureNoisePhase = 0f
        textureNextEventTime = 0L
        lastTotalDrive = 0f
        lastImpactEnvelope = 0f
        lastContinuousEnvelope = 0f
        lastTextureEnvelope = 0f
    }
}

data class HapticSynthesizerEvent(
    val type: Type,
    val semantic: KeyStrikeSemantic = KeyStrikeSemantic.NONE,
    val intensity: Float = 1f,
    val timestampMs: Long = System.currentTimeMillis()
) {
    enum class Type {
        IMPACT,
        TEXTURE_BURST,
        CONTINUOUS_ON,
        CONTINUOUS_OFF
    }
}

data class ImpactParams(
    val amplitude: Float,
    val frequency: Float,
    val sharpness: Float,
    val durationMs: Long
)

data class WaveformSegment(
    val timings: LongArray,
    val amplitudes: IntArray,
    val repeat: Int = -1
) {

    fun toVibrationEffect(): android.os.VibrationEffect {
        return android.os.VibrationEffect.createWaveform(timings, amplitudes, repeat)
    }

    override fun toString(): String {
        return "WaveformSegment(frames=${amplitudes.size}, totalMs=${timings.sum()}, amps=${amplitudes.joinToString(",")})"
    }
}