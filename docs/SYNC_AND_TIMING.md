# Sync and Timing (6.2.0)

Until 6.1.4 an onset fired the actuator as soon as its PCM had been *analysed*.
`AudioTrack.write` returns once frames are copied into the track buffer, so the
haptic led the sound by the buffer depth: tens of ms for small buffers, seconds
for large blocking float writes (the Kugou trace). 6.2.0 schedules against the
playback clock instead.

## Pipeline

1. **Anchor** (`TrackRegistry.audibleNsForWrite`). After every write, the first
   frame of that write gets an audible time in CLOCK_MONOTONIC ns:
   `ts.nanoTime + (F - ts.framePosition) / rate`, from `AudioTrack.getTimestamp`.
   Without a timestamp: playback head plus an assumed 40 ms pipeline. An
   implausible result (|lead| beyond -8 s / +4 s) returns 0 and re-bases the
   counter; 0 means "unknown" and keeps the old fire-immediately behaviour.
   `flush()` resets the frame counter and clears pending haptics.
2. **Stamp** (`engine.rs`). `set_block_time` fixes the sample->time mapping per
   block. For each onset the attack is localised inside the 1024-sample window
   from 64-sample band energies (first segment at 5% of the window peak, minus a
   band group-delay bias: kick 2.2 ms, snare 0.5 ms). Synthetic test, 48 kHz:
   kick error std ~1 ms (window-centre guess: 6.2 ms), snare ~0.6 ms.
3. **Hold and release** (`NativeBridge.cpp`). Onsets wait in a 64-slot queue and
   fire at `audible - pathLatency - trim`. Path latency is an estimate (10 ms
   direct drive, 35 ms Android Vibrator); trim is the dashboard "sync
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

## Not verified

No Android SDK/NDK/Cargo was available when this was written. The localisation
numbers come from a NumPy model of the same filters. Path-latency defaults and the
timestamp behaviour across ROMs need on-device checks: read the `[SYNC]` counters
(fired / late-dropped / untimed) and compare haptic and audio on an accelerometer
or high-speed capture before trusting the defaults.
