# 0074. Ringtone rules: the pack's rules in plain words, and your own rules per contact

- Status: accepted (defaults the owner may override: #303)
- Date: 2026-10-24
- Issue: #288
- Amends: [ADR 0039](0039-phone-ringtones-on-the-device.md) (rule modes, data),
  [ADR 0057](0057-ringtone-picker-and-ringtone-backups.md) (picker rules, backups),
  [ADR 0063](0063-independent-voice-and-ringtone.md) (data)

## Context

A character's ringtone can have several versions (day and night, seasons, live and radio). The pack decides which one
rings when through `when` conditions ([pack format](../pack-format.md#ringtones-and-variants)). Per contact, the
ringtone picker offers rule chips (ADR 0039, ADR 0057): the pack's rules, random per call, day and night, by season,
one version. "The pack's rules" is opaque: the user cannot see which version rings when, and cannot change it except by
giving up the pack's rules entirely.

The owner, 2026-10-24 (#288, verbatim in `docs/owner-log.md`): "i should be able to view the pack'rules and to edit if
i want".

The owner prefers screens over dialogs ([ADR 0055](0055-screens-over-dialogs.md)). Read: the Material 3
[lists](https://m3.material.io/components/lists/guidelines), [radio button](https://m3.material.io/components/radio-button/guidelines),
[switch](https://m3.material.io/components/switch/guidelines) and [menus](https://m3.material.io/components/menus/guidelines)
guidelines.

## Decision

### A "Ringtone rules" screen

`CucutoDestination.RingtoneRules(packId, characterId, chat?)`, a pushed screen in `ui/ringtone/rules/`. It is
reachable from:

- the **ringtone picker**: "See and edit the rules", under the rule chips of the chosen character (characters with two
  or more versions);
- the **Packs** tab: "Rules" on each character of a pack's detail screen that has two or more versions (no contact:
  the screen only shows the rules).

It shows, from top to bottom:

1. **The character**: its record, its name and pack, and the version that rings now.
2. **The pack's rules, in plain words.** A summary sentence ("Live by day, Radio at night") and one row per ringtone
   entry, in `pack.json` order, each with its play button (it rings like the call, ADR 0063) and when it rings:
   "Rings by day", "Rings in summer and autumn", "Rings from 22:00 to 06:00, every Saturday and Sunday", "Rings on 25
   December" (`when.date`, ADR 0059), "Rings the rest of the time" (no `when`). An entry that can never ring because an
   earlier one has the same condition says so. When the conditions can leave a moment uncovered, a last line says
   which version rings then (the first entry, the pack format's fallback). A pack without conditions reads "Always
   Live".
3. **For this contact** (only with a chat whose ringtone is this character): the rules to choose from, as a radio
   list. Each choice has a one-line explanation:
   - **The pack's rules** (the default);
   - **Your own rules** (below);
   - **Random per call**;
   - **Day and night** and **By season**, the tag rules of ADR 0039, only when the versions are tagged for them (as in
     the picker);
   - **One version**, with the versions to pick from.

   With a chat whose ringtone is another character (a cover tapped in the picker but not saved yet), the screen shows
   the pack's rules and says that the rules can be changed once the character rings for that contact.
4. A **bottom bar**: "Save" (disabled while nothing changed) and, when the contact's rule is not the pack's,
   **"Reset to the pack's rules"**, which saves at once. Saving closes the screen; "Random per call" asks for the phone
   permission first, as in the picker.

### Your own rules (`RingtoneMode.CUSTOM`)

"Your own rules" start as a copy of the pack's rules and edit them for one contact:

- each version has a **switch**: a version turned off never rings for this contact (at least one stays on);
- each version has a **"When"** menu: any time, by day, at night, in spring, summer, autumn or winter, or the pack's
  own condition when it is something else (an hour range, weekdays, dates, several seasons);
- **"When none of these apply"** picks the version that rings when no condition matches.

They are resolved with the pack format's own algorithm ("Variant resolution"): the matching entries, the most
specific, the first in order; the first entry when nothing matches. The "When none of these apply" version is stored
first, so it is that fallback (and it wins a tie with an equally specific version). The sync re-applies them at their
boundaries exactly like the pack's rules (ADR 0039). Rules equal to the pack's are saved as the pack's rules, so a
pack update keeps applying to them.

**Data.** `phone_ringtones` gains `rules` (database version 5): a JSON array of `{ "variant": <ringtone file>,
"when": <condition>? }`, null unless the mode is `custom`. The condition has the shape of the pack's `when`
([pack format](../pack-format.md#ringtones-and-variants)), so the same code reads both. Entries whose file is no longer
in the pack (after a pack update) are skipped; when none is left, the pack's rules apply.

**Backups.** `ringtone.rule.mode` gains `custom`, with `rule.rules` (1 to 50 entries of `{ variant, when? }`, `when`
as in the pack schema). Still `formatVersion` 1: older readers fail to restore a custom rule's contact ringtone
(unknown enum), and backwards compatibility is not a goal (#67). A restore drops entries whose file the installed pack
lacks only when the ringtone syncs, never in the stored rules, so a pack installed later still finds them.

### The picker

The picker keeps its chips. A contact with its own rules shows a **"Your own rules"** chip, selected; picking another
chip and saving replaces them, and the chip stays offered until the picker closes so the user can go back. "Your own
rules" can only be created or edited on the rules screen.

### Message clips stay out

Clips can carry `when` conditions too: day and night, seasons, weekdays and dates (ADR 0037, ADR 0059, the routing
table's `W` and `D` lines), plus the special clips of ADR 0059 (first of the day, bursts, rare). They are not in this
screen:

- a message plays a random clip from the pool whose condition matches, so there is no "which version" to choose;
- per-contact clip control already exists: contact detail's clip toggles turn single clips off;
- the conditions are compiled into the routing table the module reads; per-contact conditions would make the table
  per contact instead of per character, which is more state in `system_server` for little gain (minimum intrusion,
  #67).

Contact detail's clip list keeps showing the pack's clips as they are.

## Consequences

- One migration (4 to 5) and a `MigrationTest` case. No new permission and no module change: Telecom plays the
  contact's ringtone, as before.
- `VariantRules.pick` and `nextBoundary` take the contact's rules; `PhoneRingtones.choose` stores them.
- Tests: `VariantRulesTest` (own rules, fallback, turned-off versions, boundaries), `RuleTextTest` (the plain words,
  in English and Spanish), `RingtoneRulesViewModelTest` (seeding, editing, normalizing to the pack's rules, reset,
  save), backup export and restore of custom rules, the schema fixtures, the migration. Goldens: `ringtone-rules`
  (light, dark, large font in English and Spanish).
- Defaults the owner may change (#303): the entry points (picker and Packs, not contact detail, which #298 is
  restructuring), saving closes the screen while "Reset" saves at once, the pack's rules are shown before the
  contact's choice, and message clips have no per-contact rules.
