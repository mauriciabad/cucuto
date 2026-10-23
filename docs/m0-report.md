# M0 device spike — report

Milestone M0 is the go/no-go gate for cucuto's root-first design (issues #9–#12).

**Status: provisional GO.** Every question M0 can answer without executing code on the owner's
phone is answered, and all of them came back favourable: the hook points exist on this exact
build, the libxposed API level matches, the haptics API the brief wants is present, and the
process that will open our clip URI is identified. What remains is the *confirmation* run on the
device, which is deliberately not done here.

**Why the device phase is not done.** The owner is away and unreachable, and the device is their
daily-driver phone. Loading a new hook into `system_server` risks a boot loop, and the person who
could notice a bad state or physically recover the phone is by construction absent. The device
work is therefore packaged as a short supervised session: see issue #13 and
`spikes/m0/run-on-device.main.kts`. Everything below marked **pending-device** is a prediction
with its evidence, not a result — do not treat it as verified.

Read alongside [`docs/device.md`](device.md) for the device facts.

---

## 1. Verdicts

| # | Check | Verdict | Basis |
| --- | --- | --- | --- |
| 9 | Record device model, ROM, root manager, LSPosed version, libxposed API level | **GO** | [`docs/device.md`](device.md); LSPosed 2.2.1 (7912), **API 102** |
| 9 | Spike module project builds (Kotlin, libxposed 102 `compileOnly`, scope `android`) | **GO** | `spikes/m0/` builds a 2.5 MB debug APK with correct `META-INF/xposed/*` |
| 9 | Module loads in `system_server` and logs | **pending-device** | framework reports `systemServerRequested: true`; entry point is `onSystemServerStarting` |
| 10 | Alerting class/method exist on this build | **GO** | `NotificationAttentionHelper.buzzBeepBlinkLocked` found in `services.jar` and decompiled |
| 10 | Clean hook points for sound and vibration identified | **GO** | `playSound` / `playVibration`, see §2 |
| 10 | Contact-key strategy | **GO (design settled)**, values **pending-device** | shortcut ID carries the JID; see §4 |
| 10 | Which record alerts for a group (child vs summary) | **GO (mechanism known)**, observation **pending-device** | decided by `suppressAlertingDueToGrouping()`, see §3 |
| 11 | Sound swap to our provider | **pending-device** | mechanism verified statically, see §2 and §5 |
| 11 | Which UID/package opens the clip URI | **GO (identified)**, confirmation **pending-device** | SystemUI, `android.uid.systemui`, uid 10253 here — see §5 |
| 11 | `VibrationEffect.Builder` exists on this build | **GO** | present in the device's `framework.jar`; see §6 |
| 12 | DND / ringer / burst / alert-once parity | **GO by construction**, confirmation **pending-device** | we never decide whether to alert; see §3 |
| 12 | WaEnhancer X keeps working | **GO by construction**, confirmation **pending-device** | disjoint scope; see §7 |

No check came back negative. Nothing found so far argues against the root-first design.

---

## 2. Hook points and signatures

All in `services.jar` → `classes2.dex` on `CP3A.261005.005`. Signatures are as decompiled from the
device (jadx 1.5.6); the raw decompile is in the gitignored `private/framework/`.

### Class `com.android.server.notification.NotificationAttentionHelper`

```java
// Entry to the alerting stage. Returns a bitmask of what happened.
int buzzBeepBlinkLocked(NotificationRecord record,
                        NotificationAttentionHelper.Signals signals)

// Plays the sound. Called only once the framework has decided this record should sound.
boolean playSound(NotificationRecord record, Uri uri)

// Plays the vibration. `delayed` is true when a sound is also playing (focus ramp).
boolean playVibration(NotificationRecord record, VibrationEffect effect, boolean delayed)

// Final hand-off to VibratorHelper.
void vibrate(NotificationRecord record, VibrationEffect effect, boolean delayed)
```

`Signals` is the package-private nested class
`com.android.server.notification.NotificationAttentionHelper$Signals`.

