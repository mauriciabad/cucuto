# 0079. Security review before the Play release: what changed and what stays

- Status: accepted
- Date: 2026-10-24
- Issue: #335 (minimum intrusion, owner #67)

## Context

Before the Google Play release, the whole attack surface was reviewed against the Android intent and permission
security checklists and developer.android.com
([intents and intent filters](https://developer.android.com/guide/components/intents-filters),
[content provider security](https://developer.android.com/privacy-and-security/risks/content-resolver),
[zip path traversal](https://developer.android.com/privacy-and-security/risks/zip-path-traversal),
[network security configuration](https://developer.android.com/privacy-and-security/security-config)):
the manifest, incoming Intents and URIs, the importer and the backup reader, repository downloads and SVG checks,
the clip mirror (ADR 0018), the module in `system_server` (ADR 0010, ADR 0048, ADR 0054), backup encryption, and
logs.

Most of it holds. Four places did not, all of low severity: none gives another app Cucuto's data or code execution.

## Decision

1. **"Open with" and the share sheet take `content:` URIs of other apps only.** The `file:` scheme leaves the intent
   filters and the code. Cucuto has no storage permission, so a `file:` URI could only ever name its own files or
   world-readable system files, never a file someone shares. For `content:` URIs, the check that the provider is not
   Cucuto's own now drops the `<userId>@` prefix of cross-user URIs (`content://0@app.mauri.cucuto.creator/…`
   slipped past the old prefix check) and asks the package manager which package owns the authority.
2. **A mirrored clip is current only when it has the source's exact bytes.** ADR 0018 accepted a copy with the
   source's size and a date not older than it, so a same-size file planted or edited by an app with all-files access
   would stay in place, and SystemUI would play it for that contact. The app now compares the bytes once per process
   (clips are tens of KB) and replaces any copy that differs.
3. **The routing table decoder bounds every count by the text left.** A header or clip count above the characters
   still to come is malformed, so a corrupt table can no longer size a list of up to 2^31 entries in `system_server`
   before the body proves it wrong. A time condition whose span overflows a `Long` is rejected at decode time instead
   of indexing before its buckets on the notification path (the hook would have caught it and kept WhatsApp's tone).
   The format is unchanged, and every table the app writes still decodes.
4. **Opening a backup stops before it fills the phone.** The copy of the file another app hands over checks every
   8 MiB that 32 MiB stay free, as decryption already did.

## Checked and unchanged

- **Exported components.** `MainActivity` (launcher, zip intake, `cucuto://repo/add`, which only pre-fills
  Discover's add sheet behind a confirmation), the Quick Settings tile (`BIND_QUICK_SETTINGS_TILE`), `ClipProvider`
  (uid 1000 and SystemUI only, read-only, audio files inside an installed pack by segment grammar and canonical path)
  and `ModuleEventProvider` (uid 1000 only, for reports and the routing table pull). The FileProvider is not exported
  and serves only `cache/creator/shared/`. `CallEndedReceiver` requires `READ_PRIVILEGED_PHONE_STATE` of its sender;
  `RingtoneEventsReceiver` is not exported. The one `PendingIntent` is immutable and explicit. No nested Intent is
  ever launched.
- **The module's trust in the table.** Tables come from LSPosed (Remote Preferences and the remote file, written only
  by the module's app) or from the app's provider after `AppProvider` verified its package and the module APK's
  signing certificate. Nudges count only with the app's shared broadcast identity. Reads stop at
  `RoutingFile.MAX_BYTES` + 1 bytes and the frame has a length and CRC-32. Every path catches everything.
- **Zips.** pack-spec parses the central directory itself: entry count, 100:1 ratio, declared sizes enforced while
  inflating, CRC-32, unsafe and invalid names rejected; the importer also checks every target's canonical path, caps
  the source at `MAX_ARCHIVE_BYTES`, and backups are only ever read by entry name.
- **Remote content.** HTTPS at every hop, redirects followed by hand (at most 5), credentials only on the
  repository's origin, size and SHA-256 from the index checked while streaming, preview caps, SVGs from packs and
  previews pass `SvgSafety` before Coil renders them with no network fetcher. No network security config is needed:
  with `targetSdk` 37 cleartext is refused and only system CAs are trusted, and the HTTP client has no cleartext
  connection spec.
- **Backups.** Argon2id v1.3 (64 MiB, 3 passes, 1 lane, 16-byte random salt) and AES-256-GCM (12-byte random nonce,
  128-bit tag, fixed AAD); the reader refuses more than 256 MiB of KDF memory. Credentials travel only in encrypted
  backups. Nothing secret is logged.
- **Logs.** The module logs only key shapes (`@s.whatsapp.net`, `<title>`), contact row numbers and clip URIs;
  the app logs exception classes, pack IDs and counts. No contact names or JIDs.

## Consequences

- A file manager that still sends `file://` URIs no longer offers Cucuto in "Open with". Cucuto could not read those
  files anyway; the share sheet, the system file picker and `content://` "Open with" are unaffected.
- An app with all-files access can still swap a mirrored clip between two checks; the next app start or publication
  repairs it. That is the trade ADR 0018 made for instant sounds, now with a smaller window (#341 asks the owner).
- Routing and time-condition changes are module code: they take effect from the boot after the release that ships
  them. The app-side changes take effect on update.
