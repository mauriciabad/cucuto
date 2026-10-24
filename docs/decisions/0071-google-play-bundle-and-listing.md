# 0071. Google Play: app bundle, one signing certificate, listing and review notes

- Status: accepted. The defaults the owner may override are in #287.
- Date: 2026-10-24
- Issues: #274 (this work), #276 (the owner's steps), #211 (the owner's answer: Play too), ADR 0046 (distribution),
  ADR 0021 (release signing)

## Context

The owner wants Cucuto on Google Play as an extra channel (#211, ADR 0046). ADR 0046 found no policy that rules it
out and set three rules: one signing identity across every channel, a listing that doesn't lead with WhatsApp, and
no Play-only build. This ADR covers the parts that live in the repository. The account, the keys and the
submission stay with the owner (#276). Agents never touch a key or upload anything.

Sources, read on 2026-10-24:

- Play Console Help, [Use Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756).
  New apps are enrolled in "quantum-ready, hybrid signing with Google-generated keys" when their first bundle is
  uploaded. Developers who manage their own key can change that "before there is a release rolled out in open
  testing track or production track", under Protected with Play → Play Store distribution → Go to Play app signing →
  Change the app signing key → Provide a copy of your app signing key (PEPK). Custom keys must be RSA 2048 or larger.
  "For maximum security, your upload key and app signing key should be different." A lost upload key can be reset.
- Android Developers, [Target API level requirements](https://developer.android.com/google/play/requirements/target-sdk).
  From 31 August 2026, new apps and updates must target API 36 or higher.
- Android Developers, [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes). Apps
  targeting API 35+ must support 16 KB pages. Updates that don't can't be released after 1 February 2027. Checks:
  `zipalign -c -P 16 -v 4`, ELF `LOAD` alignment of at least `2**14`, and RELRO alignment when RELRO shares a `LOAD`
  segment with writable data. NDK r28+ aligns to 16 KB by default. AGP 8.5.1+ zip-aligns uncompressed libraries to
  16 KB.
- Play Console Help, [Add preview assets](https://support.google.com/googleplay/android-developer/answer/9866151).
  Icon: 512 × 512, 32-bit PNG with alpha. Feature graphic: 1024 × 500, JPEG or 24-bit PNG, no alpha. Screenshots:
  JPEG or 24-bit PNG, no alpha, sides 320 to 3840 px, the long side at most twice the short one, up to 8 per device
  type. For promotions: at least 4 screenshots, portrait at least 1080 × 1920. No device frames.
- F-Droid, [descriptions, graphics and screenshots](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/).
  Line breaks become `<br>`, and `b`, `i`, `ul`, `li` and others are rendered. Play renders only bold, italics and
  line breaks in a description. `<p>` and `<ul>` don't render there.
- fastlane [`supply`](https://docs.fastlane.tools/actions/supply/) (2.241.1). It reads `fastlane/metadata/android/`
  and `fastlane/Supplyfile` (`Supply.config.load_configuration_file('Supplyfile')`).

What a release build of `v0.9.0` looked like on 2026-10-24 (`bundleRelease`, bundletool 1.18.3):

- `targetSdkVersion` 37: meets Play's 36.
- `BundleConfig`: `uncompressNativeLibraries` with `PAGE_ALIGNMENT_16K`. The split APKs pass `zipalign -c -P 16`.
- `libcucuto_jxl.so` (Rust, cargo-ndk with NDK r30's linker), `libandroidx.graphics.path.so` and
  `libdatastore_shared_counter.so`: every `LOAD` segment is `align 2**14`. In each of them RELRO has a `LOAD`
  segment of its own, and the next writable segment starts on a new 16 KB page. Nothing to fix.
- The base split keeps `META-INF/xposed/{java_init.list,module.prop,scope.list}` and both dex files, so LSPosed
  loads the module from a Play install as it does from the single APK. The `.so` files go to an ABI split. The
  module in `system_server` loads no native library and no resources, so it doesn't depend on the splits.
- Permissions: the nine of `docs/distribution.md` plus AndroidX's own
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. None is on Play's restricted list. No `AD_ID`.
- The store screenshots were 822 × 1782 px RGBA: a 2.17 ratio and an alpha channel, both refused by Play. The icon
  was 24-bit, but Play asks for a 32-bit icon.
- Both descriptions used `<p>`, `<ul>`, `<li>` and `<a>`, which Play shows as plain text.

## Decision

**1. The release workflow also builds the bundle.** `release.yml` runs `:app:bundleRelease` with the same
`-Pcucuto.version`, so versionName and versionCode match the APK. It signs the bundle with the Play **upload key**
from four new secrets (`PLAY_UPLOAD_KEYSTORE_BASE64`, `PLAY_UPLOAD_KEYSTORE_PASSWORD`, `PLAY_UPLOAD_KEY_ALIAS`,
`PLAY_UPLOAD_KEY_PASSWORD`), through the same `CUCUTO_RELEASE_*` environment variables the convention plugin already
reads. It verifies the signature (one signer, not a debug key, not the release certificate, and the optional
`PLAY_UPLOAD_CERT_SHA256` pin), the version (`bundletool dump manifest`), the 16 KB setting
(`bundletool dump config`) and the module metadata. Then it attaches `cucuto-<version>.aab` and its `.sha256` to the
same GitHub release. Without the upload key the release ships the APK as before and the summary says the bundle was
skipped. A partial set of `PLAY_UPLOAD_*` secrets fails the run. bundletool comes from its GitHub release, pinned by
version and SHA-256. The APK, its name, the Obtainium filter and the reproducibility check don't change. The
website and Obtainium only pick `.apk` assets.

**2. One signing certificate on every channel.** The owner gives Play a copy of Cucuto's existing release key with
PEPK, so Play signs its APKs with the same certificate as GitHub, Obtainium and (later) F-Droid. Users can move
between channels without reinstalling. Obtainium's certificate pin, the module's certificate check (ADR 0010) and an
OAuth client for Drive (ADR 0040) keep working. This must happen **before the first bundle is uploaded**: Play
enrolls new apps in Google-generated keys on the first upload, and the change is only allowed until a release
reaches open testing or production. Any closed-test install made with Google's key would then stop updating. The
upload key is separate, as Google recommends (#287 offers the release key instead). The exact flow is in #276 and
[google-play.md](../google-play.md#signing).

**3. No language splits.** `bundle { language { enableSplit = false } }`. Cucuto picks its language per app
(ADR 0036). Third-party Play clients, common on rooted phones, may only fetch the phone language's split, which
would leave a Spanish app choice with English strings. Spanish costs a few kilobytes. ABI and density splits stay.

**4. Play's requirements are checked on every PR.** CI's `android-assemble` job builds the unsigned bundle too and
checks:

- 16 KB pages: `zipalign -c -P 16` on the release APK and `LOAD` alignment of every `.so` (`llvm-readelf`);
- `targetSdkVersion` at least Play's minimum (36, kept in the workflow with the date);
- the release APK's permissions against an allowlist, so a new permission (from Cucuto or a library) fails until
  the privacy page, the Data safety draft and the allowlist agree;
- the module's `META-INF/xposed` files and the native library in the bundle's base module.

**5. One listing for every store, written for Play's limits.** `fastlane/metadata/android/` stays the only source.
F-Droid, IzzyOnDroid and Obtainium read it, and `fastlane supply` uploads it to Play.

- Descriptions are plain text with line breaks, `<b>` for headings and "• " bullets. Both renderers agree on that.
  Links are bare URLs.
- Title at most 30 characters. The short description doesn't open with WhatsApp: "A voice for each contact: their
  own sound and vibration in WhatsApp. Needs root." The full description ends with "Not affiliated with WhatsApp or
  Meta." ("Sin relación con WhatsApp ni Meta.").
- A 1024 × 500 feature graphic per language (`brand/tools/build-store.mjs`): the clock, the wordmark and the
  website's pitch, without WhatsApp.
- Screenshots keep their pixels but are centered on a 1080 × 1920 canvas in the brand's primary-90 tone, with
  rounded corners and no alpha (`StoreFrame` in the screenshot tests). That makes them 9:16 and large enough for
  promotions. The goldens and the website images don't change.
- The icon is the brand icon as a 32-bit PNG.
- `kotlin tools/release/release-notes.main.kts --check` (CI's `store-metadata` job) enforces all of it: Play's
  lengths, allowed tags, the WhatsApp rules, the disclaimer, and image sizes, ratios and alpha.

**6. The Play Console answers are drafted in [google-play.md](../google-play.md).** That covers App access (review
notes), Ads, Content rating, Target audience (18+), Data safety ("no data collected, no data shared", matching the
privacy page), sensitive permissions (none restricted, with an explanation for each one) and the listing fields.
The owner copies them in. Any change to permissions or network use updates the privacy page and these answers in the
same PR (the CI allowlist enforces it).

**7. Reviewers without root.** Today they can reach packs, Discover, previews, the haptic editor, the creator,
backups and Diagnostics, but no chat to assign a voice to, since chats come from the module. The review notes say
so plainly, list what to try, and link a demo video recorded on a rooted phone (#286, `needs-device`). The app
change that closes the gap, a local **sample chat** that never reaches the module and plays through Simulate
message's in-app preview, is #285. It's kept out of this PR because it touches Home and the routing table builder.

**8. Upload tooling: `fastlane supply`, owner-run, not wired to CI.** `fastlane/Supplyfile` sets the package, the
metadata path, the internal track and `release_status("draft")`. A draft is the only status Play accepts for an app
that was never published, and it means nothing goes live without a click in Play Console. The service-account key
comes from `SUPPLY_JSON_KEY`, never from the repository. It was picked over Gradle Play Publisher (the issue's first
idea) because it reads the fastlane layout as is and keeps a Play plugin and credentials out of every Gradle build.
The `Supplyfile` is configuration in Ruby syntax, like the YAML of a workflow, not project code. Kotlin stays the
language for tools. A later CI step would run `fastlane supply` on the release's bundle with the
`PLAY_SERVICE_ACCOUNT_JSON` secret, and docs/releasing.md shows it. It isn't added until the owner asks.

## Consequences

- Each release has a Play-ready bundle once the owner has set the upload key. Uploading it (Play Console, or
  `fastlane supply`) and promoting it stay manual.
- The owner must set Play App Signing to the release key **before the first upload**. Otherwise Play's own key
  splits the user base for good. #276 says so in bold.
- Play installs come as split APKs. They are not byte-identical to the reproducible GitHub APK, but they carry the
  same certificate, so updates cross channels either way. On Android 14+ the system may ask the user to confirm
  when another installer updates an app Play installed (update ownership). That's a prompt, not a block.
- Store screenshots now have a peach border on F-Droid and Obtainium too.
- The descriptions lose their HTML lists. F-Droid shows the same content with bullets.
- A new permission or library permission fails CI until the privacy page and `docs/google-play.md` are updated.
- Reviewers still can't see the core feature without root until #285 ships. The video (#286) is the stopgap.
- When the optional WhatsApp hook (#273, ADR 0052) ships, the description's "never hooks the WhatsApp app" line,
  the privacy page and the review notes must change in that PR. google-play.md has the prepared wording.
