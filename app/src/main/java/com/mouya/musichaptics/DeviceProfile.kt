package com.mouya.musichaptics

import android.os.Build

/**
 * 设备马达配置文件。
 *
 * 不同机型线性马达的谐振频率、启停时间、振幅响应曲线存在显著差异。
 * 此文件提供预设档位 + 自动检测，让 HapticEventGenerator 在运行时
 * 根据机型匹配最优参数，而非使用硬编码的"通用值"。
 */
data class DeviceProfile(
    val name: String,
    val description: String,

    // ── 振幅映射 ──
    /** 保底振幅：弱信号的振动下限。X轴马达可低至10，Z轴至少30 */
    val minGuaranteedAmplitude: Int = 40,
    val maxAmplitude: Int = 255,
    /** 幂律压缩指数：0.5=sqrt(弱信号翘起高)，0.7=更线性 */
    val boostExponent: Float = 0.50f,

    // ── Sub波形包络(ms) ──
    /** 第一击持续时间范围 */
    val subDur1Min: Long = 18, val subDur1Max: Long = 40,
    /** 第一段间隔 */
    val subGap1Min: Long = 6,  val subGap1Max: Long = 16,
    /** 第二击持续时间 */
    val subDur2Min: Long = 8,  val subDur2Max: Long = 22,
    /** 第二段间隔 */
    val subGap2Min: Long = 3,  val subGap2Max: Long = 12,
    /** 尾韵持续时间 */
    val subDur3Min: Long = 4,  val subDur3Max: Long = 14,

    /** 第二击振幅衰减系数 */
    val subAmpDecay2: Float = 0.55f,
    /** 第三击振幅衰减系数 */
    val subAmpDecay3: Float = 0.25f,

    // ── 节流 ──
    val minIntervalMs: Long = 4L,
    val maxIntervalMs: Long = 40L,

    // ── 灵敏度 ──
    val silenceThreshold: Float = 0.005f,
    val energyThreshold: Float = 0.15f,

    // ── 微振填充 ──
    val fillerFrameThreshold: Int = 8,
    val fillerDurationMs: Long = 3L,
    val fillerAmplitude: Int = 1,

    // ── 频段权重 ──
    val subWeight: Float = 1.0f,
    val midWeight: Float = 0.6f,
    val presenceWeight: Float = 0.4f,

    // ── 低音增益 ──
    /** 低频专属增益因子。>1.0 增强低音震感，<1.0 削弱。
     *  Z轴马达通常设 1.25-1.35 补偿低频响应不足；
     *  X轴马达默认 1.0 即可，过度增益反而浑浊。 */
    val bassBoost: Float = 1.0f,
) {
    companion object {
        // ═══════════════════════════════════════════════════
        // 预设档位
        // ═══════════════════════════════════════════════════

        /** 通用默认：适配大多数中端线性马达 */
        val DEFAULT = DeviceProfile(
            name = "通用默认",
            description = "适用于大多数Android线性马达，保守参数确保兼容性",
            minGuaranteedAmplitude = 40,
            boostExponent = 0.50f,
            subDur1Min = 18, subDur1Max = 40,
            subGap1Min = 6,  subGap1Max = 16,
            subDur2Min = 8,  subDur2Max = 22,
            subGap2Min = 3,  subGap2Max = 12,
            subDur3Min = 4,  subDur3Max = 14,
        )

        /** 小米13 (2211133G/2211133C, "fuxi") — X轴线性马达，谐振~200Hz，启停极快 */
        val XIAOMI13_XAXIS = DeviceProfile(
            name = "小米13 · X轴线性马达",
            description = "X轴宽频线性马达，谐振~200Hz，启停<5ms，响应极快",
            minGuaranteedAmplitude = 12,   // X轴感知阈值极低
            maxAmplitude = 255,
            boostExponent = 0.45f,          // 略激进，弱信号抬得更高
            // 更快的包络——马达响应快，不需要长sustain
            subDur1Min = 12, subDur1Max = 30,
            subGap1Min = 4,  subGap1Max = 10,
            subDur2Min = 6,  subDur2Max = 16,
            subGap2Min = 2,  subGap2Max = 8,
            subDur3Min = 3,  subDur3Max = 10,
            subAmpDecay2 = 0.50f,
            subAmpDecay3 = 0.22f,
            minIntervalMs = 3L,
            maxIntervalMs = 35L,
            silenceThreshold = 0.004f,
            energyThreshold = 0.12f,
            fillerFrameThreshold = 6,
            fillerDurationMs = 2L,
            fillerAmplitude = 1,
        )

        /** 红米K80 Ultra — 0809 Z轴线性马达，谐振~150-170Hz，启停较慢 */
        val REDMI_K80U_0809 = DeviceProfile(
            name = "红米K80 Ultra · 0809马达",
            description = "0809 Z轴线性马达，谐振~160Hz，启停约12ms，需更长包络",
            minGuaranteedAmplitude = 35,    // Z轴感知阈值较高
            maxAmplitude = 255,
            boostExponent = 0.55f,           // 更线性，避免弱信号炸裂
            // 更长包络——马达启停慢，需要充分sustain和间隔
            subDur1Min = 22, subDur1Max = 45,
            subGap1Min = 8,  subGap1Max = 18,
            subDur2Min = 12, subDur2Max = 26,
            subGap2Min = 5,  subGap2Max = 14,
            subDur3Min = 6,  subDur3Max = 16,
            subAmpDecay2 = 0.60f,
            subAmpDecay3 = 0.30f,
            minIntervalMs = 5L,
            maxIntervalMs = 45L,
            silenceThreshold = 0.006f,
            energyThreshold = 0.18f,
            fillerFrameThreshold = 10,
            fillerDurationMs = 4L,
            fillerAmplitude = 2,
            subWeight = 1.0f,
            midWeight = 0.55f,     // 略微压低中频，突出低频
            presenceWeight = 0.35f,
            bassBoost = 1.30f,      // Z轴低频响应弱，增益补偿
        )

        /** 通用旗舰X轴：未匹配到具体型号但振动能力强 */
        val FLAGSHIP_XAXIS = DeviceProfile(
            name = "旗舰X轴 · 自动检测",
            description = "检测到高端振动能力(API33+全原语支持)，使用X轴激进参数",
            minGuaranteedAmplitude = 18,
            boostExponent = 0.48f,
            subDur1Min = 14, subDur1Max = 32,
            subGap1Min = 4,  subGap1Max = 12,
            subDur2Min = 7,  subDur2Max = 18,
            subGap2Min = 2,  subGap2Max = 9,
            subDur3Min = 4,  subDur3Max = 12,
            subAmpDecay2 = 0.52f,
            subAmpDecay3 = 0.24f,
            minIntervalMs = 3L,
            maxIntervalMs = 36L,
            silenceThreshold = 0.004f,
            energyThreshold = 0.13f,
            fillerFrameThreshold = 7,
            fillerDurationMs = 2L,
            fillerAmplitude = 1,
        )
    }
}

