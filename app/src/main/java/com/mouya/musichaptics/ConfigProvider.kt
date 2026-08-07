package com.mouya.musichaptics

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

class ConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null

        when (method) {

            "get_pref" -> {
                val key = arg ?: return null
                val prefs = ctx.getSharedPreferences("haptics_config", Context.MODE_PRIVATE)
                if (!prefs.contains(key)) return null

                val bundle = Bundle()
                when (val value = prefs.all[key]) {
                    is Boolean -> bundle.putBoolean(key, value)
                    is Float -> bundle.putFloat(key, value)
                    is Int -> bundle.putInt(key, value)
                    is Long -> bundle.putLong(key, value)
                    is String -> bundle.putString(key, value)
                }
                return bundle
            }

            "get_prefs" -> {
                // Start with the module-wide defaults, then overlay only the keys
                // explicitly customized for the requesting hooked package.
                val global = ctx.getSharedPreferences("haptics_config", Context.MODE_PRIVATE).all
                val targetPackage = extras?.getString("target_package").orEmpty()
                val scoped = if (targetPackage.isNotBlank()) {
                    ctx.getSharedPreferences("scoped_haptics_$targetPackage", Context.MODE_PRIVATE).all
                } else emptyMap()
                val allEntries = global + scoped
                if (allEntries.isEmpty()) return null
 
                val bundle = Bundle()
                for ((key, value) in allEntries) {
                    when (value) {
                        is Boolean -> bundle.putBoolean(key, value)
                        is Float -> bundle.putFloat(key, value)
                        is Int -> bundle.putInt(key, value)
                        is Long -> bundle.putLong(key, value)
                        is String -> bundle.putString(key, value)
                    }
                }
                return bundle
            }
        }
        return super.call(method, arg, extras)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}