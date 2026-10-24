# Device testing: production hook (M1, issues #15–#17, #121)

A supervised session on the owner's phone (Pixel 10 Pro, `CP3A.261005.005`, LSPosed 2.2.1, libxposed API 102),
run by the lead with the owner present. It enables the production module for the first time, so read the recovery
ladder before anything else. Facts about the phone are in [`device.md`](device.md); the design is
[ADR 0010](decisions/0010-production-hook-and-module-channels.md).

Privacy: the module's log never contains a JID, name, number or message text (keys are reduced to their domain:
`@lid`, `@g.us`). The app's Diagnostics screen shows raw keys from its private database: do not screenshot it into
GitHub. Anything pulled off the device goes to the gitignored `private/`.

## 0. Recovery ladder (read first)

1. Phone usable: `adb uninstall app.mauri.cucuto`, then reboot. LSPosed drops a module whose APK is gone.
2. Phone boots but misbehaves and the LSPosed manager is unreachable:
   `adb shell su -c 'touch /data/adb/modules/zygisk_lsposed/disable'` then `adb reboot` (disables all of LSPosed,
   WaEnhancer X too; delete the file and reboot to restore).
3. No adb: KernelSU safe mode (hold Volume-Down through boot), then rung 1 or 2.
4. Last resort: reflash the factory image for `CP3A.261005.005`.

Health check after every reboot:

```
adb shell getprop sys.boot_completed                       # 1
adb shell pidof system_server                              # a pid
adb shell su -c '/data/adb/modules/zygisk_lsposed/lspctl status'
```

The spike module `app.mauri.cucuto.spike` must be **uninstalled or disabled** first: both modules hook the same two
methods, and only one should swap arguments.

## 1. Install and enable

```
cd android && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

In the LSPosed manager: enable **cucuto**, scope = **System Framework** only (the list should offer nothing else:
`staticScope=true`). Reboot. Run the health check.

**Look for** (LSPosed manager → Logs → Modules, or
`adb shell su -c "grep -h -F 'app.mauri.cucuto,cucuto,' /data/adb/lspd/log/modules_*.log"`):

- `Loaded in system by LSPosed …, API 102`
- `Hooked NotificationAttentionHelper.playSound(NotificationRecord, Uri)`
- `Hooked NotificationAttentionHelper.playVibration(NotificationRecord, VibrationEffect, boolean)`
- `Haptics via VibrationEffect.Builder`
- `Hooks installed: [playSound, playVibration]; missing: []`
- within ~10 s of `Hooks installed`, and before any `alert …` line, once: `Vibrator: builder, envelopes … points …,
  amplitude control …, primitives …`. The module reads the vibrator's capabilities on its background thread as soon as the vibrator
  service is up (#190, [ADR 0044](decisions/0044-vibrator-capabilities-at-boot.md)), never on the notification path.
  `builder` must be there (the module probes `VibrationEffect.Builder` inside `system_server`, where the 37.1 build
  allows it).
- within ~1 min: `Routing table attached (generation …)` (0 before the app has published anything)

**Fail if:** `missing` is not empty, no `cucuto` lines at all, or any crash in
`adb logcat -b crash -d`. Then go to the recovery ladder. **Finding** (open an issue) if the `Vibrator:` line comes
after the first `alert …` line or is missing, if it reads `Vibrator unreadable, assuming: …` (the service never
became ready within 5 minutes; custom haptics use generic capabilities), or if any `Slow playVibration` or
`Slow playSound` line appears, the first alert after boot included.

## 2. Self-test (no WhatsApp needed)

Open Cucuto → **Diagnostics**. Expect "Xposed framework: Connected: LSPosed 2.2.1, API 102" and a routing table
generation. Tap **Post test notification** (allow notifications if asked).

**Look for:**

- You hear a **two-tone rising chirp** (not the default notification sound) and feel a short **click-tick**.
- Module log: `alert app.mauri.cucuto SWAPPED key=<title> … clip=content://app.mauri.cucuto.clips/selftest/chirp.wav (self-test) NNus`.
  `NNus` should be well under 5000; a `Slow playSound` warning is a finding.
