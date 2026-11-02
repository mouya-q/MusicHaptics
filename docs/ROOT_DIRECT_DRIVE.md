# Root Direct Drive (6.3.0)

Root is granted to the module app, never to the hooked music player. A hooked
process therefore cannot run `su` (`Cannot run program "su": error=13`), cannot
open the actuator sysfs node (SELinux), and used to fall back to Android Vibrator
waveforms with the default amplitude, which is weak on an AW8697 LRA.

## Layout

    hooked player ──UDP 10 B──▶ 127.0.0.1:27042 ──▶ RootHapticServer (root) ──▶ sysfs
                                                        ▲
                       module process (ConfigProvider) ─┘ launches it via su

- `RootHapticServer` runs under `su -c app_process`, binds the loopback port once,
  keeps the sysfs files open, and writes mode -> duration -> gain -> activate per
  packet. Packet format is unchanged (magic 0x3148584D LE, version 1, duration BE,
  amplitude, flags). Duration 0 + amplitude 0 is a liveness probe.
- `RootHapticDaemon.ensureRunningAsync` is called from `ConfigProvider`. Every
  hooked app reads its config through that provider, which starts the module
  process, so the server comes up when playback does; opening the UI is not needed.
- The native transport re-probes a lost receiver every 250 ms-2 s instead of
  condemning it for the life of the process.

## Why the old daemon never worked reliably

It was `nc -u -l ... | dd | od | tr | expr` in a loop: five process spawns per
packet and no socket bound in between. A beat sent in that gap got ICMP
port-unreachable, the native side marked the receiver dead permanently, and the
engine fell back to waveforms. It was also only started when the module UI was
opened.

## Release-build bug fixed alongside

`proguard-rules.pro` kept `onBeatTrigger(String, int)` while the real method (and
the JNI descriptor) is `(String, int, boolean)`. R8 deletes unreferenced methods, so
release builds could lose the method the native scheduler calls for every beat; the
scheduler then logs "beat callback method unavailable; event output disabled" and
no haptic is ever produced. `scripts/repo_check.py` now compares every
`GetMethodID` descriptor with the keep rules.

## Verify on device

    su -c "pidof mhx_haptic"            # server process exists
    su -c "ss -lun | grep 27042"        # bound on loopback (or netstat -lun)

Logs to expect: `[UDP] peer verified alive`, `[OUTPUT] active route=NATIVE_DIRECT_DRIVE`,
`[CHAIN] ... kotlinBeats=N`. Not verified: no Android toolchain was available.
