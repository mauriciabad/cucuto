# packer

Command-line tool that builds, validates, bundles and indexes Cucuto sound packs (`.soundpack.zip`). It enforces the
same rules as the app's importer ([pack format](../../docs/pack-format.md)) and writes reproducible archives.

## Install

Needs JDK 21 (Homebrew: `brew install openjdk@21`).

```sh
cd tools/packer
export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home
./gradlew installDist          # builds cli/build/install/packer
./gradlew build                # compiles and runs all tests, including every golden fixture in spec/fixtures
```

Run it as `tools/packer/cli/build/install/packer/bin/packer` (or add that `bin/` folder to your `PATH`). The
distribution is self-contained: copy `cli/build/install/packer` anywhere with a Java 21 runtime.

Optional tools for the media flags: `ffmpeg` (with libopus) and `cjxl` (`brew install ffmpeg jpeg-xl`). They are only
needed for `--normalize`, `--trim-silence`, `--opus`, `--jxl` and `validate --deep`.

## Commands

### build

```sh
packer build packs/chiptune-critters                  # writes ./app.mauri.demo.chiptune-critters-1.0.0.soundpack.zip
packer build packs/chiptune-critters -o dist/         # into a folder
packer build my-pack -o my-pack.soundpack.zip --opus --normalize --trim-silence --jxl
```

Turns a folder with `pack.yaml` (or an unpacked pack or bundle with `pack.json`) into `<id>-<version>.soundpack.zip`. The result is
validated with the full importer rules first; nothing is written if there is an error. Output names always end in
`.zip`: `-o` takes a folder or a `.zip` file name, and a bare `.soundpack` name is refused
([ADR 0007](../../docs/decisions/0007-zip-file-names.md)).

| Flag | Effect (needs) |
| --- | --- |
| `--normalize` | Loudness to -16 LUFS integrated, -1.5 dBTP (two-pass EBU R128 `loudnorm`) for clips and ringtones (ffmpeg). |
| `--trim-silence` | Removes leading and trailing audio below -50 dBFS from clips; ringtones are left alone so loop points stay valid (ffmpeg). |
| `--opus` | Transcodes audio to Ogg Opus `.ogg` (96 kb/s clips, 128 kb/s ringtones) and rewrites the manifest paths (ffmpeg). |
| `--jxl` | Converts PNG and GIF losslessly (`cjxl -d 0 -e 1`: fast to decode on the phone, ADR 0006), and JPEG by lossless transcoding, to JPEG XL (cjxl). SVG, WebP and AVIF are kept. |

Without flags no media is touched. Re-encoding without `--opus` keeps each file's format.

**Reproducible output.** The manifest comes first, then all entries sorted by path; every entry has the timestamp
1980-01-01 00:00 and permissions 0644; names are UTF-8; there are no extra fields or comments. Text, JSON, SVG and WAV
are deflated (level 9) when that saves space, everything else is stored. The same sources and packer version give
byte-identical files (and the same SHA-256). Media conversions are reproducible with the same ffmpeg and cjxl
versions.

### validate

```sh
packer validate pack.soundpack.zip unpacked-folder/ source-with-pack-yaml/ repository.json backup.json
packer validate --json --strict dist/*.soundpack.zip
```

Validates pack and bundle archives (any `.zip`; the type comes from the manifest inside), unpacked pack or bundle folders, `pack.yaml` sources, repository indexes
(`repository.json`), backups (`backup.json`) and encrypted backup envelopes (`envelope.json`). Use `--kind` when a
JSON file has another name. Every issue carries a stable code from
[`spec/error-codes.json`](../../spec/error-codes.json), the file and a JSON Pointer:

```
spec/fixtures/invalid/loop-beyond-end: INVALID (1 error(s), 0 warning(s))
  error   LOOP_POINTS_INVALID  pack.json /characters/0/ringtones/0/loop: Loop 1000..9000 ms does not fit in 6000 ms of audio
```

| Option | Effect |
| --- | --- |
| `--json` | Machine-readable report: `[{ "input", "valid", "errors": [{ "code", "message", "file", "pointer" }], "warnings": [...] }]` |
| `--strict` | Warnings fail too. |
| `--deep` | After the header checks, fully decodes every audio file and bitmap with ffmpeg (JPEG XL with djxl). |

Exit codes: 0 valid, 1 invalid, 2 bad input (for example an unreadable `pack.yaml`).

### bundle

```sh
packer bundle --id app.mauri.demo.all --name "Cucuto demo packs" --version 1.0.0 \
  --license CC0-1.0 --cover packs/chiptune-critters/cover.svg -o dist/ \
  packs/chiptune-critters packs/kitchen-orchestra
```

Combines packs (source folders or built `.soundpack.zip` files) into one bundle. Each pack goes to `packs/<pack id>/`.
Options: `--author`, `--license`, `--description`, `--homepage`, `--tag` (repeatable), `--cover`, and the same
media flags as `build` (applied to source folders).

### index

```sh
packer index -o site/repo --name "Cucuto demo packs" --homepage https://cucuto.mauri.app dist/
packer index -o site/repo --name "My packs" \
  --download-base-url https://github.com/me/packs/releases/download/v1 dist/*.soundpack.zip
```

Writes a ready-to-host repository folder:

```
site/repo/
├── repository.json
├── previews/<id>/cover.svg, <character>.svg, <character>-sample.ogg   (bundles: previews/<bundle>/<pack>/...)
└── packs/<file>.soundpack.zip          (only without --download-base-url)
```

