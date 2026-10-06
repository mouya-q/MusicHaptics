package com.mouya.musichaptics.phira

import com.mouya.musichaptics.DeviceProfile

object PhiraHapticTimeline {

    
    data class Beat(
        
        val atMs: Long,
        
        val event: String,
        
        val holdMs: Long,
        
        val stack: Int,
        
        val sustain: Boolean,
    )

    data class Config(
        
        val mergeWindowMs: Double = 12.0,
        
        val minGapMs: Double,
        
        val holdMinMs: Double = 150.0,
        
        val holdPulseMs: Double = 110.0,
        
        val holdPulseGrowth: Double = 1.45,
        
        val holdPulseMaxMs: Double = 420.0,
        
        val holdMaxPulses: Int = 10,
        
        val maxPerSec: Int = 16,
    )

    






    fun configFor(profile: DeviceProfile): Config {
        val gap = (42.0 * profile.actuator.responseTimeMs / 5.75).coerceIn(18.0, 120.0)
        return Config(minGapMs = gap, maxPerSec = capFor(gap))
    }

    






    fun capFor(gapMs: Double): Int =
        minOf(16.0, 1000.0 / gapMs).toInt().coerceAtLeast(6)

    private class Slot(
        var sec: Double,
        var event: String,
        var durSec: Double,
        var stack: Int,
        val sustain: Boolean = false,
    )

    data class Stats(val merged: Int, val thinned: Int, val throttled: Int)

    fun compile(
        notes: List<PhiraChart.Note>,
        cfg: Config,
    ): Pair<List<Beat>, Stats> {
        if (notes.isEmpty()) return emptyList<Beat>() to Stats(0, 0, 0)

        
        
        
        val merged = ArrayList<Slot>(notes.size)
        for (n in notes) {
            val ev = PhiraChart.eventOf(n.type) ?: continue
            val last = merged.lastOrNull()
            if (last != null && (n.sec - last.sec) * 1000.0 <= cfg.mergeWindowMs) {
                last.stack++
                if (PhiraChart.priorityOf(ev) > PhiraChart.priorityOf(last.event)) {
                    last.event = ev
                }
                if (n.durSec > last.durSec) last.durSec = n.durSec
            } else {
                merged.add(Slot(n.sec, ev, n.durSec, 1))
            }
        }
        val mergedCount = merged.size

        
        val withHolds = ArrayList<Slot>(merged.size + 64)
        withHolds.addAll(merged)
        for (m in merged) {
            if (m.event != "BODY" || m.durSec * 1000.0 < cfg.holdMinMs) continue
            val end = m.sec + m.durSec
            var step = cfg.holdPulseMs
            var t = m.sec + step / 1000.0
            var n = 0
            while (t < end - 0.02 && n < cfg.holdMaxPulses) {
                withHolds.add(Slot(t, "TICK", 0.0, 1, sustain = true))
                n++
                step = minOf(step * cfg.holdPulseGrowth, cfg.holdPulseMaxMs)
                t += step / 1000.0
            }
        }
        withHolds.sortBy { it.sec }

        
        
        val thinnedList = ArrayList<Slot>(withHolds.size)
        var thinned = 0
        for (m in withHolds) {
            val prev = thinnedList.lastOrNull()
            if (prev != null && (m.sec - prev.sec) * 1000.0 < cfg.minGapMs) {
                if (PhiraChart.priorityOf(m.event) > PhiraChart.priorityOf(prev.event)) {
                    prev.event = m.event
                    if (m.durSec > prev.durSec) prev.durSec = m.durSec
                }
                prev.stack += m.stack
                thinned++
                continue
            }
            thinnedList.add(m)
        }

        
        
        
        val kept = ArrayList<Slot>(thinnedList.size)
        var throttled = 0
        var w = 0
        for (m in thinnedList) {
            kept.add(m)
            while (kept[w].sec < m.sec - 1.0) w++
            if (kept.size - w > cfg.maxPerSec) {
                var victim = w
                for (i in w until kept.size) {
                    if (lighterThan(kept[i], kept[victim])) victim = i
                }
                val v = kept[victim]
                if (v.sustain || PhiraChart.priorityOf(v.event) <= PhiraChart.priorityOf("BODY")) {
                    kept.removeAt(victim)
                    throttled++
                    if (victim < w) w--
                }
            }
        }

        val beats = kept.map {
            Beat(
                atMs = (it.sec * 1000.0).toLong(),
                event = it.event,
                holdMs = (it.durSec * 1000.0).toLong(),
                stack = it.stack,
                sustain = it.sustain,
            )
        }
        return beats to Stats(mergedCount, thinned, throttled)
    }

    private fun lighterThan(a: Slot, b: Slot): Boolean {
        val sa = if (a.sustain) 0 else 1
        val sb = if (b.sustain) 0 else 1
        if (sa != sb) return sa < sb
        val pa = PhiraChart.priorityOf(a.event)
        val pb = PhiraChart.priorityOf(b.event)
        if (pa != pb) return pa < pb
        return a.sec > b.sec   
    }

    



    fun intensityOf(beat: Beat, base: Int = 180): Int {
        val boost = when {
            beat.stack <= 1 -> 1.0f
            beat.stack == 2 -> 1.15f
            beat.stack == 3 -> 1.28f
            else -> 1.38f
        }
        val weight = if (beat.sustain) 0.45f else 1.0f
        return (base * boost * weight).toInt().coerceIn(1, 255)
    }
}