# Distribution

Where Cucuto's APK goes and what each channel needs. Decision: [ADR 0046](decisions/0046-distribution.md). How a
release is built and published: [releasing.md](releasing.md). The brief rules out the Play Store.

Cucuto has **no update checker and makes no network calls to look for updates** (minimum intrusion). People update
with tools they already trust; every channel below reads the same GitHub release and the same store metadata.

| Channel | Status | What it reads |
| --- | --- | --- |
| [GitHub Releases](https://github.com/mauriciabad/cucuto/releases) | Live since v0.1.1 | `cucuto-<version>.apk`, `.sha256`, release notes |
| Website [Download APK](https://cucuto.mauri.app/install/) | Live | The latest release, at site build time |
| [Obtainium](https://github.com/ImranR98/Obtainium) | Ready: link and config on the Install page | GitHub releases (APK filter, title filter, certificate pin) |
| [IzzyOnDroid](https://apt.izzysoft.de/fdroid/) | Not submitted; likely refused today (see below) | GitHub releases + `fastlane/metadata/android/` |
| [F-Droid](https://f-droid.org) main repository | Not planned for v1 | Builds from source + `fastlane/metadata/android/` |

The submissions are owner decisions: #211.

## Store metadata (`fastlane/metadata/android/`)

F-Droid, IzzyOnDroid and Obtainium-compatible clients read the [fastlane
layout](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/) from the repository:

```
fastlane/metadata/android/
├── en-US/
│   ├── title.txt                 # at most 50 characters
│   ├── short_description.txt     # at most 80
│   ├── full_description.txt      # at most 4000; simple HTML (<p>, <b>, <ul>, <li>, <a>)
│   ├── changelogs/<versionCode>.txt   # at most 500, plain text; one per release
│   └── images/
│       ├── icon.png              # 512 × 512, copied from brand/app-icon/cucuto-icon-512.png
│       └── phoneScreenshots/<n>-<screen>.png
└── es-ES/                        # the same; falls back to en-US for anything missing
```

- **Descriptions** are written in the website's voice (brand/README.md) and say up front that Cucuto needs Android 17,
  root and LSPosed, and that it does nothing without them. Keep them in step with the website's Home and Install
  copy in both languages.
- **Screenshots** come from the Roborazzi screenshot tests (ADR 0032), light theme, 822 × 1782 px, with the CC0 demo
  packs and made-up chats only. Regenerate both languages after a UI change with
  `cd android && ./gradlew :app:exportStoreScreenshots`. A test joins the listing with `store = <position>` in its
  `screenshot(…)` call.
- **Changelogs** are written per release by `tools/release/release-notes.main.kts` (English); add the Spanish one by
  hand when there's time, otherwise clients show the English text. `versionCode` is
  `MAJOR * 1000000 + MINOR * 1000 + PATCH`, so `v0.2.0` is `2000.txt`.
- CI's `store-metadata` job checks every length limit (`kotlin tools/release/release-notes.main.kts --check`), and the
  media guard allows PNGs only under `fastlane/metadata/android/<locale>/images/`.

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
| "Apps for Facebook, WhatsApp & Co" won't be accepted | **Not met.** Cucuto only works with WhatsApp's notifications. |
| "Vibe-coded apps will be rejected"; "the code itself should be free of" LLM-generated text | **Not met.** Cucuto's code is written by AI agents. |
| Reproducible builds | Encouraged, not required. See [reproducible-builds.md](reproducible-builds.md). |

So a request would most likely be refused today on two grounds the project can't change. If the owner still wants to
ask (the policy is applied case by case), the request goes to the [repodata issue
tracker](https://codeberg.org/IzzyOnDroid/repodata/issues) with its new-app template, pointing at the GitHub
releases; IzzyOnDroid then pins the signing certificate from the first APK it sees. Nothing has been submitted.

## F-Droid

F-Droid builds every app from source on its own servers. Cucuto could build there in principle (Gradle, a pinned
Rust toolchain through rustup, NDK r30, no proprietary dependencies), and reproducible builds would let F-Droid ship
the APK signed with Cucuto's own key. It is not planned for v1: the recipe (an `fdroiddata` merge request with Rust
and cargo-ndk setup) is extra maintenance, and F-Droid would likely flag the app with the `NonFreeDep` anti-feature,
since it is useless without WhatsApp. Revisit after v1 if users ask for it (#211).
