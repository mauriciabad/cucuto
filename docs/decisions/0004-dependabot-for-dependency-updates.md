# 0004. Dependabot for dependency updates

- Status: accepted (owner may switch to Renovate, see the `question` issue linked from the PR)
- Date: 2026-10-23
- Issue: #2

## Context

Issue #2 asks for automated dependency updates with Renovate or Dependabot, grouping Android, Gradle and npm
updates. The owner is away and cannot install GitHub Apps.

## Decision

Use **Dependabot** (`.github/dependabot.yml`), weekly for every ecosystem: Gradle for `android/` (version catalog,
plugins and the Gradle wrapper) grouped as Kotlin + AGP, AndroidX + Compose, and everything else; Gradle for
`tools/packer` and `shared/pack-spec`; Cargo for the jxl-rs crate; GitHub Actions; and JavaScript with the `bun`
ecosystem (#115) for `web/` and `brand/tools`. Groups take patch and minor updates only (see "Auto-merge" below).

Renovate would group more precisely and understands more files (for example `gradle-daemon-jvm.properties`), but it
needs the Renovate GitHub App installed on the repository, which only the owner can do. Dependabot works from the
config file alone.

## Consequences

- Update PRs start arriving without owner action, labeled `type:chore` plus an `area:*` label.
- Pre-releases are proposed only for dependencies already on one (Material 3 1.5.0 beta).
- Switching to Renovate later is a config swap plus installing the app.

## Owner answer (2026-10-23, #68)

> I dont care, just consider that when i'm happy with the featureset and it works i will probably abandon the code. so whatever you pick has to be something that when in the future i spawn a agent to do some task it also takes the spawn oportunity to update dependencies if it is not much work in paralel.

Dependabot stays (no app to install, nothing to maintain). Added on top:

- **Agents update dependencies opportunistically.** Every agent, whatever its task, checks for open Dependabot PRs and
  outdated versions in the area it touches and, when the update is cheap, lands it in parallel in a separate PR; costly
  majors get a `type:chore` issue instead. The rule is in `CLAUDE.md` (Workflow, "Dependencies").
- **Less to land by hand:** auto-merge green patch and minor Dependabot PRs, and add the website to the config (#122).

## Auto-merge (2026-10-24, #122)

`.github/workflows/dependabot.yml` follows the GitHub docs pattern ("Automating Dependabot with GitHub Actions"):
`dependabot/fetch-metadata` reads the update type and `gh pr merge --auto --squash` enables auto-merge, so GitHub
merges once `ci-ok` is green and leaves the PR open when it is red.

- **Auto-merged:** patch and minor updates. Every group lists `update-types: [minor, patch]`, so a grouped PR never
  contains a major and one blocked major cannot hold back a whole group.
- **Left for an agent:** majors, and minors of the Cargo crates (jxl-rs and jni are 0.x; their API changes between
  minors and the decoder is only tested on a device). They arrive as single-dependency PRs labeled `deps:major`. The
  next agent lands them if cheap or opens a `type:chore` issue (`CLAUDE.md`, "Dependencies").
- **The `main` ruleset needs no change:** it requires only `ci-ok` and 0 approvals, and repository auto-merge is on.
  `require_extra_approval_for_unattributed_changes` is on (the API default), but it targets commits whose author is
  not linked to an account; Dependabot commits belong to `dependabot[bot]`. If a green Dependabot PR ever sits
  `BLOCKED`, that setting is the first suspect.
- **Main still gets a CI run:** a merge done by auto-merge that `GITHUB_TOKEN` enabled is pushed as `GITHUB_TOKEN`, and
  such pushes start no workflows, so `ci.yml` would neither verify `main` nor redeploy the site. The workflow's
  `main-ci` job waits for the merge (on `workflow_run` of `ci`) and dispatches `ci.yml` on `main` when the merge
  commit has no run. A GitHub App token would avoid this, but needs an app only the owner can install.
- Dependabot's default 3-day cooldown already delays brand-new releases before they can auto-merge.
