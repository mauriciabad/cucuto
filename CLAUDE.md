# Cucuto — agent guide

Cucuto gives each WhatsApp contact a voice: an LSPosed module in `system_server` swaps the
notification sound and vibration per contact, using clips from user-imported sound packs. The full spec is
[`docs/project-brief.md`](docs/project-brief.md). Read it before starting any non-trivial task; it is the source of truth.

## Ground rules

- **Name: "Cucuto" with a capital C in prose** (owner, 2026-10-23). Lowercase only in identifiers (`cucuto` repo,
  `app.mauri.cucuto`, `cucuto.mauri.app`, file names, code) and in the logo wordmark.
- **Owner is away.** Never block on questions. Pick a sensible default, document it, and open (or comment on) a GitHub
  issue labeled `question` so the owner can override it later.
- **`question` issues:** each states the default taken. The owner **closes** it to accept the default, or **comments and
  leaves it open** to change it; the lead then applies the answer, copies it verbatim into the owner log and closes the
  issue. Check open `question` issues for new owner comments at the start of every session.
- **Kotlin always.** All Android, tooling and scripting code is Kotlin (Gradle Kotlin DSL; `*.main.kts` scripts instead of
  Python or shell where practical). No Java sources. The website is SvelteKit + TypeScript (owner decision in the brief),
  but it reuses Kotlin where that saves a second implementation: pack validation comes from `shared/pack-spec`
  compiled for the browser, never a TypeScript port (owner, #60; #116). JavaScript tooling runs on Bun, not Node
  (owner, #63; migration in #115, until it lands the commands below still say `pnpm`).
- **Minimum intrusion** (owner, #67): "I want it to work with the minimum intrusion possible, so we lower the risk of
  critical vulnerabilities/mistakes." Hook as little as possible, as late as possible, in as few processes as
  possible; ask for the fewest permissions, only when a feature needs them; read no more of a notification than the
  feature uses. Backwards compatibility is not a goal: no compatibility code for older Android, LSPosed or
  libxposed versions, no legacy fallbacks. When two designs work, pick the less intrusive one.
- **Latest stable versions.** Check current versions when adding a dependency (official release notes, Maven Central,
  npm). Record them in `android/gradle/libs.versions.toml` or `package.json`. Betas only where the brief allows
  (Compose Material 3 1.5.0 for Expressive).
- **Read the official guidelines first.** developer.android.com (Compose, haptics, Android 17 behavior changes),
  m3.material.io (Material 3 Expressive), libxposed API docs, svelte.dev. Prefer documented APIs and best practices
  over memory; cite the doc in the PR when a decision depends on it.
- **Never commit copyrighted media.** Only CC0 demo content in `packs/`, brand assets in `brand/`, app resources and
  website assets. CI (the `media-guard` job in `.github/workflows/ci.yml`) enforces an allowlist. No pack (`*.soundpack.zip`) or backup (`cucuto-backup-*.zip`) files outside build output and `spec/fixtures/`.
- **Never hook the WhatsApp process.** The module is scoped to System Framework only (`system` in
  `META-INF/xposed/scope.list`, which is `system_server` for modern modules; `android` would be wrong, see ADR 0002). Never throw inside
  `system_server`; every hook catches everything and degrades to WhatsApp's default tone.

## Repository layout

```
android/        Gradle project (:app, :xposed, :core:*) — Kotlin, Compose, Material 3 Expressive
web/            SvelteKit static site + PWA pack creator
tools/packer/   Kotlin JVM CLI: build packs, bundles, repository indexes
shared/         pack-spec: the pack format rules (Kotlin Multiplatform), included by tools/packer and android/
spec/           JSON Schemas (pack, bundle, repository, backup) + golden fixtures (the cross-platform contract)
packs/          CC0 demo pack sources
brand/          Logo, app icon, colors, typography, motion and sound language
docs/           Brief, pack format, install guide, decisions (docs/decisions/NNNN-title.md)
```

## Identifiers

GitHub `mauriciabad/cucuto` · application ID `app.mauri.cucuto` · site `https://cucuto.mauri.app` ·
pack files `*.soundpack.zip` · backups `cucuto-backup-YYYY-MM-DD.zip` (always end in `.zip`; type detected from contents) · license GPL-3.0-or-later (demo packs CC0-1.0).

## Workflow

- **Everything an agent writes on GitHub must say it is AI, not the owner.** Issues, PRs, comments, reviews and
  releases start with this banner:

  ```markdown
  > [!NOTE]
  > 🤖 Written by an AI agent (Claude), not by the repository owner.
  ```

  Commits are authored as `Claude (AI agent) <noreply@anthropic.com>` (set with `git config user.name/user.email` in
  each clone or worktree) and keep the `Co-Authored-By` trailer.
- Work from GitHub issues. Branch `<type>/<issue>-<slug>` (e.g. `feat/12-pack-importer`), Conventional Commits
  (`feat(pack): …`, `fix(xposed): …`, `docs: …`, `ci: …`), PR with `Closes #N`.
- **Auto-merge, don't wait for CI.** Right after `gh pr create`, run `gh pr merge --auto --squash` and finish your
  task: do not watch or wait for CI. GitHub squash-merges the PR once the required `ci-ok` check is green (and deletes
  the branch). If CI fails, the PR stays open and the lead resumes the agent to fix it. The owner does not review
  PRs; the lead agent reviews them (read the diff, check for copyrighted media and private data) and can disable
  auto-merge (`gh pr merge --disable-auto`) to hold one.
- **CI** is one workflow, `.github/workflows/ci.yml`: a `changes` job (path filter) picks which jobs run, and `ci-ok`
  (always runs, green when every needed job passed or was skipped) is the only required check of the `main` ruleset
  (PRs required, 0 approvals, no force pushes or deletions; repository admins can bypass for hotfixes; branches need
  not be up to date). Never rename `ci-ok`; add new jobs to its `needs`.
- Milestones M0–M9 mirror the brief. Labels: `area:*`, `type:*`, `question`, `blocked`, `needs-device`.
- Architecture decisions go in `docs/decisions/` (short ADRs: context, decision, consequences).
- Keep PRs focused; run the relevant build and tests before pushing.
- **Dependencies: update them whenever you are spawned** (owner, #68: the owner expects to stop maintaining the code
  once the feature set works, so every agent session is a maintenance opportunity). At the start of any task, check
  for open Dependabot PRs and outdated versions in the area you touch (`android/gradle/libs.versions.toml`,
  `package.json` files, GitHub Actions, the Gradle wrapper). If updating is cheap (no API migration, builds and tests
  stay green), do it **in parallel, in a separate PR** (`build(deps): …`, its own branch), or merge the green
  Dependabot PR. If it is not cheap (a major version with breaking changes), open or update a `type:chore` issue with
  what blocks it and carry on with your task. Never mix dependency bumps into a feature PR. Dependabot keeps
  proposing updates (ADR 0004).

## Builds

- JDK 21 (Homebrew `openjdk@21`): `export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home`
  (needed to launch the wrapper; the Gradle daemon and all modules use the JDK 21 toolchain, see
  `android/gradle/gradle-daemon-jvm.properties`). CI uses Temurin 21.
- Android SDK at `~/Library/Android/sdk` (platforms `android-37.0` and `android-37.2`, build-tools 37.0.0). The
  `android` CLI is installed (`android sdk install platforms/android-37.2`).
- Android: `cd android && ./gradlew build` (debug + release APKs, Lint, ktlint, unit tests) ·
  `./gradlew spotlessApply` fixes formatting · versions live in `android/gradle/libs.versions.toml`, module setup in
  `android/build-logic/` (ADR 0001). `compileSdk` is 37.2: `VibrationEffect.Builder` and friends are missing from
  37.0. AGP 9 has built-in Kotlin: never apply `org.jetbrains.kotlin.android` (or kapt; use KSP).
- Web: `cd web && pnpm install && pnpm build` · Packer: `cd tools/packer && ./gradlew build installDist` (tests run
  every golden fixture in `spec/fixtures`; CLI at `tools/packer/cli/build/install/packer/bin/packer`, usage in
  `tools/packer/README.md`). Pack format rules: `docs/pack-format.md` (code: `shared/pack-spec`, also run by
  `android/core/pack` tests); error codes: `spec/error-codes.json`.
- Kotlin scripts (`*.main.kts`) need the `kotlin` CLI (Homebrew `kotlin`, 2.4.21). Media tools: `ffmpeg` (libopus),
  `cjxl`/`djxl` (`jpeg-xl`), `cwebp` (`webp`), `rsvg-convert` (`librsvg`). Regenerate fixtures with
  `kotlin spec/fixtures/generate.main.kts` and demo pack sounds with `kotlin packs/generate.main.kts`.
- Device: owner's rooted Android 17 phone over adb (when connected). Device-only work is labeled `needs-device`.
  Installing and launching `app.mauri.cucuto` is fine (the module is inert until enabled); never enable it in
  LSPosed, change scopes or reboot without an explicit go-ahead.

## Owner log

Append every new owner prompt, message or answer **verbatim** to [`docs/owner-log.md`](docs/owner-log.md) (dated, with
context) in the same PR or commit as the work it causes. Never paraphrase or delete entries.
