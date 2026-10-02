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

    fun getDirectDriveNodesAsync(context: Context, allowRootProbe: Boolean = true, callback: (String) -> Unit) {
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
            val nodes = getDirectDriveNodesBlocking(allowRootProbe)
            if (nodes.isNotBlank()) {
                prefs.edit().putString(PREF_DIRECT_DRIVE_NODES, nodes).apply()
                Log.i(TAG, "Detected and cached direct drive nodes: $nodes")
            }
            // Post result to callback
            Handler(Looper.getMainLooper()).post { callback(nodes) }
        }).start()
    }

    private fun getDirectDriveNodesBlocking(allowRootProbe: Boolean): String {
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
            // Qualcomm haptics / AW86224 family (common on newer Xiaomi flagships)
            "/sys/class/qcom-haptics/enable",
            "/sys/class/qcom_haptic/enable"
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

        // Phase 2 is optional. Hooked target processes stay root-free by default;
        // the module UI can request the expensive scan explicitly.
        if (nodes.isEmpty() && allowRootProbe) {
            Log.i(TAG, "No known paths found via File.exists, trying root auto-detection...")
            val awResult = runRoot("find /sys/devices /sys/bus/i2c/drivers -type f \\( \\( -name 'activate' -o -name 'enable' \\) \\) \\( -path '*aw8697*' -o -path '*aw86224*' -o -path '*qcom*haptic*' \\) 2>/dev/null | head -6")
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
                val genResult = runRoot("find /sys -type f \\( -name 'activate' -o -name 'enable' \\) \\( -path '*vibrator*' -o -path '*haptic*' -o -path '*qpnp*' \\) 2>/dev/null | head -8")
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

    private fun profileForFingerprint(fp: String): String {
        val xiaomi = fp.contains("xiaomi") || fp.contains("redmi") || fp.contains("poco")
        if (xiaomi) {
            // Most specific model families first so e.g. "Xiaomi 14 Ultra" cannot
            // be swallowed by the generic Xiaomi 14 branch.
            when {
                fp.contains("rothko") || fp.contains("k70 ultra") || fp.contains("k70u") -> return "REDMI_K70U"
                fp.contains("k80") -> return "REDMI_K80U_0809"
                // Xiaomi 14 / 14 Pro are separate device codenames but use the
                // same project-level actuator/rendering family.
                fp.contains("shennong") || fp.contains("23116pn") ||
                    fp.contains("houji") || fp.contains("23127pn") -> return "XIAOMI14"
                fp.contains("aurora") || fp.contains("ishtar") || fp.contains("2304fpn6") ||
                    fp.contains("24030pn") || fp.contains("24031pn") || fp.contains("25019pn") || fp.contains("25042pn") ||
                    (fp.contains("product_model=xiaomi") && fp.contains("ultra")) || fp.contains("eiffel") -> return "XIAOMI_ULTRA"
                fp.contains("zijin") || fp.contains("pandora") || fp.contains("25081pn") || fp.contains("25091pn") ||
                    fp.contains("25098pn5") || fp.contains("17 pro") -> return "XIAOMI_17_PRO"
                // haotian is the Xiaomi 15 Pro codename; shenni is retained as a
                // compatibility alias for older ports.
                fp.contains("haotian") || fp.contains("shenni") || fp.contains("2410dpn6") ||
                    (fp.contains("24129pn") && fp.contains("pro")) -> return "XIAOMI_15PRO"
                fp.contains("haotai") || fp.contains("dada") || fp.contains("24129pn74") -> return "XIAOMI_15"
                fp.contains("fuxi") -> return "XIAOMI13_XAXIS"
                fp.contains("nuwa") -> return "XIAOMI_13PRO"
                fp.contains("cupid") || fp.contains("zeus") || fp.contains("psyche") -> return "XIAOMI12"
                fp.contains("venus") || fp.contains("star") || fp.contains("mars") -> return "XIAOMI11"
                fp.contains("umi") || fp.contains("cmi") || fp.contains("thyme") -> return "XIAOMI10_XAXIS"
                fp.contains("babylon") || fp.contains("goku") || fp.contains("mix fold") || fp.contains("mixfold") -> return "XIAOMI_MIX_FOLD"
                fp.contains("rubens") -> return "REDMI_K50_GAMING"
                fp.contains("alioth") || fp.contains("munch") || fp.contains("diting") -> return "REDMI_K40"
                fp.contains("mondrian") || fp.contains("invenio") || fp.contains("corot") -> return "REDMI_K60"
                fp.contains("vermeer") || fp.contains("manet") -> return "REDMI_K70"
            }
        }

        when {
            fp.contains("tb320fc") || fp.contains("tb321fc") || fp.contains("y700 2023") || fp.contains("y700 2024") || fp.contains("y700pro") -> return "LENOVO_Y700_GEN2"
            fp.contains("y700") || fp.contains("tb9707") -> return "LENOVO_Y700_GEN1"
            fp.contains("reno8pro") -> return "OPPO_RENO8_PRO"
            fp.contains("aston") -> return "ONEPLUS_13T"
            fp.contains("plk") -> return "ONEPLUS_15"
            fp.contains("opus") -> return "ONEPLUS_13"
            fp.contains("waffle") -> return "ONEPLUS_12"
            fp.contains("salami") -> return "ONEPLUS_11"
            fp.contains("ovaltine") -> return "ONEPLUS_10PRO"
            fp.contains("lemonade") -> return "ONEPLUS_9"
            fp.contains("ace3pro") -> return "ONEPLUS_ACE3PRO"
            fp.contains("ace5") || fp.contains("ace3") -> return "ONEPLUS_ACE_MID"
            fp.contains("s5e8855") || fp.contains("e1q") || fp.contains("sm-s93") || fp.contains("sm-s92") -> return "SAMSUNG_S25"
            fp.contains("pd24") || fp.contains("pd23") -> return "VIVO_FLAGSHIP"
        }

        return "DEFAULT"
    }
}