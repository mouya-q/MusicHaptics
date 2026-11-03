# Changes

Current source version: **6.3.0**

## 6.3.0

### Root direct drive that actually runs; release-build beat callback

See `docs/ROOT_DIRECT_DRIVE.md`.

- R8 keep rule for `onBeatTrigger` had the old 2-argument signature; release
  builds could delete the method the native scheduler calls, disabling every
  haptic event. Rule fixed and enforced by `scripts/repo_check.py`.
- New persistent `RootHapticServer` (via `su -c app_process`) replaces the
  per-packet `nc | dd | od | expr` shell loop. It is started from the module
  process when a hooked app reads its config, not only when the UI is opened.
- Native UDP transport re-probes a lost receiver instead of giving up for good.
- Everything in 6.2.0 to 6.2.2 below is part of this release; see
  `docs/RELEASE_NOTES_6.3.0.md`.
- Android Vibrator path latency default 35 ms -> 25 ms (a field trim of -11 ms).
- UI strings no longer end with full stops (all locales).
- Untested on device: the root server, the release-build keep rule and the Rust
  unit tests (no Android/Rust toolchain was available for this change).

## 6.2.2

- Beat intensity: the continuous-energy term (45% of the mix) was clamped from a
  0..255-scaled value and therefore always 1.0; it is normalised now, so loud
  passages are actually stronger than quiet ones.
- Confirm the `[System Ready]` line shows v6.2.2 and a `[CHAIN]` line appears
  every 2 s; logs without it come from an older build.

## 6.2.0

### Playback-Clock Sync, Onset Timing, and Scheduler Fixes

See `docs/SYNC_AND_TIMING.md`.

- Haptics are scheduled against the AudioTrack playback clock (`getTimestamp`)
  instead of firing when PCM is analysed; onsets carry an audible-time stamp.
- Onset attack localised inside the analysis window (~1 ms instead of ~6 ms).
- New "sync compensation" setting (global and per app, `sync_offset_ms`).
- Fixed 45% of every beat's intensity coming from ~11 s old audio (FIFO head
  read instead of newest frame).
- `FrameRing` push/pop race fixed with compare-exchange.
- JNI critical section no longer wraps DSP work; Hann table precomputed; scheduler
  priority boost and stall resync; `flush()` clears pending haptics.
- DSP ABI version 2 (`set_block_time`, `pop_onset_timed`, `latest_semantic`).
- Anchor is sampled when `write()` is entered, not when it returns: a blocking
  1 s write returns after most of the block was played, which made every onset
  look hundreds of ms late. Late-drop failsafe: 6 consecutive late onsets switch
  the scheduler to fire-immediately for 2 s.
- Onset activation is weighted by the ratio to the band's recent peak; it was
  saturating at 0.93-0.99 on nearly every hit.
- New `[CHAIN]` log line every 2 s (timed fired / late-dropped / untimed, Kotlin
  beat count, paused/enabled flags).
- Untested on device: no Android/Rust toolchain was available for this change.

## 6.1.4

### Cross-Player Telemetry and Audio-Block Consistency

- Keep the latest valid Rust band measurements across sub-window AudioTrack writes;
  960-frame PCM16 writes no longer appear as a permanent zero-energy analyser.
- Aggregate the maximum KICK/SNARE/VOCAL/BODY onset activation across every
  completed analysis window in one PCM write. A later quiet window can no longer
  overwrite a transient that occurred earlier in a large FloatArray write.
- Clear per-write onset telemetry before analysis so old strikes are not repeated
  in the dashboard when a new write contains no transient.
- Add Rust regression tests for short-write level retention and multi-window onset
  aggregation.
- Bump Android source version to 6.1.4 (versionCode 60104).

## 6.1.3

### AudioTrack Ingress, Route Truth, and Telemetry Contract

- Bind the source sample rate to every queued PCM block. AudioTrack constructor
  callbacks now only register track metadata and cannot reconfigure the global
  analyser before that track has written audio. The DSP worker applies the rate
  for the actual block in FIFO order.
- Convert FloatArray write offsets and transfer counts from interleaved samples
  into complete PCM frames before downmixing; keep ShortArray sample units and
  byte-oriented byte units separate.
- Extend AudioTrack write diagnostics with overload type, result unit, accepted
  frames, estimated audio duration, sample rate, channel count, and encoding.
- Translate the Rust 15-field telemetry layout into the dashboard's legacy
  32-field layout, including KICK/SNARE/VOCAL/BODY onset slots 20..23. Dashboard
  onset readings no longer report permanent zero values due to offset mismatch.
- Pass the Native scheduler's per-beat direct-drive route decision to Kotlin.
  Kotlin no longer probes the transport again during that callback, avoiding a
  route race between direct output and waveform fallback.
- When a Java root-pipe write fails, immediately disable the Native Java-pipe
  transport flag so later beats fall back to Android Vibrator instead of being
  silently swallowed by a stale NativeDD selection.
- Keep UDP availability checks tied to a verified receiver rather than a socket
  that was merely created. Add explicit Android vibrator capability fields to
  the startup log so amplitude-control limitations can be compared across ROMs.
