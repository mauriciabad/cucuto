# 0021. Signed release APK on tag

- Status: accepted (defaults the owner may override, and the key the owner creates: #140); release notes from Conventional Commits instead of GitHub's generated list since ADR 0046
- Date: 2026-10-24
- Issue: #44 (feeds #39, the release-driven website)

## Context

M7 needs a signed release APK on GitHub Releases, linked from the website. The website already follows releases
(PR #94): `web/scripts/build-data.ts` takes the latest non-draft, non-prerelease `v*` release with an `.apk` asset,
and a release must trigger a site rebuild. The signing key is the app's identity for its whole life: Android installs
an update only when its certificate matches the installed app's, and Cucuto's module also checks the app's certificate
before delivering events (ADR 0010). Agents share the owner's GitHub access, so they must never create the key or
see it.

Guidelines read: Android's [Sign your app](https://developer.android.com/studio/publish/app-signing) (key valid for at
least 25 years; signing information out of the build files; a lost key can't be regenerated and blocks updates; a
leaked key lets others ship updates), GitHub's [Using secrets in GitHub
Actions](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets) (pass secrets
as environment variables or stdin, never on a command line; secrets can't be used in `if:`, check an environment
variable instead; binary files as Base64 secrets, decoded with `base64 --decode`) and GitHub's security hardening
guide (pin third-party actions to a full commit SHA).

## Decision

**A `v*` tag releases.** `.github/workflows/release.yml` runs on `push: tags: v*` only. It checks the tag format and
the four secrets first (a missing secret fails in seconds with an error and a step summary naming the setup script),
checks that the tagged commit is on `main`, builds `:app:assembleRelease`, verifies the APK, publishes the release and
dispatches `ci.yml` with `site_only=true` (a release created with `GITHUB_TOKEN` doesn't trigger `site-rebuild.yml`,
the same pattern as `packs-release.yml`). Tests and lint are not repeated: `ci-ok` already gated the commit on `main`.
A failed run is retried with "Re-run jobs"; an existing release only gets its assets replaced.

**Version from the tag.** `vMAJOR.MINOR.PATCH` (0 to 999 each, no leading zeros, no pre-release suffix) becomes
`-Pcucuto.version`, and the `cucuto.android.application` convention plugin overrides every variant's output with
`versionName = MAJOR.MINOR.PATCH` and `versionCode = MAJOR * 1_000_000 + MINOR * 1_000 + PATCH` through the AGP variant
API (`onVariants { outputs.versionCode/versionName }`), so `BuildConfig` and the manifest agree and the values in
`android/app/build.gradle.kts` stay the defaults for local and CI builds. The formula grows with every version and
stays under Android's 2,100,000,000 cap. Pre-releases are left out: the website ignores them, and supporting them
would cost two digits of the code. The workflow computes the same code and checks it with `aapt2 dump badging`.

**Signing through environment variables.** When `CUCUTO_RELEASE_KEYSTORE` is set, the convention plugin creates a
`release` signing config from it and `CUCUTO_RELEASE_KEYSTORE_PASSWORD`, `CUCUTO_RELEASE_KEY_ALIAS` and
`CUCUTO_RELEASE_KEY_PASSWORD` (any of them missing stops the build with their names). Without it nothing changes:
release builds stay unsigned, debug builds keep the debug key, `ci.yml` is untouched. The secrets are
`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`; they reach only
the check step and the build step, the keystore is decoded to `RUNNER_TEMP` and deleted when the step ends, the Gradle
cache is read-only and the configuration cache is off for that build, so nothing derived from the key is saved.
Repository secrets, not an environment with protection rules: an environment limited to `v*` tags adds setup but
doesn't stop anyone who can push a tag, and only the owner and the agents can. The owner can still add one (question
issue #140).

**Certificate pin.** The repository variable `RELEASE_CERT_SHA256` holds the certificate's SHA-256. The workflow
verifies the APK with `apksigner` (exactly one signer, not `CN=Android Debug`) and refuses to publish when the
certificate differs from the pin, so replacing the secrets with another key fails loudly instead of shipping an APK that
no existing install can update to. Without the variable it publishes with a warning.

**The owner creates the key.** `tools/release/setup-signing.main.kts` generates a PKCS12 keystore (RSA 4096, 50 years,
`CN=Cucuto`, random 256-bit password) outside the repository, refuses to overwrite one, makes the owner confirm the
backup, then sets the secrets and the pin with `gh` (values on stdin). `--existing` uploads an existing keystore.
`docs/releasing.md` has the steps, the manual equivalent and the backup instructions.

**Assets.** `cucuto-<version>.apk` and `cucuto-<version>.apk.sha256` (`sha256sum` format, so `sha256sum -c` works).
The notes start with the AI banner, list the checksum and the certificate fingerprint, and end with GitHub's generated
changelog. GitHub decides "latest" (packs releases are never latest, so it is always the newest app version).

**Pinned actions.** Every action in `release.yml` is pinned to a full commit SHA with its version in a comment, since
this workflow handles the key; Dependabot's `github-actions` updates keep them current. `ci.yml` keeps major tags.

## Consequences

- Releasing is `git tag vX.Y.Z && git push origin vX.Y.Z`; the website updates itself.
- Until the owner runs the setup script, a pushed `v*` tag fails at the first step with instructions.
- Switching from the debug build to the first release (and any future key change) means uninstall, reinstall,
  re-enabling the module in LSPosed and a reboot; the module refuses to deliver events to an app signed by another
  key until that reboot. Documented in `docs/releasing.md` ("If the signing key changes").
- Losing the key cannot be repaired. Key rotation (APK Signature Scheme v3 lineage) is possible only while the old key
  exists and would need an `apksigner` step in the workflow; not built.
- No AAB, no Play signing: the brief rules out the Play Store.
