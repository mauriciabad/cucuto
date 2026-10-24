package app.mauri.cucuto.a11y

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.contact.ContactDetailContent
import app.mauri.cucuto.contact.previewContactDetailState
import app.mauri.cucuto.core.audio.PcmAudio
import app.mauri.cucuto.core.creator.CreatorDraft
import app.mauri.cucuto.core.creator.DraftCharacter
import app.mauri.cucuto.core.creator.DraftClip
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.db.PackSource
import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.repo.RepositorySnapshot
import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.home.HomeContent
import app.mauri.cucuto.home.JourneyStep
import app.mauri.cucuto.home.buildHomeState
import app.mauri.cucuto.home.previewHomeState
import app.mauri.cucuto.packs.AssignedContact
import app.mauri.cucuto.packs.LibraryCharacter
import app.mauri.cucuto.packs.LibraryPack
import app.mauri.cucuto.packs.PacksUiState
import app.mauri.cucuto.packs.ui.PacksContent
import app.mauri.cucuto.packspec.RepositoryIndex
import app.mauri.cucuto.packspec.SpecJson
import app.mauri.cucuto.picker.PickerContent
import app.mauri.cucuto.picker.previewPickerState
import app.mauri.cucuto.ui.creator.CharacterPage
import app.mauri.cucuto.ui.creator.ClipEditorPage
import app.mauri.cucuto.ui.creator.ClipEditorUi
import app.mauri.cucuto.ui.creator.PackPage
import app.mauri.cucuto.ui.creator.RecorderPage
import app.mauri.cucuto.ui.creator.RecorderUi
import app.mauri.cucuto.ui.discover.DiscoverActions
import app.mauri.cucuto.ui.discover.DiscoverContent
import app.mauri.cucuto.ui.discover.DiscoverUiState
import app.mauri.cucuto.ui.discover.InstalledRef
import app.mauri.cucuto.ui.discover.RepoLoad
import app.mauri.cucuto.ui.discover.SavedRepository
import app.mauri.cucuto.ui.discover.sections
import app.mauri.cucuto.ui.haptics.HapticDraft
import app.mauri.cucuto.ui.haptics.HapticEditorActions
import app.mauri.cucuto.ui.haptics.HapticEditorContent
import app.mauri.cucuto.ui.haptics.HapticEditorState
import app.mauri.cucuto.ui.onboarding.OnboardingContent
import app.mauri.cucuto.ui.onboarding.OnboardingStep
import app.mauri.cucuto.ui.ringtone.PhoneRingtoneActions
import app.mauri.cucuto.ui.ringtone.PhoneRingtoneContent
import app.mauri.cucuto.ui.ringtone.PhoneRingtoneUiState
import app.mauri.cucuto.ui.ringtone.VariantChip
import app.mauri.cucuto.ui.settings.SettingsActions
import app.mauri.cucuto.ui.settings.SettingsContent
import app.mauri.cucuto.ui.settings.SettingsState
import app.mauri.cucuto.ui.status.ModuleCheck
import app.mauri.cucuto.ui.status.PacksCheck
import app.mauri.cucuto.ui.status.ScopeCheck
import app.mauri.cucuto.ui.status.SelfTestMessage
import app.mauri.cucuto.ui.status.StatusCard
import app.mauri.cucuto.ui.status.StatusReport
import app.mauri.cucuto.ui.status.WhatsAppCheck
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File

/**
 * Every screen of the accessibility pass (#29) with made-up content, for the accessibility checks and the large-font
 * screenshots, plus [A11yMoreScreens] (#226). A new screen joins every check with one line in [all] (and one test in
 * `LargeFontScreenshotTest` for its golden).
 */
internal object A11yScreens {
    val all: List<Pair<String, @Composable () -> Unit>> =
        listOf<Pair<String, @Composable () -> Unit>>(
            "status-card" to { StatusCardScreen() },
            "onboarding-requirements" to { OnboardingScreen(OnboardingStep.Requirements) },
            "onboarding-enable" to { OnboardingScreen(OnboardingStep.Enable) },
            "home" to { HomeScreen() },
            "picker" to { PickerScreen() },
            "packs" to { PacksScreen() },
            "discover" to { DiscoverScreen() },
            "contact-detail" to { ContactScreen() },
            "settings" to { SettingsScreen() },
            "haptic-editor" to { HapticEditorScreen() },
            "home-empty" to { HomeEmptyScreen() },
            "phone-ringtone" to { PhoneRingtoneScreen() },
            "creator-pack" to { CreatorPackScreen() },
            "creator-character" to { CreatorCharacterScreen() },
            "creator-recorder" to { CreatorRecorderScreen() },
            "creator-clip-editor" to { CreatorClipEditorScreen() },
        ) + A11yPushedScreens.all + A11yMoreScreens.all

    /** The screen called [name] in [all]. */
    fun content(name: String): @Composable () -> Unit = all.first { it.first == name }.second

