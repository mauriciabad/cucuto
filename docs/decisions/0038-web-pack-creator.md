# 0038. The website's pack creator: shared Kotlin for layout and clips, browser APIs for media

- Status: accepted
- Date: 2026-10-24
- Issues: #48 (Create page), #47 (in-app creator), #60 and #117 (Kotlin in the browser), #75 (clip length)

## Context

The brief asks for a Create page on the website, installable and offline, that makes the same `*.soundpack.zip` files
as the app's creator (#47, built at the same time), with all processing on the device. The owner chose to reuse
Kotlin in the browser (#60); `shared/pack-spec` already runs there as a Kotlin/JS module for validation
([ADR 0022](0022-pack-spec-in-the-browser.md)). The Kotlin/Wasm spike (#117) measured both browser targets and found
no reason to switch. Clips may last up to 30 s, with a gentle hint above 5 s (#75, [ADR 0019](0019-long-notification-clips.md)).

What the browsers offer (MDN and `@mdn/browser-compat-data` 8.1.7, read 2026-10-24):

- [`AudioEncoder`](https://developer.mozilla.org/en-US/docs/Web/API/AudioEncoder) (WebCodecs): Chrome 94, Firefox 130
  (desktop), Safari 26, secure contexts only. It returns raw Opus packets; the container is the page's job. Codecs
  vary, so [`isConfigSupported()`](https://developer.mozilla.org/en-US/docs/Web/API/AudioEncoder/isConfigSupported_static)
  comes first.
- `OfflineAudioContext.decodeAudioData` decodes whatever the browser plays and resamples to the context rate;
  `MediaRecorder` records the microphone.
- `canvas.toBlob`: WebP in Chrome and Firefox, not Safari; no browser encodes JPEG XL today (Chrome 155 decodes it).
  A browser that cannot write the asked type returns PNG, so the blob's own type says what it wrote.

## Decision

**One Kotlin implementation of what makes a pack "the same".** The creators' conventions live in
`shared/pack-spec/src/commonMain/.../authoring/` and both creators call them:

- `Authoring` (written for #47): IDs from names (`local.<author>.<name>-<suffix>`), slugs, the license choices (new
  packs start as "Personal use only", because people often use clips and pictures they did not make; CC0 and CC BY
  are one tap away), version bumps, and the audio and image targets (Opus 48 kHz mono at 96 kb/s, −16 LUFS, −1.5 dBTP, 512 px sprites).
- `PackAssembler` (written for #47): the canonical layout (`characters/<id>/sprite.<ext>`,
  `characters/<id>/clips/<label slug>.<ext>`) and `pack.json` exactly as both creators write it.
- `ClipDsp` (new here, the pure-Kotlin pipeline of #47's `core/audio/dsp` moved to common code): cut, trim
  silence, normalize loudness under the true-peak ceiling, edge fades.
- `ClipCuts.findHook` (#174) picks a song's hook when a file is longer than a clip.

`src/jsMain/.../AuthoringJs.kt` exports them to the browser (`assemblePack` takes the draft as JSON;
`processClip` takes a `Float32Array`, which Kotlin/JS sees as `FloatArray` without a copy), next to the validator.

**Browser work stays in TypeScript**, because it is platform API glue, just as the app keeps MediaCodec and Bitmap
code in Android modules: decoding (Web Audio), recording (MediaRecorder), the Ogg Opus container for WebCodecs
packets (RFC 7845: OpusHead, OpusTags, the last page's granule position trims the encoder padding, so the file lasts
exactly as long as the clip), the WAV writer, canvas cropping (the same math as the app's `SpriteCrop`) and zipping
(`fflate`: `pack.json` first, then the assembler's order, deflate level 6 and a fixed 1980-01-02 timestamp, as the
app's `PackWriter`).

**Media choices**, all pack formats ([pack format](../pack-format.md#media)), none forced:

- Clips: Ogg Opus through WebCodecs where `isConfigSupported` says yes. Otherwise **16-bit WAV**, with a notice. The
  brief's WebAssembly encoder fallback would need `'wasm-unsafe-eval'` in the site's Content Security Policy and a
  large download for the minority of browsers without an Opus encoder; WAV is bigger but every importer plays it.
  Question for the owner: #QUESTION.
- Sprites: JPEG XL when the browser can encode it (none today, so the code is ready for when one does), otherwise
  WebP like the app (quality 1 for pictures with transparency, lossless in Chromium; quality 0.9 for photos), and
  PNG or JPEG where the browser cannot write WebP (Safari).
- Haptics stay `auto` (the format's default): browsers cannot play rich haptics, and the page says to make custom
  patterns in the app.

**Checks before download.** The page zips the pack, runs `validateArchive` on those exact bytes and only then offers
the file, like the app's `PackWriter`. "Add to app": the app accepts `application/zip` from the share sheet, so the
page offers `navigator.share({ files })` where `navigator.canShare` accepts the file; elsewhere it explains how to
open the download with the app.

**Offline and private.** The page is prerendered and its chunks (the validator included) are precached by the
existing service worker; an e2e test reloads it offline. No `blob:` or `wasm-unsafe-eval` was added to the CSP:
previews play through Web Audio and pictures show as `data:` URLs. Nothing is uploaded; the privacy page says the
microphone is used only while recording.

## Consequences

- Both creators produce the same layout, names, `pack.json`, loudness and zip structure; only the encoded bytes can
  differ (different Opus and WebP encoders). `web/tests/creator.test.ts` re-lays out every valid golden fixture with
  the shared assembler and checks the result with the validator; `web/tests/create.e2e.ts` makes a pack in Chromium
  (Opus and WAV paths) and validates the download with the same module. `ClipDspTest` covers the pipeline on the JVM.
- The app's creator (#47) should call `authoring.ClipDsp` instead of keeping its own copy of the DSP code, so the
  two can never drift.
- The browser module grows by the authoring code (a few kB; kotlinx.serialization was already in it).
- Not in this first version: covers, bundles, ringtones, a custom haptic editor, saving drafts across reloads,
  editing an existing pack, and the "host your own repository" helper (#FOLLOWUP).
