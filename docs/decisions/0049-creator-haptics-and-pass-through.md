# 0049. Creator characters in the haptic editor, and duplicates that keep everything

- Status: accepted (defaults open for the owner in #222)
- Date: 2026-10-24
- Issue: #185
- Amended: 2026-10-24 (#322, the creators edit conditions, special clips, ringtones and more)

## Context

The in-app creator (#47, ADR 0034) left two gaps. The haptic editor (#32, #172) was keyed by `ChatKey` and saved a
per-chat override, so a creator character could only "follow the voice" or be "off". And "Duplicate and edit"
dropped ringtones and animated sprites (and, silently, the pack's homepage and tags, characters' descriptions and
tags, and clips' `when`). Separately, contact detail listed clips by file name ("croak") and ignored the pack's
clip `label`, which `docs/pack-format.md` says is "shown in the clip list".

## Decision

**A sealed editor target.** `HapticTarget` (in `ui/haptics/`, serializable because it is part of the navigation key)
is either `Chat(chat)` or `CreatorCharacter(characterKey, name, pattern, clip)`. `CucutoDestination.HapticEditor`
takes a target instead of a chat; null is still the preview-only scratch pad.

- A chat behaves as before: it starts from the override (else the pack's custom pattern) and saving stores an
  override (#33).
- A creator character starts from `pattern`, the pack format's events, carried in the key so it survives process
  death. "Play with" plays `clip`, a draft clip of the character. Saving stores nothing: it sends the pattern to
  `HapticEditorResults`, a singleton with an unlimited channel. The creator's view model collects it once its draft
  has loaded, so a result is not lost when the creator is recreated after the editor closes. This follows the
  Navigation 3 "returning results" recipe (an event bus) with Hilt instead of a composition local.
- `CreatorHaptics` (`:core:creator`, which now depends on the plain-Kotlin `:core:routing`) converts between the
  editor's thousandths and pack-spec's fractions with the same rules as `HapticJson`: a scale of 1 and the default
  initial sharpness are left out. The pattern goes into the character's `haptic` and through `PackValidator` with
  every other rule, so a pattern that breaks a timeline rule is caught (`HAPTIC_INVALID`).
- Tapping "Custom" on a character with no pattern opens the editor at once; "Edit pattern" reopens it.

**Pass-through data.** The shared assembler (`packspec.authoring`, also used by the website) gains optional fields:
the pack's `homepage` and `tags`; a character's `description`, `tags`, `spriteAnimated` and `ringtones`
(`RingtoneDraft`: the entry as it was, with only its `file` replaced); a clip's `when`. It writes them in packer's
canonical layout (`sprite-anim.<ext>`, `ringtones/<label slug>.<ext>`; a file shared by several variants is written
once, named after the first). A character needs a clip or a ringtone, as the format says, so a song-only character
survives a duplicate. `PackDuplicator` copies each source file once, and `DraftCharacter` stores the media names.
The creator does not edit these yet; the character page says what came from the original pack (*amended below: #322 makes most of them editable*). Choosing a new
picture drops the animated sprite, which would still show the old one (#222). Unknown `x-` keys are not kept.

**Clip labels.** The catalog reads each clip's `label`; `VoiceCharacter.clipLabels` lines up with `clips`. Contact
detail shows the label, else a name made from the file the way packer labels clips ("pond-song_2.ogg" becomes
"Pond song 2"). Decision log rows and the in-app simulation look the label up by the clip URI's path. Labels are
pack data, so they are shown as written, in the pack's language.

## Consequences

- One editor serves contacts and the creator; per-contact overrides are unchanged.
- A duplicated pack installs with the same ringtone variants, conditions and animations as the original, and its
  ringtone settings are byte-for-byte the same entries (tested by round-tripping the full fixture pack).
- The website's creator gets the pass-through fields for free; it does not set them yet.
- Ringtones are still not edited in the creator; that needs its own screen (*done in #322, below*).

## Amendment 2026-10-24 (#322): the creators edit everything but animated sprites

Pack authors should never have to hand-edit JSON. The app's creator and the website's Create page now edit what they
only passed through, with one model in `packspec.authoring` so both read and write the same entries:

- **`ConditionForm`**: a `when` and a clip's `oneIn` as the editors' controls: time of day (any, by day, at night,
  hours `from`–`to`), seasons, weekdays, dates (days or ranges, up to 50), and for clips the moment (any message, the
  first message of the day, message N in a row) and "rare: about 1 in N". `of` reads every condition the format allows;
  `toWhen` writes a canonical one (a single season as a string, several as a list in calendar order; all four seasons
  or all seven days are the same as none; ringtones never get moment keys). `problems` names what to fix in plain
  words: an empty or malformed hour range, a date that is not a calendar day, more than 50 dates, a burst outside 2 to
  50, a rarity outside 2 to 10,000.
- **`RingtoneForm`**: a ringtone version's label, condition, loop (whole file, play once, or an intro and a loop
  section), fade-in and weight, with its tags kept. `problems` also checks the file's length (5 s to 6 min) and the
  loop against it (at least 1 s, within the audio), the importer's rules.
- `Authoring.tags` turns typed text (`Animals, 8-bit games`) into the format's tags; `Authoring.isHomepage` checks a
  homepage. The browser build exports all of it (`ConditionsJs.kt`).

The editors (each in the style of its creator: dialogs on the website, pages in the app as ADR 0055 asks):

- **Clips**: each clip gets a "When it plays" editor. The clip row shows a short summary when the clip has a condition
  ("At night · Rare: about 1 in 25").
- **Ringtones**: a "Ringtones" section on the character. "Add a ringtone" picks an audio file of 5 s to 6 min, kept in
  its own format (the format never forces one, and re-encoding a song would lose quality); each version has play,
  label, its condition summary, edit (label, "When it rings", loop, fade-in, how often it is picked for "random per
  call"), "Add a version" (another entry for the same file, for example a night version with a fade-in) and remove.
  A character needs a clip or a ringtone, so ringtone-only characters (songs) can be made from scratch.
- **More details**: the pack's homepage and tags, and a character's description. A character's tags stay
  pass-through (no app feature reads them yet).
- The website's bundle tool gains homepage and tags, like `packer bundle`.
- On the website the editors are dialogs (`ConditionDialog`, `RingtoneDialog`, sharing `ConditionFields`); a version's
  label is also edited in place in its row, like a clip's. The list summaries ("At night · Saturday · Rare: about 1 in
  13") come from `web/src/lib/creator/conditions.ts`. Ringtone files keep their bytes; the browser decodes them only
  to check their length and to play them.

Animated sprites stay pass-through (#324): adding one needs an animated encoder on each platform. Ringtone-only
characters get no preview sample in repository indexes yet (#325).
