package com.mouya.musichaptics.phira

import com.mouya.musichaptics.ActuatorProfile
import com.mouya.musichaptics.DeviceProfile
import java.io.File

object PhiraChartDump {

    
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

        
        
        println("── configFor 推导核对")
        listOf(
            Triple("ESA1016", 2.5f, 4.0f),   
            Triple("0809", 3.5f, 8.0f),      
            Triple("K80U", 10.0f, 15.0f),    
        ).forEach { (n, rise, fall) ->
            val act = ActuatorProfile.DEFAULT.copy(riseTimeMs = rise, fallTimeMs = fall)
            val cfg = PhiraHapticTimeline.configFor(DeviceProfile.DEFAULT.copy(actuator = act))
            println("   %-8s responseTime=%.2fms → minGap=%.1fms maxPerSec=%d".format(
                n, act.responseTimeMs, cfg.minGapMs, cfg.maxPerSec))
        }

        files.firstOrNull { it.name == "chart43928.json" }
            ?.let { simulateScheduler(it) }
    }

    









    private fun simulateScheduler(chart: File) {
        val parsed = PhiraChart.parse(chart) ?: return
        val cfg = PhiraHapticTimeline.Config(
            minGapMs = 42.0,
            maxPerSec = PhiraHapticTimeline.capFor(42.0),
        )
        val (beats, _) = PhiraHapticTimeline.compile(parsed.notes, cfg)
        if (beats.isEmpty()) return

        
        val profile = DeviceProfile.DEFAULT.copy(
            actuator = ActuatorProfile.DEFAULT.copy(riseTimeMs = 3.5f, fallTimeMs = 8.0f)
        )
        val lead = PhiraHapticScheduler.leadMsFor(profile)

        val sched = PhiraHapticScheduler(
            beats = beats,
            leadMs = lead,
            position = PhiraHapticScheduler.PositionSource { null },  
            emit = { },                                               
        )

        println()
        println("── 调度器仿真 ${chart.name}（0809 档 lead=${lead}ms，含卡顿/暂停/seek）")

        
        
        
        
        
        var wall = 0L
        var audio = 0L
        var seekDone = false
        val endMs = beats.last().atMs + 500
        var ticks = 0
        val maxTicks = (endMs + 90_000L) / 10L      

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