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

        val ONEPLUS_13T = DeviceProfile(
            name = "OnePlus 13T · X-axis LRA",
            description = "OnePlus 13T flagship X-axis LRA, ~190 Hz resonance, fast response, needs primitive fallback support",
            minGuaranteedAmplitude = 20,
            maxAmplitude = 255,
            boostExponent = 0.42f,
            subDur1Min = 12, subDur1Max = 30,
            subGap1Min = 4,  subGap1Max = 10,
            subDur2Min = 6,  subDur2Max = 16,
            subGap2Min = 2,  subGap2Max = 8,
            subDur3Min = 4,  subDur3Max = 12,
            subAmpDecay2 = 0.50f,
            subAmpDecay3 = 0.22f,
            minIntervalMs = 3L,
            maxIntervalMs = 35L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.04f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 3L,
            fillerAmplitude = 8,
            actuator = ActuatorProfile.ONEPLUS_13T,
        )

        val ONEPLUS_15 = DeviceProfile(
            name = "OnePlus 15 · 高性能 X轴 LRA",
            description = "OnePlus 15 flagship X-axis LRA, ~200 Hz resonance, ultra-fast, crisp transient, high Q",
            minGuaranteedAmplitude = 12,
            maxAmplitude = 255,
            // v3.10.19: Lowered boost exponent — high-Q LRA already amplifies transients,
            // aggressive gamma boost causes overshoot and "pop rocks" effect.
            boostExponent = 0.32f,
            // v3.10.19: Shorter sub-pulse durations — the OnePlus 15's 3ms rise time
            // means it reaches full amplitude quickly. Shorter pulses prevent the
            // "extended buzz" that occurs when the LRA is driven past its settling time.
            subDur1Min = 8, subDur1Max = 22,
            subGap1Min = 4,  subGap1Max = 12,
            subDur2Min = 4,  subDur2Max = 12,
            subGap2Min = 3,  subGap2Max = 10,
            subDur3Min = 3,  subDur3Max = 8,
            // v3.10.19: Faster amplitude decay — high-Q LRA retains energy longer,
            // so we need faster decay to prevent pulse overlap (which causes buzzing).
            subAmpDecay2 = 0.40f,
            subAmpDecay3 = 0.15f,
            minIntervalMs = 3L,
            maxIntervalMs = 28L,
            // v3.10.19: Raised silence threshold — the previous 0.0008f was too
            // sensitive, causing the engine to vibrate on ambient noise floor.
            silenceThreshold = 0.002f,
            // v3.10.19: Raised energy threshold — prevents micro-fluctuations from
            // triggering transient detection, which produced irregular vibration patterns.
            energyThreshold = 0.055f,
            fillerFrameThreshold = 6,
            fillerDurationMs = 2L,
            fillerAmplitude = 2,
            actuator = ActuatorProfile.ONEPLUS_15,
        )

        // ════════════════════════════════════════════════════════════════
        // v3.10.20: OnePlus 全系 + 拯救者Y700 + 澎湃Ultra 适配
        // ════════════════════════════════════════════════════════════════

        val ONEPLUS_11 = DeviceProfile(
            name = "OnePlus 11 · CSA0916 X-axis LRA",
            description = "OnePlus 11 CSA0916 N52, 602mm³, ~10ms start/stop, large volume, rich bass",
            minGuaranteedAmplitude = 15,
            maxAmplitude = 255,
            boostExponent = 0.38f,
            subDur1Min = 12, subDur1Max = 30,
            subGap1Min = 4,  subGap1Max = 12,
            subDur2Min = 6,  subDur2Max = 16,
            subGap2Min = 3,  subGap2Max = 10,
            subDur3Min = 4,  subDur3Max = 12,
            subAmpDecay2 = 0.48f,
            subAmpDecay3 = 0.22f,
            minIntervalMs = 3L,
            maxIntervalMs = 32L,
            silenceThreshold = 0.0012f,
            energyThreshold = 0.042f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 3L,
            fillerAmplitude = 5,
            actuator = ActuatorProfile.ONEPLUS_11,
        )

        val ONEPLUS_12 = DeviceProfile(
            name = "OnePlus 12 · CSA0916 Turbo X-axis LRA",
            description = "OnePlus 12 N54+CSA+, 602mm³, fast start/stop, wide bandwidth",
            minGuaranteedAmplitude = 12,
            maxAmplitude = 255,
            boostExponent = 0.35f,
            subDur1Min = 10, subDur1Max = 26,
            subGap1Min = 3,  subGap1Max = 10,
            subDur2Min = 5,  subDur2Max = 14,
            subGap2Min = 2,  subGap2Max = 8,
            subDur3Min = 3,  subDur3Max = 10,
            subAmpDecay2 = 0.44f,
            subAmpDecay3 = 0.18f,
            minIntervalMs = 3L,
            maxIntervalMs = 30L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.045f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 2L,
            fillerAmplitude = 3,
            actuator = ActuatorProfile.ONEPLUS_12,
        )

        val ONEPLUS_13 = DeviceProfile(
            name = "OnePlus 13 · 仿生振感马达Turbo",
            description = "OnePlus 13 CSA+0916, 602mm³, ColorOS 15, 72 O-Haptics effects, ultra-fast",
            minGuaranteedAmplitude = 10,
            maxAmplitude = 255,
            boostExponent = 0.30f,   // 旗舰最强马达, 增益可更低
            subDur1Min = 8, subDur1Max = 22,
            subGap1Min = 3,  subGap1Max = 9,
            subDur2Min = 4,  subDur2Max = 12,
            subGap2Min = 2,  subGap2Max = 7,
            subDur3Min = 3,  subDur3Max = 8,
            subAmpDecay2 = 0.42f,
            subAmpDecay3 = 0.16f,
            minIntervalMs = 2L,
            maxIntervalMs = 28L,
            silenceThreshold = 0.0015f,
            energyThreshold = 0.05f,
            fillerFrameThreshold = 6,
            fillerDurationMs = 2L,
            fillerAmplitude = 2,
            actuator = ActuatorProfile.ONEPLUS_13,
        )

        val ONEPLUS_ACE3PRO = DeviceProfile(
            name = "OnePlus Ace 3 Pro · CSA0916 Turbo",
            description = "OnePlus Ace3 Pro, same 0916 Turbo motor as OP12, fast and crisp",
            minGuaranteedAmplitude = 12,
            maxAmplitude = 255,
            boostExponent = 0.35f,
            subDur1Min = 10, subDur1Max = 26,
            subGap1Min = 3,  subGap1Max = 10,
            subDur2Min = 5,  subDur2Max = 14,
            subGap2Min = 2,  subGap2Max = 8,
            subDur3Min = 3,  subDur3Max = 10,
            subAmpDecay2 = 0.44f,
            subAmpDecay3 = 0.18f,
            minIntervalMs = 3L,
            maxIntervalMs = 30L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.045f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 2L,
            fillerAmplitude = 3,
            actuator = ActuatorProfile.ONEPLUS_ACE3PRO,
        )

        val ONEPLUS_ACE_MID = DeviceProfile(
            name = "OnePlus Ace3/Ace5 · 0809A X-axis LRA",
            description = "OnePlus Ace3/Ace5 0809A mid-range X-axis, moderate volume and speed",
            minGuaranteedAmplitude = 25,
            maxAmplitude = 255,
            boostExponent = 0.45f,   // 小马达需要更多增益
            subDur1Min = 14, subDur1Max = 35,
            subGap1Min = 5,  subGap1Max = 14,
            subDur2Min = 7,  subDur2Max = 18,
            subGap2Min = 3,  subGap2Max = 10,
            subDur3Min = 5,  subDur3Max = 14,
            subAmpDecay2 = 0.52f,
            subAmpDecay3 = 0.25f,
            minIntervalMs = 4L,
            maxIntervalMs = 38L,
            silenceThreshold = 0.0008f,
            energyThreshold = 0.03f,
            fillerFrameThreshold = 4,
            fillerDurationMs = 3L,
            fillerAmplitude = 8,
            actuator = ActuatorProfile.ONEPLUS_ACE_MID,
        )

        val LENOVO_Y700_GEN1 = DeviceProfile(
            name = "Legion Y700 (Gen1) · 双X轴马达",
            description = "Y700 2022 dual X-axis LRA, rich bass",
            minGuaranteedAmplitude = 15,
            maxAmplitude = 255,
            boostExponent = 0.36f,
            subDur1Min = 10, subDur1Max = 28,
            subGap1Min = 4,  subGap1Max = 12,
            subDur2Min = 6,  subDur2Max = 16,
            subGap2Min = 3,  subGap2Max = 10,
            subDur3Min = 4,  subDur3Max = 12,
            subAmpDecay2 = 0.46f,
            subAmpDecay3 = 0.20f,
            minIntervalMs = 3L,
            maxIntervalMs = 35L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.04f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 3L,
            fillerAmplitude = 4,
            bassBoost = 1.15f,
            actuator = ActuatorProfile.DEFAULT,
        )

        val LENOVO_Y700_GEN2 = DeviceProfile(
            name = "Legion Y700 (Gen2/3) · 0815 X轴马达",
            description = "Y700 2023/2024 single 0815 LRA, 200Hz high Q",
            minGuaranteedAmplitude = 15,
            maxAmplitude = 255,
            boostExponent = 0.38f,
            subDur1Min = 10, subDur1Max = 28,
            subGap1Min = 4,  subGap1Max = 12,
            subDur2Min = 6,  subDur2Max = 16,
            subGap2Min = 3,  subGap2Max = 10,
            subDur3Min = 4,  subDur3Max = 12,
            subAmpDecay2 = 0.46f,
            subAmpDecay3 = 0.20f,
            minIntervalMs = 3L,
            maxIntervalMs = 35L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.04f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 3L,
            fillerAmplitude = 4,
            bassBoost = 1.0f,
            actuator = ActuatorProfile.LENOVO_Y700,
        )

        val XIAOMI_ULTRA = DeviceProfile(
            name = "Xiaomi Ultra · RichFeel ESA1016 超宽频",
            description = "Xiaomi 14/15 Ultra ESA1016, 10-500Hz, 4ms start/stop, HyperOS Haptic 2.0",
            minGuaranteedAmplitude = 10,
            maxAmplitude = 255,
            boostExponent = 0.30f,   // 超宽频马达自身灵敏, 低增益
            subDur1Min = 8, subDur1Max = 22,
            subGap1Min = 3,  subGap1Max = 9,
            subDur2Min = 4,  subDur2Max = 12,
            subGap2Min = 2,  subGap2Max = 7,
            subDur3Min = 3,  subDur3Max = 8,
            subAmpDecay2 = 0.40f,
            subAmpDecay3 = 0.15f,
            minIntervalMs = 2L,
            maxIntervalMs = 28L,
            silenceThreshold = 0.0015f,
            energyThreshold = 0.05f,
            fillerFrameThreshold = 6,
            fillerDurationMs = 2L,
            fillerAmplitude = 2,
            actuator = ActuatorProfile.XIAOMI_ULTRA_RICHFEEL,
        )

        val SAMSUNG_S25 = DeviceProfile(
            name = "Samsung Galaxy S25 · X-axis LRA",
            description = "Samsung S25 flagship X-axis LRA, ~200 Hz, precise, Samsung-tuned primitives",
            minGuaranteedAmplitude = 15,
            maxAmplitude = 255,
            boostExponent = 0.42f,
            subDur1Min = 11, subDur1Max = 28,
            subGap1Min = 3,  subGap1Max = 10,
            subDur2Min = 6,  subDur2Max = 15,
            subGap2Min = 2,  subGap2Max = 8,
            subDur3Min = 4,  subDur3Max = 11,
            subAmpDecay2 = 0.48f,
            subAmpDecay3 = 0.21f,
            minIntervalMs = 2L,
            maxIntervalMs = 32L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.038f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 2L,
            fillerAmplitude = 5,
            actuator = ActuatorProfile.SAMSUNG_S25,
        )

        val XIAOMI_15 = DeviceProfile(
            name = "Xiaomi 15 · X-axis LRA",
            description = "Xiaomi 15 flagship X-axis LRA, ~200 Hz, RichFeel engine, fast and crisp",
            minGuaranteedAmplitude = 12,
            maxAmplitude = 255,
            boostExponent = 0.40f,
            subDur1Min = 10, subDur1Max = 27,
            subGap1Min = 3,  subGap1Max = 9,
            subDur2Min = 5,  subDur2Max = 14,
            subGap2Min = 2,  subGap2Max = 7,
            subDur3Min = 3,  subDur3Max = 10,
            subAmpDecay2 = 0.47f,
            subAmpDecay3 = 0.20f,
            minIntervalMs = 2L,
            maxIntervalMs = 30L,
            silenceThreshold = 0.0008f,
            energyThreshold = 0.035f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 2L,
            fillerAmplitude = 4,
            actuator = ActuatorProfile.XIAOMI_15,
        )

        val VIVO_FLAGSHIP = DeviceProfile(
            name = "vivo/iQOO Flagship · X-axis LRA",
            description = "vivo/iQOO flagship X-axis LRA, ~190 Hz, strong transient, IPC-friendly",
            minGuaranteedAmplitude = 18,
            maxAmplitude = 255,
            boostExponent = 0.42f,
            subDur1Min = 12, subDur1Max = 30,
            subGap1Min = 4,  subGap1Max = 10,
            subDur2Min = 6,  subDur2Max = 16,
            subGap2Min = 2,  subGap2Max = 8,
            subDur3Min = 4,  subDur3Max = 12,
            subAmpDecay2 = 0.50f,
            subAmpDecay3 = 0.22f,
            minIntervalMs = 3L,
            maxIntervalMs = 35L,
            silenceThreshold = 0.001f,
            energyThreshold = 0.04f,
            fillerFrameThreshold = 5,
            fillerDurationMs = 3L,
            fillerAmplitude = 6,
            actuator = ActuatorProfile.VIVO_FLAGSHIP,
        )
    }
}

