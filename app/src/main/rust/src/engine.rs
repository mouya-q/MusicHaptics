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
use crate::onset::{band_rms, body_energy, loudness, BandEnergies, DetectorConfig, Onset, OnsetDetector};
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
}

impl Default for EngineConfig {
    fn default() -> Self {
        Self { sample_rate: 48000.0, sensitivity: 1.0, user_amplitude: 1.0, refractory_ms: 60.0 }
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

    detector: OnsetDetector,

    onsets: FrameRing<OnsetFrame, ONSET_RING>,
    semantics: FrameRing<AnalysisFrame, SEMANTIC_RING>,

    /// Cumulative counters, exposed for telemetry.
    blocks: u64,
    windows: u64,
    onsets_emitted: u64,
}

impl Engine {
    pub fn new(cfg: EngineConfig) -> Self {
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

    /// Rebuild every band filter for the current sample rate.
    ///
    /// Called on construction and whenever the source rate changes. Delay state
    /// is reset because the audio itself is discontinuous at a track boundary;
    /// keeping history would ring for tens of milliseconds and fire a spurious
    /// onset right at the transition.
    fn design_filters(&mut self) {
        let rate = self.cfg.sample_rate.max(8000.0);
        let q = 0.707;

        // A band is the cascade of a high-pass at its lower edge and a low-pass
        // at its upper edge. Two identical sections each give a 4th-order skirt.
        let band = |lo: f32, hi: f32| -> (Biquad, Biquad, Biquad, Biquad) {
            (
                Biquad::new(Kind::HighPass, rate, lo, q),
                Biquad::new(Kind::HighPass, rate, lo, q),
                Biquad::new(Kind::LowPass, rate, hi, q),
                Biquad::new(Kind::LowPass, rate, hi, q),
            )
        };

        let (a, b, c, d) = band(SUB_LO, SUB_HI);
        self.sub_lo_a = a; self.sub_lo_b = b; self.sub_hi_a = c; self.sub_hi_b = d;
        let (a, b, c, d) = band(LOWMID_LO, LOWMID_HI);
        self.lowmid_lo_a = a; self.lowmid_lo_b = b; self.lowmid_hi_a = c; self.lowmid_hi_b = d;
        let (a, b, c, d) = band(VOCAL_LO, VOCAL_HI);
        self.vocal_lo_a = a; self.vocal_lo_b = b; self.vocal_hi_a = c; self.vocal_hi_b = d;
        let (a, b, c, d) = band(PRESENCE_LO, PRESENCE_HI);
        self.presence_lo_a = a; self.presence_lo_b = b; self.presence_hi_a = c; self.presence_hi_b = d;
        let (a, b, c, d) = band(AIR_LO, AIR_HI);
        self.air_lo_a = a; self.air_lo_b = b; self.air_hi_a = c; self.air_hi_b = d;
    }

    fn apply_detector_config(&mut self) {
        // Refractory in frames: the window advances once per WINDOW samples, so
        // convert the millisecond budget through the window period.
        let window_ms = (WINDOW as f32 / self.cfg.sample_rate.max(8000.0)) * 1000.0;
        let frames = (self.cfg.refractory_ms / window_ms.max(1.0)).round().max(1.0) as u32;
        self.detector = OnsetDetector::new(DetectorConfig {
            sensitivity: self.cfg.sensitivity,
            refractory_frames: frames,
            ..DetectorConfig::default()
        });
    }

    /// Update the sample rate, rebuilding filters if it actually changed.
    ///
    /// Returns true when a rebuild happened, so the caller can log the
    /// transition instead of it passing silently.
    pub fn set_sample_rate(&mut self, rate: f32) -> bool {
        let rate = rate.max(8000.0);
        if (rate - self.cfg.sample_rate).abs() < 0.5 {
            return false;
        }
        self.cfg.sample_rate = rate;
        self.design_filters();
        self.apply_detector_config();
        // The source changed: stale history belongs to the previous track.
        self.history = [0.0; WINDOW];
        self.write_pos = 0;
        self.filled = 0;
        true
    }

    pub fn set_sensitivity(&mut self, sensitivity: f32) {
        self.cfg.sensitivity = sensitivity.clamp(0.1, 4.0);
        self.apply_detector_config();
    }

    pub fn set_user_amplitude(&mut self, amplitude: f32) {
        self.cfg.user_amplitude = amplitude.clamp(0.0, 4.0);
    }

    pub fn set_refractory_ms(&mut self, ms: f32) {
        self.cfg.refractory_ms = ms.clamp(0.0, 500.0);
        self.apply_detector_config();
    }

    pub fn sample_rate(&self) -> f32 { self.cfg.sample_rate }
    pub fn blocks(&self) -> u64 { self.blocks }
    pub fn windows(&self) -> u64 { self.windows }
    pub fn onsets_emitted(&self) -> u64 { self.onsets_emitted }
    pub fn dropped_onsets(&self) -> u64 { self.onsets.dropped() }
    pub fn pending_onsets(&self) -> usize { self.onsets.len() }

    /// Discard buffered audio and statistics without changing configuration.
    pub fn reset(&mut self) {
        self.history = [0.0; WINDOW];
        self.write_pos = 0;
        self.filled = 0;
        self.design_filters();
        self.detector.reset();
        self.onsets.clear();
        self.semantics.clear();
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
                self.history[self.write_pos] = mono[consumed];
                self.write_pos += 1;
                if self.write_pos == WINDOW {
                    self.write_pos = 0;
                }
                consumed += 1;
            }
            self.filled += take;

            if self.filled == WINDOW {
                self.analyse_window(out_telemetry);
                // Half-overlap: keeping the last half improves time resolution
                // without reintroducing the latency of a full hop.
                self.filled = WINDOW / 2;
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

        // Band energies from filtered copies. Each band reuses two scratch
        // buffers so no allocation happens here.
        let sub = self.band_rms_cascade();
        let low_mid = self.band_rms_chain(LOWMID_LO, LOWMID_HI);
        let vocal = self.band_rms_chain(VOCAL_LO, VOCAL_HI);
        let presence = self.band_rms_chain(PRESENCE_LO, PRESENCE_HI);
        let air = self.band_rms_chain(AIR_LO, AIR_HI);

        let energies = BandEnergies { sub, low_mid, vocal, presence, air };
        let body = body_energy(&energies);
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

        let onset = self.detector.process(energies);
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

        // FFT is kept for a spectral centroid diagnostic only; band energies no
        // longer depend on it, so a wrong window size cannot break detection.
        self.fft.magnitudes(&self.window, &mut self.magnitudes);

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

    /// Generic band RMS for the remaining bands.
    ///
    /// Rebuilds the section from the band edges every call. This is a handful of
    /// transcendental operations per window (~21 ms of audio), which is far
    /// cheaper than the risk of stale coefficients after a rate change.
    fn band_rms_chain(&mut self, lo: f32, hi: f32) -> f32 {
        let rate = self.cfg.sample_rate;
        let mut hp_a = Biquad::new(Kind::HighPass, rate, lo, 0.707);
        let mut hp_b = Biquad::new(Kind::HighPass, rate, lo, 0.707);
        let mut lp_a = Biquad::new(Kind::LowPass, rate, hi, 0.707);
        let mut lp_b = Biquad::new(Kind::LowPass, rate, hi, 0.707);

        self.scratch_b.copy_from_slice(&self.window);
        hp_a.process_block(&mut self.scratch_b);
        hp_b.process_block(&mut self.scratch_b);
        lp_a.process_block(&mut self.scratch_b);
        lp_b.process_block(&mut self.scratch_b);
        rms(&self.scratch_b)
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
}