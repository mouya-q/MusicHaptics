# Architecture

## 1. Runtime boundaries

MusicHapticsX has three runtime domains:

- **Module app**: settings, whitelist editing, explicit Root hardware probing and diagnostics.
- **Injected target process**: HookCoordinator, AudioIngress and HapticEngine.
- **Native DSP / output layer**: fixed-size DSP state, scheduler and actuator transports.

The target process must not perform heavyweight module management work. In particular, Root hardware discovery belongs to the module app; target processes consume cached configuration through the read-only provider.

## 2. Hook lifecycle

```text
LSPosed handleLoadPackage
        │
        ├─ blocked package? ── yes ──> return
        │
        ├─ whitelist? ── no ──> return
        │
        ▼
HookCoordinator.install()
        │
        ├─ Application.attach()
        │       └─ target Context -> engine init
        │
        ├─ AudioTrack constructors
        ├─ AudioTrack write(...)
        ├─ AudioTrack play/pause/stop/flush/release
        └─ MediaPlayer / SoundPool lifecycle
```

Hook callbacks do not run FFT or trigger long-lived vibration effects directly. They mark state, normalize input and enqueue PCM.

## 3. Audio pipeline

`AudioIngress` is a bounded MPSC producer side with a single Native DSP consumer.

- 16 preallocated slots.
- 256 mono frames per slot.
- Direct `ByteBuffer` for normalized PCM.
- No per-block object allocation after initialization.
- Queue overflow is counted and dropped rather than blocking the audio writer.

This deliberately favors low tail latency over lossless archival processing.

## 4. DSP pipeline

```text
PCM
 │
 ├─ RMS / abs / ZCR
 ├─ LR4 band bank
 │    ├─ 80–180 Hz
 │    ├─ 180–500 Hz
 │    ├─ 500–3000 Hz
 │    ├─ 3000–8000 Hz
 │    └─ >8000 Hz
 │
 ├─ 512 FFT
 │    ├─ spectral flux
 │    ├─ bass flux
 │    ├─ high flux
 │    └─ spectral centroid
 │
 ├─ autocorrelation pitch estimate
 │
 └─ transient classifier
      ├─ KICK
      ├─ SNARE
      ├─ VOCAL
      └─ BODY
```

The event layer is deliberately transient-first. A loud sustained signal should produce a modest body layer, not a stream of identical impacts.

## 5. Haptic composition

`HapticImpactPolicy` converts semantic events into bounded plans.

```text
semantic event
     │
     ├─ device tuning
     ├─ actuator rise time
     ├─ actuator Q
     ├─ amplitude / default-amplitude mode
     ├─ attack / sustain / decay fractions
     └─ minimum interval
              │
              ▼
         haptic plan
```

The Xiaomi 10 profile intentionally shortens event windows. For one-shot Awinic nodes, Native output becomes strike-only and bypasses continuous scheduler writes.

## 6. Device adaptation

The device adaptation path is intentionally centralized:

```text
Build / Root fingerprint
        │
        ▼
DeviceProfile
   ├─ actuator model
   ├─ dspEnergyFloor / band multipliers
   └─ dspRefractoryScale
        │
        ├──────────────► Native DSP configureProfile()
        │
        └──────────────► DeviceTuningRegistry
                              │
                              ▼
                       HapticImpactPolicy
                              │
                              ▼
                         VibrateProxy
```

`DeviceProfile` is the single resolved source of truth for the target model. `DeviceTuningRegistry.current(profile)` adds output-side empirical corrections without reparsing `Build.*`. This matters on ports and spoofed builds where the Root fingerprint may be more reliable than the Java-side manufacturer string.

The named profiles are deliberately explicit. Xiaomi/Redmi families, OnePlus/OPPO families, Legion Y700 generations, Samsung S25 and vivo/iQOO compatibility each get a dedicated output curve. `FLAGSHIP_XAXIS` is the generic modern-flagship fallback; `DEFAULT` is an explicit safe baseline for unknown hardware.

The DSP profile values are bounded before use and never change the user's master loudness setting. They adjust detection sensitivity and event texture instead. `DeviceTuning` separately bounds event amplitude, duration and cooldown.

## 7. Output transports

Priority is:

1. Direct writable actuator node.
2. Root-assisted file descriptor.
3. Root pipe / UDP local daemon where required by SELinux separation.
4. Android Vibrator / proxy fallback.

Only one transport owns a given event. When direct output is active, the Native scheduler does not also emit the same event through the Java callback path.

## 8. Configuration ownership

```text
Module settings
     │
     ├─ SharedPreferences
     └─ whitelist.conf under /data/adb
              │
              ▼
        ConfigProvider
        read-only + caller checks
              │
              ▼
   HookConfigPreferences in target
```

The provider is intentionally not a generic settings pipe. Only keys used by the hook engine are exposed.

## 9. Device probing

Module-side probing may run Root commands to collect board properties and discover known vibrator nodes. Hooked apps do not repeat this scan for every process start. This reduces startup cost and limits privileged work to an explicit module operation.

Unknown or invalid node paths are rejected in the Native layer before opening or writing them.

## 10. UI rendering

`liquidglass/` is a self-contained Android adaptation of the Backdrop implementation used by AndroidLiquidGlass. The dashboard shares one `LayerBackdrop` so multiple glass surfaces sample the same scene rather than constructing separate rendering contexts for every card.

The UI has a fallback path for devices that cannot use the full backdrop effect.
