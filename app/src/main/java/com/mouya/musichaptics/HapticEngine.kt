
package com.mouya.musichaptics

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/**
 * High-Performance Electro-Haptic DSP Synthesis Engine (MusicHapticsX Super-Core)
 * 
 * Implements real-time audio-to-haptic transduction utilizing:
 * 1. 4th-Order Linkwitz-Riley (LR4) Active Crossover Network.
 * 2. Time-Domain Autocorrelation Pitch-Tracking with Parabolic Interpolation.
 * 3. Dual-Node Lumped-Parameter Voice-Coil Electro-Thermal Simulation.
 * 4. Piecewise Soft-Knee Dynamic Range Compression (DRC).
 * 5. Micro-time Scale Pulse-Width Haptic Primitive Synthesizer.
 * 
 * Target Architecture: High-fidelity Linear Resonant Actuators (LRA).
 */
class HapticEngine(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val TAG = "HapticDSPCore"
        private const val RING_BUFFER_CAPACITY = 131072
        private const val FRAME_BLOCK_SIZE = 512
        private const val MAXIMUM_CHANNELS = 8
        
        // Thermal constants for the LRA dual-node RC thermal network simulation
        private const val AMBIENT_TEMPERATURE_CELSIUS = 25.0f
        private const val LIMITING_TEMPERATURE_CELSIUS = 80.0f
        private const val CRITICAL_TEMPERATURE_CELSIUS = 100.0f
    }

    var uiBuilder: MainUiBuilder? = null

    private var sampleRate = 48000
    private var channels = 2
    private val audioRingBuffer = ConcurrentAudioRingBuffer(RING_BUFFER_CAPACITY)
    private val processingFrame = FloatArray(FRAME_BLOCK_SIZE)

    // 🟢 优化：预分配分频信号缓冲区，彻底避免音频热路径上的 GC 抖动
    private val subBassSignal = FloatArray(FRAME_BLOCK_SIZE)
    private val midBassSignal = FloatArray(FRAME_BLOCK_SIZE)
    private val presenceSignal = FloatArray(FRAME_BLOCK_SIZE)

    // DSP Class Initializations
    private val crossoverNetwork = ThreeWayCrossover()
    private val pitchTracker = AutocorrelationPitchEstimator()
    private val dynamicCompressor = DualKneeCompressor()
    private val thermalSimulator = DualNodeThermalSimulator()
    private val hapticScheduler = PrecisionHapticScheduler(context)

    private val isEngineEnabled = AtomicBoolean(true)
    private val frameIndexCounter = AtomicLong(0)
    private var lastParameterUpdateTime = 0L

    val telemetryData = TelemetryMonitor()

    init {
        synchronizeParameters()
        Log.i(TAG, "Initialization of Electro-Haptic DSP Engine complete. System ready.")
    }

    /**
     * Synchronizes and updates the filter coefficients and DRC parameters 
     * based on the latest runtime user preferences.
     */
    private fun synchronizeParameters() {
        val masterState = try { prefs.getBoolean("master_switch", true) } catch (e: Exception) { true }
        isEngineEnabled.set(masterState)

        val inputGain = try { prefs.getFloat("haptic_gain", 1.0f) } catch (e: Exception) { 1.0f }
        val outputAmp = try { prefs.getFloat("haptic_amplitude", 1.0f) } catch (e: Exception) { 1.0f }
        val purityParam = try { prefs.getInt("haptic_bass_purity", 50) } catch (e: Exception) { 50 }

        // Map the purity slider to the LR4 low-pass cutoff frequency [55.0Hz - 320.0Hz]
        val lowCutoffFreq = 320.0f - (purityParam.coerceIn(0, 100).toFloat() * 2.65f)
        val highCutoffFreq = lowCutoffFreq * 2.2f // Proportional middle band spacing

        crossoverNetwork.calculateCoefficients(sampleRate.toFloat(), lowCutoffFreq, highCutoffFreq)

        // 🟢 优化：根据当前的块更新速率，动态刷新压缩器的时间常数基准
        val blockRate = sampleRate.toFloat() / FRAME_BLOCK_SIZE.toFloat()
        dynamicCompressor.setParameters(
            thresholdDb = -18.0f,
            ratio = 4.5f,
            kneeWidthDb = 6.0f,
            attackMs = 4.0f,
            releaseMs = 75.0f,
            makeupGainDb = 3.5f + (inputGain * 2.0f),
            blockRate = blockRate
        )

        telemetryData.lowPassCutoffHz = lowCutoffFreq
        telemetryData.highPassCutoffHz = highCutoffFreq
        telemetryData.userAmplitudeScale = outputAmp
    }

    /**
     * Reconfigures internal buffer geometry and DSP states upon audio hardware format changes.
     */
    fun reconfigure(newSampleRate: Int, newChannels: Int) {
        if (newSampleRate <= 0 || newChannels <= 0 || newChannels > MAXIMUM_CHANNELS) {
            Log.w(TAG, "Reconfiguration rejected. Invalid audio specification: ${newSampleRate}Hz | $newChannels Ch")
            return
        }

        if (this.sampleRate == newSampleRate && this.channels == newChannels) {
            return
        }

        this.sampleRate = newSampleRate
        this.channels = newChannels

        // Flush signal buffers to prevent discontinuities and transient thermal spikes
        audioRingBuffer.clear()
        crossoverNetwork.reset()
        pitchTracker.initialize(newSampleRate)
        dynamicCompressor.reset()
        
        // 🟢 优化：动态注入时间步长，杜绝 44.1k/96k 切换时的热力学模拟曲线畸变
        val stepTimeSec = FRAME_BLOCK_SIZE.toFloat() / sampleRate.toFloat()
        thermalSimulator.reset(stepTimeSec)
        
        hapticScheduler.flush()

        synchronizeParameters()

        val logMessage = "System reconfigured to: ${sampleRate}Hz | $channels Channels"
        Log.i(TAG, logMessage)
        uiBuilder?.appendLog(logMessage)
    }

    /**
     * Entry point for incoming PCM frame streams. Normalizes inter-channel 
     * interleaved samples to single-channel floating-point formats [-1.0f, 1.0f].
     */
    fun processAudioFrame(pcmData: ShortArray?) {
        if (pcmData == null || pcmData.isEmpty() || !isEngineEnabled.get()) {
            if (!isEngineEnabled.get()) {
                audioRingBuffer.clear()
                hapticScheduler.flush()
            }
            return
        }

        val sampleLength = pcmData.size
        val targetMonoSamples = sampleLength / channels
        if (targetMonoSamples <= 0) return

        val normalizedBuffer = FloatArray(targetMonoSamples)
        var writerOffset = 0

        try {
            when (channels) {
                1 -> {
                    val scaleFactor = 1.0f / 32768.0f
                    for (i in pcmData.indices) {
                        normalizedBuffer[writerOffset++] = pcmData[i].toFloat() * scaleFactor
                    }
                }
                2 -> {
                    var i = 0
                    val scaleFactor = 1.0f / 65536.0f // Merges both channels to prevent clipping
                    while (i < sampleLength - 1) {
                        val monoSum = (pcmData[i].toFloat() + pcmData[i + 1].toFloat()) * scaleFactor
                        normalizedBuffer[writerOffset++] = monoSum
                        i += 2
                    }
                }
                else -> {
                    // Downmix matrix algorithm for multi-channel audio setups
                    var i = 0
                    val channelNormalizationFactor = 1.0f / (channels.toFloat() * 32768.0f)
                    while (i < sampleLength - channels + 1) {
                        var matrixAccumulator = 0.0f
                        for (c in 0 until channels) {
                            matrixAccumulator += pcmData[i + c].toFloat()
                        }
                        normalizedBuffer[writerOffset++] = matrixAccumulator * channelNormalizationFactor
                        i += channels
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PCM normalization exception: ${e.message}")
            return
        }

        audioRingBuffer.write(normalizedBuffer, writerOffset)

        // Process audio in blocks of FRAME_BLOCK_SIZE to execute the DSP pipeline
        var processingSafetyIterations = 0
        while (audioRingBuffer.read(processingFrame, FRAME_BLOCK_SIZE)) {
            if (processingSafetyIterations++ > 64) {
                telemetryData.ringBufferOverruns++
                break
            }
            executeDspPipeline(processingFrame)
        }
    }

    /**
     * Executes the sequential stages of the high-fidelity electro-haptic pipeline.
     */
    private fun executeDspPipeline(block: FloatArray) {
        val currentTimeMs = SystemClock.elapsedRealtime()

        // Sync parameters at a defined interval to minimize synchronization overhead
        if (currentTimeMs - lastParameterUpdateTime > 600) {
            synchronizeParameters()
            lastParameterUpdateTime = currentTimeMs
        }

        val currentFrameId = frameIndexCounter.incrementAndGet()

        // Pitch Tracking
        val detectedFundamentalFreq = pitchTracker.analyzeSignal(block)
        telemetryData.fundamentalFrequencyHz = detectedFundamentalFreq

        // Multi-Band Decimation via Linkwitz-Riley crossover
        // 🟢 优化：直接写入复用字段，彻底消除每帧 3 个局部数组的瞬时堆开销
        for (i in 0 until FRAME_BLOCK_SIZE) {
            crossoverNetwork.processSample(block[i])
            subBassSignal[i] = crossoverNetwork.subBassOutput
            midBassSignal[i] = crossoverNetwork.midBassOutput
            presenceSignal[i] = crossoverNetwork.presenceOutput
        }

        // Root Mean Square (RMS) calculation per band
        val subBassRms = computeRms(subBassSignal)
        val midBassRms = computeRms(midBassSignal)
        val presenceRms = computeRms(presenceSignal)

        // Piecewise Soft-Knee Dynamic Range Compression (DRC)
        val compressedSub = dynamicCompressor.applyCompression(subBassRms)
        val compressedMid = dynamicCompressor.applyCompression(midBassRms)
        val compressedPresence = dynamicCompressor.applyCompression(presenceRms)

        // Electro-Thermal LRA Simulation & Protection Model
        val electricalPowerPowerSum = (compressedSub * compressedSub) + (compressedMid * compressedMid * 0.4f)
        val estimatedCoilTemperature = thermalSimulator.updateThermalDynamics(electricalPowerPowerSum)
        telemetryData.estimatedCoilTemperature = estimatedCoilTemperature

        // Closed-Loop Thermal Mitigation Gain Scaling
        val thermalSafetyGain = when {
            estimatedCoilTemperature >= CRITICAL_TEMPERATURE_CELSIUS -> 0.0f
            estimatedCoilTemperature >= LIMITING_TEMPERATURE_CELSIUS -> {
                // Smooth cosine-taper attenuation profile
                val linearInterpolationRatio = (estimatedCoilTemperature - LIMITING_TEMPERATURE_CELSIUS) / 
                        (CRITICAL_TEMPERATURE_CELSIUS - LIMITING_TEMPERATURE_CELSIUS)
                0.5f * (1.0f + cos(linearInterpolationRatio * Math.PI.toFloat()))
            }
            else -> 1.0f
        }
        telemetryData.thermalAttenuationFactor = thermalSafetyGain

        // Application of user parameters and thermal compensation factors
        val amplitudeMultiplier = telemetryData.userAmplitudeScale
        val finalSubIntensity = compressedSub * amplitudeMultiplier * thermalSafetyGain
        val finalMidIntensity = compressedMid * amplitudeMultiplier * thermalSafetyGain
        val finalPresenceIntensity = compressedPresence * amplitudeMultiplier * thermalSafetyGain

        telemetryData.subBassOutputLevel = finalSubIntensity
        telemetryData.midBassOutputLevel = finalMidIntensity
        telemetryData.presenceOutputLevel = finalPresenceIntensity

        // Synthesize physical haptic actuator directives
        if (thermalSafetyGain > 0.05f) {
            synthesizeActuatorDirectives(
                sub = finalSubIntensity,
                mid = finalMidIntensity,
                presence = finalPresenceIntensity,
                pitch = detectedFundamentalFreq,
                frameIndex = currentFrameId
            )
        }
    }

    /**
     * Synthesizes complex physical haptic directives based on multi-band spectral weights.
     */
    private fun synthesizeActuatorDirectives(
        sub: Float,
        mid: Float,
        presence: Float,
        pitch: Float,
        frameIndex: Long
    ) {
        val minimumIntervalLimitMs = try { prefs.getInt("haptic_interval", 15).toLong() } catch (e: Exception) { 15L }
        val thresholdReference = try { prefs.getInt("haptic_threshold", 1200).toFloat() / 5000f } catch (e: Exception) { 0.24f }

        val systemTimeMs = SystemClock.elapsedRealtime()
        val deltaSinceLastExecution = systemTimeMs - hapticScheduler.lastDispatchTimeMs

        if (deltaSinceLastExecution < minimumIntervalLimitMs) return

        // Case A: Sub-Bass spectral dominance (Sub-audible impacts)
        if (sub > thresholdReference && sub > mid && sub > presence) {
            val scalingRatio = (pitch / 120.0f).coerceIn(0.5f, 1.5f)
            hapticScheduler.dispatch(HapticActuatorEvent(
                classification = HapticClassification.SUB_BASS_IMPACT,
                intensity = sub.coerceIn(0.1f, 1.0f),
                frequencyScalingFactor = scalingRatio,
                timestamp = systemTimeMs
            ))
            telemetryData.dispatchedSubBassImpacts++
            if (frameIndex % 15L == 0L) {
                uiBuilder?.appendLog(String.format("DSP: Sub-Bass Transient Excitation [Intensity: %.2f | Scale: %.2f]", sub, scalingRatio))
            }
            return
        }

        // Case B: Mid-Bass spectral dominance (Percussive transients)
        if (mid > thresholdReference * 0.8f && mid > presence) {
            val scalingRatio = (pitch / 185.0f).coerceIn(0.6f, 1.4f)
            hapticScheduler.dispatch(HapticActuatorEvent(
                classification = HapticClassification.MID_BASS_TRANSIENT,
                intensity = mid.coerceIn(0.1f, 1.0f),
                frequencyScalingFactor = scalingRatio,
                timestamp = systemTimeMs
            ))
            telemetryData.dispatchedMidBassTransients++
            return
        }

        // Case C: Presence band spectral dominance (Micro-textural/frictional elements)
        if (presence > thresholdReference * 0.6f && presence > sub * 1.5f) {
            val scalingRatio = (pitch / 225.0f).coerceIn(0.8f, 1.8f)
            hapticScheduler.dispatch(HapticActuatorEvent(
                classification = HapticClassification.MICRO_TEXTURAL_WAVE,
                intensity = presence.coerceIn(0.05f, 0.8f),
                frequencyScalingFactor = scalingRatio,
                timestamp = systemTimeMs
            ))
            telemetryData.dispatchedMicroTextures++
        }
    }

    private fun computeRms(signal: FloatArray): Float {
        var sum = 0.0f
        for (i in signal.indices) {
            val sample = signal[i]
            sum += sample * sample
        }
        val rms = sqrt(sum / signal.size)
        return if (rms.isNaN() || rms.isInfinite()) 0.0f else rms
    }

    fun release() {
        hapticScheduler.terminate()
        audioRingBuffer.clear()
        Log.i(TAG, "DSP Engine successfully shutdown.")
    }

    // =========================================================================
    // DSP INTERNAL CLASS IMPLEMENTATIONS
    // =========================================================================

    /**
     * Direct-Form II Transposed Bi-quadratic Filter Core Module.
     * Difference Equation:
     * y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]
     */
    private class BiQuadFilter {
        private var b0 = 1.0f; private var b1 = 0.0f; private var b2 = 0.0f
        private var a1 = 0.0f; private var a2 = 0.0f

        private var x1 = 0.0f; private var x2 = 0.0f
        private var y1 = 0.0f; private var y2 = 0.0f

        fun reset() {
            x1 = 0.0f; x2 = 0.0f; y1 = 0.0f; y2 = 0.0f
        }

        fun configureLowPass(sampleRate: Float, cutoff: Float, q: Float) {
            val omega = (2.0 * Math.PI * cutoff / sampleRate).toFloat()
            val cosW = cos(omega)
            val sinW = sin(omega)
            val alpha = sinW / (2.0f * q)

            val b0Raw = (1.0f - cosW) / 2.0f
            val b1Raw = 1.0f - cosW
            val b2Raw = (1.0f - cosW) / 2.0f
            val a0Raw = 1.0f + alpha
            val a1Raw = -2.0f * cosW
            val a2Raw = 1.0f - alpha

            b0 = b0Raw / a0Raw
            b1 = b1Raw / a0Raw
            b2 = b2Raw / a0Raw
            a1 = a1Raw / a0Raw
            a2 = a2Raw / a0Raw
        }

        fun configureHighPass(sampleRate: Float, cutoff: Float, q: Float) {
            val omega = (2.0 * Math.PI * cutoff / sampleRate).toFloat()
            val cosW = cos(omega)
            val sinW = sin(omega)
            val alpha = sinW / (2.0f * q)

            val b0Raw = (1.0f + cosW) / 2.0f
            val b1Raw = -(1.0f + cosW)
            val b2Raw = (1.0f + cosW) / 2.0f
            val a0Raw = 1.0f + alpha
            val a1Raw = -2.0f * cosW
            val a2Raw = 1.0f - alpha

            b0 = b0Raw / a0Raw
            b1 = b1Raw / a0Raw
            b2 = b2Raw / a0Raw
            a1 = a1Raw / a0Raw
            a2 = a2Raw / a0Raw
        }

        fun process(input: Float): Float {
            val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = input
            y2 = y1
            y1 = output
            return if (output.isNaN() || output.isInfinite()) 0.0f else output
        }
    }

    /**
     * Active 4th-Order Linkwitz-Riley (LR4) Active Crossover Network.
     * Consists of cascading two 2nd-order Butterworth filters to ensure 
     * a mathematically flat summation response at the transition point.
     */
    private class ThreeWayCrossover {
        private val lowPassSubStage1 = BiQuadFilter()
        private val lowPassSubStage2 = BiQuadFilter()

        private val highPassPresStage1 = BiQuadFilter()
        private val highPassPresStage2 = BiQuadFilter()

        private val lowPassMidStage1 = BiQuadFilter()
        private val lowPassMidStage2 = BiQuadFilter()
        private val highPassMidStage1 = BiQuadFilter()
        private val highPassMidStage2 = BiQuadFilter()

        var subBassOutput = 0.0f
        var midBassOutput = 0.0f
        var presenceOutput = 0.0f

        fun reset() {
            lowPassSubStage1.reset(); lowPassSubStage2.reset()
            highPassPresStage1.reset(); highPassPresStage2.reset()
            lowPassMidStage1.reset(); lowPassMidStage2.reset()
            highPassMidStage1.reset(); highPassMidStage2.reset()
            subBassOutput = 0.0f; midBassOutput = 0.0f; presenceOutput = 0.0f
        }

        fun calculateCoefficients(sampleRate: Float, lowF: Float, highF: Float) {
            val qButterworth = 0.70710678f

            lowPassSubStage1.configureLowPass(sampleRate, lowF, qButterworth)
            lowPassSubStage2.configureLowPass(sampleRate, lowF, qButterworth)

            highPassPresStage1.configureHighPass(sampleRate, highF, qButterworth)
            highPassPresStage2.configureHighPass(sampleRate, highF, qButterworth)

            lowPassMidStage1.configureLowPass(sampleRate, highF, qButterworth)
            lowPassMidStage2.configureLowPass(sampleRate, highF, qButterworth)
            highPassMidStage1.configureHighPass(sampleRate, lowF, qButterworth)
            highPassMidStage2.configureHighPass(sampleRate, lowF, qButterworth)
        }

        fun processSample(input: Float) {
            val subStage1 = lowPassSubStage1.process(input)
            subBassOutput = lowPassSubStage2.process(subStage1)

            val presStage1 = highPassPresStage1.process(input)
            presenceOutput = highPassPresStage2.process(presStage1)

            val midLpStage1 = lowPassMidStage1.process(input)
            val midLpStage2 = lowPassMidStage2.process(midLpStage1)
            val midHpStage1 = highPassMidStage1.process(midLpStage2)
            midBassOutput = highPassMidStage2.process(midHpStage1)
        }
    }

    /**
     * Pitch Tracker Engine utilizing Time-Domain Autocorrelation.
     * Establishes parabolic interpolation over adjacent lags to extract 
     * sub-sample accurate fundamental frequencies.
     */
    private class AutocorrelationPitchEstimator {
        private var sampleRate = 48000
        private val f0UpperBoundaryHz = 300.0f
        private val f0LowerBoundaryHz = 35.0f

        private var minimumSampleLag = 0
        private var maximumSampleLag = 0

        // 🟢 修复：构建独立的环形历史状态滑窗，彻底攻克 35Hz 超低音频的采样跨度死穴
        private var historyBuffer = FloatArray(2048)

        fun initialize(rate: Int) {
            this.sampleRate = rate
            minimumSampleLag = (sampleRate / f0UpperBoundaryHz).toInt()
            maximumSampleLag = (sampleRate / f0LowerBoundaryHz).toInt()
            
            // 弹性扩展缓冲区，确保低采样率到高采样率自适应缩放
            if (maximumSampleLag + FRAME_BLOCK_SIZE > historyBuffer.size) {
                historyBuffer = FloatArray(maximumSampleLag + FRAME_BLOCK_SIZE + 256)
            } else {
                historyBuffer.fill(0.0f)
            }
        }

        fun analyzeSignal(signal: FloatArray): Float {
            val signalLength = signal.size
            
            // 将旧采样左移，新数据块无缝拼接到尾部
            System.arraycopy(historyBuffer, signalLength, historyBuffer, 0, historyBuffer.size - signalLength)
            System.arraycopy(signal, 0, historyBuffer, historyBuffer.size - signalLength, signalLength)

            var primeSampleLag = -1
            var peakAutocorrelationValue = -1e9f
            val correlationArray = FloatArray(maximumSampleLag + 1)

            // 固定锚定尾部的当前帧
            val startIndex = historyBuffer.size - signalLength

            // Autocorrelation computation step
            for (lag in minimumSampleLag..maximumSampleLag) {
                var energySum = 0.0f
                // 🟢 修复：利用历史序列进行长跨度相互自相关叠加，彻底找回消失的超低音
                for (i in 0 until signalLength) {
                    energySum += historyBuffer[startIndex + i] * historyBuffer[startIndex + i - lag]
                }
                correlationArray[lag] = energySum
            }

            // Local maxima determination phase
            for (lag in minimumSampleLag..maximumSampleLag) {
                if (lag >= correlationArray.size - 1 || lag < 1) continue
                if (correlationArray[lag] > correlationArray[lag - 1] && correlationArray[lag] > correlationArray[lag + 1]) {
                    if (correlationArray[lag] > peakAutocorrelationValue) {
                        peakAutocorrelationValue = correlationArray[lag]
                        primeSampleLag = lag
                    }
                }
            }

            if (primeSampleLag == -1 || peakAutocorrelationValue <= 0.001f) {
                return 150.0f // Fallback to LRA reference resonant frequency
            }

            // Parabolic interpolation for sub-sample lag resolution
            val leftNeighbor = correlationArray[primeSampleLag - 1]
            val centerPeak = correlationArray[primeSampleLag]
            val rightNeighbor = correlationArray[primeSampleLag + 1]

            val denominator = leftNeighbor - 2.0f * centerPeak + rightNeighbor
            if (abs(denominator) < 1e-5f) {
                return sampleRate.toFloat() / primeSampleLag.toFloat()
            }

            val interpolatedLag = primeSampleLag.toFloat() - 0.5f * (rightNeighbor - leftNeighbor) / denominator
            return sampleRate.toFloat() / interpolatedLag
        }
    }

    /**
     * Dual-Knee Dynamic Range Compressor.
     * Prevents physical over-travel (bottoming out) of LRA components 
     * while retaining extreme resolution in highly dynamic audio passages.
     */
    private class DualKneeCompressor {
        private var thresholdDb = -18.0f
        private var ratio = 4.5f
        private var kneeWidthDb = 6.0f
        private var attackMs = 4.0f
        private var releaseMs = 75.0f
        private var makeupGainDb = 4.0f
        private var blockRateHz = 93.75f // 🟢 优化：默认对齐 48k/512 转换比率

        private var envelopeStateDb = -96.0f

        fun reset() {
            envelopeStateDb = -96.0f
        }

        fun setParameters(
            thresholdDb: Float, 
            ratio: Float, 
            kneeWidthDb: Float, 
            attackMs: Float, 
            releaseMs: Float, 
            makeupGainDb: Float,
            blockRate: Float // 🟢 优化：接收动态动态块速率
        ) {
            this.thresholdDb = thresholdDb
            this.ratio = ratio
            this.kneeWidthDb = kneeWidthDb
            this.attackMs = attackMs
            this.releaseMs = releaseMs
            this.makeupGainDb = makeupGainDb
            this.blockRateHz = blockRate
        }

        fun applyCompression(inputAmplitude: Float): Float {
            if (inputAmplitude < 1e-5f) return 0.0f

            val inputDb = 20.0f * log10(inputAmplitude)
            
            // Envelope generation using single-pole smoothing filter
            // 🟢 修复：将固定的 93.75f 升级为与硬件层时刻同步的 blockRateHz
            val attackCoefficient = exp(-1.0f / (0.001f * attackMs * blockRateHz))
            val releaseCoefficient = exp(-1.0f / (0.001f * releaseMs * blockRateHz))

            if (inputDb > envelopeStateDb) {
                envelopeStateDb = envelopeStateDb * attackCoefficient + inputDb * (1.0f - attackCoefficient)
            } else {
                envelopeStateDb = envelopeStateDb * releaseCoefficient + inputDb * (1.0f - releaseCoefficient)
            }

            // Continuous piecewise function calculation
            var gainReductionDb = 0.0f
            val lowerKneeLimit = thresholdDb - kneeWidthDb / 2.0f
            val upperKneeLimit = thresholdDb + kneeWidthDb / 2.0f

            when {
                envelopeStateDb < lowerKneeLimit -> {
                    gainReductionDb = 0.0f
                }
                envelopeStateDb <= upperKneeLimit -> {
                    val difference = envelopeStateDb - lowerKneeLimit
                    gainReductionDb = (1.0f - 1.0f / ratio) * (difference * difference) / (2.0f * kneeWidthDb)
                }
                else -> {
                    gainReductionDb = (1.0f - 1.0f / ratio) * (envelopeStateDb - thresholdDb)
                }
            }

            val totalGainDb = -gainReductionDb + makeupGainDb
            val staticScalingFactor = 10.0f.pow(totalGainDb / 20.0f)

            val output = inputAmplitude * staticScalingFactor
            return if (output.isNaN() || output.isInfinite()) 0.0f else output.coerceIn(0.0f, 2.0f)
        }
    }

    /**
     * State-Space Dual-Node Actuator Electro-Thermal Simulator.
     * Solves a system of dynamic first-order thermal differential equations 
     * modeling the LRA Voice-Coil-to-Magnet and Magnet-to-Ambient thermal transitions.
     */
    private class DualNodeThermalSimulator {
        // Voice-Coil thermal parameters
        private val cCoil = 0.045f // Thermal capacitance of the coil (J/Celsius)
        private val cMagnet = 0.320f // Thermal capacitance of the magnet (J/Celsius)
        private val rCoilToMagnet = 35.0f // Thermal resistance from coil to magnet (Celsius/W)
        private val rMagnetToAmbient = 12.0f // Thermal resistance from magnet to air (Celsius/W)
        private var iterationStepTimeSec = 0.0106f // Approximate time step of 512 samples at 48kHz

        private var tempCoil = AMBIENT_TEMPERATURE_CELSIUS
        private var tempMagnet = AMBIENT_TEMPERATURE_CELSIUS

        // 🟢 修复：提供热力学时钟常数动态注入接口
        fun reset(newStepTimeSec: Float = 0.0106f) {
            tempCoil = AMBIENT_TEMPERATURE_CELSIUS
            tempMagnet = AMBIENT_TEMPERATURE_CELSIUS
            this.iterationStepTimeSec = newStepTimeSec
        }

        fun updateThermalDynamics(electricalPowerInput: Float): Float {
            val nominalRe = 10.0f // Equivalent LRA series DC electrical resistance
            val realElectricalPowerWatts = (electricalPowerInput * electricalPowerInput) / nominalRe

            // Finite difference approximations of the system states
            val rateCoilToMagnetFlow = (tempCoil - tempMagnet) / rCoilToMagnet
            val rateMagnetToAmbientFlow = (tempMagnet - AMBIENT_TEMPERATURE_CELSIUS) / rMagnetToAmbient

            val deltaTempCoil = (realElectricalPowerWatts - rateCoilToMagnetFlow) / cCoil
            val deltaTempMagnet = (rateCoilToMagnetFlow - rateMagnetToAmbientFlow) / cMagnet

            tempCoil += deltaTempCoil * iterationStepTimeSec
            tempMagnet += deltaTempMagnet * iterationStepTimeSec

            // Boundary enforcement
            if (tempCoil < AMBIENT_TEMPERATURE_CELSIUS) tempCoil = AMBIENT_TEMPERATURE_CELSIUS
            if (tempMagnet < AMBIENT_TEMPERATURE_CELSIUS) tempMagnet = AMBIENT_TEMPERATURE_CELSIUS

            return if (tempCoil.isNaN() || tempCoil.isInfinite()) AMBIENT_TEMPERATURE_CELSIUS else tempCoil
        }
    }

    /**
     * High-Speed Concurrent Audio Ring Buffer.
     */
    private class ConcurrentAudioRingBuffer(private val capacity: Int) {
        private val internalData = FloatArray(capacity)
        private var readOffset = 0
        private var writeOffset = 0
        private var activeElementCount = 0

        @Synchronized
        fun clear() {
            readOffset = 0
            writeOffset = 0
            activeElementCount = 0
            internalData.fill(0.0f)
        }

        @Synchronized
        fun write(input: FloatArray, length: Int) {
            val freeBufferSlots = capacity - activeElementCount
            if (length > freeBufferSlots) {
                val displacedAmount = length - freeBufferSlots
                readOffset = (readOffset + displacedAmount) % capacity
                activeElementCount -= displacedAmount
            }

            var elementsWritten = 0
            while (elementsWritten < length) {
                val writeSegmentSize = min(length - elementsWritten, capacity - writeOffset)
                System.arraycopy(input, elementsWritten, internalData, writeOffset, writeSegmentSize)
                writeOffset = (writeOffset + writeSegmentSize) % capacity
                elementsWritten += writeSegmentSize
            }
            activeElementCount += length
        }

        @Synchronized
        fun read(output: FloatArray, length: Int): Boolean {
            if (activeElementCount < length) return false

            var elementsRead = 0
            while (elementsRead < length) {
                val readSegmentSize = min(length - elementsRead, capacity - readOffset)
                System.arraycopy(internalData, readOffset, output, elementsRead, readSegmentSize)
                readOffset = (readOffset + readSegmentSize) % capacity
                elementsRead += readSegmentSize
            }
            activeElementCount -= length
            return true
        }
    }

    // =========================================================================
    // HIGH-PRECISION PHYSICAL SCHEDULER & HARMONIC MODULATOR
    // =========================================================================

    private enum class HapticClassification {
        SUB_BASS_IMPACT, MID_BASS_TRANSIENT, MICRO_TEXTURAL_WAVE
    }

    private data class HapticActuatorEvent(
        val classification: HapticClassification,
        val intensity: Float,
        val frequencyScalingFactor: Float,
        val timestamp: Long
    ) : Comparable<HapticActuatorEvent> {
        override fun compareTo(other: HapticActuatorEvent): Int {
            return this.timestamp.compareTo(other.timestamp)
        }
    }

    /**
     * Precision Haptic Actuator Scheduler.
     * Operates as an independent high-priority hardware controller thread 
     * synchronized with critical audio clocks.
     */
    private class PrecisionHapticScheduler(context: Context) {
        private var hardwareVibrator: Vibrator? = null
        private val executionQueue = PriorityBlockingQueue<HapticActuatorEvent>()
        private val isThreadActive = AtomicBoolean(true)
        private val schedulingThread: Thread

        @Volatile
        var lastDispatchTimeMs = 0L

        init {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    hardwareVibrator = manager?.defaultVibrator
                }
                if (hardwareVibrator == null) {
                    @Suppress("DEPRECATION")
                    hardwareVibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
            } catch (e: Exception) {
                Log.e(TAG, "Hardware Vibrator binding exception: ${e.message}")
            }

            schedulingThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                while (isThreadActive.get()) {
                    try {
                        val event = executionQueue.poll(6, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (event != null) {
                            transmitToHardware(event)
                        }
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Hardware dispatch thread exception: ${e.message}")
                    }
                }
            }.apply {
                name = "ActuatorScheduler"
                start()
            }
        }

        fun dispatch(event: HapticActuatorEvent) {
            if (executionQueue.size > 3) {
                executionQueue.clear()
            }
            executionQueue.offer(event)
        }

        fun flush() {
            executionQueue.clear()
            try {
                hardwareVibrator?.cancel()
            } catch (e: Exception) {}
        }

        fun terminate() {
            isThreadActive.set(false)
            schedulingThread.interrupt()
            flush()
        }

        private fun transmitToHardware(event: HapticActuatorEvent) {
            val vibrator = hardwareVibrator ?: return
            if (!vibrator.hasVibrator()) return

            val targetIntensity = event.intensity.coerceIn(0.01f, 1.0f)
            val tuningRatio = event.frequencyScalingFactor.coerceIn(0.5f, 2.0f)
            lastDispatchTimeMs = SystemClock.elapsedRealtime()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                when (event.classification) {
                    HapticClassification.SUB_BASS_IMPACT -> {
                        if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
                            var pattern = VibrationEffect.startComposition()
                            pattern.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, targetIntensity)
                            
                            // Secondary pulse synthesis for low-frequency phase alignment
                            if (tuningRatio < 1.0f) {
                                val simulatedPeriodOffset = (24.0f / tuningRatio).toLong().coerceIn(12, 40)
                                pattern.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, targetIntensity * 0.45f, simulatedPeriodOffset.toInt())
                            }
                            vibrator.vibrate(pattern.compose())
                        } else {
                            executeOneShotFallback(35, (targetIntensity * 255).toInt())
                        }
                    }
                    HapticClassification.MID_BASS_TRANSIENT -> {
                        if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE) &&
                            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                            
                            val pattern = VibrationEffect.startComposition()
                                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, targetIntensity)
                                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, targetIntensity * 0.5f)
                            vibrator.vibrate(pattern.compose())
                        } else {
                            executeOneShotFallback(18, (targetIntensity * 215).toInt())
                        }
                    }
                    HapticClassification.MICRO_TEXTURAL_WAVE -> {
                        if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                            val pattern = VibrationEffect.startComposition()
                                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, targetIntensity * 0.65f)
                            vibrator.vibrate(pattern.compose())
                        } else {
                            executeOneShotFallback(10, (targetIntensity * 160).toInt())
                        }
                    }
                }
            } else {
                val genericDuration = when (event.classification) {
                    HapticClassification.SUB_BASS_IMPACT -> 32L
                    HapticClassification.MID_BASS_TRANSIENT -> 16L
                    HapticClassification.MICRO_TEXTURAL_WAVE -> 8L
                }
                executeOneShotFallback(genericDuration, (targetIntensity * 255).toInt())
            }
        }

        private fun executeOneShotFallback(durationMs: Long, rawAmplitude: Int) {
            val vibrator = hardwareVibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val safeAmplitude = rawAmplitude.coerceIn(1, 255)
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, safeAmplitude))
                } catch (e: Exception) {
                    try {
                        @Suppress("DEPRECATION") vibrator.vibrate(durationMs)
                    } catch (ex: Exception) {}
                }
            } else {
                try {
                    @Suppress("DEPRECATION") vibrator.vibrate(durationMs)
                } catch (e: Exception) {}
            }
        }
    }

    /**
     * System Diagnostics and Real-time DSP Telemetry Monitor.
     */
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
    }
}

