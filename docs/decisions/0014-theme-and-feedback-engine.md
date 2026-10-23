# 0014. Theme, character colors and the Feedback engine

- Status: accepted
- Date: 2026-10-24
- Issues: #22 (theme), #23 (feedback engine)

## Context

The brief asks for a Material 3 Expressive foundation (dynamic color with a brand fallback, light and dark,
expressive motion), character screens that take their colors from the sprite, and one `Feedback` engine that fires
sound, haptics and motion together "from a single timestamp so they never drift apart", while respecting "Remove
animations". `brand/` already defines the tokens, the motion springs, the six UI sounds and the haptic language.

Guidelines read: Compose Material 3 1.5.0 release notes (1.5.0-beta02, 2026-10-21: `MaterialShapes` still opt-in,
`LocalMotionScheme` removed in favor of `MaterialTheme.motionScheme`), Android's
[haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles) (prefer
predefined constants, rich compositions only for delight, never buzzy one-shots, keep haptics in sync with visuals
and audio) and the `VibrationEffect.Composition` reference (`DELAY_TYPE_RELATIVE_START_OFFSET`, API 36).

## Decision

**Theme.** `CucutoTheme` is `MaterialExpressiveTheme` with `MotionScheme.expressive()`, `CucutoTypography` (Google
Sans Flex with ROND) and the standard expressive shape scale. Dynamic color stays the default; the brand schemes are
the fallback and are what previews use. Both follow the system contrast level live (`UiModeManager` contrast
listener). `CucutoShapes` names the `MaterialShapes` per element from the brand guide; `MorphShape` draws a `Morph`
between two of them (cookie avatars).

**Character colors.** Seed to scheme and palette extraction use **Material Color Utilities' Kotlin port**
(`com.materialkolor:material-color-utilities` 6.0.0, Apache-2.0, pure Kotlin, no Compose dependency), not
`androidx.palette`:

- Compose Material 3 has no public seed-to-scheme API. MCU's `SchemeVibrant` with spec 2025 is exactly what
  `brand/tools` used for `brand/tokens/Color.kt`, so a unit test checks that `seedColorScheme(CucutoSeed)` reproduces
  the brand schemes role by role, including medium and high contrast.
- Palette's swatches (vibrant, muted…) are not Material seeds. MCU's Celebi quantizer plus `Score` is the algorithm
  Android uses to pick a seed from the wallpaper: it prefers common, colorful hues and rejects near-greys, which is
  what a sprite needs. Transparent pixels are skipped and images are downscaled to 112 px first; it runs on
  `Dispatchers.Default`.

`CharacterTheme(seed)` / `CharacterTheme(sprite)` re-theme a subtree and cross-fade all roles with the default
effects spring.

**Feedback engine.** `FeedbackEngine` (Hilt singleton) plus `LocalFeedback`:
`LocalFeedback.current.fire(Feedback.Assign(id))` returns a `FeedbackBeat` holding one `System.nanoTime()`
timestamp, the time base of Compose frame times. Sound and haptic start on it; visuals (`ParticleBurst`,
`CuckooDoor`, `CookieAvatar`) compute their pose from "seconds since the beat" sampled from the motion scheme's
springs, so a late first frame does not shift the animation away from the sound. Rich haptics put their primitives
at absolute offsets that match the notes of the matching sound (assign: 0, 60, 130 ms, the "-cu!" note).

- Sound: `SoundPool`, one stream (a new sound cuts the previous), `USAGE_ASSISTANCE_SONIFICATION`, silent in silent
  and vibrate ringer modes; tap and toggle sounds also follow the system "Touch sounds" setting. The six
  `brand/sounds/*.ogg` are compiled by reference into a generated `res/raw` (`R.raw.ui_*`) by a build-logic task:
  resource names cannot contain `-`, and nothing is copied into the source tree (ADR 0003).
- Haptics: standard touches use Compose `HapticFeedbackType` (the system handles the haptics setting); delight
  moments use `VibrationEffect.Composition` with `USAGE_TOUCH` (the "Touch feedback" intensity applies) only when
  `areAllPrimitivesSupported`, otherwise the listed fallback type. This small builder lives in `:app`:
  `:core:haptics` (ADR 0012) models pack haptics (Builder presets `CLICK`/`TICK`/`LOW_TICK` and envelopes for
  notifications), which has no `QUICK_RISE` and no Compose fallback.
- Settings: "UI sounds" off / quiet (default, 40 %) / normal and "UI haptics" (default on) in the settings DataStore
  (`UiFeedbackSettings` in `:core:data`).
- "Remove animations" (`ANIMATOR_DURATION_SCALE` = 0, observed live) is `LocalReducedMotion`: particles, squash and
  stretch and sound arcs are dropped, the door cross-fades open; sound and haptics still play.

**Gallery.** A debug-only "Design gallery" screen (linked from Status) shows every building block.

## Consequences

- Screens get colors, shapes, motion and feedback from one place; a new feedback moment is one `Feedback` entry.
- The MCU port is a new dependency (about 150 KB before R8); if Compose ever ships a public seed-to-scheme API, the
  brand-equivalence test tells whether it can replace it.
- Per-channel output latency (audio path vs. vibrator vs. display) is not compensated yet; it is small next to the
  ±16 ms budget on the owner's phone but should be measured on device and tuned in `FeedbackEngine.fire` if needed.
