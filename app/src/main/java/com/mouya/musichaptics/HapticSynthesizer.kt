package com.mouya.musichaptics

import android.os.SystemClock
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

class HapticSynthesizer(private val profile: DeviceProfile) {
    companion object {
        const val SYNTHESIS_RATE_HZ = 60
        const val LRA_F0 = 190f
        const val LRA_Q = 15f
        const val ATTACK_TAU_IMPACT = 0.0015f
        const val DECAY_TAU_IMPACT = 0.008f
        const val ATTACK_TAU_CONTINUOUS = 0.008f
        const val DECAY_TAU_CONTINUOUS = 0.025f
        const val RELEASE_TAU = 0.025f
        const val SUSTAIN_LEVEL = 0.05f
        const val THERMAL_WARN = 70f
        const val THERMAL_CRIT = 90f
        const val THERMAL_RTH = 25f
        const val THERMAL_CTH = 1.2f
// The onset detector reports percussion energy inside a much narrower
        // band than 0..255, so mapping raw intensity straight onto 0..255
        // leaves most of the actuator unused. These bounds expand the useful
        // band onto the full range. The floor matches the lowest style onset
        // threshold so that no beat falls into an inaudible dead zone.
        const val ONSET_FLOOR = 0.08f
        const val ONSET_CEIL = 0.70f
        const val ONSET_CURVE = 0.80f

        // Segment lengths are quantised to a grid derived from the actuator, not
        // from a fixed synthesis rate. A 60 Hz grid gives a 16.7 ms quantum,
        // which is longer than a typical drum body, so every segment rounded up
        // to the same value and all beats came out identical. Deriving the
        // quantum from the actuator period keeps it a few milliseconds wide.
        const val TIMING_QUANTUM_MIN_MS = 2f
        const val TIMING_QUANTUM_MAX_MS = 6f
        const val TIMING_QUANTUM_PERIOD_DIVISOR = 6f

        // Reference and ceiling for the combined level drive term. Keeping the
        // drive near unity lets a knob turn be felt without ever driving the
        // whole waveform into the amplitude clamp. A reference of 1.0 means
        // the medium level (1.0) maps to drive 1.0, and the ceiling of 2.0
        // gives a full 4x range from low (0.3) to ultra (2.0).
        const val DRIVE_REFERENCE = 1.0f
        const val DRIVE_CEILING = 2.0f
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
        val impactGain: Float = 1f,
        val continuousGain: Float = 1f,
        val textureGain: Float = 1f,
        val masterGain: Float = 1f,
    )

    data class SculptedImpact(
        val timings: LongArray,
        val amplitudes: IntArray,
        val peakAmplitude: Int,
        val totalDurationMs: Long
    )

    @Volatile private var config = SynthConfig()
    private var coilTemp = 25f
    private var lastThermalMs = 0L

    fun updateParameters(value: SynthConfig) {
        config = value.copy(
            synthesisRateHz = value.synthesisRateHz.coerceIn(30, 120),
            lraF0 = value.lraF0.coerceIn(150f, 250f),
            lraQ = value.lraQ.coerceIn(5f, 30f),
            attackTauImpact = value.attackTauImpact.coerceIn(0.0001f, 0.05f),
            decayTauImpact = value.decayTauImpact.coerceIn(0.0005f, 0.2f),
            attackTauContinuous = value.attackTauContinuous.coerceIn(0.001f, 0.1f),
            decayTauContinuous = value.decayTauContinuous.coerceIn(0.005f, 0.3f),
            releaseTau = value.releaseTau.coerceIn(0.005f, 0.3f),
            sustainLevel = value.sustainLevel.coerceIn(0.02f, 0.8f),
            thermalWarn = value.thermalWarn.coerceIn(40f, 100f),
            thermalCrit = max(value.thermalCrit, value.thermalWarn + 2f).coerceIn(50f, 120f),
            thermalRth = value.thermalRth.coerceIn(10f, 60f),
            thermalCth = value.thermalCth.coerceIn(0.25f, 8f),
            impactGain = value.impactGain.coerceIn(0.1f, 3f),
            continuousGain = value.continuousGain.coerceIn(0.1f, 3f),
            textureGain = value.textureGain.coerceIn(0.1f, 3f),
            masterGain = value.masterGain.coerceIn(0.1f, 3f),
        )
    }

    fun sculptImpact(
        event: String,
        intensity: Float,
        levelScale: Float,
        durationMs: Long,
        sharpness: Float,
        bassBoost: Float,
        ampScale: Float = 1.0f,
        accentScale: Float = 1.0f,
        thermalInput: Float = 1f,
    ): SculptedImpact {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastThermalMs == 0L) 0f else ((now - lastThermalMs) / 1000f).coerceIn(0f, 2f)
        lastThermalMs = now
        val cooling = exp(-dt / (config.thermalCth * 0.9f).coerceAtLeast(0.2f))
        coilTemp = 25f + (coilTemp - 25f) * cooling

