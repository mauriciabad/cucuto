# 0046. Distribution: store metadata, release notes, Obtainium and reproducible builds

- Status: accepted; amended by the owner's answer on #211 (Google Play added, F-Droid after v1, see
  [Owner update](#owner-update-2026-10-24-211-google-play-too-f-droid-after-v1)); the Play
  bundle, listing format and Console answers are ADR 0071. **Amended 2026-10-24 by [ADR 0080](0080-no-google-play.md):
  no Google Play** (the owner dropped it over the closed testing requirement); F-Droid recipe ready ([update](#update-2026-10-24-275-f-droid-recipe-ready), #275)
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

## Owner update 2026-10-24 (#211): Google Play too, F-Droid after v1

> **Amended the same day ([ADR 0080](0080-no-google-play.md)):** the owner decided against Google Play ("the closed
> testing requirement is too hard to meet"). The Play decisions below are history; F-Droid after v1 stands.
> Developer verification goes through the Android Developer Console instead (#358).

> Google Play Store is not an option? i thought we would publish it there too. I'll make a developer account if necesary
> f-droid would be nice, we can do it after v1.

**Google Play becomes an extra channel**, replacing the brief's "no Play Store" non-goal. The assessment
([distribution.md → Google Play](../distribution.md#google-play)) found no policy that rules Cucuto out. Needing
root, being an LSPosed module and AI-written code are not grounds for refusal; the AI-generated content policy
covers apps that generate content. Three review risks remain:

- the Device and Network Abuse policy ("interfere with … other apps on the device"), mostly because of the optional
  hook inside WhatsApp (ADR 0052);
- reviewers can't see the app work without root;
- the WhatsApp trademark.

Decisions:

- **One signing identity.** Play App Signing uses Cucuto's existing release key (uploaded with PEPK), and CI signs
  bundles with ~~a separate upload key~~ the same release key (owner updates 2026-10-24, #287, ADR 0071). Play,
  GitHub, Obtainium and F-Droid installs can update each other, and the Obtainium pin keeps working.
- **The listing never leads with WhatsApp.** The name stays Cucuto. "WhatsApp" stays out of the title, icon,
  developer name and feature graphic, and out of the first words of the short description. The full description
  says it works with WhatsApp's notifications and isn't affiliated with WhatsApp or Meta (Meta's brand guidelines,
  Play's IP policy). The fastlane metadata changes for every store, so every listing says the same.
- **No Play-only build.** One APK and one bundle from the same source. A Play flavor without the optional WhatsApp
  hook is the fallback only if review demands it.
- **GitHub Releases and Obtainium stay the main channel.** A rejection or a removal on Play changes nothing for them.
- **Android developer verification** (worldwide from 2027, including APKs installed from outside Play) is handled by
  the same Play Console account, which registers `app.mauri.cucuto` and the release certificate.
- Work: agent side #274 (bundle, listing, review notes, Data safety answers; built in ADR 0071, with an owner-run
  `fastlane supply` config instead of an upload from CI),
  owner side #276 (account, signing, closed test with 12 testers for 14 days, submission).

**F-Droid after v1** (#275), through F-Droid's reproducible-build route, so it ships the APK signed with Cucuto's
key. **IzzyOnDroid** stays not submitted, and the optional Obtainium directory listing stays optional (the
defaults in #211 the owner didn't change).

## Update 2026-10-24 (#275): F-Droid recipe ready

The recipe is in [`fdroid/`](../../fdroid/README.md), ready for the owner to submit after v1. It builds the tag with
JDK 21, the pinned Rust toolchain through rustup, cargo-ndk and NDK r30, and uses `Binaries:` with
`AllowedAPKSigningKeys:` so F-Droid publishes the GitHub APK's signature on its own build. It declared `NonFreeDep`
(F-Droid's anti-feature for apps that need a non-free app, WhatsApp being their example) until Signal and Molly
support made WhatsApp optional (#374, [ADR 0081](0081-signal-support.md)); the recipe now declares no anti-feature. It has no inclusion
blockers: no Play services, Firebase, trackers or proprietary libraries in the release APK.

For F-Droid's update check, a tag's source must name its version, so the release PR now also sets `versionCode` and
`versionName` in `android/app/build.gradle.kts` (`release-notes.main.kts` writes them, `release.yml` warns when they
don't match the tag). Releases still pass `-Pcucuto.version`, so the APK is unchanged.
