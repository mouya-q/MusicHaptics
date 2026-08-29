#include <vector>
#include <jni.h>
#include <cmath>
#include <pthread.h>
#include <time.h>
#include <atomic>
#include <string>
#include <fcntl.h>
#include <unistd.h>
#include "haptic/HapticEngine.hpp"

// ═════════════════════════════════════════════════════════════════
//  v2.1 Native Haptic Scheduler -> v4.2 Direct Drive Renderer
//  A dedicated native thread that pulls from the C++ ring buffer
//  and writes DIRECTLY to kernel driver nodes.
//  Bypasses Android Framework completely.
// ═════════════════════════════════════════════════════════════════

static JavaVM* g_jvm = nullptr;
static std::atomic<bool> g_scheduler_running{false};
static pthread_t g_scheduler_thread{};

// Direct Drive State
static std::atomic<int> g_direct_drive_fd{-1};
static std::string g_direct_drive_path = "";
static std::string g_direct_amplitude_path = "";
static std::atomic<int> g_direct_amplitude_fd{-1};

// Initialize direct drive by probing known nodes
void init_direct_drive(const std::string& nodes) {
    if (g_direct_drive_fd.load() != -1) return;

    size_t start = 0;
    while (start < nodes.length()) {
        size_t end = nodes.find(',', start);
        if (end == std::string::npos) end = nodes.length();
        
        std::string path = nodes.substr(start, end - start);
        start = end + 1;
        if (path.empty()) continue;

        // Strip trailing newline if any
        if (!path.empty() && path.back() == '\n') path.pop_back();

        std::string enable_path = path + "/enable";
        int fd = open(enable_path.c_str(), O_WRONLY | O_NONBLOCK);
        if (fd >= 0) {
            g_direct_drive_path = enable_path;
            g_direct_drive_fd.store(fd, std::memory_order_relaxed);
            
            // Check for amplitude node
            std::string amp_path = path + "/amplitude";
            int amp_fd = open(amp_path.c_str(), O_WRONLY | O_NONBLOCK);
            if (amp_fd >= 0) {
                g_direct_amplitude_path = amp_path;
                g_direct_amplitude_fd.store(amp_fd, std::memory_order_relaxed);
            } else {
                amp_path = path + "/gain";
                amp_fd = open(amp_path.c_str(), O_WRONLY | O_NONBLOCK);
                if (amp_fd >= 0) {
                    g_direct_amplitude_path = amp_path;
                    g_direct_amplitude_fd.store(amp_fd, std::memory_order_relaxed);
                }
            }
            break;
        }
    }
}

void trigger_direct_drive(int duration_ms, int amplitude) {
    int fd = g_direct_drive_fd.load(std::memory_order_relaxed);
    if (fd < 0) return;

    int amp_fd = g_direct_amplitude_fd.load(std::memory_order_relaxed);
    if (amp_fd >= 0 && amplitude > 0) {
        char amp_str[16];
        int amp_len = snprintf(amp_str, sizeof(amp_str), "%d", amplitude);
        write(amp_fd, amp_str, amp_len);
    }

    char dur_str[16];
    int dur_len = snprintf(dur_str, sizeof(dur_str), "%d", duration_ms);
    write(fd, dur_str, dur_len);
}

// Track the global ref so we can clean it up reliably on stop
static std::atomic<jobject> g_bridge_ref{nullptr};

struct SchedulerArgs {
    haptic::HapticEngine* engine;
    jobject bridgeGlobalRef;
};

