# Cucuto pack format, version 1

This is the human specification of the files Cucuto reads and writes: sound packs, bundles, pack repository indexes
and backups. The machine-readable contract lives in [`spec/`](../spec):

| File | What it defines |
| --- | --- |
| [`spec/pack.schema.json`](../spec/pack.schema.json) | `pack.json`, the manifest of a pack |
| [`spec/bundle.schema.json`](../spec/bundle.schema.json) | `bundle.json`, the manifest of a bundle of packs |
| [`spec/repository.schema.json`](../spec/repository.schema.json) | `repository.json`, the index of a pack repository |
| [`spec/backup.schema.json`](../spec/backup.schema.json) | `backup.json` and the encrypted backup envelope |
| [`spec/common.schema.json`](../spec/common.schema.json) | Shared definitions (IDs, versions, paths, colors, URLs) |
| [`spec/error-codes.json`](../spec/error-codes.json) | The stable validation codes every implementation reports |
| [`spec/fixtures/`](../spec/fixtures) | Golden test packs and documents with their expected codes |

All schemas use JSON Schema 2020-12 and have `$id`s under `https://cucuto.mauri.app/spec/v1/`. The Android app, the
website and [`packer`](../tools/packer/README.md) must accept and reject exactly the same inputs: they all run the
golden fixtures in CI. When this document and the schemas disagree, the schemas win for structure and this document
wins for everything the schemas cannot express (marked "importer rule" below).

The key words "must", "must not", "should" and "may" are used as in RFC 2119.

## Vocabulary

- A **pack** holds **characters**. A character is anything that can speak for a contact: an animal, a game hero, a
  friend, or a song with its album art.
- A character has a **sprite** (picture), a pool of short notification **clips**, optional call **ringtones** (with
  **variants**), a default **haptic** (vibration) and a **seed color**.
- A **bundle** groups several packs in one file.
- A **repository** is an HTTPS location that serves a `repository.json` index of downloadable packs and bundles.
- A **backup** restores assignments, settings and pack references on a new phone.

## Files and layout

