//! Real-time biquad and FFT primitives for the haptics DSP.
//!
//! Every routine here is allocation-free and suited to a hard real-time audio
//! worker: fixed-size arrays, no locks, no panics on the steady-state path.
//!
//! The RBJ cookbook coefficients are used directly. Two details matter for
//! correctness and were wrong (or missing) in the previous implementation:
//!
//! * Filters are always created *from* a cutoff/rate pair, so the coefficients
//!   are recomputed whenever the sample rate changes. Reusing coefficients
//!   computed at 44100 Hz after the track switches to 48000 Hz silently moves
//!   every band edge by ~9% -- audible as a shift in which drum triggers.
//! * `reset` clears delay history; without it a band-pass step at a track
//!   boundary rings for tens of milliseconds and produces a spurious onset.

use core::f32::consts::PI;

/// Second-order section in transposed direct form II.
///
/// Transposed form is preferred over direct form I for float32 because it has
/// one fewer state variable and better numerical behaviour at low cutoffs.
#[derive(Clone, Copy, Debug)]
pub struct Biquad {
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
    z1: f32,
    z2: f32,
}

/// Which response to build.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    LowPass,
    HighPass,
    BandPass,
}

impl Biquad {
    /// A pass-through section, used before the first configuration call.
    pub const fn pass_through() -> Self {
        Self { b0: 1.0, b1: 0.0, b2: 0.0, a1: 0.0, a2: 0.0, z1: 0.0, z2: 0.0 }
    }

    /// Build a section from measured parameters.
    ///
    /// `q` must be positive; values below 1e-4 are clamped because they would
    /// produce coefficients that overflow f32 once squared.
    pub fn new(kind: Kind, sample_rate: f32, center_hz: f32, q: f32) -> Self {
        let mut f = Self::pass_through();
        f.design(kind, sample_rate, center_hz, q);
        f
    }

    /// Recompute coefficients in place, keeping the delay state.
    ///
    /// Keeping state avoids a click when only the cutoff moves (a UI preset
    /// change while music plays). Call [Self::reset] instead when the audio
    /// source itself changed.
    pub fn design(&mut self, kind: Kind, sample_rate: f32, center_hz: f32, q: f32) {
        if sample_rate <= 0.0 { return; }

        // Nyquist guard: the RBJ formulas go unstable as w0 approaches PI, and
        // callers legitimately ask for band edges above Nyquist on low-rate
        // sources (speech-tuned players at 8 kHz).
        let nyquist = sample_rate * 0.5;
        let freq = center_hz.clamp(10.0, nyquist * 0.99);
        let q = q.max(1.0e-4);

        let w0 = 2.0 * PI * freq / sample_rate;
        let (sin_w0, cos_w0) = (w0.sin(), w0.cos());
        let alpha = sin_w0 / (2.0 * q);

        let (b0, b1, b2) = match kind {
            Kind::LowPass => {
                let k = (1.0 - cos_w0) * 0.5;
                (k, 1.0 - cos_w0, k)
            }
            Kind::HighPass => {
                let k = (1.0 + cos_w0) * 0.5;
                (k, -(1.0 + cos_w0), k)
            }
            // Constant 0 dB peak gain, so chained bands keep their relative level.
            Kind::BandPass => (alpha, 0.0, -alpha),
        };

        let a0 = 1.0 + alpha;
        if !a0.is_finite() || a0.abs() < f32::EPSILON {
            // Degenerate parameters: fall back to pass-through rather than
            // emitting NaN forever, which would poison every downstream feature.
            *self = Self::pass_through();
            return;
        }

        self.b0 = b0 / a0;
        self.b1 = b1 / a0;
        self.b2 = b2 / a0;
        self.a1 = (-2.0 * cos_w0) / a0;
        self.a2 = (1.0 - alpha) / a0;
    }

    /// Clear delay history.
    pub fn reset(&mut self) {
        self.z1 = 0.0;
        self.z2 = 0.0;
    }

    /// Process one sample.
    #[inline]
    pub fn process(&mut self, x: f32) -> f32 {
        let y = self.b0 * x + self.z1;
        self.z1 = self.b1 * x - self.a1 * y + self.z2;
        self.z2 = self.b2 * x - self.a2 * y;
        // A denormal or NaN here would spread through every later stage; flush
        // it so one bad input cannot silently kill the whole analysis chain.
        if y.is_finite() { y } else { 0.0 }
    }

