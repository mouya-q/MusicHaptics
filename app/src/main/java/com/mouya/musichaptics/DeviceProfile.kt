package com.mouya.musichaptics

import android.os.Build
import java.util.Locale

data class DeviceProfile(
    val name: String,
    val description: String,

    val startLatencyMs: Float = 4.5f,

    val stopLatencyMs: Float = 8.0f,

    val minGuaranteedAmplitude: Int = 40,
    val maxAmplitude: Int = 255,

    val boostExponent: Float = 0.50f,

    val subDur1Min: Long = 18, val subDur1Max: Long = 40,

    val subGap1Min: Long = 6,  val subGap1Max: Long = 16,

    val subDur2Min: Long = 8,  val subDur2Max: Long = 22,

    val subGap2Min: Long = 3,  val subGap2Max: Long = 12,

    val subDur3Min: Long = 4,  val subDur3Max: Long = 14,

    val subAmpDecay2: Float = 0.55f,

    val subAmpDecay3: Float = 0.25f,

    val minIntervalMs: Long = 4L,
    val maxIntervalMs: Long = 40L,

    val silenceThreshold: Float = 0.0025f,
    val energyThreshold: Float = 0.07f,

    val fillerFrameThreshold: Int = 8,
    val fillerDurationMs: Long = 3L,
    val fillerAmplitude: Int = 1,

    val subWeight: Float = 1.0f,
    val midWeight: Float = 0.6f,
    val presenceWeight: Float = 0.4f,

    val bassBoost: Float = 1.0f,

    // v1.9: Actuator physical model for LRA simulation
    val actuator: ActuatorProfile = ActuatorProfile.DEFAULT,
) {
    companion object {

        val DEFAULT = DeviceProfile(
            name = "Generic Default",
            description = "High sensitivity for guaranteed vibration on all Android LRA types",
            minGuaranteedAmplitude = 45,
            maxAmplitude = 255,
            boostExponent = 0.35f,

            subDur1Min = 20, subDur1Max = 50,
            subGap1Min = 5,  subGap1Max = 15,
            subDur2Min = 10, subDur2Max = 25,
            subGap2Min = 3,  subGap2Max = 12,
            subDur3Min = 6,  subDur3Max = 18,
            subAmpDecay2 = 0.55f,
            subAmpDecay3 = 0.28f,
            minIntervalMs = 4L,
            maxIntervalMs = 45L,
            silenceThreshold = 0.0005f,
            energyThreshold = 0.008f,
            fillerFrameThreshold = 4,
            fillerDurationMs = 4L,
            fillerAmplitude = 18,
            bassBoost = 1.2f,
            actuator = ActuatorProfile.DEFAULT,
        )

        val XIAOMI13_XAXIS = DeviceProfile(
            name = "Xiaomi 13 · X-axis LRA",
            description = "X-axis wideband LRA, ~200 Hz resonance, < 5 ms start/stop, ultra-responsive",
            minGuaranteedAmplitude = 12,
            maxAmplitude = 255,
            boostExponent = 0.45f,

            subDur1Min = 12, subDur1Max = 30,
            subGap1Min = 4,  subGap1Max = 10,
            subDur2Min = 6,  subDur2Max = 16,
            subGap2Min = 2,  subGap2Max = 8,
            subDur3Min = 3,  subDur3Max = 10,
            subAmpDecay2 = 0.50f,
            subAmpDecay3 = 0.22f,
            minIntervalMs = 3L,
            maxIntervalMs = 35L,
            silenceThreshold = 0.002f,
            energyThreshold = 0.06f,
            fillerFrameThreshold = 6,
            fillerDurationMs = 2L,
            fillerAmplitude = 1,
            actuator = ActuatorProfile.XIAOMI_13_XAXIS,
        )

        val XIAOMI10_XAXIS = DeviceProfile(
            name = "Xiaomi 10 Series · 0809 X-axis LRA",
            description = "0809 X-axis wideband LRA, ~190 Hz resonance, < 5 ms start/stop, rich texture",
            minGuaranteedAmplitude = 45,
            maxAmplitude = 255,
            boostExponent = 0.5f,

            subDur1Min = 15, subDur1Max = 45,
            subGap1Min = 4,  subGap1Max = 12,
            subDur2Min = 8,  subDur2Max = 22,
            subGap2Min = 3,  subGap2Max = 10,
            subDur3Min = 5,  subDur3Max = 16,
            subAmpDecay2 = 0.55f,
            subAmpDecay3 = 0.30f,
            minIntervalMs = 3L,
            maxIntervalMs = 35L,
            silenceThreshold = 0.0001f,
            energyThreshold = 0.003f,
            fillerFrameThreshold = 2,
            fillerDurationMs = 6L,
            fillerAmplitude = 35,
            bassBoost = 1.2f,
            actuator = ActuatorProfile.XIAOMI_10_0809,
        )

        val REDMI_K80U_0809 = DeviceProfile(
            name = "Redmi K80 Ultra · 0809 Z-axis LRA",
            description = "0809 Z-axis LRA, ~160 Hz resonance, ~12 ms start/stop, needs longer envelope",
            minGuaranteedAmplitude = 35,
            maxAmplitude = 255,
            boostExponent = 0.42f,

            subDur1Min = 22, subDur1Max = 45,
            subGap1Min = 8,  subGap1Max = 18,
            subDur2Min = 12, subDur2Max = 26,
            subGap2Min = 5,  subGap2Max = 14,
            subDur3Min = 6,  subDur3Max = 16,
            subAmpDecay2 = 0.60f,
            subAmpDecay3 = 0.30f,
            minIntervalMs = 5L,
            maxIntervalMs = 45L,
            silenceThreshold = 0.003f,
            energyThreshold = 0.09f,
            fillerFrameThreshold = 10,
            fillerDurationMs = 4L,
            fillerAmplitude = 2,
            subWeight = 1.0f,
            midWeight = 0.55f,
            presenceWeight = 0.35f,
            bassBoost = 1.30f,
            actuator = ActuatorProfile.REDMI_K80U_ZAXIS,
        )

        val FLAGSHIP_XAXIS = DeviceProfile(
            name = "Flagship X-axis · Auto-Detected",
            description = "Detected high-end haptic capability (API 33+ full primitive support); using X-axis aggressive params",
            minGuaranteedAmplitude = 12,
            boostExponent = 0.42f,
            subDur1Min = 10, subDur1Max = 28,
            subGap1Min = 3,  subGap1Max = 10,
            subDur2Min = 5,  subDur2Max = 15,
            subGap2Min = 2,  subGap2Max = 8,
            subDur3Min = 3,  subDur3Max = 10,
            subAmpDecay2 = 0.48f,
            subAmpDecay3 = 0.20f,
            minIntervalMs = 2L,
            maxIntervalMs = 30L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.04f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 2L,
            fillerAmplitude = 4,
            actuator = ActuatorProfile.FLAGSHIP_XAXIS,
        )
    }
}