- Clarify the Rust FFI ownership contract: sample-rate changes are applied by
  the single DSP producer between process calls.
- Bump the Android source version to 6.1.3 (versionCode 60103).

## 6.1.2

### Native Parameter Hardening

- Sanitize every floating-point value crossing the JNI output/configuration
  boundary before clamping or publishing it to the real-time scheduler. This is
  important because `std::clamp` does not turn NaN into a finite value.
- Add finite fallbacks and range bounds for style gain, transient sensitivity,
  attack/decay/release, LRA tuning, cooldown, amplitude ceiling, DSP band gains,
  sample rate, and base amplitude.
- Keep the final model amplitude ceiling and minimum inter-event interval intact
  even when persisted preferences or an external caller supplies invalid values.
- Apply the same finite-value fallback inside the Rust engine constructor and
  runtime setters, so malformed configuration cannot poison filter coefficients,
  adaptive thresholds, or published energy values.
- Correct the Native LRA quality-factor range to match the Kotlin actuator model
  (Q 5..30, default 15). The old 0.25..2.5 clamp silently reduced real device Q
  values such as Xiaomi 10's configured Q≈16 to 2.5 in the Native response model.
- Stop forcing Xiaomi 10 / 0809 profiles into `DEFAULT_AMPLITUDE` based only on a
  device codename. The normal path now follows the actual runtime amplitude-control
  capability; full-drive remains an explicit user override for compatibility tests.
- Show the selected full-drive override in the hardware card so users can tell when
  per-segment amplitude shaping is intentionally disabled.
- Preserve Composition timing gaps if the framework rejects a primitive at runtime;
  use a bounded timed waveform fallback instead of collapsing the pattern to one buzz.
- Bump the Android source version to 6.1.2 (versionCode 60102).

## 6.1.1

### Signal Integrity, Envelope Timing, and Device-Aware Output

This update concentrates on keeping timing and gain decisions consistent from PCM
capture through DSP to the actuator. It does not claim to have measured motor
frequency response or coil temperature; per-device actuator values remain tuning
baselines until validated on the target hardware.

#### 1. PCM ingress is format-correct and bounded (`AudioIngress.kt`)

- Dispatch PCM 8/16/24-packed/32-bit integer and float formats through Android's
  `AudioFormat` constants rather than confusing the 8-bit encoding with the value 8.
- Decode packed 24-bit little-endian samples with sign extension; sanitise NaN and
  infinity from float streams and clamp normalized samples before enqueueing.
- Bound `ByteBuffer` reads to its limit and reject unknown encodings rather than
  silently interpreting them as PCM16.

#### 2. Stateful DSP no longer replays overlapping samples (`rust/src/engine.rs`)

- Persistent biquad sections now consume a non-overlapping 1024-sample window,
  so each input sample advances filter state exactly once.
- Refractory-frame conversion now matches the real ~21.3 ms analysis hop at 48 kHz.
- FFT magnitudes contribute per-band positive spectral flux alongside RMS rises;
  this helps catch attacks whose spectral shape changes more than their RMS level.
- Added regression coverage for window cadence, style filter edges, sample-rate
  changes, non-finite input, and transient-only spectral flux.

#### 3. Output envelope and pauses are enforced on every path

- Android waveform output is now a five-step attack/peak/body/decay/release shape;
  its segment durations sum exactly to the requested event duration.
- `VibrateProxy` enforces bounded waveform size/duration, preserves explicit zero
  amplitude pauses, and degrades no-amplitude-control devices to primitives or
  default-amplitude waveform segments without collapsing the whole rhythm into
  one long buzz.
- Kotlin envelope synthesis now applies the selected device's amplitude ceiling,
  sanitises non-finite tuning values, and uses the default-amplitude timing profile
  whenever a device policy opts out of linear amplitude control.
- Android 9/10 primitive fallback is represented as a timed waveform so the gaps
  between pulses remain in the pattern.
- Native StrikeOnly direct drive uses a bounded 5 ms scheduler envelope, applies
  the same normalised intensity/soft-gain model as the Kotlin output path, and suppresses
  texture pulses during and immediately after a strike. Its duration ceiling is rounded
  down to whole 5 ms ticks so the quiet gap is real, not just nominal. Both Kotlin and
  Native strike envelopes are capped below the effective refractory interval, leaving a deliberate
  quiet gap instead of having the next event truncate the previous waveform. Continuous direct-drive
  texture is emitted as short 5 ms pulses at a 20 ms cadence, not as an unbroken
  high-rate stream.
- User/preset gain uses a soft knee before the final hardware ceiling, preserving
  more useful level-slider travel than a hard 2.0x clamp.

#### 4. UI and per-device output policy

- The hardware card now explains the selected tuning baseline, minimum interval,
  amplitude ceiling, and whether the Android vibrator reports amplitude control.
  It also states that a model ceiling cannot be guaranteed by Android's
  DEFAULT_AMPLITUDE path; that path is controlled by the ROM/vibrator HAL.
- Frequency/Q/rise and thermal values are explicitly labelled as initial estimates,
  not live actuator measurements.
