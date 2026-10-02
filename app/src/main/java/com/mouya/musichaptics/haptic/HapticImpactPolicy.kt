package com.mouya.musichaptics.haptic

import android.os.VibrationEffect
import com.mouya.musichaptics.BeatShape
import com.mouya.musichaptics.DeviceProfile
import com.mouya.musichaptics.HapticEngine
import kotlin.math.pow

class HapticImpactPolicy {
    private fun tuningFor(profile: DeviceProfile): DeviceTuning = DeviceTuningRegistry.current(profile)
    data class Segment(val durationMs: Long, val amplitude: Int)
    data class Plan(val event: String, val totalDurationMs: Long, val segments: List<Segment>, val cooldownMs: Long)

    fun plan(
        event: String,
        intensity: Int,
        profile: DeviceProfile,
        amplitudeControl: Boolean,
        forceDefaultAmplitude: Boolean
    ): Plan? {
        val key = event.uppercase()
        val tuning = tuningFor(profile)
        val shape = HapticEngine.BEAT_SHAPES[key] ?: return null
        val normalized = (intensity / 255f).coerceIn(0f, 1f)
        if (normalized * 255f < tuning.minIntensity) return null

        val layerGain = when (key) {
            "TICK" -> tuning.textureGainScale
            "BODY" -> tuning.bodyGainScale
            else -> 1.0f
        }
        val gain = tuning.impactGain * layerGain * (0.72f + 0.48f * normalized.pow(0.82f))
        val timing = when {
            forceDefaultAmplitude || !amplitudeControl -> shape.force
            else -> shape.ampCtrl
        }
        val scale = when (key) {
            "KICK" -> tuning.kickMsScale
            "SNARE" -> tuning.snareMsScale
            "VOCAL" -> (tuning.snareMsScale * 0.94f)
            "TICK" -> tuning.tickMsScale
            else -> tuning.bodyMsScale
        }
        val total = (profile.actuator.riseTimeMs * timing.mul * scale)
            .toLong().coerceIn(timing.min, timing.max)

        val qShape = (16f / profile.actuator.qFactor.coerceIn(8f, 22f)).coerceIn(0.72f, 1.28f)
        val baseDecay = (1f - shape.attackFrac - shape.sustainFrac).coerceAtLeast(0.06f)
        val decayFrac = (baseDecay * qShape).coerceIn(0.05f, 0.62f)
        val attackFrac = if (shape.hasSustain) shape.attackFrac else (1f - decayFrac).coerceAtLeast(0.20f)
        val sustainFrac = if (shape.hasSustain) (1f - attackFrac - decayFrac).coerceAtLeast(0.05f) else 0f

        val attack = (total * attackFrac).toLong().coerceAtLeast(1L)
        val sustain = if (shape.hasSustain) (total * sustainFrac).toLong().coerceAtLeast(1L) else 0L
        val decay = (total - attack - sustain).coerceAtLeast(1L)

        val amplitude = (normalized * shape.ampBase * shape.weight(profile) * gain)
            .toInt().coerceIn(1, tuning.cappedAmplitude(profile))
        val useDefault = forceDefaultAmplitude || !amplitudeControl || tuning.preferDefaultAmplitude

        val segments = buildList {
            add(Segment(attack, if (useDefault) VibrationEffect.DEFAULT_AMPLITUDE else (amplitude * shape.attackAmpFrac).toInt().coerceAtLeast(1)))
            if (sustain > 0L) {
                add(Segment(sustain, if (useDefault) VibrationEffect.DEFAULT_AMPLITUDE else (amplitude * shape.sustainAmpFrac).toInt().coerceAtLeast(1)))
            }
            add(Segment(decay, if (useDefault) VibrationEffect.DEFAULT_AMPLITUDE else (amplitude * shape.decayAmpFrac).toInt().coerceAtLeast(1)))
        }
        return Plan(key, total, segments, tuning.minIntervalMs)
    }
}
