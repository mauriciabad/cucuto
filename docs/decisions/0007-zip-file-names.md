# 0007. File names: packs end in `.soundpack.zip`, backups in `.zip`

- Status: accepted
- Date: 2026-10-23
- Issue: #79
- Supersedes: the `.soundpack` and `.cucuto-backup` extensions in the brief

## Context

The brief settled `.soundpack` as the pack file extension and `.cucuto-backup` for backups. Both files are plain zip
archives. The owner asked for no custom extensions ([owner log](../owner-log.md), 2026-10-23):

> i dont want to use custom extensions (like .soundpack) if it is a zip it has to end with .zip, you can do things
> like .soundpack.zip but dont skip .zip. think what is best and do it

A custom extension also costs something in practice:

- **Tooling.** Every operating system, file manager, mail client, cloud drive and browser knows `.zip`. A
  `.soundpack` file shows as an unknown blob, cannot be opened or inspected with the built-in archive tools, and some
  mail and chat apps block or rename unknown attachments. Pack authors want to look inside their packs.
- **MIME types.** There is no registered type for `.soundpack`. Servers, GitHub Releases, Google Drive and Android's
  `MimeTypeMap` serve or report such files as `application/octet-stream`, so "Open with" never offers the app. A
  `.zip` is reported as `application/zip` almost everywhere.
- **Android.** Intent filters match on MIME type; `pathPattern` matching on extensions works only for `file:` and some
  `content:` URIs, and the Storage Access Framework hides real names behind `content://` URIs. Matching a custom
  extension reliably is not possible; matching `application/zip` is.
- **Web.** The PWA `file_handlers` API needs a MIME type and extensions. Chromium matches on the final extension, so a
  handler for `.soundpack` never sees `x.soundpack.zip`, and a vendor type like `application/vnd.cucuto.soundpack+zip`
  is not what the operating system reports.

## Decision

- **Packs and bundles** are named `<name>.soundpack.zip`. `packer` writes `<id>-<version>.soundpack.zip`. The
  `.soundpack` part is a readable hint for people, not something software relies on.
- **Backups** are named `cucuto-backup-YYYY-MM-DD.zip` (the local date of the export), for plain and encrypted
  backups alike.
- **The type comes from the contents.** Readers accept any `.zip`, or a stream with no name at all, and look at the
  root: `pack.json` is a pack, `bundle.json` a bundle, `backup.json` a plain backup, `envelope.json` with
  `payload.bin` an encrypted backup. Anything else is rejected with the usual layout errors (`MANIFEST_MISSING`,
  `MANIFEST_AMBIGUOUS`). The importer never trusts a file name.
- **MIME type** `application/zip` everywhere: repository downloads, shares, exports.
- **Android** registers `ACTION_VIEW` and `ACTION_SEND` intent filters for `application/zip` (plus
  `application/x-zip-compressed`, which some file managers and Windows-made files report), and a `pathPattern` filter
  for `.*\\.zip` on `file` and `content` URIs as a fallback for apps that report `application/octet-stream`. Exports
  use `ACTION_CREATE_DOCUMENT` with `application/zip` and the names above.
- **PWA** `file_handlers` accepts `{ "application/zip": [".zip"] }` and the `/open/` page sniffs the contents.
- **Repository.** `.gitignore` blocks `*.soundpack.zip`, bare `*.soundpack` and `cucuto-backup-*.zip` everywhere except
  `spec/fixtures/`; the `media-guard` workflow treats `.zip` like media files (allowlisted paths only).

## Consequences

- Every pack and backup opens in standard archive tools, and "Open with" works from file managers, mail and Drive.
- Registering for `application/zip` means the app shows up for every zip, not just packs. The import screen must say
  plainly when a zip is not a pack, bundle or backup, and never write anything before the contents are checked.
- File names carry no authority: renaming a pack to `holiday.zip` still imports it, and a zip named `.soundpack.zip`
  that holds anything else is rejected.
- The golden fixtures in `spec/fixtures/` were renamed to `*.soundpack.zip`; schemas, docs, packer, workflows and the
  website use the new names. Existing `.soundpack` files from before this change still import if renamed (or picked
  through the system file picker, which does not filter by name).
