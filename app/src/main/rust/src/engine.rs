//! Analysis pipeline: PCM block -> band energies -> onsets + telemetry.
//!
//! This is the piece that replaces `HapticEngine::processAudioBlock`. It keeps
//! the same job -- one block in, band features out -- but fixes three things the
//! old implementation got wrong, all of which are visible in the v5.5.3 trace:
//!
//! * **Filter coefficients now follow the sample rate.** The trace shows the
//!   track switching `44100 -> 48000` mid-session. Reusing the old coefficients
//!   shifts every band edge by ~9%, so kicks land in the wrong band.
//! * **Bands are measured from filtered signals, not from summed magnitudes.**
//!   Magnitude sums scale with window gain, which pinned `texRms` at a constant
//!   0.1 no matter what played.
//! * **Body energy is a real measurement**, not a term that multiplies out to
//!   zero when the air band is idle.

use core::sync::atomic::{AtomicU32, Ordering};

use crate::filter::{rms, Biquad, Fft, Kind};
use crate::onset::{band_rms, body_energy, loudness, BandEnergies, DetectorConfig, OnsetDetector};
use crate::ring::FrameRing;

/// Analysis window in samples. 1024 gives ~23 ms at 44.1 kHz and ~21 ms at
/// 48 kHz -- enough for a stable kick detection while staying under one video
/// frame of latency.
pub const WINDOW: usize = 1024;

/// Block size the caller feeds. Must divide [WINDOW] so windows stay aligned.
pub const BLOCK: usize = 256;

/// Seconds of history the ring keeps for the JNI consumer.
///
/// Sized for a consumer that wakes every 16 ms: at 48 kHz that is 768 frames per
/// wake, and the ring must survive several missed wakes without dropping a beat.
const ONSET_RING: usize = 512;
const SEMANTIC_RING: usize = 512;

/// Attack-localisation resolution inside one analysis window.
///
/// Each band keeps `SEGMENTS` short-time energies (64 samples = 1.33 ms at
/// 48 kHz). An onset is placed at the first segment that reaches 5% of the
/// window's peak segment energy, which pins a kick to about +-1 ms instead of
/// the +-10 ms that "somewhere inside the 21 ms window" allows (measured on
/// synthetic 60/90 Hz kicks and noise snares, see docs/SYNC_AND_TIMING.md).
const SEG_LEN: usize = 64;
const SEGMENTS: usize = WINDOW / SEG_LEN;

/// Systematic lateness of the 5% crossing caused by the band-pass group delay.
/// Measured: low-frequency kick +2.2 ms, snare +0.5 ms, upper bands ~0 ms.
const KICK_LOCATE_BIAS_MS: f32 = 2.2;
const SNARE_LOCATE_BIAS_MS: f32 = 0.5;

/// Per-band cutoff constants. Kick sits low, vocal in the presence region, and
/// air drives texture.
///
/// These are the *defaults*. The UI style presets override the outer edges via
/// [Engine::set_band_edges], which is what makes the preset selection audible in
/// the analysis rather than only in the output shaping.
const SUB_LO: f32 = 30.0;
const SUB_HI: f32 = 180.0;
const LOWMID_LO: f32 = 180.0;
const LOWMID_HI: f32 = 800.0;
const VOCAL_LO: f32 = 800.0;
const VOCAL_HI: f32 = 2500.0;
const PRESENCE_LO: f32 = 2500.0;
const PRESENCE_HI: f32 = 8000.0;
const AIR_LO: f32 = 8000.0;
const AIR_HI: f32 = 16000.0;

/// Bounds on the user-configurable band window.
///
/// The low limit keeps the sub band above the LRA's own resonance so it measures
/// musical bass instead of the actuator's own ring-out; the high limit stays
/// below Nyquist for 44.1 kHz sources.
const BAND_LOW_MIN: f32 = 20.0;
const BAND_LOW_MAX: f32 = 200.0;
const BAND_HIGH_MIN: f32 = 300.0;
const BAND_HIGH_MAX: f32 = 12000.0;

/// One analysis result, mirroring what the old `SemanticHapticFrame` carried but
/// with real band energies instead of a composite envelope.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct AnalysisFrame {
    /// 0..1 scaled band energies, ready for the output stage.
    pub kick: f32,
    pub snare: f32,
    pub vocal: f32,
    pub body: f32,
    /// Overall loudness, used to scale strike strength with volume.
    pub loudness: f32,
}

/// Frame queued for the JNI consumer when an onset occurs.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct OnsetFrame {
    pub kick: f32,
    pub snare: f32,
    pub vocal: f32,
    pub body: f32,
    /// Monotonic-clock time (CLOCK_MONOTONIC, ns) at which the transient is
    /// expected to be *audible*, derived from the PCM block's playback anchor and
    /// the localised attack sample. `0` means no anchor was available and the
    /// consumer should fire immediately (the pre-6.2 behaviour).
    pub due_ns: i64,
}

/// Runtime-tunable parameters.
#[derive(Clone, Copy, Debug)]
pub struct EngineConfig {
    pub sample_rate: f32,
    /// Scales the detector's sensitivity.
    pub sensitivity: f32,
    /// Multiplies the final band energies; wired to the UI amplitude control.
    pub user_amplitude: f32,
    /// Minimum milliseconds between onsets of the same band.
    pub refractory_ms: f32,
    /// Lower edge of the analysable window, from the UI style preset.
    pub band_low_hz: f32,
    /// Upper edge of the analysable window, from the UI style preset.
    pub band_high_hz: f32,
    /// Weight applied to the kick band before onset scoring (bass slider).
    pub bass_boost: f32,
    /// Weight applied to sustained (non-onset) energy in the body channel.
    pub texture_gain: f32,
}

impl Default for EngineConfig {
    fn default() -> Self {
        Self {
            sample_rate: 48000.0,
            sensitivity: 1.0,
            user_amplitude: 1.0,
            refractory_ms: 60.0,
            // Matches the previous hard-coded LOWMID/VOCAL window so the default
            // sound is unchanged until the user picks a style.
            band_low_hz: SUB_LO,
            band_high_hz: VOCAL_HI,
            bass_boost: 1.0,
            texture_gain: 1.0,
        }
    }
}

/// The analysis engine. Not `Sync`: it is owned by exactly one audio worker.
pub struct Engine {
    cfg: EngineConfig,
    /// Ring buffer of the most recent [WINDOW] samples, oldest first after wrap.
    history: [f32; WINDOW],
    /// Write position in `history`.
    write_pos: usize,
    /// Samples accumulated since the last full window.
    filled: usize,
    /// Window assembly scratch, refilled from `history` before each analysis.
    window: [f32; WINDOW],

