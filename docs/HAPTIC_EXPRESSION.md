# Haptic Expression (6.4.0)

How a hit is made to feel light, medium or heavy, on every kind of device rather than
on one phone.

## Design language

The reference is the way Apple's haptic design is described: separate short **transients**
(a tap, with a strength and a crispness) from **continuous** feel (a swell, a rumble), and
let strength change by class, not only by a smooth scale. Three rules carry over:

- **Causality**: a haptic coincides with the sound that caused it (6.3 playback-clock sync).
- **Harmony**: a heavy bass note feels heavy, a hi-hat feels light, vocals feel soft.
- **Utility**: nothing is added that the music does not call for; ghost notes stay faint.

## What each device can do

The output is chosen from what the device reports, not from its name:

| Output | Typical devices | How weight is expressed |
| --- | --- | --- |
| Root direct drive (gain, duration) | AW8697-class LRAs, with the root service | real amplitude and duration (tuning is the next phase) |
| Amplitude envelope | most phones with amplitude control | amplitude levels, `Light/Heavy contrast`, and a soft rumble tail on heavy kicks (Taptic style) |
| Primitive composition | phones with primitives but no amplitude control | TICK / CLICK / THUD chosen by weight class, scaled inside the class |
| Default amplitude only | HAL-owned amplitude (for example Xiaomi 10) | pulse patterns: how long, and how many pulses |
| Neither amplitude nor primitives | older and budget phones | the same pulse patterns |

## Pulse patterns (outputs that cannot take an amplitude)

Amplitude is out of reach, so a hit is made of **duration and pulse count**:

- light: one short, crisp pulse
- medium: one firmer pulse
- heavy: a long pulse, a small gap, a short echo (reads as weight; a heavy kick and a
  heavy snare use different lengths)

Nothing is tuned to one actuator. Every length is scaled by the device's own rise and fall
time (`ActuatorProfile`): a slow ERM-class motor gets longer pulses and wider gaps than a
fast X-axis LRA, a pulse is never shorter than the rise time (it would not develop), the gap
always outlasts the fall time (or two pulses merge into one), and the whole pattern is fitted
into the time left before the next onset. If it does not fit, the echo is dropped first.

## Settings

- **Style: Taptic (拟真)**: turns the pulse patterns on for the outputs above, and the
  rumble tail for amplitude-capable outputs. Other styles are unchanged.
- **Light/heavy contrast** (0.5 to 2.0, default 1.0): above 1 soft hits get softer and hard
  hits harder; 1.0 changes nothing. Works with every style and output that reads intensity.

## What is not done yet

- Continuous bass: a sustained low-frequency texture that follows the sub-band envelope. It
  needs a continuous amplitude control, so it belongs to root direct drive (and to
  amplitude-capable devices once the root path is proven stable); it is not simulated with
  fast repeated waveforms, which Android replaces on every call.
- Direct-drive envelopes: with a real gain control the same weight classes should be played
  as amplitude, not only as duration.
- A per-device calibration screen that lets the user fit the pattern lengths to their motor.

## Not verified

The pattern lengths and class boundaries are a first design, not measured on hardware. The
unit tests (`HapticExpressionTest`) cover the rules (budget, rise/fall floors, class
boundaries, contrast) and run in CI; the feel has to be judged on a device, which is why the
style is opt-in and the contrast slider exists.
