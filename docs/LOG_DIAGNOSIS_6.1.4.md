# MusicHapticsX 6.1.4 — Xiaomi 10 Cross-Player Log Diagnosis

This note analyses the 2026-11-01 Xiaomi 10 traces from Kugou Music Lite and
Bilibili supplied by the project user. It distinguishes confirmed log facts from
items that still require a fresh 6.1.4 trace or physical measurement.

## Confirmed from the supplied traces

### 1. Neither trace proves that hardware direct drive was ready

Both applications report `isDirectDriveAvailable=false` and `UDP transport
ready=false`. Bilibili additionally reports `su` failing with `error=13,
Permission denied`. Its output is therefore the Android Vibrator waveform fallback,
not a confirmed AW8697 sysfs/UDP strike path. The early Kugou `path=NativeDD`
entries conflict with the preceding unavailable transport state; they should not be
treated as proof of direct-drive output.

Version 6.1.4 passes the Native scheduler's per-event route decision into Kotlin
and revokes the Java-pipe flag after a pipe write failure. New startup diagnostics
separate Native DSP loading, scheduler state, verified hardware transport, and
Android Vibrator capabilities.

### 2. The two input formats and write chunk sizes differ materially

Kugou reports `enc=float` and a raw write result of `376832`, while Bilibili reports
`enc=pcm16` and `3840`. The older log did not include the exact `AudioTrack.write`
overload, so the Kugou result cannot safely be interpreted as bytes or samples.
For `FloatArray` and `ShortArray` overloads Android counts samples; byte-oriented
overloads count bytes. With stereo FloatArray, 376832 samples would represent
188416 frames (about 3.93 seconds at 48 kHz), whereas 3840 bytes of stereo PCM16
represent 960 frames (20 ms at 48 kHz). The first figure is conditional on the
actual overload being FloatArray; it is not inferred from `enc=float` alone.

6.1.4 logs the actual overload, count unit, accepted PCM frames, estimated duration,
sample rate, channels, and encoding so the next trace can settle this unambiguously.
It also converts FloatArray sample offsets/counts into complete interleaved frames
before downmixing.

### 3. Sample-rate reconfiguration was not tied to the actual PCM block

The Kugou trace shows global reconfiguration to 96 kHz while a later write reports
48 kHz. 6.1.4 attaches the AudioTrack sample rate to each queued PCM block and
reconfigures the analyser when that block reaches the DSP worker. Constructor hooks
no longer change the analyser rate before the track supplies audio. The DSP still
uses one serial analysis engine, so simultaneous interleaved tracks at different
sample rates would need per-track engines or resampling to a common clock; the
current change does not claim to solve that separate multi-track case.

### 4. Previous zero-onset dashboard fields were not reliable

The Rust telemetry layout and the legacy Kotlin 32-slot layout used different
indices. That translation is now explicit. In addition, an AudioTrack write can
contain multiple 1024-sample analysis windows, and an individual write can also
be shorter than one window. 6.1.4 aggregates onset peaks across all windows in
one write, retains the latest valid band levels between sub-window writes, and
clears transient flags for each new write. This avoids reporting zero merely
because a later window was quiet or the current write was shorter than 1024 frames.

### 5. The logged style parameters are not the same

Kugou's trace changes `amp` from about 1.97 to 0.80 and later back near 1.99, and
changes `bass` from about 1.60 to 2.48 and later to about 1.64. Bilibili stays near
`amp=1.94 bass=1.62`. The traces therefore do not represent a controlled same-settings
comparison, even though both use the `immersive` preset. Compare the exported
`[STYLE]` values at the moment of each BEAT, not only the preset name.

Both logs report no CLICK/TICK/HEAVY primitives. The next 6.1.4 trace also logs
`amplitudeControl` and `forceDefault`. If amplitude control is unavailable or
forced off, Android's `DEFAULT_AMPLITUDE` is controlled by the vibrator HAL; the
app cannot guarantee fine-grained amplitude differences through the framework
waveform path. In that case duration, pulse shape, and gaps may be more reliable
control dimensions than requested amplitude.

## What is not yet solved or proven

- The onset queue still carries event intensity rather than a source sample timestamp.
  A very large PCM write can be analysed much faster than real playback time, so
  multiple detected events may be delivered at scheduler cadence rather than at
  their exact source-audio offsets. A sample-clock scheduler and playback-position
  anchor are a separate follow-up; this build does not claim sample-accurate sync.
- The AW8697 physical output path has not been confirmed from these logs. The node
  names alone do not prove that a privileged file descriptor was opened or that a
  strike reached the actuator.
- LRA response, acceleration, and perceived strength require measurement on the
  target Xiaomi 10. The 190 Hz / Q16 profile remains a tuning baseline, not a live
  sensor measurement.
- The current environment has no Android SDK/NDK or Cargo toolchain; this source
  archive is not a claim that an APK has been successfully built.

## Recommended next log

After installing an APK built from this source, export one short controlled test
from each player with the same immersive settings. Include the new AudioTrack write
line (overload, unit, accepted frames, estimated duration), the startup capability
line (`amplitudeControl`, `forceDefault`, and hardware output), route changes,
and `[BEAT]` lines. Avoid changing level/bass settings during the comparison.