    // Per-band filters. Two cascaded sections per band approximate a 4th-order
    // Butterworth, which gives a usable skirt without the passband ripple that
    // would make band energies wobble with the music's key.
    sub_lo_a: Biquad,
    sub_lo_b: Biquad,
    sub_hi_a: Biquad,
    sub_hi_b: Biquad,
    lowmid_lo_a: Biquad,
    lowmid_lo_b: Biquad,
    lowmid_hi_a: Biquad,
    lowmid_hi_b: Biquad,
    vocal_lo_a: Biquad,
    vocal_lo_b: Biquad,
    vocal_hi_a: Biquad,
    vocal_hi_b: Biquad,
    presence_lo_a: Biquad,
    presence_lo_b: Biquad,
    presence_hi_a: Biquad,
    presence_hi_b: Biquad,
    air_lo_a: Biquad,
    air_lo_b: Biquad,
    air_hi_a: Biquad,
    air_hi_b: Biquad,

    /// Scratch buffers reused every block so the hot path never allocates.
    scratch_a: [f32; WINDOW],
    scratch_b: [f32; WINDOW],

    fft: Fft,
    magnitudes: [f32; WINDOW / 2],
    previous_magnitudes: [f32; WINDOW / 2],

    detector: OnsetDetector,

    onsets: FrameRing<OnsetFrame, ONSET_RING>,
    semantics: FrameRing<AnalysisFrame, SEMANTIC_RING>,

    /// Last complete-window band measurements. Small AudioTrack writes can be
    /// shorter than WINDOW; keep the most recent valid levels instead of
    /// reporting a misleading all-zero dashboard between analysis windows.
    last_band_telemetry: [f32; 7],

    /// Cumulative counters, exposed for telemetry.
    blocks: u64,
    windows: u64,
    onsets_emitted: u64,

    /// Samples consumed since construction (never reset by rate changes), the
    /// engine's own sample clock.
    total_samples: u64,
    /// Playback anchor for the block currently being fed: the CLOCK_MONOTONIC
    /// time at which sample `anchor_sample` becomes audible. 0 = unknown.
    anchor_ns: i64,
    anchor_sample: u64,

    /// Short-time energies of the most recent window for every band, used to
    /// localise the attack inside the window.
    seg_energy: [[f32; SEGMENTS]; 5],

    /// Newest semantic frame (kick, snare, vocal, body, loudness as f32 bits).
    /// The FIFO ring is for consumers that drain every frame; a consumer that
    /// wants "how loud is the music right now" must not pop the ring, because
    /// it hands back the *oldest* retained frame (up to ~11 s old).
    latest_semantic: [AtomicU32; 5],
}

#[inline]
fn design_band(
    hp_a: &mut Biquad,
    hp_b: &mut Biquad,
    lp_a: &mut Biquad,
    lp_b: &mut Biquad,
    sample_rate: f32,
    low_hz: f32,
    high_hz: f32,
    q: f32,
) {
    hp_a.design(Kind::HighPass, sample_rate, low_hz, q);
    hp_b.design(Kind::HighPass, sample_rate, low_hz, q);
    lp_a.design(Kind::LowPass, sample_rate, high_hz, q);
    lp_b.design(Kind::LowPass, sample_rate, high_hz, q);
}

/// Short-time energy of each `SEG_LEN` slice of a filtered window.
#[inline]
fn segment_energies(buf: &[f32; WINDOW], out: &mut [f32; SEGMENTS]) {
    for s in 0..SEGMENTS {
        let mut acc = 0.0f32;
        for &v in &buf[s * SEG_LEN..(s + 1) * SEG_LEN] {
            acc += v * v;
        }
        out[s] = acc;
    }
}

/// Sample offset (within the window) of the first segment whose energy reaches
/// 5% of the window peak. `None` when the window carries no usable energy.
#[inline]
fn locate_attack(seg: &[f32; SEGMENTS]) -> Option<usize> {
    let mut peak = 0.0f32;
    for &e in seg.iter() {
        if e > peak {
            peak = e;
        }
    }
    if !(peak > 1.0e-12) {
        return None;
    }
    let gate = peak * 0.05;
    for (i, &e) in seg.iter().enumerate() {
        if e >= gate {
            return Some(i * SEG_LEN);
        }
    }
    None
}

#[inline]
fn filtered_band_rms(
    window: &[f32; WINDOW],
    scratch: &mut [f32; WINDOW],
    seg: &mut [f32; SEGMENTS],
    hp_a: &mut Biquad,
    hp_b: &mut Biquad,
    lp_a: &mut Biquad,
    lp_b: &mut Biquad,
) -> f32 {
    scratch.copy_from_slice(window);
    hp_a.process_block(scratch);
    hp_b.process_block(scratch);
    lp_a.process_block(scratch);
    lp_b.process_block(scratch);
    segment_energies(scratch, seg);
    rms(scratch)
}

