// FFT.cpp — 快速傅里叶变换（用于频段分析）
// 未来可接入 KissFFT / FFTW 实现高性能实时频谱

#include <cmath>

namespace fft {

// 占位：未来实现实时 FFT 分析
void analyze(const float* input, int size, float* output) {
    for (int i = 0; i < size; ++i) {
        output[i] = std::abs(input[i]);
    }
}

} // namespace fft