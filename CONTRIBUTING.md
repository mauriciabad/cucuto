# Contributing to Cucuto

Thanks for helping give each contact a voice. This guide covers how the repository is organized, how to build each part
and the rules every change follows. The [project brief](docs/project-brief.md) is the source of truth for scope and
design; read it before starting anything non-trivial.

By contributing you agree that your work is licensed under [GPL-3.0-or-later](LICENSE) (demo packs under
[CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/)) and that you follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Repository layout

Cucuto is one monorepo:

| Path | What | Stack |
| --- | --- | --- |
| [`android/`](android) | Gradle project: `:app`, `:xposed` and `:core:*` modules | Kotlin, Jetpack Compose, Material 3 Expressive |
| [`web/`](web) | Static website, default pack repository and PWA pack creator | SvelteKit, TypeScript, Bun |
| [`tools/packer/`](tools/packer) | CLI that builds packs, bundles and repository indexes | Kotlin JVM |
| [`shared/pack-spec/`](shared/pack-spec) | The pack format rules, included by `android/` and `tools/packer/` | Kotlin Multiplatform |
| [`spec/`](spec) | JSON Schemas (pack, bundle, repository, backup), error codes and golden fixtures: the cross-platform contract | JSON Schema |
| [`packs/`](packs) | CC0 demo pack sources | |
| [`brand/`](brand) | Logo, app icon, colors, typography, motion and sound language | |
| [`docs/`](docs) | Brief, pack format, install guide, device testing, decisions (`docs/decisions/NNNN-title.md`) | Markdown |
| [`spikes/`](spikes) | Prototypes behind some decisions; not part of the product | |

## Ground rules

- **Kotlin only.** All Android, tooling and scripting code is Kotlin: Gradle Kotlin DSL, and `*.main.kts` scripts instead
  of Python or shell where practical. No Java sources. The website is the one exception (SvelteKit + TypeScript).
- **Latest stable versions.** When you add a dependency, check its current release and record it in
  `android/gradle/libs.versions.toml` or `package.json`.
- **Follow the official guidelines.** developer.android.com, m3.material.io, the libxposed API docs and svelte.dev. Cite
  the doc in your PR when a decision depends on it.
- **Never hook the WhatsApp process.** The module is scoped to System Framework only (`system` in
  `META-INF/xposed/scope.list`, see [ADR 0002](docs/decisions/0002-xposed-scope-system.md)). Code in `system_server`
  never throws: every hook catches everything and falls back to WhatsApp's default tone.
- **Architecture decisions** go in `docs/decisions/` as short ADRs (context, decision, consequences).

## Packs and media

Cucuto ships no content. The public repository never contains copyrighted media.

- Audio, image and zip files may only live in the paths allowed by the `media-guard` job in [`ci`](.github/workflows/ci.yml):
  `packs/`, `brand/`, `spec/fixtures/`, Android `res/` and `assets/`, `web/static/`, `web/src/lib/assets/`, `docs/images/`
  and the packer's test resources. CI fails otherwise.
