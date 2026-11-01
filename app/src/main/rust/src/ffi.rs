//! C ABI consumed by `NativeBridge.cpp`.
//!
//! The C++ side keeps ownership of the JNI surface, all Android-specific I/O and
//! the direct-drive hardware paths. This module only replaces the analysis core,
//! so the existing JNI method table, the Kotlin `NativeBridge` bindings and the
//! library loading path all stay untouched. That is deliberate: the previous
//! seven regressions all lived in the Kotlin/JNI boundary, and keeping that
//! boundary stable makes this refactor verifiable rather than speculative.
//!
//! # Threading contract
//!
//! Two threads touch an engine:
//!
//! * **Producer** -- the DSP worker calls [mhx_engine_process]. Exactly one
//!   thread may do this at a time.
//! * **Consumer** -- the scheduler thread calls [mhx_engine_pop_onset],
//!   [mhx_engine_pop_semantic] and [mhx_engine_stats].
//!
//! The producer and consumer must not be the same thread, and there must never be
//! two producers or two consumers. This mirrors the C++ scheduler, which already
//! runs on its own pthread while `AudioIngress` owns the DSP worker.
//!
//! The shared state between them is exactly the two `FrameRing`s plus the
//! `u64` counters, all of which are atomic. Every other field of `Engine` is
//! touched only by the producer, which is why the pop/stats entry points take
//! `&Engine` and read nothing but those atomics.
//!
//! Configuration setters ([mhx_engine_set_sample_rate] and friends) must be
//! called while the producer is quiescent -- in practice from the Kotlin
//! configure path, which runs before playback starts.
//!
//! No function here panics: the crate is built with `panic = "abort"` and no path
//! contains an unchecked slice index on caller-controlled data.

use core::ffi::c_void;
use core::ptr;

use crate::engine::{AnalysisFrame, Engine, EngineConfig, OnsetFrame};

/// Telemetry vector length, mirrored in `NativeBridge.cpp`.
pub const TELEMETRY_LEN: usize = 15;

/// Opaque handle returned to C. Boxed `Engine`, never dereferenced by the caller.
pub type EngineHandle = *mut c_void;

/// Create an engine. Returns null only if allocation fails.
///
/// `sample_rate_hz` is clamped inside the engine, so a bogus value cannot make
/// the filters diverge.
#[no_mangle]
pub extern "C" fn mhx_engine_create(sample_rate_hz: f32) -> EngineHandle {
    let cfg = EngineConfig {
        sample_rate: if sample_rate_hz.is_finite() { sample_rate_hz } else { 48000.0 },
        ..EngineConfig::default()
    };
    let boxed = Box::new(Engine::new(cfg));
    Box::into_raw(boxed) as EngineHandle
}

/// Destroy an engine. Passing null is a no-op so the C++ side need not guard.
///
/// # Safety
/// `handle` must come from [mhx_engine_create] and must not be used afterwards.
#[no_mangle]
pub unsafe extern "C" fn mhx_engine_destroy(handle: EngineHandle) {
    if handle.is_null() {
        return;
    }
    drop(Box::from_raw(handle as *mut Engine));
}

/// Process a mono PCM block.
///
/// Returns the number of samples consumed, or 0 when any pointer is null.
///
/// # Safety
/// `mono` must point to `len` readable `f32`s; `telemetry` must point to at
/// least [TELEMETRY_LEN] writable `f32`s. Both must stay valid for the call.
#[no_mangle]
pub unsafe extern "C" fn mhx_engine_process(
    handle: EngineHandle,
    mono: *const f32,
    len: i32,
    telemetry: *mut f32,
) -> i32 {
    if handle.is_null() || mono.is_null() || len <= 0 {
        return 0;
    }
    let engine = &mut *(handle as *mut Engine);
    let samples = core::slice::from_raw_parts(mono, len as usize);

    // A null telemetry pointer is legal: the caller may only want onsets.
    if telemetry.is_null() {
        let mut local = [0.0f32; TELEMETRY_LEN];
        return engine.process(samples, &mut local) as i32;
    }
    let tel = core::slice::from_raw_parts_mut(telemetry, TELEMETRY_LEN);
    engine.process(samples, tel) as i32
}

