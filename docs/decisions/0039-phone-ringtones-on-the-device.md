# 0039. Phone ringtones: ring files rendered on the phone, MediaStore, contact undo record, re-roll receiver

- Status: accepted (defaults; the owner may override them in #196); the "follow the voice" data is amended by
  [ADR 0063](0063-independent-voice-and-ringtone.md); the `choice` column (Android default, Leave as is) and the
  `cucuto:default` undo marker come from [ADR 0073](0073-sound-flow-message-then-ringtone.md)
- Date: 2026-10-24
- Issue: #52 (picker UI: #53; device run: #143)

## Context

ADR 0023 settled the mechanism for phone calls: Telecom plays a contact's `ContactsContract.Contacts.CUSTOM_RINGTONE`
itself, through a local `MediaPlayer` in `system_server`, so no hook is needed. "Random per call" re-rolls the
contact's ringtone after each call (#144). ADR 0033 fixed the ring file rule (intro once, loop repeated to 90 s,
Ogg Opus 48 kHz) and said the app renders it with `MediaCodec` and `MediaMuxer`. This ADR records how the app does all
of that with the least intrusion: what it stores, where the files live, which permissions it asks for and when, and
how every change can be undone.

## Decision

1. **`:core:ringtone` renders ring files on the phone** (`PlatformRingRenderer`):
   - `MediaExtractor` and `MediaCodec` decode the pack file.
   - `Resampler` brings it to 48 kHz: a windowed sinc (Blackman, 16 zero crossings). 48 kHz sources pass through
     untouched.
   - `RingFileReader` splices the `RingFilePlan` from pack-spec, the same rule and the same splice as `packer ring`.
     Splices are sample-exact and the fade-in happens once.
   - The platform Opus encoder writes Ogg through `MediaMuxer.MUXER_OUTPUT_OGG`, at 64 kb/s per channel.
   - If this device cannot encode Opus into Ogg, AAC-LC in `.m4a` is the fallback. Telecom's `MediaPlayer` plays both.
   - Pass-through plans copy compressed sources (Ogg, MP3, AAC) as they are. WAV and FLAC are always encoded.
   - MP3 and AAC codec priming (`encoder-delay`/`encoder-padding`) is trimmed, so loop points land where ffmpeg puts
     them.
