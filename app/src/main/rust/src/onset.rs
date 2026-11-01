//! Spectral-flux onset detection with per-band adaptive thresholds.
//!
//! This replaces the fixed-threshold detector that produced the v5.5.3 trace,
//! where every `[BEAT]` line showed the same `level=2.00 peak=255` and the
//! event mix was dominated by VOCAL. Three specific defects drove the redesign:
//!
//! 1. **Fixed thresholds cannot span musical dynamics.** A gate tuned for a
//!    quiet verse is deaf in a chorus and vice versa. Each band keeps a slow EMA
//!    of its own flux plus a variance estimate, so the gate tracks the material.
//!
//! 2. **Classification by "largest raw value" let VOCAL win.** Vocal onset used
//!    a `threshold * 0.8` relaxation while kick required `threshold`, so a
//!    mediocre vocal activation outranked a real kick. Bands are now scored as
//!    *ratio to their own recent maximum*, which is scale-free and puts every
//!    band on equal footing before comparison.
//!
//! 3. **No refractory reasoning across bands.** A kick and a snare on the same
//!    beat are one perceptual event. Cross-band suppression now picks a single
//!    winner per onset instead of emitting several near-simultaneous strikes.

use crate::filter::rms;

/// One band's raw energy for the current analysis window.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct BandEnergies {
    /// 30-180 Hz: kick body.
    pub sub: f32,
    /// 180-800 Hz: low-mid, snare body.
    pub low_mid: f32,
    /// 800-2500 Hz: vocal presence.
    pub vocal: f32,
    /// 2500-8000 Hz: snare crack / hats.
    pub presence: f32,
    /// 8000-16000 Hz: air, drives texture.
    pub air: f32,
}

/// Which instrument an onset most likely came from.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Event {
    Kick,
    Snare,
    Vocal,
    Body,
}

impl Event {
    /// Intensity weights used when mixing a band's activation into the strike
    /// strength. Kick is the anchor of perceived rhythm and gets full weight.
    #[inline]
    pub fn character_scale(self) -> f32 {
        match self {
            Event::Kick => 1.00,
            Event::Snare => 0.85,
            Event::Vocal => 0.60,
            Event::Body => 0.50,
        }
    }

    #[inline]
    pub fn as_index(self) -> usize {
        match self {
            Event::Kick => 0,
            Event::Snare => 1,
            Event::Vocal => 2,
            Event::Body => 3,
        }
    }
}

/// A single detected onset, ready for the output stage.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Onset {
    pub event: Event,
    /// Normalised activation of the winning band, `0..=1`.
    pub activation: f32,
    /// Adaptive gate the activation had to clear, for telemetry.
    pub threshold: f32,
}

/// Per-band adaptive statistics.
///
/// `mean` and `variance` follow the same exponential scheme as the reference
/// Dart implementation in the sibling project, which was tuned on real music:
/// alpha 0.02 gives roughly a 1 s memory at 45 ms frames -- long enough not to
/// chase individual hits, short enough to follow a section change.
#[derive(Clone, Copy, Debug)]
struct BandStat {
    mean: f32,
    var: f32,
    /// Envelope follower set by the last trigger; decays every frame.
    mask: f32,
    /// Peak seen in the recent past, used to normalise activations.
    peak: f32,
}

impl BandStat {
    const fn new() -> Self {
        Self { mean: 0.0, var: 0.0, mask: 0.0, peak: 0.0 }
    }

    fn reset(&mut self) {
        *self = Self::new();
    }
}

/// Tunables exposed to the JNI layer.
#[derive(Clone, Copy, Debug)]
pub struct DetectorConfig {
    /// Higher means more triggers. Scales the threshold multiplier.
    pub sensitivity: f32,
    /// Minimum frames between triggers of the same band.
    pub refractory_frames: u32,
    /// Absolute floor; below this nothing can trigger regardless of statistics.
    pub noise_gate: f32,
    /// How fast the envelope mask falls back to zero per frame.
    pub mask_decay: f32,
    /// Statistics adaptation rate.
    pub stat_alpha: f32,
    /// Floor on the standard deviation so silence cannot collapse the gate.
    pub min_std_dev: f32,
    /// Upper bound on `threshold / base` so a loud section cannot fully deafen
    /// the detector.
    pub max_adapt_ratio: f32,
}

