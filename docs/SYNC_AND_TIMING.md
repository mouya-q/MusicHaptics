# Sync and Timing (6.2.0)

Until 6.1.4 an onset fired the actuator as soon as its PCM had been *analysed*.
`AudioTrack.write` returns once frames are copied into the track buffer, so the
haptic led the sound by the buffer depth: tens of ms for small buffers, seconds
for large blocking float writes (the Kugou trace). 6.2.0 schedules against the
playback clock instead.

## Pipeline

1. **Anchor** (`TrackRegistry.anchorBeforeWrite` / `commitWrite`). Sampled when
   `write()` is *entered*: the audible time of the first frame about to be
   written, in CLOCK_MONOTONIC ns, `ts.nanoTime + (F - ts.framePosition) / rate`
   from `AudioTrack.getTimestamp`. It cannot be sampled on return: Kugou writes
   1 s of PCM with a blocking write that returns after most of the block has
   already played, which made every onset in the block look hundreds of ms late
   (6.2.0 dropped nearly all of them and produced no haptics at all). Without a
   timestamp: playback head plus an assumed 40 ms pipeline. An implausible result
   (lead beyond -8 s / +4 s) re-bases the frame counter and reports 0, meaning
   "unknown", which keeps the old fire-immediately behaviour. `flush()` resets the
   counter and clears pending haptics.
2. **Stamp** (`engine.rs`). `set_block_time` fixes the sample->time mapping per
   block. For each onset the attack is localised inside the 1024-sample window
   from 64-sample band energies (first segment at 5% of the window peak, minus a
   band group-delay bias: kick 2.2 ms, snare 0.5 ms). Synthetic test, 48 kHz:
   kick error std ~1 ms (window-centre guess: 6.2 ms), snare ~0.6 ms.
3. **Hold and release** (`NativeBridge.cpp`). Onsets wait in a 64-slot queue and
   fire at `audible - pathLatency - trim`. Path latency is an estimate (10 ms
   direct drive, 25 ms Android Vibrator; the waveform default came down from 35 ms
   after a field trim of -11 ms on a Xiaomi 10); trim is the dashboard "sync
   compensation" (-100..+200 ms, global or per app). Dropped when >150 ms late;
   an anchor claiming >3 s ahead is treated as untrusted and fires at once.

## Related fixes

- The scheduler's intensity blend read `pop_semantic`, the oldest of up to 512
  queued frames (~11 s old on the waveform path). It now reads a lock-free
  newest-frame snapshot (`mhx_engine_latest_semantic`).
- `FrameRing` evicted and popped with plain stores; both use compare-exchange now.
- JNI no longer holds `GetPrimitiveArrayCritical` across DSP and logging.
- Hann window table precomputed (was 1024 `cos` calls per window).
- Scheduler resynchronises after a stall instead of replaying missed 5 ms ticks.

## Field result (6.2.2, Kugou Lite, Xiaomi 10, waveform path)

About 3,400 timed onsets fired over a few minutes against ~340 late drops (10%) and
~360 untimed fires. Late drops and untimed fires cluster at track start, where the
timestamp is not yet available. The "6 consecutive late onsets" failsafe handles
that by ignoring anchors for 2 s.

## Not verified

No Android SDK/NDK/Cargo was available when this was written; the Rust unit tests
have not been run. The localisation numbers come from a NumPy model of the same
filters. Path-latency defaults and the
timestamp behaviour across ROMs need on-device checks: read the `[SYNC]` counters
(fired / late-dropped / untimed) and compare haptic and audio on an accelerometer
or high-speed capture before trusting the defaults.
