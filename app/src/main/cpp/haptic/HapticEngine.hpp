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





struct alignas(64) BiquadCoeffs {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
};

struct alignas(64) BiquadState {
    float x1 = 0.0f, x2 = 0.0f, y1 = 0.0f, y2 = 0.0f;
    void reset() { x1 = x2 = y1 = y2 = 0.0f; }
};


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








struct SemanticHapticFrame {
    float kickAmp;   
    float snareAmp;  
    float vocalAmp;  
    float bodyAmp;   
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
    
    float onsetKick;
    float onsetSnare;
    float onsetVocal;
    float onsetBody;
};













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

    
    
    
    std::atomic<float> profileDspFloor_{0.0040f};
    std::atomic<float> profileSubMult_{1.80f};
    std::atomic<float> profileKickMult_{0.80f};
    std::atomic<float> profileSnareMult_{0.70f};
    std::atomic<float> profileTickMult_{0.40f};
    std::atomic<float> profileBodyMult_{1.20f};
    std::atomic<float> profileRefractoryScale_{1.00f};
    std::atomic<float> outputStyleAmpScale_{1.0f};
    std::atomic<float> outputSharpness_{0.65f};
    std::atomic<float> outputAttackScale_{1.0f};
    std::atomic<float> outputAccentScale_{1.0f};
    std::atomic<float> outputBassBoost_{1.0f};
    std::atomic<float> outputImpactGain_{1.0f};
    std::atomic<float> outputContinuousGain_{1.0f};
    std::atomic<float> outputTextureGain_{1.0f};
    std::atomic<float> outputMasterGain_{1.0f};
    std::atomic<float> outputOnsetThreshold_{0.08f};
    std::atomic<float> outputAttackImpactMs_{8.0f};
    std::atomic<float> outputDecayImpactMs_{24.0f};
    std::atomic<float> outputAttackContinuousMs_{18.0f};
    std::atomic<float> outputDecayContinuousMs_{55.0f};
    std::atomic<float> outputReleaseMs_{70.0f};
    std::atomic<float> outputSustainLevel_{0.45f};
    std::atomic<float> outputLraF0_{165.0f};
    std::atomic<float> outputLraQ_{0.72f};

    LinkwitzRiley4th subLowPass_;
    LinkwitzRiley4th midHighPass_, midLowPass_;
    LinkwitzRiley4th textureHighPass_;

    
    
    
    LinkwitzRiley4th bassHp_, bassLp_;       
    LinkwitzRiley4th lowMidHp_, lowMidLp_;   
    LinkwitzRiley4th vocalHp_, vocalLp_;     
    LinkwitzRiley4th presenceHp_, presenceLp_; 
    LinkwitzRiley4th airHp_;                 

    alignas(64) float subOutput_[256];
    alignas(64) float midOutput_[256];
    alignas(64) float textureOutput_[256];

    float prevLowMidRms_ = 0.0f;
    float prevPresenceRms_ = 0.0f;
    float prevAirRms_ = 0.0f;
    float vocalBandRms_ = 0.0f;  
    float prevPitch_ = 0.0f;
    int pitchUpdateCounter_ = 0;
    float pitchConfidence_ = 0.0f;

    
    
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

    
    
    static constexpr int PITCH_DECIM = 4;
    static constexpr int PITCH_HIST = 2048 / PITCH_DECIM; 
    float pitchDecimHistory_[PITCH_HIST] = {};

    
    
    
    static constexpr int SEMANTIC_BUF_SIZE = 2048;
    SemanticHapticFrame semanticHapticBuffer_[SEMANTIC_BUF_SIZE] = {};
    std::atomic<int> semanticWriteIdx_{0};
    std::atomic<int> semanticReadIdx_{0};

