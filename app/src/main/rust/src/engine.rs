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

    /// Cumulative counters, exposed for telemetry.
    blocks: u64,
    windows: u64,
    onsets_emitted: u64,
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

#[inline]
fn filtered_band_rms(
    window: &[f32; WINDOW],
    scratch: &mut [f32; WINDOW],
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
            blocks: 0,
            windows: 0,
            onsets_emitted: 0,
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
        // The source changed: stale history belongs to the previous track.
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
        self.history = [0.0; WINDOW];
        self.previous_magnitudes = [0.0; WINDOW / 2];
        self.write_pos = 0;
        self.filled = 0;
        self.design_filters();
        self.reset_filter_states();
        self.detector.reset();
        self.onsets.clear();
        self.semantics.clear();
        self.onsets_emitted = 0;
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

            if self.filled == WINDOW {
                self.analyse_window(out_telemetry);
                // Stateful IIR filters must consume every source sample exactly
                // once. A half-overlapped analysis window would replay 512 samples
                // through each filter and corrupt its delay state. Keep a
                // non-overlapping 1024-sample hop (~21.3 ms at 48 kHz); the JNI
                // scheduler remains independently paced at 5 ms.
                self.filled = 0;
            }
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
            &mut self.lowmid_lo_a, &mut self.lowmid_lo_b,
            &mut self.lowmid_hi_a, &mut self.lowmid_hi_b);
        let vocal = filtered_band_rms(&self.window, &mut self.scratch_b,
            &mut self.vocal_lo_a, &mut self.vocal_lo_b,
            &mut self.vocal_hi_a, &mut self.vocal_hi_b);
        // Presence and air stay fixed: they describe the transient crack and the
        // tape hiss that make a strike feel crisp, and shifting them with the
        // style preset made "bass" sound muffled rather than deeper.
        let presence = filtered_band_rms(&self.window, &mut self.scratch_b,
            &mut self.presence_lo_a, &mut self.presence_lo_b,
            &mut self.presence_hi_a, &mut self.presence_hi_b);
        let air = filtered_band_rms(&self.window, &mut self.scratch_b,
            &mut self.air_lo_a, &mut self.air_lo_b,
            &mut self.air_hi_a, &mut self.air_hi_b);

        let energies = BandEnergies { sub, low_mid, vocal, presence, air };
        let body = body_energy(&energies) * self.cfg.texture_gain;
        let loud = loudness(&energies);
        let amp = self.cfg.user_amplitude;

        // Publish a semantic frame every window so the continuous/output path
        // has data even when no onset fires.
        self.semantics.push(AnalysisFrame {
            kick: (sub * amp).clamp(0.0, 1.0),
            snare: (low_mid * amp).clamp(0.0, 1.0),
            vocal: (vocal * amp).clamp(0.0, 1.0),
            body: (body * amp).clamp(0.0, 1.0),
            loudness: loud,
        });

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

}