A pack file is a zip archive (see [Archive rules](#archive-rules)) named `<name>.soundpack.zip`; `packer` writes
`<id>-<version>.soundpack.zip`. It holds either one pack or one bundle. File names always end in `.zip` (never a bare
`.soundpack`; [ADR 0007](decisions/0007-zip-file-names.md)).

### File names and type detection

| File | Name | Detected by (at the zip root) |
| --- | --- | --- |
| Pack | `<name>.soundpack.zip` | `pack.json` |
| Bundle | `<name>.soundpack.zip` | `bundle.json` |
| Backup | `cucuto-backup-YYYY-MM-DD.zip` | `backup.json` (plain) or `envelope.json` + `payload.bin` (encrypted) |

Readers never trust the file name: they accept any `.zip` (or a file with no name at all, such as a `content://`
stream) and decide what it is from the manifest at the root. A zip with none of these, or with both `pack.json` and
`bundle.json`, is rejected (`MANIFEST_MISSING`, `MANIFEST_AMBIGUOUS`). Files are served and shared as
`application/zip`; the Android app registers intent filters for `application/zip` and `.zip` paths, and the PWA
registers `file_handlers` for `.zip`.

### Single pack

`pack.json` sits at the root of the archive. Every other path is relative to the root. Packs may organize files as
they like; `packer build` writes this canonical layout:

```
chiptune-critters.soundpack.zip
├── pack.json
├── cover.svg
├── LICENSE
└── characters/
    └── frog/
        ├── sprite.svg
        ├── sprite-anim.gif          (optional)
        ├── clips/
        │   ├── ribbit.ogg
        │   └── croak.ogg
        └── ringtones/
            ├── pond-song-day.ogg
            └── pond-song-night.ogg
```

### Bundle

`bundle.json` sits at the root instead of `pack.json`. Each pack is a complete pack (its own `pack.json`, files and
relative paths) in its own folder, listed in `bundle.json`. `packer bundle` uses `packs/<pack id>/`.

```
demo-packs.soundpack.zip
├── bundle.json
├── cover.svg
└── packs/
    ├── app.mauri.demo.chiptune-critters/
    │   ├── pack.json
    │   └── characters/…
    └── app.mauri.demo.kitchen-orchestra/
        ├── pack.json
        └── characters/…
```

Importer rules for bundles:

- An archive with both `pack.json` and `bundle.json` at the root is rejected (`MANIFEST_AMBIGUOUS`); one with
  neither is rejected (`MANIFEST_MISSING`).
- Every folder listed in `packs` must contain a `pack.json` (`BUNDLE_PACK_MISSING`).
- Pack folders must not be nested in each other and must not contain a `bundle.json`: bundles never contain bundles
  (`BUNDLE_LAYOUT_INVALID`).
- Pack IDs inside a bundle must be unique (`DUPLICATE_ID`).
- Paths in a pack's `pack.json` are relative to that pack's folder. Paths in `bundle.json` are relative to the
  bundle root.
- The user picks which packs of a bundle to install; all are selected by default. Each pack installs on its own,
  exactly as if it had been imported alone.

## pack.json

```json
{
  "formatVersion": 1,
  "id": "app.mauri.demo.chiptune-critters",
  "name": "Chiptune critters",
  "version": "1.0.0",
  "author": "Cucuto contributors",
  "license": "CC0-1.0",
  "cover": "cover.svg",
  "tags": ["chiptune", "animals"],
  "characters": [
    {
      "id": "frog",
      "name": "Bleep the frog",
      "sprite": "characters/frog/sprite.svg",
      "seedColor": "#4CAF50",
      "clips": [
        { "file": "characters/frog/clips/ribbit.ogg", "label": "Ribbit", "weight": 2 },
        { "file": "characters/frog/clips/croak.ogg", "label": "Croak" }
      ],
      "ringtones": [
        { "file": "characters/frog/ringtones/pond-song-day.ogg", "label": "Pond song (day)", "when": { "time": "day" }, "loop": { "startMs": 0, "endMs": 8000 } },
        { "file": "characters/frog/ringtones/pond-song-night.ogg", "label": "Pond song (night)", "when": { "time": "night" }, "fadeInMs": 1500 }
      ],
      "haptic": { "mode": "custom", "events": [{ "at": 0, "preset": "CLICK", "scale": 0.9 }, { "at": 200, "preset": "CLICK", "scale": 0.7 }] }
    }
  ]
}
```

### Pack fields

| Field | Required | Type | Notes |
| --- | --- | --- | --- |
| `formatVersion` | yes | integer | `1` for this version. See [Versioning](#versioning). |
| `type` | no | `"pack"` | Optional marker; `bundle.json` uses `"bundle"`. |
| `id` | yes | pack ID | See [IDs](#ids). |
| `name` | yes | string, 1–80 | Shown in the app. |
| `version` | yes | SemVer | `MAJOR.MINOR.PATCH`, optional pre-release (`1.2.0-beta.1`), no build metadata. |
| `author` | yes | string, 1–120 | |
| `license` | yes | string | An [SPDX license expression](https://spdx.github.io/spdx-spec/v3.0.1/annexes/spdx-license-expressions/) (`CC0-1.0`, `CC-BY-4.0`, `MIT OR Apache-2.0`, `LicenseRef-…`) where possible, free text (`Personal use only`) otherwise. Free text gives the `LICENSE_NOT_SPDX` warning. |
| `homepage` | no | https URL | |
| `description` | no | string, up to 2,000 | Plain text. |
| `cover` | no | path to an image | Apps show the first character's sprite when absent. |
| `tags` | no | up to 20 tags | Lowercase words joined by hyphens, up to 32 characters, unique. |
| `characters` | yes | 1–500 characters | |

### Character fields

| Field | Required | Type | Notes |
| --- | --- | --- | --- |
| `id` | yes | slug | Lowercase `[a-z0-9]` words joined by single hyphens, up to 64 characters. Unique within the pack (`DUPLICATE_ID`) and **stable across versions**: assignments point at it. |
| `name` | yes | string, 1–80 | |
| `description` | no | string | |
| `sprite` | yes | path to an image | Static picture, any [image format](#images). Square pictures look best. |
| `spriteAnimated` | no | path | GIF, animated WebP or animated JPEG XL only (`FILE_TYPE_MISMATCH` otherwise). |
| `seedColor` | no | `#RRGGBB` | Seed of the character's dynamic color scheme. Apps extract one from the sprite when absent. |
| `tags` | no | tags | |
| `clips` | see note | up to 200 clips | Notification sounds. |
| `ringtones` | see note | up to 50 ringtones | Call ringtones and their variants. |
| `haptic` | no | haptic | Default `{ "mode": "auto" }`. |

A character needs at least one clip or one ringtone. A character with only ringtones (a song) is valid; apps then
cut a notification clip from it only if the user asks.

### Clips

| Field | Required | Notes |
| --- | --- | --- |
| `file` | yes | Path to an [audio file](#audio), 0.2 to 30 s. |
| `label` | no | Shown in the clip list. |
| `weight` | no | Relative probability, greater than 0 and at most 100, default 1. The app picks clips at random by weight with a shuffle bag, so the same clip never plays twice in a row. |

### Ringtones and variants

Every entry of `ringtones` is one playable file. Several entries of the same character are **variants** of the same
song (day and night, seasons, live and radio).

| Field | Required | Notes |
| --- | --- | --- |
| `file` | yes | Path to an [audio file](#audio), 5 s to 6 min. The same file may appear in several entries. |
| `label` | no | Shown on the variant chip. |
| `tags` | no | Free tags such as `live`, `radio`, `sped-up`. |
| `when` | no | Condition for the "follow the pack" rule, see below. |
| `loop` | no | `true` (default): loop the whole file. `false`: play once. `{ "startMs": …, "endMs": … }`: after the first play-through up to `endMs`, jump back to `startMs`. `endMs` defaults to the end of the file, `startMs` to 0. |
| `fadeInMs` | no | Fade-in at the start, 0 to 10,000 ms, default 0. |
| `weight` | no | Relative probability for the "random per call" rule, default 1. |

Importer rules for loops: the loop must last at least 1 s (`LOOP_POINTS_INVALID`), and `endMs` must not be beyond the
end of the audio (checked after measuring the file, with the 50 ms tolerance).

**`when` conditions.** All present keys must match (AND); a list matches any of its values (OR). Times are the
phone's local time.

| Key | Values | Meaning |
| --- | --- | --- |
| `time` | `"day"`, `"night"`, or `{ "from": "HH:MM", "to": "HH:MM" }` | Day is 07:00 to 19:00, night the rest. A range includes `from` and excludes `to`, and may wrap midnight (`22:00` to `06:00`). `from` equal to `to` is invalid (`WHEN_INVALID`). |
| `season` | `"spring"`, `"summer"`, `"autumn"`, `"winter"` or a list | Meteorological seasons: spring is March to May, summer June to August, autumn September to November, winter December to February. The app flips them when the user sets the southern hemisphere. |
| `weekday` | `"mon"` … `"sun"` or a list | |

**Variant resolution** when a contact follows the pack's conditions: take the entries whose `when` matches (an entry
without `when` always matches), keep those with the most condition keys (the most specific), and play the first of
them in manifest order. If nothing matches, play the first entry. The other per-contact rules (one fixed variant,
random per call by `weight`, by time of day, by season) ignore `when` and use `tags` and labels to present choices.

### Haptics

The `haptic` object maps one-to-one onto Android's `VibrationEffect.Builder` (see
[Create custom haptic effects](https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects)).

| `mode` | Meaning |
| --- | --- |
| `auto` (default) | "Haptics follow the voice": at import, the app decodes each clip, computes its loudness envelope in about 20 ms windows, simplifies it to the device's maximum number of control points, clamps it to 2 s and ends it at zero intensity. |
| `custom` | Plays `events`, a timeline of presets and basic envelopes. |
| `none` | No vibration from the pack (the user can still override per contact). |

Custom events (1 to 64), each placed at `at` milliseconds (0 to 5,000) from the start of the clip:

- **Preset:** `{ "at": 0, "preset": "CLICK", "scale": 0.8 }` becomes
  `addPreset(at, VibrationEffect.Preset.create(PRESET_CLICK, 0.8f))`. Presets are `CLICK`, `TICK` and `LOW_TICK`
  (the constants of `VibrationEffect.Preset` in API 37). `scale` is 0 to 1, default 1.
- **Basic envelope:** `{ "at": 220, "initialSharpness": 0.3, "envelope": [{ "intensity": 1, "sharpness": 0.8, "ms": 120 }, { "intensity": 0, "sharpness": 0.4, "ms": 180 }] }`
  becomes `addEnvelope(at, Envelope.create(BasicEnvelopeBuilder().setInitialSharpness(0.3f).addControlPoint(1f, 0.8f, 120).addControlPoint(0f, 0.4f, 180)))`.
  Intensity and sharpness are 0 to 1; `ms` (1 to 5,000) is the transition time from the previous point; 1 to 16
  points. `initialSharpness` defaults to the first point's sharpness.

Importer rules (`HAPTIC_INVALID`), mirroring what the Builder accepts:

- Events are sorted by `at` (equal values are allowed).
- An event must not start before the previous envelope has finished (`at` + the sum of its `ms`). Preset lengths
  depend on the device and are not checked; leave 50 ms or more between events, as Android recommends.
- Every envelope ends with a control point of intensity 0.
- The whole timeline (the latest envelope end) lasts at most 5,000 ms.

Waveform envelopes (`WaveformEnvelopeBuilder`) are deliberately not part of the format: they have no framework
fallback and depend on the device's frequency profile.

## bundle.json

| Field | Required | Notes |
| --- | --- | --- |
| `formatVersion` | yes | `1` |
| `type` | yes | `"bundle"` |
| `id`, `name`, `version` | yes | As for packs. The bundle ID identifies the bundle for repository updates; keep it distinct from the IDs of the packs inside. |
| `author`, `license`, `homepage`, `description`, `cover`, `tags` | no | As for packs. Each pack still carries its own license. |
| `packs` | yes | 1 to 100 unique folder paths. |

## IDs

- **Pack and bundle IDs** are lowercase reverse-domain names with at least two labels:
  `^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$`, 3 to 128 characters. Use a domain you
  control, or `io.github.<user>.<pack>`. The `app.mauri.` prefix is reserved for packs published by the Cucuto
  project.
- **Character IDs** are slugs unique within their pack. Keep them stable across versions.
- IDs are compared exactly (they are lowercase by construction).

## Versioning

- **`formatVersion`** is an integer. A reader supports every format version up to its own and rejects higher ones
  with `FORMAT_VERSION_UNSUPPORTED` (checked before the schema, so the message can say "update the app"). Version 1
  manifests are strict: unknown properties are schema violations, except keys starting with `x-`, which are reserved
  for experiments and tools and are ignored by readers. Adding any field that readers must understand requires a new
  `formatVersion`; writers should emit the lowest version that can express a pack.
- **`version`** of a pack or bundle follows SemVer 2.0.0 precedence (pre-releases sort before the release).
  When the app imports a pack whose `id` is already installed:
  - a **higher** version updates it in place (installed to `files/packs/<id>/<version>/`), keeping every assignment;
  - the **same** version asks before replacing it (`PACK_SAME_VERSION`);
  - a **lower** version asks before downgrading (`PACK_DOWNGRADE`).
- **Update semantics.** Assignments refer to `(pack id, character id)`; per-contact clip toggles and fixed ringtone
  variants refer to file paths. An update that removes a character asks the user what to do with the contacts that
  used it. Renamed clip or ringtone files lose their per-contact toggles, so keep file names stable too.

## Media

### Audio

| Format | Extensions | Notes |
| --- | --- | --- |
| Ogg Opus | `.ogg`, `.opus`, `.oga` | Recommended (smallest for voice and music). |
| Ogg Vorbis | `.ogg`, `.oga` | |
| MP3 | `.mp3` | |
| AAC in MP4 | `.m4a` | |
| AAC (ADTS) | `.aac` | |
| FLAC | `.flac` | |
| WAV | `.wav` | |

Durations (importer rule, measured from the container headers and confirmed by decoding in the app):

| Use | Minimum | Maximum |
| --- | --- | --- |
| Notification clip | 0.2 s | 30 s |
| Ringtone | 5 s | 6 min |

Both limits allow a 50 ms tolerance for encoder padding. Out of range: `CLIP_DURATION_OUT_OF_RANGE` or
`RINGTONE_DURATION_OUT_OF_RANGE`. A file whose bytes do not match its extension's signature, or whose headers
cannot be parsed, is `MEDIA_UNDECODABLE`. Tools may trim silence and normalize loudness (packer: `--trim-silence`,
`--normalize`, EBU R128 at -16 LUFS) but never force a format.

Long clips are allowed so the limit does not hold creativity back (a jingle, a song hook), but Android plays a
notification sound to the end and lowers other audio while it plays. The app and the pack creator show a gentle hint
for clips longer than 5 s, never an error. Auto haptics follow only the first 2 s of a clip, and custom haptics last
at most 5 s ([ADR 0019](decisions/0019-long-notification-clips.md)).

### Images

| Format | Extension | Animated sprite |
| --- | --- | --- |
| PNG | `.png` | no |
| JPEG | `.jpg`, `.jpeg` | no |
| WebP | `.webp` | yes |
| GIF | `.gif` | yes |
| AVIF | `.avif` | no |
| JPEG XL | `.jxl` | yes (recommended: compresses pixel art losslessly and well) |
| SVG | `.svg` | no |

- Bitmaps are at most **512 px** per side (`IMAGE_TOO_LARGE`).
- **SVG** must be static and self-contained (importer rule, `SVG_UNSAFE`). Rejected: `<!DOCTYPE>` and entity
  declarations; processing instructions other than the XML declaration; the elements `script`, `foreignObject`,
  `iframe`, `embed`, `object`, `audio`, `video`, `handler` and `listener`; any `on*` event attribute; `href`, `xlink:href`
  or `src` values other than same-document fragments (`#id`) or `data:image/{png,jpeg,gif,webp,avif,jxl};base64,`;
  `url(…)` references other than fragments or those data URIs; CSS `@import` and `expression(`; `javascript:`
  anywhere; animation elements (`set`, `animate…`) that target `href` or event attributes. Character references are
  decoded before checking. Importers reject rather than silently rewrite, and apps still render SVG with a renderer
  that has no scripting or network access. A file whose root element is not `<svg>` is `MEDIA_UNDECODABLE`.

### Text files

`LICENSE`, `LICENCE`, `COPYING`, `NOTICE`, `AUTHORS`, `README` and `CREDITS` (no extension), and any `.txt` or `.md`
file, may travel with a pack. They are never interpreted.

## Archive rules

These importer rules protect the phone from malicious or broken files. Local files and repository downloads go
through the same checks.

**Zip features.** Entries must be stored (method 0) or deflated (method 8). Encrypted entries, other compression
methods and multi-disk archives are `ARCHIVE_UNSUPPORTED`. Zip64 is accepted. A file that is not a zip, is truncated,
or has a CRC mismatch is `ARCHIVE_UNREADABLE`. Importers read the central directory, never trust local headers for
sizes, and stop inflating an entry as soon as it exceeds its declared size.

**Paths.** Entry names are relative POSIX paths.

- `UNSAFE_PATH` (zip slip): absolute paths, drive letters (`C:`), backslashes, control characters, empty segments, `.`
  or `..` segments.
- `UNSAFE_ENTRY`: symbolic links, hard links, devices or anything that is not a regular file or a directory.
- `INVALID_FILE_NAME`: segments must match `[A-Za-z0-9_][A-Za-z0-9._-]*` (no spaces, no non-ASCII, not starting with
  `.` or `-`), paths are at most 255 characters and 8 levels deep. Manifest paths follow the same pattern, so they
  cannot climb out of the pack.
- `DUPLICATE_ENTRY`: two entries whose paths are equal ignoring case.
- `IGNORED_ENTRY` (warning): `__MACOSX/`, any segment starting with `.` (`.DS_Store`, `._file`), `Thumbs.db` and
  `desktop.ini` are skipped and never installed.

**File types.** Only the audio, image and text types above plus the manifests (`pack.json`, `bundle.json`) are
allowed (`UNKNOWN_FILE_TYPE`; other `.json` files too). Executable and script extensions (`.apk`, `.dex`, `.so`,
`.jar`, `.exe`, `.sh`, `.js`, `.html` and similar) and media files that start with an executable signature (ELF, PE,
Mach-O, DEX, `#!`) are `EXECUTABLE_FILE`. Packs contain data only.

**Limits.**

| Limit | Value | Code |
| --- | --- | --- |
| Unpacked size of a pack | 1 GiB | `UNPACKED_TOO_LARGE` |
| Unpacked size of a bundle | 2 GiB | `UNPACKED_TOO_LARGE` |
| Warn before installing | over 256 MiB | `LARGE_PACK` (warning) |
| Files per pack | 5,000 | `TOO_MANY_FILES` |
| Entries per archive | 20,000 | `TOO_MANY_FILES` |
| Decompression ratio | 100:1 per entry, and for the whole archive, once more than 1 MiB is involved (declared sizes, checked before inflating) | `COMPRESSION_RATIO_EXCEEDED` |
| Audio file | 100 MiB | `FILE_TOO_LARGE` |
| Bitmap image | 8 MiB | `FILE_TOO_LARGE` |
| SVG, manifest, text file | 1 MiB each | `FILE_TOO_LARGE` |
| Characters per pack | 500 | `SCHEMA_VIOLATION` |
| Clips / ringtones per character | 200 / 50 | `SCHEMA_VIOLATION` |
| Packs per bundle | 100 | `SCHEMA_VIOLATION` |

**Manifests** are UTF-8 JSON without a byte order mark (`MANIFEST_INVALID_JSON`). Files that no manifest references
produce `UNREFERENCED_FILE` warnings and are not installed.

## Validation stages

Validators run these stages in order. A stage runs all of its checks, then the validator stops if that stage reported
any error. Warnings never stop validation. This makes the reported set of codes identical across implementations.

1. **archive** (zip files only): readable, supported features, decompression ratio, entry count.
2. **layout**: paths, entry types, duplicates, file types, executables, sizes, file counts, manifest presence.
3. **manifest**: JSON syntax, `formatVersion`, JSON Schema. For bundles: `bundle.json` and every listed `pack.json`.
4. **references**: unique IDs, referenced files exist and have the right kind, bundle layout, haptic timelines,
   `when` conditions, loop points, license expressions (warning), unreferenced files (warning).
5. **media**: audio signatures and durations, loop end within the audio, bitmap signatures and sizes, SVG safety.

Apps additionally test-decode every clip and image with the platform decoders before installing; failures there are
`MEDIA_UNDECODABLE` too.

## Error codes

Codes are stable identifiers: never renamed, never reused; new codes may be added. Messages are not part of the
contract and may be localized. The canonical list with severities and stages is
[`spec/error-codes.json`](../spec/error-codes.json).

| Code | Severity | Stage | Meaning |
| --- | --- | --- | --- |
| `ARCHIVE_UNREADABLE` | error | archive | Not a readable zip (bad signature, truncated, corrupt directory, CRC mismatch). |
| `ARCHIVE_UNSUPPORTED` | error | archive | Encryption, compression other than stored or deflate, multi-disk. |
| `COMPRESSION_RATIO_EXCEEDED` | error | archive | Decompression ratio cap (zip bomb guard). |
| `DUPLICATE_ENTRY` | error | layout | Two entries with the same path, ignoring case. |
| `UNSAFE_PATH` | error | layout | Absolute path, `..`, backslash, drive letter, control characters (zip slip). |
| `UNSAFE_ENTRY` | error | layout | Symbolic link or other non-regular file. |
| `INVALID_FILE_NAME` | error | layout | Name outside `[A-Za-z0-9._-]`, too long or too deep. |
| `EXECUTABLE_FILE` | error | layout | Executable or script, by extension or signature. |
| `UNKNOWN_FILE_TYPE` | error | layout | Extension not on the allowlist. |
| `FILE_TOO_LARGE` | error | layout | A file over the limit for its type. |
| `TOO_MANY_FILES` | error | layout | More than 5,000 files per pack or 20,000 entries per archive. |
| `UNPACKED_TOO_LARGE` | error | layout | Over 1 GiB per pack or 2 GiB per bundle. |
| `MANIFEST_MISSING` | error | layout | No `pack.json` or `bundle.json` at the root. |
| `MANIFEST_AMBIGUOUS` | error | layout | Both at the root. |
| `IGNORED_ENTRY` | warning | layout | Operating system artifact skipped. |
| `LARGE_PACK` | warning | layout | Over 256 MiB unpacked. |
| `MANIFEST_INVALID_JSON` | error | manifest | Not UTF-8 JSON or has a byte order mark (or a standalone index or backup over its size cap). |
| `FORMAT_VERSION_UNSUPPORTED` | error | manifest | `formatVersion` newer than the reader. |
| `SCHEMA_VIOLATION` | error | manifest | Does not match the JSON Schema. |
| `DUPLICATE_ID` | error | references | Duplicate character, pack, index entry or backup contact ID. |
| `FILE_MISSING` | error | references | A referenced file does not exist. |
| `FILE_TYPE_MISMATCH` | error | references | Wrong kind of file for its role. |
| `BUNDLE_PACK_MISSING` | error | references | A bundle folder without `pack.json`. |
| `BUNDLE_LAYOUT_INVALID` | error | references | Overlapping pack folders or a nested bundle. |
| `HAPTIC_INVALID` | error | references | Custom haptic breaks a timeline rule. |
| `WHEN_INVALID` | error | references | Empty or contradictory `when` (time range from = to). |
| `LOOP_POINTS_INVALID` | error | references, media | Loop reversed, shorter than 1 s, or past the end of the audio. |
| `LICENSE_NOT_SPDX` | warning | references | License is free text. |
| `UNREFERENCED_FILE` | warning | references | Media file not referenced by any manifest. |
| `MEDIA_UNDECODABLE` | error | media | Audio or image does not match its signature or cannot be parsed or decoded. |
| `CLIP_DURATION_OUT_OF_RANGE` | error | media | Clip outside 0.2 to 30 s. |
| `RINGTONE_DURATION_OUT_OF_RANGE` | error | media | Ringtone outside 5 s to 6 min. |
| `IMAGE_TOO_LARGE` | error | media | Bitmap over 512 px per side. |
| `SVG_UNSAFE` | error | media | Scripts, handlers, external references, DOCTYPE. |
| `DOWNLOAD_SIZE_MISMATCH` | error | runtime | Download size differs from the index. |
| `DOWNLOAD_HASH_MISMATCH` | error | runtime | SHA-256 differs from the index or backup. |
| `BACKUP_DECRYPTION_FAILED` | error | runtime | Wrong passphrase or tampered encrypted backup. |
| `PACK_DOWNGRADE` | warning | runtime | Importing a lower version than installed. |
| `PACK_SAME_VERSION` | warning | runtime | Same ID and version already installed. |

Runtime codes are reported by the app (downloads, backups, installs), not by file validation.

## Pack repositories

A repository is any HTTPS location that serves `repository.json` and the files it lists. `packer index` generates
it; see [the repository schema](../spec/repository.schema.json).

```json
{
  "formatVersion": 1,
  "name": "Cucuto demo packs",
  "homepage": "https://cucuto.mauri.app",
  "packs": [
    {
      "id": "app.mauri.demo.chiptune-critters",
      "type": "pack",
      "packFormatVersion": 1,
      "name": "Chiptune critters",
      "version": "1.0.0",
      "license": "CC0-1.0",
      "cover": "previews/app.mauri.demo.chiptune-critters/cover.svg",
      "characters": [
        { "id": "frog", "name": "Bleep the frog", "sprite": "previews/app.mauri.demo.chiptune-critters/frog.svg", "sample": "previews/app.mauri.demo.chiptune-critters/frog-sample.ogg", "seedColor": "#4CAF50" }
      ],
      "sizeUnpacked": 180533,
      "download": { "url": "packs/app.mauri.demo.chiptune-critters-1.0.0.soundpack.zip", "size": 171458, "sha256": "8d63…" }
    }
  ]
}
```

Rules:

- **URLs** are absolute `https://` URLs or relative references. Relative references resolve against the URL the index
  was finally fetched from (after redirects), following RFC 3986. Other schemes (`http:`, `file:`, `content:`) and
  protocol-relative references (`//host/…`) are schema violations. When a user adds a folder URL, the app fetches
  `<folder>/repository.json`.
- **IDs** are unique within an index (`DUPLICATE_ID`); an index lists only the latest version of each pack.
- **`type: "bundle"`** entries list their `packs` (with character previews) so apps can show the contents before
  downloading; `type: "pack"` entries may list `characters`. Bundle entries must not have `characters`.
- **`packFormatVersion`** (default 1) lets apps hide entries they cannot read yet.
- **Downloads.** Apps check the advertised `size` before downloading and stop if the transfer exceeds it, then check
  the exact size (`DOWNLOAD_SIZE_MISMATCH`) and SHA-256 (`DOWNLOAD_HASH_MISMATCH`), then run the normal importer.
  Redirects are capped, and credentials are never sent to a different host than the repository's.
- **Previews** are small files (covers, sprites and one short sample clip per character) so browsing stays fast. They
  follow the same media rules as pack files.

## Backups

A backup is a zip archive named `cucuto-backup-YYYY-MM-DD.zip` (same [archive rules](#archive-rules) for paths and safety).

- **Plain backup:** `backup.json` at the root, plus optional `packs/<id>-<version>.soundpack.zip` files for a "full
  backup" (each referenced by its pack entry's `included` path).
- **Encrypted backup:** `envelope.json` ([`$defs/envelope`](../spec/backup.schema.json)) and `payload.bin`. The payload
  is the plain backup zip encrypted with AES-256-GCM: a 12-byte random nonce, a 128-bit tag appended to the
  ciphertext, and the UTF-8 bytes of `cucuto-backup-v1` as additional authenticated data. The key is the 32-byte
  output of Argon2id (version 1.3) over the UTF-8 bytes of the passphrase after Unicode NFC normalization (at least
  19 MiB of memory and 2 iterations, as OWASP recommends; Cucuto writes 64 MiB, 3 iterations, parallelism 1) with a
  random salt of at least 16 bytes. A wrong passphrase or tampered file is `BACKUP_DECRYPTION_FAILED`. Readers may
  refuse memory costs they cannot afford (the app accepts up to 256 MiB).
- **Included packs** of a full backup are valid `.soundpack.zip` files: a single pack, or for a bundle reference a
  bundle holding the installed packs of that bundle. They may be rebuilt from the installed files, so their SHA-256
  can differ from the reference's `sha256` (the file originally imported); readers validate them like any import.

`backup.json` holds:

- `app`: the writing app's ID and version; `createdAt` (UTC).
- `settings`: app-owned key/value pairs; unknown keys are ignored on restore.
- `repositories`: URL, name, whether it is the default or enabled, and the auth type (`none`, `basic`, `bearer`).
  `credentials` appear only in encrypted backups and only when the user opted in.
- `packs`: every installed pack or bundle by ID, version, SHA-256 of the imported file, and source (`repository`
  with its index URL, a direct `url`, or `local`). On restore the app re-downloads packs with a source URL (verifying
  SHA-256) and asks for the files of local-only packs.
- `contacts`: every known contact with all the messenger keys seen for it (`package` + `conversationKey`), phone
  numbers in E.164, the linked Android contact lookup key, group policy, the message assignment (pack ID, character
  ID, disabled clip paths, haptic override) and the ringtone assignment (pack ID, character ID, variant rule).
  Contact IDs are unique within a backup (`DUPLICATE_ID`).

Restore is idempotent: running it twice gives the same result, and it never overwrites assignments made on the new
phone without asking.

## Authoring with pack.yaml

`pack.json` is what apps read. People writing packs by hand usually prefer `pack.yaml`, a friendlier format that
`packer build` turns into a canonical archive: paths relative to the YAML file, clip folders and globs, labels from
file names, character IDs from names, and shorthands such as `when: day` and `haptic: auto`. See the
[packer README](../tools/packer/README.md#packyaml). The pack creators in the app and on the website write `pack.json`
directly.

## Implementations and fixtures

- [`shared/pack-spec`](../shared/pack-spec) is the Kotlin implementation of every rule above, shared by packer and
  the Android importer (`android/core/pack`, see [ADR 0005](decisions/0005-shared-pack-spec-module.md) and
  [ADR 0008](decisions/0008-pack-importer.md)).
- The website validates with the same Kotlin code, `shared/pack-spec` compiled for the browser (owner decision on #60;
  work in #116), so it reports the same codes by construction.
- [`spec/fixtures/fixtures.json`](../spec/fixtures/fixtures.json) lists every golden fixture with its `kind`
  (`package`, `repository`, `backup`, `backup-envelope`), whether it is valid, and the exact sets of error and warning
  codes expected. Folders are unpacked packs or bundles; `.soundpack.zip` files are archives; `.json` files are documents.
  Regenerate them with `kotlin spec/fixtures/generate.main.kts`.

Changes to this format start with a pack format proposal issue and must update the schemas, this document, the
error codes, the fixtures and every implementation together.
