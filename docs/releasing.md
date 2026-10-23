# Releasing Cucuto

A release is a signed APK on [GitHub Releases](https://github.com/mauriciabad/cucuto/releases), built by
[`.github/workflows/release.yml`](../.github/workflows/release.yml) when a `v*` tag is pushed. The website's Download
APK button follows it automatically. Why it works this way: [ADR 0021](decisions/0021-signed-release-apk.md). Key setup status: #140.

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
2. Tag the commit on `main` and push the tag:

   ```sh
   git switch main && git pull
   git tag v0.1.0
   git push origin v0.1.0
   ```

3. The workflow checks the tag and the secrets, builds `:app:assembleRelease` with the key, verifies the signature
   (one signer, not a debug key, the pinned certificate) and the version with `apksigner` and `aapt2`, then publishes
   the release `Cucuto 0.1.0` with `cucuto-0.1.0.apk` and `cucuto-0.1.0.apk.sha256`. The notes start with the AI
   banner, list the checksum and the certificate fingerprint, and end with GitHub's generated changelog.
4. It then starts `ci.yml` on `main` with `site_only=true`, which rebuilds and redeploys the website with the new
   version, size and download link (`web/scripts/build-data.ts`). Releases created with `GITHUB_TOKEN` don't trigger
   `site-rebuild.yml`, hence the explicit dispatch.

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

## If the signing key changes

This happens when moving from a debug build to the first release, or if the release key is ever lost.

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

**Rotating instead of replacing.** While the old key still exists, APK Signature Scheme v3 can rotate it: `apksigner
rotate` writes a lineage that proves the new key comes from the old one, and an APK signed with that lineage installs
as a normal update, keeping data and the LSPosed setup. `PackageManager.hasSigningCertificate` accepts past
certificates of the lineage, so event delivery keeps working until the reboot. The release workflow does not support
rotation today (AGP signs with one key); it would need an `apksigner sign --lineage` step. With a lost key, rotation is
impossible.
