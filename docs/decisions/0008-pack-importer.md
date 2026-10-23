# 0008. Pack importer: copy, validate, stage, decode, rename

- Status: accepted
- Date: 2026-10-23
- Issue: #13

## Context

`:core:pack` imports packs and bundles from files picked with the Storage Access Framework, the share sheet, "Open
with" and repository downloads ([brief](../project-brief.md), [pack format](../pack-format.md)). It must reject
everything the format rejects, with the same codes as packer and the website, never leave a half-installed pack
behind, update packs in place without breaking assignments, and keep IO off the main thread. The format rules already
live in the shared pack-spec module ([ADR 0005](0005-shared-pack-spec-module.md)).

## Decision

**One pipeline, two calls.** `PackImporter.prepare(source)` and `PackImporter.install(prepared, selection)` return cold
`Flow<ImportEvent>`s: progress (`Copying`, `Validating`, `Extracting`, `Decoding`, `Installing`), then one
`ImportResult` (`Ready`, `Invalid` with the full `ValidationReport`, `Installed`, or `Failed` for source and storage
problems). The UI shows `Ready` (packs, warnings, `InstallAction` per pack), lets the user pick packs of a bundle and
confirm replacements and downgrades (`PackSelection`), then installs or discards. All IO runs on an injected IO
dispatcher; cancelling a flow deletes what it wrote.

**Sources.** `ImportSource` wraps a `content://` Uri (SAF, share sheet, "Open with") or a file (finished downloads).
Names and MIME types are never trusted: any zip works and `pack.json` or `bundle.json` at its root decides what it is
(owner rule, #79: files are `<name>.soundpack.zip`, pickers ask for `application/zip`).

**Copy first.** The source is copied to `cache/pack-import/` with a size cap (2 GiB bundle limit plus 64 MiB of zip
overhead) before anything is parsed. Zip validation needs random access to the central directory, SAF streams may be
remote, slow or non-seekable, and a private copy cannot change between validation and extraction.

**Validate with pack-spec, then extract with the same parser.** The copy is opened with pack-spec's `ZipArchive` and
checked by `PackValidator` (archive, layout, manifest, references and media header stages). Files are then extracted
with the same `ZipArchive` that validated them, so there is no parser differential (two zip readers disagreeing about
the entries). Only the manifest, referenced media and text files (licenses) are extracted; ignored entries and
unreferenced files never reach storage. Each output path is checked again to stay inside its folder (defense in depth
against zip slip). Free space is checked before extracting.

**Platform decode on the staged files.** Every clip, ringtone and image is decoded with the platform: audio with
`MediaExtractor` and `MediaCodec` (clips completely; ringtones their first and last 2 s, which proves the decoder,
the index and the tail without spending seconds per file), bitmaps with `ImageDecoder`. Decoded durations and sizes
go through the same rules as the header checks (`DurationRules` in pack-spec). Failures are `MEDIA_UNDECODABLE` (or
the duration and size codes), and the whole import is `Invalid`, as for any other error (one broken pack rejects
its whole bundle, as the format says; question #83). SVG has no platform decoder:
pack-spec's parser and safety rules are the check. JPEG XL has none on Android 17 ([ADR 0006](0006-jpeg-xl-decoding.md));
`ImageProbe` gets the jxl-rs decoder in #77, and until then JXL files get the header checks only.

**Store layout and atomic installs.** `PackStore` owns `files/packs/`:

```
files/packs/<pack id>/<version>/   pack.json and files, relative to the pack's root (bundles install each pack alone)
files/packs/<pack id>/active        the version in use (one line, written to a temporary file and renamed)
files/packs/.staging/<session>/     packs being prepared (same file system as the store)
files/packs/.trash/                 replaced or uninstalled folders, deleted right after the move
```

Packs are extracted and decoded in `.staging/` during `prepare`, so `install` only renames folders. A higher version
is committed next to the old one; `activate` switches the `active` pointer and only then deletes the other versions,
so a clip that is playing never loses its file mid-update. `install(activate = false)` keeps the old version active
until the caller switches (for example after asking what to do with contacts of a character the update removes).
The same version (`PACK_SAME_VERSION`) and lower versions (`PACK_DOWNGRADE`) are installed only when confirmed in the
`PackSelection`; the decision is re-checked under a lock at install time. Uninstalling moves `<pack id>/` to the
trash in one rename. `deleteLeftovers()` at startup removes staging and trash folders left by process death.

## Consequences

- The golden fixtures run through the real pipeline in `:core:pack` unit tests (folders are zipped first), with header
  based probes standing in for the platform decoders on the JVM. Instrumented tests decode every fixture format and
  import packs and bundles through a Uri on a device; CI compiles them (`assembleDebugAndroidTest`).
- An import needs about twice the archive size in free space for a moment (the cached copy and the staged files); the
  copy is deleted as soon as extraction ends.
- pack-spec reads each extracted file into memory (up to 100 MiB for audio). Streaming extraction is a follow-up
  (#84).
- `:core:data` (#14) can mirror `PackStore` state in Room, but the folders and `active` pointers are the truth about
  which files exist.
