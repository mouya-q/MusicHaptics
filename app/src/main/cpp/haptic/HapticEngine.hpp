#pragma once

#include <arm_neon.h>
#include <array>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <atomic>
#include <android/log.h>
#define HMS_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "HapticDSPCore", __VA_ARGS__)
#define HMS_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "HapticDSPCore", __VA_ARGS__)

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
    // Per-band onset strength for discrete event-driven haptics
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
        const int write = semanticWriteIdx_.load(std::memory_order_relaxed);
        const int next = (write + 1) % SEMANTIC_BUF_SIZE;
        const int read = semanticReadIdx_.load(std::memory_order_acquire);
        if (next == read) return;
        semanticHapticBuffer_[write] = {kick, snare, vocal, body};
        semanticWriteIdx_.store(next, std::memory_order_release);
    }

    std::atomic<float> sampleRate_{48000.0f};
    std::atomic<float> pendingSampleRate_{48000.0f};
    std::atomic<float> pendingLowCutoff_{60.0f};
    std::atomic<float> pendingHighCutoff_{200.0f};
    std::atomic<unsigned> pendingConfigRevision_{1};
    unsigned appliedConfigRevision_ = 0;
    std::atomic<bool> clearRequested_{false};
    std::atomic<float> userAmplitude_{2.0f};
    std::atomic<int> currentPresetId_{0};

    // Profile-specific DSP controls. These are deliberately kept separate from
    // user amplitude/preset state so a device profile can tune detection without
    // changing the user's loudness preference.
    std::atomic<float> profileDspFloor_{0.0040f};
    std::atomic<float> profileSubMult_{1.80f};
    std::atomic<float> profileKickMult_{0.80f};
    std::atomic<float> profileSnareMult_{0.70f};
    std::atomic<float> profileTickMult_{0.40f};
    std::atomic<float> profileBodyMult_{1.20f};
    std::atomic<float> profileRefractoryScale_{1.00f};
    std::atomic<float> profileMinIntervalMs_{52.0f};
    std::atomic<float> styleOnsetThreshold_{0.08f};

    LinkwitzRiley4th subLowPass_;
    LinkwitzRiley4th midHighPass_, midLowPass_;
    LinkwitzRiley4th textureHighPass_;

    // v3.8 semantic filter bank.  These are real independently filtered
    // analysis bands; unlike the old Kotlin pseudo-spectrum they retain the
    // different envelopes needed to distinguish percussion, voice and harmony.
    LinkwitzRiley4th kickHp_, kickLp_;       // 35..150 Hz — kick/transient focus
    LinkwitzRiley4th bassHp_, bassLp_;       // 70..190 Hz — bass body
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
    float vocalBandRms_ = 0.0f;  // vocal band energy for instrument-aware composition

    // Adaptive onset normalization. Absolute PCM levels vary substantially
    // between players, mixers and volumes; event detection is therefore
    // based on local change ratios as well as the raw band flux.
    float bassFluxEma_ = 0.0005f;
    float lowMidFluxEma_ = 0.0005f;
    float vocalFluxEma_ = 0.0005f;
    float presenceFluxEma_ = 0.0005f;
    float inputLevelEma_ = 0.02f;
    float inputPeakEma_ = 0.05f;
    float prevPitch_ = 0.0f;
    int pitchUpdateCounter_ = 0;
    float pitchConfidence_ = 0.0f;

    // Lightweight 512-point radix-2 spectrum. Used for spectral flux and
    // band-specific transient weighting; all buffers are fixed-size.
    static constexpr int FFT_SIZE = 512;
    static constexpr int FFT_BINS = FFT_SIZE / 2 + 1;
    float spectrumHistory_[FFT_SIZE] = {};
    float fftRe_[FFT_SIZE] = {};
    float fftIm_[FFT_SIZE] = {};
    float previousMagnitude_[FFT_BINS] = {};
    int spectrumSamples_ = 0;
    float spectralFlux_ = 0.0f;
    float bassSpectralFlux_ = 0.0f;
    float highSpectralFlux_ = 0.0f;
    float spectralCentroidHz_ = 0.0f;
    float kickProbability_ = 0.0f;
    float snareProbability_ = 0.0f;
    float hatProbability_ = 0.0f;
    float vocalProbability_ = 0.0f;
    float pluckedProbability_ = 0.0f;
    float harmonicProbability_ = 0.0f;
    float bassSustainProbability_ = 0.0f;

    SemanticHapticFrame historyBuffer_[2048] = {};

    // 4x-decimated history for pitch autocorrelation (replaces the old
    // full-rate 2048-sample history; see estimatePitch for the rationale).
    static constexpr int PITCH_DECIM = 4;
    static constexpr int PITCH_HIST = 2048 / PITCH_DECIM; // 512
    float pitchDecimHistory_[PITCH_HIST] = {};

    // Single-producer/single-consumer semantic ring. Audio processing writes;
    // the native scheduler is the sole reader while the Kotlin path is used only
    // when native scheduling is unavailable. No heap allocation or mutex in DSP.
    static constexpr int SEMANTIC_BUF_SIZE = 2048;
    SemanticHapticFrame semanticHapticBuffer_[SEMANTIC_BUF_SIZE] = {};
    std::atomic<int> semanticWriteIdx_{0};
    std::atomic<int> semanticReadIdx_{0};

