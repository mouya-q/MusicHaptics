#pragma once

#include <arm_neon.h>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <atomic>

namespace haptic {

// ════════════════════════════════════════════════════════════════
//  DSP Primitives
// ════════════════════════════════════════════════════════════════

struct alignas(64) BiquadCoeffs {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
};

struct alignas(64) BiquadState {
    float x1 = 0.0f, x2 = 0.0f, y1 = 0.0f, y2 = 0.0f;
    void reset() { x1 = x2 = y1 = y2 = 0.0f; }
};

// 4th-order Linkwitz-Riley crossover filter
class LinkwitzRiley4th {
private:
    BiquadCoeffs coeffs1_, coeffs2_;
    BiquadState state1_, state2_;

public:
    void reset() { state1_.reset(); state2_.reset(); }

    void setLowPass(float sampleRate, float cutoff) {
        float omega = 2.0f * M_PI * cutoff / sampleRate;
        float cosW = cosf(omega);
        float alpha = sinf(omega) / (2.0f * 0.70710678f);
        float a0 = 1.0f + alpha;
        coeffs1_.b0 = ((1.0f - cosW) / 2.0f) / a0;
        coeffs1_.b1 = (1.0f - cosW) / a0;
        coeffs1_.b2 = coeffs1_.b0;
        coeffs1_.a1 = (-2.0f * cosW) / a0;
        coeffs1_.a2 = (1.0f - alpha) / a0;
        coeffs2_ = coeffs1_;
    }

    void setHighPass(float sampleRate, float cutoff) {
        float omega = 2.0f * M_PI * cutoff / sampleRate;
        float cosW = cosf(omega);
        float alpha = sinf(omega) / (2.0f * 0.70710678f);
        float a0 = 1.0f + alpha;
        coeffs1_.b0 = ((1.0f + cosW) / 2.0f) / a0;
        coeffs1_.b1 = (-(1.0f + cosW)) / a0;
        coeffs1_.b2 = coeffs1_.b0;
        coeffs1_.a1 = (-2.0f * cosW) / a0;
        coeffs1_.a2 = (1.0f - alpha) / a0;
        coeffs2_ = coeffs1_;
    }

    inline float process(float in) {
        float out1 = coeffs1_.b0 * in + coeffs1_.b1 * state1_.x1 + coeffs1_.b2 * state1_.x2
                     - coeffs1_.a1 * state1_.y1 - coeffs1_.a2 * state1_.y2;
        state1_.x2 = state1_.x1; state1_.x1 = in;
        state1_.y2 = state1_.y1; state1_.y1 = out1;

        float out2 = coeffs2_.b0 * out1 + coeffs2_.b1 * state2_.x1 + coeffs2_.b2 * state2_.x2
                     - coeffs2_.a1 * state2_.y1 - coeffs2_.a2 * state2_.y2;
        state2_.x2 = state2_.x1; state2_.x1 = out1;
        state2_.y2 = state2_.y1; state2_.y1 = out2;

        return std::isnan(out2) ? 0.0f : out2;
    }
};

// ════════════════════════════════════════════════════════════════
//  1D Value Noise (Perlin-like) for Texture Layer
// ════════════════════════════════════════════════════════════════

class ValueNoise1D {
    float position_ = 0.0f;

    static inline float hash01(int x) {
        float s = sinf((x + 127.1f) * 0.1307f) * 43758.5453f;
        return s - floorf(s);
    }

    static inline float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

public:
    void reset() { position_ = 0.0f; }

    // advance: how fast to move through noise space
    // returns value in [0, 1]
    float next(float advance) {
        position_ += advance;
        int i = static_cast<int>(floorf(position_));
        float f = position_ - static_cast<float>(i);
        // Smoothstep interpolation
        float u = f * f * (3.0f - 2.0f * f);
        float a = hash01(i);
        float b = hash01(i + 1);
        return lerp(a, b, u);
    }
};

// ════════════════════════════════════════════════════════════════
//  Continuous Haptic Synthesis Engine
//  Five-layer model: Beat + Bass + Texture + Melody + Emotion
//  Output: continuous amplitude stream (0-255) for VibrationEffect
// ════════════════════════════════════════════════════════════════

class HapticEngine {
private:
    // --- DSP Configuration ---
    float sampleRate_ = 48000.0f;
    std::atomic<float> userAmplitude_{2.0f};
    std::atomic<int> currentPresetId_{0};

