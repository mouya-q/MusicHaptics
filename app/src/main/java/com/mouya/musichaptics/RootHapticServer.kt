package com.mouya.musichaptics

import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Persistent root-side receiver for direct-drive haptic packets.
 *
 * Started once by [RootHapticDaemon] as
 * `su -c app_process /system/bin com.mouya.musichaptics.RootHapticServer ...`.
 * It deliberately uses nothing from the Android framework: it runs in a bare
 * app_process VM with the module APK on the class path.
 *
 * It replaces the former `nc | dd | od | expr` shell loop, which respawned five
 * processes per packet. Between two packets nothing was bound to the port, so a
 * beat arriving in that window drew an ICMP port-unreachable, the app's native
 * transport marked the daemon dead for good, and direct drive was abandoned.
 * Here the socket stays bound and the sysfs files stay open.
 *
 * Packet (10 bytes): magic u32 LE 0x3148584D | version u16 LE = 1 |
 * durationMs u16 BE | amplitude u8 | flags u8. A packet with duration 0 and
 * amplitude 0 is a liveness probe and never drives the actuator.
 */
object RootHapticServer {
    private const val MAGIC = 0x3148584D
    private const val VERSION = 1
    private const val PACKET_LEN = 10
    private val SAFE_NODE = Regex("^/(sys|dev)/[A-Za-z0-9_/.\\-]+$")

    private fun safe(path: String) =
        path.length in 6..255 && SAFE_NODE.matches(path) && !path.contains("..")

    private fun open(path: String?): FileOutputStream? {
        if (path == null || !safe(path) || !File(path).exists()) return null
        return try { FileOutputStream(path) } catch (_: Exception) { null }
    }

    private fun put(out: FileOutputStream?, text: String) {
        if (out == null) return
        try {
            out.write(text.toByteArray())
            out.flush()
        } catch (_: Exception) {
            // A single failed write must not kill the server.
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            System.err.println("usage: RootHapticServer <activate-node> <gain-node|-> [port]")
            return
        }
        val activatePath = args[0]
        val ampPath = args[1].takeIf { it != "-" }
        val port = args.getOrNull(2)?.toIntOrNull() ?: RootHapticDaemon.DAEMON_PORT
        if (!safe(activatePath) || (ampPath != null && !safe(ampPath))) {
            System.err.println("RHS_REFUSED unsafe node path")
            return
        }

        val socket = try {
            DatagramSocket(port, InetAddress.getByName("127.0.0.1"))
        } catch (_: Exception) {
            // Another server instance already owns the port: that one keeps serving.
            println("RHS_BUSY")
            return
        }

        val dir = activatePath.substringBeforeLast('/', "")
        val isActivate = activatePath.endsWith("activate")
        val isGain = ampPath?.endsWith("gain") == true
        val activateOut = open(activatePath)
        if (activateOut == null) {
            System.err.println("RHS_FAILED cannot open $activatePath")
            socket.close()
            return
        }
        val ampOut = open(ampPath)
        // AW8697 needs mode -> duration -> gain -> activate (see RootHapticDaemon history):
        // the stock driver leaves activate_mode in rtp mode where activate replays an
        // empty RAM waveform and the motor never moves.
        val modeOut = if (isActivate) open("$dir/activate_mode") else null
        val durationOut = if (isActivate) open("$dir/duration") else null
        val fourStep = modeOut != null && durationOut != null
        println("RHS_READY port=$port activate=$activatePath amp=${ampPath ?: "-"} fourStep=$fourStep")

        val buf = ByteArray(32)
        val packet = DatagramPacket(buf, buf.size)
        while (true) {
            try {
                packet.length = buf.size
                socket.receive(packet)
            } catch (_: Exception) {
                continue
            }
            if (packet.length != PACKET_LEN) continue
            val magic = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8) or
                ((buf[2].toInt() and 0xFF) shl 16) or ((buf[3].toInt() and 0xFF) shl 24)
            val version = (buf[4].toInt() and 0xFF) or ((buf[5].toInt() and 0xFF) shl 8)
            if (magic != MAGIC || version != VERSION) continue
            var duration = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
            val amplitude = buf[8].toInt() and 0xFF
            if (duration == 0 && amplitude == 0) continue  // liveness probe
            duration = duration.coerceIn(1, 5000)

            if (ampOut != null) {
                if (isGain) put(ampOut, String.format("0x%02x", minOf(amplitude, 127)))
                else put(ampOut, amplitude.toString())
            }
            if (fourStep) {
                put(modeOut, "0\n")
                put(durationOut, "$duration\n")
                put(activateOut, "1\n")
            } else if (isActivate) {
                put(activateOut, "1\n")
            } else {
                put(activateOut, "$duration\n")
            }
        }
    }
}
