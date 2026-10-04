# Architecture

## Runtime

`HookCoordinator` captures supported audio streams.

`AudioIngress` bounds the queue, downmixes PCM, and drops stale work under load.

`NativeBridge` runs FFT, band analysis, onset detection, and direct-drive timing.

`HapticEngine` applies style, gain, device, thermal, and envelope policy.

`HapticSynthesizer` shapes semantic events into short envelopes.

`VibrateProxy` selects direct drive, vendor effect, waveform, or primitive fallback.

## Configuration

Dashboard preferences are persisted, exposed through `ConfigProvider`, refreshed in hooked processes, and consumed by the runtime engine. Global and per-app settings use the same keys.

## Device adaptation

`DeviceProfile` describes the actuator. `DeviceTuning` supplies runtime gains, thresholds, timing, and driver policy for each supported profile.
