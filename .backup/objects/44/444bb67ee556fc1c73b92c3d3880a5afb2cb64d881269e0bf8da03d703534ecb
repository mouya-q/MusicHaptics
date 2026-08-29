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

/**
 * Phira 进程内的谱面驱动总控
 *
 * 串联：PhiraChartLibrary.scan → PhiraChart.parse → PhiraHapticTimeline.compile → PhiraHapticScheduler
 *
 * 位置源降级策略：
 *   1. 正式路径：native hook 截 `AAudioStream_getFramesRead`，采样级精度（待实现）
 *   2. 降级路径：[monotonicSource]——用 SystemClock.elapsedRealtime 近似，
 *      歌曲开始时记一个墙钟锚点，匀速推进。遇到 underrun 会累积漂移，
 *      但先拿它实机试手感 —— 这正是本次"墙钟降级"接入的目标。
 *
 * **谱面检测靠 fd 反查**：Phira 全在 native 层选歌，dex 没回调。
 * 定期看 /proc/self/fd 里有没有打开 charts/download/ 下的文件
 */
class PhiraController(
    private val context: Context,
    private val engine: HapticEngine,
) {

    companion object {
        private const val TAG = "PhiraController"
        /** fd 反查间隔——太短费 CPU，太长首次进曲延迟 */
        private const val SCAN_INTERVAL_MS = 2000L
    }

    /** 当前活跃的调度器；null = 未在打歌或谱面还没加载好 */
    private val activeScheduler = AtomicReference<PhiraHapticScheduler?>(null)

    /** 谱面缓存，避免每首歌都重新扫盘 */
    private var library: List<PhiraChartLibrary.Entry> = emptyList()
    private var libraryScanned = false

    /** 当前加载的谱面 entry（用来判断是否换了歌） */
    @Volatile private var currentEntry: PhiraChartLibrary.Entry? = null

    /** 墙钟锚点：歌曲开始时的 elapsedRealtime */
    @Volatile private var clockAnchorMs: Long = 0L

    private var scanThread: Thread? = null
    @Volatile private var running = false

    private val deviceProfile: DeviceProfile = detectDeviceProfile(
        context = context,
        persistedProfileId = context.getSharedPreferences("haptics_config", Context.MODE_PRIVATE)
            .getString("device_profile", null)
    )

    /**
     * 启动监控。在 MainHook 识别到 Phira 进程后调一次。
     */
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

    /**
     * 核心循环：定期 fd 反查当前谱面，切换时重新编译并启动调度器。
     */
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
            // 没在打歌或谱面 fd 已关——停掉旧调度器
            val old = activeScheduler.getAndSet(null)
            if (old != null) {
                Log.i(TAG, "No active chart fd — stopping scheduler (fired=${old.stats.fired})")
                old.stop()
                LogBroadcaster.sendLog(context, "[Phira] Stopped — ${old.stats}")
                currentEntry = null
            }
            // 调试：每 30s 打一次，确认 tick 在跑
            if (System.currentTimeMillis() % 30000 < SCAN_INTERVAL_MS) {
                Log.d(TAG, "tick: no active chart fd (waiting for gameplay)")
            }
            return
        }

        // fd 找到目录了——检查是否换歌
        val entryId = activeDir.name
        if (entryId == currentEntry?.id) {
            Log.d(TAG, "tick: already on chart $entryId")
            return  // 同一首，不动
        }

        Log.i(TAG, "detectActiveChartDir found: $entryId, switching chart...")

        // 换歌：找到对应 entry（必要时先扫描库）
        if (!libraryScanned) {
            library = PhiraChartLibrary.scan()
            libraryScanned = true
            Log.i(TAG, "Library scanned: ${library.size} charts")
            LogBroadcaster.sendLog(context, "[Phira] Library scanned: ${library.size} charts")
        }

        val entry = library.find { it.id == entryId }
            ?: PhiraChartLibrary.entryOf(activeDir)

        if (entry == null) {
            // fd 指向了一个不在清单里的目录——直接尝试读目录里的谱面
            Log.w(TAG, "No library entry for fd dir=$entryId, skipping")
            return
        }

        switchChart(entry)
    }

    /**
     * 切换谱面：解析→编译→停旧调度器→起新调度器。
     */
    private fun switchChart(entry: PhiraChartLibrary.Entry) {
        Log.i(TAG, "Switching to chart: ${entry.name} (${entry.chartFile.name})")
        LogBroadcaster.sendLog(context, "[Phira] Loading chart: ${entry.name}")

        // 后台线程编译，blocking本会话太久所以手动计时
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

        // 按马达参数推出编译配置（configFor 内部已经标定好了 minGap / cap 等全部参数）
        val cfg = PhiraHapticTimeline.configFor(deviceProfile)

        val (beats, stats) = PhiraHapticTimeline.compile(notes, cfg)
        val elapsed = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "Compiled ${entry.name}: ${notes.size} notes → ${beats.size} beats, stats=$stats, took ${elapsed}ms")
        LogBroadcaster.sendLog(context, "[Phira] Compiled: ${notes.size} notes → ${beats.size} beats (${beats.size} vibrations), ${elapsed}ms")

        if (beats.isEmpty()) {
            Log.w(TAG, "No beats after compilation for ${entry.name}")
            return
        }

        // 停旧
        activeScheduler.getAndSet(null)?.stop()

        // 记墙钟锚点——此时假设歌曲正在开始/已经开始了
        clockAnchorMs = SystemClock.elapsedRealtime()

        val leadMs = PhiraHapticScheduler.leadMsFor(deviceProfile)

        // 墙钟降级位置源
        val position = PhiraHapticScheduler.monotonicSource(clockAnchorMs, offsetMs = entry.infoOffsetMs.toLong())

        // emit 回调：调 HapticEngine 的公开入口
        val emit: (PhiraHapticTimeline.Beat) -> Unit = { beat ->
            // 使用 intensityOf() 根据 stack 数动态计算强度
            // 同刻多 note 齐落时 stack>1，震感更强
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