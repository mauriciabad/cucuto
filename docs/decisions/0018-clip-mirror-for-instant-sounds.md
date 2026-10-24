# 0018. Clip mirror: SystemUI plays clips without starting Cucuto

- Status: accepted (owner may override the shared-storage default: #150)
- Date: 2026-10-24
- Issues: #121 (follow-up to #88)

## Context

The owner wants each notification to play "at the same instant they play in stock whatsapp" and battery life
preserved (#88). The hook swaps WhatsApp's sound for a `content://app.mauri.cucuto.clips/…` URI, and SystemUI's
`NotificationPlayer` opens it with `MediaPlayer.setDataSource(Context, Uri)`. If Cucuto's process is not running,
`ContentResolver` blocks while the ActivityManager forks the process, binds the application and publishes
`ClipProvider`. The vibration starts in `system_server` without waiting.

**Measured on the owner's Pixel 10 Pro** (Android 17, debug build, LSPosed module enabled, no reboot). The figure is
the time SystemUI spends opening the clip: from `RingtonePlayer: in run` to `RingtonePlayer: requesting AudioFocus`.
`NotificationPlayer` then waits its fixed 500 ms focus ramp before `player.start` on every path.

| Path | Open time (ms) | App process started? |
| --- | --- | --- |
| Stock tone, `content://settings/system/notification_sound` | 51, 20 | no |
| Cucuto clip, provider, process warm | 40, 82, 48 (self-test); 17, 26, 25, 61 (probe) | no |
| Cucuto clip, provider, process dead | **464, 422** (self-test); **377, 367, 347, 405** (probe) | yes (`am_proc_start … content provider`) |
| Cucuto clip, `file://` in `Android/media/app.mauri.cucuto/`, process dead | 42, 28, 23, 20 (probe) | no |

How it was measured, without touching LSPosed or rebooting:

- **Self-test:** post the Diagnostics test notification and snooze it for 15 s (`cmd notification snooze`). Kill
  the app process (`kill -9`, like the low-memory killer), then let the repost alert. The hook swapped the sound
  every time (module log `SWAPPED … (self-test)`).
- **Probe:** a throwaway `app_process` program, run as uid 1000, calls `IRingtonePlayer.playAsync(uri, user 0, …)`
  exactly like `NotificationAttentionHelper.playSound`. This tested URIs the installed module cannot produce yet.
- The logs came from a streaming `logcat` of `RingtonePlayer`, `NotificationPlayer` and `am_proc_start`. The main
  buffer wraps within seconds on this phone, so a dump taken afterwards misses lines.

So a cold start adds about **0.35 to 0.45 s** before the first sound. The vibration plays that much earlier than the
sound. It happens on the first message after Android has reclaimed Cucuto's process, which is the common case after
a quiet period. With the process warm, Cucuto is as fast as the stock tone. The release build starts faster, but it
still has to fork and bind a process.

## Options

1. **Keep the provider process alive** (foreground service, or `system_server` holding a provider connection to
   raise its priority). Removes the delay. But it pins a process in memory forever and keeps it out of the
   cached-app freezer, which costs battery and memory and makes Android kill the user's other apps first. Rejected:
   the owner asked to preserve battery.
2. **Make the cold start cheaper** (a separate `:clips` process, lazy app initialisation). Still a zygote fork and a
   bind: about 150 to 250 ms, never "the same instant". Rejected.
3. **Pass a file descriptor.** `IRingtonePlayer.playAsync` takes only a `Uri`, and SystemUI opens it itself. An FD
   path would mean hooking SystemUI, which ADR 0002 rules out (System Framework scope only). Rejected.
4. **Serve from a provider that is always running.** Options: `system_server`'s own providers (SettingsProvider's
   ringtone cache, `com.android.bitmapoffload`, `com.android.textclassifier.icons`), or MediaStore. Hooking another
   component's provider is far more intrusive than one argument swap. SettingsProvider's cache holds one file per
   sound type. MediaStore entries in `Notifications/` would show up in the system sound picker and in every music
   app. Rejected.
5. **A clip mirror SystemUI reads as a plain file.** `MediaPlayer.setDataSource(Context, Uri)` opens `file://`
   paths directly in SystemUI. On this phone SystemUI runs as `systemui_app` and holds `MANAGE_EXTERNAL_STORAGE`, so
   it can read shared storage. Shared storage is served by MediaProvider's FUSE daemon, which is always running. The
   app's own media folder (`Context.getExternalMediaDirs()`, `Android/media/app.mauri.cucuto/`) needs no
   permission, and Android deletes it with the app. A `.nomedia` marker keeps the files out of MediaStore. Measured
   above: as fast as the stock tone, and no process starts. Paths inside the app's private `files/` are not an
   option: SystemUI cannot read them (DAC `0700` and SELinux MLS categories).

## Decision

Option 5, entirely in the app. The module needs no new code for pack clips.

- **`ClipMirror`** (`:core:data`) gives each clip the URI the routing table publishes. `MediaClipMirror` (`:app`)
  copies the clip to `Android/media/app.mauri.cucuto/clips/<packId>/<version>/<path>`. It writes a temporary file
  and renames it, so SystemUI never reads half a file. It then returns `file:///storage/emulated/0/…`. Each
  installed version is an immutable folder (ADR 0008), so a pack update changes the URIs, republishes the table and
  copies the new files. A copy is current when it has the source's size and is not older than it. Each copy is
  checked once per process, so rebuilding the table does not stat every clip again.
- **`ClipMirrorStore.retain`** runs after every publication. It deletes copies the published table no longer
  references, but keeps anything handed out since the previous publication. So a table that is still being built
  never loses a file it already points at.
- **When it is used.** The mirror is used only when Cucuto runs as the system user, SystemUI holds
  `MANAGE_EXTERNAL_STORAGE` (checked with `PackageManager.checkPermission`, and SystemUI is made visible with
  `<queries>`), shared storage is mounted, and the folder can be created. Otherwise, and for any clip that cannot be
  copied, the table keeps the `ClipProvider` URI exactly as before. `ClipProvider` stays as the fallback, with
  unchanged rules.
- **Self-test.** The routing table gains an optional `S` line with the mirrored Diagnostics chirp
  (`RoutingTable.selfTestClip`, `docs/routing-table.md`). `AlertRouter` plays it when it is present and the
  provider's chirp otherwise. Readers that predate the line skip it.
- **Battery.** Nothing new runs in the background. There is no service, alarm, wake lock or kept-alive process. The
  copy happens when the app republishes the table, which it already does when assignments or packs change. The
  sound path no longer starts the app at all. The only remaining process starts are event delivery (ADR 0010: at
  most one per 10 s while chats alert, off the sound path).

## Consequences

- **Takes effect without a reboot for pack clips.** The module passes the table's URI through unchanged, so after
  the app update republishes the table, WhatsApp alerts use the mirror. The self-test switches to the mirror only
  after the next reboot, once the module code that reads the `S` line is running. Verification steps:
  [`docs/device-testing.md`](../device-testing.md), section 6.
- **The clips also live in shared storage.** File-manager apps (anything holding `MANAGE_EXTERNAL_STORAGE`) can see
  `Android/media/app.mauri.cucuto/clips/`, as they see WhatsApp's own `Android/media/com.whatsapp/`. Apps with only
  media permissions cannot, because the folder is not indexed. This is the same trade the brief already makes for
  phone ringtones (`Ringtones/cucuto/`), with less exposure. The owner can override it (#150).
- **A copy deleted behind the app's back plays nothing until it is repaired.** `NotificationPlayer` logs the error
  and stays silent; the same happens today when a pack file is missing. The app repairs the mirror every time it
  rebuilds the table: on each process start (event delivery starts it after every alert burst) and on every
  assignment or pack change. `system_server` cannot check the file without IO on the notification path.
- **Space:** clips are short Opus or WAV files (tens of KB each); only clips assigned to someone are mirrored.
- **Device dependency:** SystemUI's `MANAGE_EXTERNAL_STORAGE` is true on the Pixel. On a ROM without it, the check
  fails and everything stays on the provider, as before.
- **Side finding:** each event delivery logs `W Binder: Outgoing transactions from this process must be
  FLAG_ONEWAY` with a stack trace in logcat. `system_server` warns about every two-way Binder call it makes to an
  app. The call runs on the module's own `cucuto-events` thread, so it never blocks a notification. It is log noise
  at most once per 10 s, left for a follow-up.
