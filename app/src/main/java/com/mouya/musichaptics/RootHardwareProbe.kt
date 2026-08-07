package com.mouya.musichaptics

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Reads a small, non-identifying hardware fingerprint through Magisk/su.
 * Build.MODEL is deliberately not used to select a profile: it is easily
 * spoofed. Kernel board / boot hardware / vibrator-driver paths are preferred.
 *
 * A kernel normally does not expose LRA f0, Q or coil calibration to userspace.
 * Consequently this class only selects profiles that have a known board mapping;
 * unknown hardware remains on the conservative DEFAULT profile instead of
 * inventing physical measurements.
 */
object RootHardwareProbe {
    private const val TAG = "RootHardwareProbe"
    private const val PREFS = "haptics_config"
    const val PREF_ROOT_OK = "hardware_root_verified"
    const val PREF_PROFILE = "hardware_profile_id"
    const val PREF_FINGERPRINT = "hardware_root_fingerprint"

    data class Result(val rootGranted: Boolean, val profileId: String, val fingerprint: String)

    fun probeAndPersist(context: Context): Result {
        val output = runRoot(
            "echo boot_hardware=\$(getprop ro.boot.hardware); " +
                "echo board_platform=\$(getprop ro.board.platform); " +
                "echo product_board=\$(getprop ro.product.board); " +
                "echo product_model=\$(getprop ro.product.model); " +
                "echo device_tree=\$(cat /proc/device-tree/model 2>/dev/null | tr '\\000' ' '); " +
                "echo vibrator_nodes=\$(find /sys/class /sys/devices -type d \\( -iname '*vibrator*' -o -iname '*haptic*' -o -iname '*aw86*' -o -iname '*qpnp*' -o -iname '*leds*' \\) 2>/dev/null | head -16 | tr '\\n' ',')"
        )
        val granted = output != null
        val normalized = output.orEmpty().lowercase()
        val profileId = profileForFingerprint(normalized)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_ROOT_OK, granted)
            .putString(PREF_PROFILE, profileId)
            .putString(PREF_FINGERPRINT, output.orEmpty().take(1200))
            .apply()
        Log.i(TAG, "Root=$granted; selected profile=$profileId")
        return Result(granted, profileId, output.orEmpty())
    }

    fun hasRootAccess(): Boolean = runRoot("id")?.contains("uid=0") == true

    private fun runRoot(command: String): String? = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        if (!process.waitFor(4, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly(); null
        } else {
            BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        }
    } catch (_: Exception) { null }

    private fun profileForFingerprint(fp: String): String = when {
        // Board codenames / hardware nodes; no marketing model strings are trusted.
        fp.contains("fuxi") -> "XIAOMI13_XAXIS"
        fp.contains("haotai") || fp.contains("shenni") -> "XIAOMI15"
        fp.contains("umi") || fp.contains("cmi") || fp.contains("thyme") -> "XIAOMI10_XAXIS"
        fp.contains("k80") || (fp.contains("aw8697") && fp.contains("zaxis")) -> "REDMI_K80U_0809"
        fp.contains("aston") -> "ONEPLUS_13T"
        fp.contains("plk110") || fp.contains("plk") -> "ONEPLUS_15"
        fp.contains("opus") -> "ONEPLUS_15" // OnePlus 13, similar class
        // Samsung S25 series: e1q / s5e8855 board
        fp.contains("s5e8855") || fp.contains("e1q") -> "SAMSUNG_S25"
        // vivo X200 series: kalama / mt6985
        fp.contains("pd24") || fp.contains("pd23") -> "VIVO_FLAGSHIP"
        // A root fingerprint confirms only the driver family, not physical f0/Q.
        // Keep DEFAULT until a board has validated actuator measurements.
        else -> "DEFAULT"
    }
}