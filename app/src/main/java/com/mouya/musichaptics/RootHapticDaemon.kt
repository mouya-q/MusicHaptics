package com.mouya.musichaptics

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

object RootHapticDaemon {
    private const val TAG = "RootHapticDaemon"
    const val DAEMON_PORT = 27042
    // P1: Keep in sync with NativeBridge.cpp MHX_UDP_MAGIC / VERSION.
    // Native packet (packed, 10B): magic u32 LE | version u16 LE | durationMs u16 BE | amplitude u8 | flags u8
    private const val UDP_MAGIC_LE = "4d584831" // 0x3148584D little-endian hex
    private const val UDP_PACKET_LEN = 10
    private val SAFE_NODE_RE = Regex("^/(sys|dev)/[A-Za-z0-9_/\\.\\-]+\$")

    private fun isSafeNode(path: String): Boolean {
        if (path.length < 6 || path.length > 255) return false
        if (!SAFE_NODE_RE.matches(path)) return false
        if (path.contains("..")) return false
        return true
    }

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
        // P1: Strict node-path validation; reject on mismatch to block su injection.
        if (!isSafeNode(activatePath)) {
            Log.e(TAG, "Refusing unsafe activate node: $activatePath")
            return false
        }
        val ampPath = amplitudePath?.takeIf { it.isNotBlank() }?.also {
            if (!isSafeNode(it)) {
                Log.e(TAG, "Refusing unsafe amplitude node: $it")
                return false
            }
        }

        try {
            try {
                val killP = ProcessBuilder("su", "-c",
                    "pkill -f 'nc.*$DAEMON_PORT' 2>/dev/null; true"
                ).redirectErrorStream(true).start()
                val killed = killP.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                if (!killed) killP.destroyForcibly() else killP.destroy()
            } catch (_: Exception) {}

            // P1: Parser aligned with native HapticUdpPacket (10B):
            // magic u32 LE=4d584831 | ver u16 LE=0100 | dur u16 BE | amp u8 | flags u8
            // Drop on length/magic/version mismatch; no blind activate on any bytes.
            val isActivateNode = activatePath.contains("activate")
            val isGainNode = ampPath?.contains("gain") == true
            val script = buildString {
                append("exec 3>'$activatePath'")
                if (ampPath != null) {
                    append(" && exec 4>'$ampPath'")
                }
                append("; echo RHD_READY")
                append("; while true; do ")
                // Single packet waits at most 2s; avoid stuck nc blocking restart.
                append("pkt=${'$'}(nc -u -l -p ${'$'}DAEMON_PORT -w 2 127.0.0.1 2>/dev/null | dd bs=${'$'}UDP_PACKET_LEN count=1 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \\n'); ")
                // Length must be 10 bytes -> 20 hex chars.
                append("if [ ${'$'}{#pkt} -ne 20 ]; then continue; fi; ")
                append("case \"${'$'}pkt\" in ${'$'}UDP_MAGIC_LE*) ;; *) continue;; esac; ")
                // Version LE 01 00 at hex chars 9-12.
                append("ver=${'$'}(expr substr \"${'$'}pkt\" 9 4 2>/dev/null); ")
                append("if [ \"${'$'}ver\" != \"0100\" ]; then continue; fi; ")
                // duration BE at hex chars 13-16, amplitude at 17-18.
                append("dur_hex=${'$'}(expr substr \"${'$'}pkt\" 13 4 2>/dev/null); amp_hex=${'$'}(expr substr \"${'$'}pkt\" 17 2 2>/dev/null); ")
                append("dur=${'$'}((16_${'$'}{dur_hex})) 2>/dev/null || continue; amp=${'$'}((16_${'$'}{amp_hex})) 2>/dev/null || continue; ")
                append("if [ \"${'$'}dur\" -lt 1 ]; then dur=5; fi; if [ \"${'$'}dur\" -gt 5000 ]; then dur=5000; fi; ")
                append("if [ \"${'$'}amp\" -lt 0 ]; then amp=0; fi; if [ \"${'$'}amp\" -gt 255 ]; then amp=255; fi; ")
                if (ampPath != null) {
                    if (isGainNode) {
                        // AW8697 gain register expects hex value 0x00-0xc8 (0-200).
                        // Map amplitude 0-255 to gain 0-200 and write as hex.
                        append("gain_val=${'$'}(( ${'$'}amp * 200 / 255 )); ")
                        append("printf '0x%02x' ${'$'}{gain_val} >&4 2>/dev/null; ")
                    } else {
                        append("echo \"${'$'}amp\" >&4 2>/dev/null; ")
                    }
                }
                if (isActivateNode) {
                    append("echo 1 >&3 2>/dev/null; ")
                } else {
                    append("echo \"${'$'}dur\" >&3 2>/dev/null; ")
                }
                append("done")
            }

            Log.i(TAG, "Starting root UDP daemon on port $DAEMON_PORT")
            // Log port and packet len only; avoid dumping full script.
            Log.i(TAG, "UDP packet len=$UDP_PACKET_LEN magic=$UDP_MAGIC_LE activate=$activatePath amp=${ampPath ?: "(none)"}")

            val pb = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
            daemonProcess = pb.start()

            // P1: Bounded ready-wait instead of fixed sleep(1000); poll up to 3s.
            val deadline = android.os.SystemClock.elapsedRealtime() + 3000L
            var alive = false
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val p = daemonProcess ?: break
                if (!p.isAlive) break
                alive = true
                // Live su process means channel ready; RHD_READY echoed to su stdout.
                // Avoid blocking readText(); yield briefly then recheck.
                Thread.sleep(200)
                if (p.isAlive) break
            }

            val proc = daemonProcess
            if (proc == null || !proc.isAlive) {
                Log.e(TAG, "su process died immediately")
                runCatching {
                    // Bounded read; avoid infinite blocking readText().
                    val out = proc?.inputStream?.bufferedReader()?.readLines()?.takeLast(20)?.joinToString("\n")
                    if (!out.isNullOrBlank()) Log.e(TAG, "Output: $out")
                }
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
        // P1: Cleanup stale nc listener on stop to avoid port conflict.
        runCatching {
            val p = ProcessBuilder("su", "-c", "pkill -f 'nc.*$DAEMON_PORT' 2>/dev/null; true")
                .redirectErrorStream(true).start()
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            p.destroy()
        }
        isRunning.set(false)
        Log.i(TAG, "Root daemon stopped")
    }
}