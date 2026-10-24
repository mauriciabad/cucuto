# 0062. Instant sounds: no hook beats the clip mirror, so it stays

- Status: accepted. Confirms [ADR 0018](0018-clip-mirror-for-instant-sounds.md); nothing changes in the code.
- Date: 2026-10-24
- Issue: #216 (owner's comment on #150)

## Context

ADR 0018 made notification clips play as fast as WhatsApp's own tone. The app copies the clips it uses to
`Android/media/app.mauri.cucuto/clips/` and the routing table points at them with `file://` URIs. SystemUI reads
these through MediaProvider's FUSE daemon, which is always running, so no app process has to start. Measured on the
owner's Pixel 10 Pro (PR #151), SystemUI took this long to open the clip:

| Path | Open time (ms) |
| --- | --- |
| Stock tone | 20 to 51 |
| `ClipProvider`, Cucuto process running | 17 to 82 |
| `ClipProvider`, Cucuto process dead | **347 to 464** (the process has to start) |
| Mirror (`file://`) | 20 to 42 |

Every path then waits the same 500 ms focus ramp before the sound starts.

The owner accepted the mirror and pointed out a new option (#150): the opt-in WhatsApp scope from #145
([ADR 0051](0051-optional-whatsapp-scope.md)). This ADR compares the hooks against the mirror.

### How Android 17 plays a notification sound (AOSP `android-17.0.0_r1`)

1. `NotificationAttentionHelper.playSound(record, soundUri)` in `system_server` calls
   `IRingtonePlayer.playAsync(Uri, UserHandle, looping, AudioAttributes, volume)`. Cucuto's existing hook swaps the
   `Uri` argument and nothing else.
2. SystemUI's `RingtonePlayer` accepts `playAsync` only from the system uid. It passes the `Uri` to its
   `NotificationPlayer`, which calls `MediaPlayer.setDataSource(context, uri)` on a new thread, then `prepare()`,
   asks for audio focus, sleeps for `getFocusRampTimeMs` (500 ms), and calls `start()`.
3. `MediaPlayer.setDataSource(Context, Uri)` opens a `file:` path in SystemUI's own process with `FileInputStream`.
   A `content:` URI goes to `ContentResolver.openAssetFileDescriptor`, which waits while the provider's process
   starts. That wait is where the 0.4 s came from. (`content://settings/…` URIs are special-cased to
   SettingsProvider's ringtone cache, one file per sound type.)
4. **There is no way to hand SystemUI a file descriptor.** `IRingtonePlayer` (`media/java/android/media/IRingtonePlayer.aidl`)
   takes a `Uri` in every play method: `play`, `playWithVolumeShaping`, `playAsync`. The only method that deals in
   descriptors is `openRingtone(Uri)`, and it goes the other way: SystemUI returns a descriptor, and only for
   MediaStore entries flagged as a ringtone, alarm or notification sound. A `file:///proc/<pid>/fd/<n>` URI doesn't
   work either, because SystemUI (an app uid) can't open another process's descriptors through `/proc`.

So SystemUI always opens the clip itself, by URI. To keep the clips out of shared storage, either SystemUI has to be
changed, or the sound has to play somewhere other than SystemUI.

## Options

**(a) Clip mirror in `Android/media` (current, ADR 0018).**
- Latency: the same as the stock tone (20 to 42 ms against 20 to 51 ms). No process starts.
- Battery: nothing runs in the background. The app copies clips only when it republishes the table.
- Intrusiveness: no new hook. The module only swaps one argument.
- Privacy: the clips sit in shared storage. Apps with all-files access (file managers) can read them, and so can a
  computer browsing the phone over USB. Apps with only media permissions can't, because a `.nomedia` marker keeps the
  folder out of MediaStore. The folder holds audio files and pack IDs and versions. It holds no contacts, contact
  keys or assignments: the routing table stays in private storage and with LSPosed. Someone who reads it learns
  which packs are installed and which clips are assigned to someone, but not to whom. Android deletes the folder
  with the app.
- Robustness: it depends on SystemUI holding `MANAGE_EXTERNAL_STORAGE`. The app checks this and falls back to
  `ClipProvider` (correct, but slower when cold) when it's missing. A copy deleted behind the app's back stays
  silent until the next rebuild (ADR 0018).

**(b) Solve it inside `system_server`, so no file is shared.** No module code can make SystemUI take a descriptor
(see above). That leaves three variants:
- **(b1) Hook SystemUI's `RingtonePlayer`** so it plays a descriptor the module provides. This needs a SystemUI
  scope, a new hooked process that ADR 0002 and ADR 0051 don't allow. It also needs a side channel for the
  descriptor, because the AIDL can't carry one. Rejected.
- **(b2) Serve the clip from a provider that lives in `system_server`.** The module can't declare a provider of its
  own there. It would have to hook another component's provider (for example `SettingsProvider.openFile`) or
  Activity Manager's provider lookup, so that SystemUI gets a module-made provider for
  `app.mauri.cucuto.clips`. ADR 0018 option 4 already rejected this: it rewrites system behaviour that other apps
  rely on, instead of swapping one argument. Rejected.
- **(b3) Play the clip in `system_server`.** The module would skip `IRingtonePlayer.playAsync` for Cucuto voices
  and play the clip itself with `MediaPlayer`. It would read the clip from a descriptor obtained in advance: an
  LSPosed remote file (`openRemoteFile`, as the routing table already does, ADR 0024), or a descriptor pulled from
  the app's provider while the app is running (ADR 0048).
  - Latency: no gain. Its best case is the mirror's (an open descriptor, then `prepare`), and it would have to keep
    the 500 ms focus ramp so music ducks correctly.
  - Battery: about the same.
  - Privacy: this is the one variant that would win, because nothing goes into shared storage.
  - Intrusiveness and robustness: much worse. It stops being an argument swap (M0 report §2) and replaces a
    framework call. The module would have to re-implement `NotificationPlayer` inside `system_server`: a player
    thread, audio focus and ramp, a wake lock, looping for `FLAG_INSISTENT`, and stopping on `stopAsync` when the
    notification is cleared. That is a second hook on the clear path. Any mistake there stops the whole phone, not
    just one sound. SystemUI or OEM changes to `NotificationPlayer` would no longer apply to Cucuto's sounds. Remote
    files also depend on the LSPosed service, which goes missing after some updates (ADR 0043). After a reboot,
    the descriptors would need a pull, which can start the app. Rejected.

**(c) Hook WhatsApp's process (the owner's suggestion).**
- The delay isn't in WhatsApp. WhatsApp hands over its notification. `system_server` swaps the sound, and SystemUI
  opens it. A WhatsApp hook can only change which URI SystemUI receives, and SystemUI still has to open it.
- The only WhatsApp-side way to avoid a process start would be to serve the clip from WhatsApp's own process,
  which is running when it posts. That means storing the clips in WhatsApp's private data, or holding descriptors
  in WhatsApp's memory and hooking one of WhatsApp's providers to serve them. ADR 0051 point 4 forbids both: no
  files written by the hook, and no hooks on WhatsApp's own classes beyond the minimum.
- Holding descriptors would also be fragile: Android kills and restarts WhatsApp all the time, and each restart
  would need a refill from Cucuto, which starts Cucuto's process.
- ADR 0051 allows the WhatsApp scope only for features that can't work without it. Instant sounds already work.
  The scope is opt-in, so users who don't enable it would still need the mirror, which leaves two paths to maintain.
- Rejected.

**(d) A world-readable file in the app's private storage.** Rejected because Android blocks it, on top of
everything else.
- On disk, apps targeting API 28 or later get a `0700` data directory, and `MODE_WORLD_READABLE` throws.
- SELinux (AOSP sepolicy `android-17.0.0_r1`) blocks it twice:
  - It has no rule that lets SystemUI's domain (`platform_app`; on the Pixel, the device-specific `systemui_app`)
    read another app's `app_data_file`.
  - The MLS constraint on `file open` (`private/mls`) requires the subject to dominate the object's categories.
    Normal apps get per-app categories (`levelFrom=all` in `seapp_contexts`), while platform apps get only the
    per-user ones (`levelFrom=user`).
- Getting past this would need a custom sepolicy rule from the root solution, which changes the system far more
  than anything above.

## Decision

**Keep the mirror (a).** No hook makes the sound faster: the mirror already matches the stock tone, and every path
waits the same 500 ms focus ramp. Battery use is the same. The only thing a hook could improve is privacy, and only
(b3) gets there, at the cost of re-implementing system audio playback inside `system_server`. What the mirror
exposes is low-sensitivity (audio files, no contacts), and only to all-files-access apps and USB browsing. That
doesn't justify the extra risk. No follow-up issue.

Reopen this decision if one of these happens:
- SystemUI loses `MANAGE_EXTERNAL_STORAGE` on a future build or a common ROM, so the mirror turns itself off;
- Android adds a way to hand `IRingtonePlayer` a file descriptor;
- the mirror fails on a device in a way the provider fallback can't cover;
- the owner decides that clips in shared storage are unacceptable. Then (b3) is the plan to evaluate, with the
  descriptors coming from LSPosed remote files.

## Consequences

- No code change. ADR 0018 records this check in its section "Hook alternative re-checked (owner, #150)".
- Cucuto still needs only the System Framework scope for notification sounds. The opt-in WhatsApp scope stays
  limited to call ringtones (ADR 0051, ADR 0052).
- Sources checked (AOSP `android-17.0.0_r1`): `packages/SystemUI/src/com/android/systemui/media/RingtonePlayer.java`,
  `…/media/NotificationPlayer.java`, `media/java/android/media/IRingtonePlayer.aidl`,
  `media/java/android/media/MediaPlayer.java` (`setDataSource(Context, Uri, …)`, `attemptDataSource`),
  `media/java/android/media/RingtoneManager.java`,
  `services/core/java/com/android/server/notification/NotificationAttentionHelper.java` (`playSound`), and
  `system/sepolicy` `private/mls`, `private/seapp_contexts`, `private/platform_app_all.te`.
