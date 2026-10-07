# Changes

Current source version: **5.4.4**

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