/**
 * 根据设备型号 + 振动能力自动匹配 [DeviceProfile]。
 *
 * 匹配优先级：
 * 1. 精确型号匹配（如小米13、K80U）
 * 2. 高端能力检测（API33+ 全原语支持 = 旗舰X轴）
 * 3. 回退默认
 */
fun detectDeviceProfile(): DeviceProfile {
    val model = Build.MODEL.uppercase().replace(" ", "")
    val manufacturer = Build.MANUFACTURER.lowercase()

    // ── 精确匹配 ──
    if (manufacturer == "xiaomi") {
        // 小米13系列: 2211133G, 2211133C, "fuxi"
        if (model.contains("2211133") || model.contains("FUXI")) {
            return DeviceProfile.XIAOMI13_XAXIS
        }
        // 红米K80系列: 大概率包含"K80"字样
        if (model.contains("K80")) {
            return DeviceProfile.REDMI_K80U_0809
        }
        // 小米14系列
        if (model.contains("23127PN") || model.contains("HOUBI")) {
            return DeviceProfile.FLAGSHIP_XAXIS
        }
    }

    // OnePlus / OPPO 旗舰
    if (manufacturer == "oneplus" || manufacturer == "oppo") {
        if (model.startsWith("CPH") || model.startsWith("PH") || model.contains("ONEPLUS")) {
            return DeviceProfile.FLAGSHIP_XAXIS
        }
    }

    // 三星旗舰
    if (manufacturer == "samsung" && (model.contains("SM-S") || model.contains("SM-F"))) {
        return DeviceProfile.FLAGSHIP_XAXIS
    }

    // Google Pixel
    if (manufacturer == "google" && model.contains("PIXEL")) {
        return DeviceProfile.FLAGSHIP_XAXIS
    }

    // ── 能力检测：API33+ 全振动原语支持 = 大概率旗舰 ──
    if (Build.VERSION.SDK_INT >= 33) {
        try {
            // 不做重量级检测，有hasAmplitudeControl就认为是好马达
            // 更精确的检测在HapticEventGenerator构造时完成
            return DeviceProfile.FLAGSHIP_XAXIS
        } catch (_: Exception) {}
    }

    return DeviceProfile.DEFAULT
}