- Main level controls are grouped together; corrupted/out-of-range stored slider
  values are normalised before entering Compose state.

#### 5. Platform and model limitations

- Android framework waveform and Composition paths are used as capability allows;
  advanced PWLE frequency/amplitude control is not assumed because it requires
  platform and vibrator-HAL support beyond this project's compile SDK baseline.
- The FFT/onset design is informed by the public aubio onset references, but no
  aubio source or binary was vendored into this project.
- Real actuator response, end-to-end latency, thermal behaviour, and direct-driver
  timing still require measurement on each target handset.

## 6.1.0

### Haptic Tuning, UI Wiring, and End-to-End Verification

v6.0.0 proved the Rust core works (`Rust core available=true backend=Rust`,
`enc=float` correctly decoded, `texRms` finally varying). The exported trace then
showed that **the UI settings had almost no effect on what the user feels**, and
that the output stage was flattening every strike to the same strength.

#### 1. The amplitude clamp ate the whole dynamic range (`HapticSynthesizer.kt`)

`peak = shaped * drive * levelScale * 255` combined a drive of up to 2.0 with a
level of up to 2.0, so the product reached 3.2x. Every onset above ~0.31 saturated.
The trace proves it: `raw=87`, `raw=180`, `raw=207`, `raw=246` all produced
`peak=255`, so quiet and loud hits felt identical and the level slider did nothing.

- `DRIVE_REFERENCE` raised 1.0 → 2.5 so the combined gain stays at or below 2.0.
- `PEAK_HEADROOM` (0.86) reserves 14% of the range instead of clamping at 255.
- The level term now uses a square-root perceptual curve rather than linear, so
  the four UI steps spread across a usable span.

#### 2. UI settings never reached the analyser (`NativeBridge.cpp`)

Only `styleAmpScale` crossed into Rust, and it was mapped onto the wrong
parameter: `sensitivity = 0.5 + sharpness`, which has no physical meaning. Bass
boost, texture and the style preset's band window were dropped entirely, so
selecting "bass" or "crisp" changed the Kotlin envelope but the analyser kept
looking at a fixed 55-650 Hz view.

New ABI and wiring:

| UI control | Rust setter | Effect |
|---|---|---|
| Level (low/medium/high/ultra) | `set_user_amplitude` | scales published band energy |
| Style sharpness | `set_sensitivity` (0.6 .. 2.4) | how small a transient can trigger |
| Style attack | `set_refractory_ms` | how often strikes may land |
| Bass boost slider | `set_bass_boost` | kick band weight in onset scoring |
| Continuous gain | `set_texture_gain` | sustained body channel presence |
| Style band preset | `set_band_edges` | analysable 20..200 / 300..12000 Hz window |

`set_band_edges` rebuilds the filters and is logged as
`[DSP] Rust band window -> A..B Hz`, so a preset change is verifiable from the
exported log instead of being invisible.

#### 3. End-to-end chain verification (`src/chain.rs`)

The missing piece through seven rounds of debugging: a way to answer "assume an
input, run the whole chain, what comes out" without shipping an APK.

`chain::run_chain` feeds synthetic audio through band filters → onset detector →
onset frames and reports counts per channel, peak distribution, dynamic range and
band telemetry. Eleven tests cover:

- silence produces no output (no phantom haptics)
- a 120 BPM kick pattern produces ≥4 kicks
- the sub band dominates for bass content at both 44.1 and 48 kHz
- the body channel is non-zero (regression guard for the v5.5.3 constant-zero bug)
- raising bass boost never reduces the kick share
- widening the band window captures more of a 3 kHz tone
- two identical runs agree exactly (determinism)
- 512-sample and 4096-sample blocks reach the same verdict (visualizer vs float path)

#### 4. Preserved: the C++ analyser stays selectable

`setAnalysisBackend` still allows an instant fallback, so a problem in the new
tuning cannot again leave the device silent.

## 6.0.0

### Rust DSP Core

Vibration finally works in 5.5.3. This release replaces the analysis core with a
Rust implementation, and fixes seven quality defects that the working trace made
visible for the first time.

The JNI surface, every Kotlin binding and all direct-drive hardware paths are
unchanged. The Rust core is compiled to a static archive and linked into
`libnative-bridge.so`, so the loader contract inside the hooked process is still a
single `System.loadLibrary("native-bridge")`.

#### Defects this release fixes

These were measured from `MusicHapticsX_log_20261101_111815.txt`:

1. **`texRms` pinned at exactly 0.1.** Band energy was a sum of FFT magnitudes,
   which scales with window gain and therefore carries no audible information.
   Bands are now RMS of band-limited signals, so the value tracks content.

2. **`BODY` always zero.** The body term multiplied out to nothing whenever the
   air band was idle. It is now a real measurement over low-mid, vocal, presence
   and air.

3. **VOCAL outranked KICK.** Classification compared raw magnitudes while vocal
   onset used a `threshold * 0.8` relaxation. Bands are scored by ratio to their
   own recent peak, with kick weighted highest, so a real kick wins.

