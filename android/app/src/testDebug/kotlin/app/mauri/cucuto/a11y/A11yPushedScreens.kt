package app.mauri.cucuto.a11y

import androidx.compose.runtime.Composable
import app.mauri.cucuto.core.data.db.PackSource
import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.pack.InstallAction
import app.mauri.cucuto.core.pack.PackCandidate
import app.mauri.cucuto.core.pack.PackSelection
import app.mauri.cucuto.core.ringtone.ContactLinkRestore
import app.mauri.cucuto.core.ringtone.UnmatchedContact
import app.mauri.cucuto.home.AssignVoiceContent
import app.mauri.cucuto.home.HomeContent
import app.mauri.cucuto.home.previewHomeState
import app.mauri.cucuto.home.previewHomeStateWithQuestion
import app.mauri.cucuto.packs.AssignedContact
import app.mauri.cucuto.packs.ImportReview
import app.mauri.cucuto.packs.ImportUi
import app.mauri.cucuto.packs.LibraryCharacter
import app.mauri.cucuto.packs.LibraryPack
import app.mauri.cucuto.packs.ReviewPack
import app.mauri.cucuto.packs.ui.ImportReviewScreen
import app.mauri.cucuto.packs.ui.PackDetailContent
import app.mauri.cucuto.packspec.Character
import app.mauri.cucuto.packspec.Clip
import app.mauri.cucuto.packspec.PackManifest
import app.mauri.cucuto.ui.contacts.AddFromContactsContent
import app.mauri.cucuto.ui.contacts.AddFromContactsState
import app.mauri.cucuto.ui.contacts.ConfirmLinkContent
import app.mauri.cucuto.ui.contacts.previewContactsState
import app.mauri.cucuto.ui.creator.CreatorFinishContent
import app.mauri.cucuto.ui.creator.FinishUi
import app.mauri.cucuto.ui.discover.DiscoverActions
import app.mauri.cucuto.ui.discover.RepositoriesScreen
import app.mauri.cucuto.ui.discover.RepositoryForm
import app.mauri.cucuto.ui.discover.RepositoryFormScreen
import app.mauri.cucuto.ui.discover.SavedRepository
import app.mauri.cucuto.ui.ringtone.RingtonePickerActions
import app.mauri.cucuto.ui.ringtone.RingtonePickerContent
import app.mauri.cucuto.ui.ringtone.previewRingtonePickerState
import app.mauri.cucuto.ui.ringtone.rules.RingtoneRulesActions
import app.mauri.cucuto.ui.ringtone.rules.RingtoneRulesContent
import app.mauri.cucuto.ui.ringtone.rules.previewRingtoneRulesState
import app.mauri.cucuto.ui.settings.BackupExportContent
import app.mauri.cucuto.ui.settings.LicensesScreen
import app.mauri.cucuto.ui.settings.restore.RestoreActions
import app.mauri.cucuto.ui.settings.restore.RestoreContent
import app.mauri.cucuto.ui.settings.restore.RestoreStep
import app.mauri.cucuto.ui.settings.restore.RestoreSummary
import java.io.File

/**
 * The screens that used to be dialogs and sheets (#218, ADR 0055), with made-up content, for [A11yScreens]: the
 * accessibility checks and the large-font screenshots.
 */
internal object A11yPushedScreens {
    val all: List<Pair<String, @Composable () -> Unit>> =
        listOf(
            "restore" to { RestoreScreen() },
            "backup-export" to { BackupExportContent(onBack = {}, onConfirm = {}) },
            "licenses" to { LicensesScreen(onBack = {}) },
            "import-review" to { ImportReviewScreen() },
            "pack-detail" to { PackDetailContent(pack = pack, onBack = {}, onRemove = {}, onDuplicate = {}) },
            "repository-form" to { RepositoryFormScreen() },
            "repositories" to { RepositoriesScreen() },
            "creator-finish" to { CreatorFinishScreen() },
            "assign-voice" to { AssignVoiceContent(state = previewHomeState(), onBack = {}, onPick = {}) },
            "add-from-contacts" to { AddFromContactsContent(AddFromContactsState(), {}, {}, {}, {}, {}, {}) },
            "add-from-contacts-list" to { AddFromContactsContent(previewContactsState(), {}, {}, {}, {}, {}, {}) },
            "confirm-link" to {
                ConfirmLinkContent(
                    previewHomeStateWithQuestion().questions.first(),
                    onBack = {},
                    onPick = {},
                    onNone = {},
                )
            },
            "home-from-contacts" to {
                HomeContent(
                    state = previewHomeStateWithQuestion(),
                    onQueryChange = {},
                    onTogglePack = {},
                    onDismissRecent = {},
                    onOpenChat = {},
                    onOpenPacks = {},
                )
            },
            "ringtone-picker" to {
                RingtonePickerContent(
                    state = previewRingtonePickerState(),
                    beat = null,
                    actions = RingtonePickerActions(),
                )
            },
            "ringtone-rules" to {
                RingtoneRulesContent(state = previewRingtoneRulesState(), actions = RingtoneRulesActions())
            },
        )