impl Engine {
    pub fn new(cfg: EngineConfig) -> Self {
        // FFI callers may provide malformed persisted settings. Rust's f32::clamp
        // also preserves NaN, so normalise the complete config before designing
        // any filters or constructing the adaptive detector.
        let sample_rate = if cfg.sample_rate.is_finite() {
            cfg.sample_rate.clamp(8000.0, 384000.0)
        } else { 48000.0 };
        let low = if cfg.band_low_hz.is_finite() {
            cfg.band_low_hz.clamp(BAND_LOW_MIN, BAND_LOW_MAX)
        } else { 30.0 };
        let high_default = 2500.0f32;
        let high = (if cfg.band_high_hz.is_finite() {
            cfg.band_high_hz.clamp(BAND_HIGH_MIN, BAND_HIGH_MAX)
        } else { high_default }).max(low + 100.0).min(BAND_HIGH_MAX);
        let cfg = EngineConfig {
            sample_rate,
            sensitivity: if cfg.sensitivity.is_finite() { cfg.sensitivity.clamp(0.1, 4.0) } else { 1.0 },
            user_amplitude: if cfg.user_amplitude.is_finite() { cfg.user_amplitude.clamp(0.0, 4.0) } else { 1.0 },
            refractory_ms: if cfg.refractory_ms.is_finite() { cfg.refractory_ms.clamp(0.0, 500.0) } else { 60.0 },
            band_low_hz: low,
            band_high_hz: high,
            bass_boost: if cfg.bass_boost.is_finite() { cfg.bass_boost.clamp(0.3, 3.0) } else { 1.0 },
            texture_gain: if cfg.texture_gain.is_finite() { cfg.texture_gain.clamp(0.2, 3.0) } else { 1.0 },
        };
        let mut e = Self {
            cfg,
            history: [0.0; WINDOW],
            write_pos: 0,
            filled: 0,
            window: [0.0; WINDOW],
            sub_lo_a: Biquad::pass_through(),
            sub_lo_b: Biquad::pass_through(),
            sub_hi_a: Biquad::pass_through(),
            sub_hi_b: Biquad::pass_through(),
            lowmid_lo_a: Biquad::pass_through(),
            lowmid_lo_b: Biquad::pass_through(),
            lowmid_hi_a: Biquad::pass_through(),
            lowmid_hi_b: Biquad::pass_through(),
            vocal_lo_a: Biquad::pass_through(),
            vocal_lo_b: Biquad::pass_through(),
            vocal_hi_a: Biquad::pass_through(),
            vocal_hi_b: Biquad::pass_through(),
            presence_lo_a: Biquad::pass_through(),
            presence_lo_b: Biquad::pass_through(),
            presence_hi_a: Biquad::pass_through(),
            presence_hi_b: Biquad::pass_through(),
            air_lo_a: Biquad::pass_through(),
            air_lo_b: Biquad::pass_through(),
            air_hi_a: Biquad::pass_through(),
            air_hi_b: Biquad::pass_through(),
            scratch_a: [0.0; WINDOW],
            scratch_b: [0.0; WINDOW],
            fft: Fft::new(WINDOW),
            magnitudes: [0.0; WINDOW / 2],
            previous_magnitudes: [0.0; WINDOW / 2],
            detector: OnsetDetector::new(DetectorConfig::default()),
            onsets: FrameRing::new(OnsetFrame::default()),
            semantics: FrameRing::new(AnalysisFrame::default()),
            last_band_telemetry: [0.0; 7],
            blocks: 0,
            windows: 0,
            onsets_emitted: 0,
            total_samples: 0,
            anchor_ns: 0,
            anchor_sample: 0,
            seg_energy: [[0.0; SEGMENTS]; 5],
            latest_semantic: [
                AtomicU32::new(0),
                AtomicU32::new(0),
                AtomicU32::new(0),
                AtomicU32::new(0),
                AtomicU32::new(0),
            ],
        };
        e.design_filters();
        e.apply_detector_config();
        e
    }

    /// Design all band filters for the active sample rate and style edges.
    ///
    /// Coefficients are updated in place. Style changes therefore retain the
    /// filter delay state and do not restart every band at each UI preset switch.
    /// A source-rate change explicitly resets the histories after redesigning.
    fn design_filters(&mut self) {
        let rate = self.cfg.sample_rate.max(8000.0);
        let q = 0.707;
        let low_edge = self.cfg.band_low_hz.clamp(BAND_LOW_MIN, BAND_LOW_MAX);
        let high_edge = self.cfg.band_high_hz.clamp(BAND_HIGH_MIN, BAND_HIGH_MAX);
        let sub_hi = SUB_HI.min(low_edge + 150.0).max(low_edge + 40.0);
        let lowmid_hi = (high_edge * 0.4)
            .clamp(sub_hi + 60.0, LOWMID_HI.max(sub_hi + 200.0));
        let vocal_lo = lowmid_hi;
        let vocal_hi = high_edge.max(vocal_lo + 200.0);

        design_band(&mut self.sub_lo_a, &mut self.sub_lo_b,
            &mut self.sub_hi_a, &mut self.sub_hi_b, rate, SUB_LO, sub_hi, q);
        design_band(&mut self.lowmid_lo_a, &mut self.lowmid_lo_b,
            &mut self.lowmid_hi_a, &mut self.lowmid_hi_b, rate, low_edge, lowmid_hi, q);
        design_band(&mut self.vocal_lo_a, &mut self.vocal_lo_b,
            &mut self.vocal_hi_a, &mut self.vocal_hi_b, rate, vocal_lo, vocal_hi, q);
        design_band(&mut self.presence_lo_a, &mut self.presence_lo_b,
            &mut self.presence_hi_a, &mut self.presence_hi_b, rate, PRESENCE_LO, PRESENCE_HI, q);
        design_band(&mut self.air_lo_a, &mut self.air_lo_b,
            &mut self.air_hi_a, &mut self.air_hi_b, rate, AIR_LO, AIR_HI, q);
    }

    fn reset_filter_states(&mut self) {
        for filter in [
            &mut self.sub_lo_a, &mut self.sub_lo_b, &mut self.sub_hi_a, &mut self.sub_hi_b,
            &mut self.lowmid_lo_a, &mut self.lowmid_lo_b, &mut self.lowmid_hi_a, &mut self.lowmid_hi_b,
            &mut self.vocal_lo_a, &mut self.vocal_lo_b, &mut self.vocal_hi_a, &mut self.vocal_hi_b,
            &mut self.presence_lo_a, &mut self.presence_lo_b, &mut self.presence_hi_a, &mut self.presence_hi_b,
            &mut self.air_lo_a, &mut self.air_lo_b, &mut self.air_hi_a, &mut self.air_hi_b,
        ] {
            filter.reset();
        }
    }

    fn apply_detector_config(&mut self) {
        // Refractory in frames: each analysis frame consumes exactly WINDOW
        // new samples (non-overlapping windows), so this is the actual hop time.
        let window_ms = (WINDOW as f32 / self.cfg.sample_rate.max(8000.0)) * 1000.0;
        let frames = (self.cfg.refractory_ms / window_ms.max(1.0)).round().max(1.0) as u32;
        self.detector = OnsetDetector::new(DetectorConfig {
            sensitivity: self.cfg.sensitivity,
            refractory_frames: frames,
            ..DetectorConfig::default()
        });
        // A new detector starts neutral; carry the user's bass weight across so
        // changing sensitivity or sample rate does not silently drop it.
        self.detector.set_bass_boost(self.cfg.bass_boost);
    }

    /// Update the sample rate, rebuilding filters if it actually changed.
    ///
    /// Returns true when a rebuild happened, so the caller can log the
    /// transition instead of it passing silently.
    pub fn set_sample_rate(&mut self, rate: f32) -> bool {
        if !rate.is_finite() { return false; }
        let rate = rate.clamp(8000.0, 384000.0);
        if (rate - self.cfg.sample_rate).abs() < 0.5 {
            return false;
        }
        self.cfg.sample_rate = rate;
        self.design_filters();
        self.reset_filter_states();
        self.apply_detector_config();
        // The source changed: stale history belongs to the previous track. The
        // partial window being discarded must also leave the engine's sample
        // clock, or the next onset would be placed `filled` samples too late.
        self.total_samples = self.total_samples.saturating_sub(self.filled as u64);
        self.history = [0.0; WINDOW];
        self.previous_magnitudes = [0.0; WINDOW / 2];
        self.write_pos = 0;
        self.filled = 0;
        true
    }

