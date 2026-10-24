# 0012. Haptic effects, fallbacks and auto haptics

- Status: accepted
- Date: 2026-10-23
- Issues: #30, #31

## Context

Characters vibrate with their clips ([brief](../project-brief.md#haptics), [pack format](../pack-format.md#haptics)).
A pack haptic is `auto` (follow the voice), `custom` (a timeline of presets and basic envelopes) or `none`. The routing
table already carries haptics as `Haptic` (`Keep`, `Off`, `Auto`, `Custom`) in a compact text syntax, with an
optional envelope per clip ([routing table](../routing-table.md), [ADR 0009](0009-data-layer-and-routing-table.md)).
The module turns them into a `VibrationEffect` inside `system_server`, with no Context of its own, no DI and a 5 ms
budget.

What the platform offers, from the API reference and
[Create custom haptic effects](https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects):

- `VibrationEffect.Builder` (added in **API 37.2**, so `Build.VERSION.SDK_INT_FULL >= CINNAMON_BUN_2`) sequences
  presets and envelopes on a timeline and falls back automatically, per element, when the hardware lacks one.
  `build()` throws `IllegalArgumentException` for elements that would overlap (envelopes by their length, presets by
  1 ms) and elements must be added in start order.
- The owner's phone runs **API 37.1** (`ro.build.version.sdk_full`, `CP3A.261005.005`). The Builder classes are
  already in its framework, but as **blocked hidden API**: the instrumented test logs `hiddenapi: … api=blocked …
  denied` for `VibrationEffect$Builder` in an app process. `system_server` is exempt from hidden API checks, which
  is why the M0 spike could play a Builder effect from the module.
- `BasicEnvelopeBuilder` (API 36) must end at intensity 0 (`IllegalStateException`). Devices with envelope support
  report limits in `VibratorEnvelopeEffectInfo`: at least 16 control points, segments down to 20 ms and up to 1 s.
  The reference recommends at most 16 points for consistency across devices.
- `Composition` has no fallback: one unsupported primitive and nothing plays.
- `WaveformEnvelopeBuilder` has no fallback and depends on the device's frequency profile; the format excludes it.

## Decision

**One model, no new format.** `:core:haptics` uses `:core:routing`'s `Haptic` model and syntax. The only change to
`:core:routing` is two public functions, `RoutingCodec.encodeHaptic` and `decodeHaptic`, so a single haptic can be
stored outside a table. The routing table format is unchanged.

**Plan in pure Kotlin, build with Android.** `HapticPlanner` (pure, JVM-tested) turns a `Haptic.Custom` and the
device's `HapticCapabilities` (plain data, read once with `HapticEffects.capabilities(vibrator)`) into a plan.
`HapticEffects.create(haptic, capabilities)` maps the plan onto the platform (in the app process,
`PublicHapticEffects.create` and `PublicHapticEffects.capabilities`, [ADR 0026](0026-app-haptics-public-apis-only.md)). It needs no Context and never throws:
it returns null when nothing is playable, and the caller keeps the original vibration. Steps:

1. **Normalize** whatever the source (pack, override, analysis): sort by start; move an event that would overlap the
   previous one to the earliest free slot; clamp values to 0..1; close envelopes with a ramp to zero; end the
   timeline by 5,000 ms (the pack limit).
2. **Fit envelopes** when the device supports them: stretch segments shorter than the minimum, so short peaks stay
   perceptible; truncate to the maximum duration; simplify to the point budget with Ramer–Douglas–Peucker; split
   segments longer than the maximum.
3. **Choose the API.** With the Builder, use it (presets and basic envelopes 1:1). The Builder counts as
   available on 37.2 and later, or when a reflective probe of its constructor and methods succeeds
   (`HapticEffects.builderAvailable`). On the owner's 37.1 phone, that means the module in `system_server` uses
   it while the app (previews) uses the fallbacks. *Amended by [ADR 0026](0026-app-haptics-public-apis-only.md):*
   the app never probes or references the Builder. It uses `PublicHapticEffects`, and only the module uses
   `HapticEffects`. Mixed timelines on envelope hardware become one envelope with preset bumps, and a lone
   unsupported preset becomes `createPredefined`. ADR 0026 lists how previews differ from the module. Without it,
   use one `BasicEnvelopeBuilder` effect for envelope-only timelines (envelopes joined, gaps held at zero), a
   `Composition` for preset-only timelines whose primitives are all supported (delays from
   `getPrimitiveDurations`), and otherwise `createWaveform` (envelopes sampled every 20 ms, presets as 10–20 ms
   pulses, on/off without amplitude control). If the platform still rejects a plan, retry once as a waveform.

`forClip` resolves `Auto` to the clip's own envelope, or `Keep` when the clip has none.

**Auto haptics ("haptics follow the voice").** At import, for each clip:

- `ClipPcmDecoder` decodes the first 2 s to mono float PCM with `MediaExtractor` and `MediaCodec`, the same stack
  the importer verifies clips with.
- `AutoHapticAnalyzer` (pure) measures the RMS and zero-crossing rate of each 20 ms frame. **Intensity** is loudness
  in dB relative to the loudest frame, mapped linearly from −40 dB (0) to 0 dB (1); below −60 dBFS is silence.
  Basic envelope intensity is perceptual and so is the dB scale. **Sharpness** uses the zero-crossing rate as a
  brightness proxy: the dominant frequency is about `zcr × rate / 2`, mapped on a log scale from 100 Hz (0) to 4 kHz
  (1) and smoothed over three frames. It is cheaper and steadier than an FFT spectral centroid on short voiced clips.
- The envelope starts at the first sound, ends by 2 s with a ramp to zero, and is simplified to
  `min(device maxSize, 16)` points, no closer than the device's minimum segment (at least 20 ms). Wobbles under 3 %
  spend no points. A silent clip has no envelope, so `auto` keeps WhatsApp's vibration for it.
- `AutoHaptics` runs this on a background dispatcher. `analyzePack` writes the results next to the pack.

**Storage.** One file per installed pack version, `files/packs/<pack id>/<version>/.cucuto/auto-haptics.txt`. Pack
paths never start with a dot, so the file cannot clash with pack content. It is renamed, kept and deleted together
with its version folder ([ADR 0008](0008-pack-importer.md)). The file is tab-separated text: a header
`cucuto-auto-haptics⇥1⇥<analyzerVersion>⇥<maxControlPoints>⇥<minControlPointMs>⇥<count>`, then
`<clip path>⇥<c… haptic or ->` lines in the routing syntax, so the publisher copies envelopes into `Clip.haptic`
unchanged. The analyser version and the budget let the app redo stale analyses (`AutoHaptics.isStale`), for example
after a better analyser ships or after a restore on a phone with a smaller budget.

## Consequences

- The module (#15) needs only `HapticCapabilities` (once) and `HapticEffects.create`, which can be cached per haptic.
  Wiring is #96. The importer and catalog integration (analyse at import, read the file into
  `CatalogClip.autoHaptic`) is #97, done in [ADR 0020](0020-auto-haptics-at-import.md).
- A clip's auto envelope takes about 100–200 characters in the routing table. Clip haptics are not deduplicated
  across voices, so a heavy user with many distinct voices of 12 clips could get close to the 256 Ki-character limit.
  #98 tracks measuring this and adding a haptic dictionary if needed. ADR 0020 adds the dictionary and a compact
  form of about 55 characters per envelope.
- Behavior with the Builder relies on the framework's fallbacks. Those can only be checked on hardware. The
  instrumented test builds every path on a device but does not judge how the vibration feels.
