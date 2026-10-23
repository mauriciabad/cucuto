# 0013. Backup and restore: Bouncy Castle crypto, re-zipped full backups, ask-first restore

- Status: accepted (defaults the owner may override: #108)
- Date: 2026-10-24
- Issue: #43

## Context

One backup file must bring every assignment, setting and repository back on a new phone
([brief](../project-brief.md#backup-and-restore)). The format is fixed by `spec/backup.schema.json` and
[docs/pack-format.md#backups](../pack-format.md#backups): a zip named `cucuto-backup-YYYY-MM-DD.zip` with
`backup.json` at the root (plus `packs/*.soundpack.zip` for a "full backup"), or `envelope.json` + `payload.bin`, the
plain zip encrypted with AES-256-GCM under an Argon2id key. The file type comes from the contents
([ADR 0007](0007-zip-file-names.md)). The restore must be idempotent and never overwrite what the user set on the new
phone without asking. Credentials are exported only when the user opts in, and only encrypted.

## Decision

**`:core:backup` has four parts, all plain classes without DI** (like `:core:pack` and `:core:repo`):
`BackupExporter` (database, settings and optional credentials to `backup.json`, then the zip, optionally encrypted),
`BackupReader` (copies the source, sniffs the type, decrypts, validates with pack-spec's `DocumentValidator`, then
decodes), `BackupRestorer` (merges a document into the database) and `PackRestorePlanner` (what to do for each pack:
installed, from the backup, download from the repository or URL, or ask for the file). `ZipContentSniffer` tells
packs, bundles and both backup kinds apart for "Open with". Every exported document is validated against the schema
before a byte is written, so a bug produces an error instead of a backup that cannot be restored.

**Crypto: Bouncy Castle 1.86 (`bcprov-jdk18on`), lightweight API only.** Android has no Argon2 in the platform, and
its `Cipher` buffers the whole ciphertext when decrypting AES-GCM, which does not fit a full backup with large packs
in memory. Bouncy Castle's `Argon2BytesGenerator` and `GCMBlockCipher` are pure Java (JVM unit tests run the real
code), stream in both directions, and R8 drops the rest of the library. The key is Argon2id v1.3, 32 bytes, over the
UTF-8 bytes of the NFC-normalized passphrase (added to the pack format so other readers derive the same key). Cucuto
writes 64 MiB, 3 passes, 1 lane; it refuses to decrypt envelopes that ask for more than 256 MiB, so a crafted file
cannot exhaust memory. Decrypted bytes go to a scratch file in the cache and are used only after the tag verifies.
Tests check Argon2id against the reference implementation's test vector and GCM against the JDK's `Cipher`.

**Reading zips.** The reader reuses pack-spec's central directory parser (encrypted entries, compression methods,
Zip64, entry-count and compression-ratio caps), reads `backup.json` (64 MiB cap) and `envelope.json` fully with their
CRC checked, and streams `payload.bin` and included packs with size and CRC checked at the end. Entries are only
looked up by name and never extracted by their own path. Included packs are handed to the pack importer as an
`ImportSource`, so they get the same validation and decoding as any other pack.

**Full backups re-zip the installed packs.** The importer does not keep the original file, so a full backup zips each
installed pack folder (the files exactly as imported) into `packs/<id>-<version>.soundpack.zip`; for a bundle, the
installed packs of that bundle go into one bundle zip with a generated `bundle.json`. The reference keeps the SHA-256
of the file originally imported (what repositories serve); packs without a recorded hash (debug tools) get the hash of
the included file in a full backup and are left out of a plain backup (reported to the caller). To support this,
`PackImporter` now hashes the source while copying it (`PreparedImport.archiveSha256`), and `installed_packs` stores
the bundle's name and version (database version 2, migration `1 → 2` adds two nullable columns).

**Restore never overwrites without asking.** Contacts match on any messenger key, then any phone number. A matched
contact gains the backup's missing keys, name and Android link; keys that belong to another contact are reported and
left alone, because merging two contacts is the user's call. A voice (message assignment plus group policy) is applied
when the contact is new or has nothing set, skipped when equal, and otherwise reported as a conflict; restoring again
with the chosen contact IDs in `RestoreOptions.overwrite` replaces only those. Repositories are added when their URL is
unknown. Credentials come only from encrypted backups and never replace stored ones. Settings use an explicit list of
keys (`AppSettingsBackup`, today only `enabled`), so device-specific state never travels. Running the restore twice
gives the same database.

**Automatic backups.** `BackupFileNames` names files by local date and decides which to prune so the newest 10 stay;
files that are not backups are never touched. Writing them to the chosen folder (SAF tree URI, debounced after
changes) belongs with the Settings UI.

## Consequences

- The APK gains Bouncy Castle's crypto classes that R8 keeps (Argon2, AES, GCM); `consumer-rules.pro` silences R8
  about JNDI classes the unused provider code references.
- A full backup's pack files do not have the hash in their reference. Restore validates them with the importer, which
  is what protects the app; the hash still verifies repository and URL downloads.
- Ringtone assignments are kept in the format but have no table yet: restore counts them (`ringtonesPending`) until
  call ringtones land (M9).
- Not done here, and tracked on #43: the restore screen (pick file, passphrase, pack checklist with downloads and
  pickers, conflicts, the final LSPosed checklist), SAF export and the automatic backup folder, and re-linking Android
  contacts on a new phone (lookup keys are per device; the backup keeps the key and name for that step).
