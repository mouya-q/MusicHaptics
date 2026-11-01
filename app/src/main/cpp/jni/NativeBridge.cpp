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
#include "mhx_dsp_abi.h"

// Analysis source selection.
//
// The Rust core runs alongside the existing C++ analyser rather than replacing
// it outright. Two reasons:
//
//  * The C++ path is the one that finally produced working vibration in
//    v5.5.3, so it stays available as an instant fallback if the Rust core
//    misbehaves on a device we cannot test locally.
//  * Both can run over the same PCM for one release, so the telemetry of the
//    two implementations can be compared on real music instead of trusting a
//    port by inspection.
//
// Selection is per-engine and defaults to Rust; mhxSetAnalysisBackend() lets the
// Kotlin side (and the dashboard) switch at runtime.
enum class AnalysisBackend : int {
    Rust = 0,
    Cpp = 1,
};

// A C++ analyser that additionally owns a Rust core.
//
// Inheriting rather than wrapping keeps every existing call site valid: the
// scheduler thread, the direct-drive paths and all JNI signatures still see a
// `haptic::HapticEngine*`, so the verified v5.5.3 output chain is untouched. The
// Rust core is consulted only for analysis, and only when selected.
struct EngineSlot : public haptic::HapticEngine {
    MhxEngineHandle rust = nullptr;
    std::atomic<int> backend{static_cast<int>(AnalysisBackend::Rust)};
    // Set once the Rust core has been proven linkable and callable.
    std::atomic<bool> rust_ready{false};
    /// Counters for the diagnostic line.
    std::atomic<uint64_t> rust_calls{0};
    std::atomic<uint64_t> rust_onsets{0};
};

static JavaVM* g_jvm = nullptr;
static std::atomic<bool> g_scheduler_running{false};
static pthread_t g_scheduler_thread{};

// Read one semantic frame from whichever analyser is active.
//
// Centralised so the four call sites in the scheduler and the JNI accessor all
// agree on the fallback rules instead of each duplicating the backend check.
// Returns the number of frames produced (0 or 1).
static int read_semantic_frame(haptic::HapticEngine* engine,
                               haptic::SemanticHapticFrame* out,
                               int maxFrames) {
    if (!engine) return 0;
    auto* slot = static_cast<EngineSlot*>(engine);
    const bool rustActive = slot->rust_ready.load(std::memory_order_acquire) &&
                            slot->backend.load(std::memory_order_acquire) ==
                                static_cast<int>(AnalysisBackend::Rust);
    if (!rustActive) {
        return engine->getSemanticFrames(out, maxFrames);
    }

    // The Rust core reports already-scaled 0..1 band energies; the C++ semantic
    // frame carries the same four channels plus no loudness, so they map
    // directly. Values are scaled by 255 to match the C++ amplitude convention
    // the downstream strike math expects.
    float frame[5] = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    if (!mhx_engine_pop_semantic(slot->rust, frame)) return 0;
    out[0].kickAmp = frame[0] * 255.0f;
    out[0].snareAmp = frame[1] * 255.0f;
    out[0].vocalAmp = frame[2] * 255.0f;
    out[0].bodyAmp = frame[3] * 255.0f;
    return 1;
}


static std::atomic<int> g_direct_drive_fd{-1};
static std::string g_direct_drive_path = "";
static std::string g_direct_amplitude_path = "";
// Directory holding the haptic sysfs nodes. AW8697 needs a four-step write
// sequence (activate_mode, duration, gain, activate) so the sibling node
// paths must stay available even when only the activate fd could be opened.
static std::string g_direct_node_dir = "";

// AW8697 strike modes, verified against the driver on a 0809 X-axis LRA.
// activate_mode 0 = ram mode (a real one-shot strike), 1 = cont mode,
// 2 = rtp mode (replays the RAM waveform buffer, which is empty unless a
// custom waveform was uploaded). The stock Android vibrator service leaves
// the node in rtp mode, so writing only "activate" does nothing audible.
static constexpr int AW8697_MODE_RAM = 0;

static bool write_node(const std::string& dir, const char* name, const char* value);
static bool is_safe_node_path(const std::string& path);

static bool write_node(const std::string& dir, const char* name, const char* value) {
    if (dir.empty()) return false;
    std::string path = dir + "/" + name;
    if (!is_safe_node_path(path)) return false;
    int fd = open(path.c_str(), O_WRONLY | O_NONBLOCK);
    if (fd < 0) return false;
    const size_t len = strlen(value);
    ssize_t written = write(fd, value, len);
    close(fd);
    return written == static_cast<ssize_t>(len);
}

enum class DirectDriverKind : int {
    Unknown = 0,
    Continuous = 1,
    StrikeOnly = 2,
};
static std::atomic<int> g_direct_driver_kind{static_cast<int>(DirectDriverKind::Unknown)};
static std::atomic<int> g_direct_amplitude_fd{-1};


static std::atomic<int> g_root_shell_fd{-1};
static std::atomic<bool> g_use_root_shell{false};

static std::atomic<bool> g_use_java_pipe{false};
static std::atomic<jobject> g_java_pipe_bridge{nullptr};

static std::atomic<int> g_udp_sock_fd{-1};       
static std::atomic<bool> g_use_udp_haptic{false}; 
static struct sockaddr_in g_udp_daemon_addr;       

// UDP receiver state: 0 = unverified, 1 = verified alive, 2 = confirmed dead.
//
// A connect()ed loopback UDP socket cannot be validated with one probe: the
// first sendto always succeeds, and the ICMP port-unreachable raised when
// nothing is bound to the daemon port only surfaces on the *following* call.
// Two clean probes are therefore required before the transport is trusted, and
// a single ECONNREFUSED is enough to condemn it.
static std::atomic<int> g_udp_peer_state{0};
static std::atomic<int> g_udp_probe_count{0};


