# MusicHapticsX Premium Haptic Pipeline

## Goal

The renderer is optimized for *perceived separation*, not maximum vibration. A strong hit that masks the next hit is worse than a slightly quieter hit that lands exactly on the musical transient.

## 1. Audio front-end

- PCM16 / float PCM are downmixed to mono.
- PCM16 ByteBuffer input is explicitly little-endian.
- 256-frame processing provides roughly 5.3 ms granularity at 48 kHz.
- True RMS and crest factor are computed from the raw PCM block.
- Five semantic bands plus a 512-point Hann-window FFT feed novelty and transient features.

## 2. Transient detection

The onset score combines local energy change, per-band spectral flux, a rolling local floor, and crest factor. The profile provides device-specific sensitivity and refractory scaling. Style provides the user-facing onset threshold.

The detector intentionally separates:

- KICK: low-frequency attack and sub rise
- SNARE: low-mid + presence transient
- VOCAL: sparse accent, not a sustained vowel
- BODY: rare low-frequency reinforcement

## 3. Event sculpting

`HapticImpactPolicy` controls event eligibility, timing limits and device tuning. It is intentionally compact.

`HapticSynthesizer.sculptImpact()` owns the actual impact curve. It uses the actuator's measured rise/fall and Q to generate an attack / body / tail sequence. The existing physical simulation remains a telemetry/modeling path; it is not blindly sampled at 60 Hz to drive an LRA.

## 4. Output selection

1. Strike-only direct driver: fire exactly one native strike if the write succeeds.
2. Continuous direct driver: render a smoothed ~100 Hz control envelope, not a 200 Hz retrigger loop.
3. Android Vibrator with calibrated primitives: use a small primitive composition for impact identity.
4. Android Vibrator waveform: use the sculpted multi-segment curve when a custom amplitude trajectory is needed.

A successful direct route is authoritative for that event; Java output is only a fallback when direct delivery fails.

## 5. Real-time latency

Audio ingress is bounded. When processing falls behind, stale queued blocks are dropped so the renderer tracks the current audio position rather than replaying an old queue. This is especially important for beat-synchronous haptics, where an extra 40–80 ms of latency is perceptually obvious.

## 6. Device tuning

`DeviceProfile` remains the source of actuator characteristics and DSP multipliers. `DeviceTuningRegistry` adds product-family timing, gain, minimum intensity and refractory adjustments. Both are fed into the native detector and the Android output policy.

The target is not one universal “stronger” setting. A fast LRA should receive shorter, sharper events; a slower actuator should receive slightly wider envelopes and a longer recovery window.
