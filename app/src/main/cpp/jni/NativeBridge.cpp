#include <jni.h>
#include <cmath>
#include <pthread.h>
#include <time.h>
#include <atomic>
#include <string>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <android/log.h>
#include <signal.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <cstring>
#include <cctype>

#define TAG "MHX-NDK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

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

enum class DirectDriverKind : int {
    Unknown = 0,
    Continuous = 1,
    StrikeOnly = 2,
};
static std::atomic<int> g_direct_driver_kind{static_cast<int>(DirectDriverKind::Unknown)};
static std::atomic<int> g_direct_amplitude_fd{-1};

// Root Shell Pipe State — used when direct open() fails due to SELinux
static std::atomic<int> g_root_shell_fd{-1};
static std::atomic<bool> g_use_root_shell{false};

// Java Pipe State — when fd reflection fails and socket() is blocked,
// C++ calls back into Java's NativeBridge.onRootPipeTrigger() which
// writes to the su process's OutputStream directly.
static std::atomic<bool> g_use_java_pipe{false};
static std::atomic<jobject> g_java_pipe_bridge{nullptr};

// UDP Haptic State — when daemon is running on localhost, C++ sends
// UDP packets to trigger vibration via root daemon
static std::atomic<int> g_udp_sock_fd{-1};       // UDP socket fd
static std::atomic<bool> g_use_udp_haptic{false}; // true when UDP mode active
static struct sockaddr_in g_udp_daemon_addr;       // 127.0.0.1:port

// Binary protocol — NOT shell eval. Daemon validates magic and ranges.
#pragma pack(push, 1)
struct HapticUdpPacket {
    uint32_t magic;       // "MHX1" = 0x3148584D
    uint16_t version;     // 1
    uint16_t durationMs;  // 1..50
    uint8_t  amplitude;   // 0..255 (0 = stop)
    uint8_t  flags;       // bit0: trigger, bit1: set gain
};
#pragma pack(pop)

static constexpr uint32_t MHX_UDP_MAGIC = 0x3148584D; // "MHX1" little-endian
static constexpr uint16_t MHX_UDP_VERSION = 1;

static bool is_safe_node_path(const std::string& path) {
    if (path.size() < 6 || path.size() > 255) return false;
    if (!(path.rfind("/sys/", 0) == 0 || path.rfind("/dev/", 0) == 0)) return false;
    for (unsigned char c : path) {
        if (std::isalnum(c) || c == '/' || c == '_' || c == '-' || c == '.') continue;
        return false;
    }
    return true;
}

static bool init_haptic_udp(int port) {
    // Seccomp blocks socket() in untrusted_app. Use init_haptic_udp_from_fd instead.
    LOGE("[UDP] init_haptic_udp: socket() blocked by seccomp, use init_haptic_udp_from_fd");
    return false;
}

// Called from Kotlin where DatagramSocket creates the fd (bypasses seccomp).
// We just store the fd and target address for sendto().
static bool init_haptic_udp_from_fd(int fd, int port) {
    int oldFd = g_udp_sock_fd.exchange(-1, std::memory_order_acquire);
    if (oldFd >= 0) {
        close(oldFd);
        g_udp_sock_fd.store(-1, std::memory_order_release);
    }

    if (fd < 0) {
        LOGE("[UDP] init_haptic_udp_from_fd: invalid fd");
        return false;
    }

    struct sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(port));
    if (inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr) != 1) {
        LOGE("[UDP] inet_pton failed");
        return false;
    }

    g_udp_daemon_addr = addr;
    g_udp_sock_fd.store(fd, std::memory_order_release);
    g_use_udp_haptic.store(true, std::memory_order_release);

    LOGI("[UDP] Haptic UDP initialized from Java fd=%d port=%d", fd, port);
    return true;
}

static void shutdown_haptic_udp() {
    int fd = g_udp_sock_fd.exchange(-1, std::memory_order_acq_rel);
    g_use_udp_haptic.store(false, std::memory_order_release);
    if (fd >= 0) {
        close(fd);
        LOGI("[UDP] Haptic UDP shut down, fd=%d closed", fd);
    }
}

static bool send_haptic_udp(uint8_t amplitude, uint16_t durationMs, uint8_t flags = 0) {
    if (!g_use_udp_haptic.load(std::memory_order_acquire)) return false;

    int fd = g_udp_sock_fd.load(std::memory_order_acquire);
    if (fd < 0) return false;

    HapticUdpPacket packet{};
    packet.magic = MHX_UDP_MAGIC;
    packet.version = MHX_UDP_VERSION;
    packet.durationMs = htons(durationMs);
    packet.amplitude = amplitude;
    packet.flags = flags;

    ssize_t written = sendto(
        fd,
        &packet,
        sizeof(packet),
        MSG_DONTWAIT,
        reinterpret_cast<const sockaddr*>(&g_udp_daemon_addr),
        sizeof(g_udp_daemon_addr)
    );

    return written == sizeof(packet);
}