#pragma pack(push, 1)
struct HapticUdpPacket {
    uint32_t magic;       
    uint16_t version;     
    uint16_t durationMs;  
    uint8_t  amplitude;   
    uint8_t  flags;       
};
#pragma pack(pop)

static constexpr uint32_t MHX_UDP_MAGIC = 0x3148584D; 
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

// Timestamp (monotonic ms) of the most recent PCM block handed to the DSP.
// Used by the Kotlin layer to tell "user really paused" apart from a spurious
// pause event, without draining the semantic/onset rings the scheduler owns.
static std::atomic<int64_t> g_last_audio_block_ms{0};

static int64_t monotonic_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000L + ts.tv_nsec / 1000000L;
}

static bool init_haptic_udp(int port) {
    
    LOGE("[UDP] init_haptic_udp: socket() blocked by seccomp, use init_haptic_udp_from_fd");
    return false;
}



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
    // Reset verification: a fresh socket has an unproven receiver. Native probes
    // twice before trusting it and remembers a refusal permanently.
    g_udp_peer_state.store(0, std::memory_order_release);
    g_udp_probe_count.store(0, std::memory_order_release);

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

    if (written != sizeof(packet)) {
        // The socket is connect()ed to loopback: when nothing is bound to the
        // daemon port the kernel reports ECONNREFUSED here, which is the only
        // reliable evidence that the receiver is gone. Demote the transport so
        // the engine stops believing direct drive works and falls back to its
        // own vibration path instead of going silent.
        if (errno == ECONNREFUSED || errno == EPIPE || errno == ENOTCONN) {
            g_udp_peer_state.store(2, std::memory_order_release);
        }
        return false;
    }

    return true;
}

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

        
        while (!path.empty() && (path.back() == '\n' || path.back() == '\r' || path.back() == ' '))
            path.pop_back();
        if (path.empty() || !is_safe_node_path(path)) {
            LOGW("[DD] rejecting unsafe node path: %s", path.c_str());
            continue;
        }

        
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

            
            
            size_t lastSlash = path.rfind('/');
            std::string dirPath = (lastSlash != std::string::npos) ? path.substr(0, lastSlash) : path;
            g_direct_node_dir = dirPath;

            
            
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

    // Even though we could not open() the node directly (the target process
    // lacks write permission), we still record the driver kind from the path
    // name so that the scheduler's StrikeOnly logic (beat trigger + texture
    // pulsing) works correctly when the actual writes go through UDP or the
    // root pipe instead.
    if (g_direct_driver_kind.load(std::memory_order_acquire) ==
        static_cast<int>(DirectDriverKind::Unknown)) {
        bool strikeOnly = nodes.find("activate") != std::string::npos ||
                           nodes.find("aw8697") != std::string::npos ||
                           nodes.find("aw86224") != std::string::npos;
        g_direct_driver_kind.store(
            static_cast<int>(strikeOnly ? DirectDriverKind::StrikeOnly : DirectDriverKind::Continuous),
            std::memory_order_release);
        LOGI("[DD] driver kind preset to %s based on node path (open failed but UDP/root-pipe may still work)",
             strikeOnly ? "StrikeOnly" : "Continuous");
    }

    return false;
}

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

    
    const char* testCmd = "true\n";
    ssize_t written = write(pipe_fd, testCmd, strlen(testCmd));
    if (written < 0) {
        LOGE("[DD] init_root_pipe: test write failed: errno=%d (%s)", errno, strerror(errno));
        return false;
    }

    g_direct_drive_path = enable_path;
    g_direct_amplitude_path = amplitude_path;
    // Record the node directory so the AW8697 four-step strike sequence can
    // reach activate_mode and duration even when only the activate node was
    // opened by the root helper.
    if (!enable_path.empty()) {
        size_t lastSlash = enable_path.rfind('/');
        if (lastSlash != std::string::npos) g_direct_node_dir = enable_path.substr(0, lastSlash);
    }
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

static std::atomic<int> g_dd_tick_count{0};
static std::atomic<bool> g_dd_mode_entered{false};

// Whether the UDP transport is initialised and its receiver has been verified.
static bool udp_peer_alive() {
    return g_use_udp_haptic.load(std::memory_order_acquire) &&
           g_udp_peer_state.load(std::memory_order_acquire) == 1;
}



