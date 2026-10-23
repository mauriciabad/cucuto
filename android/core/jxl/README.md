# `:core:jxl`: JPEG XL decoding

Android 17 has no JPEG XL decoder, so Cucuto ships its own: [jxl-rs](https://github.com/libjxl/jxl-rs) (the decoder
Chrome and Firefox use) behind a small JNI crate. The decision and the measurements are in
[ADR 0006](../../../docs/decisions/0006-jpeg-xl-decoding.md); the prototype it came from is
[`spikes/jxl/`](../../../spikes/jxl/README.md).

## What it offers

- `JxlImage.readInfo(bytes)`: the header (size, alpha, animation, loop count, frame count, color space), no pixels.
- `JxlImage.open(bytes)`: frames decoded one at a time into an ARGB_8888 `Bitmap` (`decodeNextFrame`, `rewind`,
  cancellable `forEachFrame`). `decodeBitmap` returns the first frame; `verify` decodes every frame (the import check).
- `JxlCoilDecoder.Factory`: Coil 3 decoder, registered in the app's `ImageLoader` (`CucutoApplication`). A still
  image becomes a bitmap; an animation becomes a `JxlAnimatedDrawable` (one decoder, two bitmaps, decoded just in
  time; it respects the loop count and releases the decoder when stopped or finished).
- `JxlImageProbe` in `:core:pack` uses `verify`, so imports test-decode `.jxl` files with the same decoder.

Limits: 512 px per side by default (the pack limit, `Limits.MAX_BITMAP_SIDE_PX`), checked on the header before any
pixel memory is allocated (`JxlTooLargeException` carries the size); the native side never goes over 2048 px and also
sets a sample limit. Bitmaps are tagged with the image's color space (sRGB, linear sRGB, Display P3 or BT.2020; ICC
profiles show as sRGB).

## Never crash on bad input

`panic = "unwind"`, not `abort`. A panic inside jxl-rs on a hostile file must turn into an exception, not kill the app,
so every decoder call runs under `catch_unwind` twice: `guard` in `lib.rs` (which also poisons a decoder that panicked)
and jni's `EnvUnowned::with_env` at the JNI boundary. Bitmap pixels are unlocked by a drop guard, also while unwinding.
`abort` would save about 125 KB (7%) of the library, not worth turning any decoder bug into a crash. Kotlin wraps every
native error as `JxlException` (an `IOException`), and a missing library (another ABI) as `JxlUnavailableException`.
Allocation failure still aborts in Rust, which the size and sample limits rule out for valid-looking headers.

## Build

The Gradle task `:core:jxl:cargoNdk` (`CargoNdkTask` in `build-logic`) runs
`cargo ndk -t arm64-v8a -P 37 build --release --locked --lib` in `rust/` and wires the output into every variant's
`jniLibs` with `addGeneratedSourceDirectory`. Release profile: `opt-level = 3`, fat LTO, one codegen unit, stripped.
The library is about 1.8 MiB, arm64-v8a only.

- Reproducible: the compiler is pinned in `rust/rust-toolchain.toml`, dependencies in `rust/Cargo.lock` (`jxl =0.8.0`,
  `jni =0.22.4`), the NDK in `gradle/libs.versions.toml` (`ndk`, r30).
- Cached: the task is cacheable (inputs: crate sources and manifests, ABI, API level, NDK version), so an unchanged
  crate comes from the Gradle build cache; cargo's incremental state lives in `rust/target/` (git-ignored).
- Only APK packaging needs it: unit tests and Lint do not depend on the library.

One-time setup (rustup installs the pinned toolchain and target by itself on first use):

```sh
brew install rustup && rustup default stable
cargo install cargo-ndk
android sdk install ndk/30.0.16248370
```

CI (`android-assemble` in `.github/workflows/ci.yml`) does the same with `rustup toolchain install`, a prebuilt
cargo-ndk, `sdkmanager` and `Swatinem/rust-cache`.

## Tests

- JVM (`./gradlew :core:jxl:testDebugUnitTest`): the Kotlin logic against a fake native decoder (size limits before
  allocation, frame iteration, cancellation, close, verify), signature sniffing, the Coil factory, loop counting.
- Device (`src/androidTest`): the real library on images made by `generate-test-images.main.kts` (committed under
  `src/androidTest/assets/jxl/`). Lossless output matches the PNG source pixel for pixel, lossy output is within
  3/255 of libjxl's `djxl`, animation frames coalesce to their sources, Display P3 is tagged, oversized files fail from
  the header, and about 3,400 truncated or corrupted files only ever throw `JxlException`. Run on a phone:

```sh
./gradlew :core:jxl:assembleDebugAndroidTest
adb install -r -t core/jxl/build/outputs/apk/androidTest/debug/jxl-debug-androidTest.apk
adb shell am instrument -w app.mauri.cucuto.core.jxl.test/androidx.test.runner.AndroidJUnitRunner
adb uninstall app.mauri.cucuto.core.jxl.test
```
