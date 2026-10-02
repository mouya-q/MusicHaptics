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
    // Trim in batches instead of one removeAt(0) per appended line: each
    // removeAt(0) shifts the whole snapshot list, so per-line trimming was
    // O(n) per log. Batch trimming amortizes the shift.
    private val TRIM_BATCH = 32
    private val appContext = context.applicationContext
    private val logQueue = mutableStateListOf<String>().apply {
        addAll(ConsoleLogArchive.load(appContext))
    }
 
    val logs = logQueue

    private fun addLog(message: String) {
        if (logQueue.size >= MAX_LOGS) {
            val removeCount = minOf(TRIM_BATCH, logQueue.size)
            repeat(removeCount) { logQueue.removeAt(0) }
        }
        logQueue.add(message)
        ConsoleLogArchive.append(appContext, message)
    }
 
    fun clear() {
        logQueue.clear()
        ConsoleLogArchive.replace(appContext, emptyList())
    }

    override fun onStop(owner: LifecycleOwner) {
        // Best-effort durability for buffered lines when the UI goes away.
        ConsoleLogArchive.flush(appContext)
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