4. **Every strike saturated at `peak=255`.** Activation was clamped, erasing
   dynamics. It is now reported as how far past the gate the winner was, squashed
   into 0..1, which preserves relative strength.

5. **Fixed 60 ms cooldown regardless of tempo.** Refractory is now expressed in
   milliseconds and converted per window period.

6. **Sample-rate changes left stale filter coefficients.** The trace shows
   44100 -> 48000 mid-session; reusing the old coefficients shifts every band edge
   by ~9%. Coefficients are rebuilt on rate change and the rebuild is logged.

7. **Analysis lagged behind ingest.** `accepted` ran ~4% ahead of `processed`
   because the queue could not hold a full write. The engine now consumes every
   sample it is given and reports both counters.

#### Verification

- 40+ unit tests cover the ring, filters, FFT, detector and engine. They are
  safe-Rust and run on the host in CI before the Android build, so a logic
  regression fails the build instead of shipping as silent silence.
- `repo_check.py` now cross-checks the C ABI header against the Rust exports and
  asserts the telemetry length and ABI version agree on both sides.
- A runtime self-test (`mhx_self_test`) runs at engine creation. A failed static
  link is reported as `Rust core available=false` instead of presenting as a
  device fault.
- The C++ analyser remains selectable at runtime as an instant fallback, so a
  problem in the new core cannot again leave the device silent.

#### Layout

```text
app/src/main/rust/
  Cargo.toml           profile: fat LTO, panic=abort, strip
  build-android.sh     NDK cross-compile + symbol verification
  src/ring.rs          lock-free SPSC frame transport
  src/filter.rs        biquad sections, block RMS, radix-2 FFT
  src/onset.rs         adaptive per-band onset detection
  src/engine.rs        PCM -> bands -> onsets + telemetry
  src/ffi.rs           the C ABI consumed by NativeBridge.cpp
app/src/main/cpp/jni/mhx_dsp_abi.h   the contract, shared by both sides
```

## 5.5.3

### Root Cause: Empty ByteBuffer Snapshot

The v5.5.2 trace finally proved the hooks were installed and firing:

```
[Hook] AudioTrack.write: hooked 7 methods for com.kugou.android.lite
[com.kugou.android.lite] AudioTrack.write result=376832 sr=48000 ch=2
```

PCM was flowing and `write` was observed — yet still 0 `[BEAT]`. The defect was in
how the payload was captured.

#### 1. ByteBuffer was copied *after* write() consumed it (`HookCoordinator.kt`)

`AudioTrack.write` advances the caller's buffer position past the consumed region.
The snapshot was taken in `afterHookedMethod`, where `duplicate().remaining()` is
therefore 0 — every write produced an **empty** byte array. `write result=376832`
was logged from the return value while zero samples were handed to the DSP, so
onset detection saw pure silence and could never fire.

The copy now happens in `beforeHookedMethod` (stored on
`TrackState.pendingByteSnapshot`) and the after-hook uses that snapshot. This is
the reason the pipeline looked healthy in logs but produced no vibration.

#### 2. Ingress queue dropped ~96% of every write (`AudioIngress.kt`)

`result=376832` bytes is 94208 stereo frames. The queue held
`16 slots × 256 frames = 4096` frames, so a single write discarded 96% of its
audio. Spectral-flux onset detection depends on consecutive frames, so this alone
made triggers structurally impossible. Now `64 × 4096 = 262144` frames (~5.4s).

#### 3. PCM encoding was assumed to be 16-bit (`TrackRegistry.kt`, `AudioIngress.kt`)

`TrackState` had no encoding field, so a player using `ENCODING_PCM_FLOAT` had its
float bits reinterpreted as int16 — a noise spectrum where no onset can trigger.
`TrackState.encoding` now records `AudioTrack.getAudioFormat()`, and the ByteBuffer
/ ByteArray decode paths dispatch on PCM8 / PCM16 / FLOAT.

#### 4. `lastAudioWriteAtMs == 0` counted as "stalled" (`HookCoordinator.kt`)

Before the first observed write, every `pause`/`stop`/`release` was treated as a
real stop, producing the burst of pause reports right after an AudioTrack rebuild.
Unknown is not stopped; the guard now returns false in that state.

#### 5. Ingress pipeline made observable (`AudioIngress.kt`, `HapticEngine.kt`)

Added `acceptedFrames()` / `processedFrames()` counters and moved the telemetry
line to a 2-second time throttle (it was every 200 frames, which could emit
nothing in a short session). One `[DSP-TELEM]` line now distinguishes all three
failure modes:

- `accepted=0 processed=0` — PCM never entered the queue (hook problem)
- `accepted>0 processed=0` — DSP worker stalled
- both advancing, onset all zero — detector thresholds

## 5.5.2

### Root Cause: AudioTrack.write Signature Rejection

The v5.5.1 trace confirmed three fixes (phantom UDP, device detection, AudioTrack
pause false positives) all landed — but still 0 `[BEAT]` events. The root cause
was one line of code that silently rejected every modern audio write call.

#### 1. `isSupportedWriteSignature` rejected `long` parameters (`HookCoordinator.kt`)

