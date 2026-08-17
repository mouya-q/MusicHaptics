package com.mouya.musichaptics

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

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
        // ── Xiaomi 数字系列 ──
        fp.contains("fuxi") -> "XIAOMI13_XAXIS"
        fp.contains("nuwa") -> "XIAOMI_13PRO"
        fp.contains("venus") || fp.contains("star") || fp.contains("mars") -> "XIAOMI11"
        fp.contains("cupid") || fp.contains("zeus") || fp.contains("psyche") -> "XIAOMI12"
        fp.contains("houji") || fp.contains("aurora") -> "XIAOMI14"
        fp.contains("haotai") -> "XIAOMI15"
        fp.contains("shenni") -> "XIAOMI_15PRO"
        fp.contains("zijin") -> "XIAOMI_17_PRO"
        fp.contains("umi") || fp.contains("cmi") || fp.contains("thyme") -> "XIAOMI10_XAXIS"
        fp.contains("babylon") || fp.contains("goku") -> "XIAOMI_MIX_FOLD"
        // ── Redmi K系列 ──
        fp.contains("rubens") -> "REDMI_K50_GAMING"
        fp.contains("alioth") || fp.contains("munch") || fp.contains("diting") -> "REDMI_K40"
        fp.contains("mondrian") || fp.contains("invenio") || fp.contains("corot") -> "REDMI_K60"
        fp.contains("vermeer") || fp.contains("manet") -> "REDMI_K70"
        fp.contains("rothko") -> "REDMI_K70U"
        fp.contains("k80") || (fp.contains("aw8697") && fp.contains("zaxis")) -> "REDMI_K80U_0809"
        // ── Lenovo ──
        fp.contains("tb320fc") || fp.contains("tb321fc") || fp.contains("y700") -> "LENOVO_Y700_GEN2"
        // ── OPPO ──
        fp.contains("reno8pro") -> "OPPO_RENO8_PRO"
        // ── OnePlus ──
        fp.contains("aston") -> "ONEPLUS_13T"
        fp.contains("plk") -> "ONEPLUS_15"
        fp.contains("opus") -> "ONEPLUS_13"
        fp.contains("waffle") -> "ONEPLUS_12"
        fp.contains("salami") -> "ONEPLUS_11"
        fp.contains("ovaltine") -> "ONEPLUS_10PRO"
        fp.contains("lemonade") -> "ONEPLUS_9"
        // ── Samsung ──
        fp.contains("s5e8855") || fp.contains("e1q") -> "SAMSUNG_S25"
        // ── vivo ──
        fp.contains("pd24") || fp.contains("pd23") -> "VIVO_FLAGSHIP"
        else -> "DEFAULT"
    }
}