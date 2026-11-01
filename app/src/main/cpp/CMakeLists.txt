cmake_minimum_required(VERSION 3.22.1)
project("musichaptics_native")
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Keep PT_LOAD segments 16 KB aligned.
add_link_options(-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)

add_library(native-bridge SHARED
    jni/NativeBridge.cpp
    haptic/HapticEngine.cpp
)

# Rust DSP core, built by app/src/main/rust/build-android.sh before Gradle
# invokes CMake. Linked as a static archive so the loader contract inside the
# hooked process stays exactly one System.loadLibrary("native-bridge") call.
set(MHX_RUST_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../rust")
set(MHX_RUST_LIB "${MHX_RUST_DIR}/target/jniLibs/libmhx_dsp.a")

if(EXISTS "${MHX_RUST_LIB}")
    message(STATUS "MHX: linking Rust DSP core from ${MHX_RUST_LIB}")
    add_library(mhx_dsp STATIC IMPORTED)
    set_target_properties(mhx_dsp PROPERTIES
        IMPORTED_LOCATION "${MHX_RUST_LIB}"
    )
    set(MHX_HAVE_RUST TRUE)
else()
    # Hard failure rather than a silent fallback: a build without the Rust core
    # would still start but report "Rust core disabled", which looks like a
    # device problem instead of a CI problem.
    message(FATAL_ERROR
        "MHX: Rust DSP core not found at ${MHX_RUST_LIB}. "
        "Run app/src/main/rust/build-android.sh (or the CI rust-core job) first.")
endif()

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
    # Remove frame pointers from hot paths.
    -fomit-frame-pointer
    # Strip unused code and data.
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
    mhx_dsp
    ${log-lib}
    android
    m
    atomic
    c++_static
    unwind
)