void trigger_direct_drive(int duration_ms, int amplitude) {
    
    
    
    
    if (g_use_java_pipe.load(std::memory_order_acquire)) {
        JNIEnv* env = nullptr;
        bool attached = false;
        if (g_jvm) {
            if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
                if (g_jvm->AttachCurrentThread(reinterpret_cast<JNIEnv**>(&env), nullptr) == JNI_OK) {
                    attached = true;
                }
            }
        }
        if (env) {
            jobject weak = g_java_pipe_bridge.load(std::memory_order_acquire);
            
            
            
            jobject bridge = weak ? env->NewLocalRef(weak) : nullptr;
            if (bridge) {
                jclass cls = env->GetObjectClass(bridge);
                if (cls) {
                    
                    jmethodID mid = env->GetMethodID(cls, "onRootPipeTrigger", "(II)V");
                    if (mid) {
                        env->CallVoidMethod(bridge, mid, (jint)amplitude, (jint)duration_ms);
                        if (env->ExceptionCheck()) {
                            env->ExceptionClear();
                        }
                    }
                    env->DeleteLocalRef(cls);
                }
                env->DeleteLocalRef(bridge);
            }
        }
        if (attached) g_jvm->DetachCurrentThread();

        int tick = g_dd_tick_count.fetch_add(1, std::memory_order_relaxed);
        if (tick % 2000 == 0) {
            LOGI("[DD-JAVA] tick=%d amp=%d dur=%d", tick, amplitude, duration_ms);
        }
        return;
    }

    
    
    
    
    bool useUdp = g_use_udp_haptic.load(std::memory_order_acquire);
    if (useUdp) {
        
        uint8_t ampByte = static_cast<uint8_t>(std::clamp(amplitude, 0, 255));
        uint8_t flags = (amplitude > 0) ? 0x01 : 0x00;  
        bool ok = send_haptic_udp(ampByte, static_cast<uint16_t>(duration_ms), flags);

        
        int tick = g_dd_tick_count.fetch_add(1, std::memory_order_relaxed);
        if (tick % 2000 == 0) {
            LOGI("[DD-UDP] tick=%d amp=%d dur=%d sent=%s",
                 tick, amplitude, duration_ms, ok ? "OK" : "FAIL");
        }
        return;
    }

    
    
    
    bool useRootPipe = g_use_root_shell.load(std::memory_order_acquire);
    int rootFd = g_root_shell_fd.load(std::memory_order_acquire);

    if (useRootPipe && rootFd >= 0) {
        
        char cmd[512];
        int cmdLen = 0;

        
        if (!g_direct_amplitude_path.empty() && amplitude > 0) {
            bool isGainNode = g_direct_amplitude_path.find("gain") != std::string::npos;
            bool isAW8697Pipe = g_direct_drive_path.find("activate") != std::string::npos;
            if (isAW8697Pipe && !g_direct_node_dir.empty()) {
                // Hardware-verified four-step sequence for AW8697:
                // activate_mode=0 (ram) -> duration -> gain (1:1) -> activate=1.
                // Writing only activate while the chip is in rtp mode replays an
                // empty RAM waveform and the motor never moves.
                int gainVal = std::clamp(amplitude, 0, 127);
                cmdLen = snprintf(cmd, sizeof(cmd),
                    "echo 0 > %s/activate_mode; echo %d > %s/duration; echo 0x%02x > %s; echo 1 > %s\n",
                    g_direct_node_dir.c_str(), duration_ms, g_direct_node_dir.c_str(),
                    gainVal, g_direct_amplitude_path.c_str(), g_direct_drive_path.c_str());
            } else if (isGainNode) {
                // gain maps 1:1 onto the driver level register; clamp only.
                int gainVal = std::clamp(amplitude, 0, 127);
                cmdLen = snprintf(cmd, sizeof(cmd), "echo 0x%02x > %s; echo 1 > %s\n",
                    gainVal, g_direct_amplitude_path.c_str(), g_direct_drive_path.c_str());
            } else {
                cmdLen = snprintf(cmd, sizeof(cmd), "echo %d > %s; echo 1 > %s\n",
                    amplitude, g_direct_amplitude_path.c_str(), g_direct_drive_path.c_str());
            }
        } else {
            bool isAW8697 = g_direct_drive_path.find("activate") != std::string::npos;
            if (isAW8697 && !g_direct_node_dir.empty()) {
                cmdLen = snprintf(cmd, sizeof(cmd),
                    "echo 0 > %s/activate_mode; echo %d > %s/duration; echo 1 > %s\n",
                    g_direct_node_dir.c_str(), duration_ms, g_direct_node_dir.c_str(),
                    g_direct_drive_path.c_str());
            } else if (isAW8697) {
                cmdLen = snprintf(cmd, sizeof(cmd), "echo 1 > %s\n", g_direct_drive_path.c_str());
            } else {
                cmdLen = snprintf(cmd, sizeof(cmd), "echo %d > %s\n", duration_ms, g_direct_drive_path.c_str());
            }
        }

        ssize_t written = write(rootFd, cmd, cmdLen);
        if (written < 0) {
            LOGW("[DD] root pipe write failed: errno=%d (%s)", errno, strerror(errno));
        }

        
        int tick = g_dd_tick_count.fetch_add(1, std::memory_order_relaxed);
        if (tick % 2000 == 0) {
            LOGI("[DD-ROOT] tick=%d amp=%d written=%zd cmd=%s",
                 tick, amplitude, written, cmd);
        }
        return;
    }

    
int fd = g_direct_drive_fd.load(std::memory_order_acquire);
    if (fd < 0) return;

    int amp_fd = g_direct_amplitude_fd.load(std::memory_order_acquire);
    ssize_t ampWritten = -1;
    ssize_t durWritten = -1;
    bool isAW8697 = g_direct_drive_path.find("activate") != std::string::npos;

    // AW8697 four-step sequence: activate_mode=0 -> duration -> gain -> activate.
    // The stock driver leaves activate_mode in rtp mode (2); writing only
    // activate replays an empty RAM waveform and the motor never moves.
    if (isAW8697 && !g_direct_node_dir.empty()) {
        write_node(g_direct_node_dir, "activate_mode", "0");
        write_node(g_direct_node_dir, "duration", std::to_string(duration_ms).c_str());
    }

    if (amp_fd >= 0 && amplitude > 0) {
        bool isGainNode = g_direct_amplitude_path.find("gain") != std::string::npos;
        char amp_str[16];
        int amp_len;
        if (isGainNode) {
            // gain maps 1:1 onto the driver level register; clamp only.
            int gainVal = std::clamp(amplitude, 0, 127);
            amp_len = snprintf(amp_str, sizeof(amp_str), "0x%02x", gainVal);
        } else {
            amp_len = snprintf(amp_str, sizeof(amp_str), "%d", amplitude);
        }
        ampWritten = write(amp_fd, amp_str, amp_len);
        if (ampWritten < 0) {
            LOGW("[DD] amplitude write failed: errno=%d (%s)", errno, strerror(errno));
        }
    }

    if (isAW8697) {
        const char* trigger = "1";
        durWritten = write(fd, trigger, 1);
    } else {
        char dur_str[16];
        int dur_len = snprintf(dur_str, sizeof(dur_str), "%d", duration_ms);
        durWritten = write(fd, dur_str, dur_len);
    }

    if (durWritten < 0) {
        LOGW("[DD] enable write failed: errno=%d (%s)", errno, strerror(errno));
    }

    
    int tick = g_dd_tick_count.fetch_add(1, std::memory_order_relaxed);
    if (tick % 2000 == 0) {
        LOGI("[DD] tick=%d dur=%d amp=%d ampWritten=%zd enableWritten=%zd aw8697=%d",
             tick, duration_ms, amplitude, ampWritten, durWritten, isAW8697 ? 1 : 0);
    }
}


