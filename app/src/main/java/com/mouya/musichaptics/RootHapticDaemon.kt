package com.mouya.musichaptics

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Root Haptic Daemon (RHD) — UDP Loopback + Pre-opened Root FD
 *
 * Phase 1: Minimal verification daemon.
 *
 * Architecture:
 *   MusicHapticsX App (has KernelSU root)
 *      │ ProcessBuilder("su", "-c", script)
 *      ▼
 *   Root process (daemon)
 *      ├── exec 3>/sys/.../activate  (open activate fd as root, persist)
 *      ├── exec 4>/sys/.../gain      (open gain fd as root, if exists)
 *      └── while true; do
 *            nc -u -l -p 27042 127.0.0.1 | while read -r -n1 byte; do
 *              echo "1" >&3   # Any UDP data → trigger vibration
 *            done
 *          done
 *
 * The C++ scheduler sends binary HapticUdpPacket (10 bytes) to this daemon.
 * In Phase 1, the daemon ignores packet contents — it just triggers echo 1 >&3
 * on any received UDP data. This validates the full communication chain:
 *   C++ sendto → nc → shell → root fd → sysfs → LRA vibration
 *
 * Phase 2 will add proper binary packet parsing (read amplitude byte, set gain,
 * conditional trigger).
 *
 * Safety: No eval. No arbitrary command execution. Only fixed "echo 1 >&3".
 */
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
            // Kill any previous instance on this port
            try {
                val killP = ProcessBuilder("su", "-c",
                    "pkill -f 'nc.*$DAEMON_PORT' 2>/dev/null; true"
                ).redirectErrorStream(true).start()
                killP.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                killP.destroyForcibly()
            } catch (_: Exception) {}

            // Build the daemon shell script
            // Phase 1: Any UDP packet received → echo 1 >&3 (trigger vibration)
            val script = buildString {
                // Open sysfs fds as root — held persistently for the daemon's lifetime
                append("exec 3>'$activatePath'")
                if (amplitudePath != null && amplitudePath.isNotBlank()) {
                    append(" && exec 4>'$amplitudePath'")
                }
                // Ready signal
                append("; echo RHD_READY")
                // Outer loop: nc exits after each connection (UDP datagram),
                // so we loop to restart the listener
                append("; while true; do ")
                // nc -u -l: listen for UDP on the port
                // read -r -n1: read one byte (any byte triggers the action)
                // We don't care about packet contents in Phase 1 — just trigger
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

            // Give su time to start, grant root, and open fds
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

            // Watcher thread: log when daemon exits
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