    // --- Crossover Filters (reusable) ---
    LinkwitzRiley4th subLowPass_;
    LinkwitzRiley4th midHighPass_, midLowPass_;
    LinkwitzRiley4th textureHighPass_;

    // --- Filter Output Buffers ---
    alignas(64) float subOutput_[256];
    alignas(64) float midOutput_[256];
    alignas(64) float textureOutput_[256];

    // --- Pitch Detection ---
    float historyBuffer_[2048] = {0.0f};

    // --- Thermal Model ---
    float coilTemp_ = 25.0f;
    float magnetTemp_ = 25.0f;

    // ──────────────────────────────────────────────
    //  Layer 1: ONSET / BEAT Detection
    //  Spectral-flux onset detection with refractory period
    //  Produces sharp transient envelope: instant attack + fast decay
    // ──────────────────────────────────────────────
    static constexpr int BEAT_HISTORY_SIZE = 43;  // ~1 second at 256/48kHz
    float energyHistory_[BEAT_HISTORY_SIZE] = {};
    int energyHistoryIdx_ = 0;
    float beatEnvelope_ = 0.0f;
    float prevSubRms_ = 0.0f;
    int onsetRefractoryCounter_ = 1000;  // Blocks since last onset (start high = ready)

    // ──────────────────────────────────────────────
    //  Layer 2: BASS BODY (subtle floor only)
    //  Very faint sustained pressure — capped low, never dominant
    // ──────────────────────────────────────────────
    float bassSmoothed_ = 0.0f;

    // ──────────────────────────────────────────────
    //  Layer 3: MELODY (pitch-mapped, subtle)
    //  Note frequency → intensity, capped for non-interference
    // ──────────────────────────────────────────────
    float melodySmoothed_ = 0.0f;

    // ──────────────────────────────────────────────
    //  Continuous Output Ring Buffer
    //  Stores amplitude samples (0-255) for Kotlin to pull
    //  Thread-safe: processAudioBlock (producer) and getHapticFrame (consumer)
    //  run on different threads — indices use atomic operations
    // ──────────────────────────────────────────────
    static constexpr int HAPTIC_BUF_SIZE = 2048;
    float hapticBuf_[HAPTIC_BUF_SIZE] = {};
    std::atomic<int> hapticWriteIdx_{0};
    std::atomic<int> hapticReadIdx_{0};
    std::atomic<int> hapticCount_{0};

    // --- Sample rate matching: produce output at fixed 100Hz (10ms per sample) ---
    float hapticPhaseAccum_ = 0.0f;
    static constexpr float HAPTIC_SAMPLE_PERIOD_S = 0.01f;

    // --- Last composed amplitude for telemetry ---
    float lastComposedAmp_ = 0.0f;
    float lastComposite_ = 0.0f;
    float lastBeatLayer_ = 0.0f;
    float lastBassLayer_ = 0.0f;
    float lastMelodyLayer_ = 0.0f;

public:
    HapticEngine() {
        configure(48000.0f, 60.0f, 200.0f, 2.0f, 0);
    }

    void configure(float sampleRate, float lowCutoff, float highCutoff, float amplitude, int presetId) {
        sampleRate_ = sampleRate;
        userAmplitude_.store(amplitude, std::memory_order_relaxed);
        currentPresetId_.store(presetId, std::memory_order_relaxed);
        subLowPass_.setLowPass(sampleRate, lowCutoff);
        midHighPass_.setHighPass(sampleRate, lowCutoff);
        midLowPass_.setLowPass(sampleRate, highCutoff);
        textureHighPass_.setHighPass(sampleRate, highCutoff);
    }