        val normalizedEvent = event.uppercase()
        val continuous = normalizedEvent == "BODY" || normalizedEvent == "VOCAL"
        val eventGain = when (normalizedEvent) {
            "KICK", "SUB" -> bassBoost * config.impactGain
            "TICK" -> config.textureGain
            "BODY", "VOCAL" -> config.continuousGain
            else -> config.impactGain
        }
        val sharp = sharpness.coerceIn(0.05f, 1f)
        val resonanceScale = sqrt((LRA_F0 / config.lraF0).coerceIn(0.6f, 1.7f))

        // Expand the narrow onset band onto the full amplitude range so that a
        // quiet hi-hat and a hard kick land at clearly different levels.
        val onsetNorm = ((intensity - ONSET_FLOOR) / (ONSET_CEIL - ONSET_FLOOR))
            .coerceIn(0f, 1f)
        val shaped = onsetNorm.pow(ONSET_CURVE)

        // The multipliers below only shape character. Normalising them by the
        // drive reference keeps the product near unity, so the amplitude is
        // driven by intensity instead of being crushed into the 255 clamp.
        val character = (levelScale * eventGain * accentScale * ampScale
            * config.masterGain * thermalInput).coerceAtLeast(0.01f)
        val drive = (character / DRIVE_REFERENCE)
            .coerceIn(1f / DRIVE_CEILING, DRIVE_CEILING)

        val peak = (shaped * drive * 255f).coerceIn(5f, 255f)
        val qTail = (config.lraQ / profile.actuator.qFactor.coerceAtLeast(5f)).coerceIn(0.65f, 1.65f)
        val attackSource = if (continuous) config.attackTauContinuous else config.attackTauImpact
        val decaySource = if (continuous) config.decayTauContinuous else config.decayTauImpact
        val attackMs = (attackSource * 1000f * profile.actuator.riseScale / resonanceScale).coerceIn(1f, durationMs * 0.32f)
        val decayMs = (decaySource * 1000f * profile.actuator.fallScale * qTail * resonanceScale / sharp.pow(0.35f)).coerceIn(4f, durationMs * 0.62f)
        val releaseMs = (config.releaseTau * 1000f).coerceIn(4f, durationMs * 0.45f)
        // Quantise to a grid derived from the actuator period. The actuator here
        // runs at 200 Hz, so a period of 5 ms divided by six gives a ~0.83 ms
        // grid clamped to 2 ms. The old fixed rate produced a 16.7 ms grid,
        // which rounded every segment to the same value.
        val periodMs = 1000f / config.lraF0.coerceAtLeast(50f)
        val quantum = (periodMs / TIMING_QUANTUM_PERIOD_DIVISOR)
            .coerceIn(TIMING_QUANTUM_MIN_MS, TIMING_QUANTUM_MAX_MS)
        fun snap(v: Float): Long {
            val n = (v / quantum).toInt().coerceAtLeast(1)
            return (n * quantum).toLong()
        }

        val attack = snap(attackMs)
        val tail = snap(decayMs + releaseMs)
        val total = durationMs.coerceIn(12L, 220L)
        val body = (total - attack - tail).coerceAtLeast(1L)
        // Intensity-dependent sustain: strong hits have less sustain (punchier),
        // weak hits have more sustain (smoother). Apple-style energy-proportional shaping.
        val intensityShape = intensity.coerceIn(0f, 1f)
        val sustain = if (continuous) config.sustainLevel
        else config.sustainLevel * (1f - sharp * 0.55f) * (0.6f + 0.4f * (1f - intensityShape))
        val releaseLevel = (0.08f + (1f - sharp) * 0.18f).coerceIn(0.06f, 0.3f)
        val peakInt = peak.toInt().coerceIn(1, 255)
        val bodyInt = (peak * sustain).toInt().coerceIn(1, peakInt)
        val tailInt = (peak * releaseLevel).toInt().coerceIn(1, peakInt)

        val energy = (peak / 255f).pow(2f) * 0.18f * profile.actuator.maxDisplacement
        coilTemp += energy * config.thermalRth / config.thermalCth
        val thermalGain = when {
            coilTemp <= config.thermalWarn -> 1f
            coilTemp >= config.thermalCrit -> 0.45f
            else -> 1f - 0.55f * ((coilTemp - config.thermalWarn) / (config.thermalCrit - config.thermalWarn))
        }.coerceIn(0.45f, 1f)
        val scaled = intArrayOf(
            (peakInt * thermalGain).toInt().coerceIn(1, 255),
            (bodyInt * thermalGain).toInt().coerceIn(1, 255),
            (tailInt * thermalGain).toInt().coerceIn(1, 255),
        )
        return SculptedImpact(longArrayOf(attack, body, tail), scaled, scaled.maxOrNull() ?: 1, attack + body + tail)
    }

    fun forceDecay() {
        coilTemp = 25f
        lastThermalMs = 0L
    }

    fun reset() = forceDecay()
}