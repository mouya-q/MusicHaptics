package com.mouya.musichaptics.haptic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticExpressionTest {
    private val fast = ExpressionActuator(riseTimeMs = 3.5f, fallTimeMs = 5.0f)
    private val reference = ExpressionActuator(riseTimeMs = 4.5f, fallTimeMs = 7.0f)
    private val slow = ExpressionActuator(riseTimeMs = 10f, fallTimeMs = 15f)

    private fun pattern(event: String, intensity: Float, a: ExpressionActuator = reference, budget: Long = 52L, amps: Boolean = false) =
        HapticExpression.tieredPattern(event, intensity, a, budget, amps)

    @Test fun contrastOfOneIsIdentity() {
        assertEquals(0.4f, HapticExpression.contrast(0.4f, 1f), 1e-6f)
    }

    @Test fun contrastSpreadsLightAndHeavy() {
        assertTrue(HapticExpression.contrast(0.15f, 1.8f) < 0.15f)
        assertTrue(HapticExpression.contrast(0.60f, 1.8f) > 0.60f)
        assertTrue(HapticExpression.contrast(0.15f, 0.6f) > 0.15f)
        assertTrue(HapticExpression.contrast(0.60f, 0.6f) < 0.60f)
    }

    @Test fun contrastIsSafeForBadInput() {
        assertEquals(0f, HapticExpression.contrast(Float.NaN, 1f), 0f)
        assertTrue(HapticExpression.contrast(0.5f, Float.NaN) in 0f..1f)
    }

    @Test fun weightsFollowIntensity() {
        assertEquals(HitWeight.LIGHT, pattern("KICK", 0.10f).weight)
        assertEquals(HitWeight.MEDIUM, pattern("KICK", 0.35f).weight)
        assertEquals(HitWeight.HEAVY, pattern("KICK", 0.65f).weight)
    }

    @Test fun heavierHitsAreLongerOnTheSameActuator() {
        val light = pattern("KICK", 0.10f).totalMs
        val medium = pattern("KICK", 0.35f).totalMs
        val heavy = pattern("KICK", 0.65f).totalMs
        assertTrue("light=$light medium=$medium", light < medium)
        assertTrue("medium=$medium heavy=$heavy", medium < heavy)
    }

    @Test fun heavyKickIsAPulseGapEcho() {
        val p = pattern("KICK", 0.65f)
        assertEquals(3, p.timings.size)
        assertEquals(0, p.amplitudes[1])
        assertEquals(HapticExpression.DEFAULT_AMPLITUDE, p.amplitudes[0])
        assertEquals(HapticExpression.DEFAULT_AMPLITUDE, p.amplitudes[2])
    }

    @Test fun lightKickIsASinglePulse() {
        val p = pattern("KICK", 0.10f)
        assertEquals(1, p.timings.size)
    }

    @Test fun neverExceedsTheBudget() {
        for (event in listOf("KICK", "SNARE", "VOCAL", "BODY")) {
            for (i in 0..20) {
                for (a in listOf(fast, reference, slow)) {
                    val p = pattern(event, i / 20f, a, budget = 40L)
                    assertTrue("$event i=$i total=${p.totalMs}", p.totalMs <= 40L)
                    assertTrue(p.timings.all { it >= 2L })
                    assertEquals(p.timings.size, p.amplitudes.size)
                }
            }
        }
    }

    @Test fun tightBudgetDropsTheEchoFirst() {
        val p = pattern("KICK", 0.95f, budget = 30L)
        assertEquals(1, p.timings.size)
        assertTrue(p.totalMs <= 30L)
    }

    @Test fun slowActuatorsGetLongerPulsesAndGaps() {
        val onFast = pattern("KICK", 0.65f, fast, budget = 120L)
        val onSlow = pattern("KICK", 0.65f, slow, budget = 120L)
        assertTrue(onSlow.totalMs > onFast.totalMs)
        assertTrue(onSlow.timings[1] > onFast.timings[1])
        // A pulse shorter than the rise time never develops, so the floor must hold.
        assertTrue(onSlow.timings[0] >= (10f * 1.3f).toLong())
    }

    @Test fun gapOutlastsTheFallTime() {
        val p = pattern("KICK", 0.65f, slow, budget = 120L)
        assertTrue(p.timings[1] >= (15f * 0.9f).toLong())
    }

    @Test fun amplitudeClassesMapToTickClickThud() {
        val light = pattern("KICK", 0.10f, amps = true).amplitudes[0]
        val medium = pattern("KICK", 0.35f, amps = true).amplitudes[0]
        val heavy = pattern("KICK", 0.65f, amps = true).amplitudes[0]
        assertTrue("light=$light", light < 82)
        assertTrue("medium=$medium", medium in 82..183)
        assertTrue("heavy=$heavy", heavy >= 184)
    }

    @Test fun heavyKickGetsASoftTail() {
        val t = longArrayOf(4, 8, 14)
        val a = intArrayOf(180, 255, 120)
        val out = HapticExpression.withBassAfterglow("KICK", 0.65f, t, a, reference, maxTotalMs = 60L)
        assertTrue(out != null)
        assertEquals(4, out!!.first.size)
        assertTrue(out.first.sum() <= 60L)
        assertTrue(out.second.last() < 255 * 0.4f)
        assertTrue(out.second.last() >= 10)
    }

    @Test fun afterglowOnlyForHeavyKicks() {
        val t = longArrayOf(4, 8, 14)
        val a = intArrayOf(180, 255, 120)
        assertTrue(HapticExpression.withBassAfterglow("KICK", 0.10f, t, a, reference, 60L) == null)
        assertTrue(HapticExpression.withBassAfterglow("SNARE", 0.65f, t, a, reference, 60L) == null)
    }

    @Test fun afterglowNeverBreaksTheBudget() {
        val t = longArrayOf(4, 8, 40)
        val a = intArrayOf(180, 255, 120)
        // 52 ms already used of 56: not enough room for a tail that could develop.
        assertTrue(HapticExpression.withBassAfterglow("KICK", 0.65f, t, a, reference, 56L) == null)
    }

    @Test fun ghostNotesAreLeftOut() {
        assertTrue(!HapticExpression.shouldPlay("KICK", 0.09f, 1000L))
        assertTrue(HapticExpression.shouldPlay("KICK", 0.20f, 0L))
        assertTrue(!HapticExpression.shouldPlay("SNARE", 0.10f, 1000L))
        assertTrue(HapticExpression.shouldPlay("SNARE", 0.25f, 0L))
    }

    @Test fun textureOnlyFillsRhythmGaps() {
        assertTrue(!HapticExpression.shouldPlay("VOCAL", 0.50f, 100L))
        assertTrue(HapticExpression.shouldPlay("VOCAL", 0.50f, 400L))
        assertTrue(!HapticExpression.shouldPlay("BODY", 0.20f, 5000L))
        assertTrue(!HapticExpression.shouldPlay("VOCAL", Float.NaN, 5000L))
    }

    @Test fun rhythmEventsAreKickAndSnare() {
        assertTrue(HapticExpression.isRhythm("KICK"))
        assertTrue(HapticExpression.isRhythm("snare"))
        assertTrue(!HapticExpression.isRhythm("VOCAL"))
        assertTrue(!HapticExpression.isRhythm("BODY"))
    }

    @Test fun everyPulseIsLongEnoughToFeel() {
        for (event in listOf("KICK", "SNARE", "VOCAL", "BODY")) {
            for (i in 0..20) {
                val p = pattern(event, i / 20f, fast, budget = 60L)
                // rests are amplitude 0; every real pulse must clear the perceptibility floor
                for (j in p.timings.indices) if (p.amplitudes[j] != 0) assertTrue("$event i=$i ${p.timings[j]}", p.timings[j] >= 8L)
            }
        }
    }

    @Test fun loggedPeakIsNeverZeroForARealHit() {
        assertTrue(pattern("KICK", 0.65f).peakAmplitude > 0)
        assertTrue(pattern("SNARE", 0.20f).peakAmplitude > 0)
    }

    @Test fun weightIsMonotonicInIntensity() {
        var last = -1
        for (i in 0..100) {
            val w = HapticExpression.weightOf(HapticExpression.onsetStrength(i / 100f)).ordinal
            assertTrue(w >= last)
            last = w
        }
    }
}
