# Baseline Profile

A Baseline Profile lists the classes and methods that Cucuto's main journeys run. Release builds ship it
(`assets/dexopt/baseline.prof`), and Android compiles that code ahead of time at install, so the first start and the
first scrolls don't wait for the JIT. Outside Google Play (F-Droid, IzzyOnDroid, Obtainium, GitHub releases) no cloud
profile ever arrives, so the app depends on ProfileInstaller (`androidx.profileinstaller`) to compile the shipped
profile on the phone. Decision record: [ADR 0056](decisions/0056-baseline-profile.md).
Official guide: [Create Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile).

## Pieces

- `android/baselineprofile/`: a `com.android.test` module with the Baseline Profile Gradle plugin and
  Macrobenchmark (`cucuto.android.baselineprofile` in `build-logic`). `BaselineProfileGenerator` runs the journeys in
  `Journeys.kt`:
  - `startup`: start from the launcher up to the first frame. It is also the Startup Profile
    (`includeInStartupProfile`), which orders the DEX file for a faster start.
  - `mainJourneys`: start, Home (scrolled), the character picker (the first chat on Home, through its detail when it
    has a voice) and Packs (scrolled).
  The steps find tabs by the app's own strings in the phone's language, and chat rows by their resource ID
  (`chat_row`). A step that finds nothing moves on, so a phone without chats just yields a smaller profile.
- `:app` applies the plugin's consumer side (`configureBaselineProfileConsumer`). It merges
  `app/src/main/generated/baselineProfiles/` into release builds and adds two build types for the generator:
  `nonMinifiedRelease` and `benchmarkRelease`. Builds never generate a profile on their own
  (`automaticGenerationDuringBuild = false`), so CI needs no device.
- CI builds the generator APK (`:baselineprofile:assembleNonMinifiedRelease` in the `android-assemble` job), so the
  journeys keep compiling against the app. It doesn't run them.

## Generating it (the lead, on the phone)

The generator needs a device, either rooted or on API 33+. The owner's rooted Android 17 phone qualifies. An emulator
would need an Android 17 (API 37) AOSP image, which isn't set up, so the module defines no Gradle-managed device.

1. Prepare the phone the way a user would have it: onboarding done, the demo packs imported, and a few chats on Home
   (for the picker journey).
2. Check the installed signature. The generator installs `app-nonMinifiedRelease.apk`, which is signed with this
   machine's debug key (the plugin falls back to it when there is no release key). That upgrades a debug build
   installed from this machine in place, keeping the app's data and its LSPosed state. If the phone has the
   release-signed app from GitHub, the install fails. Do not uninstall it to get past that: uninstalling drops the
   module's state in LSPosed. Stop and ask.
3. Unlock the phone, keep the screen on, then run:

   ```sh
   cd android
   ./gradlew :app:generateBaselineProfile
   ```

   The plugin installs the app and the generator, runs both tests a few times, and copies the result to
   `app/src/main/generated/baselineProfiles/baseline-prof.txt` and `startup-prof.txt`.
4. Look at the files: they should name `app/mauri/cucuto/...` classes (Home, picker, Packs, the shell) and Compose.
   Commit them in a `perf:` PR. The next release build merges them in, which changes the APK's bytes, as expected
   (the reproducibility check compares two builds of the same commit).
5. Generate again when a journey changes a lot (a new shell, a new Home).

Checking the gain is device work too: a Macrobenchmark `StartupBenchmarks` comparing `CompilationMode.None()` with
`CompilationMode.Partial()` can reuse `Journeys.kt`. Measuring scrolling smoothness at 120 Hz stays in #226.
