# 0030. Haptic editor, tap the rhythm and per-contact haptic override

- Status: accepted (owner closed #170: defaults kept; AI-written vibrations suggested, #217)
- Date: 2026-10-24
- Issues: #32, #33

## Context

The brief's haptic editor (M4, screen 7) is "a timeline screen where the owner taps to drop presets, drags envelope
points, sees a live waveform, and previews with the clip. It also has a 'tap the rhythm' mode that records taps into
presets." The per-contact override is first in the haptic precedence ("Per-contact override set in the haptic
editor"). The contact detail screen lists a haptic override (screen 4).

What already existed:

- `assignments.hapticOverride` stores the override as the pack format's `haptic` JSON
  ([ADR 0009](0009-data-layer-and-routing-table.md)). `RoutingTableBuilder` already prefers it over the character's
  haptic (`HapticJson.fromOverride`: `none` → `Off`, `auto` → `Auto`, `custom` → `Custom`), and the module already
  plays all four (`HapticVibrations`, #105). Clip envelopes for `auto` are analysed for every clip at import, whatever
  the pack says, so an `auto` override works on any character ([ADR 0020](0020-auto-haptics-at-import.md)).
- App previews must use public APIs only ([ADR 0026](0026-app-haptics-public-apis-only.md), `PublicHapticEffects`).

Nothing wrote the override and nothing edited a pattern.

## Decision

**No new storage or format.** `HapticOverride` (`:core:data`) names the four choices, `PackDefault` (null), `Auto`,
`Off` and `Custom`, and converts them to and from the stored JSON. `HapticJson.toJson` is the inverse of
`fromOverride`, so an override reads back as exactly the haptic that was saved (thousandths ↔ three-decimal
fractions). `ChatHapticOverrides` reads and writes it by `ChatKey`, like the rest of the UI. An override belongs to
the assignment: a chat without a voice has none, and assigning a different character starts fresh (unchanged).

**Per-contact choice: Pack, Auto, Off, Custom.** `HapticOverrideSection(chat, onEditPattern)` is a self-contained
card for the contact detail screen (#26), with its own view model. The four options are a Material 3 Expressive
connected button group. Each choice is stored at once; the publisher republishes the routing table and the module
plays it with the next message. "Custom" without a pattern opens the editor instead. A pattern the user switches
away from is remembered while the card is open, so Custom brings it back; it is not stored once another choice is
saved. Preview plays one of the character's clips with the chosen vibration, `auto` with that clip's own envelope.

**Editor.** `CucutoDestination.HapticEditor(chat)` (null: a scratch pad that only previews) opens
`HapticEditorScreen`. It starts from the chat's custom override, else the character's custom pack pattern, else an
empty timeline. Saving stores a `Custom` override.

- *Timeline:* presets drawn as bars as tall as their strength, with a head shape per kind (round click, plain tick,
  square low tick) so the kind never depends on color; envelopes as their intensity curve. The view fits the
  pattern (1.5 to 5 s, half-second steps) and keeps its scale during a drag.
- *Edit:* tap an empty spot to drop the current tool (Click, Tick, Low tick, or Swell, a 260 ms envelope); tap an
  event to select it; drag it to move it; drag the selected envelope's dots to reshape it (intensity and segment
  length). The selected event panel repeats every edit without gestures (kind, strength, sharpness, 10 ms nudges,
  add or remove a dot, delete, previous and next), for TalkBack and for precision. Undo covers every edit; one drag
  is one step.
- *Always valid:* `HapticTimeline` applies the pack format's rules (1 to 64 events, 1 to 16 points, envelopes end
  at zero, nothing ends after 5,000 ms, no overlap), so a saved override behaves like a pack haptic in the module and
  in backups. Presets get a 50 ms slot (Android recommends 50 ms between events) and segments at least 20 ms (the
  minimum every envelope device guarantees). An edit that does not fit snaps to the nearest free spot, or is
  refused with the "reject" haptic.
- *Tap the rhythm:* a pad records presses for up to 5 s (or 64 presses). The first press is 0 ms. Press length
  becomes the event ("tap length maps to intensity and duration"): up to 70 ms a `TICK` (scale 0.5 to 0.7), up to
  180 ms a `CLICK` (scale 0.7 to 1.0), longer a swell that lasts as long as the finger is down (20 ms rise, intensity
  0.6 to 1.0 and sharpness 0.7 to 0.3 as the hold grows to 1 s, then an 80 ms fade, shortened to fit before the next
  press). The live result draws on the timeline while recording. The recording replaces the timeline; Undo brings
  the old one back.
- *Preview:* "Play" vibrates the pattern alone, "Play with <character>" plays one of its clips with it; a playhead
  sweeps the timeline. Both go through `VoicePreview`, which uses `PublicHapticEffects`: no hidden API in the app.

## Consequences

- The contact detail screen (#26) only has to place `HapticOverrideSection` and navigate to
  `CucutoDestination.HapticEditor(chat)`. Until it lands, the editor is reachable only as a scratch pad from the
  preview list on the Status screen.
- Previews use the public fallbacks (ADR 0026), so on a phone where the module uses the Builder, a timeline that
  mixes presets and envelopes feels close to, but not exactly like, what a message plays.
- How the rhythm mapping feels, and whether the thresholds suit real tapping, can only be judged on the device
  (`needs-device`).
- A per-character editor for the in-app pack creator (M8) can reuse `HapticDraft`, `HapticTimelineView` and
  `RhythmRecorder` as they are; they know nothing about contacts.

## Owner answer (2026-10-24, #170)

> I'd also recommend users using AI to generate the vibrations based on the soundwaves for more precise results.

The owner closed the issue, so the three defaults stand. Auto mode already follows each clip's loudness envelope
(ADR 0012, ADR 0020); the suggestion is read as recommending AI-written `custom` patterns, which can follow beats and
accents rather than loudness alone. #217 adds the recommendation plus "copy for AI" (prompt, onsets and envelope as
text) and "paste pattern" (validated, loaded into this editor's timeline). No model runs on the phone and the app calls
no AI service.
