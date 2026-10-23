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
- within ~1 min: `Routing table attached (generation …)` (0 before the app has published anything)

**Fail if:** `missing` is not empty, no `cucuto` lines at all, or any crash in
`adb logcat -b crash -d`. Then go to the recovery ladder.

## 2. Self-test (no WhatsApp needed)

Open Cucuto → **Diagnostics**. Expect "Xposed framework: Connected: LSPosed 2.2.1, API 102" and a routing table
generation. Tap **Post test notification** (allow notifications if asked).

**Look for:**

- You hear a **two-tone rising chirp** (not the default notification sound) and feel a short **click-tick**.
- Module log: `alert app.mauri.cucuto SWAPPED key=<title> … clip=content://app.mauri.cucuto.clips/selftest/chirp.wav (self-test) NNus`.
  `NNus` should be well under 5000; a `Slow playSound` warning is a finding.
- Once per boot, after the first custom haptic: `Vibrator: builder, envelopes …, amplitude control …, primitives …`.
  `builder` must be there (the module probes `VibrationEffect.Builder` inside `system_server`, where the 37.1 build
  allows it); `Vibrator unreadable, assuming: …` means the helper's Context could not be read (a finding).
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

## 4. Global switch and parity

- Diagnostics → switch **Cucuto voices** off. Module log: a new routing table generation. The assigned contact now
  plays WhatsApp's tone; the log says `DISABLED`. Switch it back on.
- **Muted chat**: no sound, no `alert` line (muted upstream of the hook).
- **DND on** (`adb shell cmd notification set_dnd priority`): no sound for the test notification; restore with
  `set_dnd off`.
- **Ringer silent / vibrate**: silent plays nothing; vibrate plays the click-tick only.
- **WaEnhancer X** still works; WhatsApp does not crash (`adb logcat -b crash -d`).

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