- No new `Vibrator:` line: it was logged once, right after boot (step 1). No `Slow playVibration` line either.
- `adb shell dumpsys vibrator_manager | grep -A3 -i "app.mauri.cucuto"`: a short effect with usage NOTIFICATION.
- Within ~10 s the Diagnostics screen shows a `SWAPPED` decision and "Module in system_server: Reported … Hooks:
  playSound, playVibration". The module log prints `Delivered N decision events to the app` once after boot (and
  again after any failed delivery).

**Diagnose:**

- No sound and no `alert app.mauri.cucuto` line: Android's notification cooldown muted the post before the hook
  ran. Check `adb logcat -b events -d | grep notification_alert | grep cucuto`: the last two numbers are the
  politeness state and the mute reason; `2,32768` means muted by cooldown. Avalanche (2 min after boot, airplane
  mode off or a profile becoming available) mutes every non-exempt app; the self-test is posted as a reminder,
  which is exempt. The per-app cooldown mutes a post less than 10 s after the previous one, so the app refuses to
  post the test sooner ("Wait N seconds"); within a minute it plays at reduced volume (state `1`).

- Default sound plays and the module log shows the swap: SystemUI could not open the clip. Check
  `adb logcat -d | grep -i -E "cucuto|RingtonePlayer|SecurityException"` (the provider refuses everyone except uid
  1000 and `com.android.systemui`).
- No decision appears in the app but the log shows the alert: event delivery failed. Look for
  `Delivering N decision events failed` warnings in the module log (at most one a minute); each names the reason.
  The app side can be checked without the module: `su 1000 -c "content call --uri content://app.mauri.cucuto.events
  --method report --extra v:i:1"` must print `Bundle[{accepted=0}]`.

## 2b. Status card and onboarding (#24)

On a fresh install Cucuto opens on the onboarding flow; after it (or from Status → **Setup guide**) the Status card
shows four checks. What each reads ([ADR 0015](decisions/0015-status-checks-and-onboarding.md)):

- Before enabling: module "Not enabled", scope "Cucuto can read its scope once the module is enabled".
- Enabled, before the reboot: "Enabled in LSPosed, but not loaded into the system yet" (if LSPosed hands Cucuto its
  service before the reboot) or still "Not enabled".
- After the reboot, before any alert: "Enabled, but the module has not reported since this boot" with **Post test
  notification**; scope "LSPosed reports System Framework and nothing else".
- After the self-test (about 10 s): module passes with "Hooks: playSound, playVibration". With WhatsApp installed and
  a pack imported (the debug test voice does not count) the card turns green with the success sound, the door
  opening and a particle burst.