// Initialize direct drive — the paths are ACTUAL FILE paths, not directories.
// Kotlin RootHardwareProbe gives us files like:
//   /sys/bus/i2c/drivers/aw8697_haptic/2-005a/activate
//   /sys/class/timed_output/vibrator/enable
//   /sys/class/leds/vibrator/activate
// So we open() them directly — NO "path + /enable" concatenation!
// Returns true on success, false on failure (with diagnostic logging)
bool init_direct_drive(const std::string& nodes) {
    if (g_direct_drive_fd.load() >= 0) {
        LOGI("[DD] already initialized, fd=%d", g_direct_drive_fd.load());
        return true;
    }

    LOGI("[DD] init_direct_drive: nodes=%s", nodes.c_str());

    size_t start = 0;
    while (start < nodes.length()) {
        size_t end = nodes.find(',', start);
        if (end == std::string::npos) end = nodes.length();
        
        std::string path = nodes.substr(start, end - start);
        start = end + 1;
        if (path.empty()) continue;

        // Strip trailing newline/CR if any
        while (!path.empty() && (path.back() == '\n' || path.back() == '\r' || path.back() == ' '))
            path.pop_back();
        if (path.empty() || !is_safe_node_path(path)) {
            LOGW("[DD] rejecting unsafe node path: %s", path.c_str());
            continue;
        }

        // ═══ KEY FIX: open the file path DIRECTLY, not path + "/enable" ═══
        int fd = open(path.c_str(), O_WRONLY | O_NONBLOCK);
        if (fd >= 0) {
            g_direct_drive_path = path;
            const bool strikeOnly = path.find("activate") != std::string::npos ||
                                     path.find("aw8697") != std::string::npos ||
                                     path.find("aw86224") != std::string::npos;
            g_direct_driver_kind.store(
                static_cast<int>(strikeOnly ? DirectDriverKind::StrikeOnly : DirectDriverKind::Continuous),
                std::memory_order_release);
            g_direct_drive_fd.store(fd, std::memory_order_release);

            // Try to find amplitude/gain node in the same directory
            // e.g. if path = /sys/.../activate, look for /sys/.../amplitude
            size_t lastSlash = path.rfind('/');
            std::string dirPath = (lastSlash != std::string::npos) ? path.substr(0, lastSlash) : path;

            // Try different amplitude/gain node types in same directory
            // AW8697 uses "gain" (hex value like 0x80), others use "amplitude" (decimal)
            const char* ampNodeNames[] = {"amplitude", "gain", "index_value", nullptr};
            for (int ai = 0; ampNodeNames[ai] != nullptr; ++ai) {
                std::string amp_path = dirPath + "/" + ampNodeNames[ai];
                int amp_fd = open(amp_path.c_str(), O_WRONLY | O_NONBLOCK);
                if (amp_fd >= 0) {
                    g_direct_amplitude_path = amp_path;
                    g_direct_amplitude_fd.store(amp_fd, std::memory_order_release);
                    break;
                }
            }

            LOGI("[DD] DIRECT DRIVE INIT SUCCESS");
            LOGI("[DD] enable=%s fd=%d", g_direct_drive_path.c_str(), fd);
            LOGI("[DD] amplitude=%s ampFd=%d", 
                g_direct_amplitude_path.empty() ? "(none)" : g_direct_amplitude_path.c_str(),
                g_direct_amplitude_fd.load());
            return true;
        } else {
            LOGW("[DD] open(%s) failed: errno=%d (%s)", path.c_str(), errno, strerror(errno));
        }
    }

    LOGE("[DD] no usable node found; root-assisted transport will be handled by Kotlin");
    return false;
}

// ═════════════════════════════════════════════════════════════════
//  Root Pipe Direct Drive Initialization
//  When open() fails due to SELinux AND su is not available in the
//  hooked process, Kotlin starts a root daemon (su) in the MusicHapticsX
//  app process and passes us the write-end fd of the pipe to su's stdin.
//  We write "echo 1 > /sys/.../activate\n" commands through this pipe.
//  The root shell evaluates each line → writes to sysfs as root.
// ═════════════════════════════════════════════════════════════════
bool init_root_pipe(int pipe_fd, const std::string& enable_path, const std::string& amplitude_path) {
    if (g_use_root_shell.load() && g_root_shell_fd.load() >= 0) {
        LOGI("[DD] root pipe already initialized, fd=%d", g_root_shell_fd.load());
        return true;
    }
    if (pipe_fd < 0 || !is_safe_node_path(enable_path) ||
        (!amplitude_path.empty() && !is_safe_node_path(amplitude_path))) {
        LOGE("[DD] init_root_pipe: invalid fd or node path");
        return false;
    }

    // Test the pipe by sending a no-op command
    const char* testCmd = "true\n";
    ssize_t written = write(pipe_fd, testCmd, strlen(testCmd));
    if (written < 0) {
        LOGE("[DD] init_root_pipe: test write failed: errno=%d (%s)", errno, strerror(errno));
        return false;
    }

    g_direct_drive_path = enable_path;
    g_direct_amplitude_path = amplitude_path;
    const bool strikeOnly = enable_path.find("activate") != std::string::npos ||
                             enable_path.find("aw8697") != std::string::npos ||
                             enable_path.find("aw86224") != std::string::npos;
    g_direct_driver_kind.store(
        static_cast<int>(strikeOnly ? DirectDriverKind::StrikeOnly : DirectDriverKind::Continuous),
        std::memory_order_release);
    g_root_shell_fd.store(pipe_fd, std::memory_order_release);
    g_use_root_shell.store(true, std::memory_order_release);

    LOGI("[DD] ROOT PIPE MODE INIT SUCCESS");
    LOGI("[DD] enable path=%s", enable_path.c_str());
    LOGI("[DD] amplitude path=%s", amplitude_path.empty() ? "(none)" : amplitude_path.c_str());
    LOGI("[DD] pipe_fd=%d", pipe_fd);
    return true;
}

