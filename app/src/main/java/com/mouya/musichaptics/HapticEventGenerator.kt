package com.mouya.musichaptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.*

import com.mouya.musichaptics.LinkHealthMonitor

class HapticEventGenerator(
    private val context: Context,

    val profile: DeviceProfile = detectDeviceProfile()
) {

    companion object {
        private const val TAG = "HapticEventGen"

        private const val MIN_AMPLITUDE = 1
        private const val MAX_AMPLITUDE = 255

        private const val ENERGY_CAP = 1.5f

        private const val SURGE_TRIGGER_RATIO = 2.8f

        private const val SURGE_COOLDOWN_MS = 800L
    }

    enum class HapticProfile(val displayName: String, val description: String) {
        CINEMATIC("Cinematic", "低频强·空间感，适合电影原声/交响乐"),
        EDM("EDM", "Drop增强·节奏感强，适合电子舞曲"),
        PIANO("Piano", "细腻短震·动态轻柔，适合钢琴/纯音乐"),
        ROCK("Rock", "鼓点突出·冲击力强，适合摇滚/金属"),
        DEFAULT("Default", "均衡模式·通用所有曲风")
    }

    @Volatile var activeProfile: HapticProfile = HapticProfile.DEFAULT

    enum class HapticPreset(
        val displayName: String,
        val description: String,
        val amplitudeMultiplier: Float,
        val bassMultiplier: Float
    ) {
        BALANCED("均衡", "全频段自然还原", 1.0f, 1.0f),
        BASS_ENHANCED("低音增强", "重低音震感加强", 1.2f, 1.5f),
        TEXTURE_FOCUS("纹理聚焦", "高频微震纹理细腻", 1.0f, 0.8f),
        IMPACT_MAX("冲击极致", "瞬态冲击最大化", 1.5f, 1.3f),
        CUSTOM("自定义", "手动调节参数", 1.0f, 1.0f)
    }

    @Volatile var activePreset: HapticPreset = HapticPreset.BALANCED

    enum class VibrationMode {
        DRONE,
        IMPACT,
        TEXTURE,
        CLICK,
        HEARTBEAT,
        CUSTOM
    }

    @Volatile var currentMode: VibrationMode = VibrationMode.DRONE

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibrator service resolution failed: ${e.message}")
            null
        }
    }

    val hasVibrator: Boolean = try { vibrator?.hasVibrator() ?: false } catch (e: Exception) { false }

    private val hasAmplitudeControl: Boolean =
        if (vibrator != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { vibrator!!.hasAmplitudeControl() } catch (_: Exception) { false }
        } else false

    private val isApi29Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    private val isApi26Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    private var lastEventTimeMs = 0L
    private var accumulatedEnergy = 0f
    private var eventCounter = 0L

    private var silentFrameCount = 0

    private var frameEntryLatency = 0L

    private var lastVibrateTimeMs = 0L

    private val minVibrateIntervalMs = 30L

    private var dynamicMoodEnergy = 0.5f

    private var isKeyStrikeMode = false

    private var lastKeyStrikeSemantic = KeyStrikeSemantic.NONE

    private var lastSemanticType = SemanticType.BALANCED

    private var lastSurgeTimeMs = 0L

    private var frameIndex = 0L

    val currentFrameLatencyMs: Long get() = frameEntryLatency

    @Volatile var isEnabled = true

    @Volatile var injectedSilenceThreshold: Float? = null
    @Volatile var injectedEnergyThreshold: Float? = null
    @Volatile var injectedMinGuaranteedAmplitude: Int? = null

    val silenceThresholdActive: Float
        get() = (injectedSilenceThreshold ?: profile.silenceThreshold).coerceIn(0f, 1f)

    val energyThresholdActive: Float
        get() = (injectedEnergyThreshold ?: profile.energyThreshold).coerceIn(0.001f, 5f)

    val minGuaranteedAmplitudeActive: Int
        get() = (injectedMinGuaranteedAmplitude ?: profile.minGuaranteedAmplitude).coerceIn(1, 254)

    fun testVibration() {
        val vib = vibrator ?: run {
            Log.w(TAG, "vibrator is null, attempting to get system vibrator for test")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
            } catch (e: Exception) { null }
        }
        Log.e(TAG, "═══ VIBRATOR DIAGNOSTIC ═══")
        Log.e(TAG, "vibrator=$vib class=${vib?.javaClass?.name}")
        Log.e(TAG, "hasVibrator=$hasVibrator hasAmplitudeControl=$hasAmplitudeControl")
        Log.e(TAG, "profile=${profile.name} (${profile.description})")
        Log.e(TAG, "isEnabled=$isEnabled boostLevel=$boostLevel")
        try {
            if (vib != null) {
                val effect = VibrationEffect.createOneShot(300, 255)
                vib.vibrate(effect)
                Log.e(TAG, "✅ TEST VIBRATION SENT (300ms, amp=255)")
            } else {
                Log.e(TAG, "❌ TEST FAILED: vibrator is null!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ TEST FAILED: ${e.message}")
        }
        Log.e(TAG, "═══ END DIAGNOSTIC ═══")
    }

    @Volatile var boostLevel: Float = 1.0f

    @Volatile var userAmplitudeScale: Float = 2.0f

    @Volatile var presetAmplitudeMultiplier: Float = 1.0f

    @Volatile var presetBassMultiplier: Float = 1.0f

    fun setHapticPreset(presetName: String) {
        activePreset = try {
            HapticPreset.valueOf(presetName.uppercase())
        } catch (e: Exception) {
            HapticPreset.BALANCED
        }

        presetAmplitudeMultiplier = activePreset.amplitudeMultiplier
        presetBassMultiplier = activePreset.bassMultiplier
        Log.d(TAG, "HapticPreset set to: ${activePreset.displayName} | amp×${presetAmplitudeMultiplier} bass×${presetBassMultiplier}")
    }

    fun synchronizePreset(prefs: android.content.SharedPreferences) {
        val presetStr = try { prefs.getString("haptic_preset", "BALANCED") ?: "BALANCED" } catch (e: Exception) { "BALANCED" }
        setHapticPreset(presetStr)
    }

    fun setHapticProfile(profileName: String) {
        activeProfile = try {
            HapticProfile.valueOf(profileName.uppercase())
        } catch (e: Exception) {
            HapticProfile.DEFAULT
        }
        Log.d(TAG, "HapticProfile set to: ${activeProfile.displayName}")
    }

    fun synchronizeProfile(prefs: android.content.SharedPreferences) {
        val profileStr = try { prefs.getString("haptic_profile", "DEFAULT") ?: "DEFAULT" } catch (e: Exception) { "DEFAULT" }
        setHapticProfile(profileStr)
    }

    var logListener: ((String) -> Unit)? = null

    fun getVibratorInstance(): Vibrator? = vibrator

    private val cachedHeavyClick: VibrationEffect? =
        if (isApi29Plus) VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK) else null
    private val cachedClick: VibrationEffect? =
        if (isApi29Plus) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK) else null
    private val cachedTick: VibrationEffect? =
        if (isApi29Plus) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) else null

    fun generateAndPlay(
        sub: Float,
        mid: Float,
        presence: Float,
        pitch: Float,
        currentTimeMs: Long
    ) {

        if (frameIndex % 32L == 0L) {
            Log.e(TAG, "🎵 INPUT sub=%.6f mid=%.6f pres=%.6f pitch=%.1fHz enabled=$isEnabled vib=$hasVibrator"
                .format(sub, mid, presence, pitch))
        }
        frameIndex++

        if (!isEnabled) { Log.w(TAG, "BLOCKED: isEnabled=false"); return }

        val now = android.os.SystemClock.elapsedRealtime()
        frameEntryLatency = (now - currentTimeMs).coerceAtLeast(0L)

        val blendedIntensity = (sub * profile.subWeight * profile.bassBoost * presetBassMultiplier) +
                (mid * profile.midWeight) +
                (presence * profile.presenceWeight)

        val finalBlendedIntensity = blendedIntensity * presetAmplitudeMultiplier

        if (frameIndex % 32L == 0L) {
            Log.e(TAG, "📊 BLEND=%.6f final=%.6f silenceTh=%.6f accumEnergy=%.4f energyTh=%.3f minInterval=%dms"
                .format(blendedIntensity, finalBlendedIntensity, profile.silenceThreshold,
                    accumulatedEnergy, profile.energyThreshold, profile.minIntervalMs))
        }

        if (finalBlendedIntensity < silenceThresholdActive) {

            accumulatedEnergy = (accumulatedEnergy * 0.9f).coerceAtMost(ENERGY_CAP)

            silentFrameCount++
            if (silentFrameCount >= profile.fillerFrameThreshold && hasAmplitudeControl) {
                val vib = vibrator ?: run {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                            vm?.defaultVibrator
                        } else {
                            @Suppress("DEPRECATION")
                            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                        }
                    } catch (e: Exception) { null }
                }
                try {
                    vib?.vibrate(
                        VibrationEffect.createOneShot(
                            profile.fillerDurationMs,
                            profile.fillerAmplitude
                        )
                    )
                } catch (_: Exception) {}
                silentFrameCount = 0
            }
            return
        }
        silentFrameCount = 0

        accumulatedEnergy = (accumulatedEnergy + finalBlendedIntensity).coerceAtMost(ENERGY_CAP)
        if (accumulatedEnergy < energyThresholdActive) return
        accumulatedEnergy = 0f

        val pitchIntervalMs = if (pitch > 0f && pitch < 500f) {
            (1000f / pitch).coerceIn(
                profile.minIntervalMs.toFloat(),
                profile.maxIntervalMs.toFloat()
            ).toLong()
        } else profile.maxIntervalMs

        if (currentTimeMs - lastEventTimeMs < pitchIntervalMs) return
        lastEventTimeMs = currentTimeMs

        val subPower = sub * profile.subWeight * profile.bassBoost * presetBassMultiplier
        val midPower = mid * profile.midWeight
        val presPower = presence * profile.presenceWeight
        val isSubDominant = subPower >= midPower && subPower >= presPower
        val isPresenceDominant = presPower > subPower && presPower > midPower

        val beatConfidence = when {
            activeProfile == HapticProfile.EDM -> if (isSubDominant) 0.90f else 0.70f
            activeProfile == HapticProfile.PIANO -> 0.50f
            activeProfile == HapticProfile.ROCK -> if (isSubDominant) 0.95f else 0.80f
            activeProfile == HapticProfile.CINEMATIC -> 0.75f
            else -> if (isSubDominant) 0.85f else if (isPresenceDominant) 0.55f else 0.70f
        }
        val nonlinearBoost = beatConfidence.pow(1.5f)

        val moodEnergy = (accumulatedEnergy * 0.8f + finalBlendedIntensity * 0.2f).coerceIn(0f, 1.5f)
        val moodSmoothAlpha = when (activeProfile) {
            HapticProfile.CINEMATIC -> 0.70f
            HapticProfile.EDM -> 0.90f
            HapticProfile.PIANO -> 0.60f
            HapticProfile.ROCK -> 0.85f
            else -> 0.80f
        }
        dynamicMoodEnergy = dynamicMoodEnergy * (1f - moodSmoothAlpha) + moodEnergy * moodSmoothAlpha
        dynamicMoodEnergy = dynamicMoodEnergy.coerceIn(0.05f, 1.0f)

        val emotionalEnvelope = dynamicMoodEnergy * 0.85f + (1.0f - dynamicMoodEnergy) * 0.30f

        val profileBoost = when (activeProfile) {
            HapticProfile.CINEMATIC -> 0.9f
            HapticProfile.EDM -> 1.3f
            HapticProfile.PIANO -> 0.7f
            HapticProfile.ROCK -> 1.2f
            else -> 1.0f
        }
        val boostedIntensity = finalBlendedIntensity * nonlinearBoost * emotionalEnvelope * profileBoost * boostLevel

        val targetAmplitude = if (hasAmplitudeControl) {
            (boostedIntensity * MAX_AMPLITUDE).toInt()
                .coerceAtLeast(minGuaranteedAmplitudeActive)
                .coerceAtMost(MAX_AMPLITUDE)
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }

        try {

            val effect = when {

                isEnergySurge(finalBlendedIntensity, accumulatedEnergy, currentTimeMs) && isApi26Plus -> {
                    buildDropWaveform(targetAmplitude, finalBlendedIntensity)
                }

                isKeyStrikeMode && isApi26Plus -> buildKeyStrikeWaveform(
                    targetAmplitude, finalBlendedIntensity, lastKeyStrikeSemantic, pitch, lastSemanticType
                )

                isSubDominant && isApi26Plus -> {
                    when (activeProfile) {
                        HapticProfile.PIANO -> buildClickWaveform(targetAmplitude, blendedIntensity, true)
                        HapticProfile.ROCK -> buildSubImpactWaveform(targetAmplitude, blendedIntensity)
                        HapticProfile.EDM -> {

                            buildSubImpactWaveform(targetAmplitude, blendedIntensity)
                        }
                        HapticProfile.CINEMATIC -> buildSubImpactWaveform(targetAmplitude, blendedIntensity)
                        else -> buildSubImpactWaveform(targetAmplitude, blendedIntensity)
                    }
                }

                !isPresenceDominant && isApi29Plus && cachedHeavyClick != null -> cachedHeavyClick

                isPresenceDominant && isApi29Plus && cachedTick != null -> cachedTick

                isApi26Plus -> {
                    val dur = when {
                        isSubDominant -> (boostedIntensity * 45f).toLong().coerceIn(15, 50)
                        isPresenceDominant -> 10L
                        else -> 20L
                    }
                    VibrationEffect.createOneShot(dur, targetAmplitude)
                }

                else -> {
                    @Suppress("DEPRECATION")
                    val vib = vibrator ?: run {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                                vm?.defaultVibrator
                            } else {
                                @Suppress("DEPRECATION")
                                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                            }
                        } catch (e: Exception) { null }
                    }
                    vib?.vibrate(if (isSubDominant) 40L else 15L)
                    null
                }
            }
            if (effect != null) {
                Log.e(TAG, "⚡ VIBRATE! amp=$targetAmplitude mode=%s effectClass=%s".format(
                    when {
                        isSubDominant -> "SUB"
                        isPresenceDominant -> "PRES"
                        else -> "MID"
                    }, effect.javaClass.simpleName
                ))

                Log.d("HapticLink", "【节点 3】准备发起系统震动 | Vibrator Calling... | amp=$targetAmplitude | hasVibrator=$hasVibrator")

                val now = System.currentTimeMillis()
                if (now - lastVibrateTimeMs < minVibrateIntervalMs) {
                    Log.d("HapticLink", "【抑制】振动间隔过短 (${now - lastVibrateTimeMs}ms < ${minVibrateIntervalMs}ms)，跳过本次触发")
                    return
                }
                lastVibrateTimeMs = now

                val vib = vibrator ?: run {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                            vm?.defaultVibrator
                        } else {
                            @Suppress("DEPRECATION")
                            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                        }
                    } catch (e: Exception) { null }
                }
                try { vib?.vibrate(effect) } catch (e: Exception) { Log.w(TAG, "vibrate failed: ${e.message}") }

                LinkHealthMonitor.heartbeatVibrateCall()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration effect failed: ${e.message}")
        }

        eventCounter++
        if (eventCounter % 20L == 0L) {
            val modeStr = when {
                isSubDominant -> "SUB-Wave"
                isPresenceDominant -> "PRES-Tick"
                else -> "MID-Click"
            }
            logListener?.invoke(
                "#%d | %s amp=%d blend=%.2f mood=%.2f prof=%s F0=%.0fHz Δ=%dms"
                    .format(eventCounter, modeStr, targetAmplitude,
                        blendedIntensity, dynamicMoodEnergy, activeProfile.name, pitch, frameEntryLatency)
            )
        }
    }

    private fun buildSubImpactWaveform(amplitude: Int, intensity: Float): VibrationEffect {
        val amp1 = amplitude
        val amp2 = (amplitude * profile.subAmpDecay2).toInt().coerceAtLeast(minGuaranteedAmplitudeActive / 2)
        val amp3 = (amplitude * profile.subAmpDecay3).toInt().coerceAtLeast(MIN_AMPLITUDE)

        val dur1 = (profile.subDur1Min + intensity * (profile.subDur1Max - profile.subDur1Min)).toLong()
            .coerceIn(profile.subDur1Min, profile.subDur1Max)
        val gap1 = (profile.subGap1Min + intensity * (profile.subGap1Max - profile.subGap1Min)).toLong()
            .coerceIn(profile.subGap1Min, profile.subGap1Max)
        val dur2 = (profile.subDur2Min + intensity * (profile.subDur2Max - profile.subDur2Min)).toLong()
            .coerceIn(profile.subDur2Min, profile.subDur2Max)
        val gap2 = (profile.subGap2Min + intensity * (profile.subGap2Max - profile.subGap2Min)).toLong()
            .coerceIn(profile.subGap2Min, profile.subGap2Max)
        val dur3 = (profile.subDur3Min + intensity * (profile.subDur3Max - profile.subDur3Min)).toLong()
            .coerceIn(profile.subDur3Min, profile.subDur3Max)

        val timings = longArrayOf(dur1, gap1, dur2, gap2, dur3, 0L)
        val amps = intArrayOf(amp1, amp2, amp3)
        return VibrationEffect.createWaveform(timings, amps, -1)
    }

    private fun buildDroneWaveform(amplitude: Int, intensity: Float, pitch: Float, isSubDominant: Boolean): VibrationEffect {
        val totalDurationMs = (120f + intensity * 180f).toLong().coerceIn(120, 400)
        val modPeriodMs = if (pitch > 0f) {
            (1000f / pitch * 2.5f).toLong().coerceIn(20, 100)
        } else 40L
        val segmentMs = (modPeriodMs / 2).coerceAtLeast(8)
        val numSegments = ((totalDurationMs / segmentMs).toInt()).coerceIn(6, 30)

        val timings = LongArray(numSegments * 2)
        val amps = IntArray(numSegments)

        val ampHigh = amplitude
        val ampLow = (amplitude * 0.65f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive / 2)

        for (i in 0 until numSegments) {
            timings[i * 2] = segmentMs
            timings[i * 2 + 1] = 0L
            amps[i] = if (i % 2 == 0) ampHigh else ampLow
        }
        return VibrationEffect.createWaveform(timings, amps, -1)
    }

    private fun buildImpactWaveform(amplitude: Int, intensity: Float, pitch: Float, isSubDominant: Boolean): VibrationEffect {
        val peakAmp = amplitude
        val tailAmp1 = (amplitude * 0.7f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive)
        val tailAmp2 = (amplitude * 0.45f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive / 2)
        val tailAmp3 = (amplitude * 0.25f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive / 3)

        val strikeDur = (16f + intensity * 28f).toLong().coerceIn(12, 45)
        val gap1 = (5f + intensity * 8f).toLong().coerceIn(4, 15)
        val tailDur1 = (20f + intensity * 28f).toLong().coerceIn(15, 50)
        val gap2 = (3f + intensity * 5f).toLong().coerceIn(3, 10)
        val tailDur2 = (14f + intensity * 20f).toLong().coerceIn(12, 38)
        val tailDur3 = (8f + intensity * 12f).toLong().coerceIn(6, 20)

        val timings = longArrayOf(
            strikeDur, gap1, tailDur1, gap2, tailDur2, 0L, tailDur3, 0L
        )
        val amps = intArrayOf(peakAmp, tailAmp1, tailAmp2, tailAmp3)
        return VibrationEffect.createWaveform(timings, amps, -1)
    }

    private fun buildTextureWaveform(amplitude: Int, intensity: Float, isPresenceDominant: Boolean): VibrationEffect {
        val baseAmp = if (isPresenceDominant) amplitude else (amplitude * 0.8f).toInt()
        val segmentMs = 4L
        val numSegments = (50f + intensity * 70f).toInt().coerceIn(12, 40)

        val timings = LongArray(numSegments * 2)
        val amps = IntArray(numSegments)

        for (i in 0 until numSegments) {
            timings[i * 2] = segmentMs
            timings[i * 2 + 1] = 0L
            val variance = (kotlin.math.sin(i * 0.7f) * 0.12f + 0.88f)
            amps[i] = (baseAmp * variance).toInt().coerceAtLeast(minGuaranteedAmplitudeActive / 2)
        }
        return VibrationEffect.createWaveform(timings, amps, -1)
    }

    private fun buildClickWaveform(amplitude: Int, intensity: Float, isSubDominant: Boolean): VibrationEffect {
        val dur = if (isSubDominant) {
            (35f + intensity * 45f).toLong().coerceIn(30, 80)
        } else {
            (12f + intensity * 18f).toLong().coerceIn(10, 30)
        }
        return if (isApi29Plus && !isSubDominant && cachedClick != null) cachedClick
        else VibrationEffect.createOneShot(dur, amplitude)
    }

    private fun buildHeartbeatWaveform(amplitude: Int, intensity: Float, pitch: Float): VibrationEffect {
        val strike1 = (14f + intensity * 28f).toLong().coerceIn(12, 45)
        val gap = (100f + intensity * 50f).toLong().coerceIn(80, 180)
        val strike2 = (12f + intensity * 20f).toLong().coerceIn(10, 32)
        val tail = (40f + intensity * 50f).toLong().coerceIn(30, 100)

        val timings = longArrayOf(strike1, gap, strike2, 0L, tail, 0L)
        val amp1 = amplitude
        val amp2 = (amplitude * 0.8f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive)
        val amp3 = (amplitude * 0.4f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive / 2)
        val amps = intArrayOf(amp1, amp2, amp3)
        return VibrationEffect.createWaveform(timings, amps, -1)
    }

    private fun buildCustomWaveform(
        amplitude: Int,
        intensity: Float,
        pitch: Float,
        isSubDominant: Boolean,
        isPresenceDominant: Boolean
    ): VibrationEffect {
        val dur1 = (profile.subDur1Min + (profile.subDur1Max - profile.subDur1Min) * intensity).toLong()
        val gap1 = (profile.subGap1Min + (profile.subGap1Max - profile.subGap1Min) * intensity).toLong()
        val dur2 = (profile.subDur2Min + (profile.subDur2Max - profile.subDur2Min) * intensity).toLong()
        val gap2 = (profile.subGap2Min + (profile.subGap2Max - profile.subGap2Min) * intensity).toLong()
        val dur3 = (profile.subDur3Min + (profile.subDur3Max - profile.subDur3Min) * intensity).toLong()

        val amp1 = amplitude
        val amp2 = (amplitude * profile.subAmpDecay2).toInt().coerceAtLeast(minGuaranteedAmplitudeActive)
        val amp3 = (amplitude * profile.subAmpDecay3).toInt().coerceAtLeast(minGuaranteedAmplitudeActive / 2)

        val timings = longArrayOf(dur1, gap1, dur2, gap2, dur3, 0L)
        val amps = intArrayOf(amp1, amp2, amp3)
        return VibrationEffect.createWaveform(timings, amps, -1)
    }

    private fun isEnergySurge(currentIntensity: Float, currentAccumulatedEnergy: Float, currentTimeMs: Long): Boolean {

        val timeSinceLastSurge = currentTimeMs - lastSurgeTimeMs
        if (timeSinceLastSurge < SURGE_COOLDOWN_MS) return false

        if (currentIntensity < 0.15f) return false

        val isSurge = currentAccumulatedEnergy > currentIntensity * SURGE_TRIGGER_RATIO

        if (isSurge) {
            lastSurgeTimeMs = currentTimeMs
            Log.d(TAG, "ENERGY_SURGE DETECTED | accum=${"%.3f".format(currentAccumulatedEnergy)} intensity=${"%.3f".format(currentIntensity)}")
        }

        return isSurge
    }

    private fun buildDropWaveform(amplitude: Int, intensity: Float): VibrationEffect {
        val amp1 = amplitude
        val amp2 = (amplitude * 0.75f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive)
        val amp3 = (amplitude * 0.90f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive)
        val amp4 = (amplitude * 0.50f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive / 2)
        val amp5 = (amplitude * 0.25f).toInt().coerceAtLeast(MIN_AMPLITUDE)

        val dur1 = (35f + intensity * 30f).toLong().coerceIn(30, 65)
        val gap1 = (8f + intensity * 10f).toLong().coerceIn(6, 20)
        val dur2 = (60f + intensity * 80f).toLong().coerceIn(50, 150)
        val gap2 = 0L
        val dur3 = (14f + intensity * 12f).toLong().coerceIn(10, 28)
        val gap3 = (4f + intensity * 6f).toLong().coerceIn(2, 12)
        val dur4 = (25f + intensity * 30f).toLong().coerceIn(20, 60)
        val gap4 = 0L
        val dur5 = (15f + intensity * 25f).toLong().coerceIn(10, 45)

        val timings = longArrayOf(dur1, gap1, dur2, gap2, dur3, gap3, dur4, gap4, dur5, 0L)
        val amps = intArrayOf(amp1, amp2, amp3, amp4, amp5)
        return VibrationEffect.createWaveform(timings, amps, -1)
    }

    private fun buildKeyStrikeWaveform(
        amplitude: Int,
        intensity: Float,
        semantic: KeyStrikeSemantic,
        pitch: Float,
        semanticType: SemanticType
    ): VibrationEffect {
        return when (semantic) {

            KeyStrikeSemantic.SUB_STRIKE -> {
                val amp1 = amplitude
                val amp2 = (amplitude * 0.65f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive)
                val amp3 = (amplitude * 0.35f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive / 2)
                val amp4 = (amplitude * 0.15f).toInt().coerceAtLeast(MIN_AMPLITUDE)

                val dur1 = (20f + intensity * 35f).toLong().coerceIn(18, 55)
                val gap1 = (6f + intensity * 10f).toLong().coerceIn(5, 18)
                val dur2 = (25f + intensity * 30f).toLong().coerceIn(20, 60)
                val gap2 = (4f + intensity * 8f).toLong().coerceIn(3, 14)
                val dur3 = (30f + intensity * 40f).toLong().coerceIn(25, 80)
                val gap3 = (3f + intensity * 6f).toLong().coerceIn(2, 12)
                val dur4 = (20f + intensity * 25f).toLong().coerceIn(15, 50)

                val timings = longArrayOf(dur1, gap1, dur2, gap2, dur3, gap3, dur4, 0L)
                val amps = intArrayOf(amp1, amp2, amp3, amp4)
                VibrationEffect.createWaveform(timings, amps, -1)
            }

            KeyStrikeSemantic.KICK_DRUM -> {
                val amp1 = amplitude
                val amp2 = (amplitude * 0.55f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive)
                val amp3 = (amplitude * 0.25f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive / 2)

                val strikeDur = (12f + intensity * 20f).toLong().coerceIn(10, 35)
                val gap1 = (3f + intensity * 6f).toLong().coerceIn(2, 10)
                val tailDur1 = (15f + intensity * 20f).toLong().coerceIn(12, 40)
                val gap2 = (2f + intensity * 4f).toLong().coerceIn(2, 8)
                val tailDur2 = (8f + intensity * 12f).toLong().coerceIn(6, 20)

                val timings = longArrayOf(strikeDur, gap1, tailDur1, gap2, tailDur2, 0L)
                val amps = intArrayOf(amp1, amp2, amp3)
                VibrationEffect.createWaveform(timings, amps, -1)
            }

            KeyStrikeSemantic.SNARE_ACCENT -> {
                val amp1 = amplitude
                val amp2 = (amplitude * 0.85f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive)
                val amp3 = (amplitude * 0.4f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive / 2)
                val amp4 = (amplitude * 0.2f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive / 3)

                val strike1 = (10f + intensity * 18f).toLong().coerceIn(8, 30)
                val gap = (60f + intensity * 40f).toLong().coerceIn(50, 120)
                val strike2 = (8f + intensity * 14f).toLong().coerceIn(6, 22)
                val tail = (25f + intensity * 30f).toLong().coerceIn(20, 60)

                val timings = longArrayOf(strike1, gap, strike2, 0L, tail, 0L, 0L, 0L)
                val amps = intArrayOf(amp1, amp2, amp3, amp4)
                VibrationEffect.createWaveform(timings, amps, -1)
            }

            KeyStrikeSemantic.RHYTHM_PATTERN -> {
                val amp1 = amplitude
                val amp2 = (amplitude * 0.7f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive)
                val amp3 = (amplitude * 0.6f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive)
                val amp4 = (amplitude * 0.3f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive / 2)

                val strike1 = (14f + intensity * 22f).toLong().coerceIn(12, 40)
                val gap = (80f + intensity * 40f).toLong().coerceIn(70, 140)
                val strike2 = (12f + intensity * 18f).toLong().coerceIn(10, 32)
                val tail = (30f + intensity * 35f).toLong().coerceIn(25, 70)

                val timings = longArrayOf(strike1, gap, strike2, 0L, tail, 0L, 0L, 0L)
                val amps = intArrayOf(amp1, amp2, amp3, amp4)
                VibrationEffect.createWaveform(timings, amps, -1)
            }

            KeyStrikeSemantic.BASS_GHOST -> {
                val amp1 = (amplitude * 0.6f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive)
                val amp2 = (amplitude * 0.4f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive / 2)
                val amp3 = (amplitude * 0.25f).toInt().coerceAtLeast(minGuaranteedAmplitudeActive / 3)
                val amp4 = (amplitude * 0.12f).toInt().coerceAtLeast(MIN_AMPLITUDE)

                val dur1 = (30f + intensity * 40f).toLong().coerceIn(25, 80)
                val gap1 = (8f + intensity * 12f).toLong().coerceIn(6, 20)
                val dur2 = (25f + intensity * 30f).toLong().coerceIn(20, 60)
                val gap2 = (5f + intensity * 8f).toLong().coerceIn(4, 15)
                val dur3 = (20f + intensity * 25f).toLong().coerceIn(15, 50)
                val dur4 = (15f + intensity * 20f).toLong().coerceIn(10, 40)

                val timings = longArrayOf(dur1, gap1, dur2, gap2, dur3, 0L, dur4, 0L)
                val amps = intArrayOf(amp1, amp2, amp3, amp4)
                VibrationEffect.createWaveform(timings, amps, -1)
            }

            else -> {

                buildSubImpactWaveform(amplitude, intensity)
            }
        }
    }

    fun cancel() {
        try { vibrator?.cancel() } catch (_: Exception) {}
        lastEventTimeMs = 0L
        accumulatedEnergy = 0f
    }

    fun release() {
        cancel()
        eventCounter = 0L
    }

    fun generateFromCommand(command: HapticCommand) {

        val canVibrate = if (!isEnabled) {
            false
        } else {

            val retryVibrator = try {
                val sysCtx = android.app.Application::class.java
                true
            } catch (_: Exception) { false }

            hasVibrator || retryVibrator
        }
        if (!isEnabled) return

        val vib = vibrator ?: run {
            Log.w(TAG, "vibrator is null, attempting to get system vibrator")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get fallback vibrator: ${e.message}")
                null
            }
        }

        if (vib == null) {
            Log.e(TAG, "❌ VIBRATOR IS NULL - cannot vibrate!")
            return
        }

        val now = android.os.SystemClock.elapsedRealtime()
        frameEntryLatency = (now - command.timestamp).coerceAtLeast(0L)

        val intensity = command.intensity

        val finalIntensity = intensity * presetAmplitudeMultiplier

        val beatConfidence = when (activeProfile) {
            HapticProfile.EDM -> if (command.isBeat) 0.90f else 0.70f
            HapticProfile.PIANO -> 0.50f
            HapticProfile.ROCK -> if (command.isBeat) 0.95f else 0.80f
            HapticProfile.CINEMATIC -> 0.75f
            else -> 0.80f
        }
        val nonlinearBoost = when {
            command.isKeyStrike -> beatConfidence.pow(1.5f) * 1.3f
            command.isBeat -> beatConfidence.pow(1.5f)
            else -> 1.0f
        }

        dynamicMoodEnergy = dynamicMoodEnergy * 0.80f + finalIntensity.coerceIn(0f, 1f) * 0.20f
        dynamicMoodEnergy = dynamicMoodEnergy.coerceIn(0.05f, 1.0f)
        val emotionalEnvelope = dynamicMoodEnergy * 0.85f + (1.0f - dynamicMoodEnergy) * 0.30f

        val profileBoost = when (activeProfile) {
            HapticProfile.CINEMATIC -> 0.9f
            HapticProfile.EDM -> 1.3f
            HapticProfile.PIANO -> 0.7f
            HapticProfile.ROCK -> 1.2f
            else -> 1.0f
        }

        val boostedIntensity = finalIntensity.pow(profile.boostExponent) * boostLevel * userAmplitudeScale * nonlinearBoost * emotionalEnvelope * profileBoost * presetBassMultiplier

        isKeyStrikeMode = command.isKeyStrike
        if (command.isKeyStrike) {
            lastKeyStrikeSemantic = command.keyStrikeSemantic
            lastSemanticType = command.semanticType
        }

        val targetAmplitude = if (hasAmplitudeControl) {
            (boostedIntensity * MAX_AMPLITUDE).toInt()
                .coerceAtLeast(minGuaranteedAmplitudeActive)
                .coerceAtMost(MAX_AMPLITUDE)
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }

        val envelopedAmplitude = (targetAmplitude * command.adsrEnvelope * command.thermalGain).toInt()
            .coerceAtLeast(minGuaranteedAmplitudeActive)
            .coerceAtMost(MAX_AMPLITUDE)

        val effect = when {

            command.isKeyStrike && isApi26Plus -> buildKeyStrikeWaveform(
                envelopedAmplitude, finalIntensity, command.keyStrikeSemantic, command.pitch, command.semanticType
            )

            command.isBeat && isApi29Plus -> {

                val timings = longArrayOf(0L, 2L, 8L, 2L, 12L, 0L)
                val amps = intArrayOf(0, envelopedAmplitude.coerceAtMost(255), 0)
                VibrationEffect.createWaveform(timings, amps, -1)
            }

            command.isTransient && isApi26Plus -> buildImpactWaveform(envelopedAmplitude, finalIntensity, command.pitch, true)

            command.bassComponent > 0.3f && isApi26Plus -> buildSubImpactWaveform(envelopedAmplitude, finalIntensity)

            command.textureComponent > 0.2f && isApi29Plus && cachedTick != null -> cachedTick

            command.semanticType == SemanticType.DEEP_BASS && isApi26Plus -> buildSubImpactWaveform(envelopedAmplitude, finalIntensity)
            command.semanticType == SemanticType.KICK_BASS && isApi26Plus -> buildImpactWaveform(envelopedAmplitude, finalIntensity, command.pitch, true)
            command.semanticType == SemanticType.MID_PUNCH && isApi29Plus && cachedHeavyClick != null -> cachedHeavyClick
            command.semanticType == SemanticType.TEXTURE_DETAIL && isApi29Plus && cachedTick != null -> cachedTick

            isApi26Plus -> {
                val dur = when {
                    command.bassComponent > 0.3f -> (finalIntensity * 45f).toLong().coerceIn(15, 50)
                    command.isTransient -> 20L
                    else -> 10L
                }
                VibrationEffect.createOneShot(dur, envelopedAmplitude)
            }

            else -> {
                @Suppress("DEPRECATION")
                vib.vibrate(if (command.bassComponent > 0.3f) 40L else 15L)
                null
            }
        }

        effect?.let {
            Log.e(TAG, "⚡ VIBRATE! amp=$envelopedAmplitude mode=${when {
                command.isKeyStrike -> "KEY-STRIKE[${command.keyStrikeSemantic.name}]"
                command.isBeat -> "BEAT-Pulse"
                command.isTransient -> "TRANSIENT-Impact"
                command.bassComponent > 0.3f -> "SUB-Wave"
                command.textureComponent > 0.2f -> "TEXTURE-Tick"
                else -> "MID-Click"
            }} effectClass=${it.javaClass.simpleName}")

            LinkHealthMonitor.heartbeatVibrateCall()

            try { vib.vibrate(it) } catch (e: Exception) { Log.w(TAG, "vibrate failed: ${e.message}") }
        }

        eventCounter++
        if (eventCounter % 20L == 0L) {
            val modeStr = when {
                command.isKeyStrike -> "KEY-STRIKE[${command.keyStrikeSemantic.name}]"
                command.isBeat -> "BEAT-Pulse"
                command.isTransient -> "TRANSIENT-Impact"
                command.bassComponent > 0.3f -> "SUB-Wave"
                command.textureComponent > 0.2f -> "TEXTURE-Tick"
                else -> "MID-Click"
            }
            logListener?.invoke(
                "#%d | %s amp=%d I=%.2f Env=%.2f Th=%.2f KS=%s Sem=%s F0=%.0fHz Δ=%dms"
                    .format(eventCounter, modeStr, envelopedAmplitude,
                        intensity, command.adsrEnvelope, command.thermalGain,
                        command.keyStrikeSemantic.name, command.semanticType.name,
                        command.pitch, frameEntryLatency)
            )
        }
    }
}
