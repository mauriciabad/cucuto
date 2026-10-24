# 0034. In-app pack creator

- Status: accepted
- Date: 2026-10-24
- Issues: #47 (app), #48 (website, must stay compatible)

## Context

The brief's "Pack creator (app and website)" section asks for a creator on the phone: pack details, characters with a
cropped picture and a seed color suggested from it, clips recorded or imported, trimmed, silence-trimmed and
loudness-normalized, haptics, the importer's checks, then export (share sheet or Storage Access Framework) or a direct
install. Recording must ask for `RECORD_AUDIO` only when the user taps Record. Packs that are installed can be
duplicated and edited; the original stays untouched. The website's creator (#48) is not built yet and must produce
packs that look and behave the same. Clips may last up to 30 s, with a gentle hint above 5 s (#75, ADR 0019).

## Decision

**Shared authoring rules in pack-spec.** `shared/pack-spec` gets `packspec.authoring` (common Kotlin, so it compiles
to JavaScript for the website too):

- `Authoring`: how names become slugs (accents folded, `-2` for duplicates), the suggested pack ID, the first version and
  the next version when reinstalling, the license presets, and the audio and image targets.
- `PackAssembler`: turns a `PackDraft` into packer's canonical layout (`characters/<id>/sprite.<ext>`,
  `characters/<id>/clips/<label>.<ext>`, `cover.<ext>`) and the `pack.json` text. Platforms supply the bytes behind
  opaque keys (file paths in the app, Blobs on the website).

Every pack is then checked with the same `PackValidator` as packer, the importer and the website (media included),
on the exact zip the user gets. #48 should call the assembler through Kotlin/JS: add a small `@JsExport` facade next
to `PackSpecJs.kt` when the page is built. That export is left to #48 so this PR does not grow the browser API.

- **Pack IDs:** the suggestion is `local.<author>.<name>-<6 random [a-z0-9]>`, for example `local.ana.garden-birds-k3x9q2`.
  People have no domain to use, `app.mauri.` is reserved, and the random part keeps two "My pack" packs apart. The
  first install or export freezes the pack ID and the character IDs in the draft, because assignments refer to them.
  Renaming later never breaks a contact's voice.
- **Versions:** a new pack is `1.0.0`. If the library already has this ID at the same or a newer version, the next
  install takes the next patch version, so it updates in place and never asks about a downgrade or the same version.
