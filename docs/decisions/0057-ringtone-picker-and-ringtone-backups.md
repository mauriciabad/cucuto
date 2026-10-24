# 0057. The ringtone picker, and phone ringtones in backups

- Status: accepted (defaults the owner may override: #242)
- Date: 2026-10-24
- Issues: #53 (ringtone picker UI), #198 (back up and restore phone ringtones)

## Context

[ADR 0039](0039-phone-ringtones-on-the-device.md) made phone ringtones work: ring files rendered on the phone, the
`phone_ringtones` table, the undo record and a "Phone ringtone" card on contact detail with rule chips. It left two
things out: the picker the brief describes ("a grid or carousel of album covers; tapping one plays it with a
spinning-record animation, variant chips switch versions") with mixing ("messages from Pikachu, ringtone from K.K.
Slider"), and backups. The backup schema already had `contacts[].ringtone`, but nothing wrote or restored it.

The owner prefers screens over dialogs ([ADR 0055](0055-screens-over-dialogs.md)). Read: the Material 3
[chips](https://m3.material.io/components/chips/guidelines) and [lists](https://m3.material.io/components/lists)
guidelines, `AudioAttributes.USAGE_NOTIFICATION_RINGTONE` (what Android's own ringtone picker plays previews with),
and `ContactsContract.Contacts.CONTENT_LOOKUP_URI` and `PhoneLookup` for re-matching contacts.

## Decision

### The ringtone picker (#53)

**A screen**, `CucutoDestination.RingtonePicker(chat)`, pushed from the contact detail card. The card no longer holds
the rule chips. It shows what rings as one row: the album cover, the character, the rule, and whether it is mixed.
The row opens the picker. Linking, the status, "Use Cucuto's ringtone again", the WhatsApp hint and "Stop using for
calls" stay on the card. The card also opens the picker when the voice's character has no ringtone, which used to be
a dead end.

**Layout.** At the top, the chosen character:

- its record and name, and whether it is the messages' character or a mix;
- every version with a play/stop button and its tags (a radio button picks one under "One version");
- the rule chips that make sense for its tags (the rules of ADR 0039: the pack's rules, random per call, day and
  night, by season, one version).

Below it, a grid of album covers, grouped by pack: every installed character that has at least one ringtone. A
**bottom bar** saves ("Ring with Tootle the kettle"). It reads "Already chosen" and is disabled when nothing changed.

**Game feel**, like the character picker (#132), through the Feedback engine (#112):

- A tap on a cover picks it and plays the version its rule picks now. The record slides out of its sleeve and spins
  at 45 rpm. A tap on the cover that is playing stops it.
- Taps play `Feedback.Tap`, and rule and version changes play `SegmentTick`.
- Saving fires `Feedback.Assign` with the particle burst in the character's colors, then the screen closes after
  0.9 s. A failure fires `Feedback.Error` and shows a snackbar.
- The screen takes the selected character's colors (`CharacterTheme`).
- With animations removed (`LocalReducedMotion`), the record still comes out but does not spin.

**Previews** (`RingtonePreview`) use `MediaPlayer` with ringtone audio attributes, like Android's ringtone picker, so
they follow the ring volume and the ringer mode. They play the pack's file as it is, stop after 30 s at most (a song
can last 6 minutes), and stop when the screen stops. The "now" version uses 07:00 to 19:00 for day; the sync uses the
chosen place (ADR 0037).

**Mixing.** Picking the voice's character stores no character of its own (`packId` null), so the ringtone keeps
following the voice when the voice changes. That is the brief's "assigning a character assigns both". Any other
character is stored explicitly: a mix. A contact without a voice can still get a ringtone, if it already exists as a
contact.

**Rules** keep the current choice when the new character offers it, else the pack's rules. A character with one
version saves the pack's rule. "Random per call" asks for the phone permission before saving and saves either way,
as the card did.

### Phone ringtones in backups (#198)

**Schema** (`spec/backup.schema.json`, still `formatVersion` 1; both fields are optional, so older readers ignore
them):

- `ringtone.followsVoice: true`: the ringtone follows the message character. `packId` and `characterId` still name the
  voice's character at backup time.
- `ringtone.undo`: `{ originalRingtone?, writtenUri }`, the Android contact's own ringtone before Cucuto and the file
  Cucuto wrote last.

The valid fixture gains a contact with both. Two invalid fixtures: a fixed rule without its variant, and an undo
record without `writtenUri`.

**Export.** One entry per `phone_ringtones` row:

- A row that follows the voice is written with the voice's character and `followsVoice`.
- A row with neither its own character nor a voice has nothing to ring and is left out.
- A fixed rule whose variant is unknown becomes the pack's rule, so the backup stays valid.
- The undo record goes along only while Cucuto's file is on the contact (not changed by the user since).
- Automatic backups now also watch `phone_ringtones`.

**The undo record in a backup, against #198's "never back up `contact_ringtone_writes`".** The work on #198 also asked
to keep the original ringtone for undo. It is device-local, so it is used only where it means something: on the same phone after
a reinstall. There the contact still rings with Cucuto's old file, which no longer counts as Cucuto's own MediaStore
entry. Without the record, the sync would take that stale file for the "original" and restore it on undo. With it, the
restore adopts the old record, and the contact gets its real ringtone back later. Everywhere else it is ignored:
nothing is written unless the contact rings with exactly `writtenUri`, and an existing undo record on this phone is
never replaced. MediaStore URIs of ring files are never restored as ringtones.

**Restore**, in two steps, because the second needs the contacts permission:

1. `BackupRestorer` (`:core:backup`, inside the restore transaction) writes the rows with the voice rule of ADR 0013:
   - **applied** when the contact has none on this phone, or the user overwrote the contact (the conflict tick);
   - **unchanged** when equal;
   - **kept** otherwise.

   It reports each one, with the old phone's Android contact and the undo record.
2. `PhoneRingtones.afterRestore` (`:core:ringtone`) finds each Android contact on this phone (`RingtoneRelinker`):
   1. **phone numbers** first (lookup keys are per device, numbers are not): a current link among the matches is
      kept; one match wins; several matches are narrowed by the old phone's contact name;
   2. then the **lookup key**, when it still resolves;
   3. then the **exact name**, when exactly one contact has it.

   A contact with no match on the new phone is **unlinked**. Contact detail then offers to link it instead of
   reporting a missing contact, and the ringtone choice stays. When phone ringtones are on, the sync renders the ring
   files and writes the contacts right away. Without the permission, the list waits in settings (a small JSON list)
   and finishes when the user turns phone ringtones on, or at the next app start with the permission.

**The restore summary** gets a "Phone ringtones" card:

- how many came back;
- which contacts are not on this phone (link them from the chat);
- "Turn on phone ringtones" (the contacts permission, explained first) when they are off;
- otherwise, how many contacts ring with their character again.

The feature switch itself is not in backups: turning it on needs the permission on the new phone anyway.

## Consequences

- Telecom plays the ring files, as before. No new permission, no new module code, no database migration.
- `RestoreReport.ringtonesPending` is gone; `ringtones`, `ringtonesApplied` and `ringtonesKept` replace it.
  `BackupGateway` gains `restorePhoneRingtones` and `enablePhoneRingtones`.
- Tests:
  - `RingtonePickerViewModelTest` (selection, mixing, rules, previews, save, failures);
  - `RingtonePickerContentTest` and the updated `PhoneRingtoneContentTest`;
  - `RingtoneRelinkerTest` (new phone, same phone, ambiguous and missing contacts, the undo record);
  - a sync test for an adopted undo record;
  - `BackupSnapshotRingtoneTest`;
  - restorer and round-trip cases;
  - `RestoreViewModelTest` cases.
- Goldens: `ringtone-picker` (light, dark, large font in English and Spanish) and the large-font restore summary.
- Defaults the owner may change:
  - the undo record travels in backups;
  - picking the voice's character means "follow the voice";
  - a ringtone set on the new phone is kept unless the contact's voice conflict is overwritten;
  - previews stop after 30 s;
  - an unmatched contact is unlinked rather than left with a stale link.
