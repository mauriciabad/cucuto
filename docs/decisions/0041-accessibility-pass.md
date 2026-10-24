# 0041. Accessibility pass

- Status: accepted
- Date: 2026-10-24
- Issues: #29 (follow-ups: #226)

## Context

The brief asks for "Every sprite and button has a TalkBack label. Never encode meaning only in color, sound or
haptics", the system **Remove animations** setting respected, and large fonts. ADR 0028 added Cucuto's own "Remove
animations" setting, but it only reached the components that read `LocalReducedMotion` (particles, the door): every
Material spring and Cucuto's own `motionScheme` springs kept bouncing. The audit of every screen on `main` (status and
onboarding, Home, picker, Packs and the import review, Discover, contact detail, Settings and restore, the haptic
editor, Diagnostics, and after merging them the pack creator and the per-contact phone ringtone) against the Android
and Compose accessibility guidance
([Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility),
[semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics),
[testing](https://developer.android.com/develop/ui/compose/accessibility/testing)) found duplicate announcements
(character names said twice), switches and values read apart from their labels, unlabeled loading indicators and
collapsed FABs, connected button groups whose labels were cut off with large fonts or Spanish, button rows that ran off
the screen at 200%, dialogs that could not scroll, and two touch-only controls (the haptic timeline and the rhythm pad).

## Decision

**"Remove animations" snaps the motion scheme.** `ProvideFeedback` wraps the app in `ProvideReducedMotionScheme`: when
`LocalReducedMotion` is on (the system setting or Cucuto's), `MaterialTheme.motionScheme` becomes
`ReducedMotionScheme`, whose six specs are all `snap()`. Material components read their motion from the scheme, so the
in-app setting now behaves like the system one inside Cucuto: state changes stay, motion between them goes. Avatars
also drop the press squash (the shape change stays: it says "speaking"); list items use `animateItemUnlessReduced()`.
Sound and haptics still play (brand/motion.md).

**Choice groups never clip.** `ChoiceButtonGroup` measures its labels: when one does not fit its equal share, the
connected button group becomes separate buttons in a `FlowRow`. Each option is a radio button in a selectable group.
Group policy, the per-contact vibration and the haptic editor's tools and presets use it.

**Text gets more lines with large fonts.** `adaptiveMaxLines(n)` is `n + 2` from a 1.3 font scale up, for names and
one-line summaries in lists and headers. Rows of buttons are `FlowRow`s; dialogs and step pages scroll.

**One TalkBack stop per thing, labels in words.** List rows that hold a label and a value merge their semantics; whole
rows toggle their switch (`toggleable`, the switch has no handler of its own); a name shown under an avatar that already
says it is hidden from TalkBack; a visible button text comes first in its accessible name (label in name); titles and
section titles are headings; the onboarding step title is a polite live region so a page change is announced; "Playing"
is said in words, not only with a color.

**Touch-only controls get accessible paths.** The haptic timeline offers a custom action, "Add *tool* after the last
event", and the event panel edits the selected event. A screen reader's double tap on the rhythm pad records one short
beat.

**Contrast is tested, not assumed.** `ContrastTest` checks WCAG AA (4.5:1 for text, 3:1 for icons and outlines) for
every foreground and background pair the screens use, in the brand schemes (light, dark, standard, medium and high
contrast) and in character schemes from seeds at the edges of the wheel. Dynamic (wallpaper) colors come from the same
Material tonal system, whose role pairs are built for these ratios; the wallpaper itself cannot be tested off device.

**Tests on every build (Robolectric, `src/testDebug/.../a11y`).** Compose skips the Accessibility Test Framework on
Robolectric (`enableAccessibilityChecks` logs "not supported by Robolectric" and returns; it needs API 34+ on a device).
A device run was tried and dropped: ATF 4.1.1 pulls Material Components 1.2 and old AndroidX into the instrumented
classpath, which clashes with the app's locked versions under consistent resolution. So Cucuto checks the main ATF
rules itself on every screen with made-up content (`A11yScreens`):

- every control has a label (SpeakableTextPresentCheck), no word is said twice in one TalkBack stop
  (DuplicateSpeakableTextCheck), touch targets are at least 48 dp (TouchTargetSizeCheck);
- at 200% font, in English and in Spanish, no text overflows without an ellipsis and no word is split across lines;
- key flows assert roles, states, headings and actions; `ContrastTest` covers color.

**Large-font goldens.** The same screens at 200% go through the Roborazzi harness of ADR 0032
(`LargeFontScreenshotTest`, `screenshot(…, fontScale = 2f)`): `<screen>-font200-light.png` in English and
`<screen>-font200-es-light.png` in Spanish, light theme only (dark adds nothing to reflow, and contrast has its own
test). They are verified on every build like the other goldens, so a layout that stops reflowing shows up in review.

**Follow-ups (#226, 2026-10-24).**

- *Blank captures.* In a full test run, the phone ringtone card sometimes came out as an empty window, status bar
  included. Robolectric's hardware capture replays the display lists the window recorded last. The card is the only
  still screen in the large-font set, so it records them only in its first frames. Drawing the same views in software
  at the same moment showed the card, and a fresh frame before the capture fixed it. `ScreenshotTest` now invalidates
  every window's views and draws the last frame of the settle time right before each capture. The total time stays
  the same, so no other golden changed. The card now has its 200% golden.
- *Touch targets, measured for real and on the whole screen.* Compose stretches the touch area of any clickable
  under 48 dp to 48 dp, and `touchBoundsInRoot` reports that stretched area. So the first version of the check, which
  measured it, could never fail. Now a control under 48 dp fails when its 48 dp area overlaps the area of another
  control in the same window and scrolling container, unless one contains the other or sits behind it (a scrim, a
  card). That is the case where the stretch doesn't help. The check also scrolls every scrolling container to its
  end, through the containers' own scroll action, and checks each screenful. `A11yAssertionsTest` proves it catches
  crowded controls far down lazy lists, columns and rows. It found the restore flow's "select all" row (a 24 dp
  checkbox row next to the first contact, now 48 dp).
- *More screens.* Diagnostics and Day and night now have stateless `DiagnosticsContent` and `DayNightContent`. With
  the creator's cropper, "Check and finish" and four restore steps, they joined `A11yScreens` (`A11yMoreScreens`)
  and the 200% goldens. A bottom sheet opens its own window, which on Robolectric gets neither the test's font scale
  nor its language. So the checks render the sheet's content (`FinishContent`) in the main window.
- Fixes found by the new checks: in the restore flow, the installed pack's check icon no longer repeats "Installed",
  and with large fonts the "Choose file" and "Try again" buttons sit under the pack's text instead of squeezing it to a
  word per line. The haptic editor uses `CucutoTopBar` (a medium flexible top bar), so its title shows in full at
  200% and in Spanish.

## Consequences

- New screens get the same checks by adding one line to `A11yScreens`.
- With Cucuto's "Remove animations" on, every Material transition inside the app is instant, the same as with the system
  setting. Loading indicators keep their own motion (they mean "working").
- A TalkBack walkthrough and an Accessibility Scanner pass on the phone are still to do (`needs-device`, #226).
