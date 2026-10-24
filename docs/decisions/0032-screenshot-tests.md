# 0032. Screenshot tests and website screenshots with Roborazzi

- Status: accepted
- Date: 2026-10-24
- Issues: #41 (scripted screenshots from demo packs), #38 and PR #110 (the website's screenshot slots)

## Context

The brief wants screenshots "from the app running the demo packs, captured by a script so they can be regenerated on
every release", never with copyrighted packs. The website's home page (#38) had four phone-frame placeholders for
them. The same captures can double as visual regression tests for the main screens, which several agents change in
parallel.

A device or emulator in CI is slow and flaky, and the owner's phone is not always connected. Two JVM options exist:
Paparazzi (layoutlib) and [Roborazzi](https://github.com/takahirom/roborazzi) on Robolectric Native Graphics.
The app's Compose UI tests already run on Robolectric 4.17, so Roborazzi adds no second rendering stack.

Versions checked on 2026-10-24 (Maven Central and the GitHub releases): Roborazzi **1.77.0** (2026-10-22, latest
stable), Robolectric 4.17 (already in the catalog).

## Decision

- **Roborazzi 1.77.0** on Robolectric Native Graphics, set up by a convention plugin, `cucuto.android.screenshots`
  (`android/build-logic`, ADR 0001): the Roborazzi Gradle plugin, `roborazzi` and `roborazzi-compose` for unit tests,
  `robolectric.pixelCopyRenderMode=hardware` (Roborazzi's recommendation for shadows and clipping) and the golden
  folder `android/app/src/testDebug/screenshots/`.
- **One small test per screen** in `android/app/src/testDebug/kotlin/app/mauri/cucuto/screenshots/`, extending
  `ScreenshotTest`: it renders the screen's stateless content (`HomeContent`, `PickerContent`, `PacksContent`,
  `ImportSheet`, `OnboardingContent`) with `DemoContent`. Two private content composables became `internal` for this.
  Covered now: onboarding (welcome and enable), Home with recent senders, the picker, Packs and the import review.
  Contact detail (#26), Settings (#28), Discover (#36) and the navigation shell (#153) are TODO entries in
  `ScreenshotTest`; their agents add a test once the screen is on `main`.
- **Content.** The two CC0 demo packs, read straight from `packs/` (real SVG sprites and covers, names and seed colors
  mirrored from `pack.yaml` and checked by `DemoPacksTest`), and invented chats ("Noa", "Climbers", "Book club"…).
  No real names, numbers or chat keys (the keys are obviously fake `1000000000000NN@lid`).
- **Look.** Brand colors (dynamic color off), light and dark, a 411 × 891 dp phone at xhdpi, a drawn status bar
  (9:30, signal, Wi-Fi, battery) and gesture handle, and matching fake system bar insets so screens lay out edge to edge
  as on a device. No device frame in the image: the website draws its own plain phone frame in CSS, the same for every
  shot. Animations run on the test clock and settle for 3 s; sprites decode synchronously (Coil with an unconfined
  dispatcher), so captures are deterministic.
- **Variants.** Each test runs as `[Light]`, `[Dark]`, `[LightEs]` and `[DarkEs]` (Robolectric parameterized runner).
  Only English has golden images. Spanish runs only for the website export and only once the app has Spanish strings
  (#183); until then it is skipped. A fresh composition per variant: switching the theme inside one composition left
  bottom sheets half open or stale.
- **Golden images** at half resolution (411 × 891 px, about 60 kB each), compared with Roborazzi's defaults (any
  visible pixel change fails). Robolectric Native Graphics produced byte-identical PNGs on macOS (arm64) and Linux
  (x86_64, Temurin 21 in Docker) on 2026-10-24, so goldens recorded on a Mac verify in CI. A one-letter change in a
  chat name fails the check.
- **Commands.** `./gradlew :app:recordRoborazziDebug` records, `./gradlew :app:verifyRoborazziDebug` verifies,
  `./gradlew :app:compareRoborazziDebug` writes diff images without failing. CI's `android-test` job runs
  `check -x lint -Proborazzi.test.verify=true` and uploads `build/outputs/roborazzi/` (the `_actual` and `_compare`
  images) when it fails. A plain `./gradlew build` renders every screen but captures nothing.
- **Website export.** `./gradlew :app:exportWebScreenshots` records and also writes the tests that name a `web` image
  to `web/src/lib/assets/images/screenshots/` (493 px wide, `<name>-<theme>.png`, Spanish in `es/`). The home page loads
  them with `import.meta.glob(… '?picture')`, so the site's build encodes JPEG XL, AVIF and PNG (#40). `<Picture>`
  gained a `dark` image served with `prefers-color-scheme: dark`; a missing translation falls back to English.
- **Media guard.** The allowlist gains exactly `android/app/src/testDebug/screenshots/*.png`: generated images of
  our own UI with CC0 demo content and made-up chats. The website copies live in `web/src/lib/assets/`, already allowed.
  The `android` CI filter now includes `packs/**`, since sprites appear in the goldens.

## Consequences

- A PR that changes how a covered screen looks must re-record: `./gradlew :app:recordRoborazziDebug`, then review the
  new PNGs in the diff like code. CI says which screen changed and uploads the diff.
- Screens built on `main` after this need one test each (a dozen lines); the TODO list in `ScreenshotTest` tracks them.
- Website screenshots are refreshed on demand (before a release, or when a screen changes a lot) with
  `exportWebScreenshots`; they are not regenerated on every build, so the site never depends on the Android toolchain.
- The screenshot tests add about 15 s to the app's unit tests (one Robolectric start, then about 0.3 s per capture).
