// C ABI of the Rust DSP core (app/src/main/rust).
//
// This header is the only contract between the C++ JNI layer and the Rust
// analysis engine. Keeping it narrow is deliberate: the JNI surface, the Kotlin
// bindings and the direct-drive hardware paths all stay in C++, so the loader
// contract inside the hooked process does not change.
//
// All functions are safe to call with a null handle and simply report failure.
#ifndef MHX_DSP_ABI_H
#define MHX_DSP_ABI_H

#include <cstdint>

extern "C" {

// Opaque engine handle. Never dereferenced outside the Rust side.
using MhxEngineHandle = void*;

// Telemetry vector length written by mhx_engine_process.
//
// Layout (must stay in sync with engine.rs):
//   0 sub        1 lowMid     2 vocal      3 presence   4 air
//   5 loudness   6 body       7 kickOnset  8 snareOnset 9 vocalOnset
//  10 bodyOnset 11 blocks    12 windows   13 onsets    14 dropped
static constexpr int MHX_TELEMETRY_LEN = 15;

// Compile-time guard so a future edit cannot silently desync the two sides.
static_assert(MHX_TELEMETRY_LEN == 15, "telemetry layout changed; update engine.rs and Kotlin together");

// ABI version. Bumped whenever a signature or the telemetry layout changes.
static constexpr uint32_t MHX_DSP_ABI_VERSION = 1;

// Create an engine at the given sample rate. Returns null only on allocation
// failure. The sample rate is clamped internally, so a bogus value is harmless.
MhxEngineHandle mhx_engine_create(float sample_rate_hz);

// Destroy an engine. Null is a no-op.
void mhx_engine_destroy(MhxEngineHandle handle);

// Feed a mono PCM block. Returns the number of samples consumed (0 on bad args).
//
// `telemetry` may be null when the caller only wants onsets.
int32_t mhx_engine_process(MhxEngineHandle handle,
                           const float* mono,
                           int32_t len,
                           float* telemetry);

// Change the source sample rate. Returns 1 when the band filters were rebuilt.
//
// The caller is expected to log this: a rate change mid-session is the case that
// used to leave stale coefficients behind and shift every band edge.
int32_t mhx_engine_set_sample_rate(MhxEngineHandle handle, float rate_hz);

void mhx_engine_set_sensitivity(MhxEngineHandle handle, float sensitivity);
void mhx_engine_set_user_amplitude(MhxEngineHandle handle, float amplitude);
void mhx_engine_set_refractory_ms(MhxEngineHandle handle, float ms);

// Clear buffered audio, detector statistics and pending frames.
void mhx_engine_reset(MhxEngineHandle handle);

// Pop one onset frame (4 floats: kick, snare, vocal, body). Returns 1 on success.
int32_t mhx_engine_pop_onset(MhxEngineHandle handle, float* out);

// Pop one semantic frame (5 floats: kick, snare, vocal, body, loudness).
int32_t mhx_engine_pop_semantic(MhxEngineHandle handle, float* out);

// Copy counters into `out` (5 u64: blocks, windows, onsets, dropped, pending).
int32_t mhx_engine_stats(MhxEngineHandle handle, uint64_t* out);

// ABI version of the linked library. A mismatch means the archive and this
// header came from different revisions.
uint32_t mhx_abi_version(void);

// Trivial smoke test: creates and destroys an engine. Returns 0 on success.
//
// Used at JNI load time to prove the static archive actually linked. Without
// this check a failed link can still produce a loadable library whose analysis
// silently returns zeros.
int32_t mhx_self_test(void);

}  // extern "C"

#endif  // MHX_DSP_ABI_H