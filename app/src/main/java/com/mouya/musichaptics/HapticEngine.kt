package com.mouya.musichaptics

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.mouya.musichaptics.BuildConfig
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

import com.mouya.musichaptics.LogBroadcaster
import com.mouya.musichaptics.NativeBridge
import com.mouya.musichaptics.audio.AudioIngress
import com.mouya.musichaptics.haptic.HapticImpactPolicy
import com.mouya.musichaptics.haptic.DeviceTuningRegistry
import android.os.ParcelFileDescriptor
import java.net.DatagramSocket
interface LogCallback {
    fun onLog(message: String)
}


internal data class BeatTiming(val mul: Float, val min: Long, val max: Long)

/**
 * Smooth the final user/preset gain before the device-specific hard ceiling.
 * The former direct clamp made every value above 2.0 identical, so the top
 * half of the level slider had little or no perceptible effect on loud music.
 */
internal fun softLimitOutputAmplitude(raw: Float): Float {
    if (!raw.isFinite()) return 0.3f
    val value = raw.coerceAtLeast(0f)
    val knee = 1.6f
    val ceiling = 2.0f
    val limited = if (value <= knee) {
        value
    } else {
        knee + (ceiling - knee) * (1f - exp(-(value - knee) / 0.8f))
    }
    return limited.coerceIn(0.3f, ceiling)
}


internal data class BeatShape(
    val force: BeatTiming,
    val ampCtrl: BeatTiming,
    val plain: BeatTiming,
    val ampBase: Float,
    val attackFrac: Float,
    val sustainFrac: Float,
    val attackAmpFrac: Float = 1.0f,
    val sustainAmpFrac: Float = 0.75f,
    val decayAmpFrac: Float = 0.30f,
    
    val weight: (DeviceProfile) -> Float = { 1.0f }
) {
    val hasSustain: Boolean get() = sustainFrac > 0f
}


enum class StylePreset(
    val key: String,
    val label: String,
    val description: String,
    val cooldownMs: Long,
    val ampScale: Float,
    val sharpness: Float,
    val attackScale: Float,
    val lowCutHz: Float,
    val highCutHz: Float,
    val onsetThreshold: Float,
    val accentScale: Float
) {
    BALANCED(
        "balanced", "均衡", "Balanced",
        60L, 1.00f, 0.32f, 1.00f, 55f, 650f, 0.08f, 1.00f
    ),
    BASS(
        "bass", "低频", "Bass",
        70L, 1.22f, 0.20f, 1.45f, 32f, 420f, 0.07f, 1.35f
    ),
    CRISP(
        "crisp", "清脆", "Crisp",
        45L, 0.92f, 0.62f, 0.55f, 90f, 1400f, 0.11f, 0.85f
    ),
    SOFT(
        "soft", "柔和", "Soft",
        80L, 0.66f, 0.14f, 1.80f, 45f, 520f, 0.06f, 0.60f
    ),
    IMMERSIVE(
        "immersive", "强劲", "Immersive",
        55L, 1.35f, 0.42f, 0.78f, 38f, 900f, 0.05f, 1.55f
    ),
    PURE(
        "pure", "纯净", "Pure",
        120L, 1.12f, 0.74f, 0.42f, 120f, 2200f, 0.19f, 1.25f
    );

    companion object {
        fun fromKey(key: String?): StylePreset =
            entries.firstOrNull { it.key == key } ?: BALANCED
    }
}