> jadx renders several of these as `public final`; in AOSP source they are private or
> package-private. Access modifiers are irrelevant to `getDeclaredMethod` + Xposed, but do not
> rely on them being public.

### Class `com.android.server.notification.NotificationRecord`

```java
public Uri getSound()                 // returns the field mSound
public VibrationEffect getVibration() // returns the field mVibration
public NotificationChannel getChannel()
public StatusBarNotification getSbn()
public ShortcutInfo getShortcutInfo()
public boolean isConversation()
```

`mSound` and `mVibration` are **computed once in the constructor** by `calculateSound()` and
`calculateVibration()`, then stored as fields. `buzzBeepBlinkLocked` reads each getter once into a
local at the top of the method.

### Chosen hook points

**Hook `playSound` and `playVibration`, replacing argument 1. Do not hook the getters, and do not
reimplement `buzzBeepBlinkLocked`.**

The decisive code in `buzzBeepBlinkLocked` is:

```java
Uri sound = record.getSound();
VibrationEffect vibration = record.getVibration();
...
int mute = shouldMuteNotificationLocked(record, signals, hasSound || hasVibration);
if (mute == 0) {
    ...
    if (hasSound && !cooldownVibrate) {
        soundPlayed = isInCall() ? playInCallNotification() : playSound(record, sound);
    }
    if (!isInCall() && hasVibration && ringerMode != RINGER_MODE_SILENT) {
        vibrated = playVibration(record, vibration, hasSound && !cooldownVibrate);
    }
}
```

Why these two and not the alternatives:

- **`playSound`/`playVibration` are reached only after every suppression rule has already run.**
  Importance, current-user, DND interception, group alert suppression, alert-once, silent posts,
  rate limiting, insistent-in-progress and the politeness cooldown have all been evaluated. If the
  framework decided not to alert, our code is never called and WhatsApp's behaviour is unchanged.
  This is the whole safety argument for the design, and it is free.
- **We replace an argument, never a decision.** The hook body is "if this is one of our packages,
  substitute arg 1". It cannot cause an alert that would not have happened, and it cannot suppress
  one.
- **Volume and intensity scaling still apply after us.** `playSound` passes
  `getSoundVolume(record)` to the ringtone player and `playVibration` applies
  `getVibrationIntensity(record)` — both come from the notification-cooldown politeness strategy.
  Swapping the payload keeps the user's cooldown behaviour intact.
- **Hooking `NotificationRecord.getSound()` would be wider than we need.** It is called from other
  places (including `vibrate()`'s stats call
  `logCustomVibrationPatternEventIfNeeded(2, 2, record.getSound())`), so a hook there changes
  values we have no business changing.
- **Hooking `buzzBeepBlinkLocked` and mutating the record's fields** would mean restoring them
  afterwards and racing the delayed-vibration thread (see §3). Worse, it would put us upstream of
  the mute logic.

