# Maintaining Cucuto

A guide for an agent that arrives cold and works alone. From 2026-10-25 there is no lead agent and no parallel
fleet, only an occasional single agent doing a small feature or fix. You are your own lead: you pick the work,
review your own diff, and keep the docs true. [`CLAUDE.md`](../CLAUDE.md) holds the rules; this file is the
how-to.

## 1. Orient (about 15 minutes)

1. **[`CLAUDE.md`](../CLAUDE.md):** ground rules, identifiers, workflow, builds. Every rule there is mandatory.
2. **[`project-brief.md`](project-brief.md):** the spec. Read the section for your area and the
   [milestones](project-brief.md#milestones-and-acceptance-criteria), which open with a status table.
3. **[ADR index](decisions/README.md):** one line per decision. Read every ADR your change touches.
4. **[`owner-log.md`](owner-log.md):** the owner's words, verbatim. Search it for your feature before deciding
   anything.
5. **Open `question` issues:** `gh issue list --label question --state open`, then `gh issue view <n> --comments`.
   Agents post through the owner's GitHub account, so **every comment shows `mauriciabad` as the author**. An owner
   comment is one *without* the AI banner. A question the owner commented on and left open is waiting for an agent
   to apply the answer: copy it verbatim into `owner-log.md`, do the work (or file an issue for it), then close the
   question. If the owner closed it, the default stands.
6. **Open PRs:** `gh pr list`. A PR left open usually has a red `ci-ok` or a conflict. Finish it before starting
   something that overlaps it.

## 2. Pick and scope a small feature

- **Issue first.** Every change has an issue. Pick an open one from the milestone you are working on
  (`gh issue list --milestone "M3 UI and game feel"`) or open one. Skip issues labeled `owner-action`, `blocked` or
  `question`.
- **Docs before code** (owner, 2026-10-24). List every statement in the brief, the ADRs and
  [`pack-format.md`](pack-format.md) that your change contradicts. Update those first, in the same PR: amend the ADR
  or add a new one (numbering rules in the [ADR index](decisions/README.md)), and mark owner decisions in the brief
  as "owner update YYYY-MM-DD (#N)". Then make the code match the docs.
- **Keep it small.** One issue, one PR. If you find more work, file issues for it with a milestone, labels and a
  "Next step" line.
- **Never block.** For a decision the owner should own, apply a sensible default, document it, and open a
  `question` issue that states the default (format in `CLAUDE.md`).
- **Minimum intrusion** (owner, #67): fewest hooks, permissions and reads. New permissions also need the CI
  allowlist (`ALLOWED_PERMISSIONS` in `ci.yml`), the website's privacy page and the Data safety answers in
  [`google-play.md`](google-play.md).

## 3. Build and test

Set up once per shell: `export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home`.
Full requirements: [CONTRIBUTING.md](../CONTRIBUTING.md#building).

| Area | Command (from the repo root) | What it covers |
| --- | --- | --- |
| Android | `cd android && ./gradlew build` | debug + release APKs, Lint, ktlint, unit tests |
| Android, formatting | `cd android && ./gradlew spotlessApply` | fixes ktlint findings |
| Android, what CI runs | `cd android && ./gradlew check -x lint -Proborazzi.test.verify=true` | unit tests + screenshot goldens |
| Web | `cd web && bun install && bun run check && bun run lint && bun run build` | svelte-check, Prettier, ESLint, static site |
| Web tests | `cd web && bun run test:unit` and `CI=1 bun run test` | Bun unit tests; Playwright smoke and axe tests |
| Pack checks in the browser | `cd shared/pack-spec && ./gradlew syncWebPackSpec` | writes `web/src/lib/pack-spec/generated/` (ignored) |
| pack-spec | `cd shared/pack-spec && ./gradlew build` | the pack rules on the JVM |
| Packer | `cd tools/packer && ./gradlew build installDist` | every golden fixture in `spec/fixtures/` |
| Brand | `cd brand/tools && bun install && bun run build` | logo, icons, tokens |
| Fixtures, demo sounds | `kotlin spec/fixtures/generate.main.kts`, `kotlin packs/generate.main.kts` | reproducible regeneration |

Run Playwright with `CI=1`: without it, Playwright reuses whatever preview server already listens on port 4173.
Run the builds for every area you touched before pushing.

### End-to-end smoke tests (emulator)

Before pushing any app change, run the smoke suite (#340). It drives the real app (Room database, importer,
routing publisher, backups, navigation) through the main journeys with the CC0 demo packs. In about 2 minutes it
tells you whether you broke one of them:

| Test (`android/app/src/androidTest/.../smoke/`) | Journey |
| --- | --- |
| `FirstRunSmokeTest` | onboarding ends on Home; the demo pack imports from the file picker; "Open with" imports a download (a media provider `content:` URI) |
| `VoiceSmokeTest` | message voice then the optional ringtone step (#318), then "Android default" and "Leave as is"; own ringtone rules saved and reset; "Surprise me" in the picker; each change checked in the routing table the publisher writes for the module |
| `SampleChatSmokeTest` | the sample chat plays a message and a call |
| `BackupSmokeTest` | full backup export, wipe, restore from Home; the voice is back in the routing table |

Pick one way to run it (both build the demo packs with the packer first, the `demoPacks` task):

- **Gradle Managed Device:** `cd android && ./gradlew :app:smokeTest`. Gradle downloads the API 37 AOSP ATD image
  once, then boots it, runs the suite and shuts it down.
- **Any running emulator:** `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`. Always set
  `ANDROID_SERIAL`: without it, Gradle installs on every device attached, the owner's phone included. Never run it on
  the phone (`62240DLCH0005W`): the Orchestrator clears the app's data before each test.

How it works, so you can extend it:

- Each test runs in its own process on a cleared app (Android Test Orchestrator with `clearPackageData`). It starts
  like a fresh install on Hilt's test application (`HiltTestRunner`). `SmokeTest.startApp` starts what
  `CucutoApplication.onCreate` would (the routing publisher), then opens `MainActivity`.
- Setup goes through the app's real classes: `installDemoPack` (the importer, as Install does) and `messageFrom` (a
  sender, as the module reports one). System file pickers are answered with Espresso-Intents (`answerFilePicker`).
- No sleeps. Compose idling covers the UI. `waitFor`, `waitForText` and `scrollListTo` wait for a node after
  background work. `waitForPublishedTable` reads the publisher's own copy of the table.
- Nodes are found by string resource (`R.string.…`), so a renamed string fails to compile and reworded text still
  matches. If you change a journey on purpose, update its test in the same PR.
- **Not in CI:** it needs the full APK (NDK and Rust for `:core:jxl`), an API 37 emulator image download and a boot
  on every app PR, more than 10 minutes on a GitHub runner. A slow or flaky required check would also block
  auto-merge with nobody around to retry it. Run it yourself before pushing app changes.

## 4. Screenshot goldens (Roborazzi)

CI verifies the PNGs in `android/app/src/testDebug/screenshots/` (main screens, large fonts, Spanish; ADR 0032).
If your change alters how a covered screen looks:

```sh
git merge origin/main
cd android && ./gradlew :app:recordRoborazziDebug
```

Open the changed PNGs and look at them before committing. When screens change visibly, also refresh the website
(`./gradlew :app:exportWebScreenshots`) and store (`./gradlew :app:exportStoreScreenshots`) screenshots in the same
or a follow-up PR. A failed verify uploads diffs from `android/**/build/outputs/roborazzi/` as a CI artifact.

## 5. Strings: English and Spanish

Every user-visible string ships in both languages in the same PR (owner, #70; ADR 0036):

- App: `res/values/<file>.xml` plus `res/values-es/<file>.xml` (same file name). Spain Spanish with "tú". Reuse the
  shared terms at the top of `values-es/strings.xml` (Diagnóstico, interruptor de voces, ámbito…). Lint
  `MissingTranslation` and `LocaleParityTest` (`android/app/src/test/kotlin/app/mauri/cucuto/i18n/`) fail otherwise.
- Website: `web/src/lib/i18n/en.ts` and `es.ts`.
- Setup steps live in three places that must agree: app strings, the website's Install page and
  [`install.md`](install.md).

## 6. Commit, PR, CI and auto-merge

1. In your worktree: `git config user.name "Claude (AI agent)"` and `git config user.email noreply@anthropic.com`.
   Keep the `Co-Authored-By` trailer.
2. Branch `<type>/<issue>-<slug>`. Use Conventional Commits. The PR title becomes the squash commit and a release
   note line for `feat`, `fix`, `perf` and `docs`, so write it for users ([releasing.md](releasing.md#release-notes)).
3. Run `git merge origin/main` right before pushing.
4. `gh pr create` with the AI banner first, `Closes #N`, doc changes listed at the top, and the closing line from
   `CLAUDE.md`. If an owner message caused the work, add it verbatim to `owner-log.md` in the same PR.
5. Run `gh pr merge --auto --squash` and stop. Don't wait for CI.

CI is one workflow, `.github/workflows/ci.yml`. The `changes` job picks the jobs by path: `media-guard`,
`store-metadata`, `android-assemble`, `android-test`, `android-lint`, `pack-spec-js`, `web`, `packs` and
`deploy-pages`. **`ci-ok`** is the only required check: never rename it, and add new jobs to its `needs`. With no
lead, nobody resumes you when CI fails. In your next session, or before finishing if you can wait, check
`gh pr checks <n>`. Fix a red PR before starting new work.

## 7. Releasing

Details: [releasing.md](releasing.md). In short:

- **App:** a release PR (`kotlin tools/release/release-notes.main.kts X.Y.Z`, edit `CHANGELOG.md` and the store
  changelog, `--check`), merged as `chore(release): X.Y.Z`. Then tag the merge commit on `main` and push it:
  `git tag vX.Y.Z && git push origin vX.Y.Z`. `release.yml` builds, signs and publishes the APK (and the AAB), then
  rebuilds the website. Only `vMAJOR.MINOR.PATCH` tags work. Agents never create, read or set the signing key or its
  secrets.
- **Demo packs:** push a `packs-vN` tag (next after the newest, currently `packs-v1`) to run `packs-release.yml`.
  It publishes the packs in `packs/`, the demo bundle and `repository.zip`, and the website serves the newest one at
  `/repo/index.json`, the app's default repository. Do this after changing `packs/` or the index format (for
  example `x-haptic`, ADR 0069).
- **Google Play** uploads are the owner's (#276).

The owner allows agents to make releases ("i allow you making releases", owner log) and expects one whenever
`main` has user-visible progress and CI is green. Installing a release on the phone is fine; the module side
updates only after the owner reboots.

## 8. The owner's phone

Pixel 10 Pro, Android 17 (SDK 37.1), KernelSU, LSPosed 2.2.1 (libxposed API 102), WhatsApp and WaEnhancer X. Facts:
[device.md](device.md). Test plan and log lines: [device-testing.md](device-testing.md).

- **adb serial `62240DLCH0005W`.** It is not always connected (`adb devices -l`). Use `adb -s 62240DLCH0005W …`
  when several devices are attached.
- **Allowed:** read-only `adb` (logcat, `dumpsys`, the LSPosed module log), and installing and launching
  `app.mauri.cucuto`.
- **Never** without the owner's explicit go-ahead in this session: enable or disable modules in LSPosed, change
  scopes, reboot, send messages or place calls, wipe, or open WhatsApp's files. Never uninstall Cucuto: it deletes the
  owner's packs and assignments, and LSPosed drops the module, which only the owner can re-enable.
- **The phone runs the release-signed app.** A debug build from your machine can't be installed over it (different
  key), so don't try to fit one by uninstalling. Test UI with Robolectric, Roborazzi or an emulator
  (`android` CLI) instead. An app update takes effect in `system_server` only after a reboot (see §10).
- The phone is often locked behind biometrics. Anything that needs it unlocked for a while needs the owner: label
  the issue `needs-device` and `owner-action`.
- Never post contact names, numbers, chat keys or the Diagnostics decision list on GitHub.

## 9. Dependencies

Owner, #68: every session is a maintenance session. At the start, check the open Dependabot PRs (`gh pr list`) and
outdated versions in the area you touch (`android/gradle/libs.versions.toml`, the `package.json` files, GitHub
Actions, the Gradle wrapper). Do cheap updates in a **separate** `build(deps): …` PR, or merge a green Dependabot
PR. For expensive ones (a major with breaking changes), open or update a `type:chore` issue saying what blocks it
(example: #162, TypeScript 7). Never mix bumps into a feature PR. Dependabot auto-merges green patch and minor
updates; what stays open carries `deps:major` (ADR 0004).

## 10. Gotchas

- **AGP 9 has Kotlin built in.** Never apply `org.jetbrains.kotlin.android` or kapt (use KSP). Module setup lives in
  convention plugins in `android/build-logic/` (ADR 0001).
- **compileSdk is 37 minor 2** (`sdk-compile-minor` in `libs.versions.toml`). `VibrationEffect.Builder`, `Preset`
  and `Envelope` are missing from `android-37.0`. Install `platforms/android-37.2`.
- **NDK r30 and Rust** for `:core:jxl` (APK packaging only, not unit tests or Lint):
  `android sdk install ndk/30.0.16248370`, `brew install rustup && rustup default stable`, `cargo install cargo-ndk`.
  The version is `ndk` in `libs.versions.toml`.
- **Never throw in `system_server`** (or in WhatsApp, for the opt-in ringtone hook). Every hook catches
  `Throwable` and falls back to WhatsApp's own tone or ringtone. No disk, database or Binder work on the
  notification path: the module answers from memory in well under 5 ms.
- **LSPosed and app updates.** After Cucuto is updated, the module that `system_server` loaded at boot keeps
  running the old code until a reboot. LSPosed stops delivering Remote Preferences changes to it (ADR 0024), and
  may not hand the framework service to the new app process at all (ADR 0043). Routing therefore also travels
  through the remote file plus a nudge, and through a pull from the app's provider (ADR 0048). Any change to the
  module's side of the contract must keep working with a module from an older release until the next reboot.
- **Routing table size.** Remote Preferences travel through Binder and hold at most `MAX_PREFERENCES_CHARS`
  (256 Ki characters). The full table, up to `MAX_TABLE_BYTES` (1 MiB), goes through the file and the provider, and
  the module reads at most `RoutingFile.MAX_BYTES` (all in `:core:routing`). Over the limit, clip envelopes are
  dropped first (ADR 0054). If you add fields to the table, run `HeavyUserTest` (`:xposed`) and check the headroom.
- **Scope.** `scope.list` says `system`, not `android` (ADR 0002). The WhatsApp scope is only for the opt-in call
  ringtone feature (ADR 0051). Anything else that wants it needs a new ADR and a `question` issue.
- **Media guard.** Audio, image and zip files live only in allowlisted paths. Never commit `*.soundpack.zip`
  (outside `spec/fixtures/`), backups or copyrighted media. The owner's private packs (K.K. Slider, Pokémon) stay
  out of the repo.
- **The website build** reads the newest app release and `packs-v*` release from the GitHub API. Without the
  pack-spec module it still builds, but CI sets `CUCUTO_REQUIRE_PACK_SPEC=1`.
- **Shared stash.** Worktrees share one git stash. Prefer a WIP commit to `git stash`.

## 11. What only the owner can do

`gh issue list --label owner-action` lists it: Google Play account and signing (#276), the Google Cloud project for
Drive sign-in (#188), supervised device runs that need a second phone or LSPosed changes (#143, #228), and checks
that need real incoming messages or a wiped phone (#12, #157, #43, #46). Leave these alone unless the owner is
present and says go. When your work creates a new one, label it `owner-action` and write the exact steps in the
issue.
