# Distribution

Where Cucuto's APK goes and what each channel needs. Decision: [ADR 0046](decisions/0046-distribution.md). How a
release is built and published: [releasing.md](releasing.md). The brief used to rule out the Play Store; the owner
wants it too (#211, 2026-10-24), so [Google Play](#google-play) is now an extra channel, with no guarantee of
approval.

Cucuto has **no update checker and makes no network calls to look for updates** (minimum intrusion). People update
with tools they already trust; every channel below reads the same GitHub release and the same store metadata.

| Channel | Status | What it reads |
| --- | --- | --- |
| [GitHub Releases](https://github.com/mauriciabad/cucuto/releases) | Live since v0.1.1 | `cucuto-<version>.apk`, `.sha256`, release notes |
| Website [Download APK](https://cucuto.mauri.app/install/) | Live | The latest release, at site build time |
| [Obtainium](https://github.com/ImranR98/Obtainium) | Ready: link and config on the Install page | GitHub releases (APK filter, title filter, certificate pin) |
| [IzzyOnDroid](https://apt.izzysoft.de/fdroid/) | Not submitted; likely refused today (see below) | GitHub releases + `fastlane/metadata/android/` |
| [Google Play](https://play.google.com) | Ready on the repository side ([google-play.md](google-play.md), ADR 0071); waiting for the owner's account and keys (#276). Approval not guaranteed | `cucuto-<version>.aab` from the GitHub release, uploaded to Play Console + the same metadata |
| [F-Droid](https://f-droid.org) main repository | After v1 (#275) | Builds from source + `fastlane/metadata/android/` |

The submissions are owner decisions: #211 (answered: Play yes, F-Droid after v1, IzzyOnDroid not submitted).

## Store metadata (`fastlane/metadata/android/`)

F-Droid, IzzyOnDroid and Obtainium-compatible clients read the [fastlane
layout](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/) from the repository, and
`fastlane supply` uploads the same files to Google Play ([google-play.md](google-play.md), ADR 0071). The limits are
the stricter of the stores:

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
- **WhatsApp** (ADR 0046): never in the title, the icon or the feature graphic, and not in the first words of the short
  description. The full description ends with "Not affiliated with WhatsApp or Meta." ("Sin relación con WhatsApp
  ni Meta.").
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
the APK signed with Cucuto's own key. The recipe (an `fdroiddata` merge request with Rust and cargo-ndk setup) is
extra maintenance, and F-Droid would likely flag the app with the `NonFreeDep` anti-feature, since it is useless
without WhatsApp.

**Owner update 2026-10-24 (#211):** "f-droid would be nice, we can do it after v1." Planned after the first `v1.0.0`
release: #275. Use F-Droid's reproducible-build route (`Binaries:` and `AllowedAPKSigningKeys:`), so F-Droid ships
the APK signed with Cucuto's own key. Users can then move between GitHub, Obtainium, F-Droid and Play without
reinstalling. The package also stays registered to the owner under Android developer verification (below).

## Google Play

**Owner update 2026-10-24 (#211):** "Google Play Store is not an option? i thought we would publish it there too.
I'll make a developer account if necesary". The brief had listed the Play Store as a non-goal. This section is the
honest assessment, read 2026-10-24 from the Google Play Developer Program Policy pages linked below.

**Verdict: possible, not guaranteed. Worth trying as an extra channel.** No Play policy rules out an app because it
needs root, because it is an LSPosed module, or because AI wrote its code. The risks are how a reviewer reads the
hook, that reviewers can't see the app work, and the WhatsApp trademark. A rejection costs only time. GitHub
Releases and Obtainium stay the main channel either way.

### What Play's policies say, point by point

| Topic | Policy | Cucuto | Risk |
| --- | --- | --- | --- |
| Needs root and LSPosed | No policy bans apps that need root. The [Malware](https://support.google.com/googleplay/android-developer/answer/9888380) policy's "Rooting" category is about code that roots the phone ("privilege escalation apps that root devices without user permission"). Its "Elevated Privilege Abuse" category is "code that compromises the integrity of the system by breaking the app sandbox, gaining elevated privileges, or changing or disabling access to core security-related functions". | Cucuto never roots anything and gains no privileges itself. The user has already rooted the phone and enabled the module, and LSPosed loads it. It doesn't touch SELinux, permissions or other apps' data. | Low to medium. An automated scan could still flag the module's code because it runs in `system_server`. |
| Hooking the system | [Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/9888379): no apps that "interfere with, disrupt, damage, or access in an unauthorized manner the user's device, other devices or computers, servers, networks, application programming interfaces (APIs), or services, including but not limited to other apps on the device". Also no code that "introduces or exploits security vulnerabilities". | The module runs in `system_server` and changes the sound and vibration of WhatsApp's notifications, at the user's request. It exploits nothing: LSPosed loads it because the user enabled it. | **Medium.** It depends on the reviewer. There's no exemption for root customizations a user chose, and no precedent either way for LSPosed modules on Play. |
| The optional hook inside WhatsApp (#273) | The same policy, plus "apps that access or use a service or API in a manner that violates its terms of service". WhatsApp's terms forbid modifying its app. | Off by default. It runs only if the user grants the WhatsApp scope in LSPosed. It reads only the caller's name and swaps one ringtone URI. It doesn't touch WhatsApp's files or network. | **Medium to high.** It's the most likely reason for a rejection. If Play objects, the options are an appeal, or a Play build without this one feature (a build flavor). That fallback costs code and is not planned unless it happens. |
| Reviewers can't test it | Play Console's App access declaration asks how to reach every feature. Apps that don't work can be refused under [Minimum Functionality](https://support.google.com/googleplay/android-developer/answer/9898783). | Without root the app opens: import packs, preview characters, Discover, the editors. Real chats come from the module, so a sample chat shows the core feature in the app. The setup checks say plainly what's missing. | Medium. The review notes ([google-play.md](google-play.md#review-notes)) explain the requirements, walk reviewers through the sample chat, which plays a voice in the app without root (#285, ADR 0075), and link a demo video (#286). |
| Generative AI | The [AI-Generated Content](https://support.google.com/googleplay/android-developer/answer/14094294) policy covers apps that *generate* content with AI (chatbots, image or voice generators). It says nothing about how an app's code was written. | Cucuto has no generative AI feature. "Copy for AI" only copies text the user pastes into their own assistant. The code is written by AI agents. | None for Play. Unlike IzzyOnDroid, Play has no rule against AI-written code. |
| "WhatsApp" in the listing | [Intellectual Property](https://support.google.com/googleplay/android-developer/answer/9888072): "Don't use another party's trademarks, such as logos or brand names without permission." [Meta's WhatsApp brand guidelines](https://www.meta.com/brand/resources/whatsapp/whatsapp-brand/): don't use WhatsApp "as part of a name of a product or service", don't imply "partnership, sponsorship, or endorsement", and "DON'T make WhatsApp the most distinctive or prominent feature of your materials". | The name is Cucuto. The short description was "A voice for each WhatsApp contact…"; since #274 it reads "A voice for each contact: their own sound and vibration in WhatsApp. Needs root." | Medium if left as is, low once fixed. No "WhatsApp" in the title, icon, developer name, feature graphic or the first words of the short description. The full description may say it works with WhatsApp and WhatsApp Business notifications, ending with "Not affiliated with WhatsApp or Meta" (already on the website). Meta can still file a complaint, and Play then decides. |
| Sensitive permissions | Restricted permissions need a declaration form: SMS, Call Log, All files access, `QUERY_ALL_PACKAGES`, Accessibility, background location and others. | Cucuto uses none of them. It uses `READ_CONTACTS`, `WRITE_CONTACTS` and `READ_PHONE_STATE` (phone ringtones), `RECORD_AUDIO` (pack creator), `ACCESS_COARSE_LOCATION` (day and night clips, foreground only), `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `VIBRATE` and `INTERNET`. | Low. The Data safety form must declare contacts and location use. Nothing leaves the phone except backups to the user's own Drive when they choose. Each permission is already explained in the app before it's asked for. The privacy policy is live at `/privacy/`. |
| Executable code | No downloading dex, JAR or `.so` files from outside Play, and no self-updates. | Packs are audio, images and JSON. Cucuto has no update checker. | None |

### Technical requirements

- **App bundle and Play App Signing.** New apps upload an `.aab`, and Google signs what it serves. **Owner update
  2026-10-24 (#287, second answer):** Play signs with the owner's release key, given to Play once with PEPK ("i
  preffer to give Play my own key, it is a small step i can do"), and the release key is also the upload key. So Play
  installs and the APKs from GitHub, Obtainium and F-Droid share one certificate and update each other, and the
  Obtainium certificate pin accepts an app installed from Play. Play builds split APKs from the bundle, so they
  aren't byte-identical to the reproducible GitHub APK. `release.yml` attaches
  the bundle, signed with the release key, to every release ([google-play.md → Signing](google-play.md#signing),
  ADR 0071).
- **Target API and 16 KB pages** (checked 2026-10-24, ADR 0071): Play needs API 36 since 31 August 2026, and Cucuto
  targets 37. The native libraries, including the Rust `libcucuto_jxl.so` linked by NDK r30, have 16 KB-aligned
  `LOAD` segments, and the bundle asks for 16 KB zip alignment (`PAGE_ALIGNMENT_16K`). CI checks all three on every
  PR, plus a permission allowlist.
- **Listing:** title at most 30 characters, short description at most 80, a 1024 × 500 feature graphic and 9:16
  screenshots without alpha. All of it is in `fastlane/metadata/android/` ([Store metadata](#store-metadata-fastlanemetadataandroid)).
- **Console answers** (App access and review notes, Content rating, Target audience, Data safety, permissions):
  drafted in [google-play.md](google-play.md).

### The developer account

- A **personal** account: US$25 once, a government ID check, a public developer name and contact email. Google also
  checks that the owner has an Android phone, through the Play Console app.
- **Closed test first:** personal accounts created after November 2023 need [at least 12 testers opted in for 14
  days in a row](https://support.google.com/googleplay/android-developer/answer/14151465) before production. The
  testers don't need root.
- **Account risk:** a rejected update is routine. Repeated policy violations can suspend a developer account, and
  Google may refuse accounts linked to a suspended one. Rejections are answered with changes or an appeal, never by
  resubmitting the same build.

### Android developer verification (affects GitHub installs too)

From 30 September 2026 in Brazil, Indonesia, Singapore and Thailand, and worldwide from 2027,
[Android developer verification](https://developer.android.com/developer-verification) requires apps on certified
Android phones to come from a developer who verified their identity and registered the package name. That
includes apps installed from outside Play. Play Console registers the apps it distributes and lets the developer
register the rest. So even if Play refused Cucuto, the account still registers `app.mauri.cucuto` and the release
certificate for the GitHub, Obtainium and F-Droid APKs. Without it, APKs from GitHub may need the power-user
"advanced flow" to install on certified phones from 2027.

### Steps

- Owner: create the account, set up signing, run the closed test, submit: **#276**.
- Agents (#274, done, ADR 0071): the signed bundle in `release.yml`, Play's checks in CI, the listing from the
  fastlane metadata (no "WhatsApp" up front), the Console answers and review notes in
  [google-play.md](google-play.md), and an owner-run `fastlane supply` config (`fastlane/Supplyfile`). Follow-ups: a
  sample chat that reviewers can try without root (#285, done, ADR 0075) and a demo video for review (#286).
