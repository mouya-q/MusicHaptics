package com.mouya.musichaptics.phira

import com.mouya.musichaptics.DeviceProfile

/**
 * 谱面 note 列表 → 马达真正能放出来的振动时间轴。
 *
 * 谱面是"标准答案"，但答案不能照抄：谱师一秒能塞 38 个 note，线性马达一秒
 * 放不出 38 次可分辨的冲击。硬发的结果是 v4.9 踩过的
 * `CANCELLED_SUPERSEDED`（前一发被后一发取消，最后什么都感觉不到）外加马达发烫。
 *
 * 所以要按马达物理能力"编译"一遍。四步都在 7 张真谱 + 1 张 PEC 上标定过
 * （tools/phira_chart_proto.py，同一套常数）：
 *  1. 合并同刻 note（多判定线齐落 = 一次撞击）
 *  2. 长 hold 渐疏续拍（真谱里有 20s 的 hold）
 *  3. 按 responseTime 抽稀
 *  4. 1 秒滑窗限流，牺牲顺序：续拍 → 轻拍 → 绝不牺牲 KICK/SNARE
 *
 * 标定结果（0809 基准档，gap=42ms）：平均 3.8~7.4 次/秒，峰值 ≤16/秒。
 */
object PhiraHapticTimeline {

    /** 一发振动 */
    data class Beat(
        /** 相对歌曲开头的毫秒数 */
        val atMs: Long,
        /** BEAT_SHAPES 的 key：KICK / SNARE / BODY / TICK */
        val event: String,
        /** hold 的持续毫秒；非 hold 为 0 */
        val holdMs: Long,
        /** 合并进这一发的 note 数，用来提升强度 */
        val stack: Int,
        /** true = 长 hold 期内的续拍，限流时最先牺牲 */
        val sustain: Boolean,
    )

    data class Config(
        /** 同刻合并窗口。谱面里同刻 note 的时间戳有浮点误差，12ms 足够收敛 */
        val mergeWindowMs: Double = 12.0,
        /** 相邻两发的最小间隔，由马达 responseTime 推出 */
        val minGapMs: Double,
        /** 超过这个长度的 hold 才补续拍 */
        val holdMinMs: Double = 150.0,
        /** 首次续拍间隔 */
        val holdPulseMs: Double = 110.0,
        /** 续拍间隔每次乘这个系数（渐疏，模拟"抓住 → 衰减"） */
        val holdPulseGrowth: Double = 1.45,
        /** 续拍间隔上限 */
        val holdPulseMaxMs: Double = 420.0,
        /** 单个 hold 最多补几颗。20s 的 hold 等间隔会生出 180+ 颗，必须封顶 */
        val holdMaxPulses: Int = 10,
        /** 任意 1 秒窗口内的最大发数 */
        val maxPerSec: Int = 16,
    )

    /**
     * 按机型马达推出编译参数。
     *
     * minGap 沿用 v4.10 DSP refractory 的同一套逻辑：以小米10 0809
     * （responseTime=5.75ms）为基准 42ms，其余机型按 responseTime 线性缩放。
     * 实测三档：ESA1016(3.25ms)→24ms、0809(5.75ms)→42ms、K80U(12.5ms)→90ms。
     */
    fun configFor(profile: DeviceProfile): Config {
        val gap = (42.0 * profile.actuator.responseTimeMs / 5.75).coerceIn(18.0, 120.0)
        return Config(minGapMs = gap, maxPerSec = capFor(gap))
    }

    /**
     * 1 秒窗口上限。
     *
     * 这是**感知**上限而不是物理上限 —— 物理能力已经由 minGap 管住了。
     * 超过 ~16 次/秒后连续冲击在指腹上就融成一片嗡嗡声，再密只是浪费电和发热，
     * 所以快马达也封在 16；慢马达连 16 都放不出来，就跟着物理上限走。
     */
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

        // ── 1. 合并同刻 ──
        // 锚点固定在窗口内**第一个** note 的时刻。若升级类型时把 sec 也挪到后来者，
        // 一长串密集 note 会让锚点不断向后滑，整体延迟越攒越大。
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

        // ── 2. 长 hold 渐疏续拍 ──
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

        // ── 3. 抽稀 ──
        // 同样不挪时刻：太密时只把已排定那一发**升级**成更重的类型。
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

        // ── 4. 1 秒滑窗限流 ──
        // 超额就在窗口内挑"最轻"的一发丢掉：续拍优先，其次低优先级，
        // 同级里丢更靠后的。KICK/SNARE 是谱面的真卡点，绝不牺牲。
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
        return a.sec > b.sec   // 同轻重时丢更靠后的，保住已经在响的那一发
    }

    /**
     * 叠加数 → 强度。同刻 3 个 note 齐落理应比单 note 更"实"，
     * 但不能线性叠成爆音，用对数式增益压一下。
     */
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