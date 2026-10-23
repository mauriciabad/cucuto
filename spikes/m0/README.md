# Cucuto M0 device spike

Throwaway project for milestone M0 (issues #9–#12). It exists to answer questions about the
owner's phone, not to become the production module — `:xposed` in `android/` is written fresh in
M1 using what is learned here. Findings live in [`docs/m0-report.md`](../../docs/m0-report.md) and
[`docs/device.md`](../../docs/device.md).

## What it contains

One debug APK (`app.mauri.cucuto.spike`) with two halves, mirroring the production architecture:

- **`xposed/`** — the module that runs in `system_server`, scoped to `android` only.
  - `SpikeModule` — entry point; hooks three methods on `NotificationAttentionHelper`.
  - `RecordReader` — fail-soft reflective reader for `NotificationRecord`.
  - `Redact` — the privacy guard; nothing reaches logcat unredacted.
  - `SpikeHaptics` — reflective `VibrationEffect.Builder` probe with two fallbacks.
- **`debugapp/`** — a debug app that posts WhatsApp-shaped notifications so the alerting path can
  be exercised without a real message.
  - `DebugActivity` — buttons and a live readout.
  - `SpikeReceiver` — the same actions over adb, so the device session needs no tapping.
  - `ClipProvider` — read-only provider at `content://app.mauri.cucuto.spike.clips/clip/<name>`
    that logs every caller and synthesises its WAV at runtime (no audio file is committed).

## Safety

This module runs inside `system_server` on someone's daily phone. Three independent guards:

1. Every hook body is wrapped in a `try/catch(Throwable)` that swallows and logs.
2. Every hook is registered with libxposed's `ExceptionMode.PROTECTIVE`, so the framework itself
   catches anything that escapes and proceeds as if the hook were absent.
3. Hooks only ever *substitute an argument* of a playback call the framework had already decided
   to make. They never decide whether to alert, so DND, muted chats, ringer mode, the burst
   limiter and alert-once keep working exactly as AOSP implements them.

`META-INF/xposed/scope.list` contains only `android`, and `module.prop` sets `staticScope=true`,
so the framework should refuse to let the module be scoped to WhatsApp at all.

**Read the recovery ladder before enabling anything:**

```
kotlin run-on-device.main.kts recover
```

## Build

```
export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug
```

Needs `local.properties` with `sdk.dir` (gitignored; the script writes one if missing).

## Device session

Run with the phone in your hand. Read-only phases are safe to run any time; every phase that
changes the device refuses without `--yes`.

```
kotlin run-on-device.main.kts preflight       # read-only
kotlin run-on-device.main.kts install --yes   # installs; does NOT enable
#   enable in the LSPosed manager, scope = System Framework (android) ONLY
#   reboot
kotlin run-on-device.main.kts verify          # read-only
kotlin run-on-device.main.kts matrix --yes    # drives the #11/#12 matrix
kotlin run-on-device.main.kts collect         # logs -> private/ (gitignored)
kotlin run-on-device.main.kts recover --yes   # uninstall, leave the phone clean
```

## Privacy

Notification records on this phone carry real contacts and real message text. The module never
logs a name, a number, a JID or message content: identifiers become a salted, truncated digest
that keeps only the JID *domain* (`<jid:a1b2c3@s.whatsapp.net>`), which is enough to answer the
`@lid`-versus-phone-JID question without the log containing a phone number. The salt is random per
process.

Anything pulled off the device goes to `private/`, which is gitignored. Never commit it, never
paste it into an issue or a PR.
