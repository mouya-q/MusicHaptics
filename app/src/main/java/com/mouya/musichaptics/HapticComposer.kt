package com.mouya.musichaptics

/**
 * How a key strike was classified by the semantic analyzer.
 *
 * This file is the single source of truth for the command vocabulary shared by
 * [HapticEventGenerator] and [HapticSynthesizer]. It was lost while the 5.x
 * tree was rebuilt from the 4.23 tree, which left every consumer of
 * [HapticCommand] / [KeyStrikeSemantic] / [SemanticType] failing to compile.
 * The definitions below are byte-for-byte the 4.23 ones, because the 5.x call
 * sites were written against them.
 */
enum class KeyStrikeSemantic {
    NONE, SUB_STRIKE, KICK_DRUM, SNARE_ACCENT, RHYTHM_PATTERN, BASS_GHOST, KEY_STRIKE
}

/** Broad spectral character of the current frame, used to pick a waveform. */
enum class SemanticType {
    BALANCED, DEEP_BASS, KICK_BASS, MID_PUNCH, TEXTURE_DETAIL, VOCAL, HARMONIC
}

/**
 * One fully resolved haptic command handed from the event generator to the
 * synthesizer. Every field is pre-computed upstream so the synthesizer stays
 * allocation-free on the audio thread.
 */
data class HapticCommand(
    val timestamp: Long,
    val intensity: Float,
    val pitch: Float,
    val isBeat: Boolean,
    val isKeyStrike: Boolean,
    val isTransient: Boolean,
    val keyStrikeSemantic: KeyStrikeSemantic,
    val semanticType: SemanticType,
    val adsrEnvelope: Float,
    val thermalGain: Float,
    val bassComponent: Float,
    val textureComponent: Float
)