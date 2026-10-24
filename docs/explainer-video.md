# Explainer video brief

The brief for the explainer video on the website's Home page (issue #42). Hand this file to whoever makes the
video, a person or an agent. It says what to show, what to say and what to deliver. How the site plays the
video is in [ADR 0042](decisions/0042-explainer-video-and-slot.md).

> **Owner update 2026-10-24 (#194).** "dont involve humans, AI voice. Dont use the real app ui not phone
> recordings, you can make a simplified version for the video. Only english video". And: "the point of not using
> real screenshots is to make the video evergreen, so if i update the ui the video doesnt get outdated. would be
> nice that the video project files were saved so if i want to add a new feature to the video it is doable, but
> this is NOT a priority, just a optional nice to have."
>
> So: **one English cut** with an **AI voice-over**, **no people** on or behind the camera, and **no real app
> screens or phone recordings**. Every phone shot is a **simplified, illustrated UI** drawn for the video. The video
> is **source in the repository** (scenes, timeline, voice script, sound cues), so a new feature means a new scene.
> Built in #271 ([ADR 0070](decisions/0070-illustrated-explainer-video-project.md)): the project is
> [`android/video`](../android/video/README.md), and the voice is Kokoro-82M, run locally.

Rules from the [project brief](project-brief.md#website) that this video must follow:

- **Demo packs only.** Use the CC0 packs in [`packs/`](../packs) (Chiptune critters, Kitchen orchestra) and the brand
  in [`brand/`](../brand). No Pokémon, no copyrighted sound, picture or music. WhatsApp's own notification tone
  belongs to Meta, so it is never heard either. The "plain ping" in the opening is a brand sound (see
  [Sound design](#sound-design)).
- **Made-up people only.** Every name, chat and message on screen is invented: **Ada**, **Bea** and **Leo**, the
  same cast as the app's tests. No real contact, profile picture or phone number ever appears. See
  [Staging and privacy](#staging-and-privacy).
- **An illustrated app, true to the flows.** ~~The real app: the real screens.~~ Every phone shot is a simplified,
  evergreen drawing of a flow that exists in Cucuto (owner, #194). The drawings use the brand (colors, shapes, the
  cuckoo door, the demo pack art) but not the app's exact layout or text, so a UI change doesn't date the video.
  Never show a feature Cucuto doesn't have, or make a step look easier than it is (setup still needs root and
  LSPosed).
- **Machines only.** No human voice, actor, hand or phone recording. The voice-over is synthesized (an open
  text-to-speech model whose license allows publishing the result).
- **Never autoplays with sound.** The site plays it muted at most, and never moves when the viewer asks for reduced
  motion (ADR 0042).

## At a glance

| | |
| --- | --- |
| Length | **58 s** (target 45–60 s; never longer than 60 s) |
| Frame | 16:9, 1920 × 1080, 60 fps (Cucuto's springs look choppy at 30 fps) |
| Languages | **English only** (owner, #194): one cut with an English AI voice-over. English and Spanish captions; `/es/` plays the English cut with Spanish captions on. ~~Two cuts on the same timeline.~~ |
| Tone | The brand voice ([`brand/README.md`](../brand/README.md#voice-and-tone)): friendly, short, concrete; playful in the moments of joy, plain about requirements. At most one exclamation mark in the whole script. |
| Message | Each WhatsApp contact gets their own character's sound and vibration, so you know who wrote without looking. Free, open source, for rooted Android 17 with LSPosed. |

## Audience

1. **Android tinkerers who already root**: they have Magisk or KernelSU and LSPosed, and they get many WhatsApp
   messages. They want to know in under a minute what Cucuto does, that it is safe to try (WhatsApp is never
   hooked, errors fall back to WhatsApp's tone) and how quickly a contact gets a voice.
2. **Their friends and family**, who see the video shared in a chat and should understand the idea from the
   pictures alone, with the sound off. That's why every beat reads without the voice-over, and the captions name
   every sound.
3. **Pack authors**: people who might make a pack. They should see that characters are just pictures and short
   clips.

The video does not teach the install. It shows that setup exists (root, LSPosed, a self-test) and links to the
site, where the install guide lives.

## Story

One idea, in four movements:

1. **The problem (0–5 s).** Three messages, the same sound three times. Who was that?
2. **The promise (5–9 s).** The cuckoo door pops open: Cucuto gives each contact a voice.
3. **How (9–46 s).** Set up once, import the demo packs, pick a recent sender, tap a character to hear it and tap
   again to assign it, the door celebrates, Simulate message plays exactly what's coming.
4. **The payoff and the call to action (46–58 s).** Real WhatsApp messages from Ada and Bea: a frog, then a kettle.
   You know who wrote without looking. Cucuto, free and open source, at cucuto.mauri.app.

## Shot list

Times are the edit's timeline. "App" shots are the **illustrated** phone UI (owner, #194), not recordings of the real
app: the same steps, drawn simply, with a drawn touch indicator so viewers see each tap. The screen names, button
labels and quotes below describe the flow; the drawings may simplify or drop them (few words on screen, large).
Shots 1 and 9 are illustrated too: a drawn lock screen with generic notification cards (no WhatsApp branding). The phone is
framed in a simple device outline, centered, on the brand's cream background (`#FFF4F0`) with the soft sound
rings of the [social card](../brand/web/og-image.png). Camera moves are slow and small: a push-in of 5 % at most,
no shake, no whip pans.

| # | Time | Beat | Picture | Sound |
| --- | --- | --- | --- | --- |
| 1 | 00:00.0–00:05.0 | **Same ping** | Lock screen (cream wallpaper, no personal widgets). Three WhatsApp notifications land one after another, from Ada ("Dinner at 8?"), Bea ("Running 5 min late") and Leo ("Look at this 😄"). All three look and sound alike. | Plain ping at 0.6, 1.6 and 2.6 s. VO from 2.8 s. |
| 2 | 00:05.0–00:09.0 | **The door** | Full frame: the brand's signature door-open animation ([`brand/motion/door-open.svg`](../brand/motion/door-open.svg), timing in [`motion.md`](../brand/motion.md#the-signature-the-door-pops-open)). The door swings open at 5.45 s, the bird calls, the wordmark fades in below. | `assign.ogg` ("cu-cu!") with the door, at 5.45 s. VO from 6.2 s. |
| 3 | 00:09.0–00:15.0 | **Set up once** | App: onboarding. Welcome ("Give every contact a voice", 1.5 s), swipe to "Before you start" (Android 17, root, LSPosed, 1.5 s), then the self-test: tap **Post the self-test** and the status card turns green, "Cu-cu! All set". Skip the risks and reboot pages in the edit (a quick wipe), but never hide that setup needs root. | VO 9.3–13.6 s. `success.ogg` when the card turns green, at 13.8 s. |
| 4 | 00:15.0–00:21.0 | **Get the demo packs** | App: Home, card "Give your chats a voice" → tap **Get the demo packs** → Discover opens on the "Cucuto demo packs" bundle and the import sheet downloads it (#179) → the review lists Chiptune critters and Kitchen orchestra → tap **Install**. | VO 15.4–19.8 s. `success.ogg` when the packs appear, at 20.0 s. |
| 5 | 00:21.0–00:26.0 | **Recent senders** | App: Home, "Recent senders" lists Ada, Bea and Leo, each with "No voice yet: WhatsApp's tone". The "Give Ada a voice" card: tap **Choose a voice**. | VO 21.3–25.6 s. `tap.ogg` on the tap. |
| 6 | 00:26.0–00:36.0 | **Tap to hear, tap to assign** | App: the picker, "Choose a voice · For Ada". Tap Mew the cat (it squashes and speaks), then Tootle the kettle, then Bleep the frog ("Tap again to assign"). Tap the frog again: the tile morphs, the burst fires in green, the snackbar says "Bleep the frog now speaks for Ada". Hold on the snackbar. | VO 26.1–27.7 s. Cat `mew.ogg` at 27.8 s, kettle `whistle.ogg` at 28.9 s, frog `ribbit.ogg` at 30.0 s. VO 30.9–32.5 s. Assign at 32.6 s: `assign.ogg`, frog `ribbit.ogg` on its second note (+130 ms). |
| 7 | 00:36.0–00:40.0 | **The cuckoo door** | App: back on Home, the first-voice card "Ada has a voice!": the cuckoo door pops open on the frog, with a burst in its colors. | `assign.ogg` with the door at 36.45 s, frog `croak.ogg` +130 ms. VO 37.4–40.0 s. |
| 8 | 00:40.0–00:46.0 | **Simulate message** | App: Ada's contact detail (frog avatar, clip pool). Tap **Simulate message** at 41.6 s; the card says "Through the module…". Show the vibration as two quick ripples around the phone outline (the frog's haptic is two clicks). | VO 40.3–43.0 s. Frog clip at 43.3 s (record the take that plays `ribbit.ogg`). |
| 9 | 00:46.0–00:53.0 | **The real thing** | The illustrated phone, screen on, locked. A message from Ada lands ("Dinner at 8? 🍝") and the phone plays the frog and wiggles. Then one from Bea ("Running 5 min late"): the kettle whistles. Bea was given Tootle the kettle off camera. | Frog `ribbit.ogg` at 46.8 s, kettle `whistle.ogg` at 49.4 s. VO 50.6–52.8 s. |
| 10 | 00:53.0–00:58.0 | **End card** | The Cucuto logo ([`brand/logo/cucuto-logo.svg`](../brand/logo/cucuto-logo.svg), light variant) on cream, the line "Give each contact a voice." and **cucuto.mauri.app**. Fine print: "Free and open source · Android 17 · Needs root and LSPosed · Not affiliated with WhatsApp or Meta". (English only; the Spanish end card is dropped with the Spanish cut, #194.) | `assign.ogg` once at 53.2 s. VO 54.0–57.85 s. Silence to the end. |

**Poster frame.** The frame at 00:32.8 (the burst around the frog tile, snackbar not yet up) is the poster. Export it
as described in [Deliverables](#deliverables). ~~Until the video exists, the site shows an illustrated, text-free
poster with the same idea.~~ Since #302 the site uses the real frame,
[`web/src/lib/assets/images/explainer-poster.png`](../web/src/lib/assets/images/explainer-poster.png).

## Voice-over script

One calm, warm **AI narrator in English** (owner, #194). Not a hype voice. Pronounce Cucuto "koo-KOO-toe" (spell it
phonetically in the text-to-speech input if needed) and say the URL as "cucuto dot mauri dot app". Each line must
fit its slot. The Spanish column is no longer spoken: it is the text of the Spanish captions (`explainer.es.vtt`)
for `/es/`, in Spain Spanish with "tú".

| # | Slot | English (spoken) | Spanish (captions only) |
| --- | --- | --- | --- |
| 1 | 02.8–05.0 | Same sound every time. Who was that? | Siempre el mismo sonido. ¿Quién era? |
| 2 | 06.2–08.9 | Cucuto gives each WhatsApp contact a voice. | Cucuto da voz a cada contacto de WhatsApp. |
| 3 | 09.3–13.6 | Set it up once on a rooted Android 17 phone with LSPosed. | Se configura una vez, en un Android 17 con root y LSPosed. |
| 4 | 15.4–19.8 | Import a sound pack. The free demo packs bring eight characters. | Importa un pack de sonidos. Los de demostración traen ocho personajes. |
| 5 | 21.3–25.6 | Everyone who messaged you shows up here. Let's give Ada a voice. | Aquí aparece quien te ha escrito. Vamos a darle voz a Ada. |
| 6a | 26.1–27.7 | Tap a character to hear it. | Toca un personaje para oírlo. |
| 6b | 30.9–32.5 | Tap it again, and it's Ada's. | Tócalo otra vez y es de Ada. |
| 7 | 37.4–40.0 | Next time Ada writes, you hear Bleep the frog. | La próxima vez que Ada escriba, oirás a la rana Bleep. |
| 8 | 40.3–43.0 | Simulate a message to hear exactly what's coming. | Simula un mensaje y oye justo lo que llegará. |
| 9 | 50.6–52.8 | Now you know who wrote, without looking. | Ahora sabes quién te escribe, sin mirar. |
| 10 | 54.0–57.85 | Cucuto. Free and open source, at cucuto.mauri.app. | Cucuto. Gratis y de código abierto, en cucuto.mauri.app. |

About 95 words, roughly 2.4 words a second while the narrator speaks. The gaps are on purpose: the characters do
the talking.

**The voice is AI-generated** (owner, #194: "dont involve humans, AI voice"; this replaces the earlier default of a
human volunteer). Use an open text-to-speech model run locally, whose model and voice licenses allow publishing the
audio, and record the model, voice and settings next to the script so the voice can be regenerated identically
when a line changes (#271). No cloned or celebrity voice. **Chosen (#271, ADR 0070):** Kokoro-82M v1.0, voice
`af_heart`, on sherpa-onnx 1.13.8, all Apache-2.0. The engine, model, voice and speed rules are pinned in
[`tools/video/voice.main.kts`](../tools/video/voice.main.kts) and the pronunciations in
[`voice-lexicon.txt`](../tools/video/voice-lexicon.txt). The spoken text is the English column above; the timeline
may respell a word for the voice ("cucuto dot mauri dot app").

## Sound design

Only two sources, both CC0:

- **Character clips** from the demo packs, unedited except for level: [`packs/chiptune-critters`](../packs/chiptune-critters)
  (frog `ribbit`, `croak`, `hop`; cat `mew`, `mrrp`, `purr`; bird; bee) and
  [`packs/kitchen-orchestra`](../packs/kitchen-orchestra) (kettle `whistle`, `bubble`, `reel-hook`; pot; glass;
  whisk). Use the files in `packs/*/*/clips/` (no phone recordings, #194).
- **Brand UI sounds** from [`brand/sounds`](../brand/sounds): `assign.ogg` (the "cu-cu!" call), `success.ogg`,
  `tap.ogg`. Rules in [`sound-and-haptics.md`](../brand/sound-and-haptics.md): one UI sound at a time, and the
  character clip starts on the call's second note, about 130 ms after `assign.ogg`.

Specifics:

- **The plain ping (shot 1)** is `brand/sounds/tap.ogg` pitched down one octave (`asetrate=24000,aresample=48000`),
  played three times, identical. It stands in for "the same default tone for everyone" without using WhatsApp's
  tone.
- **No music bed.** The clips are short and quiet; music would bury them. A single soft room tone under the whole
  thing (generated pink noise at −60 dBFS, or none) is fine.
- **Mix.** Voice-over on top at about −18 LUFS short-term; character clips peak just under the voice so they read as
  the stars without being shouty; UI sounds 6 dB under the clips (the app plays them at 40 % too). Duck nothing:
  the script leaves gaps for the clips. Final loudness −16 LUFS integrated, true peak −1 dBTP, stereo (voice and
  clips centered).
- **Haptics can't be heard or seen**, so show them: two short ripples around the phone outline when it vibrates,
  and name the vibration in the captions.

## Captions

Closed captions in WebVTT, one file per language: `explainer.en.vtt` and `explainer.es.vtt`, both timed to the one
English cut (`/es/` shows the Spanish ones). Both are generated from the video's timeline
([`android/video`](../android/video/README.md)) into [`web/static/video/`](../web/static/video) with
`./gradlew :video:writeExplainerCaptions`, keeping the cue IDs; a unit test fails when they don't match it.

- **Captions for deaf and hard-of-hearing viewers (SDH)**: the voice-over plus every meaningful sound in square
  brackets: "[The same plain ping, three times]", "[Cat mews]", "[Cuckoo call, then the frog ribbits]".
  The sounds are the product, so they are always captioned.
- At most 2 lines and 42 characters per line; each cue on screen for 1–6 s; break lines at natural phrase
  boundaries; no cue straddles a shot change by more than a few frames.
- Within ±100 ms of the audio. Sentence case, the script's punctuation, no ALL CAPS.
- No styling in the files (no `::cue` colors, positions or `<c>` classes): the viewer's caption settings apply.
- Captions are never burned into the web cuts. The optional vertical social cut (below) may burn them in, because
  social players often start muted.

## Accessibility checklist

Check every item before delivery.

- [ ] English and Spanish SDH captions (`explainer.en.vtt`, `explainer.es.vtt`), timed to the final edit.
- [ ] No information only in the picture: the voice-over says what each tap does, so no separate audio description
      track is needed (WCAG 1.2.5). Watch it with your eyes closed; then watch it muted with captions.
- [ ] No information only in the sound: every clip, UI sound and vibration is captioned, and vibrations are shown
      with ripples.
- [ ] A text transcript is available: the site builds it from the captions, so keep the captions complete.
- [ ] No flashing more than three times a second (WCAG 2.3.1). The bursts are small and brief; check the whole
      cut with a photosensitive epilepsy analyzer (for example PEAT) and keep its report.
- [ ] Gentle motion: no camera shake, no fast zooms, push-ins of 5 % at most, nothing spinning behind text.
- [ ] Text on screen (illustrated UI, end card) has a contrast of at least 4.5:1, is at least 32 px tall at 1080p, and
      stays up long enough to read twice.
- [ ] Loudness −16 LUFS integrated, true peak −1 dBTP; the voice is clearly above everything else.
- [ ] Made-up people only: no real name, number, profile picture, chat or wallpaper photo anywhere, including the
      status bar, notification shade and keyboard suggestions. Check frame by frame around shots 1 and 9.
- [ ] Only CC0 demo-pack sounds and brand sounds in the mix; no WhatsApp tone, no music.
- [ ] Both caption files line up with the English cut.
- [ ] No real app screenshot or phone recording anywhere: every phone shot is the illustrated UI (#194).

The site's side (in place now, ADR 0042): native controls, keyboard operable, never autoplays with sound, never
autoplays when the viewer prefers reduced motion or saves data, pauses when scrolled away, captions on by default
whenever the video plays muted or the page language differs from the audio, and the transcript below the player.

## Staging and privacy

Everything is drawn, so there is no shoot, no phone and no WhatsApp account (owner, #194). The earlier staging
rules (a clean Cucuto profile, WhatsApp test accounts, Android's demo mode, the recording device) are dropped.

- **Made-up people only.** Ada, Bea and Leo, drawn with initials or demo-pack characters, never photos.
- **No numbers.** No phone number is ever drawn. If one is unavoidable, use the fictional range
  `+44 7700 900000`–`+44 7700 900999` that Ofcom reserves for drama.
- **No WhatsApp branding.** Notification cards are generic (no WhatsApp logo, green or tone); the voice-over and the
  end card may name WhatsApp in words, with the "Not affiliated" line.

## Producing the video

All shots are rendered from source, with no device (owner, #194): the illustrated phone UI (shots 1 and 3–9) and the
motion graphics built from `brand/` files (shots 2 and 10). The project lives in the repository so a new feature can
be added as a new scene: the scenes, the timeline (this shot list with its times), the voice-over text and voice
settings, and the sound cues. Renders, masters and stems are build output, not committed (#271).

### How it is built (#271)

[ADR 0070](decisions/0070-illustrated-explainer-video-project.md) has the details;
[`android/video/README.md`](../android/video/README.md) explains how to add a scene.

1. **Pictures.** The Gradle module `:video` (`android/video/`) draws every shot in Compose from `brand/` and
   `packs/` and never depends on the app. `Explainer.kt` is the timeline: each shot's length, picture, sounds,
   vibrations, captions and voice lines. `./gradlew :video:renderExplainerFrames` renders the 3 480 frames on the
   JVM (Robolectric, no device), about 5 min.
2. **Voice.** `kotlin tools/video/voice.main.kts` speaks the voice lines with Kokoro-82M (voice `af_heart`) on
   sherpa-onnx, locally. Each line must fit its slot, at most 1.15× faster.
3. **Mix and encode.** `kotlin tools/video/explainer.main.kts` places the CC0 clips and brand sounds, puts the voice
   on top, normalizes to −16 LUFS / −1 dBTP and writes the deliverables below, plus a review MP4 with both caption
   tracks.

`cd android && ./gradlew :video:explainerVideo` runs all three into `android/video/build/explainer/`. The manual
`explainer-video` workflow (Actions tab) does the same and uploads the files as an artifact. The workflow commits
nothing: an approved cut is published by hand, see [Publishing](#publishing).

### Publishing

> **Owner update 2026-10-24 (#302).** "the video is ready to be published it is a moment". The cut from run
> [40092869144](https://github.com/mauriciabad/cucuto/actions/runs/40092869144) is live on Home
> ([ADR 0070](decisions/0070-illustrated-explainer-video-project.md#published-2026-10-24-302)).

The published cut lives in the repository as website assets (about 6.3 MB): `web/static/video/explainer.webm`,
`web/static/video/explainer.mp4` and `web/src/lib/assets/images/explainer-poster.png`. To republish after a change
to the video:

1. Change the scenes or the timeline in `android/video`, then run `cd android && ./gradlew
   :video:writeExplainerCaptions` and commit the regenerated `web/static/video/explainer.{en,es}.vtt` with it (the
   `:video` tests fail if they don't match the timeline).
2. Once that is on `main`, run the `explainer-video` workflow from the Actions tab (keep `av1_preset` at 4).
3. Watch the review MP4 in the artifact, with sound and with each caption track. Publish only a cut a person has
   watched and approved (the owner, for a change to the story or the voice).
4. Download the artifact (`gh run download <run id> -n explainer-video`) and copy `explainer.webm` and
   `explainer.mp4` over the ones in `web/static/video/` and `explainer-poster.png` over the one in
   `web/src/lib/assets/images/`. Don't commit `explainer-review.mp4` or `voice/`.
5. Check the sizes against the budgets (WebM 10 MB, MP4 16 MB), run `cd web && bun run check && bun run test && bun
   run build`, and open a PR that names the run. Merging it to `main` redeploys the site.

To take the video down, delete the two cuts: the site goes back to the "coming soon" card on its own (it then needs a
poster again in `web/src/lib/assets/images/`).

Earlier routes, kept for the record (from #42, before #194): an animatic from the scripted screenshots (#41), the
app's real screens rendered offline (built in #195, [ADR 0060](decisions/0060-explainer-video-draft-pipeline.md),
now superseded) and UI Automator with `screenrecord` on the device. The owner's answer ruled out the last two.

## Credits

- Voice: [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) v1.0 by hexgrad, voice `af_heart`, Apache-2.0,
  run with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0). The audio it makes has no licence
  conditions.
- Sounds: the CC0 demo packs ([`packs/`](../packs)) and the brand's UI sounds (CC0, [`brand/sounds`](../brand/sounds)).
- Pictures: the brand ([`brand/`](../brand), GPL-3.0-or-later) and the demo packs' sprites (CC0). Typeface: Google
  Sans Flex (SIL OFL 1.1).

## Deliverables

Web files go in `web/static/video/` (served at `/video/…`; the site's service worker never precaches them). The
site finds them at build time: the English cut makes the player appear, and `/es/` plays it with Spanish captions.
There is no Spanish cut (owner, #194). The site's support for an optional `explainer.es.*` stays harmless and
unused.

| File | What | Spec |
| --- | --- | --- |
| `explainer.webm` | English cut, modern browsers | AV1 (SVT-AV1, `-crf 34 -preset 4`, 10-bit `yuv420p10le`) + Opus 96 kb/s stereo, 1920 × 1080, 60 fps. Budget 10 MB. |
| `explainer.mp4` | English cut, everything else (Safari without AV1 hardware) | H.264 High 4.2 (`libx264 -crf 22 -preset slow -pix_fmt yuv420p`) + AAC-LC 128 kb/s, `-movflags +faststart`, 1920 × 1080, 60 fps. Budget 16 MB. |
| `explainer.en.vtt`, `explainer.es.vtt` | Captions | WebVTT, UTF-8, LF line endings, SDH (see [Captions](#captions)). |
| `web/src/lib/assets/images/explainer-poster.png` | Poster frame | The frame at 00:32.8 of the English cut, 1920 × 1080 PNG. The site encodes it to JPEG XL, AVIF and PNG at build time (`?picture`), so the poster is served as JPEG XL with fallbacks for browsers that can't decode it. `ExplainerVideo.svelte` imports it (the SVG stand-in was deleted in #302). |

Encoding, from a lossless master (`master-en.mov`):

```sh
ffmpeg -i master-en.mov -c:v libsvtav1 -crf 34 -preset 4 -pix_fmt yuv420p10le -g 240 \
  -c:a libopus -b:a 96k explainer.webm
ffmpeg -i master-en.mov -c:v libx264 -profile:v high -level 4.2 -crf 22 -preset slow -pix_fmt yuv420p -g 120 \
  -c:a aac -b:a 128k -movflags +faststart explainer.mp4
ffmpeg -ss 32.8 -i master-en.mov -frames:v 1 explainer-poster.png
```

**In the repository (the project files, owner #194):** the scene sources, the timeline, the voice-over text with
its text-to-speech settings, the sound cue list and the assembly script, so anyone can re-render the video or add
a scene. **Kept outside the repository** (large and rebuildable; attach them to a GitHub release if worth keeping):
the lossless masters (FFV1), the stems (voice, clips, UI sounds) and the photosensitivity report.

Optional, not used by the site: a vertical 1080 × 1920 cut for social posts, same timeline, phone filling the frame,
captions burned in.
