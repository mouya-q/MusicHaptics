//! MusicHapticsX real-time haptics DSP core.
//!
//! Layout:
//!
//! * [`ring`] -- lock-free SPSC frame transport between the audio worker and the
//!   JNI consumer.
//! * [`filter`] -- biquad sections, block RMS and a radix-2 FFT.
//! * [`onset`] -- spectral-flux onset detection with per-band adaptive gates.
//! * [`engine`] -- the analysis pipeline that turns PCM blocks into band
//!   energies, onsets and telemetry.
//! * [`ffi`] -- the C ABI consumed by `NativeBridge.cpp`.
//!
//! Everything above `ffi` is safe Rust and unit-testable on the host; only the
//! FFI layer is `unsafe`.

pub mod engine;
pub mod ffi;
pub mod filter;
pub mod onset;
pub mod ring;

pub use engine::{AnalysisFrame, Engine, EngineConfig};
pub use onset::{BandEnergies, Event, Onset, OnsetDetector};