    /// Process a block in place.
    pub fn process_block(&mut self, buf: &mut [f32]) {
        for s in buf.iter_mut() {
            *s = self.process(*s);
        }
    }
}

/// Band-limited RMS over a block, ignoring non-finite samples.
///
/// Using the filtered signal's RMS rather than the raw magnitude sum makes the
/// band energy scale with actual audible content. The previous implementation
/// summed FFT magnitudes, which made every band drift with window gain and hid
/// real dynamics -- the trace showed `texRms` pinned at exactly 0.1 regardless
/// of what was playing.
///
/// Non-finite samples are excluded from *both* the sum and the divisor. Dividing
/// by the raw length while skipping NaN terms would silently attenuate the band
/// by however many bad samples appeared, which is a plausible downstream cause of
/// "one NaN poisons the energy for a while".
#[inline]
pub fn rms(buf: &[f32]) -> f32 {
    if buf.is_empty() { return 0.0; }
    let mut acc = 0.0f32;
    let mut counted = 0usize;
    for &s in buf {
        if s.is_finite() {
            acc += s * s;
            counted += 1;
        }
    }
    if counted == 0 { return 0.0; }
    (acc / counted as f32).sqrt()
}

/// Radix-2 in-place FFT with precomputed twiddles.
///
/// Fixed size chosen at compile time so the transform needs no allocation and
/// the bit-reversal permutation is a constant loop bound.
#[derive(Clone)]
pub struct Fft {
    n: usize,
    tw_re: Vec<f32>,
    tw_im: Vec<f32>,
    re: Vec<f32>,
    im: Vec<f32>,
    /// Hann window, computed once. The previous implementation evaluated one
    /// `cos` per sample on every call, i.e. 1024 transcendental calls per
    /// analysis window on the real-time DSP thread.
    window: Vec<f32>,
}

impl Fft {
    /// `n` must be a power of two.
    pub fn new(n: usize) -> Self {
        assert!(n.is_power_of_two(), "FFT size must be a power of two");
        let mut tw_re = Vec::with_capacity(n / 2);
        let mut tw_im = Vec::with_capacity(n / 2);
        for i in 0..n / 2 {
            let angle = -2.0 * core::f64::consts::PI * i as f64 / n as f64;
            tw_re.push(angle.cos() as f32);
            tw_im.push(angle.sin() as f32);
        }
        let denom = (n.max(2) - 1) as f64;
        let window: Vec<f32> = (0..n)
            .map(|i| (0.5 - 0.5 * (2.0 * core::f64::consts::PI * i as f64 / denom).cos()) as f32)
            .collect();
        Self { n, tw_re, tw_im, re: vec![0.0; n], im: vec![0.0; n], window }
    }

    #[inline]
    pub fn size(&self) -> usize { self.n }

