#include <jni.h>
#include <cmath>
#include "haptic/HapticEngine.hpp"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeCreateEngine(JNIEnv* env, jobject thiz) {
    auto* engine = new haptic::HapticEngine();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeDestroyEngine(JNIEnv* env, jobject thiz, jlong ptr) {
    if (ptr != 0) {
        delete reinterpret_cast<haptic::HapticEngine*>(ptr);
    }
}

JNIEXPORT void JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeConfigure(
    JNIEnv* env, jobject thiz, jlong ptr, jfloat sampleRate, jfloat lowCut, jfloat highCut, jfloat amp, jint presetId) {
    auto* engine = reinterpret_cast<haptic::HapticEngine*>(ptr);
    if (engine) {
        engine->configure(sampleRate, lowCut, highCut, amp, presetId);
    }
}

JNIEXPORT void JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeProcessAudioDirect(
    JNIEnv* env, jobject thiz, jlong ptr, jobject directInputBuffer, jint size, jfloatArray outTelemetry) {
    
    auto* engine = reinterpret_cast<haptic::HapticEngine*>(ptr);
    if (!engine || !directInputBuffer || !outTelemetry) return;

    auto* inputPtr = static_cast<float*>(env->GetDirectBufferAddress(directInputBuffer));
    if (!inputPtr) return;

    jfloat* telemetry = env->GetFloatArrayElements(outTelemetry, nullptr);
    if (!telemetry) return;

    engine->processAudioBlock(inputPtr, size, telemetry);

    // 核心：第 3 个参数必须是 0，保证将 C++ 写入的数据刷新回 Java 数组！
    env->ReleaseFloatArrayElements(outTelemetry, telemetry, 0);
}

// ════════════════════════════════════════════════════════════════
//  Continuous Haptic Frame Pull
//  Copies amplitude samples from C++ ring buffer to Java array.
//  Returns number of samples actually copied.
// ════════════════════════════════════════════════════════════════
JNIEXPORT jint JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeGetHapticFrame(
    JNIEnv* env, jobject thiz, jlong ptr, jfloatArray outBuffer, jint maxCount) {
    auto* engine = reinterpret_cast<haptic::HapticEngine*>(ptr);
    if (!engine || !outBuffer || maxCount <= 0) return 0;

    jfloat* out = env->GetFloatArrayElements(outBuffer, nullptr);
    if (!out) return 0;

    int count = engine->getHapticFrame(out, maxCount);

    env->ReleaseFloatArrayElements(outBuffer, out, 0);
    return count;
}

// ════════════════════════════════════════════════════════════════
//  Clear Haptic Buffer
//  Flushes ring buffer and resets all envelope states.
//  Called on pause / stop / thermal shutdown.
// ════════════════════════════════════════════════════════════════
JNIEXPORT void JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeClearHapticBuffer(
    JNIEnv* env, jobject thiz, jlong ptr) {
    auto* engine = reinterpret_cast<haptic::HapticEngine*>(ptr);
    if (engine) {
        engine->clearHapticBuffer();
    }
}

} // extern "C"