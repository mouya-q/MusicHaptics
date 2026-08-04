package com.mouya.musichaptics

import android.content.Context
import android.content.Intent
import android.util.Log

object LogBroadcaster {

    const val ACTION_LOG = "com.mouya.musichaptics.ACTION_LOG"
    const val ACTION_TELEMETRY = "com.mouya.musichaptics.ACTION_TELEMETRY"
    const val EXTRA_LOG_MSG = "log_msg"

    private const val OWN_PACKAGE = "com.mouya.musichaptics"

    fun sendLog(context: Context, msg: String) {
        try {
            val intent = Intent(ACTION_LOG).apply {
                putExtra(EXTRA_LOG_MSG, msg)

                setPackage(OWN_PACKAGE)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e("LogBroadcaster", "Failed to broadcast log: ${e.message}")
        }
    }

    fun sendTelemetry(
        context: Context,
        sub: Float,
        mid: Float,
        pres: Float,
        f0: Float,
        temp: Float,
        atten: Float,
        latency: Long,
        loFreq: Float,
        hiFreq: Float,
        ampScale: Float,
        overruns: Long,
        subCount: Long,
        midCount: Long,
        texCount: Long,

        keyStrikeActive: Boolean = false,
        keyStrikeSemantic: String = "NONE",
        semanticType: String = "BALANCED",

        lraDisp: Float = 0f,
        lraVel: Float = 0f,
        lraForce: Float = 0f,
        lraPhase: Float = 0f,
        adsrEnv: Float = 0f,
        thermalGain: Float = 1f,

        personaName: String = "POP",
        primitiveType: String = "",
        primitiveSemantic: String = "",
        primitiveIntensity: Int = 0,
        primitiveDuration: Int = 0,
        gammaValue: Float = 0.5f
    ) {
        try {
            val intent = Intent(ACTION_TELEMETRY).apply {
                setPackage(OWN_PACKAGE)
                putExtra("sub", sub)
                putExtra("mid", mid)
                putExtra("pres", pres)
                putExtra("f0", f0)
                putExtra("temp", temp)
                putExtra("atten", atten)
                putExtra("latency", latency)
                putExtra("loFreq", loFreq)
                putExtra("hiFreq", hiFreq)
                putExtra("ampScale", ampScale)
                putExtra("overruns", overruns)
                putExtra("subCount", subCount)
                putExtra("midCount", midCount)
                putExtra("texCount", texCount)
                putExtra("keyStrikeActive", keyStrikeActive)
                putExtra("keyStrikeSemantic", keyStrikeSemantic)
                putExtra("semanticType", semanticType)

                putExtra("lraDisp", lraDisp)
                putExtra("lraVel", lraVel)
                putExtra("lraForce", lraForce)
                putExtra("lraPhase", lraPhase)
                putExtra("adsrEnv", adsrEnv)
                putExtra("thermalGain", thermalGain)

                putExtra("personaName", personaName)
                putExtra("primitiveType", primitiveType)
                putExtra("primitiveSemantic", primitiveSemantic)
                putExtra("primitiveIntensity", primitiveIntensity)
                putExtra("primitiveDuration", primitiveDuration)
                putExtra("gammaValue", gammaValue)
                putExtra("time", System.currentTimeMillis())
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e("LogBroadcaster", "Failed to broadcast telemetry: ${e.message}")
        }
    }

    fun log(context: Context, tag: String, msg: String) {
        sendLog(context, "[$tag] $msg")
    }
}
