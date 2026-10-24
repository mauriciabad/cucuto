# 0053. AI-written haptic patterns: Copy for AI and Paste pattern

- Status: accepted
- Date: 2026-10-24
- Issues: #217 (from the owner's answer on #170)

## Context

On #170 the owner suggested recommending AI to generate vibrations from the sound wave "for more precise results".
Auto haptics already come from the waveform ([ADR 0012](0012-haptics-effects-and-auto-haptics.md),
[ADR 0020](0020-auto-haptics-at-import.md)): each clip's loudness in 20 ms windows, simplified to a short envelope.
They follow loudness only, so they buzz along with the voice rather than hit beats and accents. The haptic editor
([ADR 0030](0030-haptic-editor-and-per-contact-override.md)) edits `custom` patterns by hand. The brief rules out a
backend and network calls from the app.

## Decision

**A recommendation plus two clipboard actions in the haptic editor (`ui/haptics/`), no AI in Cucuto.**

- *Tip.* An "Ask an AI" section under the editor's preview row says that Auto already follows each clip's loudness,
  that an AI chat can write a pattern that hits the beats, and that Cucuto sends nothing anywhere. For a chat with a
  voice, "Use Auto instead" stores the `auto` override and closes, like Save.
- *Copy for AI* (`HapticAiText.export`) puts a compact, self-contained prompt on the clipboard: the character's name,
  its clips (at most 6; path from `pack.json`, length from the container headers via `AudioProbe`, and the stored auto
  envelope as `ms,loudness,sharpness` rows), the current pattern as the pack format's JSON, the format rules, an
  example and "answer with only the JSON". The prompt stays in English whatever the UI language: a model reads it,
  and one fixed text is easy to test. No contact name or other private data goes in. Android shows its own "copied"
  confirmation (API 33+; `minSdk` is 37), so the editor adds none.
- *Paste pattern* (`HapticAiText.parse`) reads the clipboard only when tapped. It takes the first code block that
  holds JSON (else the first JSON object or array) and accepts the whole haptic, `{"haptic":{…}}`, `{"events":[…]}` or
  the bare array, since chat answers vary. The rest is strict and mirrors `pack.schema.json` and
  `PackValidator.checkHaptic`: 1 to 64 events, exactly one of `preset` or `envelope`, known presets, numbers in range,
  whole milliseconds, 1 to 16 points ending at intensity 0, sorted, no overlap, at most 5,000 ms, no unknown fields.
  The first broken rule becomes a `PatternProblem`, shown in plain words (English and Spanish) with a hint to tell
  the AI what is wrong. It is a small walker in the app rather than `SpecSchemas`, whose messages are schema jargon.
- A valid pattern replaces the timeline through `HapticDraft.replaceAll` (one undo step), so it stays within the
  editor's guarantees. The editor keeps 50 ms after each preset, so `normalize` may move or drop events of a pattern
  the pack format allows; the snackbar then says so. The user previews with Play and stores it with Save, as before.

## Consequences

- No network, no service, no model on the phone: the brief's non-goals hold. What the AI writes is only as good as the
  model and what it is given. The loudness rows are the simplified auto envelope (16 points at most, first 2 s), so
  attaching the audio gives better results; the tip says so.
- The pack creators (app #47, web #48) and a website guide, also listed on #217, can reuse `HapticAiText` and the
  rules in `docs/pack-format.md#writing-a-custom-haptic-with-an-ai`; they are not part of this change. Since #191
  the prompt and parser live in `shared/pack-spec` (`HapticPatternText`), shared by the app and the website's creator
  ([ADR 0058](0058-create-page-tools.md)); `HapticAiText` adapts them to the editor's timeline.
- How useful real AI answers are can only be judged by trying them (`needs-device` for the feel).