// ═════════════════════════════════════════════════════════════════
//  Root-assisted Direct Drive Initialization
//  When open() fails due to SELinux, Kotlin can open the file
//  via a root subprocess and pass the fd to this function.
//  The fd is a /proc/self/fd/N reference that works in our process.
// ═════════════════════════════════════════════════════════════════
bool init_direct_drive_from_fd(int enable_fd, int amplitude_fd, const std::string& enable_path, const std::string& amplitude_path) {
    if (g_direct_drive_fd.load() >= 0) {
        LOGI("[DD] already initialized, fd=%d", g_direct_drive_fd.load());
        return true;
    }
    if (enable_fd < 0 || !is_safe_node_path(enable_path) ||
        (!amplitude_path.empty() && !is_safe_node_path(amplitude_path))) {
        LOGE("[DD] init_direct_drive_from_fd: invalid fd or node path");
        return false;
    }

    // Verify the fd is actually writable
    int flags = fcntl(enable_fd, F_GETFL);
    if (flags < 0) {
        LOGE("[DD] init_direct_drive_from_fd: fcntl F_GETFL failed: errno=%d", errno);
        return false;
    }

    g_direct_drive_path = enable_path;
    g_direct_drive_fd.store(enable_fd, std::memory_order_release);

    if (amplitude_fd >= 0) {
        g_direct_amplitude_path = amplitude_path;
        g_direct_amplitude_fd.store(amplitude_fd, std::memory_order_release);
    }

    LOGI("[DD] DIRECT DRIVE INIT SUCCESS (root-assisted fd)");
    LOGI("[DD] enable=%s fd=%d flags=0x%x", enable_path.c_str(), enable_fd, flags);
    LOGI("[DD] amplitude=%s ampFd=%d",
        amplitude_path.empty() ? "(none)" : amplitude_path.c_str(),
        g_direct_amplitude_fd.load());
    return true;
}

// Track diagnostic counters for periodic logging
static std::atomic<int> g_dd_tick_count{0};
static std::atomic<bool> g_dd_mode_entered{false};

// AW8697 Haptic Protocol:
//   - "activate" node: writing "1" triggers a single preset waveform playback.
//     It is NOT a duration value. Writing a large number like "5" would be
//     interpreted as waveform index 5, not 5 milliseconds.
//   - "gain" node: controls vibration amplitude. Accepts hex (e.g. "0x80")
//     and decimal values. Range ~0x00–0xFF. 0x80 is default, 0xFF causes
//     distortion/buzzing. We map our 0–255 amplitude to 0x00–0xC8 (safe range).
//   - "duration" node: can optionally set vibration duration before
//     triggering, but for continuous 200Hz drive we just re-trigger quickly.
//
// For other driver types (timed_output, LED vibrator), the activate/enable
// node may accept duration in milliseconds — we detect this heuristically
// by checking the node name in init_direct_drive (stored in g_direct_drive_path).

