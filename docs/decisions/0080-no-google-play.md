# 0080. No Google Play

- Status: accepted (owner decision, 2026-10-24). Supersedes ADR 0071 and the Google Play part of ADR 0046's owner
  update.
- Date: 2026-10-24
- Issues: #276 and #286 (closed, not planned), #211 and #274 (the earlier Play decision), #275 (F-Droid), #308
  (sample chat defaults), #358 (developer verification); ADR 0046 (distribution), ADR 0071 (Play bundle and
  listing), ADR 0075 (sample chat)

## Context

The owner had added Google Play as an extra channel (#211, ADR 0046), and the repository side was ready (ADR 0071):
a signed `.aab` on every release, Play checks in CI, a listing and drafted Console answers in `docs/google-play.md`,
and a `fastlane supply` config. Personal developer accounts created after November 2023 must run a
[closed test with at least 12 testers opted in for 14 days in a row](https://support.google.com/googleplay/android-developer/answer/14151465)
before they can apply for production. The owner decided:

> I decided i wont publish to the Play store because of the closed testing requirement is too hard to meet.

## Decision

**Cucuto is not published on Google Play.** The brief's "no Play Store" non-goal is back. Channels: GitHub
Releases (with the website's Download APK button and Obtainium), then F-Droid after v1 (#275).

The Play-only machinery is removed, so it doesn't rot or ask for secrets nobody sets:

- `release.yml` no longer builds, verifies or attaches `cucuto-<version>.aab`, and the `PLAY_UPLOAD_*` secrets and
  the `PLAY_UPLOAD_CERT_SHA256` variable are gone.
- CI no longer builds `:app:bundleRelease`. The checks that guard any release stay, renamed **Check release
  requirements**: 16 KB page alignment (Android 17 phones can use 16 KB pages), a target SDK floor and the permission
  allowlist (which keeps the privacy page honest). The bundle content check goes with the bundle.
- `docs/google-play.md` and `fastlane/Supplyfile` are deleted (git history keeps them). The `bundle {}` block in
  `android/app/build.gradle.kts` goes too.
- `fastlane/metadata/android/`, the feature graphic and the 9:16 store screenshots **stay**: F-Droid and IzzyOnDroid
  read the same layout.
- The sample chat (ADR 0075) **stays**. It was built for Play reviewers, but it also lets anyone without the module
  hear a character before setting up LSPosed.

**Android developer verification** (worldwide on certified phones from 2027, APKs from outside Play included) no
longer comes with a Play Console account. The owner registers through the free-standing
[Android Developer Console](https://developer.android.com/developer-verification/guides/android-developer-console):
**Register package name** `app.mauri.cucuto`, then **Add key** with the release certificate SHA-256
(`RELEASE_CERT_SHA256`). F-Droid ships Cucuto's own signature through reproducible builds (#275), so one key covers
every channel ([open-source app registration](https://developer.android.com/developer-verification/guides/open-source-app-registration)).
The owner's steps: #358.

## Consequences

- No developer fee, no 14-day closed test, no Play review and no account strikes. Nothing changes for GitHub,
  Obtainium or F-Droid users.
- Phones without root never find Cucuto in a store. They couldn't use it anyway.
- Without Play's cloud profiles, the Baseline Profile in the APK is the only startup optimization (ADR 0056, as
  before).
- Reversing this means restoring the ADR 0071 machinery from git history and running the closed test.
