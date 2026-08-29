#include <mutex>
#pragma once

#include <arm_neon.h>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <vector>
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
//  1D Value Noise for Texture Layer
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

    float next(float advance) {
        position_ += advance;
        int i = static_cast<int>(floorf(position_));
        float f = position_ - static_cast<float>(i);
        float u = f * f * (3.0f - 2.0f * f);
        float a = hash01(i);
        float b = hash01(i + 1);
        return lerp(a, b, u);
    }
};

// ════════════════════════════════════════════════════════════════
//  Haptic Telemetry
// ════════════════════════════════════════════════════════════════

// ════════════════════════════════════════════════════════════════
//  v3.8 Semantic Instrument Engine: Multi-Track Frame
// ════════════════════════════════════════════════════════════════
struct SemanticHapticFrame {
    float kickAmp;   // Fast-attack, fast-decay specifically for kick drums
    float snareAmp;  // Fast-attack, exponential decay for snare/clap/hi-hat
    float vocalAmp;  // Slow-attack, long-release envelope for vocal/harmony
    float bodyAmp;   // The general sub/low-mid composite background rumble
};

struct HapticTelemetry {
    float sub;
    float mid;
    float texture;
    float pitch;
    float temperature;
    float thermalGain;
    float beatStrength;
    float onsetFlag;
    float beatIntervalMs;
    float beatConfidence;
    // v4.1: Per-band onset strength for discrete event-driven haptics
    float onsetKick;
    float onsetSnare;
    float onsetVocal;
    float onsetBody;
};

// ════════════════════════════════════════════════════════════════
//  Continuous Haptic Synthesis Engine  (v3.7.3 — smoothness overhaul)
//
//  Key changes from v3.7.2:
//   1. Onset decay τ 40ms→60ms — less aggressive falloff, smoother between beats
//   2. Bass body raised from 0.10→0.18 with faster tracking — fills gaps between beats
//   3. Inter-onset hold: after a beat, hold a decaying sustain instead of dropping to 0
//   4. Ring buffer pushes every processAudioBlock (removed phase accumulator that
//      could skip samples when audio blocks arrive at irregular intervals)
//   5. Minimum floor of 3 (not 0) when music is active — prevents full-off gaps
// ════════════════════════════════════════════════════════════════

class HapticEngine {
private:
    void pushSemanticFrame(float kick, float snare, float vocal, float body) {
        SemanticHapticFrame frame;
        frame.kickAmp = kick;
        frame.snareAmp = snare;
        frame.vocalAmp = vocal;
        frame.bodyAmp = body;
        std::lock_guard<std::mutex> lock(hapticBufferMutex_);
        if (semanticHapticBuffer_.size() < 2048) {
            semanticHapticBuffer_.push_back(frame);
        }
    }

    float sampleRate_ = 48000.0f;
    std::atomic<float> userAmplitude_{2.0f};
    std::atomic<int> currentPresetId_{0};

    LinkwitzRiley4th subLowPass_;
    LinkwitzRiley4th midHighPass_, midLowPass_;
    LinkwitzRiley4th textureHighPass_;

    // v3.8 semantic filter bank.  These are real independently filtered
    // analysis bands; unlike the old Kotlin pseudo-spectrum they retain the
    // different envelopes needed to distinguish percussion, voice and harmony.
    LinkwitzRiley4th bassHp_, bassLp_;       // 80..180 Hz
    LinkwitzRiley4th lowMidHp_, lowMidLp_;   // 180..500 Hz
    LinkwitzRiley4th vocalHp_, vocalLp_;     // 500..3000 Hz
    LinkwitzRiley4th presenceHp_, presenceLp_; // 3..8 kHz
    LinkwitzRiley4th airHp_;                 // >8 kHz

    alignas(64) float subOutput_[256];
    alignas(64) float midOutput_[256];
    alignas(64) float textureOutput_[256];

