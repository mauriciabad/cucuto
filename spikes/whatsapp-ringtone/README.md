# Cucuto WhatsApp ringtone spike

Throwaway project for issue #215. It answers how WhatsApp plays its incoming-call ringtone on the owner's phone and
tests the least intrusive way to swap it; it is not the production module. Findings, design and the test plan live in
[`docs/whatsapp-ringtone-spike.md`](../../docs/whatsapp-ringtone-spike.md), the decision in
[ADR 0052](../../docs/decisions/0052-whatsapp-call-ringtones.md).

**Status: builds, not installed.** The static phase only read the device. The supervised run is a separate
`needs-device` issue, and it needs the owner's go-ahead (it adds an LSPosed module scoped to WhatsApp).

## What it contains

One debug APK, `app.mauri.cucuto.spike.waring`, scope **`com.whatsapp` only** (`staticScope=true`):

- **`xposed/WaRingtoneSpikeModule`**: in WhatsApp's process, hooks framework methods only:
  - `RingtoneManager.getRingtone(Context, Uri)`: logs every call (URI shape, thread, whether WhatsApp's
    incoming-call notification is already posted). In `swap` mode it replaces the URI for 1:1 calls from the test
    contact, and falls back to WhatsApp's URI when the file cannot be opened.
  - `Ringtone.play()` and `Ringtone.stop()`: observers for timing (ringtone usage only); they change nothing.
- **`xposed/CallNotificationReader`**: finds WhatsApp's own incoming-call notification with the public
  `NotificationManager.getActiveNotifications()` and reads only the caller's display name, its age and WhatsApp's
  `isGroupCall` flag. Message notifications are skipped before any extra is read.
- **`xposed/SwapDecision`**: the pure swap rule (unit-tested). Every doubt keeps WhatsApp's own ringtone.
- **`app/ConfigReceiver`**: adb-only (`android.permission.DUMP`) settings in libxposed Remote Preferences: mode
  (`off` by default, `observe`, `swap`), the test contact's display name, the ring URI.

## Safety

- Mode `off` (the default) installs no hook at all.
- Every hook body is in `try/catch(Throwable)` and registered with `ExceptionMode.PROTECTIVE`; methods are resolved by
  exact signature, a miss is logged and skipped.
- The swap only replaces the URI WhatsApp already decided to play, after its own silent, vibrate, DND and per-chat
  checks. Group calls (bundled `android.resource` tones) and every other caller are untouched.
- Logs never contain a name, a number, a JID or a full URI (`Redact`).
- WaEnhancer X hooks none of these methods (spike report, section 7).

## Build

```
export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Needs `local.properties` with `sdk.dir` (gitignored).

## Device session (owner present, second phone calling)

The full plan, pass criteria and recovery are in the spike report, section 10. In short:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk        # installs, does NOT enable
#   LSPosed manager: enable "Cucuto WhatsApp ringtone spike", scope = WhatsApp only. No reboot needed for an app
#   scope; WhatsApp must restart: adb shell am force-stop com.whatsapp
adb shell am broadcast -n app.mauri.cucuto.spike.waring/.app.ConfigReceiver --es mode observe
adb shell am force-stop com.whatsapp
adb logcat -s cucuto-waring                                     # then call from the second phone
adb shell am broadcast -n app.mauri.cucuto.spike.waring/.app.ConfigReceiver \
    --es mode swap --es test_name 'Cucuto Test' --es ring_uri content://media/external/audio/media/<id>
# Recovery: --es mode off, untick the module in LSPosed, adb uninstall app.mauri.cucuto.spike.waring,
# adb shell am force-stop com.whatsapp
```
