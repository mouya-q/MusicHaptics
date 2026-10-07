# Native Engine — What the C++ Actually Does

This document explains the native (C++) half of MusicHapticsX: what it
computes, what it decides, and how it reaches the haptic hardware.

## 1. Position in the pipeline

```
AudioTrack.write (hooked process)
        |
        v
HookCoordinator  --PCM copy-->  AudioIngress (bounded queue, downmix)
        |
        v
NativeBridge (JNI)  --pushPcm-->  haptic::HapticEngine  (C++)
        |
        v
FFT / band analysis / onset detection / semantic frames
        |
        v
scheduler_thread_func  (5 ms frame loop)
        |
        +--> onBeatTrigger(JNI callback)  -> Kotlin shapes a waveform
        +--> trigger_direct_drive()       -> sysfs / UDP / root pipe / java pipe
```

Everything above the `scheduler_thread_func` line is *analysis*.
Everything below it is *output*. The Kotlin layer never sees raw PCM;
it only receives semantic events.

## 2. Analysis: HapticEngine

`haptic::HapticEngine` lives in `app/src/main/cpp/haptic/HapticEngine.hpp`
(header-only implementation). It owns the DSP state and is fed PCM by
`NativeBridge.cpp` through `pushPcm`.

### 2.1 Ingest

PCM arrives as `float` frames at the stream sample rate (44.1 / 48 kHz).
The engine keeps a rolling analysis window and a small history buffer so
that onset detection can look backwards in time. `pushSemanticFrame` is
the boundary between the DSP internals and the scheduler: it publishes a
`SemanticHapticFrame` snapshot that the scheduler reads with
`getSemanticFrames`.

### 2.2 Frequency analysis

A windowed FFT (Hann) produces a magnitude spectrum. The spectrum is
folded into five musical channels:

| Channel | Band | Musical role |
|---|---|---|
| Percussion | wideband transient | drums, clicks, attacks |
| Bass | low | kick body, sub, rumble |
| Vocal | mid | voice, leads, mid presence |
| Harmonic | mid-high | chords, pads, strings |
| Texture | high / noise floor | cymbals, air, reverb tails |

Each channel produces a smoothed amplitude envelope (fast attack, slow
release) so that a single sample spike does not create a false event.

### 2.3 Onset detection

`getOnsetFrames` returns four transient descriptors per frame:

* `kick` — low-band spectral flux
* `snare` — broadband flux with a noise-band emphasis
* `vocal` — mid-band flux with a formant emphasis
* `body` — low-mid sustained energy rise

Each descriptor is gated by a per-type refractory counter
(`onsetRefractoryFrames_`) so a decaying drum hit cannot retrigger. The
refractory length is scaled by the user's style sharpness and by the
device profile, which is why a fast LRA can accept denser events than a
slow one.

### 2.4 Semantic frames

`getSemanticFrames` returns the *sustained* picture rather than the
transient picture: `kickAmp`, `snareAmp`, `vocalAmp`, `bodyAmp`, plus the
five-channel envelope. The scheduler uses these for two purposes:
blending beat intensity with overall loudness, and driving continuous
direct-drive output.

## 3. Output: the scheduler thread

`startScheduler` spawns `scheduler_thread_func`, a `CLOCK_MONOTONIC`
loop with a 5 ms frame period (200 Hz). Each iteration performs four
steps in order.

### 3.1 Step 1 — beat trigger

1. Read one onset frame.
2. If `now - g_last_beat_trigger_ns >= BEAT_REFRACTORY_NS`, pick the
   strongest of `kick / snare / vocal / body` that clears
   `onsetThreshold` (with slightly relaxed gates for vocal and body).
3. Convert the onset value into a `beatAccent` using a per-type accent
   scale (kick 135, snare 100, vocal 58, body 42).
4. Read one semantic frame and compute a loudness term:
   `rmsEnergy = clamp(kick*0.45 + snare*0.25 + vocal*0.10 + body*0.35)`.
5. Blend: `blended = eventValue * 0.55 + rmsEnergy * 0.45`.
6. Apply the per-type character scale and map to 10..255:
   `intensity = clamp(blended * eventCharScale * 255)`.
7. Fire both outputs:
   * JNI callback `onBeatTrigger(eventName, intensity)` so Kotlin can log
     and (on capable devices) emit a shaped waveform.
   * For `StrikeOnly` drivers, a raw `trigger_direct_drive(5, intensity *
     userAmp)` so the hardware actually strikes.

The blend in step 5 is what makes the same drum hit feel stronger in a
chorus than in a verse; a pure onset value would be volume-independent.

### 3.2 Step 2 — texture pulsing (StrikeOnly only)

AW8697-class drivers cannot hold a continuous waveform; they only accept
a strike command. To fake the "continuous texture" that Apple Music
Haptics produces, the scheduler reads the sustained semantic frame and
computes

```
texEnergy = kick*0.45 + snare*0.20 + vocal*0.15 + body*0.30
texAmp    = clamp(texEnergy * styleAmpScale * masterGain * userAmp * 255)
```

