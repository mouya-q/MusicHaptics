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
# The script lives inside the crate root, so RUST_DIR is SCRIPT_DIR itself.
# Deriving it as SCRIPT_DIR/.. pointed one level too high and made cargo look for
# Cargo.toml under app/src/main, which fails with a confusing "could not find
# Cargo.toml" message.
RUST_DIR="$SCRIPT_DIR"

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
#
# Two shell hazards are avoided deliberately:
#  * No `printf | grep -q` under `set -o pipefail`: grep -q exits on first match,
#    SIGPIPEs the writer, and pipefail then reports the whole pipeline as failed
#    even though the symbol was found.
#  * No exact " T name" column pattern: llvm-nm layouts differ across versions.
NM_FILE="$(mktemp)"
trap 'rm -f "$NM_FILE"' EXIT
if ! "$TOOLCHAIN/bin/llvm-nm" --defined-only "$ARTIFACT" > "$NM_FILE" 2>/dev/null; then
  echo "error: llvm-nm failed on $ARTIFACT" >&2
  exit 1
fi
if [ ! -s "$NM_FILE" ]; then
  echo "error: llvm-nm produced no output for $ARTIFACT (archive may be stripped)" >&2
  exit 1
fi

MISSING=""
for sym in mhx_engine_create mhx_engine_destroy mhx_engine_process \
           mhx_engine_pop_onset mhx_engine_pop_semantic mhx_engine_stats \
           mhx_engine_set_block_time mhx_engine_pop_onset_timed \
           mhx_engine_latest_semantic \
           mhx_abi_version mhx_self_test; do
  if ! grep -qw "$sym" "$NM_FILE"; then
    MISSING="$MISSING $sym"
  fi
done

if [ -n "$MISSING" ]; then
  echo "error: libmhx_dsp.a is missing required symbols:$MISSING" >&2
  echo "--- first 20 symbols in the archive ---" >&2
  head -20 "$NM_FILE" >&2
  exit 1
fi

echo "verified $(grep -cw 'mhx_' "$NM_FILE" || true) mhx_* symbols in the archive"

cp -f "$ARTIFACT" "$OUT_DIR/libmhx_dsp.a"
echo "wrote $(wc -c < "$OUT_DIR/libmhx_dsp.a") bytes -> $OUT_DIR/libmhx_dsp.a"