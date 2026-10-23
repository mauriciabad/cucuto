# Cucuto sound and haptics

Cucuto is about sound, so its own UI sounds must stay small and polite: the characters are the stars. UI sounds are
**quiet by default and switchable off**; haptics follow the system touch-feedback setting. Every sound and haptic
is fired by the single `Feedback` engine together with its motion (see [motion](motion.md)), and never carries
meaning alone: the screen always says what happened too.

## UI sound palette

All six are synthesized from sine and triangle tones with ffmpeg (`tools/build-sounds.mjs`): no recordings, so they
are original works dedicated to the public domain (**CC0-1.0**). Ogg Opus, 48 kHz mono, 64 kb/s, under 300 ms,
peaks around −3 dBFS before the app's own UI volume (default 40 %).

| Sound | File | Length | Pitch and character | Use for |
| --- | --- | --- | --- | --- |
| tap | [`sounds/tap.ogg`](sounds/tap.ogg) | 50 ms | A6 (1760 Hz) sine with an octave overtone, 2 ms attack, very fast decay: a soft wooden tick, like a clock | Taps on cards, chips, grid items. Not on every scroll or keyboard key |
| toggle on | [`sounds/toggle-on.ogg`](sounds/toggle-on.ogg) | 110 ms | Two triangle blips rising a fifth (A5 → E6) | Switches, checkboxes turning on, enabling a clip |
| toggle off | [`sounds/toggle-off.ogg`](sounds/toggle-off.ogg) | 110 ms | The same blips falling (E6 → A5) | Switches turning off |
| success | [`sounds/success.ogg`](sounds/success.ogg) | 280 ms | C6–E6–G6 major arpeggio, 60 ms apart, soft triangle | Pack imported, backup written, status card turns green |
| error | [`sounds/error.ogg`](sounds/error.ogg) | 240 ms | Two low round notes falling a major third (A4 → F4); a gentle "nope", never a buzzer | Invalid pack, failed download, rejected action |
| assign | [`sounds/assign.ogg`](sounds/assign.ogg) | 290 ms | **The Cucuto call.** A flute-like falling major third (G5 → E♭5) with light vibrato: "cu-cu!", like a cuckoo clock | Assigning a character (then the character's own clip plays), the door-open moment |

Rules:

- One UI sound at a time; a new one cuts the previous. Never layer a UI sound over a character clip: the assign
  call plays first and the character clip starts on its second note (about 130 ms later).
- Play through `SoundPool` with `USAGE_ASSISTANCE_SONIFICATION`, so the system's touch-sound and volume
  settings apply. Respect silent and vibrate ringer modes (no UI sounds).
- Same interaction, same sound, everywhere (app and website).
- The website uses the same files (`<audio>` is never autoplayed; sounds play only after a user gesture).

## Haptic language

Following [Android's haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles):
**clear** (crisp, short) haptics for touches, **rich** effects only for the few moments of delight, and never
**buzzy** vibrations for UI. Prefer the predefined feedback types; they adapt to each device and stay consistent
with the system. Strength follows importance: frequent events are subtle, important ones are stronger.

### Standard touches: Compose `HapticFeedbackType`

Use `LocalHapticFeedback.current.performHapticFeedback(type)`.

| Interaction | `HapticFeedbackType` |
| --- | --- |
| Toggle a switch, enable or disable a clip | `ToggleOn` / `ToggleOff` |
| Confirm an action (save, assign confirmed, import confirmed) | `Confirm` |
| Rejected or failed action, invalid input | `Reject` |
| Long press on a contact or a sprite | `LongPress` |
| Dragging a reorder item past a neighbor, slider steps, haptic editor timeline snapping | `SegmentTick` |
| Trimming a clip in the waveform editor (many fine steps) | `SegmentFrequentTick` |
| Swipe-to-dismiss or predictive back crossing its threshold | `GestureThresholdActivate` |
| Gesture released after a threshold | `GestureEnd` |
| Secondary click (mouse/stylus) | `ContextClick` |

Plain taps on buttons get no extra haptic: Material components and the system already handle them.

### Moments of delight: `VibrationEffect.Composition`

Rich effects are built from composition primitives. Before playing one, check
`Vibrator.areAllPrimitivesSupported(...)`; if any is missing, fall back to the listed feedback type.

| Moment | Composition | Fallback |
| --- | --- | --- |
| **Assign** a character (rising) | `PRIMITIVE_QUICK_RISE` 0.5 → +60 ms `PRIMITIVE_CLICK` 0.9 → +130 ms `PRIMITIVE_TICK` 0.6 (lands on the "-cu!" note) | `Confirm` |
| Door pops open (preview, onboarding) | `PRIMITIVE_TICK` 0.4 → +100 ms `PRIMITIVE_CLICK` 0.7 | `Confirm` |
| Pack imported / status all green | `PRIMITIVE_TICK` 0.3, +60 ms `PRIMITIVE_TICK` 0.5, +60 ms `PRIMITIVE_CLICK` 0.8 (mirrors the success arpeggio) | `Confirm` |
| Error | `PRIMITIVE_LOW_TICK` 0.8 → +90 ms `PRIMITIVE_LOW_TICK` 0.6 (mirrors the two low notes) | `Reject` |
| Sprite "speaks" in the picker | The character's own haptic (pack haptic or auto mode), scaled to 0.6 | none |

Never use `createOneShot`, `vibrate(long)` or legacy waveforms for UI feedback: they ring on and feel buzzy.

### Character haptics

Character vibrations (what plays with a WhatsApp notification) follow the pack haptic schema in the
[project brief](../docs/project-brief.md#haptics): `VibrationEffect.Builder` presets and basic envelopes,
"haptics follow the voice" in auto mode, at most 2 s, always ending at zero intensity. They are the one place where
longer patterns are welcome, because a notification must be felt in a pocket.

## Accessibility

- Sound and haptics are redundant channels, never the only signal.
- Settings: "UI sounds" (off, quiet, normal; default quiet) and "UI haptics" (on by default, follows the system
  "Vibration and haptics" setting). Character sounds and vibrations are configured separately.
- No sound or haptic loops; nothing longer than 300 ms in the UI.
