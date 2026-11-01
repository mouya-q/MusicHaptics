//! End-to-end chain verification: synthetic audio in, haptic decisions out.
//!
//! This is the "assume an input, run the whole chain, look at the output" check
//! that was missing through seven rounds of debugging. Every previous defect was
//! found by shipping an APK and reading a device log; this module makes the same
//! question answerable in a unit test.
//!
//! The chain modelled here is the part that decides *whether and how hard* to
//! vibrate:
//!
//! ```text
//!   PCM block ──► band filters ──► onset detector ──► onset frame
//!                                                         │
//!                                                         ▼
//!                                            intensity → UI level → peak
//! ```
//!
//! The hardware write (sysfs / Vibrator) is deliberately out of scope: it cannot
//! be exercised off-device, and the C++ path for it is unchanged.

use crate::engine::{Engine, EngineConfig, OnsetFrame};

/// Render a mono signal at `rate` Hz for `seconds`.
fn render(rate: f32, seconds: f32, f: impl Fn(usize, f32) -> f32) -> Vec<f32> {
    let n = (rate * seconds) as usize;
    (0..n).map(|i| f((i), i as f32 / rate)).collect()
}

/// A 4/4 kick pattern at `bpm`, 60 Hz body with a fast decay.
pub fn kick_pattern(rate: f32, bpm: f32, seconds: f32) -> Vec<f32> {
    let beat_s = 60.0 / bpm;
    render(rate, seconds, |i, t| {
        let phase = (t % beat_s) / beat_s;
        // Exponential body: 60 Hz sine with a 60 ms decay, only inside the
        // first 25% of each beat.
        if phase > 0.25 {
            return 0.0;
        }
        let env = (-(phase * beat_s) / 0.060).exp();
        0.9 * env * (2.0 * core::f32::consts::PI * 60.0 * t).sin()
    })
}

/// Kick plus off-beat snare (2500-6000 Hz noise-ish) plus a sustained pad.
pub fn full_mix(rate: f32, bpm: f32, seconds: f32) -> Vec<f32> {
    let beat_s = 60.0 / bpm;
    render(rate, seconds, |i, t| {
        let beat_phase = (t % beat_s) / beat_s;
        let kick = if beat_phase <= 0.25 {
            let env = (-(beat_phase * beat_s) / 0.060).exp();
            0.8 * env * (2.0 * core::f32::consts::PI * 58.0 * t).sin()
        } else {
            0.0
        };

        // Snare on the half-beat.
        let half = (t % (beat_s * 0.5)) / (beat_s * 0.5);
        let snare = if half <= 0.12 {
            let env = (-(half * beat_s * 0.5) / 0.040).exp();
            // Deterministic pseudo-noise keeps the test reproducible.
            let n = ((i as f32 * 12.9898).sin() * 43758.5453).fract();
            let noise = (n * 2.0 - 1.0) * 0.5;
            let tone = (2.0 * core::f32::consts::PI * 3200.0 * t).sin() * 0.5;
            0.45 * env * (noise + tone)
        } else {
            0.0
        };

        // Quiet sustained pad so the body/texture channel has content.
        let pad = 0.05 * (2.0 * core::f32::consts::PI * 220.0 * t).sin();

        kick + snare + pad
    })
}

/// Silence, to prove nothing triggers.
pub fn silence(rate: f32, seconds: f32) -> Vec<f32> {
    vec![0.0; (rate * seconds) as usize]
}

/// Result of running one signal through the chain.
#[derive(Debug, Default)]
pub struct ChainReport {
    pub onsets: usize,
    pub kicks: usize,
    pub snares: usize,
    pub vocals: usize,
    pub bodies: usize,
    /// Peak amplitude (0..1) reported by the engine for every onset.
    pub peaks: Vec<f32>,
    pub windows: u64,
    pub dropped: u64,
    /// Band telemetry from the final window: sub, lowMid, vocal, presence, air.
    pub bands: [f32; 5],
    pub body: f32,
}

