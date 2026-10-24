# 0026. App-process haptics use public APIs only

- Status: accepted
- Date: 2026-10-24
- Issue: #139
- Amends: [ADR 0012](0012-haptics-effects-and-auto-haptics.md) (step 3, "Choose the API")

## Context

`VibrationEffect.Builder`, `Envelope` and `Preset` are public from **API 37.2**. Comparing the `android-37.0` and
`android-37.2` platform jars, 37.2 adds these vibration APIs: the classes `VibrationEffect.Builder`, `Envelope`,
`Event`, `Preset` and `VibrationParseException`, plus `VibrationEffect.getEvents`, `VibrationEffect.parseFromXml`,
`Vibrator.getPresetDuration` and `Vibrator.isPresetSupported`. The owner's phone runs **37.1**. It already ships
those classes, but as blocked hidden API for apps. `system_server` is exempt, so the module plays Builder effects
there (#105).

`:core:haptics` decided Builder support per process with a reflective probe (`HapticEffects.builderAvailable`). It
ran inside `Vibrator.hapticCapabilities()`, which the app also called: the picker preview (`MediaVoicePreview`) and
the auto-haptics runner (`AutoHapticsRunner`). In the app process, each probed member logged a `hiddenapi: … api=blocked
… denied` line (`<init>`, `addPreset`, `addEnvelope`, `build`, `Preset.create`, `Envelope.create`). The probe then
failed, so previews took the fallbacks. Those were coarser than they needed to be: a timeline that mixed presets
and envelopes always became an on/off-style waveform.

[Create custom haptic effects](https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects) gives
the public tools and their checks:

- `Composition` primitives, after `arePrimitivesSupported` (no fallback: one unsupported primitive and nothing
  plays);
- `BasicEnvelopeBuilder` (API 36), after `areEnvelopeEffectsSupported` ("Android doesn't provide fallbacks for
  envelope effects");
- `createWaveform` with amplitudes, after `hasAmplitudeControl` (otherwise non-zero amplitudes play at 100 %);
- `createPredefined`, which falls back to a generic pattern on its own.

The Feedback engine (#112, `UiHapticPlayer`) already used only `Composition` primitives (with
`DELAY_TYPE_RELATIVE_START_OFFSET`, API 36) and Compose's predefined types. The Diagnostics self-test plays no
in-app haptic: its click-tick runs in the module.

## Decision

**Two entry points, chosen per process up front.** There is no try/catch around blocked calls.

- `PublicHapticEffects` (app process): `capabilities(vibrator)` reads only public vibrator APIs and always reports
  `builderSupported = false`. `create(haptic, capabilities)` ignores `builderSupported`, plans and builds with
  public APIs only. The picker preview, the auto-haptics runner and the instrumented test use it.
- `HapticEffects` (module, `system_server`): `capabilities(vibrator)` adds `builderAvailable`, the probe.
  `create` uses the Builder for `Timeline` plans and the same public builders for everything else.
- `BuilderEffects` (internal) is the only class that names the Builder classes or probes them. Only `HapticEffects`
  references it. `PublicEffects` (internal) holds the public builders and the shared create-and-retry logic.
- The public `Vibrator.hapticCapabilities()` extension is gone. Every caller must pick its process's entry point.

**Closer public fallbacks** (in `HapticPlanner`, shared by both entry points whenever the Builder is missing):

- **Mixed timelines on envelope hardware become one basic envelope.** Each preset turns into a bump: a rise to the
  preset's strength and a fall to zero, each `max(10 ms, device minimum segment)` long. Bump sharpness is 0.9 for a
  click, 1.0 for a tick and 0.3 for a low tick. Bump intensity is the waveform pulse strength × (0.3 + 0.7 × scale),
  so scale 0 stays perceptible, as with presets. Previously the whole timeline became a waveform.
- **A lone preset whose primitive is unsupported becomes `createPredefined`:** `EFFECT_CLICK`, or `EFFECT_TICK`
  for tick and low tick (there is no predefined low tick). Previously it became a waveform pulse.

**Guard rails.**

- `HiddenApiSurfaceTest` (`:app` unit test) walks the static class graph. It starts from every class of the app plus
  the public `:core:haptics` entry points and follows our own code. It fails if anything reachable refers to the
  37.2-only vibration surface above (as a class, a member, or a reflection string) or reaches `HapticEffects` or
  `BuilderEffects`. A second test proves that the walk does find the Builder from `HapticEffects`. The graph reader
  is a small constant-pool parser (`testing/ClassGraph.kt`), with no bytecode library. A lint rule would only see
  direct calls. The class graph also catches indirect paths, such as the app reaching the probe through
  `hapticCapabilities()`.
- `:core:haptics` ships an R8 rule that keeps `BuilderEffects` a class of its own, so release builds never merge it
  into the classes the app loads.

## How previews differ from the module

The module on the owner's phone uses the Builder (`Timeline`, presets and envelopes one to one). The preview on the
same phone:

| Timeline | Module (Builder) | Preview (public APIs) |
| --- | --- | --- |
| Envelopes only | Each envelope fitted on its own | One joined `BasicEnvelopeBuilder` envelope, gaps held at zero. The point budget (`maxControlPoints`, at least 16) covers the whole timeline, so a timeline of several detailed envelopes can lose detail. |
| Presets only, primitives supported | `Preset.create(…, scale)` at absolute times | `Composition` of the same primitives and scales. Delays come from `getPrimitiveDurations`, so the start times match when the device reports durations. |
| A lone preset, primitive unsupported | Framework fallback (unspecified) | `createPredefined` (no scale) |
| Presets and envelopes, envelope support | One to one | One envelope with preset bumps. A bump lasts at least two minimum segments (40 ms on a 20 ms device), longer than a primitive, so an event right after a preset can start up to that much later. A bump is softer than a real click. |
| Presets and envelopes without envelope support, or several presets with an unsupported primitive | Framework fallback | Waveform: envelopes sampled every 20 ms, presets as 10–20 ms pulses, sharpness lost. On/off without amplitude control. |

## Consequences

- No more `hiddenapi` denials from Cucuto's app process. The module's behaviour is unchanged.
- Previews are close but not identical to what a notification plays. Each Builder preset and its `Composition`
  primitive should feel the same where the hardware has the primitive, but only a device can confirm it.
- On API 37.2 and later the Builder is public, and the app could use it for previews that match the module exactly.
  For now it deliberately stays on the public path: one app path, guarded by a simple test. Revisit when such a
  device is available. That means relaxing `HiddenApiSurfaceTest` for an SDK-gated class, not undoing the split.
