# 0004. Dependabot for dependency updates

- Status: accepted (owner may switch to Renovate, see the `question` issue linked from the PR)
- Date: 2026-10-23
- Issue: #2

## Context

Issue #2 asks for automated dependency updates with Renovate or Dependabot, grouping Android, Gradle and npm
updates. The owner is away and cannot install GitHub Apps.

## Decision

Use **Dependabot** (`.github/dependabot.yml`): weekly Gradle updates for `android/` (version catalog, plugins and
the Gradle wrapper) grouped as Kotlin, AndroidX + AGP, and everything else; weekly GitHub Actions updates in one
group; monthly npm updates for `brand/tools` (add `/web` when the site exists).

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