    // ══════════════════════════════════════════════
    //  NEON-accelerated RMS computation
    // ══════════════════════════════════════════════
    static float computeRmsNeon(const float* buffer, int size) {
        if (size <= 0) return 0.0f;
        int i = 0;
        float32x4_t vSum = vdupq_n_f32(0.0f);
        for (; i <= size - 4; i += 4) {
            float32x4_t vIn = vld1q_f32(buffer + i);
            vSum = vmlaq_f32(vSum, vIn, vIn);
        }
        float sum = vaddvq_f32(vSum);
        for (; i < size; ++i) {
            sum += buffer[i] * buffer[i];
        }
        float rms = std::sqrt(sum / static_cast<float>(size));
        return std::isnan(rms) ? 0.0f : rms;
    }

    // ══════════════════════════════════════════════
    //  Pitch estimation via autocorrelation
    // ══════════════════════════════════════════════
    float estimatePitch(const float* signal, int size) {
        std::memmove(historyBuffer_, historyBuffer_ + size, (2048 - size) * sizeof(float));
        std::memcpy(historyBuffer_ + (2048 - size), signal, size * sizeof(float));

        int minLag = static_cast<int>(sampleRate_ / 300.0f);
        int maxLag = static_cast<int>(sampleRate_ / 35.0f);
        if (maxLag > 1500) maxLag = 1500;

        int bestLag = -1;
        float maxCorr = -1e9f;
        int startIndex = 2048 - size;

        for (int lag = minLag; lag <= maxLag; ++lag) {
            float corr = 0.0f;
            for (int i = 0; i < size; ++i) {
                corr += historyBuffer_[startIndex + i] * historyBuffer_[startIndex + i - lag];
            }
            if (corr > maxCorr) {
                maxCorr = corr;
                bestLag = lag;
            }
        }

        if (bestLag == -1 || maxCorr <= 0.001f) return 150.0f;
        float freq = sampleRate_ / static_cast<float>(bestLag);
        return std::clamp(freq, 35.0f, 300.0f);
    }

    // ══════════════════════════════════════════════
    //  Main audio processing block
    //  Performs: crossover → RMS → pitch → thermal → 5-layer compose
    //  Outputs telemetry to outTelemetry[6]
    //  Stores continuous haptic amplitude to ring buffer
    // ══════════════════════════════════════════════
    void processAudioBlock(const float* input, int size, float* outTelemetry) {
        if (size > 256) size = 256;

        // 1. Three-band crossover
        for (int i = 0; i < size; ++i) {
            float s = input[i];
            subOutput_[i] = subLowPass_.process(s);
            float midTemp = midHighPass_.process(s);
            midOutput_[i] = midLowPass_.process(midTemp);
            textureOutput_[i] = textureHighPass_.process(s);
        }

        // 2. NEON-accelerated RMS energy extraction
        float subRms = computeRmsNeon(subOutput_, size);
        float midRms = computeRmsNeon(midOutput_, size);
        float textureRms = computeRmsNeon(textureOutput_, size);

        // 3. F0 pitch tracking
        float pitch = estimatePitch(input, size);

        // 4. Preset-based gain
        float amp = userAmplitude_.load(std::memory_order_relaxed);
        int preset = currentPresetId_.load(std::memory_order_relaxed);
        if (preset == 1) { subRms *= 1.4f; }
        else if (preset == 2) { textureRms *= 1.5f; }
        else if (preset == 3) { subRms *= 1.25f; midRms *= 1.35f; }

        // 5. Thermal dual-node model
        float powerSum = (subRms * subRms) + (midRms * midRms * 0.4f);
        float dt = static_cast<float>(size) / sampleRate_;
        float heatFlow = (coilTemp_ - magnetTemp_) / 25.0f;
        coilTemp_ += (powerSum - heatFlow) * dt / 0.8f;
        magnetTemp_ += (heatFlow - (magnetTemp_ - 25.0f) / 15.0f) * dt / 4.0f;

        float thermalGain = 1.0f;
        if (coilTemp_ >= 100.0f) {
            thermalGain = 0.0f;
        } else if (coilTemp_ >= 80.0f) {
            float ratio = (coilTemp_ - 80.0f) / 20.0f;
            thermalGain = 0.5f * (1.0f + cosf(ratio * M_PI));
        }

        // 6. Write telemetry (backward-compatible)
        outTelemetry[0] = subRms * amp * thermalGain;
        outTelemetry[1] = midRms * amp * thermalGain;
        outTelemetry[2] = textureRms * amp * thermalGain;
        outTelemetry[3] = pitch;
        outTelemetry[4] = coilTemp_;
        outTelemetry[5] = thermalGain;

        // ════════════════════════════════════════════
        //  7. Five-Layer Continuous Haptic Composition
        // ════════════════════════════════════════════
        composeHapticLayer(subRms, midRms, textureRms, pitch, thermalGain, amp, dt);

        prevSubRms_ = subRms;
    }

