# 0029. Discover screen: browsing repositories, previews, installs and adding repositories

- Status: accepted (defaults open for the owner in the `question` issue linked from #36)
- Date: 2026-10-24
- Issue: #36

## Context

`:core:repo` (ADR 0011) has the HTTPS client, the ETag index cache, link parsing and the Keystore credential store;
the website publishes the default repository at `https://cucuto.mauri.app/repo/index.json` (#94); the Packs screen
(ADR 0016) has the import pipeline with its review sheet and errors in plain words. Nothing in the app used the
repository code. The brief's Discover screen browses the default and user-added repositories, previews characters,
downloads through the standard importer, and adds repositories by URL, QR code or the `cucuto://repo/add?url=…` link,
with optional credentials ([brief](../project-brief.md#pack-repositories)).

The minimum-intrusion principle adds a network rule: the network is used only when the user opens Discover or adds a
repository. Nothing runs in the background and there is no analytics.

## Decision

**When the network is used.** Only from `DiscoverViewModel`, so only while Discover is open:

- every enabled repository's index when the screen opens and on pull to refresh (`If-None-Match`, so an unchanged
  index costs one small 304);
- covers and character sprites when their card is on screen, a sample clip only when its character is tapped;
- the pack file when the user taps Install, Update or Install again;
- the index of a repository the user is adding, when they press Add.

The brief says repositories are "checked when the app opens"; Discover checks them when it opens instead, so the app
start never touches the network. Update badges therefore show in Discover only. `INTERNET` is the only new
permission; there is no `ACCESS_NETWORK_STATE` (a failed request says "can't reach the server", which covers being
offline). The Hilt singletons (client, caches, credential store) are created on first use by Discover.

**Offline and errors.** A failed refresh keeps the last index that worked, from memory or from the ETag cache
(`RepositoryClient.cachedIndex`, new, no network), marked "Showing the last copy" with the reason and a retry. With no
copy, the section shows the reason in plain words (`plainWords(RepoError)` next to the importer's sentences) with
Try again, plus Sign in on a 401 or 403. Empty repositories and searches without matches have their own states.

**Previews.** `PreviewStore` keeps fetched previews in `cache/repo-preview/` (named by the SHA-256 of the URL, at
most four fetches at once, failures not retried until the next app start). A preview must follow the pack media rules
before it is written: an image or audio extension from pack-spec's allowlist, a readable header (pack-spec's
`ImageProbe` and `AudioProbe`), bitmaps at most 512 px per side, and SVGs that pass `SvgSafety`. Coil only ever
decodes local files, so no network fetcher is added to the image loader. Character badges look and behave like the
Packs screen's: tap to hear, the cookie avatar morphs while the clip loads and plays. Haptic previews need data the
index does not have yet; they are left for later.

**Installs go through the existing review.** The import state machine moved out of `PacksViewModel` into
`ImportController`, shared by both screens, so Discover shows the same `ImportSheet`: download progress (eased, with
"1.2 MB of 4.8 MB"), then reading, checking and the review, then install. `PackDownloader` downloads with the index's
size and SHA-256 (ADR 0011) into `cache/repo-download/` and hands the file to `PackCatalog.prepare(source, origin)`.
The review remembers the origin (`PackOrigin.Repository` with the registered index URL, or `PackOrigin.Url` for a
direct link), which `installed_packs` records for update badges and backups. A failed download ends in the sheet's
problem view ("Couldn't download the pack") and never reaches the importer.

**Install state.** A pack entry is installed when a pack with its ID is; a bundle entry when packs installed from it
(same bundle ID) are. An older installed version (SemVer) shows "Update available"; the same or a newer one shows
"Installed" with an "Install again" button, which the review turns into Replace or Install older version. Entries
that need a newer pack format are shown with "Needs a newer Cucuto" and cannot be installed.

**Adding repositories.** One sheet adds a repository, a direct pack link, or signs in to a saved repository. The link
is parsed as the user types (`RepositoryLinks`), so the sheet shows the host before anything is fetched, and any host
but `cucuto.mauri.app` gets the short unknown-source warning. Sign-in is None, Password (HTTP Basic) or Token
(bearer); secrets live only in the form state (redacted `toString`) and the Keystore store. A repository is saved only
after its index was fetched and validated with the given credentials, so typos and wrong passwords never end up in
the list; a 401 or 403 asks for the sign-in in place. A URL that is already saved is turned back on instead. A direct
`.zip` link starts a one-off download and is not saved.

**"Add to app" links.** `MainActivity` accepts `cucuto://repo/add` (VIEW, BROWSABLE), queues the link in
`IncomingRepoLinks` and the navigation host opens Discover, which fills in the sheet ("Add this repository?"). Links
are untrusted: nothing is fetched until the user presses Add, and invalid links only show a snackbar. QR codes work
through the system camera or Lens, which open the same link; there is no in-app scanner (it would need the camera
permission and a barcode library).

**Managing repositories.** The default repository is added on Discover's first open and cannot be removed, only
turned off; others can be turned off, signed in to again, or removed (credentials and cached index go too, installed
packs stay). Sections show each repository's name and host, with a lock when it has credentials.

## Consequences

- No network at app start and none in the background; update badges appear only when the user opens Discover.
- `PacksViewModel` is now a thin wrapper over `ImportController`; future import entry points (backups restoring packs,
  the pack creator) can reuse it.
- `ImportSheet.onPickAnother` is optional (Discover has no file to pick again), and `ImportUi.Working` carries an
  optional download.
- Previews in the cache folder can be cleared by Android at any time; that only costs another fetch.
- Haptic previews, an in-app QR scanner and Google Drive folders (v1.1) are not built.