static std::atomic<jobject> g_bridge_ref{nullptr};


static float g_prev_kick_onset = 0.0f;
static float g_prev_snare_onset = 0.0f;
static int64_t g_last_beat_trigger_ns = 0;
static constexpr int64_t BEAT_REFRACTORY_NS = 30000000L;  

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
    char threadName[] = "HapticScheduler";
    JavaVMAttachArgs attachArgs = {JNI_VERSION_1_6, threadName, nullptr};
    if (g_jvm->AttachCurrentThread(reinterpret_cast<JNIEnv**>(&env), &attachArgs) != JNI_OK) {
        return nullptr;
    }

    
    jclass bridgeClass = env->GetObjectClass(bridgeRef);
    jmethodID onBeatTrigger = env->GetMethodID(bridgeClass, "onBeatTrigger", "(Ljava/lang/String;I)V");
    env->DeleteLocalRef(bridgeClass);
    if (!onBeatTrigger) {
        LOGW("[DD] beat callback method unavailable; event output disabled");
    }

    
    
    
    jstring evKick  = onBeatTrigger ? (jstring)env->NewGlobalRef(env->NewStringUTF("KICK"))  : nullptr;
    jstring evSnare = onBeatTrigger ? (jstring)env->NewGlobalRef(env->NewStringUTF("SNARE")) : nullptr;
    jstring evVocal = onBeatTrigger ? (jstring)env->NewGlobalRef(env->NewStringUTF("VOCAL")) : nullptr;
    jstring evBody  = onBeatTrigger ? (jstring)env->NewGlobalRef(env->NewStringUTF("BODY"))  : nullptr;

    
    const long frame_period_ns = 5000000L;  
    
    struct timespec nextWake;
    clock_gettime(CLOCK_MONOTONIC, &nextWake);

    LOGI("[DD] scheduler thread started, initial fd=%d", g_direct_drive_fd.load());

    
    float currentAmp = 0.0f;       
    float targetAmp = 0.0f;        
    
    
    float lra_position = 0.0f;
    float lra_velocity = 0.0f;
    float spring_k = 0.8f;
    float damping_c = 0.3f;

    while (g_scheduler_running.load(std::memory_order_acquire)) {
        
        
        
        
        
        
        bool use_direct_drive = 
            (g_direct_drive_fd.load(std::memory_order_acquire) >= 0) ||
            (g_use_root_shell.load(std::memory_order_acquire) && 
             g_root_shell_fd.load(std::memory_order_acquire) >= 0) ||
            udp_peer_alive() ||
            (g_use_java_pipe.load(std::memory_order_acquire));

        
        
        
        haptic::HapticEngine::OnsetFrame onsetFrames[1] = {};
        int onsetN = 0;

        // Pull onsets from whichever analyser is active. The Rust core exposes
        // its frames through mhx_engine_pop_onset; the C++ analyser through
        // getOnsetFrames. Both feed the same downstream strike logic, so the
        // hardware side is unaffected by the choice.
        auto* slot = static_cast<EngineSlot*>(engine);
        const bool rustActive = slot->rust_ready.load(std::memory_order_acquire) &&
                                slot->backend.load(std::memory_order_acquire) ==
                                    static_cast<int>(AnalysisBackend::Rust);
        if (rustActive) {
            float rustFrame[4] = {0.0f, 0.0f, 0.0f, 0.0f};
            if (mhx_engine_pop_onset(slot->rust, rustFrame)) {
                onsetFrames[0].kick = rustFrame[0];
                onsetFrames[0].snare = rustFrame[1];
                onsetFrames[0].vocal = rustFrame[2];
                onsetFrames[0].body = rustFrame[3];
                onsetN = 1;
            }
        } else {
            onsetN = engine->getOnsetFrames(onsetFrames, 1);
        }
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
                const float onsetThreshold = engine->getOutputOnsetThreshold();
                int eventType = 0; 
                float eventValue = 0.0f;
                if (kickVal >= snareVal && kickVal >= vocalVal && kickVal >= bodyVal && kickVal > onsetThreshold) {
                    eventType = 1; eventValue = kickVal;
                } else if (snareVal >= vocalVal && snareVal >= bodyVal && snareVal > onsetThreshold) {
                    eventType = 2; eventValue = snareVal;
                } else if (vocalVal >= bodyVal && vocalVal > onsetThreshold * 0.8f) {
                    eventType = 3; eventValue = vocalVal;
                } else if (bodyVal > onsetThreshold * 1.2f) {
                    eventType = 4; eventValue = bodyVal;
                }
                if (eventType != 0) {
                    const float accentScale = eventType == 1 ? 135.0f : eventType == 2 ? 100.0f : eventType == 3 ? 58.0f : 42.0f;
                    beatAccent = eventValue * accentScale;

                    // Blend the onset transient with the continuous RMS energy
                    // so that the same drum hit in a loud chorus produces a
                    // stronger buzz than in a quiet verse. The onset value
                    // alone only captures the momentary spike, not the overall
                    // volume of the music at that point.
                    haptic::SemanticHapticFrame semFr[1] = {};
                    const int semN = read_semantic_frame(engine, semFr, 1);
                    float rmsEnergy = 0.0f;
                    if (semN > 0) {
                        rmsEnergy = std::clamp(
                            semFr[0].kickAmp * 0.45f
                            + semFr[0].snareAmp * 0.25f
                            + semFr[0].vocalAmp * 0.10f
                            + semFr[0].bodyAmp * 0.35f, 0.0f, 1.0f);
                    }

                    // Character scale per event type, shared by both the
                    // Kotlin callback and the StrikeOnly direct-drive strike.
                    const float eventCharScale = eventType == 1 ? 1.0f : eventType == 2 ? 0.85f : eventType == 3 ? 0.60f : 0.50f;
                    // Mix 55% onset transient + 45% continuous RMS so that
                    // the overall music volume has a visible effect on the
                    // haptic intensity, not just the transient sharpness.
                    const float blended = eventValue * 0.55f + rmsEnergy * 0.45f;
                    const int intensity = static_cast<int>(std::clamp(
                        blended * eventCharScale * 255.0f,
                        10.0f, 255.0f));

                    if (onBeatTrigger) {
                        jstring eventName = eventType == 1 ? evKick : eventType == 2 ? evSnare : eventType == 3 ? evVocal : evBody;
                        if (eventName) {
                            env->CallVoidMethod(bridgeRef, onBeatTrigger, eventName, static_cast<jint>(intensity));
                            if (env->ExceptionCheck()) env->ExceptionClear();
                        }
                    }

                    // For StrikeOnly devices (AW8697 etc), the Kotlin waveform
                    // path degrades to DEFAULT_AMPLITUDE one-shots which are
                    // barely perceptible. Instead, fire a direct-drive strike
                    // with the full intensity so the hardware LRA actually
                    // moves. The Kotlin callback is still invoked for logging
                    // but the real haptic output comes from this sysfs write.
                    if (use_direct_drive &&
                        g_direct_driver_kind.load(std::memory_order_acquire) ==
                            static_cast<int>(DirectDriverKind::StrikeOnly)) {
                        // Scale the beat intensity into a gain value the AW8697
                        // understands. The intensity is already 10-255 from
                        // the blended onset+RMS calculation above.
                        // Use the user amplitude to scale the final gain so
                        // the UI level slider has a real effect on strike power.
                        const float userAmp = engine->getUserAmplitude();
                        const int strikeAmp = static_cast<int>(
                            std::clamp(intensity * userAmp, 10.0f, 255.0f));
                        // Duration: short strike (5ms default for AW8697 activate).
                        trigger_direct_drive(5, strikeAmp);
                    }

                    g_last_beat_trigger_ns = nowNs;
                }
            }
        }

        // RMS-based texture pulsing for StrikeOnly devices.
        // Even though the device can only do one-shot strikes, we can create a
        // perceived continuous texture by pulsing at ~50Hz with amplitude
        // proportional to the audio's RMS energy. This gives the "textures"
        // sensation that Apple Music Haptics provides.
        if (use_direct_drive &&
            g_direct_driver_kind.load(std::memory_order_acquire) == static_cast<int>(DirectDriverKind::StrikeOnly)) {
            haptic::SemanticHapticFrame texFrames[1];
            const int texN = read_semantic_frame(engine, texFrames, 1);
            if (texN > 0) {
                const float texEnergy =
                      texFrames[0].kickAmp  * 0.45f
                    + texFrames[0].snareAmp * 0.20f
                    + texFrames[0].vocalAmp * 0.15f
                    + texFrames[0].bodyAmp  * 0.30f;
                const float styleAmpScale = engine->getOutputStyleAmpScale();
                const float masterGain = engine->getOutputMasterGain();
                const float userAmp = engine->getUserAmplitude();
                const float texAmp = std::clamp(texEnergy * styleAmpScale * masterGain * userAmp * 255.0f, 0.0f, 255.0f);
                // Only pulse if energy is above a perceptual threshold
                if (texAmp > 15.0f) {
                    // Pulse every ~20ms (every 4th frame at 5ms) to avoid overloading
                    // the sysfs interface, with amplitude tracking the music's energy.
                    static int texPulseCounter = 0;
                    texPulseCounter++;
                    if (texPulseCounter >= 4) {
                        texPulseCounter = 0;
                        trigger_direct_drive(5, static_cast<int>(texAmp));
                    }
                }
            }
        }

        if (use_direct_drive) {
            const bool strikeOnlyDriver =
                g_direct_driver_kind.load(std::memory_order_acquire) == static_cast<int>(DirectDriverKind::StrikeOnly);

            
            if (!g_dd_mode_entered.load(std::memory_order_relaxed)) {
                g_dd_mode_entered.store(true, std::memory_order_relaxed);
                LOGI("[DD] DIRECT MODE ENTERED — fd=%d ampFd=%d rootShell=%d rootFd=%d javaPipe=%d",
                     g_direct_drive_fd.load(), g_direct_amplitude_fd.load(),
                     g_use_root_shell.load() ? 1 : 0, g_root_shell_fd.load(),
                     g_use_java_pipe.load() ? 1 : 0);
            }

            
            
            if (!strikeOnlyDriver) {
                haptic::SemanticHapticFrame semFrames[1];
                const int semN = read_semantic_frame(engine, semFrames, 1);

                float continuous = 0.0f;
                if (semN > 0) {
                    continuous =
                          semFrames[0].kickAmp  * 0.55f
                        + semFrames[0].snareAmp * 0.25f
                        + semFrames[0].vocalAmp * 0.08f
                        + semFrames[0].bodyAmp  * 0.35f;
                }

                
                const float styleAmpScale = engine->getOutputStyleAmpScale();
                const float accentScaleUser = engine->getOutputAccentScale();
                const float bassBoost = engine->getOutputBassBoost();
                const float masterGain = engine->getOutputMasterGain();
                const float userAmp = engine->getUserAmplitude();
                const float attackScale = engine->getOutputAttackScale();
                const float sharpness = engine->getOutputSharpness();
                const float sustain = engine->getOutputSustainLevel();
                const float attackMs = engine->getOutputAttackImpactMs() * attackScale / std::max(sharpness, 0.05f);
                const float releaseMs = engine->getOutputReleaseMs() / std::max(sustain + 0.20f, 0.20f);
                const float attackAlpha = 1.0f - std::exp(-5.0f / std::max(attackMs, 2.0f));
                const float releaseAlpha = 1.0f - std::exp(-5.0f / std::max(releaseMs, 8.0f));

                beatAccent *= accentScaleUser;
                if (onsetN > 0 && onsetFrames[0].kick > 0.0f) beatAccent *= bassBoost;
                targetAmp = std::clamp((continuous + beatAccent) * styleAmpScale * masterGain * userAmp, 0.0f, 255.0f);

                const float alpha = (targetAmp > currentAmp) ? attackAlpha : releaseAlpha;
                currentAmp += (targetAmp - currentAmp) * alpha;

                if (semN == 0 && onsetN == 0) {
                    currentAmp += (0.0f - currentAmp) * releaseAlpha;
                    if (currentAmp < 1.0f) currentAmp = 0.0f;
                }

                if (currentAmp > 1.0f) {
                    const int amplitude = static_cast<int>(currentAmp);
                    trigger_direct_drive(5, amplitude);

                    const float f0 = engine->getOutputLraF0();
                    const float q = engine->getOutputLraQ();
                    const float omega = 2.0f * static_cast<float>(M_PI) * f0;
                    const float springK = std::clamp((omega * 0.005f) * (omega * 0.005f), 0.20f, 4.00f);
                    const float dampingC = std::clamp((omega * 0.005f) / std::max(q, 0.25f), 0.05f, 1.50f);
                    const float acceleration = (currentAmp / 255.0f) - (springK * lra_position) - (dampingC * lra_velocity);
                    lra_velocity += acceleration;
                    lra_position += lra_velocity;
                } else if (g_dd_tick_count.load(std::memory_order_relaxed) % 2400 == 0) {
                    LOGI("[DD] idle (no audio), currentAmp=%.1f", currentAmp);
                }
            } else {
                currentAmp = 0.0f;
            }
        } else {
            
            
        }

        
        nextWake.tv_nsec += frame_period_ns;
        if (nextWake.tv_nsec >= 1000000000L) {
            nextWake.tv_sec++;
            nextWake.tv_nsec -= 1000000000L;
        }
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &nextWake, nullptr);
    }

    LOGI("[DD] scheduler thread exiting");

    
    
    env->DeleteGlobalRef(bridgeRef);
    if (evKick)  env->DeleteGlobalRef(evKick);
    if (evSnare) env->DeleteGlobalRef(evSnare);
    if (evVocal) env->DeleteGlobalRef(evVocal);
    if (evBody)  env->DeleteGlobalRef(evBody);
    g_jvm->DetachCurrentThread();
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
    auto* slot = new EngineSlot();

    // Prove the Rust archive actually linked before trusting it with audio.
    // A failed static link can still yield a loadable .so whose analysis calls
    // resolve to nothing, which would look exactly like "no beats" -- the exact
    // failure mode this refactor is meant to eliminate.
    const uint32_t abi = mhx_abi_version();
    if (abi != MHX_DSP_ABI_VERSION) {
        LOGE("[DSP] ABI mismatch: header=%u library=%u -- Rust core disabled",
             MHX_DSP_ABI_VERSION, abi);
    } else if (mhx_self_test() != 0) {
        LOGE("[DSP] Rust self-test failed -- falling back to the C++ analyser");
    } else {
        slot->rust = mhx_engine_create(48000.0f);
        if (slot->rust) {
            slot->rust_ready.store(true, std::memory_order_release);
            LOGI("[DSP] Rust core ready (abi=%u)", abi);
        } else {
            LOGE("[DSP] mhx_engine_create returned null -- falling back to C++");
        }
    }

    return reinterpret_cast<jlong>(slot);
}

