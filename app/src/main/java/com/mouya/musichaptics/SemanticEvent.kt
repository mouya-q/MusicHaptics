package com.mouya.musichaptics

data class SemanticEvent(

    val label: String,

    val strength: Float,

    val confidence: Float,

    val timestampMs: Long,

    val bandType: SemanticBand,

    val pitch: Float = 0f
)

enum class SemanticBand {
    SUB_BASS,
    BASS,
    MID,
    HIGH_MID,
    TREBLE,
    FULL_BAND
}
