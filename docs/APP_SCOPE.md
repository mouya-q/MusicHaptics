# App Scope and Whitelist (6.3.2)

Two separate lists decide what the module touches:

- **LSPosed scope** (set in LSPosed): which processes the module is injected into.
- **Application filter** (`/data/adb/musichaptics/whitelist.conf`): which of those
  injected packages are actually processed. Written with `su` by the module app,
  read by the hook when a process starts, so it needs no `Context`.

## Where the app list comes from

1. `libxposed` service API, when it binds. It does not for this module: it still
   loads through the legacy `assets/xposed_init`, and the service is only offered to
   modern-API modules.
2. **LSPosed's own database** (`/data/adb/lspd/config/modules_config.db`, tables
   `modules` and `scope`), copied into the app cache with `su` and queried there.
   This is the real scope, including packages with no launcher activity and apps
   that are not on any recommended list. Read when the UI opens and on "Refresh",
   never when a hooked app merely wakes the module process.
3. Fallback: the recommended packages that are installed (`xposed_scope`).

Entries the package manager cannot resolve are still listed by package name, and
`QUERY_ALL_PACKAGES` is declared so non-launcher packages resolve.

## System framework

LSPosed offers "System Framework" (`system` / `android`) in a scope. The module
never injects into it (`MainHook.BLOCKED`), because `AudioTrack` lives in each app's
own process: ticking the framework would only hook `system_server`, not the music
apps. The row is shown for information with no switch. It used to be a switch that
could not stick, since the whitelist rejects names without a dot, so it flipped back
whenever the page was reopened. `android` was also removed from the recommended
scope list, whose comment claimed it hooked every AudioTrack user.

## Writes that fail are reported

Every whitelist write now returns success. When the root write fails the switch is
put back and a message is shown, instead of staying on until the page is reopened.

## Phira

Detected by package id `org.flos.phira` or any package name containing "phira"; its
chart folder is taken from the game's own files directory instead of a fixed
package id. It appears in the list once it is in the LSPosed scope.