    // ══════════════════════════════════════════════
    //  Haptic Composer — Event-Driven Transient Model
    //  Onset detection triggers sharp ADSR envelope.
    //  Between hits: near-zero.  On hit: fast attack → punchy decay.
    //  Output: single amplitude sample (0-255) pushed to ring buffer
    // ══════════════════════════════════════════════
    void composeHapticLayer(float subRms, float midRms, float textureRms,
                            float pitch, float thermalGain, float userAmp, float dt) {
        // ─── ONSET DETECTION ───
        // Track energy history for adaptive threshold
        energyHistory_[energyHistoryIdx_] = subRms;
        energyHistoryIdx_ = (energyHistoryIdx_ + 1) % BEAT_HISTORY_SIZE;

        float avgEnergy = 0.0f;
        for (int i = 0; i < BEAT_HISTORY_SIZE; ++i) {
            avgEnergy += energyHistory_[i];
        }
        avgEnergy /= static_cast<float>(BEAT_HISTORY_SIZE);

        // Positive spectral flux = energy rising sharply
        float energyFlux = subRms - prevSubRms_;

        // Onset condition: energy spikes above 1.5x average AND is rising
        bool isOnset = (subRms > avgEnergy * 1.5f)
                     && (subRms > 0.008f)
                     && (energyFlux > avgEnergy * 0.3f)
                     && (onsetRefractoryCounter_ >= 3);  // ~16ms min between hits

        if (isOnset) {
            // Hit strength: how much this exceeds the average
            float hitStrength = std::clamp(subRms / (avgEnergy + 0.001f) - 1.0f, 0.3f, 3.0f);
            // Scale envelope — stronger hits punch harder
            float triggerLevel = std::clamp(0.4f + hitStrength * 0.35f, 0.4f, 1.0f);
            beatEnvelope_ = std::max(beatEnvelope_, triggerLevel);
            onsetRefractoryCounter_ = 0;
        }
        onsetRefractoryCounter_++;

        // ─── BEAT ENVELOPE: punchy exponential decay ───
        // τ ≈ 40ms → e^(-25t): snappy hit that fades fast
        // Attack is instant (envelope set directly), decay is exponential
        beatEnvelope_ *= expf(-25.0f * dt);
        float beatLayer = std::clamp(beatEnvelope_, 0.0f, 1.0f);

        // ─── BASS BODY: faint sustained floor (never dominant) ───
        // Capped at 0.10 — reduced from 0.15 to lower sustained rumble for Kugou
        float bassTarget = std::min(0.10f, subRms * 0.25f);
        bassSmoothed_ += 0.3f * (bassTarget - bassSmoothed_);
        float bassLayer = std::clamp(bassSmoothed_, 0.0f, 0.10f);

        // ─── MELODY: pitch-mapped subtle accent ───
        // Capped at 0.10 — adds flavour on mid-range notes, never dominant
        float pitchNorm = std::clamp((pitch - 40.0f) / 260.0f, 0.0f, 1.0f);
        float melodyRaw = std::min(0.10f, midRms * 0.5f) * (1.0f - pitchNorm * 0.5f);
        melodySmoothed_ += 0.15f * (melodyRaw - melodySmoothed_);
        float melodyLayer = std::clamp(melodySmoothed_, 0.0f, 0.10f);

        // ─── COMPOSITE: beat-dominant with subtle layers ───
        // Beat is the star. Bass/Melody are faint garnish.
        // Weights adjusted for Kugou: Beat 0.85 (up from 0.80),
        // Bass 0.08 (down from 0.12), Melody 0.07 (same).
        // This increases transient punch while reducing sustained rumble.
        float composite = beatLayer   * 0.85f
                        + bassLayer    * 0.08f
                        + melodyLayer  * 0.07f;

        // ─── Apply thermal safety + user gain ───
        composite *= thermalGain * userAmp;

        // Store raw composite for telemetry
        lastComposite_ = composite;

        // ─── Map to 0-255 — linear, no floor ───
        // Silence must be 0. Transient peaks should be punchy.
        float clamped = std::clamp(composite, 0.0f, 1.5f);
        int amplitude;
        if (clamped > 0.003f) {
            amplitude = static_cast<int>(clamped * 255.0f);
            amplitude = std::clamp(amplitude, 1, 255);
        } else {
            amplitude = 0;
        }

        // Store layer values for telemetry
        lastComposedAmp_ = static_cast<float>(amplitude);
        lastBeatLayer_ = beatLayer;
        lastBassLayer_ = bassLayer;
        lastMelodyLayer_ = melodyLayer;

        // Push to ring buffer at 100Hz rate (matching Kotlin's 10ms per sample playback)
        // Phase accumulator ensures sample rate matches playback rate exactly
        hapticPhaseAccum_ += dt;
        if (hapticPhaseAccum_ >= HAPTIC_SAMPLE_PERIOD_S) {
            hapticPhaseAccum_ -= HAPTIC_SAMPLE_PERIOD_S;
            int writeIdx = hapticWriteIdx_.load(std::memory_order_relaxed);
            int count = hapticCount_.load(std::memory_order_relaxed);
            if (count < HAPTIC_BUF_SIZE) {
                hapticBuf_[writeIdx] = static_cast<float>(amplitude);
                hapticWriteIdx_.store((writeIdx + 1) % HAPTIC_BUF_SIZE, std::memory_order_relaxed);
                hapticCount_.store(count + 1, std::memory_order_relaxed);
            }
        }
    }