- **License:** chips for "Personal use only" (the default, free text, so the validator gives a `LICENSE_NOT_SPDX`
  warning that the creator hides for this choice), CC0 and CC BY 4.0 (the brief's presets), or any other text. The
  creators never pick public domain for the user, because people often build packs from clips they did not make (the
  owner's own Pokémon pack is one).

**Audio in `:core:audio`, with platform codecs only.** The placeholder module now holds the creator's audio:

- Decoding: `MediaExtractor` and `MediaCodec`, to mono 16-bit PCM, at most 6 min (the longest ringtone), so a whole
  song can be opened to cut its hook.
- Recording: `AudioRecord`, mono 48 kHz, at most 30 s, in memory only.
- Preview: `PcmPlayer` (`AudioTrack`) for the selection. Finished clips play as a notification, with the character's
  vibration (`CreatorPreview`, auto haptics from `:core:haptics`).
- Processing (`dsp/`, plain Kotlin, tested on the JVM) uses packer's targets:
  1. Cut to the selection.
  2. Trim leading and trailing audio below −50 dBFS, keeping 20 ms of silence.
  3. Resample to 48 kHz with a windowed-sinc filter (Opus only takes 8 to 48 kHz, most files are 44.1 kHz).
  4. Normalize to −16 LUFS integrated (ITU-R BS.1770 K-weighting with gating), capped by a −1.5 dBTP true peak
     (4× oversampled) and by +24 dB of gain at most.
  5. Add 5 ms fades at both ends.
- Encoding: `MediaCodec` (`audio/opus`) into `MediaMuxer`'s Ogg output, mono, 96 kb/s (packer's clip bitrate).

No native library is added. Android 17 ships an Opus encoder and an Ogg muxer (API 29+). A libopus or ffmpeg
build would add tens of megabytes of native code to maintain and audit, against the brief's "minimum intrusion".
Muxers vary between devices, so `ClipEncoder` checks every file with pack-spec's own `AudioProbe`: the clip must
parse and measure its expected length within the format's 50 ms tolerance. When it fails, or the device has no Opus
encoder, the encoder writes AAC-LC in MP4 (`.m4a`, 128 kb/s) instead. Both are pack formats, and the brief says the
tools "never force a format".

**Pictures.** The photo picker (no permission) gives the picture. JPEG XL goes through `:core:jxl`. The cropper frames
a square under the app's cookie shape (pinch, drag, and a zoom slider for TalkBack; `SpriteCrop` is pure, tested math).
The crop is written as WebP at no more than 512 px and never enlarged: lossless when the picture has transparency
(sprites and pixel art stay sharp), lossy at quality 90 for photos. The suggested colors come from the cropped picture
with Material Color Utilities, the same quantizer and scorer as `CharacterTheme`. The best one is preselected, the
others and "automatic" are one tap away, and the character page previews them in its own colors.

**Haptics.** Each character picks "Follows the voice" (`auto`, the default; the importer computes the envelopes),
"Off" (`none`), or "Custom". Custom shows only when the screen has a haptic editor hook (`onEditHaptic`, which calls
`CreatorViewModel.setCustomHaptic`) or the character already has a pattern, from a duplicated pack. The haptic editor
(#32) landed while this was built. Wiring its screen to the hook is a small follow-up.

**Draft, install, export.**

- One draft at a time, in `files/creator/draft/` (`draft.json` and its media). It is saved 300 ms after every change,
  from a scope that outlives the screen. Opening the creator offers to continue it or start a new pack. Unused media
  is pruned when the creator opens.
- "Check and finish" builds `cache/creator/pack.soundpack.zip` and validates it.
- Install sends that file through the normal importer (`PackCatalog.prepare` and `install`), so a created pack gets
  exactly what an imported one gets: platform test-decoding, auto haptics and the library record.
- Export uses `CreateDocument` (Storage Access Framework). Sharing uses a non-exported `FileProvider` with a per-URI
  grant on one copied file in `cache/creator/shared/`.

**Duplicate and edit.** `PackDuplicator` copies the active version of an installed pack into a new draft: new pack ID,
version `1.0.0`, name "… (copy)". It keeps the character IDs, names, sprites, colors, clips (labels, weights, files in
their own formats) and custom haptics. Ringtones and animated sprites are not edited by the creator yet, so they are
dropped and the user is told. The Packs screen gets "Create a pack" (top bar and empty state) and the detail sheet gets
"Duplicate and edit"; the creator is one Navigation 3 destination, `CucutoDestination.Creator(sourcePackId)`, with its
pages (pack, character, framing, recording, clip editor) stacked inside it.

## Consequences

- A pack made on the phone, on the website or with packer has the same layout and passes the same checks. The two
  creators share slugs, IDs and targets by construction once #48 calls `packspec.authoring`.
- The audio pipeline has no native code. Its numbers (LUFS, true peak, resampling, silence) are unit-tested against
  reference signals. Encoding and the Ogg muxer can only run on a device; the probe check and the AAC fallback make a
  bad muxer a lower-quality clip instead of a broken pack. Needs a device pass (`needs-device`).
- `RECORD_AUDIO` joins the manifest. It is asked for only on the Record tap, and nothing records in the background.
- Ringtones are not edited by the creator, and duplicated packs lose them. "Simulate message" (posting a real
  notification) is not built; the preview plays as a notification without posting one.
- Open defaults (ID scheme, default license, mono 96 kb/s, WebP) are listed in a `question` issue for the owner.
