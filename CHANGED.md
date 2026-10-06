# Changes
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