# Cucuto M9 spike: Telecom ringer and WhatsApp call ringtones

Throwaway project for issue #50. It answers questions about the owner's phone for M9 (ringtones); it is not
the production module. Findings and the test plan live in [`docs/m9-report.md`](../../docs/m9-report.md), the
decision in [ADR 0023](../../docs/decisions/0023-call-ringtones-native-first.md).

**Status: builds, not installed.** The static phase (this PR) only reads the device. The supervised run is #143, a
separate `needs-device` issue.

## What it contains

One debug APK, `app.mauri.cucuto.spike.m9`:

- **`xposed/M9SpikeModule`**: scope System Framework (`system`) only, `staticScope=true`.
  - Observes `Ringer.startRinging(Call, boolean)` and `RingtoneFactory.getRingtone(Call, VolumeShaper.Configuration, boolean)`
    for every ringing call (SIM or self-managed).
  - With `debug.cucuto.m9.swap=1` only: replaces the return value of `Call.getRingtone()` (the contact's
    `CUSTOM_RINGTONE`) and the `VibrationEffect` argument of `Ringer.vibrateIfNeeded(boolean, Call, VibrationEffect, Uri)`
    for incoming, non-self-managed calls.
  - Observes (read-only) WhatsApp records on a call channel at `NotificationAttentionHelper.buzzBeepBlinkLocked`.
- **`xposed/CallReader`, `xposed/Redact`**: fail-soft reflection on Telecom's `Call`, and the privacy guard.
- **`app/RingProvider`**: `content://app.mauri.cucuto.spike.m9.ring/ring/spike_ring.wav`, a synthesised 3 s
  "ring-ring" (no audio file committed). Logs every caller and its uid.

The hooks are disjoint from the production module's (`playSound`, `playVibration`), so the two can be enabled
together, but the supervised run enables only one experimental module at a time.

## Safety

Same three guards as M0: every hook body in `try/catch(Throwable)`, every hook registered with
`ExceptionMode.PROTECTIVE`, and hooks only substitute a value of a call Telecom already decided to make (DND,
ringer mode, volume, vibrate settings, ramping and fallback stay Telecom's). With the debug property unset the
module only observes. Logs never contain a number, a name or a JID.

## Build

```
export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug
```

Needs `local.properties` with `sdk.dir` (gitignored).

## Device session (owner present)

```
kotlin run-on-device.main.kts preflight            # read-only
kotlin run-on-device.main.kts recover              # read the ladder first
kotlin run-on-device.main.kts install --yes        # installs, does NOT enable
#   LSPosed manager: enable "Cucuto M9 spike", scope = System Framework ONLY, reboot
kotlin run-on-device.main.kts verify               # read-only
#   ... test plan in docs/m9-report.md §7 ...
kotlin run-on-device.main.kts collect              # redacted log -> private/m9/ (gitignored)
kotlin run-on-device.main.kts recover --yes        # swap off, uninstall
```
