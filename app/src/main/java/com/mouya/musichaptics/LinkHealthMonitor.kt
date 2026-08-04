package com.mouya.musichaptics

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

object LinkHealthMonitor {

    private const val TAG = "LinkHealth"

    private const val TIMEOUT_AUDIO_INPUT_MS = 200L
    private const val TIMEOUT_DSP_OUTPUT_MS = 200L
    private const val TIMEOUT_COMPOSER_MS = 500L
    private const val TIMEOUT_VIBRATE_CALL_MS = 5000L
    private const val TIMEOUT_TELEMETRY_MS = 1000L
    private const val TIMEOUT_HOOK_READY_MS = 10000L

    @Volatile private var lastAudioInputMs: Long = 0L
    @Volatile private var lastDspOutputMs: Long = 0L
    @Volatile private var lastComposerMs: Long = 0L
    @Volatile private var lastVibrateCallMs: Long = 0L
    @Volatile private var lastTelemetryMs: Long = 0L
    @Volatile private var lastHookReadyMs: Long = 0L

    private val audioInputCount = AtomicLong(0)
    private val dspOutputCount = AtomicLong(0)
    private val composerCount = AtomicLong(0)
    private val vibrateCallCount = AtomicLong(0)
    private val telemetryCount = AtomicLong(0)
    private val hookReadyCount = AtomicLong(0)

    @Volatile private var isPlaying: Boolean = false
    @Volatile private var lastPlayStateChangeMs: Long = 0L

    enum class Stage(val displayName: String, val order: Int, val timeoutMs: Long) {
        HOOK_READY("Hook就绪", 0, TIMEOUT_HOOK_READY_MS),
        AUDIO_INPUT("音频输入", 1, TIMEOUT_AUDIO_INPUT_MS),
        DSP_OUTPUT("Native DSP", 2, TIMEOUT_DSP_OUTPUT_MS),
        COMPOSER("Composer合成", 3, TIMEOUT_COMPOSER_MS),
        VIBRATE_CALL("马达调用", 4, TIMEOUT_VIBRATE_CALL_MS),
        TELEMETRY("遥测上报", 5, TIMEOUT_TELEMETRY_MS);

        fun getStatus(): StageStatus {
            val lastMs = when (this) {
                HOOK_READY -> lastHookReadyMs
                AUDIO_INPUT -> lastAudioInputMs
                DSP_OUTPUT -> lastDspOutputMs
                COMPOSER -> lastComposerMs
                VIBRATE_CALL -> lastVibrateCallMs
                TELEMETRY -> lastTelemetryMs
            }
            val count = when (this) {
                HOOK_READY -> hookReadyCount.get()
                AUDIO_INPUT -> audioInputCount.get()
                DSP_OUTPUT -> dspOutputCount.get()
                COMPOSER -> composerCount.get()
                VIBRATE_CALL -> vibrateCallCount.get()
                TELEMETRY -> telemetryCount.get()
            }
            val now = SystemClock.elapsedRealtime()

            if (this == HOOK_READY) {
                return if (count == 0L) {
                    StageStatus.NEVER_RECEIVED
                } else if (now - lastMs > timeoutMs) {
                    StageStatus.TIMEOUT
                } else {
                    StageStatus.OK
                }
            }

            if (this == VIBRATE_CALL) {
                return if (count == 0L) {

                    if (isPlaying) {
                        StageStatus.TIMEOUT
                    } else {
                        StageStatus.WAITING_PLAY
                    }
                } else {

                    StageStatus.OK
                }
            }

            if (count == 0L) {
                return if (isPlaying) {
                    StageStatus.TIMEOUT
                } else {
                    StageStatus.WAITING_PLAY
                }
            }

            if (!isPlaying) {
                return StageStatus.PAUSED
            }

            return if (now - lastMs > timeoutMs) {
                StageStatus.TIMEOUT
            } else {
                StageStatus.OK
            }
        }
    }

    enum class StageStatus(val color: Int, val label: String) {
        OK(0xFF00E676.toInt(), "正常"),
        TIMEOUT(0xFFFF3D00.toInt(), "超时断链"),
        NEVER_RECEIVED(0xFFBDBDBD.toInt(), "无数据"),
        WAITING_PLAY(0xFF2196F3.toInt(), "等待播放"),
        PAUSED(0xFFFFC107.toInt(), "已暂停");
    }

