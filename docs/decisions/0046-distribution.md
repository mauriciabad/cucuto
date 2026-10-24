# 0046. Distribution: store metadata, release notes, Obtainium and reproducible builds

- Status: accepted (submissions are the owner's: #211)
- Date: 2026-10-24
- Issues: #206 (this work), #44 / ADR 0021 (signed release on tag), #41 / ADR 0032 (screenshot tests)

## Context

v0.1.1 and v0.1.2 are on GitHub Releases, signed with the owner's key (ADR 0021). Their notes were GitHub's generated
list of PR titles ("let the analyzer report import progress from its own dispatcher"), which says nothing to users,
and nothing told users that v0.1.1 could not import any pack. M7 makes the first public releases, and the brief rules
out the Play Store, so people install from GitHub and update with their own tools. Under minimum intrusion Cucuto gets
no update checker and no network calls for updates.

Sources read: F-Droid's [descriptions, graphics and
screenshots](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/) (fastlane layout, limits 50 /
80 / 4000 / 500 characters, changelogs by versionCode, fallback to en-US); the [IzzyOnDroid App Inclusion
Policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/); Obtainium's [deep links](https://wiki.obtainium.imranr.dev/deep_links/),
its source settings (`lib/app_sources/github.dart`) and certificate parsing (`lib/utils/signing_cert_utils.dart`);
[Reproducible Builds](https://reproducible-builds.org/docs/) and F-Droid's
[reproducible builds](https://f-droid.org/docs/Reproducible_Builds/) docs (apksigcopier).

## Decision

**Fastlane metadata in `fastlane/metadata/android/{en-US,es-ES}/`**, the layout F-Droid, IzzyOnDroid and Obtainium
clients read: title, short and full descriptions in the website's voice, root and LSPosed stated in the first lines;
changelogs per versionCode; the brand icon; six light-theme screenshots per language rendered by the Roborazzi tests at
822 × 1782 px (`./gradlew :app:exportStoreScreenshots`, a `store` position on the test, beside the website's `web`
export). CI checks the length limits (`store-metadata` job) and the media guard allows PNGs under `images/` only.

**Release notes from Conventional Commits** with `tools/release/release-notes.main.kts` (Kotlin, runs on the CI
runner's Kotlin CLI). One run reads the commits since the previous `v*` tag, keeps `feat` (New), `fix` and `perf`
(Fixes) and `docs` (Docs), drops internal scopes (`web`, `ci`, `deps`, `owner-log`, `release`, `screenshots`, …), and
writes three things that therefore agree: the store changelog `en-US/changelogs/<versionCode>.txt` (500 characters
at most, whole lines, link to the release), the `CHANGELOG.md` section, and with `--notes` the Markdown body of the
GitHub release. A `Release note: <words>` line in a PR description (squash merges keep the PR body) replaces the
title for users; `Release note: skip` hides it. Existing files are kept, so hand edits win.

The release PR runs it before tagging, because F-Droid and IzzyOnDroid read the changelog from the tagged source and
`GITHUB_TOKEN` can neither push to `main` (ruleset) nor open PRs (the repository doesn't allow Actions to). In
`release.yml` the same script writes the notes in the run (replacing `--generate-notes`); if the tag lacks the
changelog, the run writes it too and puts the patch in the job summary with a warning, so the metadata can still be
completed. The signing and verification steps are unchanged.

**Obtainium, not an update checker.** The website's Install page (en, es) has a "Get it with Obtainium" button with a
bare `obtainium://app/<config>` link (no redirect page: it loads analytics and ads) and the same settings for manual
setup: APK filter `^cucuto-\d+\.\d+\.\d+\.apk$`, release title filter `^Cucuto ` (skips the demo pack releases) and
the release certificate as Obtainium's expected signing hash, so a replaced key is refused. Asset names, release
titles, tags and the key stay stable.

**IzzyOnDroid and F-Droid: documented, not submitted.** Cucuto meets IzzyOnDroid's technical rules, but its policy
refuses "apps for Facebook, WhatsApp & Co" and "vibe-coded apps", so a request would likely fail; F-Droid is
possible but costly and would carry `NonFreeDep`. Both are owner decisions (#211; default: don't submit for v1).

**Reproducible builds, checked on every release.** Rebuilding v0.1.2 on a Mac matched every file in the published APK
except two native libraries: the Rust library embedded `CARGO_HOME` paths in panic messages, and AGP stripped an AAR's
library with whatever NDK the machine had (`.shstrtab` layout differs between `llvm-strip` versions). Fixes:
`CargoNdkTask` remaps `CARGO_HOME` and the crate directory with `--remap-path-prefix`, and the application convention
pins `ndkVersion` to the same NDK as `:core:jxl`. A `reproducible` job in `release.yml` rebuilds the tag unsigned on a
fresh runner (another directory and `CARGO_HOME`, no caches) after publishing and compares every file in the APK; it
costs one release build (about 5 minutes) per release and never blocks one. Details:
[reproducible-builds.md](../reproducible-builds.md).

## Consequences

- Releasing gains one step before the tag: run the script, edit the words, merge `chore(release): X.Y.Z`
  (docs/releasing.md). Forgetting it still produces good release notes, plus a warning and a patch to commit.
- Release notes read as user-facing changes; PR titles matter more, and `Release note:` lines fix the rest.
- Spanish changelogs are hand-written and may lag; clients fall back to English.
- Screenshots need re-exporting after UI changes; nothing checks they are current (the goldens are checked).
- The certificate is pinned in three places: the repository variable, `web/src/lib/site.ts` and every Obtainium install
  made from the link. A key change (ADR 0021) must update the site too.
- A failing `reproducible` job turns a release run red after publishing; the APK stays. Cross-OS reproducibility is
  checked by hand only.
