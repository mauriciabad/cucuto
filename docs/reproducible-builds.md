# Reproducible builds

A build is reproducible when anyone who builds a release tag gets the same APK as the published one, apart from the
signature. It lets people (and F-Droid or IzzyOnDroid, should Cucuto ever be listed there) check that the APK on GitHub
Releases comes from the source in this repository and nothing else. Decision: [ADR 0046](decisions/0046-distribution.md).

## Status

**Expected to be reproducible from the next release on (the first built with the fixes below); CI checks every release.** v0.1.1 and v0.1.2 are not.

Rebuilding `v0.1.2` on a Mac (2026-10-24, Apple silicon, JDK 21, the pinned Rust and NDK) and comparing every file
inside the APK with the published `cucuto-0.1.2.apk` (built on GitHub's Ubuntu runner):

| Part of the APK | Same bytes? | Why |
| --- | --- | --- |
| `classes*.dex` (R8) | yes | R8 is deterministic for the same inputs and version. |
| `resources.arsc`, `res/`, `AndroidManifest.xml` | yes | AGP writes them deterministically. |
| `assets/dexopt/baseline.prof`, `.prof` metadata | yes | |
| `META-INF/` (version control info, service files) | yes | The VCS info is the tagged commit. |
| Zip timestamps and order | yes | AGP writes fixed timestamps (1981-01-01) and a stable order. |
| `lib/arm64-v8a/libandroidx.graphics.path.so` | yes | |
| `lib/arm64-v8a/libcucuto_jxl.so` (Rust, ADR 0006) | **no** | Panic messages embed the absolute paths of the dependencies' sources under `CARGO_HOME` (`/home/runner/.cargo/registry/…` vs `/Users/…/.cargo/registry/…`). |
| `lib/arm64-v8a/libdatastore_shared_counter.so` (from AndroidX DataStore) | **no** | AGP strips native libraries with the `llvm-strip` of whatever NDK it finds when the app doesn't pin one. NDK 28 and the CI runner's NDK lay out `.shstrtab` differently (5 bytes). Stripping the same input with NDK 28 reproduced the Mac build byte for byte. |

**v0.2.2 on macOS (2026-10-24, #275):** rebuilt on Apple silicon with Homebrew's OpenJDK 21 and the F-Droid recipe's
source changes. Every file matched the published APK except `libcucuto_jxl.so`: same size, different code layout. The
Linux rebuild in CI matched, so the cause depends on the host. Most likely cargo hashes `rustc -vV`, host triple
included, into symbol names. Linux x86_64 builders (GitHub, F-Droid) aren't affected. Rebuilding on another OS gives
a different Rust library until this is fixed.

## Fixes (from the release after v0.1.2)

- **Rust paths.** `CargoNdkTask` passes `--remap-path-prefix` for `CARGO_HOME` (to `/cargo`) and the crate directory
  (to `/cucuto-jxl`), through `CARGO_ENCODED_RUSTFLAGS`. The crate itself is already deterministic: pinned compiler
  (`rust-toolchain.toml`), `Cargo.lock` with `--locked`, `codegen-units = 1`, fat LTO, `strip = true`, pinned NDK
  linker. The standard library's paths are already remapped by rustup's builds (`/rustc/<hash>/…`).
- **Stripping.** The `cucuto.android.application` convention plugin sets `ndkVersion` to the same NDK as `:core:jxl`
  (`ndk` in `libs.versions.toml`, r30), so every machine strips with the same `llvm-strip`.

## The CI check

The `reproducible` job of [`release.yml`](../.github/workflows/release.yml) runs after a release is published. On a
fresh runner it checks out the tag into another directory, points `CARGO_HOME` elsewhere, builds
`:app:assembleRelease` without the signing key and without any Gradle or cargo cache, downloads the published APK and
compares the SHA-256 of every file inside both. The APK Signing Block, outside the zip entries, is the only part
allowed to differ; with `minSdk 37` there are no v1 signature files in `META-INF/`. A difference fails that job (the
release stays published) and the job summary lists the files.

The check runs on the same OS and runner image as the release, so it catches path, timestamp and cache leaks, not
differences between operating systems. A cross-OS rebuild (the v0.1.2 table above) is a manual check for now.

Once Cucuto is on F-Droid (#275), F-Droid's build server rebuilds every tag on Debian with the recipe in
[`fdroid/`](../fdroid/README.md) and compares it the same way (apksigcopier). A mismatch there means F-Droid skips
that version, so it doubles as the cross-OS check.

## Check a release yourself

You need the tools from [CONTRIBUTING.md](../CONTRIBUTING.md) (JDK 21, the Android SDK with NDK r30, rustup and
cargo-ndk 4.1.2).

```sh
v=0.2.0   # the release to check
git clone https://github.com/mauriciabad/cucuto && cd cucuto
git switch --detach "v$v"
(cd android && ./gradlew :app:assembleRelease -Pcucuto.version="$v" --no-build-cache)
gh release download "v$v" --repo mauriciabad/cucuto --pattern "cucuto-$v.apk"
mkdir published rebuilt
unzip -q "cucuto-$v.apk" -d published
unzip -q android/app/build/outputs/apk/release/app-release-unsigned.apk -d rebuilt
diff -r published rebuilt && echo reproducible
```

For a byte-for-byte check of the whole file, copy the signature onto the rebuild with
[apksigcopier](https://github.com/obfusk/apksigcopier) (`apksigcopier compare "cucuto-$v.apk" --unsigned
android/app/build/outputs/apk/release/app-release-unsigned.apk`), which is how F-Droid verifies reproducible builds.

## If the check fails

Usual causes, in the order they have shown up elsewhere:

1. A native library: a new Rust dependency with a build script that embeds paths or times, or an AAR whose `.so` AGP
   strips (pinned NDK missing on the machine).
2. A Gradle task that writes the build time, the user name or an absolute path into a resource or `BuildConfig`.
3. A different JDK, Gradle, AGP, R8, Rust or NDK version than the tag pins.
4. Baseline profiles from an unpinned dependency version.
