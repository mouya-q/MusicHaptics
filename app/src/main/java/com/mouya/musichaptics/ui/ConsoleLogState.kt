package com.mouya.musichaptics.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.mouya.musichaptics.LogBroadcaster

class ConsoleLogState(private val context: Context) : DefaultLifecycleObserver {

    private val MAX_LOGS = 300
    private val appContext = context.applicationContext
    private val logQueue = mutableStateListOf<String>().apply {
        addAll(ConsoleLogArchive.load(appContext))
    }
 
    val logs = logQueue

    private fun addLog(message: String) {
        if (logQueue.size >= MAX_LOGS) logQueue.removeAt(0)
        logQueue.add(message)
        ConsoleLogArchive.append(appContext, message)
    }
 
    fun clear() {
        logQueue.clear()
        ConsoleLogArchive.replace(appContext, emptyList())
    }

    fun exportToDownloads(): Result<String> = ConsoleLogArchive.exportToDownloads(appContext, logQueue.toList())

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