#!/usr/bin/env bash
# Cross-compile the Rust DSP core into a static library that CMake links into
# libnative-bridge.so.
#
# Why a static library rather than its own .so:
#   The JNI surface, the direct-drive sysfs paths and every Kotlin binding stay
#   in NativeBridge.cpp. Adding a second shared object would introduce a new
#   load order requirement and a new System.loadLibrary call inside the hooked
#   process, which is exactly the kind of boundary the last several regressions
#   came from. A static archive keeps the loader contract identical.
#
# Environment:
#   ANDROID_NDK_HOME or ANDROID_NDK_ROOT  -- NDK root containing toolchains/
#   MHX_RUST_TARGET  -- override the Rust target (default aarch64-linux-android)
#   MHX_RUST_PROFILE -- release (default) or debug
#   MHX_MIN_SDK      -- API level for the linker (default 28, matching minSdk)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUST_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

TARGET="${MHX_RUST_TARGET:-aarch64-linux-android}"
PROFILE="${MHX_RUST_PROFILE:-release}"
MIN_SDK="${MHX_MIN_SDK:-28}"

# CMake passes these through when invoked from Gradle; default them so the
# script also works standalone in CI.
OUT_DIR="${MHX_RUST_OUT_DIR:-$RUST_DIR/target/jniLibs}"
mkdir -p "$OUT_DIR"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "error: ANDROID_NDK_HOME/ANDROID_NDK_ROOT is not set or does not exist" >&2
  exit 1
fi

HOST_TAG="linux-x86_64"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
if [ ! -d "$TOOLCHAIN" ]; then
  echo "error: NDK toolchain not found at $TOOLCHAIN" >&2
  exit 1
fi

# Map the Rust target onto the NDK's clang wrapper. Only arm64 is built because
# the app ships abiFilters += ["arm64-v8a"]; adding a target here without
# updating that filter would produce an APK that silently omits the library.
case "$TARGET" in
  aarch64-linux-android)   NDK_TRIPLE="aarch64-linux-android" ;;
  armv7-linux-androideabi) NDK_TRIPLE="armv7a-linux-androideabi" ;;
  x86_64-linux-android)    NDK_TRIPLE="x86_64-linux-android" ;;
  *) echo "error: unsupported MHX_RUST_TARGET=$TARGET" >&2; exit 1 ;;
esac

CC_BIN="$TOOLCHAIN/bin/${NDK_TRIPLE}${MIN_SDK}-clang"
AR_BIN="$TOOLCHAIN/bin/llvm-ar"
if [ ! -x "$CC_BIN" ]; then
  echo "error: compiler not found: $CC_BIN" >&2
  exit 1
fi

echo "building mhx_dsp for $TARGET (profile=$PROFILE, api=$MIN_SDK)"
echo "  cc  = $CC_BIN"
echo "  ar  = $AR_BIN"

# `cargo build` rather than `cargo rustc` so the profile settings in Cargo.toml
# (fat LTO, panic=abort) actually apply.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_BIN"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$CC_BIN"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$CC_BIN"
export CC_aarch64_linux_android="$CC_BIN"
export AR_aarch64_linux_android="$AR_BIN"
export CC_armv7_linux_androideabi="$CC_BIN"
export AR_armv7_linux_androideabi="$AR_BIN"
export CC_x86_64_linux_android="$CC_BIN"
export AR_x86_64_linux_android="$AR_BIN"

cd "$RUST_DIR"
if [ "$PROFILE" = "release" ]; then
  cargo build --target "$TARGET" --release
  ARTIFACT="$RUST_DIR/target/$TARGET/release/libmhx_dsp.a"
else
  cargo build --target "$TARGET"
  ARTIFACT="$RUST_DIR/target/$TARGET/debug/libmhx_dsp.a"
fi

if [ ! -f "$ARTIFACT" ]; then
  echo "error: expected archive not produced: $ARTIFACT" >&2
  exit 1
fi

# A missing symbol here means CMake would link successfully but every call would
# resolve to a null weak reference at runtime; fail loudly instead.
if ! "$TOOLCHAIN/bin/llvm-nm" --defined-only "$ARTIFACT" | grep -q ' mhx_engine_process$'; then
  echo "error: libmhx_dsp.a does not export mhx_engine_process" >&2
  exit 1
fi

cp -f "$ARTIFACT" "$OUT_DIR/libmhx_dsp.a"
echo "wrote $(wc -c < "$OUT_DIR/libmhx_dsp.a") bytes -> $OUT_DIR/libmhx_dsp.a"