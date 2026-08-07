package com.mouya.musichaptics

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Single semantic haptic timeline.
 *
 * Detectors only publish [HapticCommand]s; this class is the sole place where
 * semantic primitives are arbitrated and authored into a future waveform.
 * It intentionally does not call Vibrator APIs.
 */
class HapticTimelineScheduler(
    private val windowMs: Long = 100L,
    private val binMs: Long = 10L,
    // v3.11: LRA waveform smoother — dynamically adapted per device Q factor.
    // High-Q actuators (0816 ESA Q=18, 0815 Q=15) need stronger smoothing
    // because their narrow resonance band amplifies inter-bin transients.
    // Lower-Q actuators (CSA0916 Q=12) can use lighter smoothing to
    // preserve more texture detail.
    private var maxSlewPerBin: Int = 35,   // max Δ amplitude between adjacent 10ms bins
    private var smootherAlpha: Float = 0.38f // one-pole LPF coefficient (0=fully smooth, 1=no filter)
) {

    /**
     * v3.11: Adapt smoothing parameters to the device's actuator Q factor.
     * Higher Q → more aggressive smoothing needed.
     * Q < 13:  maxSlew=45, alpha=0.50 (light smoothing, preserve texture)
     * Q 13-16: maxSlew=35, alpha=0.38 (moderate smoothing, balanced)
     * Q > 16:  maxSlew=25, alpha=0.25 (heavy smoothing, kill 跳跳糖)
     */
    fun adaptToActuatorQ(qFactor: Float) {
        when {
            qFactor > 16f -> {
                // 0816 ESA / OnePlus 15
                // High Q needs heavy smoothing to avoid pop-rocks. 
                // Smaller alpha = heavier smoothing (more prev value kept)
                maxSlewPerBin = 40
                smootherAlpha = 0.20f 
            }
            qFactor > 12f -> {
                maxSlewPerBin = 60
                smootherAlpha = 0.35f
            }
            else -> {
                maxSlewPerBin = 85
                smootherAlpha = 0.50f
            }
        }
    }
    private data class Event(
        val primitive: HapticPrimitive,
        val timestampMs: Long,
        val priority: Int
    )

    private val lock = Any()
    private val pending = ArrayList<Event>()

    /** Cross-window continuity: the last bin value of the previous render() call. */
    private var prevWindowTail: Int = 0

    // ════════════════════════════════════════════════════════════════
    //  v3.9: Dynamic Energy Layering — gain follows music intensity
    // ════════════════════════════════════════════════════════════════
    /** Smoothed dynamic gain factor (0.3~1.4), EMA-filtered for anti-jitter. */
    private var dynGainEma = 0.5f

    /**
     * v3.9: Compute a dynamic gain factor from the music's real-time energy.
     *
     * The gain is driven by `structure.energy` (EMA-smoothed, range 0~2) with:
     * 1. Gamma-0.6 curve mapping: low energy still maintains 30% floor (not dead),
     *    high energy pushes past unity for extra punch.
     * 2. Dynamic rise bonus: when energy is trending upward (dynamicRise > 0),
     *    add up to +30% gain to emphasise build-ups.
     * 3. Asymmetric EMA: fast attack (α=0.25) so kicks hit at full power;
     *    slow release (α=0.08) so intensity fades naturally instead of cutting off.
     *
     * This is multiplied WITH the app-level outputGain and section body gain,
     * so all three layers compose: final = outputGain × sectionBodyGain × dynGain.
     */
    private fun computeDynamicGain(structure: MusicStructureAnalyzer.Snapshot): Float {
        val energy = structure.energy.coerceIn(0f, 2f)

        // Gamma-0.6 curve: maps energy [0,2] → gain [0.3, 1.4]
        // Low energy → 0.3 floor (subtle ambient vibration, not dead silence)
        // Mid energy (0.5) → ~0.8 (comfortable medium intensity)
        // High energy (1.5+) → 1.0~1.4 (full power, slight boost for climax)
        val normalizedEnergy = (energy / 2f).coerceIn(0f, 1f)
        val gammaCurve = normalizedEnergy.pow(0.6f)
        val targetGain = 0.3f + gammaCurve * 1.1f  // range: 0.3 ~ 1.4

        // Dynamic rise bonus: up to +30% when energy is climbing
        val riseBonus = (structure.dynamicRise.coerceIn(0f, 1f) * 0.3f)
        val targetWithRise = (targetGain + riseBonus).coerceIn(0.3f, 1.5f)

        // Asymmetric EMA: fast attack, slow release
        val alpha = if (targetWithRise > dynGainEma) 0.25f else 0.08f
        dynGainEma += alpha * (targetWithRise - dynGainEma)

        val result = dynGainEma.coerceIn(0.3f, 1.5f)

        android.util.Log.i("HapticTimelineScheduler",
            "dynGain | energy=${"%.3f".format(energy)} raw=${"%.3f".format(targetWithRise)} ema=${"%.3f".format(result)} rise=${"%.3f".format(structure.dynamicRise)}")

        return result
    }

    fun offer(command: HapticCommand) {
        command.primitive?.let { offerPrimitive(it, command.timestamp) }
        // v3.10: Also offer additional iOS-style layers (Beat tap, Bass body, Vocal wave, HiHat)
        command.additionalPrimitives.forEach { prim ->
            offerPrimitive(prim, command.timestamp)
        }
    }

    /** Used by the explicitly labelled low-band-onset fallback track. */
    fun offerPrimitive(primitive: HapticPrimitive, timestampMs: Long) {
        synchronized(lock) {
            pending += Event(primitive, timestampMs, priorityOf(primitive))
            // A live engine must not accumulate events while the renderer is stalled.
            if (pending.size > 192) {
                pending.sortByDescending { it.priority }
                pending.subList(128, pending.size).clear()
            }
        }
    }

    fun hasMultiTrackActive(): Boolean {
        return tracks["KICK"]!!.active || tracks["BODY"]!!.active || tracks["SNARE"]!!.active || tracks["VOCAL"]!!.active
    }

    fun render(
        nativeSamples: FloatArray,
        sampleCount: Int,
        windowStartMs: Long,
        structure: MusicStructureAnalyzer.Snapshot,
        outputGain: Float
    ): IntArray {
        val bins = (windowMs / binMs).toInt()
        
        // v3.9: Compute dynamic gain from real-time music energy.
        // Three layers compose: finalGain = outputGain(app) × sectionBodyGain(section) × dynGain(energy)
        val dynGain = computeDynamicGain(structure)
        val sectionGain = sectionBodyGain(structure.section)
        val compositeGain = outputGain * dynGain
        
        // v3.8: Apply multi-track sidechain if tracks are active.
        // Otherwise fallback to raw nativeSamples (legacy flat).
        val hasMultiTrack = hasMultiTrackActive()
        val base = if (hasMultiTrack) {
            val multitrackArray = composeSidechainCompressed()
            IntArray(bins) { index ->
                val value = if (index < multitrackArray.size) multitrackArray[index] else 0
                // v3.11: Transient gate — kill the continuous floor.
                // Any bin below this threshold is floor noise, not a real
                // transient. Zeroing it creates silence between beats → "哒哒哒"
                val gated = if (value < 25) 0 else value
                (gated * sectionGain).roundToInt().coerceIn(0, 255)
            }
        } else {
            IntArray(bins) { index ->
                val source = if (sampleCount > 0) nativeSamples[index.coerceAtMost(sampleCount - 1)] else 0f
                // v3.11: Same transient gate for fallback path
                val gated = if (source < 0.10f) 0f else source
                (gated * sectionGain).roundToInt().coerceIn(0, 255)
            }
        }
        
                    // active clear was removed. but we should still clear it maybe after full render sequence or natural decay. Actually wait, they accumulate envelope values...
        if (!hasMultiTrack) {
           // if fallback, we do nothing with active flags
        }
        // v3.8.6 FIX: Added diagnostic logging to track which path render() takes
        android.util.Log.i("HapticTimelineScheduler", "render() | hasMultiTrack=$hasMultiTrack bins=$bins sampleCount=$sampleCount")
        val events = synchronized(lock) {
            val expiry = windowStartMs - 40L
            pending.removeAll { it.timestampMs < expiry }
            val selected = pending.filter { it.timestampMs < windowStartMs + windowMs }
                .sortedByDescending { it.priority }
            pending.removeAll(selected.toSet())
            selected
        }

        // Higher priority events author first; lower-priority layers may fill space
        // but cannot overwrite an already stronger accent in that bin.
        for (event in events) {
            val start = ((event.timestampMs - windowStartMs) / binMs).toInt().coerceIn(0, bins - 1)
            mixPrimitive(base, start, event.primitive, structure)
        }

        // ── v3.11: Fast-decay transient smoother ──
        // Previous smoother was too slow on decay — amplitude took 5-8 bins
        // (50-80ms) to fade, causing beats to "bleed" into each other ("呜呜呜").
        //
        // New strategy:
        // 1. Slew rate: keep fast attack (1.5x maxSlew), but allow FAST decay
        //    (2x maxSlew) so transients snap back to zero quickly.
        // 2. One-pole LPF: on attack, use high alpha (preserve punch);
        //    on decay, use VERY high alpha (0.65-0.85) so amplitude drops
        //    to near-zero within 1-2 bins (10-20ms).
        val raw = IntArray(bins) { (base[it] * compositeGain).roundToInt().coerceIn(0, 255) }
        val finalOutput = IntArray(bins)
        var prev = prevWindowTail.toFloat()
        for (i in 0 until bins) {
            val target = raw[i].toFloat()

            // Asymmetric Slew rate limiting
            // Fast attack, even faster decay (v3.11: reversed from slow-decay)
            val diff = target - prev
            val slewedTarget = if (diff > maxSlewPerBin * 1.5f) {
                prev + (maxSlewPerBin * 1.5f) // Fast attack
            } else if (diff < -maxSlewPerBin * 2.5f) {
                prev - (maxSlewPerBin * 2.5f) // v3.11: 2.5x faster decay
            } else {
                target
            }

            // Asymmetric smoothing
            // v3.11: Much faster decay alpha — kills the "呜呜呜" bleed
            val currentAlpha = if (slewedTarget > prev) {
                smootherAlpha * 2f // Fast attack: preserve punch
            } else {
                (smootherAlpha * 2.5f).coerceAtMost(0.85f) // v3.11: Very fast decay
            }.coerceIn(0f, 1f)

            prev = (prev * (1f - currentAlpha)) + (slewedTarget * currentAlpha)
            // v3.11: Snap to zero if below 5 — prevent residual floor
            finalOutput[i] = if (prev < 5f) 0 else prev.roundToInt().coerceIn(0, 255)
        }
        prevWindowTail = if (finalOutput.lastOrNull() ?: 0 < 5) 0 else finalOutput.lastOrNull() ?: 0

        // v3.8.6: Log final output max for debugging
        val outputMax = finalOutput.maxOrNull() ?: 0
        android.util.Log.i("HapticTimelineScheduler", "render() DONE | outputMax=$outputMax dynGain=${"%.3f".format(dynGain)} compositeGain=${"%.3f".format(compositeGain)} section=${structure.section}")

        return finalOutput
    }

    private fun mixPrimitive(out: IntArray, start: Int, primitive: HapticPrimitive, structure: MusicStructureAnalyzer.Snapshot) {
        fun put(index: Int, value: Int) {
            if (index in out.indices) out[index] = maxOf(out[index], value.coerceIn(0, 255))
        }
        // v3.10.19: Interpolated envelope write — instead of writing discrete
        // values to sparse bins (which causes step jumps on high-Q LRAs),
        // linearly interpolate between envelope points so the LRA sees a
        // continuous drive curve.  This is critical for OnePlus 15 (Q=16):
        // a jump from 255→112 between adjacent 10ms bins produces a mechanical
        // transient that sounds like "click click click" (跳跳糖).
        fun putInterpolated(startIdx: Int, envelope: FloatArray, intensity: Int) {
            for (i in envelope.indices) {
                val targetIdx = startIdx + i
                if (targetIdx !in out.indices) break
                put(targetIdx, (intensity * envelope[i]).roundToInt())
                // Interpolate to next point
                if (i < envelope.lastIndex) {
                    val nextIdx = startIdx + i + 1
                    if (nextIdx !in out.indices) break
                    val avg = (envelope[i] + envelope[i + 1]) * 0.5f
                    put(nextIdx, (intensity * avg).roundToInt())
                }
            }
        }
        when (primitive) {
            is HapticPrimitive.Impact -> {
                val env = when {
                    primitive.semantic == "BEAT_TAP_STRONG" ->
                        // v3.11: Strong beat — full punch with quick decay
                        floatArrayOf(1f, .70f, .35f, .12f, .03f)
                    primitive.semantic == "BEAT_TAP" ->
                        // v3.11: Regular beat — crisp, shorter
                        floatArrayOf(1f, .55f, .20f, .05f, .01f)
                    primitive.semantic.contains("SNARE") || primitive.semantic.contains("PLUCKED") ->
                        // v3.10.19: Smoother double-stage strike — fill intermediate bins
                        floatArrayOf(1f, .85f, .55f, .72f, .45f, .25f, .18f, .08f, .04f, .02f)
                    primitive.semantic.contains("KICK") || primitive.semantic.contains("SUB_") ||
                        primitive.semantic == "LOW_BAND_ONSET" || primitive.semantic == "PCM_LOW_BAND_ATTACK" ->
                        // v3.10.19: Smoother dense low-band attack — gradual decay curve
                        floatArrayOf(1f, .92f, .80f, .65f, .50f, .38f, .28f, .20f, .13f, .08f)
                    else ->
                        // v3.10.19: Extended generic impact with smooth decay
                        floatArrayOf(1f, .80f, .60f, .42f, .28f, .18f, .12f, .07f, .04f, .02f)
                }
                putInterpolated(start, env, primitive.intensity)
            }
            is HapticPrimitive.Pulse -> {
                val hits = primitive.repeatCount.coerceIn(1, 3)
                val step = (primitive.periodMs / binMs).toInt().coerceAtLeast(2)
                repeat(hits) { hit ->
                    val hitStart = start + hit * step
                    put(hitStart, (primitive.intensity * .76f).roundToInt())
                    // v3.10.19: Add decay tail after each pulse hit
                    if (hitStart + 1 < out.size) put(hitStart + 1, (primitive.intensity * .45f).roundToInt())
                    if (hitStart + 2 < out.size) put(hitStart + 2, (primitive.intensity * .20f).roundToInt())
                }
            }
            is HapticPrimitive.Texture -> {
                // v3.10.19: Dense grains with smooth decay instead of sparse clicks.
                val bins = (primitive.durationMs / binMs).toInt().coerceIn(1, 5)
                repeat(bins) { i ->
                    val decay = 1f - i * 0.15f
                    put(start + i, (primitive.intensity * .52f * decay).roundToInt())
                }
            }
            is HapticPrimitive.Wave -> {
                val durationBins = (primitive.durationMs / binMs).toInt().coerceIn(1, out.size - start)
                repeat(durationBins) { i ->
                    val curveIndex = (i * primitive.amplitudeCurve.size / durationBins)
                        .coerceIn(0, primitive.amplitudeCurve.lastIndex)
                    val sectionGain = if (structure.section == MusicStructureAnalyzer.Section.BREAKDOWN) .75f else 1f
                    put(start + i, (primitive.amplitudeCurve[curveIndex] * 255f * sectionGain).roundToInt())
                }
            }
        }
    }

    private fun priorityOf(primitive: HapticPrimitive): Int = when (primitive) {
        is HapticPrimitive.Impact -> when {
            primitive.semantic.contains("KICK") || primitive.semantic.contains("SUB_") ||
                primitive.semantic == "LOW_BAND_ONSET" || primitive.semantic == "PCM_LOW_BAND_ATTACK" -> 100
            primitive.semantic == "BEAT_TAP_STRONG" -> 95  // v3.11: Strong beat (with kick)
            primitive.semantic == "BEAT_TAP" -> 88  // v3.11: Regular beat tap
            primitive.semantic.contains("SNARE") || primitive.semantic.contains("PLUCKED") -> 80
            else -> 70
        }
        is HapticPrimitive.Pulse -> 75
        is HapticPrimitive.Wave -> when (primitive.semantic) {
            "VOCAL_PHRASE", "VOCAL_SUSTAIN", "VOCAL_WAVE" -> 60
            "BASS_SUSTAIN", "BASS_BODY" -> 55
            else -> 50
        }
        is HapticPrimitive.Texture -> when {
            primitive.semantic == "HIHAT_TICK" -> 35
            else -> 20
        }
    }

    private fun sectionBodyGain(section: MusicStructureAnalyzer.Section): Float = when (section) {
        MusicStructureAnalyzer.Section.INTRO -> .68f
        MusicStructureAnalyzer.Section.VERSE -> .82f
        MusicStructureAnalyzer.Section.BUILD -> .96f
        MusicStructureAnalyzer.Section.CHORUS -> 1f
        MusicStructureAnalyzer.Section.BREAKDOWN -> .58f
        MusicStructureAnalyzer.Section.OUTRO -> .55f
    }

    // ════════════════════════════════════════════════════════════════
    //  v3.8 Multi-Track Timeline: Independent track rendering + sidechain
    // ════════════════════════════════════════════════════════════════
    data class SemanticTrack(
        val name: String,
        val envelope: FloatArray = FloatArray(10) { 0f },
        var active: Boolean = false,
        var priority: Int = 0
    )

    private val tracks = mutableMapOf(
        "KICK" to SemanticTrack("Kick"),
        "SNARE" to SemanticTrack("Snare"),
        "VOCAL" to SemanticTrack("Vocal"),
        "BODY" to SemanticTrack("Body")
    )

    fun applyMultiTrackFrames(frames: FloatArray, count: Int) {
        val maxFramesPerPull = 10
        var anyActive = false
        for (i in 0 until minOf(count, maxFramesPerPull)) {
            val kick = frames.getOrElse(i * 4 + 0) { 0f }
            val snare = frames.getOrElse(i * 4 + 1) { 0f }
            val vocal = frames.getOrElse(i * 4 + 2) { 0f }
            val body = frames.getOrElse(i * 4 + 3) { 0f }
            tracks["KICK"]!!.envelope[i % 10] = kick
            tracks["SNARE"]!!.envelope[i % 10] = snare
            tracks["VOCAL"]!!.envelope[i % 10] = vocal
            tracks["BODY"]!!.envelope[i % 10] = body
            
            if (kick > 1.0f) tracks["KICK"]!!.active = true
            if (snare > 1.0f) tracks["SNARE"]!!.active = true
            if (vocal > 1.0f) tracks["VOCAL"]!!.active = true
            if (body > 1.0f) tracks["BODY"]!!.active = true
            
            if (kick > 1.0f || snare > 1.0f || vocal > 1.0f || body > 1.0f) {
                anyActive = true
            }
        }
        
        if (!anyActive) {
            // Decay active status if silence is drawn for a whole block
            tracks["KICK"]!!.active = false
            tracks["SNARE"]!!.active = false
            tracks["VOCAL"]!!.active = false
            tracks["BODY"]!!.active = false
        }
    }

    fun composeSidechainCompressed(): IntArray {
        val bins = 10
        val result = IntArray(bins) { 0 }
        val kickVals = tracks["KICK"]!!.envelope
        val snareVals = tracks["SNARE"]!!.envelope
        val vocalVals = tracks["VOCAL"]!!.envelope
        val bodyVals = tracks["BODY"]!!.envelope
        for (i in 0 until bins) {
            val k = kickVals[i]
            val s = snareVals[i]
            val v = vocalVals[i]
            val b = bodyVals[i]
            // v3.11: No floor — BODY track contribution reduced from 0.75 to 0.35
            // The BODY track was creating a continuous bass floor that masked
            // beat taps and kick transients. Now KICK and SNARE dominate.
            // Sidechain compression: kick ducks body, snare ducks vocal
            val compressedBody = if (k > 50.0f) b * 0.2f else b * 0.5f
            val compressedVocal = if (s > 50.0f) v * 0.3f else v * 0.6f
            val composed = maxOf(
                k * 1.0f,
                s * 0.95f,
                compressedVocal,
                compressedBody * 0.35f
            ).toInt()
            
            result[i] = composed.coerceIn(0, 255)

            // Clear envelope after rendering
            kickVals[i] = 0f
            snareVals[i] = 0f
            vocalVals[i] = 0f
            bodyVals[i] = 0f
        }
        return result
    }
}