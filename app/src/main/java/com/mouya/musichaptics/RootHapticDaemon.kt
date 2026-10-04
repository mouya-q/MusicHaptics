package com.mouya.musichaptics

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean





























object RootHapticDaemon {
    private const val TAG = "RootHapticDaemon"
    const val DAEMON_PORT = 27042

    @Volatile
    private var daemonProcess: Process? = null

    @Volatile
    private var daemonThread: Thread? = null

    val isRunning = AtomicBoolean(false)

    fun start(context: Context, activatePath: String, amplitudePath: String?): Boolean {
        if (isRunning.get()) {
            Log.i(TAG, "Daemon already running")
            return true
        }

        try {
            
            try {
                val killP = ProcessBuilder("su", "-c",
                    "pkill -f 'nc.*$DAEMON_PORT' 2>/dev/null; true"
                ).redirectErrorStream(true).start()
                killP.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                killP.destroyForcibly()
            } catch (_: Exception) {}

            
            
            val script = buildString {
                
                append("exec 3>'$activatePath'")
                if (amplitudePath != null && amplitudePath.isNotBlank()) {
                    append(" && exec 4>'$amplitudePath'")
                }
                
                append("; echo RHD_READY")
                
                
                append("; while true; do ")
                
                
                
                append("nc -u -l -p $DAEMON_PORT 127.0.0.1 | while IFS= read -r -n1 _byte; do ")
                append("echo 1 >&3 2>/dev/null; ")
                append("done 2>/dev/null; ")
                append("done")
            }

            Log.i(TAG, "Starting root UDP daemon on port $DAEMON_PORT")
            Log.i(TAG, "Script: $script")

            val pb = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
            daemonProcess = pb.start()

            
            Thread.sleep(1000)

            if (!daemonProcess!!.isAlive) {
                Log.e(TAG, "su process died immediately")
                try {
                    val output = daemonProcess!!.inputStream.bufferedReader().readText()
                    Log.e(TAG, "Output: $output")
                } catch (_: Exception) {}
                stop()
                return false
            }

            Log.i(TAG, "Root UDP daemon started on port $DAEMON_PORT (fd=3 → $activatePath)")
            isRunning.set(true)

            
            daemonThread = Thread {
                try {
                    val exitCode = daemonProcess?.waitFor()
                    Log.i(TAG, "Daemon exited with code $exitCode")
                } catch (e: Exception) {
                    Log.w(TAG, "Daemon watcher: ${e.message}")
                } finally {
                    isRunning.set(false)
                }
            }.apply { isDaemon = true; start() }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start root daemon: ${e.message}", e)
            stop()
            return false
        }
    }

    fun stop() {
        try {
            daemonProcess?.destroyForcibly()
        } catch (_: Exception) {}
        daemonProcess = null
        daemonThread = null
        isRunning.set(false)
        Log.i(TAG, "Root daemon stopped")
    }
}
