package com.mouya.musichaptics

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle


class ConfigProvider : ContentProvider() {
    companion object {
        private val PACKAGE_RE = Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$")
        private val SAFE_KEYS = setOf(
            "master_switch", "haptic_amplitude", "selected_preset",
            "style_preset", "haptic_bass_boost", "force_default_amplitude", "visualizer_fallback_enabled",
            "synth_rate_hz", "synth_lra_f0", "synth_lra_q",
            "synth_attack_impact", "synth_decay_impact", "synth_attack_continuous",
            "synth_decay_continuous", "synth_release", "synth_sustain",
            "synth_thermal_warn", "synth_thermal_crit", "synth_thermal_rth",
            "synth_thermal_cth", "synth_impact_gain", "synth_continuous_gain",
            "synth_texture_gain", "synth_master_gain", "device_profile",
            "hardware_root_verified", "hardware_profile_id", "hardware_root_fingerprint",
            "direct_drive_nodes"
        )
        // P0: hardware paths and fingerprints must never leave the module process.
        // Only the module UID may read them; hooked apps get audio/haptic keys.
        private val SENSITIVE_KEYS = setOf(
            "hardware_root_verified", "hardware_profile_id", "hardware_root_fingerprint",
            "direct_drive_nodes"
        )
    }

    override fun onCreate(): Boolean = true

    private fun isModuleCaller(caller: String?): Boolean {
        if (caller == null) return false
        return try {
            caller == context?.packageName || caller == BuildConfig.APPLICATION_ID
        } catch (_: Exception) { false }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val caller = callingPackage
        val targetPackage = extras?.getString("target_package").orEmpty()
        val ownPackage = BuildConfig.APPLICATION_ID
        val packageOk = targetPackage.isNotBlank() && PACKAGE_RE.matches(targetPackage)
        if (!packageOk) return null

        // P0: unknown caller (null) must not read config; previously it fell through.
        if (caller == null) return null
        val moduleCaller = isModuleCaller(caller)
        // Non-module callers must be the target app itself AND whitelisted.
        if (!moduleCaller) {
            if (caller != targetPackage) return null
            if (!WhitelistManager().isPackageAllowed(caller)) return null
        }

        val globalPrefs = ctx.getSharedPreferences("haptics_config", Context.MODE_PRIVATE)
        return when (method) {
            "get_pref" -> {
                val key = arg ?: return null
                if (key !in SAFE_KEYS || !globalPrefs.contains(key)) return null
                // P0: sensitive keys never leave the module process.
                if (key in SENSITIVE_KEYS && !moduleCaller) return null
                bundleOfValue(key, globalPrefs.all[key])
            }
            "get_prefs" -> {
                val scoped = ctx.getSharedPreferences("scoped_haptics_$targetPackage", Context.MODE_PRIVATE).all
                val bundle = Bundle()
                for ((key, value) in globalPrefs.all) {
                    if (key !in SAFE_KEYS) continue
                    if (key in SENSITIVE_KEYS && !moduleCaller) continue
                    put(bundle, key, value)
                }
                for ((key, value) in scoped) {
                    if (key !in SAFE_KEYS) continue
                    if (key in SENSITIVE_KEYS && !moduleCaller) continue
                    put(bundle, key, value)
                }
                bundle.takeUnless { it.isEmpty }
            }
            else -> null
        }
    }

    private fun bundleOfValue(key: String, value: Any?): Bundle? = Bundle().also { put(it, key, value) }.takeIf { value != null }

    private fun put(bundle: Bundle, key: String, value: Any?) {
        when (value) {
            is Boolean -> bundle.putBoolean(key, value)
            is Float -> bundle.putFloat(key, value)
            is Int -> bundle.putInt(key, value)
            is Long -> bundle.putLong(key, value)
            is Double -> bundle.putDouble(key, value)
            is String -> bundle.putString(key, value)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}