public:
    // Onset ring buffer for event-driven haptics
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

    void pushOnsetFrame(float kick, float snare, float vocal, float body) {
        const int write = onsetWriteIdx_.load(std::memory_order_relaxed);
        const int next = (write + 1) % ONSET_BUF_SIZE;
        const int read = onsetReadIdx_.load(std::memory_order_acquire);
        if (next == read) return; // drop newest; keep event order intact
        onsetBuf_[write] = {kick, snare, vocal, body};
        onsetWriteIdx_.store(next, std::memory_order_release);
    }

    int getOnsetFrames(OnsetFrame* outFrames, int maxFrames) {
        if (!outFrames || maxFrames <= 0) return 0;
        int read = onsetReadIdx_.load(std::memory_order_relaxed);
        const int write = onsetWriteIdx_.load(std::memory_order_acquire);
        int count = 0;
        while (read != write && count < maxFrames) {
            outFrames[count++] = onsetBuf_[read];
            read = (read + 1) % ONSET_BUF_SIZE;
        }
        onsetReadIdx_.store(read, std::memory_order_release);
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

    // Beat sustain hold — REMOVED in v3.11
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

    float lastComposedAmp_ = 0.0f;
    float lastComposite_ = 0.0f;
    float lastBeatLayer_ = 0.0f;
    float lastBassLayer_ = 0.0f;
    float lastMelodyLayer_ = 0.0f;

    ValueNoise1D textureNoise_;

    // Track whether we've seen audio recently (for floor)
    int blocksSinceAudio_ = 1000;

    // Onset detector state (per-band spectral flux + energy diff)
    float prevBassRms_ = 0.0f;
    float prevVocalRms_ = 0.0f;
    int onsetRefractoryFrames_[4] = {0, 0, 0, 0}; // kick, snare, vocal, body
    static constexpr int ONSET_REFRACTORY_FRAMES = 2; // Base frame count; profile scale adjusts it at runtime

public:
    HapticEngine() {
        configure(48000.0f, 60.0f, 200.0f, 2.0f, 0);
    }

    void configure(float sampleRate, float lowCutoff, float highCutoff, float amplitude, int presetId) {
        pendingSampleRate_.store(sampleRate, std::memory_order_relaxed);
        pendingLowCutoff_.store(lowCutoff, std::memory_order_relaxed);
        pendingHighCutoff_.store(highCutoff, std::memory_order_relaxed);
        userAmplitude_.store(amplitude, std::memory_order_relaxed);
        currentPresetId_.store(presetId, std::memory_order_relaxed);
        pendingConfigRevision_.fetch_add(1, std::memory_order_release);
    }

    void configureProfile(float dspFloor, float subMult, float kickMult, float snareMult,
                          float tickMult, float bodyMult, float refractoryScale, float minIntervalMs) {
        profileDspFloor_.store(std::clamp(dspFloor, 0.0010f, 0.0200f), std::memory_order_relaxed);
        profileSubMult_.store(std::clamp(subMult, 0.50f, 3.00f), std::memory_order_relaxed);
        profileKickMult_.store(std::clamp(kickMult, 0.20f, 2.00f), std::memory_order_relaxed);
        profileSnareMult_.store(std::clamp(snareMult, 0.20f, 2.00f), std::memory_order_relaxed);
        profileTickMult_.store(std::clamp(tickMult, 0.20f, 2.00f), std::memory_order_relaxed);
        profileBodyMult_.store(std::clamp(bodyMult, 0.20f, 2.00f), std::memory_order_relaxed);
        profileRefractoryScale_.store(std::clamp(refractoryScale, 0.50f, 2.00f), std::memory_order_relaxed);
        profileMinIntervalMs_.store(std::clamp(minIntervalMs, 28.0f, 84.0f), std::memory_order_relaxed);
        HMS_LOGI("[DSP-PROFILE] floor=%.5f sub=%.3f kick=%.3f snare=%.3f tick=%.3f body=%.3f refractory=%.2f minInterval=%.1fms",
                  dspFloor, subMult, kickMult, snareMult, tickMult, bodyMult, refractoryScale, minIntervalMs);
    }

    void configureStyle(float onsetThreshold) {
        styleOnsetThreshold_.store(std::clamp(onsetThreshold, 0.035f, 0.220f), std::memory_order_relaxed);
    }

private:
    void applyPendingConfig() {
        const unsigned revision = pendingConfigRevision_.load(std::memory_order_acquire);
        if (revision == appliedConfigRevision_) return;

        const float sampleRate = std::clamp(pendingSampleRate_.load(std::memory_order_relaxed), 8000.0f, 384000.0f);
        const float nyquistGuard = sampleRate * 0.45f;
        const float lowCut = std::clamp(pendingLowCutoff_.load(std::memory_order_relaxed), 10.0f, nyquistGuard);
        const float highCut = std::clamp(pendingHighCutoff_.load(std::memory_order_relaxed), 20.0f, nyquistGuard);

        sampleRate_.store(sampleRate, std::memory_order_release);
        subLowPass_.reset();
        midHighPass_.reset();
        midLowPass_.reset();
        textureHighPass_.reset();
        kickHp_.reset();
        kickLp_.reset();
        bassHp_.reset();
        bassLp_.reset();
        lowMidHp_.reset();
        lowMidLp_.reset();
        vocalHp_.reset();
        vocalLp_.reset();
        presenceHp_.reset();
        presenceLp_.reset();
        airHp_.reset();
        subLowPass_.setLowPass(sampleRate, lowCut);
        midHighPass_.setHighPass(sampleRate, lowCut);
        midLowPass_.setLowPass(sampleRate, std::max(highCut, lowCut + 10.0f));
        textureHighPass_.setHighPass(sampleRate, std::max(highCut, lowCut + 10.0f));

        // Keep the analysis bank aligned with perceptual instrument regions.
        // The lower kick window is intentionally wider than the old 80 Hz floor
        // so deep electronic kicks are not mistaken for generic background bass.
        kickHp_.setHighPass(sampleRate, 35.0f);
        kickLp_.setLowPass(sampleRate, 150.0f);
        bassHp_.setHighPass(sampleRate, 70.0f);
        bassLp_.setLowPass(sampleRate, 190.0f);
        lowMidHp_.setHighPass(sampleRate, 150.0f);
        lowMidLp_.setLowPass(sampleRate, 650.0f);
        vocalHp_.setHighPass(sampleRate, 250.0f);
        vocalLp_.setLowPass(sampleRate, 3200.0f);
        presenceHp_.setHighPass(sampleRate, 2500.0f);
        presenceLp_.setLowPass(sampleRate, 7500.0f);
        airHp_.setHighPass(sampleRate, 7500.0f);
        appliedConfigRevision_ = revision;
    }

    void clearAnalysisState() {
        beatEnvelope_ = 0.0f;
        onsetRefractoryCounter_ = 1000;
        bassSmoothed_ = 0.0f;
        melodySmoothed_ = 0.0f;
        energyHistoryIdx_ = 0;
        std::memset(energyHistory_, 0, sizeof(energyHistory_));
        std::memset(previousMagnitude_, 0, sizeof(previousMagnitude_));
        std::memset(spectrumHistory_, 0, sizeof(spectrumHistory_));
        std::memset(pitchDecimHistory_, 0, sizeof(pitchDecimHistory_));
        frameCounter_ = 0;
        lastOnsetFrame_ = -1000;
        beatIntervalFrames_ = 0.0f;
        beatConfidence_ = 0.0f;
        onsetThisFrame_ = false;
        blocksSinceAudio_ = 1000;
        textureNoise_.reset();
        spectrumSamples_ = 0;
        pitchUpdateCounter_ = 0;
        spectralFlux_ = bassSpectralFlux_ = highSpectralFlux_ = spectralCentroidHz_ = 0.0f;
        kickProbability_ = snareProbability_ = hatProbability_ = 0.0f;
        vocalProbability_ = pluckedProbability_ = harmonicProbability_ = 0.0f;
        bassSustainProbability_ = pitchConfidence_ = 0.0f;
        vocalEnvelope_ = harmonicEnvelope_ = smoothedAmp_ = 0.0f;
        prevSubRms_ = prevLowMidRms_ = prevPresenceRms_ = prevAirRms_ = prevVocalRms_ = 0.0f;
        bassFluxEma_ = lowMidFluxEma_ = vocalFluxEma_ = presenceFluxEma_ = 0.0005f;
        inputLevelEma_ = 0.02f;
        inputPeakEma_ = 0.05f;
        prevPitch_ = 0.0f;
        coilTemp_ = 25.0f;
        magnetTemp_ = 25.0f;
        lastComposedAmp_ = 0.0f;
        lastComposite_ = 0.0f;
        lastBeatLayer_ = 0.0f;
        lastBassLayer_ = 0.0f;
        lastMelodyLayer_ = 0.0f;
    }

public:
    void clearHapticBuffer() {
        semanticReadIdx_.store(semanticWriteIdx_.load(std::memory_order_acquire), std::memory_order_release);
        onsetReadIdx_.store(onsetWriteIdx_.load(std::memory_order_acquire), std::memory_order_release);
        clearRequested_.store(true, std::memory_order_release);
    }

private:

    // Portable horizontal sum of a float32x4_t.
    // vaddvq_f32 is aarch64-only; on armeabi-v7a use pairwise add (vpadd).
    static inline float neonReduceF32(float32x4_t v) {
#if defined(__aarch64__)
        return vaddvq_f32(v);
#else
        float32x2_t s = vadd_f32(vget_low_f32(v), vget_high_f32(v));
        s = vpadd_f32(s, s);
        return vget_lane_f32(s, 0);
#endif
    }

    static float computeRmsNeon(const float* buffer, int size) {
        if (size <= 0) return 0.0f;
        int i = 0;
        float32x4_t vSum = vdupq_n_f32(0.0f);
        for (; i <= size - 4; i += 4) {
            float32x4_t vIn = vld1q_f32(buffer + i);
            vSum = vmlaq_f32(vSum, vIn, vIn);
        }
        float sum = neonReduceF32(vSum);
        for (; i < size; ++i) {
            sum += buffer[i] * buffer[i];
        }
        float rms = std::sqrt(sum / static_cast<float>(size));
        return std::isnan(rms) ? 0.0f : rms;
    }

    void updateSpectrum(const float* signal, int size) {
        if (!signal || size <= 0) return;
        size = std::min(size, FFT_SIZE);
        if (size < FFT_SIZE) {
            std::memmove(spectrumHistory_, spectrumHistory_ + size, (FFT_SIZE - size) * sizeof(float));
        }
        std::memcpy(spectrumHistory_ + (FFT_SIZE - size), signal, size * sizeof(float));
        spectrumSamples_ = std::min(FFT_SIZE, spectrumSamples_ + size);
        if (spectrumSamples_ < FFT_SIZE) return;

        // Hann window, computed exactly once (was 512 cosf() calls per block).
        static const std::array<float, FFT_SIZE> kHannWindow = [] {
            std::array<float, FFT_SIZE> w{};
            const float step = 2.0f * static_cast<float>(M_PI) / static_cast<float>(FFT_SIZE);
            for (int i = 0; i < FFT_SIZE; ++i) {
                w[i] = 0.5f - 0.5f * std::cos(step * static_cast<float>(i));
            }
            return w;
        }();
        for (int i = 0; i < FFT_SIZE; ++i) {
            fftRe_[i] = spectrumHistory_[i] * kHannWindow[i];
            fftIm_[i] = 0.0f;
        }
        for (int i = 1, j = 0; i < FFT_SIZE; ++i) {
            int bit = FFT_SIZE >> 1;
            for (; j & bit; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                std::swap(fftRe_[i], fftRe_[j]);
                std::swap(fftIm_[i], fftIm_[j]);
            }
        }
        for (int len = 2; len <= FFT_SIZE; len <<= 1) {
            const float angle = -2.0f * static_cast<float>(M_PI) / static_cast<float>(len);
            const float wLenRe = cosf(angle);
            const float wLenIm = sinf(angle);
            for (int i = 0; i < FFT_SIZE; i += len) {
                float wRe = 1.0f, wIm = 0.0f;
                const int half = len >> 1;
                for (int j = 0; j < half; ++j) {
                    const int even = i + j;
                    const int odd = even + half;
                    const float tRe = wRe * fftRe_[odd] - wIm * fftIm_[odd];
                    const float tIm = wRe * fftIm_[odd] + wIm * fftRe_[odd];
                    const float uRe = fftRe_[even];
                    const float uIm = fftIm_[even];
                    fftRe_[even] = uRe + tRe;
                    fftIm_[even] = uIm + tIm;
                    fftRe_[odd] = uRe - tRe;
                    fftIm_[odd] = uIm - tIm;
                    const float nextWRe = wRe * wLenRe - wIm * wLenIm;
                    wIm = wRe * wLenIm + wIm * wLenRe;
                    wRe = nextWRe;
                }
            }
        }

        const float binHz = sampleRate_.load(std::memory_order_relaxed) / static_cast<float>(FFT_SIZE);
        float total = 1.0e-9f;
        float flux = 0.0f, bassFlux = 0.0f, highFlux = 0.0f, weightedHz = 0.0f;
        for (int k = 0; k < FFT_BINS; ++k) {
            const float mag = sqrtf(fftRe_[k] * fftRe_[k] + fftIm_[k] * fftIm_[k]);
            const float positiveDelta = std::max(0.0f, mag - previousMagnitude_[k]);
            previousMagnitude_[k] = mag;
            total += mag;
            flux += positiveDelta;
            const float freq = static_cast<float>(k) * binHz;
            weightedHz += mag * freq;
            if (freq >= 35.0f && freq <= 180.0f) bassFlux += positiveDelta;
            if (freq >= 2500.0f && freq <= 12000.0f) highFlux += positiveDelta;
        }
        spectralFlux_ = std::clamp((flux / total) * 7.5f, 0.0f, 1.0f);
        bassSpectralFlux_ = std::clamp((bassFlux / total) * 12.0f, 0.0f, 1.0f);
        highSpectralFlux_ = std::clamp((highFlux / total) * 10.0f, 0.0f, 1.0f);
        spectralCentroidHz_ = std::clamp(weightedHz / total, 0.0f, sampleRate_.load(std::memory_order_relaxed) * 0.5f);
    }

    // ── Pitch estimation: 4x-decimated autocorrelation ──
    // The old full-rate sweep ran lags 160..1371 (48 kHz) over a 256-sample
    // window — ~310K multiply-accumulates per call, the single most expensive
    // DSP operation in the engine. Pitch only targets 35..300 Hz, far below
    // the 6 kHz Nyquist of a 4x-decimated 12 kHz signal, so we box-decimate
    // first (the box average doubles as an anti-alias low-pass) and sweep
    // lags 40..343 over a 64-sample window instead: ~16x less work for
    // effectively identical pitch accuracy in the target band.
    float estimatePitch(const float* signal, int size) {
        const int decim = size / PITCH_DECIM;
        if (decim <= 0) return 150.0f;

        std::memmove(pitchDecimHistory_, pitchDecimHistory_ + decim,
                     (PITCH_HIST - decim) * sizeof(float));
        float* dst = pitchDecimHistory_ + (PITCH_HIST - decim);
        for (int i = 0; i < decim; ++i) {
            const float* p = signal + i * PITCH_DECIM;
            dst[i] = (p[0] + p[1] + p[2] + p[3]) * 0.25f;
        }

        // Silence gate on the fresh block: skip the sweep when there is
        // nothing to track (saves the full lag scan during quiet passages).
        float absSum = 0.0f;
        for (int i = 0; i < decim; ++i) absSum += std::fabs(dst[i]);
        if (absSum / static_cast<float>(decim) < 0.001f) return 150.0f;

        const float decimatedRate = sampleRate_.load(std::memory_order_relaxed)
                                    / static_cast<float>(PITCH_DECIM);
        int minLag = static_cast<int>(decimatedRate / 300.0f);
        int maxLag = static_cast<int>(decimatedRate / 35.0f);
        const int maxLagCap = PITCH_HIST - decim - 1;
        if (maxLag > maxLagCap) maxLag = maxLagCap;
        if (minLag < 2) minLag = 2;

        int bestLag = -1;
        float maxCorr = 1.0e-4f; // doubles as the "no periodicity" threshold
        const float* base = pitchDecimHistory_ + (PITCH_HIST - decim);

        for (int lag = minLag; lag <= maxLag; ++lag) {
            const float* lagged = base - lag;
            int i = 0;
            float32x4_t vSum = vdupq_n_f32(0.0f);
            for (; i <= decim - 4; i += 4) {
                float32x4_t vA = vld1q_f32(base + i);
                float32x4_t vB = vld1q_f32(lagged + i);
                vSum = vmlaq_f32(vSum, vA, vB);
            }
            float corr = neonReduceF32(vSum);
            for (; i < decim; ++i) {
                corr += base[i] * lagged[i];
            }
            if (corr > maxCorr) {
                maxCorr = corr;
                bestLag = lag;
            }
        }

        if (bestLag <= 0) return 150.0f;
        float freq = decimatedRate / static_cast<float>(bestLag);
        return std::clamp(freq, 35.0f, 300.0f);
    }

public:
    // ══════════════════════════════════════════════
    //  Main audio processing block
    // ══════════════════════════════════════════════
    void processAudioBlock(const float* input, int size, float* outTelemetry) {
        if (size > 256) size = 256;
        if (!input || !outTelemetry || size <= 0) return;
        applyPendingConfig();
        if (clearRequested_.exchange(false, std::memory_order_acq_rel)) {
            clearAnalysisState();
        }

        onsetThisFrame_ = false;
        blocksSinceAudio_ = 0;

        static int s_dbgCounter = 0;
        bool dbgThisFrame = (s_dbgCounter % 200 == 0); // log every ~1 second
        s_dbgCounter++;

        // 1. Legacy crossover plus v3.8 real semantic filter bank.
        float kickSq = 0.0f, bassSq = 0.0f, lowMidSq = 0.0f, vocalSq = 0.0f;
        float presenceSq = 0.0f, airSq = 0.0f, absSum = 0.0f, sumSq = 0.0f, peak = 0.0f;
        int zeroCrossings = 0;
        float previousSample = input[0];
        for (int i = 0; i < size; ++i) {
            float s = input[i];
            subOutput_[i] = subLowPass_.process(s);
            float midTemp = midHighPass_.process(s);
            midOutput_[i] = midLowPass_.process(midTemp);
            textureOutput_[i] = textureHighPass_.process(s);

            float kick = kickLp_.process(kickHp_.process(s));
            float bass = bassLp_.process(bassHp_.process(s));
            float lowMid = lowMidLp_.process(lowMidHp_.process(s));
            float vocal = vocalLp_.process(vocalHp_.process(s));
            float presence = presenceLp_.process(presenceHp_.process(s));
            float air = airHp_.process(s);
            kickSq += kick * kick;
            bassSq += bass * bass;
            lowMidSq += lowMid * lowMid;
            vocalSq += vocal * vocal;
            presenceSq += presence * presence;
            airSq += air * air;
            const float absS = std::abs(s);
            absSum += absS;
            sumSq += s * s;
            peak = std::max(peak, absS);
            if ((s >= 0.0f) != (previousSample >= 0.0f)) zeroCrossings++;
            previousSample = s;
        }

        // 2. RMS and shape descriptors.
        float subRms = computeRmsNeon(subOutput_, size);
        float midRms = computeRmsNeon(midOutput_, size);
        float textureRms = computeRmsNeon(textureOutput_, size);
        // Lowered cap from 0.15f to 0.05f to avoid "一直震" (constant vibration).
        // texture channel contains most music content (200Hz+), so it's always high.
        // It should only add micro-texture, not drive volume envelope.
        textureRms = std::min(textureRms, 0.05f);
        const float subProfileGain = std::clamp(profileSubMult_.load(std::memory_order_relaxed), 0.75f, 1.75f);
        subRms *= subProfileGain;
        const float invSize = 1.0f / static_cast<float>(size);
        float kickBand = std::sqrt(kickSq * invSize);
        float bassBand = std::sqrt(bassSq * invSize);
        float lowMidBand = std::sqrt(lowMidSq * invSize);
        float vocalBand = std::sqrt(vocalSq * invSize);
        vocalBandRms_ = vocalBand;
        float presenceBand = std::sqrt(presenceSq * invSize);
        float airBand = std::sqrt(airSq * invSize);
        const float rawInputRms = std::sqrt(sumSq * invSize);
        const float crestFactor = peak / (rawInputRms + 1.0e-4f);
        inputLevelEma_ += 0.025f * (rawInputRms - inputLevelEma_);
        inputPeakEma_ += 0.025f * (peak - inputPeakEma_);
        // Soft loudness normalization used only for feature extraction. It keeps
        // quiet player mixes from starving onset detection without making loud
        // mixes artificially dominate.
        const float featureGain = std::clamp(0.070f / (inputLevelEma_ + 0.012f), 0.70f, 1.65f);
        kickBand *= featureGain;
        bassBand *= featureGain;
        lowMidBand *= featureGain;
        vocalBand *= featureGain;
        presenceBand *= featureGain;
        airBand *= featureGain;
        subRms *= std::clamp(featureGain, 0.78f, 1.45f);
        float zcr = static_cast<float>(zeroCrossings) * invSize;
        updateSpectrum(input, size);
        float totalBand = bassBand + lowMidBand + vocalBand + presenceBand + airBand + 1.0e-6f;
        float highRatio = (presenceBand + airBand) / totalBand;
        float vocalRatio = vocalBand / totalBand;
        float bassRatio = (subRms + bassBand) / (totalBand + subRms);
        float lowMidFlux = std::max(0.0f, lowMidBand - prevLowMidRms_);
        float presenceFlux = std::max(0.0f, presenceBand - prevPresenceRms_);
        float airFlux = std::max(0.0f, airBand - prevAirRms_);

        // 3. Pitch and periodicity. Autocorrelation is intentionally throttled;
        // spectral analysis runs every block while pitch updates every 8 blocks.
        float pitch = prevPitch_;
        if (pitchUpdateCounter_++ % 8 == 0) pitch = estimatePitch(input, size);
        if (pitch <= 0.0f) pitch = 150.0f;
        float pitchDelta = prevPitch_ > 0.0f ? std::abs(pitch - prevPitch_) / std::max(prevPitch_, 1.0f) : 1.0f;
        pitchConfidence_ += 0.18f * (((pitch >= 70.0f && pitch <= 300.0f) && pitchDelta < 0.18f ? 1.0f : 0.0f) - pitchConfidence_);

        // Adaptive transients: compare each band against its own recent flux floor.
        // This is much less sensitive to player volume and avoids treating a loud
        // sustained note as a new hit on every audio block.
        const float bassFlux = std::max(0.0f, bassBand - prevBassRms_);
        const float lowMidFlux2 = std::max(0.0f, lowMidBand - prevLowMidRms_);
        const float vocalFlux = std::max(0.0f, vocalBand - prevVocalRms_);
        const float presenceFlux2 = std::max(0.0f, presenceBand - prevPresenceRms_);
        const float airFlux2 = std::max(0.0f, airBand - prevAirRms_);
        const float fluxFloor = std::max(profileDspFloor_.load(std::memory_order_relaxed) * 0.35f, 0.00035f);
        bassFluxEma_ += 0.055f * (bassFlux - bassFluxEma_);
        lowMidFluxEma_ += 0.055f * (lowMidFlux2 - lowMidFluxEma_);
        vocalFluxEma_ += 0.045f * (vocalFlux - vocalFluxEma_);
        presenceFluxEma_ += 0.055f * (presenceFlux2 - presenceFluxEma_);

        const float bassFluxRatio = bassFlux / (bassFluxEma_ + fluxFloor);
        const float lowMidFluxRatio = lowMidFlux2 / (lowMidFluxEma_ + fluxFloor);
        const float vocalFluxRatio = vocalFlux / (vocalFluxEma_ + fluxFloor);
        const float presenceFluxRatio = presenceFlux2 / (presenceFluxEma_ + fluxFloor);
        const float kickRiseRatio = std::max(0.0f, subRms - prevSubRms_) / (prevSubRms_ + profileDspFloor_.load(std::memory_order_relaxed));

        const float transientCrest = std::clamp((crestFactor - 2.2f) / 5.0f, 0.0f, 1.0f);
        float kickTarget = std::clamp(
            0.48f * std::clamp((bassFluxRatio - 1.12f) * 0.58f, 0.0f, 1.0f)
            + 0.28f * std::clamp((kickRiseRatio - 0.22f) * 0.62f, 0.0f, 1.0f)
            + 0.18f * bassSpectralFlux_
            + 0.14f * transientCrest
            - highRatio * 0.14f,
            0.0f, 1.0f);
        float snareTarget = std::clamp(
            0.46f * std::clamp((lowMidFluxRatio - 1.13f) * 0.64f, 0.0f, 1.0f)
            + 0.32f * std::clamp((presenceFluxRatio - 1.07f) * 0.72f, 0.0f, 1.0f)
            + 0.18f * highSpectralFlux_
            + 0.10f * transientCrest
            - bassRatio * 0.15f,
            0.0f, 1.0f);
        float hatTarget = std::clamp(
            0.62f * highSpectralFlux_ * profileTickMult_.load(std::memory_order_relaxed)
            + 0.24f * std::clamp((presenceFluxRatio - 1.15f) * 0.65f, 0.0f, 1.0f)
            + 0.14f * std::clamp(zcr * 2.0f, 0.0f, 1.0f)
            - bassRatio * 0.22f,
            0.0f, 1.0f);
        float vocalTarget = std::clamp(
            vocalRatio * 0.78f
            + pitchConfidence_ * 0.28f
            + 0.24f * std::clamp((vocalFluxRatio - 1.20f) * 0.50f, 0.0f, 1.0f)
            - highRatio * 0.22f
            - std::max(kickTarget, snareTarget) * 0.45f,
            0.0f, 1.0f);
        float pluckedTarget = std::clamp(
            0.48f * std::clamp((lowMidFluxRatio - 1.18f) * 0.58f, 0.0f, 1.0f)
            + 0.26f * std::clamp((presenceFluxRatio - 1.12f) * 0.55f, 0.0f, 1.0f)
            + pitchConfidence_ * 0.28f
            - vocalTarget * 0.22f, 0.0f, 1.0f);
        float harmonicTarget = std::clamp(
            pitchConfidence_ * 0.55f
            + (lowMidBand + vocalBand) / totalBand * 0.48f
            - std::max({kickTarget, snareTarget, hatTarget}) * 0.24f,
            0.0f, 1.0f);
        float bassSustainTarget = std::clamp(
            0.42f * std::clamp(kickBand * 2.4f, 0.0f, 1.0f)
            + 0.58f * std::clamp((subRms - profileDspFloor_.load(std::memory_order_relaxed) * 5.0f) * 5.0f, 0.0f, 1.0f),
            0.0f, 1.0f);

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

        // Onset detection uses the per-band attack differentials above. Keeping the
        // same features for probability and event detection prevents the detector from
        // disagreeing with itself at quiet/loud playback levels.

        // Refractory counters (decrement each frame)
        for (int i = 0; i < 4; ++i) {
            if (onsetRefractoryFrames_[i] > 0) onsetRefractoryFrames_[i]--;
        }

        // ═══ v4.23: TRANSIENT-ONLY onset detection ═══
        // Key insight: onset = ATTACK (transient change), NOT LEVEL.
        // Only sudden energy changes should trigger — continuous content should NOT.
        // This matches Apple's Taptic Engine: sharp, short, event-driven.

        // Kick: use adaptive change ratios. A steady kick tail cannot retrigger.
        float kickOnset = 0.0f;
        if (onsetRefractoryFrames_[0] == 0) {
            const float kickShape = std::clamp(
                0.44f * std::clamp((bassFluxRatio - 1.10f) * 0.70f, 0.0f, 1.0f)
                + 0.32f * std::clamp((kickRiseRatio - 0.20f) * 0.60f, 0.0f, 1.0f)
                + 0.18f * bassSpectralFlux_
                + 0.14f * transientCrest,
                0.0f, 1.0f);
            kickOnset = kickShape * profileKickMult_.load(std::memory_order_relaxed);
            const float floorRatio = std::clamp(profileDspFloor_.load(std::memory_order_relaxed) / 0.0040f, 0.55f, 2.00f);
            const float styleThreshold = styleOnsetThreshold_.load(std::memory_order_relaxed);
            const float kickThreshold = std::clamp(styleThreshold * 3.50f * std::sqrt(floorRatio), 0.14f, 0.52f);
            if (kickOnset < kickThreshold) kickOnset = 0.0f;
            if (kickOnset > 0.0f) onsetRefractoryFrames_[0] = std::max(1, static_cast<int>(std::lround(ONSET_REFRACTORY_FRAMES * profileRefractoryScale_.load(std::memory_order_relaxed))));
        }

        // Snare / clap: low-mid attack plus upper transient, without level-based retriggers.
        float snareOnset = 0.0f;
        if (onsetRefractoryFrames_[1] == 0) {
            const float snareShape = std::clamp(
                0.46f * std::clamp((lowMidFluxRatio - 1.12f) * 0.68f, 0.0f, 1.0f)
                + 0.30f * std::clamp((presenceFluxRatio - 1.08f) * 0.72f, 0.0f, 1.0f)
                + 0.14f * highSpectralFlux_
                + 0.10f * transientCrest, 0.0f, 1.0f);
            snareOnset = snareShape * profileSnareMult_.load(std::memory_order_relaxed);
            const float floorRatio = std::clamp(profileDspFloor_.load(std::memory_order_relaxed) / 0.0040f, 0.55f, 2.00f);
            const float styleThreshold = styleOnsetThreshold_.load(std::memory_order_relaxed);
            const float snareThreshold = std::clamp(styleThreshold * 3.15f * std::sqrt(floorRatio), 0.13f, 0.48f);
            if (snareOnset < snareThreshold) snareOnset = 0.0f;
            if (snareOnset > 0.0f) onsetRefractoryFrames_[1] = std::max(1, static_cast<int>(std::lround(ONSET_REFRACTORY_FRAMES * profileRefractoryScale_.load(std::memory_order_relaxed))));
        }

        // Vocal: sparse accents only; sustained vowels stay in the background layer.
        float vocalOnset = 0.0f;
        if (onsetRefractoryFrames_[2] == 0) {
            float vocalStrength = std::clamp(
                0.58f * std::clamp((vocalFluxRatio - 1.18f) * 0.60f, 0.0f, 1.0f)
                + 0.28f * pitchConfidence_
                + 0.14f * std::clamp(vocalRatio * 1.8f, 0.0f, 1.0f), 0.0f, 1.0f);
            if (snareOnset > 0.30f) vocalStrength *= 0.30f;
            if (vocalStrength >= 0.42f) {
                vocalOnset = std::clamp((vocalStrength - 0.42f) * 0.75f, 0.0f, 0.35f);
            }
            if (vocalOnset > 0.0f) onsetRefractoryFrames_[2] = std::max(1, static_cast<int>(std::lround((ONSET_REFRACTORY_FRAMES + 2) * profileRefractoryScale_.load(std::memory_order_relaxed))));
        }

        // Body events are deliberately rare and softer than kick/snare events.
        float bodyOnset = 0.0f;
        if (onsetRefractoryFrames_[3] == 0) {
            const float bodyRise = std::clamp((kickBand - prevBassRms_) / (prevBassRms_ + profileDspFloor_.load(std::memory_order_relaxed) * 4.0f), 0.0f, 1.0f);
            const float bodyStrength = bodyRise * profileBodyMult_.load(std::memory_order_relaxed);
            if (bodyStrength >= 0.55f) bodyOnset = std::clamp((bodyStrength - 0.55f) * 0.32f, 0.0f, 0.18f);
            if (bodyOnset > 0.0f) onsetRefractoryFrames_[3] = std::max(1, static_cast<int>(std::lround((ONSET_REFRACTORY_FRAMES + 3) * profileRefractoryScale_.load(std::memory_order_relaxed))));
        }

        // DEBUG: Log band energies and onset values periodically
        if (dbgThisFrame) {
            HMS_LOGI("[DSP-DBG] bassBand=%.5f lowMid=%.5f vocal=%.5f subRms=%.5f | onset: KICK=%.3f SNARE=%.3f VOCAL=%.3f BODY=%.3f | refract=[%d %d %d %d]",
                bassBand, lowMidBand, vocalBand, subRms,
                kickOnset, snareOnset, vocalOnset, bodyOnset,
                onsetRefractoryFrames_[0], onsetRefractoryFrames_[1], onsetRefractoryFrames_[2], onsetRefractoryFrames_[3]);
        }

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
        float dt = static_cast<float>(size) / sampleRate_.load(std::memory_order_relaxed);
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
        composeHapticLayer(subRms, midRms, textureRms, pitch, thermalGain, amp, dt, kickOnset, snareOnset, vocalOnset, bodyOnset);

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
        // Semantic probabilities (heuristic confidence, 0..1)
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
        // Per-band onset strength for discrete event-driven haptics
        outTelemetry[20] = kickOnset;
        outTelemetry[21] = snareOnset;
        outTelemetry[22] = vocalOnset;
        outTelemetry[23] = bodyOnset;
        outTelemetry[24] = spectralFlux_;
        outTelemetry[25] = bassSpectralFlux_;
        outTelemetry[26] = highSpectralFlux_;
        outTelemetry[27] = spectralCentroidHz_;

        // Push to onset ring buffer for Kotlin event-driven consumption
        // Only push when at least one onset is non-zero (avoid filling buffer with zeros)
        if (kickOnset > 0.0f || snareOnset > 0.0f || vocalOnset > 0.0f || bodyOnset > 0.0f) {
            pushOnsetFrame(kickOnset, snareOnset, vocalOnset, bodyOnset);
        }

        prevSubRms_ = subRms;
        prevLowMidRms_ = lowMidBand;
        prevPresenceRms_ = presenceBand;
        prevAirRms_ = airBand;
        prevPitch_ = pitch;
        frameCounter_++;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Multi-Track Compose
    //  Instead of squashing everything into one amplitude, we render envelopes
    //  for each instrumental track separately.
    // ═════════════════════════════════════════════════════════════════
    void composeHapticLayer(float subRms, float midRms, float textureRms,
                              float pitch, float thermalGain, float userAmp, float dt,
                              float kickOnset, float snareOnset, float vocalOnset, float bodyOnset) {
        // The output is intentionally sparse: a good haptic track has hierarchy.
        // Kicks are the sharpest events, snares are shorter and lighter, vocal
        // energy is a soft bed, and body is present only when the music really
        // carries sub-bass.
        const float kickGain = std::clamp(profileKickMult_.load(std::memory_order_relaxed), 0.30f, 1.60f);
        const float snareGain = std::clamp(profileSnareMult_.load(std::memory_order_relaxed), 0.30f, 1.60f);
        const float bodyGain = std::clamp(profileBodyMult_.load(std::memory_order_relaxed), 0.30f, 1.80f);
        const float tickGain = std::clamp(profileTickMult_.load(std::memory_order_relaxed), 0.25f, 1.60f);

        float kickEnv = std::pow(std::clamp(kickOnset, 0.0f, 1.0f), 0.72f) * 0.95f * kickGain;
        float snareEnv = std::pow(std::clamp(snareOnset, 0.0f, 1.0f), 0.78f) * 0.72f * snareGain;

        // Vocal/body beds use slow envelopes and a hard ceiling so the device
        // never turns an entire chorus into a continuous buzz.
        const float vocalTarget = std::clamp(
            vocalBandRms_ * 2.0f * (0.42f + 0.58f * pitchConfidence_), 0.0f, 0.20f);
        vocalEnvelope_ += (vocalTarget - vocalEnvelope_) * (vocalTarget > vocalEnvelope_ ? 0.11f : 0.045f);
        float vocalEnv = vocalEnvelope_ * 0.34f;
        vocalEnv += std::pow(std::clamp(vocalOnset, 0.0f, 0.35f), 0.82f) * 0.20f;

        const float bodyTarget = std::clamp(
            std::max(0.0f, subRms - profileDspFloor_.load(std::memory_order_relaxed) * 4.5f)
            * 2.7f * (0.40f + 0.60f * bassSustainProbability_),
            0.0f, 0.32f);
        bassSmoothed_ += (bodyTarget - bassSmoothed_) * (bodyTarget > bassSmoothed_ ? 0.075f : 0.035f);
        float bodyEnv = bassSmoothed_ * 0.36f * bodyGain;
        bodyEnv += std::pow(std::clamp(bodyOnset, 0.0f, 0.18f) / 0.18f, 0.85f) * 0.08f;

        // High-frequency texture should never become a constant buzz. Use it as
        // a very low-level micro layer and only when there is actual transient
        // activity in the upper spectrum.
        const float textureActivity = std::clamp(highSpectralFlux_ * tickGain + hatProbability_ * 0.24f, 0.0f, 1.0f);
        float textureEnv = std::pow(textureRms / 0.05f, 0.70f) * textureActivity * 0.06f;

        // Global musical loudness follows a smoothed program level. This is softer
        // than sqrt(raw RMS) and preserves quiet-to-loud contrast.
        const float loudness = std::clamp(inputLevelEma_ * 9.5f, 0.0f, 1.0f);
        const float dynamicGate = std::clamp(0.26f + 0.74f * std::pow(loudness, 0.72f), 0.20f, 1.0f);
        kickEnv *= dynamicGate;
        snareEnv *= dynamicGate;
        vocalEnv *= dynamicGate;
        bodyEnv *= dynamicGate;
        textureEnv *= dynamicGate;

        // Avoid a flat wall of amplitude. If the body dominates, duck it under
        // a transient so the attack always remains perceptually separated.
        const float transientBus = std::max(kickEnv, snareEnv);
        if (transientBus > 0.30f) bodyEnv *= 0.55f;

        const float composite = std::clamp(
            kickEnv * 1.00f + snareEnv * 0.90f + bodyEnv * 0.72f + vocalEnv * 0.42f + textureEnv,
            0.0f, 1.20f);
        // Soft-knee output mapping preserves musical contrast instead of hard
        // saturating the semantic bus as soon as userAmp exceeds ~1.0x.
        const float master = std::max(0.30f, userAmp) * thermalGain;
        auto renderLevel = [master](float env) -> float {
            const float x = std::max(0.0f, env) * master;
            return std::clamp(255.0f * (1.0f - std::exp(-1.05f * x)), 0.0f, 255.0f);
        };
        const float kickOut = renderLevel(kickEnv);
        const float snareOut = renderLevel(snareEnv);
        const float vocalOut = renderLevel(vocalEnv);
        const float bodyOut = renderLevel(bodyEnv + textureEnv * 0.35f);

        pushSemanticFrame(kickOut, snareOut, vocalOut, bodyOut);

        lastComposite_ = composite;
        lastComposedAmp_ = renderLevel(composite);
        lastBeatLayer_ = kickOut;
        lastBassLayer_ = renderLevel(bodyEnv);
        lastMelodyLayer_ = vocalOut;
    }
    float getLastComposedAmp() const { return lastComposedAmp_; }
    float getLastBeatLayer() const { return lastBeatLayer_; }
    float getLastBassLayer() const { return lastBassLayer_; }
    float getLastMelodyLayer() const { return lastMelodyLayer_; }
    float getLastComposite() const { return lastComposite_; }
    float getBeatEnvelope() const { return beatEnvelope_; }
    float getBeatIntervalMs() const { return beatIntervalFrames_ * (256.0f / sampleRate_.load(std::memory_order_relaxed)) * 1000.0f; }
    float getBeatConfidence() const { return beatConfidence_; }
    float getProfileMinIntervalMs() const {
        return std::clamp(profileMinIntervalMs_.load(std::memory_order_relaxed), 28.0f, 84.0f);
    }

    int getSemanticFrames(SemanticHapticFrame* outFrames, int maxFrames) {
        if (!outFrames || maxFrames <= 0) return 0;
        int read = semanticReadIdx_.load(std::memory_order_relaxed);
        const int write = semanticWriteIdx_.load(std::memory_order_acquire);
        int count = 0;
        while (read != write && count < maxFrames) {
            outFrames[count++] = semanticHapticBuffer_[read];
            read = (read + 1) % SEMANTIC_BUF_SIZE;
        }
        semanticReadIdx_.store(read, std::memory_order_release);
        return count;
    }
};

} // namespace haptic