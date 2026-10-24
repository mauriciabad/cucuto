# 0069. Haptic previews in Discover: the pack's haptic in the index, auto envelopes on the phone

- Status: accepted; amended 2026-10-24 (#301, owner on #284, item 6: "at least show a visual preview of the haptic
  (also on the app would be nice)"); amended 2026-10-24 (#325: samples for ringtone-only characters)
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
page doesn't vibrate. It draws the vibration instead (owner update 2026-10-24 (#301), see below).

**Drawn previews (owner update 2026-10-24, #301).** The owner, on #284 item 6: "at least show a visual preview of the
haptic (also on the app would be nice)". Default 6 was "the website keeps sound-only previews"; it changes to this:

- **Every character preview shows its vibration** from `x-haptic`, on the website's Packs page (the default
  repository) and on Discover's character badges in the app, under the character's name.
- `custom`: a small drawing of the timeline, scaled to the pattern's own length, with the haptic editor's marks
  ([ADR 0030](0030-haptic-editor-and-per-contact-override.md)): presets as bars as tall as their strength, envelopes
  as their filled intensity curve. The app reuses the editor's drawing code (`HapticTimelineView.kt`, a compact
  `HapticPatternThumbnail` without grid or labels); the website reuses its creator's (`timelinePaths`). Screen readers
  hear "Vibration: own pattern, N s".
- `auto`: no stored pattern, so a "Follows the voice" mark (a wave icon and the words), not a drawing. Computing the
  envelope on the website would mean downloading and decoding every sample on page load, and the app only analyses a
  sample once it is tapped (above); a drawing that appears after a tap would move the layout. The mark is honest and
  free.
- `none`: "No vibration".
- No `x-haptic`: nothing, as for playback (absent means unknown).
- The drawing doesn't depend on the "App vibration" switch: it shows what the pack does, not what the preview plays.

**Samples for ringtone-only characters (amendment 2026-10-24, #325).** The sample is a character's first clip, so a
ringtone-only character (a song, [ADR 0063](0063-independent-voice-and-ringtone.md)) had no sample, no `x-haptic` and
nothing to play in Discover. Three options were weighed: cut a preview from the ringtone at index time, point `sample`
at the ringtone file itself (up to 6 min and 100 MiB, against "previews are small files"), or a new `x-ringtone` key
that apps play from the start with range requests (a change in every app and a slow first second over the network).

- **`packer index` cuts the sample**: the hook `ClipCuts` finds (the same heuristic as `packer cut` and the creators,
  [ADR 0033](0033-ring-files-and-auto-cut-clips.md)) in the character's first ringtone, searched within its loop
  (the song's body, not an intro or outro; the whole file for `loop: false`), 2.5 s with the auto-cut fades, Ogg Opus
  at 96 kb/s. It is saved as `<character>-sample.ogg` like any sample, and gets `x-haptic` like any sample: `auto`
  previews are computed from the cut, which is close to what the ringtone's vibration feels like.
- **Without ffmpeg, no sample**: `index` warns and writes the entry without that `sample`, as before. The website's
  helper has no encoder in common Kotlin, so it leaves it out too (`RepositoryIndexer.entry(cutSamples = false)`, its
  default). An index stays valid either way, and the schema doesn't change: `sample` was already optional.
- **Discover and the website change nothing**: a cut sample is a short Ogg Opus file like any other. The cut depends on
  the ffmpeg version only within a few milliseconds of decoding, and the default repository is indexed by CI.

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
- Drawn previews (#301) cost nothing extra: the drawing comes from the same `x-haptic`. Indexes without it show no
  drawing, on the website as in the app. Installed packs (the Packs tab, the pickers) don't show vibrations yet; a
  character's vibration is drawn where it is previewed before downloading, and in the haptic editor.