    // ══════════════════════════════════════════════
    //  Pull continuous haptic frame from ring buffer
    //  Returns number of amplitude samples copied
    // ══════════════════════════════════════════════
    int getHapticFrame(float* outAmplitudes, int maxCount) {
        int count = std::min(hapticCount_.load(std::memory_order_relaxed), maxCount);
        int readIdx = hapticReadIdx_.load(std::memory_order_relaxed);
        for (int i = 0; i < count; ++i) {
            outAmplitudes[i] = hapticBuf_[readIdx];
            readIdx = (readIdx + 1) % HAPTIC_BUF_SIZE;
        }
        hapticReadIdx_.store(readIdx, std::memory_order_relaxed);
        hapticCount_.fetch_sub(count, std::memory_order_relaxed);
        return count;
    }

    // Clear the haptic buffer (called on pause/reset)
    void clearHapticBuffer() {
        hapticWriteIdx_.store(0, std::memory_order_relaxed);
        hapticReadIdx_.store(0, std::memory_order_relaxed);
        hapticCount_.store(0, std::memory_order_relaxed);
        hapticPhaseAccum_ = 0.0f;
        beatEnvelope_ = 0.0f;
        onsetRefractoryCounter_ = 1000;
        bassSmoothed_ = 0.0f;
        melodySmoothed_ = 0.0f;
        std::memset(energyHistory_, 0, sizeof(energyHistory_));
        lastComposedAmp_ = 0.0f;
        lastComposite_ = 0.0f;
    }

    // Telemetry accessors
    float getLastComposedAmp() const { return lastComposedAmp_; }
    float getLastBeatLayer() const { return lastBeatLayer_; }
    float getLastBassLayer() const { return lastBassLayer_; }
    float getLastMelodyLayer() const { return lastMelodyLayer_; }
};

} // namespace haptic