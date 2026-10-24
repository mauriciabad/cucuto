# The explainer video as source

The 58-second explainer on the website's Home page (brief: [`docs/explainer-video.md`](../../docs/explainer-video.md),
decision: [ADR 0070](../../docs/decisions/0070-illustrated-explainer-video-project.md)). Everything you see is drawn
here in Compose, from [`brand/`](../../brand) and the CC0 demo packs in [`packs/`](../../packs): a simplified,
illustrated phone, not the app's real screens. This module never depends on `:app`, so changing the app's UI never
changes or breaks the video (owner, #194: "the point of not using real screenshots is to make the video evergreen").

## Build it

```sh
cd android && ./gradlew :video:explainerVideo      # frames, voice, mix and encodes → android/video/build/explainer/
```

Or step by step, from the repository root:

```sh
(cd android && ./gradlew :video:renderExplainerFrames)   # every frame, 1920 × 1080 at 60 fps (about 5 min)
kotlin tools/video/voice.main.kts                       # the AI voice-over (downloads the model once, ~370 MB)
kotlin tools/video/explainer.main.kts                   # mix and encode: explainer.webm, .mp4, poster, review cut
```

Needs JDK 21, `kotlin`, `ffmpeg` (with libsvtav1, libx264, libopus), `tar` and `bzip2`. The manual
`explainer-video` workflow (Actions tab) does the same and uploads the result as an artifact. Nothing it makes is
committed.

To look at a few frames while you work, render stills (written to `android/video/build/explainer/frames/`):

```sh
cd android && ./gradlew :video:renderExplainerFrames -Pexplainer.stills=6.9,32.8
```

## Where things are

| File | What |
| --- | --- |
| [`Explainer.kt`](src/main/kotlin/app/mauri/cucuto/video/Explainer.kt) | **The timeline**: every shot with its length, picture, sounds, vibrations, captions and voice lines. |
| [`scenes/`](src/main/kotlin/app/mauri/cucuto/video/scenes) | One file per screen of the illustrated phone (lock screen, setup, packs, chats, picker…) and the full-frame shots (door, end card). `Ui.kt` has the shared parts. |
| [`Stage.kt`](src/main/kotlin/app/mauri/cucuto/video/Stage.kt) | The stage: cream background, the phone, transitions, the characters popping out beside it, vibration ripples, the touch indicator. |
| [`Art.kt`](src/main/kotlin/app/mauri/cucuto/video/Art.kt) | Brand art drawn from its geometry: the cuckoo house and its door motion, cookie avatars, the assign burst, sound arcs. |
| [`Motion.kt`](src/main/kotlin/app/mauri/cucuto/video/Motion.kt) | Time helpers: the brand's springs, fades, taps, squash and stretch. |
| [`Cast.kt`](src/main/kotlin/app/mauri/cucuto/video/Cast.kt) | The demo characters and the made-up people (Ada, Bea, Leo). |
| [`tools/video/voice.main.kts`](../../tools/video/voice.main.kts), [`voice-lexicon.txt`](../../tools/video/voice-lexicon.txt) | The text-to-speech step: engine, model, voice and pronunciations. |
| [`tools/video/explainer.main.kts`](../../tools/video/explainer.main.kts) | The mix and the encodes. |

Every picture is a pure function of time: a scene reads `t` (seconds since its shot started) and computes where
things are with the helpers in `Motion.kt`. There is no animation state, so any single frame can be rendered on its
own and always looks the same.

## Add a scene for a new feature

1. **Draw the screen.** Add a file in `scenes/` with a `@Composable internal fun ShotScope.MyFeatureScreen(...)`.
   Start from `Screen { ScreenTitle("…") … }` and the parts in `Ui.kt`. Keep it an idea of the feature, not a copy of
   the app's layout or wording: few words, big (text at least 16 sp, which is 32 px in the video). Animate with
   `spring(at)`, `fade(at)` and `Modifier.tapped(this, at)` for a tap. Use only `brand/` and `packs/` art.
2. **Add the shot** to the list in `Explainer.kt`, where it belongs in the story:

   ```kotlin
   Shot(
       id = "my-feature",
       duration = 5.0,
       frame = ShotFrame.Phone,
       captions = listOf(
           voice("s11-vo", 0.3, 4.6, "One short line that\nsays what it does.", "Una frase corta que\ndice lo que hace."),
       ),
       cues = listOf(Cue(2.0, Cast.cat.clip("mew"), Mix.CLIP_DB, pop = Cast.cat)),
   ) { MyFeatureScreen(tapAt = 1.8) }
   ```

   Times are seconds from the shot's start, so everything after it moves on its own. Voice lines are captions with a
   spoken text (English, in the video's voice) and a Spanish caption. Name every sound in a sound caption
   (`sound(…)`), because the captions are also the transcript. The whole video must stay under 60 s, so shorten or
   drop something else.
3. **Update the captions** the website serves: `cd android && ./gradlew :video:writeExplainerCaptions`
   (the unit tests fail until the committed files match the timeline).
4. **Check it**: `./gradlew :video:testDebugUnitTest`, then render stills of the new shot, then build the video
   (the voice step fails if a line doesn't fit its slot).
5. **Update the brief** ([`docs/explainer-video.md`](../../docs/explainer-video.md): shot list and script) in the same
   pull request.

If a word comes out wrong in the voice, add it to `tools/video/voice-lexicon.txt` (one word, then its phonemes, as
in Kokoro's `lexicon-us-en.txt`).
