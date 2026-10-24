# 0056. Baseline Profile

- Status: accepted
- Date: 2026-10-24
- Issues: #226 (from #29)

## Context

The brief asks for smoothness at 120 Hz, and one of the tools it names is baseline profiles. Without one, an app's
first start and first scrolls run in the interpreter and the JIT until the runtime's own background compilation
catches up. Google Play makes up for that with cloud profiles, but Cucuto ships through F-Droid, IzzyOnDroid,
Obtainium and GitHub (ADR 0046), where no cloud profile ever arrives. The official way is the Baseline Profile Gradle
plugin with a Macrobenchmark generator module
([guide](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile)). Generating a
profile needs a rooted device or one on API 33+. The app is Android 17 only (minSdk 37). The only Android 17 device we
have is the owner's phone, and CI has no Android 17 emulator set up (it would need an AOSP API 37 image and a KVM
runner).

## Decision

- **A generator module, `:baselineprofile`.** It is a `com.android.test` module with `androidx.baselineprofile` and
  `benchmark-macro-junit4` 1.5.0 (the latest stable, 2026-09-09; since 1.5.0-alpha01 it works with AGP 9's new DSL),
  plus UiAutomator 2.4.0. Its setup lives in the `cucuto.android.baselineprofile` convention plugin. It targets `:app`
  and uses connected devices only. `BaselineProfileGenerator` records the startup journey (also the Startup Profile)
  and the main journeys: Home, contact detail with the ringtone picker and the ringtone rules, the character picker
  (folding a pack, searching), add from contacts and Packs, plus the sample chat (update 2026-10-24, #350). The
  steps live in `Journeys.kt`, where a future Macrobenchmark can reuse them.
- **Steps that survive language and data.** Tabs are found by the app's own string resources, read in the phone's
  language. Chat rows on Home carry a resource ID (`testTag("chat_row")` under `testTagsAsResourceId`). So do the
  pickers' pack headers (`pack_header`, #350). Any step that finds nothing moves on instead of failing.
- **The app consumes, never generates during a build.** `:app` applies the consumer side with `mergeIntoMain = true`
  and `automaticGenerationDuringBuild = false`, and depends on ProfileInstaller 1.4.1, which compiles the profile
  for sideloaded installs. Release builds merge `src/main/generated/baselineProfiles/` when it exists. Until then
  they are unchanged.
- **CI compiles the generator but doesn't run it.** The `android-assemble` job now lists its tasks
  (`assembleDebug assembleRelease assembleDebugAndroidTest :baselineprofile:assembleNonMinifiedRelease`) instead of
  `assemble`. Plain `assemble` would also build the plugin's `benchmarkRelease` (a second R8 run) for nothing.
- **The lead generates it on a device** (`./gradlew :app:generateBaselineProfile`, steps in
  `docs/baseline-profile.md`) and commits the profile in its own PR. See the addendum: it is an emulator.

## Consequences

- Until a profile is committed, release APKs are the same as before, apart from ProfileInstaller's few classes and
  its receiver.
- `nonMinifiedRelease` is signed with the debug key, so it upgrades a debug build from the same machine in place. It
  cannot replace a release-signed install, and uninstalling to force it would drop the module's LSPosed state, so the
  guide says to stop and ask instead.
- A committed profile changes the release APK's bytes, as any code change does. Reproducibility compares two builds
  of one commit, so it is unaffected.
- Measuring the gain (startup and frame timing at 120 Hz) needs the device and stays in #226.

## Addendum (2026-10-24): generated on an emulator

The owner's phone runs the release-signed app, so the generator's debug-signed APK can't go there (see above). The
first profile was generated on an Android 17 emulator instead (the API 37 Google APIs image, arm64), prepared like a
user's app: onboarding skipped, the demo packs imported, and made-up chats added to its database as root.
`ANDROID_SERIAL` keeps a plugged-in phone out of the run. There is still no Gradle-managed device: it would boot
empty, and the journeys would profile onboarding instead of Home, the picker and Packs. `StartupBenchmarks` (cold
start, `CompilationMode.None()` against the profile alone) now lives in `:baselineprofile`. Steps in
`docs/baseline-profile.md`.
