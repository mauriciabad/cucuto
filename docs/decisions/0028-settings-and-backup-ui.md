# 0028. Settings, backup and restore UI

- Status: accepted; the owner confirmed the #166 defaults except item 9 (restore becomes a screen, #218)
- Date: 2026-10-24
- Issues: #28, #43 (the UI left over from [ADR 0013](0013-backup-and-restore.md))
- Amended: 2026-10-24 (#43, the restore summary's Contacts card)

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

- **Manual export:** a screen since ADR 0055, a dialog before (full backup, password with confirmation and an 8-character minimum, repository
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
- **Restore (`RestoreViewModel` + `RestoreScreen`):** a screen on the tab it starts from (Settings or Home), since
  ADR 0055; it was a Material 3 full-screen dialog over Settings. Steps: open (passphrase prompt for encrypted backups; packs and broken files are plain problems),
  the pack checklist from `PackRestorePlanner` (packs in the backup, repository and link downloads start by
  themselves, one at a time; local-only packs show "Choose file"; failures can be retried; the plan is recomputed
  after each install so a bundle can satisfy several rows), contacts and voices (`BackupRestorer`), conflicts
  (unticked by default: this phone's voice is kept unless the user ticks the contact; a second, idempotent restore
  with `RestoreOptions.overwrite` applies only the ticked ones), then a summary with the LSPosed checklist (enable,
  System Framework scope, reboot, test notification) and a link to the setup guide. Since #43 the summary also has a
  Contacts card for the Android links the backup brought (see the amendment below). The user can go on with packs
  missing; the summary says how many.
- **Downloads during restore** use `RepositoryClient` directly: repository packs come from the current index entry
  with the same ID (same version preferred) and are verified against the index's SHA-256; direct links are verified
  against the backup's SHA-256. After installing a pack from a full backup, its row records the **original** file's
  hash from the reference instead of the re-zipped one, so later backups still point at what repositories serve.
  They use the `INTERNET` permission Discover added.

**About** shows the version, website, source and a licenses screen (a dialog before ADR 0055): GPL-3.0-or-later for Cucuto, OFL 1.1 for Google
Sans Flex, CC0 1.0 for the demo packs, and the libraries' licenses.

## Consequences

- `AppBackupGateway` uses the app's `RepositoryClient` and `CredentialStore` singletons (Discover's
  `RepositoryModule`, ADR 0029), injected lazily so app start touches neither the network nor the Keystore.
- Full automatic backups re-zip every pack on each change. With large private packs this costs time and space in the
  chosen folder (10 copies at most, and only when something changed); the switch to leave packs out is next to it.
- Google Drive backups (the owner's request on #108, now #163) are separate work
  ([ADR 0040](0040-google-drive-backups-and-pack-folders.md)). Re-linking Android contacts on a new phone came later
  (the amendment below).

## Owner answer (2026-10-24, #166)

> 9 i preffer a screen unless the content is very minimal and temporary.
> other LGTM

Defaults 1 to 8 stand. Item 9 changes: the restore flow becomes a screen (a navigation destination with predictive
back), not a full-screen dialog. The answer also became a UI rule in the brief ("Screens over dialogs"): dialogs and
sheets only for very minimal, temporary content. The manual backup options dialog and the licenses dialog are
non-trivial too; all conversions are listed in #218.

## Amendment 2026-10-24 (#43): the Contacts card

Android links in a backup are matched again on the new phone ([ADR 0013](0013-backup-and-restore.md#amendment-2026-10-24-43-re-linking-android-contacts)).
The summary shows where that stands in a **Contacts** card, above the Phone ringtones card. The card appears only when
the backup brought Android links or phone ringtones.

- **No contacts permission:** "N chats are linked to people in your contacts." A plain explanation follows: contacts
  are found again on each phone; Cucuto only reads them for this; they never leave the phone. Then **Find contacts**,
  which asks for `READ_CONTACTS` only, explained first in a dialog like every contacts prompt (#145). If the user
  refuses, a second dialog offers Android's app settings, or choosing each contact by hand later.
- **Matched:** "Found N of M in this phone's contacts."
- **Unmatched:** each contact is a row with its name and **Choose contact**. The button opens Android's contact picker
  (`ActivityResultContracts.PickContact`, no permission needed), and the pick links the contact. A pick on a contact with
  a phone ringtone also starts the ringtone sync when phone ringtones are on. The row then disappears and the count goes
  up. A failed or cancelled pick changes nothing; contact detail can still link it later.
- The Phone ringtones card keeps its count, "Turn on phone ringtones" and "N contacts ring with their character again".
  Its list of missing contacts moved to the Contacts card.

Defaults the owner may change (on #242, next to the ringtone backup defaults):

- `READ_CONTACTS` alone for matching, rather than both contacts permissions. This is minimum intrusion (#67).
  Android grants `WRITE_CONTACTS` without another prompt when phone ringtones need it, because both are in the same
  permission group.
- Unmatched contacts are unlinked, as ADR 0057 does for ringtones.
