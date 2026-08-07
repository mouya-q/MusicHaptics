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

        /** OnePlus 13T X-axis LRA — ~190Hz, fast response, rich haptic */
        val ONEPLUS_13T = ActuatorProfile(
            resonanceFreq = 190f,
            dampingRatio = 0.035f,
            riseTimeMs = 3.5f,
            fallTimeMs = 5.5f,
            maxDisplacement = 1.0f,
            qFactor = 14f,
            thermalResistance = 22f
        )

        /** OnePlus 15 — 0816 ESA超宽频 X轴线性马达 (v3.11精确参数)
         *  瑞声科技第三代ESA, 448mm³, 共振~130Hz
         *  瞬态振动量提升82%, 超宽频响
         *  高Q+超宽频 → 跳跳糖风险高, 需更强inter-frame平滑
         *  低共振频率(130Hz) → 低频纹理更丰富 */
        val ONEPLUS_15 = ActuatorProfile(
            resonanceFreq = 130f,      // 0816 ESA 标称共振130Hz
            dampingRatio = 0.028f,      // ESA超宽频 → 低阻尼, 高Q
            riseTimeMs = 2.5f,          // ESA超快启停
            fallTimeMs = 4.0f,
            maxDisplacement = 1.1f,     // 448mm³, 瞬态+82%
            qFactor = 18f,              // 0816 高Q, 跳跳糖风险
            thermalResistance = 20f
        )

        // ════════════════════════════════════════════════════════════════
        // v3.10.20: OnePlus 全系适配 + ColorOS 深度适配
        // ════════════════════════════════════════════════════════════════

        /** OnePlus 11 — CSA0916 N52, 602mm³, ~10ms启停, 50-500Hz频宽
         *  N52磁钢基础版, 启停偏慢(10ms), 但振量大, 低频有力 */
        val ONEPLUS_11 = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.034f,
            riseTimeMs = 5.0f,    // N52基础版偏慢
            fallTimeMs = 7.0f,
            maxDisplacement = 1.0f, // 602mm³ 超大体积
            qFactor = 14f,
            thermalResistance = 20f
        )

        /** OnePlus 12 — CSA0916 Turbo, N54磁钢+CSA+, 602mm³
         *  N54磁王材料, 启停更快, 频宽更宽, 瞬态更硬 */
        val ONEPLUS_12 = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.032f,
            riseTimeMs = 3.5f,
            fallTimeMs = 5.0f,
            maxDisplacement = 1.0f,
            qFactor = 15f,
            thermalResistance = 20f
        )

        /** OnePlus 13 — CSA+0916 "仿生振感马达Turbo", 602mm³
         *  ColorOS 15调校, 72种O-Haptics效果, 最强振动量/带宽/启停 */
        val ONEPLUS_13 = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.030f,
            riseTimeMs = 3.0f,    // 旗舰级快速启停
            fallTimeMs = 4.5f,
            maxDisplacement = 1.0f,
            qFactor = 17f,        // 高Q, 清脆
            thermalResistance = 20f
        )

        /** OnePlus Ace3 Pro — CSA0916 Turbo (同一加12), 0916规格 */
        val ONEPLUS_ACE3PRO = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.032f,
            riseTimeMs = 3.5f,
            fallTimeMs = 5.0f,
            maxDisplacement = 1.0f,
            qFactor = 15f,
            thermalResistance = 20f
        )

        /** OnePlus Ace3 / Ace5 — 0809A X轴, 中端马达
         *  体积小, 振量中等, 启停略慢, 适合温和参数 */
        val ONEPLUS_ACE_MID = ActuatorProfile(
            resonanceFreq = 190f,
            dampingRatio = 0.038f,
            riseTimeMs = 5.0f,
            fallTimeMs = 7.5f,
            maxDisplacement = 0.85f, // 0809 体积小
            qFactor = 12f,
            thermalResistance = 25f
        )

        // ════════════════════════════════════════════════════════════════
        // v3.11: 拯救者 Y700 二代 — 0815 X轴线性马达
        // ════════════════════════════════════════════════════════════════

        /** 拯救者Y700二代 — 0815 X轴线性马达
         *  360mm³, 共振~200Hz, 频宽107-400Hz
         *  瞬态振动量2.21, 稳态1.18
         *  高Q特性: 窄带共振, 但频响较宽(107-400Hz)
         *  启停较快(~5ms), 适合纹理类振动
         *  注意: 非双马达, 实际为单颗0815 */
        val LENOVO_Y700 = ActuatorProfile(
            resonanceFreq = 200f,      // 0815 标称共振
            dampingRatio = 0.032f,     // 较低阻尼 → 高Q
            riseTimeMs = 5.0f,         // 0815 启停~5ms
            fallTimeMs = 7.0f,
            maxDisplacement = 0.92f,   // 360mm³, 振量中等偏上
            qFactor = 15f,             // 0815 高Q窄带共振
            thermalResistance = 24f
        )

        // ════════════════════════════════════════════════════════════════
        // v3.10.20: 小米澎湃 HyperOS RichFeel 深度适配
        // ════════════════════════════════════════════════════════════════

        /** 小米14/15 Ultra — ESA1016 超宽频马达, RichFeel引擎
         *  10-500Hz频宽, 4ms启停, 毫秒级响应延迟
         *  HyperOS触觉2.0: 上升/衰减曲线自研优化, 无拖尾 */
        val XIAOMI_ULTRA_RICHFEEL = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.029f,  // 极低阻尼 → 极脆
            riseTimeMs = 2.5f,     // 4ms启停 → 2.5ms电气上升
            fallTimeMs = 4.0f,     // 快速止振, 无拖尾
            maxDisplacement = 1.0f,
            qFactor = 18f,         // 超高Q → 极清脆
            thermalResistance = 19f
        )

        /** Samsung Galaxy S25 系列 X轴 LRA — ~200Hz, 精准, Samsung 调校 */
        val SAMSUNG_S25 = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.032f,
            riseTimeMs = 3.2f,
            fallTimeMs = 5.0f,
            maxDisplacement = 0.95f,
            qFactor = 15f,
            thermalResistance = 22f
        )

        /** Xiaomi 15 系列 X轴 LRA — ~200Hz, 快, RichFeel 引擎 */
        val XIAOMI_15 = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.031f,
            riseTimeMs = 3.2f,
            fallTimeMs = 4.8f,
            maxDisplacement = 1.0f,
            qFactor = 16f,
            thermalResistance = 21f
        )

        /** vivo/iQOO 旗舰 X轴 LRA — ~190Hz, X轴, 瞬态强 */
        val VIVO_FLAGSHIP = ActuatorProfile(
            resonanceFreq = 190f,
            dampingRatio = 0.034f,
            riseTimeMs = 3.5f,
            fallTimeMs = 5.2f,
            maxDisplacement = 0.95f,
            qFactor = 14f,
            thermalResistance = 23f
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