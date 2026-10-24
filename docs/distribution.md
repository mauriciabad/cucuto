# Distribution

Where Cucuto's APK goes and what each channel needs. Decision: [ADR 0046](decisions/0046-distribution.md). How a
release is built and published: [releasing.md](releasing.md). Cucuto is **not on Google Play** (owner, 2026-10-24,
[ADR 0080](decisions/0080-no-google-play.md)): see [Google Play](#google-play).

Cucuto has **no update checker and makes no network calls to look for updates** (minimum intrusion). People update
with tools they already trust; every channel below reads the same GitHub release and the same store metadata.

| Channel | Status | What it reads |
| --- | --- | --- |
| [GitHub Releases](https://github.com/mauriciabad/cucuto/releases) | Live since v0.1.1 | `cucuto-<version>.apk`, `.sha256`, release notes |
| Website [Download APK](https://cucuto.mauri.app/install/) | Live | The latest release, at site build time |
| [Obtainium](https://github.com/ImranR98/Obtainium) | Ready: link and config on the Install page | GitHub releases (APK filter, title filter, certificate pin) |
| [IzzyOnDroid](https://apt.izzysoft.de/fdroid/) | Not submitted; likely refused today (see below) | GitHub releases + `fastlane/metadata/android/` |
| [Google Play](https://play.google.com) | Not published (owner decision, ADR 0080) | — |
| [F-Droid](https://f-droid.org) main repository | After v1 (#275) | Builds from source + `fastlane/metadata/android/` |

The submissions are owner decisions: #211 (answered: F-Droid after v1, IzzyOnDroid not submitted) and ADR 0080 (no
Google Play).

## Store metadata (`fastlane/metadata/android/`)

F-Droid, IzzyOnDroid and Obtainium-compatible clients read the [fastlane
layout](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/) from the repository. The limits
are the stricter of the stores, Google Play's included: they were set for it (ADR 0071) and cost nothing to keep, so
the listing stays ready if Play ever comes back:

```
fastlane/metadata/android/
├── en-US/
│   ├── title.txt                 # at most 30 characters (Play; F-Droid allows 50)
│   ├── short_description.txt     # at most 80
│   ├── full_description.txt      # at most 4000; plain text with line breaks, only <b> and <i>
│   ├── changelogs/<versionCode>.txt   # at most 500, plain text; one per release
│   └── images/
│       ├── icon.png              # 512 × 512, 32-bit, from brand/app-icon/cucuto-icon-512.png
│       ├── featureGraphic.png    # 1024 × 500, no alpha, from brand/tools/build-store.mjs
│       └── phoneScreenshots/<n>-<screen>.png   # 1080 × 1920, no alpha, at most 8
└── es-ES/                        # the same; falls back to en-US for anything missing
```

- **Descriptions** are written in the website's voice (brand/README.md) and say up front that Cucuto needs Android 17,
  root and LSPosed, and that it does nothing without them. Keep them in step with the website's Home and Install
  copy in both languages. Format: plain text, a blank line between paragraphs, `<b>` for headings, "• " bullets and
  bare URLs. Play renders only bold, italics and line breaks, and F-Droid turns line breaks into `<br>`. `<p>`,
  `<ul>` and `<a>` would show as raw tags on Play.
- **WhatsApp and Signal** (ADR 0046, ADR 0081): never in the title, the icon or the feature graphic, and not in the
  first words of the short description. The full description ends with "Not affiliated with WhatsApp, Meta or
  Signal." ("Sin relación con WhatsApp, Meta ni Signal.").
- **Screenshots** come from the Roborazzi screenshot tests (ADR 0032), light theme, with the CC0 demo packs and
  made-up chats only. The screen keeps its 822 × 1782 px and is centered on a 1080 × 1920 canvas in the brand's
  primary-90 tone (`StoreFrame`), because Play refuses screenshots taller than 2:1 and any alpha. Regenerate both
  languages after a UI change with `cd android && ./gradlew :app:exportStoreScreenshots`. A test joins the listing
  with `store = <position>` in its `screenshot(…)` call.
- **Feature graphic**: `cd brand/tools && bun build-store.mjs` draws it in English and Spanish (the clock, the
  wordmark and the website's pitch) and writes the 24-bit listing copies.
- **Changelogs** are written per release by `tools/release/release-notes.main.kts` (English); add the Spanish one by
  hand when there's time, otherwise clients show the English text. `versionCode` is
  `MAJOR * 1000000 + MINOR * 1000 + PATCH`, so `v0.2.0` is `2000.txt`. Play shows the same file as the release notes.
- CI's `store-metadata` job checks all of it (`kotlin tools/release/release-notes.main.kts --check`): lengths, tags,
  the WhatsApp rules and the disclaimer, and the images' sizes, ratios and alpha. The media guard allows PNGs only
  under `fastlane/metadata/android/<locale>/images/`.

## Obtainium

The website's Install page has a **Get it with Obtainium** button (`obtainium://app/<config>`, [deep link
format](https://wiki.obtainium.imranr.dev/deep_links/)) and the same settings for manual setup. The config lives in
`web/src/lib/obtainium.ts`:

- source `https://github.com/mauriciabad/cucuto`, no pre-releases;
- APK filter `^cucuto-\d+\.\d+\.\d+\.apk$`, which skips the `.sha256` file;
- release title filter `^Cucuto `, which skips the demo pack releases (`Demo packs 1`, no APK), with "fallback to
  older releases" on;
- expected signing certificate: the release certificate (`RELEASE_CERT_SHA256` in `web/src/lib/site.ts`, the same
  value as the repository variable that `release.yml` checks). Obtainium then refuses an APK signed by another key.

The button uses the bare `obtainium://` scheme, not the `apps.obtainium.imranr.dev/redirect` page, because that page
loads analytics and ads and the site promises no third-party requests. The Obtainium badge image is not used: its
license isn't stated. Listing Cucuto in Obtainium's [crowdsourced configs](https://apps.obtainium.imranr.dev) is
optional (a PR to that repository) and part of #211.

**Keep these stable:** the asset name `cucuto-<version>.apk`, the release title `Cucuto <version>`, the `v` tags and
the signing key. Changing any of them breaks existing Obtainium installs.

## IzzyOnDroid

What its [App Inclusion Policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/) asks for (read
2026-10-24), and where Cucuto stands:

| Requirement | Cucuto |
| --- | --- |
| Free license, public source | GPL-3.0-or-later on GitHub. Met. |
| APK attached to GitHub release tags, signed with a release key, not debuggable or `testOnly` | `cucuto-<version>.apk` on `v*` releases, one release key. Met. |
| At most about 30 MB per APK | About 7 MB. Met. |
| Fastlane metadata in the repo: short and full description, icon, screenshots | `fastlane/metadata/android/`. Met. |
| No trackers or ads; apps that request root must have no tracking at all | None. Met. |
| No self-updater, or an opt-in one | No update checker at all. Met. |
| Avoid `usesCleartextTraffic` | Not set (HTTPS only). Met. |
| "Apps for Facebook, WhatsApp & Co" won't be accepted | **Probably met now.** Since #374 ([ADR 0081](decisions/0081-signal-support.md)) Cucuto works fully with Signal or Molly, so WhatsApp is optional. Until then it only worked with WhatsApp's notifications. |
| "Vibe-coded apps will be rejected"; "the code itself should be free of" LLM-generated text | **Not met.** Cucuto's code is written by AI agents. |
| Reproducible builds | Encouraged, not required. See [reproducible-builds.md](reproducible-builds.md). |

So a request would most likely be refused today on the AI-written code, which the project can't change (the WhatsApp ground likely fell with Signal support). If the owner still wants to
ask (the policy is applied case by case), the request goes to the [repodata issue
tracker](https://codeberg.org/IzzyOnDroid/repodata/issues) with its new-app template, pointing at the GitHub
releases; IzzyOnDroid then pins the signing certificate from the first APK it sees. Nothing has been submitted.

## F-Droid

F-Droid builds every app from source on its own servers.

**Owner update 2026-10-24 (#211):** "f-droid would be nice, we can do it after v1." Planned after the first `v1.0.0`
release: #275. It uses F-Droid's reproducible-build route (`Binaries:` and `AllowedAPKSigningKeys:`), so F-Droid ships
the APK signed with Cucuto's own key. Users can then move between GitHub, Obtainium and F-Droid without reinstalling,
and one developer verification registration covers all three (below).

**Ready to submit (2026-10-24).** The recipe is [`fdroid/app.mauri.cucuto.yml`](../fdroid/app.mauri.cucuto.yml),
with what it does and what was checked in [`fdroid/README.md`](../fdroid/README.md):

- It builds a `v*` tag with JDK 21, rustup and the pinned Rust toolchain, `cargo-ndk` 4.1.2 and NDK r30.
- F-Droid publishes its build only if it matches the GitHub release APK, signed with the release certificate.
- It updates itself on new `v*` tags. F-Droid reads `versionCode` and `versionName` from
  `android/app/build.gradle.kts`, which the release PR now sets ([releasing.md](releasing.md)).
- **No inclusion blockers.** The release APK has no Google Play services, Firebase, GMS, Google sign-in, trackers or
  proprietary libraries. `:core:drive` isn't part of it. `fdroid scanner` and `fdroid lint` pass with the recipe.
- **No anti-features** (since #374, [ADR 0081](decisions/0081-signal-support.md)): Cucuto works fully with Signal or
  Molly, which are free software, so `NonFreeDep` no longer applies. `NonFreeNet` doesn't fit, because Cucuto never
  talks to any messenger's servers.
- Still untested: a full `fdroid build` on F-Droid's Debian image. The merge request's CI runs it.

**Owner steps** (GitLab account, fork, merge request): #275. Once F-Droid publishes Cucuto, add it to the website's
Install page.

## Google Play

**Not published.** The owner decided on 2026-10-24 ([ADR 0080](decisions/0080-no-google-play.md)):

> I decided i wont publish to the Play store because of the closed testing requirement is too hard to meet.

Personal developer accounts created after November 2023 need a [closed test with at least 12 testers opted in for
14 days in a row](https://support.google.com/googleplay/android-developer/answer/14151465) before production. The
earlier plan (#211, ADR 0071: a signed bundle on every release, Play checks in CI, the Console answers in
`docs/google-play.md`, a `fastlane supply` config) was removed; its policy assessment is in this file's git history.
CI still checks what any release needs: 16 KB pages, a target SDK floor and the permission allowlist.

### Android developer verification (affects GitHub installs)

From 30 September 2026 in Brazil, Indonesia, Singapore and Thailand, and worldwide from 2027,
[Android developer verification](https://developer.android.com/developer-verification) requires apps on certified
Android phones to come from a developer who verified their identity and registered the package name, apps installed
from outside Play included. Without Play Console, the owner registers through the
[Android Developer Console](https://developer.android.com/developer-verification/guides/android-developer-console):
**Register package name** `app.mauri.cucuto`, then **Add key** with the release certificate SHA-256
([open-source apps](https://developer.android.com/developer-verification/guides/open-source-app-registration)).
F-Droid ships the APK with Cucuto's own signature (reproducible builds, #275), so one registration covers GitHub,
Obtainium and F-Droid. Steps: **#358**. Without it, people on certified phones need Android's "advanced flow" (a
one-time setup for apps from unverified developers) to install Cucuto.
