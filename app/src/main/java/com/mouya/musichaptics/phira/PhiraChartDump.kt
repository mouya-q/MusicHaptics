package com.mouya.musichaptics.phira

import com.mouya.musichaptics.ActuatorProfile
import com.mouya.musichaptics.DeviceProfile
import java.io.File

/**
 * 离线对拍入口：把 Kotlin 解析/编译结果打到 stdout，跟
 * `tools/phira_chart_proto.py` 的输出逐项比对。
 *
 * 两边算法必须完全等价 —— 常数是在 Python 侧用 7 张真谱标定的，
 * 移植到 Kotlin 如果哪一步实现漂了，手感就跟标定结论对不上了。
 *
 * 用 dalvikvm 直接跑（不启动 App，因此不需要任何权限）：
 *   dalvikvm -cp app-debug.apk \
 *     com.mouya.musichaptics.phira.PhiraChartDump /sdcard/phira_probe
 *
 * 仅供开发期验证，不在任何生产路径上被调用。
 */
object PhiraChartDump {

    /** 三档马达，跟 Python 原型的 motors 表一致 */
    private val MOTORS = listOf(
        "ESA1016 3.25ms" to 24.0,
        "0809 5.75ms" to 42.0,
        "K80U 12.5ms" to 90.0,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.firstOrNull() ?: "/sdcard/phira_probe")
        val files = (dir.listFiles() ?: emptyArray())
            .filter {
                it.isFile && (
                    (it.extension.equals("json", true) && it.name != "data.json") ||
                        it.extension.equals("pec", true)
                    )
            }
            .sortedBy { it.name }

        for (f in files) {
            val t0 = System.nanoTime()
            val parsed = PhiraChart.parse(f)
            val parseMs = (System.nanoTime() - t0) / 1_000_000
            if (parsed == null) {
                println("── ${f.name}  PARSE FAILED")
                continue
            }
            val notes = parsed.notes
            val meta = parsed.meta
            val last = notes.lastOrNull()?.sec ?: 0.0
            val dist = notes.groupingBy { PhiraChart.eventOf(it.type) ?: "?" }.eachCount()

            println("── ${f.name}  「${meta.name.take(28)}」  ${f.length() / 1024}KB 解析${parseMs}ms")
            println(
                "   notes=%d  bpmSegs=%d  fake剔除=%d  末note=%.1fs  META.dur=%.1f".format(
                    notes.size, meta.bpmSegments, meta.fakeSkipped, last, meta.durationSec,
                )
            )
            println(
                "   分布: " + listOf("KICK", "BODY", "SNARE", "TICK")
                    .filter { (dist[it] ?: 0) > 0 }
                    .joinToString("  ") { "$it=${dist[it]}" }
            )

            for ((name, gap) in MOTORS) {
                val (beats, stats) = PhiraHapticTimeline.compile(
                    notes,
                    PhiraHapticTimeline.Config(
                        minGapMs = gap,
                        maxPerSec = PhiraHapticTimeline.capFor(gap),
                    ),
                )
                val dens = if (last > 0) beats.size / last else 0.0
                var worst = 0
                var j = 0
                for (i in beats.indices) {
                    while (beats[i].atMs - beats[j].atMs > 1000) j++
                    worst = maxOf(worst, i - j + 1)
                }
                val sus = beats.count { it.sustain }
                println(
                    "   %-14s gap=%2dms → 合并%d → 输出%d (抽稀丢%d, 限流丢%d, 均%.1f/s, 峰%d/s, 续拍%d)".format(
                        name, gap.toInt(), stats.merged, beats.size,
                        stats.thinned, stats.throttled, dens, worst, sus,
                    )
                )
            }
            println()
        }

