# MusicHapticsX

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://www.android.com/)
[![LSPosed](https://img.shields.io/badge/LSPosed-Module-6F42C1)](https://github.com/LSPosed/LSPosed)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![C++](https://img.shields.io/badge/Native-C%2B%2B-00599C?logo=cplusplus&logoColor=white)](https://isocpp.org/)
[![DSP](https://img.shields.io/badge/Audio-DSP-111827)](#audio-analysis)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/mouya-q/MusicHaptics/build.yml?label=Build)](../../actions)

[中文](README.md) | **English**

> An Android LSPosed music-haptics module that turns meaningful moments in music into controlled, device-aware haptic events.

MusicHapticsX captures PCM audio from supported players, extracts transients, frequency information and rhythmic events, then synthesizes short haptic responses around the characteristics of the target actuator.

The goal is not to make the phone vibrate continuously with the music. It is to place the right sensation at the right moment.

> MusicHapticsX is an independent open-source project. It is not affiliated with, endorsed by, or supported by Xiaomi, HyperOS, Apple, LSPosed, or any music service provider.

---

## Contents

- [Features](#features)
- [How it works](#how-it-works)
- [Audio analysis](#audio-analysis)
- [Haptic synthesis](#haptic-synthesis)
- [Device adaptation](#device-adaptation)
- [Installation](#installation)
- [Configuration](#configuration)
- [Repository layout](#repository-layout)
- [Building](#building)
- [Validation](#validation)
- [Known limitations](#known-limitations)
- [Third-party and licensing](#third-party-and-licensing)

---

## Features

- **AudioTrack hooks** — supports common `write(ByteArray / ShortArray / FloatArray / ByteBuffer, …)` paths and playback lifecycle events.
- **PCM-first processing** — uses AudioTrack PCM when available, with Visualizer as a compatibility fallback.
- **Native DSP** — C++ analysis core with fixed-size buffers, RMS, five frequency bands, 512-point FFT, spectral flux, spectral centroid, autocorrelation pitch estimation and transient detection.
- **Semantic events** — separates music into `KICK / SNARE / VOCAL / BODY` instead of following overall volume.
- **Dynamic haptics** — event intensity affects amplitude, duration and envelope instead of making every beat identical.
- **Actuator-aware rendering** — device profiles adjust gain, duration, cooldown, band weights and output policy.
- **Strike-only support** — one-shot nodes such as AW8697 / AW86224 are not incorrectly treated as continuous amplitude interfaces.
- **Application whitelist** — LSPosed controls injection scope; MusicHapticsX independently controls which injected applications are processed.
- **LiquidGlass UI** — settings, app lists and the console use a dedicated `:liquidglass` module with a normal translucent fallback.
- **ARM64 Native** — release Native binaries target modern ARM64 Android devices.

---

## How it works

The runtime is split into independent stages:

```text
LSPosed
   │
   ▼
HookCoordinator
   │  AudioTrack PCM
   ▼
AudioIngress
   │  bounded queue
   ▼
Native DSP
   │  onset / bands / rhythm
   ▼
Semantic Events
   │  KICK / SNARE / VOCAL / BODY
   ▼
HapticImpactPolicy
   │  timing / gating
   ▼
HapticSynthesizer
   │  actuator-aware envelope
   ├───────────────┐
   ▼               ▼
Direct Drive    Android Vibrator
```

The hook thread only performs interception, lightweight parameter reads and PCM normalization. FFT, filtering, probability updates and haptic synthesis do not run directly on the host application's audio write thread.

Audio input uses a bounded 16-slot queue, with up to 256 mono frames per slot. Under load, stale work is discarded rather than allowing latency to grow without bound.

---

## Audio analysis

The Native DSP pipeline currently includes:

1. **Input shaping** — PCM16, float PCM and ByteBuffer are normalized into a mono analysis stream.
2. **Band analysis** — approximately 80–180 Hz, 180–500 Hz, 500–3000 Hz, 3–8 kHz and above 8 kHz.
3. **Energy features** — RMS, absolute amplitude, zero-crossing rate and dynamic change.
4. **Spectral features** — 512-point windowed FFT, spectral flux, low/high-frequency flux and spectral centroid.
5. **Periodicity** — throttled autocorrelation-based pitch estimation.
6. **Transient detection** — attack/onset changes are used instead of raw volume alone.
7. **Semantic classification** — results become KICK, SNARE, VOCAL and BODY events.

### Why not simply follow volume?

A direct RMS-to-vibration mapping makes the phone vibrate harder whenever the mix is louder, but it cannot distinguish a kick from vocals or background bass.

MusicHapticsX first determines what changed, then decides how that event should feel.

---

## Haptic synthesis

Events enter `HapticImpactPolicy` and are converted into short actuator-aware envelopes:

```text
Semantic Event
      │
      ├─ KICK   → fast attack + controlled low-frequency body
      ├─ SNARE  → short peak + texture tail
      ├─ VOCAL  → soft accent
      └─ BODY   → sparse low-amplitude support
      │
      ▼
Compact Impact Policy
      │
      ▼
Actuator-aware Sculpting
      │
      ├─ calibrated primitive
      └─ multi-segment waveform
```

The core rules are deliberately simple:

- attack stays short;
- body matters more than the tail;
- the tail must not mask the next beat;
- continuous layers add presence without replacing transients;
- real-time freshness is preferred over processing stale audio blocks.

The 5.4.x line also fixes dynamic amplitude and duration behaviour so strong and weak events no longer collapse to the same output.

---

## Device adaptation

Device adaptation has three layers:

```text
DeviceProfile
      │
      ├── actuator model
      ├── DSP floor / band multipliers
      └── event weights
              │
              ▼
DeviceTuning
      │
      ├── duration
      ├── gain
      ├── cooldown
      └── amplitude ceiling
              │
              ▼
HapticImpactPolicy
              │
              ▼
VibrateProxy / direct drive
```

The current source contains named profiles for Xiaomi, Redmi, OnePlus, OPPO, Lenovo, Samsung, vivo / iQOO and other device families, with a generic fallback for unknown hardware.

> “Deep tuning” means the profile participates in actual rendering decisions. It does not imply that every actuator parameter is an official vendor specification; some curves are empirical models based on device testing.

---

## Installation

### Requirements

- Android 8.0+ / API 28+
- ARM64 Android device
- Root
- LSPosed or another compatible Xposed API 82+ framework

### Steps

1. Install the MusicHapticsX APK.
2. Enable the module in LSPosed.
3. Add the music applications you want to process to the LSPosed scope.
4. Enable the same applications in the MusicHapticsX whitelist.
5. Force-stop the target application and start it again.
6. Play music and inspect the dashboard / console if needed.

For normal use, keep whitelist mode enabled. `mode=all` is intended for debugging only.

---

## Configuration

Whitelist file:

```text
/data/adb/musichaptics/whitelist.conf
```

Example:

```text
mode=whitelist
com.netease.cloudmusic
com.tencent.qqmusic
com.spotify.music
```

`mode=all` is available for debugging.

Runtime configuration is exposed through a read-only `ContentProvider`. The provider validates the caller package, target package format and whitelist state before exposing configuration and hardware information to an enabled target process.

---

## Repository layout

```text
MusicHapticsX/
├─ app/
│  └─ src/main/
│     ├─ java/com/mouya/musichaptics/
│     │  ├─ hook/          HookCoordinator / TrackRegistry / IPC
│     │  ├─ audio/         PCM ingress
│     │  ├─ haptic/        device tuning / impact policy
│     │  ├─ phira/         Phira timing
│     │  └─ ui/             dashboard / console
│     └─ cpp/
│        ├─ haptic/         Native DSP / haptic engine
│        └─ jni/            JNI bridge / scheduler
├─ liquidglass/              LiquidGlass / Backdrop integration
├─ docs/                     architecture notes
├─ scripts/                  repository checks
└─ .github/workflows/        CI
```

---

## Building

### Toolchain

- JDK 17
- Android SDK 35
- Android NDK `27.0.12077973`
- CMake `3.22.1`
- Gradle 8.9
- Android Gradle Plugin 8.7.0
- Kotlin 2.0.21

### Debug

```bash
./gradlew assembleDebug
```

### Release

```bash
./gradlew assembleRelease
```

### Repository checks

```bash
python3 scripts/repo_check.py
```

CI uses the repository's fixed release signing key. Local builds and builds without the required CI secrets fall back to debug signing.

The current Native release target is:

```text
arm64-v8a
```

---

## Validation

Recommended checks:

1. Verify normal playback with the module disabled.
2. Enable one player and confirm that only its whitelisted package is processed.
3. Test silence, vocals, drums and dense electronic music.
4. Test fast 8th- and 16th-note patterns and make sure impacts do not become motor hum.
5. Compare multiple device profiles and verify that duration and cooldown actually change.
6. Test both direct-drive and Android Vibrator fallback paths.
7. Pause, switch tracks and background the player; verify that haptics are released.
8. Test Visualizer fallback in an application without usable AudioTrack PCM.
9. Run long playback and inspect thermal and haptic stability.

---

## Known limitations

- Vendor `/sys` haptic nodes do not share a universal semantic contract; unknown devices prefer Android Vibrator fallback.
- Visualizer is a compatibility path, not equivalent to direct PCM hooking; latency and dynamic range differ.
- Root direct-drive paths depend on vendor nodes and SELinux policy and may change after system updates.
- Device tuning is based partly on real-device testing and does not imply that the same parameters are correct for every device in a family.
- Native release binaries currently target `arm64-v8a` only.

---

## Third-party and licensing

MusicHapticsX is licensed under the **MIT License**.

See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for third-party notices.

The bundled LiquidGlass / Backdrop sources retain their Apache-2.0 license file.

Credits:

- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) — LiquidGlass / Backdrop implementation.
- [KISS FFT](https://github.com/mborgerding/kissfft) — reference for FFT design.
- The Android / Jetpack Compose / LSPosed ecosystem.

---

## Documentation

- [`README.md`](README.md) — 简体中文
- [`CHANGED.md`](CHANGED.md) — release changes
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — runtime architecture
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — contribution guide
- [`SECURITY.md`](SECURITY.md) — security notes
- [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) — third-party notices