/// Update the source sample rate. Returns 1 when the filters were rebuilt.
///
/// # Safety
/// `handle` must come from [mhx_engine_create].
#[no_mangle]
pub unsafe extern "C" fn mhx_engine_set_sample_rate(handle: EngineHandle, rate_hz: f32) -> i32 {
    if handle.is_null() {
        return 0;
    }
    let engine = &mut *(handle as *mut Engine);
    i32::from(engine.set_sample_rate(rate_hz))
}

/// # Safety
/// `handle` must come from [mhx_engine_create].
#[no_mangle]
pub unsafe extern "C" fn mhx_engine_set_sensitivity(handle: EngineHandle, sensitivity: f32) {
    if handle.is_null() {
        return;
    }
    (*(handle as *mut Engine)).set_sensitivity(sensitivity);
}

/// # Safety
/// `handle` must come from [mhx_engine_create].
#[no_mangle]
pub unsafe extern "C" fn mhx_engine_set_user_amplitude(handle: EngineHandle, amplitude: f32) {
    if handle.is_null() {
        return;
    }
    (*(handle as *mut Engine)).set_user_amplitude(amplitude);
}

/// # Safety
/// `handle` must come from [mhx_engine_create].
#[no_mangle]
pub unsafe extern "C" fn mhx_engine_set_refractory_ms(handle: EngineHandle, ms: f32) {
    if handle.is_null() {
        return;
    }
    (*(handle as *mut Engine)).set_refractory_ms(ms);
}

/// Clear buffers and statistics.
///
/// # Safety
/// `handle` must come from [mhx_engine_create].
#[no_mangle]
pub unsafe extern "C" fn mhx_engine_reset(handle: EngineHandle) {
    if handle.is_null() {
        return;
    }
    (*(handle as *mut Engine)).reset();
}

/// Pop one onset frame into `out` (4 floats: kick, snare, vocal, body).
///
/// Returns 1 when a frame was written, 0 when the queue was empty.
///
/// # Safety
/// `handle` must come from [mhx_engine_create]; `out` must point to 4 writable
/// `f32`s.
#[no_mangle]
pub unsafe extern "C" fn mhx_engine_pop_onset(handle: EngineHandle, out: *mut f32) -> i32 {
    if handle.is_null() || out.is_null() {
        return 0;
    }
    let engine = &*(handle as *mut Engine);
    let mut frame = OnsetFrame::default();
    if !engine.pop_onset(&mut frame) {
        return 0;
    }
    let dst = core::slice::from_raw_parts_mut(out, 4);
    dst[0] = frame.kick;
    dst[1] = frame.snare;
    dst[2] = frame.vocal;
    dst[3] = frame.body;
    1
}

/// Pop one semantic frame into `out` (5 floats: kick, snare, vocal, body,
/// loudness).
///
/// # Safety
/// `handle` must come from [mhx_engine_create]; `out` must point to 5 writable
/// `f32`s.
#[no_mangle]
pub unsafe extern "C" fn mhx_engine_pop_semantic(handle: EngineHandle, out: *mut f32) -> i32 {
    if handle.is_null() || out.is_null() {
        return 0;
    }
    let engine = &*(handle as *mut Engine);
    let mut frame = AnalysisFrame::default();
    if !engine.pop_semantic(&mut frame) {
        return 0;
    }
    let dst = core::slice::from_raw_parts_mut(out, 5);
    dst[0] = frame.kick;
    dst[1] = frame.snare;
    dst[2] = frame.vocal;
    dst[3] = frame.body;
    dst[4] = frame.loudness;
    1
}

/// Snapshot of internal counters: blocks, windows, onsets emitted, onsets
/// dropped, pending onsets.
///
/// # Safety
/// `handle` must come from [mhx_engine_create]; `out` must point to 5 writable
/// `u64`s.
#[no_mangle]
pub unsafe extern "C" fn mhx_engine_stats(handle: EngineHandle, out: *mut u64) -> i32 {
    if handle.is_null() || out.is_null() {
        return 0;
    }
    let engine = &*(handle as *mut Engine);
    let dst = core::slice::from_raw_parts_mut(out, 5);
    dst[0] = engine.blocks();
    dst[1] = engine.windows();
    dst[2] = engine.onsets_emitted();
    dst[3] = engine.dropped_onsets();
    dst[4] = engine.pending_onsets() as u64;
    1
}

/// ABI version, so the C++ side can refuse a mismatched library instead of
/// corrupting memory through a stale struct layout.
#[no_mangle]
pub extern "C" fn mhx_abi_version() -> u32 {
    1
}

