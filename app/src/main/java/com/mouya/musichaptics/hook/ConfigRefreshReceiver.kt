package com.mouya.musichaptics.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

internal class ConfigRefreshReceiver(
    private val onRefresh: () -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        // Accept only directed broadcast from own dashboard; reject spoofed refresh.
        val sender = getSendingPackage()
        val selfPkg = context.packageName
        // getSendingPackage may return null on some ROMs: allow but rely on throttle;
        // drop explicitly when sender is a foreign package.
        if (sender != null && sender != selfPkg && sender != "com.mouya.musichaptics") {
            Log.w(TAG, "[cfg] rejected refresh from $sender")
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRefreshMs < MIN_INTERVAL_MS) return
        lastRefreshMs = now
        Log.i(TAG, "[cfg] refresh broadcast received -> re-pulling prefs")
        runCatching { onRefresh() }
            .onFailure { Log.w(TAG, "[cfg] refresh failed: ${it.message}") }
    }

    companion object {
        const val ACTION = "com.mouya.musichaptics.ACTION_REFRESH_CONFIG"
        private const val TAG = "MusicHapticsX-Hook"
        private const val MIN_INTERVAL_MS = 1000L
        @Volatile private var lastRefreshMs = 0L

        fun register(context: Context, onRefresh: () -> Unit): ConfigRefreshReceiver? {
            val filter = IntentFilter(ACTION)
            return runCatching {
                ConfigRefreshReceiver(onRefresh).also { r ->
                    if (Build.VERSION.SDK_INT >= 33) {
                        // Hook side must be EXPORTED to receive cross-process broadcast;
                        // Security relies on sender check + throttle in onReceive, not NOT_EXPORTED.
                        context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        @Suppress("DEPRECATION")
                        context.registerReceiver(r, filter)
                    }
                }
            }.onFailure { Log.w(TAG, "registerReceiver failed: ${it.message}") }.getOrNull()
        }

        /**
         * Unified dashboard sender: deliver one-by-one to whitelisted packages, no global broadcast.
         * No setPackage(null) / package-less global broadcast.
         */
        fun sendRefresh(context: Context, targetPackages: List<String>) {
            if (targetPackages.isEmpty()) return
            val appId = context.packageName
            for (pkg in targetPackages.distinct().take(32)) {
                if (pkg.isBlank() || pkg == appId) continue
                runCatching {
                    context.sendBroadcast(Intent(ACTION).setPackage(pkg))
                }.onFailure { Log.w(TAG, "sendRefresh to $pkg failed: ${it.message}") }
            }
        }
    }
}