    float prevLowMidRms_ = 0.0f;
    float prevPresenceRms_ = 0.0f;
    float prevAirRms_ = 0.0f;
    float vocalBandRms_ = 0.0f;  // v3.11: vocal band energy for instrument-aware composition
    float prevPitch_ = 0.0f;
    float pitchConfidence_ = 0.0f;
    float kickProbability_ = 0.0f;
    float snareProbability_ = 0.0f;
    float hatProbability_ = 0.0f;
    float vocalProbability_ = 0.0f;
    float pluckedProbability_ = 0.0f;
    float harmonicProbability_ = 0.0f;
    float bassSustainProbability_ = 0.0f;

    SemanticHapticFrame historyBuffer_[2048] = {};
    float pitchHistory_[2048] = {};

    // v3.8 Semantic multi-track output buffer
    std::vector<SemanticHapticFrame> semanticHapticBuffer_;
    std::mutex hapticBufferMutex_;

    public:
    // v4.1: Onset ring buffer for event-driven haptics
    struct OnsetFrame {
        float kick = 0.0f;
        float snare = 0.0f;
        float vocal = 0.0f;
        float body = 0.0f;
    };
    static constexpr int ONSET_BUF_SIZE = 256;
    OnsetFrame onsetBuf_[ONSET_BUF_SIZE] = {};
    std::atomic<int> onsetWriteIdx_{0};
    std::atomic<int> onsetReadIdx_{0};
    std::atomic<int> onsetCount_{0};

    void pushOnsetFrame(float kick, float snare, float vocal, float body) {
        int writeIdx = onsetWriteIdx_.load(std::memory_order_relaxed);
        int count = onsetCount_.load(std::memory_order_relaxed);
        if (count < ONSET_BUF_SIZE) {
            onsetBuf_[writeIdx] = {kick, snare, vocal, body};
            onsetWriteIdx_.store((writeIdx + 1) % ONSET_BUF_SIZE, std::memory_order_relaxed);
            onsetCount_.store(count + 1, std::memory_order_relaxed);
        } else {
            // Buffer full - overwrite oldest
            onsetBuf_[writeIdx] = {kick, snare, vocal, body};
            onsetWriteIdx_.store((writeIdx + 1) % ONSET_BUF_SIZE, std::memory_order_relaxed);
            int readIdx = onsetReadIdx_.load(std::memory_order_relaxed);
            onsetReadIdx_.store((readIdx + 1) % ONSET_BUF_SIZE, std::memory_order_relaxed);
        }
    }

    int getOnsetFrames(OnsetFrame* outFrames, int maxFrames) {
        int count = std::min(onsetCount_.load(std::memory_order_relaxed), maxFrames);
        int readIdx = onsetReadIdx_.load(std::memory_order_relaxed);
        for (int i = 0; i < count; ++i) {
            outFrames[i] = onsetBuf_[readIdx];
            readIdx = (readIdx + 1) % ONSET_BUF_SIZE;
        }
        onsetReadIdx_.store(readIdx, std::memory_order_relaxed);
        onsetCount_.fetch_sub(count, std::memory_order_relaxed);
        return count;
    }

    private:
    float coilTemp_ = 25.0f;
    float magnetTemp_ = 25.0f;

    // ── Layer 1: Onset / Beat ──
    static constexpr int BEAT_HISTORY_SIZE = 43;
    float energyHistory_[BEAT_HISTORY_SIZE] = {};
    int energyHistoryIdx_ = 0;
    float beatEnvelope_ = 0.0f;
    float prevSubRms_ = 0.0f;
    int onsetRefractoryCounter_ = 1000;

    int frameCounter_ = 0;
    int lastOnsetFrame_ = -1000;
    float beatIntervalFrames_ = 0.0f;
    float beatConfidence_ = 0.0f;
    bool onsetThisFrame_ = false;

    // v3.7.3: Beat sustain hold — REMOVED in v3.11
    // Was causing unconditional "底震" (background rumble).
    // Bass sustain is now content-aware via bassSustainProbability_.

    // ── Layer 2: Bass Body ──
    float bassSmoothed_ = 0.0f;

    // ── v3.11: Instrument-aware envelopes ──
    float vocalEnvelope_ = 0.0f;
    float harmonicEnvelope_ = 0.0f;
    float smoothedAmp_ = 0.0f;  // one-pole smoother to prevent inter-frame jumps

    // ── Layer 3: Melody ──
    float melodySmoothed_ = 0.0f;

