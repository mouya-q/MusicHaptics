# Changes

Current source version: **5.5.2**

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