**Look for:** the scope line really comes from LSPosed (`getScope()` returns `[system]`), and the module line never
asks for a reboot right after one. If it does, `getRunningTargets()` does not list `system_server` as expected:
record what LSPosed returns (a finding for #24).

## 2c. Simulate message (#26, no WhatsApp message needed)

Give a chat a voice (Home → a recent sender → tap a character twice), then open it from the Home list: contact
detail. The line under **Simulate message** says which path runs ([ADR 0025](decisions/0025-contact-detail-and-simulate-message.md)):

- **Right after installing this build (no reboot):** "In-app preview: the module runs older code until the next
  reboot…". Tapping plays one of the character's clips in the app; the result line names the clip. Tap a few times
  (no 12 s wait here): the same clip never plays twice in a row.
- **After the next reboot:** "Through the module…". Tapping posts a "Simulated message from <chat>" notification.

**Look for (module path):**

- You hear the chat's clip (not the default sound) and feel its vibration, from the notification itself.
- Module log: `alert app.mauri.cucuto SWAPPED key=…@lid contact=#N clip=… (simulated) NNus`.
- Within ~10 s the screen's result changes to "The module played <clip>", and **Recent alerts** shows the entry
  marked "Simulated". Real messages from the chat show as "Message".
- A second tap within 12 s says "Wait N s" (Android's per-app cooldown would mute it).

**Diagnose:**

- Default sound on the module path: `system_server` runs a module older than this build (LSPosed said `UP_TO_DATE`
  wrongly?), or the routing table in the module has no route for the chat. Check the module log's `Routing table
  <generation>` line after the assignment, and the decision's outcome in Recent alerts.

## 3. Real WhatsApp messages

Have someone send a 1:1 message and a group message (WhatsApp in the background, chats not muted).

**Look for** (module log): `alert com.whatsapp UNASSIGNED key=@lid group=false` for the 1:1 chat and
`key=@g.us group=true` for the group; WhatsApp's own tone plays (nothing is assigned yet). The Diagnostics screen
lists both decisions.

Then tap **Give the latest sender a test voice** (debug builds only; it installs a generated three-chirp pack
`app.mauri.cucuto.devtest` and assigns it to the newest WhatsApp conversation). The routing table line should move
to a new generation with 1 key and 1 voice, and the module log should print `Routing table <generation>: 1 keys,
1 voices` without a reboot.

Ask that contact to send three or four more messages, a few seconds apart (WhatsApp throttles quick follow-ups onto
its silent channel; that is expected and not a bug).

**Look for:**

- Each alert plays one of three chirps (rising, falling, high) and **never the same one twice in a row**, with the
  click-tick vibration.
- Module log: `alert com.whatsapp SWAPPED key=@lid … contact=#N clip=content://app.mauri.cucuto.clips/app.mauri.cucuto.devtest/chirp/characters/chirp/clips/<up|down|high>.wav`.
- Another, unassigned chat still plays WhatsApp's tone (`UNASSIGNED`).
- Note the `NNus` values; record the slowest.

## 3b. Group messages and per-sender voices (#20), WhatsApp Business (#21)

Design: [ADR 0031](decisions/0031-group-policy-and-whatsapp-business.md). You need a group with at least two other
members (A and B) who agree to send a few messages. Keep messages a few seconds apart, as in section 3.

1. **Does the summary carry the sender?** A sends a message to the group. Module log:
   `alert com.whatsapp UNASSIGNED key=@g.us group=true sender=<shape>`. Record the shape:
   - `@lid` (or `@s.whatsapp.net`): the sender is there and keyed like a 1:1 chat. Per-sender voices work.
   - `<other>`: a sender key that is not a JID. Per-sender still routes, but members do not appear in recent senders.
   - `<name only>` or `<none>`: the alerting summary has no usable sender. Per-sender groups fall back to the group's
     voice; record it in the follow-up issue (reading the child notification would need another hook point).
2. **Default policy.** Give the group a voice (Home → the group → a character). A writes: the group's voice plays
   (`SWAPPED key=@g.us … contact=#<group>` with no `per-sender` detail), even if A has a voice of their own.
3. **Per sender.** On the group's detail screen, switch "Who speaks in this group" to **Each sender** (the setting sits on
   the contact detail screen, #26; run this step once that screen has shipped). The routing table moves to a new
   generation. Then:
   - A writes again. Within ~10 s A appears in Home's recent senders (if step 1 showed `@lid`). Give A a different
     character.
   - A writes: A's voice plays; log detail `(per-sender)`, `contact=#<A>`.
   - B (no voice) writes: the group's voice plays; detail `(per-sender, sender has no voice: group voice)`.
   - Remove the group's voice and have B write again: WhatsApp's tone; detail `(per-sender, sender has no voice)`.
   - If A also has a 1:1 chat with the phone, A's direct messages now play the same voice, and the clips never repeat
     back to back across the two chats.
4. **Back to the group.** Switch to **The group**: A's group messages play the group's voice again (or WhatsApp's tone
   if it has none).
5. **WhatsApp Business** (only if it is installed and has a chat): a 1:1 or group message logs
   `alert com.whatsapp.w4b …`. The chat appears on Home as "Business · …". Giving it a voice does not change the
   WhatsApp chat of the same person, and the other way round.

**Look for:** `NNus` stays in the same range as 1:1 alerts (the sender read adds one Bundle lookup), no `Slow
playSound`, and no key or name in the module log (only `@g.us`, `@lid` and `<shape>` forms).

## 4. Global switch and parity

- Diagnostics → switch **Cucuto voices** off. Module log: a new routing table generation. The assigned contact now
  plays WhatsApp's tone; the log says `DISABLED`. Switch it back on.
- **Muted chat**: no sound, no `alert` line (muted upstream of the hook).
- **DND on** (`adb shell cmd notification set_dnd priority`): no sound for the test notification; restore with
  `set_dnd off`.
- **Ringer silent / vibrate**: silent plays nothing; vibrate plays the click-tick only.
- **WaEnhancer X** still works; WhatsApp does not crash (`adb logcat -b crash -d`).

## 4b. Live table updates, with and without an app update since boot (#154)

The module must follow every publication within seconds, also after the app was reinstalled since the last boot
([ADR 0024](decisions/0024-routing-updates-after-app-update.md)). Run part A first, then part B. Module code
changes need a reboot (`system_server` keeps the module it loaded at boot); app-only changes do not.

Watch the module log in a second terminal:

```
adb shell su -c "tail -f /data/adb/lspd/log/modules_*.log" | grep --line-buffered -F 'app.mauri.cucuto,cucuto,'
```

**After the boot** (once per boot), expect `Routing table attached (generation …)` and `Listening for new routing
tables` within about 20 s.

**A. No app update since boot.** Open Cucuto → **Diagnostics** and toggle **Cucuto voices** off, then on.

- Each toggle logs exactly one `Routing table <gen>: N keys, M voices` line within 2 s, with a new, larger `<gen>`.
- Within about 15 s, Diagnostics → "Table in the module" reads `Generation <gen>, N keys: the app's latest` (the same
  generation as the "Routing table" row).

**B. After an app update.** Without rebooting, reinstall the app (`adb install -r app-debug.apk`, a newer build or the
same one), open **Diagnostics** and toggle the switch again.

- Same expectations as A: one `Routing table <gen>` line within 2 s per toggle, and Diagnostics catches up within
  about 15 s. Here the line comes from the nudge and the remote file; LSPosed no longer notifies preferences.
- Optional, to see the transport: `adb shell su -c 'find /data/misc /data/adb -name routing.table -exec ls -l {} +'`
  shows the remote file with a fresh timestamp (LSPosed keeps it under `<base>/<user>/app.mauri.cucuto/`).

**Fail if:** no `Routing table` line after a toggle; `Ignored a routing nudge from …` (the sender's identity did not
arrive: record what it says); `Registering the routing nudge receiver failed` or `Could not listen for new routing
tables`; `Ignoring an incomplete routing file` on every toggle (an occasional one during rapid toggling is fine:
the next nudge reads the complete file); or "Table in the module" still says "Not yet the app's latest" a minute
after the last toggle. Also check `adb logcat -b crash -d` for anything from `system_server`.

## 4c. Settings, backup and restore (#28, #43)

- **Quick Settings tile:** edit the tiles, add **Cucuto voices**, tap it. It turns off and on like the switch in
  Settings (the routing table moves to a new generation each time).
- **Back up to a file:** Settings → Back up to a file → (optionally) a password → save to Downloads. The file is
  `cucuto-backup-YYYY-MM-DD.zip`. With "Include sound packs" it holds `packs/*.soundpack.zip`.
- **Automatic backups:** Settings → Choose folder → pick an empty folder. A backup appears within a few seconds; change
  a voice and another one replaces today's file about 10 s later. "Back up now" with nothing changed says "Nothing
  changed since the last backup" only for the automatic run, never for the button (the button always writes).
- **Restore:** Settings → Restore from backup → pick the file (and type the password). The pack checklist installs
  what it can by itself; a local-only pack asks for its file. Give one contact a different voice first: the restore
  lists it as a conflict and keeps this phone's voice unless ticked. The summary ends with the LSPosed checklist.
  Running the same restore twice changes nothing.

**Never** restore on the owner's phone without asking: it adds contacts and repositories (it never overwrites voices
without a tick, but it is still their data).

## 4d. Google Drive (#163, #37, ADR 0040)

Needs the Google Drive app signed in. Nothing here enables the module or touches LSPosed.

- **Folder picker:** Settings → Choose folder. Note whether **Drive** appears in the picker's side menu and whether
  "Use this folder" is enabled inside it. Report it on #163 (it decides whether Drive needs the file mode at all).
- **One file in Drive:** Settings → Choose file → Drive → keep `cucuto-backup-latest.zip` → Save. The row says "To
  cucuto-backup-latest.zip". Change a voice; about 10 s later "Last backup" updates. In Drive (web or app), the file
  shows a new modified time and, under Manage versions, an older version. Turn on airplane mode, change a voice:
  note what the row reports, then check that Drive uploads the file once the phone is back online.
- **Shorter backup:** turn off "Include sound packs" and press Back up now. The Drive file must shrink and still open
  as a zip (download it and restore from it), which proves the write mode truncates.
- **New phone flow:** Settings → Restore from backup → Drive → `cucuto-backup-latest.zip`. The summary offers "Keep
  backing up to this file"; tap it, and the automatic backup row switches to that file. An encrypted backup must
  not offer it.
- **Drive links in Discover:** share a `.soundpack.zip` in Drive with "Anyone with the link", paste the link in
  Discover → Add: it downloads and opens the import sheet. A Drive folder link shows the "can't be added yet" message.

## 5. Report and clean up

Record in issue #15 (redacted): hook lines, self-test result, the 1:1/group key domains, whether per-message clips
alternated, the slowest `NNus`, and the parity results. If everything passed, #15 can close.

Leave the module enabled only if the owner wants to keep using it; otherwise disable it in LSPosed and reboot, or
`adb uninstall app.mauri.cucuto`. To remove the test voice later: clear the app's data.

## 6. Instant clips: the clip mirror (#121, ADR 0018)

The sound must start as fast as WhatsApp's own tone, also when Cucuto's process is not running. Before #121,
SystemUI opened clips through `ClipProvider`, and a dead process added 0.35 to 0.45 s while Android cold-started
it. The app now mirrors the clips to `Android/media/app.mauri.cucuto/clips/` and publishes `file://` URIs, which
SystemUI reads with no process start ([ADR 0018](decisions/0018-clip-mirror-for-instant-sounds.md)).

**After installing the build (no reboot needed).** Open Cucuto once, then:

```
adb shell ls -R /sdcard/Android/media/app.mauri.cucuto/clips    # .nomedia, selftest/chirp.wav, <packId>/<version>/…
adb logcat -d -s cucuto                                         # no "Clip mirror off (…)" line
adb shell su -c "grep -h -F 'app.mauri.cucuto,cucuto,' /data/adb/lspd/log/modules_*.log" | grep 'Routing table' | tail -2
```

The last command should show a new `Routing table <generation>` line from after the install: the clip URIs changed,
so the app republished. From then on, WhatsApp alerts for assigned contacts already use the mirror. The module log
shows `clip=file:///storage/emulated/0/Android/media/app.mauri.cucuto/clips/<packId>/<version>/…`.

**After the next reboot.** The self-test switches to the mirrored chirp once the module code that reads the
table's `S` line is running. To measure the cold path without a WhatsApp message:

1. Start a streaming log in one terminal (the main buffer wraps within seconds, so a later dump misses lines):

   ```
   adb logcat -b main,events -v threadtime -T 1 RingtonePlayer:D NotificationPlayer:I am_proc_start:I '*:S'
   ```

2. Tap **Post test notification**, then within 4 s snooze it, leave the app and kill its process (as the
   low-memory killer would; `am kill` does not kill a process that is still stopping):

   ```
   K=$(adb shell cmd notification list | grep '|app.mauri.cucuto|' | grep -v '|android|' | head -1)
   adb shell cmd notification snooze --for 15000 "$K"
   adb shell input keyevent HOME
   sleep 3; adb shell su -c 'kill -9 $(pidof app.mauri.cucuto)'
   ```

3. 15 s later the notification returns and alerts.

**Look for:**

- `RingtonePlayer: playAsync(uri=file:///storage/emulated/0/Android/media/app.mauri.cucuto/clips/selftest/chirp.wav`.
- **No** `am_proc_start … app.mauri.cucuto,content provider,{…ClipProvider}` line.
- `in run` → `requesting AudioFocus` within about 60 ms (stock tone: 20 to 50 ms; the old cold path: 350 to 460 ms).
  `player.start` follows about 500 ms later on every path (`NotificationPlayer`'s fixed focus ramp).
- Module log: `alert app.mauri.cucuto SWAPPED … clip=file:///…/clips/selftest/chirp.wav (self-test)`.

**Diagnose:**

- `clip=content://…` after the reboot: the mirror is off. `adb logcat -d -s cucuto` names the reason (not the
  system user, SystemUI without `MANAGE_EXTERNAL_STORAGE`, shared storage unavailable). Clips still play through the
  provider, cold start included.
- `file://` URI but silence: the copy is missing. `NotificationPlayer` logs `error loading sound for file://…`.
  Opening Cucuto rebuilds the table and repairs the mirror.


## 7. Phone ringtones (#52, ADR 0039)

Phone ringtones use only public APIs: no LSPosed change and no reboot. The module can stay as it is. The
supervised call tests with a second phone (Telecom ringing, `vibration_uri`, WhatsApp calls) are #143. This section
checks Cucuto's side. Use only a test contact, for example "Cucuto Test" from #143. Every step can be undone with
**Settings › Phone ringtones › off**, which gives every contact its old ringtone back.

**Ring file rendering (codec path, no contact needed).** With the phone unlocked:

```
cd android && ./gradlew :core:ringtone:connectedDebugAndroidTest
```

`PlatformRingRendererTest` renders a 14 s, 44.1 kHz stereo WAV with loop 4–12 s into a 92 s Ogg Opus file, and a
play-once file padded to 90 s. If it reports `.m4a`, the device fell back to AAC; check logcat for `Opus in Ogg
failed`.

**Turn on and link.**

1. Give the chat a character whose pack has ringtones, for example Kitchen orchestra's kettle "Kettle reel".
2. Open the chat's detail. The **Phone ringtone** card says "Turn on phone ringtones". Tap it. Check that the
   explanation dialog comes before Android's permission dialog, then allow Contacts.
3. Link the Android contact. A WhatsApp chat with a phone JID suggests "Link <name>"; an `@lid` chat needs
   **Choose contact**. The card then says "Rings with …".
4. Check the result:

   ```
   adb shell content query --uri content://media/external/audio/media --projection _id:_display_name:title:relative_path \
     --where "relative_path='Ringtones/cucuto/'"
   adb shell content query --uri content://com.android.contacts/contacts --projection display_name:custom_ringtone \
     --where "display_name='Cucuto Test'"
   ```

   - One file per variant, named `<character>-<variant>-<hash>.ogg`, titled "Character · Variant".
   - The contact's `custom_ringtone` is one of those `content://media/external/audio/media/<id>` URIs.
   - The Contacts app shows the ringtone under that title. A call from the second phone plays it and loops it (#143
     N2).

**Rules.**

- **One version**: pick another chip; `custom_ringtone` changes within a second.
- **Day and night** (sunrise and sunset at the Day and night place, else 07:00 to 19:00): `adb shell dumpsys alarm |
  grep -A3 app.mauri.cucuto` shows one `RINGTONE_BOUNDARY` alarm at the next sunrise or sunset.
- **Random per call**: the phone permission dialog follows its explanation. After a call ends (place and end one,
  #143), `custom_ringtone` changes to a different variant. `adb shell pm dump app.mauri.cucuto | grep -A2
  CallEndedReceiver` shows the receiver enabled only while some contact uses this rule.

**Respect and undo.**

1. Change the test contact's ringtone in the Contacts app. Reopen the card: it says "This ringtone was changed in
   Contacts", and Cucuto no longer writes it. **Use Cucuto's ringtone again** takes it back.
2. **Stop using for calls** gives the contact the ringtone it had before (or the default), and the unused files
   disappear from `Ringtones/cucuto/`.
3. Settings › Phone ringtones › off: every linked contact gets its old ringtone back, `Ringtones/cucuto/` is empty,
   and both receivers are disabled again.

**Repair.** Delete a file from `Ringtones/cucuto/` with a file manager, then open Cucuto. The file is rendered again
and the contact points at the new URI.