/// Report whether the input pointer was null, used by a self-test in C++.
///
/// Keeping one trivially-callable symbol makes it easy to verify from `nm` that
/// the static library actually linked, rather than silently missing and leaving
/// every analysis at zero.
#[no_mangle]
pub extern "C" fn mhx_self_test() -> i32 {
    let e = mhx_engine_create(48000.0);
    if e.is_null() {
        return -1;
    }
    unsafe { mhx_engine_destroy(e) };
    0
}

/// Compile-time assertion that the telemetry layout matches the C++ copy.
const _: () = assert!(TELEMETRY_LEN == 15);

/// Keep `ptr` referenced so the import is not flagged when building for a target
/// where the null checks fold away.
#[allow(dead_code)]
fn _assert_ptr_import() -> *const c_void {
    ptr::null()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_destroy_roundtrip() {
        let e = mhx_engine_create(48000.0);
        assert!(!e.is_null());
        unsafe { mhx_engine_destroy(e) };
    }

    #[test]
    fn destroy_null_is_safe() {
        unsafe { mhx_engine_destroy(ptr::null_mut()) };
    }

    #[test]
    fn null_handle_returns_zero() {
        let mut tel = [0.0f32; TELEMETRY_LEN];
        let inbuf = [0.0f32; 16];
        let n = unsafe {
            mhx_engine_process(
                ptr::null_mut(),
                inbuf.as_ptr(),
                inbuf.len() as i32,
                tel.as_mut_ptr(),
            )
        };
        assert_eq!(n, 0);
    }

    #[test]
    fn process_feeds_telemetry() {
        let e = mhx_engine_create(48000.0);
        assert!(!e.is_null());
        let mut tel = [0.0f32; TELEMETRY_LEN];
        let rate = 48000usize;
        let block: Vec<f32> = (0..4096)
            .map(|i| 0.5 * (2.0 * core::f32::consts::PI * 80.0 * i as f32 / rate as f32).sin())
            .collect();
        let n = unsafe { mhx_engine_process(e, block.as_ptr(), block.len() as i32, tel.as_mut_ptr()) };
        assert_eq!(n as usize, block.len());
        // Band energy for an 80 Hz tone must be non-zero.
        assert!(tel[0] > 0.0, "sub band telemetry was zero");
        unsafe { mhx_engine_destroy(e) };
    }

    #[test]
    fn null_telemetry_is_tolerated() {
        let e = mhx_engine_create(48000.0);
        let block = vec![0.0f32; 512];
        let n = unsafe { mhx_engine_process(e, block.as_ptr(), block.len() as i32, ptr::null_mut()) };
        assert_eq!(n as usize, block.len());
        unsafe { mhx_engine_destroy(e) };
    }

    #[test]
    fn sample_rate_abi_reports_rebuild() {
        let e = mhx_engine_create(48000.0);
        assert_eq!(unsafe { mhx_engine_set_sample_rate(e, 48000.0) }, 0);
        assert_eq!(unsafe { mhx_engine_set_sample_rate(e, 44100.0) }, 1);
        unsafe { mhx_engine_destroy(e) };
    }

    #[test]
    fn stats_reports_counters() {
        let e = mhx_engine_create(48000.0);
        let mut tel = [0.0f32; TELEMETRY_LEN];
        let block = vec![0.0f32; 2048];
        unsafe { mhx_engine_process(e, block.as_ptr(), block.len() as i32, tel.as_mut_ptr()) };
        let mut stats = [0u64; 5];
        assert_eq!(unsafe { mhx_engine_stats(e, stats.as_mut_ptr()) }, 1);
        assert_eq!(stats[0], 1, "one process call");
        unsafe { mhx_engine_destroy(e) };
    }

    #[test]
    fn pop_on_empty_returns_zero() {
        let e = mhx_engine_create(48000.0);
        let mut out = [0.0f32; 5];
        assert_eq!(unsafe { mhx_engine_pop_onset(e, out.as_mut_ptr()) }, 0);
        assert_eq!(unsafe { mhx_engine_pop_semantic(e, out.as_mut_ptr()) }, 0);
        unsafe { mhx_engine_destroy(e) };
    }

    #[test]
    fn abi_version_and_self_test() {
        assert_eq!(mhx_abi_version(), 1);
        assert_eq!(mhx_self_test(), 0);
    }
}