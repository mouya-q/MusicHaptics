# Algorithm and Platform References

This project keeps its real-time audio path in a small, allocation-free Rust DSP
core. The following public sources are used as design references; they are not
vendored as runtime dependencies.

## Onset detection

- [aubio](https://github.com/aubio/aubio) documents multiple onset novelty
  functions, including energy, high-frequency content, complex-domain,
  phase, spectral difference, KL divergence, modified KL divergence, and
  spectral flux. MusicHapticsX uses a deliberately small hybrid: per-band RMS
  rises plus positive per-band FFT spectral flux, followed by adaptive gating,
  cross-band winner selection, and a refractory mask.
- The design does not claim that spectral flux alone identifies musical beats.
  It is a transient cue. Tempo, event classification, and haptic scheduling
  remain separate decisions so a loud sustained note does not automatically
  become a kick.

## Android vibration capabilities

- [VibrationEffect](https://developer.android.com/reference/android/os/VibrationEffect)
  defines waveform timing/amplitude arrays, including zero amplitude for an
  intentional off interval and the default-amplitude sentinel.
- [Android haptics implementation guidance](https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects)
  explains amplitude-control limitations and Composition primitives. The
  application checks supported primitives before selecting that fallback.
- API 36 introduces newer envelope construction APIs, but this source currently
  targets compile SDK 35 and retains the compatible waveform/Composition route.
  Advanced frequency control is not assumed without framework and vibrator-HAL
  support on the actual device.

## Device calibration

The profile table is a conservative initial policy, not a database of laboratory
measurements. Resonance, Q, rise/fall, amplitude ceiling, and thermal constants
must be validated on each handset/ROM/actuator path. For repeatable calibration,
record the command timestamps and measure chassis acceleration with an external
accelerometer; do not infer physical frequency response or coil temperature from
model names or software estimates.
