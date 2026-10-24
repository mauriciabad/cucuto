# Demo packs

CC0-1.0 demo packs used by the website, screenshots and the explainer video. Every file is made for this repository:
sounds are synthesized with ffmpeg, pictures are hand-written SVG.

| Pack | ID | Characters |
| --- | --- | --- |
| [Chiptune critters](chiptune-critters) | `app.mauri.demo.chiptune-critters` | frog (with day and night clips and a day/night ringtone), bird (with special clips: a dawn chorus for the first message of the day, a squawk for the third in a row and a rare cuckoo), cat, bee |
| [Kitchen orchestra](kitchen-orchestra) | `app.mauri.demo.kitchen-orchestra` | pot, glass, whisk, kettle (with a ringtone that has an intro, a loop and an outro, and a clip cut from it) |

CI (the `packs` job in `.github/workflows/ci.yml`) also builds them into one bundle, `app.mauri.demo.all`, and a repository index.

## Build

```sh
(cd tools/packer && ./gradlew installDist)
packer=tools/packer/cli/build/install/packer/bin/packer
$packer build packs/chiptune-critters -o dist/
$packer build packs/kitchen-orchestra -o dist/
$packer bundle --id app.mauri.demo.all --name "Cucuto demo packs" --version 1.1.0 --license CC0-1.0 \
  --author "Cucuto contributors" --cover packs/demo-bundle-cover.svg -o dist/ packs/chiptune-critters packs/kitchen-orchestra
$packer index -o dist/repo --name "Cucuto demo packs" --homepage https://cucuto.mauri.app dist/
```

## Regenerate the sounds

```sh
(cd tools/packer && ./gradlew installDist)
kotlin packs/generate.main.kts      # from the repository root; needs ffmpeg with libopus and the kotlin CLI
```

The script overwrites the `.ogg` files from ffmpeg expressions; edit the expressions there, never the audio files.
Auto-cut clips (the kettle's "reel hook") are then cut from the ringtones with `packer cut` and committed, so building
the packs never needs ffmpeg. `packer ring packs/kitchen-orchestra -o rings/` renders the ring files a phone would
play.

## Adding a pack

Only content you made yourself or that is clearly CC0, with its provenance in the pack's README. Add a `LICENSE`
(CC0-1.0), a `pack.yaml` ([format](../tools/packer/README.md#packyaml)) and list the pack in the `packs` job of `.github/workflows/ci.yml`.
