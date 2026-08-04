package com.mouya.musichaptics

import android.os.Bundle

object TelemetryHub {

    @Volatile var subBassLevel: Float = 0f
    @Volatile var midBassLevel: Float = 0f
    @Volatile var presenceLevel: Float = 0f
    @Volatile var fundamentalFrequencyHz: Float = 150f
    @Volatile var coilTemperature: Float = 25f
    @Volatile var thermalAttenuation: Float = 1f
    @Volatile var frameLatencyMs: Long = 0L
    @Volatile var lowCutoffHz: Float = 160f
    @Volatile var highCutoffHz: Float = 350f
    @Volatile var userAmplitudeScale: Float = 1f
    @Volatile var ringBufferOverruns: Long = 0L
    @Volatile var dispatchedSubImpacts: Long = 0L
    @Volatile var dispatchedMidTransients: Long = 0L
    @Volatile var dispatchedMicroTextures: Long = 0L

    @Volatile var lraDisplacement: Float = 0f

    @Volatile var lraVelocity: Float = 0f

    @Volatile var lraForce: Float = 0f

    @Volatile var lraPhase: Float = 0f

    @Volatile var adsrEnvelope: Float = 0f

    @Volatile var thermalGain: Float = 1f

    @Volatile var keyStrikeActive: Boolean = false

    @Volatile var keyStrikeSemantic: String = "NONE"

    @Volatile var semanticType: String = "BALANCED"

    @Volatile var lastUpdateTimeMs: Long = 0L

    @Volatile var frameCount: Long = 0L

    @Volatile var personaName: String = "POP"
    @Volatile var primitiveType: String = ""
    @Volatile var primitiveSemantic: String = ""
    @Volatile var primitiveIntensity: Int = 0
    @Volatile var primitiveDuration: Int = 0
    @Volatile var gammaValue: Float = 0.5f

    fun applySnapshot(bundle: Bundle) {
        subBassLevel = bundle.getFloat("sub", 0f)
        midBassLevel = bundle.getFloat("mid", 0f)
        presenceLevel = bundle.getFloat("pres", 0f)
        fundamentalFrequencyHz = bundle.getFloat("f0", 150f)
        coilTemperature = bundle.getFloat("temp", 25f)
        thermalAttenuation = bundle.getFloat("atten", 1f)
        frameLatencyMs = bundle.getLong("latency", 0L)
        lowCutoffHz = bundle.getFloat("loFreq", 160f)
        highCutoffHz = bundle.getFloat("hiFreq", 350f)
        userAmplitudeScale = bundle.getFloat("ampScale", 1f)
        ringBufferOverruns = bundle.getLong("overruns", 0L)
        dispatchedSubImpacts = bundle.getLong("subCount", 0L)
        dispatchedMidTransients = bundle.getLong("midCount", 0L)
        dispatchedMicroTextures = bundle.getLong("texCount", 0L)

        lraDisplacement = bundle.getFloat("lraDisp", 0f)
        lraVelocity = bundle.getFloat("lraVel", 0f)
        lraForce = bundle.getFloat("lraForce", 0f)
        lraPhase = bundle.getFloat("lraPhase", 0f)
        adsrEnvelope = bundle.getFloat("adsrEnv", 0f)
        thermalGain = bundle.getFloat("thermalGain", 1f)
        keyStrikeActive = bundle.getBoolean("keyStrikeActive", bundle.getBoolean("keyStrike", false))
        keyStrikeSemantic = bundle.getString("keyStrikeSemantic")
            ?: bundle.getString("keySemantic")
            ?: "NONE"
        semanticType = bundle.getString("semanticType")
            ?: bundle.getString("semType")
            ?: "BALANCED"

        personaName = bundle.getString("personaName") ?: "POP"
        primitiveType = bundle.getString("primitiveType") ?: ""
        primitiveSemantic = bundle.getString("primitiveSemantic") ?: ""
        primitiveIntensity = bundle.getInt("primitiveIntensity", 0)
        primitiveDuration = bundle.getInt("primitiveDuration", 0)
        gammaValue = bundle.getFloat("gammaValue", 0.5f)

        lastUpdateTimeMs = bundle.getLong("time", 0L)
        frameCount++
    }
}
