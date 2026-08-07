package com.mouya.musichaptics

import kotlin.math.abs
import kotlin.math.max

/**
 * v3.8 phase 2: low-cost musical structure tracker.
 *
 * It deliberately works on the live feature stream rather than guessing a genre
 * from one audio frame. The result is stable enough to guide future haptic
 * density/contrast decisions while preserving a graceful real-time fallback.
 */
class MusicStructureAnalyzer {
    enum class Section { INTRO, VERSE, BUILD, CHORUS, BREAKDOWN, OUTRO }

    data class Snapshot(
        val section: Section = Section.INTRO,
        val energy: Float = 0f,
        val beatDensity: Float = 0f,
        val dynamicRise: Float = 0f,
        val confidence: Float = 0f
    )

    private var energyEma = 0f
    private var longEnergyEma = 0f
    private var densityEma = 0f
    private var lastFrameMs = 0L
    private var activeSinceMs = 0L
    private var lastSectionChangeMs = 0L
    private var current = Snapshot()

    fun update(
        timestampMs: Long,
        sub: Float,
        mid: Float,
        texture: Float,
        isBeat: Boolean,
        instruments: InstrumentFeatures
    ): Snapshot {
        val rawEnergy = (sub * 1.25f + mid * 0.85f + texture * 0.45f).coerceIn(0f, 2f)
        energyEma += 0.12f * (rawEnergy - energyEma)
        longEnergyEma += 0.018f * (rawEnergy - longEnergyEma)
        val dt = if (lastFrameMs == 0L) 20L else (timestampMs - lastFrameMs).coerceIn(1L, 200L)
        lastFrameMs = timestampMs
        val beatRate = if (isBeat) 1000f / dt else 0f
        densityEma += 0.04f * (beatRate.coerceAtMost(12f) - densityEma)
        val rise = (energyEma - longEnergyEma).coerceIn(-1f, 1f)

        if (rawEnergy > 0.025f && activeSinceMs == 0L) activeSinceMs = timestampMs
        val elapsed = if (activeSinceMs == 0L) 0L else timestampMs - activeSinceMs
        val vocalOrHarmony = max(instruments.vocal, instruments.harmonic)
        val percussion = max(instruments.kick, max(instruments.snare, instruments.hiHat))

        val candidate = when {
            elapsed < 8_000L -> Section.INTRO
            energyEma < 0.035f && elapsed > 25_000L -> Section.OUTRO
            energyEma < longEnergyEma * 0.62f && elapsed > 12_000L -> Section.BREAKDOWN
            rise > 0.075f && densityEma > 1.2f -> Section.BUILD
            energyEma > longEnergyEma * 1.18f && percussion > 0.42f -> Section.CHORUS
            vocalOrHarmony > 0.40f || energyEma <= longEnergyEma * 1.12f -> Section.VERSE
            else -> current.section
        }

        // A 4-second hysteresis avoids section labels bouncing on every fill.
        if (candidate != current.section && timestampMs - lastSectionChangeMs >= 4_000L) {
            current = current.copy(section = candidate)
            lastSectionChangeMs = timestampMs
        }
        val confidence = (abs(energyEma - longEnergyEma) * 2.2f + percussion * 0.25f + vocalOrHarmony * 0.15f)
            .coerceIn(0f, 1f)
        current = current.copy(
            energy = energyEma,
            beatDensity = densityEma,
            dynamicRise = rise,
            confidence = confidence
        )
        return current
    }
}