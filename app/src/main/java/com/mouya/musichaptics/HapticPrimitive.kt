package com.mouya.musichaptics

/**
 * ════════════════════════════════════════════════════════════════════
 *  HapticPrimitive — 触觉原语 (v3.0)
 *
 *  从音乐语义事件 → 人格映射 → 生成的独立触觉原语。
 *  每个原语携带完整的物理参数，可直接驱动 HapticSynthesizer。
 * ════════════════════════════════════════════════════════════════════
 */
sealed class HapticPrimitive {

    /** 原语类型名（用于 Dashboard 显示 & 日志） */
    abstract val typeName: String

    /**
     * IMPACT — 瞬时冲击
     *
     * 鼓击、贝斯重音、军鼓 etc.
     * 特点：短促、高能量、锐利。
     */
    data class Impact(
        val intensity: Int,          // 0..255
        val durationMs: Int,         // 振动持续时间 (10..40ms)
        val velocityFactor: Float,   // 力度因子 0.0..1.0（1.0=full velocity, 0.3=ghost）
        val sharpness: Float,        // 冲击锐度 0.0..1.0（0.9=snare, 0.3=bass）
        val semantic: String         // 语义标签（如 "KICK_DRUM"）
    ) : HapticPrimitive() {
        override val typeName: String = "IMPACT"
    }

    /**
     * PULSE — 短脉冲序列
     *
     * 快速节拍、打点、节奏型连续击打。
     */
    data class Pulse(
        val intensity: Int,          // 0..255
        val periodMs: Int,           // 脉冲间隔 (30..80ms)
        val repeatCount: Int,        // 重复次数 (2..6)
        val rhythmStrength: Float,    // 节奏强度 0.0..1.0
        val semantic: String         // 语义标签
    ) : HapticPrimitive() {
        override val typeName: String = "PULSE"
    }

    /**
     * TEXTURE — 持续纹理振动
     *
     * 人声、合成器 sustain、嗡嗡声。
     * 特点：连续、变化丰富、非冲击性。
     */
    data class Texture(
        val intensity: Int,          // 0..255
        val durationMs: Int,         // 持续时间 (100..500ms)
        val modulationDepth: Float,  // 调制深度 0.0..1.0（0.8=人声, 0.2=噪声）
        val frequencyMod: Float,     // 频率调制 0.0..1.0
        val semantic: String         // 语义标签
    ) : HapticPrimitive() {
        override val typeName: String = "TEXTURE"
    }

    /**
     * WAVE — 复杂波形振动
     *
     * 弦乐、钢琴 sustain、低频 drone。
     * 特点：由 amplitudeCurve 定义完整波形包络。
     */
    data class Wave(
        val durationMs: Int,         // 总时长 (50..1000ms)
        val gamma: Float,           // Gamma 压缩曲线系数 (0.3..0.8)
        val amplitudeCurve: FloatArray, // 归一化振幅曲线（每点 0..255，时间分辨率 ≈16ms/点）
        val semantic: String         // 语义标签
    ) : HapticPrimitive() {
        override val typeName: String = "WAVE"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Wave) return false
            return durationMs == other.durationMs &&
                    gamma == other.gamma &&
                    semantic == other.semantic &&
                    amplitudeCurve.contentEquals(other.amplitudeCurve)
        }

        override fun hashCode(): Int {
            var result = durationMs
            result = 31 * result + gamma.hashCode()
            result = 31 * result + amplitudeCurve.contentHashCode()
            result = 31 * result + semantic.hashCode()
            return result
        }
    }
}