Android 12+ adds `AudioTrack.write(ByteBuffer, int, int, long)` where the trailing
`long` is a timestamp in nanoseconds. The filter only accepted `Int`-typed trailing
parameters, so every modern write method was silently skipped and PCM never
reached the DSP — 0 onset frames, 0 beats, 0 vibrations. The filter now accepts
`Long` as a trailing type and relaxes the arity check for `ByteBuffer`/`FloatArray`.

#### 2. MediaPlayer pause/stop/release was ungated (`HookCoordinator.kt`)

The v5.5.1 fix added `audioWritesHaveStalled()` to AudioTrack lifecycle hooks but
missed `hookMediaPlayers`, which still called `onPlaybackPaused()` unconditionally
on every `pause`/`stop`/`release`. MediaPlayer.release fires on every track change,
producing the four `[PLAYBACK PAUSED CAUGHT]` lines in the trace. All three
MediaPlayer lifecycle events now require the same stall guard.

#### 3. Hook diagnostics were invisible in dashboard (`LogBroadcaster.kt`, `HookCoordinator.kt`)

Hook code runs inside the target process before any Context has been acquired, so
`LogBroadcaster.sendLog(context, ...)` silently failed — the dashboard never saw
the "AudioTrack.write: hooked N methods" line or any write-trigger log.
`LogBroadcaster` now exposes a `queue(msg)` method that stores messages until a
Context is bound, at which point `flush(context)` drains the queue. All hook
installation and write-trigger logs use this path, so the next trace will show
exactly which hooks were installed and whether they fired.

## 5.5.1

### Root Cause: Phantom Direct Drive + Root-Bound Device Detection

The v5.5.0 trace showed the engine going silent for two independent reasons, plus
one long-standing defect the device-ID question exposed.

#### 1. `isDirectDriveAvailable` lied about UDP (`NativeBridge.cpp`)

`nativeIsDirectDriveAvailable()` returned `true` as soon as `g_use_udp_haptic` was
set — which happens the moment a `DatagramSocket` is created. With `su` refused the
root daemon never starts, so the packets went to an unbound port, yet Kotlin
classified every beat as `NativeDD` and skipped its own vibration path. The device
stayed silent while both layers believed the other was driving the hardware.

`nativeIsUdpHapticReady()` now probes the receiver instead of reading a flag. A
`connect()`ed loopback UDP socket cannot be validated with one send: the first
`sendto` always succeeds and the ICMP port-unreachable only surfaces on the
*following* call. Two clean probes are therefore required before the transport is
trusted, and a single `ECONNREFUSED` condemns it permanently. Both
`nativeIsDirectDriveAvailable` and the scheduler's `use_direct_drive` gate on this
verified state.

#### 2. Pause events were treated as proof of silence (`HookCoordinator.kt`)

Players routinely `pause()`/`flush()`/`release()` an `AudioTrack` while
re-buffering, seeking or swapping tracks. Every such call was reported as a
playback pause, producing the six consecutive `[PLAYBACK PAUSED CAUGHT]` lines in
the trace while music was still audible. `pause`/`stop`/`release` now also require
`audioWritesHaveStalled()`, so a transient lifecycle event on a track that is still
writing PCM is ignored.

#### 3. Device detection no longer depends on root (`RootHardwareProbe.kt`, `MainActivity.kt`)

`probeAndPersist` derived its whole fingerprint from a single `su` invocation, so a
refused `su` left it empty and `profileForFingerprint` returned `DEFAULT` for every
device — which is why the 35 hard-won actuator profiles were effectively dead
code. Identification now runs unconditionally over public build properties
(`Build.MODEL`, `DEVICE`, `PRODUCT`, `BOARD`, `HARDWARE`, `MANUFACTURER`, `BRAND`)
and only *enriches* the result with root-only details when available.

#### Result

A device that is neither AW8697-based nor root-capable now:
- reports `isDirectDriveAvailable=false` instead of a phantom `true`,
- falls back to its own `Vibrator` path within one beat,
- and selects the actuator profile that actually matches its hardware.

## 5.5.0

### Root Cause: Permanent hapticPaused Deadlock

The v5.4.7 trace finally produced a `[BEAT]` line, immediately followed by a
`[PLAYBACK TRULY PAUSED]` that silenced everything again. Reading the loop
structure showed why that state was terminal.

#### Fixes

1. **Un-latch was unreachable code** (`HapticEngine.kt`)

   `runSemanticFrameLoop` opened with `if (hapticPaused) { delay(); continue }`,
   while the only recovery branch (`hasAudioActivity && hapticPaused`) sat *below*
   that guard. Once a spurious pause latched `hapticPaused = true` the branch
   could never be evaluated again, so the engine stayed silent for the rest of the
   session. Recovery is now evaluated before the guard, using two independent
   PCM-liveness sources (the `AudioTrack.write` timestamp and a native DSP block
   counter that no consumer drains).

2. **Native onset clears a stale latch** (`HapticEngine.kt`)

   Both `beatTriggerCallback` assignments now route through `onNativeBeat`, which
   treats a C++ onset as first-hand proof that audio is flowing and un-latches
   before dispatching. Previously `triggerBeatVibration` discarded the event on
   its first line while paused, so the one signal that could prove audio was live
   was the signal being dropped.