static void* scheduler_thread_func(void* arg) {
    auto* sargs = static_cast<SchedulerArgs*>(arg);
    auto* engine = sargs->engine;
    jobject bridgeRef = sargs->bridgeGlobalRef;
    delete sargs;

    if (!engine || !bridgeRef || !g_jvm) return nullptr;

    JNIEnv* env = nullptr;
    JavaVMAttachArgs attachArgs = {JNI_VERSION_1_6, "HapticScheduler", nullptr};
    if (g_jvm->AttachCurrentThread(&env, &attachArgs) != JNI_OK) {
        return nullptr;
    }

    // Cache method IDs once
    jclass bridgeClass = env->GetObjectClass(bridgeRef);
    jmethodID onFrameReady = env->GetMethodID(bridgeClass, "onNativeFrameReady", "([FI)V");
    env->DeleteLocalRef(bridgeClass);
    if (!onFrameReady) {
        g_jvm->DetachCurrentThread();
        // Clean up the global ref before exiting
        JNIEnv* cleanupEnv = nullptr;
        if (g_jvm->AttachCurrentThread(&cleanupEnv, nullptr) == JNI_OK) {
            cleanupEnv->DeleteGlobalRef(bridgeRef);
            g_jvm->DetachCurrentThread();
        }
        g_bridge_ref.store(nullptr, std::memory_order_relaxed);
        return nullptr;
    }

    // 10ms precise timing using absolute-time clock_nanosleep
    const long frame_period_ns = 5000000L;  // v4.2: 5ms for 200Hz Control Loop
    
    struct timespec nextWake;
    clock_gettime(CLOCK_MONOTONIC, &nextWake);

    int direct_fd = g_direct_drive_fd.load(std::memory_order_relaxed);
    bool use_direct_drive = (direct_fd >= 0);

    // Filter states for physical model
    float lra_position = 0.0f;
    float lra_velocity = 0.0f;
    float spring_k = 0.8f;
    float damping_c = 0.3f;

    while (g_scheduler_running.load(std::memory_order_relaxed)) {
        if (use_direct_drive) {
            haptic::HapticEngine::OnsetFrame frames[1];
            int n = engine->getOnsetFrames(frames, 1);
            if (n > 0) {
                // v4.2: Real-time physics rendering (Simplified ADSR for now)
                float total_force = frames[0].kick * 1.5f + frames[0].snare * 1.0f + frames[0].body * 0.8f;
                
                // Extremely rudimentary physical model step
                float acceleration = total_force - (spring_k * lra_position) - (damping_c * lra_velocity);
                lra_velocity += acceleration;
                lra_position += lra_velocity;

                if (total_force > 0.1f) {
                    int duration = 5; // 5ms per tick
                    int amplitude = static_cast<int>(std::clamp(total_force * 255.0f, 0.0f, 255.0f));
                    trigger_direct_drive(duration, amplitude);
                }
            }
        } else {
            // Fallback to JNI callback if direct drive not available
            int batchCount = 0;
            float batchBuffer[6];
            for (int b = 0; b < 6 && g_scheduler_running.load(std::memory_order_relaxed); b++) {
                float s = 0.0f;
                int n = engine->getHapticFrame(&s, 1);
                if (n > 0) {
                    batchBuffer[batchCount++] = s;
                }
                nextWake.tv_nsec += frame_period_ns;
                if (nextWake.tv_nsec >= 1000000000L) {
                    nextWake.tv_sec++;
                    nextWake.tv_nsec -= 1000000000L;
                }
                clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &nextWake, nullptr);
            }

            if (batchCount > 0) {
                jfloatArray jArr = env->NewFloatArray(batchCount);
                if (jArr) {
                    env->SetFloatArrayRegion(jArr, 0, batchCount, batchBuffer);
                    env->CallVoidMethod(bridgeRef, onFrameReady, jArr, batchCount);
                    env->DeleteLocalRef(jArr);
                }
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                }
            }
            continue; // Skip the single 5ms wait below
        }

        // Wait for next 5ms boundary (absolute time sleep = zero jitter)
        nextWake.tv_nsec += frame_period_ns;
        if (nextWake.tv_nsec >= 1000000000L) {
            nextWake.tv_sec++;
            nextWake.tv_nsec -= 1000000000L;
        }
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &nextWake, nullptr);
    }

    // Thread exit: detach and clean up the global ref
    g_jvm->DetachCurrentThread();

    // Re-attach briefly to delete the global ref
    JNIEnv* cleanupEnv = nullptr;
    if (g_jvm->AttachCurrentThread(&cleanupEnv, nullptr) == JNI_OK) {
        cleanupEnv->DeleteGlobalRef(bridgeRef);
        g_jvm->DetachCurrentThread();
    }
    g_bridge_ref.store(nullptr, std::memory_order_relaxed);

    return nullptr;
}

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

// ═════════════════════════════════════════════════════════════════
//  Continuous Haptic Frame Pull (legacy — used when scheduler is off)
//  Copies amplitude samples from C++ ring buffer to Java array.
//  Returns number of samples actually copied.
// ═════════════════════════════════════════════════════════════════
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

// ═════════════════════════════════════════════════════════════════
//  Clear Haptic Buffer
//  Flushes ring buffer and resets all envelope states.
//  Called on pause / stop / thermal shutdown.
// ═════════════                       ═════════════════════════════
JNIEXPORT void JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeClearHapticBuffer(
    JNIEnv* env, jobject thiz, jlong ptr) {
    auto* engine = reinterpret_cast<haptic::HapticEngine*>(ptr);
    if (engine) {
        engine->clearHapticBuffer();
    }
}

