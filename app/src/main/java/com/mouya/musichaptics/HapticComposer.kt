package com.mouya.musichaptics

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlin.math.*

import com.mouya.musichaptics.LinkHealthMonitor

class HapticComposer(
    private val context: android.content.Context,
    private val profile: DeviceProfile,
    private val prefs: android.content.SharedPreferences
) {
    companion object {
        private const val TAG = "HapticComposer"

        const val FRAME_BLOCK_SIZE = 256

        const val SUB_BASS_LOW = 20f
        const val SUB_BASS_HIGH = 80f
        const val MID_BASS_LOW = 80f
        const val MID_BASS_HIGH = 200f
        const val TEXTURE_LOW = 200f
        const val TEXTURE_HIGH = 800f

        private const val TRANSIENT_ENERGY_THRESHOLD = 0.12f
        private const val TRANSIENT_SPECTRAL_FLUX_THRESHOLD = 0.08f
        private const val TRANSIENT_DECAY = 0.82f
        private const val TRANSIENT_HOLD_MS = 15L

        private const val BEAT_HISTORY_SIZE = 120
        private const val BEAT_ENERGY_RATIO = 1.35f
        private const val BEAT_MIN_INTERVAL_MS = 180L
        private const val BEAT_MAX_INTERVAL_MS = 2500L
        private const val BEAT_PHASE_LOCK_ALPHA = 0.15f

        private const val KEY_STRIKE_ENERGY_ACCUM_WINDOW = 3
        private const val KEY_STRIKE_MIN_ENERGY = 0.18f
        private const val KEY_STRIKE_PITCH_GUARD_RATIO = 0.6f
        private const val KEY_STRIKE_COOLDOWN_MS = 120L
        private const val SUB_BASS_EMPHASIS_BOOST = 1.4f
        private const val ACCENT_VELOCITY_THRESHOLD = 1.3f

        private const val SEMANTIC_SUB_BASS_MAX = 100f
        private const val SEMANTIC_LOW_MID_MAX = 180f
        private const val SEMANTIC_MID_MAX = 300f
        private const val SEMANTIC_HIGH_MID_MAX = 500f
        private const val SEMANTIC_TEXTURE_MIN = 500f

        const val LRA_F0 = 190f
        const val LRA_Q = 15f
        const val LRA_W0 = 2f * Math.PI.toFloat() * LRA_F0
        const val LRA_ZETA = 1f / (2f * LRA_Q)
        const val LRA_MAX_VOLTAGE = 3.6f
        const val LRA_START_STOP_MS = 4.5f

        const val ADSR_ATTACK_TAU = 0.0015f
        const val ADSR_DECAY_TAU = 0.012f
        const val ADSR_SUSTAIN_LEVEL = 0.35f
        const val ADSR_RELEASE_TAU = 0.035f

        const val THERMAL_RTH = 25f
        const val THERMAL_CTH = 1.2f
        const val THERMAL_AMBIENT = 25f
        const val THERMAL_WARN = 70f
        const val THERMAL_CRIT = 90f

        const val FM_MOD_INDEX = 0.15f
        const val FM_MOD_RATE = 0.5f
    }

    // v1.9: LRA physical parameters from ActuatorProfile (per-device)
    private val lraF0: Float = profile.actuator.resonanceFreq
    private val lraDamping: Float = profile.actuator.dampingRatio
    private val lraW0: Float = profile.actuator.angularFreq
    private val lraMaxVoltage: Float = 3.6f  // Max drive voltage (hardware constant)
    private val lraResponseTimeMs: Float = profile.actuator.responseTimeMs

    private var prevFrameEnergy = 0f
    private var prevFrameSpectrum = FloatArray(128)
    private val reusableFrameSpectrum = FloatArray(128)
    private var transientAccumulator = 0f
    private var transientHoldTimer = 0L

    private val beatEnergyHistory = FloatArray(BEAT_HISTORY_SIZE)
    private var beatHistoryIndex = 0
    private var beatHistoryCount = 0
    private var lastBeatTimeMs = 0L
    private var predictedBeatIntervalMs = 500L
    private var beatPhase = 0.0f
    private var beatPhaseVelocity = 0.0f

    private val keyStrikeEnergyBuffer = FloatArray(KEY_STRIKE_ENERGY_ACCUM_WINDOW)
    private var keyStrikeBufferIndex = 0
    private var lastKeyStrikeTimeMs = 0L
    private var lastKeyStrikePitch = 0f
    private var consecutiveSubBassFrames = 0
    private var subBassEnergyAccumulator = 0f

    @Volatile var lastDisplacement: Float = 0f
    @Volatile var lastVelocity: Float = 0f
    @Volatile var lastForce: Float = 0f
    @Volatile var lastPhase: Float = 0f
    @Volatile var lastEnvelope: Float = 0f
    @Volatile var lastTemperature: Float = 25f
    @Volatile var lastThermalGain: Float = 1f
    @Volatile var lastKeyStrikeActive: Boolean = false
    @Volatile var lastKeyStrikeSemantic: String = "NONE"
    @Volatile var lastSemanticType: String = "BALANCED"
    @Volatile var lastDrive: Float = 0f

    private val hapticCommandChannel = Channel<HapticCommand>(128)
    val hapticCommands: ReceiveChannel<HapticCommand> = hapticCommandChannel

    private var bassGain = 1.2f
    private var midGain = 1.0f
    private var textureGain = 0.3f
    private var transientGain = 0.65f
    private var beatGain = 1.6f
    private var keyStrikeGain = 2.0f
    private var subBassSustainGain = 0.45f

    @Volatile var currentPersona: MusicPersona = MusicPersona.DEFAULT
        private set

    @Volatile var gammaOverride: Float = -1f

    private val lraStartLatencyMs: Float = profile.startLatencyMs
    private val lraStopLatencyMs: Float = profile.stopLatencyMs

    @Volatile var lastPrimitive: HapticPrimitive? = null
        private set
    @Volatile var lastSemanticEvent: SemanticEvent? = null
        private set

    private var textureActiveUntilMs: Long = 0L
    private var textureLastIntensity: Int = 0

    private val waveBuffer = FloatArray(64)
    private var waveBufferIndex = 0
    private var waveBufferCount = 0

    // v1.9: LRA physical state — declared before init block
    private var lraPhase = 0f
    private var lraPhaseVelocity = 0f
    private var lraDisplacement = 0f
    private var lraVelocity = 0f
    private var lraLastTimeMs = 0L

    init {
        loadPreferences()
        lraPhaseVelocity = lraW0  // Initialize phase velocity from actuator resonance
        Log.i(TAG, "HapticComposer v3.7 initialized | Persona=${currentPersona.name} | KeyStrike=ON | ADSR=ON(asymmetricRise/Fall) | Thermal=ON | LRA(f0=${lraF0}Hz ζ=${lraDamping} rise=${profile.actuator.riseTimeMs}ms fall=${profile.actuator.fallTimeMs}ms lat=${lraStartLatencyMs}ms)")
    }

    private fun loadPreferences() {
        bassGain = prefs.getFloat("haptic_bass_gain", 1.2f)
        midGain = prefs.getFloat("haptic_mid_gain", 1.0f)
        textureGain = prefs.getFloat("haptic_texture_gain", 0.3f)
        transientGain = prefs.getFloat("haptic_transient_gain", 0.65f)
        beatGain = prefs.getFloat("haptic_beat_gain", 1.6f)
        keyStrikeGain = prefs.getFloat("haptic_keystrike_gain", 2.0f)
        subBassSustainGain = prefs.getFloat("haptic_sub_sustain_gain", 0.45f)

        val personaName = prefs.getString("music_persona", MusicPersona.DEFAULT.name) ?: MusicPersona.DEFAULT.name
        currentPersona = MusicPersona.byName(personaName) ?: MusicPersona.DEFAULT

        val gammaVal = prefs.getFloat("haptic_gamma_override", -1f)
        gammaOverride = gammaVal

        applyPersonaToRuntimeParams()
    }

    private fun applyPersonaToRuntimeParams() {
        val p = currentPersona

        bassGain = p.bassGain * prefs.getFloat("haptic_bass_gain", 1.0f)
        midGain = p.vocalGain * prefs.getFloat("haptic_mid_gain", 1.0f)
        textureGain = p.textureGain * prefs.getFloat("haptic_texture_gain", 0.5f)
    }

    fun setPersona(persona: MusicPersona) {
        currentPersona = persona
        prefs.edit().putString("music_persona", persona.name).apply()
        applyPersonaToRuntimeParams()
        Log.i(TAG, "Persona switched → ${persona.displayName} | bass=${persona.bassGain} gamma=${persona.gamma}")
    }

    fun updateGammaOverride(gamma: Float) {
        gammaOverride = gamma
        prefs.edit().putFloat("haptic_gamma_override", gamma).apply()
        Log.i(TAG, "Gamma override → $gamma (persona default=${currentPersona.gamma})")
    }

    fun getEffectiveGamma(): Float = if (gammaOverride > 0f) gammaOverride else currentPersona.gamma

    fun updatePreferences() {
        loadPreferences()
    }

    fun processFrame(
        subBass: Float,
        midBass: Float,
        texture: Float,
        pitch: Float,
        timestamp: Long
    ) {

        computeFrameSpectrum(subBass, midBass, texture, pitch)
        val spectralFlux = computeSpectralFlux(reusableFrameSpectrum)

        prevFrameSpectrum = reusableFrameSpectrum.copyOf()

        val currentEnergy = subBass + midBass + texture
        val deltaEnergy = (currentEnergy - prevFrameEnergy).coerceAtLeast(0f)
        prevFrameEnergy = currentEnergy

        val isTransient = (deltaEnergy > TRANSIENT_ENERGY_THRESHOLD) ||
                          (spectralFlux > TRANSIENT_SPECTRAL_FLUX_THRESHOLD)
        if (isTransient) {
            transientAccumulator = 1f
            transientHoldTimer = timestamp
        } else if (timestamp - transientHoldTimer > TRANSIENT_HOLD_MS) {
            transientAccumulator *= TRANSIENT_DECAY
        }

        val (isBeat, beatPhaseNow) = detectBeatWithPLL(currentEnergy, timestamp)

        val (isKeyStrike, keyStrikeSemantic) = detectKeyStrike(
            subBass, midBass, texture, pitch, currentEnergy, timestamp
        )

        val (semanticWeights, semanticType) = mapHapticSemantics(pitch, subBass, midBass, texture)

        val (adsrEnvelope, lraDriveSignal, thermalGain) = computePhysicsPipeline(
            subBass, midBass, texture,
            isTransient, isBeat, isKeyStrike, keyStrikeSemantic,
            timestamp
        )

        val bassContinuous = subBass * bassGain * semanticWeights.first * subBassSustainGain
        val lowMidImpact = midBass * midGain * semanticWeights.second
        val midHighTexture = texture * textureGain * semanticWeights.third
        val highTextureDetail = texture * textureGain * semanticWeights.fourth

        val continuousComponent = (bassContinuous + lowMidImpact) * 0.35f
        val transientComponent = (transientAccumulator + midHighTexture) * transientGain * adsrEnvelope
        val beatComponent = if (isBeat) beatGain * adsrEnvelope else 0f
        val keyStrikeComponent = if (isKeyStrike) keyStrikeGain * keyStrikeSemantic.intensity * adsrEnvelope else 0f

        var finalIntensity = (continuousComponent + transientComponent + beatComponent + keyStrikeComponent) * thermalGain

        finalIntensity = applyLraDriveStrategy(finalIntensity, isTransient, isBeat, isKeyStrike, timestamp)

        lastKeyStrikeActive = isKeyStrike
        lastKeyStrikeSemantic = if (isKeyStrike) keyStrikeSemantic.name else "NONE"
        lastSemanticType = semanticType.name

        val semanticEvent = detectSemanticEvent(
            subBass, midBass, texture, pitch,
            isTransient, isBeat, isKeyStrike, keyStrikeSemantic,
            currentEnergy, finalIntensity, timestamp
        )

        val primitive = if (semanticEvent != null) {
            mapEventToPrimitive(semanticEvent, currentPersona, finalIntensity, adsrEnvelope, timestamp)
        } else null

        lastSemanticEvent = semanticEvent
        lastPrimitive = primitive

        val gamma = getEffectiveGamma()
        val gammaIntensity = applyGammaCurve(finalIntensity, gamma)

        val command = HapticCommand(
            intensity = gammaIntensity.coerceIn(0f, 2.5f),
            isTransient = isTransient,
            isBeat = isBeat,
            isKeyStrike = isKeyStrike,
            keyStrikeSemantic = keyStrikeSemantic,
            pitch = pitch,
            timestamp = timestamp,
            bassComponent = bassContinuous,
            midComponent = lowMidImpact,
            textureComponent = midHighTexture + highTextureDetail,
            transientComponent = transientAccumulator,
            adsrEnvelope = adsrEnvelope,
            thermalGain = thermalGain,
            semanticType = semanticType,
            semanticEvent = semanticEvent,
            primitive = primitive
        )

        hapticCommandChannel.trySend(command)

        LinkHealthMonitor.heartbeatComposer()
    }

    private fun computeFrameSpectrum(subBass: Float, midBass: Float, texture: Float, pitch: Float) {

        for (i in 0..15) reusableFrameSpectrum[i] = subBass * (1f - i / 16f)

        for (i in 16..47) reusableFrameSpectrum[i] = midBass * (1f - (i - 16) / 32f)

        for (i in 48..95) reusableFrameSpectrum[i] = texture * (1f - (i - 48) / 48f)

        if (pitch > 0f) {
            val bin = (pitch / 48000f * 128).toInt().coerceIn(0, 127)
            reusableFrameSpectrum[bin] = maxOf(reusableFrameSpectrum[bin], 0.5f)
        }
    }

    private fun computeSpectralFlux(current: FloatArray): Float {
        var flux = 0f
        for (i in current.indices) {
            val diff = current[i] - prevFrameSpectrum[i]
            flux += diff * diff
        }
        return sqrt(flux / current.size)
    }

    private fun detectBeatWithPLL(currentEnergy: Float, timestamp: Long): Pair<Boolean, Float> {

        beatEnergyHistory[beatHistoryIndex] = currentEnergy
        beatHistoryIndex = (beatHistoryIndex + 1) % BEAT_HISTORY_SIZE
        beatHistoryCount = min(beatHistoryCount + 1, BEAT_HISTORY_SIZE)

        var sum = 0f
        for (i in 0 until beatHistoryCount) sum += beatEnergyHistory[i]
        val avgEnergy = sum / beatHistoryCount

        val threshold = avgEnergy * BEAT_ENERGY_RATIO

        val timeSinceLastBeat = timestamp - lastBeatTimeMs
        val isEnergyPeak = currentEnergy > threshold
        val isIntervalValid = timeSinceLastBeat >= BEAT_MIN_INTERVAL_MS

        val expectedInterval = predictedBeatIntervalMs.toFloat()
        beatPhase += (timeSinceLastBeat / expectedInterval) % 1f
        beatPhase = beatPhase % 1f

        val predictionWindow = expectedInterval * 0.12f
        val isNearPredictedBeat = beatPhase > (1f - predictionWindow / expectedInterval) || beatPhase < (predictionWindow / expectedInterval)
        val adaptiveThreshold = if (isNearPredictedBeat) threshold * 0.65f else threshold

        val beatDetected = isEnergyPeak && isIntervalValid && currentEnergy > adaptiveThreshold

        if (beatDetected) {

            if (lastBeatTimeMs > 0) {
                val interval = (timestamp - lastBeatTimeMs).toFloat()
                predictedBeatIntervalMs = (predictedBeatIntervalMs * 0.7f + interval * 0.3f).toLong()
                    .coerceIn(BEAT_MIN_INTERVAL_MS, BEAT_MAX_INTERVAL_MS)
            }

            val phaseError = beatPhase - 0f
            beatPhaseVelocity += phaseError * BEAT_PHASE_LOCK_ALPHA
            beatPhase = 0f
            lastBeatTimeMs = timestamp
            Log.d(TAG, "BEAT | energy=$currentEnergy avg=$avgEnergy IBI=${predictedBeatIntervalMs}ms phaseVel=$beatPhaseVelocity")
        } else {

            val dtFrames = 1f / (48000f / FRAME_BLOCK_SIZE) * 1000f
            beatPhase += dtFrames / expectedInterval
            beatPhase = beatPhase % 1f
        }

        return Pair(beatDetected, beatPhase)
    }

    private fun detectKeyStrike(
        subBass: Float, midBass: Float, texture: Float,
        pitch: Float, currentEnergy: Float, timestamp: Long
    ): Pair<Boolean, KeyStrikeSemantic> {

        keyStrikeEnergyBuffer[keyStrikeBufferIndex] = subBass
        keyStrikeBufferIndex = (keyStrikeBufferIndex + 1) % KEY_STRIKE_ENERGY_ACCUM_WINDOW

        var accumEnergy = 0f
        for (e in keyStrikeEnergyBuffer) accumEnergy += e
        accumEnergy /= KEY_STRIKE_ENERGY_ACCUM_WINDOW

        if (subBass > 0.15f) consecutiveSubBassFrames++ else consecutiveSubBassFrames = 0
        subBassEnergyAccumulator = subBassEnergyAccumulator * 0.9f + subBass * 0.1f

        val energyCondition = accumEnergy > KEY_STRIKE_MIN_ENERGY
        val cooldownPassed = timestamp - lastKeyStrikeTimeMs >= KEY_STRIKE_COOLDOWN_MS

        if (!energyCondition || !cooldownPassed) {
            return Pair(false, KeyStrikeSemantic.NONE)
        }

        var pitchGuardPassed = true
        if (lastKeyStrikePitch > 0f && pitch > 0f) {
            val pitchRatio = pitch / lastKeyStrikePitch

            pitchGuardPassed = pitchRatio < KEY_STRIKE_PITCH_GUARD_RATIO || pitchRatio > 1.5f
        }

        if (!pitchGuardPassed) {
            return Pair(false, KeyStrikeSemantic.NONE)
        }

        val semantic = when {

            subBass > 0.25f && pitch > 0f && pitch < SEMANTIC_SUB_BASS_MAX && subBass > midBass * 1.5f -> {
                KeyStrikeSemantic.SUB_STRIKE
            }

            pitch >= SEMANTIC_SUB_BASS_MAX && pitch < SEMANTIC_LOW_MID_MAX && transientAccumulator > 0.4f -> {
                KeyStrikeSemantic.KICK_DRUM
            }

            pitch >= SEMANTIC_LOW_MID_MAX && pitch < SEMANTIC_MID_MAX &&
                (midBass > 0.3f || transientAccumulator > ACCENT_VELOCITY_THRESHOLD) -> {
                KeyStrikeSemantic.SNARE_ACCENT
            }

            consecutiveSubBassFrames >= 2 && subBassEnergyAccumulator > 0.2f && pitch < SEMANTIC_LOW_MID_MAX -> {
                KeyStrikeSemantic.RHYTHM_PATTERN
            }

            subBass > 0.15f && pitch > 0f && pitch < SEMANTIC_SUB_BASS_MAX && transientAccumulator < 0.2f -> {
                KeyStrikeSemantic.BASS_GHOST
            }
            else -> KeyStrikeSemantic.NONE
        }

        val isKeyStrike = semantic != KeyStrikeSemantic.NONE

        if (isKeyStrike) {
            lastKeyStrikeTimeMs = timestamp
            lastKeyStrikePitch = pitch
            Log.d(TAG, "KEY_STRIKE | ${semantic.name} | pitch=${pitch.toInt()}Hz sub=$subBass mid=$midBass transient=$transientAccumulator")
        }

        return Pair(isKeyStrike, semantic)
    }

    private fun mapHapticSemantics(pitch: Float, subBass: Float, midBass: Float, texture: Float): Pair<Quad<Float, Float, Float, Float>, SemanticType> {
        val semanticType = when {
            pitch > 0f && pitch < SEMANTIC_SUB_BASS_MAX && subBass > midBass -> SemanticType.DEEP_BASS
            pitch >= SEMANTIC_SUB_BASS_MAX && pitch < SEMANTIC_LOW_MID_MAX -> SemanticType.KICK_BASS
            pitch >= SEMANTIC_LOW_MID_MAX && pitch < SEMANTIC_MID_MAX -> SemanticType.MID_PUNCH
            pitch >= SEMANTIC_MID_MAX && pitch < SEMANTIC_HIGH_MID_MAX -> SemanticType.HIGH_MID_DETAIL
            pitch >= SEMANTIC_TEXTURE_MIN -> SemanticType.TEXTURE_DETAIL
            else -> SemanticType.BALANCED
        }

        val weights = when (semanticType) {
            SemanticType.DEEP_BASS -> Quad(1.5f, 0.6f, 0.2f, 0.1f)
            SemanticType.KICK_BASS -> Quad(1.2f, 1.0f, 0.4f, 0.2f)
            SemanticType.MID_PUNCH -> Quad(0.7f, 1.2f, 0.8f, 0.3f)
            SemanticType.HIGH_MID_DETAIL -> Quad(0.4f, 0.8f, 1.1f, 0.5f)
            SemanticType.TEXTURE_DETAIL -> Quad(0.2f, 0.4f, 0.6f, 1.0f)
            else -> Quad(1.0f, 1.0f, 0.6f, 0.4f)
        }

        return Pair(weights, semanticType)
    }

    private var adsrEnvelopeValue = 0f
    private var adsrState = 0
    private var adsrTargetDrive = 0f
    private var adsrLastTimeMs = 0L

    private var estimatedCoilTemp = THERMAL_AMBIENT
    private var lraThermalGain = 1f

    private val pendingEvents = mutableListOf<PendingEvent>()
    private data class PendingEvent(
        val amplitude: Float,
        val frequency: Float,
        val phase: Float,
        val envelope: Float,
        val startTimeMs: Long,
        val durationMs: Long,
        val semantic: KeyStrikeSemantic
    )

    private fun computePhysicsPipeline(
        subBass: Float, midBass: Float, texture: Float,
        isTransient: Boolean, isBeat: Boolean, isKeyStrike: Boolean, keyStrikeSemantic: KeyStrikeSemantic,
        timestamp: Long
    ): Triple<Float, Float, Float> {

        val dt = if (lraLastTimeMs > 0) (timestamp - lraLastTimeMs) / 1000f else (FRAME_BLOCK_SIZE.toFloat() / 48000f)
        lraLastTimeMs = timestamp

        val targetDrive = (subBass * 1.0f + midBass * 0.7f + texture * 0.3f).coerceIn(0f, 1.5f)
        adsrTargetDrive = targetDrive

        val triggerAttack = isTransient || isBeat || isKeyStrike
        val triggerRelease = !triggerAttack && targetDrive < 0.03f

        // v2.0: Asymmetric actuator-aware ADSR — rise/fall separated
        // Rise time → attack tau (how fast LRA reaches full displacement)
        // Fall time → decay/release tau (how long the LRA keeps vibrating after drive stops)
        val riseScale = profile.actuator.riseScale
        val fallScale = profile.actuator.fallScale
        val attackTau = ADSR_ATTACK_TAU * riseScale
        val decayTau = ADSR_DECAY_TAU * fallScale
        val releaseTau = ADSR_RELEASE_TAU * fallScale

        when (adsrState) {
            0 -> {
                if (triggerAttack) {
                    adsrState = 1
                    adsrEnvelopeValue = 0f
                }
            }
            1 -> {
                val attackAlpha = 1f - exp(-dt / attackTau)
                adsrEnvelopeValue += (1f - adsrEnvelopeValue) * attackAlpha
                if (adsrEnvelopeValue >= 0.95f) {
                    adsrEnvelopeValue = 1f
                    adsrState = 2
                }
            }
            2 -> {
                val decayAlpha = exp(-dt / decayTau)
                adsrEnvelopeValue = ADSR_SUSTAIN_LEVEL + (adsrEnvelopeValue - ADSR_SUSTAIN_LEVEL) * decayAlpha
                if (abs(adsrEnvelopeValue - ADSR_SUSTAIN_LEVEL) < 0.02f) {
                    adsrEnvelopeValue = ADSR_SUSTAIN_LEVEL
                    adsrState = 3
                }
            }
            3 -> {
                adsrEnvelopeValue = adsrEnvelopeValue * 0.95f + targetDrive * ADSR_SUSTAIN_LEVEL * 0.05f
                if (triggerRelease) {
                    adsrState = 4
                } else if (triggerAttack) {
                    adsrState = 1
                }
            }
            4 -> {
                val releaseAlpha = exp(-dt / releaseTau)
                adsrEnvelopeValue *= releaseAlpha
                if (adsrEnvelopeValue <= 0.001f) {
                    adsrEnvelopeValue = 0f
                    adsrState = 0
                }
            }
        }

        val semanticGain = when (keyStrikeSemantic) {
            KeyStrikeSemantic.SUB_STRIKE -> 1.8f
            KeyStrikeSemantic.KICK_DRUM -> 1.5f
            KeyStrikeSemantic.SNARE_ACCENT -> 1.6f
            KeyStrikeSemantic.RHYTHM_PATTERN -> 1.4f
            KeyStrikeSemantic.BASS_GHOST -> 1.1f
            else -> 1f
        }

        if (triggerAttack && (isKeyStrike || isBeat || isTransient)) {
            val eventFreq = when {
                isKeyStrike && keyStrikeSemantic == KeyStrikeSemantic.SUB_STRIKE -> lraF0 * 0.85f
                isKeyStrike && keyStrikeSemantic == KeyStrikeSemantic.KICK_DRUM -> lraF0 * 1.05f
                isKeyStrike && keyStrikeSemantic == KeyStrikeSemantic.SNARE_ACCENT -> lraF0 * 1.2f
                isBeat -> lraF0
                else -> lraF0
            }

            val eventAmp = (adsrEnvelopeValue * semanticGain).coerceAtMost(1.5f)
            // v2.0: Event duration scaled by actuator response (rise-biased for impact reach)
            val responseScale = profile.actuator.riseScale
            val eventDurMs = when {
                isKeyStrike && keyStrikeSemantic == KeyStrikeSemantic.SUB_STRIKE -> (180L * responseScale).toLong()
                isKeyStrike && keyStrikeSemantic == KeyStrikeSemantic.KICK_DRUM -> (80L * responseScale).toLong()
                isKeyStrike && keyStrikeSemantic == KeyStrikeSemantic.SNARE_ACCENT -> (120L * responseScale).toLong()
                isKeyStrike && keyStrikeSemantic == KeyStrikeSemantic.RHYTHM_PATTERN -> (200L * responseScale).toLong()
                isKeyStrike && keyStrikeSemantic == KeyStrikeSemantic.BASS_GHOST -> (300L * responseScale).toLong()
                isBeat -> (60L * responseScale).toLong()
                isTransient -> (40L * responseScale).toLong()
                else -> (30L * responseScale).toLong()
            }

            val alignedPhase = (lraPhase + (Math.random() * 0.1 - 0.05).toFloat()) % (2f * Math.PI.toFloat())

            pendingEvents.add(PendingEvent(
                amplitude = eventAmp,
                frequency = eventFreq,
                phase = alignedPhase,
                envelope = adsrEnvelopeValue,
                startTimeMs = timestamp,
                durationMs = eventDurMs,
                semantic = keyStrikeSemantic
            ))

            if (pendingEvents.size > 8) pendingEvents.removeAt(0)
        }

        var totalForce = 0f

        val activeEvents = mutableListOf<PendingEvent>()
        for (event in pendingEvents) {
            val ageMs = timestamp - event.startTimeMs
            if (ageMs <= event.durationMs) {

                val eventEnv = when {
                    ageMs < 10 -> ageMs / 10f
                    ageMs < event.durationMs * 0.3f -> 1f
                    else -> 1f - (ageMs - event.durationMs * 0.3f).toFloat() / (event.durationMs * 0.7f).toFloat()
                }.coerceIn(0f, 1f)

                val fmMod = FM_MOD_INDEX * sin(FM_MOD_RATE * event.frequency * (ageMs / 1000f) * 2f * Math.PI.toFloat())
                val instFreq = event.frequency * (1f + fmMod)
                val instPhase = (event.phase + instFreq * 2f * Math.PI.toFloat() * (ageMs / 1000f)) % (2f * Math.PI.toFloat())

                val force = event.amplitude * eventEnv * cos(instPhase) * lraMaxVoltage
                totalForce += force

                activeEvents.add(event)
            }
        }
        pendingEvents.clear()
        pendingEvents.addAll(activeEvents)

        val invMass = 1f
        val dampingTerm = 2f * lraDamping * lraW0 * lraVelocity
        val springTerm = lraW0 * lraW0 * lraDisplacement
        val acceleration = (totalForce * invMass - dampingTerm - springTerm)

        lraVelocity += acceleration * dt
        lraDisplacement += lraVelocity * dt

        lraPhaseVelocity = lraW0 * (1f + FM_MOD_INDEX * sin(FM_MOD_RATE * lraPhase))
        lraPhase = (lraPhase + lraPhaseVelocity * dt) % (2f * Math.PI.toFloat())

        val lraDriveOutput = (lraDisplacement.absoluteValue * 50f).coerceIn(0f, 2f)

        val electricalPower = (lraDriveOutput * lraDriveOutput).coerceAtMost(2f)
        val heatFlow = (estimatedCoilTemp - THERMAL_AMBIENT) / THERMAL_RTH
        val dT = (electricalPower - heatFlow) * dt / THERMAL_CTH
        estimatedCoilTemp += dT
        estimatedCoilTemp = estimatedCoilTemp.coerceAtLeast(THERMAL_AMBIENT)

        lraThermalGain = when {
            estimatedCoilTemp >= THERMAL_CRIT -> 0f
            estimatedCoilTemp >= THERMAL_WARN -> {
                val ratio = (estimatedCoilTemp - THERMAL_WARN) / (THERMAL_CRIT - THERMAL_WARN)
                0.5f * (1f + cos(ratio * Math.PI.toFloat()))
            }
            else -> 1f
        }

        val finalEnvelope = (adsrEnvelopeValue * lraThermalGain).coerceIn(0f, 1f)
        val finalDrive = (lraDriveOutput * lraThermalGain).coerceIn(0f, 2.5f)

        lastDisplacement = lraDisplacement
        lastVelocity = lraVelocity
        lastForce = totalForce
        lastPhase = lraPhase
        lastEnvelope = finalEnvelope
        lastDrive = finalDrive
        lastTemperature = estimatedCoilTemp
        lastThermalGain = lraThermalGain

        return Triple(finalEnvelope, finalDrive, lraThermalGain)
    }

    private fun applyLraDriveStrategy(
        intensity: Float,
        isTransient: Boolean,
        isBeat: Boolean,
        isKeyStrike: Boolean,
        timestamp: Long
    ): Float {
        if (isKeyStrike) return intensity * 2.0f
        if (isBeat || isTransient) return intensity * 1.6f

        if (intensity > 0.02f) {
            // Subtle breathing modulation (±10%) instead of aggressive 50%/15% gating.
            // Sustained bass & melodic content now retains full body.
            val breath = 0.90f + 0.10f * sin(timestamp * 0.0008f)
            return intensity * breath
        }

        return 0f
    }

    fun cancel() {
        hapticCommandChannel.cancel()
    }

    fun release() {
        cancel()
    }

    private fun detectSemanticEvent(
        subBass: Float, midBass: Float, texture: Float, pitch: Float,
        isTransient: Boolean, isBeat: Boolean, isKeyStrike: Boolean,
        keyStrikeSemantic: KeyStrikeSemantic,
        currentEnergy: Float, finalIntensity: Float, timestamp: Long
    ): SemanticEvent? {
        val persona = currentPersona

        if (isKeyStrike && (keyStrikeSemantic == KeyStrikeSemantic.SUB_STRIKE ||
                        keyStrikeSemantic == KeyStrikeSemantic.KICK_DRUM)) {
            val strength = (subBass * persona.bassGain).coerceIn(0f, 1f)
            if (strength > 0.15f) {
                val compensatedTs = timestamp - lraStartLatencyMs.toLong()
                return SemanticEvent(
                    label = keyStrikeSemantic.name,
                    strength = strength,
                    confidence = if (isTransient) 0.92f else 0.78f,
                    timestampMs = compensatedTs,
                    bandType = if (pitch < 80f) SemanticBand.SUB_BASS else SemanticBand.BASS,
                    pitch = pitch
                )
            }
        }

        if (isKeyStrike && keyStrikeSemantic == KeyStrikeSemantic.SNARE_ACCENT) {
            val strength = (midBass * persona.vocalGain).coerceIn(0f, 1f)
            if (strength > 0.12f) {
                val compensatedTs = timestamp - lraStartLatencyMs.toLong()
                return SemanticEvent(
                    label = "SNARE_ACCENT",
                    strength = strength,
                    confidence = if (isTransient) 0.88f else 0.70f,
                    timestampMs = compensatedTs,
                    bandType = SemanticBand.MID,
                    pitch = pitch
                )
            }
        }

        if (isBeat && !isKeyStrike) {

            val rhythmStrength = currentEnergy / persona.beatThreshold.coerceAtLeast(0.5f)
            if (rhythmStrength > 0.20f) {
                val compensatedTs = timestamp - lraStartLatencyMs.toLong()
                return SemanticEvent(
                    label = "RHYTHM_PATTERN",
                    strength = rhythmStrength.coerceIn(0f, 1f),
                    confidence = 0.75f,
                    timestampMs = compensatedTs,
                    bandType = SemanticBand.FULL_BAND,
                    pitch = pitch
                )
            }
        }

        if (isKeyStrike && keyStrikeSemantic == KeyStrikeSemantic.BASS_GHOST) {
            val strength = (subBass * 0.6f * persona.bassGain).coerceIn(0f, 0.5f)
            if (strength > 0.08f) {
                val compensatedTs = timestamp - lraStartLatencyMs.toLong()
                return SemanticEvent(
                    label = "BASS_GHOST",
                    strength = strength,
                    confidence = 0.65f,
                    timestampMs = compensatedTs,
                    bandType = SemanticBand.BASS,
                    pitch = pitch
                )
            }
        }

        val midRatio = if (currentEnergy > 0.01f) midBass / currentEnergy else 0f
        if (!isTransient && !isBeat && !isKeyStrike && midRatio > 0.45f && midBass > 0.05f) {

            val textureDuration = persona.textureDurationMax.coerceAtLeast(persona.textureDurationMin)
            textureActiveUntilMs = timestamp + textureDuration
            textureLastIntensity = (midBass * persona.vocalGain * 180f).toInt().coerceIn(0, 255)

            val compensatedTs = timestamp - lraStartLatencyMs.toLong()
            return SemanticEvent(
                label = "VOCAL_SUSTAIN",
                strength = midBass.coerceIn(0f, 1f),
                confidence = 0.60f,
                timestampMs = compensatedTs,
                bandType = SemanticBand.HIGH_MID,
                pitch = pitch
            )
        }

        if (!isTransient && !isBeat && texture > 0.08f && currentEnergy < 0.3f) {
            val strength = (texture * persona.textureGain).coerceIn(0f, 0.6f)
            if (strength > 0.05f) {
                val compensatedTs = timestamp - lraStartLatencyMs.toLong()
                return SemanticEvent(
                    label = "TEXTURE_DETAIL",
                    strength = strength,
                    confidence = 0.55f,
                    timestampMs = compensatedTs,
                    bandType = SemanticBand.TREBLE,
                    pitch = pitch
                )
            }
        }

        return null
    }

    private fun mapEventToPrimitive(
        event: SemanticEvent, persona: MusicPersona,
        finalIntensity: Float, adsrEnvelope: Float, timestamp: Long
    ): HapticPrimitive? {
        val gamma = getEffectiveGamma()

        val rawIntensity = event.strength.coerceIn(0f, 1f)
        val gammaIntensity = applyGammaCurve(rawIntensity, gamma).coerceIn(0f, 1f)
        val intensity255 = (gammaIntensity * 255f).toInt().coerceIn(0, 255)

        when (event.label) {

            "KICK_DRUM", "SUB_STRIKE" -> {
                if (persona.impactBias < 0.3f) return null
                val adjustedIntensity = (intensity255 * persona.impactBias).toInt().coerceIn(0, 255)
                val duration = lerp(persona.impactDurationMin, persona.impactDurationMax, event.strength)
                val velocity = event.strength.coerceIn(0f, 1f)
                val sharpness = if (event.bandType == SemanticBand.SUB_BASS) 0.3f else 0.5f
                return HapticPrimitive.Impact(
                    intensity = adjustedIntensity,
                    durationMs = duration,
                    velocityFactor = velocity,
                    sharpness = sharpness,
                    semantic = event.label
                )
            }

            "SNARE_ACCENT" -> {
                if (persona.impactBias < 0.3f) return null
                val adjustedIntensity = (intensity255 * persona.impactBias).toInt().coerceIn(0, 255)
                val duration = lerp(persona.impactDurationMin, persona.impactDurationMax, event.strength * 0.8f)
                return HapticPrimitive.Impact(
                    intensity = adjustedIntensity,
                    durationMs = duration,
                    velocityFactor = event.strength.coerceIn(0.3f, 1f),
                    sharpness = 0.9f,
                    semantic = event.label
                )
            }

            "RHYTHM_PATTERN" -> {
                if (persona.pulseBias < 0.3f) return null
                val adjustedIntensity = (intensity255 * persona.pulseBias).toInt().coerceIn(0, 255)
                val period = lerp(persona.pulsePeriodMin, persona.pulsePeriodMax, 1f - event.strength)
                val repeatCount = if (event.strength > 0.6f) 4 else 2
                return HapticPrimitive.Pulse(
                    intensity = adjustedIntensity,
                    periodMs = period,
                    repeatCount = repeatCount,
                    rhythmStrength = (event.strength * persona.pulseBias).coerceIn(0f, 1f),
                    semantic = event.label
                )
            }

            "BASS_GHOST" -> {
                if (persona.impactBias < 0.3f) return null
                val adjustedIntensity = (intensity255 * persona.impactBias * 0.5f).toInt().coerceIn(0, 255)
                return HapticPrimitive.Impact(
                    intensity = adjustedIntensity,
                    durationMs = persona.impactDurationMin,
                    velocityFactor = 0.3f,
                    sharpness = 0.2f,
                    semantic = event.label
                )
            }

            "VOCAL_SUSTAIN" -> {
                if (persona.textureBias < 0.3f) return null
                val adjustedIntensity = (intensity255 * persona.textureBias).toInt().coerceIn(0, 255)
                val duration = lerp(persona.textureDurationMin, persona.textureDurationMax, event.strength)
                return HapticPrimitive.Texture(
                    intensity = adjustedIntensity,
                    durationMs = duration,
                    modulationDepth = 0.8f,
                    frequencyMod = 0.3f,
                    semantic = event.label
                )
            }

            "TEXTURE_DETAIL" -> {
                if (persona.textureBias < 0.3f) return null
                val adjustedIntensity = (intensity255 * persona.textureBias * 0.5f).toInt().coerceIn(0, 255)
                val duration = lerp(persona.textureDurationMin, persona.textureDurationMax, event.strength)
                return HapticPrimitive.Texture(
                    intensity = adjustedIntensity,
                    durationMs = duration,
                    modulationDepth = 0.2f,
                    frequencyMod = 0.5f,
                    semantic = event.label
                )
            }

            else -> return null
        }
    }

    private fun applyGammaCurve(input: Float, gamma: Float): Float {
        if (input <= 0f) return 0f
        return input.pow(gamma)
    }

    private fun lerp(min: Int, max: Int, t: Float): Int {
        return (min + (max - min) * t.coerceIn(0f, 1f)).toInt().coerceIn(min, max)
    }
}

