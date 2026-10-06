package com.mouya.musichaptics.hook

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import com.mouya.musichaptics.BuildConfig

internal class HookConfigPreferences(
    private val context: Context,
    private val targetPackage: String
) : SharedPreferences {
    @Volatile
    private var values: Map<String, Any> = emptyMap()

    init {
        refresh()
    }

    fun refresh() {
        val bundle = runCatching {
            context.contentResolver.call(
                android.net.Uri.parse("content://${BuildConfig.APPLICATION_ID}.provider"),
                "get_prefs",
                null,
                Bundle().apply { putString("target_package", targetPackage) }
            )
        }.getOrNull()

        
        
        
        if (bundle == null) {
            if (!refreshWarned.compareAndSet(false, true)) return
            android.util.Log.w(
                "MusicHapticsX-Prefs",
                "[prefs] ConfigProvider unreachable for $targetPackage; " +
                    "UI settings will NOT apply (all reads fall back to defaults)"
            )
            return
        }

        val next = HashMap<String, Any>(bundle.keySet().size)
        for (key in bundle.keySet()) {
            val value = bundle.get(key)
            if (value != null) next[key] = value
        }
        if (values.isEmpty() && next.isNotEmpty()) {
            android.util.Log.i(
                "MusicHapticsX-Prefs",
                "[prefs] loaded ${next.size} keys for $targetPackage: ${next.keys.take(12).joinToString(",")}"
            )
        }
        values = next
    }

    private val refreshWarned = java.util.concurrent.atomic.AtomicBoolean(false)

    
    
    
    
    override fun getAll(): Map<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        (key?.let { values[it] } as? String) ?: defValue

    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        (key?.let { values[it] } as? Set<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int =
        (key?.let { values[it] } as? Int) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        (key?.let { values[it] } as? Long) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        (key?.let { values[it] } as? Float) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        (key?.let { values[it] } as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = key != null && values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = ReadOnlyEditor(values.toMutableMap())

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private class ReadOnlyEditor(private val shadow: MutableMap<String, Any>) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String?): SharedPreferences.Editor = this
        override fun clear(): SharedPreferences.Editor = this
        override fun commit(): Boolean = false
        override fun apply() = Unit
    }
}