Folder inputs contribute every `.zip` in them. Every input is validated first. Entries are sorted by ID; if an ID appears twice the higher version wins. Each
download has its exact size and SHA-256. Preview URLs are relative, so the folder can be hosted anywhere (they resolve
against the index URL). `generatedAt` is written only with `--generated-at` or when `SOURCE_DATE_EPOCH` is set, so
indexes stay reproducible.

### ring

```sh
packer ring packs/kitchen-orchestra -o rings/                         # every ringtone of a pack or bundle
packer ring my-songs/ -c bubblegum -v 2 -o bubblegum-radio.ogg --normalize
```

Renders **ring files**, the audio the app exports for Android's per-contact ringtone, to hear exactly what a phone
will play. Telecom loops the whole file, so the intro plays once and the loop section repeats until the file lasts at
least 90 s; `loop: false` is padded with silence; the fade-in happens once
([rule](../../docs/pack-format.md#ring-files)). Writes Ogg Opus at 48 kHz, 64 kb/s per channel, named
`<pack id>-<character id>-<n>.ogg` (`n` counts the character's ringtones from 1) unless `-o` names one `.ogg` file. A
ringtone that needs no rendering (a whole-file loop of 90 s or more without fade-in) is copied when its extension
matches. `--normalize` applies the same loudness normalization as `build`. Needs ffmpeg and ffprobe.

### cut

```sh
packer cut songs/bubblegum.flac -o clips/bubblegum-hook.ogg                 # the hook, 2.5 s
packer cut songs/bubblegum.flac -o clips/intro.ogg --start-ms 1200 --length-ms 3000
```

Cuts a notification clip from a song as Ogg Opus (96 kb/s), with a 10 ms fade-in and a 200 ms fade-out. Without
`--start-ms` it finds the hook: the loudest stretch, moved back to its beat
([hook finder](../../docs/pack-format.md#auto-cut-clips)); `--from-ms` and `--to-ms` limit the search (for example to
the ringtone's loop section). Needs ffmpeg and ffprobe.

## pack.yaml

`pack.yaml` is the authoring format: `packer build` maps it onto `pack.json` and the canonical layout
(`cover.<ext>`, `characters/<id>/sprite.<ext>`, `characters/<id>/clips/<name>.<ext>`, ...). Paths are relative to the
YAML file and may point anywhere inside its folder.

```yaml
id: io.github.me.my-cats            # pack ID, reverse domain
name: My cats
version: 1.0.0
author: Me
license: CC-BY-4.0                  # SPDX expression, or free text (warning)
description: Optional text.
cover: art/cover.png
tags: [animals]
characters:
  - name: Tabby                     # id defaults to the name as a slug: "tabby"
    sprite: tabby/tabby.png
    spriteAnimated: tabby/tabby.gif
    seedColor: "#F4A7C0"            # quote it: '#' starts a YAML comment
    clips: tabby/clips/             # a folder: every audio file in it, sorted by name
  - id: mittens
    name: Mittens
    sprite: mittens.svg
    clips:
      - mittens/meow-*.ogg          # glob, relative to pack.yaml
      - file: mittens/purr.wav      # full form
        label: Purr                 # default: from the file name ("purr" -> "Purr")
        weight: 2
      - cut: songs/day.flac         # auto-cut clip: the song's hook, cut at build time (needs ffmpeg)
      - cut: songs/night.flac       # or from a given point: startMs, lengthMs (default 2500),
        startMs: 30500              # or search only fromMs..toMs for the hook
        label: Lullaby
    ringtones:
      - file: songs/day.flac
        when: day                   # shorthand for { time: day }; also a season or weekday name
        loop: { startMs: 1200, endMs: 98000 }
        fadeInMs: 500
      - file: songs/night.flac
        when: { time: night, season: [autumn, winter] }
    haptic: auto                    # or none, or a list of events (custom):
    # haptic:
    #   - { at: 0, preset: CLICK, scale: 0.8 }
    #   - at: 120
    #     envelope:
    #       - { intensity: 1.0, sharpness: 0.8, ms: 120 }
    #       - { intensity: 0.0, sharpness: 0.4, ms: 180 }
```

Every other key is copied to `pack.json` unchanged, so all pack.json fields work. Text files next to `pack.yaml`
named `LICENSE`, `LICENCE`, `README`, `CREDITS`, `NOTICE`, `AUTHORS` or `COPYING` (optionally with `.txt` or `.md`)
are included. Output file names are
lowercase slugs of the source names (`My Clip.WAV` becomes `clips/my-clip.wav`).

### Auto-cut clips

A `cut:` entry in `clips` makes a notification clip from a song when the pack is built, with the same hook finder as
`packer cut`: `clips/<song>-hook.ogg`, labeled "<Song> (hook)", with an `x-cut` key recording the source and range
(readers ignore `x-` keys). The result is an ordinary clip, so the pack stays format 1. It needs ffmpeg at build time
and the encoder's output can differ between ffmpeg versions; for packs that must build byte-for-byte anywhere, run
`packer cut` once and commit the clip instead (as `packs/generate.main.kts` does for the demo packs).

## Layout

| Module | Contents |
| --- | --- |
| [`../../shared/pack-spec/`](../../shared/pack-spec) | Kotlin Multiplatform validation core (`commonMain` has no JVM APIs) plus JVM adapters, shared with `android/core/pack` and included with `includeBuild` ([ADR 0005](../../docs/decisions/0005-shared-pack-spec-module.md)); its `js` target is the website's pack checker ([ADR 0022](../../docs/decisions/0022-pack-spec-in-the-browser.md)). Its tests, which run every golden fixture, run with packer's `./gradlew build`. |
| `cli/` | The `packer` command (Clikt), `pack.yaml` mapping (kaml), deterministic zip writer, ffmpeg and cjxl integration, indexer. |

Versions are in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