    // ── Ring Buffer ──
    static constexpr int HAPTIC_BUF_SIZE = 2048;
    float hapticBuf_[HAPTIC_BUF_SIZE] = {};
    std::atomic<int> hapticWriteIdx_{0};
    std::atomic<int> hapticReadIdx_{0};
    std::atomic<int> hapticCount_{0};

    // v3.7.3: Removed phase accumulator — push one sample per
    // processAudioBlock call. The Kotlin/native scheduler polls at
    // 10ms intervals; as long as audio blocks arrive at ~5ms each
    // (256 samples / 48kHz ≈ 5.3ms), the ring buffer stays filled
    // and the scheduler never starves.
    // If audio blocks arrive slower (e.g. 44100Hz), we still push
    // one sample per block — the scheduler will simply pull 0 when
    // the buffer is momentarily empty, then pull 2+ when it refills.
    // This is much smoother than the old phase-accumulator which
    // could skip pushing on some blocks, creating gaps.

    float lastComposedAmp_ = 0.0f;
    float lastComposite_ = 0.0f;
    float lastBeatLayer_ = 0.0f;
    float lastBassLayer_ = 0.0f;
    float lastMelodyLayer_ = 0.0f;

    ValueNoise1D textureNoise_;

    // v3.7.3: Track whether we've seen audio recently (for floor)
    int blocksSinceAudio_ = 1000;

    // v4.1: Onset detector state (per-band spectral flux + energy diff)
    float prevBassRms_ = 0.0f;
    float prevVocalRms_ = 0.0f;
    int onsetRefractoryFrames_[4] = {0, 0, 0, 0}; // kick, snare, vocal, body
    static constexpr int ONSET_REFRACTORY_FRAMES = 6; // 60ms at 10ms/frame

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