    pub fn set_sensitivity(&mut self, sensitivity: f32) {
        self.cfg.sensitivity = if sensitivity.is_finite() { sensitivity.clamp(0.1, 4.0) } else { 1.0 };
        self.apply_detector_config();
    }

    pub fn set_user_amplitude(&mut self, amplitude: f32) {
        self.cfg.user_amplitude = if amplitude.is_finite() { amplitude.clamp(0.0, 4.0) } else { 1.0 };
    }

    pub fn set_refractory_ms(&mut self, ms: f32) {
        self.cfg.refractory_ms = if ms.is_finite() { ms.clamp(0.0, 500.0) } else { 60.0 };
        self.apply_detector_config();
    }

    /// Set the analysable window from a UI style preset.
    ///
    /// The window is clamped so the sub band cannot fall below the actuator's own
    /// resonance and the top cannot exceed what a 44.1 kHz source can represent.
    /// Returns true when the edges actually changed, so the caller can log it.
    pub fn set_band_edges(&mut self, low_hz: f32, high_hz: f32) -> bool {
        let low = if low_hz.is_finite() { low_hz.clamp(BAND_LOW_MIN, BAND_LOW_MAX) } else { self.cfg.band_low_hz };
        let high = if high_hz.is_finite() { high_hz.clamp(BAND_HIGH_MIN, BAND_HIGH_MAX) } else { self.cfg.band_high_hz };
        // A window of zero width would silence every band; keep a usable span.
        let high = high.max(low + 100.0);
        if (low - self.cfg.band_low_hz).abs() < 0.5 && (high - self.cfg.band_high_hz).abs() < 0.5 {
            return false;
        }
        self.cfg.band_low_hz = low;
        self.cfg.band_high_hz = high;
        self.design_filters();
        true
    }

    /// Weight the kick band before onset scoring (UI bass slider).
    pub fn set_bass_boost(&mut self, boost: f32) {
        self.cfg.bass_boost = if boost.is_finite() { boost.clamp(0.3, 3.0) } else { 1.0 };
        self.detector.set_bass_boost(self.cfg.bass_boost);
    }

    /// Weight sustained energy in the body channel (UI texture control).
    pub fn set_texture_gain(&mut self, gain: f32) {
        self.cfg.texture_gain = if gain.is_finite() { gain.clamp(0.2, 3.0) } else { 1.0 };
    }

    pub fn band_edges(&self) -> (f32, f32) {
        (self.cfg.band_low_hz, self.cfg.band_high_hz)
    }

    pub fn sample_rate(&self) -> f32 { self.cfg.sample_rate }
    pub fn blocks(&self) -> u64 { self.blocks }
    pub fn windows(&self) -> u64 { self.windows }
    pub fn onsets_emitted(&self) -> u64 { self.onsets_emitted }
    pub fn dropped_onsets(&self) -> u64 { self.onsets.dropped() }
    pub fn pending_onsets(&self) -> usize { self.onsets.len() }

    /// Discard buffered audio and statistics without changing configuration.
    ///
    /// Resets the diagnostic counters too. Leaving `onsets_emitted` at its old
    /// value after a clear made the exported telemetry report a cumulative total
    /// that could not be compared against a fresh session.
    pub fn reset(&mut self) {
        self.total_samples = self.total_samples.saturating_sub(self.filled as u64);
        self.history = [0.0; WINDOW];
        self.previous_magnitudes = [0.0; WINDOW / 2];
        self.write_pos = 0;
        self.filled = 0;
        self.design_filters();
        self.reset_filter_states();
        self.detector.reset();
        self.onsets.clear();
        self.semantics.clear();
        self.last_band_telemetry = [0.0; 7];
        self.onsets_emitted = 0;
        self.anchor_ns = 0;
        self.seg_energy = [[0.0; SEGMENTS]; 5];
        for slot in self.latest_semantic.iter() {
            slot.store(0, Ordering::Relaxed);
        }
        // `blocks` and `windows` are deliberately kept: they count work done by
        // this engine instance, and zeroing them would make the "is the DSP
        // running at all" diagnostic lie after every pause/resume.
    }

    /// Push mono PCM. `out_telemetry` receives a fixed-layout diagnostic vector.
    ///
    /// Telemetry layout (must stay in sync with `NativeBridge.cpp`):
    /// ```text
    ///  0 sub      1 lowMid   2 vocal    3 presence  4 air
    ///  5 loudness 6 body     7 kickOn   8 snareOn   9 vocalOn
    /// 10 bodyOn  11 blocks  12 windows 13 onsets  14 dropped
    /// ```
    pub fn process(&mut self, mono: &[f32], out_telemetry: &mut [f32]) -> usize {
        self.blocks = self.blocks.wrapping_add(1);

        // A single AudioTrack.write may contain zero, one, or many analysis
        // windows. Telemetry is a per-write snapshot: retain the latest valid
        // band levels when this write is shorter than WINDOW, but clear onset
        // fields first so a previous write's transient is never repeated.
        let mut block_onsets = [0.0f32; 4];
        if out_telemetry.len() >= 15 {
            out_telemetry[..7].copy_from_slice(&self.last_band_telemetry);
            out_telemetry[7..11].fill(0.0);
            out_telemetry[11] = self.blocks as f32;
            out_telemetry[12] = self.windows as f32;
            out_telemetry[13] = self.onsets_emitted as f32;
            out_telemetry[14] = self.onsets.dropped() as f32;
        }

        // Accumulate into the ring, analysing once per full window.
        let mut consumed = 0usize;
        while consumed < mono.len() {
            let space = WINDOW - self.filled;
            let take = space.min(mono.len() - consumed);
            for _ in 0..take {
                let sample = mono[consumed];
                self.history[self.write_pos] = if sample.is_finite() {
                    sample.clamp(-1.0, 1.0)
                } else {
                    0.0
                };
                self.write_pos += 1;
                if self.write_pos == WINDOW {
                    self.write_pos = 0;
                }
                consumed += 1;
            }
            self.filled += take;
            self.total_samples = self.total_samples.wrapping_add(take as u64);

            if self.filled == WINDOW {
                self.analyse_window(out_telemetry);
                if out_telemetry.len() >= 15 {
                    self.last_band_telemetry.copy_from_slice(&out_telemetry[..7]);
                    for (index, value) in out_telemetry[7..11].iter().copied().enumerate() {
                        block_onsets[index] = block_onsets[index].max(value);
                    }
                }
                // Stateful IIR filters must consume every source sample exactly
                // once. A half-overlapped analysis window would replay 512 samples
                // through each filter and corrupt its delay state. Keep a
                // non-overlapping 1024-sample hop (~21.3 ms at 48 kHz); the JNI
                // scheduler remains independently paced at 5 ms.
                self.filled = 0;
            }
        }
        if out_telemetry.len() >= 15 {
            // Preserve any onset found in any completed window of this write.
            // Previously a later quiet window overwrote an earlier transient
            // with zero, making large FloatArray writes appear event-free.
            out_telemetry[7..11].copy_from_slice(&block_onsets);
            out_telemetry[11] = self.blocks as f32;
            out_telemetry[12] = self.windows as f32;
            out_telemetry[13] = self.onsets_emitted as f32;
            out_telemetry[14] = self.onsets.dropped() as f32;
        }
        consumed
    }

