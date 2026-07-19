package com.mouya.musichaptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.*

/**
 * iPhone Music Haptics 风格触觉事件生成器 — 增强版 v2。
 *
 * 将 DSP 管线输出的三频段强度 + 音高信息映射为 Android VibrationEffect。
 * 数据流：DSP → [能量累积 + 振幅增强] → [Waveform/Predefined/OneShot] → Vibrator HAL
 *
 * ## 增强特性
 * - **振幅增强**：sqrt 幂律压缩 + 保底振幅(MIN_GUARANTEED_AMPLITUDE=40)
 * - **振动模式分层**：Sub→衰减Waveform, Mid→HEAVY_CLICK, Presence→TICK
 * - **能量累积触发**：弱信号攒够了再发射，避免无效微弱振动
 * - **boostLevel 外部注入**：预设档位可动态调节整体强度
 */
class HapticEventGenerator(
    context: Context,
    /** 设备马达配置。运行时根据机型自动匹配。 */
    val profile: DeviceProfile = detectDeviceProfile()
) {

    companion object {
        private const val TAG = "HapticEventGen"

        // ── 物理极限（不随设备变化） ──
        private const val MIN_AMPLITUDE = 1
        private const val MAX_AMPLITUDE = 255
        /** 累积能量上限，防止静音后第一帧爆发 */
        private const val ENERGY_CAP = 1.5f
    }

    // ── Vibrator 基础设施 ──
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    val hasVibrator: Boolean = vibrator.hasVibrator()

    private val hasAmplitudeControl: Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { vibrator.hasAmplitudeControl() } catch (_: Exception) { false }
        } else false

    private val isApi29Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    private val isApi26Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    // ── 状态 ──
    private var lastEventTimeMs = 0L
    private var accumulatedEnergy = 0f
    private var eventCounter = 0L
    private var silentFrameCount = 0  // 连续静默帧计数，用于微振填充
    private var frameEntryLatency = 0L // 最近一帧从DSP到这里的延迟(ms)

    /** Public read-only access for telemetry */
    val currentFrameLatencyMs: Long get() = frameEntryLatency

    @Volatile var isEnabled = true

    /** 外部注入的整体强度倍率。1.0=标准，2.0=双倍。由预设档位设置。 */
    @Volatile var boostLevel: Float = 1.0f

    /** UI 日志回调：将事件数据推送到前端事件监视器 */
    var logListener: ((String) -> Unit)? = null

    // ── 预定义效果缓存 ──
    private val cachedHeavyClick: VibrationEffect? =
        if (isApi29Plus) VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK) else null
    private val cachedClick: VibrationEffect? =
        if (isApi29Plus) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK) else null
    private val cachedTick: VibrationEffect? =
        if (isApi29Plus) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) else null

    /**
     * 将 DSP 输出转化为触觉事件并播放。
     */
    fun generateAndPlay(
        sub: Float,
        mid: Float,
        presence: Float,
        pitch: Float,
        currentTimeMs: Long
    ) {
        if (!isEnabled || !hasVibrator) return

        // ── 延迟测量 ──
        val now = android.os.SystemClock.elapsedRealtime()
        frameEntryLatency = (now - currentTimeMs).coerceAtLeast(0L)

        // ── Step 1: 加权混合 ──
        val blendedIntensity = (sub * profile.subWeight * profile.bassBoost) + (mid * profile.midWeight) + (presence * profile.presenceWeight)

        if (blendedIntensity < profile.silenceThreshold) {
            // 静默帧缓慢泄放累积能量
            accumulatedEnergy = (accumulatedEnergy * 0.9f).coerceAtMost(ENERGY_CAP)
            // ── 微振填充: 马达不闲着 ──
            silentFrameCount++
            if (silentFrameCount >= profile.fillerFrameThreshold && hasAmplitudeControl) {
                try { vibrator.vibrate(VibrationEffect.createOneShot(profile.fillerDurationMs, profile.fillerAmplitude)) }
                catch (_: Exception) {}
                silentFrameCount = 0
            }
            return
        }
        silentFrameCount = 0  // 有信号，重置填充计数

        // ── Step 2: 能量累积触发 ──
        accumulatedEnergy = (accumulatedEnergy + blendedIntensity).coerceAtMost(ENERGY_CAP)
        if (accumulatedEnergy < profile.energyThreshold) return
        accumulatedEnergy = 0f

        // ── Step 3: 音高间隔硬上限保护 ──
        val pitchIntervalMs = if (pitch > 0f && pitch < 500f) {
            (1000f / pitch).coerceIn(profile.minIntervalMs.toFloat(), profile.maxIntervalMs.toFloat()).toLong()
        } else profile.maxIntervalMs

        if (currentTimeMs - lastEventTimeMs < pitchIntervalMs) return
        lastEventTimeMs = currentTimeMs

        // ── Step 4: 振幅增强 (sqrt幂律 + 保底 + boostLevel) ──
        val boostedIntensity = blendedIntensity.pow(profile.boostExponent) * boostLevel
        val targetAmplitude = if (hasAmplitudeControl) {
            (boostedIntensity * MAX_AMPLITUDE).toInt()
                .coerceAtLeast(profile.minGuaranteedAmplitude)
                .coerceAtMost(MAX_AMPLITUDE)
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }

        // ── Step 5: 频段判定 ──
        val subPower = sub * profile.subWeight * profile.bassBoost
        val midPower = mid * profile.midWeight
        val presPower = presence * profile.presenceWeight
        val isSubDominant = subPower >= midPower && subPower >= presPower
        val isPresenceDominant = presPower > subPower && presPower > midPower

        // ── Step 6: 振动模式分层 ──
        try {
            when {
                // 低频主导 → 衰减Waveform（重击+余震包络）
                isSubDominant && isApi26Plus -> {
                    vibrator.vibrate(buildSubImpactWaveform(targetAmplitude, blendedIntensity))
                }
                // 中频主导 → HEAVY_CLICK（强节奏感）
                !isPresenceDominant && isApi29Plus -> {
                    vibrator.vibrate(cachedHeavyClick)
                }
                // 高频主导 → TICK（细腻纹理）
                isPresenceDominant && isApi29Plus -> {
                    vibrator.vibrate(cachedTick)
                }
                // API 26-28 回退
                isApi26Plus -> {
                    val dur = when {
                        isSubDominant -> (boostedIntensity * 45f).toLong().coerceIn(15, 50)
                        isPresenceDominant -> 10L
                        else -> 20L
                    }
                    vibrator.vibrate(VibrationEffect.createOneShot(dur, targetAmplitude))
                }
                // API < 26 最简回退
                else -> {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(if (isSubDominant) 40L else 15L)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration effect failed: ${e.message}")
        }

        // ── 周期性日志 → 事件监视器 ──
        eventCounter++
        if (eventCounter % 20L == 0L) {
            val modeStr = when {
                isSubDominant -> "SUB-Wave"
                isPresenceDominant -> "PRES-Tick"
                else -> "MID-Click"
            }
            logListener?.invoke(
                "#%d | %s amp=%d blend=%.2f boost=%.2f F0=%.0fHz Δ=%dms"
                    .format(eventCounter, modeStr, targetAmplitude,
                        blendedIntensity, boostLevel, pitch, frameEntryLatency)
            )
        }
    }

    /**
     * 构建低频衰减波形：模拟重击后的余震衰减包络。
     * 包络: 高振幅 → 暂停 → 中振幅 → 暂停 → 余韵
     */
    private fun buildSubImpactWaveform(amplitude: Int, intensity: Float): VibrationEffect {
        val amp1 = amplitude
        val amp2 = (amplitude * profile.subAmpDecay2).toInt().coerceAtLeast(profile.minGuaranteedAmplitude / 2)
        val amp3 = (amplitude * profile.subAmpDecay3).toInt().coerceAtLeast(MIN_AMPLITUDE)

        // 强度越高，各阶段越长（在设备预设范围内线性插值）
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

        // timings: [wait, sustain0, wait, sustain1, wait, sustain2]
        val timings = longArrayOf(0, dur1, gap1, dur2, gap2, dur3)
        val amps = intArrayOf(amp1, amp2, amp3)
        return VibrationEffect.createWaveform(timings, amps, -1)
    }

    fun cancel() {
        try { vibrator.cancel() } catch (_: Exception) {}
        lastEventTimeMs = 0L
        accumulatedEnergy = 0f
    }

    fun release() {
        cancel()
        eventCounter = 0L
    }
}