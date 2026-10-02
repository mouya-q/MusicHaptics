cmake_minimum_required(VERSION 3.22.1)
project("musichaptics_native")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(native-bridge SHARED
    jni/NativeBridge.cpp
    haptic/HapticEngine.cpp
)

target_include_directories(native-bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/jni
    ${CMAKE_CURRENT_SOURCE_DIR}/haptic
)

target_compile_options(native-bridge PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    # Frame pointers are debug aids; omitting them frees a register on every
    # DSP hot-path call. Symbolicated debugging still works via .eh_frame.
    -fomit-frame-pointer
    # Let the linker strip unreferenced code (LTO + gc-sections shrink the
    # .so that gets injected into every hooked process).
    -ffunction-sections
    -fdata-sections
    -flto
)

target_link_options(native-bridge PRIVATE
    -Wl,--gc-sections
    -Wl,--icf=all
    -flto
)

find_library(log-lib log)
target_link_libraries(native-bridge
    ${log-lib}
    android
    m
    atomic
    c++_static
    unwind
)