JNIEXPORT jboolean JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeIsRustCoreAvailable(JNIEnv*, jobject, jlong ptr) {
    if (ptr == 0) return JNI_FALSE;
    auto* slot = reinterpret_cast<EngineSlot*>(ptr);
    return slot->rust_ready.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

// 0 = Rust, 1 = C++. Returns the backend actually in effect, so the caller can
// report a fallback instead of silently believing the switch took.
JNIEXPORT jint JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeSetAnalysisBackend(JNIEnv*, jobject, jlong ptr, jint backend) {
    if (ptr == 0) return 1;
    auto* slot = reinterpret_cast<EngineSlot*>(ptr);
    if (backend == 0 && !slot->rust_ready.load(std::memory_order_acquire)) {
        slot->backend.store(1, std::memory_order_release);
        return 1;
    }
    slot->backend.store(backend == 0 ? 0 : 1, std::memory_order_release);
    return slot->backend.load(std::memory_order_acquire);
}

JNIEXPORT jint JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeGetAnalysisBackend(JNIEnv*, jobject, jlong ptr) {
    if (ptr == 0) return 1;
    auto* slot = reinterpret_cast<EngineSlot*>(ptr);
    return slot->backend.load(std::memory_order_acquire);
}

JNIEXPORT void JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeDestroyEngine(JNIEnv* env, jobject thiz, jlong ptr) {
    
    if (g_scheduler_running.load(std::memory_order_acquire)) {
        LOGI("[DD] nativeDestroyEngine: stopping scheduler first");
        g_scheduler_running.store(false, std::memory_order_release);
        pthread_join(g_scheduler_thread, nullptr);
        LOGI("[DD] scheduler joined, safe to delete engine");
    }

    if (ptr != 0) {
        auto* slot = reinterpret_cast<EngineSlot*>(ptr);
        // Release the Rust core first: the scheduler is already joined above, so
        // no analysis call can be in flight.
        if (slot->rust) {
            mhx_engine_destroy(slot->rust);
            slot->rust = nullptr;
            slot->rust_ready.store(false, std::memory_order_release);
        }
        delete slot;
        LOGI("[DD] engine deleted (ptr=%lld)", (long long)ptr);
    }

    
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
        // Keep the Rust analyser on the same rate. Without this its band filters
        // keep the construction-time coefficients, and a track at 44.1 kHz would
        // be analysed with 48 kHz edges -- a ~9% shift across every band.
        auto* slot = static_cast<EngineSlot*>(engine);
        if (slot->rust_ready.load(std::memory_order_acquire) && slot->rust) {
            if (mhx_engine_set_sample_rate(slot->rust, sampleRate) == 1) {
                LOGI("[DSP] Rust core rebuilt band filters for %.0f Hz", sampleRate);
            }
        }
    }
}

