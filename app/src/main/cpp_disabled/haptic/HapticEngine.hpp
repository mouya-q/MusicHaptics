#pragma once

#include <arm_neon.h>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <atomic>

namespace haptic {

// 64 字节 Cache Line 内存对齐
struct alignas(64) BiquadCoeffs {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
};

struct alignas(64) BiquadState {
    float x1 = 0.0f, x2 = 0.0f, y1 = 0.0f, y2 = 0.0f;
    void reset() { x1 = x2 = y1 = y2 = 0.0f; }
};

// 4 阶 Linkwitz-Riley 滤波器 (两阶 Butterworth 级联)
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

class HapticEngine {
private:
    float sampleRate_ = 48000.0f;
    std::atomic<float> userAmplitude_{2.0f};
    std::atomic<int> currentPresetId_{0};

    LinkwitzRiley4th subLowPass_;
    LinkwitzRiley4th midHighPass_, midLowPass_;
    LinkwitzRiley4th textureHighPass_;

    float coilTemp_ = 25.0f;
    float magnetTemp_ = 25.0f;

    alignas(64) float subOutput_[256];
    alignas(64) float midOutput_[256];
    alignas(64) float textureOutput_[256];

    float historyBuffer_[2048] = {0.0f};

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

    // NEON SIMD 平方和计算
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

    // 自相关算法提取基频 F0
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

    void processAudioBlock(const float* input, int size, float* outTelemetry) {
        if (size > 256) size = 256;

        // 1. 三频段 Crossover 快速分频
        for (int i = 0; i < size; ++i) {
            float s = input[i];
            subOutput_[i] = subLowPass_.process(s);
            float midTemp = midHighPass_.process(s);
            midOutput_[i] = midLowPass_.process(midTemp);
            textureOutput_[i] = textureHighPass_.process(s);
        }

        // 2. ARM NEON 加速能量提取
        float subRms = computeRmsNeon(subOutput_, size);
        float midRms = computeRmsNeon(midOutput_, size);
        float textureRms = computeRmsNeon(textureOutput_, size);

        // 3. F0 追踪
        float pitch = estimatePitch(input, size);

        // 4. 根据预设模式 (Preset) 进行实时增益调节
        float amp = userAmplitude_.load(std::memory_order_relaxed);
        int preset = currentPresetId_.load(std::memory_order_relaxed);

        if (preset == 1) { // BASS_ENHANCED
            subRms *= 1.4f;
        } else if (preset == 2) { // TEXTURE_FOCUS
            textureRms *= 1.5f;
        } else if (preset == 3) { // IMPACT_MAX
            subRms *= 1.25f;
            midRms *= 1.35f;
        }

        // 5. 热力学双节点模型
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

        // 6. 写回遥测结果给 Java 侧
        outTelemetry[0] = subRms * amp * thermalGain;
        outTelemetry[1] = midRms * amp * thermalGain;
        outTelemetry[2] = textureRms * amp * thermalGain;
        outTelemetry[3] = pitch;
        outTelemetry[4] = coilTemp_;
        outTelemetry[5] = thermalGain;
    }
};

} // namespace haptic
