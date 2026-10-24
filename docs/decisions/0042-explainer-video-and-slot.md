# 0042. Explainer video: brief, files and the Home player

- Status: accepted; amended by the owner's answer on #194 (illustrated UI, AI voice, English only, see
  [Owner update](#owner-update-2026-10-24-194-illustrated-ai-voiced-english-only))
- Date: 2026-10-24
- Issues: #42 (this work), #58 (brief after M3, demo packs only), #38/#110 (Home slot), #41 (scripted screenshots),
  #194 (owner question: defaults below), #195 (automated draft, follow-up)

## Context

The project brief wants an explainer video on Home: made by a separate agent from demo packs only, served from
`web/static/video/explainer.webm` and `.mp4` with a `poster.jxl` and WebVTT captions, lazy-loaded, and never
autoplaying with sound. #110 left a CSS placeholder. The site is in English and Spanish (#70), the app too (#183).
It promises no third-party requests (CSP `media-src 'self'`), Lighthouse 95+, and a service worker that precaches
every static file.

Constraints found while building the slot:

- `<video poster>` takes one URL. A JPEG XL poster stays blank in browsers that can't decode JPEG XL (Firefox
  without the preference), while the site already serves every raster as `<picture>` with JPEG XL first and
  AVIF/PNG fallbacks (`?picture`, #40).
- Browsers allow autoplay only when muted, or after a user gesture
  ([Chrome autoplay policy](https://developer.chrome.com/blog/autoplay),
  [MDN autoplay guide](https://developer.mozilla.org/en-US/docs/Web/Media/Guides/Autoplay)). Moving content that
  starts on its own and lasts more than 5 s needs a way to pause it
  ([WCAG 2.2.2](https://www.w3.org/WAI/WCAG22/Understanding/pause-stop-hide)).
- The service worker's `cache.addAll` of every static file would download the whole video on install, and caching a
  video's range responses is fragile ([SvelteKit service workers](https://svelte.dev/docs/kit/service-workers)).

## Decision

**Brief.** [`docs/explainer-video.md`](../explainer-video.md): 58 s, ten shots on the app's real flows, a
voice-over in English and Spanish, sound from the CC0 demo packs and brand sounds only, SDH captions, an
accessibility checklist, staging rules (only the made-up Ada, Bea and Leo; Ofcom drama numbers if a number can't be
avoided), and the deliverables. Both language cuts share **one timeline**, so one pair of caption files
(`explainer.en.vtt`, `explainer.es.vtt`) fits both cuts. Draft captions that match the script are committed in
`web/static/video/`, with a test that checks them against the brief's rules.

**Files.** `web/static/video/explainer.{webm,mp4}` (English cut), `explainer.es.{webm,mp4}` (Spanish cut,
optional), `explainer.{en,es}.vtt`. WebM is AV1 + Opus and MP4 is H.264 + AAC, each `<source>` with its codecs
string, so browsers that can't decode AV1 go straight to the MP4. One resolution (1080p) for now.

**Poster.** Not a separate `poster.jxl`, and not the `poster` attribute: a `?picture` import
(`web/src/lib/assets/images/explainer-poster.*`), rendered as a lazy `<picture>` behind the player (JPEG XL, then
AVIF, then PNG). The `<video>` is transparent until its first frame, so the poster shows through while it loads.
Until the real frame exists, an illustrated, text-free SVG poster (demo sprites and the brand mark) stands in, so it
works in both languages.

**Player** (`web/src/lib/components/ExplainerVideo.svelte`):

- Availability is decided at build time (`src/lib/server/explainer-video.ts`): the English cut makes the player
  appear; `/es/` uses the Spanish cut when it exists and otherwise the English one.
- No video: the poster with a "coming soon" card (over the poster's calm corner on wide screens, below it on narrow
  ones).
- Lazy: no `<video>` element exists until it plays, so nothing but the lazy poster is downloaded.
- Scrolled at least half into view, it plays **muted** with captions on. It doesn't when the viewer prefers reduced
  motion (`MediaQuery`, which assumes reduced motion until hydration) or has Data Saver on (`navigator.connection.saveData`).
  A muted preview pauses when scrolled away, resumes when it comes back, and stops if reduced motion is turned on.
- Otherwise a play button starts it **with sound** (a user gesture) and moves focus to the player.
- Native controls (keyboard, captions menu, full screen, pause for WCAG 2.2.2). Captions start on when the video is
  muted or the page language differs from the voice-over's.
- A transcript (`<details>`) built from the page language's captions at build time.

**Service worker.** `/video/` is neither precached nor handled by the fetch handler; the browser streams it
directly.

**Automated draft: explored, not built.** The three routes and why none is cheap today are in the brief. The
recommended pipeline (Compose frames rendered offline under Robolectric, device footage for the system UI shots, an
ffmpeg assembly script) is #195, built in ADR 0060.

The defaults the owner may change (voice, one or two cuts, muted autoplay, WhatsApp test accounts, the poster file)
are in #194.

## Consequences

- Shipping the video means adding files and rebuilding: the player, captions and transcript appear on their own.
  The e2e tests switch from the "coming soon" checks to the player checks when the files exist.
- The project brief's `poster.jxl` becomes a `?picture` source. Every browser gets a poster it can decode, and JPEG
  XL is still served first.
- Videos in the repository grow its history (about 25 MB per cut). Replace them only with final versions; masters
  and stems stay outside the repository.
- Muted autoplay costs bandwidth for visitors who scroll past. The 50 % visibility threshold, the Data Saver check and
  pausing offscreen limit that.
- The captions double as the transcript, so they must stay complete (every sound, not only the speech).

## Owner update 2026-10-24 (#194): illustrated, AI-voiced, English only

> dont involve humans, AI voice.
> Dont use the real app ui not phone recordings, you can make a simplified version for the video.
> Only english video

> just a note, the point of not using real screenshots is to make the video evergreen, so if i update the ui the
> video doesnt get outdated.
> would be nice that the video project files were saved so if i want to add a new feature to the video it is
> doable, but this is NOT a priority, just a optional nice to have.

What changes in the decision above:

- **Brief:** ~~ten shots on the app's real flows, a voice-over in English and Spanish~~. The ten shots show the
  app's real flows as a **simplified, illustrated UI** drawn for the video, never real screens or phone recordings.
  The video stays true when the app's UI changes. The voice-over is **AI-generated, in English**, and no human takes
  part.
- **Files:** **one English cut** (`explainer.{webm,mp4}`). No Spanish cut is planned. `/es/` plays the English cut
  with the Spanish captions, which the player already does when no Spanish cut exists. Both caption files stay.
  The player keeps its optional support for `explainer.es.*`; it costs nothing.
- **Project files:** the video is kept as source in the repository (scenes, timeline, voice script and settings,
  sound cues) so a feature can be added later. This is a nice-to-have, not a priority. Renders and masters stay out.
- **Defaults that stand** (the owner didn't change them): muted autoplay with captions when in view, never with
  reduced motion or Data Saver (item 3); the `?picture` poster (item 5). Item 4 (WhatsApp test accounts) is moot:
  nothing is filmed.
- The draft pipeline (ADR 0060) renders the real screens, so it is reworked in #271. Done:
  [ADR 0070](0070-illustrated-explainer-video-project.md) (illustrated scenes in `android/video`, a Kokoro AI voice,
  English only). ~~The site keeps "coming soon" until the owner has watched the cut and says to publish it (#302).~~
  Published 2026-10-24 (#302, owner: "the video is ready to be published it is a moment"): the English cut and
  its poster are committed as planned here, see ADR 0070's
  [Published](0070-illustrated-explainer-video-project.md#published-2026-10-24-302).
