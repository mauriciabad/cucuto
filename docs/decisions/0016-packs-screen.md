# 0016. Packs screen: import flow, errors in plain words and removal

- Status: accepted (defaults open for the owner in #130)
- Date: 2026-10-24
- Issue: #27

## Context

The importer (`:core:pack`, ADR 0008) validates, unpacks and installs packs, but nothing in the app called it: users
had no way to get the demo packs onto the phone. The brief's Packs screen lists installed packs, imports from a file,
shows pack details, updates and removes packs, and asks what happens to contacts when a pack is removed. ADR 0007
says packs are plain zips detected by content and opened from the file picker, "Open with" and the share sheet.

## Decision

**Structure.** `PacksViewModel` talks to a small `PackCatalog` interface (installed packs, `prepare`, `install`,
`discard`, `remove`). `PackLibrary` implements it on top of `PackImporter`, the installed-packs table, assignments
and contacts. The prepared import is wrapped in an `ImportReview` (opaque handle), so the screen logic is unit tested
with a fake catalog and made-up packs. `:core:pack` gained `PreparedImport.stagedFolder()` (previews read sprites and
clips from the staging folder before anything is installed) and `PackImporter.uninstall()` (serialized with
installs). Leftovers of interrupted imports are deleted before the first import of each process.

**Entry points.**

- The system file picker (`ACTION_OPEN_DOCUMENT`) with `application/zip`, `application/x-zip-compressed` and
  `application/octet-stream`, so providers that do not know the type (old `.soundpack` files, some cloud drives) still
  show the file.
- `ACTION_VIEW` and `ACTION_SEND` filters on `MainActivity` exactly as ADR 0007 lists them, with
  `pathAdvancedPattern` (a real regular expression, API 31+) for the `.zip` name fallback instead of the stacked
  `pathPattern` workarounds. The activity hands the file to a singleton queue (`IncomingImports`); the navigation host
  opens the Packs screen and its view model takes the file. Only the bytes are read. URIs that point back into Cucuto
  (its own providers or private files) are refused, so another app cannot make Cucuto read its own data as a "pack".
- Discover (#36) is one tap away: a top bar action and a "Find more packs" card that also links to the website's free
  packs until repositories are browsable in the app.

**Errors in plain words.** Every stable code of `spec/error-codes.json` and every importer failure reason has one
sentence for people who never saw a `pack.json` (`ImportProblems.kt`, `strings.xml`). The sheet shows a title, one
summary sentence and up to five distinct problems with the first file each concerns ("and 3 more files"). The raw
codes, paths and validator messages sit behind "Show details for pack authors". Special cases get their own title: a
zip without a manifest ("This isn't a sound pack"), a newer format ("update Cucuto") and a Cucuto backup, recognised
by peeking at the zip root for `backup.json` or `envelope.json` with `payload.bin` (only after `MANIFEST_MISSING`).

**Defaults** (the owner can override them in #130):

1. A bundle with one broken pack is rejected as a whole, as ADR 0008 and #83 settled.
2. Bundle packs are all selected, except packs that would replace the same or a newer installed version: those start
   unselected and selecting one is the confirmation. A single pack's button says what it does ("Install", "Update",
   "Replace", "Install older version") and is the confirmation.
3. Removing a pack used by contacts offers "Keep their characters" (the default: they hear WhatsApp's own tone until
   the pack is imported again, then their characters come back) or "Clear their characters". Rows are removed from
   the database before the files, so routing never points at a deleted clip.
4. An update that drops characters some contacts use says so in the review; those contacts fall back to WhatsApp's
   tone (their assignment is kept, so a later version that brings the character back restores it).
5. Installed packs are a vertical list of cards, each with its characters in a horizontal carousel (tap one to hear a
   clip, the avatar morphs while it plays). The brief's "carousel of packs" would hide most of each pack's characters
   on a phone; the per-pack character row keeps the carousel feel and shows more at once. Pack details open in a
   bottom sheet rather than a new destination, which keeps navigation untouched while Home and the picker are built.
6. The review reveals characters one by one (all at once with "Remove animations") but does not auto-play clips: a
   bundle with dozens of characters would play a long, loud medley. Tapping plays one.
7. Previews play through `USAGE_MEDIA` (media volume), like other in-app previews, cycling through each character's
   clips on repeated taps.

**Images.** `coil-svg` joins the app's `ImageLoader`: the demo packs and the owner's Dream World packs use SVG
sprites, which the importer has already sanitized (`SVG_UNSAFE`). JPEG XL goes through the existing decoder.

## Consequences

- Users can import the demo packs (and any pack or bundle) from Downloads, Google Drive, a file manager or a chat app,
  see why a broken pack is refused, preview characters and remove packs safely.
- Because Cucuto registers for every zip, it appears in "Open with" for unrelated zips; those get the "This isn't a
  sound pack" explanation and nothing is written.
- Backups opened here are only recognised and explained; restoring them belongs to the backup screen (#43 follow-up).
- The import sheet disappears when an install finishes; the success arpeggio, a particle burst and a snackbar mark it.