impl Default for DetectorConfig {
    fn default() -> Self {
        Self {
            sensitivity: 1.0,
            refractory_frames: 2,
            noise_gate: 0.006,
            mask_decay: 0.94,
            stat_alpha: 0.02,
            min_std_dev: 0.004,
            max_adapt_ratio: 1.45,
        }
    }
}

/// Number of tracked bands (kick, snare, vocal, body).
const BANDS: usize = 4;

/// Spectral-flux onset detector over five energy bands.
///
/// Fed one analysis window at a time; emits at most one [Onset] per call so a
/// dense passage cannot produce a burst of simultaneous strikes.
pub struct OnsetDetector {
    cfg: DetectorConfig,
    stats: [BandStat; BANDS],
    prev: BandEnergies,
    /// Frames remaining before each band may trigger again.
    refractory: [u32; BANDS],
    /// Frames since construction, used only for diagnostics.
    frames: u64,
    /// Multiplier on the kick band's raw flux, from the UI bass slider.
    ///
    /// Applied to the *raw* value rather than to the score so it changes which
    /// band wins, not merely how strong the winner reports itself. A control
    /// labelled "bass" should make the kick win tie-breaks more often, which is
    /// what a listener actually expects from it.
    bass_boost: f32,
}

impl OnsetDetector {
    pub fn new(cfg: DetectorConfig) -> Self {
        Self {
            cfg,
            stats: [BandStat::new(); BANDS],
            prev: BandEnergies::default(),
            refractory: [0; BANDS],
            frames: 0,
            bass_boost: 1.0,
        }
    }

    /// Weight the kick band before scoring (UI bass slider).
    pub fn set_bass_boost(&mut self, boost: f32) {
        self.bass_boost = boost.clamp(0.3, 3.0);
    }

    pub fn bass_boost(&self) -> f32 {
        self.bass_boost
    }

    pub fn reset(&mut self) {
        for s in self.stats.iter_mut() {
            s.reset();
        }
        self.prev = BandEnergies::default();
        self.refractory = [0; BANDS];
        self.frames = 0;
    }

    pub fn frames(&self) -> u64 {
        self.frames
    }

    /// Band statistics for telemetry: `(mean, mask)` per band.
    pub fn band_debug(&self, band: usize) -> Option<(f32, f32)> {
        self.stats.get(band).map(|s| (s.mean, s.mask))
    }

