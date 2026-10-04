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
        forceDefaultAmplitude: Boolean,
        styleCooldownMs: Long = 0L
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
        val scale = when (key) {
            "KICK" -> tuning.kickMsScale
            "SNARE" -> tuning.snareMsScale
            "VOCAL" -> tuning.snareMsScale * 0.94f
            "TICK" -> tuning.tickMsScale
            else -> tuning.bodyMsScale
        }
        // The policy is deliberately compact: the detailed envelope is rendered
        // by HapticSynthesizer. These limits stop a slow vendor vibrator from
        // turning a transient into a long, mushy buzz.
        val responseMs = (profile.actuator.riseTimeMs + profile.actuator.fallTimeMs * 0.42f)
            .coerceIn(5f, 55f)
        val totalFloat = when (key) {
            "KICK" -> responseMs * 1.25f * scale
            "SNARE" -> responseMs * 1.00f * scale
            "TICK" -> responseMs * 0.58f * scale
            "VOCAL" -> responseMs * 0.92f * scale
            else -> responseMs * 1.52f * scale
        }
        val (minMs, maxMs) = when (key) {
            "KICK" -> 9L to 28L
            "SNARE" -> 8L to 24L
            "TICK" -> 5L to 14L
            "VOCAL" -> 8L to 24L
            else -> 12L to 34L
        }
        val total = totalFloat.toLong().coerceIn(minMs, maxMs)

        val qTail = (16f / profile.actuator.qFactor.coerceIn(8f, 22f)).coerceIn(0.75f, 1.22f)
        val attackFrac = when (key) {
            "KICK" -> 0.18f
            "SNARE" -> 0.24f
            "TICK" -> 0.30f
            "VOCAL" -> 0.28f
            else -> 0.22f
        }
        val sustainFrac = when (key) {
            "KICK" -> 0.30f
            "SNARE" -> 0.20f
            "VOCAL" -> 0.26f
            else -> 0.34f
        }
        val decayFrac = (1f - attackFrac - sustainFrac) * qTail
        val attack = (total * attackFrac).toLong().coerceAtLeast(1L)
        val sustain = (total * sustainFrac).toLong().coerceAtLeast(0L)
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
        val cooldown = maxOf(tuning.minIntervalMs, styleCooldownMs.coerceAtLeast(0L))
        return Plan(key, total, segments, cooldown)
    }
}
