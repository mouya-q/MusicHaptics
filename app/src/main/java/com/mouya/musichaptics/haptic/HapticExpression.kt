package com.mouya.musichaptics.haptic

import kotlin.math.pow

/** How hard a hit lands. The perceptual classes behind the light / medium / heavy difference. */
enum class HitWeight { LIGHT, MEDIUM, HEAVY }

/** The only actuator facts the pattern generator needs; taken from the device's ActuatorProfile. */
data class ExpressionActuator(val riseTimeMs: Float, val fallTimeMs: Float)

/** A pulse pattern ready for `VibrateProxy.performWaveform`. A 0 amplitude is an intentional rest. */
class TieredPattern(
    val timings: LongArray,
    val amplitudes: IntArray,
    val weight: HitWeight,
    /** 0..1 strength after tier classification, for logging and peak reporting. */
    val strength: Float
) {
    val totalMs: Long get() = timings.sum()
    val peakAmplitude: Int get() = amplitudes.maxOrNull()?.let { if (it < 0) (strength * 255f).toInt() else it } ?: 0
}

/**
 * Weight-aware pulse patterns for actuators whose amplitude cannot be driven
 * (the HAL ignores it, the device has no amplitude control, or it is forced to the
 * default amplitude).
 *
 * On such a device the only things that can still tell a ghost note from a downbeat
 * are how long a pulse is and how many pulses a hit is made of. The patterns follow
 * the way Apple's haptic design separates a tap from an impact: a light hit is one
 * short, crisp tick; a medium hit is a firmer single pulse; a heavy hit is a longer
 * pulse followed, after a small gap, by a short echo, which reads as weight.
 *
 * Nothing here is tied to one phone. Every duration is scaled by the actuator's own
 * rise and fall time (a slow ERM-class motor needs longer pulses and wider gaps than
 * a fast X-axis LRA), and the whole pattern is fitted into the time budget the caller
 * gives it so it can never run into the next onset.
 *
 * Kept free of Android classes so it can be unit-tested on the JVM.
 */
object HapticExpression {
    /** Mirrors VibrationEffect.DEFAULT_AMPLITUDE. */
    const val DEFAULT_AMPLITUDE = -1

    // Same intensity normalisation as HapticSynthesizer.sculptImpact, so a hit is
    // classified on the scale the rest of the pipeline already uses.
    private const val ONSET_FLOOR = 0.08f
    private const val ONSET_CEIL = 0.70f
    private const val ONSET_CURVE = 0.80f

    private const val LIGHT_CEIL = 0.34f
    private const val MEDIUM_CEIL = 0.68f

    /** Centre of the intensities that actually occur (about 0.1 to 0.6). */
    private const val CONTRAST_PIVOT = 0.35f

    /** Response time of the reference LRA (Xiaomi 10): mean of 4.5 ms rise and 7 ms fall, rounded. */
    private const val REFERENCE_RESPONSE_MS = 5.75f

    /**
     * Light/heavy contrast. Above 1 pulls soft hits softer and hard hits harder, below 1
     * flattens the difference. 1.0 returns the input unchanged.
     */
    fun contrast(intensity: Float, contrast: Float): Float {
        val x = if (intensity.isFinite()) intensity.coerceIn(0f, 1f) else 0f
        val c = if (contrast.isFinite()) contrast.coerceIn(0.5f, 2.0f) else 1f
        if (c == 1f) return x
        return (CONTRAST_PIVOT + (x - CONTRAST_PIVOT) * c).coerceIn(0.02f, 1f)
    }

    /** Position of an intensity on the onset scale, 0..1. */
    fun onsetStrength(intensity: Float): Float {
        val x = if (intensity.isFinite()) intensity.coerceIn(0f, 1f) else 0f
        return ((x - ONSET_FLOOR) / (ONSET_CEIL - ONSET_FLOOR)).coerceIn(0f, 1f).pow(ONSET_CURVE)
    }

    fun weightOf(strength: Float): HitWeight = when {
        strength < LIGHT_CEIL -> HitWeight.LIGHT
        strength < MEDIUM_CEIL -> HitWeight.MEDIUM
        else -> HitWeight.HEAVY
    }

    private class Shape(
        val mainMin: Float, val mainMax: Float,
        val echoMin: Float = 0f, val echoMax: Float = 0f, val gap: Float = 0f
    ) {
        val hasEcho: Boolean get() = echoMax > 0f
    }

    private fun shapeFor(event: String, weight: HitWeight): Shape = when (event.uppercase()) {
        "KICK", "SUB" -> when (weight) {
            HitWeight.LIGHT -> Shape(10f, 14f)
            HitWeight.MEDIUM -> Shape(16f, 24f)
            HitWeight.HEAVY -> Shape(26f, 32f, echoMin = 10f, echoMax = 14f, gap = 6f)
        }
        "SNARE" -> when (weight) {
            HitWeight.LIGHT -> Shape(6f, 8f)
            HitWeight.MEDIUM -> Shape(9f, 12f)
            HitWeight.HEAVY -> Shape(12f, 15f, echoMin = 6f, echoMax = 8f, gap = 5f)
        }
        "VOCAL", "TICK" -> when (weight) {
            HitWeight.LIGHT -> Shape(5f, 7f)
            HitWeight.MEDIUM -> Shape(8f, 10f)
            HitWeight.HEAVY -> Shape(11f, 14f)
        }
        else -> when (weight) {  // BODY and anything unknown: broader, softer
            HitWeight.LIGHT -> Shape(9f, 12f)
            HitWeight.MEDIUM -> Shape(14f, 18f)
            HitWeight.HEAVY -> Shape(20f, 26f)
        }
    }

