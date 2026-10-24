# 0055. Screens over dialogs: flows and long content are destinations

- Status: accepted (defaults the owner may override: #237)
- Date: 2026-10-24
- Issue: #218 (the owner's answer on #166, item 9)

## Context

The owner on #166: "i preffer a screen unless the content is very minimal and temporary." The brief now says so
("Screens over dialogs"). Nine places still showed a flow or a body of content in a dialog or bottom sheet: the
restore flow (a full-screen dialog over Settings), the manual backup options, the licenses, the import review, the
pack detail, the repository form, the saved repositories, the creator's "Check and finish" and Home's "Assign a voice"
list. Confirmations, short choices and blocking progress cards stay dialogs.

The shell (ADR 0027) gives each tab a Navigation 3 back stack, and every entry its own `ViewModelStore`. Most of these
flows keep their state in the view model of the screen below them (an import lives in `PacksViewModel` or
`DiscoverViewModel`, the repository form in `DiscoverViewModel`, the check in `CreatorViewModel`), and those view
models carry the flows' tests.

Read: Navigation 3 ([saving and managing state](https://developer.android.com/guide/navigation/navigation-3/save-state),
view models scoped to entries, `NavEntryDecorator`), the lifecycle `ViewModelStoreProvider` and
`rememberViewModelStoreOwner` APIs (lifecycle 2.11), Material 3 top app bars, and `NavigationBackHandler`
(`androidx.navigationevent`).

## Decision

**Each conversion is a destination** (`CucutoDestination`): `Restore(backupUri)`, `BackupExport`, `Licenses`,
`ImportReview(from)`, `PackDetail(packId)`, `RepositoryForm`, `Repositories`, `CreatorFinish(sourcePackId)` and
`AssignVoice`, pushed on the tab that opens it. They use `PushedScreen`: the shared `CucutoTopBar` (medium flexible
bar with a back arrow) in a `Scaffold`, and `BottomActionBar` for a step's buttons. They slide like every pushed
screen, predictive back included, and the navigation bar stays (only the setup guide is immersive).

**Child screens share their parent's view models.** The shell replaces `rememberViewModelStoreNavEntryDecorator` with
its own decorator (`EntryViewModels.kt`): one store per entry, cleared when the entry is popped, as before. An entry
declared with `childOf(parent)` metadata also gets the parent entry's store as `LocalParentViewModelStoreOwner`
(looked up by the parent's tab-prefixed content key, so it exists even when the parent was never composed in this
process), and reads it with `parentViewModel()`. The import review, pack detail, repository screens, backup options,
"Check and finish" and "Assign a voice" are children; the flows keep one state and their existing tests.
`OnEntryPopped` runs a callback from a tiny view model in the child's own store when that store is cleared, so a
child closes its part of the parent's state (an open import, the form, the check) only when it really leaves its
stack, not on a rotation or a tab switch.

**Opening a child is an event kept as state.** `ImportController.showReview` and `DiscoverViewModel.showForm` turn true
when an import or the form starts (a tap, "Open with", Home's demo packs, an "Add to app" link) and false once the
screen is open. The parent pushes the child when it sees true, so a child left with back is never pushed again while
its state is still being closed. A child leaves by itself when its state is over (installed, saved) or missing.

**State across process death.** The back stacks survive (ADR 0027). Restore keeps the backup's URI in its key and
opens it again (the picker's read grant lasts until reboot); an encrypted backup asks for its password again, and the
packs and contacts already restored stay restored, so going through it again is harmless. Pack detail, saved
repositories, licenses, the backup options' switches and the assign list come back as they were. Passwords never go
into saved state. An import in progress (its temporary files and the file's grant), the repository form and the
check's result live only in memory, as they did in the sheets: after process death those screens close and the
parent shows, with nothing lost that was saved (the creator's draft is saved after every change).

**Details.** Restore's "Choose another file" replaces the Restore screen with one for the new file
(`AppNavigator.replace`), and its summary's links to the setup guide and Diagnostics replace it too, so back returns
where the restore started. Settings and Home's first-run card start a restore with `rememberBackupPicker`; keeping
automatic backups in the restored file moved into `RestoreViewModel`. The backup options close once a place is picked
and Settings shows the export's progress and result; cancelling the picker stays on the screen. Back is blocked while
packs install (import review, "Check and finish"), as the sheets were. Picking a chat in "Assign a voice" hands over
to the character picker, so back from there returns to Home. Removing a pack from its detail asks on Packs, as before.

## Consequences

- Nine dialogs and sheets fewer; the flows can be linked to, show in the back stack and get predictive back.
- New code in `navigation/`: `EntryViewModels.kt` and `AppNavigator.replace`; `CucutoTopBar` and `BackButton` gain
  `backEnabled`. No new dependency.
- The CompositionLocal allowlist lint is suppressed for `LocalParentViewModelStoreOwner` (file-level), which sits next
  to `LocalViewModelStoreOwner` for the same reason.
- Tests: `EntryViewModelsTest` (a child shares its parent's view model, hears its pop, not a tab switch),
  `AppNavigatorTest` (replace, the new keys serialize), view model tests for the open-screen events and for Restore
  opening its file once per view model; every new screen joins the accessibility checks and the large-font goldens.
  The import review golden is now the screen.
