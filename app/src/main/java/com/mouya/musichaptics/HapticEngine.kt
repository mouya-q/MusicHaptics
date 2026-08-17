package com.mouya.musichaptics

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
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
    private val targetPackage: String = context.packageName
) {
    companion object {
        private const val TAG = "HapticDSPCore"

        private const val RING_BUFFER_CAPACITY = 131072

        private const val FRAME_BLOCK_SIZE = 256

        private const val MAXIMUM_CHANNELS = 8

        private fun outputGainForPackage(packageName: String): Float = when (packageName) {
            // v4.0: All gains boosted for full dynamic range.
            "com.kugou.android.lite",
            "com.kugou.android" -> 1.45f  // v4.0: was 1.15
            "tv.danmaku.bili" -> 1.50f  // v4.0: was 1.28
            "cn.kuwo.player" -> 1.40f  // v4.0: was 1.10
            "com.netease.cloudmusic" -> 1.45f  // v4.0: was 1.15
            "org.flos.phira" -> 1.45f  // v4.0: was 1.20
            "com.md3music.md3music" -> 1.45f  // v4.0: was 1.15
            else -> 1.40f  // v4.0: was 1.10
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

    private val vibrateProxy = VibrateProxy(context)

    private val directPcmBuffer: ByteBuffer = ByteBuffer.allocateDirect(FRAME_BLOCK_SIZE * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val floatPcmView: FloatBuffer = directPcmBuffer.asFloatBuffer()

    private val nativeTelemetryResult = FloatArray(20)

    private val deviceProfile = detectDeviceProfile(
        context = context,
        persistedProfileId = prefs.getString(RootHardwareProbe.PREF_PROFILE, null)
    )
    val hapticEventGenerator = HapticEventGenerator(context, deviceProfile)

    val hapticComposer = HapticComposer(context, deviceProfile, prefs)

    private val hapticSynthesizer = HapticSynthesizer(deviceProfile)

    private val hapticTimeline = HapticTimelineScheduler().also {
        it.adaptToActuatorQ(deviceProfile.actuator.qFactor)
    }

    private val musicStructureAnalyzer = MusicStructureAnalyzer()
    @Volatile private var currentMusicStructure = MusicStructureAnalyzer.Snapshot()

    @Volatile var isVisualizerSource = false

    private var vizPrevEnergy = 0f
    private var vizEnergyBaseline = 0f
    private var vizLastOnsetMs = 0L
    private var vizOnsetCount = 0L

    private var lastLowBandOnsetMs = 0L
    private var lowBandOnsetCount = 0L

    private var pcmLowPassState = 0f
    private var pcmLowBandEnvelope = 0f
    private var pcmLowBandBaseline = 0f
    private var lastPcmLowBandOnsetMs = 0L
    private var pcmLowBandOnsetCount = 0L
    // ─── v4.1: Vibration mode (KICK / BASS_COMP / SMART) ───
    enum class VibrationMode { KICK, BASS_COMP, SMART }
    @Volatile private var vibrationMode: VibrationMode = VibrationMode.SMART
    @Volatile private var bassCompSmoothAmp = 0f       // BASS_COMP low-freq envelope (0..1)
    @Volatile private var lastSubIntensity = 0f        // latest native sub-band energy
    private var lastKickImpactMs = 0L                  // kick/onset refractory (KICK mode)
    @Volatile private var hapticThreshold = 0f                    // v4.1: min intensity to vibrate (0..1)

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

    @Volatile private var directDriveSmoothAmp = 0f  // Smoothed amplitude for telemetry display
    @Volatile private var bodyAmpScale = 1.0f

    @Volatile private var nativeSchedulerActive = false
    @Volatile private var nativeLastAudioTime = 0L
    @Volatile private var hapticPaused = false  // v2.1.1: Immediate mute flag for pause/stop
    @Volatile var quietMode = false  // 静音时段（定时开关）
    @Volatile private var quietHoursEnabled = false
    private var quietHoursStart = "23:00"
    private var quietHoursEnd = "07:00"

    @Volatile private var pcmFallbackAmplitude = 0
    @Volatile private var pcmFallbackAtMs = 0L

    private var lastPcmIngressLogMs = 0L
    private var ignoredSilentPcmBlocks = 0L
    private val pcmActivityRmsFloor = 0.0003f
    private val pcmActivityPeakFloor = 0.0008f  // v3.15: was 0.0030 — preserve micro-transients

    @Volatile private var pendingPrimitive: HapticPrimitive? = null
    @Volatile private var pendingSemanticLabel: String = "NONE"
    @Volatile private var pendingPrimitiveTime: Long = 0L

    val telemetryData = TelemetryMonitor()


    init {

        synchronizeParameters()

        val proxyReady = vibrateProxy.init()
        Log.i(TAG, "VibrateProxy initialized: ready=$proxyReady path=${if (vibrateProxy.isProxyActive) "IPC_PROXY" else "DIRECT"}")
        Log.i(TAG, "App haptic calibration: package=$targetPackage outputGain=${outputGainForPackage(targetPackage)}")
        Log.i(TAG, "[Device Profile] name=${hapticEventGenerator.profile.name} actuator.f0=${hapticEventGenerator.profile.actuator.resonanceFreq}Hz maxAmp=${hapticEventGenerator.profile.actuator.maxAmplitude} damping=${hapticEventGenerator.profile.actuator.dampingRatio} q=${hapticEventGenerator.profile.actuator.qFactor}")
        Log.i(TAG, "[Vibrator Capability] hasAmpCtrl=${vibrateProxy.hasAmplitudeControl} primitives: CLICK=${vibrateProxy.primitiveClickSupported} TICK=${vibrateProxy.primitiveTickSupported} THUD=${vibrateProxy.primitiveHeavyClickSupported}")

        if (nativeBridge.isLoaded) {
            nativeBridge.onFrameCallback = { _, _ ->
            }
            nativeSchedulerActive = false
            Log.i(TAG, "Native Haptic Scheduler: DISABLED (v3.7.3 — using coroutine for app-consistent timing)")
        }

        engineScope.launch {
            runContinuousHapticLoop()
        }
        Log.i(TAG, "Using coroutine-based haptic loop (v3.7.3 uniform timing mode)")

        val readyMsg = "[System Ready] v${BuildConfig.VERSION_NAME} Dual-Track Fusion Engine: ${if (nativeBridge.isLoaded) "NATIVE ACTIVE" else "FALLBACK"} | Device: ${hapticEventGenerator.profile.name} | Actuator: ${hapticEventGenerator.profile.actuator.resonanceFreq.toInt()}Hz Q=${hapticEventGenerator.profile.actuator.qFactor} rise=${hapticEventGenerator.profile.actuator.riseTimeMs.toInt()}ms fall=${hapticEventGenerator.profile.actuator.fallTimeMs.toInt()}ms | C++ 5-Channel: Percussion+Bass+Vocal+Harmonic+Texture | Priority Masking: ON | No Bass Floor | Inter-frame Smooth: ON | Scheduler: ${if (nativeSchedulerActive) "NATIVE (10ms)" else "COROUTINE (100ms)"}"
        Log.i(TAG, readyMsg)
        logCallback?.onLog(readyMsg)
        LogBroadcaster.sendLog(context, readyMsg)
    }

    private suspend fun runContinuousHapticLoop() {
        val pullIntervalMs = 100L
        val sampleDurationMs = 10L  // Each amplitude sample → 10ms of vibration
        val maxSamplesPerPull = 10  // 10 samples × 10ms = one timeline window
        val frameBuffer = FloatArray(maxSamplesPerPull)

        val isApi29Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        var lastKickTime = 0L  // v4.0: No debounce — every onset fires
        var lastShortTickTime = 0L
        val kickRefractoryMs = 0L  // v4.0: was 70ms — ZERO cooldown
        val shortTickRefractoryMs = 0L  // v4.0: was 35ms — ZERO cooldown

        var lastSemanticImpactTime = 0L
        val semanticImpactRefractoryMs = 0L  // v4.0: was 50ms — ZERO cooldown

        val kickThreshold = 180
        val longVibeThreshold = 50

        var frameCounter = 0L
        var lastAudioInputTime = 0L
        val silenceTimeoutMs = 2500L

        while (true) {
            val frameStartTime = SystemClock.elapsedRealtime()

            try {
                if (hapticPaused) {
                    kotlinx.coroutines.delay(pullIntervalMs)
                    continue
                }
                // 静音时段判断（每 100ms 内联计算，避免自锁）
                if (quietHoursEnabled) {
                    val now = java.util.Calendar.getInstance()
                    val nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
                    val s = quietHoursStart.split(":").map { it.toIntOrNull() ?: 0 }
                    val e = quietHoursEnd.split(":").map { it.toIntOrNull() ?: 0 }
                    val startMin = s.getOrElse(0) { 23 } * 60 + s.getOrElse(1) { 0 }
                    val endMin = e.getOrElse(0) { 7 } * 60 + e.getOrElse(1) { 0 }
                    val inQuiet = if (startMin <= endMin) nowMin in startMin..endMin
                                 else nowMin >= startMin || nowMin <= endMin
                    if (inQuiet) {
                        kotlinx.coroutines.delay(pullIntervalMs)
                        continue
                    }
                }
        val nativeBuffer = FloatArray(maxSamplesPerPull)
        val nativeSampleCount = if (nativeBridge.isLoaded) {
            nativeBridge.getHapticFrame(nativeBuffer, maxSamplesPerPull)
        } else 0
        val sampleCount = nativeSampleCount
        val usableSampleCount = nativeSampleCount

        // v3.8: Pull semantic frames for multi-track fusion
        val semanticFrameBuffer = FloatArray(64)
        val semanticFrameCount = if (nativeBridge.isLoaded) {
            nativeBridge.getSemanticFrames(semanticFrameBuffer, 64)
        } else 0

        if (semanticFrameCount > 0) {
            hapticTimeline.applyMultiTrackFrames(semanticFrameBuffer, semanticFrameCount)
        }

        if (nativeSampleCount > 0 || semanticFrameCount > 0) {
            val maxAmpLegacy = if (nativeSampleCount > 0) (0 until nativeSampleCount).maxOfOrNull { nativeBuffer[it] } ?: 0f else 0f
            if (maxAmpLegacy > 1f || semanticFrameCount > 0) {
                lastAudioInputTime = frameStartTime
            }
        }

        val timeSinceAudio = frameStartTime - lastAudioInputTime
        val hasNativeAudioActivity = timeSinceAudio < silenceTimeoutMs
        val fallbackFresh = frameStartTime - pcmFallbackAtMs < silenceTimeoutMs
        val hasAudioActivity = hasNativeAudioActivity || fallbackFresh

        if (hasAudioActivity && vibrateProxy.paused) {
            vibrateProxy.setResumed()
        }

        if (hasAudioActivity && vibrateProxy.hasVibrator) {
            val semanticPrim = pendingPrimitive
            val semanticAge = frameStartTime - pendingPrimitiveTime
            val semanticFresh = semanticPrim != null && semanticAge < 100L

            if (semanticFresh) {
                val prim = semanticPrim!!
                val timeSinceSemantic = frameStartTime - lastSemanticImpactTime
                if (timeSinceSemantic >= semanticImpactRefractoryMs) {
                    hapticTimeline.offerPrimitive(prim, frameStartTime)
                    lastSemanticImpactTime = frameStartTime
                    pendingPrimitive = null
                }
            }

// v4.1: KICK -> zero out continuous body; BASS_COMP -> envelope
        val baseSamples = nativeBuffer
        val modeSamples = when (vibrationMode) {
            VibrationMode.KICK -> FloatArray(baseSamples.size) { 0f }
            VibrationMode.BASS_COMP -> {
                val target = if (lastSubIntensity > 0.005f) lastSubIntensity.coerceIn(0f, 1f) else 0f
                val alpha = if (target > bassCompSmoothAmp) 0.35f else 0.92f  // fast attack, slow decay
                bassCompSmoothAmp = bassCompSmoothAmp + (target - bassCompSmoothAmp) * alpha
                val level = (bassCompSmoothAmp * 255).toInt().coerceIn(0, 255)
                FloatArray(baseSamples.size) { level.toFloat() }
            }
            VibrationMode.SMART -> baseSamples
        }
        val calibratedAmplitudes = hapticTimeline.render(
            nativeSamples = modeSamples,
            sampleCount = maxOf(nativeSampleCount, maxSamplesPerPull),
            windowStartMs = frameStartTime,
            structure = currentMusicStructure,
            outputGain = outputGainForPackage(targetPackage)
        )
        val finalMax = calibratedAmplitudes.maxOrNull() ?: 0

        // v4.1: floor only for SMART; KICK/BASS_COMP avoid micro-floor
        val perceptualFloor = when (vibrationMode) {
            VibrationMode.SMART -> 30
            VibrationMode.KICK, VibrationMode.BASS_COMP -> 8
        }
        val rawAdjusted = if (finalMax in 1 until perceptualFloor) {
            calibratedAmplitudes.map { amp ->
                if (amp > 0) (amp * (perceptualFloor.toFloat() / finalMax.coerceAtLeast(1))).toInt().coerceIn(0, 255) else 0
            }.toIntArray()
        } else {
            calibratedAmplitudes
        }
        // v4.1: user intensity threshold — below it, no vibration
        val adjustedAmplitudes = if (hapticThreshold > 0f) {
            val minAmp = (hapticThreshold * 255).toInt().coerceIn(0, 255)
            rawAdjusted.map { amp -> if (amp < minAmp) 0 else amp }.toIntArray()
        } else {
            rawAdjusted
        }
            val adjustedMax = adjustedAmplitudes.maxOrNull() ?: 0

            if (adjustedMax > 0) {
                // v3.13.2: Discrete impact rendering.
                val cDurations = mutableListOf<Long>()
                val cAmplitudes = mutableListOf<Int>()
                var currentDur = 0L
                var currentAmp = -1

                for (amp in adjustedAmplitudes) {
                    if (currentAmp == -1) {
                        currentAmp = amp
                        currentDur = sampleDurationMs
                    } else if (Math.abs(amp - currentAmp) < 8) {
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
                    cDurations.add(currentDur + 20L)
                    cAmplitudes.add(currentAmp)
                }
                vibrateProxy.performWaveform(cDurations.toLongArray(), cAmplitudes.toIntArray())

                if (frameCounter % 30L == 0L) {
                    val renderMsg = "▶ RENDERED: max=$adjustedMax dur=${cDurations.sum()}ms bins=${cDurations.size}"
                    Log.i(TAG, renderMsg)
                    LogBroadcaster.sendLog(context, renderMsg)
                }
            } else {
                if (frameCounter % 30L == 0L) {
                    val idleMsg = "▶ IDLE (no onset) — no vibration"
                    Log.i(TAG, idleMsg)
                    LogBroadcaster.sendLog(context, idleMsg)
                }
            }

            LinkHealthMonitor.heartbeatVibrateCall()

            if (frameCounter % 30L == 0L) {
                val modeStr = if (isVisualizerSource) "VIZ" else "PCM"
                val fusionMsg = "▶ IMPACT v${BuildConfig.VERSION_NAME} | src=$modeStr | semFrames=$semanticFrameCount | multiTrack=${hapticTimeline.hasMultiTrackActive()}"
                Log.i(TAG, fusionMsg)
                LogBroadcaster.sendLog(context, fusionMsg)
            }
        } else if (timeSinceAudio >= silenceTimeoutMs) {
            if (frameCounter % 60L == 0L) {
                vibrateProxy.cancel()
            }
            directDriveSmoothAmp = 0f
        }

                LinkHealthMonitor.heartbeatTelemetry()

                if (frameCounter % 60L == 0L) {
                    refreshFromProvider()
                }

                if (hasAudioActivity && usableSampleCount > 0 && frameCounter % 12L == 0L) {
                    val latency = SystemClock.elapsedRealtime() - frameStartTime
                    telemetryData.frameLatencyMs = latency
                    telemetryData.dispatchedSubBassImpacts++
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
                Log.i(TAG, "Continuous haptic loop cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Continuous haptic loop error: ${e.message}")
            }

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


    /**
     * 跨进程从模块 App 重新拉取最新设置快照 → 更新本地 SharedPreferences 缓存 → 立即同步引擎参数。
     * 广播接收器与心跳轮询均走此路径，避免全量重建引擎。
     */
    fun refreshFromProvider() {
        try {
            val snapshot = context.contentResolver.call(
                Uri.parse("content://com.mouya.musichaptics.provider"),
                "get_prefs", null,
                Bundle().apply { putString("target_package", targetPackage) }
            )
            if (snapshot != null) {
                val editor = prefs.edit()
                var changed = false
                for (key in snapshot.keySet()) {
                    when (val value = snapshot.get(key)) {
                        is Boolean -> { editor.putBoolean(key, value); changed = true }
                        is Float -> { editor.putFloat(key, value); changed = true }
                        is Int -> { editor.putInt(key, value); changed = true }
                        is Long -> { editor.putLong(key, value); changed = true }
                        is String -> { editor.putString(key, value); changed = true }
                    }
                }
                if (changed) {
                    editor.apply()
                    Log.i(TAG, "Provider refresh: ${snapshot.keySet().size} pref(s) loaded for $targetPackage")
                    synchronizeParameters()
                }
            } else {
                Log.w(TAG, "Provider refresh: no prefs returned, keeping local snapshot")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Provider refresh failed: ${e.message}")
        }
    }

    fun synchronizeParameters() {
        val masterState = try { prefs.getBoolean("master_switch", true) } catch (e: Exception) { true }
        isEngineEnabled.set(masterState)
        hapticThreshold = try { prefs.getFloat("haptic_threshold", 0f) } catch (e: Exception) { 0f }.coerceIn(0f, 1f)
        Log.i(TAG, "sync: threshold=$hapticThreshold mode=${prefs.getString("vibration_mode", "smart")} amp=${prefs.getFloat("haptic_amplitude", 2.0f)}")
        vibrationMode = when (try { prefs.getString("vibration_mode", "smart") } catch (e: Exception) { "kick" }) {
            "bass_comp" -> VibrationMode.BASS_COMP
            "smart" -> VibrationMode.SMART
            else -> VibrationMode.KICK
        }

        val baseAmplitude = try { prefs.getFloat("haptic_amplitude", 2.0f) } catch (e: Exception) { 2.0f }
        val boostLevel = try {
            if (prefs.contains("haptic_boost_level")) prefs.getFloat("haptic_boost_level", 1.0f)
            else prefs.getFloat("haptic_bass_boost", 1.0f)
        } catch (e: Exception) { 1.0f }
        val presetId = try { prefs.getInt("haptic_preset_id", HapticPreset.BALANCED.id) } catch (e: Exception) { HapticPreset.BALANCED.id }
        val crossoverBypass = try { prefs.getBoolean("crossover_bypass", true) } catch (e: Exception) { true }
        val powerAmplify = try { prefs.getBoolean("power_amplify", false) } catch (e: Exception) { false }
        val uiPreset = try { prefs.getInt("selected_preset", 2) } catch (e: Exception) { 2 }
        val presetGain = floatArrayOf(0.70f, 0.90f, 1.00f, 1.20f).getOrElse(uiPreset) { 1.00f }
        val outputAmp = (baseAmplitude * presetGain * if (powerAmplify) 1.15f else 1.0f).coerceIn(0.5f, 4.0f)

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

        // ── 静音时段（定时开关）──
        quietHoursEnabled = try { prefs.getBoolean("quiet_hours_enabled", false) } catch (e: Exception) { false }
        quietHoursStart = try { prefs.getString("quiet_hours_start", "23:00") } catch (e: Exception) { "23:00" } ?: "23:00"
        quietHoursEnd = try { prefs.getString("quiet_hours_end", "07:00") } catch (e: Exception) { "07:00" } ?: "07:00"
        if (quietHoursEnabled) {
            val now = java.util.Calendar.getInstance()
            val nowMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
            val startParts = quietHoursStart.split(":").map { it.toIntOrNull() ?: 0 }
            val endParts = quietHoursEnd.split(":").map { it.toIntOrNull() ?: 0 }
            val startMinutes = startParts.getOrElse(0) { 23 } * 60 + startParts.getOrElse(1) { 0 }
            val endMinutes = endParts.getOrElse(0) { 7 } * 60 + endParts.getOrElse(1) { 0 }
            quietMode = if (startMinutes <= endMinutes) {
                nowMinutes in startMinutes..endMinutes
            } else {
                // 跨天（如 23:00-07:00）：当前时间 >= 开始 或 <= 结束
                nowMinutes >= startMinutes || nowMinutes <= endMinutes
            }
        } else {
            quietMode = false
        }
    }

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

    fun onPlaybackPaused() {
        Log.i(TAG, "[PLAYBACK PAUSED] Forcing immediate haptic decay")
        LogBroadcaster.sendLog(context, "[PLAYBACK PAUSED] Forcing immediate haptic decay")
        
        hapticPaused = true
        vibrateProxy.setPaused()
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
        pcmFallbackAmplitude = (30f + sqrt(rms) * 450f).toInt().coerceIn(0, 220)
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

    // v3.13.1: Visualizer-specific onset detection
    private fun detectVisualizerOnset(block: FloatArray, timestampMs: Long) {
        var energy = 0f
        for (sample in block) {
            energy += sample * sample
        }
        energy /= block.size

        vizEnergyBaseline += 0.015f * (energy - vizEnergyBaseline)

        val energyDelta = energy - vizPrevEnergy
        vizPrevEnergy = energy

        // v3.13.1: Onset criteria for Visualizer data:
        val absoluteFloor = 0.0015f  // v4.0: was 0.0025 — even more sensitive
        val ratioThreshold = 1.3f  // v4.0: was 1.6 — catch subtle onsets
        val deltaThreshold = 0.0005f  // v4.0: was 0.001 — catch weakest transients
        val isRhythmGame = targetPackage == "org.flos.phira"
        // v4.1: KICK strict, BASS_COMP skip
        val (vizRatio, vizDelta, vizFloor) = when (vibrationMode) {
            VibrationMode.KICK -> Triple(2.0f, 0.002f, 0.004f)
            VibrationMode.BASS_COMP -> return
            VibrationMode.SMART -> Triple(1.3f, 0.0005f, 0.0015f)
        }
        val cooldownMs = if (vibrationMode == VibrationMode.KICK) 60L else 0L

        val isOnset = energy >= vizFloor &&
            energy >= vizEnergyBaseline * vizRatio &&
            energyDelta >= vizDelta &&
            timestampMs - vizLastOnsetMs >= cooldownMs

        if (!isOnset) return

        vizLastOnsetMs = timestampMs
        vizOnsetCount++

        val intensity = (200f + energy * 8000f).toInt().coerceIn(200, 255)

        hapticTimeline.offerPrimitive(
            HapticPrimitive.Impact(
                intensity = intensity,
                durationMs = if (isRhythmGame) 35 else 45,
                velocityFactor = 1f,
                sharpness = if (isRhythmGame) 0.40f else 0.30f,
                semantic = "VIZ_ONSET"
            ),
            timestampMs
        )

        if (vizOnsetCount % 6L == 1L) {
            val message = "Timeline VIZ onset queued #${vizOnsetCount} energy=${"%.5f".format(energy)} base=${"%.5f".format(vizEnergyBaseline)} delta=${"%.5f".format(energyDelta)} amp=$intensity app=$targetPackage"
            Log.i(TAG, message)
            LogBroadcaster.sendLog(context, message)
        }
    }

    private fun detectPcmLowBandAttack(block: FloatArray, timestampMs: Long) {
        var lowEnergy = 0f
        for (sample in block) {
            pcmLowPassState += 0.024f * (sample - pcmLowPassState)
            lowEnergy += abs(pcmLowPassState)
        }
        val blockEnvelope = lowEnergy / block.size
        pcmLowBandEnvelope += 0.55f * (blockEnvelope - pcmLowBandEnvelope)
        val previousBaseline = pcmLowBandBaseline
        pcmLowBandBaseline += 0.035f * (pcmLowBandEnvelope - pcmLowBandBaseline)

        val isVideoApp = targetPackage == "tv.danmaku.bili"
        // v4.1: KICK strict thresholds, BASS_COMP skip onset
        val (ratioThreshold, absoluteThreshold, riseDelta) = when (vibrationMode) {
            VibrationMode.KICK -> if (isVideoApp) Triple(2.5f, 0.014f, 0.005f) else Triple(2.2f, 0.010f, 0.003f)
            VibrationMode.BASS_COMP -> return
            VibrationMode.SMART -> if (isVideoApp) Triple(1.8f, 0.008f, 0.002f) else Triple(1.3f, 0.004f, 0.001f)
        }
        val cooldownMs = if (vibrationMode == VibrationMode.KICK) 60L else 0L
        val rise = pcmLowBandEnvelope - previousBaseline
        val lowAttack = pcmLowBandEnvelope >= absoluteThreshold &&
            pcmLowBandEnvelope >= previousBaseline * ratioThreshold &&
            rise >= riseDelta &&
            timestampMs - lastPcmLowBandOnsetMs >= cooldownMs
        if (!lowAttack) return

        lastPcmLowBandOnsetMs = timestampMs
        pcmLowBandOnsetCount++
        val intensity = if (isVideoApp) {
            (128f + pcmLowBandEnvelope * 1800f).toInt().coerceIn(100, 200)  // v4.0: expanded range
        } else {
            (190f + pcmLowBandEnvelope * 2100f).toInt().coerceIn(150, 255)  // v4.0: was 180-250 → 150-255 (full range)
        }
        hapticTimeline.offerPrimitive(
            HapticPrimitive.Impact(
                intensity = intensity,
                durationMs = if (isVideoApp) 30 else 38,  // NETEASE: 更短 duration (was 38/52)
                velocityFactor = 1f,
                sharpness = if (isVideoApp) 0.35f else 0.45f,  // NETEASE: 更高 sharpness
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
        
        if (isVisualizerSource) {
            detectVisualizerOnset(block, currentTimeMs)
        } else {
            detectPcmLowBandAttack(block, currentTimeMs)
        }

        if (currentTimeMs - lastParameterUpdateTime > 300) {
            synchronizeParameters()
            lastParameterUpdateTime = currentTimeMs
        }

        val currentFrameId = frameIndexCounter.incrementAndGet()

        floatPcmView.position(0)
        floatPcmView.put(block, 0, FRAME_BLOCK_SIZE)

        nativeBridge.processAudioDirect(directPcmBuffer, FRAME_BLOCK_SIZE, nativeTelemetryResult)

        val finalSubIntensity = nativeTelemetryResult[0]
        lastSubIntensity = finalSubIntensity
        val finalMidIntensity = nativeTelemetryResult[1]
        val finalPresenceIntensity = nativeTelemetryResult[2]
        val detectedFundamentalFreq = nativeTelemetryResult[3]
        val estimatedCoilTemperature = nativeTelemetryResult[4]
        val thermalSafetyGain = nativeTelemetryResult[5]
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

        if (isEngineEnabled.get()) {
            if (thermalSafetyGain <= 0.01f) {
                nativeBridge.clearHapticBuffer()
                vibrateProxy.cancel()
                directDriveSmoothAmp = 0f
            }

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

        val onsetSubThreshold = if (isVisualizerSource) 0.01f else 0.025f  // was 0.015/0.035
        // v4.1: mode-gated low-band onset
        val lowBandOnset = when (vibrationMode) {
            VibrationMode.BASS_COMP -> false  // envelope-driven, no onset ticks
            VibrationMode.KICK -> onsetFlag && finalSubIntensity >= 0.18f &&
                currentTimeMs - lastLowBandOnsetMs >= 60L
            VibrationMode.SMART -> onsetFlag && finalSubIntensity >= onsetSubThreshold &&
                currentTimeMs - lastLowBandOnsetMs >= 80L  // NETEASE: 更短冷却 (was 95ms)
        }
        if (lowBandOnset) {
            lastLowBandOnsetMs = currentTimeMs
            lowBandOnsetCount++
            val intensity = (185f + finalSubIntensity.coerceIn(0f, 1f) * 70f).toInt()
                .coerceIn(185, 255)
            hapticTimeline.offerPrimitive(
                HapticPrimitive.Impact(
                    intensity = intensity,
                    durationMs = 40,  // NETEASE: 更短 (was 48)
                    velocityFactor = 1f,
                    sharpness = 0.35f,  // NETEASE: 更高 (was 0.25)
                    semantic = "LOW_BAND_ONSET"
                ),
                currentTimeMs
            )
            if (lowBandOnsetCount % 8L == 1L) {
                Log.i(TAG, "Timeline low-band onset queued #$lowBandOnsetCount sub=${"%.3f".format(finalSubIntensity)} amp=$intensity")
            }
        }
    }

    fun release() {

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
        vibrateProxy.setPaused()
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