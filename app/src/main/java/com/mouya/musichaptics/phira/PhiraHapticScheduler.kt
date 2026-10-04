package com.mouya.musichaptics.phira

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

















class PhiraHapticScheduler(
    private val beats: List<PhiraHapticTimeline.Beat>,
    
    private val leadMs: Long,
    
    private val lateToleranceMs: Long = 45L,
    private val tickMs: Long = 10L,
    
    private val position: PositionSource,
    
    private val emit: (PhiraHapticTimeline.Beat) -> Unit,
) {

    fun interface PositionSource {
        
        fun currentMs(): Long?
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    
    @Volatile private var cursor = 0

    
    private var lastPosMs = Long.MIN_VALUE

    val stats = Stats()

    class Stats {
        @Volatile var fired = 0
        @Volatile var skippedLate = 0
        @Volatile var resyncs = 0

        
        private val errs = ArrayList<Long>()

        fun record(errMs: Long) {
            synchronized(errs) {
                
                if (errs.size >= 4096) errs.removeAt(0)
                errs.add(errMs)
            }
        }

        
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
            priority = Thread.MAX_PRIORITY   
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

    





    fun step(posMs: Long?) {
        if (posMs == null) {
            
            lastPosMs = Long.MIN_VALUE
            return
        }

        
        
        if (lastPosMs == Long.MIN_VALUE ||
            posMs < lastPosMs ||
            posMs - lastPosMs > 400L
        ) {
            cursor = lowerBound(posMs)
            if (lastPosMs != Long.MIN_VALUE) stats.resyncs++
        }
        lastPosMs = posMs

        
        val due = posMs + leadMs
        while (cursor < beats.size && beats[cursor].atMs <= due) {
            val b = beats[cursor]
            cursor++
            if (posMs - b.atMs > lateToleranceMs) {
                stats.skippedLate++     
                continue
            }
            
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

        






        fun leadMsFor(profile: com.mouya.musichaptics.DeviceProfile): Long =
            (profile.actuator.riseTimeMs + profile.startLatencyMs).toLong().coerceIn(2L, 30L)

        





        fun monotonicSource(startAtElapsedMs: Long, offsetMs: Long = 0L) =
            PositionSource { SystemClock.elapsedRealtime() - startAtElapsedMs + offsetMs }
    }
}