    /** Home before any pack is installed: the first-run journey offers the demo packs. */
    @Composable
    private fun HomeEmptyScreen() {
        HomeContent(
            state = buildHomeState(chats = emptyList(), packs = emptyList(), query = "", packFilter = null, now = 0),
            onQueryChange = {},
            onTogglePack = {},
            onDismissRecent = {},
            onOpenChat = {},
            onOpenPacks = {},
            journey = JourneyStep.ImportPacks,
        )
    }

    @Composable
    private fun PhoneRingtoneScreen() {
        // Inside the contact detail's scrolling column on the device.
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            PhoneRingtoneContent(
                state =
                    PhoneRingtoneUiState(
                        loading = false,
                        enabled = true,
                        contactId = 1,
                        characterName = "Bleep the frog",
                        hasCharacter = true,
                        variants =
                            listOf(VariantChip("day.ogg", "Morning croak"), VariantChip("night.ogg", "Night song")),
                        linked = true,
                        linkedName = "Ada Lovelace",
                        using = true,
                        modes = listOf(RingtoneMode.PACK, RingtoneMode.RANDOM, RingtoneMode.TIME, RingtoneMode.FIXED),
                        ringingWith = "Morning croak",
                        exportTitle = "Bleep the frog · Morning croak",
                    ),
                actions = PhoneRingtoneActions(),
            )
        }
    }

    private val creatorCharacter =
        DraftCharacter(
            key = "frog",
            name = "Bleep the frog",
            seedColor = "#3A7D44",
            clips =
                listOf(
                    DraftClip(key = "a", file = "a.ogg", label = "Croak", durationMs = 1_200),
                    DraftClip(key = "b", file = "b.ogg", label = "", weight = 2.0, durationMs = 2_400),
                ),
        )

    private val creatorDraft =
        CreatorDraft(
            name = "Chiptune critters",
            author = "Cucuto",
            description = "Small animals that beep.",
            characters = listOf(creatorCharacter, DraftCharacter(key = "bee", name = "Buzz the bee")),
        )

    @Composable
    private fun CreatorPackScreen() {
        PackPage(
            draft = creatorDraft,
            media = { File(it) },
            onBack = {},
            onName = {},
            onAuthor = {},
            onLicense = {},
            onDescription = {},
            onPickCover = {},
            onRemoveCover = {},
            onOpenCharacter = {},
            onAddCharacter = {},
            onCheck = {},
            onStartOver = {},
        )
    }

    @Composable
    private fun CreatorCharacterScreen() {
        CharacterPage(
            character = creatorCharacter,
            suggestions = listOf(0xFF3A7D44.toInt(), 0xFFB5523B.toInt()),
            media = { File(it) },
            canEditHaptic = true,
            onBack = {},
            onName = {},
            onPickSprite = {},
            onSeedColor = {},
            onRecord = {},
            onPickAudio = {},
            onPlayClip = {},
            onClipLabel = { _, _ -> },
            onClipWeight = { _, _ -> },
            onRemoveClip = {},
            onHaptic = {},
            onEditHaptic = {},
            onRemove = {},
        )
    }

    @Composable
    private fun CreatorRecorderScreen() {
        RecorderPage(
            recorder =
                RecorderUi(
                    characterKey = "frog",
                    recording = true,
                    elapsedMs = 2_300,
                    levels = listOf(0.2f, 0.6f),
                ),
            onBack = {},
            onRecord = {},
            onStop = {},
        )
    }

    @Composable
    private fun CreatorClipEditorScreen() {
        val audio = PcmAudio(ShortArray(SAMPLE_RATE * 4) { ((it % 200) * 100).toShort() }, SAMPLE_RATE)
        ClipEditorPage(
            editor = ClipEditorUi(characterKey = "frog", source = audio, startMs = 500, endMs = 2_500, label = "Croak"),
            onBack = {},
            onRange = { _, _ -> },
            onZoomIn = {},
            onZoomOut = {},
            onPlay = {},
            onTrimSilence = {},
            onNormalize = {},
            onLabel = {},
            onSave = {},
        )
    }

    @Composable
    private fun StatusCardScreen() {
        StatusCard(
            report =
                StatusReport(
                    module = ModuleCheck.AwaitingSelfTest,
                    scope = ScopeCheck.SystemOnly,
                    whatsApp = WhatsAppCheck.Found(listOf("com.whatsapp")),
                    packs = PacksCheck.None,
                ),
            beat = null,
            selfTest = SelfTestMessage.None,
            onAction = {},
        )
    }

    @Composable
    private fun OnboardingScreen(step: OnboardingStep) {
        OnboardingContent(
            step = step,
            report = null,
            selfTest = SelfTestMessage.None,
            onStep = {},
            onFinish = {},
            onAction = {},
            onPostSelfTest = {},
        )
    }

    @Composable
    private fun HomeScreen() {
        HomeContent(
            state = previewHomeState(),
            onQueryChange = {},
            onTogglePack = {},
            onDismissRecent = {},
            onOpenChat = {},
            onOpenPacks = {},
        )
    }

    @Composable
    private fun PickerScreen() {
        PickerContent(
            state = previewPickerState(),
            assignBeat = null,
            onTap = {},
            onUnassign = {},
            onBack = {},
            onOpenPacks = {},
        )
    }

    @Composable
    private fun PacksScreen() {
        val characters =
            listOf("Bleep the frog", "Buzz the bee", "Purr the cat").mapIndexed { i, name ->
                LibraryCharacter("c$i", name, sprite = null, seedColor = null, clips = emptyList(), ringtoneCount = 0)
            }
        val pack =
            LibraryPack(
                id = "app.mauri.demo.critters",
                name = "Chiptune critters",
                version = "1.0.0",
                author = "Cucuto",
                license = "CC0-1.0",
                description = null,
                cover = null,
                characters = characters,
                sizeBytes = 120_000,
                source = PackSource.LOCAL,
                bundleId = null,
                contacts = listOf(AssignedContact(1, "Ada", "c0")),
            )
        PacksContent(
            state = PacksUiState(packs = listOf(pack)),
            onDiscover = {},
            onAssignVoices = {},
            onImport = {},
            onRemove = {},
            onConsumeMessage = {},
        )
    }

    @Composable
    private fun DiscoverScreen() {
        val repo =
            SavedRepository(
                1,
                "https://packs.example.com/repository.json",
                "Friends' packs",
                false,
                true,
                RepositoryAuthType.NONE,
            )
        val snapshot =
            RepositorySnapshot(
                indexUrl = repo.url.toHttpUrl(),
                baseUrl = repo.url.toHttpUrl(),
                index = SpecJson.decodeFromString(RepositoryIndex.serializer(), DISCOVER_INDEX),
                warnings = emptyList(),
                etag = null,
            )
        val installed = listOf(InstalledRef("app.example.cats", "1.0.0", null, null))
        DiscoverContent(
            state =
                DiscoverUiState(
                    sections =
                        sections(
                            listOf(repo),
                            mapOf(repo.id to RepoLoad(snapshot = snapshot)),
                            installed,
                            "",
                            null,
                        ),
                    repositories = listOf(repo),
                ),
            previews = { _, _ -> null },
            actions = DiscoverActions(),
            onOpenRepositories = {},
        )
    }

    @Composable
    private fun ContactScreen() {
        ContactDetailContent(
            state = previewContactDetailState(),
            onBack = {},
            onChangeCharacter = {},
            onUnassign = {},
            onSimulate = {},
            onPlayClip = {},
            onToggleClip = { _, _ -> },
            onGroupPolicyChange = {},
            onConsumeFailure = {},
        )
    }

    @Composable
    private fun SettingsScreen() {
        SettingsContent(state = SettingsState(), systemRemovesAnimations = false, actions = SettingsActions())
    }

    @Composable
    private fun HapticEditorScreen() {
        HapticEditorContent(
            state =
                HapticEditorState(
                    loading = false,
                    chat = ChatKey("com.whatsapp", "100000000000001@lid"),
                    draft =
                        HapticDraft(
                            events =
                                listOf(
                                    HapticEvent.Preset(0, HapticPreset.CLICK, 800),
                                    HapticEvent.Envelope(
                                        220,
                                        listOf(ControlPoint(1000, 800, 120), ControlPoint(0, 400, 180)),
                                    ),
                                ),
                            selected = 1,
                        ),
                    dirty = true,
                ),
            actions = HapticEditorActions(),
        )
    }
}

