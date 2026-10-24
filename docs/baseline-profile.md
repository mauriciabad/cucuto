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
  - `mainJourneys`: start, Home (scrolled), contact detail of the first chat on Home when it has a voice (scrolled),
    its ringtone picker ("Phone calls") and the ringtone rules, the character picker (through that detail; a pack
    folded and unfolded, a search typed), "Add a chat from contacts" from "Assign a voice" (scrolled and searched) and
    Packs (scrolled).
  The steps find tabs, buttons and rows by the app's own strings in the phone's language, chat rows and the pickers'
  pack headers by their resource IDs (`chat_row`, `pack_header`), and scroll a list a few times to find what's
  further down (the setup card fills Home's first screen on an emulator). A step that finds nothing moves on, so a
  phone without chats just yields a smaller profile.
  `StartupBenchmarks`, in the same module, measures what the profile buys ([Measuring it](#measuring-it)).
- `:app` applies the plugin's consumer side (`configureBaselineProfileConsumer`). It merges
  `app/src/main/generated/baselineProfiles/` into release builds and adds two build types for the generator:
  `nonMinifiedRelease` and `benchmarkRelease`. Builds never generate a profile on their own
  (`automaticGenerationDuringBuild = false`), so CI needs no device.
- CI builds the generator APK (`:baselineprofile:assembleNonMinifiedRelease` in the `android-assemble` job), so the
  journeys keep compiling against the app. It doesn't run them.

## Generating it (on an emulator)

The generator needs a device, either rooted or on API 33+. Use an Android 17 emulator, not the owner's phone: the
phone runs the release-signed app, and the generator's `app-nonMinifiedRelease.apk` is signed with this machine's
debug key, so it can't be installed there without uninstalling, which drops the module's LSPosed state. The committed
profile was generated this way (2026-10-24, #226; again with the ringtone and contacts journeys, #350).

There is no Gradle-managed device on purpose: a managed device boots empty, so the journeys would only see onboarding.
The profile should follow a user who finished setup, so the emulator is prepared by hand once.

1. **Emulator.** Install the API 37 Google APIs image (not Play Store: `adb root` must work for step 3) and create
   an AVD for it (Android Studio's Device Manager, or an AVD folder whose `config.ini` has
   `image.sysdir.1=system-images/android-37.0/google_apis/arm64-v8a/`; `x86_64` on Intel). Boot it on port 5554:

   ```sh
   android sdk install system-images/android-37.0/google_apis/arm64-v8a
   ~/Library/Android/sdk/emulator/emulator -avd <name> -port 5554 -no-window -no-audio -no-boot-anim -no-snapshot-save
   ```

2. **The app, set up like a user's.** Build and install `:app:assembleNonMinifiedRelease`, skip onboarding, and
   import the demo packs: build the bundle with the packer (`packer bundle --id app.mauri.demo.all ... packs/*`, see
   `tools/packer/README.md`), `adb push` it to `/sdcard/Download/`, then use Home's "Choose a file from Downloads".
3. **Chats.** On an emulator no module reports senders, so add a few made-up ones to the app's database as root (never
   real names or numbers):

   ```sh
   adb -s emulator-5554 root
   adb -s emulator-5554 shell am force-stop app.mauri.cucuto
   adb -s emulator-5554 shell "sqlite3 /data/data/app.mauri.cucuto/databases/cucuto.db \"INSERT INTO seen_senders VALUES
     ('com.whatsapp','10000000001@s.whatsapp.net','Ana',0,NULL,NULL,1761300000000,1761300900000,5),
     ('com.whatsapp','120000000000000001@g.us','Climbing club',1,'10000000001@s.whatsapp.net','Ana',1761300000000,1761300850000,12)\""
   ```

   Ten or so rows make Home scroll. Give the newest chat a voice with a ringtone that has versions (Home's "Choose a
   voice" card: Bleep the frog, then "Ring with Bleep the frog"), so the journeys pass its detail, the ringtone
   picker and the rules.
4. **Contacts.** "Add a chat from contacts" lists contacts with a WhatsApp entry. Add a few made-up ones (never real
   names or numbers) and grant the permission, so the journey reaches the list instead of Android's prompt:

   ```sh
   adb -s emulator-5554 shell content insert --uri content://com.android.contacts/raw_contacts \
     --bind account_type:s:com.whatsapp --bind account_name:s:WhatsApp
   # with <id> the new raw contact's _id (content query --uri content://com.android.contacts/raw_contacts):
   adb -s emulator-5554 shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:<id> \
     --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:Ivan
   adb -s emulator-5554 shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:<id> \
     --bind mimetype:s:vnd.android.cursor.item/vnd.com.whatsapp.profile --bind data1:s:20000000001@s.whatsapp.net
   adb -s emulator-5554 shell pm grant app.mauri.cucuto android.permission.READ_CONTACTS
   ```
5. **Generate**, with `ANDROID_SERIAL` so that only the emulator is used even if a phone is plugged in (AGP's
   connected tests honour it), and with the APKs left installed so the prepared data survives the run:

   ```sh
   cd android
   ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateBaselineProfile \
     -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
   ```

   It takes about 20 minutes on an Apple Silicon Mac. The plugin runs every journey until the profile is stable and
   writes `app/src/main/generated/baselineProfiles/baseline-prof.txt` and `startup-prof.txt`. The test report also
   lists the `StartupBenchmarks` tests as failed: Macrobenchmark skips them with an assumption during generation, and
   the build still passes.
6. **Check and commit.** The files should name `app/mauri/cucuto/...` classes (navigation, Home, contact detail,
   the pickers, ringtones and their rules, contacts, Packs, status) and Compose. Commit them in a `perf:` PR. The
   next release build merges them in, which changes the APK's bytes, as expected (the reproducibility check
   compares two builds of the same commit).
7. Generate again when a journey changes a lot (a new shell, a new Home).

The phone also works (`useConnectedDevices` stays on) if it runs a debug build from this machine; a release-signed
install can't be replaced without uninstalling, so don't.

## Measuring it

`StartupBenchmarks` measures a cold start without any compilation (`CompilationMode.None()`) and with only the
Baseline Profile (`CompilationMode.Partial(BaselineProfileMode.Require)`), 10 starts each, on `benchmarkRelease`
(minified, like a release). On the same prepared emulator:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.skipOnEmulator=false
```

Results land in `baselineprofile/build/outputs/connected_android_test_additional_output/`. Emulator timings only
show the direction of the change; real numbers need the phone. Measuring scrolling smoothness at 120 Hz stays in #226.