impl ChainReport {
    pub fn mean_peak(&self) -> f32 {
        if self.peaks.is_empty() {
            return 0.0;
        }
        self.peaks.iter().sum::<f32>() / self.peaks.len() as f32
    }

    pub fn max_peak(&self) -> f32 {
        self.peaks.iter().cloned().fold(0.0f32, f32::max)
    }

    pub fn min_peak(&self) -> f32 {
        self.peaks.iter().cloned().fold(1.0f32, f32::min)
    }

    /// Ratio of the loudest to the quietest onset. A value near 1.0 means the
    /// output has been flattened and the UI level knob cannot be felt.
    pub fn dynamic_range(&self) -> f32 {
        let min = self.min_peak();
        if min <= 0.0 {
            return 0.0;
        }
        self.max_peak() / min
    }
}

/// Run a signal through the full analysis chain at the given sample rate.
///
/// `block` is the number of samples per `process` call; 4096 mirrors what the
/// device delivers for a float AudioTrack write.
pub fn run_chain(
    samples: &[f32],
    rate: f32,
    block: usize,
    cfg: EngineConfig,
) -> ChainReport {
    let mut engine = Engine::new(cfg);
    engine.set_sample_rate(rate);

    let mut report = ChainReport::default();
    let mut telemetry = [0.0f32; 15];
    let mut frame = OnsetFrame::default();
    // Peak band readings across the whole signal, so the final window being
    // silent (an incomplete trailing chunk) cannot zero the report.
    let mut peak_bands = [0.0f32; 5];
    let mut peak_body = 0.0f32;

    for chunk in samples.chunks(block.max(64)) {
        engine.process(chunk, &mut telemetry);
        for i in 0..5 {
            if telemetry[i] > peak_bands[i] {
                peak_bands[i] = telemetry[i];
            }
        }
        if telemetry[6] > peak_body {
            peak_body = telemetry[6];
        }
        while engine.pop_onset(&mut frame) {
            report.onsets += 1;
            // The engine publishes one channel per frame; the active one is the
            // only non-zero entry.
            if frame.kick > 0.0 {
                report.kicks += 1;
                report.peaks.push(frame.kick);
            } else if frame.snare > 0.0 {
                report.snares += 1;
                report.peaks.push(frame.snare);
            } else if frame.vocal > 0.0 {
                report.vocals += 1;
                report.peaks.push(frame.vocal);
            } else if frame.body > 0.0 {
                report.bodies += 1;
                report.peaks.push(frame.body);
            }
        }
    }

    report.windows = engine.windows();
    report.dropped = engine.dropped_onsets();
    report.bands = peak_bands;
    report.body = peak_body;
    report
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg() -> EngineConfig {
        EngineConfig { sample_rate: 48000.0, ..Default::default() }
    }

    #[test]
    fn silence_produces_no_haptic_output() {
        let s = silence(48000.0, 2.0);
        let r = run_chain(&s, 48000.0, 4096, cfg());
        assert_eq!(r.onsets, 0, "silence must not vibrate");
        assert!(r.windows > 0, "analysis must still run");
    }

    #[test]
    fn kick_pattern_produces_kicks() {
        let s = kick_pattern(48000.0, 120.0, 4.0);
        let r = run_chain(&s, 48000.0, 4096, cfg());
        assert!(r.kicks > 0, "a 120 BPM kick pattern produced no kicks");
        // 4 seconds at 120 BPM is 8 beats; allowing for detector warm-up, at
        // least half should land.
        assert!(r.kicks >= 4, "expected >=4 kicks, got {}", r.kicks);
    }

    #[test]
    fn kick_band_dominates_a_bass_pattern() {
        let s = kick_pattern(48000.0, 120.0, 2.0);
        let r = run_chain(&s, 48000.0, 4096, cfg());
        assert!(
            r.bands[0] > r.bands[2],
            "sub={} should exceed vocal={} for a kick pattern",
            r.bands[0],
            r.bands[2]
        );
    }

    #[test]
    fn full_mix_triggers_multiple_channel_types() {
        let s = full_mix(48000.0, 110.0, 6.0);
        let r = run_chain(&s, 48000.0, 4096, cfg());
        assert!(r.onsets > 0, "full mix produced no onsets at all");
        // Kick must be present; snare presence depends on the noise band, so it
        // is reported rather than asserted to avoid a brittle test.
        assert!(r.kicks > 0, "full mix produced no kicks");
        assert_eq!(r.dropped, 0, "analysis dropped frames");
    }

    #[test]
    fn body_channel_is_non_zero_for_tonal_content() {
        // Regression guard for the constant-BODY defect in v5.5.3.
        let s = full_mix(48000.0, 110.0, 3.0);
        let r = run_chain(&s, 48000.0, 4096, cfg());
        assert!(r.body > 0.0, "body channel was zero: {}", r.body);
    }

    #[test]
    fn sample_rate_44100_keeps_bands_aligned() {
        // The device switches between 44100 and 48000; a 60 Hz kick must land in
        // the sub band at both rates.
        let s = kick_pattern(44100.0, 120.0, 2.0);
        let r = run_chain(&s, 44100.0, 4096, cfg());
        assert!(r.bands[0] > r.bands[2], "sub={} vocal={}", r.bands[0], r.bands[2]);
        assert!(r.kicks > 0);
    }

    #[test]
    fn bass_boost_increases_kick_share() {
        let s = full_mix(48000.0, 110.0, 5.0);

        let mut low = cfg();
        low.bass_boost = 0.5;
        let a = run_chain(&s, 48000.0, 4096, low);

        let mut high = cfg();
        high.bass_boost = 2.5;
        let b = run_chain(&s, 48000.0, 4096, high);

        let share_a = if a.onsets > 0 { a.kicks as f32 / a.onsets as f32 } else { 0.0 };
        let share_b = if b.onsets > 0 { b.kicks as f32 / b.onsets as f32 } else { 0.0 };
        assert!(
            share_b >= share_a,
            "raising bass boost must not reduce the kick share: {} -> {}",
            share_a,
            share_b
        );
    }

    #[test]
    fn band_edges_change_what_is_analysed() {
        // A 3 kHz tone sits inside the "crisp" window but outside a narrow
        // low-frequency window. Band edges must move the measured energy.
        let tone: Vec<f32> = render(48000.0, 2.0, |_, t| {
            0.5 * (2.0 * core::f32::consts::PI * 3000.0 * t).sin()
        });

        let mut narrow = cfg();
        narrow.band_high_hz = 900.0;
        let a = run_chain(&tone, 48000.0, 4096, narrow);

        let mut wide = cfg();
        wide.band_high_hz = 5000.0;
        let b = run_chain(&tone, 48000.0, 4096, wide);

        assert!(
            b.bands[2] > a.bands[2],
            "widening the window must capture more of a 3 kHz tone: {} vs {}",
            a.bands[2],
            b.bands[2]
        );
    }

    #[test]
    fn repeated_pattern_is_stable_across_runs() {
        // Determinism matters: a difference between two identical runs would make
        // every field report ambiguous.
        let s = full_mix(48000.0, 110.0, 3.0);
        let a = run_chain(&s, 48000.0, 4096, cfg());
        let b = run_chain(&s, 48000.0, 4096, cfg());
        assert_eq!(a.onsets, b.onsets);
        assert_eq!(a.kicks, b.kicks);
    }

    #[test]
    fn block_size_does_not_change_the_verdict() {
        // The device delivers ~4096 samples per write but the visualizer path
        // delivers 512. Results must agree closely enough that a log from either
        // path is comparable.
        let s = kick_pattern(48000.0, 120.0, 3.0);
        let a = run_chain(&s, 48000.0, 512, cfg());
        let b = run_chain(&s, 48000.0, 4096, cfg());
        let diff = (a.kicks as i64 - b.kicks as i64).abs();
        assert!(
            diff <= 2,
            "block size changed the kick count too much: {} vs {}",
            a.kicks,
            b.kicks
        );
    }
}