// ═════════════════════════════════════════════════════════════════
//  v2.1 Native Haptic Scheduler — start/stop
//  Starts a dedicated native thread that pulls from the C++ ring
//  buffer at precise 10ms intervals and calls back to Java.
//  This eliminates coroutine delay jitter and JNI polling overhead.
// ═════════════════════════════════════════════════════════════════
JNIEXPORT jboolean JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeStartScheduler(
    JNIEnv* env, jobject thiz, jlong ptr) {
    if (ptr == 0) return JNI_FALSE;
    if (g_scheduler_running.load(std::memory_order_relaxed)) return JNI_TRUE;

    auto* engine = reinterpret_cast<haptic::HapticEngine*>(ptr);
    if (!engine) return JNI_FALSE;

    // Create global ref to the NativeBridge Java object for callbacks
    jobject bridgeRef = env->NewGlobalRef(thiz);

    auto* sargs = new SchedulerArgs{engine, bridgeRef};
    g_scheduler_running.store(true, std::memory_order_relaxed);
    g_bridge_ref.store(bridgeRef, std::memory_order_relaxed);

    int result = pthread_create(&g_scheduler_thread, nullptr, scheduler_thread_func, sargs);
    if (result != 0) {
        g_scheduler_running.store(false, std::memory_order_relaxed);
        g_bridge_ref.store(nullptr, std::memory_order_relaxed);
        env->DeleteGlobalRef(bridgeRef);
        delete sargs;
        return JNI_FALSE;
    }

    // Set thread name for debugging
    pthread_setname_np(g_scheduler_thread, "HapticScheduler");

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeStopScheduler(
    JNIEnv* env, jobject thiz) {
    if (!g_scheduler_running.load(std::memory_order_relaxed)) return;

    g_scheduler_running.store(false, std::memory_order_relaxed);
    pthread_join(g_scheduler_thread, nullptr);

    // GlobalRef cleanup is done inside scheduler_thread_func on exit.
    // But as a safety net, check if it's still around and clean it.
    jobject ref = g_bridge_ref.exchange(nullptr, std::memory_order_acq_rel);
    if (ref) {
        env->DeleteGlobalRef(ref);
    }
}

// ═════════════════════════════════════════════════════════════════
//  JNI_OnLoad — cache JavaVM pointer for scheduler thread
// ═════════════════════════════════════════════════════════════════
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

} // extern "C"
extern "C" JNIEXPORT jint JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeGetSemanticFrames(JNIEnv* env, jobject, jlong ptr, jfloatArray outBuffer, jint maxFrames) {
    auto* engine = reinterpret_cast<haptic::HapticEngine*>(ptr);
    if (!engine || !outBuffer) return 0;
    
    // 4 floats per frame: kick, snare, vocal, body
    jsize capacity = env->GetArrayLength(outBuffer) / 4;
    int framesToRead = std::min(static_cast<int>(capacity), static_cast<int>(maxFrames));
    if (framesToRead <= 0) return 0;
    
    std::vector<haptic::SemanticHapticFrame> frames(framesToRead);
    int count = engine->getSemanticFrames(frames.data(), framesToRead);
    
    if (count > 0) {
        // Flatten into the float array
        std::vector<float> flat(count * 4);
        for (int i = 0; i < count; i++) {
            flat[i * 4 + 0] = frames[i].kickAmp;
            flat[i * 4 + 1] = frames[i].snareAmp;
            flat[i * 4 + 2] = frames[i].vocalAmp;
            flat[i * 4 + 3] = frames[i].bodyAmp;
        }
        env->SetFloatArrayRegion(outBuffer, 0, count * 4, flat.data());
    }
    return count;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeSetDirectDriveNodes(JNIEnv* env, jobject, jstring nodes) {
    if (!nodes) return;
    const char* c_nodes = env->GetStringUTFChars(nodes, nullptr);
    if (c_nodes) {
        init_direct_drive(std::string(c_nodes));
        env->ReleaseStringUTFChars(nodes, c_nodes);
    }
}
extern "C" JNIEXPORT jint JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeGetOnsetFrames(JNIEnv* env, jobject, jlong ptr, jfloatArray outBuffer, jint maxFrames) {
    auto* engine = reinterpret_cast<haptic::HapticEngine*>(ptr);
    if (!engine || !outBuffer) return 0;
    
    // 4 floats per frame: kick, snare, vocal, body
    jsize capacity = env->GetArrayLength(outBuffer) / 4;
    int framesToRead = std::min(static_cast<int>(capacity), static_cast<int>(maxFrames));
    if (framesToRead <= 0) return 0;
    
    std::vector<haptic::HapticEngine::OnsetFrame> frames(framesToRead);
    int count = engine->getOnsetFrames(frames.data(), framesToRead);
    
    if (count > 0) {
        // Flatten into the float array
        std::vector<float> flat(count * 4);
        for (int i = 0; i < count; i++) {
            flat[i * 4 + 0] = frames[i].kick;
            flat[i * 4 + 1] = frames[i].snare;
            flat[i * 4 + 2] = frames[i].vocal;
            flat[i * 4 + 3] = frames[i].body;
        }
        env->SetFloatArrayRegion(outBuffer, 0, count * 4, flat.data());
    }
    return count;
}