# CMakeLists.txt — MusicHapticsX Native Haptic Engine
cmake_minimum_required(VERSION 3.22.1)
project("musichaptics_native")

# ─── 现代 C++20 标准 ──────────────────────────────────────────
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ─── 编译参数：O3 + LTO + 符号隐藏 ─────────────────────────────
# 注意：Gradle 会自动添加 -fPIC -fstack-protector-strong 等 Android 必需标志
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fvisibility=hidden -flto")

# ─── 链接参数：防止符号泄漏 ──────────────────────────────────
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--no-undefined")

# ─── HapticEngine 核心库 ──────────────────────────────────────
add_library(
    haptic-engine
    SHARED
    haptic/HapticEngine.cpp
    haptic/FFT.cpp
    haptic/BeatDetector.cpp
    haptic/PatternGenerator.cpp
)

target_include_directories(
    haptic-engine
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/haptic
)

# 仅对 DSP 核心文件开启 -ffast-math，避免影响其他逻辑
target_compile_options(
    haptic-engine
    PRIVATE
    $<$<COMPILE_LANGUAGE:CXX>:-ffast-math>
)

find_library(log-lib log)
target_link_libraries(
    haptic-engine
    ${log-lib}
    m
    atomic
)

# ─── JNI 桥接库 ───────────────────────────────────────────────
add_library(
    native-bridge
    SHARED
    jni/NativeBridge.cpp
)

target_include_directories(
    native-bridge
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/jni
    ${CMAKE_CURRENT_SOURCE_DIR}/haptic
)

target_link_libraries(
    native-bridge
    haptic-engine
    ${log-lib}
    android
    c++_shared
)