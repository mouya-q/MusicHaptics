package com.mouya.musichaptics

import android.os.Build
import java.util.Locale

data class DeviceProfile(
    val name: String,
    val description: String,

    val startLatencyMs: Float = 4.5f,

    val stopLatencyMs: Float = 8.0f,

    val minGuaranteedAmplitude: Int = 40,
    val maxAmplitude: Int = 255,

    val boostExponent: Float = 0.50f,

    val subDur1Min: Long = 18, val subDur1Max: Long = 40,

    val subGap1Min: Long = 6,  val subGap1Max: Long = 16,

    val subDur2Min: Long = 8,  val subDur2Max: Long = 22,

    val subGap2Min: Long = 3,  val subGap2Max: Long = 12,

    val subDur3Min: Long = 4,  val subDur3Max: Long = 14,

    val subAmpDecay2: Float = 0.55f,

    val subAmpDecay3: Float = 0.25f,

    val minIntervalMs: Long = 4L,
    val maxIntervalMs: Long = 40L,

    val silenceThreshold: Float = 0.0025f,
    val energyThreshold: Float = 0.07f,

    val fillerFrameThreshold: Int = 8,
    val fillerDurationMs: Long = 3L,
    val fillerAmplitude: Int = 1,

    val subWeight: Float = 1.0f,
    val midWeight: Float = 0.6f,
    val presenceWeight: Float = 0.4f,

    val bassBoost: Float = 1.0f,

    val actuator: ActuatorProfile = ActuatorProfile.DEFAULT,

    // ═══════════════════════════════════════════════════════════════════
    // v4.10: DSP-domain thresholds (separate quantity from energyThreshold!)
    //
    // `energyThreshold` above lives in the HapticEventGenerator domain — it is
    // compared against *accumulated* energy over many frames (values 0.003-0.09).
    // DspWorkerThread instead compares against a *single-frame* RMS (typical
    // real values on music: rmsLow ≈ 0.02-0.05, rmsHigh ≈ 0.003-0.01), so it
    // needs its own, far smaller floor. Feeding energyThreshold into the DSP
    // path is what made every non-Xiaomi-10 device silent: e.g. OnePlus 13's
    // 0.05 → subThreshold 0.09, which single-frame low-band RMS never reaches.
    //
    // Leave the overrides null to auto-derive the floor from the actuator's
    // physics (speed /振量 / Q). The derivation is calibrated so that the
    // Xiaomi 10 0809 reference reproduces its known-good value (~0.004).
    // ═══════════════════════════════════════════════════════════════════
    val dspEnergyFloorOverride: Float? = null,
    val dspSubMultOverride: Float? = null,
    val dspKickMultOverride: Float? = null,
    val dspSnareMultOverride: Float? = null,
    val dspTickMultOverride: Float? = null,
    val dspBodyMultOverride: Float? = null,
) {
    /**
     * v4.10: Single-frame RMS floor for DspWorkerThread beat detection.
     *
     * Reference point (measured on real logs): Xiaomi 10 / 0809 LRA —
     * responseTime 5.75 ms, maxDisplacement 0.95, Q 16 → 0.0040.
     *
     * - Fast motors (ESA1016: 3.25 ms) can retrigger sooner → lower floor,
     *   more events, denser texture.
     * - Weak motors (small 振量) need a higher floor, otherwise quiet frames
     *   produce vibrations too faint to feel and just waste refractory time.
     * - Low-Q motors smear successive pulses together → higher floor.
     */
    val dspEnergyFloor: Float
        get() = dspEnergyFloorOverride ?: run {
            val a = actuator
            val speed = (a.responseTimeMs / 5.75f).coerceIn(0.45f, 2.2f)
            val power = (0.95f / a.maxDisplacement.coerceAtLeast(0.5f)).coerceIn(0.80f, 1.40f)
            val agility = (16f / a.qFactor.coerceAtLeast(6f)).coerceIn(0.85f, 1.60f)
            val floor = DSP_FLOOR_REF *
                pow(speed, 0.65f) *
                pow(power, 0.50f) *
                pow(agility, 0.35f)
            floor.coerceIn(0.0015f, 0.0085f)
        }

    /** SUB band multiplier — low-resonance wideband motors reproduce deep bass better. */
    val dspSubMult: Float
        get() = dspSubMultOverride
            ?: (1.8f * (actuator.resonanceFreq / 200f).coerceIn(0.75f, 1.10f))

    /** KICK band multiplier — same reasoning as SUB but a shallower tilt. */
    val dspKickMult: Float
        get() = dspKickMultOverride
            ?: (1.0f * (actuator.resonanceFreq / 200f).coerceIn(0.85f, 1.10f))

    /** SNARE band multiplier — mid transients, mostly actuator-agnostic. */
    val dspSnareMult: Float
        get() = dspSnareMultOverride
            ?: (0.7f * (15f / actuator.qFactor.coerceIn(8f, 20f)).coerceIn(0.85f, 1.30f))

    /** TICK band multiplier — only crisp (high-Q) motors can render fine needles. */
    val dspTickMult: Float
        get() = dspTickMultOverride
            ?: (0.4f * (15f / actuator.qFactor.coerceIn(8f, 20f)).coerceIn(0.75f, 1.40f))

    /** BODY band multiplier — slow motors would turn sustained parts into mush. */
    val dspBodyMult: Float
        get() = dspBodyMultOverride
            ?: (1.5f * (actuator.responseTimeMs / 5.75f).coerceIn(0.90f, 1.35f))

    /** v4.10: Refractory scale — slow motors need longer gaps, fast ones can go denser. */
    val dspRefractoryScale: Float
        get() = (actuator.responseTimeMs / 5.75f).coerceIn(0.50f, 2.00f)

    companion object {
        /** Calibrated against the Xiaomi 10 0809 reference (known-good in real logs). */
        private const val DSP_FLOOR_REF = 0.0040f

        private fun pow(base: Float, exp: Float): Float =
            Math.pow(base.toDouble(), exp.toDouble()).toFloat()


        val DEFAULT = DeviceProfile(
            name = "Generic Default",
            description = "High sensitivity for guaranteed vibration on all Android LRA types",
            minGuaranteedAmplitude = 45,
            maxAmplitude = 255,
            boostExponent = 0.35f,

            subDur1Min = 20, subDur1Max = 50,
            subGap1Min = 5,  subGap1Max = 15,
            subDur2Min = 10, subDur2Max = 25,
            subGap2Min = 3,  subGap2Max = 12,
            subDur3Min = 6,  subDur3Max = 18,
            subAmpDecay2 = 0.55f,
            subAmpDecay3 = 0.28f,
            minIntervalMs = 4L,
            maxIntervalMs = 45L,
            silenceThreshold = 0.0005f,
            energyThreshold = 0.008f,
            fillerFrameThreshold = 4,
            fillerDurationMs = 4L,
            fillerAmplitude = 18,
            bassBoost = 1.2f,
            actuator = ActuatorProfile.DEFAULT,
        )

        val XIAOMI13_XAXIS = DeviceProfile(
            name = "Xiaomi 13 · X-axis LRA",
            description = "X-axis wideband LRA, ~200 Hz resonance, < 5 ms start/stop, ultra-responsive",
            minGuaranteedAmplitude = 12,
            maxAmplitude = 255,
            boostExponent = 0.45f,

            subDur1Min = 12, subDur1Max = 30,
            subGap1Min = 4,  subGap1Max = 10,
            subDur2Min = 6,  subDur2Max = 16,
            subGap2Min = 2,  subGap2Max = 8,
            subDur3Min = 3,  subDur3Max = 10,
            subAmpDecay2 = 0.50f,
            subAmpDecay3 = 0.22f,
            minIntervalMs = 3L,
            maxIntervalMs = 35L,
            silenceThreshold = 0.002f,
            energyThreshold = 0.06f,
            fillerFrameThreshold = 6,
            fillerDurationMs = 2L,
            fillerAmplitude = 1,
            actuator = ActuatorProfile.XIAOMI_13_XAXIS,
        )

        val XIAOMI10_XAXIS = DeviceProfile(
            name = "Xiaomi 10 Series · 0809 X-axis LRA",
            description = "0809 X-axis wideband LRA, ~190 Hz resonance, < 5 ms start/stop, rich texture",
            minGuaranteedAmplitude = 45,
            maxAmplitude = 255,
            boostExponent = 0.5f,

            subDur1Min = 15, subDur1Max = 45,
            subGap1Min = 4,  subGap1Max = 12,
            subDur2Min = 8,  subDur2Max = 22,
            subGap2Min = 3,  subGap2Max = 10,
            subDur3Min = 5,  subDur3Max = 16,
            subAmpDecay2 = 0.55f,
            subAmpDecay3 = 0.30f,
            minIntervalMs = 3L,
            maxIntervalMs = 35L,
            silenceThreshold = 0.0001f,
            energyThreshold = 0.003f,
            fillerFrameThreshold = 2,
            fillerDurationMs = 6L,
            fillerAmplitude = 35,
            bassBoost = 1.2f,
            actuator = ActuatorProfile.XIAOMI_10_0809,
        )

        val REDMI_K80U_0809 = DeviceProfile(
            name = "Redmi K80 Ultra · 0809 Z-axis LRA",
            description = "0809 Z-axis LRA, ~160 Hz resonance, ~12 ms start/stop, needs longer envelope",
            minGuaranteedAmplitude = 35,
            maxAmplitude = 255,
            boostExponent = 0.42f,

            subDur1Min = 22, subDur1Max = 45,
            subGap1Min = 8,  subGap1Max = 18,
            subDur2Min = 12, subDur2Max = 26,
            subGap2Min = 5,  subGap2Max = 14,
            subDur3Min = 6,  subDur3Max = 16,
            subAmpDecay2 = 0.60f,
            subAmpDecay3 = 0.30f,
            minIntervalMs = 5L,
            maxIntervalMs = 45L,
            silenceThreshold = 0.003f,
            energyThreshold = 0.09f,
            fillerFrameThreshold = 10,
            fillerDurationMs = 4L,
            fillerAmplitude = 2,
            subWeight = 1.0f,
            midWeight = 0.55f,
            presenceWeight = 0.35f,
            bassBoost = 1.30f,
            actuator = ActuatorProfile.REDMI_K80U_ZAXIS,
        )

        val FLAGSHIP_XAXIS = DeviceProfile(
            name = "Flagship X-axis · Auto-Detected",
            description = "Detected high-end haptic capability (API 33+ full primitive support); using X-axis aggressive params",
            minGuaranteedAmplitude = 12,
            boostExponent = 0.42f,
            subDur1Min = 10, subDur1Max = 28,
            subGap1Min = 3,  subGap1Max = 10,
            subDur2Min = 5,  subDur2Max = 15,
            subGap2Min = 2,  subGap2Max = 8,
            subDur3Min = 3,  subDur3Max = 10,
            subAmpDecay2 = 0.48f,
            subAmpDecay3 = 0.20f,
            minIntervalMs = 2L,
            maxIntervalMs = 30L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.04f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 2L,
            fillerAmplitude = 4,
            actuator = ActuatorProfile.FLAGSHIP_XAXIS,
        )

        val ONEPLUS_13T = DeviceProfile(
            name = "OnePlus 13T · X-axis LRA",
            description = "OnePlus 13T flagship X-axis LRA, ~190 Hz resonance, fast response, needs primitive fallback support",
            minGuaranteedAmplitude = 20,
            maxAmplitude = 255,
            boostExponent = 0.42f,
            subDur1Min = 12, subDur1Max = 30,
            subGap1Min = 4,  subGap1Max = 10,
            subDur2Min = 6,  subDur2Max = 16,
            subGap2Min = 2,  subGap2Max = 8,
            subDur3Min = 4,  subDur3Max = 12,
            subAmpDecay2 = 0.50f,
            subAmpDecay3 = 0.22f,
            minIntervalMs = 3L,
            maxIntervalMs = 35L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.04f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 3L,
            fillerAmplitude = 8,
            actuator = ActuatorProfile.ONEPLUS_13T,
        )

        val OPPO_RENO8_PRO = DeviceProfile(
            name = "OPPO Reno8 Pro · ELA0809 X-axis",
            description = "OPPO Reno8 Pro mid-range X-axis LRA, ELA0809 170 Hz, 252mm³, ~10ms rise/fall, ColorOS 4D haptics",
            minGuaranteedAmplitude = 30,
            maxAmplitude = 220,  // 小体积马达, 降低上限防止失真
            boostExponent = 0.55f,  // 较低boost, 入门级马达不耐高增益
            subDur1Min = 16, subDur1Max = 38,
            subGap1Min = 6,  subGap1Max = 14,
            subDur2Min = 8,  subDur2Max = 20,
            subGap2Min = 3,  subGap2Max = 10,
            subDur3Min = 5,  subDur3Max = 15,
            subAmpDecay2 = 0.60f,  // 更快衰减, 防止余震重叠
            subAmpDecay3 = 0.30f,
            minIntervalMs = 4L,
            maxIntervalMs = 40L,
            silenceThreshold = 0.0025f,  // 稍高阈值滤除底噪
            energyThreshold = 0.07f,
            fillerFrameThreshold = 8,
            fillerDurationMs = 3L,
            fillerAmplitude = 1,
            bassBoost = 1.1f,  // 低频稍增补偿小体积不足
            actuator = ActuatorProfile.OPPO_RENO8_PRO,
        )

        val ONEPLUS_15 = DeviceProfile(
            name = "OnePlus 15 · 高性能 X轴 LRA",
            description = "OnePlus 15 flagship X-axis LRA, ~200 Hz resonance, ultra-fast, crisp transient, high Q",
            minGuaranteedAmplitude = 12,
            maxAmplitude = 255,
            boostExponent = 0.32f,
            subDur1Min = 8, subDur1Max = 22,
            subGap1Min = 4,  subGap1Max = 12,
            subDur2Min = 4,  subDur2Max = 12,
            subGap2Min = 3,  subGap2Max = 10,
            subDur3Min = 3,  subDur3Max = 8,
            subAmpDecay2 = 0.40f,
            subAmpDecay3 = 0.15f,
            minIntervalMs = 3L,
            maxIntervalMs = 28L,
            silenceThreshold = 0.002f,
            energyThreshold = 0.055f,
            fillerFrameThreshold = 6,
            fillerDurationMs = 2L,
            fillerAmplitude = 2,
            actuator = ActuatorProfile.ONEPLUS_15,
        )

        // v3.10.20: OnePlus 全系 + 拯救者Y700 + 澎湃Ultra 适配

        val ONEPLUS_11 = DeviceProfile(
            name = "OnePlus 11 · CSA0916 X-axis LRA",
            description = "OnePlus 11 CSA0916 N52, 602mm³, ~10ms start/stop, large volume, rich bass",
            minGuaranteedAmplitude = 15,
            maxAmplitude = 255,
            boostExponent = 0.38f,
            subDur1Min = 12, subDur1Max = 30,
            subGap1Min = 4,  subGap1Max = 12,
            subDur2Min = 6,  subDur2Max = 16,
            subGap2Min = 3,  subGap2Max = 10,
            subDur3Min = 4,  subDur3Max = 12,
            subAmpDecay2 = 0.48f,
            subAmpDecay3 = 0.22f,
            minIntervalMs = 3L,
            maxIntervalMs = 32L,
            silenceThreshold = 0.0012f,
            energyThreshold = 0.042f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 3L,
            fillerAmplitude = 5,
            actuator = ActuatorProfile.ONEPLUS_11,
        )

        val ONEPLUS_12 = DeviceProfile(
            name = "OnePlus 12 · CSA0916 Turbo X-axis LRA",
            description = "OnePlus 12 N54+CSA+, 602mm³, fast start/stop, wide bandwidth",
            minGuaranteedAmplitude = 12,
            maxAmplitude = 255,
            boostExponent = 0.35f,
            subDur1Min = 10, subDur1Max = 26,
            subGap1Min = 3,  subGap1Max = 10,
            subDur2Min = 5,  subDur2Max = 14,
            subGap2Min = 2,  subGap2Max = 8,
            subDur3Min = 3,  subDur3Max = 10,
            subAmpDecay2 = 0.44f,
            subAmpDecay3 = 0.18f,
            minIntervalMs = 3L,
            maxIntervalMs = 30L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.045f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 2L,
            fillerAmplitude = 3,
            actuator = ActuatorProfile.ONEPLUS_12,
        )

        val ONEPLUS_13 = DeviceProfile(
            name = "OnePlus 13 · 仿生振感马达Turbo",
            description = "OnePlus 13 CSA+0916, 602mm³, ColorOS 15, 72 O-Haptics effects, ultra-fast",
            minGuaranteedAmplitude = 10,
            maxAmplitude = 255,
            boostExponent = 0.30f,  // 旗舰最强马达, 增益可更低
            subDur1Min = 8, subDur1Max = 22,
            subGap1Min = 3,  subGap1Max = 9,
            subDur2Min = 4,  subDur2Max = 12,
            subGap2Min = 2,  subGap2Max = 7,
            subDur3Min = 3,  subDur3Max = 8,
            subAmpDecay2 = 0.42f,
            subAmpDecay3 = 0.16f,
            minIntervalMs = 2L,
            maxIntervalMs = 28L,
            silenceThreshold = 0.0015f,
            energyThreshold = 0.05f,
            fillerFrameThreshold = 6,
            fillerDurationMs = 2L,
            fillerAmplitude = 2,
            actuator = ActuatorProfile.ONEPLUS_13,
        )

        val ONEPLUS_ACE3PRO = DeviceProfile(
            name = "OnePlus Ace 3 Pro · CSA0916 Turbo",
            description = "OnePlus Ace3 Pro, same 0916 Turbo motor as OP12, fast and crisp",
            minGuaranteedAmplitude = 12,
            maxAmplitude = 255,
            boostExponent = 0.35f,
            subDur1Min = 10, subDur1Max = 26,
            subGap1Min = 3,  subGap1Max = 10,
            subDur2Min = 5,  subDur2Max = 14,
            subGap2Min = 2,  subGap2Max = 8,
            subDur3Min = 3,  subDur3Max = 10,
            subAmpDecay2 = 0.44f,
            subAmpDecay3 = 0.18f,
            minIntervalMs = 3L,
            maxIntervalMs = 30L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.045f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 2L,
            fillerAmplitude = 3,
            actuator = ActuatorProfile.ONEPLUS_ACE3PRO,
        )

        val ONEPLUS_ACE_MID = DeviceProfile(
            name = "OnePlus Ace3/Ace5 · 0809A X-axis LRA",
            description = "OnePlus Ace3/Ace5 0809A mid-range X-axis, moderate volume and speed",
            minGuaranteedAmplitude = 25,
            maxAmplitude = 255,
            boostExponent = 0.45f,  // 小马达需要更多增益
            subDur1Min = 14, subDur1Max = 35,
            subGap1Min = 5,  subGap1Max = 14,
            subDur2Min = 7,  subDur2Max = 18,
            subGap2Min = 3,  subGap2Max = 10,
            subDur3Min = 5,  subDur3Max = 14,
            subAmpDecay2 = 0.52f,
            subAmpDecay3 = 0.25f,
            minIntervalMs = 4L,
            maxIntervalMs = 38L,
            silenceThreshold = 0.0008f,
            energyThreshold = 0.03f,
            fillerFrameThreshold = 4,
            fillerDurationMs = 3L,
            fillerAmplitude = 8,
            actuator = ActuatorProfile.ONEPLUS_ACE_MID,
        )

        val LENOVO_Y700_GEN1 = DeviceProfile(
            name = "Legion Y700 (Gen1) · 双X轴马达",
            description = "Y700 2022 dual X-axis LRA, rich bass",
            minGuaranteedAmplitude = 15,
            maxAmplitude = 255,
            boostExponent = 0.36f,
            subDur1Min = 10, subDur1Max = 28,
            subGap1Min = 4,  subGap1Max = 12,
            subDur2Min = 6,  subDur2Max = 16,
            subGap2Min = 3,  subGap2Max = 10,
            subDur3Min = 4,  subDur3Max = 12,
            subAmpDecay2 = 0.46f,
            subAmpDecay3 = 0.20f,
            minIntervalMs = 3L,
            maxIntervalMs = 35L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.04f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 3L,
            fillerAmplitude = 4,
            bassBoost = 1.15f,
            actuator = ActuatorProfile.DEFAULT,
        )

        val LENOVO_Y700_GEN2 = DeviceProfile(
            name = "Legion Y700 (Gen2/3) · 0815 X轴马达",
            description = "Y700 2023/2024 single 0815 LRA, 200Hz high Q",
            minGuaranteedAmplitude = 15,
            maxAmplitude = 255,
            boostExponent = 0.38f,
            subDur1Min = 10, subDur1Max = 28,
            subGap1Min = 4,  subGap1Max = 12,
            subDur2Min = 6,  subDur2Max = 16,
            subGap2Min = 3,  subGap2Max = 10,
            subDur3Min = 4,  subDur3Max = 12,
            subAmpDecay2 = 0.46f,
            subAmpDecay3 = 0.20f,
            minIntervalMs = 3L,
            maxIntervalMs = 35L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.04f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 3L,
            fillerAmplitude = 4,
            bassBoost = 1.0f,
            actuator = ActuatorProfile.LENOVO_Y700,
        )

        val XIAOMI_ULTRA = DeviceProfile(
            name = "Xiaomi Ultra · RichFeel ESA1016 超宽频",
            description = "Xiaomi 14/15 Ultra ESA1016, 10-500Hz, 4ms start/stop, HyperOS Haptic 2.0",
            minGuaranteedAmplitude = 10,
            maxAmplitude = 255,
            boostExponent = 0.30f,  // 超宽频马达自身灵敏, 低增益
            subDur1Min = 8, subDur1Max = 22,
            subGap1Min = 3,  subGap1Max = 9,
            subDur2Min = 4,  subDur2Max = 12,
            subGap2Min = 2,  subGap2Max = 7,
            subDur3Min = 3,  subDur3Max = 8,
            subAmpDecay2 = 0.40f,
            subAmpDecay3 = 0.15f,
            minIntervalMs = 2L,
            maxIntervalMs = 28L,
            silenceThreshold = 0.0015f,
            energyThreshold = 0.05f,
            fillerFrameThreshold = 6,
            fillerDurationMs = 2L,
            fillerAmplitude = 2,
            actuator = ActuatorProfile.XIAOMI_ULTRA_RICHFEEL,
        )

        val SAMSUNG_S25 = DeviceProfile(
            name = "Samsung Galaxy S25 · X-axis LRA",
            description = "Samsung S25 flagship X-axis LRA, ~200 Hz, precise, Samsung-tuned primitives",
            minGuaranteedAmplitude = 15,
            maxAmplitude = 255,
            boostExponent = 0.42f,
            subDur1Min = 11, subDur1Max = 28,
            subGap1Min = 3,  subGap1Max = 10,
            subDur2Min = 6,  subDur2Max = 15,
            subGap2Min = 2,  subGap2Max = 8,
            subDur3Min = 4,  subDur3Max = 11,
            subAmpDecay2 = 0.48f,
            subAmpDecay3 = 0.21f,
            minIntervalMs = 2L,
            maxIntervalMs = 32L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.038f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 2L,
            fillerAmplitude = 5,
            actuator = ActuatorProfile.SAMSUNG_S25,
        )

        // v3.11: Xiaomi 11/12/14 series + Redmi K70U 新增适配

        val XIAOMI11 = DeviceProfile(
            name = "Xiaomi 11 Series · X-axis LRA",
            description = "Mi 11/11Pro/11Ultra X-axis LRA, ~190Hz, moderate speed, rich bass",
            minGuaranteedAmplitude = 25,
            maxAmplitude = 255,
            boostExponent = 0.42f,
            subDur1Min = 14, subDur1Max = 35,
            subGap1Min = 4,  subGap1Max = 12,
            subDur2Min = 7,  subDur2Max = 18,
            subGap2Min = 3,  subGap2Max = 10,
            subDur3Min = 5,  subDur3Max = 14,
            subAmpDecay2 = 0.52f,
            subAmpDecay3 = 0.24f,
            minIntervalMs = 3L,
            maxIntervalMs = 35L,
            silenceThreshold = 0.0008f,
            energyThreshold = 0.035f,
            fillerFrameThreshold = 4,
            fillerDurationMs = 3L,
            fillerAmplitude = 8,
            bassBoost = 1.15f,
            actuator = ActuatorProfile.XIAOMI_11,
        )

        val XIAOMI12 = DeviceProfile(
            name = "Xiaomi 12 Series · X-axis LRA",
            description = "Mi 12/12Pro/12X X-axis LRA, ~200Hz, CyberEngine, fast response",
            minGuaranteedAmplitude = 20,
            maxAmplitude = 255,
            boostExponent = 0.40f,
            subDur1Min = 12, subDur1Max = 30,
            subGap1Min = 4,  subGap1Max = 11,
            subDur2Min = 6,  subDur2Max = 16,
            subGap2Min = 2,  subGap2Max = 9,
            subDur3Min = 4,  subDur3Max = 12,
            subAmpDecay2 = 0.50f,
            subAmpDecay3 = 0.22f,
            minIntervalMs = 3L,
            maxIntervalMs = 32L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.038f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 2L,
            fillerAmplitude = 6,
            bassBoost = 1.1f,
            actuator = ActuatorProfile.XIAOMI_12,
        )

        val XIAOMI14 = DeviceProfile(
            name = "Xiaomi 14 Series · X-axis LRA",
            description = "Mi 14/14Pro X-axis LRA, ~200Hz, RichFeel engine, crisp transient",
            minGuaranteedAmplitude = 15,
            maxAmplitude = 255,
            boostExponent = 0.38f,
            subDur1Min = 10, subDur1Max = 26,
            subGap1Min = 3,  subGap1Max = 10,
            subDur2Min = 5,  subDur2Max = 14,
            subGap2Min = 2,  subGap2Max = 8,
            subDur3Min = 3,  subDur3Max = 10,
            subAmpDecay2 = 0.48f,
            subAmpDecay3 = 0.20f,
            minIntervalMs = 2L,
            maxIntervalMs = 30L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.04f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 2L,
            fillerAmplitude = 4,
            actuator = ActuatorProfile.XIAOMI_14,
        )

        val REDMI_K70U = DeviceProfile(
            name = "Redmi K70 Ultra · 0809 X-axis LRA",
            description = "K70U 0809 X-axis LRA, ~190Hz, moderate speed, good bass response",
            minGuaranteedAmplitude = 30,
            maxAmplitude = 255,
            boostExponent = 0.43f,
            subDur1Min = 16, subDur1Max = 38,
            subGap1Min = 6,  subGap1Max = 14,
            subDur2Min = 8,  subDur2Max = 20,
            subGap2Min = 3,  subGap2Max = 11,
            subDur3Min = 5,  subDur3Max = 14,
            subAmpDecay2 = 0.55f,
            subAmpDecay3 = 0.26f,
            minIntervalMs = 4L,
            maxIntervalMs = 38L,
            silenceThreshold = 0.0015f,
            energyThreshold = 0.05f,
            fillerFrameThreshold = 6,
            fillerDurationMs = 3L,
            fillerAmplitude = 5,
            subWeight = 1.0f,
            midWeight = 0.55f,
            presenceWeight = 0.38f,
            bassBoost = 1.25f,
            actuator = ActuatorProfile.REDMI_K70U,
        )

        val XIAOMI_15 = DeviceProfile(
            name = "Xiaomi 15 · X-axis LRA",
            description = "Xiaomi 15 flagship X-axis LRA, ~200 Hz, RichFeel engine, fast and crisp",
            minGuaranteedAmplitude = 12,
            maxAmplitude = 255,
            boostExponent = 0.40f,
            subDur1Min = 10, subDur1Max = 27,
            subGap1Min = 3,  subGap1Max = 9,
            subDur2Min = 5,  subDur2Max = 14,
            subGap2Min = 2,  subGap2Max = 7,
            subDur3Min = 3,  subDur3Max = 10,
            subAmpDecay2 = 0.47f,
            subAmpDecay3 = 0.20f,
            minIntervalMs = 2L,
            maxIntervalMs = 30L,
            silenceThreshold = 0.0008f,
            energyThreshold = 0.035f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 2L,
            fillerAmplitude = 4,
            actuator = ActuatorProfile.XIAOMI_15,
        )

        val XIAOMI_17_PRO = DeviceProfile(
            name = "Xiaomi 17 Pro · ESA1016 Ultra",
            description = "Xiaomi 17 Pro ESA1016 130Hz, 600mm³+, 10-500Hz超宽频, HyperOS 3.0, 旗舰级低频纹理",
            minGuaranteedAmplitude = 6,  // ESA1016 可更低
            maxAmplitude = 255,
            boostExponent = 0.18f,  // 极低增益，马达自身灵敏
            subDur1Min = 4, subDur1Max = 12,  // 极短脉冲
            subGap1Min = 1,  subGap1Max = 5,  // 极窄间隙
            subDur2Min = 2,  subDur2Max = 6,
            subGap2Min = 1,  subGap2Max = 3,
            subDur3Min = 1,  subDur3Max = 4,
            subAmpDecay2 = 0.28f,  // 更快衰减
            subAmpDecay3 = 0.08f,
            minIntervalMs = 1L,
            maxIntervalMs = 20L,
            silenceThreshold = 0.0008f,  // 更敏感
            energyThreshold = 0.025f,
            fillerFrameThreshold = 4,
            fillerDurationMs = 1L,
            fillerAmplitude = 1,
            bassBoost = 1.05f,
            actuator = ActuatorProfile.ESA1016_CYBER,
        )

        val VIVO_FLAGSHIP = DeviceProfile(
            name = "vivo/iQOO Flagship · X-axis LRA",
            description = "vivo/iQOO flagship X-axis LRA, ~190 Hz, strong transient, IPC-friendly",
            minGuaranteedAmplitude = 18,
            maxAmplitude = 255,
            boostExponent = 0.42f,
            subDur1Min = 12, subDur1Max = 30,
            subGap1Min = 4,  subGap1Max = 10,
            subDur2Min = 6,  subDur2Max = 16,
            subGap2Min = 2,  subGap2Max = 8,
            subDur3Min = 4,  subDur3Max = 12,
            subAmpDecay2 = 0.50f,
            subAmpDecay3 = 0.22f,
            minIntervalMs = 3L,
            maxIntervalMs = 35L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.04f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 3L,
            fillerAmplitude = 6,
            actuator = ActuatorProfile.VIVO_FLAGSHIP,
        )

        // v3.13: 全机型适配扩展 — 小米/红米/一加全系

        val XIAOMI_13PRO = DeviceProfile(
            name = "Xiaomi 13 Pro · ESA1016 CyberEngine",
            description = "Mi 13Pro ESA1016 130Hz, 560mm³, 50-500Hz超宽频, 低频纹理丰富",
            minGuaranteedAmplitude = 8,
            maxAmplitude = 255,
            boostExponent = 0.28f,  // 超大马达, 低增益
            subDur1Min = 8, subDur1Max = 22,
            subGap1Min = 3,  subGap1Max = 9,
            subDur2Min = 4,  subDur2Max = 12,
            subGap2Min = 2,  subGap2Max = 7,
            subDur3Min = 3,  subDur3Max = 8,
            subAmpDecay2 = 0.40f,
            subAmpDecay3 = 0.15f,
            minIntervalMs = 2L,
            maxIntervalMs = 28L,
            silenceThreshold = 0.0015f,
            energyThreshold = 0.05f,
            fillerFrameThreshold = 6,
            fillerDurationMs = 2L,
            fillerAmplitude = 2,
            actuator = ActuatorProfile.ESA1016_CYBER,
        )

        val XIAOMI_15PRO = DeviceProfile(
            name = "Xiaomi 15 Pro · 0815 X-axis LRA",
            description = "Xiaomi 15 Pro 0815 X-axis, 360mm³, 200Hz, RichFeel engine, flagship",
            minGuaranteedAmplitude = 12,
            maxAmplitude = 255,
            boostExponent = 0.38f,
            subDur1Min = 10, subDur1Max = 26,
            subGap1Min = 3,  subGap1Max = 9,
            subDur2Min = 5,  subDur2Max = 14,
            subGap2Min = 2,  subGap2Max = 7,
            subDur3Min = 3,  subDur3Max = 10,
            subAmpDecay2 = 0.47f,
            subAmpDecay3 = 0.20f,
            minIntervalMs = 2L,
            maxIntervalMs = 30L,
            silenceThreshold = 0.0008f,
            energyThreshold = 0.035f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 2L,
            fillerAmplitude = 4,
            actuator = ActuatorProfile.LRA_0815_GEN,
        )

        val XIAOMI_MIX_FOLD = DeviceProfile(
            name = "Xiaomi MIX Fold 3/4 · 0815 X-axis LRA",
            description = "MIX Fold 3/4 0815 X-axis, 360mm³, 200Hz, foldable form factor",
            minGuaranteedAmplitude = 18,
            maxAmplitude = 255,
            boostExponent = 0.38f,
            subDur1Min = 12, subDur1Max = 30,
            subGap1Min = 4,  subGap1Max = 11,
            subDur2Min = 6,  subDur2Max = 16,
            subGap2Min = 3,  subGap2Max = 9,
            subDur3Min = 4,  subDur3Max = 12,
            subAmpDecay2 = 0.50f,
            subAmpDecay3 = 0.22f,
            minIntervalMs = 3L,
            maxIntervalMs = 33L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.038f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 3L,
            fillerAmplitude = 5,
            bassBoost = 1.1f,
            actuator = ActuatorProfile.LRA_0815_GEN,
        )

        val REDMI_K50_GAMING = DeviceProfile(
            name = "Redmi K50 电竞版 · CyberEngine ESA1016",
            description = "K50 Gaming CyberEngine 130Hz, 560mm³, 50-500Hz超宽频, 低频纹理丰富",
            minGuaranteedAmplitude = 8,
            maxAmplitude = 255,
            boostExponent = 0.28f,  // 超大马达, 低增益
            subDur1Min = 8, subDur1Max = 22,
            subGap1Min = 3,  subGap1Max = 9,
            subDur2Min = 4,  subDur2Max = 12,
            subGap2Min = 2,  subGap2Max = 7,
            subDur3Min = 3,  subDur3Max = 8,
            subAmpDecay2 = 0.40f,
            subAmpDecay3 = 0.15f,
            minIntervalMs = 2L,
            maxIntervalMs = 28L,
            silenceThreshold = 0.0015f,
            energyThreshold = 0.05f,
            fillerFrameThreshold = 6,
            fillerDurationMs = 2L,
            fillerAmplitude = 2,
            actuator = ActuatorProfile.ESA1016_CYBER,
        )

        val REDMI_K40 = DeviceProfile(
            name = "Redmi K40/K50 · 0809 X-axis LRA",
            description = "K40/K50/K50Pro/K50U 0809 X-axis, ~200Hz, moderate speed",
            minGuaranteedAmplitude = 30,
            maxAmplitude = 255,
            boostExponent = 0.43f,
            subDur1Min = 16, subDur1Max = 38,
            subGap1Min = 6,  subGap1Max = 14,
            subDur2Min = 8,  subDur2Max = 20,
            subGap2Min = 3,  subGap2Max = 11,
            subDur3Min = 5,  subDur3Max = 14,
            subAmpDecay2 = 0.55f,
            subAmpDecay3 = 0.26f,
            minIntervalMs = 4L,
            maxIntervalMs = 38L,
            silenceThreshold = 0.0015f,
            energyThreshold = 0.05f,
            fillerFrameThreshold = 6,
            fillerDurationMs = 3L,
            fillerAmplitude = 5,
            bassBoost = 1.20f,
            actuator = ActuatorProfile.REDMI_0809_STD,
        )

        val REDMI_K60 = DeviceProfile(
            name = "Redmi K60/K60Pro · 0809 X-axis LRA",
            description = "K60/K60Pro/K60U 0809 X-axis, ~200Hz, moderate speed, good bass",
            minGuaranteedAmplitude = 28,
            maxAmplitude = 255,
            boostExponent = 0.42f,
            subDur1Min = 15, subDur1Max = 36,
            subGap1Min = 5,  subGap1Max = 13,
            subDur2Min = 7,  subDur2Max = 19,
            subGap2Min = 3,  subGap2Max = 10,
            subDur3Min = 5,  subDur3Max = 14,
            subAmpDecay2 = 0.54f,
            subAmpDecay3 = 0.25f,
            minIntervalMs = 4L,
            maxIntervalMs = 36L,
            silenceThreshold = 0.0012f,
            energyThreshold = 0.045f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 3L,
            fillerAmplitude = 5,
            bassBoost = 1.22f,
            actuator = ActuatorProfile.REDMI_0809_STD,
        )

        val REDMI_K70 = DeviceProfile(
            name = "Redmi K70/K70Pro · 0809 X-axis LRA",
            description = "K70/K70Pro 0809 X-axis, ~200Hz, HyperOS tuned, good response",
            minGuaranteedAmplitude = 25,
            maxAmplitude = 255,
            boostExponent = 0.42f,
            subDur1Min = 14, subDur1Max = 34,
            subGap1Min = 5,  subGap1Max = 12,
            subDur2Min = 7,  subDur2Max = 18,
            subGap2Min = 3,  subGap2Max = 10,
            subDur3Min = 4,  subDur3Max = 13,
            subAmpDecay2 = 0.52f,
            subAmpDecay3 = 0.24f,
            minIntervalMs = 3L,
            maxIntervalMs = 35L,
            silenceThreshold = 0.0012f,
            energyThreshold = 0.045f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 3L,
            fillerAmplitude = 5,
            bassBoost = 1.22f,
            actuator = ActuatorProfile.REDMI_0809_STD,
        )

        val ONEPLUS_9 = DeviceProfile(
            name = "OnePlus 9/9Pro · X-axis LRA",
            description = "OnePlus 9/9Pro/9R 0809 X-axis, ~200Hz, ColorOS tuned, moderate",
            minGuaranteedAmplitude = 25,
            maxAmplitude = 255,
            boostExponent = 0.42f,
            subDur1Min = 14, subDur1Max = 34,
            subGap1Min = 5,  subGap1Max = 12,
            subDur2Min = 7,  subDur2Max = 18,
            subGap2Min = 3,  subGap2Max = 10,
            subDur3Min = 4,  subDur3Max = 13,
            subAmpDecay2 = 0.52f,
            subAmpDecay3 = 0.24f,
            minIntervalMs = 3L,
            maxIntervalMs = 35L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.04f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 3L,
            fillerAmplitude = 6,
            actuator = ActuatorProfile.REDMI_0809_STD,
        )

        val ONEPLUS_10PRO = DeviceProfile(
            name = "OnePlus 10 Pro · 定制0815 X-axis LRA",
            description = "OnePlus 10 Pro custom 0815, 360mm³, +47% vibration, 130+ effects",
            minGuaranteedAmplitude = 15,
            maxAmplitude = 255,
            boostExponent = 0.36f,
            subDur1Min = 10, subDur1Max = 26,
            subGap1Min = 3,  subGap1Max = 10,
            subDur2Min = 5,  subDur2Max = 14,
            subGap2Min = 2,  subGap2Max = 8,
            subDur3Min = 3,  subDur3Max = 10,
            subAmpDecay2 = 0.46f,
            subAmpDecay3 = 0.20f,
            minIntervalMs = 3L,
            maxIntervalMs = 32L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.042f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 2L,
            fillerAmplitude = 4,
            bassBoost = 1.1f,
            actuator = ActuatorProfile.ONEPLUS_10PRO_LRA,
        )
    }
}

