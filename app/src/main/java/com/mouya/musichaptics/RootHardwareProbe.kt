package com.mouya.musichaptics

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    const val PREF_DIRECT_DRIVE_NODES = "direct_drive_nodes"

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

    fun getDirectDriveNodesAsync(context: Context, callback: (String) -> Unit) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString(PREF_DIRECT_DRIVE_NODES, null)
        if (cached != null && cached.isNotBlank()) {
            // Verify cached path still exists — use File.exists() (no root needed!)
            val exists = java.io.File(cached).exists()
            if (exists) {
                Log.i(TAG, "Using cached direct drive nodes: $cached")
                callback(cached)
                return
            } else {
                Log.w(TAG, "Cached direct drive nodes no longer exist, re-probing: $cached")
                prefs.edit().remove(PREF_DIRECT_DRIVE_NODES).apply()
            }
        }
        // Run detection in background thread
        Thread(Runnable {
            val nodes = getDirectDriveNodesBlocking()
            if (nodes.isNotBlank()) {
                prefs.edit().putString(PREF_DIRECT_DRIVE_NODES, nodes).apply()
                Log.i(TAG, "Detected and cached direct drive nodes: $nodes")
            }
            // Post result to callback
            Handler(Looper.getMainLooper()).post { callback(nodes) }
        }).start()
    }

    private fun getDirectDriveNodesBlocking(): String {
        val nodes = mutableListOf<String>()

        // ═══ Known hardware-specific vibrator control nodes ═══
        // These are ACTUAL FILE paths (not directories).
        // The C++ code opens them directly with open(path, O_WRONLY).
        // AW8697 sysfs nodes are world-writable (rw-r--r--), so we can
        // detect them WITHOUT root by using java.io.File.exists().
        val possiblePaths = listOf(
            // Awinic AW8697 — the real device path on Xiaomi 10 (umi)
            "/sys/devices/platform/soc/a8c000.i2c/i2c-2/2-005a/activate",
            // Awinic AW8697 — alternate I2C bus addresses
            "/sys/devices/platform/soc/a8c000.i2c/i2c-1/1-005a/activate",
            "/sys/devices/platform/soc/a8c000.i2c/i2c-4/4-005a/activate",
            // Awinic AW8697 — legacy bus path (may be symlink)
            "/sys/bus/i2c/drivers/aw8697_haptic/2-005a/activate",
            "/sys/bus/i2c/drivers/aw8697_haptic/1-005a/activate",
            // Standard timed_output vibrator
            "/sys/class/timed_output/vibrator/enable",
            // LED vibrator (generic)
            "/sys/class/leds/vibrator/activate",
            // Qualcomm haptics
            "/sys/class/qcom-haptics/enable"
        )

        // Phase 1: Check known paths WITHOUT root (java.io.File.exists)
        // This works because sysfs nodes are world-readable by default
        for (path in possiblePaths) {
            val file = java.io.File(path)
            if (file.exists()) {
                nodes.add(path)
                Log.i(TAG, "Found direct drive node (no-root): $path")
            }
        }

        // Phase 2: If no known path matched, try root-based auto-detection
        if (nodes.isEmpty()) {
            Log.i(TAG, "No known paths found via File.exists, trying root auto-detection...")
            val awResult = runRoot("find /sys/devices -name 'activate' -path '*aw8697*' 2>/dev/null | head -3")
            if (awResult != null && awResult.isNotBlank()) {
                awResult.trim().lines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("/sys/") && !nodes.contains(trimmed)) {
                        nodes.add(trimmed)
                        Log.i(TAG, "Auto-detected AW8697 node: $trimmed")
                    }
                }
            }
            if (nodes.isEmpty()) {
                val genResult = runRoot("find /sys -name 'activate' -path '*vibrator*' -o -name 'enable' -path '*vibrator*' -o -name 'enable' -path '*haptic*' 2>/dev/null | head -5")
                if (genResult != null && genResult.isNotBlank()) {
                    genResult.trim().lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("/sys/") && !nodes.contains(trimmed)) {
                            nodes.add(trimmed)
                            Log.i(TAG, "Auto-detected vibrator node: $trimmed")
                        }
                    }
                }
            }
        }

        Log.i(TAG, "getDirectDriveNodesBlocking result: ${nodes.joinToString(",")}")
        return nodes.joinToString(",")
    }

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