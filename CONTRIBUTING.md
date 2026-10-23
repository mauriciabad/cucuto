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
| [`web/`](web) | Static website, default pack repository and PWA pack creator | SvelteKit, TypeScript |
| [`tools/packer/`](tools/packer) | CLI that builds packs, bundles and repository indexes | Kotlin JVM |
| [`spec/`](spec) | JSON Schemas (pack, bundle, repository, backup) and golden fixtures: the cross-platform contract | JSON Schema |
| [`packs/`](packs) | CC0 demo pack sources | |
| [`brand/`](brand) | Logo, app icon, colors, typography, motion and sound language | |
| [`docs/`](docs) | Brief, pack format, install guide, decisions (`docs/decisions/NNNN-title.md`) | Markdown |

Some folders appear as the milestones land; check the brief for the plan.

## Ground rules

- **Kotlin only.** All Android, tooling and scripting code is Kotlin: Gradle Kotlin DSL, and `*.main.kts` scripts instead
  of Python or shell where practical. No Java sources. The website is the one exception (SvelteKit + TypeScript).
- **Latest stable versions.** When you add a dependency, check its current release and record it in
  `android/gradle/libs.versions.toml` or `package.json`.
- **Follow the official guidelines.** developer.android.com, m3.material.io, the libxposed API docs and svelte.dev. Cite
  the doc in your PR when a decision depends on it.
- **Never hook the WhatsApp process.** The module is scoped to System Framework (`android`) only. Code in `system_server`
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
  private experiments in `private/`, which is git-ignored.
- Screenshots, videos and website previews use demo packs only.
- Changes to the pack, bundle, repository or backup format start with a
  [pack format proposal](https://github.com/mauriciabad/cucuto/issues/new?template=pack_format_proposal.yml) and update
  the schemas in `spec/`, the fixtures and `docs/pack-format.md` together.

## Building

Requirements: JDK 21, the Android SDK (platform `android-37.0`, build-tools 37) and, for the website, Node.js with pnpm.

```sh
# Android app and Xposed module
cd android && ./gradlew build

# Website and PWA pack creator
cd web && pnpm install && pnpm build

# Packer CLI
cd tools/packer && ./gradlew build
```

On macOS with Homebrew: `export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home`.

Running the module needs a rooted Android 17 device with LSPosed (libxposed API 102): enable Cucuto in LSPosed with the
System Framework scope and reboot. Work that can only be verified on a device is labeled `needs-device`.

## Workflow

1. **Start from an issue.** Pick one or open one with the right template. Comment before starting large work.
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

- Issues, pull requests, comments, reviews and releases written by an agent start with this banner:

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
