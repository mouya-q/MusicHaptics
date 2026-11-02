# Haptic Output Policy (6.2.2)

## Processing contract

1. Decode the source in its declared PCM encoding; reject unknown formats.
2. Downmix to mono, replace non-finite float samples with zero, and clamp to [-1, 1].
3. Analyse each fresh 1024-sample block once. At 48 kHz this is a ~21.3 ms hop.
4. Combine per-band positive RMS change with positive FFT spectral flux.
5. Apply event/style/device cooldown before scheduling an onset.
6. Sanitise all JNI-facing floating-point parameters before they are published to the native real-time scheduler, and sanitise Rust engine configuration before filter design; NaN/Inf never become actuator or DSP settings. Keep LRA Q in the mechanical quality-factor domain (5..30), consistent with the Kotlin actuator profile.
7. Limit each strike envelope to finish at least 8 ms before the next eligible onset;
   Android `Vibrator` waveform requests replace an earlier effect, so overlapping
   envelopes are not treated as additive layers. Native direct-drive durations are
   rounded down to the scheduler's 5 ms time grid before enforcing the same gap.
8. Shape the onset envelope, apply the user and style gain once, then apply the device amplitude ceiling.
9. Preserve zero-amplitude segments as intentional rests on waveform-capable paths.

## Output bounds

| Boundary | Limit |
|---|---:|
| Android waveform segments | 32 |
| Single waveform segment | 250 ms |
| Total waveform | 1,000 ms |
| One-shot request | 500 ms |
| IPC actuator request interval | 5 ms |
| Native direct-drive pulse | 1–80 ms |
| StrikeOnly scheduler pulse | 5 ms |
| StrikeOnly shaped strike | 5–60 ms, quantised to 5 ms scheduler ticks and capped below cooldown |
| StrikeOnly texture cadence | 20 ms (5 ms active pulse) |

These are software safety bounds, not a statement of the motor's physical safe
operating area. Do not infer coil temperature from the software thermal estimator.

## Capability degradation

- Amplitude-controlled vibrator: use the five-step amplitude envelope, capped by the selected model policy. Do not infer full-drive mode from a device marketing name or codename; it is an explicit compatibility override.
- No amplitude control, Android Composition available: map amplitude tiers to
  supported primitives and carry silence into primitive delay. Per-primitive
  capability is checked before composition because one unsupported primitive can
  invalidate the whole composition.
- No amplitude control and no Composition: use a default-amplitude waveform with
  the same zero-amplitude rest segments. The framework does not expose a
  per-segment amplitude ceiling in this mode; the actual level is owned by the
  ROM/vibrator HAL, and the UI must not imply that a software cap is guaranteed.
- Vendor/root direct-drive: clamp duration/amplitude at the native transport edge;
  use profile cooldown and max-amplitude ceiling.
- Unknown actuator: use generic tuning and do not assume an exact resonance.

## Hardware validation checklist

For each phone/ROM/driver combination, record: model and build fingerprint, Android
API level, vibrator ID and capabilities, amplitude-control support, supported
primitives, selected output path, audio sample rate/format, ingress accepted and
dropped frames, onset timestamps, scheduled pulse timestamps, measured actuator
acceleration, and chassis temperature. Compare electrical command timing with an
accelerometer trace; never tune rise/fall or Q from a model name alone.
