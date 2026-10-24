# 0069. Haptic previews in Discover: the pack's haptic in the index, auto envelopes on the phone

- Status: accepted (defaults open to the owner in #284)
- Date: 2026-10-24
- Issue: #272 (amends [ADR 0029](0029-discover-screen.md); owner on #175, item 7: "it has to be done eventually, make
  sure this is not forgotten")

## Context

Discover plays a character's sample clip when it is tapped, but no vibration: `repository.json` has no haptic data
(ADR 0029). The brief's Discover screen previews "sample clips and haptics before downloading". A character's
vibration is its `haptic` in `pack.json`: `auto` (the default: an envelope computed from each clip on the phone at
install, [ADR 0020](0020-auto-haptics-at-import.md)), `custom` (a timeline of presets and envelopes) or `none`. Most
demo characters have `custom` patterns; the rest are `auto`.

Two ways to make a preview feel like the character, with as little extra download as possible:

1. **Everything in the index:** the authored pattern, plus an auto envelope precomputed by `packer index`
   (`RepositoryIndexer`) in the compact form of #149.
2. **Everything on the phone:** compute the auto envelope from the sample clip Discover already downloaded.

Option 1 doesn't fit `RepositoryIndexer`: it is common Kotlin that also runs in the browser (the website's "Host
your own repository" helper), with no audio decoder. An envelope computed there would also use a fixed point budget,
while the app fits auto envelopes to each phone's vibrator (`AutoHapticAnalyzer.Options.forDevice`). Option 2 alone
can't know about authored patterns: it would play the wrong vibration for every `custom` character, and vibrate for
`none`.

Apps released so far validate indexes strictly. In `repository.schema.json`, a character preview has
`additionalProperties: false` and allows only `x-` keys besides its own fields. A new `haptic` key would make those
apps reject the whole index, so the default repository would break for everyone who hasn't updated.

## Decision

**Both halves, each where it is cheap.** The index says which haptic a character has, and carries the pattern when the
author wrote one. The phone computes auto envelopes from the sample it downloads anyway.

**Index field `x-haptic`.** A character preview (in a pack entry or in a bundle's packs) may have `x-haptic`: the
character's `haptic` object from `pack.json`, unchanged (`pack.schema.json#/$defs/haptic`). It is named like an
extension key so that apps released before it accept and ignore it. Old apps validate it as an `x-` key and
`ignoreUnknownKeys` drops it. The schema now defines its shape, so new validators check it. The timeline rules
(sorted events, envelopes ending at zero, no overlap) aren't checked in an index: the indexer copies the haptic from
a pack that passed them, and the app plays what it gets through the same planner and fallbacks as installed packs.

- `RepositoryIndexer` writes it for every character with a sample (no sample, nothing to preview): the pack's
  pattern for `custom`, `{ "mode": "none" }`, and `{ "mode": "auto" }` for an explicit or default auto. That covers
  `packer index` and the website helper.
- A custom pattern costs a few hundred bytes (64 events of 16 points at most); auto and none cost about 20.
- **Absent means unknown, not auto:** an index written before this change, or by another tool, gets sound-only
  previews, as before. Playing an auto envelope there would be wrong for every character with a custom pattern.

**Discover plays it in sync.** When a character is tapped, Discover fetches the sample (as before), works out its
vibration, then starts the clip. The vibration starts on the same callback that starts the clip (`MediaPlayer`'s
prepared listener), so both begin together.

- `custom`: the pattern, through `PublicHapticEffects` (ADR 0026), like the picker's preview.
- `auto`: `AutoHaptics.analyze` on the sample, with this phone's vibrator budget. It is the same analyser and the
  same 2 s cap as at install, and the sample is the character's first clip copied as is. So the preview is the
  vibration a message with that clip would play. The result is kept in memory per sample for the app's lifetime, so
  the analysis (about 140 ms for 2 s of Opus on an emulator, ADR 0020) only runs on the first tap. A silent clip or
  a failed decode means sound only.
- `none`, or no `x-haptic`: sound only.
- The vibration uses notification vibration attributes, like the picker's voice preview, so it follows the system's
  notification vibration setting. The clip keeps media audio, as before.
- **The "App vibration" switch in Settings (`UiFeedbackSettings.hapticsEnabled`) turns haptic previews off**, and its
  description says so. Someone who turned vibration off in Cucuto doesn't expect Discover to buzz. The picker's
  voice preview doesn't follow that switch yet; that's a separate decision.
- Tapping another character, or leaving Discover, stops both the clip and the vibration.

**Website.** Browsers can't play rich haptics (the Vibration API is on and off only, and missing on iOS), so the packs
page keeps sound-only previews. Its index types list `x-haptic` as optional, and nothing reads it.

## Consequences

- Previews of `custom` and `none` characters feel exactly like the pack. `auto` previews match what the module plays
  for the first clip (within the compact form's rounding, ADR 0020).
- The default repository gets `x-haptic` with the next `packs-v*` release, which runs `packer index` again. Until
  then, Discover previews stay sound only.
- No new permission, no background work, no extra network request: the vibration comes from the index and from the
  sample Discover already fetches.
- Anything new in a repository index faces the same constraint: it must be an `x-` key (or wait for a format version
  that relaxes the schema), or apps released today reject the index.
- How previews feel can only be judged on a device (`needs-device`).