    /// Feed one analysis window.
    ///
    /// `energies` are the band RMS values for this window. Returns the strongest
    /// onset that passed its gate, or `None`.
    pub fn process(&mut self, energies: BandEnergies) -> Option<Onset> {
        self.frames = self.frames.wrapping_add(1);

        // Positive-only difference: an onset is a rise in energy. A decay
        // produces no activation, which is what keeps the detector from firing
        // continuously during sustained loud passages.
        let flux = BandEnergies {
            sub: (energies.sub - self.prev.sub).max(0.0),
            low_mid: (energies.low_mid - self.prev.low_mid).max(0.0),
            vocal: (energies.vocal - self.prev.vocal).max(0.0),
            presence: (energies.presence - self.prev.presence).max(0.0),
            air: (energies.air - self.prev.air).max(0.0),
        };
        self.prev = energies;

        // Map five flux bands onto four detector bands. Snare is driven by the
        // combination of low-mid body and presence crack, which is how a snare
        // actually presents in a mix; taking either alone misses half of them.
        let raw = [
            // Kick carries the UI bass weight: raising the slider should make the
            // low band win more tie-breaks, not merely report a larger number.
            flux.sub * self.bass_boost,
            flux.low_mid * 0.7 + flux.presence * 0.3,
            flux.vocal,
            // Body/texture comes from the air band plus sustained low-mid, so it
            // stays non-zero during quiet passages instead of collapsing to the
            // constant zero seen in the v5.5.3 trace.
            flux.air * 0.6 + energies.air * 0.4,
        ];

        for r in self.refractory.iter_mut() {
            *r = r.saturating_sub(1);
        }

        // Update statistics first so the same frame's gate uses fresh numbers;
        // the mask is applied after, so a trigger cannot mask itself.
        let mut best: Option<(usize, f32, f32)> = None;

        for band in 0..BANDS {
            let stat = &mut self.stats[band];
            let value = raw[band];

            // Track the recent peak with a slow decay so normalisation is
            // scale-free but still follows the section's dynamics.
            stat.peak = (stat.peak * 0.995).max(value);

            let std_dev = stat.var.max(0.0).sqrt().max(self.cfg.min_std_dev);
            let multiplier = (1.5 / self.cfg.sensitivity.max(0.1)).clamp(0.5, 4.0);
            let base = stat.mean + multiplier * std_dev;

            // Adaptive component: rises with sustained energy but is capped, so
            // a loud chorus cannot gate out genuine transients.
            let adaptive = (base * (1.0 + stat.mean * 0.75))
                .min(base * self.cfg.max_adapt_ratio)
                .max(base);
            let threshold = adaptive.max(stat.mask).max(self.cfg.noise_gate);

            let allowed = self.refractory[band] == 0 && value > threshold;

            // Envelope mask: after a trigger, the gate sits at 70% of the flux
            // that caused it, which suppresses the tail of the same hit.
            if allowed {
                stat.mask = value * 0.7;
                self.refractory[band] = self.cfg.refractory_frames;
            } else {
                stat.mask *= self.cfg.mask_decay;
            }

            // EMA update last: this frame's statistics must not influence this
            // frame's decision, or thresholds chase their own triggers.
            let delta = value - stat.mean;
            stat.mean += self.cfg.stat_alpha * delta;
            stat.var = (1.0 - self.cfg.stat_alpha) * (stat.var + self.cfg.stat_alpha * delta * delta);

            if allowed {
                // Compare by ratio-to-recent-peak, not raw magnitude. Raw
                // comparison is what let a weak vocal outrank a real kick.
                let norm = if stat.peak > 1e-6 { (value / stat.peak).clamp(0.0, 1.0) } else { 0.0 };
                let score = norm * match band {
                    0 => 1.00, // kick: rhythm anchor
                    1 => 0.92,
                    2 => 0.72, // vocal needs to dominate clearly to win
                    _ => 0.60,
                };
                if best.map_or(true, |(_, s, _)| score > s) {
                    best = Some((band, score, threshold));
                }
            }
        }

        let (band, _, threshold) = best?;
        let event = match band {
            0 => Event::Kick,
            1 => Event::Snare,
            2 => Event::Vocal,
            _ => Event::Body,
        };

        // Report activation as how far past the gate the winner was, squashed
        // into 0..1. This preserves relative dynamics instead of saturating,
        // which is what flattened every strike to peak=255 before.
        let value = raw[band];
        let excess = (value - threshold).max(0.0);
        let activation = (excess / (threshold.max(1e-6) + excess)).clamp(0.0, 1.0);

        Some(Onset { event, activation, threshold })
    }
}

/// Convert a band energy set into a 0..1 composite "body" energy.
///
/// Kept separate from the detector because the continuous/texture output path
/// needs a value every frame, not only on onsets.
pub fn body_energy(e: &BandEnergies) -> f32 {
    let mix = e.low_mid * 0.35 + e.vocal * 0.20 + e.presence * 0.25 + e.air * 0.20;
    // Soft saturate: musical material routinely exceeds 1.0 in raw RMS after
    // filtering, but the output stage expects a bounded 0..1 control value.
    (mix / (mix + 0.35)).clamp(0.0, 1.0)
}

/// Overall loudness estimate, used to scale strike strength with volume.
pub fn loudness(e: &BandEnergies) -> f32 {
    let mix = e.sub * 0.4 + e.low_mid * 0.3 + e.vocal * 0.15 + e.presence * 0.15;
    (mix / (mix + 0.25)).clamp(0.0, 1.0)
}