    fun getSnapshot(): LinkHealthSnapshot {
        val now = SystemClock.elapsedRealtime()
        return LinkHealthSnapshot(
            stages = Stage.values().map { stage ->
                val status = stage.getStatus()
                val lastMs = when (stage) {
                    Stage.HOOK_READY -> lastHookReadyMs
                    Stage.AUDIO_INPUT -> lastAudioInputMs
                    Stage.DSP_OUTPUT -> lastDspOutputMs
                    Stage.COMPOSER -> lastComposerMs
                    Stage.VIBRATE_CALL -> lastVibrateCallMs
                    Stage.TELEMETRY -> lastTelemetryMs
                }
                val count = when (stage) {
                    Stage.HOOK_READY -> hookReadyCount.get()
                    Stage.AUDIO_INPUT -> audioInputCount.get()
                    Stage.DSP_OUTPUT -> dspOutputCount.get()
                    Stage.COMPOSER -> composerCount.get()
                    Stage.VIBRATE_CALL -> vibrateCallCount.get()
                    Stage.TELEMETRY -> telemetryCount.get()
                }
                val ageMs = if (lastMs > 0) now - lastMs else -1L
                StageSnapshot(stage, status, count, lastMs, ageMs)
            }.toList(),
            timestampMs = now
        )
    }

    fun heartbeatAudioInput() {
        lastAudioInputMs = SystemClock.elapsedRealtime()
        audioInputCount.incrementAndGet()
    }

    fun heartbeatDspOutput() {
        lastDspOutputMs = SystemClock.elapsedRealtime()
        dspOutputCount.incrementAndGet()
    }

    fun heartbeatComposer() {
        lastComposerMs = SystemClock.elapsedRealtime()
        composerCount.incrementAndGet()
    }

    fun heartbeatVibrateCall() {
        lastVibrateCallMs = SystemClock.elapsedRealtime()
        vibrateCallCount.incrementAndGet()
    }

    fun heartbeatTelemetry() {
        lastTelemetryMs = SystemClock.elapsedRealtime()
        telemetryCount.incrementAndGet()
    }

    fun heartbeatHookReady() {
        lastHookReadyMs = SystemClock.elapsedRealtime()
        hookReadyCount.incrementAndGet()
    }

    fun setPlayingState(playing: Boolean) {
        isPlaying = playing
        lastPlayStateChangeMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "Play state changed: isPlaying=$playing")
    }

    fun isCurrentlyPlaying(): Boolean = isPlaying

    fun reset() {
        lastAudioInputMs = 0L
        lastDspOutputMs = 0L
        lastComposerMs = 0L
        lastVibrateCallMs = 0L
        lastTelemetryMs = 0L
        audioInputCount.set(0)
        dspOutputCount.set(0)
        composerCount.set(0)
        vibrateCallCount.set(0)
        telemetryCount.set(0)
        isPlaying = false
        lastPlayStateChangeMs = 0L
        Log.i(TAG, "LinkHealthMonitor reset (hook status preserved)")
    }

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun startSelfCheck(scope: CoroutineScope = monitorScope) {
        scope.launch(Dispatchers.Default) {
            while (true) {
                kotlinx.coroutines.delay(5000)
                val snap = getSnapshot()
                val issues = snap.stages.filter { it.status != StageStatus.OK && it.status != StageStatus.WAITING_PLAY && it.status != StageStatus.PAUSED }
                if (issues.isNotEmpty()) {
                    Log.w(TAG, "⚠️ Link Health Issues: ${issues.joinToString(", ") { "${it.stage.displayName}=${it.status.label}(age=${it.ageMs}ms)" }}")
                } else if (snap.stages.any { it.totalCount > 0 }) {
                    Log.d(TAG, "✅ Link Healthy: all ${snap.stages.count { it.totalCount > 0 }} active stages OK")
                }
            }
        }
    }
}

data class StageSnapshot(
    val stage: LinkHealthMonitor.Stage,
    val status: LinkHealthMonitor.StageStatus,
    val totalCount: Long,
    val lastHeartbeatMs: Long,
    val ageMs: Long
) {
    override fun toString(): String {
        return "${stage.displayName}: ${status.label} (count=$totalCount, age=${if (ageMs >= 0) "${ageMs}ms" else "never"})"
    }
}

data class LinkHealthSnapshot(
    val stages: List<StageSnapshot>,
    val timestampMs: Long
) {

    val isHealthy: Boolean get() = stages.all { it.status == LinkHealthMonitor.StageStatus.OK }

    val firstBrokenStage: StageSnapshot? get() = stages.firstOrNull { it.status != LinkHealthMonitor.StageStatus.OK }

    override fun toString(): String {
        return stages.joinToString(" → ") { "${it.stage.displayName}=${it.status.label}" }
    }
}