data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

enum class KeyStrikeSemantic {
    NONE,
    SUB_STRIKE,
    KICK_DRUM,
    SNARE_ACCENT,
    RHYTHM_PATTERN,
    BASS_GHOST
;

    val intensity: Float
        get() = when (this) {
            SUB_STRIKE -> 1.0f
            KICK_DRUM -> 0.9f
            SNARE_ACCENT -> 0.85f
            RHYTHM_PATTERN -> 0.8f
            BASS_GHOST -> 0.5f
            NONE -> 0f
        }
}

enum class SemanticType {
    DEEP_BASS,
    KICK_BASS,
    MID_PUNCH,
    HIGH_MID_DETAIL,
    TEXTURE_DETAIL,
    BALANCED
}

data class HapticCommand(
    val intensity: Float,
    val isTransient: Boolean,
    val isBeat: Boolean,
    val isKeyStrike: Boolean,
    val keyStrikeSemantic: KeyStrikeSemantic,
    val pitch: Float,
    val timestamp: Long,
    val bassComponent: Float,
    val midComponent: Float,
    val textureComponent: Float,
    val transientComponent: Float,
    val adsrEnvelope: Float,
    val thermalGain: Float,
    val semanticType: SemanticType,

    val semanticEvent: SemanticEvent? = null,
    val primitive: HapticPrimitive? = null
) {
    fun toLogString(): String {
        val primStr = primitive?.let { " Prim=${it.typeName}" } ?: ""
        val semStr = semanticEvent?.let { " Ev=${it.label}" } ?: ""
        return String.format(
            "Cmd | I=%.2f T=%b B=%b KS=%s(%s) F0=%.0fHz Env=%.2f Th=%.2f Sem=%s%s%s",
            intensity, isTransient, isBeat, keyStrikeSemantic.name, isKeyStrike, pitch,
            adsrEnvelope, thermalGain, semanticType.name, semStr, primStr
        )
    }
}