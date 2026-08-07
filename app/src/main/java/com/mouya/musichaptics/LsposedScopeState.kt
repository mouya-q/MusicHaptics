package com.mouya.musichaptics

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * Read-only mirror of the scope that LSPosed assigned to this module.
 *
 * The primary source is the LibXposed Service API (XposedServiceHelper).
 * If the service is unavailable — e.g. the installed LSPosed version doesn't
 * expose it, or the module is only registered as API 82 — we fall back to the
 * recommended scope declared in @array/xposed_scope, filtered to packages
 * that are actually installed on the device.  This keeps the UI functional
 * instead of showing "无法读取" when the user has clearly already selected
 * a scope in LSPosed.
 */
object LsposedScopeState {
    private const val TAG = "LsposedScopeState"

    enum class Source { SERVICE, FALLBACK, UNAVAILABLE }

    /** null = unavailable; empty = available but no app selected. */
    val packages = mutableStateOf<List<String>?>(null)
    val source = mutableStateOf(Source.UNAVAILABLE)

    fun initFallback(app: Application) {
        try {
            val scopeArray = app.resources.getStringArray(R.array.xposed_scope)
            val pm = app.packageManager
            val installed = scopeArray.filter { pkg ->
                try { pm.getApplicationInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
            }.sorted()
            packages.value = installed
            source.value = Source.FALLBACK
            Log.i(TAG, "Service API unavailable; showing ${installed.size} installed recommended-scope packages as fallback.")
        } catch (e: Exception) {
            Log.e(TAG, "Fallback also failed", e)
            packages.value = null
            source.value = Source.UNAVAILABLE
        }
    }
}

class MusicHapticsApplication : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        try {
            XposedServiceHelper.registerListener(this)
        } catch (e: Exception) {
            Log.w("MusicHapticsApplication", "XposedServiceHelper unavailable: ${e.message}")
        }
        // Immediately populate the fallback list so the UI isn't blank while
        // waiting for the (possibly never-arriving) service callback.
        LsposedScopeState.initFallback(this)
    }

    override fun onServiceBind(service: XposedService) {
        // Service connected — replace the fallback with the real scope.
        val scope = try { service.scope?.toList()?.sorted() } catch (e: Exception) { null }
        if (!scope.isNullOrEmpty()) {
            LsposedScopeState.packages.value = scope
            LsposedScopeState.source.value = LsposedScopeState.Source.SERVICE
            Log.i("MusicHapticsApplication", "Service API connected; ${scope.size} scoped packages.")
        }
    }

    override fun onServiceDied(service: XposedService) {
        // Service died — fall back to recommended scope again.
        LsposedScopeState.initFallback(this)
    }
}