# F-Droid recipe

[`app.mauri.cucuto.yml`](app.mauri.cucuto.yml) is Cucuto's build recipe for F-Droid's main repository. It goes in
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) as `metadata/app.mauri.cucuto.yml`. This copy is the one we
maintain. Once F-Droid merges theirs, changes go to fdroiddata through a merge request. Plan and status: #275,
[docs/distribution.md](../docs/distribution.md#f-droid).

## What the recipe does

- **Builds a release tag from source** on F-Droid's build server (Debian): JDK 21, Gradle from the wrapper's
  version, `assembleRelease` in `android/app`. `:core:jxl` needs Rust: rustup (F-Droid's `rustup` srclib) installs
  the toolchain pinned in `android/core/jxl/rust/rust-toolchain.toml`, then `cargo install --locked
  cargo-ndk@4.1.2`. The NDK is r30 (`30.0.16248370`), the `ndk` value in `android/gradle/libs.versions.toml`.
- **Ships Cucuto's own signature** (reproducible builds, [docs/reproducible-builds.md](../docs/reproducible-builds.md)).
  `Binaries:` points at the GitHub release APK and `AllowedAPKSigningKeys:` pins the release certificate. F-Droid
  builds the APK, copies the signature from the GitHub APK onto it ([apksigcopier](https://github.com/obfusk/apksigcopier))
  and publishes it only if the result is the same file. If it isn't, F-Droid skips that version. It never signs Cucuto
  with its own key, so people can move between GitHub, Obtainium and F-Droid without reinstalling, and one Android
  developer verification registration (#358) covers all three.
- **Source changes, all build-neutral:**
  - `prebuild` deletes the foojay toolchain resolver line from `android/settings.gradle.kts`. F-Droid's scanner refuses
    it, and the build doesn't need it because the server has JDK 21.
  - `prebuild` writes `cucuto.version=<tag>` to `android/gradle.properties`, the same value `release.yml` passes as
    `-Pcucuto.version`.
  - `scandelete` removes `spec/fixtures` (test packs, including `.zip` files the scanner flags). The app build doesn't
    use them.
- **Updates itself on tags:** `UpdateCheckMode: Tags ^v\d+\.\d+\.\d+$` and `AutoUpdateMode: Version`. F-Droid's bot
  reads `versionCode` and `versionName` from `android/app/build.gradle.kts` at each new tag. The release PR sets them
  (`tools/release/release-notes.main.kts`, [docs/releasing.md](../docs/releasing.md)), and `release.yml` warns if a tag
  lacks them. The bot copies the last build entry for each new version, so the commands stay the same.
- **No anti-features.** `NonFreeDep` ("needs a non-libre app to work", [F-Droid's
  definition](https://f-droid.org/docs/Anti-Features/)) was declared while Cucuto only worked with WhatsApp. Since
  #374 ([ADR 0081](../docs/decisions/0081-signal-support.md)) it works fully with Signal or Molly, which are free
  software, so WhatsApp is optional and the recipe drops it. `NonFreeNet` ("depends entirely on a proprietary network
  service") doesn't fit either: Cucuto never talks to any messenger's servers, it only changes how notifications sound
  on the phone. `Tracking` and `Ads` don't apply (no trackers, no ads). Root and LSPosed aren't anti-features.
- Name, summary, description, icon, screenshots and changelogs come from `fastlane/metadata/android/`. F-Droid wants
  none of them in the recipe.

## Inclusion check (2026-10-24, v0.2.2)

- **Release dependencies** (`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`): AndroidX, Compose,
  Kotlin and kotlinx, Hilt/Dagger, Room, DataStore, Coil, OkHttp/Okio, libxposed (Apache-2.0), MaterialKolor and a few
  small free libraries. No Google Play services, Firebase, GMS, Credential Manager or Google ID. Repositories: Google's
  Maven and Maven Central only.
- **Google Drive sign-in** is not planned (#188, ADR 0040): it needs Google Play services. `:core:drive` was deleted
  on 2026-10-25.
- **Rust crate** (`:core:jxl`): jxl-rs and its dependencies come from crates.io under free licenses, pinned by
  `Cargo.lock`.
- **`fdroid scanner`** (fdroidserver 2.4.5) on the v0.2.2 source with this recipe: no problems. Without the two
  `prebuild`/`scandelete` fixes it reports the foojay plugin and 13 fixture zips. On the published
  `cucuto-0.2.2.apk` (dexdump for known non-free classes, extra signing blocks): no problems.
- **`fdroid lint`** and **`fdroid rewritemeta`**: clean. The file is in `rewritemeta`'s canonical form.

## Not verified yet

- **`fdroid build` on F-Droid's build server image.** It needs Debian x86_64 (Docker or a VM), and `fdroid build`
  runs the recipe's commands on the host, so it wasn't run on the Mac. What ran instead: v0.2.2 cloned fresh, the
  recipe's source changes applied, and `assembleRelease` built in `android/app` on macOS with Homebrew's OpenJDK 21
  and the pinned Rust and NDK, then compared with the published APK. **Every file matched except
  `lib/arm64-v8a/libcucuto_jxl.so`** (same size, different code layout). That includes `classes*.dex`, so a JDK 21
  from another vendor (Homebrew's, not the Temurin that releases use) gives the same dex. The Rust library differs only
  across host systems: `release.yml`'s `reproducible` job rebuilt v0.2.2 on Linux and matched. The likely cause is that
  cargo hashes `rustc -vV`, including the host triple, into symbol names, which changes code order. F-Droid's server is
  x86_64 Linux like GitHub's runner, so the host triple is the same (`x86_64-unknown-linux-gnu`). The real test is the
  fdroiddata merge request's CI, which runs `fdroid build` and reports any mismatch.
- **JDK vendor on the server.** The server uses Debian's OpenJDK 21. The macOS rebuild suggests the vendor doesn't
  matter. If the merge request's CI reports a mismatch in `classes*.dex` anyway, suspect this first.

## When to update this file

- **NDK or cargo-ndk version** changes (`ndk` in `libs.versions.toml`, `cargo-ndk@` in `release.yml`): change `ndk:`
  and the `cargo install` line here and in fdroiddata. Auto-update copies the old values.
- **JDK** major version changes: change the `sudo` lines.
- **Signing key** changes (ADR 0021): change `AllowedAPKSigningKeys:`. F-Droid then stops publishing until the merge
  request lands.
- **Release asset name or tag format** changes: change `Binaries:` and the tag pattern (docs/distribution.md, "Keep
  these stable").
- The Rust toolchain doesn't need a change: the recipe reads it from `rust-toolchain.toml`.

## Check it locally

```sh
python3 -m venv /tmp/fdroid && /tmp/fdroid/bin/pip install fdroidserver
git clone --depth 1 https://gitlab.com/fdroid/fdroiddata.git && cd fdroiddata
cp ../cucuto/fdroid/app.mauri.cucuto.yml metadata/
/tmp/fdroid/bin/fdroid lint app.mauri.cucuto
/tmp/fdroid/bin/fdroid rewritemeta app.mauri.cucuto   # then copy the file back if it changed
/tmp/fdroid/bin/fdroid scanner -e app.mauri.cucuto    # clones Cucuto into build/ and scans it
```

## Submitting (owner)

The steps are in #275. The merge request needs a GitLab account, so the owner opens it.

Draft text for the merge request's "Abstract" section:

> Cucuto gives each contact a voice: when they message on WhatsApp or Signal (or Molly), the phone plays a random clip from the character
> you assigned them and fires that character's vibration; when they call, their ringtone plays. Characters come from
> sound packs that anyone can make (in the app or on the website) and import; the app ships empty. It is an LSPosed
> module that runs only in the system framework (system_server) and swaps the sound and vibration of those apps'
> notifications. It reads no message content. It needs Android 17, root and LSPosed. Screenshots:
> `fastlane/metadata/android/en-US/images/phoneScreenshots/`.
>
> Why: a fixed tone per chat tells you little. Cucuto plays a different clip each time, with matching haptics, from
> packs people share, and keeps everything in one backup. Nothing similar is in F-Droid as far as we know.
>
> The build is reproducible and checked on every release (`docs/reproducible-builds.md`). `Binaries` and
> `AllowedAPKSigningKeys` are set so F-Droid ships the upstream signature. No anti-feature is declared: the app works
> fully with Signal or Molly, so WhatsApp is optional. The code is written by AI agents (Claude) under the owner's direction.
