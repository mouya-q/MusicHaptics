package com.mouya.musichaptics

import android.content.Context
import android.util.Log
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Launches and supervises the root-side haptic receiver ([RootHapticServer]).
 *
 * Root belongs to the module app, not to the hooked music player, so the hooked
 * process can never start this itself (`su` is refused there). The module
 * process does it, and sends nothing to the actuator directly: the hooked app
 * posts UDP packets to 127.0.0.1:[DAEMON_PORT] and the server writes sysfs.
 *
 * [ensureRunningAsync] is called from [ConfigProvider]: every hooked app reads
 * its configuration through that provider when it starts, which also starts this
 * process, so the server comes up when music playback does, without anyone having
 * to open the module UI first.
 */
object RootHapticDaemon {
    private const val TAG = "RootHapticDaemon"
    const val DAEMON_PORT = 27042
    private const val SERVER_CLASS = "com.mouya.musichaptics.RootHapticServer"
    private val SAFE_NODE_RE = Regex("^/(sys|dev)/[A-Za-z0-9_/\\.\\-]+$")
    private val SAFE_APK_RE = Regex("^/[A-Za-z0-9_/\\.\\-=~]+$")
    private const val RETRY_THROTTLE_MS = 15_000L

    val isRunning = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val lastAttemptMs = AtomicLong(0L)
    private val lastOkMs = AtomicLong(0L)

    private fun isSafeNode(path: String): Boolean =
        path.length in 6..255 && SAFE_NODE_RE.matches(path) && !path.contains("..")

    /** The server keeps its socket bound, so a bind attempt tells whether one is alive. */
    fun isListening(): Boolean {
        val probe = DatagramSocket(null)
        return try {
            probe.reuseAddress = false
            probe.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), DAEMON_PORT))
            false
        } catch (_: java.net.BindException) {
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { probe.close() }
        }
    }

    /** Starts the server if it is not already up. Blocking; call from a worker thread. */
    fun start(context: Context, activatePath: String, amplitudePath: String?): Boolean {
        if (isListening()) {
            isRunning.set(true)
            return true
        }
        // Strict validation: these strings end up inside a root shell command.
        if (!isSafeNode(activatePath)) {
            Log.e(TAG, "Refusing unsafe activate node: $activatePath")
            return false
        }
        val ampPath = amplitudePath?.takeIf { it.isNotBlank() }
        if (ampPath != null && !isSafeNode(ampPath)) {
            Log.e(TAG, "Refusing unsafe amplitude node: $ampPath")
            return false
        }
        val apk = context.applicationInfo.sourceDir
        if (apk.isNullOrBlank() || !SAFE_APK_RE.matches(apk)) {
            Log.e(TAG, "Unexpected APK path, refusing to launch: $apk")
            return false
        }

        return try {
            // Remove the old nc based listener of earlier versions if one survived.
            runRoot("pkill -f 'nc.*$DAEMON_PORT' 2>/dev/null; true")
            val cmd = "CLASSPATH='$apk' nohup setsid app_process /system/bin " +
                "--nice-name=mhx_haptic $SERVER_CLASS '$activatePath' '${ampPath ?: "-"}' " +
                "$DAEMON_PORT >/dev/null 2>&1 &"
            Log.i(TAG, "Launching root haptic server activate=$activatePath amp=${ampPath ?: "-"}")
            runRoot(cmd)
            val deadline = android.os.SystemClock.elapsedRealtime() + 4000L
            var up = false
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (isListening()) { up = true; break }
                Thread.sleep(100)
            }
            isRunning.set(up)
            Log.i(TAG, "Root haptic server listening=$up")
            up
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start root haptic server: ${e.message}", e)
            false
        }
    }

    /**
     * Resolve the actuator nodes from the cached hardware probe and start the
     * server. Blocking, throttled; safe to call as often as convenient.
     */
    fun ensureRunning(context: Context): Boolean {
        if (isListening()) {
            isRunning.set(true)
            return true
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastAttemptMs.get()
        if (last != 0L && now - last < RETRY_THROTTLE_MS) return false
        lastAttemptMs.set(now)

        val prefs = context.getSharedPreferences("haptics_config", Context.MODE_PRIVATE)
        var nodes = prefs.getString(RootHardwareProbe.PREF_DIRECT_DRIVE_NODES, null)
        if (nodes.isNullOrBlank()) {
            // First run, or the cache was invalidated: probe (needs root).
            runCatching { RootHardwareProbe.probeAndPersist(context) }
            nodes = prefs.getString(RootHardwareProbe.PREF_DIRECT_DRIVE_NODES, null)
        }
        if (nodes.isNullOrBlank()) {
            Log.w(TAG, "No direct-drive nodes known; not starting the server")
            return false
        }
        if (!runCatching { RootHardwareProbe.hasRootAccess() }.getOrDefault(false)) {
            Log.w(TAG, "Root access unavailable for the module; not starting the server")
            return false
        }
        val activate = nodes.split(",").map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return false
        val dir = activate.substringBeforeLast('/')
        val amp = listOf("gain", "amplitude", "index_value")
            .map { "$dir/$it" }
            .firstOrNull { java.io.File(it).exists() }
        return start(context, activate, amp)
    }

    fun ensureRunningAsync(context: Context) {
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - lastOkMs.get() < 5_000L) return
        if (!busy.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        Thread({
            try {
                if (ensureRunning(app)) lastOkMs.set(android.os.SystemClock.elapsedRealtime())
            } catch (e: Throwable) {
                Log.w(TAG, "ensureRunning failed: ${e.message}")
            } finally {
                busy.set(false)
            }
        }, "mhx-root-daemon").apply { isDaemon = true }.start()
    }

    fun stop() {
        runRoot("pkill -f mhx_haptic 2>/dev/null; pkill -f 'nc.*$DAEMON_PORT' 2>/dev/null; true")
        isRunning.set(false)
        Log.i(TAG, "Root haptic server stopped")
    }

    private fun runRoot(command: String) {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        if (!p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        p.destroy()
    }
}