public:
    
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
        if (next == read) return; 
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

    
    
    

    
    float bassSmoothed_ = 0.0f;

    
    float vocalEnvelope_ = 0.0f;
    float harmonicEnvelope_ = 0.0f;
    float smoothedAmp_ = 0.0f;  

    
    float melodySmoothed_ = 0.0f;

    float lastComposedAmp_ = 0.0f;
    float lastComposite_ = 0.0f;
    float lastBeatLayer_ = 0.0f;
    float lastBassLayer_ = 0.0f;
    float lastMelodyLayer_ = 0.0f;

    ValueNoise1D textureNoise_;

    
    int blocksSinceAudio_ = 1000;

    
    float prevBassRms_ = 0.0f;
    float prevVocalRms_ = 0.0f;
    int onsetRefractoryFrames_[4] = {0, 0, 0, 0}; 
    static constexpr int ONSET_REFRACTORY_FRAMES = 2; 

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

    void configureOutput(float styleAmpScale, float sharpness, float attackScale, float accentScale, float bassBoost,
                         float impactGain, float continuousGain, float textureGain, float masterGain,
                         float onsetThreshold, float attackImpactMs, float decayImpactMs,
                         float attackContinuousMs, float decayContinuousMs, float releaseMs,
                         float sustainLevel, float lraF0, float lraQ) {
        outputStyleAmpScale_.store(std::clamp(styleAmpScale, 0.30f, 2.40f), std::memory_order_relaxed);
        outputSharpness_.store(std::clamp(sharpness, 0.05f, 1.00f), std::memory_order_relaxed);
        outputAttackScale_.store(std::clamp(attackScale, 0.45f, 1.80f), std::memory_order_relaxed);
        outputAccentScale_.store(std::clamp(accentScale, 0.50f, 2.00f), std::memory_order_relaxed);
        outputBassBoost_.store(std::clamp(bassBoost, 1.00f, 2.50f), std::memory_order_relaxed);
        outputImpactGain_.store(std::clamp(impactGain, 0.20f, 3.00f), std::memory_order_relaxed);
        outputContinuousGain_.store(std::clamp(continuousGain, 0.20f, 3.00f), std::memory_order_relaxed);
        outputTextureGain_.store(std::clamp(textureGain, 0.20f, 3.00f), std::memory_order_relaxed);
        outputMasterGain_.store(std::clamp(masterGain, 0.20f, 3.00f), std::memory_order_relaxed);
        outputOnsetThreshold_.store(std::clamp(onsetThreshold, 0.03f, 0.90f), std::memory_order_relaxed);
        outputAttackImpactMs_.store(std::clamp(attackImpactMs, 2.0f, 80.0f), std::memory_order_relaxed);
        outputDecayImpactMs_.store(std::clamp(decayImpactMs, 4.0f, 140.0f), std::memory_order_relaxed);
        outputAttackContinuousMs_.store(std::clamp(attackContinuousMs, 4.0f, 120.0f), std::memory_order_relaxed);
        outputDecayContinuousMs_.store(std::clamp(decayContinuousMs, 8.0f, 240.0f), std::memory_order_relaxed);
        outputReleaseMs_.store(std::clamp(releaseMs, 8.0f, 300.0f), std::memory_order_relaxed);
        outputSustainLevel_.store(std::clamp(sustainLevel, 0.0f, 1.0f), std::memory_order_relaxed);
        outputLraF0_.store(std::clamp(lraF0, 80.0f, 320.0f), std::memory_order_relaxed);
        outputLraQ_.store(std::clamp(lraQ, 0.25f, 2.50f), std::memory_order_relaxed);
    }

    float getOutputStyleAmpScale() const { return outputStyleAmpScale_.load(std::memory_order_relaxed); }
    float getOutputSharpness() const { return outputSharpness_.load(std::memory_order_relaxed); }
    float getOutputAttackScale() const { return outputAttackScale_.load(std::memory_order_relaxed); }
    float getOutputAccentScale() const { return outputAccentScale_.load(std::memory_order_relaxed); }
    float getOutputBassBoost() const { return outputBassBoost_.load(std::memory_order_relaxed); }
    float getOutputMasterGain() const { return outputMasterGain_.load(std::memory_order_relaxed); }
    float getOutputOnsetThreshold() const { return outputOnsetThreshold_.load(std::memory_order_relaxed); }
    float getOutputAttackImpactMs() const { return outputAttackImpactMs_.load(std::memory_order_relaxed); }
    float getOutputReleaseMs() const { return outputReleaseMs_.load(std::memory_order_relaxed); }
    float getOutputSustainLevel() const { return outputSustainLevel_.load(std::memory_order_relaxed); }
    float getOutputLraF0() const { return outputLraF0_.load(std::memory_order_relaxed); }
    float getOutputLraQ() const { return outputLraQ_.load(std::memory_order_relaxed); }

    void configureProfile(float dspFloor, float subMult, float kickMult, float snareMult,
                          float tickMult, float bodyMult, float refractoryScale) {
        profileDspFloor_.store(std::clamp(dspFloor, 0.0010f, 0.0200f), std::memory_order_relaxed);
        profileSubMult_.store(std::clamp(subMult, 0.50f, 3.00f), std::memory_order_relaxed);
        profileKickMult_.store(std::clamp(kickMult, 0.20f, 2.00f), std::memory_order_relaxed);
        profileSnareMult_.store(std::clamp(snareMult, 0.20f, 2.00f), std::memory_order_relaxed);
        profileTickMult_.store(std::clamp(tickMult, 0.20f, 2.00f), std::memory_order_relaxed);
        profileBodyMult_.store(std::clamp(bodyMult, 0.20f, 2.00f), std::memory_order_relaxed);
        profileRefractoryScale_.store(std::clamp(refractoryScale, 0.50f, 2.00f), std::memory_order_relaxed);
        HMS_LOGI("[DSP-PROFILE] floor=%.5f sub=%.3f kick=%.3f snare=%.3f tick=%.3f body=%.3f refractory=%.2f",
                  dspFloor, subMult, kickMult, snareMult, tickMult, bodyMult, refractoryScale);
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

        bassHp_.setHighPass(sampleRate, 80.0f);
        bassLp_.setLowPass(sampleRate, 180.0f);
        lowMidHp_.setHighPass(sampleRate, 180.0f);
        lowMidLp_.setLowPass(sampleRate, 500.0f);
        vocalHp_.setHighPass(sampleRate, 500.0f);
        vocalLp_.setLowPass(sampleRate, 3000.0f);
        presenceHp_.setHighPass(sampleRate, 3000.0f);
        presenceLp_.setLowPass(sampleRate, 8000.0f);
        airHp_.setHighPass(sampleRate, 8000.0f);
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
        float maxCorr = 1.0e-4f; 
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
        bool dbgThisFrame = (s_dbgCounter % 200 == 0); 
        s_dbgCounter++;

        
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

        
        float subRms = computeRmsNeon(subOutput_, size);
        float midRms = computeRmsNeon(midOutput_, size);
        float textureRms = computeRmsNeon(textureOutput_, size);
        
        
        
        textureRms = std::min(textureRms, 0.05f);
        const float subProfileGain = 1.0f + (profileSubMult_.load(std::memory_order_relaxed) - 1.0f) * 0.25f;
        subRms *= std::clamp(subProfileGain, 0.75f, 1.50f);
        const float invSize = 1.0f / static_cast<float>(size);
        float bassBand = std::sqrt(bassSq * invSize);
        float lowMidBand = std::sqrt(lowMidSq * invSize);
        float vocalBand = std::sqrt(vocalSq * invSize);
        float presenceBand = std::sqrt(presenceSq * invSize);
        float airBand = std::sqrt(airSq * invSize);
        float zcr = static_cast<float>(zeroCrossings) * invSize;
        updateSpectrum(input, size);
        float totalBand = bassBand + lowMidBand + vocalBand + presenceBand + airBand + 1.0e-6f;
        float highRatio = (presenceBand + airBand) / totalBand;
        float vocalRatio = vocalBand / totalBand;
        float bassRatio = (subRms + bassBand) / (totalBand + subRms);
        float lowMidFlux = std::max(0.0f, lowMidBand - prevLowMidRms_);
        float presenceFlux = std::max(0.0f, presenceBand - prevPresenceRms_);
        float airFlux = std::max(0.0f, airBand - prevAirRms_);

        
        
        float pitch = prevPitch_;
        if (pitchUpdateCounter_++ % 8 == 0) pitch = estimatePitch(input, size);
        if (pitch <= 0.0f) pitch = 150.0f;
        float pitchDelta = prevPitch_ > 0.0f ? std::abs(pitch - prevPitch_) / std::max(prevPitch_, 1.0f) : 1.0f;
        pitchConfidence_ += 0.18f * (((pitch >= 70.0f && pitch <= 300.0f) && pitchDelta < 0.18f ? 1.0f : 0.0f) - pitchConfidence_);

        float kickTarget = std::clamp(
            std::max(0.0f, subRms - prevSubRms_) * 12.0f
            + bassSpectralFlux_ * 0.85f
            - highRatio * 0.30f,
            0.0f, 1.0f
        );
        float snareTarget = std::clamp(
            lowMidFlux * 15.0f + presenceFlux * 7.0f + spectralFlux_ * 0.30f
            + highRatio * 0.28f - bassRatio * 0.25f,
            0.0f, 1.0f);
        float hatTarget = std::clamp(
            highSpectralFlux_ * (0.75f * profileTickMult_.load(std::memory_order_relaxed))
            + airFlux * 22.0f + presenceFlux * 6.0f
            + zcr * 1.4f - bassRatio * 0.35f,
            0.0f, 1.0f);
        float vocalTarget = std::clamp(
            vocalRatio * 1.2f  
            + pitchConfidence_ * 0.35f  
            - highRatio * 0.45f
            - std::max(kickTarget, snareTarget) * 0.65f,  
            0.0f, 1.0f
        );
        float pluckedTarget = std::clamp(lowMidFlux * 14.0f + presenceFlux * 5.0f + pitchConfidence_ * 0.35f - vocalTarget * 0.25f, 0.0f, 1.0f);
        float harmonicTarget = std::clamp(pitchConfidence_ * 0.65f + (lowMidBand + vocalBand) / totalBand * 0.55f - std::max({kickTarget, snareTarget, hatTarget}) * 0.3f, 0.0f, 1.0f);
        float bassSustainTarget = std::clamp(
            std::max(0.0f, subRms - prevSubRms_) * 8.0f  
            + subRms * 0.3f,  
            0.0f, 1.0f
        );

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

        
        
        float bassFlux = std::max(0.0f, bassBand - prevBassRms_);
        float lowMidFlux2 = std::max(0.0f, lowMidBand - prevLowMidRms_);
        float vocalFlux = std::max(0.0f, vocalBand - prevVocalRms_);
        float presenceFlux2 = std::max(0.0f, presenceBand - prevPresenceRms_);
        float airFlux2 = std::max(0.0f, airBand - prevAirRms_);

        
        for (int i = 0; i < 4; ++i) {
            if (onsetRefractoryFrames_[i] > 0) onsetRefractoryFrames_[i]--;
        }

        
        
        
        

        
        float kickOnset = 0.0f;
        if (onsetRefractoryFrames_[0] == 0) {
            float bassFluxVal = std::clamp(bassFlux * 15.0f, 0.0f, 1.0f);
            float subFluxVal  = std::clamp(std::max(0.0f, subRms - prevSubRms_) * 15.0f, 0.0f, 1.0f);
            float spectralBassVal = bassSpectralFlux_ * 0.95f;
            kickOnset = std::max({bassFluxVal, subFluxVal, spectralBassVal});
            kickOnset *= profileKickMult_.load(std::memory_order_relaxed);
            
            const float floorRatio = std::clamp(profileDspFloor_.load(std::memory_order_relaxed) / 0.0040f, 0.55f, 2.00f);
            const float kickThreshold = std::clamp(0.40f * std::sqrt(floorRatio), 0.28f, 0.58f);
            if (kickOnset < kickThreshold) kickOnset = 0.0f;  
            if (kickOnset > 0.0f) onsetRefractoryFrames_[0] = std::max(1, static_cast<int>(std::lround(ONSET_REFRACTORY_FRAMES * profileRefractoryScale_.load(std::memory_order_relaxed))));
        }

        
        float snareOnset = 0.0f;
        if (onsetRefractoryFrames_[1] == 0) {
            
            float lowMidEnergy = std::clamp((lowMidBand - 0.10f) * 4.0f, 0.0f, 1.0f);  
            float lowMidFluxV  = std::clamp(lowMidFlux2 * 12.0f, 0.0f, 1.0f);  
            float presFluxVal  = std::clamp(presenceFlux2 * 10.0f, 0.0f, 1.0f);  
            snareOnset = std::max({lowMidEnergy, lowMidFluxV, presFluxVal});
            snareOnset *= profileSnareMult_.load(std::memory_order_relaxed);
            const float floorRatio = std::clamp(profileDspFloor_.load(std::memory_order_relaxed) / 0.0040f, 0.55f, 2.00f);
            const float snareThreshold = std::clamp(0.50f * std::sqrt(floorRatio), 0.34f, 0.68f);
            if (snareOnset < snareThreshold) snareOnset = 0.0f;  
            if (snareOnset > 0.0f) onsetRefractoryFrames_[1] = std::max(1, static_cast<int>(std::lround(ONSET_REFRACTORY_FRAMES * profileRefractoryScale_.load(std::memory_order_relaxed))));
        }

        
        float vocalOnset = 0.0f;
        if (onsetRefractoryFrames_[2] == 0) {
            float vocalEnergy = std::clamp((vocalBand - 0.02f) * 6.0f, 0.0f, 1.0f);
            float vocalFluxV  = std::clamp(vocalFlux * 10.0f, 0.0f, 1.0f);
            float vocalStrength = std::max(vocalEnergy, vocalFluxV);

            if (snareOnset > 0.3f) vocalStrength *= 0.3f;

            if (vocalStrength >= 0.12f) {
                vocalOnset = std::clamp((vocalStrength - 0.12f) * 1.6f, 0.0f, 0.50f);
            }
            if (vocalOnset > 0.0f) onsetRefractoryFrames_[2] = std::max(1, static_cast<int>(std::lround(ONSET_REFRACTORY_FRAMES * profileRefractoryScale_.load(std::memory_order_relaxed))));
        }

        
        
        
        float bodyOnset = 0.0f;
        if (onsetRefractoryFrames_[3] == 0) {
            const float rawBody = std::clamp((subRms - profileDspFloor_.load(std::memory_order_relaxed) * 4.0f) * 8.0f, 0.0f, 1.0f);
            const float bodyStrength = rawBody * profileBodyMult_.load(std::memory_order_relaxed);
            if (bodyStrength >= 0.35f) {
                bodyOnset = std::clamp((bodyStrength - 0.35f) * 1.2f, 0.0f, 0.40f);
            }
            if (bodyOnset > 0.0f) onsetRefractoryFrames_[3] = std::max(1, static_cast<int>(std::lround(ONSET_REFRACTORY_FRAMES * profileRefractoryScale_.load(std::memory_order_relaxed))));
        }

        
        if (dbgThisFrame) {
            HMS_LOGI("[DSP-DBG] bassBand=%.5f lowMid=%.5f vocal=%.5f subRms=%.5f | onset: KICK=%.3f SNARE=%.3f VOCAL=%.3f BODY=%.3f | refract=[%d %d %d %d]",
                bassBand, lowMidBand, vocalBand, subRms,
                kickOnset, snareOnset, vocalOnset, bodyOnset,
                onsetRefractoryFrames_[0], onsetRefractoryFrames_[1], onsetRefractoryFrames_[2], onsetRefractoryFrames_[3]);
        }

        
        prevBassRms_ = bassBand;
        prevLowMidRms_ = lowMidBand;
        prevVocalRms_ = vocalBand;
        prevPresenceRms_ = presenceBand;
        prevAirRms_ = airBand;

        
        float amp = userAmplitude_.load(std::memory_order_relaxed);
        int preset = currentPresetId_.load(std::memory_order_relaxed);
        if (preset == 1) { subRms *= 1.4f; }
        else if (preset == 2) { textureRms *= 1.5f; }
        else if (preset == 3) { subRms *= 1.25f; midRms *= 1.35f; }

        
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

        
        composeHapticLayer(subRms, midRms, textureRms, pitch, thermalGain, amp, dt);

        
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
        
        outTelemetry[20] = kickOnset;
        outTelemetry[21] = snareOnset;
        outTelemetry[22] = vocalOnset;
        outTelemetry[23] = bodyOnset;
        outTelemetry[24] = spectralFlux_;
        outTelemetry[25] = bassSpectralFlux_;
        outTelemetry[26] = highSpectralFlux_;
        outTelemetry[27] = spectralCentroidHz_;

        
        
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

    
    
    
    
    
    void composeHapticLayer(float subRms, float midRms, float textureRms,
                              float pitch, float thermalGain, float userAmp, float dt) {
        
        const float impactGain = outputImpactGain_.load(std::memory_order_relaxed);
        const float continuousGain = outputContinuousGain_.load(std::memory_order_relaxed);
        const float textureGain = outputTextureGain_.load(std::memory_order_relaxed);
        const float masterGain = outputMasterGain_.load(std::memory_order_relaxed);
        const float bassBoost = outputBassBoost_.load(std::memory_order_relaxed);

        float kickEnv = std::max(0.0f, subRms - prevSubRms_) * 6.0f * kickProbability_ * impactGain * bassBoost;
        kickEnv = std::clamp(kickEnv, 0.0f, 1.0f);
        
        
        
        float snareEnv = midRms * 0.5f * snareProbability_ * impactGain;  
        snareEnv = std::clamp(snareEnv, 0.0f, 0.60f);  
        
        
        
        
        float vocalTarget = midRms * 0.15f * vocalProbability_ * continuousGain * textureGain;  
        vocalEnvelope_ += (vocalTarget - vocalEnvelope_) * 0.20f;  
        float vocalEnv = std::clamp(vocalEnvelope_, 0.0f, 0.10f);  
        
        
        
        float bodyTarget = subRms * 0.08f * bassSustainProbability_ * continuousGain * bassBoost;  
        bassSmoothed_ += (bodyTarget - bassSmoothed_) * 0.20f;  
        float bodyEnv = std::clamp(bassSmoothed_, 0.0f, 0.05f);  
        
        
        
        
        float overallVolume = std::clamp((subRms + midRms) / 2.0f, 0.0f, 1.0f);
        
        float volumeMod = std::sqrt(overallVolume);
        kickEnv *= volumeMod;
        snareEnv *= volumeMod;
        vocalEnv *= volumeMod;
        bodyEnv *= volumeMod;
        
        
        
        
        
        
        float totalEnergy = kickEnv + snareEnv + vocalEnv + bodyEnv;
        if (totalEnergy < 0.002f) {
            float tailGain = std::clamp(totalEnergy / 0.002f, 0.0f, 1.0f);
            
            tailGain = std::sqrt(tailGain);
            kickEnv *= tailGain;
            snareEnv *= tailGain;
            vocalEnv *= tailGain;
            bodyEnv *= tailGain;
        }
        
        
        float scale = 255.0f * userAmp * thermalGain * masterGain;
        pushSemanticFrame(kickEnv * scale, snareEnv * scale, vocalEnv * scale, bodyEnv * scale);
        
        float composite = kickEnv + snareEnv + vocalEnv + bodyEnv;
        lastComposedAmp_ = std::clamp(composite * scale, 0.0f, 255.0f);
        lastBeatLayer_ = kickEnv;
        lastBassLayer_ = bodyEnv;
        lastMelodyLayer_ = vocalEnv;
    }
    float getLastComposedAmp() const { return lastComposedAmp_; }
    float getLastBeatLayer() const { return lastBeatLayer_; }
    float getLastBassLayer() const { return lastBassLayer_; }
    float getLastMelodyLayer() const { return lastMelodyLayer_; }
    float getLastComposite() const { return lastComposite_; }
    float getBeatEnvelope() const { return beatEnvelope_; }
    float getBeatIntervalMs() const { return beatIntervalFrames_ * (256.0f / sampleRate_.load(std::memory_order_relaxed)) * 1000.0f; }
    float getBeatConfidence() const { return beatConfidence_; }

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

} 