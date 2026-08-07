package com.mouya.musichaptics

/**
 * v3.8 semantic analysis output from the native DSP filter bank.
 * Values are smoothed confidence estimates in [0, 1]. They deliberately
 * describe instrument families rather than pretending to perform stem separation.
 */
data class InstrumentFeatures(
    val kick: Float = 0f,
    val snare: Float = 0f,
    val hiHat: Float = 0f,
    val vocal: Float = 0f,
    val plucked: Float = 0f,
    val harmonic: Float = 0f,
    val bassSustain: Float = 0f,
    val pitchConfidence: Float = 0f,
    val vocalEnergy: Float = 0f,
    val airEnergy: Float = 0f
) {
    val dominantFamily: InstrumentFamily
        get() {
            val candidates = listOf(
                InstrumentFamily.KICK to kick,
                InstrumentFamily.SNARE to snare,
                InstrumentFamily.HI_HAT to hiHat,
                InstrumentFamily.VOCAL to vocal,
                InstrumentFamily.PLUCKED to plucked,
                InstrumentFamily.HARMONIC to harmonic,
                InstrumentFamily.BASS_SUSTAIN to bassSustain
            )
            val best = candidates.maxByOrNull { it.second } ?: return InstrumentFamily.NONE
            return if (best.second >= 0.35f) best.first else InstrumentFamily.NONE
        }
}

enum class InstrumentFamily {
    NONE, KICK, SNARE, HI_HAT, VOCAL, PLUCKED, HARMONIC, BASS_SUSTAIN
}