fun detectDeviceProfile(
    context: android.content.Context? = null,
    persistedProfileId: String? = null
): DeviceProfile {
    // A root-derived board fingerprint takes precedence over spoofable Build fields.
    // Hook processes receive this value through the module preference snapshot.
    val rootProfileId = persistedProfileId ?: context
        ?.getSharedPreferences("haptics_config", android.content.Context.MODE_PRIVATE)
        ?.getString(RootHardwareProbe.PREF_PROFILE, null)
    when (rootProfileId) {
        "XIAOMI10_XAXIS" -> return DeviceProfile.XIAOMI10_XAXIS
        "XIAOMI13_XAXIS" -> return DeviceProfile.XIAOMI13_XAXIS
        "XIAOMI15" -> return DeviceProfile.XIAOMI_15
        "XIAOMI_ULTRA" -> return DeviceProfile.XIAOMI_ULTRA
        "REDMI_K80U_0809" -> return DeviceProfile.REDMI_K80U_0809
        "ONEPLUS_11" -> return DeviceProfile.ONEPLUS_11
        "ONEPLUS_12" -> return DeviceProfile.ONEPLUS_12
        "ONEPLUS_13" -> return DeviceProfile.ONEPLUS_13
        "ONEPLUS_13T" -> return DeviceProfile.ONEPLUS_13T
        "ONEPLUS_15" -> return DeviceProfile.ONEPLUS_15
        "ONEPLUS_ACE3PRO" -> return DeviceProfile.ONEPLUS_ACE3PRO
        "ONEPLUS_ACE_MID" -> return DeviceProfile.ONEPLUS_ACE_MID
        "LENOVO_Y700_GEN1" -> return DeviceProfile.LENOVO_Y700_GEN1
        "LENOVO_Y700_GEN2" -> return DeviceProfile.LENOVO_Y700_GEN2
        "SAMSUNG_S25" -> return DeviceProfile.SAMSUNG_S25
        "VIVO_FLAGSHIP" -> return DeviceProfile.VIVO_FLAGSHIP
        "FLAGSHIP_XAXIS" -> return DeviceProfile.FLAGSHIP_XAXIS
        "DEFAULT" -> return DeviceProfile.DEFAULT
    }

    val model = Build.MODEL.uppercase().replace(" ", "")
    val manufacturer = Build.MANUFACTURER.lowercase()
    val device = Build.DEVICE.lowercase(Locale.ROOT)
    val board = (Build.BOARD ?: "").lowercase(Locale.ROOT)

    if (manufacturer == "xiaomi") {
        if (device.contains("umi") || device.contains("cmi") || device.contains("thyme")) {
            return DeviceProfile.XIAOMI10_XAXIS
        }

        if (device.contains("fuxi")) {
            return DeviceProfile.XIAOMI13_XAXIS
        }

        // Xiaomi 15 / 15 Pro: codename "haotai" / "shenni"
        if (device.contains("haotai") || device.contains("shenni") ||
            model.contains("23127PN") || model.contains("24129PN")) {
            return DeviceProfile.XIAOMI_15
        }

        // v3.10.20: Xiaomi Ultra series (14U/15U) — ESA1016 super-wideband
        if (model.contains("24031PN") || model.contains("25042PN") ||  // 14U / 15U
            device.contains("aurora") || device.contains("eiffel") ||
            model.contains("ULTRA")) {
            return DeviceProfile.XIAOMI_ULTRA
        }

        if (device.contains("k80") || model.contains("K80")) {
            return DeviceProfile.REDMI_K80U_0809
        }

        if (device.contains("houbi")) {
            return DeviceProfile.FLAGSHIP_XAXIS
        }
    }

    if (manufacturer.contains("lenovo")) {
        val m = model.lowercase()
        val d = device.lowercase()
        if (m.contains("tb320") || m.contains("tb321") || d.contains("tb320") || d.contains("tb321") || m.contains("y700_2023") || m.contains("y700_2024")) {
            return DeviceProfile.LENOVO_Y700_GEN2
        } else if (m.contains("y700") || d.contains("y700") || m.contains("tb9707")) {
            return DeviceProfile.LENOVO_Y700_GEN1
        }
    }

    if (manufacturer == "oneplus" || manufacturer == "oppo") {
        // OnePlus 13T specific detection
        if (model.contains("CPH2653") || model.contains("13T") || device.contains("aston")) {
            return DeviceProfile.ONEPLUS_13T
        }
        // OnePlus 15: model PLK110 (China) / CPH2747 (global)
        if (model.contains("PLK110") || model.contains("CPH2747") ||
            model.contains("ONEPLUS15") || model.contains("PG110") ||
            device.contains("plk110") || board.contains("plk110")) {
            return DeviceProfile.ONEPLUS_15
        }
        // v3.10.20: OnePlus 13: model PJZ110 / CPH2653 variant
        if (model.contains("PJZ110") || model.contains("CPH2699") ||
            model.contains("ONEPLUS13") || device.contains("opus")) {
            return DeviceProfile.ONEPLUS_13
        }
        // v3.10.20: OnePlus 12: model PJD110 / CPH2581
        if (model.contains("PJD110") || model.contains("CPH2581") ||
            model.contains("ONEPLUS12") || device.contains("waffle")) {
            return DeviceProfile.ONEPLUS_12
        }
        // v3.10.20: OnePlus 11: model PHB110 / CPH2447
        if (model.contains("PHB110") || model.contains("CPH2447") ||
            model.contains("ONEPLUS11") || device.contains("salami")) {
            return DeviceProfile.ONEPLUS_11
        }
        // v3.10.20: OnePlus Ace 3 Pro: model PJD110 variant / CPH2611
        if (model.contains("CPH2611") || model.contains("PHZ110") ||
            model.contains("ACE3PRO") || device.contains("ace3pro")) {
            return DeviceProfile.ONEPLUS_ACE3PRO
        }
        // v3.10.20: OnePlus Ace 3 / Ace 5 — mid-range 0809A motor
        if (model.contains("PHD110") || model.contains("PHB") ||
            model.contains("ACE3") || model.contains("ACE5") ||
            model.contains("PHK") || model.contains("CPH2671")) {
            return DeviceProfile.ONEPLUS_ACE_MID
        }
        // OnePlus Ace 5 / 6 series — flagship X-axis fallback
        if (model.startsWith("CPH") || model.startsWith("PH") || model.startsWith("PJ") ||
            model.startsWith("PLK") || model.contains("ONEPLUS")) {
            return DeviceProfile.FLAGSHIP_XAXIS
        }
    }

    if (manufacturer == "samsung") {
        // Galaxy S25 series: SM-S93x
        if (model.contains("SM-S93") || model.contains("SM-S92")) {
            return DeviceProfile.SAMSUNG_S25
        }
        if (model.contains("SM-S") || model.contains("SM-F")) {
            return DeviceProfile.FLAGSHIP_XAXIS
        }
    }

    if (manufacturer == "google" && model.contains("PIXEL")) {
        return DeviceProfile.FLAGSHIP_XAXIS
    }

    if (manufacturer == "vivo" || manufacturer == "iqoo") {
        // vivo X200 / iQOO 13 series
        if (model.contains("V24") || model.contains("V23") ||
            device.contains("pd24") || device.contains("pd23")) {
            return DeviceProfile.VIVO_FLAGSHIP
        }
        if (model.startsWith("V") || model.contains("IQOO")) {
            return DeviceProfile.FLAGSHIP_XAXIS
        }
    }

    if (manufacturer == "huawei" || manufacturer == "honor") {
        // Huawei Mate 70 series
        if (model.contains("BNE") || model.contains("ALA") || model.contains("MAS")) {
            return DeviceProfile.FLAGSHIP_XAXIS
        }
    }

    // Android 13+ (API 33+) devices are likely to have decent X-axis LRA.
    if (Build.VERSION.SDK_INT >= 33) {
        return DeviceProfile.FLAGSHIP_XAXIS
    }

    return DeviceProfile.DEFAULT
}