void trigger_direct_drive(int duration_ms, int amplitude) {
    // ─── Java Pipe Mode ───
    // When direct fd, UDP socket, and root pipe fd all fail, fall back
    // to calling Java's NativeBridge.onRootPipeTrigger() which writes
    // to the su process's OutputStream from the Java side.
    if (g_use_java_pipe.load(std::memory_order_acquire)) {
        JNIEnv* env = nullptr;
        bool attached = false;
        if (g_jvm) {
            if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
                if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                    attached = true;
                }
            }
        }
        if (env) {
            jobject bridge = g_java_pipe_bridge.load(std::memory_order_acquire);
            if (bridge) {
                jclass cls = env->GetObjectClass(bridge);
                if (cls) {
                    // Call onRootPipeTrigger for root pipe vibration
                    jmethodID mid = env->GetMethodID(cls, "onRootPipeTrigger", "(II)V");
                    if (mid) {
                        env->CallVoidMethod(bridge, mid, (jint)amplitude, (jint)duration_ms);
                        if (env->ExceptionCheck()) {
                            env->ExceptionClear();
                        }
                    }
                    env->DeleteLocalRef(cls);
                }
            }
        }
        if (attached) g_jvm->DetachCurrentThread();

        int tick = g_dd_tick_count.fetch_add(1, std::memory_order_relaxed);
        if (tick % 20 == 0) {
            LOGI("[DD-JAVA] tick=%d amp=%d dur=%d", tick, amplitude, duration_ms);
        }
        return;
    }

    // ─── UDP Haptic Mode ───
    // When direct open() fails and root pipe is unavailable (SELinux blocks
    // cross-process fd), we send UDP packets to a root daemon that holds
    // persistent sysfs fds. The daemon writes "1" to activate on receipt.
    bool useUdp = g_use_udp_haptic.load(std::memory_order_acquire);
    if (useUdp) {
        // Map amplitude 0..255 to gain byte, set trigger flag
        uint8_t ampByte = static_cast<uint8_t>(std::clamp(amplitude, 0, 255));
        uint8_t flags = (amplitude > 0) ? 0x01 : 0x00;  // bit0 = trigger
        bool ok = send_haptic_udp(ampByte, static_cast<uint16_t>(duration_ms), flags);

        // Periodic diagnostic
        int tick = g_dd_tick_count.fetch_add(1, std::memory_order_relaxed);
        if (tick % 20 == 0) {
            LOGI("[DD-UDP] tick=%d amp=%d dur=%d sent=%s",
                 tick, amplitude, duration_ms, ok ? "OK" : "FAIL");
        }
        return;
    }

    // ─── Root Pipe Mode ───
    // When direct open() fails due to SELinux, write validated commands through a
    // long-lived root shell pipe that owns the sysfs file descriptor.
    bool useRootPipe = g_use_root_shell.load(std::memory_order_acquire);
    int rootFd = g_root_shell_fd.load(std::memory_order_acquire);

    if (useRootPipe && rootFd >= 0) {
        // Build command: "echo 1 > /sys/.../activate" (+ amplitude if available)
        char cmd[512];
        int cmdLen = 0;

        // Write amplitude first (if amplitude path exists)
        if (!g_direct_amplitude_path.empty() && amplitude > 0) {
            bool isGainNode = g_direct_amplitude_path.find("gain") != std::string::npos;
            if (isGainNode) {
                int gainVal = static_cast<int>(std::clamp(amplitude, 0, 255) * 200 / 255);
                cmdLen = snprintf(cmd, sizeof(cmd), "echo 0x%02x > %s; echo 1 > %s\n",
                    gainVal, g_direct_amplitude_path.c_str(), g_direct_drive_path.c_str());
            } else {
                cmdLen = snprintf(cmd, sizeof(cmd), "echo %d > %s; echo 1 > %s\n",
                    amplitude, g_direct_amplitude_path.c_str(), g_direct_drive_path.c_str());
            }
        } else {
            // Just trigger activate
            bool isAW8697 = g_direct_drive_path.find("activate") != std::string::npos;
            if (isAW8697) {
                cmdLen = snprintf(cmd, sizeof(cmd), "echo 1 > %s\n", g_direct_drive_path.c_str());
            } else {
                cmdLen = snprintf(cmd, sizeof(cmd), "echo %d > %s\n", duration_ms, g_direct_drive_path.c_str());
            }
        }

        ssize_t written = write(rootFd, cmd, cmdLen);
        if (written < 0) {
            LOGW("[DD] root pipe write failed: errno=%d (%s)", errno, strerror(errno));
        }

        // Periodic diagnostic
        int tick = g_dd_tick_count.fetch_add(1, std::memory_order_relaxed);
        if (tick % 20 == 0) {
            LOGI("[DD-ROOT] tick=%d amp=%d written=%zd cmd=%s",
                 tick, amplitude, written, cmd);
        }
        return;
    }

    // ─── Direct FD Mode (original path) ───
    int fd = g_direct_drive_fd.load(std::memory_order_acquire);
    if (fd < 0) return;

    int amp_fd = g_direct_amplitude_fd.load(std::memory_order_acquire);

    ssize_t ampWritten = -1;

    // ─── Write amplitude/gain first (before triggering) ───
    if (amp_fd >= 0 && amplitude > 0) {
        // Detect AW8697-style gain node (writes hex values)
        bool isGainNode = g_direct_amplitude_path.find("gain") != std::string::npos;

        char amp_str[16];
        int amp_len;
        if (isGainNode) {
            // AW8697: map 0..255 to 0x00..0xC8 (200 decimal = safe max)
            // 0x80 (128) = default, 0xFF causes distortion
            int gainVal = static_cast<int>(std::clamp(amplitude, 0, 255) * 200 / 255);
            amp_len = snprintf(amp_str, sizeof(amp_str), "0x%02x", gainVal);
        } else {
            // Generic: write decimal amplitude value
            amp_len = snprintf(amp_str, sizeof(amp_str), "%d", amplitude);
        }
        ampWritten = write(amp_fd, amp_str, amp_len);
        if (ampWritten < 0) {
            LOGW("[DD] amplitude write failed: errno=%d (%s)", errno, strerror(errno));
        }
    }

    // ─── Trigger the vibration ───
    // AW8697: write "1" to activate preset waveform
    // timed_output/LED: write duration in ms
    ssize_t durWritten = -1;
    bool isAW8697 = g_direct_drive_path.find("activate") != std::string::npos;

    if (isAW8697) {
        // Write "1" to trigger one-shot preset waveform
        const char* trigger = "1";
        durWritten = write(fd, trigger, 1);
    } else {
        // Generic driver: write duration in milliseconds
        char dur_str[16];
        int dur_len = snprintf(dur_str, sizeof(dur_str), "%d", duration_ms);
        durWritten = write(fd, dur_str, dur_len);
    }

    if (durWritten < 0) {
        LOGW("[DD] enable write failed: errno=%d (%s)", errno, strerror(errno));
    }

    // Periodic diagnostic (every 20 ticks = 100ms at 5ms/tick)
    int tick = g_dd_tick_count.fetch_add(1, std::memory_order_relaxed);
    if (tick % 20 == 0) {
        LOGI("[DD] tick=%d dur=%d amp=%d ampWritten=%zd enableWritten=%zd aw8697=%d",
             tick, duration_ms, amplitude, ampWritten, durWritten, isAW8697 ? 1 : 0);
    }
}