    /// Copy the ring into `window` in chronological order, then analyse.
    fn analyse_window(&mut self, out_telemetry: &mut [f32]) {
        // `write_pos` points at the oldest sample because the ring just filled.
        for i in 0..WINDOW {
            let mut idx = self.write_pos + i;
            if idx >= WINDOW {
                idx -= WINDOW;
            }
            self.window[i] = self.history[idx];
        }
        self.windows = self.windows.wrapping_add(1);

        // Band energies from filtered copies. Coefficients and delay state are
        // retained between windows; only the two fixed scratch buffers are reused.
        //
        // The low and high edges come from the UI style preset. Previously they
        // were compile-time constants, so selecting "bass" or "crisp" changed the
        // output envelope but the analyser kept looking at 55-650 Hz regardless.
        let sub = self.band_rms_cascade();
        let low_mid = filtered_band_rms(&self.window, &mut self.scratch_b,
            &mut self.seg_energy[1],
            &mut self.lowmid_lo_a, &mut self.lowmid_lo_b,
            &mut self.lowmid_hi_a, &mut self.lowmid_hi_b);
        let vocal = filtered_band_rms(&self.window, &mut self.scratch_b,
            &mut self.seg_energy[2],
            &mut self.vocal_lo_a, &mut self.vocal_lo_b,
            &mut self.vocal_hi_a, &mut self.vocal_hi_b);
        // Presence and air stay fixed: they describe the transient crack and the
        // tape hiss that make a strike feel crisp, and shifting them with the
        // style preset made "bass" sound muffled rather than deeper.
        let presence = filtered_band_rms(&self.window, &mut self.scratch_b,
            &mut self.seg_energy[3],
            &mut self.presence_lo_a, &mut self.presence_lo_b,
            &mut self.presence_hi_a, &mut self.presence_hi_b);
        let air = filtered_band_rms(&self.window, &mut self.scratch_b,
            &mut self.seg_energy[4],
            &mut self.air_lo_a, &mut self.air_lo_b,
            &mut self.air_hi_a, &mut self.air_hi_b);

        let energies = BandEnergies { sub, low_mid, vocal, presence, air };
        let body = body_energy(&energies) * self.cfg.texture_gain;
        let loud = loudness(&energies);
        let amp = self.cfg.user_amplitude;

        // Publish a semantic frame every window so the continuous/output path
        // has data even when no onset fires.
        let semantic = AnalysisFrame {
            kick: (sub * amp).clamp(0.0, 1.0),
            snare: (low_mid * amp).clamp(0.0, 1.0),
            vocal: (vocal * amp).clamp(0.0, 1.0),
            body: (body * amp).clamp(0.0, 1.0),
            loudness: loud,
        };
        self.semantics.push(semantic);
        self.latest_semantic[0].store(semantic.kick.to_bits(), Ordering::Relaxed);
        self.latest_semantic[1].store(semantic.snare.to_bits(), Ordering::Relaxed);
        self.latest_semantic[2].store(semantic.vocal.to_bits(), Ordering::Relaxed);
        self.latest_semantic[3].store(semantic.body.to_bits(), Ordering::Relaxed);
        self.latest_semantic[4].store(semantic.loudness.to_bits(), Ordering::Relaxed);

        // FFT magnitudes drive a positive spectral-flux cue in addition to
        // band RMS deltas. The 0.25*N scale converts coherent sine peaks to
        // approximately their input amplitude before the relative-flux ratio.
        self.fft.magnitudes(&self.window, &mut self.magnitudes);
        let spectral_flux = self.measure_spectral_flux();
        let onset = self.detector.process_with_spectral_flux(energies, spectral_flux);
        if let Some(o) = onset {
            self.onsets_emitted = self.onsets_emitted.wrapping_add(1);
            let scaled = (o.activation * o.event.character_scale()).clamp(0.0, 1.0);
            let mut frame = OnsetFrame::default();
            match o.event {
                crate::onset::Event::Kick => frame.kick = scaled,
                crate::onset::Event::Snare => frame.snare = scaled,
                crate::onset::Event::Vocal => frame.vocal = scaled,
                crate::onset::Event::Body => frame.body = scaled,
            }
            frame.due_ns = self.audible_time_ns(o.event);
            self.onsets.push(frame);
        }

        // Previous magnitudes are updated by `measure_spectral_flux` above so
        // every analysis window compares against its immediate predecessor.

        if out_telemetry.len() >= 15 {
            out_telemetry[0] = sub;
            out_telemetry[1] = low_mid;
            out_telemetry[2] = vocal;
            out_telemetry[3] = presence;
            out_telemetry[4] = air;
            out_telemetry[5] = loud;
            out_telemetry[6] = body;
            out_telemetry[7] = onset.map_or(0.0, |o| if o.event == crate::onset::Event::Kick { o.activation } else { 0.0 });
            out_telemetry[8] = onset.map_or(0.0, |o| if o.event == crate::onset::Event::Snare { o.activation } else { 0.0 });
            out_telemetry[9] = onset.map_or(0.0, |o| if o.event == crate::onset::Event::Vocal { o.activation } else { 0.0 });
            out_telemetry[10] = onset.map_or(0.0, |o| if o.event == crate::onset::Event::Body { o.activation } else { 0.0 });
            out_telemetry[11] = self.blocks as f32;
            out_telemetry[12] = self.windows as f32;
            out_telemetry[13] = self.onsets_emitted as f32;
            out_telemetry[14] = self.onsets.dropped() as f32;
        }
    }

    /// Sub-band RMS using the dedicated kick filters.
    fn band_rms_cascade(&mut self) -> f32 {
        self.scratch_a.copy_from_slice(&self.window);
        self.sub_lo_a.process_block(&mut self.scratch_a);
        self.sub_lo_b.process_block(&mut self.scratch_a);
        self.sub_hi_a.process_block(&mut self.scratch_a);
        self.sub_hi_b.process_block(&mut self.scratch_a);
        segment_energies(&self.scratch_a, &mut self.seg_energy[0]);
        band_rms(&self.scratch_a)
    }