3. **UDP transport liveness, not mere existence** (`HapticEngine.kt` / `NativeBridge.cpp`)

   `nativeDrivingDirect` counted a merely-constructed `DatagramSocket` as direct
   drive. When `su` is refused the root daemon never starts, so no packet is ever
   consumed — yet Kotlin still skipped its own vibration path and the hardware
   stayed silent. `nativeIsUdpHapticReady` now reports whether sends are actually
   accepted: the socket is `connect()`ed to loopback, so the kernel raises
   `ECONNREFUSED` on the next send when nothing is bound to the daemon port, and
   that demotes the transport immediately. The engine therefore falls back to its
   own vibration path within one beat instead of going quiet for the session.

4. **False-positive pause suppression** (`HapticEngine.kt`)

   `markCandidateStopped()` now requires *both* liveness sources to be stale
   before latching, and skips entirely when no PCM has ever arrived. Previously
   the hook timestamp alone decided, so a never-firing `AudioTrack.write` hook
   made every live session look paused after 2 s.

5. **`nativeAudioSilenceMs()` probe** (`NativeBridge.cpp` / `NativeBridge.kt`)

   New JNI call returning milliseconds since the last PCM block reached the DSP,
   or `-1` when none ever has. Kept separate from the semantic/onset rings because
   the native scheduler drains those; a drained count cannot serve as a liveness
   signal.

6. **Bounded adaptive onset gating** (`HapticEngine.hpp`)

   The kick/snare onset gates were fixed constants (`0.40` / `0.30`), which cannot
   span a quiet acoustic passage and a loud electronic chorus — the same absolute
   flux is a drum hit in one and noise floor in the other. A slow EMA of the onset
   energy now raises the gate with sustained energy, clamped to `1.45x` the fixed
   base so a loud section can still never gate out real transients.

7. **Pause/resume diagnostics exported to dashboard** (`HapticEngine.kt`)

   `[PLAYBACK PAUSED CAUGHT]`, `[PLAYBACK FALSE PAUSED]` and the true-pause line
   now reach `LogBroadcaster` with the measured native silence age, so the next
   trace can distinguish a real pause from a hook failure without a logcat session.

## 5.4.7

### Root Cause: No AudioTrack.write Hook Trigger

**Critical fix for "no vibration at all" on v5.4.6.**

The v5.4.6 log revealed 0 `[BEAT]` events and 4 `[PLAYBACK TRULY PAUSED]` triggers. Investigation confirmed the root cause: **AudioTrack write hooks were never firing**, causing `pcmFallbackAtMs` to remain 0 forever. The false-positive `[PLAYBACK TRULY PAUSED]` then latched `hapticPaused=true`, killing all vibration.

#### Fixes

1. **`markCandidateStopped()` guard** (`HapticEngine.kt`): skip false-positive pause when no PCM has ever been received (`pcmFallbackAtMs == 0 && nativeLastAudioTime == 0`)

2. **Scheduler start logging** (`HapticEngine.kt`): log `startScheduler` return value via dashboard for diagnostics

3. **Write hook diagnostics** (`HookCoordinator.kt`):
   - Log count of hooked `AudioTrack.write` methods at install time
   - Warn when 0 methods hooked (indicates signature mismatch or hook framework failure)
   - Write trigger logs now use `Log.i` + `LogBroadcaster` (was `Log.d` only) so they appear in dashboard exports

## 5.4.6

### AW8697 Four-Sequence Fix (Hardware-Verified)
- **Root cause**: The AW8697 driver defaults to `activate_mode=2` (rtp mode). Writing only `activate=1` in rtp mode replays an empty RAM waveform, causing the chip to immediately erase → stop → standby. The motor never moves.
- **Fix**: All three output paths (UDP daemon shell script, root pipe, direct fd) now execute the hardware-verified four-step sequence:
  1. `activate_mode=0` (switch to ram mode)
  2. `duration=<ms>` (set duration)
  3. `gain=<hex>` (set amplitude, 1:1 mapping)
  4. `activate=1` (trigger)
- **Gain mapping corrected**: Removed the incorrect `*200/255` scaling. Hardware testing confirmed gain maps 1:1 onto the driver `level` register (writing `0x80` yields `level=0x80`). Clamped to `0x00-0x7f`.
- **RootHapticDaemon.kt**: Daemon script now opens `activate_mode` (fd5) and `duration` (fd6) alongside `activate` (fd3) and `gain` (fd4), and writes all four in correct order.
- **NativeBridge.cpp**: `trigger_direct_drive` root pipe and direct fd paths both use `write_node()` for the four-step sequence when AW8697 is detected.

## 5.4.5

### StrikeOnly Output Path Restored
- Restored the direct-drive beat strike for StrikeOnly drivers (AW8697 and
  similar). It had been removed to avoid double-firing with the Kotlin
  callback, but that left the device with no real output: the Kotlin path
  degrades to `DEFAULT_AMPLITUDE` one-shots on drivers without amplitude
  control, which are barely perceptible. The scheduler now fires
  `trigger_direct_drive(5, intensity * userAmp)` for StrikeOnly drivers in
  addition to the callback.
