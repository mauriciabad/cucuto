# 0040. Google Drive: one SAF file for automatic backups now, the Drive API behind an interface

- Status: accepted (owner decisions pending: #187; the Google Cloud project from #188 exists since 2026-10-25)
- Date: 2026-10-24
- Issues: #163 (automatic backups to Google Drive, the owner's request on #108), #37 (a Drive folder as a pack
  source), #46 (private packs from Drive on a clean install)

## Context

The owner asked on #108: "whatsapp can save the chat backup in google drive automatically, i would like to also have
that feature and it also backs up the files needed and their source. so if i loose the phone, on the new one i'm
ofered to restore a backup and it works even if the repositories are down or closed." Automatic backups (ADR 0028)
already write full backups (pack files included) to a folder picked with `ACTION_OPEN_DOCUMENT_TREE`, and the restore
takes packs from the backup first. What's missing is getting those backups into Drive with as little setup as
possible, and reading pack files from Drive (#37, #46).

Constraints: minimum intrusion; the network only when the user acts or a backup runs; no analytics; credentials in
the Keystore (ADR 0011); Android 17; rooted phones, where Google Play services may be missing (microG or none).

### What the Storage Access Framework can do with Drive

- **Folders.** Drive's `DocumentsProvider` has historically not offered its roots to `ACTION_OPEN_DOCUMENT_TREE`: a
  provider must set `Root.FLAG_SUPPORTS_IS_CHILD` to appear in the folder picker, and Drive has been reported missing
  or greyed out there. Recent developer reports disagree (one app reads a Drive folder picked as a tree in Oct 2026,
  and notes the listing comes from Drive's cache, with `EXTRA_LOADING` set until Drive catches up). I could not check
  the owner's phone (no device access for this task), so folder support is treated as "maybe, depending on the Drive
  version". If Drive does appear in the folder picker, the existing folder mode already works with it.
- **Single files.** Drive does appear in `ACTION_CREATE_DOCUMENT` and `ACTION_OPEN_DOCUMENT`, and both return
  persistable read and write grants
  ([SAF guide](https://developer.android.com/training/data-storage/shared/documents-files)). Drive writes through its
  own app: the upload happens when Drive can, including later if the phone was offline. Cucuto itself makes no
  network call at all.
- **Write modes differ per provider.** `openFileDescriptor` modes are provider-specific ("w" may or may not truncate,
  [ContentResolver](https://developer.android.com/reference/android/content/ContentResolver#openFileDescriptor(android.net.Uri,%20java.lang.String))).
  Apps report Drive answering `"wt"` with "Unsupported mode: wt" and accepting `"rwt"`; cloud providers often hand
  out a pipe and upload when it closes.
- **History for free.** Drive keeps previous versions of an uploaded file ("might be permanently deleted after 30
  days or if there are 100 newer versions", [Drive help](https://support.google.com/drive/answer/2409045)).

### What the Drive REST API would add, and cost

- **Setup only the owner can do.** Google Identity Services' `AuthorizationClient` (`play-services-auth`,
  [Authorize access to Google user data](https://developer.android.com/identity/authorization)) needs a Google Cloud
  project with the Drive API enabled, an OAuth consent screen, and an **Android OAuth client per signing key**
  (package `app.mauri.cucuto` + the SHA-1 of the release key, plus one for debug builds). Credential Manager is for
  signing in, not for Drive access; the docs keep the two flows separate.
- **Scope.** `drive.file` is non-sensitive (no Google security assessment) and covers files the app created or the
  user picked. `drive.readonly` and `drive` are restricted (a yearly third-party assessment), so they're out.
  `drive.file` does **not** grant the contents of a folder the user picks in the Google Picker, only the folder
  itself ([scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth); Google DevRel says
  the same). The Android Picker flow is an `AuthorizationRequest` with the `drive.file` scope and the
  `PICKER_OAUTH_TRIGGER` parameter ([Picker for mobile](https://developers.google.com/workspace/drive/picker/guides/overview-desktop)).
- **Tokens.** A plain `authorize()` returns a one-hour access token and no refresh token; Play services keeps the
  grant and later calls return a token without UI. Refresh tokens need a backend and must not be stored on the device.
- **Costs.** Google Play services in the APK path (absent on many rooted phones), a consent screen, network calls
  from Cucuto itself (and failures when offline), and a consent screen that stays in "Testing" (grants expire after
  7 days) until the owner publishes it.

## Decision

**1. Ship now: automatic backups to one SAF file** (`AutoBackupTargetKind.FILE`). Settings → Automatic backups gets
"Choose file" next to "Choose folder", with a hint for Google Drive and other cloud apps. It opens
`ACTION_CREATE_DOCUMENT` with `cucuto-backup-latest.zip` (no date: it always holds the newest backup, and the folder
pruning never matches it). Every backup is exported to the cache, then replaces the file's content; the same
fingerprint as ADR 0028 skips identical backups. The file mode keeps one copy; Drive keeps the old versions. Full
backups (packs included) stay the default, so a restore works when repositories are gone, as the owner asked.

**Overwriting is defensive** (`DocumentOverwrite`, also used by the folder mode now): try `"wt"`, then `"rwt"`, then
`"w"`; a missing permission is not retried; after writing, a regular file is truncated to the written length (so a
shorter backup never keeps an old tail that breaks the zip); a pipe is never truncated; a failed copy closes the
descriptor with an error so the provider can drop a half-written upload.

**2. A new phone carries on in the same file.** The restore flow already picks the backup through the SAF picker,
which shows Drive. After restoring a **plain** backup, the summary offers "Keep backing up to this file", which turns
on file mode with that document. Encrypted backups are never offered: an automatic backup is plain and would replace
the encrypted one. (Offering the restore on first run is the shell's job, #153: Home can call the same restore.)

**3. #37: public Drive links work now; folders wait.** `RepositoryLinks` recognizes Drive share links
(`drive.google.com/file/d/<id>/…`, `open?id=`, `uc?id=`, `drive.usercontent.google.com/download?id=`) and turns them
into a direct pack download from `https://drive.usercontent.google.com/download?id=<id>&export=download&confirm=t`
(`confirm=t` skips the "can't scan for viruses" page Drive shows for large files). Public files need no sign-in, the
importer checks every byte, and ADR 0011's HTTPS and redirect rules apply unchanged. A Drive **folder** link gets a
clear message instead of a broken `…/repository.json` guess: share each pack file, or download and open them. A
Drive-hosted `repository.json` is not practical, since its relative pack links can't resolve to Drive file IDs.

**4. The Drive API is built behind interfaces, without Google Play services** (`:core:drive`):

- `DriveAuthorizer`: `authorize()` returns a token, "needs consent" (an `IntentSender` the UI launches), or
  "unavailable" (no OAuth client, no Play services, denied); `invalidate(token)` after a 401. The only implementation
  today is `NotConfiguredDriveAuthorizer`, so no Drive API feature is visible. The Play services implementation is a
  small follow-up once the OAuth client exists.
- `DriveClient`: the five REST v3 calls Cucuto needs (search/list with paging, create folder, resumable upload or
  replace, download with a size cap, delete), over OkHttp like `:core:repo`, HTTPS only. The token goes only to
  Google's API hosts; an upload session URL on another host is refused. A 401 invalidates the token and retries once.
- `DriveBackupStore`: a visible **"Cucuto backups"** folder created by the app (so `drive.file` covers it, also
  after a reinstall with the same OAuth client), found again by an app property, not its name. Uploads replace a
  same-named file (one file per day, like the folder mode), pruning takes `BackupFileNames.toPrune`, and `backups()`
  lists them newest first for "offer to restore" on a clean install.
- `DrivePackFolder`: classifies a folder listing into `repository.json` and pack files, for the Picker-based pack
  source (with `drive.file`, only the files the user picked are listed).

**Credentials.** Nothing secret is stored on disk: Play services keeps the grant, access tokens live in memory and
redact themselves in `toString`. If a future flow ever needs a stored secret, it goes through ADR 0011's Keystore
AES-GCM store.

**Why the SAF file is the default, not the API.** It needs no Google Cloud project, no Play services, no account
picker and no network code in Cucuto, works offline (Drive uploads later), works with any cloud app that has a
document provider (Drive, Nextcloud, Proton Drive, Dropbox…), and Drive keeps 30 days of versions. What it can't do
is find the backup by itself on a new phone: the user picks it once in the restore picker. The Drive API would add
exactly that (and the hidden "Cucuto backups" folder discovery), at the cost of the setup above.

**Encryption.** Unchanged from ADR 0028: automatic backups are plain (no one is there to type a passphrase, and a
Keystore key dies with the phone). Backups hold contact names and WhatsApp keys, so Drive copies are as private as
the user's Drive. Question #187 asks whether the owner wants a stored passphrase instead.

## Consequences

- Drive users get automatic full backups today with one extra tap ("Choose file"), and a new phone carries on in the
  same file after restoring it.
- One copy in file mode instead of 10; Drive's version history (30 days or 100 versions) covers older ones.
- The owner decides whether the Drive API is worth a Google Cloud project (#188): until then
  `:core:drive` is tested with MockWebServer and fakes only, and nothing calls it.
- Private Drive pack folders (#46) work today only through the SAF picker: import each pack file from Drive (the
  import picker shows Drive), or restore a full backup, which carries the pack files. A real Drive
  folder source needs either the Picker (user picks the files, `drive.file`) or a restricted scope, which is not
  worth a security assessment.
- To check on the device (`needs-device`): whether Drive appears in the folder picker on Android 17, which write mode
  Drive accepts, and that a rewritten file shows a new version in Drive (docs/device-testing.md, section 4d).

## Update 2026-10-25: the Google Cloud project exists (#188)

The owner created it with an agent. Nothing here is secret, and nothing goes into GitHub secrets or the app:

- Project ID `cucuto-app` (number 50484089495), owned by the owner's Google account. Google Drive API and Google
  Picker API enabled.
- Google Auth Platform: app name Cucuto, External, **In production**. Home page `https://cucuto.mauri.app`, privacy
  policy `https://cucuto.mauri.app/privacy/`, authorized domain `mauri.app`. No logo, so no brand verification.
- Data access: `https://www.googleapis.com/auth/drive.file` only (non-sensitive).
- Android OAuth clients for `app.mauri.cucuto`: "Cucuto Android (release key)", SHA-1
  `74:4B:E3:74:38:2F:16:E4:38:26:4E:74:F1:1E:8E:49:3F:3D:58:46` (`CN=Cucuto`, read from the installed release APK),
  and "Cucuto Android (debug key)", SHA-1 `61:8E:2E:AC:C7:80:F5:4B:49:5B:16:44:F9:85:4C:A7:53:40:B4:DF` (the
  default `~/.android/debug.keystore` on the owner's Mac). A Google Play build with Play App Signing would need a
  third client with the Play signing key's SHA-1.
