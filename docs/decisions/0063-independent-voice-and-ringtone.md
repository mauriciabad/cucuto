# 0063. Message voice and ringtone are independent choices; ringtone previews from the start

- Status: accepted (owner update 2026-10-24, #245 and #246; the defaults below can be overridden on those issues)
- Date: 2026-10-24
- Issues: #245 (independent choices, capability filters), #246 (ringtone previews)
- Amends: [ADR 0017](0017-home-recent-senders-and-picker.md) (picker), [ADR 0039](0039-phone-ringtones-on-the-device.md)
  (data), [ADR 0057](0057-ringtone-picker-and-ringtone-backups.md) (mixing, previews, backups)

## Context

The brief said "assigning a character assigns both its notification clips and its ringtones", with mixing as the
exception. ADR 0057 built on that: a phone ringtone with no character of its own follows the voice, and picking the
voice's character in the ringtone picker means "follow". The character picker listed every character, and the
ringtone picker every character with ringtones.

The owner, 2026-10-24 (#245, verbatim in `docs/owner-log.md`): "i should be able to pick a diferent character for the
ringtone than the notification. usually packs will be just one of them, so if tweet the bird has no ringtone, i can
easily select one from the pther packs. an dif it had, i should be able to change it also. and when selecting pack
there should be a filter based on this so i see the apropiate ones."

And (#246): "on the kk pack, the sounds should be ringtones, not notifications. and when playing they should play
longer and from the beginig."

Most packs offer only one of the two: creature packs have clips, song packs (the private K.K. Slider pack, rebuilt as
ringtone-only) have ringtones. The pack format already allows both kinds of character
([pack format](../pack-format.md#character-fields)).

## Decision

### Two choices per contact

A contact has a **message voice** (the character whose clips play for messages) and a **phone ringtone** (the
character that rings for calls). They are chosen separately: contact detail shows two rows, **Messages** and **Phone
calls**, near the top, and each opens its picker with one tap.

How the ringtone relates to the voice:

1. **It follows the voice until the user picks one.** A contact whose ringtone was never picked rings with its voice's
   character, when that character has ringtones.
2. **A voice without ringtones keeps what rang before.** If the new voice has no ringtones, the ringtone stays as it
   was: empty if nothing rang, otherwise the character that rang last. It keeps following: a later voice with
   ringtones rings again.
3. **An explicit ringtone never changes with the voice.** Picking any character in the ringtone picker other than the
   voice's own stores it. Changing or removing the voice leaves it alone.
4. **Picking the voice's own character follows again.** That is how the user goes back to rule 1.

Removing the voice keeps the phone ringtone (rule 2 or 3). A 1:1 chat with no contact yet gets one when its ringtone
is saved, so a chat can ring before it has a voice.

**Data.** `phone_ringtones` gains `followsVoice` (database version 4; the migration sets it for rows without their own
character, so existing choices keep their meaning). The character columns keep their role: null means "the voice's
character". A following row whose voice has no ringtones stores the character that rang last there, so the sync and
the backup need no new logic (`COALESCE(own, voice)`, as before). The chat directory updates the row whenever it
changes the voice (`RoomChatDirectory.assign` and `unassign`, through `PhoneRingtoneFollow`):

| Row | New voice has ringtones | New voice has none, or no voice |
| --- | --- | --- |
| follows, no stored character | follows it | stays empty, or keeps the old voice if it had ringtones |
| follows, stored character (kept) | follows it (stored character cleared) | keeps the stored character |
| explicit | unchanged | unchanged |

Whether a character has clips or ringtones comes from the installed `pack.json` (`VoiceCharacter.ringtoneCount`, next
to `clips`).

### Capability filters

- The **character picker** (messages) lists only characters with at least one notification clip. A ringtone-only
  character, such as a K.K. Slider song, never appears there.
- The **ringtone picker** lists only characters with at least one ringtone (as before).
- Both pickers get **pack filter chips** when two or more packs have eligible characters: "All" plus one chip per such
  pack, each with its number of eligible characters ("K.K. Slider · 98"). Packs with none are not offered.
- **Empty states** (no eligible character at all) explain what is missing and link to **Packs** and **Discover**.

### Ringtone previews (#246)

Ringtone previews play the pack's file **from 0 ms** (never from a loop point, and never an auto-cut clip), for
**30 s** or the whole file when it is shorter, with a play/stop button and a progress bar. They use ringtone audio
attributes, as in ADR 0057, and stop when the screen stops. This applies everywhere a ringtone plays:

- the ringtone picker (each version, and a tap on a cover);
- the Phone calls row on contact detail (the version that rings now);
- the character list of a pack's detail screen on Packs.

Notification clip previews stay short and unchanged (ADR 0017).

### Packs: what each character offers

A pack's detail screen lists each character with what it offers: **Messages**, **Ringtone**, or both. A tap on the
picture plays a clip; a ringtone has its own play/stop button with progress. A ringtone-only character's picture plays
its ringtone.

### Backups

No new field. `ringtone.followsVoice` (ADR 0057) now means "follows the voice whenever it has ringtones", and `packId`
and `characterId` name the character that rang at backup time: the voice's, or the one kept while the voice had no
ringtones. A restore stores no character when it names the restored voice, and keeps it otherwise. A contact may have a
ringtone and no message voice. The valid fixture gains both cases. Older readers restore a kept ringtone as "follow",
which rings nothing until the voice has ringtones.

## Consequences

- The brief's Linking rule, Screens 3 and 4, and the ringtone UI paragraph change (owner update 2026-10-24, #245 and
  #246). ADR 0017, 0039 and 0057 point here.
- One migration (3 to 4) and a `MigrationTest` case. No new permission and no module change: routing reads only the
  voice, Telecom only the contact's ringtone.
- `RingtonePreview.play` takes the start position (always 0 for ringtones) and the playing state carries the preview
  length for the progress bar.
- Tests: the filters (`PickerFiltersTest`), the follow and override rules (`PhoneRingtoneFollowTest` on Room), the
  picker's selection rules, preview start positions and lengths (`RingtonePreviewWindowTest`), backup export and
  restore of kept ringtones, and the migration. Goldens: contact detail, picker, ringtone picker and Packs, light,
  dark and large font.
- Defaults the owner may change (#245, #246): chips appear from two eligible packs; removing a voice keeps the
  ringtone; previews last 30 s.