JNIEXPORT void JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeConfigureOutput(
    JNIEnv* env, jobject thiz, jlong ptr, jfloat styleAmpScale, jfloat sharpness, jfloat attackScale,
    jfloat accentScale, jfloat bassBoost, jfloat impactGain, jfloat continuousGain, jfloat textureGain,
    jfloat masterGain, jfloat onsetThreshold, jfloat attackImpactMs, jfloat decayImpactMs,
    jfloat attackContinuousMs, jfloat decayContinuousMs, jfloat releaseMs, jfloat sustainLevel,
    jfloat lraF0, jfloat lraQ) {
    auto* engine = reinterpret_cast<haptic::HapticEngine*>(ptr);
    if (engine) {
        engine->configureOutput(styleAmpScale, sharpness, attackScale, accentScale, bassBoost,
                                impactGain, continuousGain, textureGain, masterGain, onsetThreshold,
                                attackImpactMs, decayImpactMs, attackContinuousMs, decayContinuousMs,
                                releaseMs, sustainLevel, lraF0, lraQ);
        // Mirror the two knobs the Rust analyser owns: the UI level scales
        // published band energy, and the onset gate is derived from sensitivity.
        auto* slot = static_cast<EngineSlot*>(engine);
        if (slot->rust_ready.load(std::memory_order_acquire) && slot->rust) {
            mhx_engine_set_user_amplitude(slot->rust, styleAmpScale);
            // `sharpness` in the C++ path biases the transient gate; map it onto
            // the Rust sensitivity so the UI style still has an audible effect.
            mhx_engine_set_sensitivity(slot->rust, 0.5f + sharpness);
        }
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

    
    
    
    
    jfloat* telemetry = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(outTelemetry, nullptr));
    if (!telemetry) return;

    auto* slot = static_cast<EngineSlot*>(engine);
    const bool useRust = slot->rust_ready.load(std::memory_order_acquire) &&
                         slot->backend.load(std::memory_order_acquire) ==
                             static_cast<int>(AnalysisBackend::Rust);

    if (useRust) {
        // Rust writes its own telemetry layout into the shared array. Onsets are
        // drained from the Rust rings by the scheduler thread through
        // mhx_engine_pop_onset, so nothing extra is needed here beyond the
        // counters used by the diagnostic line.
        mhx_engine_process(slot->rust, inputPtr, size, telemetry);
        slot->rust_calls.fetch_add(1, std::memory_order_relaxed);
        const uint64_t emitted = static_cast<uint64_t>(telemetry[13] < 0.0f ? 0.0f : telemetry[13]);
        slot->rust_onsets.store(emitted, std::memory_order_relaxed);
    } else {
        engine->processAudioBlock(inputPtr, size, telemetry);
    }

    env->ReleasePrimitiveArrayCritical(outTelemetry, telemetry, 0);
    // Record arrival only after the DSP accepted the block: a crashed or
    // rejected block must not look like healthy audio activity.
    g_last_audio_block_ms.store(monotonic_ms(), std::memory_order_release);
}