    /** Amplitude classes for devices that read amplitudes (primitive composition): TICK / CLICK / THUD. */
    private fun pulseAmplitude(weight: HitWeight, t: Float): Int = when (weight) {
        HitWeight.LIGHT -> 60 + (t * 20f).toInt()      // below 82 -> TICK
        HitWeight.MEDIUM -> 110 + (t * 50f).toInt()    // 82..183 -> CLICK
        HitWeight.HEAVY -> 200 + (t * 55f).toInt()     // 184+ -> THUD
    }.coerceIn(1, 255)

    /**
     * Low-frequency "afterglow" for a heavy kick on an output that can take amplitudes: a
     * short, soft tail after the strike, so a bass hit ends in a rounded rumble instead of
     * stopping dead. Returns null when the hit is not a heavy kick or no time budget is left.
     *
     * The tail's amplitude is a fixed fraction of the strike's peak. A device that ignores
     * amplitudes (default-amplitude only) would play it at full strength, so callers use
     * this only where amplitude is honoured.
     */
    fun withBassAfterglow(
        event: String,
        intensity: Float,
        timings: LongArray,
        amplitudes: IntArray,
        actuator: ExpressionActuator,
        maxTotalMs: Long
    ): Pair<LongArray, IntArray>? {
        val key = event.uppercase()
        if (key != "KICK" && key != "SUB") return null
        if (timings.isEmpty() || timings.size != amplitudes.size) return null
        if (weightOf(onsetStrength(intensity)) != HitWeight.HEAVY) return null
        val peak = amplitudes.maxOrNull() ?: return null
        if (peak <= 0) return null

        val k = (((actuator.riseTimeMs + actuator.fallTimeMs) * 0.5f) / REFERENCE_RESPONSE_MS).coerceIn(0.8f, 3.0f)
        val wanted = (22f * (0.6f + 0.4f * k)).toLong()
        val room = maxTotalMs - timings.sum()
        // Shorter than the rise time it would not even develop.
        val minTail = (actuator.riseTimeMs * 1.3f).toLong().coerceAtLeast(6L)
        val tail = minOf(wanted, room)
        if (tail < minTail) return null
        val tailAmp = (peak * 0.30f).toInt().coerceAtLeast(10)
        return Pair(timings + tail, amplitudes + tailAmp)
    }

    /**
     * @param intensity normalised 0..1 hit strength (after [contrast])
     * @param maxTotalMs time budget; the pattern never exceeds it
     * @param pulseAmplitudes false: pulses use the default amplitude (the HAL owns it);
     *   true: pulses carry weight-class amplitudes for devices that map them to primitives
     */
    fun tieredPattern(
        event: String,
        intensity: Float,
        actuator: ExpressionActuator,
        maxTotalMs: Long,
        pulseAmplitudes: Boolean
    ): TieredPattern {
        val strength = onsetStrength(intensity)
        val weight = weightOf(strength)
        val lo = when (weight) { HitWeight.LIGHT -> 0f; HitWeight.MEDIUM -> LIGHT_CEIL; HitWeight.HEAVY -> MEDIUM_CEIL }
        val hi = when (weight) { HitWeight.LIGHT -> LIGHT_CEIL; HitWeight.MEDIUM -> MEDIUM_CEIL; HitWeight.HEAVY -> 1f }
        // Position inside the tier keeps the strength continuous within a class.
        val t = ((strength - lo) / (hi - lo)).coerceIn(0f, 1f)

        val shape = shapeFor(event, weight)
        val rise = actuator.riseTimeMs.coerceIn(1f, 40f)
        val fall = actuator.fallTimeMs.coerceIn(1f, 60f)
        val k = (((rise + fall) * 0.5f) / REFERENCE_RESPONSE_MS).coerceIn(0.8f, 3.0f)
        val scale = 0.6f + 0.4f * k   // 1.0 on the reference actuator, up to 1.8 on a slow one

        // A pulse shorter than the actuator's own rise time never reaches full strength.
        val minPulse = rise * 1.3f
        var main = (shape.mainMin + (shape.mainMax - shape.mainMin) * t) * scale
        main = main.coerceAtLeast(minPulse)
        var echo = if (shape.hasEcho) ((shape.echoMin + (shape.echoMax - shape.echoMin) * t) * scale).coerceAtLeast(minPulse) else 0f
        // The gap must outlast the previous pulse's ring-down or the two merge into one.
        var gap = if (shape.hasEcho) (shape.gap * scale).coerceAtLeast(fall * 0.9f) else 0f

        val budget = maxTotalMs.coerceAtLeast(4L).toFloat()
        if (main + gap + echo > budget) {
            // First give up the echo (the heavy hit becomes one long pulse), then shrink the pulse.
            echo = 0f
            gap = 0f
            main = main.coerceAtMost(budget)
        }

        val timings = ArrayList<Long>(3)
        val amplitudes = ArrayList<Int>(3)
        val amp = if (pulseAmplitudes) pulseAmplitude(weight, t) else DEFAULT_AMPLITUDE
        timings += main.toLong().coerceAtLeast(2L)
        amplitudes += amp
        if (echo > 0f) {
            timings += gap.toLong().coerceAtLeast(2L)
            amplitudes += 0
            timings += echo.toLong().coerceAtLeast(2L)
            amplitudes += if (pulseAmplitudes) (amp * 0.85f).toInt().coerceAtLeast(1) else DEFAULT_AMPLITUDE
        }
        return TieredPattern(timings.toLongArray(), amplitudes.toIntArray(), weight, strength)
    }
}
