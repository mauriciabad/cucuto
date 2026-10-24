# 0058. Create page tools: drafts, editing packs, covers, custom haptics, bundles and the repository helper

- Status: accepted (defaults open for the owner in the `question` issue linked from #191)
- Date: 2026-10-24
- Issue: #191 (follow-ups to #48 and [ADR 0038](0038-web-pack-creator.md))

## Context

The first Create page (#48) makes one pack in one sitting: no cover, `auto` haptics only, nothing saved across a
reload, no way to change a pack once downloaded, and the brief's bundle and "host your own repository" helpers left to
`packer` (which needs Java). The app's creator meanwhile gained "Duplicate and edit" with pass-through data (#225,
[ADR 0049](0049-creator-haptics-and-pass-through.md)) and its haptic editor gained "Copy for AI" and "Paste pattern"
(#227, [ADR 0053](0053-ai-written-haptic-patterns.md)). The rule from ADR 0038 stands: what makes two creators produce
the same file is Kotlin in `shared/pack-spec`, compiled to JavaScript; the page only adds browser glue.

## Decision

**More shared Kotlin (`shared/pack-spec/.../authoring/`), so the site, the app and packer cannot drift:**

- `PackEditing.draftOf(manifest)`: an existing pack as a `PackDraft` with the pass-through rules of "Duplicate and
  edit" (characters' IDs, descriptions, tags, animated sprites, clip labels, weights and `when`, ringtone variants
  as they were, haptics; the pack's cover, homepage and tags). Draft file keys are the manifest paths, so the page
  reads each file from the archive by its key. The assembler changes are #225's, carried here unchanged so this PR
  does not wait for it.
- `HapticPatternText`: the "Copy for AI" prompt and the "Paste pattern" parser, moved from the app's `HapticAiText`
  onto pack-spec's `HapticEvent`. The app's `HapticAiText` is now a thin adapter (thousandths ↔ fractions, problem
  types mapped one to one); its tests pass unchanged, and the same cases run in `HapticPatternTextTest` on the JVM
  and `creator-tools.test.ts` in the browser build.
- `BundleAssembler`: `bundle.json` and the layout of `packer bundle` (`packs/<pack id>/`, `cover.<ext>`), with plain
  problems (bad ID or version, no packs, a pack twice, a bundle ID equal to one of its packs' IDs).
- `RepositoryIndexer`: entries, preview paths (`previews/<id>/…`), download URLs and "latest version per ID", now
  used by `packer index` too. Size and SHA-256 come from the platform (Java's `MessageDigest`, Web Crypto).
- `AuthoringJs.kt` and `AuthoringToolsJs.kt` export them; drafts and results cross the boundary as JSON, like
  `assemblePack`.

**Drafts in IndexedDB.** The page saves the draft (a snapshot of its state: texts, encoded files, processed samples)
600 ms after the last change, in a `drafts` store with a small `summaries` store for the list. The address carries
`#draft=<id>`, so a reload reopens the same draft; the list resumes or deletes others (deleting asks inline, since
the page has no dialogs for this). Sources longer than 60 s (a whole song is up to 69 MB of floats) are not saved:
after a reload such a clip is edited again from the processed clip. Without IndexedDB (private windows, blocked
storage) the page says so and works as before. The privacy page says drafts stay in the browser.

**Opening a pack.** "Open a pack file" (and "Edit in the pack creator" on `/open/`, the PWA file handler, which hands
the file over through an IndexedDB `pending` store) validates the archive with every rule, then builds a draft with
`PackEditing`. A bundle asks which of its packs to edit. Clips are decoded for previews; one the browser cannot
decode stays in the pack as it is, without preview or cutting. Ringtones and animated sprites are kept but not
edited (the page says how many); choosing a new picture drops the animated sprite, as the app does (#222).
**Default: an opened pack saves as an update** (same ID, `Authoring.versionAfter`: one patch above, or the release of
a pre-release), because people mostly open their own pack to fix it; a checkbox saves it as a copy instead (derived
ID, 1.0.0), like the app's "Duplicate and edit". The version field shows for opened packs.

**Covers** use the sprite cropper and encoder (square, 512 px), written as `cover.<ext>` by the assembler.

**Custom haptics per character** (the format has no per-clip haptic): Automatic, None or Custom. Custom is a small
timeline editor: an SVG drawing, then each event as a row (start in ms; a tap's kind and strength; a swell's points
with intensity, sharpness and duration), "Add a tap", "Add a swell". Every change is checked with
`HapticPatternText.parse`, so the page shows the same first broken rule, in plain words in English and Spanish, as the
app. "Copy for AI" writes the shared prompt with each clip's length and a simplified loudness analysis computed in
the browser (RMS in 20 ms windows over the first 2 s, sharpness from the zero-crossing rate), since the app's import
analysis does not run on the web. "Paste pattern" replaces the timeline, with Undo. Browsers cannot play rich haptics
([MDN: Vibration API](https://developer.mozilla.org/en-US/docs/Web/API/Vibration_API) is on/off only), so the page
says to preview in the app and does not fake it.

**Bundles** (`/create/bundle/`): pick finished pack files (valid single packs only), name, author, ID (suggested as
`local.<author>.<name>-<suffix>`, editable), version, license, description, cover; the page zips `bundle.json` first
and the rest sorted by path, as packer does, and validates the bytes before offering them.

**Repository helper** (`/create/repository/`): pick packs and bundles; name, description, homepage; packs in the
folder (`packs/`) or at an `https://` base URL (GitHub Releases). The page writes `repository.json` (checked with the
repository schema), the previews and the packs into `repository.zip`, shows the index with a copy button, and gives
exact steps: GitHub Pages (upload, Settings → Pages, deploy from `main` / root, then **Discover → Add a repository**
in the app), Google Drive (no index possible, since its links are file IDs, not paths, ADR 0040; share each pack link
instead) and other static hosts, plus a `cucuto://repo/add?url=…` link for the address the folder will have. File
names are made URL-safe (`My pack.zip` → `My-pack.soundpack.zip`). Only the highest version of each ID and its
previews go in the folder. Nothing is uploaded and no network request is made.

## Consequences

- The site, the app and packer share the haptic text, the edit rules and the repository layout; packer's `index`
  output is unchanged (its tests pass).
- The site's pack-spec module grows by the new authoring code (a few kB).
- Ringtones, animated sprites, tags and descriptions are kept but still not editable on the web or in the app.
- Drafts live only in one browser; a lost browser profile loses them (the page says to download the pack).
- Defaults open for the owner: opened packs save as updates; long sources are not kept in drafts; the browser's
  loudness rows stand in for the app's analysis in the AI prompt.