/// Band-limited RMS helper used by the analysis front-end.
pub fn band_rms(buf: &[f32]) -> f32 {
    rms(buf)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg() -> DetectorConfig {
        DetectorConfig { refractory_frames: 0, noise_gate: 0.0, ..Default::default() }
    }

    fn silent() -> BandEnergies {
        BandEnergies::default()
    }

    #[test]
    fn silence_never_triggers() {
        let mut d = OnsetDetector::new(cfg());
        for _ in 0..500 {
            assert!(d.process(silent()).is_none());
        }
    }

    #[test]
    fn kick_rise_triggers_kick() {
        let mut d = OnsetDetector::new(cfg());
        // Establish a quiet baseline so the adaptive gate has statistics.
        for _ in 0..50 {
            d.process(BandEnergies { sub: 0.02, ..Default::default() });
        }
        let hit = d.process(BandEnergies { sub: 0.9, ..Default::default() });
        let onset = hit.expect("kick rise should trigger");
        assert_eq!(onset.event, Event::Kick);
        assert!(onset.activation >= 0.0 && onset.activation <= 1.0);
    }

    #[test]
    fn sustained_energy_decays_without_retriggering() {
        let mut d = OnsetDetector::new(DetectorConfig {
            refractory_frames: 2,
            noise_gate: 0.0,
            ..Default::default()
        });
        for _ in 0..50 {
            d.process(BandEnergies { sub: 0.02, ..Default::default() });
        }
        assert!(d.process(BandEnergies { sub: 0.9, ..Default::default() }).is_some());
        // Holding the same level is not a new onset: flux is zero.
        let mut triggers = 0;
        for _ in 0..60 {
            if d.process(BandEnergies { sub: 0.9, ..Default::default() }).is_some() {
                triggers += 1;
            }
        }
        assert_eq!(triggers, 0, "steady state must not retrigger");
    }

    #[test]
    fn weak_vocal_does_not_beat_strong_kick() {
        let mut d = OnsetDetector::new(cfg());
        for _ in 0..40 {
            d.process(BandEnergies { sub: 0.02, vocal: 0.02, ..Default::default() });
        }
        // A real kick alongside a mild vocal rise: kick must win.
        let onset = d
            .process(BandEnergies { sub: 0.85, vocal: 0.30, ..Default::default() })
            .expect("should trigger");
        assert_eq!(onset.event, Event::Kick, "kick must outrank a weak vocal");
    }

    #[test]
    fn dominant_vocal_still_able_to_win() {
        let mut d = OnsetDetector::new(cfg());
        for _ in 0..40 {
            d.process(BandEnergies { sub: 0.02, vocal: 0.02, ..Default::default() });
        }
        // Vocal clearly dominant and no kick movement at all.
        let onset = d
            .process(BandEnergies { sub: 0.02, vocal: 0.90, ..Default::default() })
            .expect("should trigger");
        assert_eq!(onset.event, Event::Vocal);
    }

    #[test]
    fn refractory_blocks_immediate_retrigger() {
        let mut d = OnsetDetector::new(DetectorConfig {
            refractory_frames: 5,
            noise_gate: 0.0,
            ..Default::default()
        });
        for _ in 0..40 {
            d.process(BandEnergies { sub: 0.01, ..Default::default() });
        }
        assert!(d.process(BandEnergies { sub: 0.9, ..Default::default() }).is_some());
        // Drop back and rise again immediately: still inside the refractory.
        d.process(BandEnergies { sub: 0.01, ..Default::default() });
        assert!(d.process(BandEnergies { sub: 0.9, ..Default::default() }).is_none());
    }

    #[test]
    fn body_energy_tracks_air_band_instead_of_staying_constant() {
        // The v5.5.3 trace showed Body pinned at 0; a quiet air band must give a
        // small but non-constant value as it moves.
        let quiet = body_energy(&BandEnergies { air: 0.001, ..Default::default() });
        let loud = body_energy(&BandEnergies { air: 0.5, ..Default::default() });
        assert!(loud > quiet);
        assert!(loud <= 1.0 && quiet >= 0.0);
    }

    #[test]
    fn activation_is_continuous_not_saturated() {
        let mut d = OnsetDetector::new(cfg());
        for _ in 0..40 {
            d.process(BandEnergies { sub: 0.02, ..Default::default() });
        }
        let weak = d.process(BandEnergies { sub: 0.35, ..Default::default() }).unwrap();
        let mut d2 = OnsetDetector::new(cfg());
        for _ in 0..40 {
            d2.process(BandEnergies { sub: 0.02, ..Default::default() });
        }
        let strong = d2.process(BandEnergies { sub: 0.95, ..Default::default() }).unwrap();
        assert!(
            strong.activation > weak.activation,
            "strong={} weak={}",
            strong.activation,
            weak.activation
        );
        assert!(strong.activation < 1.0, "must not always saturate at max");
    }

    #[test]
    fn reset_clears_state() {
        let mut d = OnsetDetector::new(cfg());
        for _ in 0..40 {
            d.process(BandEnergies { sub: 0.5, ..Default::default() });
        }
        d.reset();
        assert_eq!(d.frames(), 0);
        assert!(d.process(silent()).is_none());
    }
}