# 0003. The app compiles brand tokens and the icon straight from `brand/`

- Status: accepted
- Date: 2026-10-23
- Issue: #2 (brand: #1)

## Context

`brand/` holds generated, ready-to-use Android assets: `tokens/Color.kt` (color schemes), `tokens/Type.kt`
(`CucutoTypography`) and `app-icon/android-res/` (adaptive and themed icon). The brand guide suggests copying them
into `:app`, but copies drift silently whenever `brand/tools` regenerates them.

## Decision

`:app` adds `../../brand/tokens` as a Kotlin source directory and `../../brand/app-icon/android-res` as a resource
directory (`app/build.gradle.kts`). Nothing is copied. Spotless only formats `src/`, so the generated files stay
byte-identical to their generator's output.

The Google Sans Flex font is the exception: the brand ships no font file, so a Latin subset (645 KB, `ROND`,
`opsz` and `wght` axes) lives at `android/app/src/main/res/font/google_sans_flex.ttf`, with its license and the
exact `hb-subset` command in `android/third_party/google-sans-flex/`.

## Consequences

- Rebuilding the brand updates the app on the next build; the Android CI workflow also runs on changes to
  `brand/tokens/**` and `brand/app-icon/android-res/**`.
- Files in those brand folders must stay valid Kotlin and valid Android resources.
- UI sounds (`brand/sounds/*.ogg`) are not wired yet; they belong in the Feedback engine work and can be referenced
  the same way (`res/raw`) when it lands.