If `texAmp > 15`, it pulses `trigger_direct_drive(5, texAmp)` once every
four frames (20 ms, i.e. 50 Hz). The 20 ms throttle exists because the
sysfs interface cannot sustain 200 writes/second without stalling.

### 3.3 Step 3 — continuous direct drive (non-StrikeOnly only)

For drivers that accept an amplitude node directly (qcom-haptics,
timed_output, some lra class nodes), the scheduler builds a target
amplitude every frame:

```
continuous = kick*0.55 + snare*0.25 + vocal*0.08 + body*0.35
beatAccent *= accentScaleUser          // user accent preference
if (kick onset) beatAccent *= bassBoost
targetAmp = clamp((continuous + beatAccent) * styleAmpScale
                  * masterGain * userAmp, 0, 255)
```

`currentAmp` follows `targetAmp` through an asymmetric one-pole filter:
a fast attack coefficient (`attackAlpha`, derived from the style attack
time and sharpness) when rising, and a slower release coefficient
(`releaseAlpha`, derived from the style release time and sustain) when
falling. When no audio is present, `currentAmp` decays to zero and the
driver is left idle.

A small LRA physics model runs alongside this to keep the spring position
and velocity bounded:

```
springK  = clamp((omega * 0.005)^2, 0.20, 4.00)
dampingC = clamp((omega * 0.005) / q, 0.05, 1.50)
accel    = (currentAmp / 255) - springK * position - dampingC * velocity
```

This prevents the amplitude curve from commanding motion the actuator
physically cannot follow.

### 3.4 Step 4 — idle decay

With no onsets and no semantic frames, `currentAmp` is pulled toward zero
with `releaseAlpha`; below 1.0 it snaps to 0.0 so the driver node is not
written with a residual value.

## 4. Output transport: trigger_direct_drive

`trigger_direct_drive(duration_ms, amplitude)` picks the first available
transport, in this order:

1. **Java pipe** (`g_use_java_pipe`) — calls back into Kotlin
   `onRootPipeTrigger(amplitude, duration)`; Kotlin writes to a `su`
   shell fd pair.
2. **UDP** (`g_use_udp_haptic`) — packs a 10-byte `HapticUdpPacket`
   (magic `0x3148584D`, version 1, duration big-endian, amplitude, flags)
   and `sendto`s it to the root daemon on `127.0.0.1:27042`.
3. **Root pipe** (`g_use_root_shell`) — writes a shell command to a
   persistent `su` fd.
4. **Direct fd** (`g_direct_drive_fd`) — writes to the sysfs node opened
   at init time.

### 4.1 Node handling

`init_direct_drive(nodes)` walks the comma-separated node list and tries
`open(O_WRONLY | O_NONBLOCK)` on each. On success it:

* records the enable path,
* records the driver kind (`StrikeOnly` when the path contains
  `activate`, `aw8697` or `aw86224`; otherwise `Continuous`),
* searches the same directory for an amplitude node, in the order
  `amplitude`, `gain`, `index_value`.

If every `open()` fails — the usual case inside a hooked app process,
which has no write permission to `/sys` — the driver kind is still
preset from the path name so that the StrikeOnly logic in the scheduler
behaves correctly once UDP or the root pipe takes over.

### 4.2 Amplitude encoding

Not every node wants the same number format:

* a `gain` node (AW8697) expects a hex value in the 0x00–0xc8 range, so
  the amplitude 0–255 is mapped to 0–200 and written as `0xNN`;
* any other amplitude node expects a plain decimal 0–255;
* if no amplitude node exists, the amplitude is dropped and only the
  enable/activate write is performed.

The same encoding is applied in three places — the native root pipe
command, the native direct fd write, and the Kotlin UDP daemon script —
so all transports are consistent.

### 4.3 AW8697 strike semantics

For an AW8697-class node the path contains `activate`. Writing `1` to it
fires one fixed-length strike; the duration argument is therefore ignored
and the perceived length is controlled by how often strikes are issued
(beat triggers plus the 50 Hz texture pulse train). The amplitude written
just before the strike sets the strike strength.

## 5. Threading and lifetime

* One scheduler thread, started by `startScheduler`, stopped by
  `stopScheduler` via `g_scheduler_running`.
* All cross-thread state uses `std::atomic` with explicit memory orders;
  paths are plain `std::string` written once at init.
* The JNI beat callback resolves `onBeatTrigger` once at thread start and
  caches global refs for the four event-name strings.
* `NativeBridge.kt` guards `nativePtr` with a lock and a volatile field so
  hook, engine and release paths cannot race.

## 6. What the native layer deliberately does not do

* It does not decide *whether* haptics are enabled — that is the Kotlin
  master switch.
* It does not apply device-specific gain tables — those live in
  `DeviceTuning` / `DeviceProfile` on the Kotlin side and reach the engine
  through `configure(...)`.
* It does not parse the user's UI level directly — `userAmplitude_` is
  pushed in by `configure(...)`, and the scheduler multiplies it into
  every output path so the UI slider has a real effect.
