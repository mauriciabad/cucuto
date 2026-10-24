# 0028. Settings, backup and restore UI

- Status: accepted (defaults the owner may override: #166)
- Date: 2026-10-24
- Issues: #28, #43 (the UI left over from [ADR 0013](0013-backup-and-restore.md))

## Context

Settings is one of the four top-level destinations (app structure contract, #153). It has to hold the global switch,
the Feedback engine settings (#112), backup and restore, the setup guide and status (#128), Diagnostics, About and,
in debug builds, developer tools. `:core:backup` (ADR 0013) already exports, reads, restores and plans packs; what was
missing is everything a person touches: SAF export, the automatic backup folder, and the restore flow from the brief
("Restore on a new phone": pick the file, packs, contacts, a final LSPosed checklist). The owner answered #108:
"if backups are identical saving many of them seems unnecessary".

## Decision

**One Settings screen, self-contained in `ui/settings/`.** `SettingsScreen(onNavigate)` is a `LargeFlexibleTopAppBar`
over grouped sections (titled `surfaceContainer` groups of `ListItem`s): Cucuto voices, Sound and feel, Backup and
restore, Setup, Diagnostics, About, Developer (`BuildConfig.DEBUG` only). Setup and Diagnostics link to the existing
destinations; navigation only gains the Settings entry. Strings live in `res/values/strings_settings.xml` so parallel
screen work does not collide in `strings.xml`.

**The global switch writes `RoutingSettings.enabled`,** the same setting Diagnostics used, so `RoutingPublisher`
republishes the routing table as before. A **Quick Settings tile** (`VoicesTileService`, toggleable,
`BIND_QUICK_SETTINGS_TILE`) writes the same setting; it was in #28's scope.

**"Remove animations" is also an in-app setting** (`UiFeedbackSettings.removeAnimations`, default off).
`ProvideFeedback` ORs it into `LocalReducedMotion`, so every component that already honours the system setting
(particles, bounces, the door) honours this one too, without slowing other apps. When the system setting is on, the
row shows it as on and disabled.

**Backups go through a `BackupGateway`** (`AppBackupGateway` wires `:core:backup` to the database, settings, the pack
library, the credential store and SAF). View models depend on the interface and see documents as URI strings, so
they are unit-tested with a fake.

- **Manual export:** a dialog (full backup, password with confirmation and an 8-character minimum, repository
  passwords only with a password), then `ACTION_CREATE_DOCUMENT` with `cucuto-backup-YYYY-MM-DD.zip`. A failed
  export deletes the half-written document. The password lives in memory only until the export, then is wiped; it
  never goes into saved state.
- **Automatic backups (`AutoBackup`):** the user picks a folder (`ACTION_OPEN_DOCUMENT_TREE`, persisted read/write
  permission). A process-scoped collector watches contacts, voices, installed packs, repositories and the global
  switch, debounced 10 s, plus one run after each app start (changes made just before the process died). Each run
  exports to the cache, then copies into the folder: today's file is replaced, so there is one file per day, and
  `BackupFileNames.toPrune` keeps the newest 10. A run is skipped when the backup's **fingerprint** (SHA-256 of
  `backup.json` without `createdAt`, the app version and the key order, plus the full-backup flag) equals the last
  one and that file is still there: identical backups are not written again (#108). "Back up now" always writes.
  Automatic backups are never encrypted and never include credentials (nobody is there to type a password) and,
  by default, **include the sound packs**, so a restore works even if a repository is gone (the owner's reason for
  wanting Drive backups on #108). No WorkManager: every change that matters happens while the app runs.
- **Restore (`RestoreViewModel` + `RestoreFlow`):** a Material 3 full-screen dialog over Settings, so the flow needs
  no extra route. Steps: open (passphrase prompt for encrypted backups; packs and broken files are plain problems),
  the pack checklist from `PackRestorePlanner` (packs in the backup, repository and link downloads start by
  themselves, one at a time; local-only packs show "Choose file"; failures can be retried; the plan is recomputed
  after each install so a bundle can satisfy several rows), contacts and voices (`BackupRestorer`), conflicts
  (unticked by default: this phone's voice is kept unless the user ticks the contact; a second, idempotent restore
  with `RestoreOptions.overwrite` applies only the ticked ones), then a summary with the LSPosed checklist (enable,
  System Framework scope, reboot, test notification) and a link to the setup guide. The user can go on with packs
  missing; the summary says how many.
- **Downloads during restore** use `RepositoryClient` directly: repository packs come from the current index entry
  with the same ID (same version preferred) and are verified against the index's SHA-256; direct links are verified
  against the backup's SHA-256. After installing a pack from a full backup, its row records the **original** file's
  hash from the reference instead of the re-zipped one, so later backups still point at what repositories serve.
  This needs the `INTERNET` permission, added here.

**About** shows the version, website, source and a licenses dialog: GPL-3.0-or-later for Cucuto, OFL 1.1 for Google
Sans Flex, CC0 1.0 for the demo packs, and the libraries' licenses.

## Consequences

- `AppBackupGateway` opens its own `EncryptedCredentialStore` (same file and Keystore key as the repository code)
  instead of adding a Hilt binding another stream may add too; two instances only race if both write at once.
- Full automatic backups re-zip every pack on each change. With large private packs this costs time and space in the
  chosen folder (10 copies at most, and only when something changed); the switch to leave packs out is next to it.
- Re-linking Android contacts on a new phone, and Google Drive backups (the owner's request on #108, now #163), are separate
  work.