// Track the global ref so we can clean it up reliably on stop
static std::atomic<jobject> g_bridge_ref{nullptr};

// Onset detection state for beat-triggered vibration (Java Pipe mode)
static float g_prev_kick_onset = 0.0f;
static float g_prev_snare_onset = 0.0f;
static int64_t g_last_beat_trigger_ns = 0;
static constexpr int64_t BEAT_REFRACTORY_NS = 55000000L;  // 55ms hard floor; final policy is applied in Kotlin

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
    jmethodID onBeatTrigger = env->GetMethodID(bridgeClass, "onBeatTrigger", "(Ljava/lang/String;I)V");
    env->DeleteLocalRef(bridgeClass);
    if (!onBeatTrigger) {
        LOGW("[DD] beat callback method unavailable; event output disabled");
    }

    // 5ms precise timing using absolute-time clock_nanosleep
    const long frame_period_ns = 5000000L;  // 5ms for 200Hz Control Loop
    
    struct timespec nextWake;
    clock_gettime(CLOCK_MONOTONIC, &nextWake);

    LOGI("[DD] scheduler thread started, initial fd=%d", g_direct_drive_fd.load());

    // Envelope smoothing state for continuous haptic rendering
    float currentAmp = 0.0f;       // Smoothed output amplitude (0..255)
    float targetAmp = 0.0f;        // Target amplitude before smoothing
    const float attackAlpha = 0.45f;  // Fast attack
    const float releaseAlpha = 0.08f; // Slow release

    // LRA physical model state (for false-color position telemetry)
    float lra_position = 0.0f;
    float lra_velocity = 0.0f;
    float spring_k = 0.8f;
    float damping_c = 0.3f;

    while (g_scheduler_running.load(std::memory_order_acquire)) {
        // === S-LEVEL FIX: Check direct drive state EVERY tick ===
        // Now supports FOUR modes:
        //   1. Direct FD mode: g_direct_drive_fd >= 0
        //   2. Root pipe mode: g_use_root_shell && g_root_shell_fd >= 0
        //   3. UDP haptic mode: g_use_udp_haptic (root daemon on localhost)
        //   4. Java Pipe mode: g_use_java_pipe (C++ callbacks Java OutputStream)
        bool use_direct_drive = 
            (g_direct_drive_fd.load(std::memory_order_acquire) >= 0) ||
            (g_use_root_shell.load(std::memory_order_acquire) && 
             g_root_shell_fd.load(std::memory_order_acquire) >= 0) ||
            (g_use_udp_haptic.load(std::memory_order_acquire)) ||
            (g_use_java_pipe.load(std::memory_order_acquire));

        // Event timing is useful even without a kernel direct-drive node.
        // Drain onset events here so the native scheduler remains the single
        // consumer and Android Vibrator fallback still works on ordinary devices.
        haptic::HapticEngine::OnsetFrame onsetFrames[1] = {};
        const int onsetN = engine->getOnsetFrames(onsetFrames, 1);
        float beatAccent = 0.0f;
        if (onsetN > 0) {
            struct timespec ts;
            clock_gettime(CLOCK_MONOTONIC, &ts);
            const int64_t nowNs = static_cast<int64_t>(ts.tv_sec) * 1000000000L + ts.tv_nsec;
            if (nowNs - g_last_beat_trigger_ns >= BEAT_REFRACTORY_NS) {
                const float kickVal = onsetFrames[0].kick;
                const float snareVal = onsetFrames[0].snare;
                const float vocalVal = onsetFrames[0].vocal;
                const float bodyVal = onsetFrames[0].body;
                constexpr float ONSET_THRESHOLD = 0.08f;
                int eventType = 0; // 1=KICK, 2=SNARE, 3=VOCAL, 4=BODY
                float eventValue = 0.0f;
                if (kickVal >= snareVal && kickVal >= vocalVal && kickVal >= bodyVal && kickVal > ONSET_THRESHOLD) {
                    eventType = 1; eventValue = kickVal;
                } else if (snareVal >= vocalVal && snareVal >= bodyVal && snareVal > ONSET_THRESHOLD) {
                    eventType = 2; eventValue = snareVal;
                } else if (vocalVal >= bodyVal && vocalVal > ONSET_THRESHOLD * 1.5f) {
                    eventType = 3; eventValue = vocalVal;
                } else if (bodyVal > ONSET_THRESHOLD * 2.0f) {
                    eventType = 4; eventValue = bodyVal;
                }
                if (eventType != 0) {
                    const float accentScale = eventType == 1 ? 135.0f : eventType == 2 ? 100.0f : eventType == 3 ? 58.0f : 42.0f;
                    beatAccent = eventValue * accentScale;

                    if (!use_direct_drive && onBeatTrigger) {
                        const int intensity = static_cast<int>(std::clamp(
                            eventValue * (eventType == 1 ? 255.0f : eventType == 2 ? 220.0f : eventType == 3 ? 170.0f : 150.0f),
                            18.0f, 255.0f));
                        const char* eventName = eventType == 1 ? "KICK" : eventType == 2 ? "SNARE" : eventType == 3 ? "VOCAL" : "BODY";
                        jstring eventStr = env->NewStringUTF(eventName);
                        env->CallVoidMethod(bridgeRef, onBeatTrigger, eventStr, static_cast<jint>(intensity));
                        env->DeleteLocalRef(eventStr);
                        if (env->ExceptionCheck()) env->ExceptionClear();
                    }

                    // Hardware nodes named "activate" are usually one-shot waveform
                    // triggers, not a 200 Hz control input. Treat them as strike-only.
                    if (use_direct_drive &&
                        g_direct_driver_kind.load(std::memory_order_acquire) == static_cast<int>(DirectDriverKind::StrikeOnly)) {
                            const int duration = eventType == 1 ? 16 : eventType == 2 ? 13 : eventType == 3 ? 9 : 11;
                            const int amplitude = static_cast<int>(std::clamp(
                                eventValue * (eventType == 1 ? 255.0f : eventType == 2 ? 220.0f : eventType == 3 ? 170.0f : 150.0f),
                                18.0f, 255.0f));
                            trigger_direct_drive(duration, amplitude);
                        }
                    }
                    g_last_beat_trigger_ns = nowNs;
                }
            }
        }

        if (use_direct_drive) {
            const bool strikeOnlyDriver =
                g_direct_driver_kind.load(std::memory_order_acquire) == static_cast<int>(DirectDriverKind::StrikeOnly);

            // Log first entry into direct drive mode
            if (!g_dd_mode_entered.load(std::memory_order_relaxed)) {
                g_dd_mode_entered.store(true, std::memory_order_relaxed);
                LOGI("[DD] DIRECT MODE ENTERED — fd=%d ampFd=%d rootShell=%d rootFd=%d javaPipe=%d",
                     g_direct_drive_fd.load(), g_direct_amplitude_fd.load(),
                     g_use_root_shell.load() ? 1 : 0, g_root_shell_fd.load(),
                     g_use_java_pipe.load() ? 1 : 0);
            }

            // Continuous output is used only for nodes designed for duration/amplitude control.
            // One-shot activate/Awinic nodes are driven exclusively by semantic strikes above.
            if (!strikeOnlyDriver) {
                haptic::SemanticHapticFrame semFrames[1];
                const int semN = engine->getSemanticFrames(semFrames, 1);

                float continuous = 0.0f;
                if (semN > 0) {
                    continuous =
                          semFrames[0].kickAmp  * 0.55f
                        + semFrames[0].snareAmp * 0.25f
                        + semFrames[0].vocalAmp * 0.08f
                        + semFrames[0].bodyAmp  * 0.35f;
                }

                // Target = continuous base + onset accent. The direct path owns the actuator.
                targetAmp = std::clamp(continuous + beatAccent, 0.0f, 255.0f);

                // Smooth envelope: fast attack, slower release.
                const float alpha = (targetAmp > currentAmp) ? attackAlpha : releaseAlpha;
                currentAmp += (targetAmp - currentAmp) * alpha;

                if (semN == 0 && onsetN == 0) {
                    currentAmp *= 0.90f;
                    if (currentAmp < 1.0f) currentAmp = 0.0f;
                }

                if (currentAmp > 1.0f) {
                    const int amplitude = static_cast<int>(currentAmp);
                    trigger_direct_drive(5, amplitude);

                    const float acceleration = (currentAmp / 255.0f) - (spring_k * lra_position) - (damping_c * lra_velocity);
                    lra_velocity += acceleration;
                    lra_position += lra_velocity;
                } else if (g_dd_tick_count.load(std::memory_order_relaxed) % 40 == 0) {
                    LOGI("[DD] idle (no audio), currentAmp=%.1f", currentAmp);
                }
            } else {
                currentAmp = 0.0f;
            }
        } else {
            // No direct actuator is available. Beat events were already delivered
            // through onBeatTrigger; avoid polling an unused legacy continuous ring.
        }

        // Wait for next 5ms boundary (absolute time sleep = zero jitter)
        nextWake.tv_nsec += frame_period_ns;
        if (nextWake.tv_nsec >= 1000000000L) {
            nextWake.tv_sec++;
            nextWake.tv_nsec -= 1000000000L;
        }
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &nextWake, nullptr);
    }

    LOGI("[DD] scheduler thread exiting");

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

