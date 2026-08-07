package com.mouya.musichaptics

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.mouya.musichaptics.BuildConfig
import android.os.VibrationEffect
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

import com.mouya.musichaptics.LinkHealthMonitor
import com.mouya.musichaptics.LogBroadcaster
import com.mouya.musichaptics.NativeBridge
import android.os.Build

interface LogCallback {
    fun onLog(message: String)
}

class HapticEngine(
    private val context: Context,
    private val prefs: SharedPreferences,
    // In a hooked process Context may be the system context, so the Hook passes
    // the actual target package explicitly. Normal module UI use keeps default.
    private val targetPackage: String = context.packageName
) {
    companion object {
        private const val TAG = "HapticDSPCore"

        private const val RING_BUFFER_CAPACITY = 131072

        private const val FRAME_BLOCK_SIZE = 256

        private const val MAXIMUM_CHANNELS = 8

        /**
         * App-specific calibration measured relative to Kuwo (1.00). These are
         * output gains, applied after native composition, so rhythm/onsets and
         * semantic classification remain identical across players.
         */
        private fun outputGainForPackage(packageName: String): Float = when (packageName) {
            // KuGou's installed Concept/Lite build uses this package; retain the
            // regular package mapping as well for compatibility with other builds.
            "com.kugou.android.lite",
            "com.kugou.android" -> 0.78f
            // Bilibili ExoPlayer path is quieter after its mixer/normalizer.
            "tv.danmaku.bili" -> 1.28f
            // Kuwo is the reference calibration.
            "cn.kuwo.player" -> 1.00f
            else -> 1.00f
        }

        private const val AMBIENT_TEMPERATURE_CELSIUS = 25.0f
        private const val LIMITING_TEMPERATURE_CELSIUS = 80.0f
        private const val CRITICAL_TEMPERATURE_CELSIUS = 100.0f

        const val SUB_BASS_LOW = 20f
        const val SUB_BASS_HIGH = 80f
        const val MID_BASS_LOW = 80f
        const val MID_BASS_HIGH = 200f
        const val TEXTURE_LOW = 200f
        const val TEXTURE_HIGH = 800f

        val WAVE_SUB_BASS_IMPACT = floatArrayOf(1.0f, 0.95f, 0.85f, 0.70f, 0.50f, 0.30f, 0.15f, 0.05f)
        val WAVE_MID_TRANSIENT  = floatArrayOf(1.0f, 0.60f, 0.20f, 0.05f)
        val WAVE_MICRO_TEXTURE  = floatArrayOf(0.4f, 0.80f, 0.40f, 0.10f, 0.60f, 0.20f)
    }

    enum class HapticPreset(val id: Int, val description: String) {
        BALANCED(0, "标准平衡模式"),
        BASS_ENHANCED(1, "重低音增强 (Sub-Bass Emphasized)"),
        TEXTURE_FOCUS(2, "高频微震纹理 (Micro-Texture Focus)"),
        IMPACT_MAX(3, "极致冲击爆发 (Maximum Transient Attack)"),
        CUSTOM(4, "自定义调校 (Custom Parameters)")
    }

    private val nativeBridge = NativeBridge()

    // v2.1.2: Cross-process vibration proxy — auto-detects direct vs IPC path
    private val vibrateProxy = VibrateProxy(context)

    private val directPcmBuffer: ByteBuffer = ByteBuffer.allocateDirect(FRAME_BLOCK_SIZE * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val floatPcmView: FloatBuffer = directPcmBuffer.asFloatBuffer()

    // v3.11: 10 legacy values + 10 native instrument-family features (7 instrument probabilities + 3 band levels).
    private val nativeTelemetryResult = FloatArray(20)

    // Resolve once: all renderer components must share the same root-selected profile.
    private val deviceProfile = detectDeviceProfile(
        context = context,
        persistedProfileId = prefs.getString(RootHardwareProbe.PREF_PROFILE, null)
    )
    val hapticEventGenerator = HapticEventGenerator(context, deviceProfile)

    val hapticComposer = HapticComposer(context, deviceProfile, prefs)

    private val hapticSynthesizer = HapticSynthesizer(deviceProfile)

    // Semantic detectors publish commands only. This scheduler owns event priority,
    // 100ms authoring windows and waveform composition; it never drives hardware itself.
    private val hapticTimeline = HapticTimelineScheduler().also {
        it.adaptToActuatorQ(deviceProfile.actuator.qFactor)
    }

    // v3.8 phase 2 — global song context; designed for later cached timelines.
    private val musicStructureAnalyzer = MusicStructureAnalyzer()
    @Volatile private var currentMusicStructure = MusicStructureAnalyzer.Snapshot()

    // This is a low-band onset fallback, not a claim of drum stem separation.
    // It keeps confirmed bass attacks perceptible if native family confidence is stale.
    private var lastLowBandOnsetMs = 0L
    private var lowBandOnsetCount = 0L

    // Kotlin-side low-band transient track. This intentionally describes a
    // mixed-audio "low-frequency attack candidate", not verified drum separation.
    // It remains available even when the packaged JNI onset telemetry is stale.
    private var pcmLowPassState = 0f
    private var pcmLowBandEnvelope = 0f
    private var pcmLowBandBaseline = 0f
    private var lastPcmLowBandOnsetMs = 0L
    private var pcmLowBandOnsetCount = 0L

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineJob = engineScope.coroutineContext[Job]!!

    var logCallback: LogCallback? = null

    private var sampleRate = 48000
    private var channels = 2

    private val audioRingBuffer = AudioFifoBuffer(RING_BUFFER_CAPACITY)
    private val processingFrame = FloatArray(FRAME_BLOCK_SIZE)

    private val isEngineEnabled = AtomicBoolean(true)
    private val frameIndexCounter = AtomicLong(0)
    private var lastParameterUpdateTime = 0L

    // === Continuous Waveform State ===
    @Volatile private var directDriveSmoothAmp = 0f  // Smoothed amplitude for telemetry display
    @Volatile private var bodyAmpScale = 1.0f  // v2.1.2: Smooth body scale state (persists across frames)

    // v2.1: Native scheduler state — must be declared before init block
    @Volatile private var nativeSchedulerActive = false
    @Volatile private var nativeLastAudioTime = 0L  // Tracked from native callback for silence detection
    @Volatile private var hapticPaused = false  // v2.1.1: Immediate mute flag for pause/stop

    // Last-resort Kotlin signal path. Native DSP remains the primary renderer,
    // but a missing/failed JNI pull must never turn all applications silent.
    // This is intentionally populated from the already-hooked PCM stream.
    @Volatile private var pcmFallbackAmplitude = 0
    @Volatile private var pcmFallbackAtMs = 0L

    // A write() call alone does not prove that it carries music. Players commonly
    // keep auxiliary AudioTracks alive with zero-filled buffers. Do not let those
    // buffers create a fake "audio active" state, a vibration floor, or semantic
    // `none` log spam.
    private var lastPcmIngressLogMs = 0L
    private var ignoredSilentPcmBlocks = 0L
    private val pcmActivityRmsFloor = 0.0015f // about 49 / 32768 in PCM16
    private val pcmActivityPeakFloor = 0.0030f // preserve quiet transients

    // ══════════════════════════════════════════════════════════════════
    // v1.8 Haptic Fusion: Semantic primitive bridge
    // The Composer produces HapticCommands at ~50Hz. The latest command
    // is stored here and consumed by runContinuousHapticLoop at 40ms intervals.
    // Primitive events (Impact/Pulse/Texture) are overlaid on top of the
    // C++ continuous waveform — not replacing it.
    // ══════════════════════════════════════════════════════════════════
    @Volatile private var pendingPrimitive: HapticPrimitive? = null
    @Volatile private var pendingSemanticLabel: String = "NONE"
    @Volatile private var pendingPrimitiveTime: Long = 0L

    val telemetryData = TelemetryMonitor()

    // Channels and DspFrameData removed — C++ 5-layer synthesizer handles all synthesis internally

    init {

        synchronizeParameters()

        // v2.1.2: Initialize vibration proxy (auto-detects direct vs IPC path)
        val proxyReady = vibrateProxy.init()
        Log.i(TAG, "VibrateProxy initialized: ready=$proxyReady path=${if (vibrateProxy.isProxyActive) "IPC_PROXY" else "DIRECT"}")
        Log.i(TAG, "App haptic calibration: package=$targetPackage outputGain=${outputGainForPackage(targetPackage)}")
        Log.i(TAG, "[Device Profile] name=${hapticEventGenerator.profile.name} actuator.f0=${hapticEventGenerator.profile.actuator.resonanceFreq}Hz maxAmp=${hapticEventGenerator.profile.actuator.maxAmplitude} damping=${hapticEventGenerator.profile.actuator.dampingRatio} q=${hapticEventGenerator.profile.actuator.qFactor}")
        Log.i(TAG, "[Vibrator Capability] hasAmpCtrl=${vibrateProxy.hasAmplitudeControl} primitives: CLICK=${vibrateProxy.primitiveClickSupported} TICK=${vibrateProxy.primitiveTickSupported} THUD=${vibrateProxy.primitiveHeavyClickSupported}")

        // v3.7.3: Disable native scheduler — it pulls at 10ms intervals but
        // the ring buffer fill rate varies per app (B站 ExoPlayer fills fast,
        // 酷狗 fills slower), causing inconsistent vibration patterns.
        // Instead, use the coroutine loop with a fixed 50ms pull interval and
        // 50ms batch flush — this normalises output timing across all apps.
        if (nativeBridge.isLoaded) {
            nativeBridge.onFrameCallback = { _, _ ->
                // Native callback output is deliberately not rendered. The coroutine
                // timeline below is the unique hardware-output owner.
            }
            // Native scheduler disabled for timing consistency
            nativeSchedulerActive = false
            Log.i(TAG, "Native Haptic Scheduler: DISABLED (v3.7.3 — using coroutine for app-consistent timing)")
        }

        // v3.7.3: Always use coroutine loop — uniform pull interval ensures
        // all apps get the same vibration rhythm regardless of PCM fill rate.
        engineScope.launch {
            runContinuousHapticLoop()
        }
        Log.i(TAG, "Using coroutine-based haptic loop (v3.7.3 uniform timing mode)")

        val readyMsg = "[System Ready] v${BuildConfig.VERSION_NAME} Dual-Track Fusion Engine: ${if (nativeBridge.isLoaded) "NATIVE ACTIVE" else "FALLBACK"} | Device: ${hapticEventGenerator.profile.name} | Actuator: ${hapticEventGenerator.profile.actuator.resonanceFreq.toInt()}Hz Q=${hapticEventGenerator.profile.actuator.qFactor} rise=${hapticEventGenerator.profile.actuator.riseTimeMs.toInt()}ms fall=${hapticEventGenerator.profile.actuator.fallTimeMs.toInt()}ms | C++ 5-Channel: Percussion+Bass+Vocal+Harmonic+Texture | Priority Masking: ON | No Bass Floor | Inter-frame Smooth: ON | Scheduler: ${if (nativeSchedulerActive) "NATIVE (10ms)" else "COROUTINE (100ms)"}"
        Log.i(TAG, readyMsg)
        logCallback?.onLog(readyMsg)
        LogBroadcaster.sendLog(context, readyMsg)
    }

    /**
     * Continuous Haptic Loop — pulls continuous amplitude frames from C++ 5-layer
     * synthesizer and plays them via VibrationEffect.createWaveform.
     *
     * This replaces the old discrete pulse generation with a pull-based
     * continuous waveform playback model.
     *
     * Architecture:
     * - C++ HapticEngine.hpp processes audio and fills a ring buffer with continuous
     *   amplitude samples (0-255) from 5-layer synthesis (Beat/Bass/Texture/Melody/Emotion)
     * - C++ produces samples at 100Hz (10ms each), matching Kotlin playback timing exactly
     * - This loop pulls up to 4 samples per 40ms interval → 40ms of vibration per pull
     * - Uses VibrationEffect.createWaveform with repeat=-1 (one-shot, no looping)
     * - Thermal safety and user gain are applied in C++ layer
     */
    private suspend fun runContinuousHapticLoop() {
        // v3.7.3: Unified timing — 50ms pull interval, 5 samples per pull.
        // This is the ONLY vibration output path for all apps (native scheduler
        // disabled).  50ms interval ensures:
        // 1. Consistent rhythm regardless of how fast/slow each app feeds PCM
        // 2. Fewer vibrate() calls (one per 50ms batch) = less cancel-restart
        // 3. Ring buffer naturally absorbs timing jitter from different apps
        // The renderer submits one complete 100ms window. Keeping cadence equal to
        // window length prevents Android from cancelling a still-playing waveform.
        val pullIntervalMs = 100L
        val sampleDurationMs = 10L     // Each amplitude sample → 10ms of vibration
        val maxSamplesPerPull = 10      // 10 samples × 10ms = one timeline window
        val frameBuffer = FloatArray(maxSamplesPerPull)

        val isApi29Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        // ── Primitive trigger state ──
        var lastKickTime = 0L           // Debounce: minimum 80ms between KICK triggers
        var lastShortTickTime = 0L      // Debounce: minimum 40ms between short ticks
        val kickRefractoryMs = 80L
        val shortTickRefractoryMs = 40L

        // v1.8: Semantic primitive refractory — prevent double-firing
        var lastSemanticImpactTime = 0L
        val semanticImpactRefractoryMs = 60L   // Min 60ms between semantic impacts

        // ── Primitive classification thresholds (0-255 scale) ──
        val kickThreshold = 200         // >200 → KICK (EFFECT_HEAVY_CLICK)
        val longVibeThreshold = 100      // 100-200 → sustained vibration (createOneShot)
        // <100 → short tick (EFFECT_TICK or rapid createOneShot)

        var frameCounter = 0L
        var lastAudioInputTime = 0L
        val silenceTimeoutMs = 1500L  // v2.1.2: raised from 300ms to 1500ms — music has natural dips

        while (true) {
            val frameStartTime = SystemClock.elapsedRealtime()

            try {
                // v2.1.1: Skip all processing when paused
                if (hapticPaused) {
                    kotlinx.coroutines.delay(pullIntervalMs)
                    continue
                }
                // ═══ v3.8 Multi-Track Semantic Fusion ═══
                // Pull 4-track semantic frames from C++ native engine
                val semanticFrameBuffer = FloatArray(40) // 10 frames × 4 tracks
                val semanticFrameCount = if (nativeBridge.isLoaded) {
                    nativeBridge.getSemanticFrames(semanticFrameBuffer, 10)
                } else 0

                if (semanticFrameCount > 0) {
                    hapticTimeline.applyMultiTrackFrames(semanticFrameBuffer, semanticFrameCount)
                    // The actual blending with fallback buffer happens inside hapticTimeline.render now
                }

                // Fallback track must read from the legacy buffer, but we don't want to overwrite our new multitrack data.
                // We need a separate buffer for fallback logic to read from.
                val fallbackBuffer = FloatArray(maxSamplesPerPull)
                val sampleCount = if (nativeBridge.isLoaded) {
                    nativeBridge.getHapticFrame(fallbackBuffer, maxSamplesPerPull)
                } else {
                    0
                }

                // Track audio activity for silence detection
                if (sampleCount > 0 || semanticFrameCount > 0) {
                    val maxAmpLegacy = if (sampleCount > 0) (0 until sampleCount).maxOfOrNull { fallbackBuffer[it] } ?: 0f else 0f
                    if (maxAmpLegacy > 1f || semanticFrameCount > 0) {
                        lastAudioInputTime = frameStartTime
                    }
                }

                val timeSinceAudio = frameStartTime - lastAudioInputTime
                val hasNativeAudioActivity = timeSinceAudio < silenceTimeoutMs
                val fallbackFresh = frameStartTime - pcmFallbackAtMs < silenceTimeoutMs

                // JNI pull is preferred. If it returns no usable samples despite a
                // live PCM hook, render one safe 50ms fallback window instead.
                val nativeMaxAmplitude = if (sampleCount > 0) {
                    (0 until sampleCount).maxOfOrNull { fallbackBuffer[it] } ?: 0f
                } else 0f
                val usingPcmFallback = (sampleCount <= 0 && !hapticTimeline.hasMultiTrackActive()) || (nativeMaxAmplitude <= 2f && !hapticTimeline.hasMultiTrackActive() && fallbackFresh)
                val usableSampleCount = when {
                    !usingPcmFallback -> maxOf(sampleCount, if (hapticTimeline.hasMultiTrackActive() || semanticFrameCount > 0) maxSamplesPerPull else 0)
                    fallbackFresh && pcmFallbackAmplitude > 0 -> {
                        // v3.10.6: Do not fill the buffer with a flat square wave of constant
                        // amplitude. Flat 10ms segments cause modern high-Q X-axis LRAs
                        // (like OnePlus 15) to buzz aggressively like "pop rocks" (跳跳糖)
                        // due to aggressive driver interpolation. Instead, we synthesize a
                        // smooth 30Hz sine envelope modulated by the PCM amplitude.
                        // v3.10.19: Replaced 30Hz sine with 15Hz sine + slew-rate limiter.
                        // 30Hz modulation on a 200Hz LRA creates intermodulation products
                        // at 170Hz/230Hz — both within the LRA's sensitive band — which
                        // produces the "buzzing" sensation. 15Hz is below the LRA's
                        // mechanical sensitivity floor, so the modulation is felt as
                        // a smooth swell rather than discrete clicks.
                        for (i in 0 until maxSamplesPerPull) {
                            val timeMs = frameStartTime + i * 10L
                            // 15Hz sine wave (softer than 30Hz)
                            val phase = (timeMs * 0.0942478f) % (Math.PI.toFloat() * 2f)
                            val sinMod = (kotlin.math.sin(phase) + 1f) * 0.5f // 0 to 1
                            // v3.10.19: Apply gamma curve to compress the modulation range
                            // — keeps the body vibration more consistent (less "pulsing")
                            val compressedMod = sinMod * 0.3f + 0.7f // 0.7 to 1.0 range
                            fallbackBuffer[i] = pcmFallbackAmplitude * compressedMod
                        }
                        maxSamplesPerPull
                    }
                    else -> 0
                }
                val hasAudioActivity = hasNativeAudioActivity || fallbackFresh

                // v3.8.5 FIX: When audio activity resumes, ensure proxy is not paused
                if (hasAudioActivity && vibrateProxy.paused) {
                    vibrateProxy.setResumed()
                }

                if (hasAudioActivity && (usableSampleCount > 0 || hapticTimeline.hasMultiTrackActive() || semanticFrameCount > 0)) {
                    val maxAmp = if (usableSampleCount > 0) (0 until usableSampleCount).maxOfOrNull { fallbackBuffer[it] } ?: 0f else 0f
                    if (maxAmp > 1f || hapticTimeline.hasMultiTrackActive() || semanticFrameCount > 0) {
                        // v3.8.6: Diagnostic log at entry to vibration path
                        if (frameCounter % 30L == 0L) {
                            val entryMsg = "▶ VIBRATION PATH ENTERED | hasAudio=$hasAudioActivity maxAmp=$maxAmp multiTrack=${hapticTimeline.hasMultiTrackActive()} semFrames=$semanticFrameCount usableSamples=$usableSampleCount paused=${vibrateProxy.paused} hasVibrator=${vibrateProxy.hasVibrator}"
                            Log.i(TAG, entryMsg)
                            LogBroadcaster.sendLog(context, entryMsg)
                        }
                        // ═══ v3.12 Dual-Track Semantic Fusion ═══
                        // Track 1 (Native Body): C++ 5-channel continuous waveform.
                        // Track 2 (Authored Primitives): Kotlin HapticComposer parses the 7 
                        // instrument probabilities and authors distinct tactile language 
                        // (Impact curves, Pulse rebounds, Wave breaths).
                        // Both tracks are fused smoothly in HapticTimelineScheduler.

                        if (vibrateProxy.hasVibrator) {
                            
                            // ── Check for authored semantic primitive from Composer ──
                            val semanticPrim = pendingPrimitive
                            val semanticAge = frameStartTime - pendingPrimitiveTime
                            val semanticFresh = semanticPrim != null && semanticAge < 100L  // 100ms freshness window

                            if (semanticFresh) {
                                val prim = semanticPrim!!
                                val timeSinceSemantic = frameStartTime - lastSemanticImpactTime
                                if (timeSinceSemantic >= semanticImpactRefractoryMs) {
                                    // Send authored primitive to Timeline Scheduler to be drawn as
                                    // a distinct tactile curve over the native body.
                                    hapticTimeline.offerPrimitive(prim, frameStartTime)
                                    lastSemanticImpactTime = frameStartTime
                                    pendingPrimitive = null
                                }
                            }
                            
                            var kickTriggered = false
                            var shortTickTriggered = false
                            
                            // v3.11: Only run legacy amplitude-threshold parsing if we are in PCM fallback mode
                            if (usingPcmFallback) {
                                for (i in 0 until usableSampleCount) {
                                    val amp = fallbackBuffer[i].toInt().coerceIn(0, 255)
                                    when {
                                        amp > kickThreshold && !kickTriggered -> {
                                            val timeSinceKick = frameStartTime - lastKickTime
                                            if (timeSinceKick >= kickRefractoryMs) {
                                                kickTriggered = true
                                                lastKickTime = frameStartTime
                                            }
                                        }
                                        amp in 1..99 -> {
                                            val timeSinceTick = frameStartTime - lastShortTickTime
                                            if (timeSinceTick >= shortTickRefractoryMs && !shortTickTriggered) {
                                                shortTickTriggered = true
                                                lastShortTickTime = frameStartTime
                                            }
                                        }
                                    }
                                }
                                
                                if (kickTriggered && frameStartTime - lastSemanticImpactTime >= kickRefractoryMs) {
                                    hapticTimeline.offerPrimitive(
                                        HapticPrimitive.Impact(255, 30, 1.0f, 1.0f, "PCM_LOW_BAND_ATTACK"),
                                        frameStartTime
                                    )
                                    lastSemanticImpactTime = frameStartTime
                                } else if (shortTickTriggered && frameStartTime - lastSemanticImpactTime >= shortTickRefractoryMs) {
                                    hapticTimeline.offerPrimitive(
                                        HapticPrimitive.Texture(120, 20, 0.5f, 1.0f, "PCM_TEXTURE"),
                                        frameStartTime
                                    )
                                    lastSemanticImpactTime = frameStartTime
                                }
                                bodyAmpScale = 1.0f // Let timeline render it cleanly
                            }

                            run {
                                // One 100ms timeline window is the only semantic rendering path.
                                // In normal native mode, nativeSamples contains the full 5-layer mix.
                                val calibratedAmplitudes = hapticTimeline.render(
                                    nativeSamples = fallbackBuffer,
                                    sampleCount = usableSampleCount,
                                    windowStartMs = frameStartTime,
                                    structure = currentMusicStructure,
                                    outputGain = outputGainForPackage(targetPackage)
                                )
                                val finalMax = calibratedAmplitudes.maxOrNull() ?: 0
                                if (finalMax > 0) {
                                    // v3.11: Transient-preserving waveform compression.
                                    // Previous logic merged any bins within 15/255 of each other,
                                    // including zero-gaps between beats, creating long sustained
                                    // segments ("呜呜呜" instead of "哒哒哒").
                                    //
                                    // New rules:
                                    // 1. Zero bins are NEVER merged with non-zero bins — they
                                    //    are silence between transients and must be preserved.
                                    // 2. Non-zero bins can only merge if within 20/255.
                                    // 3. No 20ms overlap tail — it sustains the motor between
                                    //    beats, defeating the whole point of transient gaps.
                                    val cDurations = mutableListOf<Long>()
                                    val cAmplitudes = mutableListOf<Int>()
                                    var currentDur = 0L
                                    var currentAmp = -1

                                    for (amp in calibratedAmplitudes) {
                                        if (currentAmp == -1) {
                                            currentAmp = amp
                                            currentDur = sampleDurationMs
                                        } else if (amp == 0 && currentAmp == 0) {
                                            // Merge consecutive zeros
                                            currentDur += sampleDurationMs
                                        } else if (amp == 0 || currentAmp == 0) {
                                            // v3.11: Never merge zero with non-zero
                                            cDurations.add(currentDur)
                                            cAmplitudes.add(currentAmp)
                                            currentAmp = amp
                                            currentDur = sampleDurationMs
                                        } else if (Math.abs(amp - currentAmp) < 20) {
                                            currentDur += sampleDurationMs
                                            currentAmp = (currentAmp * 0.7f + amp * 0.3f).toInt()
                                        } else {
                                            cDurations.add(currentDur)
                                            cAmplitudes.add(currentAmp)
                                            currentAmp = amp
                                            currentDur = sampleDurationMs
                                        }
                                    }
                                    if (currentDur > 0) {
                                        // v3.11: No overlap tail — let the motor stop between beats
                                        cDurations.add(currentDur)
                                        cAmplitudes.add(currentAmp)
                                    }
                                    vibrateProxy.performWaveform(cDurations.toLongArray(), cAmplitudes.toIntArray())
                                     
                                    if (frameCounter % 30L == 0L) {
                                        val renderMsg = "▶ RENDERED: max=$finalMax dur=${cDurations.sum()}ms"
                                        Log.i(TAG, renderMsg)
                                        LogBroadcaster.sendLog(context, renderMsg)
                                    }
                                } else {
                                    if (frameCounter % 30L == 0L) {
                                        val silenceMsg = "▶ RENDERED SILENCE (finalMax=0)"
                                        Log.i(TAG, silenceMsg)
                                        LogBroadcaster.sendLog(context, silenceMsg)
                                    }
                                }
                            }

                            LinkHealthMonitor.heartbeatVibrateCall()
                            val normalizedMaxAmp = maxAmp / 255.0f
                            directDriveSmoothAmp = directDriveSmoothAmp * 0.7f + normalizedMaxAmp * 0.3f  // v2.1.2: Smoother decay curve

                            if (frameCounter % 30L == 0L) {
                                val ampStr = (0 until usableSampleCount).joinToString(",") { fallbackBuffer[it].toInt().coerceIn(0, 255).toString() }
                                val modeStr = if (usingPcmFallback) "FALLBACK" else "NATIVE_SEMANTIC"
                                val fusionMsg = "▶ FUSION v${BuildConfig.VERSION_NAME} | mode=$modeStr | samples=$sampleCount amps=[$ampStr] max=${fallbackBuffer.maxOrNull()?.toInt() ?: 0} smooth=$directDriveSmoothAmp | hasMultiTrack=${hapticTimeline.hasMultiTrackActive()} | semFrames=$semanticFrameCount | usableSamples=$usableSampleCount"
                                Log.i(TAG, fusionMsg)
                                LogBroadcaster.sendLog(context, fusionMsg)
                            }
                        }
                    } else {
                        // v3.8.6: Log why vibration path was NOT entered
                        if (frameCounter % 30L == 0L) {
                            val skipMsg = "▶ VIBRATION SKIPPED | hasAudio=$hasAudioActivity maxAmp=$maxAmp(≤1) multiTrack=${hapticTimeline.hasMultiTrackActive()} semFrames=$semanticFrameCount usableSamples=$usableSampleCount"
                            Log.i(TAG, skipMsg)
                            LogBroadcaster.sendLog(context, skipMsg)
                        }
                        // Near-silence from C++ (amplitude ≤ 2): smooth decay, don't hard-cancel
                        // v3.7.2: Removed separate decay vibrate() call — it was
                        // immediately cancelled by the body waveform, creating a
                        // micro-gap.  Instead, just update the smooth amplitude;
                        // the body path below will play a quiet waveform naturally
                        // because the C++ samples themselves are already low.
                        directDriveSmoothAmp = directDriveSmoothAmp * 0.7f
                    }
                } else if (timeSinceAudio >= silenceTimeoutMs) {
                    // True silence (1500ms no audio) — stop vibration
                    if (frameCounter % 60L == 0L) {
                        vibrateProxy.cancel()
                    }
                    directDriveSmoothAmp = 0f
                }

                LinkHealthMonitor.heartbeatTelemetry()

                if (frameCounter % 60L == 0L) {
                    synchronizeParameters()
                }

                // Periodic telemetry output (every ~600ms at 50ms intervals)
                // Do not archive empty polling frames. Before PCM arrives the coroutine still
                // wakes on schedule, but `samples=0 / S=M=T=0` is not diagnostic data.
                // v3.13: Telemetry bypass - NO String.format, NO IPC, NO logging in hot path.
                // Only capture lightweight volatile snapshots; a separate slow coroutine
                // handles formatting and broadcasting. This prevents diagnostic overhead
                // from stealing CPU time from the vibration waveform pipeline.
                if (hasAudioActivity && usableSampleCount > 0 && frameCounter % 12L == 0L) {
                    val latency = SystemClock.elapsedRealtime() - frameStartTime
                    telemetryData.frameLatencyMs = latency
                    telemetryData.dispatchedSubBassImpacts++
                    // Snapshot all values needed - cheap volatile reads, no allocation
                    val snapSub = telemetryData.subBassOutputLevel
                    val snapMid = telemetryData.midBassOutputLevel
                    val snapTex = telemetryData.presenceOutputLevel
                    val snapF0 = telemetryData.fundamentalFrequencyHz
                    val snapTemp = telemetryData.estimatedCoilTemperature
                    val snapThermalGain = telemetryData.thermalAttenuationFactor
                    val snapLoFreq = telemetryData.lowPassCutoffHz
                    val snapHiFreq = telemetryData.highPassCutoffHz
                    val snapAmpScale = telemetryData.userAmplitudeScale
                    val snapOverruns = telemetryData.ringBufferOverruns
                    val snapSubCount = telemetryData.dispatchedSubBassImpacts
                    val snapMidCount = telemetryData.dispatchedMidBassTransients
                    val snapTexCount = telemetryData.dispatchedMicroTextures
                    val snapQ = hapticEventGenerator.profile.actuator.qFactor
                    val snapSmoothAmp = directDriveSmoothAmp
                    val snapSampleCount = sampleCount
                    val snapUsableCount = usableSampleCount
                    val snapKsActive = hapticComposer.lastKeyStrikeActive
                    val snapKsSem = hapticComposer.lastKeyStrikeSemantic
                    val snapSemType = hapticComposer.lastSemanticType
                    val snapLraDisp = hapticComposer.lastDisplacement
                    val snapLraVel = hapticComposer.lastVelocity
                    val snapLraForce = hapticComposer.lastForce
                    val snapLraPhase = hapticComposer.lastPhase
                    val snapAdsr = hapticComposer.lastEnvelope
                    val snapCompThermal = hapticComposer.lastThermalGain
                    val snapPersona = hapticComposer.currentPersona.displayName
                    val snapPrimType = hapticComposer.lastPrimitive?.typeName ?: ""
                    val snapPrimSem = hapticComposer.lastSemanticEvent?.label ?: ""
                    val snapPrimInt = hapticComposer.lastPrimitive?.let { when(it) { is HapticPrimitive.Impact -> it.intensity; is HapticPrimitive.Pulse -> it.intensity; is HapticPrimitive.Texture -> it.intensity; is HapticPrimitive.Wave -> 0 } } ?: 0
                    val snapPrimDur = hapticComposer.lastPrimitive?.let { when(it) { is HapticPrimitive.Impact -> it.durationMs; is HapticPrimitive.Pulse -> it.periodMs; is HapticPrimitive.Texture -> it.durationMs; is HapticPrimitive.Wave -> it.durationMs } } ?: 0
                    val snapGamma = hapticComposer.getEffectiveGamma()
                    // Offload ALL formatting + IPC to a background coroutine
                    engineScope.launch(Dispatchers.Default) {
                        val logMsg = String.format(
                            "DSP v${BuildConfig.VERSION_NAME} [Semantic] | S:%.2f M:%.2f T:%.2f | F0:%.0fHz Q=%.0f native=%d rendered=%d smooth=%.2f Δ=%dms",
                            snapSub, snapMid, snapTex, snapF0, snapQ, snapSampleCount, snapUsableCount, snapSmoothAmp, latency
                        )
                        logCallback?.onLog(logMsg)
                        LogBroadcaster.sendLog(context, logMsg)
                        LogBroadcaster.sendTelemetry(
                            context = context, sub = snapSub, mid = snapMid, pres = snapTex,
                            f0 = snapF0, temp = snapTemp, atten = snapThermalGain, latency = latency,
                            loFreq = snapLoFreq, hiFreq = snapHiFreq, ampScale = snapAmpScale,
                            overruns = snapOverruns, subCount = snapSubCount, midCount = snapMidCount,
                            texCount = snapTexCount, keyStrikeActive = snapKsActive,
                            keyStrikeSemantic = snapKsSem, semanticType = snapSemType,
                            lraDisp = snapLraDisp, lraVel = snapLraVel, lraForce = snapLraForce,
                            lraPhase = snapLraPhase, adsrEnv = snapAdsr, thermalGain = snapCompThermal,
                            personaName = snapPersona, primitiveType = snapPrimType,
                            primitiveSemantic = snapPrimSem, primitiveIntensity = snapPrimInt,
                            primitiveDuration = snapPrimDur, gammaValue = snapGamma
                        )
                    }
                }

                frameCounter++
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Coroutine cancelled (e.g. release() called) — exit gracefully
                Log.i(TAG, "Continuous haptic loop cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Continuous haptic loop error: ${e.message}")
            }

            // Sleep until next pull interval
            val elapsed = SystemClock.elapsedRealtime() - frameStartTime
            val sleepMs = (pullIntervalMs - elapsed).coerceIn(1L, pullIntervalMs)
            try {
                kotlinx.coroutines.delay(sleepMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Continuous haptic loop cancelled during sleep")
                break
            }
        }
    }

// playWaveformSegment and generateDirectDriveSegment removed — replaced by runContinuousHapticLoop
    // which pulls continuous amplitude frames from C++ 5-layer synthesizer and plays them
    // directly via VibrationEffect.createWaveform, eliminating discrete on-off pulse generation.

    // updateSynthesizerTelemetry removed — C++ 5-layer engine handles all synthesis internally.
    // Telemetry is now updated directly in executeDspPipeline from nativeTelemetryResult.

    // ════════════════════════════════════════════════════════════════
    //  v2.1: Native Haptic Frame Callback
    //  Called from the C++ scheduler thread at 20ms intervals.
    //  Receives a batch of amplitude samples and immediately drives the vibrator.
    //  This bypasses coroutine delay entirely — the native thread uses
    //  clock_nanosleep(CLOCK_MONOTONIC) for precise 10ms timing.
    // ════════════════════════════════════════════════════════════════

    fun synchronizeParameters() {
        val masterState = try { prefs.getBoolean("master_switch", true) } catch (e: Exception) { true }
        isEngineEnabled.set(masterState)

        val baseAmplitude = try { prefs.getFloat("haptic_amplitude", 2.0f) } catch (e: Exception) { 2.0f }
        val boostLevel = try {
            // haptic_boost_level is the canonical UI-to-engine key; old installs may only have the legacy name.
            if (prefs.contains("haptic_boost_level")) prefs.getFloat("haptic_boost_level", 1.0f)
            else prefs.getFloat("haptic_bass_boost", 1.0f)
        } catch (e: Exception) { 1.0f }
        val presetId = try { prefs.getInt("haptic_preset_id", HapticPreset.BALANCED.id) } catch (e: Exception) { HapticPreset.BALANCED.id }
        val crossoverBypass = try { prefs.getBoolean("crossover_bypass", true) } catch (e: Exception) { true }
        val powerAmplify = try { prefs.getBoolean("power_amplify", false) } catch (e: Exception) { false }
        val uiPreset = try { prefs.getInt("selected_preset", 2) } catch (e: Exception) { 2 }
        val presetGain = floatArrayOf(0.70f, 0.90f, 1.00f, 1.20f).getOrElse(uiPreset) { 1.00f }
        val outputAmp = (baseAmplitude * presetGain * if (powerAmplify) 1.15f else 1.0f).coerceIn(0.5f, 4.0f)

        // "有源分频" selects the focused bass band; bypass keeps a wider musical band.
        val lowCutoffFreq = if (crossoverBypass) 55.0f else 150.0f
        val highCutoffFreq = if (crossoverBypass) 650.0f else 330.0f

        nativeBridge.configure(
            sampleRate = sampleRate.toFloat(),
            lowCut = lowCutoffFreq,
            highCut = highCutoffFreq,
            amplitude = outputAmp,
            presetId = presetId
        )

        telemetryData.lowPassCutoffHz = lowCutoffFreq
        telemetryData.highPassCutoffHz = highCutoffFreq
        telemetryData.userAmplitudeScale = outputAmp

        hapticEventGenerator.boostLevel = boostLevel
        hapticEventGenerator.userAmplitudeScale = outputAmp.coerceIn(0.5f, 4.0f)

        val silenceTh = try { prefs.getFloat("silence_threshold", Float.NaN) } catch (e: Exception) { Float.NaN }
        hapticEventGenerator.injectedSilenceThreshold = if (silenceTh.isNaN()) null else silenceTh

        val energyTh = try { prefs.getFloat("energy_threshold", Float.NaN) } catch (e: Exception) { Float.NaN }
        hapticEventGenerator.injectedEnergyThreshold = if (energyTh.isNaN()) null else energyTh

        val minAmp = try { prefs.getInt("min_amplitude", -1) } catch (e: Exception) { -1 }
        hapticEventGenerator.injectedMinGuaranteedAmplitude = if (minAmp < 0) null else minAmp

        hapticEventGenerator.synchronizeProfile(prefs)
        hapticEventGenerator.synchronizePreset(prefs)

        hapticEventGenerator.logListener = { msg ->
            logCallback?.onLog(msg)
            LogBroadcaster.sendLog(context, msg)
        }

        val synthConfig = HapticSynthesizer.SynthConfig(
            synthesisRateHz = try { prefs.getInt("synth_rate_hz", HapticSynthesizer.SYNTHESIS_RATE_HZ) } catch (e: Exception) { HapticSynthesizer.SYNTHESIS_RATE_HZ },
            lraF0 = try { prefs.getFloat("synth_lra_f0", HapticSynthesizer.LRA_F0) } catch (e: Exception) { HapticSynthesizer.LRA_F0 },
            lraQ = try { prefs.getFloat("synth_lra_q", HapticSynthesizer.LRA_Q) } catch (e: Exception) { HapticSynthesizer.LRA_Q },
            attackTauImpact = try { prefs.getFloat("synth_attack_impact", HapticSynthesizer.ATTACK_TAU_IMPACT) } catch (e: Exception) { HapticSynthesizer.ATTACK_TAU_IMPACT },
            decayTauImpact = try { prefs.getFloat("synth_decay_impact", HapticSynthesizer.DECAY_TAU_IMPACT) } catch (e: Exception) { HapticSynthesizer.DECAY_TAU_IMPACT },
            attackTauContinuous = try { prefs.getFloat("synth_attack_continuous", HapticSynthesizer.ATTACK_TAU_CONTINUOUS) } catch (e: Exception) { HapticSynthesizer.ATTACK_TAU_CONTINUOUS },
            decayTauContinuous = try { prefs.getFloat("synth_decay_continuous", HapticSynthesizer.DECAY_TAU_CONTINUOUS) } catch (e: Exception) { HapticSynthesizer.DECAY_TAU_CONTINUOUS },
            releaseTau = try { prefs.getFloat("synth_release", HapticSynthesizer.RELEASE_TAU) } catch (e: Exception) { HapticSynthesizer.RELEASE_TAU },
            sustainLevel = try { prefs.getFloat("synth_sustain", HapticSynthesizer.SUSTAIN_LEVEL) } catch (e: Exception) { HapticSynthesizer.SUSTAIN_LEVEL },
            thermalWarn = try { prefs.getFloat("synth_thermal_warn", HapticSynthesizer.THERMAL_WARN) } catch (e: Exception) { HapticSynthesizer.THERMAL_WARN },
            thermalCrit = try { prefs.getFloat("synth_thermal_crit", HapticSynthesizer.THERMAL_CRIT) } catch (e: Exception) { HapticSynthesizer.THERMAL_CRIT },
            thermalRth = try { prefs.getFloat("synth_thermal_rth", HapticSynthesizer.THERMAL_RTH) } catch (e: Exception) { HapticSynthesizer.THERMAL_RTH },
            thermalCth = try { prefs.getFloat("synth_thermal_cth", HapticSynthesizer.THERMAL_CTH) } catch (e: Exception) { HapticSynthesizer.THERMAL_CTH },
            impactGain = try { prefs.getFloat("synth_impact_gain", 1.0f) } catch (e: Exception) { 1.0f },
            continuousGain = try { prefs.getFloat("synth_continuous_gain", 1.0f) } catch (e: Exception) { 1.0f },
            textureGain = try { prefs.getFloat("synth_texture_gain", 1.0f) } catch (e: Exception) { 1.0f },
            masterGain = try { prefs.getFloat("synth_master_gain", 1.0f) } catch (e: Exception) { 1.0f },
        )
        hapticSynthesizer.updateParameters(synthConfig)
    }

    /** Applies values freshly copied into this process's haptic_settings snapshot. */
    fun refreshSettings() {
        synchronizeParameters()
        hapticComposer.updatePreferences()
        val message = "Settings refreshed in hooked process | master=${isEngineEnabled.get()} amp=${"%.2f".format(telemetryData.userAmplitudeScale)}"
        Log.i(TAG, message)
        LogBroadcaster.sendLog(context, message)
    }

    fun reconfigure(newSampleRate: Int, newChannels: Int) {
        if (newSampleRate <= 0 || newChannels <= 0 || newChannels > MAXIMUM_CHANNELS) {
            Log.w(TAG, "Reconfiguration rejected: ${newSampleRate}Hz | $newChannels Ch")
            return
        }

        if (this.sampleRate == newSampleRate && this.channels == newChannels) return

        this.sampleRate = newSampleRate
        this.channels = newChannels

        audioRingBuffer.clear()
        synchronizeParameters()

        val logMessage = "System reconfigured to: ${sampleRate}Hz | $channels Channels (Native Engine Active)"
        Log.i(TAG, logMessage)
        logCallback?.onLog(logMessage)
        LogBroadcaster.sendLog(context, logMessage)
    }

    /**
     * Called when AudioTrack.pause() or stop() is detected via Hook.
     * Immediately forces all ADSR envelopes into release state and cancels
     * any ongoing vibration, preventing the "still vibrating after pause" bug.
     */
    fun onPlaybackPaused() {
        Log.i(TAG, "[PLAYBACK PAUSED] Forcing immediate haptic decay")
        LogBroadcaster.sendLog(context, "[PLAYBACK PAUSED] Forcing immediate haptic decay")
        
        hapticPaused = true  // v2.1.1: Immediately block native callbacks from driving vibrator
        vibrateProxy.setPaused()  // v2.1.2: Hard pause gate at proxy level — blocks all performXxx() calls
        nativeBridge.clearHapticBuffer()
        directDriveSmoothAmp = 0f
        pendingPrimitive = null  // v1.8: Clear semantic bridge
        pendingSemanticLabel = "NONE"
        hapticSynthesizer.forceDecay()
        audioRingBuffer.clear()
        LinkHealthMonitor.setPlayingState(false)
    }

    fun processAudioFrame(pcmData: ShortArray?) {
        if (pcmData == null || pcmData.isEmpty() || !isEngineEnabled.get()) {
            if (!isEngineEnabled.get()) {
                audioRingBuffer.clear()
                vibrateProxy.cancel()
            }
            return
        }

        // v2.1.1: Clear pause flag — new audio means playback resumed
        if (hapticPaused) {
            hapticPaused = false
            vibrateProxy.setResumed()  // v2.1.2: Re-enable proxy output
            nativeBridge.clearHapticBuffer()  // Flush any stale samples from C++ ring buffer
            Log.i(TAG, "[PLAYBACK RESUMED] hapticPaused cleared, native callbacks re-enabled")
        }

        LinkHealthMonitor.setPlayingState(true)

        LinkHealthMonitor.heartbeatAudioInput()

        if (frameIndexCounter.get() % 50L == 0L) {
            Log.d("HapticLink", "【节点 1】音频已输入 | 采样点数: ${pcmData.size} | channels: $channels | engineEnabled: ${isEngineEnabled.get()}")
        }

        val sampleLength = pcmData.size
        val targetMonoSamples = sampleLength / channels
        if (targetMonoSamples <= 0) return

        val normalizedBuffer = FloatArray(targetMonoSamples)
        var writerOffset = 0

        try {
            when (channels) {
                1 -> {
                    val scaleFactor = 1.0f / 32768.0f
                    for (i in pcmData.indices) {
                        normalizedBuffer[writerOffset++] = pcmData[i].toFloat() * scaleFactor
                    }
                }
                2 -> {
                    var i = 0

                    val scaleFactor = 1.0f / 65536.0f
                    while (i < sampleLength - 1) {
                        normalizedBuffer[writerOffset++] = (pcmData[i].toFloat() + pcmData[i + 1].toFloat()) * scaleFactor
                        i += 2
                    }
                }
                else -> {
                    var i = 0
                    val channelNormalizationFactor = 1.0f / (channels.toFloat() * 32768.0f)
                    while (i < sampleLength - channels + 1) {
                        var matrixAccumulator = 0.0f
                        for (c in 0 until channels) {
                            matrixAccumulator += pcmData[i + c].toFloat()
                        }
                        normalizedBuffer[writerOffset++] = matrixAccumulator * channelNormalizationFactor
                        i += channels
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PCM normalization exception: ${e.message}")
            return
        }

        // Measure ingress before touching the DSP. A number of apps issue regular
        // zero-filled writes for an auxiliary/mixer track; those must not masquerade
        // as music just because AudioTrack.write() was invoked.
        var sumSquares = 0.0
        var peak = 0f
        for (i in 0 until writerOffset) {
            val value = normalizedBuffer[i]
            sumSquares += (value * value).toDouble()
            peak = maxOf(peak, abs(value))
        }
        val rms = if (writerOffset > 0) sqrt(sumSquares / writerOffset).toFloat() else 0f
        val hasMeaningfulPcm = rms >= pcmActivityRmsFloor || peak >= pcmActivityPeakFloor
        if (!hasMeaningfulPcm) {
            ignoredSilentPcmBlocks++
            return
        }

        val now = SystemClock.elapsedRealtime()
        pcmFallbackAmplitude = (18f + sqrt(rms) * 300f).toInt().coerceIn(0, 150)
        pcmFallbackAtMs = now
        if (now - lastPcmIngressLogMs >= 1000L) {
            lastPcmIngressLogMs = now
            val message = "PCM ingress | mono=$writerOffset rms=${"%.4f".format(rms)} peak=${"%.4f".format(peak)} ignoredSilent=$ignoredSilentPcmBlocks"
            Log.i(TAG, message)
            LogBroadcaster.sendLog(context, message)
            ignoredSilentPcmBlocks = 0L
        }

        audioRingBuffer.write(normalizedBuffer, writerOffset)

        var processingSafetyIterations = 0
        while (audioRingBuffer.read(processingFrame, FRAME_BLOCK_SIZE)) {
            if (processingSafetyIterations++ > 64) {
                telemetryData.ringBufferOverruns++
                break
            }
            try {
                executeDspPipeline(processingFrame)
            } catch (t: Throwable) {
                Log.e(TAG, "DSP pipeline crashed: ${t.message}")
                audioRingBuffer.clear()
                break
            }
        }
    }

    private fun detectPcmLowBandAttack(block: FloatArray, timestampMs: Long) {
        // One-pole ~190 Hz envelope proxy. It is deliberately cheap enough for
        // the hooked real-time path and only feeds a semantic candidate track.
        var lowEnergy = 0f
        for (sample in block) {
            pcmLowPassState += 0.024f * (sample - pcmLowPassState)
            lowEnergy += abs(pcmLowPassState)
        }
        val blockEnvelope = lowEnergy / block.size
        pcmLowBandEnvelope += 0.55f * (blockEnvelope - pcmLowBandEnvelope)
        val previousBaseline = pcmLowBandBaseline
        pcmLowBandBaseline += 0.035f * (pcmLowBandEnvelope - pcmLowBandBaseline)

        // Video has many incidental low-frequency cuts/effects. Keep it more
        // conservative and softer than dedicated music-player rendering.
        val isVideoApp = targetPackage == "tv.danmaku.bili"
        val ratioThreshold = if (isVideoApp) 2.65f else 2.05f
        val absoluteThreshold = if (isVideoApp) 0.018f else 0.011f
        val cooldownMs = if (isVideoApp) 180L else 105L
        val rise = pcmLowBandEnvelope - previousBaseline
        val lowAttack = pcmLowBandEnvelope >= absoluteThreshold &&
            pcmLowBandEnvelope >= previousBaseline * ratioThreshold &&
            rise >= (if (isVideoApp) 0.005f else 0.003f) &&
            timestampMs - lastPcmLowBandOnsetMs >= cooldownMs
        if (!lowAttack) return

        lastPcmLowBandOnsetMs = timestampMs
        pcmLowBandOnsetCount++
        val intensity = if (isVideoApp) {
            (128f + pcmLowBandEnvelope * 1800f).toInt().coerceIn(128, 176)
        } else {
            (190f + pcmLowBandEnvelope * 2100f).toInt().coerceIn(190, 245)
        }
        hapticTimeline.offerPrimitive(
            HapticPrimitive.Impact(
                intensity = intensity,
                durationMs = if (isVideoApp) 38 else 52,
                velocityFactor = 1f,
                sharpness = 0.32f,
                semantic = "PCM_LOW_BAND_ATTACK"
            ),
            timestampMs
        )
        if (pcmLowBandOnsetCount % 6L == 1L) {
            val message = "Timeline PCM low-band attack queued #$pcmLowBandOnsetCount env=${"%.4f".format(pcmLowBandEnvelope)} base=${"%.4f".format(previousBaseline)} amp=$intensity app=$targetPackage"
            Log.i(TAG, message)
            LogBroadcaster.sendLog(context, message)
        }
    }

    private fun executeDspPipeline(block: FloatArray) {
        val currentTimeMs = SystemClock.elapsedRealtime()
        detectPcmLowBandAttack(block, currentTimeMs)

        if (currentTimeMs - lastParameterUpdateTime > 300) {
            synchronizeParameters()
            lastParameterUpdateTime = currentTimeMs
        }

        val currentFrameId = frameIndexCounter.incrementAndGet()

        floatPcmView.position(0)
        floatPcmView.put(block, 0, FRAME_BLOCK_SIZE)

        // The size parameter specifies how many samples in directPcmBuffer.
        // C++ size is in number of floats.
        nativeBridge.processAudioDirect(directPcmBuffer, FRAME_BLOCK_SIZE, nativeTelemetryResult)

        val finalSubIntensity = nativeTelemetryResult[0]
        val finalMidIntensity = nativeTelemetryResult[1]
        val finalPresenceIntensity = nativeTelemetryResult[2]
        val detectedFundamentalFreq = nativeTelemetryResult[3]
        val estimatedCoilTemperature = nativeTelemetryResult[4]
        val thermalSafetyGain = nativeTelemetryResult[5]
        // v1.7 Semantic Bridge: beat/onset telemetry from C++
        val beatStrength = nativeTelemetryResult[6]
        val onsetFlag = nativeTelemetryResult[7] > 0.5f
        val beatIntervalMs = nativeTelemetryResult[8]
        val beatConfidence = nativeTelemetryResult[9]
        val instrumentFeatures = InstrumentFeatures(
            kick = nativeTelemetryResult[10].coerceIn(0f, 1f),
            snare = nativeTelemetryResult[11].coerceIn(0f, 1f),
            hiHat = nativeTelemetryResult[12].coerceIn(0f, 1f),
            vocal = nativeTelemetryResult[13].coerceIn(0f, 1f),
            plucked = nativeTelemetryResult[14].coerceIn(0f, 1f),
            harmonic = nativeTelemetryResult[15].coerceIn(0f, 1f),
            bassSustain = nativeTelemetryResult[16].coerceIn(0f, 1f),
            pitchConfidence = nativeTelemetryResult[17].coerceIn(0f, 1f),
            vocalEnergy = nativeTelemetryResult[18].coerceAtLeast(0f),
            airEnergy = nativeTelemetryResult[19].coerceAtLeast(0f)
        )

        currentMusicStructure = musicStructureAnalyzer.update(
            timestampMs = currentTimeMs,
            sub = finalSubIntensity,
            mid = finalMidIntensity,
            texture = finalPresenceIntensity,
            isBeat = onsetFlag,
            instruments = instrumentFeatures
        )

        LinkHealthMonitor.heartbeatDspOutput()

        if (currentFrameId % 20L == 0L) {
            Log.d("HapticLink", "【节点 2】Native 输出 | Sub: $finalSubIntensity | Mid: $finalMidIntensity | Texture: $finalPresenceIntensity | F0: ${detectedFundamentalFreq}Hz | Temp: ${estimatedCoilTemperature}°C | ThermalGain: $thermalSafetyGain | Beat: ${"%.2f".format(beatStrength)} onset=$onsetFlag IBI=${beatIntervalMs.toInt()}ms conf=${"%.2f".format(beatConfidence)}")
        }

        if (currentFrameId % 12L == 0L) {
            Log.d("HapticDebug", "Sub: $finalSubIntensity | Mid: $finalMidIntensity | Temp: ${estimatedCoilTemperature}°C | ThermalGain: $thermalSafetyGain | Pitch: ${detectedFundamentalFreq}Hz | Beat: ${"%.2f".format(beatStrength)} IBI=${beatIntervalMs.toInt()}ms")
        }

        telemetryData.subBassOutputLevel = finalSubIntensity
        telemetryData.midBassOutputLevel = finalMidIntensity
        telemetryData.presenceOutputLevel = finalPresenceIntensity
        telemetryData.fundamentalFrequencyHz = detectedFundamentalFreq
        telemetryData.estimatedCoilTemperature = estimatedCoilTemperature
        telemetryData.thermalAttenuationFactor = thermalSafetyGain

        // ══════════════════════════════════════════════════════════════════
        // v1.7 Semantic Bridge: Feed DSP output to HapticComposer
        // Composer runs at reduced rate (every 2nd frame = ~50Hz at 100Hz DSP)
        // It analyzes the music semantically but does NOT directly drive vibration.
        // Output is logged for verification — no haptic changes yet.
        // ══════════════════════════════════════════════════════════════════
        if (isEngineEnabled.get()) {
            if (thermalSafetyGain <= 0.01f) {
                nativeBridge.clearHapticBuffer()
                vibrateProxy.cancel()
                directDriveSmoothAmp = 0f
            }

            // Low-band onset fallback track: native onset is available from the live DSP
            // telemetry even when its optional family-probability binary is stale.
            // This is intentionally a bass-attack cue, not an asserted Kick classifier.
            val lowBandOnset = onsetFlag && finalSubIntensity >= 0.035f &&
                currentTimeMs - lastLowBandOnsetMs >= 95L
            if (lowBandOnset) {
                lastLowBandOnsetMs = currentTimeMs
                lowBandOnsetCount++
                val intensity = (185f + finalSubIntensity.coerceIn(0f, 1f) * 70f).toInt()
                    .coerceIn(185, 255)
                hapticTimeline.offerPrimitive(
                    HapticPrimitive.Impact(
                        intensity = intensity,
                        durationMs = 48,
                        velocityFactor = 1f,
                        sharpness = 0.25f,
                        semantic = "LOW_BAND_ONSET"
                    ),
                    currentTimeMs
                )
                if (lowBandOnsetCount % 8L == 1L) {
                    Log.i(TAG, "Timeline low-band onset queued #$lowBandOnsetCount sub=${"%.3f".format(finalSubIntensity)} amp=$intensity")
                }
            }

            // Feed Composer every 2nd frame (~50Hz, matching human semantic resolution)
            if (currentFrameId % 2L == 0L) {
                try {
                    hapticComposer.processFrame(
                        subBass = finalSubIntensity,
                        midBass = finalMidIntensity,
                        texture = finalPresenceIntensity,
                        pitch = detectedFundamentalFreq,
                        timestamp = currentTimeMs,
                        instruments = instrumentFeatures
                    )

                    // Drain every command into the timeline. Keeping only the last command
                    // silently discarded a Kick whenever a later sustain/texture command
                    // arrived during the same DSP cycle.
                    var lastCommand: HapticCommand? = null
                    while (true) {
                        val command = hapticComposer.hapticCommands.tryReceive().getOrNull() ?: break
                        hapticTimeline.offer(command)
                        lastCommand = command
                    }
                    lastCommand?.let { command ->
                        if (currentFrameId % 10L == 0L) {
                            val beatStr = if (command.isBeat) "BEAT" else "---"
                            val ksStr = if (command.isKeyStrike) "KS=${command.keyStrikeSemantic.name}" else ""
                            val primStr = command.primitive?.typeName ?: "none"
                            val semStr = command.semanticEvent?.label ?: "none"
                            val layerStr = if (command.additionalPrimitives.isNotEmpty()) {
                                command.additionalPrimitives.joinToString(",") { prim ->
                                    val sem = when (prim) {
                                        is HapticPrimitive.Impact -> prim.semantic
                                        is HapticPrimitive.Pulse -> prim.semantic
                                        is HapticPrimitive.Texture -> prim.semantic
                                        is HapticPrimitive.Wave -> prim.semantic
                                    }
                                    "${prim.typeName}:$sem"
                                }
                            } else ""
                            Log.i("SemanticBridge", "▶ Composer | $beatStr $ksStr | Sem=$semStr | Prim=$primStr | Layers=[$layerStr] | I=${"%.2f".format(command.intensity)} | Persona=${hapticComposer.currentPersona.name} | Section=${currentMusicStructure.section} | Env=${"%.2f".format(command.adsrEnvelope)} | C++Beat=${"%.2f".format(beatStrength)} IBI=${beatIntervalMs.toInt()}ms")
                            LogBroadcaster.sendLog(context, "SemanticBridge | $beatStr $ksStr | Sem=$semStr | Prim=$primStr | Layers=[$layerStr] | Persona=${hapticComposer.currentPersona.name} | Section=${currentMusicStructure.section} energy=${"%.2f".format(currentMusicStructure.energy)} conf=${"%.2f".format(currentMusicStructure.confidence)}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Composer processFrame error: ${e.message}")
                }
            }
        }
    }

    fun release() {

        // v2.1: Stop native scheduler first — pthread_join ensures clean exit
        if (nativeSchedulerActive) {
            try { nativeBridge.stopScheduler() } catch (_: Throwable) {}
            nativeSchedulerActive = false
            Log.i(TAG, "Native Haptic Scheduler stopped (pthread_join complete).")
        }

        LinkHealthMonitor.setPlayingState(false)
        hapticEventGenerator.release()
        hapticComposer.release()
        hapticSynthesizer.reset()
        engineJob.cancel()
        audioRingBuffer.clear()
        nativeBridge.release()
        vibrateProxy.setPaused()  // v2.1.2: Block any pending vibrations during shutdown
        vibrateProxy.unbind()  // v2.1.2: Unbind IPC proxy service
        Log.i(TAG, "DSP Engine successfully shutdown.")
    }

    class TelemetryMonitor {
        @Volatile var lowPassCutoffHz = 0.0f
        @Volatile var highPassCutoffHz = 0.0f
        @Volatile var userAmplitudeScale = 1.0f
        @Volatile var fundamentalFrequencyHz = 0.0f
        @Volatile var estimatedCoilTemperature = AMBIENT_TEMPERATURE_CELSIUS
        @Volatile var thermalAttenuationFactor = 1.0f
        @Volatile var subBassOutputLevel = 0.0f
        @Volatile var midBassOutputLevel = 0.0f
        @Volatile var presenceOutputLevel = 0.0f
        @Volatile var ringBufferOverruns = 0L
        @Volatile var dispatchedSubBassImpacts = 0L
        @Volatile var dispatchedMidBassTransients = 0L
        @Volatile var dispatchedMicroTextures = 0L
        @Volatile var frameLatencyMs = 0L

        @Volatile var lraDisplacement = 0f
        @Volatile var lraVelocity = 0f
        @Volatile var lraForce = 0f
        @Volatile var lraPhase = 0f
        @Volatile var adsrEnvelope = 0f
        @Volatile var coilTemperature = 25f
        @Volatile var thermalGain = 1f
    }
}