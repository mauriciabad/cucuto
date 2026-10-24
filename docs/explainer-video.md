# Explainer video brief

The brief for the explainer video on the website's Home page (issue #42). Hand this file to whoever makes the
video, a person or an agent. It says what to show, what to say and what to deliver. How the site plays the
video is in [ADR 0042](decisions/0042-explainer-video-and-slot.md).

Rules from the [project brief](project-brief.md#website) that this video must follow:

- **Demo packs only.** Use the CC0 packs in [`packs/`](../packs) (Chiptune critters, Kitchen orchestra) and the brand
  in [`brand/`](../brand). No Pokémon, no copyrighted sound, picture or music. WhatsApp's own notification tone
  belongs to Meta, so it is never heard either. The "plain ping" in the opening is a brand sound (see
  [Sound design](#sound-design)).
- **Made-up people only.** Every name, chat and message on screen is invented: **Ada**, **Bea** and **Leo**, the
  same cast as the app's tests. No real contact, profile picture or phone number ever appears. See
  [Staging and privacy](#staging-and-privacy).
- **The real app.** Every app shot shows a flow that exists in Cucuto today, with the real screens and the real
  motion and sound. Nothing is mocked up to look better than the app.
- **Never autoplays with sound.** The site plays it muted at most, and never moves when the viewer asks for reduced
  motion (ADR 0042).

## At a glance

| | |
| --- | --- |
| Length | **58 s** (target 45–60 s; never longer than 60 s) |
| Frame | 16:9, 1920 × 1080, 60 fps (Cucuto's springs look choppy at 30 fps) |
| Languages | Two cuts on the same timeline: English (app in English, English voice-over) and Spanish (app in Spanish, Spanish voice-over). Captions in both languages work on both cuts. |
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

Times are the edit's timeline, the same for both cuts. "App" shots are screen recordings of the real app (see
[Producing the footage](#producing-the-footage)); keep the touch indicator on so viewers see each tap. The phone is
framed in a simple device outline, centered, on the brand's cream background (`#FFF4F0`) with the soft sound
rings of the [social card](../brand/web/og-image.png). Camera moves are slow and small: a push-in of 5 % at most,
no shake, no whip pans.

| # | Time | Beat | Picture | Sound |
| --- | --- | --- | --- | --- |
| 1 | 00:00.0–00:05.0 | **Same ping** | Lock screen (cream wallpaper, no personal widgets). Three WhatsApp notifications land one after another, from Ada ("Dinner at 8?"), Bea ("Running 5 min late") and Leo ("Look at this 😄"). All three look and sound alike. | Plain ping at 0.6, 1.6 and 2.6 s. VO from 3.0 s. |
| 2 | 00:05.0–00:09.0 | **The door** | Full frame: the brand's signature door-open animation ([`brand/motion/door-open.svg`](../brand/motion/door-open.svg), timing in [`motion.md`](../brand/motion.md#the-signature-the-door-pops-open)). The door swings open at 5.45 s, the bird calls, the wordmark fades in below. | `assign.ogg` ("cu-cu!") with the door, at 5.45 s. VO from 6.2 s. |
| 3 | 00:09.0–00:15.0 | **Set up once** | App: onboarding. Welcome ("Give every contact a voice", 1.5 s), swipe to "Before you start" (Android 17, root, LSPosed, 1.5 s), then the self-test: tap **Post the self-test** and the status card turns green, "Cu-cu! All set". Skip the risks and reboot pages in the edit (a quick wipe), but never hide that setup needs root. | VO 9.3–13.6 s. `success.ogg` when the card turns green, at 13.8 s. |
| 4 | 00:15.0–00:21.0 | **Get the demo packs** | App: Home, card "Give your chats a voice" → tap **Get the demo packs** → Discover opens on the "Cucuto demo packs" bundle and the import sheet downloads it (#179) → the review lists Chiptune critters and Kitchen orchestra → tap **Install**. | VO 15.4–19.8 s. `success.ogg` when the packs appear, at 20.0 s. |
| 5 | 00:21.0–00:26.0 | **Recent senders** | App: Home, "Recent senders" lists Ada, Bea and Leo, each with "No voice yet: WhatsApp's tone". The "Give Ada a voice" card: tap **Choose a voice**. | VO 21.3–25.6 s. `tap.ogg` on the tap. |
| 6 | 00:26.0–00:36.0 | **Tap to hear, tap to assign** | App: the picker, "Choose a voice · For Ada". Tap Mew the cat (it squashes and speaks), then Tootle the kettle, then Bleep the frog ("Tap again to assign"). Tap the frog again: the tile morphs, the burst fires in green, the snackbar says "Bleep the frog now speaks for Ada". Hold on the snackbar. | VO 26.1–27.7 s. Cat `mew.ogg` at 27.8 s, kettle `whistle.ogg` at 28.9 s, frog `ribbit.ogg` at 30.0 s. VO 30.9–32.5 s. Assign at 32.6 s: `assign.ogg`, frog `ribbit.ogg` on its second note (+130 ms). |
| 7 | 00:36.0–00:40.0 | **The cuckoo door** | App: back on Home, the first-voice card "Ada has a voice!": the cuckoo door pops open on the frog, with a burst in its colors. | `assign.ogg` with the door at 36.45 s, frog `croak.ogg` +130 ms. VO 37.4–40.0 s. |
| 8 | 00:40.0–00:46.0 | **Simulate message** | App: Ada's contact detail (frog avatar, clip pool). Tap **Simulate message** at 41.6 s; the card says "Through the module…". Show the vibration as two quick ripples around the phone outline (the frog's haptic is two clicks). | VO 40.3–43.0 s. Frog clip at 43.3 s (record the take that plays `ribbit.ogg`). |
| 9 | 00:46.0–00:53.0 | **The real thing** | The phone on a table, screen on, locked. A real WhatsApp message from Ada lands ("Dinner at 8? 🍝") and the phone plays the frog and wiggles. Then one from Bea ("Running 5 min late"): the kettle whistles. Bea was given Tootle the kettle off camera. | Frog `ribbit.ogg` at 46.8 s, kettle `whistle.ogg` at 49.4 s. VO 50.6–52.8 s. |
| 10 | 00:53.0–00:58.0 | **End card** | The Cucuto logo ([`brand/logo/cucuto-logo.svg`](../brand/logo/cucuto-logo.svg), light variant) on cream, the line "Give each contact a voice." and **cucuto.mauri.app**. Fine print: "Free and open source · Android 17 · Needs root and LSPosed · Not affiliated with WhatsApp or Meta". Spanish cut: "Dale voz a cada contacto." and "Gratis y de código abierto · Android 17 · Necesita root y LSPosed · Sin relación con WhatsApp ni Meta". | `assign.ogg` once at 53.2 s. VO 54.0–57.6 s. Silence to the end. |

**Poster frame.** The frame at 00:32.8 (the burst around the frog tile, snackbar not yet up) is the poster. Export it
as described in [Deliverables](#deliverables). Until the video exists, the site shows an illustrated, text-free
poster with the same idea ([`web/src/lib/assets/images/explainer-poster.svg`](../web/src/lib/assets/images/explainer-poster.svg)).

## Voice-over script

One calm, warm narrator per language. Not a hype voice. The Spanish cut uses Spain Spanish with "tú", in the voice
of the website's Spanish pages. Pronounce Cucuto "koo-KOO-toe" in English and as written in Spanish. Say the URL as
"cucuto dot mauri dot app" in English and "cucuto punto mauri punto app" in Spanish. Each line must fit its slot;
the Spanish lines are written short so both cuts share one timeline.

| # | Slot | English | Spanish |
| --- | --- | --- | --- |
| 1 | 03.0–04.9 | Same sound every time. Who was that? | Siempre el mismo sonido. ¿Quién era? |
| 2 | 06.2–08.9 | Cucuto gives each WhatsApp contact a voice. | Cucuto da voz a cada contacto de WhatsApp. |
| 3 | 09.3–13.6 | Set it up once on a rooted Android 17 phone with LSPosed. | Se configura una vez, en un Android 17 con root y LSPosed. |
| 4 | 15.4–19.8 | Import a sound pack. The free demo packs bring eight characters. | Importa un pack de sonidos. Los de demostración traen ocho personajes. |
| 5 | 21.3–25.6 | Everyone who messaged you shows up here. Let's give Ada a voice. | Aquí aparece quien te ha escrito. Vamos a darle voz a Ada. |
| 6a | 26.1–27.7 | Tap a character to hear it. | Toca un personaje para oírlo. |
| 6b | 30.9–32.5 | Tap it again, and it's Ada's. | Tócalo otra vez y es de Ada. |
| 7 | 37.4–40.0 | Next time Ada writes, you hear Bleep the frog. | La próxima vez que Ada escriba, oirás a la rana Bleep. |
| 8 | 40.3–43.0 | Simulate a message to hear exactly what's coming. | Simula un mensaje y oye justo lo que llegará. |
| 9 | 50.6–52.8 | Now you know who wrote, without looking. | Ahora sabes quién te escribe, sin mirar. |
| 10 | 54.0–57.6 | Cucuto. Free and open source, at cucuto.mauri.app. | Cucuto. Gratis y de código abierto, en cucuto.mauri.app. |

About 95 words in each language, roughly 2.4 words a second while the narrator speaks. The gaps are
on purpose: the characters do the talking.

Who records it is an open question (#194). The default: a human volunteer
voice for the final cuts, credited in the release notes if they agree. A synthetic voice is fine for drafts only.

## Sound design

Only two sources, both CC0:

- **Character clips** from the demo packs, unedited except for level: [`packs/chiptune-critters`](../packs/chiptune-critters)
  (frog `ribbit`, `croak`, `hop`; cat `mew`, `mrrp`, `purr`; bird; bee) and
  [`packs/kitchen-orchestra`](../packs/kitchen-orchestra) (kettle `whistle`, `bubble`, `reel-hook`; pot; glass;
  whisk). Use the files in `packs/*/*/clips/`, or record them from the phone so the device's speaker colors them.
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

Closed captions in WebVTT, one file per language: `explainer.en.vtt` and `explainer.es.vtt`. Both cuts share the
timeline, so the same two files serve both. Drafts that match this script are already in
[`web/static/video/`](../web/static/video); adjust the times to the final edit and keep the cue IDs.

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
- [ ] Text on screen (app UI, end card) has a contrast of at least 4.5:1, is at least 32 px tall at 1080p, and
      stays up long enough to read twice.
- [ ] Loudness −16 LUFS integrated, true peak −1 dBTP; the voice is clearly above everything else.
- [ ] Made-up people only: no real name, number, profile picture, chat or wallpaper photo anywhere, including the
      status bar, notification shade and keyboard suggestions. Check frame by frame around shots 1 and 9.
- [ ] Only CC0 demo-pack sounds and brand sounds in the mix; no WhatsApp tone, no music.
- [ ] Both cuts share the timeline, so both caption files line up with both cuts.

The site's side (in place now, ADR 0042): native controls, keyboard operable, never autoplays with sound, never
autoplays when the viewer prefers reduced motion or saves data, pauses when scrolled away, captions on by default
whenever the video plays muted or the page language differs from the audio, and the transcript below the player.

## Staging and privacy

- **Record on a clean profile.** Cucuto's Home lists real people from real WhatsApp notifications, so never record
  on a phone with real chats in Cucuto's data. Use a test phone, or clear Cucuto's data first (Settings › Apps ›
  Cucuto › Storage) and restore afterwards from a backup.
- **WhatsApp test accounts.** Shots 1 and 9 need real WhatsApp messages. Use test accounts only: the recording
  phone runs a second WhatsApp account, and a second phone sends as "Ada", "Bea" and "Leo" (#194). Save them in the
  recording phone's contacts under those names with no photo, so no number shows. Turn off the owner's own
  WhatsApp account's notifications during the shoot.
- **No real numbers.** If a number would ever show (an unsaved sender, a profile page), use the fictional range
  `+44 7700 900000`–`+44 7700 900999` that Ofcom reserves for drama, and prefer to avoid the shot altogether.
- **Status bar.** Turn on Android's demo mode (`adb shell settings put global sysui_demo_allowed 1`, then the
  `com.android.systemui.demo` broadcasts) for a fixed 12:00 clock, full battery and signal, and no notification icons.
- **Device.** Rooted Android 17 with LSPosed and the module enabled (System Framework scope), the demo packs from
  the latest `packs-v*` release, touch indicator on (Developer options › Show taps), animations at 1×, dark mode
  off for the English and Spanish cuts alike, app language set per cut (Settings › System › Languages › App
  languages › Cucuto).

## Producing the footage

Shots 3–8 are app footage. Record them with `adb shell screenrecord --bit-rate 20M --size 1080x2400` (or the
phone's own recorder at 60 fps) while a script or a steady hand drives the app. Shots 1 and 9 also need the
system's lock screen and notifications, so they are always recorded on the device. Shots 2 and 10 are motion
graphics built from `brand/` files.

### An automated draft (explored)

We looked for a way to make a draft video with no human editor. Findings:

1. **From the scripted screenshots (#41).** Once the screenshot pipeline captures one still per shot (Home with
   recent senders, the picker, the burst, contact detail…), a small Kotlin script (`*.main.kts`) can build an
   animatic: each still held for its slot from the shot list above, slow 3 % push-ins (`zoompan`), cross-fades
   (`xfade`), the clips and brand sounds placed at their times (`adelay` + `amix`), and the draft captions burned
   in for review (`subtitles`). Cheap (one script, no device, runs in CI) and good for checking timing and the
   script, but it is a slideshow: no taps, no springs, no burst. A timing animatic, not the public video.
2. **From Compose, rendered offline.** The app's screens already render under Robolectric with fakes (the UI tests
   do it). A test with `composeTestRule.mainClock.autoAdvance = false` can step the clock 16 ms at a time and
   capture each frame (`captureToImage()`, or Roborazzi's `captureRoboGif`/frame capture) while it performs the taps,
   then ffmpeg turns the PNG sequence into video. That gives the real springs, burst and door, pixel-exact and
   repeatable, with no device and no private data. It can't show system UI (lock screen, notifications, file
   picker), so shots 1, 4 (file picker part) and 9 still come from the device.
3. **On the device.** UI Automator drives the app while `screenrecord` films it. The most real, but it needs the
   rooted phone with the module enabled and WhatsApp test accounts (`needs-device`), and it is the flakiest.

**Decision: not built yet.** Route 1 waits for #41's stills, and route 2 means adding a frame-capture harness to
the app's test sources while several UI agents are changing those screens. Neither is cheap today, and a slideshow
would not look good on the Home page. The recommended path, tracked in #195: route 2
for shots 3–8 once the screens settle, the device for shots 1 and 9, the brand animations for 2 and 10, and route 1's
script to assemble everything with the sound and captions on the shot list's timeline.

## Deliverables

Web files go in `web/static/video/` (served at `/video/…`; the site's service worker never precaches them). The
site finds them at build time: the English cut makes the player appear, the Spanish cut is used on `/es/` when it
exists, and the English cut plays there with Spanish captions until then.

| File | What | Spec |
| --- | --- | --- |
| `explainer.webm` | English cut, modern browsers | AV1 (SVT-AV1, `-crf 34 -preset 4`, 10-bit `yuv420p10le`) + Opus 96 kb/s stereo, 1920 × 1080, 60 fps. Budget 10 MB. |
| `explainer.mp4` | English cut, everything else (Safari without AV1 hardware) | H.264 High 4.2 (`libx264 -crf 22 -preset slow -pix_fmt yuv420p`) + AAC-LC 128 kb/s, `-movflags +faststart`, 1920 × 1080, 60 fps. Budget 16 MB. |
| `explainer.es.webm`, `explainer.es.mp4` | Spanish cut | Same specs. |
| `explainer.en.vtt`, `explainer.es.vtt` | Captions | WebVTT, UTF-8, LF line endings, SDH (see [Captions](#captions)). |
| `web/src/lib/assets/images/explainer-poster.png` | Poster frame | The frame at 00:32.8 of the English cut, 1920 × 1080 PNG. The site encodes it to JPEG XL, AVIF and PNG at build time (`?picture`), so the poster is served as JPEG XL with fallbacks for browsers that can't decode it; replace the import of `explainer-poster.svg` in `ExplainerVideo.svelte` with it and delete the SVG. |

Encoding, from a lossless master (`master-en.mov`):

```sh
ffmpeg -i master-en.mov -c:v libsvtav1 -crf 34 -preset 4 -pix_fmt yuv420p10le -g 240 \
  -c:a libopus -b:a 96k explainer.webm
ffmpeg -i master-en.mov -c:v libx264 -profile:v high -level 4.2 -crf 22 -preset slow -pix_fmt yuv420p -g 120 \
  -c:a aac -b:a 128k -movflags +faststart explainer.mp4
ffmpeg -ss 32.8 -i master-en.mov -frames:v 1 explainer-poster.png
```

Kept outside the repository (large; attach them to a GitHub release or keep them with the project files): the
lossless masters (ProRes 422 HQ or FFV1), the stems (voice, clips, UI sounds), the edit project and the
photosensitivity report.

Optional, not used by the site: a vertical 1080 × 1920 cut for social posts, same timeline, phone filling the frame,
captions burned in.
