# 0072. Folded pack sections and "Surprise me" in the character pickers

- Status: accepted (owner updates 2026-10-24, #289 and #290; the defaults below can be overridden on #297 or on those
  issues)
- Date: 2026-10-24
- Issues: #289 (fold packs in the pickers), #290 (random character from a pack, and from every pack)
- Amends: [ADR 0017](0017-home-recent-senders-and-picker.md) (character picker),
  [ADR 0063](0063-independent-voice-and-ringtone.md) (both pickers)

## Context

The character picker (ADR 0017) and the ringtone picker (ADR 0057) show one grid section per pack, filtered by what
each character offers, with pack chips (ADR 0063). The owner's private packs have 268 and 98 characters, so both grids
become very long.

The owner, 2026-10-24 (verbatim in `docs/owner-log.md`): "when picking a character there should be some kind of
folding on the packs because there are soo many that it is hard to get to the one i want. and i would also like a
button to pick (select) a random sound from a pack, and a random from every pack"

Read: Material 3 [lists](https://m3.material.io/components/lists/guidelines) (expandable sections),
[chips](https://m3.material.io/components/chips/guidelines), [search](https://m3.material.io/components/search/guidelines)
and [icon buttons](https://m3.material.io/components/icon-buttons/guidelines); Compose
[lists and grids](https://developer.android.com/develop/ui/compose/lists) (sticky headers, `animateItem`) and
[haptics](https://developer.android.com/develop/ui/views/haptics/haptics-principles).

## Decision

The same pattern in both pickers (voice and ringtone).

### Folding (#289)

**Pack sections fold.** Each pack's header is a sticky row with the pack's name, its number of eligible characters, a
"Surprise me" dice button and a chevron. A tap on the header folds or unfolds the pack. The header stays at the top
while its characters scroll, so a long pack can be folded from anywhere in it.

**Which packs start unfolded** (first match wins):

1. a pack the user folded or unfolded on this screen;
2. the pack that holds the contact's current choice (its voice in the character picker, its ringtone in the ringtone
   picker), so the current character is always in sight;
3. what the user chose last time for that pack in that picker (remembered);
4. otherwise, small packs (12 eligible characters or fewer, two rows on a phone) are unfolded and large ones are
   folded.

With the demo packs nothing folds by default. With the owner's packs, the Pokémon and K.K. Slider sections start
folded unless the contact uses one of them, and the list of headers works as an index.

**Remembered.** Each picker remembers, per pack, whether the user last folded or unfolded it (`PickerFoldSettings`, in
the app's settings DataStore). The fold state is a device preference: backups leave it out.

**Quick jump.** The pack chips of ADR 0063 are the jump: a pack's chip shows that pack alone, unfolded, from the top,
and "All" goes back to every section as the user left it. A pack shown alone, or the only pack, has no fold control.

**Search.** Both pickers get a search field above the chips. It ignores case and accents (as Home's search) and matches
character names and pack names. While it has text, every pack with a match is shown unfolded with only its matching
characters (all of them when the pack's name matches), whatever its fold state; clearing it brings the fold state
back. Search works inside the chosen chip. No match shows a short message.

**Motion.** The chevron turns and the characters fade and slide in and out (`animateItem`) with the expressive motion
scheme. With animations removed (`LocalReducedMotion`, or the system setting), the state changes and the motion goes.

### Surprise me (#290)

**Where.** A dice button in each pack header picks from that pack. A dice button in the top bar picks from every pack.

**What it picks.** A random eligible character: one with notification clips in the character picker, one with
ringtones in the ringtone picker (the capability filters of ADR 0063). It is never the character selected right now
(the previous pick, or else the contact's current one), unless it is the only eligible one. The pick ignores search
and chips; it then makes itself visible: the search clears, the chip goes back to "All" if it hid the pick, the pick's
pack unfolds (for this visit only, not remembered) and the grid scrolls to it.

**Preview, then re-roll or confirm.** The pick is selected and previewed like a first tap: in the character picker a
clip and the character's haptic, in the ringtone picker the ring that a call would play. The Feedback engine plays a
new `Feedback.Roll` first, a short rattle of rising ticks (no UI sound). In the character picker the clip and the
character's haptic land just after it (220 ms, `FeedbackBeat.revealAtNanos`); the ringtone starts at once, under its
own fade-in. A bottom bar then offers **Re-roll** (same scope, never the same character twice in a row) and the
confirm action: **Use Bleep the frog** in the character picker (the same assign moment as a second tap), and the
existing **Ring with …** save button in the ringtone picker. A second tap on the character still assigns too.

The pick rule lives in one function (`SurprisePick.pick`), so the owner's corrections on #290 change one place.

## Consequences

- The brief's screen 3 and its ringtone UI paragraph change (owner update 2026-10-24, #289 and #290). ADR 0017 and ADR
  0063 point here. `brand/sound-and-haptics.md` gains the roll haptic.
- New: `PackFolds`, `packSections` and `SurprisePick` (pure, tested on the JVM), `PickerFoldSettings` (core:data), a
  shared section header, search field and dice icon, and `Feedback.Roll`.
- Tests: fold defaults and precedence, remembered state, search inside folded packs, chips as the jump, the pick rules
  (eligible only, never the previous pick, the only character), and both view models' roll and re-roll. Goldens:
  picker and ringtone picker, light, dark and large font.
- Defaults the owner may change (#297): the 12-character threshold, the current pack always unfolded, the top-bar dice
  ignoring the chosen chip, and a roll clearing the search.