        bassHp_.setHighPass(sampleRate, 80.0f);
        bassLp_.setLowPass(sampleRate, 180.0f);
        lowMidHp_.setHighPass(sampleRate, 180.0f);
        lowMidLp_.setLowPass(sampleRate, 500.0f);
        vocalHp_.setHighPass(sampleRate, 500.0f);
        vocalLp_.setLowPass(sampleRate, 3000.0f);
        presenceHp_.setHighPass(sampleRate, 3000.0f);
        presenceLp_.setLowPass(sampleRate, 8000.0f);
        airHp_.setHighPass(sampleRate, 8000.0f);
    }

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

    float estimatePitch(const float* signal, int size) {
        std::memmove(pitchHistory_, pitchHistory_ + size, (2048 - size) * sizeof(float));
        std::memcpy(pitchHistory_ + (2048 - size), signal, size * sizeof(float));

        int minLag = static_cast<int>(sampleRate_ / 300.0f);
        int maxLag = static_cast<int>(sampleRate_ / 35.0f);
        if (maxLag > 1500) maxLag = 1500;

        int bestLag = -1;
        float maxCorr = -1e9f;
        int startIndex = 2048 - size;

        for (int lag = minLag; lag <= maxLag; ++lag) {
            float corr = 0.0f;
            int i = 0;
            float32x4_t vSum = vdupq_n_f32(0.0f);
            const float* base = pitchHistory_ + startIndex;
            const float* lagged = base - lag;
            for (; i <= size - 4; i += 4) {
                float32x4_t vA = vld1q_f32(base + i);
                float32x4_t vB = vld1q_f32(lagged + i);
                vSum = vmlaq_f32(vSum, vA, vB);
            }
            corr = vaddvq_f32(vSum);
            for (; i < size; ++i) {
                corr += base[i] * lagged[i];
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
    // ══════════════════════════════════════════════
    void processAudioBlock(const float* input, int size, float* outTelemetry) {
        if (size > 256) size = 256;

        onsetThisFrame_ = false;
        blocksSinceAudio_ = 0;

        // 1. Legacy crossover plus v3.8 real semantic filter bank.
        float bassSq = 0.0f, lowMidSq = 0.0f, vocalSq = 0.0f;
        float presenceSq = 0.0f, airSq = 0.0f, absSum = 0.0f;
        int zeroCrossings = 0;
        float previousSample = input[0];
        for (int i = 0; i < size; ++i) {
            float s = input[i];
            subOutput_[i] = subLowPass_.process(s);
            float midTemp = midHighPass_.process(s);
            midOutput_[i] = midLowPass_.process(midTemp);
            textureOutput_[i] = textureHighPass_.process(s);

            float bass = bassLp_.process(bassHp_.process(s));
            float lowMid = lowMidLp_.process(lowMidHp_.process(s));
            float vocal = vocalLp_.process(vocalHp_.process(s));
            float presence = presenceLp_.process(presenceHp_.process(s));
            float air = airHp_.process(s);
            bassSq += bass * bass;
            lowMidSq += lowMid * lowMid;
            vocalSq += vocal * vocal;
            presenceSq += presence * presence;
            airSq += air * air;
            absSum += std::abs(s);
            if ((s >= 0.0f) != (previousSample >= 0.0f)) zeroCrossings++;
            previousSample = s;
        }

        // 2. RMS and shape descriptors.
        float subRms = computeRmsNeon(subOutput_, size);
        float midRms = computeRmsNeon(midOutput_, size);
        float textureRms = computeRmsNeon(textureOutput_, size);
        const float invSize = 1.0f / static_cast<float>(size);
        float bassBand = std::sqrt(bassSq * invSize);
        float lowMidBand = std::sqrt(lowMidSq * invSize);
        float vocalBand = std::sqrt(vocalSq * invSize);
        float presenceBand = std::sqrt(presenceSq * invSize);
        float airBand = std::sqrt(airSq * invSize);
        float zcr = static_cast<float>(zeroCrossings) * invSize;
        float totalBand = bassBand + lowMidBand + vocalBand + presenceBand + airBand + 1.0e-6f;
        float highRatio = (presenceBand + airBand) / totalBand;
        float vocalRatio = vocalBand / totalBand;
        float bassRatio = (subRms + bassBand) / (totalBand + subRms);
        float lowMidFlux = std::max(0.0f, lowMidBand - prevLowMidRms_);
        float presenceFlux = std::max(0.0f, presenceBand - prevPresenceRms_);
        float airFlux = std::max(0.0f, airBand - prevAirRms_);

        // 3. Pitch and periodicity. Stable periodic mid-band content is a useful
        // low-cost voice/harmonic cue; it is deliberately exposed as probability,
        // not claimed as source separation.
        float pitch = estimatePitch(input, size);
        float pitchDelta = prevPitch_ > 0.0f ? std::abs(pitch - prevPitch_) / std::max(prevPitch_, 1.0f) : 1.0f;
        pitchConfidence_ += 0.18f * (((pitch >= 70.0f && pitch <= 300.0f) && pitchDelta < 0.18f ? 1.0f : 0.0f) - pitchConfidence_);

        float kickTarget = std::clamp((bassRatio - 0.28f) * 2.2f + std::max(0.0f, subRms - prevSubRms_) * 12.0f - highRatio * 0.5f, 0.0f, 1.0f);
        float snareTarget = std::clamp(lowMidFlux * 18.0f + presenceFlux * 10.0f + highRatio * 0.35f - bassRatio * 0.25f, 0.0f, 1.0f);
        float hatTarget = std::clamp(airFlux * 28.0f + presenceFlux * 8.0f + zcr * 1.5f - bassRatio * 0.35f, 0.0f, 1.0f);
        float vocalTarget = std::clamp(vocalRatio * 1.8f + pitchConfidence_ * 0.65f - highRatio * 0.45f - std::max(kickTarget, snareTarget) * 0.35f, 0.0f, 1.0f);
        float pluckedTarget = std::clamp(lowMidFlux * 14.0f + presenceFlux * 5.0f + pitchConfidence_ * 0.35f - vocalTarget * 0.25f, 0.0f, 1.0f);
        float harmonicTarget = std::clamp(pitchConfidence_ * 0.65f + (lowMidBand + vocalBand) / totalBand * 0.55f - std::max({kickTarget, snareTarget, hatTarget}) * 0.3f, 0.0f, 1.0f);
        float bassSustainTarget = std::clamp(bassRatio * 1.35f - std::max(0.0f, subRms - prevSubRms_) * 5.0f, 0.0f, 1.0f);

        auto smoothProbability = [](float current, float target) {
            const float alpha = target > current ? 0.42f : 0.10f;
            return current + alpha * (target - current);
        };
        kickProbability_ = smoothProbability(kickProbability_, kickTarget);
        snareProbability_ = smoothProbability(snareProbability_, snareTarget);
        hatProbability_ = smoothProbability(hatProbability_, hatTarget);
        vocalProbability_ = smoothProbability(vocalProbability_, vocalTarget);
        pluckedProbability_ = smoothProbability(pluckedProbability_, pluckedTarget);
        harmonicProbability_ = smoothProbability(harmonicProbability_, harmonicTarget);
        bassSustainProbability_ = smoothProbability(bassSustainProbability_, bassSustainTarget);

        // v4.1: Onset detection via spectral flux + energy differential
        // Each band: flux = max(0, current - previous), onset = flux * probability * gain
        float bassFlux = std::max(0.0f, bassBand - prevBassRms_);
        float lowMidFlux2 = std::max(0.0f, lowMidBand - prevLowMidRms_);
        float vocalFlux = std::max(0.0f, vocalBand - prevVocalRms_);
        float presenceFlux2 = std::max(0.0f, presenceBand - prevPresenceRms_);
        float airFlux2 = std::max(0.0f, airBand - prevAirRms_);

        // Refractory counters (decrement each frame)
        for (int i = 0; i < 4; ++i) {
            if (onsetRefractoryFrames_[i] > 0) onsetRefractoryFrames_[i]--;
        }

        // Kick onset: bass flux + subRms rise
        float kickOnsetRaw = bassFlux * 8.0f + std::max(0.0f, subRms - prevSubRms_) * 6.0f;
        float kickOnset = (onsetRefractoryFrames_[0] == 0 && kickOnsetRaw > 0.008f) ? std::clamp(kickOnsetRaw * kickProbability_ * 1.5f, 0.0f, 1.0f) : 0.0f;
        if (kickOnset > 0.0f) onsetRefractoryFrames_[0] = ONSET_REFRACTORY_FRAMES;

        // Snare onset: lowMid + presence flux
        float snareOnsetRaw = lowMidFlux2 * 12.0f + presenceFlux2 * 8.0f;
        float snareOnset = (onsetRefractoryFrames_[1] == 0 && snareOnsetRaw > 0.006f) ? std::clamp(snareOnsetRaw * snareProbability_ * 1.3f, 0.0f, 1.0f) : 0.0f;
        if (snareOnset > 0.0f) onsetRefractoryFrames_[1] = ONSET_REFRACTORY_FRAMES;

        // Vocal onset: vocal flux + pitch confidence
        float vocalOnsetRaw = vocalFlux * 5.0f + presenceFlux2 * 3.0f;
        float vocalOnset = (onsetRefractoryFrames_[2] == 0 && vocalOnsetRaw > 0.004f) ? std::clamp(vocalOnsetRaw * vocalProbability_ * 1.2f, 0.0f, 1.0f) : 0.0f;
        if (vocalOnset > 0.0f) onsetRefractoryFrames_[2] = ONSET_REFRACTORY_FRAMES;

        // Body onset: bass sustain + air flux (for atmosphere)
        float bodyOnsetRaw = bassFlux * 3.0f + airFlux2 * 4.0f;
        float bodyOnset = (onsetRefractoryFrames_[3] == 0 && bodyOnsetRaw > 0.005f) ? std::clamp(bodyOnsetRaw * bassSustainProbability_ * 1.0f, 0.0f, 1.0f) : 0.0f;
        if (bodyOnset > 0.0f) onsetRefractoryFrames_[3] = ONSET_REFRACTORY_FRAMES;

        // Update previous RMS for next frame
        prevBassRms_ = bassBand;
        prevLowMidRms_ = lowMidBand;
        prevVocalRms_ = vocalBand;
        prevPresenceRms_ = presenceBand;
        prevAirRms_ = airBand;

        // 4. Preset gain
        float amp = userAmplitude_.load(std::memory_order_relaxed);
        int preset = currentPresetId_.load(std::memory_order_relaxed);
        if (preset == 1) { subRms *= 1.4f; }
        else if (preset == 2) { textureRms *= 1.5f; }
        else if (preset == 3) { subRms *= 1.25f; midRms *= 1.35f; }

        // 5. Thermal model
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

        // 6. Compose
        composeHapticLayer(subRms, midRms, textureRms, pitch, thermalGain, amp, dt);

        // 7. Telemetry
        outTelemetry[0] = subRms * amp * thermalGain;
        outTelemetry[1] = midRms * amp * thermalGain;
        outTelemetry[2] = textureRms * amp * thermalGain;
        outTelemetry[3] = pitch;
        outTelemetry[4] = coilTemp_;
        outTelemetry[5] = thermalGain;
        outTelemetry[6] = beatEnvelope_;
        outTelemetry[7] = onsetThisFrame_ ? 1.0f : 0.0f;
        float frameDurationMs = dt * 1000.0f;
        outTelemetry[8] = beatIntervalFrames_ * frameDurationMs;
        outTelemetry[9] = beatConfidence_;
        // v3.8 semantic probabilities (heuristic confidence, 0..1)
        outTelemetry[10] = kickProbability_;
        outTelemetry[11] = snareProbability_;
        outTelemetry[12] = hatProbability_;
        outTelemetry[13] = vocalProbability_;
        outTelemetry[14] = pluckedProbability_;
        outTelemetry[15] = harmonicProbability_;
        outTelemetry[16] = bassSustainProbability_;
        outTelemetry[17] = pitchConfidence_;
        outTelemetry[18] = vocalBand;
        outTelemetry[19] = presenceBand + airBand;
        // v4.1: Per-band onset strength for discrete event-driven haptics
        outTelemetry[20] = kickOnset;
        outTelemetry[21] = snareOnset;
        outTelemetry[22] = vocalOnset;
        outTelemetry[23] = bodyOnset;

        // Push to onset ring buffer for Kotlin event-driven consumption
        pushOnsetFrame(kickOnset, snareOnset, vocalOnset, bodyOnset);

        prevSubRms_ = subRms;
        prevLowMidRms_ = lowMidBand;
        prevPresenceRms_ = presenceBand;
        prevAirRms_ = airBand;
        prevPitch_ = pitch;
        frameCounter_++;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  v3.8: Multi-Track Compose
    //  Instead of squashing everything into one amplitude, we render envelopes
    //  for each instrumental track separately.
    // ═════════════════════════════════════════════════════════════════
    void composeHapticLayer(float subRms, float midRms, float textureRms,
                            float pitch, float thermalGain, float userAmp, float dt) {
        // 1. Kick Track: Fast attack, sharp exponential decay
        float kickEnv = std::max(0.0f, subRms - prevSubRms_) * 8.0f * kickProbability_;
        kickEnv = std::clamp(kickEnv, 0.0f, 1.0f);
        
        // 2. Snare Track: Snap transient
        float snareEnv = (midRms * 1.5f + textureRms * 2.0f) * snareProbability_;
        snareEnv = std::clamp(snareEnv, 0.0f, 1.0f);
        
        // 3. Vocal Track: Long-sustain low-pass envelope
        float vocalTarget = (midRms * 0.8f + textureRms * 0.3f) * vocalProbability_;
        vocalEnvelope_ += (vocalTarget - vocalEnvelope_) * 0.15f;
        float vocalEnv = std::clamp(vocalEnvelope_, 0.0f, 1.0f);
        
        // 4. Body (Background Rumble)
        float bodyTarget = (subRms * 0.5f + midRms * 0.2f) * bassSustainProbability_;
        bassSmoothed_ += (bodyTarget - bassSmoothed_) * 0.2f;
        float bodyEnv = std::clamp(bassSmoothed_, 0.0f, 1.0f);
        
        // v3.15: TAIL-CATCHER — replaced hard cutoff with smooth attenuation.
        // Old: if (totalEnergy < 0.003f) → everything zeroed → "尾气" (fade tails) lost.
        // New: Below 0.002f, apply progressive gain reduction instead of hard zero.
        //      0.002→1.0x, 0.001→0.5x, 0.0005→0.25x, approaching 0→0x.
        //      This preserves the C++ DSP output for Kotlin-side rendering.
        float totalEnergy = kickEnv + snareEnv + vocalEnv + bodyEnv;
        if (totalEnergy < 0.002f) {
            float tailGain = std::clamp(totalEnergy / 0.002f, 0.0f, 1.0f);
            // Apply sqrt curve to preserve relative dynamics at micro-levels
            tailGain = std::sqrt(tailGain);
            kickEnv *= tailGain;
            snareEnv *= tailGain;
            vocalEnv *= tailGain;
            bodyEnv *= tailGain;
        }
        
        // Scale to 0-255
        float scale = 255.0f * userAmp * thermalGain;
        pushSemanticFrame(kickEnv * scale, snareEnv * scale, vocalEnv * scale, bodyEnv * scale);
        
        // Legacy single-channel state tracking (for Kotlin fallback if needed)
        float composite = kickEnv + snareEnv + vocalEnv + bodyEnv;
        lastComposedAmp_ = std::clamp(composite * scale, 0.0f, 255.0f);
        pushHapticSample(lastComposedAmp_);
        lastBeatLayer_ = kickEnv;
        lastBassLayer_ = bodyEnv;
        lastMelodyLayer_ = vocalEnv;
    }
    void pushHapticSample(float amplitude) {
        int writeIdx = hapticWriteIdx_.load(std::memory_order_relaxed);
        int count = hapticCount_.load(std::memory_order_relaxed);
        if (count < HAPTIC_BUF_SIZE) {
            hapticBuf_[writeIdx] = amplitude;
            hapticWriteIdx_.store((writeIdx + 1) % HAPTIC_BUF_SIZE, std::memory_order_relaxed);
            hapticCount_.store(count + 1, std::memory_order_relaxed);
        } else {
            // Buffer full — overwrite oldest (drop-read)
            hapticBuf_[writeIdx] = amplitude;
            hapticWriteIdx_.store((writeIdx + 1) % HAPTIC_BUF_SIZE, std::memory_order_relaxed);
            int readIdx = hapticReadIdx_.load(std::memory_order_relaxed);
            hapticReadIdx_.store((readIdx + 1) % HAPTIC_BUF_SIZE, std::memory_order_relaxed);
        }
    }

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

    void clearHapticBuffer() {
        hapticWriteIdx_.store(0, std::memory_order_relaxed);
        hapticReadIdx_.store(0, std::memory_order_relaxed);
        hapticCount_.store(0, std::memory_order_relaxed);
        beatEnvelope_ = 0.0f;
        onsetRefractoryCounter_ = 1000;
        bassSmoothed_ = 0.0f;
        melodySmoothed_ = 0.0f;
        std::memset(energyHistory_, 0, sizeof(energyHistory_));
        lastComposedAmp_ = 0.0f;
        lastComposite_ = 0.0f;
        frameCounter_ = 0;
        lastOnsetFrame_ = -1000;
        beatIntervalFrames_ = 0.0f;
        beatConfidence_ = 0.0f;
        onsetThisFrame_ = false;
        blocksSinceAudio_ = 1000;
        textureNoise_.reset();
    }

    float getLastComposedAmp() const { return lastComposedAmp_; }
    float getLastBeatLayer() const { return lastBeatLayer_; }
    float getLastBassLayer() const { return lastBassLayer_; }
    float getLastMelodyLayer() const { return lastMelodyLayer_; }
    float getLastComposite() const { return lastComposite_; }
    float getBeatEnvelope() const { return beatEnvelope_; }
    float getBeatIntervalMs() const { return beatIntervalFrames_ * (256.0f / sampleRate_) * 1000.0f; }
    float getBeatConfidence() const { return beatConfidence_; }

    int getSemanticFrames(SemanticHapticFrame* outFrames, int maxFrames) {
        std::lock_guard<std::mutex> lock(hapticBufferMutex_);
        int count = std::min(maxFrames, static_cast<int>(semanticHapticBuffer_.size()));
        if (count > 0) {
            std::memcpy(outFrames, semanticHapticBuffer_.data(), count * sizeof(SemanticHapticFrame));
            semanticHapticBuffer_.erase(semanticHapticBuffer_.begin(), semanticHapticBuffer_.begin() + count);
        }
        return count;
    }
};

} // namespace haptic