- Demo packs in `packs/` must be **CC0-1.0**, and you must be able to show where every file came from (record the source
  in the pack's README or manifest). If in doubt, leave it out.
- Never commit built `.soundpack.zip` files (only golden fixtures in `spec/fixtures/`) or backups (`cucuto-backup-*.zip`). Keep
  private experiments in `private/`, which is ignored.
- Screenshots, videos and website previews use demo packs only.
- Changes to the pack, bundle, repository or backup format start with a
  [pack format proposal](https://github.com/mauriciabad/cucuto/issues/new?template=pack_format_proposal.yml) and update
  the schemas in `spec/`, the fixtures and `docs/pack-format.md` together.

## Building

Requirements:

- **JDK 21** (Homebrew: `brew install openjdk@21`, then
  `export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home`).
- **Android SDK** with platform `android-37.2` and build-tools 37.0.0. Point Gradle at it with `ANDROID_HOME` or with
  `sdk.dir` in `android/local.properties` (not committed).
- **Rust and NDK r30** for `:core:jxl`, the JPEG XL decoder: `brew install rustup && rustup default stable`,
  `cargo install cargo-ndk` and `android sdk install ndk/30.0.16248370` (or `sdkmanager "ndk;30.0.16248370"`). rustup fetches the pinned toolchain by itself.
  Only APK packaging needs them; unit tests and Lint do not ([details](android/core/jxl/README.md#build)).
- **[Bun](https://bun.com)** for the website and the brand tools (`brew install oven-sh/bun/bun`; the version is
  `packageManager` in `web/package.json`). Node is not needed.
- Optional: the `kotlin` CLI for `*.main.kts` scripts, and `ffmpeg`, `jpeg-xl`, `webp` and `librsvg` for media work.

```sh
# Android app, Xposed module and :core:* (debug + release APKs, Lint, ktlint, unit tests)
cd android
./gradlew build
./gradlew spotlessApply            # fix formatting

# Website and PWA pack creator
cd web
bun install
bun run check                      # svelte-check
bun run lint                       # Prettier + ESLint
bun run build                      # static site in web/build/
bun run test                       # Playwright smoke and axe tests (bun run playwright install chromium first)

# Packer CLI (its tests run every golden fixture in spec/fixtures)
cd tools/packer
./gradlew build installDist
cli/build/install/packer/bin/packer --help

# The pack format rules on their own
cd shared/pack-spec
./gradlew build

# From the repository root: regenerate the golden fixtures and the demo pack sounds (both reproducible)
kotlin spec/fixtures/generate.main.kts
kotlin packs/generate.main.kts
```

Each part has its own README with more: [`web/`](web/README.md), [`tools/packer/`](tools/packer/README.md),
[`packs/`](packs/README.md), [`brand/`](brand/README.md).

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs only the jobs whose paths changed: `android-assemble`,
`android-test`, `android-lint`, `web`, `packs` and `media-guard`. The `cucuto-debug-apk` artifact is the debug APK.

### Testing on a device

The module needs a rooted Android 17 phone with LSPosed (libxposed API 102). Install a debug build as in the
[install guide](docs/install.md#before-the-first-release), enable Cucuto in LSPosed with the System Framework scope and
reboot. Learn the [recovery steps](docs/install.md#if-the-phone-does-not-boot) first: a broken module in
`system_server` can stop the phone from booting. [`docs/device-testing.md`](docs/device-testing.md) is the full test
plan, with the log lines to look for. Work that can only be verified on a device is labeled `needs-device`.

Never post the Diagnostics decision list, or logs with chat keys, names or numbers, on GitHub.

## Docs and the website

Setup text lives in three places that must agree: the app's strings, the website's Install page (English in
`web/src/lib/i18n/en.ts`, Spanish in `es.ts`) and [`docs/install.md`](docs/install.md). When you change a setup step,
update all three. Write "Cucuto" with a capital C in prose.

## Workflow

1. **Start from an issue.** Pick one or open one with the right
   [template](https://github.com/mauriciabad/cucuto/issues/new/choose). Comment before starting large work.
2. **Branch** as `<type>/<issue>-<slug>`, for example `feat/12-pack-importer` or `fix/31-zip-slip`.
3. **Commit** with [Conventional Commits](https://www.conventionalcommits.org/): `feat(pack): …`, `fix(xposed): …`,
   `docs: …`, `ci: …`, `chore: …`. Use the area as the scope when it helps.
4. **Open a pull request** with `Closes #N`, fill in the template, and keep it focused. Run the relevant builds and tests
   before pushing.
5. **Squash-merge.** The PR title becomes the commit title on `main`, so it must be a Conventional Commit too.
   `main` is protected by a ruleset: changes land through pull requests only, and the single required status check is
   `ci-ok` (the last job of `.github/workflows/ci.yml`, green when every needed job passed or was skipped). Enable
   auto-merge (`gh pr merge --auto --squash`) and GitHub merges once `ci-ok` is green.

Labels: `area:*` (which part), `type:*` (bug, feature, spike, chore, docs), `question`, `blocked`, `needs-device`,
`good first issue` and `help wanted`.

## AI-agent contributions

AI agents are welcome contributors here, and much of the project is built by them. Their work must always be labeled as
AI-written so nobody mistakes it for a human's:

- Issues, pull requests, comments, reviews and releases written by an agent start with this banner (the issue and pull
  request templates repeat it):

  ```markdown
  > [!NOTE]
  > 🤖 Written by an AI agent (Claude), not by the repository owner.
  ```

  Agents other than Claude name themselves in the banner.
- Agent commits are authored with an AI identity (for Claude: `Claude (AI agent) <noreply@anthropic.com>`) and keep the
  tool's `Co-Authored-By` trailer.
- Humans who use AI assistance for a substantial part of a change say so in the PR description.
- Agents follow [`CLAUDE.md`](CLAUDE.md): never block on questions (decide, document, and open a `question` issue), and
  append any owner instruction verbatim to [`docs/owner-log.md`](docs/owner-log.md) in the same PR.

## Security

Do not report vulnerabilities in public issues. See [SECURITY.md](SECURITY.md).
