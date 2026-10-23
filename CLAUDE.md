# cucuto — agent guide

cucuto (always lowercase) gives each WhatsApp contact a voice: an LSPosed module in `system_server` swaps the
notification sound and vibration per contact, using clips from user-imported sound packs. The full spec is
[`docs/project-brief.md`](docs/project-brief.md). Read it before starting any non-trivial task; it is the source of truth.

## Ground rules

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
- **Never hook the WhatsApp process.** The module is scoped to System Framework (`android`) only. Never throw inside
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
pack file extension `.soundpack` · backup `.cucuto-backup` · license GPL-3.0-or-later (demo packs CC0-1.0).

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
  (`feat(pack): …`, `fix(xposed): …`, `docs: …`, `ci: …`), PR with `Closes #N`. Squash-merge.
- Milestones M0–M9 mirror the brief. Labels: `area:*`, `type:*`, `question`, `blocked`, `needs-device`.
- Architecture decisions go in `docs/decisions/` (short ADRs: context, decision, consequences).
- Keep PRs focused; run the relevant build and tests before pushing.

## Builds

- JDK 21 (Homebrew `openjdk@21`): `export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home`
- Android SDK at `~/Library/Android/sdk` (platform `android-37.0`, build-tools 37). The `android` CLI is installed.
- Android: `cd android && ./gradlew build` · Web: `cd web && pnpm install && pnpm build` · Packer: `cd tools/packer && ./gradlew build`
- Device: owner's rooted Android 17 phone over adb (when connected). Device-only work is labeled `needs-device`.

## Owner log

Append every new owner prompt, message or answer **verbatim** to [`docs/owner-log.md`](docs/owner-log.md) (dated, with
context) in the same PR or commit as the work it causes. Never paraphrase or delete entries.
