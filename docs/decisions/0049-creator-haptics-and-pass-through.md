# 0049. Creator characters in the haptic editor, and duplicates that keep everything

- Status: accepted (defaults open for the owner in #222)
- Date: 2026-10-24
- Issue: #185

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
The creator does not edit these yet; the character page says what came from the original pack. Choosing a new
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
- Ringtones are still not edited in the creator; that needs its own screen.