    /// Positive spectral flux, normalised per band to 0..1.
    ///
    /// Magnitudes are divided by the Hann-window coherent gain (~N/4), then
    /// positive bin changes are divided by current band energy plus a noise
    /// floor. This makes the cue relatively level-independent and keeps silent
    /// bands from generating enormous ratios.
    fn measure_spectral_flux(&mut self) -> BandEnergies {
        let rate = self.cfg.sample_rate.max(8000.0);
        let low_edge = self.cfg.band_low_hz;
        let high_edge = self.cfg.band_high_hz;
        let sub_hi = SUB_HI.min(low_edge + 150.0).max(low_edge + 40.0);
        let lowmid_hi = (high_edge * 0.4)
            .clamp(sub_hi + 60.0, LOWMID_HI.max(sub_hi + 200.0));
        let vocal_lo = lowmid_hi;
        let vocal_hi = high_edge.max(vocal_lo + 200.0);
        let bounds = [
            (SUB_LO, sub_hi),
            (low_edge, lowmid_hi),
            (vocal_lo, vocal_hi),
            (PRESENCE_LO, PRESENCE_HI),
            (AIR_LO, AIR_HI),
        ];
        let mut positive = [0.0f32; 5];
        let mut current_sum = [0.0f32; 5];
        let normalizer = WINDOW as f32 * 0.25;

        for i in 0..self.magnitudes.len() {
            let hz = (i as f32 + 1.0) * rate / WINDOW as f32;
            let current = (self.magnitudes[i] / normalizer).max(0.0);
            let previous = (self.previous_magnitudes[i] / normalizer).max(0.0);
            self.previous_magnitudes[i] = self.magnitudes[i];
            for band in 0..bounds.len() {
                let (lo, hi) = bounds[band];
                if hz >= lo && hz < hi {
                    current_sum[band] += current;
                    positive[band] += (current - previous).max(0.0);
                }
            }
        }

        let mut flux = [0.0f32; 5];
        for i in 0..flux.len() {
            flux[i] = (positive[i] / (current_sum[i] + 0.025)).clamp(0.0, 1.0);
        }
        BandEnergies {
            sub: flux[0],
            low_mid: flux[1],
            vocal: flux[2],
            presence: flux[3],
            air: flux[4],
        }
    }

    /// Declare when the *next* sample passed to [Engine::process] becomes
    /// audible, as a CLOCK_MONOTONIC timestamp in nanoseconds (<= 0 = unknown).
    ///
    /// The host derives this from `AudioTrack.getTimestamp()` plus the number of
    /// frames already queued, so it already includes the buffer depth and the
    /// output pipeline latency. Must be called by the producer thread before the
    /// matching `process` call.
    pub fn set_block_time(&mut self, audible_ns: i64) {
        if audible_ns > 0 {
            self.anchor_ns = audible_ns;
            self.anchor_sample = self.total_samples;
        } else {
            self.anchor_ns = 0;
        }
    }

    /// When the transient of the window just analysed becomes audible.
    ///
    /// The window ends at `total_samples`; the attack is localised inside it from
    /// the short-time band energies of the bands that characterise `event`.
    fn audible_time_ns(&self, event: crate::onset::Event) -> i64 {
        if self.anchor_ns <= 0 {
            return 0;
        }
        let window_start = self.total_samples.saturating_sub(WINDOW as u64);

        let located = match event {
            crate::onset::Event::Kick => locate_attack(&self.seg_energy[0]),
            crate::onset::Event::Snare => {
                match (locate_attack(&self.seg_energy[1]), locate_attack(&self.seg_energy[3])) {
                    (Some(a), Some(b)) => Some(a.min(b)),
                    (a, b) => a.or(b),
                }
            }
            crate::onset::Event::Vocal => locate_attack(&self.seg_energy[2]),
            crate::onset::Event::Body => locate_attack(&self.seg_energy[4]),
        };
        let rate = self.cfg.sample_rate.max(8000.0) as f64;
        let bias_ms = f64::from(match event {
            crate::onset::Event::Kick => KICK_LOCATE_BIAS_MS,
            crate::onset::Event::Snare => SNARE_LOCATE_BIAS_MS,
            _ => 0.0,
        });
        // Without a usable localisation fall back to the window centre, the
        // minimum-variance guess for an attack somewhere inside it.
        let offset = located.unwrap_or(WINDOW / 2) as f64 - bias_ms * 1.0e-3 * rate;
        let onset_sample = window_start as f64 + offset.max(0.0);
        let delta_samples = onset_sample - self.anchor_sample as f64;
        self.anchor_ns.saturating_add((delta_samples * 1.0e9 / rate) as i64).max(1)
    }

    /// Newest semantic frame. Lock-free and non-destructive; safe to call from
    /// the scheduler thread at any rate.
    pub fn latest_semantic(&self) -> AnalysisFrame {
        let load = |i: usize| f32::from_bits(self.latest_semantic[i].load(Ordering::Relaxed));
        AnalysisFrame {
            kick: load(0),
            snare: load(1),
            vocal: load(2),
            body: load(3),
            loudness: load(4),
        }
    }

    /// Pop one onset frame, if available.
    pub fn pop_onset(&self, out: &mut OnsetFrame) -> bool {
        match self.onsets.pop() {
            Some(v) => { *out = v; true }
            None => false,
        }
    }

