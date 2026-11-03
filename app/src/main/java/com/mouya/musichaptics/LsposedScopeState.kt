package com.mouya.musichaptics

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

object LsposedScopeState {
    private const val TAG = "LsposedScopeState"

    enum class Source { SERVICE, LSPOSED_DB, FALLBACK, UNAVAILABLE }

    val packages = mutableStateOf<List<String>?>(null)
    val source = mutableStateOf(Source.UNAVAILABLE)

    /** Framework entries LSPosed lists in a scope; modules never run in them. */
    fun isFramework(packageName: String): Boolean = packageName == "system" || packageName == "android"

    @Volatile private var refreshing = false

    /**
     * Read the real scope from LSPosed's database (root). Keeps the service result
     * when the service is bound, and the recommended-list fallback when root or the
     * database is unavailable. Blocking; call from a worker thread.
     */
    fun refreshFromRoot(app: Application) {
        if (refreshing) return
        refreshing = true
        try {
            if (source.value == Source.SERVICE) return
            val scope = LspScopeReader.read(app) ?: return
            packages.value = scope.sorted()
            source.value = Source.LSPOSED_DB
        } finally {
            refreshing = false
        }
    }

    fun initFallback(app: Application) {
        if (source.value == Source.LSPOSED_DB || source.value == Source.SERVICE) return
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
        LsposedScopeState.initFallback(this)
        // The real scope (LSPosed's database, needs root) is read when the UI opens, not
        // here: this class is also instantiated whenever a hooked app wakes the module
        // process for its configuration, and that must not cost a root call every time.
    }

    override fun onServiceBind(service: XposedService) {
        val scope = try { service.scope?.toList()?.sorted() } catch (e: Exception) { null }
        if (!scope.isNullOrEmpty()) {
            LsposedScopeState.packages.value = scope
            LsposedScopeState.source.value = LsposedScopeState.Source.SERVICE
            Log.i("MusicHapticsApplication", "Service API connected; ${scope.size} scoped packages.")
        }
    }

    override fun onServiceDied(service: XposedService) {
        LsposedScopeState.source.value = LsposedScopeState.Source.UNAVAILABLE
        LsposedScopeState.initFallback(this)
    }
}