fun detectDeviceProfile(): DeviceProfile {
    val model = Build.MODEL.uppercase().replace(" ", "")
    val manufacturer = Build.MANUFACTURER.lowercase()
    val device = Build.DEVICE.lowercase(Locale.ROOT)

    if (manufacturer == "xiaomi") {
        if (device.contains("umi") || device.contains("cmi") || device.contains("thyme")) {
            return DeviceProfile.XIAOMI10_XAXIS
        }

        if (device.contains("fuxi")) {
            return DeviceProfile.XIAOMI13_XAXIS
        }

        if (device.contains("k80") || model.contains("K80")) {
            return DeviceProfile.REDMI_K80U_0809
        }

        if (device.contains("houbi") || model.contains("23127PN")) {
            return DeviceProfile.FLAGSHIP_XAXIS
        }
    }

    if (manufacturer == "xiaomi") {

        if (model.contains("2211133") || model.contains("FUXI")) {
            return DeviceProfile.XIAOMI13_XAXIS
        }

        if (model.contains("M2001J2") || model.contains("M2102J2") ||
            model.contains("MI10") || model.contains("MI 10")) {
            return DeviceProfile.XIAOMI10_XAXIS
        }
    }

    if (manufacturer == "oneplus" || manufacturer == "oppo") {
        if (model.startsWith("CPH") || model.startsWith("PH") || model.contains("ONEPLUS")) {
            return DeviceProfile.FLAGSHIP_XAXIS
        }
    }

    if (manufacturer == "samsung" && (model.contains("SM-S") || model.contains("SM-F"))) {
        return DeviceProfile.FLAGSHIP_XAXIS
    }

    if (manufacturer == "google" && model.contains("PIXEL")) {
        return DeviceProfile.FLAGSHIP_XAXIS
    }

    if (Build.VERSION.SDK_INT >= 33) {
        try {
            return DeviceProfile.FLAGSHIP_XAXIS
        } catch (_: Exception) {}
    }

    return DeviceProfile.DEFAULT
}