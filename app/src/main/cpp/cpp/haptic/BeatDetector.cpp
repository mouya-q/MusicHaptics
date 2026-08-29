// BeatDetector.cpp — 节拍检测（用于触觉节奏同步）
#include <cmath>

namespace beat {

bool detect(const float* buffer, int size, float threshold) {
    float avg = 0.0f;
    for (int i = 0; i < size; ++i) avg += std::abs(buffer[i]);
    avg /= size;
    return avg > threshold;
}

} // namespace beat