# JPEG XL decoding spike (issue #7)

Throwaway prototype that answers one question: how should the Cucuto Android app decode JPEG XL
sprites and covers (up to 512 px, static and animated)? The decision and the numbers live in
[ADR 0006](../../docs/decisions/0006-jpeg-xl-decoding.md). Production code is written fresh in the
`android/` project; nothing here is imported from it.

## What it contains

- `rust/`: crate `cucuto-jxl` (GPL-3.0-or-later). It wraps [jxl-rs](https://github.com/libjxl/jxl-rs)
  0.8.0, the decoder Chrome and Firefox ship.
  - `src/lib.rs`: a small API (open, info, next frame into a caller buffer, rewind, frame scan). It
    outputs premultiplied RGBA_8888 and rejects images over 2048 px per side before allocating.
  - `src/android.rs`: JNI entry points (`jni` 0.22). Every call runs under `catch_unwind`, so
    errors and panics become `RuntimeException`. Pixels go straight into the `Bitmap` through
    `AndroidBitmap_lockPixels`.
  - `src/bin/jxl-bench.rs`: host benchmark plus a mutation pass (truncated and corrupted files)
    that counts clean errors against panics.
- `android/`: a standalone test APK (`app.mauri.cucuto.jxlspike`, minSdk 37, arm64-v8a only).
  - `JxlImage.kt`: the Kotlin wrapper.
  - `JxlRsCoilDecoder.kt`: a Coil 3 `Decoder`. Static files become a bitmap. Animated files become a
    double-buffered drawable that decodes each frame just in time.
  - `BenchActivity.kt`: runs every measurement on launch. It compares jxl-rs, jxl-coder 2.6.1 and
    the platform PNG/WebP decoders.
  - The Gradle build calls `cargo ndk` through a small task (`CargoNdkTask`) and wires the output
    into the APK with `addGeneratedSourceDirectory`.
- `make-test-images.main.kts`: generates the test images from code (pixel-art sprite, photo-like
  noise, 12-frame animation) and encodes them with `cjxl`, `cwebp` and `img2webp`. The images are
  build output and are never committed.

## Run it

```sh
# toolchain (once)
brew install rustup && rustup default stable && rustup target add aarch64-linux-android
cargo install cargo-ndk
android sdk install ndk/30.0.16248370

cd spikes/jxl
kotlin make-test-images.main.kts                       # -> build/test-images/

# host benchmark + robustness
(cd rust && cargo run --release --bin jxl-bench -- ../build/test-images/{sprite,photo,anim}.jxl)

# device benchmark (standalone app, touches nothing else on the phone)
cd android
./gradlew :app:assembleRelease                          # builds the Rust crate too
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -W -n app.mauri.cucuto.jxlspike/app.mauri.cucuto.jxl.BenchActivity
adb shell cat /sdcard/Android/data/app.mauri.cucuto.jxlspike/files/results.txt
adb uninstall app.mauri.cucuto.jxlspike                 # when done
```

The release build is debug-signed and exists only so that jxl-coder resolves its release AAR. Its
debug AAR ships unoptimised native code.
