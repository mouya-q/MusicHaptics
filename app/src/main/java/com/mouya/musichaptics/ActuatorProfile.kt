package com.mouya.musichaptics

data class ActuatorProfile(
    val resonanceFreq: Float,
    val dampingRatio: Float,
    val riseTimeMs: Float,
    val fallTimeMs: Float,
    val maxDisplacement: Float,
    val qFactor: Float,
    val thermalResistance: Float = 25f,
    val thermalCapacitance: Float = 1.2f
) {
    val angularFreq: Float
        get() = 2f * Math.PI.toFloat() * resonanceFreq

    val riseScale: Float
        get() = (riseTimeMs / 4.5f).coerceIn(0.8f, 3.0f)

    val fallScale: Float
        get() = (fallTimeMs / 8.0f).coerceIn(0.8f, 3.0f)

    val responseScale: Float
        get() = (riseScale + fallScale) * 0.5f

    val responseTimeMs: Float
        get() = (riseTimeMs + fallTimeMs) * 0.5f

    val maxAmplitude: Float
        get() = maxDisplacement

    companion object {
        val XIAOMI_13_XAXIS = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.033f,
            riseTimeMs = 3.5f,
            fallTimeMs = 5.0f,
            maxDisplacement = 1.0f,
            qFactor = 15f,
            thermalResistance = 22f
        )

        val XIAOMI_10_0809 = ActuatorProfile(
            resonanceFreq = 190f,
            dampingRatio = 0.031f,
            riseTimeMs = 4.5f,
            fallTimeMs = 7.0f,
            maxDisplacement = 0.95f,
            qFactor = 16f,
            thermalResistance = 25f
        )

        val OPPO_RENO8_PRO = ActuatorProfile(
            resonanceFreq = 170f,  // ELA0809 标称共振
            dampingRatio = 0.038f,  // 入门级ELA → 较高阻尼, 低Q
            riseTimeMs = 5.5f,  // 启停~10ms → 电气上升~5.5ms
            fallTimeMs = 8.0f,  // 余震偏长
            maxDisplacement = 0.75f,  // 252mm³ 小体积, 振量较弱
            qFactor = 12f,  // 入门级Q值
            thermalResistance = 28f  // 小体积散热差
        )

        val REDMI_K80U_ZAXIS = ActuatorProfile(
            resonanceFreq = 160f,
            dampingRatio = 0.050f,
            riseTimeMs = 10.0f,
            fallTimeMs = 15.0f,
            maxDisplacement = 0.85f,
            qFactor = 10f,
            thermalResistance = 30f
        )

        val FLAGSHIP_XAXIS = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.030f,
            riseTimeMs = 3.0f,
            fallTimeMs = 4.5f,
            maxDisplacement = 1.0f,
            qFactor = 17f,
            thermalResistance = 20f
        )

        val ONEPLUS_13T = ActuatorProfile(
            resonanceFreq = 190f,
            dampingRatio = 0.035f,
            riseTimeMs = 3.5f,
            fallTimeMs = 5.5f,
            maxDisplacement = 1.0f,
            qFactor = 14f,
            thermalResistance = 22f
        )

        val ONEPLUS_15 = ActuatorProfile(
            resonanceFreq = 130f,  // 0816 ESA 标称共振130Hz
            dampingRatio = 0.028f,  // ESA超宽频 → 低阻尼, 高Q
            riseTimeMs = 2.5f,  // ESA超快启停
            fallTimeMs = 4.0f,
            maxDisplacement = 1.1f,  // 448mm³, 瞬态+82%
            qFactor = 18f,  // 0816 高Q, 跳跳糖风险
            thermalResistance = 20f
        )

        // v3.10.20: OnePlus 全系适配 + ColorOS 深度适配

        val ONEPLUS_11 = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.034f,
            riseTimeMs = 5.0f,  // N52基础版偏慢
            fallTimeMs = 7.0f,
            maxDisplacement = 1.0f,  // 602mm³ 超大体积
            qFactor = 14f,
            thermalResistance = 20f
        )

        val ONEPLUS_12 = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.032f,
            riseTimeMs = 3.5f,
            fallTimeMs = 5.0f,
            maxDisplacement = 1.0f,
            qFactor = 15f,
            thermalResistance = 20f
        )

        val ONEPLUS_13 = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.030f,
            riseTimeMs = 3.0f,  // 旗舰级快速启停
            fallTimeMs = 4.5f,
            maxDisplacement = 1.0f,
            qFactor = 17f,  // 高Q, 清脆
            thermalResistance = 20f
        )

        val ONEPLUS_ACE3PRO = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.032f,
            riseTimeMs = 3.5f,
            fallTimeMs = 5.0f,
            maxDisplacement = 1.0f,
            qFactor = 15f,
            thermalResistance = 20f
        )

        val ONEPLUS_ACE_MID = ActuatorProfile(
            resonanceFreq = 190f,
            dampingRatio = 0.038f,
            riseTimeMs = 5.0f,
            fallTimeMs = 7.5f,
            maxDisplacement = 0.85f,  // 0809 体积小
            qFactor = 12f,
            thermalResistance = 25f
        )

        // v3.11: 拯救者 Y700 二代 — 0815 X轴线性马达

        val LENOVO_Y700 = ActuatorProfile(
            resonanceFreq = 200f,  // 0815 标称共振
            dampingRatio = 0.032f,  // 较低阻尼 → 高Q
            riseTimeMs = 5.0f,  // 0815 启停~5ms
            fallTimeMs = 7.0f,
            maxDisplacement = 0.92f,  // 360mm³, 振量中等偏上
            qFactor = 15f,  // 0815 高Q窄带共振
            thermalResistance = 24f
        )

        // v3.10.20: 小米澎湃 HyperOS RichFeel 深度适配

        val XIAOMI_ULTRA_RICHFEEL = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.029f,  // 极低阻尼 → 极脆
            riseTimeMs = 2.5f,  // 4ms启停 → 2.5ms电气上升
            fallTimeMs = 4.0f,  // 快速止振, 无拖尾
            maxDisplacement = 1.0f,
            qFactor = 18f,  // 超高Q → 极清脆
            thermalResistance = 19f
        )

        val SAMSUNG_S25 = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.032f,
            riseTimeMs = 3.2f,
            fallTimeMs = 5.0f,
            maxDisplacement = 0.95f,
            qFactor = 15f,
            thermalResistance = 22f
        )

        val XIAOMI_11 = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.035f,
            riseTimeMs = 5.0f,
            fallTimeMs = 7.0f,
            maxDisplacement = 0.92f,
            qFactor = 14f,
            thermalResistance = 24f
        )

        val XIAOMI_12 = ActuatorProfile(
            resonanceFreq = 190f,
            dampingRatio = 0.036f,
            riseTimeMs = 5.5f,
            fallTimeMs = 8.0f,
            maxDisplacement = 0.88f,
            qFactor = 13f,
            thermalResistance = 25f
        )

        val XIAOMI_14 = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.030f,
            riseTimeMs = 2.8f,
            fallTimeMs = 4.5f,
            maxDisplacement = 1.0f,
            qFactor = 17f,
            thermalResistance = 20f
        )

        val REDMI_K70U = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.034f,
            riseTimeMs = 4.5f,
            fallTimeMs = 6.5f,
            maxDisplacement = 0.90f,
            qFactor = 14f,
            thermalResistance = 23f
        )

        val XIAOMI_15 = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.031f,
            riseTimeMs = 3.2f,
            fallTimeMs = 4.8f,
            maxDisplacement = 1.0f,
            qFactor = 16f,
            thermalResistance = 21f
        )

        val VIVO_FLAGSHIP = ActuatorProfile(
            resonanceFreq = 190f,
            dampingRatio = 0.034f,
            riseTimeMs = 3.5f,
            fallTimeMs = 5.2f,
            maxDisplacement = 0.95f,
            qFactor = 14f,
            thermalResistance = 23f
        )

        // v3.13: 全机型适配扩展 — 小米/红米/一加全系马达参数

        val ESA1016_CYBER = ActuatorProfile(
            resonanceFreq = 130f,  // CyberEngine 独有130Hz低谐振
            dampingRatio = 0.025f,  // 超低阻尼 → 超宽频
            riseTimeMs = 2.5f,  // ESA超快启停
            fallTimeMs = 4.0f,
            maxDisplacement = 1.1f,  // 560mm³, 稳态3倍于0809
            qFactor = 20f,  // 超高Q, 超宽频
            thermalResistance = 18f,  // v3.14: ESA1016 大体积散热好
            thermalCapacitance = 2.0f  // v3.14: ESA1016 热惯性高, 升温慢
        )

        val LRA_0815_GEN = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.033f,
            riseTimeMs = 4.5f,
            fallTimeMs = 6.5f,
            maxDisplacement = 0.92f,  // 360mm³
            qFactor = 15f,
            thermalResistance = 23f
        )

        val ONEPLUS_10PRO_LRA = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.031f,  // 低阻尼, 更脆
            riseTimeMs = 3.5f,  // 定制版更快启停
            fallTimeMs = 5.5f,
            maxDisplacement = 1.05f,  // +47%振动量
            qFactor = 16f,
            thermalResistance = 21f
        )

        val REDMI_0809_STD = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.035f,
            riseTimeMs = 4.5f,
            fallTimeMs = 6.5f,
            maxDisplacement = 0.88f,  // 252mm³, 中等
            qFactor = 13f,
            thermalResistance = 25f
        )

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