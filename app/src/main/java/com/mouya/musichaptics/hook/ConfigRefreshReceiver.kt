package com.mouya.musichaptics.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

/**
 * 5.2.9: ACTION_REFRESH_CONFIG 此前只有发送方、没有任何注册方。
 * UI 每次改设置都会广播这个 action，但注入进程里没有 receiver，
 * 于是 HookConfigPreferences 永远停留在启动那一刻的快照 ——
 * 这就是「设置改完完全没变化」的根因。
 *
 * 收到广播后立即重新拉取配置并重建 DSP 参数。
 */
internal class ConfigRefreshReceiver(
    private val onRefresh: () -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        Log.i(TAG, "[cfg] refresh broadcast received -> re-pulling prefs")
        runCatching { onRefresh() }
            .onFailure { Log.w(TAG, "[cfg] refresh failed: ${it.message}") }
    }

    companion object {
        const val ACTION = "com.mouya.musichaptics.ACTION_REFRESH_CONFIG"
        private const val TAG = "MusicHapticsX-Hook"

        fun register(context: Context, onRefresh: () -> Unit): ConfigRefreshReceiver? {
            val filter = IntentFilter(ACTION)
            return runCatching {
                ConfigRefreshReceiver(onRefresh).also { r ->
                    if (Build.VERSION.SDK_INT >= 33) {
                        context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        @Suppress("DEPRECATION")
                        context.registerReceiver(r, filter)
                    }
                }
            }.onFailure { Log.w(TAG, "registerReceiver failed: ${it.message}") }.getOrNull()
        }
    }
}