package com.mouya.musichaptics.phira

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 按播放位置驱动谱面振动。
 *
 * 这是"照着标准答案打"的执行端。关键设计取舍：
 *
 * **只按播放位置对齐，不按墙钟推进。** 歌曲开始时记一个"墙钟 ↔ 播放位置"的锚点然后
 * 自由跑，是最省事也最错的做法：Phira 的音频流会被系统 underrun、切后台、暂停打断，
 * 墙钟不会跟着停，几十秒后振动就飘到别的小节上去了。所以每一 tick 都重新问一次
 * 播放位置（[PositionSource]，未来接 `AAudioStream_getFramesRead`，采样级精度）。
 *
 * **落后的 beat 直接丢，不补发。** 卡顿后一次性把攒下的 5 发全放出去，只会糊成一坨，
 * 而且它们对应的音符早就过去了 —— 触感的意义在于同步，迟到的触感是噪声。
 *
 * **提前 leadMs 触发。** 从下发 vibrate 到马达真正起振有物理延迟（马达上升时间 +
 * 系统调度），不提前就一定偏晚。这个量直接来自 DeviceProfile 的马达参数。
 */
class PhiraHapticScheduler(
    private val beats: List<PhiraHapticTimeline.Beat>,
    /** 提前触发量（ms）：马达上升时间 + 系统下发延迟 */
    private val leadMs: Long,
    /** 迟到超过这个量就放弃该发 */
    private val lateToleranceMs: Long = 45L,
    private val tickMs: Long = 10L,
    /** 播放位置源，返回当前播放到第几毫秒；不可用时返回 null */
    private val position: PositionSource,
    /** 真正下发振动 */
    private val emit: (PhiraHapticTimeline.Beat) -> Unit,
) {

    fun interface PositionSource {
        /** 当前播放位置（ms）；未在播放/取不到时返回 null */
        fun currentMs(): Long?
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** 下一个待发的下标 */
    @Volatile private var cursor = 0

    /** 上一 tick 的播放位置，用来识别 seek / 重开 */
    private var lastPosMs = Long.MIN_VALUE

    val stats = Stats()

    class Stats {
        @Volatile var fired = 0
        @Volatile var skippedLate = 0
        @Volatile var resyncs = 0

        /** 对齐误差直方图（ms，+ 为偏晚），只留必要的分位统计，不存全量 */
        private val errs = ArrayList<Long>()

        fun record(errMs: Long) {
            synchronized(errs) {
                // 上限 4096：一首歌 1000 多发，够；超了就丢最早的，保住近期分布
                if (errs.size >= 4096) errs.removeAt(0)
                errs.add(errMs)
            }
        }

        /** 返回 (min, p50, p95, max, |err|>20ms 的发数)；无样本时返回 null */
        fun errorPercentiles(): LongArray? = synchronized(errs) {
            if (errs.isEmpty()) return null
            val s = errs.sorted()
            longArrayOf(
                s.first(),
                s[s.size / 2],
                s[(s.size * 95) / 100],
                s.last(),
                s.count { kotlin.math.abs(it) > 20 }.toLong(),
            )
        }

        override fun toString(): String {
            val p = errorPercentiles()
            val e = if (p == null) "" else
                " err[min=${p[0]} p50=${p[1]} p95=${p[2]} max=${p[3]} 超20ms=${p[4]}]"
            return "fired=$fired late=$skippedLate resync=$resyncs$e"
        }
    }

    fun start() {
        if (beats.isEmpty()) {
            Log.i(TAG, "empty timeline — scheduler not started")
            return
        }
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "PhiraHapticSched").apply {
            priority = Thread.MAX_PRIORITY   // 触感对抖动敏感，别被普通线程挤掉
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        while (running.get()) {
            step(position.currentMs())
            sleepTick()
        }
    }

    /**
     * 单步推进 —— 循环体的全部判定都在这里，不含任何 sleep / 线程状态。
     *
     * 抽出来是为了让离线仿真（PhiraChartDump）能喂一段带卡顿/暂停/seek 的假位置
     * 序列去验证**同一份**逻辑。如果仿真自己抄一遍判定，测的就不是线上跑的代码了。
     */
    fun step(posMs: Long?) {
        if (posMs == null) {
            // 没在播放：位置源下次可能从头开始，标记待重定位
            lastPosMs = Long.MIN_VALUE
            return
        }

        // 位置回退，或向前跳超过 400ms → seek / 换歌 / 重开，重新定位游标。
        // 阈值取 400ms：正常 tick 的推进量远小于它，而任何有意义的 seek 都远大于它。
        if (lastPosMs == Long.MIN_VALUE ||
            posMs < lastPosMs ||
            posMs - lastPosMs > 400L
        ) {
            cursor = lowerBound(posMs)
            if (lastPosMs != Long.MIN_VALUE) stats.resyncs++
        }
        lastPosMs = posMs

        // 触发所有已到点的 beat。提前 leadMs 下发，抵消马达起振延迟
        val due = posMs + leadMs
        while (cursor < beats.size && beats[cursor].atMs <= due) {
            val b = beats[cursor]
            cursor++
            if (posMs - b.atMs > lateToleranceMs) {
                stats.skippedLate++     // 迟到太多：丢掉，不补发
                continue
            }
            // 记录预期起振误差（+ 为偏晚），供仿真和真机遥测共用
            stats.record((posMs + leadMs) - b.atMs)
            try {
                emit(b)
                stats.fired++
            } catch (t: Throwable) {
                Log.w(TAG, "emit failed: ${t.message}")
            }
        }
    }

    private fun sleepTick() {
        try {
            Thread.sleep(tickMs)
        } catch (_: InterruptedException) {
            running.set(false)
        }
    }

    /** 第一个 atMs >= posMs 的下标 */
    private fun lowerBound(posMs: Long): Int {
        var lo = 0
        var hi = beats.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (beats[mid].atMs < posMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private const val TAG = "PhiraHapticSched"

        /**
         * 由马达参数推 lead。
         *
         * riseTimeMs 是马达从静止到全幅的物理时间，加上 startLatencyMs（下发到
         * HAL 起振的系统开销）。两者都已经在 DeviceProfile / ActuatorProfile 里
         * 按机型标定过，这里只是把它们加起来，不再引入新的魔法常数。
         */
        fun leadMsFor(profile: com.mouya.musichaptics.DeviceProfile): Long =
            (profile.actuator.riseTimeMs + profile.startLatencyMs).toLong().coerceIn(2L, 30L)

        /**
         * 基于系统单调钟的位置源，用于没有 framesRead 时的降级路径。
         *
         * 精度明显不如 framesRead：它假设音频匀速播放，遇到 underrun 会累积漂移。
         * 只在 hook 不可用时兜底，正式路径应当接 `AAudioStream_getFramesRead`。
         */
        fun monotonicSource(startAtElapsedMs: Long, offsetMs: Long = 0L) =
            PositionSource { SystemClock.elapsedRealtime() - startAtElapsedMs + offsetMs }
    }
}