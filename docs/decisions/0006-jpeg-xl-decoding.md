# 0006. JPEG XL decoding on Android: jxl-rs behind a small JNI layer

- Status: accepted
- Date: 2026-10-23
- Issue: #7 (spike), follow-up #77
- Prototype: [`spikes/jxl/`](../../spikes/jxl/README.md)

## Context

Packs recommend JPEG XL for sprites, animated sprites and covers. These images are at most 512 px per side, and some
are animated. The brief named jxl-coder plus its Coil plugin. It also allowed dropping JXL in the app and converting at
import if decoding became a burden. The owner asked for the Rust implementation if it is compatible ("jxl maybe use the
rust implementation better if compatible", [owner log](../owner-log.md)).

Options evaluated:

1. **Platform.** Android 17 has no public JPEG XL decoder. It is not on the
   [supported media formats](https://developer.android.com/media/platform/supported-formats) list or the
   [NDK ImageDecoder](https://developer.android.com/ndk/guides/image-decoder) list. On the owner's Pixel 10 Pro
   (Android 17, API 37), `ImageDecoder.isMimeTypeSupported("image/jxl")` is `false`. `ImageDecoder` fails with
   `unimplemented`, and `BitmapFactory` returns `null`. The phone does have a `/system/lib64/libjxl.so`, used
   internally for DNG, but it is a private platform library that apps cannot link.
2. **[jxl-rs](https://github.com/libjxl/jxl-rs) 0.8.0.** This is the JPEG XL project's Rust decoder, and the one
   Chrome and Firefox ship. It is mostly safe Rust, and any `unsafe` blocks are reviewed. Its CI runs the official
   conformance corpus (`main_level5`). It decodes animations with coalesced frames and per-frame durations, and it can
   scan frames and seek to keyframes. It has a C-free build and is BSD-3-Clause. Its API is 0.x and changes between
   minor versions.
3. **[jxl-coder](https://github.com/awxkee/jxl-coder) 2.6.1** with
   [jxl-coder-coil](https://github.com/awxkee/jxl-coder-coil) 2.6.1. This is libjxl (C++) plus brotli behind JNI,
   Apache-2.0. It always bundles the encoder. Its 3.0.0 line, still alpha (alpha05, September 2026), replaces libjxl
   with jxl-rs 0.7 plus its own Rust encoder. So the jxl-coder path also ends up on jxl-rs.

## Measurements

The tests used 512 × 512 images generated in code ([`make-test-images.main.kts`](../../spikes/jxl/make-test-images.main.kts)):

- a pixel-art sprite with alpha, lossless (`cjxl -d 0`, 489 B)
- a photo-like image, lossy (`-d 1`, 20.5 KB)
- a 12-frame lossless animation (5.7 KB)

Device results come from the release APK on a Pixel 10 Pro (Tensor G5, Android 17), with medians of 100 runs (30 for
animations). Host results come from an Apple M1 Pro.

| Metric | jxl-rs (this spike) | jxl-coder 2.6.1 | Platform PNG / WebP |
| --- | --- | --- | --- |
| arm64 native code added to the APK (stored uncompressed) | **1.80 MiB** (1 `.so`, 1,886,456 B; 0.9 MiB gzipped) | 4.94 MiB (7 `.so`, 5,179,096 B); 3.0.0-alpha05: 4.57 MiB | 0 |
| Sprite, lossless, open + decode to `Bitmap` | **6.7 ms** (6.2 ms into a reused bitmap) | 24.6 ms | 1.3 ms / 0.5 ms |
| Photo, lossy, open + decode | **4.7 ms** | 22.6 ms | 2.7 ms / 1.1 ms |
| Animation, all 12 frames | **55 ms** (4.6 ms/frame, one bitmap) | 92 ms (`getFrame`) | n/a |
| Animation, frame count | 0.03 ms (TOC scan, no pixels) | 1.5 ms (open) | n/a |
| Animation, loop back to frame 0 | 6.3 ms (`rewind`) | 9.0 ms (`getFrame(11)`) | n/a |
| Coil 3.6.3 end to end, sprite, no caches | **10.4 ms** | 27.3 ms (jxl-coder-coil) | 2.8 ms (PNG) |
| Pixel check | lossless sprite identical to the PNG source; lossy photo within 2/255 of libjxl | reference | n/a |
| 646 truncated or corrupted files, on device | 644 clean exceptions, 2 decoded, 0 crashes | 644 / 2 / 0 | n/a |
| Host (M1 Pro), one thread, sprite / photo | 8.0 ms / 5.1 ms; 0 panics in 12,814 mutated files | libjxl 0.12 `djxl`: 10.1 ms / 6.6 ms | n/a |

Other findings:

- **Multithreading is not worth it at 512 px.** A naive scoped-thread runner made device medians worse (19–21 ms),
  because the decoder calls the runner many times per frame. Decode on one thread, and let Coil decode several images
  in parallel.
- **Encoder effort matters more than the decoder.** Pixel art encoded with `cjxl -d 0 -e 1` is about 1 KB and decodes in
  1.7 ms on the host, 5× faster than the default effort (`-e 7`, 8.0 ms). Effort 9 is slower still (9.0 ms).
- **`opt-level = "s"`** only saves 0.18 MiB (1.63 MiB), so the speed-optimised build stays.
- **Licenses.** The whole Rust dependency tree is BSD-3-Clause, MIT, Apache-2.0 or Unlicense, which is
  GPL-3.0-compatible (checked with `cargo metadata`). jxl-rs also carries the JPEG XL royalty-free patent grant
  (`PATENTS`), the same one libjxl has. jxl-coder is Apache-2.0 plus BSD (libjxl, brotli), also compatible with GPLv3.
- **The library is 16 KB page aligned** and links only `libjnigraphics`, `libc`, `libm` and `libdl`.

## Decision

**Decode JPEG XL with jxl-rs through our own small JNI crate. Do not use jxl-coder.**

- A new Gradle module (working name `:core:jxl`) holds the Rust crate, the Kotlin wrapper and the Coil 3 decoder,
  following the spike's shape:
  - The crate's JNI surface is about 6 functions.
  - Frames decode straight into an ARGB_8888 `Bitmap`.
  - Static files become a bitmap.
  - Animated files become a just-in-time, double-buffered drawable, so memory stays at 2 bitmaps per animation.
  - The importer (#13) uses the same wrapper to test-decode `.jxl` files.
- Build only for arm64-v8a. Use NDK r30 (30.0.16248370, the newest stable; r28 stops at API 35). Build with `cargo-ndk`
  from a Gradle task wired in with `addGeneratedSourceDirectory`. Set `opt-level = 3`, `lto`, `codegen-units = 1`,
  `strip` and `panic = "unwind"`. Pin jxl-rs to an exact version.
- Safety: every JNI entry point catches errors and panics and turns them into exceptions. The Kotlin side treats any
  exception as a broken image. Reject images over 2048 px per side, and set a sample limit before allocating. Packs are
  capped at 512 px.
- Color: the decoder outputs sRGB for lossy (XYB) files and the embedded profile for lossless files. Non-sRGB lossless
  files either get their `Bitmap` tagged with the right `ColorSpace` or are converted to sRGB at import (#77).
- The packer encodes lossless sprites with `cjxl -d 0 -e 1` or `-e 2` by default. This trades about 2× more bytes, still
  around 1 KB, for 5× faster decoding.

Why not jxl-coder: jxl-coder 2.6.1 is 2.7× larger and 3.5–5× slower (2.6× slower through Coil), and it ships an encoder we do not need. Its future,
3.0, is the same jxl-rs decoder, but in an alpha that is still larger (4.57 MiB). Owning about 600 lines of Rust and
Kotlin is cheaper than tracking an alpha.

## Fallback

If jxl-rs causes problems in the field (crashes, regressions, or size pressure), fall back in this order:

1. **One bad file:** show a placeholder and warn at import. The pack still installs.
2. **Decoder risk at runtime:** transcode at import. Decode each `.jxl` once with the same library, store it as
   lossless WebP (`Bitmap.compress(WEBP_LOSSLESS)`) or PNG in the installed pack, and never decode JXL at display time.
   Runtime cost then equals WebP: 0.5–1.1 ms on the device.
3. **Remove the native library:** the app stops accepting JXL. The importer rejects `.jxl` with an explanation, and the
   packer (`djxl`) and the web pack creator (WASM) convert JXL to WebP or PNG before packaging. A device can only
   convert at import while it still has a decoder.

## Consequences

- The Android build and CI need the Rust toolchain: `rustup` with the `aarch64-linux-android` target, `cargo-ndk`,
  and NDK r30. A release build of the crate takes about 30 s from cold.
- The APK grows by about 1.8 MiB, uncompressed so the library can be memory-mapped. Setting
  `useLegacyPackaging = true` would shrink that to about 0.9 MiB, at the cost of extracting the library on install.
- JXL decoding costs about 2–5× as much as PNG or WebP per sprite, at single-digit milliseconds, on Coil's background
  dispatcher with its memory cache. That is fine for grids of sprites. Covers and sprites stay at most 512 px.
- jxl-rs is pre-1.0 and its API moves between minor versions. Dependabot gets a `cargo` entry (#77), and the
  crate's small surface keeps each bump cheap.
