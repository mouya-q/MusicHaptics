package com.mouya.musichaptics.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.mouya.musichaptics.LogBroadcaster

class ConsoleLogState(private val context: Context) : DefaultLifecycleObserver {

    private val MAX_LOGS = 100
    private val logQueue = mutableStateListOf<String>()

    val logs = logQueue

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val msg = it.getStringExtra(LogBroadcaster.EXTRA_LOG_MSG)
                if (msg != null && msg.isNotBlank()) {

                    addLog(msg)
                }
            }
        }
    }

    init {

        val filter = IntentFilter(LogBroadcaster.ACTION_LOG)
        context.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun addLog(message: String) {

        if (logQueue.size >= MAX_LOGS) {
            logQueue.removeAt(0)
        }
        logQueue.add(message)
    }

    fun clear() {
        logQueue.clear()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        context.unregisterReceiver(broadcastReceiver)
    }

    companion object {

        @Volatile private var globalInstance: ConsoleLogState? = null

        fun setGlobalInstance(instance: ConsoleLogState?) {
            globalInstance = instance
        }

        fun addGlobalLog(message: String) {
            globalInstance?.addLog(message)
        }
    }
}

@Composable
fun rememberConsoleLogState(): ConsoleLogState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val logState = remember { ConsoleLogState(context) }

    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(logState)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(logState)
        }
    }

    DisposableEffect(Unit) {
        ConsoleLogState.setGlobalInstance(logState)
        onDispose {
            ConsoleLogState.setGlobalInstance(null)
        }
    }

    return logState
}
