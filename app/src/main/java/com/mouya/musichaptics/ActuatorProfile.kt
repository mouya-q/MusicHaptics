package com.mouya.musichaptics

/**
 * ActuatorProfile — 设备线性马达（LRA）物理参数模型 (v2.0)
 *
 * 不同设备的 LRA 硬件特性差异巨大：
 * - X轴宽频 LRA（如小米13）：f0≈200Hz, 快上升, 硬瞬态, 高Q
 * - Z轴 LRA（如Redmi K80U）：f0≈160Hz, 慢上升, 柔余震, 低Q
 * - 旗舰 X轴（Pixel/Samsung）：f0≈200Hz, 极快, 精准控制
 *
 * v2.0 升级：分离 riseTime / fallTime，更真实地建模 LRA 物理特性。
 * 两个同为 200Hz 的设备，Q factor + damping + rise/fall 差异
 * 决定了"硬/瞬态强" vs "柔/余震长"的触觉风格。
 *
 * @param resonanceFreq      LRA 谐振频率 (Hz)
 * @param dampingRatio       阻尼比 ζ — 越小越"脆"（余震长），越大越"闷"（快速止振）
 * @param riseTimeMs         上升时间 (ms) — 从驱动到达到满幅的时间（瞬态硬度）
 * @param fallTimeMs         下降时间 (ms) — 从断电到止振的时间（余震长度）
 * @param maxDisplacement    最大归一化位移 (0-1) — 硬件物理极限
 * @param qFactor            品质因数 Q — 带宽 = f0/Q
 * @param thermalResistance  热阻 (°C/W) — 线圈散热能力，影响持续输出降额
 */
data class ActuatorProfile(
    val resonanceFreq: Float,
    val dampingRatio: Float,
    val riseTimeMs: Float,
    val fallTimeMs: Float,
    val maxDisplacement: Float,
    val qFactor: Float,
    val thermalResistance: Float = 25f
) {
    /** 角频率 ω₀ = 2π × f₀ */
    val angularFreq: Float
        get() = 2f * Math.PI.toFloat() * resonanceFreq

    /** 上升时间归一化缩放因子 (1.0 = 基准 4.5ms) */
    val riseScale: Float
        get() = (riseTimeMs / 4.5f).coerceIn(0.8f, 3.0f)

    /** 下降时间归一化缩放因子 (1.0 = 基准 8.0ms) */
    val fallScale: Float
        get() = (fallTimeMs / 8.0f).coerceIn(0.8f, 3.0f)

    /** 综合响应缩放（取 rise/fall 平均，用于 ADSR tau 补偿） */
    val responseScale: Float
        get() = (riseScale + fallScale) * 0.5f

    /** 向后兼容：旧代码引用 responseTimeMs */
    val responseTimeMs: Float
        get() = (riseTimeMs + fallTimeMs) * 0.5f

    /** 向后兼容：旧代码引用 maxAmplitude */
    val maxAmplitude: Float
        get() = maxDisplacement

    companion object {
        /** 小米13 X轴宽频LRA — 200Hz, 快上升/快止振, 硬瞬态, 高Q */
        val XIAOMI_13_XAXIS = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.033f,
            riseTimeMs = 3.5f,
            fallTimeMs = 5.0f,
            maxDisplacement = 1.0f,
            qFactor = 15f,
            thermalResistance = 22f
        )

        /** 小米10系列 0809 X轴LRA — 190Hz, 快, 略柔 */
        val XIAOMI_10_0809 = ActuatorProfile(
            resonanceFreq = 190f,
            dampingRatio = 0.031f,
            riseTimeMs = 4.5f,
            fallTimeMs = 7.0f,
            maxDisplacement = 0.95f,
            qFactor = 16f,
            thermalResistance = 25f
        )

        /** Redmi K80U 0809 Z轴LRA — 160Hz, 慢上升/长余震, 柔 */
        val REDMI_K80U_ZAXIS = ActuatorProfile(
            resonanceFreq = 160f,
            dampingRatio = 0.050f,
            riseTimeMs = 10.0f,
            fallTimeMs = 15.0f,
            maxDisplacement = 0.85f,
            qFactor = 10f,
            thermalResistance = 30f
        )

        /** 旗舰 X轴自动检测 — 200Hz, 极快, 精准 */
        val FLAGSHIP_XAXIS = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.030f,
            riseTimeMs = 3.0f,
            fallTimeMs = 4.5f,
            maxDisplacement = 1.0f,
            qFactor = 17f,
            thermalResistance = 20f
        )

        /** 通用默认 — 保守参数，适配未知设备 */
        val DEFAULT = ActuatorProfile(
            resonanceFreq = 180f,
            dampingRatio = 0.060f,
            riseTimeMs = 7.0f,
            fallTimeMs = 10.0f,
            maxDisplacement = 0.80f,
            qFactor = 8f,
            thermalResistance = 28f
        )
    }
}