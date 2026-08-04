package com.mouya.musichaptics

sealed class HapticPrimitive {

    abstract val typeName: String

    data class Impact(
        val intensity: Int,
        val durationMs: Int,
        val velocityFactor: Float,
        val sharpness: Float,
        val semantic: String
    ) : HapticPrimitive() {
        override val typeName: String = "IMPACT"
    }

    data class Pulse(
        val intensity: Int,
        val periodMs: Int,
        val repeatCount: Int,
        val rhythmStrength: Float,
        val semantic: String
    ) : HapticPrimitive() {
        override val typeName: String = "PULSE"
    }

    data class Texture(
        val intensity: Int,
        val durationMs: Int,
        val modulationDepth: Float,
        val frequencyMod: Float,
        val semantic: String
    ) : HapticPrimitive() {
        override val typeName: String = "TEXTURE"
    }

    data class Wave(
        val durationMs: Int,
        val gamma: Float,
        val amplitudeCurve: FloatArray,
        val semantic: String
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
