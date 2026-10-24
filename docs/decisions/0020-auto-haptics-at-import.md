# 0020. Auto haptics at import and clip envelopes in the routing table

- Status: accepted
- Date: 2026-10-24
- Issues: #97, #98, #267 (parallel analysis), #315 (batches, one decoder per worker)

## Context

[ADR 0012](0012-haptics-effects-and-auto-haptics.md) built the auto haptics analyser (`AutoHaptics`,
`AutoHapticFile`) and the module already resolves `auto` against the chosen clip (`forClip`, #105). Nothing ran the
analysis, and nothing put the envelopes in the routing table. A 16-point envelope takes about 190 characters in the
table's haptic syntax, and clip haptics were written once per voice. In the heavy-user case of `RoutingScaleTest`
(120 characters of 12 clips, 176 voices), envelopes on every clip would add about 320 Ki characters, beyond the
256 Ki limit (`RoutingContract.MAX_ENCODED_CHARS`). Notification clips can now last 30 s
([ADR 0019](0019-long-notification-clips.md)).

## Decision

**Analyse at install, before the commit.** `:core:pack` gets a small hook, `PackAnalyzer`, which `PackImporter.install`
runs on each staged pack it is about to install (chosen, and confirmed when that is needed). It runs outside the
install lock and reports `ImportProgress.Analyzing`, which the Packs screen shows as part of installing. What it
writes in the staging folder (`.cucuto/auto-haptics.txt`) moves into place in the same rename as the pack. Any
exception except cancellation is ignored, so analysis never fails an import. The analysis runs after the user
confirms rather than during `prepare`, so packs that are left out of a bundle, discarded or not confirmed cost
nothing. `:core:pack` stays free of audio code; the app plugs in `AutoHaptics` (`AutoHapticsRunner.packAnalyzer`).

**Every clip, bounded.** All notification clips are analysed whatever their character's pack haptic, because a
per-contact `auto` override can apply to any character (#33). Only the first 2 s of each clip are decoded and
analysed, so a 30 s clip costs the same as a short one, and the decoder has a 10 s timeout per clip.

**Several clips at once (#267).** `AutoHaptics.analyzePack` decodes and analyses clips on a bounded pool, like the
import test-decodes ([ADR 0061](0061-fast-import-checks.md)): the platform decoders mostly wait for the codec
process, one packet at a time, so several clips at once go almost proportionally faster. The app passes
`PackImporter.DEFAULT_DECODE_PARALLELISM` (CPU cores, at least 2 and at most 8); `AutoHaptics` defaults to the same
bound, since `:core:haptics` does not depend on `:core:pack`. The workers run on `Dispatchers.IO`, because the work is
blocking waits rather than CPU. The stored file keeps manifest order and is the same as one at a time, clip for clip.
Progress still comes from the calling coroutine, from 0 to the clip count, one step per finished clip, so
`PackImporter.install` (a `channelFlow`, #181) reports `ImportProgress.Analyzing` as before. A clip that fails is left
out, and the analyser never fails an import.

*Amended by [ADR 0077](0077-batched-test-decodes.md) (#315):* each worker keeps one decoder for all its clips
(`PcmDecoder.session`) and sends the packets of a clip in batches, through the same decoding loop as the import
checks (`:core:codec`). The envelopes are the same as before, clip for clip.

**Redo stale analyses in the background.** At app start, `AutoHapticsRunner.start` analyses again, once per process,
every installed pack whose file is missing or `AutoHaptics.isStale` (new analyser version, restore on a phone with a
smaller point budget, a pack installed by an older app, a failed analysis). `AutoHapticFile.write` never recreates a
missing pack folder, so a background run that loses a race with an update or a removal cannot leave a fake version
behind.

**Publish.** `PackManifestCatalog` reads the file and fills `CatalogClip.autoHaptic`. It gets an `invalidate(path)`
and a `revisions` flow, which `RoutingTableSource` combines with the database, so new envelopes republish the table
even though no row changed.

**Keep the table small (#98).**

1. Clips carry their envelope only in voices whose haptic is `Auto`; the module ignores them otherwise.
2. A clip haptic dictionary: each distinct envelope is written once in an `H` line, and voices refer to it by index in
   a trailing `h` field. Toggle variants of a character share their envelopes.
3. A compact form for dictionary entries: `HapticGrid` snaps auto envelopes to 64 intensity and sharpness levels and
   20 ms steps (the analyser's frame; durations round up, so segments never get shorter than the device minimum),
   and the codec writes on-grid envelopes as three base-64 digits per point. A 16-point envelope drops from about
   190 to about 55 characters. The analyser already ignores changes under 3 %, so 1.6 % steps are below what anyone
   feels. Encoding stays lossless (off-grid haptics use the full syntax), so the publisher's "same table, no
   rewrite" check still works.
4. If a table is still too large, the publisher drops the clip envelopes and publishes the rest
   (`PublishState.Published.clipHapticsLeftOut`), because changing the sound matters more than the vibration.

All of this stays in format version 1. A module still running the previous code (until the next reboot after an
app update) skips `H` lines and trailing fields, so it reads new tables correctly, only without clip envelopes.

## Consequences

- The heavy-user table measures about 111 Ki characters without envelopes (127 Ki before the dictionary) and about
  195 Ki with a 16-point envelope on every one of its 1,440 clips, under the 256 Ki limit. `RoutingScaleTest` checks
  both.
- Clip envelopes in the table differ from the stored analysis by at most 8 thousandths and 19 ms per point.
- Installing takes longer, shown as progress. Decoding 2 s of Opus took about 140 ms on an emulator, mostly waiting
  for the codec process (ADR 0061), so clips are analysed several at once (#267). The first start after this update
  analyses packs installed before it, in the background.
- Up to 8 codec instances are busy while a pack is analysed, and up to 16 if the background refresh overlaps an
  import, well under the 32 instances each codec allows.
- The diagnostics screen does not yet say when clip envelopes were left out; `PublishState` carries it for later.
- How auto haptics feel can only be judged on the device (`needs-device`).
