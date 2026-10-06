package com.mouya.musichaptics.phira

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mouya.musichaptics.DeviceProfile
import com.mouya.musichaptics.HapticEngine
import com.mouya.musichaptics.detectDeviceProfile
import com.mouya.musichaptics.LogBroadcaster
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class PhiraController(
    private val context: Context,
    private val engine: HapticEngine,
) {

    companion object {
        private const val TAG = "PhiraController"
        
        private const val SCAN_INTERVAL_MS = 2000L
    }

    
    private val activeScheduler = AtomicReference<PhiraHapticScheduler?>(null)

    
    private var library: List<PhiraChartLibrary.Entry> = emptyList()
    private var libraryScanned = false

    
    @Volatile private var currentEntry: PhiraChartLibrary.Entry? = null

    
    @Volatile private var clockAnchorMs: Long = 0L

    private var scanThread: Thread? = null
    @Volatile private var running = false

    private val deviceProfile: DeviceProfile = detectDeviceProfile(
        context = context,
        persistedProfileId = context.getSharedPreferences("haptics_config", Context.MODE_PRIVATE)
            .getString("device_profile", null)
    )

    


    fun start() {
        if (running) return
        running = true
        scanThread = Thread({ scanLoop() }, "PhiraChartMonitor").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
            start()
        }
        Log.i(TAG, "PhiraController started — monitoring fd for chart switches")
        LogBroadcaster.sendLog(context, "[Phira] Chart-driven haptic controller started")
    }

    fun stop() {
        running = false
        scanThread?.join(1000)
        scanThread = null
        activeScheduler.getAndSet(null)?.stop()
    }

    


    private fun scanLoop() {
        while (running) {
            try {
                tick()
            } catch (t: Throwable) {
                Log.w(TAG, "scan tick error: ${t.message}")
            }
            try {
                Thread.sleep(SCAN_INTERVAL_MS)
            } catch (_: InterruptedException) {
                running = false
            }
        }
    }

    private fun tick() {
        val activeDir = PhiraChartLibrary.detectActiveChartDir()
        if (activeDir == null) {
            
            val old = activeScheduler.getAndSet(null)
            if (old != null) {
                Log.i(TAG, "No active chart fd — stopping scheduler (fired=${old.stats.fired})")
                old.stop()
                LogBroadcaster.sendLog(context, "[Phira] Stopped — ${old.stats}")
                currentEntry = null
            }
            
            if (System.currentTimeMillis() % 30000 < SCAN_INTERVAL_MS) {
                Log.d(TAG, "tick: no active chart fd (waiting for gameplay)")
            }
            return
        }

        
        val entryId = activeDir.name
        if (entryId == currentEntry?.id) {
            Log.d(TAG, "tick: already on chart $entryId")
            return  
        }

        Log.i(TAG, "detectActiveChartDir found: $entryId, switching chart...")

        
        if (!libraryScanned) {
            library = PhiraChartLibrary.scan()
            libraryScanned = true
            Log.i(TAG, "Library scanned: ${library.size} charts")
            LogBroadcaster.sendLog(context, "[Phira] Library scanned: ${library.size} charts")
        }

        val entry = library.find { it.id == entryId }
            ?: PhiraChartLibrary.entryOf(activeDir)

        if (entry == null) {
            
            Log.w(TAG, "No library entry for fd dir=$entryId, skipping")
            return
        }

        switchChart(entry)
    }

    


    private fun switchChart(entry: PhiraChartLibrary.Entry) {
        Log.i(TAG, "Switching to chart: ${entry.name} (${entry.chartFile.name})")
        LogBroadcaster.sendLog(context, "[Phira] Loading chart: ${entry.name}")

        
        val t0 = SystemClock.elapsedRealtime()
        val parsed = PhiraChart.parse(entry.chartFile)
        if (parsed == null) {
            Log.w(TAG, "Parse failed for ${entry.chartFile.name}")
            LogBroadcaster.sendLog(context, "[Phira] Parse failed — falling back")
            return
        }

        val notes = parsed.notes
        if (notes.isEmpty()) {
            Log.w(TAG, "No playable notes in ${entry.chartFile.name}")
            return
        }

        
        val cfg = PhiraHapticTimeline.configFor(deviceProfile)

        val (beats, stats) = PhiraHapticTimeline.compile(notes, cfg)
        val elapsed = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "Compiled ${entry.name}: ${notes.size} notes → ${beats.size} beats, stats=$stats, took ${elapsed}ms")
        LogBroadcaster.sendLog(context, "[Phira] Compiled: ${notes.size} notes → ${beats.size} beats (${beats.size} vibrations), ${elapsed}ms")

        if (beats.isEmpty()) {
            Log.w(TAG, "No beats after compilation for ${entry.name}")
            return
        }

        
        activeScheduler.getAndSet(null)?.stop()

        
        clockAnchorMs = SystemClock.elapsedRealtime()

        val leadMs = PhiraHapticScheduler.leadMsFor(deviceProfile)

        
        val position = PhiraHapticScheduler.monotonicSource(clockAnchorMs, offsetMs = entry.infoOffsetMs.toLong())

        
        val emit: (PhiraHapticTimeline.Beat) -> Unit = { beat ->
            
            
            val intensity = PhiraHapticTimeline.intensityOf(beat, base = when (beat.event.uppercase()) {
                "KICK" -> 255
                "SNARE" -> 200
                "BODY" -> 180
                "TICK" -> 120
                else -> 160
            })
            engine.emitPhiraBeat(beat.event, intensity)
        }

        val scheduler = PhiraHapticScheduler(
            beats = beats,
            leadMs = leadMs,
            position = position,
            emit = emit,
        )

        currentEntry = entry
        activeScheduler.set(scheduler)
        scheduler.start()

        Log.i(TAG, "Scheduler started: ${entry.name} lead=${leadMs}ms minGap=${cfg.minGapMs.toInt()}ms cap=${cfg.maxPerSec}/s")
        LogBroadcaster.sendLog(context, "[Phira] ▶ Playing: ${entry.name} | lead=${leadMs}ms gap=${cfg.minGapMs.toInt()}ms cap=${cfg.maxPerSec}/s")
    }
}