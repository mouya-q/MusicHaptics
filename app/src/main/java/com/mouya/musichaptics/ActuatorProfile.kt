package com.mouya.musichaptics

/**
 * ActuatorProfile — 设备线性马达（LRA）物理参数模型
 *
 * 不同设备的 LRA 硬件特性差异巨大：
 * - X轴宽频 LRA（如小米13）：f0≈200Hz, 快响应, 高Q值
 * - Z轴 LRA（如Redmi K80U）：f0≈160Hz, 慢响应, 需要更长包络
 * - 旗舰 X轴（Pixel/Samsung）：f0≈200Hz, 极快响应
 *
 * 这些参数驱动 HapticComposer 中的二阶弹簧-阻尼物理模型，
 * 以及 ADSR 包络的预补偿（对慢响应设备延长 attack 时间）。
 *
 * @param resonanceFreq   LRA 谐振频率 (Hz) — 马达自然振动频率
 * @param dampingRatio    阻尼比 ζ — 越小越"脆"（余震长），越大越"闷"（快速止振）
 * @param responseTimeMs   启动响应时间 (ms) — 从驱动到达到满幅所需时间
 * @param maxAmplitude     最大归一化振幅 (0-1) — 硬件最大输出能力
 * @param qFactor          品质因数 Q — 带宽 = f0/Q，高Q=窄带=锐利共振
 */
data class ActuatorProfile(
    val resonanceFreq: Float,
    val dampingRatio: Float,
    val responseTimeMs: Float,
    val maxAmplitude: Float,
    val qFactor: Float
) {
    /** 角频率 ω₀ = 2π × f₀ */
    val angularFreq: Float
        get() = 2f * Math.PI.toFloat() * resonanceFreq

    companion object {
        /** 小米13 X轴宽频LRA — 200Hz, 快速, 高Q */
        val XIAOMI_13_XAXIS = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.033f,   // Q≈15
            responseTimeMs = 4.0f,
            maxAmplitude = 1.0f,
            qFactor = 15f
        )

        /** 小米10系列 0809 X轴LRA — 190Hz, 快速 */
        val XIAOMI_10_0809 = ActuatorProfile(
            resonanceFreq = 190f,
            dampingRatio = 0.031f,   // Q≈16
            responseTimeMs = 5.0f,
            maxAmplitude = 0.95f,
            qFactor = 16f
        )

        /** Redmi K80U 0809 Z轴LRA — 160Hz, 慢响应 */
        val REDMI_K80U_ZAXIS = ActuatorProfile(
            resonanceFreq = 160f,
            dampingRatio = 0.050f,   // Q≈10, more damped
            responseTimeMs = 12.0f,
            maxAmplitude = 0.85f,
            qFactor = 10f
        )

        /** 旗舰 X轴自动检测 — 200Hz, 极快 */
        val FLAGSHIP_XAXIS = ActuatorProfile(
            resonanceFreq = 200f,
            dampingRatio = 0.030f,
            responseTimeMs = 3.5f,
            maxAmplitude = 1.0f,
            qFactor = 17f
        )

        /** 通用默认 — 保守参数，适配未知设备 */
        val DEFAULT = ActuatorProfile(
            resonanceFreq = 180f,
            dampingRatio = 0.060f,
            responseTimeMs = 8.0f,
            maxAmplitude = 0.80f,
            qFactor = 8f
        )
    }
}