`buzzBeepBlinkLocked` is still hooked by the spike, but **read-only**, purely to log every record
that reaches the alerting stage (issue #9). It always calls `chain.proceed()` with the original
arguments.

### Resolution strategy

Methods are resolved by **name + parameter count**, not exact signature, because `Signals` is a
package-private nested type whose shape changes between QPRs. A miss logs a warning and no-ops;
an ambiguous match is skipped rather than guessed. This is the mitigation the brief asks for
against "hook point differs on a later QPR".

---

## 3. Behaviour parity (issue #12)

Parity is **structural**, not something we have to re-implement. `shouldMuteNotificationLocked`
runs before our hook points and returns a non-zero reason code to suppress alerting:

| Reason | Condition in `shouldMuteNotificationLocked` |
| --- | --- |
| 32 | record has neither sound nor vibration |
| 64 | update to a notification with `FLAG_ONLY_ALERT_ONCE` |
| 128 | `record.shouldPostSilently()` |
| 256 | effects disabled by listener hints / DND effect suppression |
| 512 | `record.isIntercepted()` — DND / zen intercepted it |
| 1024 | `sbn.isGroup() && notification.suppressAlertingDueToGrouping()` |
| 2048 | `notification.isSilent()` |
| 4096 | `mUsageStats.isAlertRateLimited(pkg)` — burst limiter |
| 8192 | another insistent notification is currently playing |
| 16384 | bubble-suppressed update |
| 32768 | notification-cooldown politeness muted it |

Consequences for cucuto:

- **Muted chats, DND and silent mode** are handled at 256/512 — our hook never runs.
- **Ringer silent** is checked separately in `buzzBeepBlinkLocked` before `playVibration`.
- **Quick bursts** are handled at 4096 by the existing alert rate limiter, plus the politeness
  cooldown at 32768. The brief's worry about clips cutting each other off is already mitigated by
  AOSP.
- **`setOnlyAlertOnce` re-posts** are handled at 64.
- **Group child vs summary** is decided at 1024 via `Notification.suppressAlertingDueToGrouping()`,
  which implements the `GROUP_ALERT_*` contract. **Whichever record survives to `playSound` is the
  one that alerts**, so cucuto reads the sender off exactly that record and never has to guess.
  Whether WhatsApp's groups alert on the child or the summary in practice is the one thing here
  that still needs observing on-device.

One detail worth carrying into M1: when a sound is also playing, `playVibration` **starts a new
`Thread`**, sleeps for the audio focus ramp, re-checks `mVibrateNotificationKey` and only then
vibrates. Our substituted `VibrationEffect` is captured before that sleep, so swapping the
argument is safe — but anything stateful we attach to a record must survive that delay.

---

## 4. Contact key strategy

**Primary key: the notification's shortcut ID.**

WhatsApp posts conversation notifications with a shortcut ID set to the chat JID, and
`NotificationRecord.isConversation()` on this build requires MessagingStyle plus a shortcut for
any app targeting SDK 30+ — which WhatsApp does. So for every notification cucuto cares about, a
shortcut ID is present. It is readable two ways, and the spike logs both so they can be compared:

- `record.getSbn().getNotification().getShortcutId()`
- `record.getShortcutInfo()?.id` (the resolved `ShortcutInfo`)

**Fallbacks, in order**, all captured by the spike's `RecordReader`:

1. `MessagingStyle.getMessages().last().getSenderPerson()` → `Person.getKey()`, then
   `Person.getUri()`. For a group this is the *sender*, which is what a per-sender voice needs;
   the shortcut ID is the *chat*.
2. `Notification.EXTRA_PEOPLE_LIST`.
3. `MessagingStyle.getConversationTitle()` — a display name, weakest and locale-dependent; useful
   only as a last-resort label, never as a stored key.

**Store every key seen, match on any.** The brief already calls for this because of the phone-JID
to `@lid` migration, and nothing found here changes that. The spike's debug app can post both a
`@s.whatsapp.net` and an `@lid` identity so the production matcher can be tested against both
before M2.

For a group, the design needs both axes: `shortcutId` (`…@g.us`) identifies the *chat* and the
message `Person` identifies the *sender*. That is exactly the data needed to answer open question
3 in the brief ("one character per group, or per sender?") — the plumbing supports either.

> Privacy: the spike never logs a JID, a contact name or message text. Identifiers are emitted as
> a salted, truncated digest with the JID *domain* preserved (`<jid:a1b2c3@s.whatsapp.net>`), so
> the `@lid`-versus-phone-JID question can be answered from the log without the log containing a
> phone number. The salt is random per process. See `spikes/m0/.../Redact.kt`.

---

## 5. The clip provider and its caller

`playSound` does:

```java
long token = Binder.clearCallingIdentity();
IRingtonePlayer player = mAudioManager.getRingtonePlayer();
player.playAsync(uri, record.getSbn().getUser(), looping, record.getAudioAttributes(),
                 getSoundVolume(record));
```

`IRingtonePlayer` is hosted by **SystemUI**, so SystemUI — not `system_server` — is the process
that opens `content://app.mauri.cucuto.clips/...`.

| | |
| --- | --- |
| Opening process | `com.android.systemui` |
| Shared user | `android.uid.systemui` |
| UID on this device | **10253** |

**The numeric UID is install-time assigned and not portable.** A production allowlist must match
the calling *package* (`com.android.systemui`, and `android` as a belt-and-braces second entry),
resolved via `getCallingPackage()` / `getCallingPackageUnchecked()`, never the integer. This is
recorded here because it is exactly the kind of thing that works on one phone and silently fails
on another.

The spike's `ClipProvider` logs `callingUid`, `callingPid` and `callingPackage` on every
`openFile`, with enforcement **off** (`ENFORCE_CALLER = false`) so that a mismatch shows up as a
wrong-caller log line rather than as silence. Production must invert this.

The clip itself is synthesised at runtime as a PCM WAV in the app's cache — no audio file is
committed, which keeps the media-licence guard out of the picture entirely.

---

## 6. Haptics: `VibrationEffect.Builder` is available

Confirmed present in the device's `framework.jar`:

```
android/os/VibrationEffect$Builder
android/os/VibrationEffect$Preset
android/os/VibrationEffect$Envelope
android/os/VibrationEffect$BasicEnvelopeBuilder
android/os/VibrationEffect$WaveformEnvelopeBuilder
android/os/VibrationEffect$Composition
```

The API maps onto the brief's `pack.json` haptic schema one-to-one:

```java
new VibrationEffect.Builder()
    .addPreset(long startTimeMillis, VibrationEffect.Preset)   // {"at":0,"preset":"CLICK","scale":0.8}
    .addEnvelope(long startTimeMillis, VibrationEffect.Envelope)
    .setRepeatingEffect(long, VibrationEffect, long)
    .build();

VibrationEffect.Preset.create(int presetId, float scale);      // scale must be 0..1
VibrationEffect.Envelope.create(BasicEnvelopeBuilder);
VibrationEffect.Envelope.create(WaveformEnvelopeBuilder);

new VibrationEffect.BasicEnvelopeBuilder()
    .setInitialSharpness(float)
    .addControlPoint(float intensity, float sharpness, long durationMillis)  // must end at 0
    .build();
```

**No fallback is needed at runtime on this build.** The spike still probes reflectively and keeps
`BasicEnvelopeBuilder` and `createWaveform` as rungs 2 and 3, which costs nothing and is the right
shape for other devices.

**One build-config consequence:** `VibrationEffect.Builder` is in the **`android-37.2`** SDK
platform but **not in `android-37.0`**, which is the platform `CLAUDE.md` names. To reference the
Builder at compile time, `android/` needs `compileSdkMinor = 2`. The alternative is to keep using
reflection, which the spike does. Recommendation in §8.

---

## 7. Coexistence with WaEnhancer X

Structurally disjoint, and now enforced rather than merely intended:

- cucuto's `META-INF/xposed/scope.list` contains only `android`, and `module.prop` sets
  `staticScope=true`, which tells the framework the scope is exhaustive. The LSPosed manager
  should therefore not even *offer* WhatsApp as a target. (If it does, that is a finding — the
  owner-action checklist asks for it to be reported.)
- WaEnhancer hooks `com.whatsapp`; cucuto hooks `android`. Different processes, no shared hook
  sites, no ordering interaction.
- Both run on the same framework at API 102, and WaEnhancer already proves that level works on
  this phone.

Still **pending-device**: that WhatsApp and WaEnhancer come up healthy after a boot with our
module enabled. That is the single most important thing to watch in the supervised session.

---

## 8. Risks found, and recommendations for the production `:xposed` module (M1)

### Safety

1. **Use `ExceptionMode.PROTECTIVE` on every hook.** libxposed 102 has a framework-level net:
   *"any exception thrown by the hooker will be caught and logged, and the call will proceed as if
   no hook exists."* This is a genuinely new mitigation for the brief's top risk and the spike sets
   it explicitly on all three hooks. Keep the manual `try/catch(Throwable)` anyway — the two guard
   different things, and neither is expensive.
2. **Never throw, never decide.** The hook body does argument substitution only.
3. **Resolve by name + arity, degrade to no-op**, and surface "hook not active" in the app's status
   screen as the brief plans.
4. **Ship the recovery ladder with the module**, not just in an agent's head. It is in
   `spikes/m0/run-on-device.main.kts recover` and reproduced in issue #13.

### Correctness

5. **Allowlist the clip provider by package, not UID** (§5).
6. **Key contacts on the shortcut ID, store every key ever seen** (§4).
7. **Read the sender off the record that actually reaches `playSound`** — do not try to predict
   child vs summary (§3).
8. **Account for the delayed-vibration thread** in `playVibration` if M1 ever attaches per-record
   state (§3).

### Build

9. **Decide the `compileSdk` question** (§6): either set `compileSdkMinor = 2` in `android/` and
   link `VibrationEffect.Builder` directly, or keep a thin reflective wrapper. Recommendation:
   **set `compileSdkMinor = 2`** for the app/core modules so the haptic editor (M4) gets type
   safety, and keep the `:xposed` module reflective, because a `NoClassDefFoundError` inside
   `system_server` is in a different risk class from one inside the app.
10. **AGP 9 has built-in Kotlin support** and *rejects* the `org.jetbrains.kotlin.android` plugin.
    `CLAUDE.md`'s build notes and `android/gradle/libs.versions.toml` should reflect this.
11. **`CLAUDE.md` says platform `android-37.0`**; `android-37.2` is installed and is what the
    haptics work needs. Worth updating.

### Open, needs the device

12. Does WhatsApp's group chat alert on the **child** or the **summary**?
13. Is the shortcut ID a phone JID, an `@lid`, or both depending on the chat?
14. Does SystemUI actually open our provider, and under which package string?

---

## 9. Device state

**The device was not modified.** Everything done for this report was read-only:

- `getprop`, `uname`, `pm list packages`, `dumpsys package <pkg> | grep versionName`
- `su -c 'ls /data/adb/'`, `su -c 'lspctl --json status'`, `su -c 'cat <lspd config db>'`
- `adb pull /system/framework/services.jar`, `adb pull /system/framework/framework.jar`
  (Google's code, not owner data; kept in the gitignored `private/`)

No module was installed, enabled or scoped. No reboot was performed. No notification dump was
taken — `dumpsys notification` was deliberately **not** run in any form, redacted or otherwise, so
no message content or contact ever entered this workspace. The WhatsApp notification shapes in §4
come from the framework's own conversation rules and from the spike's imitation notifications, not
from the owner's messages.

One temporary file was created and removed during the session: an attempted copy of the LSPosed
config DB at `/data/adb/.../mc.db` → `/data/local/tmp/mc.db`, which failed on `chmod` (SELinux)
and was deleted; the DB was then read with `adb exec-out su -c cat` instead. Verified gone.

`spikes/m0/run-on-device.main.kts preflight` was run against the live device and is read-only; its
output is reproduced in §1 and `docs/device.md`.

---

## 10. What the supervised session must confirm

Tracked as issue #13 (`owner-action` + `needs-device`). In short:

```
cd spikes/m0
kotlin run-on-device.main.kts preflight          # read-only
kotlin run-on-device.main.kts install --yes      # installs, does NOT enable
#  -> enable in LSPosed, scope = System Framework (android) ONLY
#  -> reboot
kotlin run-on-device.main.kts verify             # read-only
kotlin run-on-device.main.kts matrix --yes       # drives #11 / #12 over adb
kotlin run-on-device.main.kts collect            # logs -> private/ (gitignored)
kotlin run-on-device.main.kts recover --yes      # uninstall; leaves the phone clean
```

Then three checks that only a real WhatsApp message can answer (a 1:1, a group, a muted chat), with
exact steps in the issue.

**Leave the module uninstalled at the end** unless the session is going straight on into M1.
