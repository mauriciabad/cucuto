# cucuto — agent guide

Cucuto gives each WhatsApp contact a voice: an LSPosed module in `system_server` swaps the
notification sound and vibration per contact, using clips from user-imported sound packs. The full spec is
[`docs/project-brief.md`](docs/project-brief.md). Read it before starting any non-trivial task; it is the source of truth.

## Ground rules

- **Name: "Cucuto" with a capital C in prose** (owner, 2026-10-23). Lowercase only in identifiers (`cucuto` repo,
  `app.mauri.cucuto`, `cucuto.mauri.app`, file names, code) and in the logo wordmark.
- **Owner is away.** Never block on questions. Pick a sensible default, document it, and open (or comment on) a GitHub
  issue labeled `question` so the owner can override it later.
- **Kotlin always.** All Android, tooling and scripting code is Kotlin (Gradle Kotlin DSL; `*.main.kts` scripts instead of
  Python or shell where practical). No Java sources. The website is SvelteKit + TypeScript (owner decision in the brief).
- **Latest stable versions.** Check current versions when adding a dependency (official release notes, Maven Central,
  npm). Record them in `android/gradle/libs.versions.toml` or `package.json`. Betas only where the brief allows
  (Compose Material 3 1.5.0 for Expressive).
- **Read the official guidelines first.** developer.android.com (Compose, haptics, Android 17 behavior changes),
  m3.material.io (Material 3 Expressive), libxposed API docs, svelte.dev. Prefer documented APIs and best practices
  over memory; cite the doc in the PR when a decision depends on it.
- **Never commit copyrighted media.** Only CC0 demo content in `packs/`, brand assets in `brand/`, app resources and
  website assets. CI (`.github/workflows/media-guard.yml`) enforces an allowlist. No `.soundpack` files outside build output.
- **Never hook the WhatsApp process.** The module is scoped to System Framework only (`system` in
  `META-INF/xposed/scope.list`, which is `system_server` for modern modules; `android` would be wrong, see ADR 0002). Never throw inside
  `system_server`; every hook catches everything and degrades to WhatsApp's default tone.

## Repository layout

```
android/        Gradle project (:app, :xposed, :core:*) — Kotlin, Compose, Material 3 Expressive
web/            SvelteKit static site + PWA pack creator
tools/packer/   Kotlin JVM CLI: build packs, bundles, repository indexes
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
  (`feat(pack): …`, `fix(xposed): …`, `docs: …`, `ci: …`), PR with `Closes #N`. Squash-merge once CI is green; the owner does not review PRs, so the lead agent reviews (read the diff, check for copyrighted media and private data) and merges.
- Milestones M0–M9 mirror the brief. Labels: `area:*`, `type:*`, `question`, `blocked`, `needs-device`.
- Architecture decisions go in `docs/decisions/` (short ADRs: context, decision, consequences).
- Keep PRs focused; run the relevant build and tests before pushing.

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
- Web: `cd web && pnpm install && pnpm build` · Packer: `cd tools/packer && ./gradlew build`
- Device: owner's rooted Android 17 phone over adb (when connected). Device-only work is labeled `needs-device`.
  Installing and launching `app.mauri.cucuto` is fine (the module is inert until enabled); never enable it in
  LSPosed, change scopes or reboot without an explicit go-ahead.

## Owner log

Append every new owner prompt, message or answer **verbatim** to [`docs/owner-log.md`](docs/owner-log.md) (dated, with
context) in the same PR or commit as the work it causes. Never paraphrase or delete entries.
