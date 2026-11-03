# MusicHapticsX 6.3.1

## What changed

**Haptics now land with the sound.** Until 6.1 an onset fired the actuator as soon
as its audio had been analysed, which is before it is heard: the player's buffer
sits in between, from tens of milliseconds to a second. 6.3.0 reads the audio
track's playback clock, works out when each onset will be heard, and fires the
haptic then. A new **Sync compensation** slider (global, and per app) trims the
remaining offset of your motor.

**Sharper timing.** The attack is located inside the analysis window to about 1 ms
(it used to be anywhere in a 21 ms window).

**Stronger dynamics.** Hit strength is now weighted against the band's recent peak
and the continuous-loudness term is normalised correctly. Before, nearly every hit
reported 0.93 to 0.99 and the loudness term was always at maximum, so soft and
loud passages felt alike.

**Root direct drive that can actually run.** Root belongs to the module, not to the
music player, so the player process can never start the root helper. The module
process now keeps a small root receiver alive on loopback UDP and the hooked player
sends it packets. It starts when a hooked app loads its config, a lost receiver is
re-probed, and the engine uses the Android Vibrator while it is down.

**Release builds.** The R8 keep rule for the beat callback had a stale signature, so
a minified build could delete the method the native scheduler calls. Fixed, and
`scripts/repo_check.py` now checks every JNI callback against the keep rules.

## Fixes

- Beat intensity read audio ~11 s old on the waveform path.
- Lock-free queue race between producer eviction and consumer pop.
- JNI critical section no longer wraps the DSP call.
- Hann window computed once instead of 1,024 times per window.
- Scheduler no longer replays missed ticks after a stall, and runs at a higher priority.
- `flush()` on the host track (seek, track change) clears queued haptics.
- UI text no longer ends with full stops.

## Since the first 6.3.0 build

Release build, Xiaomi 10: beats flow, so the R8 fix holds, and the root server came
up by itself and drove the actuator for the first beats. It then dropped back to the
Android Vibrator and flipped a few times; 6.3.1 re-probes after 0.4 s and logs why
(`[CHAIN] udp ...`, `/data/local/tmp/mhx_haptic.log`). Players no longer run `su`.

## Diagnostics

`[CHAIN]` is logged every 2 seconds: timed onsets fired, dropped late, fired without
an anchor, and how many reached Kotlin. `[SYNC]` repeats it every 40 beats.

## Verified and not verified

Verified on a Xiaomi 10 (Kugou Lite and Bilibili): playback-clock scheduling,
intensity changes, the sync slider, the release build (beats reach the actuator),
and the root server starting and driving the actuator at first. Not verified:
that direct drive stays up for a whole session (see above), the Rust unit tests.
