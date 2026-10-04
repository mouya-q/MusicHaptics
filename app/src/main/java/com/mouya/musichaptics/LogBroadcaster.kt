package com.mouya.musichaptics

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object LogBroadcaster {

    const val ACTION_LOG = "com.mouya.musichaptics.ACTION_LOG"
    const val EXTRA_LOG_MSG = "log_msg"

    private const val OWN_PACKAGE = "com.mouya.musichaptics"

    
    
    
    private const val WINDOW_MS = 1000L
    private const val MAX_PER_WINDOW = 16

    private val windowStartMs = AtomicLong(0L)
    private val windowCount = AtomicInteger(0)
    private val droppedTotal = AtomicLong(0L)
    private val lastDropReportMs = AtomicLong(0L)

    private fun allowBroadcast(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val start = windowStartMs.get()
        if (now - start >= WINDOW_MS && windowStartMs.compareAndSet(start, now)) {
            windowCount.set(0)
        }
        if (windowCount.incrementAndGet() <= MAX_PER_WINDOW) return true

        val dropped = droppedTotal.incrementAndGet()
        val lastReport = lastDropReportMs.get()
        if (now - lastReport >= 5000L && lastDropReportMs.compareAndSet(lastReport, now)) {
            Log.w("LogBroadcaster", "Log broadcast throttled; $dropped messages dropped so far")
        }
        return false
    }

    fun sendLog(context: Context, msg: String) {
        if (!allowBroadcast()) return
        try {
            val intent = Intent(ACTION_LOG).apply {
                putExtra(EXTRA_LOG_MSG, msg)

                setPackage(OWN_PACKAGE)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e("LogBroadcaster", "Failed to broadcast log: ${e.message}")
        }
    }

    fun log(context: Context, tag: String, msg: String) {
        sendLog(context, "[$tag] $msg")
    }
}
