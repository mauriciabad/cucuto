# 0070. Explainer video: an illustrated, AI-voiced project in `android/video`

- Status: accepted; supersedes the screen recording part of [ADR 0060](0060-explainer-video-draft-pipeline.md)
- Date: 2026-10-24
- Issues: #271 (this work), #194 (owner's answer), #195 and #260 (the draft pipeline it replaces), #42 and
  [ADR 0042](0042-explainer-video-and-slot.md) (brief and the website's slot), #278 (docs updated for #194)

## Context

The owner answered #194 (verbatim in [`docs/owner-log.md`](../owner-log.md)):

> dont involve humans, AI voice.
> Dont use the real app ui not phone recordings, you can make a simplified version for the video.
> Only english video

> just a note, the point of not using real screenshots is to make the video evergreen, so if i update the ui the
> video doesnt get outdated.
> would be nice that the video project files were saved so if i want to add a new feature to the video it is
> doable, but this is NOT a priority, just a optional nice to have.

The draft pipeline from #260 (ADR 0060) recorded the app's real screens in `:app`'s tests, in English and Spanish,
and had no voice. Every part of that contradicts the answer except the frame stepping, the cue files and the ffmpeg
assembly.

What the video needs now: a simplified, illustrated UI that never depends on the app's screens; an English voice
from an AI text-to-speech engine that runs locally, is open source and allows publishing its output; CC0 sounds
only; English and Spanish captions; AV1 and H.264 cuts and a poster from a manual CI workflow; and the whole project
as small, diffable source, so a feature can be added later.

## Decision

### Renderer: Compose on the JVM, in its own module

The video is the Gradle module **`:video`** (`android/video/`), an Android library that depends only on Compose,
Material 3, Coil's SVG decoder and the brand: the brand's color scheme and type scale (`brand/tokens/Color.kt` and
`Type.kt`, copied into a generated source folder as the app's brand sounds are, ADR 0003), the brand typeface, the
logo SVGs and the demo packs' sprites and sounds. **It never depends on `:app`**, so no app screen, view model or
string can change or break the video.

Frames render headlessly under Robolectric Native Graphics, as the screenshot tests do (ADR 0032), at 1920 × 1080
and 60 fps: a 960 × 540 dp stage at 2 px per dp.

Why Compose and not a web or SVG renderer (Remotion, an SVG timeline in headless Chrome, Lottie):

- **The brand is already Compose.** The color scheme, the type scale with Google Sans Flex and its roundness axis,
  the cookie and burst shapes (`MaterialShapes`, `Morph`) and the motion springs (`brand/motion.md`) are Compose APIs
  and tokens. A web renderer would redraw all of that in another stack.
- **Kotlin only** (CLAUDE.md). A web renderer means a Node toolchain and a headless browser in CI for one file.
- **#260's frame stepper and assembly carry over**: one recorder that sets the time, lets Compose draw a frame and
  captures it, identical frames stored once, and the ffmpeg script.
- Cost: Robolectric draws a frame in about 80 ms, so the 3 480 frames take about 5 min (M-series Mac). Fine for a
  manual workflow; the normal build only renders one frame per shot as a smoke test.

Compose Desktop would draw faster, but it brings the JetBrains Compose plugin and a second Compose stack for no
visible gain.

### Every frame is a function of time

Scenes don't use Compose's animation clock. Each shot's composable gets `t` (seconds since the shot started) and
computes every position, color and shape from it with closed-form springs (the brand's motion tokens), fades, taps,
squash and stretch (`Motion.kt`). There is no state to advance, so any frame renders on its own
(`-Pexplainer.stills=32.8` renders just that frame), renders are identical every time, and a transition can draw the
previous shot's last frame next to the new shot's first.

### The project file: one timeline

`Explainer.kt` lists the shots back to back. A shot has a length, a frame (the phone, or the full 16:9 frame), its
picture, its sounds (file, gain, and optionally the character that pops out beside the phone while it speaks), its
vibrations (drawn as ripples) and its captions. A voice line is a caption with a spoken text. All times are relative
to the shot, so inserting a shot moves everything after it.

The timeline generates the rest, so nothing drifts:

- `web/static/video/explainer.{en,es}.vtt` (`./gradlew :video:writeExplainerCaptions`); a unit test fails when the
  committed files don't match.
- `voice.tsv` (the lines to speak and their slots) and `cues.tsv` (the mix), written with the frames.

Unit tests check the brief's rules on the timeline: at most 60 s, captions 0.8–6 s with at most 2 lines of 42
characters, voice lines that don't overlap, and sounds only from `packs/` and `brand/sounds/`.

How to add a scene: [`android/video/README.md`](../../android/video/README.md).

### The illustrated UI

A phone outline on the cream stage with the social card's sound rings. Screens are a few big parts in the brand's
colors and type: a lock screen with generic notification cards (no WhatsApp branding), a three-step setup with a
self-test, a demo-pack card, the recent chats (Ada, Bea, Leo with cookie avatars), the eight-character grid, the
cuckoo house in the frog's colors, Ada's card with Simulate message, and the end card. The door (shot 2) and the
assign burst are drawn from the brand's geometry and motion spec (`brand/logo/cucuto-mark.svg`,
`brand/motion.md`), not from the app's `CuckooDoor`. When a character speaks, it also pops out beside the phone,
big, with sound arcs: the sound is the product, so the picture shows who made it. Taps show a drawn touch indicator.
Text is at least 16 sp (32 px), the issue's minimum; the previous draft's phone text was 15 px.

### Voice: Kokoro-82M on sherpa-onnx, run locally

`tools/video/voice.main.kts` speaks each line of `voice.tsv` with:

| | |
| --- | --- |
| Engine | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8, its prebuilt `sherpa-onnx-offline-tts` (Apache-2.0) |
| Model | [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) v1.0 as packaged by sherpa-onnx (`kokoro-multi-lang-v1_0`), Apache-2.0 |
| Voice | `af_heart` (speaker 3), American English, length scale 1.0 unless a line needs up to 1.15× to fit its slot |
| Pronunciation | Kokoro's US lexicon plus [`tools/video/voice-lexicon.txt`](../../tools/video/voice-lexicon.txt): Cucuto (koo-KOO-toe), LSPosed, WhatsApp, mauri |

The engine and model are downloaded from their GitHub releases on first use, pinned by version and SHA-256, and
cached (CI caches them too). Nothing leaves the machine; no account, key or paid service.

**Licence.** The Kokoro weights and voices are Apache-2.0, which puts no condition on what the model outputs, so the
voice-over can be published and bundled with the video and the site (GPL-3.0-or-later). The model card says it was
trained only on permissively licensed and non-copyrighted audio. Its voices are built-in style vectors, not a clone
of a named person. sherpa-onnx is Apache-2.0 too. Its English front end looks words up in the lexicon and falls back
to espeak-ng's data (GPL-3.0) for unknown words; that runs only at generation time and isn't distributed, and the
licence of a tool doesn't extend to the audio it makes. The credits are in the brief anyway
([`docs/explainer-video.md`](../explainer-video.md#credits)).

Alternatives: **Piper** voices have mixed dataset licences (several are research or non-commercial only) and sound
flatter. **Kokoro in Python** pulls PyTorch (about 2 GB) into CI. **Cloud TTS** (paid, an account) needs the owner's
go-ahead, and the local model is good enough.

Each line is trimmed of the model's leading and trailing silence and must fit its slot. If it doesn't, it is spoken
slightly faster, up to 1.15×, and the script fails beyond that. Two slots were widened for the voice: line 1 now
runs 2.8–5.0 s (was 3.0–4.9) and line 10 runs 54.0–57.85 s (was 57.6). The voice track is levelled to about
−18 LUFS. As a check, Whisper base.en (also through sherpa-onnx) transcribed the lines back: nine of eleven came
back word for word or nearly ("Cucuto" included, after the lexicon entry). The misses were "Import a sound pack"
heard as "In Potosound Pack", "give Ada a voice" as "give Adora a voice", "it's Ada's" cut short, "source" as
"sauce" and "mauri" as "mori". Those are typical speech-recognition slips on names and run-together words, not
proof the voice is clear to a person, so a human should still listen (see the website section below).

### Sound, captions, output

Unchanged from ADR 0060 except where noted. Cues are CC0 demo-pack clips and brand sounds (the plain ping is
`tap.ogg` an octave down). The voice goes on top, and the mix is normalized in two passes to −16 LUFS / −1 dBTP.
English and Spanish SDH captions come from the timeline. `tools/video/explainer.main.kts` writes a lossless master
and the brief's `explainer.webm` (SVT-AV1 + Opus), `explainer.mp4` (H.264 + AAC), `explainer-poster.png` (00:32.8)
and a review MP4 with both caption tracks. **One English cut**: the Spanish cut and `-Pexplainer.languages` are gone.

The manual `explainer-video` workflow renders, speaks, mixes and uploads the files as an artifact. Nothing is
committed. The flat illustrations compress well: about 2.4 MB for the WebM and 3.9 MB for the MP4, far under the
brief's 10 and 16 MB budgets.

### The website keeps "coming soon" for now

#271 leaves publishing out of scope ("a separate decision once a cut with voice exists"). The video is now a full
cut with an AI voice, and its pictures look finished. But no human has watched it or heard the voice yet: this agent
can't hear audio, and the Whisper check proves intelligibility, not quality. So `web/static/video/` stays empty, and
a `question` issue (#302) asks the owner to watch the artifact and say whether to publish. Publishing means copying three
files (`explainer.webm`, `explainer.mp4`, the poster) into the site, as ADR 0042 describes.

## Consequences

- The app's UI can change freely; the video only changes when someone edits `android/video`.
- The video tells the same story as the app but is no longer proof of it. Keeping it true is a review rule: the
  brief's "never show a feature Cucuto doesn't have", checked when the timeline changes.
- Adding a feature is a scene file and a timeline entry. The captions regenerate, the voice is spoken again, and the
  workflow rebuilds the cut. The 60 s limit means something else gets shorter.
- `:video` adds about 10 s to `./gradlew check` (Robolectric start and one frame per shot) and nothing to the APK.
- The voice depends on two pinned downloads (about 370 MB, cached). A new Kokoro or sherpa-onnx release only
  changes the voice when someone updates the pins and the digests.
- The app keeps `FeedbackController`'s injectable clock from ADR 0060. It's harmless, and tests can still step beats.
