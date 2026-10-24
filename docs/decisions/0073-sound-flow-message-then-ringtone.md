# 0073. The sound flow: the message sound, then the ringtone, with "Android default" and "Leave as is"

- Status: accepted (owner update 2026-10-24, #298; the defaults below can be overridden on #282)
- Date: 2026-10-24
- Issue: #298
- Amends: [ADR 0063](0063-independent-voice-and-ringtone.md) (ringtone choices, first-assign flow, display),
  [ADR 0057](0057-ringtone-picker-and-ringtone-backups.md) (picker, "Stop using for calls", backups),
  [ADR 0039](0039-phone-ringtones-on-the-device.md) (data, sync, undo record),
  [ADR 0017](0017-home-recent-senders-and-picker.md) (Home rows, assigning from Home),
  [ADR 0025](0025-contact-detail-and-simulate-message.md) (contact detail header)

## Context

The owner, 2026-10-24 (#298, verbatim in `docs/owner-log.md`, seen on v0.1.7): "i think this flow has to be revisited
because the usual will be that a pack is either notifications or ringtones, it will be rare that a character has both
to select only once. mos of the times user needs to pick the notification and then the ringtone (this also includes
not using the pack ringtone and using the default android one. or also just picking a ringtone). and would be nice to
also see the ringtone in the header part of the person chat details (and in the home list)".

ADR 0063 (#281) already made the two choices independent: the voice picker lists only characters with clips, the
ringtone picker only characters with ringtones, and contact detail has a **Messages** row and a **Phone calls** row.
What was missing:

- Assigning from Home picked only the message sound. The ringtone needed a second trip through contact detail.
- The ringtone picker offered only pack characters. "Not the pack's ringtone, the phone's normal one" had no clear
  answer: "Stop using for calls" sat at the bottom of the set-up card, and a ringtone the user never picked kept
  following a voice that has ringtones.
- Home and the contact header showed only the message character.

## Decision

### Two picks: the message sound, then the ringtone

Picking the message sound and then the ringtone is the main flow. A character that has both is the rare case, and
nothing depends on it.

- **From Home** (a recent sender, a chat without a voice, or "Assign a voice"), the character picker assigns the message
  sound. For a 1:1 chat it then hands over to the ringtone picker as **step 2**: the same screen, with the subtitle
  "Step 2 of 2 · optional" and a **Skip** button in the top bar. Skip, back and saving all return to Home. Groups stop
  after step 1: phone calls come from people (#52).
- The step opens even when no installed character has ringtones. It then shows the empty state (links to Packs and
  Discover) with the two choices below, so the user learns where ringtones come from and can still pick "Android
  default" or "Leave as is".
- **From contact detail**, the Messages row opens the character picker and the Phone calls row opens the ringtone
  picker, each on its own, and both return to contact detail.

### What the ringtone can be

The ringtone picker offers three kinds of choice. Below the chosen character, the first section is **Other choices**
with two chips, then the album covers. The chosen one explains itself in the header, where the record would be:

1. **A Cucuto character**: any character with ringtones, with or without a message voice (ADR 0063).
2. **Android default**: the contact rings with the phone's default ringtone.
   - If the linked Android contact has a ringtone of its own (set in Contacts, or by Cucuto earlier), Cucuto clears it
     (`CUSTOM_RINGTONE` = null). The undo record keeps the ringtone it had before Cucuto first changed it.
   - If the contact has no ringtone of its own, Cucuto writes nothing.
   - Like any phone ringtone, this needs the linked Android contact and phone ringtones on. The set-up card stays on
     contact detail for it.
3. **Leave as is**: Cucuto never touches this contact's ringtone, whatever it is.
   - If Cucuto wrote one earlier, the contact gets its own ringtone back from the undo record. After that, Cucuto
     stops writing it.
   - This replaces "Stop using for calls" on the set-up card. A contact that never had a phone ringtone is also left
     as is. The difference is that "Leave as is" is a stored choice: a later voice with ringtones, or linking the
     contact, does not make it ring.

**Not chosen yet** (nothing stored, or a row that still follows the voice) keeps ADR 0063's rule: the ringtone follows
the message character when that character has ringtones. The owner asked to revisit this default. It stays because it
only matters in the rare case, where it saves a step, and because the main flow now always offers the ringtone step
right after the message sound. Every explicit choice (a picked character, Android default, Leave as is) never changes
with the voice.

How the two new choices compare, for a linked contact with phone ringtones on:

| The Android contact's ringtone | Android default | Leave as is |
| --- | --- | --- |
| None (the phone's default) | nothing written | nothing written |
| Its own (set in Contacts) | cleared, kept in the undo record | untouched |
| Cucuto's ring file | cleared (the undo record keeps the original) | the original comes back |
| Changed in Contacts after Cucuto cleared it | "Changed in Contacts", left alone until "Use Cucuto's ringtone again" | untouched |

Turning phone ringtones off, unlinking, or switching to Leave as is gives an Android default contact its original
ringtone back, as for ring files (ADR 0039).

### Data

- `phone_ringtones.choice` (database version 6, after ADR 0074's `rules`): `CHARACTER` (the default; every existing
  row), `ANDROID_DEFAULT` or `UNCHANGED`. The two new choices store no character, `followsVoice` false and the pack
  rule, so `PhoneRingtoneFollow` leaves them alone.
- The sync query leaves out `UNCHANGED` rows, so the sync restores those contacts exactly like contacts without a row.
  `ANDROID_DEFAULT` rows are synced with no character: the wanted ringtone is "none".
- The undo record's `writtenUri` holds `cucuto:default` after Cucuto cleared a contact. "Changed elsewhere" means the
  same as before: the contact now rings with something Cucuto did not leave there (any ringtone at all, after a
  clear). Restoring compares against "no ringtone" for that marker.
- `Chat` gains `ringtone` (the stored choice), so Home and contact detail read it with the chat list. Nothing else
  changes in the routing table: the module never reads phone ringtones.

### Showing the ringtone

- **Contact detail header**: the message character's avatar with the ringtone's record cover tucked against it, and a
  second line under "Speaks as …": "Rings with K.K. Slider", or "Rings with the phone's default ringtone". There is no
  line and no cover for "Leave as is", or when nothing rings.
- **Home list rows**: a small record cover (24 dp) tucked against the avatar for a character ringtone, or a call icon
  for Android default, as in the header, so the row's text keeps its width. TalkBack reads it with the row
  ("Ringtone: K.K. Slider", "Ringtone: phone default").
- The **Phone calls** row says what rings: the character with its rule, "Android default", "Left as is", or "No
  ringtone yet". For a character with several versions, "See and edit the rules" under it opens the rules screen of
  [ADR 0074](0074-ringtone-rules-screen.md).

### Pickers

The character picker lists only characters with notification clips, and the ringtone picker only characters with
ringtones (ADR 0063, verified by `PickerFiltersTest`). The pack filters and the empty states stay. The ringtone picker
also shows its two choices when no pack has ringtones.

### Backups

`contacts[].ringtoneChoice` (optional, `formatVersion` 1): `"android-default"` or `"unchanged"`, in place of
`ringtone`. A contact never has both. A restore stores the choice with the voice rule of ADR 0013 (applied, unchanged
or kept). An Android default contact is matched to an Android contact on the new phone like any phone ringtone
(ADR 0057), so its own ringtone can be cleared there. "Leave as is" needs no match: Cucuto writes nothing for it.
Older readers ignore the field and leave the contact's ringtone alone, which is "Leave as is". The valid backup fixture
gains both cases, and an invalid fixture has both `ringtone` and `ringtoneChoice`.

## Consequences

- The brief's Linking rule, the ringtone UI paragraph and Screens 2, 3 and 4 change (owner update 2026-10-24, #298).
  ADR 0063, 0057, 0039, 0017 and 0025 point here.
- One migration (5 to 6) and a `MigrationTest` case. No new permission. The module doesn't change: Telecom plays the
  contact's ringtone (none, for Android default).
- Android default writes to the Android contact only when it has a ringtone of its own, and records it first, so
  every write can be undone (ADR 0039).
- `CucutoDestination.Picker` gains `ringtoneNext` (Home's flows set it), and `CucutoDestination.RingtonePicker` gains
  `step` (step 2 of the first-assign flow).
- Tests: the sync for Android default (clear, nothing to clear, user change after a clear, restore on Leave as is and
  on turning off), the picker's choices and saving, the follow rule leaving explicit choices alone, the backup export,
  restore and fixtures, the migration, the Home and header ringtone display. Goldens: Home, contact detail, ringtone
  picker.
- Defaults the owner may change (#282): "not chosen yet" still follows a voice with ringtones; the ringtone step opens
  after every first message sound of a 1:1 chat from Home, also without ringtone packs; Android default clears a
  contact's own ringtone (with undo) instead of doing nothing.
