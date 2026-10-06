package com.mouya.musichaptics.haptic

import com.mouya.musichaptics.DeviceProfile

data class DeviceTuning(
    val profileId: String = "DEFAULT",
    val impactGain: Float = 1.00f,
    val kickMsScale: Float = 1.00f,
    val snareMsScale: Float = 1.00f,
    val tickMsScale: Float = 1.00f,
    val bodyMsScale: Float = 1.00f,
    val minIntervalMs: Long = 58L,
    val minIntensity: Int = 14,
    val preferDefaultAmplitude: Boolean = false,
    val amplitudeCeilingScale: Float = 1.00f,
    val textureGainScale: Float = 1.00f,
    val bodyGainScale: Float = 1.00f,
    val reason: String = "generic fallback"
) {
    fun cappedAmplitude(profile: DeviceProfile): Int =
        (profile.maxAmplitude * amplitudeCeilingScale)
            .toInt()
            .coerceIn(1, profile.maxAmplitude)
}

object DeviceTuningRegistry {
    
    fun current(profile: DeviceProfile): DeviceTuning = when (profile) {
        DeviceProfile.DEFAULT -> DeviceTuning(
            profileId = "DEFAULT",
            impactGain = 1.00f,
            kickMsScale = 1.00f,
            snareMsScale = 1.00f,
            tickMsScale = 1.00f,
            bodyMsScale = 1.00f,
            minIntervalMs = 58L,
            minIntensity = 14,
            amplitudeCeilingScale = 0.96f,
            textureGainScale = 1.00f,
            bodyGainScale = 0.96f,
            reason = "generic safe baseline; no named actuator profile"
        )
        DeviceProfile.XIAOMI10_XAXIS -> DeviceTuning(
            profileId = "XIAOMI10_XAXIS",
            impactGain = 1.10f,
            kickMsScale = 0.76f,
            snareMsScale = 0.80f,
            tickMsScale = 0.68f,
            bodyMsScale = 0.84f,
            minIntervalMs = 52L,
            minIntensity = 12,
            preferDefaultAmplitude = true,
            amplitudeCeilingScale = 0.96f,
            textureGainScale = 1.08f,
            bodyGainScale = 0.78f,
            reason = "0809/X-axis compatibility path; preserve short attacks and avoid long rumble"
        )
        DeviceProfile.XIAOMI13_XAXIS -> DeviceTuning(
            profileId = "XIAOMI13_XAXIS",
            impactGain = 1.08f,
            kickMsScale = 0.72f,
            snareMsScale = 0.76f,
            tickMsScale = 0.62f,
            bodyMsScale = 0.80f,
            minIntervalMs = 48L,
            minIntensity = 10,
            amplitudeCeilingScale = 1.00f,
            textureGainScale = 1.10f,
            bodyGainScale = 0.74f,
            reason = "fast X-axis profile; emphasize transient separation"
        )
        DeviceProfile.XIAOMI11 -> DeviceTuning(
            profileId = "XIAOMI11",
            impactGain = 1.05f,
            kickMsScale = 0.86f,
            snareMsScale = 0.88f,
            tickMsScale = 0.78f,
            bodyMsScale = 0.92f,
            minIntervalMs = 54L,
            minIntensity = 14,
            reason = "balanced X-axis tuning"
        )
        DeviceProfile.XIAOMI12 -> DeviceTuning(
            profileId = "XIAOMI12",
            impactGain = 1.06f,
            kickMsScale = 0.82f,
            snareMsScale = 0.84f,
            tickMsScale = 0.74f,
            bodyMsScale = 0.88f,
            minIntervalMs = 52L,
            minIntensity = 12,
            reason = "CyberEngine-class fast transient tuning"
        )
        DeviceProfile.XIAOMI14 -> DeviceTuning(
            profileId = "XIAOMI14",
            impactGain = 1.08f,
            kickMsScale = 0.70f,
            snareMsScale = 0.74f,
            tickMsScale = 0.60f,
            bodyMsScale = 0.78f,
            minIntervalMs = 46L,
            minIntensity = 10,
            textureGainScale = 1.12f,
            bodyGainScale = 0.72f,
            reason = "fast RichFeel-style transient rendering"
        )
        DeviceProfile.XIAOMI_15 -> DeviceTuning(
            profileId = "XIAOMI_15",
            impactGain = 1.08f,
            kickMsScale = 0.68f,
            snareMsScale = 0.72f,
            tickMsScale = 0.58f,
            bodyMsScale = 0.76f,
            minIntervalMs = 44L,
            minIntensity = 9,
            textureGainScale = 1.14f,
            bodyGainScale = 0.70f,
            reason = "fast flagship transient rendering"
        )
        DeviceProfile.XIAOMI_13PRO -> DeviceTuning(
            profileId = "XIAOMI_13PRO",
            impactGain = 1.05f,
            kickMsScale = 0.72f,
            snareMsScale = 0.76f,
            tickMsScale = 0.62f,
            bodyMsScale = 0.70f,
            minIntervalMs = 44L,
            minIntensity = 8,
            textureGainScale = 1.20f,
            bodyGainScale = 0.95f,
            reason = "wideband actuator profile; retain low-frequency texture without sustained level"
        )
        DeviceProfile.XIAOMI_ULTRA -> DeviceTuning(
            profileId = "XIAOMI_ULTRA",
            impactGain = 1.04f,
            kickMsScale = 0.64f,
            snareMsScale = 0.68f,
            tickMsScale = 0.54f,
            bodyMsScale = 0.68f,
            minIntervalMs = 42L,
            minIntensity = 8,
            textureGainScale = 1.18f,
            bodyGainScale = 0.86f,
            reason = "wideband flagship tuning; short pulse envelope"
        )
        DeviceProfile.XIAOMI_15PRO -> DeviceTuning(
            profileId = "XIAOMI_15PRO",
            impactGain = 1.06f,
            kickMsScale = 0.70f,
            snareMsScale = 0.74f,
            tickMsScale = 0.60f,
            bodyMsScale = 0.76f,
            minIntervalMs = 44L,
            minIntensity = 10,
            textureGainScale = 1.12f,
            bodyGainScale = 0.76f,
            reason = "0815 X-axis flagship rendering"
        )
        DeviceProfile.XIAOMI_MIX_FOLD -> DeviceTuning(
            profileId = "XIAOMI_MIX_FOLD",
            impactGain = 1.04f,
            kickMsScale = 0.78f,
            snareMsScale = 0.82f,
            tickMsScale = 0.68f,
            bodyMsScale = 0.84f,
            minIntervalMs = 50L,
            minIntensity = 12,
            amplitudeCeilingScale = 0.94f,
            reason = "foldable chassis-safe 0815 tuning"
        )
        DeviceProfile.XIAOMI_17_PRO -> DeviceTuning(
            profileId = "XIAOMI_17_PRO",
            impactGain = 1.02f,
            kickMsScale = 0.58f,
            snareMsScale = 0.62f,
            tickMsScale = 0.50f,
            bodyMsScale = 0.64f,
            minIntervalMs = 40L,
            minIntensity = 6,
            textureGainScale = 1.24f,
            bodyGainScale = 0.92f,
            reason = "ESA1016/CyberEngine-style wideband rendering"
        )
        DeviceProfile.REDMI_K50_GAMING -> DeviceTuning(
            profileId = "REDMI_K50_GAMING",
            impactGain = 1.06f,
            kickMsScale = 0.64f,
            snareMsScale = 0.70f,
            tickMsScale = 0.58f,
            bodyMsScale = 0.68f,
            minIntervalMs = 44L,
            minIntensity = 8,
            textureGainScale = 1.20f,
            bodyGainScale = 0.96f,
            reason = "gaming-oriented wideband bass texture"
        )
        DeviceProfile.REDMI_K40 -> DeviceTuning(
            profileId = "REDMI_K40",
            impactGain = 1.08f,
            kickMsScale = 0.82f,
            snareMsScale = 0.86f,
            tickMsScale = 0.72f,
            bodyMsScale = 0.88f,
            minIntervalMs = 54L,
            minIntensity = 16,
            reason = "legacy 0809 balanced tuning"
        )
        DeviceProfile.REDMI_K60 -> DeviceTuning(
            profileId = "REDMI_K60",
            impactGain = 1.08f,
            kickMsScale = 0.80f,
            snareMsScale = 0.84f,
            tickMsScale = 0.70f,
            bodyMsScale = 0.86f,
            minIntervalMs = 52L,
            minIntensity = 15,
            reason = "0809 balanced tuning with stronger bass accent"
        )
        DeviceProfile.REDMI_K70 -> DeviceTuning(
            profileId = "REDMI_K70",
            impactGain = 1.07f,
            kickMsScale = 0.78f,
            snareMsScale = 0.82f,
            tickMsScale = 0.68f,
            bodyMsScale = 0.84f,
            minIntervalMs = 50L,
            minIntensity = 14,
            reason = "HyperOS 0809 transient tuning"
        )
        DeviceProfile.REDMI_K70U -> DeviceTuning(
            profileId = "REDMI_K70U",
            impactGain = 1.02f,
            kickMsScale = 0.86f,
            snareMsScale = 0.92f,
            tickMsScale = 0.80f,
            bodyMsScale = 0.96f,
            minIntervalMs = 58L,
            minIntensity = 20,
            amplitudeCeilingScale = 0.90f,
            reason = "moderate-speed 0809 profile; allow longer envelope"
        )
        DeviceProfile.REDMI_K80U_0809 -> DeviceTuning(
            profileId = "REDMI_K80U_0809",
            impactGain = 0.98f,
            kickMsScale = 1.00f,
            snareMsScale = 1.04f,
            tickMsScale = 0.92f,
            bodyMsScale = 1.06f,
            minIntervalMs = 64L,
            minIntensity = 24,
            amplitudeCeilingScale = 0.86f,
            textureGainScale = 0.94f,
            bodyGainScale = 0.86f,
            reason = "slow Z-axis profile; suppress rapid retriggers and hard clipping"
        )
        DeviceProfile.OPPO_RENO8_PRO -> DeviceTuning(
            profileId = "OPPO_RENO8_PRO",
            impactGain = 1.02f,
            kickMsScale = 0.92f,
            snareMsScale = 0.96f,
            tickMsScale = 0.86f,
            bodyMsScale = 0.98f,
            minIntervalMs = 60L,
            minIntensity = 20,
            amplitudeCeilingScale = 0.86f,
            textureGainScale = 0.96f,
            bodyGainScale = 0.90f,
            reason = "smaller ELA0809-style actuator; conservative drive"
        )
        DeviceProfile.ONEPLUS_9 -> DeviceTuning(
            profileId = "ONEPLUS_9",
            impactGain = 1.06f,
            kickMsScale = 0.84f,
            snareMsScale = 0.88f,
            tickMsScale = 0.74f,
            bodyMsScale = 0.90f,
            minIntervalMs = 54L,
            minIntensity = 16,
            reason = "older 0809 X-axis tuning"
        )
        DeviceProfile.ONEPLUS_10PRO -> DeviceTuning(
            profileId = "ONEPLUS_10PRO",
            impactGain = 1.08f,
            kickMsScale = 0.72f,
            snareMsScale = 0.76f,
            tickMsScale = 0.64f,
            bodyMsScale = 0.78f,
            minIntervalMs = 48L,
            minIntensity = 12,
            textureGainScale = 1.10f,
            bodyGainScale = 0.78f,
            reason = "0815 X-axis / O-Haptics-style crisp output"
        )
        DeviceProfile.ONEPLUS_11 -> DeviceTuning(
            profileId = "ONEPLUS_11",
            impactGain = 1.06f,
            kickMsScale = 0.74f,
            snareMsScale = 0.78f,
            tickMsScale = 0.64f,
            bodyMsScale = 0.80f,
            minIntervalMs = 48L,
            minIntensity = 12,
            reason = "large-volume X-axis; emphasize kick body separation"
        )
        DeviceProfile.ONEPLUS_12 -> DeviceTuning(
            profileId = "ONEPLUS_12",
            impactGain = 1.08f,
            kickMsScale = 0.68f,
            snareMsScale = 0.72f,
            tickMsScale = 0.58f,
            bodyMsScale = 0.74f,
            minIntervalMs = 44L,
            minIntensity = 10,
            textureGainScale = 1.10f,
            bodyGainScale = 0.78f,
            reason = "Turbo X-axis transient tuning"
        )
        DeviceProfile.ONEPLUS_13 -> DeviceTuning(
            profileId = "ONEPLUS_13",
            impactGain = 1.06f,
            kickMsScale = 0.62f,
            snareMsScale = 0.66f,
            tickMsScale = 0.54f,
            bodyMsScale = 0.68f,
            minIntervalMs = 42L,
            minIntensity = 8,
            textureGainScale = 1.12f,
            bodyGainScale = 0.74f,
            reason = "Turbo X-axis; short high-contrast impulses"
        )
        DeviceProfile.ONEPLUS_13T -> DeviceTuning(
            profileId = "ONEPLUS_13T",
            impactGain = 1.05f,
            kickMsScale = 0.66f,
            snareMsScale = 0.70f,
            tickMsScale = 0.56f,
            bodyMsScale = 0.72f,
            minIntervalMs = 44L,
            minIntensity = 10,
            textureGainScale = 1.10f,
            reason = "fast X-axis with primitive fallback"
        )
        DeviceProfile.ONEPLUS_15 -> DeviceTuning(
            profileId = "ONEPLUS_15",
            impactGain = 1.03f,
            kickMsScale = 0.56f,
            snareMsScale = 0.60f,
            tickMsScale = 0.48f,
            bodyMsScale = 0.62f,
            minIntervalMs = 40L,
            minIntensity = 8,
            amplitudeCeilingScale = 0.94f,
            textureGainScale = 1.18f,
            bodyGainScale = 0.68f,
            reason = "very fast/high-Q profile; keep pulses tiny and separated"
        )
        DeviceProfile.ONEPLUS_ACE3PRO -> DeviceTuning(
            profileId = "ONEPLUS_ACE3PRO",
            impactGain = 1.06f,
            kickMsScale = 0.70f,
            snareMsScale = 0.74f,
            tickMsScale = 0.60f,
            bodyMsScale = 0.76f,
            minIntervalMs = 46L,
            minIntensity = 10,
            reason = "0916 Turbo family tuning"
        )
        DeviceProfile.ONEPLUS_ACE_MID -> DeviceTuning(
            profileId = "ONEPLUS_ACE_MID",
            impactGain = 1.10f,
            kickMsScale = 0.86f,
            snareMsScale = 0.90f,
            tickMsScale = 0.76f,
            bodyMsScale = 0.92f,
            minIntervalMs = 56L,
            minIntensity = 18,
            amplitudeCeilingScale = 0.94f,
            reason = "mid-range 0809A; extra gain with longer recovery"
        )
        DeviceProfile.LENOVO_Y700_GEN1 -> DeviceTuning(
            profileId = "LENOVO_Y700_GEN1",
            impactGain = 1.04f,
            kickMsScale = 0.82f,
            snareMsScale = 0.86f,
            tickMsScale = 0.76f,
            bodyMsScale = 0.92f,
            minIntervalMs = 54L,
            minIntensity = 14,
            textureGainScale = 1.04f,
            bodyGainScale = 0.96f,
            reason = "dual-actuator tablet balance"
        )
        DeviceProfile.LENOVO_Y700_GEN2 -> DeviceTuning(
            profileId = "LENOVO_Y700_GEN2",
            impactGain = 1.04f,
            kickMsScale = 0.78f,
            snareMsScale = 0.82f,
            tickMsScale = 0.70f,
            bodyMsScale = 0.86f,
            minIntervalMs = 52L,
            minIntensity = 14,
            reason = "0815 single-actuator tablet tuning"
        )
        DeviceProfile.SAMSUNG_S25 -> DeviceTuning(
            profileId = "SAMSUNG_S25",
            impactGain = 1.04f,
            kickMsScale = 0.68f,
            snareMsScale = 0.72f,
            tickMsScale = 0.62f,
            bodyMsScale = 0.76f,
            minIntervalMs = 48L,
            minIntensity = 11,
            textureGainScale = 1.06f,
            reason = "primitive-friendly flagship tuning"
        )
        DeviceProfile.VIVO_FLAGSHIP -> DeviceTuning(
            profileId = "VIVO_FLAGSHIP",
            impactGain = 1.05f,
            kickMsScale = 0.72f,
            snareMsScale = 0.76f,
            tickMsScale = 0.64f,
            bodyMsScale = 0.80f,
            minIntervalMs = 50L,
            minIntensity = 12,
            reason = "fast X-axis / IPC fallback tuning"
        )
        DeviceProfile.FLAGSHIP_XAXIS -> DeviceTuning(
            profileId = "FLAGSHIP_XAXIS",
            impactGain = 1.03f,
            kickMsScale = 0.62f,
            snareMsScale = 0.66f,
            tickMsScale = 0.54f,
            bodyMsScale = 0.70f,
            minIntervalMs = 44L,
            minIntensity = 10,
            amplitudeCeilingScale = 0.98f,
            textureGainScale = 1.10f,
            bodyGainScale = 0.76f,
            reason = "generic modern flagship fallback"
        )
        else -> fallback(profile)
    }

    
    fun current(): DeviceTuning = DeviceTuning()

    private fun fallback(profile: DeviceProfile): DeviceTuning {
        val actuator = profile.actuator
        val slow = actuator.responseTimeMs >= 8.0f
        val highQ = actuator.qFactor >= 16f
        return DeviceTuning(
            profileId = "PROFILE_FALLBACK",
            impactGain = if (slow) 1.02f else 1.00f,
            kickMsScale = if (slow) 0.95f else 0.82f,
            snareMsScale = if (slow) 0.98f else 0.86f,
            tickMsScale = if (highQ) 0.62f else 0.78f,
            bodyMsScale = if (slow) 1.02f else 0.90f,
            minIntervalMs = when {
                slow -> 62L
                highQ -> 46L
                else -> 54L
            },
            minIntensity = when {
                actuator.maxDisplacement < 0.78f -> 22
                highQ -> 10
                else -> 14
            },
            amplitudeCeilingScale = if (slow) 0.92f else 1.00f,
            textureGainScale = if (highQ) 1.08f else 1.00f,
            bodyGainScale = if (slow) 0.90f else 0.96f,
            reason = "derived from actuator response/Q; no named profile override"
        )
    }
}