2. **Ring files go to `Ringtones/cucuto/` through MediaStore**, as the brief and ADR 0023 say. The alternatives:
   - Our own provider: rejected. Telecom would cold-start Cucuto before the first ring (ADR 0018, M9 report §3.3).
   - `Android/media/` `file://` paths: rejected. Whether `system_server` opens them from a contact URI is untested,
     and the files would vanish with the app while contacts still point at them.

   MediaStore needs no permission for the app's own files, MediaProvider is always running, and uid 1000 opens these
   URIs (#143 N2).
   - Contacts store `content://media/external/audio/media/<id>`.
   - Each variant of a used character is exported once and shared by every contact with that character, so a re-roll
     or a boundary is only a contact write.
   - The file name is `<character>-<variant>-<hash>`. The hash covers the pack version, loop, fade and renderer
     version, so a pack update makes new files and never reuses a stale one. The title is "Character · Variant", which
     the Contacts app and the system ringtone list show.
   - Files nobody uses any more are deleted.
   - Trade-off: they also show up in Android's own ringtone list, as any ringtone in `Ringtones/` does.
3. **Data.** Database version 3 adds two tables:
   - `phone_ringtones`, one row per Cucuto contact: character (null means "follow the voice"), rule mode, fixed
     variant. Database version 4 adds `followsVoice`: the voice and the ringtone are independent choices, and a
     following row keeps the character that rang last while the voice has no ringtones
     ([ADR 0063](0063-independent-voice-and-ringtone.md), owner update 2026-10-24, #245). Modes are the backup schema's: `fixed`, `random`, `time`, `season`, `pack`. The default is `pack`, which
     with no `when` conditions is the first variant.
   - `contact_ringtone_writes`, the **undo record**, keyed by the Android contact's lookup key with no foreign key. It
     holds the contact's own ringtone before Cucuto, what Cucuto last wrote, and the current variant.

   The Cucuto ↔ Android link reuses `contacts.androidLookupKey`. Linking also stores the contact's E.164 numbers as
   phone keys, so a restore can re-match them (brief, Backup).
4. **Sync** (`RingtoneSync`, idempotent): compare each linked Android contact with what it should ring with, and write
   only the difference.
   - The original is recorded before the first write.
   - A contact that loses its phone ringtone (stopped, unlinked, linked elsewhere) gets its original back.
   - A ringtone that is neither Cucuto's last write nor one of its files was changed by the user. Cucuto stops
     writing that contact and never restores over it, until the user taps "Use Cucuto's ringtone again", which makes
     the user's latest ringtone the one to restore later.
   - Turning the feature off restores every contact and deletes every ring file. The choices stay for next time.
   - It runs on database changes while the app runs (debounced), once per app start (this repairs deleted files,
     brief risk table), at boundaries, and after calls.
5. **Re-roll after each call**: a manifest receiver for `TelephonyManager.ACTION_PHONE_STATE_CHANGED`, acting only on
   `EXTRA_STATE_IDLE`.
   - Needs `READ_PHONE_STATE`. It never reads the number (that would need `READ_CALL_LOG`).
   - It is enabled with `PackageManager.setComponentEnabledSetting` only while some linked contact uses "random per
     call" and the permission is granted. Otherwise Cucuto is never woken by calls.
   - Battery: one process start of well under a second per call. No listener stays registered, and nothing runs
     between calls.
   - It never renders (the files already exist). It runs inside `goAsync()` with an 8 s budget.
   - Rejected options:
     - `TelephonyCallback`: needs a live process, so a foreground service.
     - A call-log content trigger: needs `READ_CALL_LOG`, which is more sensitive.
     - `CallScreeningService`: needs a role, far more intrusive.
     - The Telecom hook: ADR 0023 keeps it as a fallback only.
6. **Boundaries** (day/night, hour ranges, midnight for weekdays, season starts): one inexact
   `AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP)`.
   - It needs no exact-alarm permission and wakes the phone a few times a day at most. A ringtone that switches a few
     minutes late is fine.
   - A second receiver (also disabled while the feature is off) re-applies the rules after `BOOT_COMPLETED`,
     `TIME_SET` and `TIMEZONE_CHANGED`. All three are exempt from the implicit-broadcast limits.
   - Day runs from sunrise to sunset at the place chosen for day and night clips (`SolarCalculator`, ADR 0037),
     and from 07:00 to 19:00 until a place is chosen. A polar day counts as day and a polar night as night.
   - Seasons use the northern hemisphere until a hemisphere setting exists.
7. **Permissions are asked in context, explained first.**
   - Contacts (`READ_CONTACTS` + `WRITE_CONTACTS`) are asked when the user turns phone ringtones on, in Settings or in
     a contact's detail screen. The dialog says what is read, what is changed, that contacts never leave the phone,
     and that the code is open source at github.com/mauriciabad/cucuto (the owner, #145).
   - `READ_PHONE_STATE` is asked only when the user picks "Random per call".
   - Denial leaves everything as it was and offers Android's app settings.
8. **UI** (the full picker is #53; it landed in [ADR 0057](0057-ringtone-picker-and-ringtone-backups.md), which moved
   the rule chips from the card into it):
   - A "Phone ringtone" card on contact detail (1:1 chats only). It offers: turn the feature on, link the Android
     contact (suggested by the WhatsApp phone JID's number, else by exact name, or picked with the system contact
     picker), the rule chips that make sense for the character's variant tags, version chips for "One version", the
     status, "Stop using for calls", and the WhatsApp hint (#145: WhatsApp rings by itself; pick the named file in
     the chat's call ringtone).
   - A switch in Settings.
9. **Not in this change:**
   - `vibration_uri` per-contact call vibration waits for #143 V2 (whether `system_server` can read our file).
   - Backup and restore of phone ringtones (#198): the restorer already counts them as pending (done in ADR 0057).

## Consequences

- No hook and no new module code. Phone ringtones work with the module disabled.
- The app now declares `READ_CONTACTS`, `WRITE_CONTACTS`, `READ_PHONE_STATE` and `RECEIVE_BOOT_COMPLETED` (in
  `:core:ringtone`'s manifest). All runtime ones are asked only on use.
- The Contacts app shows the ringtone (and its changes after calls for "random"). If Cucuto is uninstalled, contacts
  keep ringing with the last file, because MediaStore keeps it. The user can reset them in Contacts, or turn the
  feature off before uninstalling to restore them.
- Rendering parity with packer is tested against `spec/fixtures/ring-files.json` on the JVM (`RingFileReaderTest`).
  The codec path has an instrumented test (`PlatformRingRendererTest`) and device steps in `docs/device-testing.md`
  §7.
- Telecom loops whole files, so the wrap after 90 s or more goes back to the intro (ADR 0033).
