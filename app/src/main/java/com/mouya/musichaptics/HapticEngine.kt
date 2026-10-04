package com.mouya.musichaptics

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
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

import com.mouya.musichaptics.LinkHealthMonitor
import com.mouya.musichaptics.LogBroadcaster
import com.mouya.musichaptics.NativeBridge
import com.mouya.musichaptics.audio.AudioIngress
import com.mouya.musichaptics.haptic.HapticImpactPolicy
import com.mouya.musichaptics.haptic.DeviceTuningRegistry
import android.os.Build
import android.os.ParcelFileDescriptor
import java.net.DatagramSocket
interface LogCallback {
    fun onLog(message: String)
}


internal data class BeatTiming(val mul: Float, val min: Long, val max: Long)


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


/**
 * 5.2.7 风格预设（Style Preset）
 *
 * 设计要点：UI 的"震感预设"必须与 DSP 参数一一对应，否则用户调了看不出差别。
 * 每档直接改写四组真实生效的量：
 *   - cooldownMs  节拍冷却间隔（决定疏密）
 *   - ampScale     幅度缩放（决定强弱）
 *   - sharpness    LRA 锐度（决定清脆 / 绵长）
 *   - attackScale  起音时间缩放（决定瞬态硬度）
 *   - lowCut/highCut 频段权重（决定低频厚度 / 高频纹理）
 *   - onsetThreshold onset 触发阈值（决定灵敏度）
 *
 * 强度滑块（haptic_amplitude）在上述曲线上做乘算，二者互不干扰。
 */
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
        118L, 1.00f, 0.32f, 1.00f, 55f, 650f, 0.08f, 1.00f
    ),
    BASS(
        "bass", "低频", "Bass",
        128L, 1.22f, 0.20f, 1.45f, 32f, 420f, 0.07f, 1.35f
    ),
    CRISP(
        "crisp", "清脆", "Crisp",
        98L, 0.92f, 0.62f, 0.55f, 90f, 1400f, 0.11f, 0.85f
    ),
    SOFT(
        "soft", "柔和", "Soft",
        142L, 0.66f, 0.14f, 1.80f, 45f, 520f, 0.06f, 0.60f
    ),
    IMMERSIVE(
        "immersive", "强劲", "Immersive",
        108L, 1.35f, 0.42f, 0.78f, 38f, 900f, 0.05f, 1.55f
    ),
    PURE(
        "pure", "纯净", "Pure",
        260L, 1.12f, 0.74f, 0.42f, 120f, 2200f, 0.19f, 1.25f
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
                ampCtrl = BeatTiming(2.2f, 8L, 18L),
                plain = BeatTiming(3.2f, 10L, 25L),
                ampBase = 70f, attackFrac = 0.30f, sustainFrac = 0f,
                attackAmpFrac = 1.0f, decayAmpFrac = 0.40f
            ),
        )

        private const val RING_BUFFER_CAPACITY = 131072

        private const val FRAME_BLOCK_SIZE = 256

        private const val MAXIMUM_CHANNELS = 8

        private fun outputGainForPackage(packageName: String): Float = when (packageName) {
            
            "com.kugou.android.lite",
            "com.kugou.android" -> 1.45f  
            "tv.danmaku.bili" -> 1.50f  
            "cn.kuwo.player" -> 1.40f  
            "com.netease.cloudmusic" -> 1.45f  
            "org.flos.phira" -> 1.45f  
            "com.md3music.md3music" -> 1.45f  
            else -> 1.40f  
        }

        private const val AMBIENT_TEMPERATURE_CELSIUS = 25.0f
        private const val LIMITING_TEMPERATURE_CELSIUS = 80.0f
        private const val CRITICAL_TEMPERATURE_CELSIUS = 100.0f

        const val SUB_BASS_LOW = 20f
        const val SUB_BASS_HIGH = 80f
        const val MID_BASS_LOW = 80f
        const val MID_BASS_HIGH = 200f
        const val TEXTURE_LOW = 200f
        const val TEXTURE_HIGH = 800f

        /** Per-event diagnostic logging; off in release to keep the beat path clean. */
        @Volatile var verboseLogging: Boolean = BuildConfig.DEBUG

        val WAVE_SUB_BASS_IMPACT = floatArrayOf(1.0f, 0.95f, 0.85f, 0.70f, 0.50f, 0.30f, 0.15f, 0.05f)
        val WAVE_MID_TRANSIENT  = floatArrayOf(1.0f, 0.60f, 0.20f, 0.05f)
        val WAVE_MICRO_TEXTURE  = floatArrayOf(0.4f, 0.80f, 0.40f, 0.10f, 0.60f, 0.20f)
    }

    enum class HapticPreset(val id: Int, val description: String) {
        BALANCED(0, "标准平衡模式"),
        BASS_ENHANCED(1, "重低音增强 (Sub-Bass Emphasized)"),
        TEXTURE_FOCUS(2, "高频微震纹理 (Micro-Texture Focus)"),
        IMPACT_MAX(3, "极致冲击爆发 (Maximum Transient Attack)"),
        CUSTOM(4, "自定义调校 (Custom Parameters)")
    }

    private val nativeBridge = NativeBridge()
    private lateinit var audioIngress: AudioIngress
    private val impactPolicy = HapticImpactPolicy()

    private val vibrateProxy = VibrateProxy(context)

    private val deviceProfile = detectDeviceProfile(
        context = context,
        persistedProfileId = prefs.getString(RootHardwareProbe.PREF_PROFILE, null)
    )
    val hapticEventGenerator = HapticEventGenerator(context, deviceProfile)

    private val hapticSynthesizer = HapticSynthesizer(deviceProfile)

    private val musicStructureAnalyzer = MusicStructureAnalyzer()
    @Volatile private var currentMusicStructure = MusicStructureAnalyzer.Snapshot()

    @Volatile var isVisualizerSource = false

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineJob = engineScope.coroutineContext[Job]!!

    var logCallback: LogCallback? = null

    private var sampleRate = 48000
    private var channels = 2

    private val isEngineEnabled = AtomicBoolean(true)
    private val frameIndexCounter = AtomicLong(0)
    private var lastParameterUpdateTime = 0L

    @Volatile private var directDriveSmoothAmp = 0f
    @Volatile private var bodyAmpScale = 1.0f
    private var telemetryDbgCounter = 0

    @Volatile private var nativeSchedulerActive = false
    @Volatile private var nativeLastAudioTime = 0L
    // 5.2.7: 记录上一次的风格快照，避免每 60 帧重复刷同一条日志。
    @Volatile private var lastStyleSnapshot = ""
    // 风格预设换算出的运行时参数，供 triggerBeatVibration / native 回调使用。
    @Volatile private var activeStyle: StylePreset = StylePreset.BALANCED
    @Volatile private var activeIntensityPct: Int = 75
    // 5.2.7: beat 调试日志计数器（前 12 次全打，之后每 40 次打一条）。
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

    @Volatile private var pcmFallbackAmplitude = 0
    @Volatile private var pcmFallbackAtMs = 0L
    @Volatile private var disabledCancelSent = false

    private val lastVibrationMs = AtomicLong(0L)
    @Volatile private var lastBeatEvent = ""

    private var lastPcmIngressLogMs = 0L
    private var ignoredSilentPcmBlocks = 0L
    private var processFrameCount = 0L  

    @Volatile private var pendingPrimitive: HapticPrimitive? = null
    @Volatile private var pendingSemanticLabel: String = "NONE"
    @Volatile private var pendingPrimitiveTime: Long = 0L
    private var lastSemanticImpactTime = 0L
    private val semanticImpactRefractoryMs: Long
        get() = DeviceTuningRegistry.current(deviceProfile).minIntervalMs.coerceIn(24L, 72L)

    val telemetryData = TelemetryMonitor()


    init {
        audioIngress = AudioIngress(nativeBridge, ::onNativeTelemetry)

        // Do not spawn `su` or recursively scan /sys from every injected target process.
        // Root probing is a module-side operation; hooked apps only consume cached/known nodes.
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
        Log.i(TAG, "App haptic calibration: package=$targetPackage outputGain=${outputGainForPackage(targetPackage)}")
        val deviceTuning = com.mouya.musichaptics.haptic.DeviceTuningRegistry.current(deviceProfile)
        Log.i(TAG, "[Device Profile] name=${hapticEventGenerator.profile.name} id=${deviceTuning.profileId} actuator.f0=${hapticEventGenerator.profile.actuator.resonanceFreq}Hz maxAmp=${hapticEventGenerator.profile.actuator.maxAmplitude} damping=${hapticEventGenerator.profile.actuator.dampingRatio} q=${hapticEventGenerator.profile.actuator.qFactor} tuning=${deviceTuning.reason}")
        Log.i(TAG, "[Vibrator Capability] hasAmpCtrl=${vibrateProxy.hasAmplitudeControl} primitives: CLICK=${vibrateProxy.primitiveClickSupported} TICK=${vibrateProxy.primitiveTickSupported} THUD=${vibrateProxy.primitiveHeavyClickSupported}")
        if (nativeBridge.isLoaded) {
            
            
            
            nativeBridge.beatTriggerCallback = { event, intensity ->
                triggerBeatVibration(event, intensity)
            }
            val beatMsg = "[Beat] beatTriggerCallback registered: hasVibrator=${vibrateProxy.hasVibrator} primitives: click=${vibrateProxy.primitiveClickSupported} tick=${vibrateProxy.primitiveTickSupported} heavyClick=${vibrateProxy.primitiveHeavyClickSupported}"
            Log.i(TAG, beatMsg)
            LogBroadcaster.sendLog(context, beatMsg)

            nativeSchedulerActive = nativeBridge.startScheduler()

            
            
            
            
            
            
            
            if (nativeSchedulerActive) {
                
                
                try { initUdpRootTransport() } catch (_: Exception) {}
            }

            if (nativeSchedulerActive) {
                Log.i(TAG, "Native Haptic Scheduler: ENABLED (5ms frame timing, Direct Drive + Android Vibrator fallback)")
            } else {
                Log.w(TAG, "Native Haptic Scheduler: START FAILED — falling back to coroutine")
            }
        }

        
        // C++ 核心重构：不再启动 Kotlin DSP Worker
        // PCM 数据通过 processAudioFrame → nativeBridge.processAudioDirect() 直接送入 C++
        engineScope.launch {
            runSemanticFrameLoop()
        }
        Log.i(TAG, "Native scheduler: low-latency event timing; Haptics: transient-first onset strikes")
        val readyMsg = "[System Ready] v${BuildConfig.VERSION_NAME} C++ Direct Drive Renderer: ${if (nativeBridge.isLoaded) "NATIVE ACTIVE" else "FALLBACK"} | Device: ${hapticEventGenerator.profile.name} | Actuator: ${hapticEventGenerator.profile.actuator.resonanceFreq.toInt()}Hz Q=${hapticEventGenerator.profile.actuator.qFactor} rise=${hapticEventGenerator.profile.actuator.riseTimeMs.toInt()}ms fall=${hapticEventGenerator.profile.actuator.fallTimeMs.toInt()}ms | C++ 5-Channel: Percussion+Bass+Vocal+Harmonic+Texture | Onset Detection: KICK/SNARE/VOCAL/BODY | 200Hz LRA Physics Model: ON | Scheduler: ${if (nativeSchedulerActive) "NATIVE DIRECT" else "COROUTINE (16ms)"}"
        Log.i(TAG, readyMsg)
        logCallback?.onLog(readyMsg)
        LogBroadcaster.sendLog(context, readyMsg)
    }

    
    private fun initUdpRootTransport() {
        try {
            val socket = DatagramSocket()
            socket.connect(java.net.InetAddress.getByName("127.0.0.1"), RootHapticDaemon.DAEMON_PORT)

            
            
            var owner: Any? = socket
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
            val rawFd = descriptor.getInt(fdObj)
            val parcel = ParcelFileDescriptor.fromFd(rawFd)
            val ok = nativeBridge.initUdpHapticFromFd(parcel.fd, RootHapticDaemon.DAEMON_PORT)
            Log.i(TAG, "[UDP] Java socket fd=$rawFd nativeFd=${parcel.fd} init=$ok")
            LogBroadcaster.sendLog(context, "[UDP] transport init=$ok rawFd=$rawFd nativeFd=${parcel.fd}")
            if (!ok) {
                parcel.close()
                socket.close()
            } else {
                udpSocket = socket
                udpParcel = parcel
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

                
                nativeBridge.beatTriggerCallback = { event, intensity ->
                    triggerBeatVibration(event, intensity)
                }

            } catch (e: Exception) {
                LogBroadcaster.sendLog(context, "[DirectDrive] tryRootAssisted exception: ${e.message}")
                Log.e(TAG, "tryRootAssistedDirectDrive failed", e)
            }
        }).start()
    }

    
    // Pre-encoded root-pipe commands. The pipe write runs at up to 200 Hz on
    // the native scheduler callback thread; String.format() allocates a
    // Formatter + several boxed objects per call, so we keep lookup tables.
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
        }
    }

    
    fun onKotlinBeatDetected(event: String, intensity: Int, rms: Float) {
        if (verboseLogging) {
            Log.d(TAG, "[BEAT] event=$event intensity=$intensity rms=${"%.5f".format(rms)}")
        }
        triggerBeatVibration(event, intensity)
    }

    
    fun emitPhiraBeat(event: String, intensity: Int) = triggerBeatVibration(event, intensity)

    
    private fun triggerBeatVibration(event: String, intensity: Int) {
        if (!vibrateProxy.hasVibrator || hapticPaused) return
        val now = SystemClock.elapsedRealtime()
        val plan = impactPolicy.plan(
            event = event,
            intensity = intensity,
            profile = hapticEventGenerator.profile,
            amplitudeControl = vibrateProxy.hasAmplitudeControl,
            forceDefaultAmplitude = vibrateProxy.forceDefaultAmplitude
        ) ?: return

        val previous = lastVibrationMs.get()
        if (now - previous < plan.cooldownMs) return
        if (!lastVibrationMs.compareAndSet(previous, now)) return

        // 5.2.7: 风格预设真正作用于包络 —— 幅度按 intensity% × ampScale 三级乘算，
        // 锐度与起音时间由预设改写，冷却间隔沿用预设的疏密节奏。
        val style = activeStyle
        val intensityPct = activeIntensityPct.coerceIn(10, 100)
        val styleAmp = ((intensity / 255f) * (intensityPct / 100f) * style.ampScale * style.accentScale)
            .coerceIn(0.08f, 0.98f)
        val durSec = (plan.totalDurationMs * style.attackScale.coerceIn(0.4f, 2.0f) / 1000f)
            .coerceIn(0.008f, 0.40f)
        val attackSec = (hapticEventGenerator.profile.actuator.riseTimeMs / 1000f)
            .coerceIn(0.001f, 0.08f) * style.attackScale.coerceIn(0.4f, 2.0f)
        val sharpness = style.sharpness.coerceIn(0.05f, 1.0f)
        val usedDynamic = vibrateProxy.performDynamicEffect(styleAmp, sharpness, durSec, attackSec)

        if (!usedDynamic) {
            val timings = LongArray(plan.segments.size)
            val amplitudes = IntArray(plan.segments.size)
            val gain = (intensityPct / 100f) * style.ampScale
            plan.segments.forEachIndexed { index, segment ->
                timings[index] = segment.durationMs
                amplitudes[index] = (segment.amplitude * gain).toInt().coerceIn(1, 255)
            }
            try {
                vibrateProxy.performWaveform(timings, amplitudes)
            } catch (t: Throwable) {
                Log.w(TAG, "[HAPTIC] output failed: ${t.message}")
            }
        }
        lastBeatEvent = plan.event

        // 5.2.7 调试日志：每次触发都打印（不受 verboseLogging 门控），并做限流，
        // 便于核对"UI 预设 → DSP 参数 → 实际输出"三者是否一致。
        val beatCounter = beatLogCounter.incrementAndGet()
        if (beatCounter <= 12 || beatCounter % 40 == 0L) {
            val dbg = "[BEAT] #${beatCounter} ${plan.event} rawInt=$intensity " +
                    "style=${style.key} pct=${intensityPct}% amp=${"%.3f".format(styleAmp)} " +
                    "sharp=${"%.2f".format(sharpness)} dur=${"%.3f".format(durSec)}s " +
                    "atk=${"%.4f".format(attackSec)}s dyn=$usedDynamic " +
                    "path=${if (usedDynamic) "DynamicEffect" else "Waveform"} " +
                    "cooldown=${plan.cooldownMs}ms"
            Log.i(TAG, dbg)
            LogBroadcaster.sendLog(context, dbg)
        }

        if (verboseLogging) {
            Log.d(TAG, "[HAPTIC] ${plan.event} intensity=$intensity duration=${plan.totalDurationMs}ms cooldown=${plan.cooldownMs}ms dynamic=$usedDynamic")
        }
    }

    private suspend fun runSemanticFrameLoop() {
        val pullIntervalMs = 16L
        var frameCounter = 0L
        val semanticFrameBuffer = FloatArray(64 * 4)
        val onsetBuffer = FloatArray(64 * 4)
        var lastAudioInputTime = 0L
        val silenceTimeoutMs = 2500L

        // C++ 核心重构：onset 帧触发振动
        var lastBeatMs = 0L
        val beatRefractoryMs = DeviceTuningRegistry.current(deviceProfile).minIntervalMs.coerceIn(24L, 72L)

        while (true) {
            val frameStartTime = SystemClock.elapsedRealtime()

            try {
                if (hapticPaused) {
                    kotlinx.coroutines.delay(pullIntervalMs)
                    continue
                }

                // 1. Low-rate semantic telemetry is consumed only when the native scheduler is unavailable.
                val semanticFrameCount = if (nativeBridge.isLoaded && !nativeSchedulerActive) {
                    nativeBridge.getSemanticFrames(semanticFrameBuffer, 64)
                } else 0

                // 2. Onset 帧 → 事件驱动振动（C++ onsetBuf_ 读取）
                val onsetFrameCount = if (nativeBridge.isLoaded && !nativeSchedulerActive) {
                    nativeBridge.getOnsetFrames(onsetBuffer, 64)
                } else 0

                if (onsetFrameCount > 0 && !hapticPaused) {
                    val now = SystemClock.elapsedRealtime()
                    for (i in 0 until onsetFrameCount) {
                        val k = onsetBuffer[i * 4 + 0]
                        val s = onsetBuffer[i * 4 + 1]
                        val v = onsetBuffer[i * 4 + 2]
                        val b = onsetBuffer[i * 4 + 3]

                        if (now - lastBeatMs < beatRefractoryMs) continue

                        // 选择最强的 onset 类型
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
                        break // 每轮只触发一次
                    }
                }

                if (semanticFrameCount > 0 || onsetFrameCount > 0) {
                    lastAudioInputTime = frameStartTime
                }

                val timeSinceAudio = frameStartTime - lastAudioInputTime
                val hasNativeAudioActivity = timeSinceAudio < silenceTimeoutMs
                // 5.2.6 修复：lastAudioInputTime 仅在 semantic/onset 帧计数非零时
                // 刷新，而这两个计数在 native 调度器启用时恒为 0，导致
                // hasAudioActivity 永远 false、恢复逻辑永不触发。
                // markHookAudioArrival() 每次 PCM 到达都会写 nativeLastAudioTime，
                // 以它为准才是真实音频活动。
                val hookPcmAge = frameStartTime - nativeLastAudioTime
                val hasHookAudioActivity = nativeLastAudioTime > 0L && hookPcmAge < silenceTimeoutMs
                val hasAudioActivity = hasNativeAudioActivity || hasHookAudioActivity

                if (hasAudioActivity && vibrateProxy.paused) {
                    vibrateProxy.setResumed()
                }

                // 5.2.6 修复：markCandidateStopped() 会置 hapticPaused=true，
                // 但此前只有 vibrateProxy.paused 被复位，hapticPaused 一旦被
                // 误判暂停就永久锁死 —— onset 分支的 !hapticPaused 守卫会
                // 让整条振动链路再也无法触发。音频恢复时必须一并解冻。
                if (hasAudioActivity && hapticPaused) {
                    hapticPaused = false
                    Log.i(TAG, "[PLAYBACK RESUMED] PCM activity detected, clearing hapticPaused latch")
                    LogBroadcaster.sendLog(context, "[PLAYBACK RESUMED] haptic engine unpaused")
                }

                if (hasAudioActivity && vibrateProxy.hasVibrator) {
                    val semanticPrim = pendingPrimitive
                    val semanticAge = frameStartTime - pendingPrimitiveTime
                    val semanticFresh = semanticPrim != null && semanticAge < 100L

                    if (semanticFresh) {
                        val prim = semanticPrim!!
                        val timeSinceSemantic = frameStartTime - lastSemanticImpactTime
                        if (timeSinceSemantic >= semanticImpactRefractoryMs) {
                            lastSemanticImpactTime = frameStartTime
                            pendingPrimitive = null
                        }
                    }
                }

                LinkHealthMonitor.heartbeatTelemetry()

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
        if (prefs is com.mouya.musichaptics.hook.HookConfigPreferences) {
            prefs.refresh()
        }
        val masterState = try { prefs.getBoolean("master_switch", true) } catch (e: Exception) { true }
        isEngineEnabled.set(masterState)

        val baseAmplitude = try { prefs.getFloat("haptic_amplitude", 2.0f) } catch (e: Exception) { 2.0f }
        val boostLevel = try {
            if (prefs.contains("haptic_boost_level")) prefs.getFloat("haptic_boost_level", 1.0f)
            else prefs.getFloat("haptic_bass_boost", 1.0f)
        } catch (e: Exception) { 1.0f }
        val presetId = try { prefs.getInt("haptic_preset_id", HapticPreset.BALANCED.id) } catch (e: Exception) { HapticPreset.BALANCED.id }
        val crossoverBypass = try { prefs.getBoolean("crossover_bypass", true) } catch (e: Exception) { true }
        val powerAmplify = try { prefs.getBoolean("power_amplify", false) } catch (e: Exception) { false }
        val uiPreset = try { prefs.getInt("selected_preset", 2) } catch (e: Exception) { 2 }
        val presetGain = floatArrayOf(0.70f, 0.90f, 1.00f, 1.20f).getOrElse(uiPreset) { 1.00f }

        // 5.2.7: 风格预设真正接管 DSP 参数。每档改写频段、幅度、锐度与阈值，
        // 用户在 UI 切换后必须能明显听出/觉出差别，而不是只乘一个 0.7~1.2 的常数。
        val styleKey = try { prefs.getString("style_preset", "balanced") } catch (e: Exception) { "balanced" }
        val style = StylePreset.fromKey(styleKey)
        val intensityPct = try { prefs.getInt("haptic_intensity_pct", 75) } catch (e: Exception) { 75 }
        // 参考实现的三级乘算：onset 强度 × intensity% × preset.ampScale，
        // 并保留 0.5 下限，保证弱起音也能被感知（与参考实现 0.08 下限同理）。
        val intensityScale = (intensityPct.coerceIn(10, 100) / 100f) * style.ampScale
        val outputAmp = (baseAmplitude * presetGain * intensityScale * if (powerAmplify) 1.15f else 1.0f)
            .coerceIn(0.3f, 6.0f)

        // 频段权重改由风格预设决定；crossover_bypass 仅作为未选预设时的兼容开关。
        val lowCutoffFreq = if (prefs.contains("style_preset")) style.lowCutHz
                            else if (crossoverBypass) 55.0f else 150.0f
        val highCutoffFreq = if (prefs.contains("style_preset")) style.highCutHz
                             else if (crossoverBypass) 650.0f else 330.0f

        // 预设切换需要立刻可见：打印一行完整快照，便于核对 UI 与 DSP 是否一致。
        val styleSnapshot = "[STYLE] preset=${style.key}(${style.label}) intensity=${intensityPct}% " +
                "ampScale=${style.ampScale} effAmp=${"%.2f".format(outputAmp)} " +
                "sharp=${style.sharpness} atkScale=${style.attackScale} " +
                "band=${lowCutoffFreq.toInt()}-${highCutoffFreq.toInt()}Hz " +
                "onsetTh=${style.onsetThreshold} cooldown=${style.cooldownMs}ms"
        if (styleSnapshot != lastStyleSnapshot) {
            lastStyleSnapshot = styleSnapshot
            activeStyle = style
            activeIntensityPct = intensityPct
            Log.i(TAG, styleSnapshot)
            LogBroadcaster.sendLog(context, styleSnapshot)
        } else {
            activeStyle = style
            activeIntensityPct = intensityPct
        }

        nativeBridge.configure(
            sampleRate = sampleRate.toFloat(),
            lowCut = lowCutoffFreq,
            highCut = highCutoffFreq,
            amplitude = outputAmp,
            presetId = presetId
        )

        // DeviceProfile is now a live DSP input, not only a renderer hint.
        nativeBridge.configureProfile(deviceProfile)

        telemetryData.lowPassCutoffHz = lowCutoffFreq
        telemetryData.highPassCutoffHz = highCutoffFreq
        telemetryData.userAmplitudeScale = outputAmp

        hapticEventGenerator.boostLevel = boostLevel
        hapticEventGenerator.userAmplitudeScale = outputAmp.coerceIn(0.5f, 4.0f)

        val silenceTh = try { prefs.getFloat("silence_threshold", Float.NaN) } catch (e: Exception) { Float.NaN }
        hapticEventGenerator.injectedSilenceThreshold = if (silenceTh.isNaN()) null else silenceTh

        
        
        try {
            val forceDefaultPref = prefs.getBoolean("force_default_amplitude", vibrateProxy.forceDefaultAutoDetected)
            vibrateProxy.setForceDefaultAmplitude(forceDefaultPref)
        } catch (e: Exception) {
            Log.w(TAG, "force_default_amplitude sync failed: ${e.message}")
        }

        val energyTh = try { prefs.getFloat("energy_threshold", Float.NaN) } catch (e: Exception) { Float.NaN }
        hapticEventGenerator.injectedEnergyThreshold = if (energyTh.isNaN()) null else energyTh

        val minAmp = try { prefs.getInt("min_amplitude", -1) } catch (e: Exception) { -1 }
        hapticEventGenerator.injectedMinGuaranteedAmplitude = if (minAmp < 0) null else minAmp

        hapticEventGenerator.synchronizeProfile(prefs)
        hapticEventGenerator.synchronizePreset(prefs)

        hapticEventGenerator.logListener = { msg ->
            logCallback?.onLog(msg)
            LogBroadcaster.sendLog(context, msg)
        }

        val deviceTuning = DeviceTuningRegistry.current(deviceProfile)
        val actuator = deviceProfile.actuator
        val synthConfig = HapticSynthesizer.SynthConfig(
            synthesisRateHz = try { prefs.getInt("synth_rate_hz", HapticSynthesizer.SYNTHESIS_RATE_HZ) } catch (e: Exception) { HapticSynthesizer.SYNTHESIS_RATE_HZ },
            // Device-specific physical defaults are used only when the user has
            // not overridden the synthesizer controls in preferences.
            lraF0 = try { prefs.getFloat("synth_lra_f0", actuator.resonanceFreq) } catch (e: Exception) { actuator.resonanceFreq },
            lraQ = try { prefs.getFloat("synth_lra_q", actuator.qFactor) } catch (e: Exception) { actuator.qFactor },
            attackTauImpact = try { prefs.getFloat("synth_attack_impact", HapticSynthesizer.ATTACK_TAU_IMPACT) } catch (e: Exception) { HapticSynthesizer.ATTACK_TAU_IMPACT },
            decayTauImpact = try { prefs.getFloat("synth_decay_impact", HapticSynthesizer.DECAY_TAU_IMPACT) } catch (e: Exception) { HapticSynthesizer.DECAY_TAU_IMPACT },
            attackTauContinuous = try { prefs.getFloat("synth_attack_continuous", HapticSynthesizer.ATTACK_TAU_CONTINUOUS) } catch (e: Exception) { HapticSynthesizer.ATTACK_TAU_CONTINUOUS },
            decayTauContinuous = try { prefs.getFloat("synth_decay_continuous", HapticSynthesizer.DECAY_TAU_CONTINUOUS) } catch (e: Exception) { HapticSynthesizer.DECAY_TAU_CONTINUOUS },
            releaseTau = try { prefs.getFloat("synth_release", HapticSynthesizer.RELEASE_TAU) } catch (e: Exception) { HapticSynthesizer.RELEASE_TAU },
            sustainLevel = try { prefs.getFloat("synth_sustain", HapticSynthesizer.SUSTAIN_LEVEL) } catch (e: Exception) { HapticSynthesizer.SUSTAIN_LEVEL },
            thermalWarn = try { prefs.getFloat("synth_thermal_warn", HapticSynthesizer.THERMAL_WARN) } catch (e: Exception) { HapticSynthesizer.THERMAL_WARN },
            thermalCrit = try { prefs.getFloat("synth_thermal_crit", HapticSynthesizer.THERMAL_CRIT) } catch (e: Exception) { HapticSynthesizer.THERMAL_CRIT },
            thermalRth = try { prefs.getFloat("synth_thermal_rth", actuator.thermalResistance) } catch (e: Exception) { actuator.thermalResistance },
            thermalCth = try { prefs.getFloat("synth_thermal_cth", actuator.thermalCapacitance) } catch (e: Exception) { actuator.thermalCapacitance },
            impactGain = try { prefs.getFloat("synth_impact_gain", deviceTuning.impactGain) } catch (e: Exception) { deviceTuning.impactGain },
            continuousGain = try { prefs.getFloat("synth_continuous_gain", 1.0f) } catch (e: Exception) { 1.0f },
            textureGain = try { prefs.getFloat("synth_texture_gain", deviceTuning.textureGainScale) } catch (e: Exception) { deviceTuning.textureGainScale },
            masterGain = try { prefs.getFloat("synth_master_gain", 1.0f) } catch (e: Exception) { 1.0f },
        )
        hapticSynthesizer.updateParameters(synthConfig)

        // Profile configuration is applied above together with the live native DSP parameters.
        Log.i(TAG, "[Device Adaptation] profile=${deviceTuning.profileId} dspFloor=${"%.4f".format(deviceProfile.dspEnergyFloor)} sub=${"%.2f".format(deviceProfile.dspSubMult)} kick=${"%.2f".format(deviceProfile.dspKickMult)} snare=${"%.2f".format(deviceProfile.dspSnareMult)} tick=${"%.2f".format(deviceProfile.dspTickMult)} body=${"%.2f".format(deviceProfile.dspBodyMult)} refractory=${"%.2f".format(deviceProfile.dspRefractoryScale)}")
    }

    fun refreshSettings() {
        synchronizeParameters()
        val message = "Settings refreshed in hooked process | master=${isEngineEnabled.get()} amp=${"%.2f".format(telemetryData.userAmplitudeScale)}"
        Log.i(TAG, message)
        LogBroadcaster.sendLog(context, message)
    }

    // dspWorkerLock moved to class declaration

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

        // C++ 核心重构：不再需要重启 DSP Worker
        // C++ 引擎会在下次 processAudioDirect 时自动适应新采样率
        nativeBridge.configure(sampleRate.toFloat(), 60.0f, 200.0f, 2.0f, 0)

        synchronizeParameters()

        val logMessage = "System reconfigured to: ${sampleRate}Hz | $channels Channels (C++ Core)"
        Log.i(TAG, logMessage)
        logCallback?.onLog(logMessage)
        LogBroadcaster.sendLog(context, logMessage)
    }

    fun onPlaybackPaused() {
        Log.w(TAG, "[PLAYBACK PAUSED CAUGHT] Marking as candidate stopped, deferring immediate haptic decay...")
        
        markCandidateStopped()
    }
    
    private fun markCandidateStopped() {
        val lastPcmMs = pcmFallbackAtMs
        
        lifecycleScope.launch {
            kotlinx.coroutines.delay(200)
            if (pcmFallbackAtMs - lastPcmMs <= 50) { 
                Log.i(TAG, "[PLAYBACK TRULY PAUSED] No PCM received for 200ms. Forcing immediate haptic decay")
                LogBroadcaster.sendLog(context, "[PLAYBACK TRULY PAUSED] Forcing immediate haptic decay")
                hapticPaused = true
                pcmFallbackAtMs = 0L
                vibrateProxy.setPaused()
                nativeBridge.clearHapticBuffer()
                directDriveSmoothAmp = 0f
                pendingPrimitive = null  
                pendingSemanticLabel = "NONE"
                hapticSynthesizer.forceDecay()
                LinkHealthMonitor.setPlayingState(false)
            } else {
                Log.i(TAG, "[PLAYBACK FALSE PAUSED] PCM is still arriving. Ignoring pause/release event.")
            }
        }
    }

    
    fun onDspWorkerLog(msg: String) {
        LogBroadcaster.sendLog(context, msg)
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
        
        
        val totalEnergy = telemetry[0] + telemetry[1] + telemetry[2]
        directDriveSmoothAmp += (totalEnergy * 255f - directDriveSmoothAmp) * 0.3f

        
        telemetryDbgCounter++
        if (telemetryDbgCounter % 200 == 0) {
            val msg = "[DSP-TELEM] subRms=%.5f midRms=%.5f texRms=%.5f bassBand=? lowMid=? vocal=? | onset: KICK=%.4f SNARE=%.4f VOCAL=%.4f BODY=%.4f | framesRead=$framesRead"
                .format(telemetry[0], telemetry[1], telemetry[2],
                        telemetryData.kickOnset, telemetryData.snareOnset,
                        telemetryData.vocalOnset, telemetryData.bodyOnset)
            Log.i(TAG, msg)
            LogBroadcaster.sendLog(context, msg)
        }
    }
fun processAudioFrame(pcmData: ShortArray?) {
        if (pcmData == null || pcmData.isEmpty() || !isEngineEnabled.get()) {
            // Cancel exactly once when the master switch flips off; the old code
            // issued a Vibrator.cancel() on every incoming frame while disabled.
            if (!isEngineEnabled.get() && !disabledCancelSent) {
                disabledCancelSent = true
                vibrateProxy.cancel()
            }
            return
        }
        disabledCancelSent = false
        resumeFromHook()
        audioIngress.processPcm16(pcmData, 0, pcmData.size, channels)
    }

    internal fun markHookAudioArrival(atMs: Long = SystemClock.elapsedRealtime()) {
        pcmFallbackAtMs = atMs
        nativeLastAudioTime = atMs
    }

    internal fun processPcm16FromHook(data: ShortArray, offset: Int, sampleCount: Int, channelCount: Int) {
        if (!isEngineEnabled.get()) return
        ensureIngressFormat(channelCount)
        resumeFromHook()
        markHookAudioArrival()
        audioIngress.processPcm16(data, offset, sampleCount, channelCount)
    }

    internal fun processPcmFloatFromHook(data: FloatArray, offset: Int, frameCount: Int, channelCount: Int) {
        if (!isEngineEnabled.get()) return
        ensureIngressFormat(channelCount)
        resumeFromHook()
        markHookAudioArrival()
        audioIngress.processFloatPcm(data, offset, frameCount, channelCount)
    }

    internal fun processPcmBytesFromHook(data: ByteArray, offset: Int, byteCount: Int, channelCount: Int) {
        if (!isEngineEnabled.get()) return
        ensureIngressFormat(channelCount)
        resumeFromHook()
        markHookAudioArrival()
        audioIngress.processPcm16Bytes(data, offset, byteCount, channelCount)
    }

    internal fun processPcmBufferFromHook(data: java.nio.ByteBuffer, startPosition: Int, byteCount: Int, channelCount: Int) {
        if (!isEngineEnabled.get()) return
        ensureIngressFormat(channelCount)
        resumeFromHook()
        markHookAudioArrival()
        audioIngress.processPcm16Buffer(data, startPosition, byteCount, channelCount)
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
        LinkHealthMonitor.setPlayingState(true)
        LinkHealthMonitor.heartbeatAudioInput()
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

        LinkHealthMonitor.setPlayingState(false)
        hapticEventGenerator.release()
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