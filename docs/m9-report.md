# M9 spike: Telecom ringer and WhatsApp call ringtones

Issue #50. Ringtones are part of v1 (owner, #59). This report covers the **static phase**: Android 17 sources plus
the framework pulled read-only from the owner's phone. Nothing was installed and LSPosed was not touched. The
supervised device run is #143. Decision: [ADR 0023](decisions/0023-call-ringtones-native-first.md).

Device: Pixel 10 Pro, `CP3A.261005.005` (SDK 37.1), LSPosed 2.2.1 (libxposed API 102); see [`device.md`](device.md).
Code read: `service-telecom.jar` and `framework-telecom.jar` from the `com.android.telephonycore` APEX,
`framework.jar` (`android.media.Ringtone`, `RingtoneManager`, `MediaPlayer`, `Utils`), the M0 decompile of
`NotificationAttentionHelper`, and AOSP `main` (`packages/services/Telecomm`) for comments and intent. Where AOSP
`main` and the device differ, the device wins and this report quotes the device. The decompiled sources sit in the
gitignored scratch area, not in the repo.

---

## 1. Verdicts

| Call type | Mechanism | Verdict |
| --- | --- | --- |
| Phone call, fixed or rule-based variant | Native `CUSTOM_RINGTONE` (Telecom plays it) | **GO, no hook.** Telecom reads the contact's `custom_ringtone` and plays it in `system_server` (§3). |
| Phone call, per-contact vibration | Native `vibration_uri` parameter on the ringtone URI | **Provisional GO, no hook.** Supported and enabled on this Pixel (§3.4); the device run must find a file path `system_server` can read. Fallback: hook `Ringer.vibrateIfNeeded` (§4). |
| Phone call, random per call | Re-roll `CUSTOM_RINGTONE` after each call (no hook) | **GO, no hook** (default, #144). Optional hook `Call.getRingtone()` is a **provisional GO** pending the device run (§4). |
| WhatsApp call, per contact | WhatsApp rings itself, in its own process | **NO-GO** for automatic ringtones without hooking WhatsApp (§5). |
| WhatsApp call, fallback | Export to `Ringtones/cucuto/`, guide the user to WhatsApp's per-chat call ringtone | **GO, no hook** (one file per chat, no variants; #145). |

Nothing here needs a second LSPosed scope: Telecom runs inside `system_server` (§2).

---

## 2. Where Telecom runs, and the scope

**Telecom is inside `system_server` on this build. `system` stays the only scope.**

| Fact | Evidence (read-only) |
| --- | --- |
| Telecom's service code is `service-telecom.jar` in the `com.android.telephonycore` APEX (`/apex/com.android.telephonycore/javalib/`). | `find` over `/apex`; `services.jar` contains no `com.android.server.telecom` class. |
| It is on **`SYSTEMSERVERCLASSPATH`**, not in `STANDALONE_SYSTEMSERVER_JARS`. | `echo $SYSTEMSERVERCLASSPATH` on the device. |
| So it is loaded by the system server class loader, the one libxposed passes to `onSystemServerStarting`. | Standalone jars get their own loader; classpath jars do not. Confirm in the device run (the spike logs `telecom classes: Ringer=true …`). |
| It is mapped into `system_server`'s memory. | `/proc/<system_server>/maps` lists `…telephonycore@javalib@service-telecom.jar@classes.art`. |
| The package `com.android.server.telecom` is now `TelecomShim.apk` (manifest only): `sharedUserId=android.uid.system`, `android:process="system"`. | `aapt2 dump xmltree`; `ps -A` shows no Telecom process. |
| Telecom's UI pieces are separate APEX apps (`com.google.android.telecomui`, `com.google.android.telecomresources`), not on the ringing path. | `pm list packages -f`. |

The brief's "Telecom runs in the system process" holds. Hooks on `com.android.server.telecom.*` load in the
same process and through the same entry point as the M1 hooks, with the same `scope.list` (`system`) and
`staticScope=true` (ADR 0002). WhatsApp's process is never involved.

---

## 3. Phone calls: how the ringtone is chosen and played

All signatures are from the device's `service-telecom.jar`. The names are not obfuscated, but the
`startRinging` body is mostly lambdas (`lambda$startRinging$N`), which must never be hook targets.

### 3.1 The path

```
CallAudioManager → Ringer.startRinging(Call foregroundCall, boolean isHfpDeviceAttached)
  ├─ getRingerAttributes(call)               (runs as lambda$startRinging$0 on "Ringer-Executor")
  │    isRingerAudible   = ringVolume > 0 && shouldRingForContact(call)    // DND via DndCallFilter / matchesCallFilter
  │    endEarly          = letDialerHandleRinging || isSelfManaged || hasExternalRinger
  │                        || isSilentRingingRequested || workProfileQuiet
  ├─ endEarly → return (Telecom plays nothing)
  ├─ isVibratorEnabled(ctx, shouldRingForContact)    // ringer mode, "vibrate for calls", DND
  ├─ ringtone supplier → RingtoneFactory.getRingtone(Call, VolumeShaper.Configuration, boolean hapticChannelsMuted)
  │    uri = call.getRingtone()                       // the contact's CUSTOM_RINGTONE, or null
  │    → RingtoneManager.getRingtone(userContext, uri, shaper, USAGE_NOTIFICATION_RINGTONE attrs)
  │    → falls back to the (per-phone-account) default ringtone when uri is null or cannot be opened
  ├─ AsyncRingtonePlayer.play(supplier, afterRingtone, hfp)   // own single-thread executor
  │    ringtone.setLooping(true); ringtone.play()
  └─ afterRingtone → Ringer.vibrateIfNeeded(boolean isUsingAudioCoupledHaptics, Call, VibrationEffect, Uri ringtoneUri)
       skips when audio-coupled haptics play, or when the ringtone URI carries vibration_uri
       else mVibrator.vibrate(effect, VibrationAttributes(USAGE_RINGTONE))
```

### 3.2 Where `CUSTOM_RINGTONE` comes from

`Call.getRingtone()` returns `mCallerInfo.contactRingtoneUri`. `CallerInfo` fills it from the PhoneLookup row's
`custom_ringtone` column (`Uri.EMPTY` for "silent", `null` when unset). Telecom waits for the contact lookup before
ringing (AOSP comment: "the information about custom ringtones should be available by the time this code
executes"). On this build, `Call.getRingtone()` has **exactly two callers**: `RingtoneFactory.getRingtone` and
`Ringer.hasExplicitVibration`.

`RingtoneFactory.currentUserOwnsRingtone` accepts any URI without a user-info part, so a plain
`content://media/external/audio/media/<id>` is fine.

### 3.3 Who opens the file

`RingtoneManager.getRingtone(ctx, uri, shaper, attrs)` creates `Ringtone(ctx, allowRemote = true)` and calls
`createLocalMediaPlayer()`: a **local `MediaPlayer` inside `system_server`** (uid 1000) opens the URI. Only if that
throws (`IOException`/`SecurityException`) does playback fall back to SystemUI's `IRingtonePlayer`. So, unlike
notification sounds (SystemUI opens them, M0 §5), a ringtone URI is opened by **uid 1000**:

- MediaStore URIs under `Ringtones/cucuto/` (the #52 plan) open through MediaProvider, as for any ringtone.
- An app-provider URI (`content://app.mauri.cucuto.clips/…`) would be opened by uid 1000, which ADR 0010 already
  allows. But a cold start of Cucuto's process would delay the first ring (the M1 cold-start finding, #121), so
  ringtones should stay on MediaStore and never go through our provider.

### 3.4 Native per-contact vibration: `vibration_uri`

Android 17's ringtone haptics customisation is **present and enabled on this Pixel**:

| Check | Value |
| --- | --- |
| `android.media.audio.enable_ringtone_haptics_customization` | enabled (read-only flag) |
| `android.media.audio.ringtone_vibration_utils_api` | enabled |
| `config_ringtoneVibrationSettingsSupported` | `false` in `framework-res`, **`true` after the Pixel overlay** (`cmd overlay lookup`) |
| ROM vibration files | `/product/media/vibration/ringtone/*.xml` (`system_file`, 0644), Android's vibration XML format |

How it works (`android.media.Ringtone`, `android.media.Utils`, `Ringer`): if the ringtone URI has a query parameter
`vibration_uri=<path>`, `Ringtone.setUri` parses that file with `VibrationXmlParser`, makes it repeat
(`applyRepeatingIndefinitely(true, 200)`) and plays it with `USAGE_RINGTONE` alongside the audio; Telecom's
`useCustomVibration` path loads the ringtone even in vibrate mode (haptic-only), and `vibrateIfNeeded` skips its
default pattern ("using custom haptics"). `vibration_uri=synchronized` means audio-coupled haptics.

So a contact's `CUSTOM_RINGTONE` can be `content://media/external/audio/media/<id>?vibration_uri=<file path>` and
Telecom plays a per-contact vibration **with no hook**. The constraint: `parseVibrationEffect` reads a
**filesystem path** with `new File(uri.getPath())`, in `system_server`. A ROM file works; whether `system_server`
can read a file Cucuto writes (shared storage `Ringtones/cucuto/`, or app storage) depends on DAC and SELinux
and is test V2 in §7. Pack haptics (presets, envelopes, ADR 0012) would be written as vibration XML
(`<vibration-effect>` with `<primitive-effect>`, `<predefined-effect>` or `<waveform-effect>`), which
`VibrationXmlParser` resolves against the device's capabilities.

### 3.5 Whether Telecom rings at all

Telecom skips ringing when the connected default dialer declares `android.telecom.IN_CALL_SERVICE_RINGING=true`.
The owner's default dialer (`com.google.android.dialer`, role holder) declares it **`false`**, so Telecom's `Ringer`
plays the ringtone. A user whose dialer rings by itself gets that dialer's behaviour (most dialers also honour
`CUSTOM_RINGTONE`; the optional hook would not apply).

---

## 4. Recommended hook points (phone calls), if a hook is used at all

The native path covers fixed and rule-based variants, the re-roll covers "random per call", and `vibration_uri`
covers vibration if V2 passes. The two hooks below are the **least intrusive fallbacks**, prepared in `spikes/m9/`
and to be validated in the device run. Both substitute a value; neither makes a decision.

| Hook | What it replaces | Why this one |
| --- | --- | --- |
| `com.android.server.telecom.Call.getRingtone()` (0 args), around | its return value, for an incoming, non-self-managed, ringing call that Cucuto routes | Only two callers (§3.2), both on the ringing path. Telecom keeps the ownership check, audio attributes, volume shaper (ramping), haptic-channel muting, `vibration_uri` handling **and the fallback to the default ringtone when our URI cannot be opened**. The pick must be made once per call (memo keyed by the call) because the getter runs two or three times per ring. |
| `com.android.server.telecom.Ringer.vibrateIfNeeded(boolean, Call, VibrationEffect, Uri)`, around | argument 2 (the `VibrationEffect`) | Runs after `isVibratorEnabled`, DND and the vibrator reservation; ring vibration intensity still applies through `USAGE_RINGTONE`. The effect must repeat (Telecom's default repeats from index 1), for example `VibrationEffect.Builder.setRepeatingEffect` or a repeating waveform. |

Rejected alternatives:

- `Ringer.startRinging`, `getRingerAttributes`, `isVibratorEnabled`: decision points; changing them changes
  whether the phone rings.
- `RingtoneFactory.getRingtone`: replacing its returned `Pair<Uri, Ringtone>` means building a `Ringtone` ourselves
  and duplicating attributes, shaper and fallback.
- `Call.setCallerInfo` / writing `CallerInfo.contactRingtoneUri`: mutates state that the call log and other
  readers also see.
- `AsyncRingtonePlayer` and the synthetic lambdas: unstable names and threading.
- The `CallsManager` / `CallAudioManager` level: far wider than needed.

Routing for the hook would key on the call's handle (E.164 number) and the matched contact's lookup key, through
the same routing table as M1 (ADR 0009) with phone keys added, and URIs restricted to Cucuto's MediaStore exports.

**No-hook alternative for "random per call" (the default, #144).** The app re-rolls the contact's `CUSTOM_RINGTONE`
after each call ends (`TelephonyManager.ACTION_PHONE_STATE_CHANGED` to idle, `READ_PHONE_STATE`; the next call
then rings with a new random variant). From the caller's side it behaves the same. The cost: the Contacts app shows
the ringtone changing, and if the app cannot run (force-stopped) the last pick sticks. It reuses the
boundary-rewrite code of #52.

---

## 5. WhatsApp calls

### 5.1 Findings

| Question | Answer | Evidence |
| --- | --- | --- |
| Does WhatsApp use Telecom? | Yes, as a **self-managed** app: `com.whatsapp/.calling.telecom.SelfManagedConnectionService` and Jetpack `androidx.core.telecom` (`JetpackConnectionService`), with `MANAGE_OWN_CALLS` granted. | `dumpsys package com.whatsapp` (2.26.42.9, targetSdk 36) |
| Does Telecom ring for it? | **No.** `getRingerAttributes` sets `endEarly` for `isSelfManaged`; `startCallWaiting` also skips "Self-managed". | `Ringer` (device) |
| Does its incoming-call notification carry the ringtone? | **No.** Channel `voip_notification_9`: importance 4 (high), **sound `null`**, vibration off. | `dumpsys notification` (redacted, channel definitions only) |
| So who plays it? | **WhatsApp, in its own process** (its own `Ringtone`/`MediaPlayer` and vibrator). The exact player is confirmed in the device run with `players` (test W1). | By elimination: neither Telecom nor the notification channel plays a sound. |

Google's guide for calling apps recommends the opposite: an incoming-call channel at high importance whose sound is
the ringtone with `USAGE_NOTIFICATION_RINGTONE` ([Call notifications](https://developer.android.com/develop/connectivity/telecom/voip-app/notifications)).
If WhatsApp ever switches to that pattern, M1's existing `playSound` hook would see the call notification and could
re-voice it with no new hook point (only routing for the voip channel and looping). The device run records the call
notification's shape so this can be detected (test W2).

### 5.2 Options outside WhatsApp's process

| Option | How | Verdict |
| --- | --- | --- |
| W-a. Redirect WhatsApp's default-ringtone read | If WhatsApp plays `content://settings/system/ringtone`, `MediaPlayer` opens SettingsProvider's ringtone cache, and SettingsProvider runs in `system_server`. A hook would serve a different file to WhatsApp's uid while a WhatsApp call rings. | **Rejected.** SettingsProvider's classes come from its own APK (a wider scope), or else the hook sits on the framework's generic `ContentProvider.Transport.openTypedAssetFile`, which every provider call in `system_server` goes through. It only works if WhatsApp uses the system default, and it changes what an app reads by uid. |
| W-b. Ring the CallStyle notification | The user sets WhatsApp's call ringtone to "None"; the module gives WhatsApp's call notification a sound, `USAGE_NOTIFICATION_RINGTONE` and `FLAG_INSISTENT`, so `NotificationAttentionHelper` loops it until WhatsApp cancels the notification. | **Not for v1.** It mutates three fields upstream of the mute logic (a decision, not a substitution), needs a manual WhatsApp setting, and its stop and DND behaviour needs proof. It could be a later opt-in experiment (#145). |
| W-c. Make Telecom ring and mute WhatsApp | Un-skip self-managed calls in `getRingerAttributes`, and mute WhatsApp's player with app-op `PLAY_AUDIO` for ringtone usage. | **Rejected.** Two decisions changed, re-implements DND, and risks double or no ringing. |
| W-d. Guided fallback | The app exports the character's ringtone to `Ringtones/cucuto/` (MediaStore) and walks the user to WhatsApp → chat → Notifications → custom call ringtone. | **GO.** No hook, works today; one file per chat, no variants, manual. |

The brief already lists "per-contact ringtones for WhatsApp calls" under later ideas and the matching risk
("WhatsApp plays its call ringtone inside its own process") in its risk table. This spike confirms that risk.

---

## 6. Constraints

### Looping

- Telecom loops the **whole file** (`Ringtone.setLooping(true)` → `MediaPlayer`). The pack format's
  `loop.startMs`/`loop.endMs` and fade-in have no equivalent in `MediaPlayer` or `CUSTOM_RINGTONE`.
  **Consequence for #51/#52:** at export, render a "ring file": the intro once, then the loop section repeated until
  the file is at least 90 s long (Telecom stops ringing when the call is answered or goes to voicemail, typically
  20–60 s), so the seam is seamless and the intro never repeats. Rendering is offline in the app or packer; nothing
  happens at ring time.
- `vibration_uri` vibrations repeat with a 200 ms gap. A hook-provided effect must repeat by itself.

### Vibration

- Ring vibration uses `VibrationAttributes` usage `RINGTONE` (33), so the user's ring vibration intensity applies.
- Telecom vibrates only when `isVibratorEnabled`: the device has a vibrator, "vibrate for calls" is on, and the ringer
  is not silent (or DND lets this contact through). Cucuto never overrides that.
- With audio-coupled haptics (a ringtone with haptic channels while haptics playback is supported), Telecom skips the
  vibrator. Cucuto's exported files have no haptic channels, so this does not arise unless a pack ships them.

### DND, silent and vibrate parity (phone calls)

Parity is structural, as in M0: every decision below happens before the ringtone URI or the vibration effect is used.

| Situation | What Telecom does | With Cucuto (native or hook) |
| --- | --- | --- |
| DND blocks the caller | `shouldRingForContact` false → inaudible, no vibration (unless DND lets the caller through) | same; the URI is never opened |
| Ring volume 0 / vibrate mode | haptic-only; ringtone loaded only for a `vibration_uri` | same; a per-contact `vibration_uri` still vibrates |
| Silent mode | nothing, unless DND lets this contact through | same |
| Ramping ringer | volume shaper on Cucuto's ringtone; vibration first | same |
| Our file missing or unreadable | falls back to the default ringtone | same (and #52's repair-on-start re-exports it) |
| Self-managed (WhatsApp) | Telecom does not ring | hooks ignore self-managed calls |
| Dialer rings by itself | Telecom skips | out of scope |

---

## 7. Supervised device test plan (#143)

Owner present, phone unlocked, a **second phone** to place calls, and a contact named exactly **"Cucuto Test"**
created with the second phone's number (the script only ever reads or writes that one contact). Read the recovery
ladder (`kotlin run-on-device.main.kts recover`, same as `device-testing.md` §0) first.

**Part A: no module installed (native path).** Everything here uses stock Android and is undone with `clear-ringtone`.

| # | Steps | Pass |
| --- | --- | --- |
| N1 | Pick a stock ringtone other than the default (`adb shell content query --uri content://media/internal/audio/media --projection _id:title --where "is_ringtone=1"`), `set-ringtone --yes content://media/internal/audio/media/<id>`; call from the second phone. | That ringtone plays and loops; the Contacts app shows it. |
| N2 | `adb push` any CC0 audio file to `/sdcard/Ringtones/cucuto-m9/` (files written through FUSE are indexed at once), find its `content://media/external/audio/media/<id>`, `set-ringtone --yes` it; call. | It plays: the `Ringtones/cucuto/` export route of #52 works. |
| V1 | `set-ringtone --yes '<same uri>?vibration_uri=/product/media/vibration/ringtone/Heartbeat.xml'`; call. Repeat in vibrate mode. | Heartbeat vibration instead of the default; in vibrate mode, vibration only. |
| V2 | `vibration-files --yes`, then repeat V1 with `/storage/emulated/0/Ringtones/cucuto-m9/cucuto-m9-vibration.xml`, then `/data/local/tmp/cucuto-m9-vibration.xml`. | Record which paths vibrate. On failure, `logcat` shows `File exists:…, canRead:…`. |
| P1 | DND on (calls from nobody), then ringer silent; call with the V1 ringtone set. | No sound or vibration, exactly as with no custom ringtone. |
| W1 | Have someone place a **WhatsApp** call to the phone; while it rings run `players`. | Records the player type, usage and uid that rings (expected: WhatsApp's uid). |

**Part B: spike module (observe first, then swap).** `install --yes`; LSPosed manager → enable "Cucuto M9 spike",
scope **System Framework only**; reboot; `verify`.

| # | Steps | Pass |
| --- | --- | --- |
| H1 | `verify` | all six `hooked …` lines and `telecom classes: Ringer=true Call=true RingtoneFactory=true`; crash buffer empty; WhatsApp and WaEnhancer X healthy. |
| O1 | SIM call from the second phone (observe-only). | `START_RINGING … selfManaged=false account=com.android.phone`, `RINGTONE … loaded=true localPlayer=true`. |
| W2 | WhatsApp call (observe-only). | `START_RINGING … selfManaged=true account=com.whatsapp` then nothing else from Telecom; `WA_CALL_NOTIFICATION channel=voip_notification* sound=<null> …` with category, CallStyle template, `fsi`, shortcut domain. |
| S1 | `swap-on --yes`; SIM call. | The spike "ring-ring" plays and loops; `SWAP_RINGTONE` once per call; `RING_OPEN callingUid=1000` in logcat; the spike vibration (three pulses) with `SWAP_VIBRATION`. |
| S2 | `swap-on --yes content://media/external/audio/media/<id>` (the N2 file); SIM call. | That file plays: the MediaStore route works with the hook. |
| S3 | `adb shell am force-stop app.mauri.cucuto.spike.m9`, `swap-on --yes` (provider URI); SIM call. | Measures the cold-start delay of a provider URI (`sinceProcessStartMs`); expected to justify MediaStore-only. |
| S4 | With swap on: DND (nobody), vibrate mode, silent mode. | Same behaviour as P1/stock; no `SWAP_*` sound when Telecom decided not to ring. |
| S5 | With swap on: WhatsApp call. | No `SWAP_*` lines (self-managed calls are ignored); WhatsApp rings as usual. |
| R1 | `collect`, then `recover --yes`, `clear-ringtone --yes`, reboot, `preflight`. | Spike gone, test contact back to default, phone healthy. |

Record redacted results in #143 and in a "Supervised device run" section appended to this report, as M0 did.

---

## 8. Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| `system_server` cannot read Cucuto's vibration XML (V2 fails) | No native per-contact call vibration | Keep the default vibration (no regression), or the `vibrateIfNeeded` hook; or have the module copy XMLs into a `system_server`-owned folder (more intrusive, needs its own ADR). |
| Telecom is an APEX and updates through Google Play system updates | Hook names (`Call.getRingtone`, `Ringer.vibrateIfNeeded`) can change between monthly trains | Name + arity resolution, miss = no-op (native path unaffected); the native path uses public APIs only. |
| `vibration_uri` is Pixel-gated (`config_ringtoneVibrationSettingsSupported`) | Other devices ignore the parameter | Harmless: the parameter is ignored and the default vibration plays. Show "custom call vibration not supported" in the app when the config is false. |
| Re-roll misses a call (app force-stopped) | The same variant twice | Acceptable; re-roll also at each boundary alarm and on app start. |
| A dialer with `IN_CALL_SERVICE_RINGING=true` | Telecom does not ring; the hook does not apply | Native `CUSTOM_RINGTONE` still works with most dialers; document it. |
| WhatsApp changes its call notification to carry the ringtone | A new opportunity, not a breakage | W2 logs it; M1's `playSound` hook could take it with routing only. |
| Writing contacts (`WRITE_CONTACTS`) | The ringtone URI is a device-local MediaStore ID; if an account syncs the column, other devices get a dangling URI | Only the URI string can travel, never the audio, and Telecom falls back to the default ringtone; #52's repair-on-start rewrites stale IDs after a restore. |

---

## 9. Device state

**The device was not modified.** Read-only commands only: `getprop`, `echo $SYSTEMSERVERCLASSPATH`, `ps`,
`cat /proc/<pid>/maps` (paths only), `find` under `/system`, `/product`, `/apex`, `pm path` and `pm list`,
`dumpsys package` (WhatsApp and Google Dialer, permission and component lines only), `dumpsys activity providers`,
`dumpsys audio` (players by uid, no content), `cmd overlay lookup`, `aflags list`, `settings get` (three non-personal
keys), and `adb pull` of Google's framework and Telecom jars, `framework-res.apk` and the Dialer APK into the
gitignored scratch area. `dumpsys notification` ran **without `--noredact`**, filtered on the device side to
WhatsApp's channel definitions (no notification records) with digits, JIDs and URI paths masked; the raw dump was
deleted right away. No contact was queried (the script's `test-contact` found no "Cucuto Test" contact and
printed nothing else). No module was installed, LSPosed was not touched, nothing was rebooted.