class HapticEngine(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val targetPackage: String = context.packageName
) {
    companion object {
        private const val TAG = "HapticDSPCore"

        
        internal val BEAT_SHAPES: Map<String, BeatShape> = mapOf(
            
            "SUB" to BeatShape(
                force = BeatTiming(22f, 80L, 150L),
                ampCtrl = BeatTiming(13f, 35L, 80L),
                plain = BeatTiming(17f, 50L, 100L),
                ampBase = 255f, attackFrac = 0.30f, sustainFrac = 0.50f,
                attackAmpFrac = 1.0f, sustainAmpFrac = 0.85f, decayAmpFrac = 0.40f,
                weight = { it.subWeight }
            ),
            
            "KICK" to BeatShape(
                force = BeatTiming(4f, 30L, 50L),
                ampCtrl = BeatTiming(3f, 15L, 40L),
                plain = BeatTiming(3.5f, 20L, 45L),
                ampBase = 255f, attackFrac = 0.08f, sustainFrac = 0.25f,
                attackAmpFrac = 1.0f, sustainAmpFrac = 0.60f, decayAmpFrac = 0.15f,
                weight = { it.subWeight }
            ),
            
            "SNARE" to BeatShape(
                force = BeatTiming(7.5f, 30L, 55L),
                ampCtrl = BeatTiming(4.5f, 12L, 30L),
                plain = BeatTiming(6.5f, 20L, 40L),
                ampBase = 160f, attackFrac = 0.25f, sustainFrac = 0.45f,
                attackAmpFrac = 1.0f, sustainAmpFrac = 0.60f, decayAmpFrac = 0.20f,
                weight = { it.midWeight }
            ),
            
            "TICK" to BeatShape(
                force = BeatTiming(3.5f, 12L, 25L),
                ampCtrl = BeatTiming(1.8f, 5L, 15L),
                plain = BeatTiming(2.8f, 8L, 20L),
                ampBase = 80f, attackFrac = 0.40f, sustainFrac = 0f,
                attackAmpFrac = 1.0f, decayAmpFrac = 0.30f,
                weight = { it.presenceWeight }
            ),
            
            "BODY" to BeatShape(
                force = BeatTiming(9f, 35L, 60L),
                ampCtrl = BeatTiming(3.5f, 12L, 25L),
                plain = BeatTiming(5.5f, 20L, 40L),
                ampBase = 100f, attackFrac = 0.35f, sustainFrac = 0.40f,
                attackAmpFrac = 0.70f, sustainAmpFrac = 1.0f, decayAmpFrac = 0.30f
            ),
            
            "VOCAL" to BeatShape(
                force = BeatTiming(4.5f, 15L, 30L),
                ampCtrl = BeatTiming(3.5f, 12L, 25L),
                plain = BeatTiming(3.2f, 10L, 25L),
                ampBase = 120f, attackFrac = 0.30f, sustainFrac = 0f,
                attackAmpFrac = 1.0f, decayAmpFrac = 0.40f
            ),
        )

        private const val RING_BUFFER_CAPACITY = 131072

        private const val FRAME_BLOCK_SIZE = 256

        private const val MAXIMUM_CHANNELS = 8

        private const val AMBIENT_TEMPERATURE_CELSIUS = 25.0f
        private const val LIMITING_TEMPERATURE_CELSIUS = 80.0f
        private const val CRITICAL_TEMPERATURE_CELSIUS = 100.0f

        const val SUB_BASS_LOW = 20f
        const val SUB_BASS_HIGH = 80f
        const val MID_BASS_LOW = 80f
        const val MID_BASS_HIGH = 200f
        const val TEXTURE_LOW = 200f
        const val TEXTURE_HIGH = 800f

        @Volatile var verboseLogging: Boolean = BuildConfig.DEBUG
    }
    private val nativeBridge = NativeBridge()
    private lateinit var audioIngress: AudioIngress
    private val impactPolicy = HapticImpactPolicy()

    private val vibrateProxy = VibrateProxy(context)

    private val deviceProfile = detectDeviceProfile(
        context = context,
        persistedProfileId = prefs.getString(RootHardwareProbe.PREF_PROFILE, null)
    )
    private val hapticSynthesizer = HapticSynthesizer(deviceProfile)

    @Volatile var isVisualizerSource = false

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineJob = engineScope.coroutineContext[Job]!!

    var logCallback: LogCallback? = null

    private var sampleRate = 48000
    private var channels = 2

    private val isEngineEnabled = AtomicBoolean(true)
    private var telemetryDbgCounter = 0
    @Volatile private var lastTelemetryLogMs = 0L

    @Volatile private var nativeSchedulerActive = false
    @Volatile private var nativeLastAudioTime = 0L
    @Volatile private var lastStyleSnapshot = ""
    @Volatile private var lastOutputRouteSnapshot = ""
    @Volatile private var activeStyle: StylePreset = StylePreset.BALANCED
    // Runtime amplitude state, refreshed by synchronizeParameters() and read
    // from the beat-dispatch path on another thread.
    @Volatile private var activeOutputLevel: Float = 1.0f
    @Volatile private var activeBassBoost: Float = 1.0f
    private val beatLogCounter = java.util.concurrent.atomic.AtomicLong(0L)

    private var udpSocket: DatagramSocket? = null
    private var udpParcel: ParcelFileDescriptor? = null

    @Volatile private var rootPipeProcess: Process? = null
    @Volatile private var rootPipeStream: java.io.OutputStream? = null
    @Volatile private var rootPipeActive = false
    @Volatile private var rootPipeActivatePath: String = ""
    @Volatile private var rootPipeGainPath: String = ""
    @Volatile private var rootPipeGainIsHex = false
    private val rootPipeLock = Any()
    @Volatile private var hapticPaused = false
    private val lifecycleJob = SupervisorJob()
    private val lifecycleScope = CoroutineScope(lifecycleJob + Dispatchers.Default)

    @Volatile private var pcmFallbackAtMs = 0L

    private val lastVibrationMs = AtomicLong(0L)
    @Volatile private var lastBeatEvent = ""


    @Volatile private var pendingSemanticLabel: String = "NONE"
    val telemetryData = TelemetryMonitor()


    init {
        audioIngress = AudioIngress(nativeBridge, ::onNativeTelemetry)

        val configuredProfile = prefs.getString(RootHardwareProbe.PREF_PROFILE, "DEFAULT") ?: "DEFAULT"
        val configuredNodes = prefs.getString(RootHardwareProbe.PREF_DIRECT_DRIVE_NODES, null)
        Log.i(TAG, "Hardware profile=$configuredProfile; configuredNodes=${configuredNodes?.isNotBlank() == true}")
        LogBroadcaster.sendLog(context, "[DirectDrive] profile=$configuredProfile; cachedNodes=${configuredNodes?.isNotBlank() == true}")
        
        if (!configuredNodes.isNullOrBlank()) {
            runCatching { nativeBridge.setDirectDriveNodes(configuredNodes) }
                .onFailure { Log.w(TAG, "Configured direct-drive nodes rejected", it) }
        }
        
        RootHardwareProbe.getDirectDriveNodesAsync(context, allowRootProbe = false) { nodes ->
            Log.i(TAG, "DirectDriveNodes received: '$nodes'")
            LogBroadcaster.sendLog(context, "[DirectDrive] Nodes received: '$nodes'")
            if (nodes.isNotBlank()) {
                nativeBridge.setDirectDriveNodes(nodes)
                Log.i(TAG, "Passed vibrator nodes to NativeBridge for direct drive: $nodes")
                LogBroadcaster.sendLog(context, "[DirectDrive] setDirectDriveNodes called with: $nodes")
                val available = nativeBridge.isDirectDriveAvailable()
                LogBroadcaster.sendLog(context, "[DirectDrive] isDirectDriveAvailable=$available")

                if (!available) {
                    
                    
                    LogBroadcaster.sendLog(context, "[DirectDrive] open() failed, trying root-assisted fd...")
                    tryRootAssistedDirectDrive(context, nodes)
                }
            } else {
                LogBroadcaster.sendLog(context, "[DirectDrive] WARNING: no nodes found by RootHardwareProbe!")
            }
        }

        synchronizeParameters()

        val proxyReady = vibrateProxy.init(deviceProfile)
        Log.i(TAG, "VibrateProxy initialized: ready=$proxyReady path=${if (vibrateProxy.isProxyActive) "IPC_PROXY" else "DIRECT"}")
        val deviceTuning = com.mouya.musichaptics.haptic.DeviceTuningRegistry.current(deviceProfile)
        Log.i(TAG, "[Device Profile] name=${deviceProfile.name} id=${deviceTuning.profileId} actuator.f0=${deviceProfile.actuator.resonanceFreq}Hz maxAmp=${deviceProfile.actuator.maxAmplitude} damping=${deviceProfile.actuator.dampingRatio} q=${deviceProfile.actuator.qFactor} tuning=${deviceTuning.reason}")
        Log.i(TAG, "[Vibrator Capability] hasAmpCtrl=${vibrateProxy.hasAmplitudeControl} primitives: CLICK=${vibrateProxy.primitiveClickSupported} TICK=${vibrateProxy.primitiveTickSupported} THUD=${vibrateProxy.primitiveHeavyClickSupported}")
        if (nativeBridge.isLoaded) {
            
            
            
            nativeBridge.beatTriggerCallback = { event, intensity, directDriveSelected ->
                onNativeBeat(event, intensity, directDriveSelected)
            }
            val beatMsg = "[Beat] beatTriggerCallback registered: hasVibrator=${vibrateProxy.hasVibrator} primitives: click=${vibrateProxy.primitiveClickSupported} tick=${vibrateProxy.primitiveTickSupported} heavyClick=${vibrateProxy.primitiveHeavyClickSupported}"
            Log.i(TAG, beatMsg)
            LogBroadcaster.sendLog(context, beatMsg)

            nativeSchedulerActive = nativeBridge.startScheduler()
            LogBroadcaster.sendLog(context, "[Scheduler] startScheduler returned=$nativeSchedulerActive")

            // Report which analyser is live. This is the first thing to check in
            // an exported log: a false here means the Rust archive did not link
            // and the C++ analyser is running instead.
            val rustReady = nativeBridge.isRustCoreAvailable()
            val backend = nativeBridge.analysisBackend()
            val dspMsg = "[DSP] Rust core available=$rustReady backend=${if (backend == 0) "Rust" else "C++"}"
            Log.i(TAG, dspMsg)
            LogBroadcaster.sendLog(context, dspMsg)

            if (nativeSchedulerActive) {
                
                
                try { initUdpRootTransport() } catch (_: Exception) {}
            }

            if (nativeSchedulerActive) {
                Log.i(TAG, "Native Haptic Scheduler: ENABLED (5ms frame timing, Direct Drive + Android Vibrator fallback)")
            } else {
                Log.w(TAG, "Native Haptic Scheduler: START FAILED — falling back to coroutine")
            }
        }

        
        engineScope.launch {
            runSemanticFrameLoop()
        }
        Log.i(TAG, "Native scheduler: low-latency event timing; Haptics: transient-first onset strikes")
        val startupDirectReady = isNativeDrivingDirect()
        val backendName = if (nativeBridge.analysisBackend() == 0) "Rust" else "C++"
        val readyMsg = "[System Ready] v${BuildConfig.VERSION_NAME} | DSP: ${if (nativeBridge.isLoaded) "Native loaded ($backendName)" else "unavailable"} | Scheduler: ${if (nativeSchedulerActive) "Native 5ms" else "Coroutine 16ms"} | Hardware output: ${if (startupDirectReady) "verified direct-drive transport" else "Android Vibrator waveform (direct drive unavailable)"} | Vibrator: present=${vibrateProxy.hasVibrator} amplitudeControl=${vibrateProxy.hasAmplitudeControl} forceDefault=${vibrateProxy.forceDefaultAmplitude} primitives(click/tick/heavy)=${vibrateProxy.primitiveClickSupported}/${vibrateProxy.primitiveTickSupported}/${vibrateProxy.primitiveHeavyClickSupported} | Device: ${deviceProfile.name} | Actuator profile: ${deviceProfile.actuator.resonanceFreq.toInt()}Hz Q=${deviceProfile.actuator.qFactor} rise=${deviceProfile.actuator.riseTimeMs.toInt()}ms fall=${deviceProfile.actuator.fallTimeMs.toInt()}ms | Onsets: KICK/SNARE/VOCAL/BODY"
        Log.i(TAG, readyMsg)
        logCallback?.onLog(readyMsg)
        LogBroadcaster.sendLog(context, readyMsg)
    }

    
    private fun initUdpRootTransport() {
        try {
            val socket = DatagramSocket()
            socket.connect(java.net.InetAddress.getByName("127.0.0.1"), RootHapticDaemon.DAEMON_PORT)

            // P1: Prefer public API (API 31+); reflection is legacy fallback only.
            // Reflection is legacy fallback only with try/catch; failure degrades only.
            var parcel: ParcelFileDescriptor? = null
            var rawFd = -1
            var nativeFd = -1
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    parcel = ParcelFileDescriptor.fromDatagramSocket(socket)
                    rawFd = parcel!!.fd
                } else {
                    throw UnsupportedOperationException("pre-S")
                }
            }.onFailure {
                // Legacy reflection fallback
                var impl: Any? = null
                var c: Class<*>? = socket.javaClass
                while (c != null && impl == null) {
                    try {
                        val f = c.getDeclaredField("impl")
                        f.isAccessible = true
                        impl = f.get(socket)
                    } catch (_: NoSuchFieldException) { c = c.superclass }
                }
                if (impl == null) throw IllegalStateException("DatagramSocket impl unavailable")
                var fdObj: java.io.FileDescriptor? = null
                c = impl.javaClass
                while (c != null && fdObj == null) {
                    for (name in listOf("fd", "fileDescriptor")) {
                        try {
                            val f = c.getDeclaredField(name)
                            f.isAccessible = true
                            fdObj = f.get(impl) as? java.io.FileDescriptor
                            if (fdObj != null) break
                        } catch (_: NoSuchFieldException) { }
                    }
                    c = c.superclass
                }
                if (fdObj == null) throw IllegalStateException("DatagramSocket fd unavailable")
                val descriptor = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
                descriptor.isAccessible = true
                rawFd = descriptor.getInt(fdObj)
                parcel = ParcelFileDescriptor.fromFd(rawFd)
            }
            val p = parcel ?: throw IllegalStateException("parcel unavailable")
            nativeFd = p.fd
            val ok = nativeBridge.initUdpHapticFromFd(nativeFd, RootHapticDaemon.DAEMON_PORT)
            Log.i(TAG, "[UDP] Java socket fd=$rawFd nativeFd=$nativeFd init=$ok")
            LogBroadcaster.sendLog(context, "[UDP] transport init=$ok rawFd=$rawFd nativeFd=$nativeFd")
            if (!ok) {
                p.close()
                socket.close()
            } else {
                udpSocket = socket
                udpParcel = p
                // A constructed socket proves nothing by itself: the root daemon
                // that consumes the packets is started separately and never starts
                // when su is refused. Native now reports the transport as ready
                // only while sends are actually accepted, so log both sides for
                // the next trace.
                LogBroadcaster.sendLog(
                    context,
                    "[UDP] transport ready=${nativeBridge.isUdpHapticReady()}"
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "[UDP] transport init failed: ${t.javaClass.simpleName}: ${t.message}", t)
            LogBroadcaster.sendLog(context, "[UDP] transport FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    
    private fun tryRootAssistedDirectDrive(context: Context, nodes: String) {
        Thread(Runnable {
            try {
                LogBroadcaster.sendLog(context, "[DirectDrive] tryRootAssisted: Java OutputStream pipe mode...")

                val paths = nodes.split(",").filter { it.isNotBlank() }
                val activatePath = paths.firstOrNull()?.trim() ?: ""
                if (activatePath.isBlank()) {
                    LogBroadcaster.sendLog(context, "[DirectDrive] No activate path")
                    return@Runnable
                }
                val dirPath = activatePath.substringBeforeLast('/')
                var amplitudePath: String? = null
                for (ampName in listOf("gain", "amplitude", "index_value")) {
                    val candidate = "$dirPath/$ampName"
                    if (java.io.File(candidate).exists()) {
                        amplitudePath = candidate
                        break
                    }
                }

                
                
                val script = buildString {
                    append("exec 3>'$activatePath'")
                    if (amplitudePath != null) {
                        append(" && exec 4>'$amplitudePath'")
                    }
                    
                    
                    append("; while IFS= read -r line; do")
                    append(" case \"\$line\" in")
                    append("   A) echo 1 >&3 2>/dev/null;;")
                    if (amplitudePath != null) {
                        append("   G*) echo \"\${line#G}\" >&4 2>/dev/null; echo 1 >&3 2>/dev/null;;")
                    }
                    append("   *) ;;")
                    append(" esac")
                    append("; done")
                }

                LogBroadcaster.sendLog(context, "[DirectDrive] Starting Java pipe daemon: $activatePath")
                val pb = ProcessBuilder("su", "-c", script).redirectErrorStream(true)
                val suProcess = pb.start()

                
                Thread.sleep(800)
                if (!suProcess.isAlive) {
                    val err = try { suProcess.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
                    LogBroadcaster.sendLog(context, "[DirectDrive] su process died: $err")
                    return@Runnable
                }

                val stream = suProcess.outputStream
                
                stream.write("A\n".toByteArray())
                stream.flush()
                Thread.sleep(100)

                if (!suProcess.isAlive) {
                    LogBroadcaster.sendLog(context, "[DirectDrive] su process died after test")
                    return@Runnable
                }

                synchronized(rootPipeLock) {
                    rootPipeProcess = suProcess
                    rootPipeStream = stream
                    rootPipeActivatePath = activatePath
                    rootPipeGainPath = amplitudePath ?: ""
                    rootPipeGainIsHex = amplitudePath?.contains("gain") == true
                    rootPipeActive = true
                }

                LogBroadcaster.sendLog(context, "[DirectDrive] ✅ JAVA PIPE MODE ACTIVE — test vibration sent!")
                Log.i(TAG, "[DirectDrive] Java pipe active: activate=$activatePath gain=$amplitudePath")

                
                nativeBridge.enableRootPipe { amplitude, duration ->
                    triggerRootPipeVibration(amplitude, duration)
                }

                
                nativeBridge.beatTriggerCallback = { event, intensity, directDriveSelected ->
                    onNativeBeat(event, intensity, directDriveSelected)
                }

            } catch (e: Exception) {
                LogBroadcaster.sendLog(context, "[DirectDrive] tryRootAssisted exception: ${e.message}")
                Log.e(TAG, "tryRootAssistedDirectDrive failed", e)
            }
        }).start()
    }

    
    private val activateCmdBytes = "A\n".toByteArray(Charsets.US_ASCII)
    private val decGainCmdBytes: Array<ByteArray> by lazy {
        Array(256) { i -> "G$i\n".toByteArray(Charsets.US_ASCII) }
    }
    private val hexGainCmdBytes: Array<ByteArray> by lazy {
        Array(201) { i -> "G0x%02x\n".format(i).toByteArray(Charsets.US_ASCII) }
    }

    private fun triggerRootPipeVibration(amplitude: Int, duration: Int) {
        if (!rootPipeActive) return
        try {
            val stream = rootPipeStream ?: return
            synchronized(rootPipeLock) {
                if (rootPipeGainPath.isNotEmpty() && amplitude > 0) {
                    val cmd = if (rootPipeGainIsHex) {
                        hexGainCmdBytes[amplitude.coerceIn(0, 255) * 200 / 255]
                    } else {
                        decGainCmdBytes[amplitude.coerceIn(0, 255)]
                    }
                    stream.write(cmd)
                } else {
                    stream.write(activateCmdBytes)
                }
                stream.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[DirectDrive] pipe write failed: ${e.message}")
            rootPipeActive = false
            // The scheduler reported this exact beat as a direct-drive event,
            // so Kotlin intentionally skipped its full waveform. Preserve the
            // current strike with a bounded one-shot before demoting the pipe.
            runCatching {
                vibrateProxy.performOneShot(
                    durationMs = duration.coerceIn(12, 80).toLong(),
                    amplitude = amplitude.coerceIn(1, 255)
                )
            }.onFailure { Log.w(TAG, "[DirectDrive] immediate waveform fallback failed: ${it.message}") }
            val fallbackMessage = "[OUTPUT] Java pipe failed; current strike fell back to Android one-shot"
            Log.i(TAG, fallbackMessage)
            LogBroadcaster.sendLog(context, fallbackMessage)
            logOutputRoute(false)
            // Clear the native scheduler's Java-pipe flag immediately. Leaving
            // it set makes the scheduler keep selecting NativeDD while no
            // OutputStream consumer exists, so Kotlin suppresses its waveform.
            runCatching { nativeBridge.disableRootPipe() }
        }
    }
    fun emitPhiraBeat(event: String, intensity: Int) = triggerBeatVibration(event, intensity)

    /**
     * Entry point for beats produced by the C++ scheduler.
     *
     * A native onset is first-hand proof that PCM is still flowing through the
     * engine, so it must clear a stale hapticPaused latch. Without this the
     * Kotlin latch (set by a spurious "playback truly paused" event) would
     * silently swallow every native beat: the only unlock path lived inside
     * runSemanticFrameLoop *after* its `if (hapticPaused) continue` guard, which
     * is unreachable code once the latch is set.
     */
    private fun onNativeBeat(event: String, intensity: Int, directDriveSelected: Boolean) {
        nativeLastAudioTime = SystemClock.elapsedRealtime()
        if (hapticPaused) {
            hapticPaused = false
            vibrateProxy.setResumed()
            nativeBridge.clearHapticBuffer()
            val msg = "[PLAYBACK RESUMED] native onset while latched — hapticPaused cleared"
            Log.i(TAG, msg)
            LogBroadcaster.sendLog(context, msg)
        }
        logOutputRoute(directDriveSelected)
        triggerBeatVibration(event, intensity, directDriveSelected)
    }

    private fun logOutputRoute(directDriveSelected: Boolean) {
        val route = if (directDriveSelected) "NATIVE_DIRECT_DRIVE" else "ANDROID_VIBRATOR_WAVEFORM"
        if (route == lastOutputRouteSnapshot) return
        lastOutputRouteSnapshot = route
        val message = "[OUTPUT] active route=$route (selected by native scheduler)"
        Log.i(TAG, message)
        LogBroadcaster.sendLog(context, message)
    }

    /**
     * Whether a hardware direct-drive transport is currently verified.
     *
     * A non-null [udpSocket] only proves the datagram socket was created; it
     * says nothing about whether a listener exists on the other end. The root
     * daemon that consumes those packets is started separately and is refused
     * outright when `su` is unavailable, so counting the bare socket as a
     * working transport makes Kotlin skip its own vibration path while nothing
     * fires the hardware at all. Gate on transports that are confirmed live.
     */
    private fun isNativeDrivingDirect(): Boolean {
        if (!nativeSchedulerActive) return false
        // Only transports with a confirmed consumer count:
        //  - the direct fd was opened successfully, or
        //  - the Java pipe daemon answered its handshake, or
        //  - UDP sends are being accepted by a bound receiver.
        // A bare DatagramSocket is deliberately excluded: when su is refused
        // nobody consumes those packets, so claiming direct drive would make
        // Kotlin skip its own vibration path for no reason. The native flag is
        // demoted on the first ECONNREFUSED, so this self-heals within a beat.
        return nativeBridge.isDirectDriveAvailable() ||
            rootPipeActive ||
            nativeBridge.isUdpHapticReady()
    }


    
    private fun triggerBeatVibration(
        event: String,
        intensity: Int,
        nativeDirectDriveSelected: Boolean = false,
    ) {
        if (!vibrateProxy.hasVibrator || hapticPaused || !isEngineEnabled.get()) return

        // The native scheduler passes the route decision made for this exact
        // beat. Do not probe transport state again here: a pipe/UDP failure
        // between the native decision and this callback could otherwise make
        // both the native path and Kotlin fallback fire (or neither fire).
        val nativeDrivingDirect = nativeSchedulerActive && nativeDirectDriveSelected

        val style = activeStyle
        val normalizedIntensity = (intensity / 255f).coerceIn(0f, 1f)
        if (normalizedIntensity < style.onsetThreshold) return

        val now = SystemClock.elapsedRealtime()
        val plan = impactPolicy.plan(
            event = event,
            intensity = intensity,
            profile = deviceProfile,
            amplitudeControl = vibrateProxy.hasAmplitudeControl,
            forceDefaultAmplitude = vibrateProxy.forceDefaultAmplitude
        ) ?: return

        val deviceCooldown = DeviceTuningRegistry.current(deviceProfile).minIntervalMs
        val effectiveCooldown = maxOf(plan.cooldownMs, style.cooldownMs, deviceCooldown)
        val previous = lastVibrationMs.get()
        if (now - previous < effectiveCooldown) return
        if (!lastVibrationMs.compareAndSet(previous, now)) return

        val eventBass = if (event.equals("KICK", true) || event.equals("SUB", true)) activeBassBoost else 1f
        val level = activeOutputLevel.coerceIn(0.3f, 2.0f)
        val styleTime = style.attackScale.coerceIn(0.45f, 1.8f)
        val planFloor = plan.totalDurationMs.coerceIn(8L, 40L)
        // A new waveform replaces the previous one on Vibrator. Keep the
        // current envelope shorter than the event gate so it finishes cleanly
        // and leaves a deliberate silent interval before the next onset.
        val maxEnvelopeMs = (effectiveCooldown - 8L).coerceAtLeast(12L).coerceAtMost(80L)
        val duration = (planFloor * (0.48f + 0.92f * normalizedIntensity) * styleTime)
            .toLong().coerceIn(12L, maxEnvelopeMs)
        val shaped = hapticSynthesizer.sculptImpact(
            event = plan.event,
            intensity = normalizedIntensity,
            levelScale = level,
            durationMs = duration,
            sharpness = style.sharpness,
            bassBoost = eventBass,
            ampScale = style.ampScale,
            accentScale = style.accentScale,
            thermalInput = 1f,
        )

        val useDynamic = !vibrateProxy.forceDefaultAmplitude
        val usedDynamic = if (nativeDrivingDirect) {
            // Skip Kotlin vibration path entirely — the C++ scheduler
            // has already fired the hardware strike.
            false
        } else {
            useDynamic && vibrateProxy.performDynamicEffect(
                amplitude = shaped.peakAmplitude / 255f,
                sharpness = style.sharpness.coerceIn(0.05f, 1f),
                durationSec = (shaped.totalDurationMs / 1000f).coerceIn(0.012f, 0.22f),
                attackSec = (shaped.timings.firstOrNull() ?: 1L).coerceIn(1L, 25L) / 1000f
            )
        }

        if (!usedDynamic && !nativeDrivingDirect) {
            runCatching {
                vibrateProxy.performWaveform(shaped.timings, shaped.amplitudes)
            }.onFailure { Log.w(TAG, "Haptic output failed: ${it.message}") }
        }

        lastBeatEvent = plan.event
        val beatCounter = beatLogCounter.incrementAndGet()
        if (beatCounter <= 12 || beatCounter % 40 == 0L) {
            val pathLabel = if (nativeDrivingDirect) "NativeDD" else if (usedDynamic) "DynamicEffect" else "Waveform"
            val msg = "[BEAT] #$beatCounter ${plan.event} raw=$intensity style=${style.key} level=${"%.2f".format(level)} peak=${shaped.peakAmplitude} duration=${shaped.totalDurationMs}ms cooldown=${effectiveCooldown}ms path=$pathLabel"
            Log.i(TAG, msg)
            LogBroadcaster.sendLog(context, msg)
        }
    }


        private suspend fun runSemanticFrameLoop() {
        val pullIntervalMs = 16L
        var frameCounter = 0L
        val semanticFrameBuffer = FloatArray(64 * 4)
        val onsetBuffer = FloatArray(64 * 4)
        var lastAudioInputTime = 0L
        val silenceTimeoutMs = 2500L

        var lastBeatMs = 0L
        val beatRefractoryMs = DeviceTuningRegistry.current(deviceProfile).minIntervalMs.coerceIn(24L, 72L)
        while (true) {
            val frameStartTime = SystemClock.elapsedRealtime()

            try {
                // Recovery must be evaluated *before* the paused guard below.
                // Two independent PCM-liveness signals are consulted: the hook
                // timestamp updated by the AudioTrack.write path, and the DSP
                // block counter reported by native. Both keep advancing while
                // this loop is latched, whereas checking them after `continue`
                // made the un-latch branch unreachable code.
                if (hapticPaused) {
                    val hookPcmAgeNow = frameStartTime - nativeLastAudioTime
                    val nativeAgeNow = nativeBridge.audioSilenceMs()
                    val flowing = (nativeLastAudioTime > 0L && hookPcmAgeNow < silenceTimeoutMs) ||
                        (nativeAgeNow >= 0L && nativeAgeNow < silenceTimeoutMs)
                    if (flowing) {
                        hapticPaused = false
                        vibrateProxy.setResumed()
                        nativeBridge.clearHapticBuffer()
                        val msg = "[PLAYBACK RESUMED] PCM activity detected, clearing hapticPaused latch"
                        Log.i(TAG, msg)
                        LogBroadcaster.sendLog(context, msg)
                    } else {
                        kotlinx.coroutines.delay(pullIntervalMs)
                        continue
                    }
                }

                // Do not pull frames while the native scheduler is active: it consumes
                // these rings itself, so any frame taken here is a beat the
                // scheduler will never dispatch (a bounded pull still steals real
                // events). Diagnosis instead relies on the accepted/processed
                // counters, which show whether PCM reached the DSP at all.
                val semanticFrameCount = if (nativeBridge.isLoaded && !nativeSchedulerActive) {
                    nativeBridge.getSemanticFrames(semanticFrameBuffer, 64)
                } else 0

                val onsetFrameCount = if (nativeBridge.isLoaded && !nativeSchedulerActive) {
                    nativeBridge.getOnsetFrames(onsetBuffer, 64)
                } else 0

                if (onsetFrameCount > 0) {
                    val now = SystemClock.elapsedRealtime()
                    for (i in 0 until onsetFrameCount) {
                        val k = onsetBuffer[i * 4 + 0]
                        val s = onsetBuffer[i * 4 + 1]
                        val v = onsetBuffer[i * 4 + 2]
                        val b = onsetBuffer[i * 4 + 3]

                        if (now - lastBeatMs < beatRefractoryMs) continue

                        val maxVal = maxOf(k, s, v, b)
                        if (maxVal < 0.05f) continue

                        val beatType = when {
                            maxVal == k -> "KICK"
                            maxVal == s -> "SNARE"
                            maxVal == v -> "VOCAL"
                            else -> "BODY"
                        }
                        val intensity = (maxVal * 255f).toInt().coerceIn(15, 255)
                        triggerBeatVibration(beatType, intensity)

                        lastBeatMs = now
                        if (verboseLogging) {
                            Log.i(TAG, "[C++-ONSET] $beatType intensity=$intensity k=${"%.3f".format(k)} s=${"%.3f".format(s)} v=${"%.3f".format(v)} b=${"%.3f".format(b)}")
                        }
                        break // Trigger one event per frame
                    }
                }

                if (semanticFrameCount > 0 || onsetFrameCount > 0) {
                    lastAudioInputTime = frameStartTime
                }

                val timeSinceAudio = frameStartTime - lastAudioInputTime
                val hasNativeAudioActivity = timeSinceAudio < silenceTimeoutMs
                val hookPcmAge = frameStartTime - nativeLastAudioTime
                val hasHookAudioActivity = nativeLastAudioTime > 0L && hookPcmAge < silenceTimeoutMs
                val hasAudioActivity = hasNativeAudioActivity || hasHookAudioActivity

                if (hasAudioActivity && vibrateProxy.paused) {
                    vibrateProxy.setResumed()
                }




                if (frameCounter % 60L == 0L) {
                    synchronizeParameters()
                }

                frameCounter++
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Semantic frame loop cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Semantic frame loop error: ${e.message}")
            }

            val elapsed = SystemClock.elapsedRealtime() - frameStartTime
            val sleepMs = (pullIntervalMs - elapsed).coerceIn(1L, pullIntervalMs)
            try {
                kotlinx.coroutines.delay(sleepMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Semantic frame loop cancelled during sleep")
                break
            }
        }
    }

    fun synchronizeParameters() {
        if (prefs is com.mouya.musichaptics.hook.HookConfigPreferences) prefs.refresh()

        val masterState = runCatching { prefs.getBoolean("master_switch", true) }.getOrDefault(true)
        val wasEnabled = isEngineEnabled.get()
        isEngineEnabled.set(masterState)
        if (!masterState && wasEnabled) {
            // Master switch turned OFF: stop everything immediately
            hapticPaused = true
            vibrateProxy.setPaused()
            nativeBridge.clearHapticBuffer()
            hapticSynthesizer.forceDecay()
            if (nativeSchedulerActive) {
                try { nativeBridge.stopScheduler() } catch (_: Throwable) {}
                nativeSchedulerActive = false
            }
            LogBroadcaster.sendLog(context, "[MASTER] Switch OFF — all haptics stopped")
        } else if (masterState && !wasEnabled) {
            // Master switch turned back ON: restart scheduler
            hapticPaused = false
            vibrateProxy.setResumed()
            if (!nativeSchedulerActive && nativeBridge.isLoaded) {
                nativeSchedulerActive = nativeBridge.startScheduler()
            }
            LogBroadcaster.sendLog(context, "[MASTER] Switch ON — scheduler restarted=${nativeSchedulerActive}")
        }

        val baseAmplitude = runCatching { prefs.getFloat("haptic_amplitude", 2.3f) }
            .getOrDefault(2.3f).let { if (it.isFinite()) it.coerceIn(0.5f, 3.5f) else 2.3f }
        val uiPreset = runCatching { prefs.getInt("selected_preset", 2) }.getOrDefault(2)
            .coerceIn(0, 3)
        val presetGain = floatArrayOf(0.45f, 0.75f, 1.10f, 1.60f)[uiPreset]
        val style = StylePreset.fromKey(runCatching { prefs.getString("style_preset", "balanced") }.getOrDefault("balanced"))
        // A soft knee preserves useful slider travel above the former 2.0x hard
        // clamp. The final 0..255 hardware cap is still applied per device.
        val outputAmp = softLimitOutputAmplitude(baseAmplitude * presetGain)
        val lowCutoffFreq = style.lowCutHz
        val highCutoffFreq = style.highCutHz

        activeStyle = style
        activeOutputLevel = outputAmp
        activeBassBoost = runCatching { prefs.getFloat("haptic_bass_boost", 1.6f) }.getOrDefault(1.6f).coerceIn(1f, 2.5f)

        nativeBridge.configure(
            sampleRate = sampleRate.toFloat(),
            lowCut = lowCutoffFreq,
            highCut = highCutoffFreq,
            amplitude = outputAmp,
            presetId = uiPreset
        )
        nativeBridge.configureProfile(deviceProfile)

        // Capability-based output is the default. DEFAULT_AMPLITUDE is a
        // compatibility override and must never be selected solely from a
        // guessed device codename or an unmeasured actuator profile.
        val forceDefaultPref = runCatching {
            prefs.getBoolean("force_default_amplitude", false)
        }.getOrDefault(false)
        vibrateProxy.setForceDefaultAmplitude(forceDefaultPref)

        val deviceTuning = DeviceTuningRegistry.current(deviceProfile)
        val actuator = deviceProfile.actuator
        hapticSynthesizer.updateParameters(
            HapticSynthesizer.SynthConfig(
                synthesisRateHz = runCatching { prefs.getInt("synth_rate_hz", HapticSynthesizer.SYNTHESIS_RATE_HZ) }.getOrDefault(HapticSynthesizer.SYNTHESIS_RATE_HZ),
                lraF0 = runCatching { prefs.getFloat("synth_lra_f0", actuator.resonanceFreq) }.getOrDefault(actuator.resonanceFreq),
                lraQ = runCatching { prefs.getFloat("synth_lra_q", actuator.qFactor) }.getOrDefault(actuator.qFactor),
                attackTauImpact = runCatching { prefs.getFloat("synth_attack_impact", HapticSynthesizer.ATTACK_TAU_IMPACT) }.getOrDefault(HapticSynthesizer.ATTACK_TAU_IMPACT),
                decayTauImpact = runCatching { prefs.getFloat("synth_decay_impact", HapticSynthesizer.DECAY_TAU_IMPACT) }.getOrDefault(HapticSynthesizer.DECAY_TAU_IMPACT),
                attackTauContinuous = runCatching { prefs.getFloat("synth_attack_continuous", HapticSynthesizer.ATTACK_TAU_CONTINUOUS) }.getOrDefault(HapticSynthesizer.ATTACK_TAU_CONTINUOUS),
                decayTauContinuous = runCatching { prefs.getFloat("synth_decay_continuous", HapticSynthesizer.DECAY_TAU_CONTINUOUS) }.getOrDefault(HapticSynthesizer.DECAY_TAU_CONTINUOUS),
                releaseTau = runCatching { prefs.getFloat("synth_release", HapticSynthesizer.RELEASE_TAU) }.getOrDefault(HapticSynthesizer.RELEASE_TAU),
                sustainLevel = runCatching { prefs.getFloat("synth_sustain", HapticSynthesizer.SUSTAIN_LEVEL) }.getOrDefault(HapticSynthesizer.SUSTAIN_LEVEL),
                thermalWarn = runCatching { prefs.getFloat("synth_thermal_warn", HapticSynthesizer.THERMAL_WARN) }.getOrDefault(HapticSynthesizer.THERMAL_WARN),
                thermalCrit = runCatching { prefs.getFloat("synth_thermal_crit", HapticSynthesizer.THERMAL_CRIT) }.getOrDefault(HapticSynthesizer.THERMAL_CRIT),
                thermalRth = runCatching { prefs.getFloat("synth_thermal_rth", actuator.thermalResistance) }.getOrDefault(actuator.thermalResistance),
                thermalCth = runCatching { prefs.getFloat("synth_thermal_cth", actuator.thermalCapacitance) }.getOrDefault(actuator.thermalCapacitance),
                impactGain = runCatching { prefs.getFloat("synth_impact_gain", deviceTuning.impactGain) }.getOrDefault(deviceTuning.impactGain),
                continuousGain = runCatching { prefs.getFloat("synth_continuous_gain", 1.0f) }.getOrDefault(1.0f),
                textureGain = runCatching { prefs.getFloat("synth_texture_gain", deviceTuning.textureGainScale) }.getOrDefault(deviceTuning.textureGainScale),
                masterGain = runCatching { prefs.getFloat("synth_master_gain", 1.0f) }.getOrDefault(1.0f),
            )
        )

        nativeBridge.configureOutput(
            styleAmpScale = style.ampScale,
            sharpness = style.sharpness,
            attackScale = style.attackScale,
            accentScale = style.accentScale,
            bassBoost = activeBassBoost,
            impactGain = runCatching { prefs.getFloat("synth_impact_gain", deviceTuning.impactGain) }.getOrDefault(deviceTuning.impactGain),
            continuousGain = runCatching { prefs.getFloat("synth_continuous_gain", 1.0f) }.getOrDefault(1.0f),
            textureGain = runCatching { prefs.getFloat("synth_texture_gain", deviceTuning.textureGainScale) }.getOrDefault(deviceTuning.textureGainScale),
            masterGain = runCatching { prefs.getFloat("synth_master_gain", 1.0f) }.getOrDefault(1.0f),
            onsetThreshold = style.onsetThreshold,
            attackImpactMs = runCatching { prefs.getFloat("synth_attack_impact", HapticSynthesizer.ATTACK_TAU_IMPACT) * 1000f }.getOrDefault(HapticSynthesizer.ATTACK_TAU_IMPACT * 1000f),
            decayImpactMs = runCatching { prefs.getFloat("synth_decay_impact", HapticSynthesizer.DECAY_TAU_IMPACT) * 1000f }.getOrDefault(HapticSynthesizer.DECAY_TAU_IMPACT * 1000f),
            attackContinuousMs = runCatching { prefs.getFloat("synth_attack_continuous", HapticSynthesizer.ATTACK_TAU_CONTINUOUS) * 1000f }.getOrDefault(HapticSynthesizer.ATTACK_TAU_CONTINUOUS * 1000f),
            decayContinuousMs = runCatching { prefs.getFloat("synth_decay_continuous", HapticSynthesizer.DECAY_TAU_CONTINUOUS) * 1000f }.getOrDefault(HapticSynthesizer.DECAY_TAU_CONTINUOUS * 1000f),
            releaseMs = runCatching { prefs.getFloat("synth_release", HapticSynthesizer.RELEASE_TAU) * 1000f }.getOrDefault(HapticSynthesizer.RELEASE_TAU * 1000f),
            sustainLevel = runCatching { prefs.getFloat("synth_sustain", HapticSynthesizer.SUSTAIN_LEVEL) }.getOrDefault(HapticSynthesizer.SUSTAIN_LEVEL),
            lraF0 = runCatching { prefs.getFloat("synth_lra_f0", actuator.resonanceFreq) }.getOrDefault(actuator.resonanceFreq),
            lraQ = runCatching { prefs.getFloat("synth_lra_q", actuator.qFactor) }.getOrDefault(actuator.qFactor),
            cooldownMs = maxOf(style.cooldownMs, deviceTuning.minIntervalMs).toFloat(),
            maxAmplitude = deviceTuning.cappedAmplitude(deviceProfile).toFloat()
        )

        telemetryData.lowPassCutoffHz = lowCutoffFreq
        telemetryData.highPassCutoffHz = highCutoffFreq
        telemetryData.userAmplitudeScale = outputAmp

        val snapshot = "[STYLE] preset=${style.key} amp=${"%.2f".format(outputAmp)} bass=${"%.2f".format(activeBassBoost)} sharp=${"%.2f".format(style.sharpness)} band=${lowCutoffFreq.toInt()}-${highCutoffFreq.toInt()}Hz cooldown=${style.cooldownMs}ms"
        if (snapshot != lastStyleSnapshot) {
            lastStyleSnapshot = snapshot
            Log.i(TAG, snapshot)
            LogBroadcaster.sendLog(context, snapshot)
        }
    }


        fun refreshSettings() {
        synchronizeParameters()
        val message = "Settings refreshed in hooked process | master=${isEngineEnabled.get()} amp=${"%.2f".format(telemetryData.userAmplitudeScale)}"
        Log.i(TAG, message)
        LogBroadcaster.sendLog(context, message)
    }


    internal fun visualizerFallbackEnabled(): Boolean =
        prefs.getBoolean("visualizer_fallback_enabled", true)

    internal fun onPlaybackStarted() {
        resumeFromHook()
    }

    fun reconfigure(newSampleRate: Int, newChannels: Int) {
        if (newSampleRate <= 0 || newChannels <= 0 || newChannels > MAXIMUM_CHANNELS) {
            Log.w(TAG, "Reconfiguration rejected: ${newSampleRate}Hz | $newChannels Ch")
            return
        }

        if (this.sampleRate == newSampleRate && this.channels == newChannels) return

        this.sampleRate = newSampleRate
        this.channels = newChannels

        synchronizeParameters()

        val logMessage = "System reconfigured to: ${sampleRate}Hz | $channels Channels (C++ Core)"
        Log.i(TAG, logMessage)
        logCallback?.onLog(logMessage)
        LogBroadcaster.sendLog(context, logMessage)
    }

    fun onPlaybackPaused() {
        val msg = "[PLAYBACK PAUSED CAUGHT] Marking as candidate stopped, deferring immediate haptic decay..."
        Log.w(TAG, msg)
        LogBroadcaster.sendLog(context, msg)
        markCandidateStopped()
    }
    
    private fun markCandidateStopped() {
        val lastPcmMs = pcmFallbackAtMs
        if (lastPcmMs == 0L && nativeLastAudioTime == 0L) {
            val nativeAge = nativeBridge.audioSilenceMs()
            if (nativeAge < 0) {
                Log.d(TAG, "[PLAYBACK PAUSED CANDIDATE] No PCM ever received, not pausing (guard against false positive)")
                return
            }
        }

        lifecycleScope.launch {
            kotlinx.coroutines.delay(2000)
            // Two independent liveness sources: the AudioTrack.write timestamp
            // and the DSP block counter. Requiring *both* to be stale before
            // latching avoids killing haptics when only the hook path is
            // unavailable (the write hooks never fired in the v5.4.7 trace while
            // audio was plainly still playing).
            val hookStillFlowing = pcmFallbackAtMs - lastPcmMs > 50
            val nativeAge = nativeBridge.audioSilenceMs()
            val nativeStillFlowing = nativeAge >= 0L && nativeAge < 2500L
            if (hookStillFlowing || nativeStillFlowing) {
                Log.i(TAG, "[PLAYBACK FALSE PAUSED] PCM is still arriving (nativeAge=${nativeAge}ms). Ignoring pause/release event.")
            } else {
                Log.i(TAG, "[PLAYBACK TRULY PAUSED] No PCM for 2000ms (nativeAge=${nativeAge}ms). Forcing immediate haptic decay")
                LogBroadcaster.sendLog(context, "[PLAYBACK TRULY PAUSED] Forcing immediate haptic decay")
                hapticPaused = true
                pcmFallbackAtMs = 0L
                vibrateProxy.setPaused()
                nativeBridge.clearHapticBuffer()
                pendingSemanticLabel = "NONE"
                hapticSynthesizer.forceDecay()
            }
        }
    }

    fun onNativeTelemetry(telemetry: FloatArray, framesRead: Int) {
        if (telemetry.size < 24) return
        
        telemetryData.subRms = telemetry[0]
        telemetryData.midRms = telemetry[1]
        telemetryData.textureRms = telemetry[2]
        telemetryData.pitch = telemetry[3]
        telemetryData.coilTemp = telemetry[4]
        telemetryData.thermalGain = telemetry[5]
        telemetryData.beatStrength = telemetry[6]
        telemetryData.onsetFlag = telemetry[7]
        telemetryData.beatIntervalMs = telemetry[8]
        telemetryData.beatConfidence = telemetry[9]
        
        telemetryData.kickOnset = telemetry[20]
        telemetryData.snareOnset = telemetry[21]
        telemetryData.vocalOnset = telemetry[22]
        telemetryData.bodyOnset = telemetry[23]
        
        
        
        telemetryDbgCounter++
        // Time-based rather than count-based: at 200 frames the interval grows
        // with the audio load and a short listening session could export no
        // telemetry line at all, which is exactly what happened on v5.5.2.
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastTelemetryLogMs >= 2000L) {
            lastTelemetryLogMs = nowMs
            // accepted/processed exposes the whole ingress chain in one line:
            // accepted > 0 but processed == 0 means the DSP worker is stalled;
            // both 0 means PCM never entered the queue at all (hook problem);
            // both advancing yet onset all zero means the detector thresholds
            // are wrong. Without these counters every one of those failures
            // looked identical in the exported log.
            val accepted = audioIngress.acceptedFrames()
            val processed = audioIngress.processedFrames()
            val dropped = audioIngress.droppedFrames()
            val msg = "[DSP-TELEM] subRms=%.5f midRms=%.5f texRms=%.5f | onset: KICK=%.4f SNARE=%.4f VOCAL=%.4f BODY=%.4f | frames=$framesRead accepted=$accepted processed=$processed dropped=$dropped"
                .format(telemetry[0], telemetry[1], telemetry[2],
                        telemetryData.kickOnset, telemetryData.snareOnset,
                        telemetryData.vocalOnset, telemetryData.bodyOnset)
            Log.i(TAG, msg)
            LogBroadcaster.sendLog(context, msg)
        }
    }

    internal fun markHookAudioArrival(atMs: Long = SystemClock.elapsedRealtime()) {
        pcmFallbackAtMs = atMs
        nativeLastAudioTime = atMs
    }

    internal fun processPcm16FromHook(
        data: ShortArray, offset: Int, sampleCount: Int, channelCount: Int,
        sourceSampleRateHz: Int = sampleRate
    ) {
        if (!isEngineEnabled.get()) return
        ensureIngressFormat(channelCount)
        resumeFromHook()
        markHookAudioArrival()
        audioIngress.processPcm16(data, offset, sampleCount, channelCount, sourceSampleRateHz)
    }

    internal fun processPcmFloatFromHook(
        data: FloatArray, offsetFrames: Int, frameCount: Int, channelCount: Int,
        sourceSampleRateHz: Int = sampleRate
    ) {
        if (!isEngineEnabled.get()) return
        ensureIngressFormat(channelCount)
        resumeFromHook()
        markHookAudioArrival()
        audioIngress.processFloatPcm(data, offsetFrames, frameCount, channelCount, sourceSampleRateHz)
    }

    internal fun processPcmBytesFromHook(
        data: ByteArray, offset: Int, byteCount: Int, channelCount: Int,
        encoding: Int = android.media.AudioFormat.ENCODING_PCM_16BIT,
        sourceSampleRateHz: Int = sampleRate
    ) {
        if (!isEngineEnabled.get()) return
        ensureIngressFormat(channelCount)
        resumeFromHook()
        markHookAudioArrival()
        audioIngress.processPcmBytes(data, offset, byteCount, channelCount, encoding, sourceSampleRateHz)
    }

    internal fun processPcmBufferFromHook(
        data: java.nio.ByteBuffer, startPosition: Int, byteCount: Int, channelCount: Int,
        encoding: Int = android.media.AudioFormat.ENCODING_PCM_16BIT,
        sourceSampleRateHz: Int = sampleRate
    ) {
        if (!isEngineEnabled.get()) return
        ensureIngressFormat(channelCount)
        resumeFromHook()
        markHookAudioArrival()
        audioIngress.processPcmBuffer(data, startPosition, byteCount, channelCount, encoding, sourceSampleRateHz)
    }

    private fun ensureIngressFormat(channelCount: Int) {
        channels = channelCount.coerceIn(1, MAXIMUM_CHANNELS)
    }

    private fun resumeFromHook() {
        if (!isEngineEnabled.get()) return
        if (hapticPaused) {
            hapticPaused = false
            vibrateProxy.setResumed()
            nativeBridge.clearHapticBuffer()
        }
        isVisualizerSource = false
    }

    fun release() {

        
        try { nativeBridge.disableRootPipe() } catch (_: Throwable) {}
        synchronized(rootPipeLock) {
            rootPipeActive = false
            try { rootPipeStream?.close() } catch (_: Exception) {}
            try { rootPipeProcess?.destroyForcibly() } catch (_: Exception) {}
            rootPipeStream = null
            rootPipeProcess = null
        }
        
        try { udpParcel?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        udpParcel = null
        udpSocket = null

        if (nativeSchedulerActive) {
            try { nativeBridge.stopScheduler() } catch (_: Throwable) {}
            nativeSchedulerActive = false
            Log.i(TAG, "Native Haptic Scheduler stopped (pthread_join complete).")
        }

        hapticSynthesizer.reset()
        engineJob.cancel()
        lifecycleJob.cancel()
        runCatching { audioIngress.shutdown() }
        nativeBridge.release()
        vibrateProxy.setPaused()
        vibrateProxy.unbind()  
        Log.i(TAG, "DSP Engine successfully shutdown.")
    }

    class TelemetryMonitor {
        @Volatile var lowPassCutoffHz = 0.0f
        @Volatile var highPassCutoffHz = 0.0f
        @Volatile var userAmplitudeScale = 1.0f
        @Volatile var fundamentalFrequencyHz = 0.0f
        @Volatile var estimatedCoilTemperature = AMBIENT_TEMPERATURE_CELSIUS
        @Volatile var thermalAttenuationFactor = 1.0f
        @Volatile var subBassOutputLevel = 0.0f
        @Volatile var midBassOutputLevel = 0.0f
        @Volatile var presenceOutputLevel = 0.0f
        @Volatile var ringBufferOverruns = 0L
        @Volatile var dispatchedSubBassImpacts = 0L
        @Volatile var dispatchedMidBassTransients = 0L
        @Volatile var dispatchedMicroTextures = 0L
        @Volatile var frameLatencyMs = 0L

        @Volatile var lraDisplacement = 0f
        @Volatile var lraVelocity = 0f
        @Volatile var lraForce = 0f
        @Volatile var lraPhase = 0f
        @Volatile var adsrEnvelope = 0f
        @Volatile var coilTemperature = 25f
        @Volatile var thermalGain = 1f

        
        @Volatile var subRms = 0f
        @Volatile var midRms = 0f
        @Volatile var textureRms = 0f
        @Volatile var pitch = 0f
        @Volatile var coilTemp = 25f
        @Volatile var thermalGainValue = 1f  
        @Volatile var beatStrength = 0f
        @Volatile var onsetFlag = 0f
        @Volatile var beatIntervalMs = 0f
        @Volatile var beatConfidence = 0f
        @Volatile var kickOnset = 0f
        @Volatile var snareOnset = 0f
        @Volatile var vocalOnset = 0f
        @Volatile var bodyOnset = 0f
    }
}