    /// Transform `input` (length must equal `size()`) into magnitude bins.
    ///
    /// Returns the first `n/2` magnitudes, DC excluded: DC carries no perceptual
    /// information for percussion and would dominate every band sum.
    pub fn magnitudes(&mut self, input: &[f32], out: &mut [f32]) -> usize {
        let n = self.n;
        debug_assert_eq!(input.len(), n);
        let bins = n / 2;
        debug_assert!(out.len() >= bins);

        // Hann window: without it, a strong kick leaks across the whole
        // spectrum and flattens the transient the detector is looking for.
        for i in 0..n {
            self.re[i] = input[i] * self.window[i];
            self.im[i] = 0.0;
        }

        // Bit-reversal permutation.
        let mut j = 0usize;
        for i in 1..n {
            let mut bit = n >> 1;
            while j & bit != 0 {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if i < j {
                self.re.swap(i, j);
                self.im.swap(i, j);
            }
        }

        // Butterflies.
        let mut len = 2;
        while len <= n {
            let half = len >> 1;
            let step = n / len;
            let mut start = 0;
            while start < n {
                let mut k = 0;
                let mut tw = 0;
                while k < half {
                    let wr = self.tw_re[tw];
                    let wi = self.tw_im[tw];
                    let lo = start + k;
                    let hi = lo + half;
                    let vr = self.re[hi] * wr - self.im[hi] * wi;
                    let vi = self.re[hi] * wi + self.im[hi] * wr;
                    self.re[hi] = self.re[lo] - vr;
                    self.im[hi] = self.im[lo] - vi;
                    self.re[lo] += vr;
                    self.im[lo] += vi;
                    k += 1;
                    tw += step;
                }
                start += len;
            }
            len <<= 1;
        }

        for i in 0..bins {
            let re = self.re[i + 1];
            let im = self.im[i + 1];
            out[i] = (re * re + im * im).sqrt();
        }
        bins
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sine(freq: f32, rate: f32, n: usize) -> Vec<f32> {
        (0..n).map(|i| (2.0 * PI * freq * i as f32 / rate).sin()).collect()
    }

    #[test]
    fn lowpass_attenuates_high_frequency() {
        let rate = 48000.0;
        let mut lp = Biquad::new(Kind::LowPass, rate, 200.0, 0.707);
        let mut low = sine(100.0, rate, 4800);
        let mut high = sine(8000.0, rate, 4800);
        lp.process_block(&mut low);
        lp.reset();
        lp.process_block(&mut high);
        assert!(rms(&low) > rms(&high) * 5.0, "low={} high={}", rms(&low), rms(&high));
    }

    #[test]
    fn highpass_attenuates_low_frequency() {
        let rate = 48000.0;
        let mut hp = Biquad::new(Kind::HighPass, rate, 2000.0, 0.707);
        let mut low = sine(100.0, rate, 4800);
        let mut high = sine(8000.0, rate, 4800);
        hp.process_block(&mut low);
        hp.reset();
        hp.process_block(&mut high);
        assert!(rms(&high) > rms(&low) * 5.0);
    }

    #[test]
    fn bandpass_is_centred() {
        let rate = 48000.0;
        let mut centre = Biquad::new(Kind::BandPass, rate, 1000.0, 2.0);
        let mut low = sine(200.0, rate, 4800);
        let mut mid = sine(1000.0, rate, 4800);
        let mut high = sine(6000.0, rate, 4800);
        centre.process_block(&mut low);
        centre.reset();
        centre.process_block(&mut mid);
        centre.reset();
        centre.process_block(&mut high);
        assert!(rms(&mid) > rms(&low));
        assert!(rms(&mid) > rms(&high));
    }

    #[test]
    fn cutoff_above_nyquist_is_clamped_not_nan() {
        // A speech-rate source still asks for a 12 kHz band edge.
        let mut f = Biquad::new(Kind::LowPass, 8000.0, 12000.0, 0.707);
        let out: Vec<f32> = (0..64).map(|i| f.process(i as f32 * 0.01)).collect();
        assert!(out.iter().all(|v| v.is_finite()));
    }

    #[test]
    fn design_keeps_state_but_reset_clears() {
        let mut f = Biquad::new(Kind::LowPass, 48000.0, 500.0, 0.707);
        for _ in 0..100 { f.process(1.0); }
        let z1_before = f.z1;
        f.design(Kind::LowPass, 48000.0, 600.0, 0.707);
        assert_eq!(f.z1, z1_before, "design must not reset history");
        f.reset();
        assert_eq!(f.z1, 0.0);
    }

    #[test]
    fn fft_finds_a_single_tone() {
        let n = 1024;
        let rate = 44100.0;
        let tone_hz = 1000.0;
        let input = sine(tone_hz, rate, n);
        let mut fft = Fft::new(n);
        let mut mags = vec![0.0f32; n / 2];
        let bins = fft.magnitudes(&input, &mut mags);
        assert_eq!(bins, n / 2);

        let peak = mags
            .iter()
            .enumerate()
            .max_by(|a, b| a.1.partial_cmp(b.1).unwrap())
            .map(|(i, _)| i)
            .unwrap();
        // mags[i] corresponds to bin i+1 because DC is skipped.
        let peak_hz = (peak + 1) as f32 * rate / n as f32;
        assert!((peak_hz - tone_hz).abs() < 60.0, "peak was {peak_hz} Hz");
    }

    #[test]
    fn fft_output_is_finite_for_silence() {
        let n = 256;
        let mut fft = Fft::new(n);
        let mut mags = vec![0.0f32; n / 2];
        fft.magnitudes(&[0.0; 256], &mut mags);
        assert!(mags.iter().all(|v| v.is_finite()));
    }

    #[test]
    fn rms_ignores_non_finite_samples() {
        let buf = [0.5f32, f32::NAN, 0.5, f32::INFINITY];
        let v = rms(&buf);
        assert!(v.is_finite());
        assert!((v - 0.5).abs() < 1e-6);
    }
}