- Kotlin `triggerBeatVibration` now detects when the native scheduler is
  already driving the hardware (direct fd, root pipe or UDP transport) and
  skips its own vibration call, so the strike is not duplicated. The BEAT
  log line reports `path=NativeDD` in that case.
- Fixed the driver kind never being set when `open()` on the sysfs node
  fails. The hooked app process has no write permission to `/sys`, so
  `init_direct_drive` always returned false and left the driver kind as
  `Unknown` — which meant the StrikeOnly branches (beat strike and texture
  pulsing) were never taken even though UDP or the root pipe was doing the
  actual writing. The kind is now preset from the node path name.

### UI Level Reaches Every Output Path
- Added `userAmplitude` to the StrikeOnly texture pulsing amplitude. It was
  previously `texEnergy * styleAmpScale * masterGain * 255`, so the UI level
  slider had no effect on the texture layer at all.

### AW8697 Gain Encoding
- The root UDP daemon now writes the AW8697 `gain` node as a hex value in
  the 0x00-0xc8 range (amplitude 0-255 mapped to 0-200), matching what the
  native root pipe and direct fd paths already did. Previously the daemon
  wrote a decimal value, which the gain register did not interpret as
  intended.

### Playback Pause Debounce
- Raised the `PLAYBACK TRULY PAUSED` timeout from 800 ms to 2000 ms. Apple
  Music briefly interrupts its audio stream on seek and track change, and
  800 ms was short enough to trigger the full haptic teardown
  (`hapticPaused`, `vibrateProxy.setPaused()`, `clearHapticBuffer()`,
  `forceDecay()`) several times per track.

### Documentation
- Added `docs/NATIVE_ENGINE.md` describing the native analysis and output
  pipeline: FFT band split, onset detection, semantic frames, the 5 ms
  scheduler loop, the three output transports, and the AW8697 strike
  semantics.

## 5.4.4

### UI Level Fix (Second Pass)
- Removed the `0.65f` compression factor from `outputAmp` calculation. Previously
  `outputAmp = baseAmplitude * presetGain * 0.65f`, which cut 35% off the amplitude
  before it even reached the synthesizer. Now `outputAmp = baseAmplitude * presetGain`.
- Widened the preset gain range from [0.70, 1.20] to [0.45, 1.60], so the difference
  between low and ultra is now 3.6x instead of 1.7x.
- Moved `levelScale` out of the `character` product and applied it directly to the
  final peak. Previously `levelScale` was multiplied inside `character` along with
  `eventGain * accentScale * ampScale * masterGain` (product ~3.6), then divided by
  `DRIVE_REFERENCE=1.0` and clamped to `DRIVE_CEILING=2.0` — so levelScale was always
  crushed by the ceiling clamp. Now levelScale multiplies the final peak directly:
  `peak = shaped * drive * levelScale * 255`.
- Added failure logging to `performDynamicEffect` so that `path=Waveform` in the BEAT
  log line is now accompanied by a `DynamicEffect unavailable: ExceptionClass: message`
  warning, enabling diagnosis of why the DynamicEffect path fails.

## 5.4.3

### UI Level Fix
- Fixed the UI vibration level (low/medium/high/ultra) having no audible effect on the
  native direct-drive path. The native scheduler computed `targetAmp` using only
  `styleAmpScale * masterGain`, completely ignoring the user's amplitude setting. Now
  `userAmplitude_` is read from the engine and multiplied into the target amplitude.
- Fixed the drive reference normalisation crushing the UI level range. `DRIVE_REFERENCE`
  was 2.60 and `DRIVE_CEILING` was 1.30, which mapped both low (0.3) and ultra (2.0) to
  the same clamped drive value of 0.77. Now `DRIVE_REFERENCE` is 1.0 and `DRIVE_CEILING`
  is 2.0, giving a full 4x range from low to ultra.

### Event Diversity
- Fixed SNARE onsets never triggering. The snare threshold was `0.50 * sqrt(floorRatio)`
  clamped to [0.34, 0.68], which was too high for the actual low-mid band energy in
  most music. Now `0.30 * sqrt(floorRatio)` clamped to [0.18, 0.42].
- Fixed BODY onsets never triggering. The body threshold required `bodyStrength >= 0.35`
  with a `dspFloor * 4.0f` offset, which was too sensitive. Now `dspFloor * 2.0f` with
  a `bodyStrength >= 0.15` threshold and a wider output range up to 0.50.
- Fixed VOCAL events being nearly inaudible. The `ampBase` was 70f and the `ampCtrl`
  timing was 28.2ms total, producing a very short and quiet pulse. Now `ampBase` is
  120f and the `ampCtrl` timing is 37ms, giving VOCAL events a clearly audible presence.

## 5.4.2

### Beat Dynamics
- Fixed every beat firing at an identical amplitude. The peak formula multiplied the
  onset value by level, bass boost, impact gain, style scale and master gain, which
  pushed the result far past 255 before the clamp, so the clamp flattened all beats
  to the same peak regardless of how hard the hit was.
