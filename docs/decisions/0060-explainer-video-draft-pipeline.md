# 0060. Explainer video: an automated draft from the app's real screens

- Status: accepted, **to be reworked**: the owner's answer on #194 rules out the app's real screens (see
  [Owner update](#owner-update-2026-10-24-194-no-real-screens)); rework in #271
- Date: 2026-10-24
- Issues: #195 (this work), #42 and ADR 0042 (brief and the website's slot), #194 (voice and device footage), #202 and
  ADR 0032 (the Roborazzi screenshot harness this builds on)

## Context

The explainer brief ([`docs/explainer-video.md`](../explainer-video.md)) has a 58 s shot list with ten shots, made-up
chats (Ada, Bea and Leo), an English and a Spanish cut, and sound from the CC0 demo packs and brand sounds only. #42
explored three ways to get a draft with no human editor and recommended rendering the app's screens offline. The app
shell is now stable (#186, #233, #238), and the screenshot tests already render every main screen under Robolectric
Native Graphics with the demo packs and fake system bars.

Constraints found while building it:

- Compose's test clock (`mainClock`) produces a frame every 16 ms; a 60 fps video frame lasts 16.7 ms. Stepping the
  clock to the nearest 16 ms frame would skip one app frame in 25, a visible hitch in the springs.
- Beat-driven motion (the cuckoo door, the burst) measures time from `FeedbackBeat.startNanos`, stamped with
  `System.nanoTime()`, while frames under test carry the test clock's time (ms × 10⁶). Out of the box the door just
  snaps open in tests.
- A still Compose screen is not drawn again under Robolectric; capturing it can return a blank first frame.
- Shots 1 and 9 need the system lock screen and real WhatsApp messages (#194), which Robolectric can't show.
- Large videos must not be committed (ADR 0042: only final cuts go in `web/static/video/`).

## Decision

**Two steps, both reproducible, no device.**

1. **Frames on the JVM.** `./gradlew :app:renderExplainerFrames` runs the tests in
   `android/app/src/testDebug/kotlin/app/mauri/cucuto/video/` (skipped in every other build). A `SceneRecorder`
   steps the test clock frame by frame, runs the scene's actions at their times on the brief's timeline and captures
   every frame. Holds cost nothing: each distinct frame is written once and `frames.txt` lists the PNG of every video
   frame. Each scene also writes its sound cues and vibration times.
   - Shots 4–8 are **one take in the real app shell** (`CucutoShellLayout`, Navigation 3 transitions, shared
     elements, the navigation bar) driving the same stateless screens as the screenshot tests: Home's journey,
     Discover finding the demo bundle, the import review, the picker, the first-voice card and contact detail. Taps
     are real touch input (`performTouchInput`) with a drawn "Show taps" indicator, and each callback changes the
     state as the view model would. Shot 3 is the real onboarding. Shot 2 is the app's own `CuckooDoor` (the
     brand's door motion) full frame with the wordmark; shot 10 is the end card in the cut's language.
   - **One clock frame (16 ms) per video frame**: the app plays at 96 % speed, which no one can see, and no frame is
     skipped or doubled. Actions, sounds and captions stay on the video's timeline.
   - `FeedbackController` gains an internal `nanoTime` clock (default `System::nanoTime`, unchanged in the app), so
     the video's beats use the test clock and the door, burst and avatar morph play frame by frame.
   - The recorder invalidates the window before each capture, so still frames are never blank.
   - **Shots 1 and 9 are stand-ins** drawn in Compose (a plain cream lock screen with generic notifications, no
     WhatsApp branding) until device footage exists (#194). Ripples around the phone outline show the vibrations.
2. **Assembly with ffmpeg.** `kotlin tools/video/explainer.main.kts` (or `./gradlew :app:explainerDraft`, both steps)
   checks that the scenes tile 0–58 s, lays the phone frames (rounded corners by an alpha mask) on the stage (cream,
   sound rings, phone outline, ripples), mixes the cues (`adelay` + `amix`: the plain ping is `tap.ogg` an octave
   down, clips at -3 dB, UI sounds 6 dB under, a -1 dBFS limiter) into a lossless master (FFV1 + FLAC), and encodes
   the brief's deliverables: `explainer.webm` (SVT-AV1 + Opus), `explainer.mp4` (H.264 + AAC), the Spanish
   `explainer.es.*`, `explainer-poster.png` (00:32.8) and `explainer-review.<lang>.mp4` with both caption files as
   selectable tracks. The web files carry no caption track (the site serves the VTT files). **No voice-over** (#194):
   `--voice-over-en FILE` mixes one in and normalizes to -16 LUFS / -1 dBTP; without it the clips keep their levels.
3. **CI**: `.github/workflows/explainer-video.yml`, manual (`workflow_dispatch`, cuts and AV1 preset as inputs),
   uploads the draft as an artifact. It is not part of `ci.yml` or `ci-ok`. Locally, both cuts take about 5 min to
   render and 5 min to encode at the brief's preset 4 (M-series Mac).

**The website keeps "coming soon".** The draft follows the shot list closely and its app shots are the real screens
and motion, but it has no voice-over, shots 1 and 9 are stand-ins rather than device footage, and the phone fills
only the middle of the frame (app text is about 15 px tall, under the brief's 32 px). That is not the public video,
so nothing goes in `web/static/video/` (ADR 0042: only final cuts).

## Consequences

- The draft can be rebuilt after any UI change, in both languages, with no device and no private data. Reviewers
  download it from the workflow's artifact.
- The video's taps and states are scripted in `ExplainerPhoneTest`: a renamed string or a moved button fails the
  render, as a UI test would. Screens it uses but the screenshot tests don't (none today) would need fakes there.
- The edit holds the picker after assigning until the door's time on the brief's timeline (the real picker closes
  0.9 s after the assignment), and skips the module and reboot pages of the onboarding (the brief's "quick wipe").
- The final video still needs the voice-over and shots 1 and 9 from the device; when they exist, the script takes the
  voice files, and device footage can replace the two stand-in scenes (same folder layout).
- No push-ins: the camera is static, within the brief's "5 % at most".

## Owner update 2026-10-24 (#194): no real screens

> Dont use the real app ui not phone recordings, you can make a simplified version for the video.

> just a note, the point of not using real screenshots is to make the video evergreen, so if i update the ui the
> video doesnt get outdated.

This contradicts the core of the decision above: shots 3–8 render the app's real screens (`CucutoShellLayout`, the
screenshot tests' stateless screens), and a renamed string breaks the render. The owner also wants an AI voice
(no human), English only, and the project files kept so features can be added later (a nice-to-have).

**What stays:** the two steps (frames on the JVM, assembly with ffmpeg), `SceneRecorder` and its frame-stepped
clock, the cue and vibration files, the stage, the mix, the encodes and the manual workflow.

**What changes (#271, not built yet):**

- The phone shows **illustrated scenes drawn for the video**. They don't depend on the app's screens, view models
  or strings, only on brand tokens and the demo pack art. An app UI change never changes or breaks the video.
  `ExplainerPhoneTest` stops driving `CucutoShellLayout`.
- Shots 1 and 9 stay drawn and become final, not stand-ins. No device footage is ever needed.
- An **English AI voice-over** is generated from the brief's script by an open text-to-speech model, with the model,
  voice and settings recorded so it can be regenerated. `--voice-over-en` takes its output.
- **English only:** `-Pexplainer.languages` and the `explainer.es.*` outputs go away. Both caption files stay.
- The scenes, timeline, voice text and cues are the editable project files, all in the repository.

Until #271 lands, the current draft is a timing reference only, and the website keeps "coming soon".