        // 顺带核对 configFor 的推导：三档马达应落在 24 / 42 / 90 附近
        // responseTimeMs 是 (rise+fall)/2 的派生值，所以这里构造 rise/fall 来凑
        println("── configFor 推导核对")
        listOf(
            Triple("ESA1016", 2.5f, 4.0f),   // → 3.25ms
            Triple("0809", 3.5f, 8.0f),      // → 5.75ms
            Triple("K80U", 10.0f, 15.0f),    // → 12.5ms
        ).forEach { (n, rise, fall) ->
            val act = ActuatorProfile.DEFAULT.copy(riseTimeMs = rise, fallTimeMs = fall)
            val cfg = PhiraHapticTimeline.configFor(DeviceProfile.DEFAULT.copy(actuator = act))
            println("   %-8s responseTime=%.2fms → minGap=%.1fms maxPerSec=%d".format(
                n, act.responseTimeMs, cfg.minGapMs, cfg.maxPerSec))
        }

        files.firstOrNull { it.name == "chart43928.json" }
            ?.let { simulateScheduler(it) }
    }

    /**
     * 调度器仿真：不碰马达，只喂一个会卡顿 / 暂停 / seek 的假播放位置，
     * 量化每一发的对齐误差。
     *
     * 真机上没法复现"如果这里卡顿 120ms 会怎样"，但这正是最容易出错的路径 ——
     * 墙钟推进的实现会在这里累积漂移，按位置对齐的实现应当自动收敛。
     *
     * 关键：驱动的是 [PhiraHapticScheduler.step]，也就是线上真正跑的那份判定，
     * 不是仿真里另抄一遍的复制品。
     */
    private fun simulateScheduler(chart: File) {
        val parsed = PhiraChart.parse(chart) ?: return
        val cfg = PhiraHapticTimeline.Config(
            minGapMs = 42.0,
            maxPerSec = PhiraHapticTimeline.capFor(42.0),
        )
        val (beats, _) = PhiraHapticTimeline.compile(parsed.notes, cfg)
        if (beats.isEmpty()) return

        // 0809 基准档：rise 3.5ms + startLatency 4.5ms
        val profile = DeviceProfile.DEFAULT.copy(
            actuator = ActuatorProfile.DEFAULT.copy(riseTimeMs = 3.5f, fallTimeMs = 8.0f)
        )
        val lead = PhiraHapticScheduler.leadMsFor(profile)

        val sched = PhiraHapticScheduler(
            beats = beats,
            leadMs = lead,
            position = PhiraHapticScheduler.PositionSource { null },  // 仿真直接调 step
            emit = { },                                               // 不碰马达
        )

        println()
        println("── 调度器仿真 ${chart.name}（0809 档 lead=${lead}ms，含卡顿/暂停/seek）")

        // 假播放位置：墙钟每 10ms 走一步，音频位置按扰动状态推进
        //  0~30s      正常
        //  30.00~30.12s  underrun：音频卡住不动，墙钟继续
        //  60.00~60.30s  暂停：位置源取不到值（音频也不动）
        //  80s        向后 seek 到 20s
        var wall = 0L
        var audio = 0L
        var seekDone = false
        val endMs = beats.last().atMs + 500
        var ticks = 0
        val maxTicks = (endMs + 90_000L) / 10L      // 防跑飞的硬上限

        while (audio < endMs && ticks < maxTicks) {
            ticks++
            val stalled = wall in 30_000L..30_120L
            val paused = wall in 60_000L..60_300L

            if (wall >= 80_000L && !seekDone) {
                audio = 20_000L
                seekDone = true
            }

            sched.step(if (paused) null else audio)

            wall += 10L
            if (!stalled && !paused) audio += 10L
        }

        val s = sched.stats
        println("   beats=${beats.size} $s")
        val p = s.errorPercentiles()
        if (p != null) {
            println(
                "   对齐误差(ms, +为偏晚): min=%d p50=%d p95=%d max=%d  |误差|>20ms=%d 发".format(
                    p[0], p[1], p[2], p[3], p[4],
                )
            )
        }
    }
}