- Fixed every beat having an identical duration. Segment lengths were rounded to a
  16.7 ms grid derived from a fixed 60 Hz synthesis rate, which is longer than a
  typical drum body, so all three segments collapsed onto the same value.
- The onset band used by percussion events is now expanded onto the full amplitude
  range, so a light hi-hat and a hard kick are no longer mapped to the same level.
- The combined level and style multipliers are now normalised to a single drive term
  with a bounded ceiling, so user gain still has an audible effect without driving the
  waveform into the amplitude clamp.
- Beat duration is now mapped onto an explicit intensity window instead of being
  scaled by a small factor, so weak and strong hits produce measurably different
  lengths.

### Event Diversity
- Fixed VOCAL and BODY onsets never triggering. The onset detector capped vocal
  output at 0.10 and body at 0.05, but the trigger thresholds required 0.12 and 0.16
  respectively, making both event types mathematically impossible to fire. The caps
  and thresholds are now aligned so that vocals and bass sustain produce haptic
  output alongside kicks and snares.
- Intensity now blends 55% onset transient with 45% continuous RMS energy, so the
  same drum hit in a loud chorus produces a stronger buzz than in a quiet verse.
  Previously the intensity only reflected the momentary onset spike, ignoring the
  overall music volume.
- Reduced the native scheduler refractory period from 55 ms to 30 ms and the
  balanced style cooldown from 118 ms to 60 ms, allowing faster consecutive events
  to produce individual haptic pulses instead of being suppressed.

### Playback Stability
- Increased the playback-paused detection timeout from 200 ms to 800 ms to stop
  false pause triggers during brief audio gaps, which previously interrupted the
  haptic output multiple times per song.

## 5.4.1
### Release Signing
- Fixed the release build being signed with the CI runner's throwaway debug key, which produced a different signature for every build and made Android refuse to install over the previously installed app ("signatures do not match")
- Release builds now use a fixed RSA-4096 signing key, delivered to CI through the repository secrets `MHX_KEYSTORE_B64`, `MHX_KEYSTORE_PASS` and `MHX_KEY_ALIAS`; the keystore is never committed to the repository
- The CI workflow restores the keystore before Gradle runs and passes it to a dedicated `release` signing config
- Builds without access to the secrets (fork pull requests, local builds) fall back to debug signing instead of failing
- **Note:** the first package signed with the new key must be installed after uninstalling the existing app, because Android does not allow replacing an app whose signature differs. Every later release upgrades in place.
### Fixes
- Fixed the crash-on-launch regression introduced by the 5.4.0 foreground keepalive service

## 5.4.0

### Dynamic Haptics (Apple-style)

- Vibration amplitude now tracks the music's actual energy in real-time
- Quiet passages produce gentle vibrations, loud passages produce strong ones
- Dynamic range restored: peak amplitude varies from 5 to 255 instead of always 255
- Duration scales with beat intensity: strong beats slightly longer, weak beats shorter and crisper
- Envelope sustain is intensity-dependent: punchy for strong hits, smooth for gentle ones

### Fixes

- Fixed crash on startup: `HapticForegroundService` acquired a partial wake lock without declaring `android.permission.WAKE_LOCK`, throwing `SecurityException` on the main thread and killing the process the moment the Dashboard opened
- Fixed amplitude crushing: level was always clamped to 1.90, peak always 255, making every beat feel identical regardless of music energy
- Fixed double-fire on StrikeOnly devices (AW8697): native direct-drive and Kotlin Waveform were both firing for each beat, causing a double-tap. Now only the Kotlin shaped-envelope path fires
- Fixed fixed 33ms duration: duration now scales with intensity (6-80ms range)
- Fixed native intensity mapping: onset values were multiplied by fixed per-event constants (255/220/170/150), now use dynamic onset strength directly

### Foreground Keepalive Hardening

- Every call in `HapticForegroundService` is now wrapped in `runCatching`; keepalive failures degrade to a log line instead of taking down the process
- Wake lock is now reference-counted by an explicit flag and re-asserted on `START_STICKY` redelivery, so it can never leak or double-release
- Foreground promotion failure now calls `stopSelf()` rather than leaving the service half-started
- Added `POST_NOTIFICATIONS` for the Android 13+ foreground notification

### Continuous Texture

- Added RMS-based texture pulsing for StrikeOnly devices
- Pulses at ~50Hz with amplitude proportional to the audio's band energy
- Creates perceived sustained texture similar to Apple Music Haptics "textures"

### UI / Settings

- Master switch now properly stops the native scheduler and all haptic output
- UI settings (amplitude, bass boost, preset, style) now have real effect on output
- Added foreground service for background keepalive with a persistent notification

## 5.3.2

- Removed the root onboarding screen.
- Removed unused music-analysis, legacy haptic and health-monitor code.
- Unified global and per-app runtime settings.
- Fixed PCM16 decoding and stale-frame handling.
- Advanced synthesizer parameters applied to live output.
- Preserved envelope timing on fallback devices.
- Raised default haptic output.
- Removed unused dependencies and dead code.