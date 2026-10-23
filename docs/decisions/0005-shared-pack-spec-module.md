# 0005. One Kotlin Multiplatform pack-spec module for packer and the app

- Status: accepted
- Date: 2026-10-23
- Issues: #4, #5, #13 (moved to `shared/pack-spec/`)

## Context

The pack format ([`docs/pack-format.md`](../pack-format.md)) is enforced in three places: `packer` (JVM CLI), the
Android importer (`android/core/pack`) and the website's pack creator (TypeScript). The brief requires that a pack made
anywhere imports everywhere, and that all three run the same golden fixtures. Two of the three are Kotlin, so the
Kotlin rules should exist once.

JSON Schema validation is the part most likely to drift. The usual JVM library, networknt `json-schema-validator`,
is JVM-only and its 3.x line depends on Jackson 3, which is heavy for an Android app and impossible in common code.

## Decision

- **`tools/packer/pack-spec` is a Kotlin Multiplatform module** (only the `jvm()` target for now) that holds every
  validation rule: error codes, limits, path and file-type rules, a zip central-directory reader, header parsers for
  audio durations and image sizes, the SVG safety check, SPDX expressions, SemVer, manifest models, and the staged
  `PackValidator` / `DocumentValidator`.
- **`commonMain` uses no JVM APIs.** Platform work sits behind small interfaces: `PackSource` (files of a folder,
  archive or memory), `RandomAccessInput` (archive bytes) and `RawInflater` (DEFLATE). `jvmMain` provides
  `FolderPackSource`, `FileInput` and a `java.util.zip.Inflater` adapter, which also work on Android.
- **Schemas are embedded at build time.** A Gradle task turns `spec/*.schema.json` and `spec/error-codes.json` into
  Kotlin string constants, so `spec/` stays the single source and common code needs no resources.
- **JSON Schema validation uses [`io.github.optimumcode:json-schema-validator`](https://github.com/OptimumCode/json-schema-validator)**
  (MIT, Kotlin Multiplatform, built on kotlinx.serialization `JsonElement`, draft 2020-12 compliant per Bowtie)
  instead of networknt, so the same validator runs in common code on the JVM and on Android.
- **Sharing with Android:** when `android/core/pack` is built, move the module to `shared/pack-spec/` (a standalone
  Gradle build) and include it from both `android/settings.gradle.kts` and `tools/packer/settings.gradle.kts` with
  `includeBuild`, adding an `androidTarget()` (or keeping `jvm()`, which Android can consume). The Android importer
  adds what only the device can do on top: decoding every clip and image with `MediaExtractor` / `MediaCodec` and the
  image decoders, installing, and the runtime codes (downloads, backups, versions).
- **The TypeScript site** does not share code. It validates with the same schemas (Ajv in 2020-12 mode) and ports the
  non-schema rules, and it must pass the same `spec/fixtures/fixtures.json`.
  *Superseded by the owner's answer on #60 (option 1): the website reuses this module compiled for the browser
  (Kotlin/Wasm or Kotlin/JS) instead of a TypeScript port; see "Owner update" below and #116.*

## Consequences

- One Kotlin implementation of every rule, tested against all golden fixtures in `./gradlew build` of packer.
- The validator never needs ffmpeg: durations and dimensions come from container headers (Ogg, WAV, FLAC, MP3, ADTS,
  MP4, PNG, JPEG, GIF, WebP, AVIF, JPEG XL), so results are deterministic across machines. `packer validate --deep`
  and the app add a real decode on top.
- Moving the module out of `tools/packer` is a mechanical change (directory move plus `includeBuild`), done in the
  first Android importer PR.

## Update (#13)

The module now lives in `shared/pack-spec/`, a standalone Gradle build (its own wrapper and version catalog) that
`tools/packer` and `android/` include with `includeBuild`; both depend on `app.mauri.cucuto:pack-spec`, which Gradle
substitutes with the included project. It keeps the single `jvm()` target: Android consumes the JVM variant directly,
so an `androidTarget()` (and AGP inside the shared build) would add build complexity without new capabilities while
the module has no Android-specific code. Packer's `check` runs the module's tests, and `:core:pack` runs every golden
fixture through the Android importer ([ADR 0008](0008-pack-importer.md)). The importer also uses two additions made
for it: `PackManifest.mediaReferences()` and `DurationRules`.
- The optimumcode validator is younger than networknt. If it ever blocks us, swapping it touches only
  `SpecSchemas.kt`; the fixtures catch any behavior change.

## Owner update (2026-10-23, #60)

The question offered: 1. share the pack validator as a Kotlin Multiplatform module compiled to JS/Wasm for the
website; 2. rewrite the site in Kotlin; or keep. The owner answered:

> Actually if we can use kotlin for the PWA we may be able to reuse code and make our codebase simpler to mantain and work on. maybe webassembly helps

> I want 1

So `shared/pack-spec` gains a browser target and the website (the PWA pack creator and any other pack check on the site) calls it
instead of a TypeScript validator; there is one implementation of the rules, run by packer, the app and the site
against the same fixtures (#116). The site itself stays SvelteKit. How much more of the PWA creator can be shared
Kotlin is spike #117.

## Update (#116)

Done in [ADR 0022](0022-pack-spec-in-the-browser.md): the module now also has a `js` target (Kotlin/JS, chosen over
Kotlin/Wasm), with a small exported API and a common-code DEFLATE decoder for the browser. The website loads it from
`web/src/lib/pack-spec/`, and CI runs every golden fixture through the browser build as well.