// Milliseconds since the last PCM block reached the DSP, or -1 when no block
// has ever arrived in this process. The Kotlin gate uses this instead of a
// drained-frame count because it must keep working while the onset and
// semantic rings are being consumed by the native scheduler thread.
extern "C" JNIEXPORT jlong JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeAudioSilenceMs(JNIEnv*, jobject) {
    const int64_t last = g_last_audio_block_ms.load(std::memory_order_acquire);
    if (last <= 0) return -1;
    const int64_t now = monotonic_ms();
    const int64_t age = now - last;
    return age > 0 ? static_cast<jlong>(age) : 0;
}

JNIEXPORT void JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeClearHapticBuffer(
    JNIEnv* env, jobject thiz, jlong ptr) {
    auto* engine = reinterpret_cast<haptic::HapticEngine*>(ptr);
    if (engine) {
        engine->clearHapticBuffer();
        auto* slot = static_cast<EngineSlot*>(engine);
        if (slot->rust_ready.load(std::memory_order_acquire) && slot->rust) {
            mhx_engine_reset(slot->rust);
        }
    }
}
JNIEXPORT jboolean JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeStartScheduler(
    JNIEnv* env, jobject thiz, jlong ptr) {
    if (ptr == 0) return JNI_FALSE;
    if (g_scheduler_running.load(std::memory_order_relaxed)) return JNI_TRUE;

    auto* engine = reinterpret_cast<haptic::HapticEngine*>(ptr);
    if (!engine) return JNI_FALSE;

    
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

    
    pthread_setname_np(g_scheduler_thread, "HapticScheduler");

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeStopScheduler(
    JNIEnv* env, jobject thiz) {
    if (!g_scheduler_running.load(std::memory_order_relaxed)) return;

    g_scheduler_running.store(false, std::memory_order_relaxed);
    pthread_join(g_scheduler_thread, nullptr);

    
    
    jobject ref = g_bridge_ref.exchange(nullptr, std::memory_order_acq_rel);
    if (ref) {
        env->DeleteGlobalRef(ref);
    }
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

} 
extern "C" JNIEXPORT jint JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeGetSemanticFrames(JNIEnv* env, jobject, jlong ptr, jfloatArray outBuffer, jint maxFrames) {
    auto* engine = reinterpret_cast<haptic::HapticEngine*>(ptr);
    if (!engine || !outBuffer) return 0;
    jsize capacity = env->GetArrayLength(outBuffer) / 4;
    const int framesToRead = std::min(static_cast<int>(capacity), std::min(static_cast<int>(maxFrames), 64));
    if (framesToRead <= 0) return 0;
    haptic::SemanticHapticFrame frames[64] = {};
    const int count = read_semantic_frame(engine, frames, framesToRead);
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
    // Only transports with a *verified* receiver count as available.
    //
    // Previously this returned true as soon as g_use_udp_haptic was set, which
    // happens the moment a DatagramSocket is created -- even when su is refused
    // and no daemon is listening. Kotlin then classified the beat as
    // "NativeDD" and skipped its own vibration path, so the device went
    // completely silent while native believed it was driving the hardware.
    // That single optimistic check is the root cause of the "no vibration at
    // all" reports on v5.4.6/v5.4.7/v5.5.0.
    const bool available =
        g_direct_drive_fd.load(std::memory_order_acquire) >= 0 ||
        (g_use_root_shell.load(std::memory_order_acquire) &&
         g_root_shell_fd.load(std::memory_order_acquire) >= 0) ||
        g_use_java_pipe.load(std::memory_order_acquire) ||
        udp_peer_alive();
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeInitUdpHaptic(JNIEnv*, jobject, jint port) {
    
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
    
    bool ok = send_haptic_udp(180, 100, 0x01);
    LOGI("[UDP] test vibration sent: %s", ok ? "OK" : "FAILED");
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeIsUdpHapticReady(JNIEnv*, jobject) {
    // Reports a transport with a *verified* receiver, not merely a live socket.
    //
    // A connect()ed loopback UDP socket cannot be validated with a single send:
    // the first sendto always succeeds and the ICMP port-unreachable raised when
    // nothing is bound to the daemon port only surfaces on the *following* call.
    // Two clean probes are therefore required before the transport is trusted,
    // which is exactly the difference between "a socket exists" (always true,
    // even with su refused and no daemon) and "someone is reading the packets".
    if (!g_use_udp_haptic.load(std::memory_order_acquire)) return JNI_FALSE;
    const int fd = g_udp_sock_fd.load(std::memory_order_acquire);
    if (fd < 0) return JNI_FALSE;
    // 2 = already condemned; a dead daemon never comes back without a re-init.
    if (g_udp_peer_state.load(std::memory_order_acquire) == 2) return JNI_FALSE;

    HapticUdpPacket probe{};
    probe.magic = MHX_UDP_MAGIC;
    probe.version = MHX_UDP_VERSION;
    probe.durationMs = 0;
    probe.amplitude = 0;
    probe.flags = 0;

    errno = 0;
    const ssize_t written = sendto(
        fd, &probe, sizeof(probe), MSG_DONTWAIT,
        reinterpret_cast<const sockaddr*>(&g_udp_daemon_addr), sizeof(g_udp_daemon_addr));

    if (written != sizeof(probe) ||
        errno == ECONNREFUSED || errno == EPIPE || errno == ENOTCONN) {
        g_udp_peer_state.store(2, std::memory_order_release);
        LOGI("[UDP] peer probe failed (errno=%d) -> transport confirmed dead", errno);
        return JNI_FALSE;
    }

    const int probes = g_udp_probe_count.fetch_add(1, std::memory_order_relaxed) + 1;
    if (probes >= 2) {
        const int prev = g_udp_peer_state.exchange(1, std::memory_order_acq_rel);
        if (prev != 1) LOGI("[UDP] peer verified alive after %d probes", probes);
    }
    return g_udp_peer_state.load(std::memory_order_acquire) == 1 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeShutdownUdpHaptic(JNIEnv*, jobject) {
    shutdown_haptic_udp();
}




extern "C" JNIEXPORT jboolean JNICALL
Java_com_mouya_musichaptics_NativeBridge_nativeEnableJavaPipe(JNIEnv* env, jobject self) {
    
    jobject weak = env->NewWeakGlobalRef(self);
    if (!weak) return JNI_FALSE;

    
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