    /// Pop one semantic frame, if available.
    pub fn pop_semantic(&self, out: &mut AnalysisFrame) -> bool {
        match self.semantics.pop() {
            Some(v) => { *out = v; true }
            None => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sine(freq: f32, rate: f32, n: usize, amp: f32) -> Vec<f32> {
        (0..n)
            .map(|i| amp * (2.0 * core::f32::consts::PI * freq * i as f32 / rate).sin())
            .collect()
    }

    fn engine() -> Engine {
        Engine::new(EngineConfig { sample_rate: 48000.0, ..Default::default() })
    }

    #[test]
    fn silence_produces_no_onsets() {
        let mut e = engine();
        let mut telemetry = [0.0f32; 15];
        let silence = vec![0.0f32; 48000];
        e.process(&silence, &mut telemetry);
        assert_eq!(e.onsets_emitted(), 0);
        assert_eq!(telemetry[0], 0.0);
    }

    #[test]
    fn kick_band_responds_to_low_frequency() {
        let mut e = engine();
        let mut telemetry = [0.0f32; 15];
        let low = sine(60.0, 48000.0, 48000, 0.6);
        e.process(&low, &mut telemetry);
        // The sub band must dominate a 60 Hz tone.
        assert!(telemetry[0] > telemetry[2], "sub={} vocal={}", telemetry[0], telemetry[2]);
        assert!(telemetry[0] > 0.01, "sub band was silent: {}", telemetry[0]);
    }

    #[test]
    fn air_band_responds_to_high_frequency() {
        let mut e = engine();
        let mut telemetry = [0.0f32; 15];
        let high = sine(11000.0, 48000.0, 48000, 0.6);
        e.process(&high, &mut telemetry);
        assert!(telemetry[4] > telemetry[0], "air={} sub={}", telemetry[4], telemetry[0]);
        // Regression guard for the constant 0.1 texture value.
        assert!(telemetry[4] > 0.005, "air band too small: {}", telemetry[4]);
    }

    #[test]
    fn body_energy_is_not_constant() {
        let mut e = engine();
        let mut t_quiet = [0.0f32; 15];
        let mut t_loud = [0.0f32; 15];
        e.process(&vec![0.0f32; 24000], &mut t_quiet);
        e.process(&sine(3000.0, 48000.0, 24000, 0.7), &mut t_loud);
        assert!(t_loud[6] > t_quiet[6], "body must respond to content");
    }

    #[test]
    fn filters_consume_non_overlapping_windows() {
        let mut e = engine();
        let mut telemetry = [0.0f32; 15];
        let block = vec![0.0f32; WINDOW / 2];
        e.process(&block, &mut telemetry);
        e.process(&block, &mut telemetry);
        assert_eq!(e.windows(), 1, "first 1024 fresh samples produce one frame");
        e.process(&block, &mut telemetry);
        assert_eq!(e.windows(), 1, "the next half-window is not replayed");
        e.process(&block, &mut telemetry);
        assert_eq!(e.windows(), 2, "second frame requires another 1024 samples");
    }

    #[test]
    fn short_audio_writes_keep_last_band_levels_but_clear_onsets() {
        let mut e = engine();
        let mut telemetry = [0.0f32; 15];
        e.process(&sine(60.0, 48000.0, WINDOW, 0.6), &mut telemetry);
        let measured_sub = telemetry[0];
        assert!(measured_sub > 0.01, "first complete window should produce a band level");

        // AudioTrack commonly delivers sub-window writes (e.g. 960 frames at
        // 48 kHz). Keep the latest level for UI continuity, but never repeat
        // transient flags from the preceding write.
        e.process(&vec![0.0; 256], &mut telemetry);
        assert_eq!(telemetry[0], measured_sub);
        assert!(telemetry[7..11].iter().all(|value| *value == 0.0));
    }

    #[test]
    fn per_write_onset_telemetry_aggregates_all_completed_windows() {
        let mut e = engine();
        let mut telemetry = [0.0f32; 15];
        let rate = 48000usize;
        let mut block = vec![0.0f32; rate * 2];
        let mut pos = 0usize;
        while pos + 960 < block.len() {
            for i in 0..960 {
                let t = i as f32 / rate as f32;
                block[pos + i] = 0.8 * (2.0 * core::f32::consts::PI * 60.0 * t).sin();
            }
            pos += rate / 4;
        }
        // One large write contains many transient windows followed by quiet
        // windows. The final quiet window must not erase earlier detections.
        e.process(&block, &mut telemetry);
        assert!(e.onsets_emitted() > 0, "burst should emit at least one onset");
        let telemetry_peak = telemetry[7..11]
            .iter()
            .copied()
            .fold(0.0f32, |peak, value| peak.max(value));
        assert!(telemetry_peak > 0.0, "a later quiet window must not erase a transient in the same write");
    }

    #[test]
    fn sample_rate_change_rebuilds_and_reports() {
        let mut e = engine();
        assert!(!e.set_sample_rate(48000.0), "same rate must not rebuild");
        assert!(e.set_sample_rate(44100.0), "new rate must rebuild");
        assert_eq!(e.sample_rate(), 44100.0);
    }

    #[test]
    fn bands_stay_correct_after_rate_change() {
        // A 60 Hz tone must still land in the sub band at 44.1 kHz; stale
        // coefficients would shift the band edges and break this.
        let mut e = engine();
        e.set_sample_rate(44100.0);
        let mut telemetry = [0.0f32; 15];
        e.process(&sine(60.0, 44100.0, 44100, 0.6), &mut telemetry);
        assert!(telemetry[0] > telemetry[2], "sub={} vocal={}", telemetry[0], telemetry[2]);
    }

    #[test]
    fn impulse_train_emits_onsets() {
        let mut e = engine();
        let mut telemetry = [0.0f32; 15];
        // 4 Hz kick-like bursts: 20 ms of 60 Hz, then silence.
        let rate = 48000usize;
        let mut buf = vec![0.0f32; rate * 2];
        let mut pos = 0;
        while pos + 960 < buf.len() {
            for i in 0..960 {
                let t = i as f32 / rate as f32;
                buf[pos + i] = 0.8 * (2.0 * core::f32::consts::PI * 60.0 * t).sin();
            }
            pos += rate / 4;
        }
        e.process(&buf, &mut telemetry);
        assert!(e.onsets_emitted() > 0, "impulse train produced no onsets");
    }

    #[test]
    fn onset_frame_is_poppable_after_trigger() {
        let mut e = engine();
        let mut telemetry = [0.0f32; 15];
        let rate = 48000usize;
        let mut buf = vec![0.0f32; rate];
        for i in 0..4800 {
            let t = i as f32 / rate as f32;
            buf[i] = 0.9 * (2.0 * core::f32::consts::PI * 55.0 * t).sin();
        }
        e.process(&buf, &mut telemetry);
        let mut frame = OnsetFrame::default();
        assert!(e.pop_onset(&mut frame), "expected a poppable onset");
        assert!(frame.kick > 0.0);
    }

    #[test]
    fn reset_clears_counters_and_buffers() {
        let mut e = engine();
        let mut telemetry = [0.0f32; 15];
        e.process(&sine(100.0, 48000.0, 24000, 0.5), &mut telemetry);
        e.reset();
        assert_eq!(e.pending_onsets(), 0);
        assert_eq!(e.onsets_emitted(), 0);
    }

    #[test]
    fn blocks_counter_advances_per_call() {
        let mut e = engine();
        let mut telemetry = [0.0f32; 15];
        let block = vec![0.0f32; BLOCK];
        for _ in 0..4 {
            e.process(&block, &mut telemetry);
        }
        assert_eq!(e.blocks(), 4);
    }

    #[test]
    fn non_finite_configuration_and_setters_fall_back_to_finite_values() {
        let nan = f32::NAN;
        let cfg = EngineConfig {
            sample_rate: nan,
            sensitivity: nan,
            user_amplitude: nan,
            refractory_ms: nan,
            band_low_hz: nan,
            band_high_hz: nan,
            bass_boost: nan,
            texture_gain: nan,
        };
        let mut e = Engine::new(cfg);
        assert_eq!(e.sample_rate(), 48000.0);
        assert_eq!(e.band_edges(), (30.0, 2500.0));
        assert!(!e.set_sample_rate(nan));
        e.set_sensitivity(nan);
        e.set_user_amplitude(nan);
        e.set_refractory_ms(nan);
        e.set_bass_boost(nan);
        e.set_texture_gain(nan);
        let mut telemetry = [0.0f32; 15];
        e.process(&vec![0.0; WINDOW], &mut telemetry);
        assert!(telemetry.iter().all(|v| v.is_finite()));
    }

    #[test]
    fn user_amplitude_scales_published_energy() {
        let mut e = engine();
        let mut t_low = [0.0f32; 15];
        e.set_user_amplitude(0.5);
        e.process(&sine(60.0, 48000.0, 24000, 0.6), &mut t_low);
        let a = t_low[0];

        let mut e2 = engine();
        let mut t_high = [0.0f32; 15];
        e2.set_user_amplitude(1.0);
        e2.process(&sine(60.0, 48000.0, 24000, 0.6), &mut t_high);
        let b = t_high[0];
        // Telemetry band values are pre-amplitude; only the semantic frame is
        // scaled, so the raw band readings must match.
        assert!((a - b).abs() < 1e-3);
    }
    #[test]
    fn style_edges_reconfigure_persistent_low_mid_filter() {
        let tone = sine(60.0, 48000.0, 48000, 0.6);
        let mut default_engine = engine();
        let mut tuned_engine = engine();
        assert!(tuned_engine.set_band_edges(90.0, 1400.0));
        let mut default_telemetry = [0.0f32; 15];
        let mut tuned_telemetry = [0.0f32; 15];
        default_engine.process(&tone, &mut default_telemetry);
        tuned_engine.process(&tone, &mut tuned_telemetry);
        assert!(
            tuned_telemetry[1] < default_telemetry[1],
            "raising the low-mid edge should attenuate 60 Hz: tuned={} default={}",
            tuned_telemetry[1], default_telemetry[1]
        );
    }

    #[test]
    fn non_finite_input_is_sanitized_before_filtering() {
        let mut e = engine();
        let mut telemetry = [0.0f32; 15];
        let mut input = sine(60.0, 48000.0, 4096, 0.4);
        input[10] = f32::NAN;
        input[20] = f32::INFINITY;
        e.process(&input, &mut telemetry);
        assert!(telemetry.iter().all(|v| v.is_finite()), "telemetry must stay finite");
    }


    /// Kick-like burst: 60 Hz sine with a 60 ms decay starting at `start`.
    fn kick_at(total: usize, start: usize, rate: f32) -> Vec<f32> {
        let mut buf = vec![0.0f32; total];
        for i in start..total.min(start + 9600) {
            let t = (i - start) as f32 / rate;
            buf[i] = 0.9 * (-t / 0.060).exp() * (2.0 * core::f32::consts::PI * 60.0 * t).sin();
        }
        buf
    }

    #[test]
    fn onsets_without_playback_anchor_report_unknown_time() {
        let mut e = engine();
        let mut telemetry = [0.0f32; 15];
        let mut buf = vec![0.0f32; 48000];
        buf.extend(kick_at(48000, 24000, 48000.0));
        e.process(&buf, &mut telemetry);
        let mut frame = OnsetFrame::default();
        let mut seen = 0;
        while e.pop_onset(&mut frame) {
            assert_eq!(frame.due_ns, 0, "no anchor was supplied");
            seen += 1;
        }
        assert!(seen > 0, "the burst must still be detected");
    }

    #[test]
    fn anchored_onset_is_placed_near_the_true_attack_time() {
        let rate = 48000.0f32;
        let mut e = engine();
        let mut telemetry = [0.0f32; 15];
        // The kick starts 1.2 s into the stream, at an off-grid sample.
        let kick_start = 57_713usize;
        let total = 48000 * 3;
        let mut buf = vec![0.0f32; total];
        let burst = kick_at(total, kick_start, rate);
        buf.copy_from_slice(&burst);

        // Stream sample 0 becomes audible at this monotonic time.
        let base_ns: i64 = 5_000_000_000;
        let block = 4096usize;
        let mut pos = 0usize;
        let mut found: Option<i64> = None;
        let mut frame = OnsetFrame::default();
        while pos < total {
            let end = (pos + block).min(total);
            let audible = base_ns + (pos as f64 * 1.0e9 / rate as f64) as i64;
            e.set_block_time(audible);
            e.process(&buf[pos..end], &mut telemetry);
            while e.pop_onset(&mut frame) {
                if found.is_none() && frame.kick > 0.0 {
                    found = Some(frame.due_ns);
                }
            }
            pos = end;
        }
        let due = found.expect("the kick must be detected");
        let expected = base_ns + (kick_start as f64 * 1.0e9 / rate as f64) as i64;
        let error_ms = (due - expected) as f64 / 1.0e6;
        assert!(
            error_ms.abs() < 6.0,
            "kick audible time off by {error_ms:.2} ms (due={due}, expected={expected})"
        );
    }

    #[test]
    fn latest_semantic_tracks_the_newest_window_not_the_oldest() {
        let mut e = engine();
        let mut telemetry = [0.0f32; 15];
        // Loud first, then a long quiet tail that fills the FIFO ring. The FIFO
        // hands back the oldest retained frame, the snapshot must report silence.
        e.process(&sine(60.0, 48000.0, WINDOW * 4, 0.8), &mut telemetry);
        e.process(&vec![0.0f32; WINDOW * 40], &mut telemetry);
        let latest = e.latest_semantic();
        assert!(latest.kick < 0.01, "latest kick={} should be ~0 after silence", latest.kick);
        let mut oldest = AnalysisFrame::default();
        assert!(e.pop_semantic(&mut oldest));
        assert!(oldest.kick > latest.kick, "FIFO head is older and louder");
    }

    #[test]
    fn rate_change_does_not_shift_the_sample_clock() {
        let mut e = engine();
        let mut telemetry = [0.0f32; 15];
        e.process(&vec![0.0f32; 700], &mut telemetry);
        let before = e.total_samples;
        e.set_sample_rate(44100.0);
        assert_eq!(e.total_samples, before - 700, "discarded partial window leaves the clock");
    }
}
