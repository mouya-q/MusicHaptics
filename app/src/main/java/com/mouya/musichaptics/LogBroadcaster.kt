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
            // v3.13: Pack all float values into a single float array to reduce
            // Binder IPC overhead from 30+ putExtra calls to ~10 packed calls.
            val floats = floatArrayOf(
                sub, mid, pres, f0, temp, atten,
                loFreq, hiFreq, ampScale,
                lraDisp, lraVel, lraForce, lraPhase, adsrEnv, thermalGain, gammaValue
            )
            val longs = longArrayOf(latency, overruns, subCount, midCount, texCount)
            val ints = intArrayOf(primitiveIntensity, primitiveDuration)
            val intent = Intent(ACTION_TELEMETRY).apply {
                setPackage(OWN_PACKAGE)
                putExtra("floats", floats)
                putExtra("longs", longs)
                putExtra("ints", ints)
                putExtra("ksActive", keyStrikeActive)
                putExtra("ksSem", keyStrikeSemantic)
                putExtra("semType", semanticType)
                putExtra("persona", personaName)
                putExtra("primType", primitiveType)
                putExtra("primSem", primitiveSemantic)
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
