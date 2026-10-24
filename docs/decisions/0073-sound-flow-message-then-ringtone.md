# 0073. The sound flow: the message sound, then the ringtone, with "Android default" and "Leave as is"

- Status: accepted (owner update 2026-10-24, #298; the defaults below can be overridden on #282); contact detail's
  Phone calls row and set-up card amended 2026-10-24 (#348), the rule chips moved to the rules screen (#347, ADR 0074);
  undo without a link amended 2026-10-24 (#369); an explicit choice overrides a change in Contacts, amended
  2026-10-24 (#372)
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
| Changed in Contacts after Cucuto cleared it | "Changed in Contacts", left alone until "Use Cucuto's ringtone again" or a new save in the picker | untouched |

Turning phone ringtones off, unlinking, or switching to Leave as is gives an Android default contact its original
ringtone back, as for ring files (ADR 0039).

Amended 2026-10-24 (#369): Android default and Leave as is give the contact back from the undo record by its lookup
key, whether or not the chat is still linked, and only while the contact still has what Cucuto left there. A chat
without a link has no Android contact to clear, so for it Android default means "give back the recorded contact, then
nothing". The sync writes Android default before rendering any ring file. "Change" under the Phone calls row no longer
unlinks, and a one-time repair re-links chats that lost their link this way (ADR 0039's amendment).

Amended 2026-10-24 (#372): saving any choice in the ringtone picker or the rules screen is a fresh decision. It
clears "Changed in Contacts" and takes the contact's current ringtone (unless it is Cucuto's own) as the one to give
back later. So after a change in Contacts, picking a character writes Cucuto's file, Android default clears the
contact, and Leave as is keeps the ringtone set in Contacts. Only passive syncs keep respecting a change in Contacts
(ADR 0039's amendment). While they do, the header says "Rings with the ringtone set in Contacts" and the Phone calls
row "Set in Contacts", never the character.

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
  ("Ringtone: K.K. Slider", "Ringtone: Android default").
- **In transitions** (owner update 2026-10-24, #339): the avatar and its cover move as one group. The cover is its own
  shared element (`SharedCompanion`, keyed by the chat) drawn above the avatar's in the shared transition overlay, so
  it never hides under the flying avatar or pops above it when the transition starts or ends; its size and offset
  interpolate from the row to the header. A screen that shows the avatar without the cover (the character picker,
  the recent senders strip) keeps an empty, zero-size anchor at the avatar's corner, so the cover shrinks into or
  grows out of the avatar.
- The **Phone calls** row says what rings: the character with its rule, "Android default", "Left as is", or "No
  ringtone yet". ~~For a character with several versions, "See and edit the rules" under it opens the rules screen of
  [ADR 0074](0074-ringtone-rules-screen.md).~~

#### Contact detail says it once (amended 2026-10-24, #348)

Contact detail said what rings three times: in the header, in the Phone calls row, and in the "Phone ringtone" card
further down ("Rings with …", with the linked contact and the WhatsApp hint). Now:

- The **header** keeps "Rings with …" under the name: it is who the contact is, as "Speaks as …" is.
- The **Phone calls** row is the one place to see and change the ringtone. A tap opens the ringtone picker; the
  picker's "Which version rings" row opens the rules screen (#347, [ADR 0074](0074-ringtone-rules-screen.md#the-picker)).
  The row has no rules link of its own. While the contact is linked and phone ringtones are on, two detail lines sit
  under it: "Android contact: Ada Lovelace" with **Change** (link another contact), and, for a character ringtone, the
  WhatsApp hint (#145).
- The **set-up card** shows up only when it asks for something: turn phone ringtones on, link the Android contact,
  "Ring with …" for a ringtone that is not in use yet, allow the phone permission ("Random per call"), or fix a problem
  (the contact is gone, "Changed in Contacts", the contacts permission, a write or render failure). It has no
  "Rings with …" line, is titled "Phone calls" like the row ("Phone ringtone" was a second name for the same thing),
  and sits right under the Sounds card. "Preparing the ringtone…" is not shown: it lasts a moment and needs nothing.

### Pickers

The character picker lists only characters with notification clips, and the ringtone picker only characters with
ringtones (ADR 0063, verified by `PickerFiltersTest`). The pack filters and the empty states stay. The ringtone picker
also shows its two choices when no pack has ringtones.

### Backups

`contacts[].ringtoneChoice` (optional, `formatVersion` 1): `"android-default"` or `"unchanged"`, in place of
`ringtone`. A contact never has both. A restore stores the choice with the voice rule of ADR 0013 (applied, unchanged
or kept). An Android default contact is matched to an Android contact on the new phone like any phone ringtone
(ADR 0057), so its own ringtone can be cleared there. "Leave as is" needs no match: Cucuto writes nothing for it.
Cucuto before 0.2.0 refuses the whole backup instead: its schema allows no unknown contact field (corrected
2026-10-24 in the ringtone review; it said older readers ignore the field). Backwards compatibility is not a goal
(#67). The valid backup fixture gains both cases, and an invalid fixture has both `ringtone` and `ringtoneChoice`.

## Consequences

- The brief's Linking rule, the ringtone UI paragraph and Screens 2, 3 and 4 change (owner update 2026-10-24, #298).
  ADR 0063, 0057, 0039, 0017 and 0025 point here.
- One migration (5 to 6) and a `MigrationTest` case. No new permission. The module doesn't change: Telecom plays the
  contact's ringtone (none, for Android default).
- Android default writes to the Android contact only when it has a ringtone of its own, and records it first, so
  every write can be undone (ADR 0039).
- `CucutoDestination.Picker` gains `ringtoneNext` (Home's flows set it), and `CucutoDestination.RingtonePicker` gains
  `step` (step 2 of the first-assign flow).
- #348: `PhoneRingtoneUiState.needsAction` decides the card; `PhoneRingtoneContentTest` and `ContactDetailContentTest`
  cover the card and the row's detail lines. Goldens: contact detail, phone ringtone.
- Tests: the sync for Android default (clear, nothing to clear, user change after a clear, restore on Leave as is and
  on turning off), the picker's choices and saving, the follow rule leaving explicit choices alone, the backup export,
  restore and fixtures, the migration, the Home and header ringtone display. Goldens: Home, contact detail, ringtone
  picker.
- Defaults the owner may change (#282): "not chosen yet" still follows a voice with ringtones; the ringtone step opens
  after every first message sound of a 1:1 chat from Home, also without ringtone packs; Android default clears a
  contact's own ringtone (with undo) instead of doing nothing.