fun detectDeviceProfile(
    context: android.content.Context? = null,
    persistedProfileId: String? = null
): DeviceProfile {
    val rootProfileId = persistedProfileId ?: context
        ?.getSharedPreferences("haptics_config", android.content.Context.MODE_PRIVATE)
        ?.getString(RootHardwareProbe.PREF_PROFILE, null)
    when (rootProfileId) {
        "XIAOMI10_XAXIS" -> return DeviceProfile.XIAOMI10_XAXIS
        "XIAOMI13_XAXIS" -> return DeviceProfile.XIAOMI13_XAXIS
        "XIAOMI11" -> return DeviceProfile.XIAOMI11
        "XIAOMI12" -> return DeviceProfile.XIAOMI12
        "XIAOMI14" -> return DeviceProfile.XIAOMI14
        "XIAOMI15" -> return DeviceProfile.XIAOMI_15
        "XIAOMI_ULTRA" -> return DeviceProfile.XIAOMI_ULTRA
        "REDMI_K80U_0809" -> return DeviceProfile.REDMI_K80U_0809
        "REDMI_K70U" -> return DeviceProfile.REDMI_K70U
        "ONEPLUS_11" -> return DeviceProfile.ONEPLUS_11
        "ONEPLUS_12" -> return DeviceProfile.ONEPLUS_12
        "ONEPLUS_13" -> return DeviceProfile.ONEPLUS_13
        "ONEPLUS_13T" -> return DeviceProfile.ONEPLUS_13T
        "ONEPLUS_15" -> return DeviceProfile.ONEPLUS_15
        "ONEPLUS_ACE3PRO" -> return DeviceProfile.ONEPLUS_ACE3PRO
        "ONEPLUS_ACE_MID" -> return DeviceProfile.ONEPLUS_ACE_MID
        "LENOVO_Y700_GEN1" -> return DeviceProfile.LENOVO_Y700_GEN1
        "LENOVO_Y700_GEN2" -> return DeviceProfile.LENOVO_Y700_GEN2
        "SAMSUNG_S25" -> return DeviceProfile.SAMSUNG_S25
        "VIVO_FLAGSHIP" -> return DeviceProfile.VIVO_FLAGSHIP
        "XIAOMI_13PRO" -> return DeviceProfile.XIAOMI_13PRO
        "XIAOMI_15PRO" -> return DeviceProfile.XIAOMI_15PRO
        "XIAOMI_MIX_FOLD" -> return DeviceProfile.XIAOMI_MIX_FOLD
        "REDMI_K50_GAMING" -> return DeviceProfile.REDMI_K50_GAMING
        "REDMI_K40" -> return DeviceProfile.REDMI_K40
        "REDMI_K60" -> return DeviceProfile.REDMI_K60
        "REDMI_K70" -> return DeviceProfile.REDMI_K70
        "ONEPLUS_9" -> return DeviceProfile.ONEPLUS_9
        "ONEPLUS_10PRO" -> return DeviceProfile.ONEPLUS_10PRO
        "FLAGSHIP_XAXIS" -> return DeviceProfile.FLAGSHIP_XAXIS
    }

    val model = Build.MODEL.uppercase().replace(" ", "")
    val manufacturer = Build.MANUFACTURER.lowercase()
    val device = Build.DEVICE.lowercase(Locale.ROOT)
    val board = (Build.BOARD ?: "").lowercase(Locale.ROOT)

    if (manufacturer == "xiaomi") {
        if (device.contains("umi") || device.contains("cmi") || device.contains("thyme")) {
            return DeviceProfile.XIAOMI10_XAXIS
        }

        if (device.contains("venus") || device.contains("star") || device.contains("mars") ||
            model.contains("M2011") || model.contains("21111")) {
            return DeviceProfile.XIAOMI11
        }

        if (device.contains("cupid") || device.contains("zeus") || device.contains("psyche") ||
            model.contains("22011") || model.contains("21201")) {
            return DeviceProfile.XIAOMI12
        }

        if (device.contains("fuxi") || device.contains("nuwa")) {
            if (device.contains("nuwa")) return DeviceProfile.XIAOMI_13PRO
            return DeviceProfile.XIAOMI13_XAXIS
        }

        if (device.contains("houji") || device.contains("aurora") ||
            model.contains("23127PN") && !model.contains("23127PN0")) {
            return DeviceProfile.XIAOMI14
        }

        if (device.contains("haotai") || device.contains("shenni") ||
            model.contains("24129PN")) {
            if (device.contains("shenni")) return DeviceProfile.XIAOMI_15PRO
            return DeviceProfile.XIAOMI_15
        }

        if (device.contains("zijin") || model.contains("25081PN") || model.contains("25091PN") ||
            model.contains("XIAOMI17PRO") || model.contains("17PRO")) {
            return DeviceProfile.XIAOMI_17_PRO
        }

        if (device.contains("babylon") || device.contains("goku") ||
            model.contains("MIXFOLD3") || model.contains("MIXFOLD4") ||
            model.contains("2317BP") || model.contains("2405CP")) {
            return DeviceProfile.XIAOMI_MIX_FOLD
        }

        if (model.contains("24031PN") || model.contains("25042PN") ||  // 14U / 15U
            device.contains("eiffel") ||
            model.contains("ULTRA")) {
            return DeviceProfile.XIAOMI_ULTRA
        }

        if (device.contains("rubens") || model.contains("K50GAMING") ||
            model.contains("21270RK")) {
            return DeviceProfile.REDMI_K50_GAMING
        }

        if (device.contains("alioth") || device.contains("munch") || device.contains("diting") ||
            model.contains("K40") || model.contains("K50")) {
            return DeviceProfile.REDMI_K40
        }

        if (device.contains("mondrian") || device.contains("invenio") || device.contains("corot") ||
            model.contains("K60")) {
            return DeviceProfile.REDMI_K60
        }

        if (device.contains("vermeer") || device.contains("manet") ||
            model.contains("K70") && !model.contains("K70U") && !model.contains("K70ULTRA")) {
            return DeviceProfile.REDMI_K70
        }

        // v3.11: Redmi K70 Ultra: codename "rothko"
        if (device.contains("rothko") || model.contains("24013RK") ||
            model.contains("K70ULTRA") || model.contains("K70U")) {
            return DeviceProfile.REDMI_K70U
        }

        if (device.contains("k80") || model.contains("K80")) {
            return DeviceProfile.REDMI_K80U_0809
        }

        if (device.contains("houbi") || Build.VERSION.SDK_INT >= 33) {
            return DeviceProfile.FLAGSHIP_XAXIS
        }
    }

    if (manufacturer.contains("lenovo")) {
        val m = model.lowercase()
        val d = device.lowercase()
        // v3.11: Y700 Gen2/Gen3 — multiple model variants
        if (m.contains("tb320") || m.contains("tb321") ||
            d.contains("tb320") || d.contains("tb321") ||
            m.contains("y700_2023") || m.contains("y700_2024") ||
            d.contains("y7002023") || d.contains("y7002024") ||
            m.contains("y700pro")) {
            return DeviceProfile.LENOVO_Y700_GEN2
        } else if (m.contains("y700") || d.contains("y700") || m.contains("tb9707")) {
            return DeviceProfile.LENOVO_Y700_GEN1
        }
    }

    if (manufacturer == "oneplus" || manufacturer == "oppo") {
        if (model.contains("PGAM10") || model.contains("RENO8PRO") ||
            device.contains("reno8pro") || board.contains("reno8pro")) {
            return DeviceProfile.OPPO_RENO8_PRO
        }
        if (model.contains("CPH2653") || model.contains("13T") || device.contains("aston")) {
            return DeviceProfile.ONEPLUS_13T
        }
        if (model.contains("PLK110") || model.contains("CPH2747") ||
            model.contains("ONEPLUS15") || model.contains("PG110") ||
            device.contains("plk110") || board.contains("plk110")) {
            return DeviceProfile.ONEPLUS_15
        }
        if (model.contains("PJZ110") || model.contains("CPH2699") ||
            model.contains("ONEPLUS13") || device.contains("opus")) {
            return DeviceProfile.ONEPLUS_13
        }
        if (model.contains("PJD110") || model.contains("CPH2581") ||
            model.contains("ONEPLUS12") || device.contains("waffle")) {
            return DeviceProfile.ONEPLUS_12
        }
        if (model.contains("NE221") || model.contains("CPH2449") ||
            model.contains("ONEPLUS10") || device.contains("ovaltine")) {
            return DeviceProfile.ONEPLUS_10PRO
        }
        if (model.contains("LE21") || model.contains("LE22") ||
            model.contains("ONEPLUS9") || device.contains("lemonade")) {
            return DeviceProfile.ONEPLUS_9
        }
        if (model.contains("PHB110") || model.contains("CPH2447") ||
            model.contains("ONEPLUS11") || device.contains("salami")) {
            return DeviceProfile.ONEPLUS_11
        }
        if (model.contains("CPH2611") || model.contains("PHZ110") ||
            model.contains("ACE3PRO") || device.contains("ace3pro")) {
            return DeviceProfile.ONEPLUS_ACE3PRO
        }
        if (model.contains("PHD110") || model.contains("PHB") ||
            model.contains("ACE3") || model.contains("ACE5") ||
            model.contains("PHK") || model.contains("CPH2671")) {
            return DeviceProfile.ONEPLUS_ACE_MID
        }
        if (model.startsWith("CPH") || model.startsWith("PH") || model.startsWith("PJ") ||
            model.startsWith("PLK") || model.contains("ONEPLUS")) {
            return DeviceProfile.FLAGSHIP_XAXIS
        }
    }

    if (manufacturer == "samsung") {
        if (model.contains("SM-S93") || model.contains("SM-S92")) {
            return DeviceProfile.SAMSUNG_S25
        }
        if (model.contains("SM-S") || model.contains("SM-F")) {
            return DeviceProfile.FLAGSHIP_XAXIS
        }
    }

    if (manufacturer == "google" && model.contains("PIXEL")) {
        return DeviceProfile.FLAGSHIP_XAXIS
    }

    if (manufacturer == "vivo" || manufacturer == "iqoo") {
        if (model.contains("V24") || model.contains("V23") ||
            device.contains("pd24") || device.contains("pd23")) {
            return DeviceProfile.VIVO_FLAGSHIP
        }
        if (model.startsWith("V") || model.contains("IQOO")) {
            return DeviceProfile.FLAGSHIP_XAXIS
        }
    }

    if (manufacturer == "huawei" || manufacturer == "honor") {
        if (model.contains("BNE") || model.contains("ALA") || model.contains("MAS")) {
            return DeviceProfile.FLAGSHIP_XAXIS
        }
    }

    if (Build.VERSION.SDK_INT >= 33) {
        return DeviceProfile.FLAGSHIP_XAXIS
    }

    return DeviceProfile.DEFAULT
}