# 0022. pack-spec in the browser: Kotlin/JS for the website's pack checks

- Status: accepted
- Date: 2026-10-24
- Issues: #116 (browser target), #49 (golden fixtures through every validator), #60 (owner decision), #117 (spike)

## Context

The owner chose option 1 on #60: the website validates packs with `shared/pack-spec` compiled for the browser, never a
TypeScript port ([ADR 0005](0005-shared-pack-spec-module.md), "Owner update"). The module's `commonMain` already has no
JVM APIs; only the `jvm()` target existed. Its two libraries publish both browser targets on Maven Central
(`io.github.optimumcode:json-schema-validator` 0.5.5 ships `-js` and `-wasm-js`; so does kotlinx.serialization).

Kotlin 2.4 offers two browser targets. What the docs say (read 2026-10-24):
[stability table](https://kotlinlang.org/docs/components-stability.html): Kotlin/JS **Stable** (since 1.3.0),
Kotlin/Wasm **Beta** (since 2.2.20);
[Wasm interop](https://kotlinlang.org/docs/wasm-js-interop.html): `@JsExport` only on top-level functions with
primitives, strings, `JsAny` and `JsReference`, arrays are copied, `.d.ts` generation is experimental;
[JS interop](https://kotlinlang.org/docs/js-to-kotlin-interop.html): `@JsExport` classes and functions with
generated TypeScript declarations, and `ByteArray` **is** an `Int8Array`;
[Wasm overview](https://kotlinlang.org/docs/wasm-overview.html): browsers need WasmGC and exception handling.

Measured on this module (the full validator reachable, production builds):

| Target | Raw | gzip | brotli |
| --- | --- | --- | --- |
| Kotlin/JS (whole-program ES module, minified by Vite) | 734 kB | 218 kB | 166 kB |
| Kotlin/Wasm (`wasmJs`, Binaryen-optimized `.wasm`) | 804 kB | 280 kB | 221 kB |

## Decision

- **`shared/pack-spec` gains a `js` target** (IR, ES modules, `binaries.library()`, whole-program granularity so the
  output is one `pack-spec.mjs`, plus `.d.mts` declarations). Not Wasm: size is no better, Kotlin/JS is Stable, pack
  bytes cross into Kotlin without a copy (`Uint8Array` → `Int8Array` view; packs can be tens of MB), exported classes
  get real TypeScript types, any current browser runs it, and the site's Content Security Policy (`script-src 'self'`)
  needs no `'wasm-unsafe-eval'`.
- **API** (`src/jsMain/.../PackSpecJs.kt`, small on purpose): `validateArchive(bytes)` for any pack or bundle `.zip`,
  `validateFiles(paths, contents)` for unpacked files (folder picker, the future pack creator #48),
  `validateDocument(kind, bytes)` for `repository`, `backup` and `backup-envelope`, and `packFormatVersion()`. Results
  carry `valid`, every issue (code, severity, stage, message, file, pointer), the sorted `errorCodes` and
  `warningCodes` that fixtures compare, and a summary of each pack. No `Long` in exported signatures.
- **DEFLATE in common code.** Browsers have no synchronous inflater (`DecompressionStream` is async; the validator is
  synchronous), so `zip/CommonInflater.kt` is a small RFC 1951 decoder following zlib's `puff.c`, with the same
  contract as the `java.util.zip` adapter. The JVM and Android keep `java.util.zip`; a JVM test checks both agree on
  stored, fixed and dynamic blocks and reject the same bad input.
- **No Node.js, npm or webpack in Gradle.** The browser module is just the output of `compileProductionLibraryKotlinJs`.
  The js target has no Kotlin tests (fixtures run under Bun, below), so the npm install the Kotlin plugin wires into
  `check` (Karma, webpack, a `tsc` check of the declarations) is disabled, and `NodeJsEnvSpec.download = false` keeps
  the plugin from adding a Node.js repository that `FAIL_ON_PROJECT_REPOS` would reject. Packer and the app still only
  build the jvm variant; packer's `check` keeps running the module's `check`.
- **Into the site:** `./gradlew syncWebPackSpec` (in `shared/pack-spec`) copies `pack-spec.mjs` and `pack-spec.d.mts` to
  `web/src/lib/pack-spec/generated/` (git-ignored). `web/src/lib/pack-spec/` types it (string unions, plain objects)
  and loads it with `import.meta.glob`, so it is a lazy chunk and the site still builds without a JDK: the checker
  then says it is not part of this build. CI never ships that: the `pack-spec-js` job compiles the module and uploads
  it, `web` declares `needs: [changes, pack-spec-js]`, downloads it, and builds with `CUCUTO_REQUIRE_PACK_SPEC=1`
  (`vite.config.ts` fails without it). `ci-ok` needs both jobs. The `web` path filter now includes `shared/**` and
  `spec/**`.
- **Where the site uses it:** a "Check a pack in your browser" section on the authors page (file picker, nothing
  uploaded), `/open/` (files the installed PWA is launched with), and the repository e2e test, which validates
  `/repo/index.json` with `validateDocument('repository')` instead of Ajv (the `ajv` dependency is gone).
- **Golden fixtures through every validator (#49):** packer's build runs them through the JVM module
  (`GoldenFixturesTest`), Android's `:core:pack` tests through the importer, and `web/tests/pack-spec.test.ts`
  (`bun run test:unit`, in the `web` job) through the browser build: archives with `validateArchive`, fixture
  folders with `validateFiles`, documents with `validateDocument`. All compare against the same
  `spec/fixtures/fixtures.json`, so the three report identical codes. `web/tests/pack-check.e2e.ts` runs the checker
  in Chromium under the real CSP.

## Consequences

- One implementation of the rules on every platform; a pack-spec change reaches the site on the next build with no
  TypeScript to update. Adding a JS-incompatible API to `commonMain` now fails `compileKotlinJs` (run by packer's
  `check` and by `pack-spec-js`).
- The site lazily loads about 220 kB gzipped the first time someone checks a file; the service worker precaches it
  with the rest of the build, so checks also work offline.
- Issue messages come from Kotlin and are English; the site says so and shows the stable codes, linked to the pack
  format's error table.
- If Kotlin/Wasm becomes Stable and its interop gets typed arrays without copies, switching is a build change plus the
  API file; the measurements above are the baseline for spike #117.
