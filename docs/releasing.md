# Releasing Cucuto

A release is a signed APK on [GitHub Releases](https://github.com/mauriciabad/cucuto/releases), built by
[`.github/workflows/release.yml`](../.github/workflows/release.yml) when a `v*` tag is pushed. The website's Download
APK button follows it automatically. Why it works this way: [ADR 0021](decisions/0021-signed-release-apk.md); release notes, store metadata and the reproducibility check: [ADR 0046](decisions/0046-distribution.md). Key setup status: #140. Where the APK goes from there (Obtainium, IzzyOnDroid, F-Droid): [distribution.md](distribution.md). The Google Play bundle and upload: [Google Play bundle](#google-play-bundle) and [Upload to Google Play](#upload-to-google-play) (ADR 0071).

## Once: create the signing key (owner only)

Every Cucuto release is signed with one key, the app's long-lived identity. Android installs an update only when it is
signed by the same key as the installed app, so **this key must never change or get lost**. Agents never create it or
touch the secrets that hold it.

Needs a JDK 21 (`brew install openjdk@21`), the `kotlin` CLI (`brew install kotlin`) and `gh` logged in with access to
the repository's secrets.

```sh
export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home
kotlin tools/release/setup-signing.main.kts
```

[The script](../tools/release/setup-signing.main.kts):

1. creates `~/cucuto-release-key/cucuto-release.jks` (PKCS12, RSA 4096, valid for 50 years, alias `cucuto`,
   certificate `CN=Cucuto`) with a random 43-character password, outside the repository, readable only by you. It
   refuses to overwrite an existing keystore;
2. prints the password and the certificate's SHA-256 fingerprint, then waits until you type `saved`;
3. stores four repository secrets with `gh secret set`, values on stdin: `RELEASE_KEYSTORE_BASE64` (the keystore,
   Base64), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` (the same password: PKCS12 has
   one);
4. sets the repository variable `RELEASE_CERT_SHA256` to the certificate fingerprint. The release workflow refuses to
   publish an APK signed by any other certificate.

Options: `--out DIR` puts the key elsewhere; `--existing FILE --alias NAME` uploads a keystore you already have (it
asks for the passwords); `--no-upload` skips the secrets; `--repo OWNER/NAME` targets another repository.

**Back it up before anything else.** Save the password in your password manager and copy `cucuto-release.jks` to two
places you control, for example the password manager (as an attachment) and an offline drive. GitHub secrets can't be
read back, so they are not a backup. If the key is lost, there is no recovery: the next release needs a new key, and
every user has to uninstall Cucuto and set it up again (see [If the signing key changes](#if-the-signing-key-changes)).
If it leaks, anyone can sign an APK that phones accept as an update to Cucuto, and Cucuto's module runs inside
`system_server`. Never commit the keystore (`*.jks` and `*.keystore` are in `.gitignore`).

Manual equivalent, if you prefer to run each step yourself:

```sh
keytool -genkeypair -storetype PKCS12 -keystore cucuto-release.jks -alias cucuto \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 18262 -dname "CN=Cucuto"
base64 -i cucuto-release.jks | gh secret set RELEASE_KEYSTORE_BASE64 --repo mauriciabad/cucuto
gh secret set RELEASE_KEYSTORE_PASSWORD --repo mauriciabad/cucuto   # prompts for the value
gh secret set RELEASE_KEY_PASSWORD --repo mauriciabad/cucuto        # the same password
gh secret set RELEASE_KEY_ALIAS --repo mauriciabad/cucuto --body cucuto
keytool -list -v -keystore cucuto-release.jks -alias cucuto | grep SHA256:
gh variable set RELEASE_CERT_SHA256 --repo mauriciabad/cucuto --body <fingerprint, lowercase, no colons>
```

## Every release

1. Make sure `main` is green and has everything the release should contain.
2. **Write the changelog** in a release PR (ADR 0046). From an up-to-date `main`:

   ```sh
   git switch -c chore/<issue>-release-0.2.0 origin/main
   kotlin tools/release/release-notes.main.kts 0.2.0
   ```

   It reads the Conventional Commits since the previous `v*` tag and writes
   `fastlane/metadata/android/en-US/changelogs/2000.txt` (the versionCode) and a `## [0.2.0]` section at the top of
   `CHANGELOG.md`. Edit both into words for users: say what changed for them, merge related lines, drop what nobody
   notices, mention anything broken (a known issue) or anything they must do. Add
   `fastlane/metadata/android/es-ES/changelogs/2000.txt` if you can (otherwise clients show the English one). Check
   the limits with `kotlin tools/release/release-notes.main.kts --check`, then merge the PR as
   `chore(release): 0.2.0`. If screens changed, refresh the store screenshots in the same PR
   (`cd android && ./gradlew :app:exportStoreScreenshots`).
3. Tag the merge commit on `main` and push the tag:

   ```sh
   git switch main && git pull
   git tag v0.2.0
   git push origin v0.2.0
   ```

4. The workflow checks the tag and the secrets, builds `:app:assembleRelease` with the key, verifies the signature
   (one signer, not a debug key, the pinned certificate) and the version with `apksigner` and `aapt2`, then publishes
   the release `Cucuto 0.2.0` with `cucuto-0.2.0.apk` and `cucuto-0.2.0.apk.sha256`, plus `cucuto-0.2.0.aab` and
   its checksum for Google Play (see [Google Play bundle](#google-play-bundle)). The notes start with the AI
   banner, list the checksum and the certificate fingerprint, and end with the changes since the previous tag,
   grouped into New, Fixes and Docs, from the same script (`--notes`). If the tag has no store changelog for its
   versionCode (step 2 skipped), the run warns and puts the generated files in its summary as a patch to commit.
5. It then starts `ci.yml` on `main` with `site_only=true`, which rebuilds and redeploys the website with the new
   version, size and download link (`web/scripts/build-data.ts`). Releases created with `GITHUB_TOKEN` don't trigger
   `site-rebuild.yml`, hence the explicit dispatch.
6. The `reproducible` job rebuilds the tag unsigned on a fresh runner and compares it with the published APK
   ([reproducible-builds.md](reproducible-builds.md)). A red result doesn't undo the release; open an issue with the
   job summary.
7. Google Play (owner, optional): upload `cucuto-0.2.0.aab` to the internal testing track and promote it in Play
   Console ([Upload to Google Play](#upload-to-google-play)).

### Release notes

The notes list `feat` commits under **New**, `fix` and `perf` under **Fixes** and `docs` under **Docs**, minus internal
scopes (`web`, `ci`, `deps`, `owner-log`, `release`, `screenshots` and a few more: `internalScopes` in the script).
PR titles become the lines, so write them for users when the change is visible. When a title can't be, add a line to
the PR description (squash merges keep it as the commit body):

```
Release note: Pack import works again
```

`Release note: skip` leaves the PR out. Where the notes go: GitHub release, `CHANGELOG.md`, and the store changelog
that F-Droid, IzzyOnDroid and Obtainium show ([distribution.md](distribution.md)).

If the run fails (missing secrets, a tag that is not on `main`), fix the cause and use **Re-run jobs** on the run: it
reads the secrets again, and a release that already exists only gets its assets replaced. A tag that should not be a
release: delete it (`git push origin :v0.1.0` and `git tag -d v0.1.0`) and the release, if one was created.

### Versions

The tag is the only source of the version: `vMAJOR.MINOR.PATCH`, each part 0 to 999 without leading zeros. Other tags
(`v1.0`, `v1.0.0-rc1`) fail the run. The build gets `-Pcucuto.version=MAJOR.MINOR.PATCH`, and the
`cucuto.android.application` convention plugin sets:

- `versionName` = `MAJOR.MINOR.PATCH`
- `versionCode` = `MAJOR * 1_000_000 + MINOR * 1_000 + PATCH` (`v0.1.0` gives 1000, `v1.2.3` gives 1002003)

Android refuses to install an update with a lower `versionCode`, and this formula always grows with the version. Builds
without the property (local, CI, debug) keep `versionCode 1` and `versionName "0.1.0"` from `android/app/build.gradle.kts`.

### A signed build on your machine

The same environment variables the workflow uses sign a local release build:

```sh
export CUCUTO_RELEASE_KEYSTORE=~/cucuto-release-key/cucuto-release.jks
export CUCUTO_RELEASE_KEY_ALIAS=cucuto
read -rs CUCUTO_RELEASE_KEYSTORE_PASSWORD && export CUCUTO_RELEASE_KEYSTORE_PASSWORD
export CUCUTO_RELEASE_KEY_PASSWORD=$CUCUTO_RELEASE_KEYSTORE_PASSWORD
cd android && ./gradlew :app:assembleRelease -Pcucuto.version=0.1.0 --no-configuration-cache
```

Without `CUCUTO_RELEASE_KEYSTORE` the release build stays unsigned (`app-release-unsigned.apk`), as in CI. With it set
but one of the other three missing, the build stops with a message naming them.

## Google Play bundle

Google Play takes an app bundle, not an APK ([google-play.md](google-play.md), ADR 0071). Every release also gets
`cucuto-<version>.aab` and its `.sha256`: the same Gradle run, R8 output and version as the APK, signed with the same
release key. Nothing to set up in the repository: no extra keystore or secret (owner updates 2026-10-24, #287).

For Play, the release key is both the **app signing key** and the **upload key** (#287, second answer). The owner
gives Play a copy of it once with PEPK, before the first upload ([google-play.md → Signing](google-play.md#signing)).
Play App Signing re-signs what it serves with that same key, so **Play installs and GitHub, Obtainium or F-Droid
installs update each other** ([Sign your app](https://developer.android.com/studio/publish/app-signing): "If you do
not generate a new upload key, you continue to use your app signing key as your upload key to sign each release").

What the workflow checks before attaching the bundle: exactly one signer, not a debug key, the same certificate as
the APK (so the upload key never changes by accident), the version (bundletool), Play's 16 KB setting and the
LSPosed metadata.

If the release key ever leaked or got lost, Play and the GitHub APKs would both need a new key ([If the signing key
changes](#if-the-signing-key-changes)): with one key in both roles, an upload key reset alone doesn't help.

A bundle on your machine: the same variables as [A signed build on your machine](#a-signed-build-on-your-machine),
then `./gradlew :app:bundleRelease -Pcucuto.version=0.1.0 --no-configuration-cache`.

## Upload to Google Play

Nothing in CI uploads to Play. Either way, the release starts as a draft on the **internal testing** track, and
promotion (closed testing for the 14-day, 12-tester test, then production) is a click in Play Console.

**By hand:** download `cucuto-<version>.aab` from the GitHub release, then in Play Console open Test and release →
Testing → Internal testing → Create new release, upload it, and paste the store changelog
(`fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`) as the release notes. The first time, also copy
the listing from `fastlane/metadata/android/` and the App content answers from [google-play.md](google-play.md).

**With fastlane** (optional, the owner's machine). `fastlane/Supplyfile` sets the package, the metadata folder, the
internal track and draft status:

1. Once: `brew install fastlane` (2.241.1 or later). In Google Cloud (the Cucuto project from #188), create a service
   account and a JSON key, and keep the key outside the repository (for example in `~/cucuto-release-key/`). Enable
   the Google Play Android Developer API. In Play Console → Users and permissions, invite the service account's
   email with release permissions for Cucuto only.
2. Each release, from the repository root on the tagged commit (so the listing matches the release):

   ```sh
   gh release download v1.0.0 --pattern 'cucuto-1.0.0.aab*' --dir /tmp/cucuto
   (cd /tmp/cucuto && sha256sum -c cucuto-1.0.0.aab.sha256)   # macOS: shasum -a 256 -c
   SUPPLY_JSON_KEY=~/cucuto-release-key/play-service-account.json fastlane supply --aab /tmp/cucuto/cucuto-1.0.0.aab
   ```

   It uploads the bundle, the release notes for its versionCode, the titles, descriptions and images in both
   languages, as a draft. `--skip_upload_images true --skip_upload_screenshots true` keeps the current images;
   `--validate_only true` checks without changing anything.

**Later, from CI** (not wired; only if the owner asks): a step after "Publish the release" would install fastlane
(`ruby/setup-ruby` and `gem install fastlane -v 2.241.1`) and run the command above with
`SUPPLY_JSON_KEY_DATA: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}`, skipped when the secret is empty. Drafts only, so a
bad upload never reaches anyone.

## If the signing key changes

This happens when moving from a debug build to the first release, or if the release key is ever lost. Switching
between Google Play and the APK from GitHub, Obtainium or F-Droid is not a key change: Play signs with the release key
too (ADR 0071).

**Android.** An APK signed by a different certificate is not an update: installing it fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. The old app has to be uninstalled first, which deletes its data: assignments,
installed packs, settings and the decision log. Export a Cucuto backup before uninstalling and restore it afterwards
(packs from a repository download again; local-only packs need their files picked again).

Debug builds are signed with a per-machine debug key (`~/.android/debug.keystore`). Every CI run generates a new
one, so a debug APK from CI never updates a debug APK built elsewhere, and no debug APK updates a release.

**LSPosed.** LSPosed keeps its module list by package. When the module's app is fully uninstalled, LSPosed forgets
it, including its enabled state and scope (as far as we know; not yet checked on the owner's phone). After installing
the APK with the new key: open LSPosed, enable Cucuto again, select **System Framework** as the scope, and reboot.

**Cucuto's own check.** The module sends events (recent senders, decision log, module status) to the app only after
checking the receiving app's signing certificate (`providerRefusal` in
[`EventReporter.kt`](../android/xposed/src/main/kotlin/app/mauri/cucuto/xposed/events/EventReporter.kt), ADR 0010):
the installed `app.mauri.cucuto` must hold every certificate that signed the APK the module was loaded from. The module
reads that certificate once after boot and keeps using it until the next reboot. So between installing an APK signed
by another key and rebooting, the module still running in `system_server` is the old one, and every delivery is
refused with "the event provider's app is signed by another key" (logged, rate-limited). The app shows no new
senders and no module status; alerts never depend on events, and contacts without an assignment (all of them, until
the backup is restored) keep WhatsApp's tone. The reboot that LSPosed needs anyway loads the new APK, reads its
certificate and fixes it. A regular update signed with the same key never hits this: the certificate stays the same.

**Website and Obtainium.** The release certificate is also pinned in `RELEASE_CERT_SHA256` of `web/src/lib/site.ts`,
which the Install page shows and puts into its Obtainium config. Update it with the repository variable. Obtainium
installs made from the old link refuse the new APK until their users update the expected certificate (or re-add the
app from the Install page).

**Rotating instead of replacing.** While the old key still exists, APK Signature Scheme v3 can rotate it: `apksigner
rotate` writes a lineage that proves the new key comes from the old one, and an APK signed with that lineage installs
as a normal update, keeping data and the LSPosed setup. `PackageManager.hasSigningCertificate` accepts past
certificates of the lineage, so event delivery keeps working until the reboot. The release workflow does not support
rotation today (AGP signs with one key); it would need an `apksigner sign --lineage` step. With a lost key, rotation is
impossible.