JNIEXPORT jboolean JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeSetDirectDriveNodes(
    JNIEnv* env, jobject, jstring nodes) {
    if (!nodes) return JNI_FALSE;
    const char* raw = env->GetStringUTFChars(nodes, nullptr);
    if (!raw) return JNI_FALSE;
    const std::string value(raw);
    env->ReleaseStringUTFChars(nodes, raw);
    return init_direct_drive(value) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeCreateEngine(JNIEnv* env, jobject thiz) {
    auto* engine = new haptic::HapticEngine();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeDestroyEngine(JNIEnv* env, jobject thiz, jlong ptr) {
    // S-LEVEL FIX: Stop scheduler BEFORE deleting engine to prevent use-after-free
    if (g_scheduler_running.load(std::memory_order_acquire)) {
        LOGI("[DD] nativeDestroyEngine: stopping scheduler first");
        g_scheduler_running.store(false, std::memory_order_release);
        pthread_join(g_scheduler_thread, nullptr);
        LOGI("[DD] scheduler joined, safe to delete engine");
    }

    if (ptr != 0) {
        auto* engine = reinterpret_cast<haptic::HapticEngine*>(ptr);
        delete engine;
        LOGI("[DD] engine deleted (ptr=%lld)", (long long)ptr);
    }

    // Close direct drive file descriptors
    int fd = g_direct_drive_fd.exchange(-1, std::memory_order_acq_rel);
    if (fd >= 0) close(fd);
    int ampFd = g_direct_amplitude_fd.exchange(-1, std::memory_order_acq_rel);
    if (ampFd >= 0) close(ampFd);
    g_direct_drive_path.clear();
    g_direct_amplitude_path.clear();
    g_direct_driver_kind.store(static_cast<int>(DirectDriverKind::Unknown), std::memory_order_release);
    g_dd_mode_entered.store(false, std::memory_order_relaxed);
    g_dd_tick_count.store(0, std::memory_order_relaxed);
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
Java_com_mouya_musichaptics_NativeBridge_nativeConfigureProfile(
    JNIEnv* env, jobject thiz, jlong ptr, jfloat dspFloor, jfloat subMult,
    jfloat kickMult, jfloat snareMult, jfloat tickMult, jfloat bodyMult, jfloat refractoryScale) {
    auto* engine = reinterpret_cast<haptic::HapticEngine*>(ptr);
    if (engine) {
        engine->configureProfile(dspFloor, subMult, kickMult, snareMult, tickMult, bodyMult, refractoryScale);
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
//  Native Haptic Scheduler — start/stop
//  Starts a dedicated native thread that consumes native events at a precise 5ms
//  cadence and only crosses into Java for fallback impact events.
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
    jsize capacity = env->GetArrayLength(outBuffer) / 4;
    const int framesToRead = std::min(static_cast<int>(capacity), std::min(static_cast<int>(maxFrames), 64));
    if (framesToRead <= 0) return 0;
    haptic::SemanticHapticFrame frames[64] = {};
    const int count = engine->getSemanticFrames(frames, framesToRead);
    if (count <= 0) return 0;
    float flat[64 * 4] = {};
    for (int i = 0; i < count; ++i) {
        flat[i * 4 + 0] = frames[i].kickAmp;
        flat[i * 4 + 1] = frames[i].snareAmp;
        flat[i * 4 + 2] = frames[i].vocalAmp;
        flat[i * 4 + 3] = frames[i].bodyAmp;
    }
    env->SetFloatArrayRegion(outBuffer, 0, count * 4, flat);
    return count;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeTriggerDirectDriveStrike(JNIEnv*, jobject, jint durationMs, jint amplitude) {
    // Support all three modes: Direct FD, Root Pipe, UDP
    bool hasDirectFd = g_direct_drive_fd.load(std::memory_order_acquire) >= 0;
    bool hasRootPipe = g_use_root_shell.load(std::memory_order_acquire) &&
                       g_root_shell_fd.load(std::memory_order_acquire) >= 0;
    bool hasUdp = g_use_udp_haptic.load(std::memory_order_acquire);
    bool hasJavaPipe = g_use_java_pipe.load(std::memory_order_acquire);
    if (!hasDirectFd && !hasRootPipe && !hasUdp && !hasJavaPipe) return JNI_FALSE;
    trigger_direct_drive(durationMs, amplitude);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeIsDirectDriveAvailable(JNIEnv*, jobject) {
    const bool available =
        g_direct_drive_fd.load(std::memory_order_acquire) >= 0 ||
        (g_use_root_shell.load(std::memory_order_acquire) && g_root_shell_fd.load(std::memory_order_acquire) >= 0) ||
        g_use_udp_haptic.load(std::memory_order_acquire) ||
        g_use_java_pipe.load(std::memory_order_acquire);
    return available ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeGetOnsetFrames(JNIEnv* env, jobject, jlong ptr, jfloatArray outBuffer, jint maxFrames) {
    auto* engine = reinterpret_cast<haptic::HapticEngine*>(ptr);
    if (!engine || !outBuffer) return 0;
    const jsize capacity = env->GetArrayLength(outBuffer) / 4;
    const int framesToRead = std::min(static_cast<int>(capacity), std::min(static_cast<int>(maxFrames), 64));
    if (framesToRead <= 0) return 0;
    haptic::HapticEngine::OnsetFrame frames[64] = {};
    const int count = engine->getOnsetFrames(frames, framesToRead);
    if (count <= 0) return 0;
    float flat[64 * 4] = {};
    for (int i = 0; i < count; ++i) {
        flat[i * 4 + 0] = frames[i].kick;
        flat[i * 4 + 1] = frames[i].snare;
        flat[i * 4 + 2] = frames[i].vocal;
        flat[i * 4 + 3] = frames[i].body;
    }
    env->SetFloatArrayRegion(outBuffer, 0, count * 4, flat);
    return count;
}

// ═════════════════════════════════════════════════════════════════
//  Root-assisted Direct Drive: Kotlin opens sysfs via root subprocess
//  and passes the file descriptors to C++.
// ═════════════════════════════════════════════════════════════════
extern "C" JNIEXPORT jboolean JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeSetDirectDriveFd(
    JNIEnv* env, jobject, jint enable_fd, jint amplitude_fd,
    jstring enable_path, jstring amplitude_path) {

    const char* e_path = enable_path ? env->GetStringUTFChars(enable_path, nullptr) : "";
    const char* a_path = amplitude_path ? env->GetStringUTFChars(amplitude_path, nullptr) : "";

    bool ok = init_direct_drive_from_fd(enable_fd, amplitude_fd,
                                        std::string(e_path), std::string(a_path));

    if (enable_path) env->ReleaseStringUTFChars(enable_path, e_path);
    if (amplitude_path) env->ReleaseStringUTFChars(amplitude_path, a_path);

    return ok ? JNI_TRUE : JNI_FALSE;
}

// ═════════════════════════════════════════════════════════════════
//  Root Pipe Direct Drive: Kotlin starts a su daemon in the
//  MusicHapticsX app process and passes us the pipe fd to su's stdin.
//  C++ writes shell commands through this pipe at 200Hz.
// ═════════════════════════════════════════════════════════════════
extern "C" JNIEXPORT jboolean JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeInitRootPipe(
    JNIEnv* env, jobject, jint pipe_fd, jstring enable_path, jstring amplitude_path) {

    const char* e_path = enable_path ? env->GetStringUTFChars(enable_path, nullptr) : "";
    const char* a_path = amplitude_path ? env->GetStringUTFChars(amplitude_path, nullptr) : "";

    bool ok = init_root_pipe(pipe_fd, std::string(e_path), std::string(a_path));

    if (enable_path) env->ReleaseStringUTFChars(enable_path, e_path);
    if (amplitude_path) env->ReleaseStringUTFChars(amplitude_path, a_path);

    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeIsRootPipeAvailable(JNIEnv*, jobject) {
    return (g_use_root_shell.load(std::memory_order_acquire) &&
            g_root_shell_fd.load(std::memory_order_acquire) >= 0) ? JNI_TRUE : JNI_FALSE;
}

// ═════════════════════════════════════════════════════════════════
//  UDP Haptic Interface — connects to Root Haptic Daemon via UDP
//  Daemon (root) holds persistent fd to sysfs, receives binary packets.
// ═════════════════════════════════════════════════════════════════
extern "C" JNIEXPORT jboolean JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeInitUdpHaptic(JNIEnv*, jobject, jint port) {
    // Seccomp blocks socket() in untrusted_app. Use nativeInitUdpHapticFromFd instead.
    LOGE("[UDP] nativeInitUdpHaptic: socket() blocked by seccomp, use nativeInitUdpHapticFromFd");
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeInitUdpHapticFromFd(JNIEnv*, jobject, jint fd, jint port) {
    if (fd < 0 || port < 1 || port > 65535) return JNI_FALSE;
    return init_haptic_udp_from_fd(static_cast<int>(fd), static_cast<int>(port)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeTestUdpHaptic(JNIEnv*, jobject) {
    // Send a test vibration: amplitude=180, duration=100ms, trigger
    bool ok = send_haptic_udp(180, 100, 0x01);
    LOGI("[UDP] test vibration sent: %s", ok ? "OK" : "FAILED");
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeIsUdpHapticReady(JNIEnv*, jobject) {
    return g_use_udp_haptic.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeShutdownUdpHaptic(JNIEnv*, jobject) {
    shutdown_haptic_udp();
}

// ═════════════════════════════════════════════════════════════════
//  Java Pipe Mode — C++ calls back into Java to write to root su pipe
// ═════════════════════════════════════════════════════════════════
extern "C" JNIEXPORT jboolean JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeEnableJavaPipe(JNIEnv* env, jobject self) {
    // Store a weak global ref to the NativeBridge instance for callbacks
    jobject weak = env->NewWeakGlobalRef(self);
    if (!weak) return JNI_FALSE;

    // Clean up previous ref if any
    jobject prev = g_java_pipe_bridge.exchange(weak, std::memory_order_acq_rel);
    if (prev) env->DeleteWeakGlobalRef(prev);

    g_use_java_pipe.store(true, std::memory_order_release);
    LOGI("[DD] JAVA PIPE MODE ENABLED");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeDisableJavaPipe(JNIEnv* env, jobject) {
    g_use_java_pipe.store(false, std::memory_order_release);
    jobject old = g_java_pipe_bridge.exchange(nullptr, std::memory_order_acq_rel);
    if (old && env) env->DeleteWeakGlobalRef(old);
    LOGI("[DD] JAVA PIPE MODE DISABLED");
}