    private val characters =
        listOf("Bleep the frog", "Buzz the bee", "Purr the cat").mapIndexed { i, name ->
            LibraryCharacter("c$i", name, sprite = null, seedColor = null, clips = emptyList(), ringtoneCount = 0)
        }

    private val pack =
        LibraryPack(
            id = "app.mauri.demo.critters",
            name = "Chiptune critters",
            version = "1.0.0",
            author = "Cucuto",
            license = "CC0-1.0",
            description = "Small animals that beep.",
            cover = null,
            characters = characters,
            sizeBytes = 120_000,
            source = PackSource.LOCAL,
            bundleId = null,
            contacts = listOf(AssignedContact(1, "Ada", "c0")),
        )

    private val manifest =
        PackManifest(
            formatVersion = 1,
            id = pack.id,
            name = pack.name,
            version = "1.0.0",
            author = "Cucuto",
            license = "CC0-1.0",
            characters =
                characters.map { c ->
                    Character(
                        id = c.id,
                        name = c.name,
                        sprite = "${c.id}/sprite.png",
                        clips = listOf(Clip("${c.id}/a.ogg")),
                    )
                },
        )

    @Composable
    private fun RestoreScreen() {
        RestoreContent(
            step =
                RestoreStep.Done(
                    summary =
                        RestoreSummary(
                            contacts = 12,
                            created = 3,
                            voicesApplied = 10,
                            conflictsKept = 1,
                            keysLeftAlone = 0,
                            repositoriesAdded = 1,
                            credentialsRestored = 0,
                            packsMissing = 1,
                            contactLinks =
                                ContactLinkRestore(
                                    restored = 4,
                                    linked = 3,
                                    unmatched = listOf(UnmatchedContact(7, "Grandpa", ringtone = true)),
                                    ringtones = 3,
                                    ringtonesLinked = 2,
                                    waitingForPermission = false,
                                    ringtonesEnabled = false,
                                ),
                        ),
                    reusableSource = "content://backup",
                ),
            actions = RestoreActions(),
        )
    }

    @Composable
    private fun ImportReviewScreen() {
        val candidate =
            PackCandidate(
                root = "critters",
                manifest = manifest,
                action = InstallAction.INSTALL,
                installedVersion = null,
                sizeBytes = 120_000,
                fileCount = 7,
            )
        ImportReviewScreen(
            import =
                ImportUi.Review(
                    review =
                        ImportReview(
                            fileName = "critters.soundpack.zip",
                            bundle = null,
                            packs = listOf(ReviewPack(candidate, characters)),
                            warnings = emptyList(),
                            handle = null,
                        ),
                    selection = PackSelection(selected = setOf(pack.id)),
                ),
            onSelect = { _, _ -> },
            onInstall = {},
            onPickAnother = null,
            onBack = {},
        )
    }

    @Composable
    private fun RepositoryFormScreen() {
        RepositoryFormScreen(
            form = RepositoryForm(input = "https://packs.example.com/repository.json"),
            onChange = {},
            onSubmit = {},
            onBack = {},
        )
    }

    @Composable
    private fun RepositoriesScreen() {
        val repositories =
            listOf(
                SavedRepository(
                    0,
                    "https://cucuto.mauri.app/repository.json",
                    "Cucuto",
                    true,
                    true,
                    RepositoryAuthType.NONE,
                ),
                SavedRepository(
                    1,
                    "https://packs.example.com/repository.json",
                    "Friends' packs",
                    false,
                    true,
                    RepositoryAuthType.BASIC,
                ),
            )
        RepositoriesScreen(repositories = repositories, actions = DiscoverActions(), onBack = {})
    }

    @Composable
    private fun CreatorFinishScreen() {
        CreatorFinishContent(
            finish = FinishUi.Ready(File("critters.soundpack.zip"), manifest, warnings = emptyList()),
            onInstall = {},
            onExport = {},
            onShare = {},
            onDone = {},
            onBack = {},
        )
    }
}
