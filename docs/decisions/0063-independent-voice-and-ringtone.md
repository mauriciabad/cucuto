# 0063. Message voice and ringtone are independent choices; ringtone previews ring like the call

- Status: accepted (owner updates 2026-10-24, #245, #246 and #242; the defaults below can be overridden on those
  issues); `phone_ringtones` gains `rules` in [ADR 0074](0074-ringtone-rules-screen.md) (database version 5, #288)
- Date: 2026-10-24
- Issues: #245 (independent choices, capability filters), #246 (ringtone previews), #242 (preview length, pause)
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

And on #242 (item 3), about ADR 0057's 30 s preview: "preview should use the same logic as the actuall ringtone when
they call, so the stopping should be the same as that (wich i doubt is cut at 30s). we may need a way to pause."

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

### Ringtone previews (#246; owner update 2026-10-24, #242)

ADR 0057's owner update (#242 item 3) sets the rule: a preview plays what a call plays, from the beginning, with no
time limit, and can be paused. This ADR builds it:

- **The ring file's timeline.** `RingTimeline` turns the ringtone's `RingFilePlan` (the plan every renderer uses,
  [ADR 0033](0033-ring-files-and-auto-cut-clips.md), [pack format](../pack-format.md#ring-files)) into what sounds
  when: from 0 ms (never a loop point, never an auto-cut clip), the intro once, the loop section repeated (a looping
  ring file leaves the outro out, as ADR 0033 says), the silence after a `loop: false` version, and the fade-in at the
  start of each ring file, round and round as Telecom loops the file.
- **One `MediaPlayer`** (`MediaRingtonePreview`) follows that timeline: it seeks back at each loop end, pauses for the
  silence, and steps the volume up for the fade-in. It holds transient audio focus and stops when it loses it.
- **Play, pause and stop.** `RingtonePlayback` keeps one preview at a time. Pause keeps the place and play resumes it;
  stop goes back to the start. A ring around the play button goes round once per ring file, and holds still while
  paused. The version is the one the rule picks now, as in ADR 0057.

It applies everywhere a ringtone plays:

- the ringtone picker (each version, and a tap on a cover);
- the Phone calls row on contact detail (the version that rings now);
- the character list of a pack's detail screen on Packs, and the badge of a ringtone-only character on the Packs list
  and the import review.

Notification clip previews stay short and unchanged (ADR 0017).

### Packs: what each character offers

A pack's detail screen lists each character with what it offers: **Messages**, **Ringtone**, or both. A tap on the
picture plays a clip; a ringtone has its own play, pause and stop buttons with progress. A ringtone-only character's
picture rings its ringtone. The character badges on the Packs list and the import review say what each character
offers too, and a ringtone-only character's badge rings its song until tapped again.

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
- `RingtonePreview` rings a `RingSound` (file, loop, fade-in) and can pause, resume and stop; `RingtonePlayback` keeps
  the state the buttons and the progress ring read.
- The preview's loop seam is a `MediaPlayer` seek, so it can be a few milliseconds less seamless than the rendered
  ring file the phone rings with; Telecom plays the real file (ADR 0033, ADR 0039). Rendering a ring file for every
  preview would take seconds per tap.
- Tests: the filters (`PickerFiltersTest`), the follow and override rules (`PhoneRingtoneFollowTest` on Room), the
  picker's selection rules, the ring timeline from 0 ms with the pack format's examples (`RingTimelineTest`), ringing
  until stopped with pause and progress (`RingtonePlaybackTest`), backup export and restore of kept ringtones, and the
  migration. Goldens: contact detail, picker, ringtone picker and Packs, light, dark and large font.
- Defaults the owner may change (#245): chips appear from two eligible packs; removing a voice keeps the ringtone.