/** One made-up repository entry: a pack with one character. */
private fun entry(
    id: String,
    name: String,
    version: String,
    character: String,
) = """
    {
      "id": "$id", "type": "pack", "name": "$name", "version": "$version", "author": "Test author",
      "license": "CC0-1.0", "tags": [], "cover": "previews/$id/cover.png",
      "characters": [
        { "id": "c1", "name": "$character", "sprite": "previews/$id/c1.png", "sample": "previews/$id/c1.ogg" }
      ],
      "download": { "url": "packs/$id-$version.soundpack.zip", "size": 4096, "sha256": "${"0".repeat(SHA_CHARS)}" }
    }
    """

/** A bundle with Dogs (also listed alone, so its row can install it) and Birds (only in the bundle), ADR 0064. */
private val BUNDLE_ENTRY =
    """
    {
      "id": "app.example.friends", "type": "bundle", "name": "Friends' bundle", "version": "1.0.0",
      "author": "Test author", "license": "CC0-1.0",
      "packs": [
        { "id": "app.example.dogs", "name": "Dogs", "version": "1.0.0", "characters": [ { "id": "c1", "name": "Rex" } ] },
        { "id": "app.example.birds", "name": "Birds", "version": "1.0.0", "characters": [ { "id": "c1", "name": "Kiwi" } ] }
      ],
      "download": { "url": "packs/friends-1.0.0.soundpack.zip", "size": 8192, "sha256": "${"0".repeat(SHA_CHARS)}" }
    }
    """

/** A repository index with a pack installed in an older version, then a bundle and one of its packs on its own. */
private val DISCOVER_INDEX =
    """{ "formatVersion": 1, "name": "Friends' packs", "packs": [${
        entry("app.example.cats", "Cats", "1.1.0", "Tabby")
    }, $BUNDLE_ENTRY, ${entry("app.example.dogs", "Dogs", "1.0.0", "Rex")}] }"""

private const val SHA_CHARS = 64
private const val SAMPLE_RATE = 8_000
