# 0010. Production hook, clip provider and the module → app event channel

- Status: accepted (owner may override the event-delivery default, see the `question` issue linked from the PR)
- Date: 2026-10-23
- Issues: #15, #16, #17

## Context

M0 settled the mechanics on the owner's Pixel 10 Pro (`CP3A.261005.005`, LSPosed 2.2.1, libxposed API 102):
`NotificationAttentionHelper.playSound(NotificationRecord, Uri)` and `playVibration(NotificationRecord,
VibrationEffect, boolean)` are reached only after every suppression rule, WhatsApp's alerting record is the group
summary whose shortcut ID is the conversation that just alerted (`…@lid`, `…@g.us`), SystemUI opens the clip URI,
`VibrationEffect.Builder` exists, and the module's log goes to LSPosed's modules log, not logcat
([M0 report](../m0-report.md), "Supervised device run"). ADR 0009 (#14) defines the routing table and its Remote
Preferences transport. The brief asks for under 5 ms on the notification path, no IO there, a shuffle bag, a global
switch, and a "seen sender" event from the module to the app through "the provider or a protected broadcast".

## Decision

**Hooks.** `:xposed` hooks exactly `playSound` and `playVibration`, resolved by name and arity (a missing or
ambiguous method is logged and skipped), registered with `ExceptionMode.PROTECTIVE`, with every body in
`try/catch(Throwable)`. They only substitute argument 1, so DND, muted chats, ringer mode, the burst limiter,
alert-once and group-alert rules stay AOSP's. Only `com.whatsapp` and `com.whatsapp.w4b` are routed; any other
package is dismissed after one `getSbn()` read. `buzzBeepBlinkLocked` is no longer hooked.

**Notification path.** One reflective snapshot of the record (shortcut ID, resolved shortcut, channel, title,
conversation title, group flag, newest MessagingStyle sender read key by key from `EXTRA_MESSAGES`), then pure
Kotlin: `ConversationKeys` (shortcut IDs first; the conversation title only when there is no shortcut at all;
group from the `group_chat_defaults_` / `individual_chat_defaults_` channel prefix, then the `@g.us` domain, then
the MessagingStyle flag) and `AlertRouter` over the in-memory `RoutingTable` (a volatile read; the table is attached
and reloaded off the notification path). The decision is computed once per record and shared by both hooks. Every
alert is timed with `SystemClock.elapsedRealtimeNanos`; anything over 5 ms is logged (rate-limited). Classes and the
reflective haptics API are warmed up on a background thread after boot.

**Shuffle bag.** One weighted bag per speaker (`package + key`), bounded LRU of 512. Weights are honoured at quarter
resolution (pack weight × 4 copies, 1–400). The same clip never plays twice in a row, also across a refill and
across a table republish (the bag remembers the last clip by URI).

**Haptics.** `Haptic.Custom` presets and basic envelopes are built by `:core:haptics`
(`HapticEffects.create`, [ADR 0012](0012-haptics-effects-and-auto-haptics.md); wired in #96): `VibrationEffect.Builder`
when it is available (probed by reflection, so it also works on the owner's 37.1 build inside `system_server`),
otherwise a basic envelope, a `Composition` or a waveform. The vibrator's `HapticCapabilities` are read once, through
`NotificationAttentionHelper`'s Context, on the first custom haptic; each effect is cached per haptic (bounded LRU,
failures included). `Keep` and unbuildable effects keep WhatsApp's vibration; `Auto` uses the envelope of the clip
`playSound` played (`forClip` on the shared decision); `Off` (per-contact override, ADR 0009) skips the vibration.

**Clip provider (#16).** `content://app.mauri.cucuto.clips/<packId>/<characterId>/<path>` serves audio files from the
active version of an installed pack (`files/packs/`, ADR 0008). Exported, read-only, and checked on every call:
`Binder.getCallingUid()` must be uid 1000 (any user) or belong to `com.android.systemui` by
`PackageManager.getPackagesForUid()`; the integer uid of SystemUI is never hard-coded (it differs per device). Every
segment must match the pack format's ID, slug and path grammar, only audio extensions are served, and the canonical
file must stay inside the pack version folder (no `..`, encoded separators or symlink escapes).

**Module → app events (#17).** A `ContentResolver.call("report")` from `system_server` to
`content://app.mauri.cucuto.events`, whose provider accepts only uid 1000. Chosen over a protected broadcast because
it is synchronous (the module knows whether the batch landed), needs no custom permission, and works from the
system context (user 0) without `sendBroadcastAsUser` warnings. Before each call the module checks that the
provider belongs to its own app: same package name as `getModuleApplicationInfo()`, and the installed package
holds every certificate that signed the APK the module was loaded from (read once after boot; an app update
replaces the file but the loaded module keeps running until the next reboot), so an app squatting the authority
receives nothing. The first version compared `getModuleApplicationInfo().uid` with the provider's uid; on the
device that never matched, because LSPosed builds the module's `ApplicationInfo` for `system_server` by parsing
the APK file, so its `uid` is not the installed app's (device finding, #17). Every refused or failed delivery is
logged with its reason (rate-limited), and the first success after boot or after a failure is logged once. Delivery runs
on a dedicated background `HandlerThread`: the hook only appends to a bounded queue (200, oldest dropped) and posts.
Batches of up to 50 go out 2 s after the first event, at most once every 10 s, backing off from 30 s to 15 min on
failure. Each event carries time, package, raw key, display name, group flag, sender, matched contact, outcome,
clip and timing; the app writes it to the private Room decision log and the "recent senders" feed. Raw keys and
names never reach any log: the module's log line keeps only the JID domain. A module health report (hooks, table
generation, counters) rides along with every batch for the Diagnostics screen.

**Self-test.** The app's own notifications on channel `cucuto_self_test` are swapped for a synthesised chirp
(`content://app.mauri.cucuto.clips/selftest/chirp.wav`; `selftest` can never be a pack ID) and a click-tick, so the
Diagnostics screen can test hook, provider, haptics and event delivery without a WhatsApp message. Nothing else from
the app's package is touched. Android's notification cooldown (politeness) mutes alerts before `playSound` is ever
called (device finding, #15): during an "avalanche" (2 min after boot, airplane mode off, a profile becoming
available) every non-exempt alert is muted, and an app's alert less than 10 s after its previous one is muted
(`config_notifCooldownMuteMillis`; within 60 s it only plays quieter). So the test notification uses
`CATEGORY_REMINDER` (avalanche-exempt at full volume, as WhatsApp's conversations are) and the app refuses to post
it again within 12 s.

**Debug test voice.** Until the assignment UI exists (M2/M3), debug builds offer "Give the latest sender a test
voice" on the Diagnostics screen: it writes a generated three-chirp pack (`app.mauri.cucuto.devtest`) in the
importer's layout and assigns it through the normal repositories, so the device session can verify the real
WhatsApp path. `PackLibrary` in `:app` is the importer → database glue: every pack the importer activates is
recorded in `installed_packs`, which the routing table reads.

## Consequences

- Event delivery starts the app process when it is not running, at most once per 10 s while chats are alerting.
  The alternative (hold events in `system_server` until the app opens) needs an app → module query channel that
  libxposed does not offer; the owner can choose (question issue).
- The per-sender group policy depends on the summary record carrying the newest sender, which M0 could not confirm;
  if it does not, groups fall back to the group's voice (ADR 0009 default).
- Release builds shrink `:xposed` with R8; only the entry class is kept by name. The module reflects only on framework
  classes, never on its own.
- End-to-end confirmation on the phone is a supervised session: [`docs/device-testing.md`](../device-testing.md).

## Owner answer (2026-10-23, #88)

> I dont understand the question not the changes in behaveour. i want the notifications to play at the same instant they play in stock whatsapp. and i also want to preserve baterylife.  i close the issue because i feel like my answer wont be educated, if i find out an unexpected behaveour because of this, you can revisit this

The owner closed the issue, so the delivery default stands (batches, at most one app wake-up per 10 s). The comment
sets two requirements for the whole notification path, now an M1 acceptance criterion in the brief:

- **Same instant as stock WhatsApp.** Event delivery complies: the hook only queues and posts, and the Binder call
  runs later on the `cucuto-events` thread, so `playSound` and `playVibration` never wait for it. The gap is elsewhere:
  SystemUI opens the clip from `ClipProvider`, and when Cucuto's process is not running Android cold-starts it
  (Hilt graph, `RoutingPublisher.start()`) before the first byte is served, while the vibration from `system_server`
  is not delayed. #121 measured it at 0.35 to 0.45 s, and
  [ADR 0018](0018-clip-mirror-for-instant-sounds.md) removes it: clips are mirrored to shared storage and SystemUI
  reads them as `file://` paths, so no process starts on the sound path. `ClipProvider` remains the fallback.
- **Battery.** Delivery uses `Handler.postAtTime` on `uptimeMillis`: no alarm, no wake lock, no service, so it never
  wakes the phone and only runs while the CPU is already awake after an alert. Its cost is at most one process start
  and a Room write per 10 s during active chats. #121 checks it with batterystats and, if it shows, defers delivery
  while the screen is off.
