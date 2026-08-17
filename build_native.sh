#!/usr/bin/env bash
# Build native .so with 16KB page alignment
set -euo pipefail

SRC="D:/work/MusicHapticsX/app/src/main/cpp"
OUT="D:/work/MusicHapticsX/app/src/main/jniLibs/arm64-v8a"
TC="D:/work/MusicHapticsX/local-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/Windows-x86_64"
CXX="bash $TC/bin/aarch64-linux-android28-clang++"
AR="bash $TC/bin/llvm-ar"
SYSROOT="$TC/sysroot"

CFLAGS="-O3 -flto -ffast-math -std=c++20 -fvisibility=hidden -fexceptions -fPIC"
LDFLAGS="-Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
LDFLAGS_STATIC="-static-libstdc++ -lc++abi"

mkdir -p "$OUT"

# 1. haptic-engine objects
echo "== Compiling haptic-engine =="
$CXX $CFLAGS -c -I "$SRC/haptic" "$SRC/haptic/HapticEngine.cpp" -o /tmp/HEngine.o
$CXX $CFLAGS -c -I "$SRC/haptic" "$SRC/haptic/FFT.cpp" -o /tmp/FFT.o
$CXX $CFLAGS -c -I "$SRC/haptic" "$SRC/haptic/BeatDetector.cpp" -o /tmp/BeatDet.o
$CXX $CFLAGS -c -I "$SRC/haptic" "$SRC/haptic/PatternGenerator.cpp" -o /tmp/Pattern.o

# 2. Link haptic-engine.so (no libc++ needed, pure C++ header trick)
echo "== Linking haptic-engine.so =="
$CXX $CFLAGS $LDFLAGS -shared -Wl,--no-undefined \
    /tmp/HEngine.o /tmp/FFT.o /tmp/BeatDet.o /tmp/Pattern.o \
    -llog -lm -latomic \
    -o "$OUT/libhaptic-engine.so"

# 3. native-bridge object
echo "== Compiling native-bridge =="
$CXX $CFLAGS -c -I "$SRC" -I "$SRC/jni" -I "$SRC/haptic" \
    "$SRC/jni/NativeBridge.cpp" -o /tmp/NBridge.o

# 4. Link native-bridge.so (static libc++ to eliminate libc++_shared.so)
echo "== Linking native-bridge.so =="
$CXX $CFLAGS $LDFLAGS -shared \
    /tmp/NBridge.o -L"$OUT" -lhaptic-engine \
    -llog -landroid -lm -latomic -pthread \
    $LDFLAGS_STATIC \
    -o "$OUT/libnative-bridge.so"

# 5. Verify 16KB alignment
echo "== Verifying LOAD segment alignment =="
"$TC/bin/llvm-readelf" -l "$OUT/libhaptic-engine.so" 2>/dev/null | grep -E "LOAD|Align" | head -5
"$TC/bin/llvm-readelf" -l "$OUT/libnative-bridge.so" 2>/dev/null | grep -E "LOAD|Align" | head -5

# 6. Remove old libc++_shared.so (no longer needed)
rm -f "$OUT/libc++_shared.so"

echo "== Done. Output =="
ls -la "$OUT/"