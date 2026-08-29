// PatternGenerator.cpp — 触觉模式生成（根据频段生成振动波形）
#include <cmath>

namespace pattern {

void generate(float intensity, float* outBuffer, int size) {
    for (int i = 0; i < size; ++i) {
        outBuffer[i] = intensity * std::sin(i * 0.1f);
    }
}

} // namespace pattern