package app.mauri.cucuto.video

import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.pack.InstallAction
import app.mauri.cucuto.core.pack.PackCandidate
import app.mauri.cucuto.core.pack.PackSelection
import app.mauri.cucuto.core.repo.DEFAULT_REPOSITORY_URL
import app.mauri.cucuto.core.repo.ExpectedFile
import app.mauri.cucuto.core.repo.ResolvedEntry
import app.mauri.cucuto.packs.ImportReview
import app.mauri.cucuto.packs.ImportUi
import app.mauri.cucuto.packs.ReviewPack
import app.mauri.cucuto.packspec.BundleManifest
import app.mauri.cucuto.packspec.BundledPack
import app.mauri.cucuto.packspec.Character
import app.mauri.cucuto.packspec.CharacterPreview
import app.mauri.cucuto.packspec.Clip
import app.mauri.cucuto.packspec.Download
import app.mauri.cucuto.packspec.PackManifest
import app.mauri.cucuto.packspec.RepositoryEntry
import app.mauri.cucuto.screenshots.DemoContent
import app.mauri.cucuto.screenshots.DemoPack
import app.mauri.cucuto.ui.discover.DEMO_BUNDLE_ID
import app.mauri.cucuto.ui.discover.DemoPacksUi
import app.mauri.cucuto.ui.discover.DiscoverUiState
import app.mauri.cucuto.ui.discover.InstallState
import app.mauri.cucuto.ui.discover.PackCard
import app.mauri.cucuto.ui.discover.PreviewLoader
import app.mauri.cucuto.ui.discover.RemoteCharacter
import app.mauri.cucuto.ui.discover.RepositorySection
import app.mauri.cucuto.ui.discover.SavedRepository
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File

/**
 * Everything the explainer video shows: the brief's made-up cast (Ada, Bea and Leo, docs/explainer-video.md) and the
 * two CC0 demo packs from `packs/`. Never put a real name, number or chat here. The chat keys are obviously fake.
 */
internal object ExplainerCast {
    val ada = key(1)
    val bea = key(2)
    val leo = key(3)

    val frog: ChatVoice = DemoContent.critters.voice("frog")

    /** The three people who messaged (Ada newest), [adaVoice] being Ada's character once she has one. */
    fun chats(
        now: Long,
        adaVoice: ChatVoice? = null,
    ): List<Chat> =
        listOf(
            Chat(ada, 101, "Ada", false, adaVoice, now - 4 * MINUTE, messageCount = 3),
            Chat(bea, 102, "Bea", false, null, now - 9 * MINUTE, messageCount = 2),
            Chat(leo, 103, "Leo", false, null, now - 14 * MINUTE, messageCount = 1),
        )

    val packs = listOf(DemoContent.critters, DemoContent.kitchen)

    // Discover: the default repository with the "Cucuto demo packs" bundle, which Home asked for (#179).

    private val repository =
        SavedRepository(
            id = REPOSITORY_ID,
            url = DEFAULT_REPOSITORY_URL,
            name = "Cucuto packs",
            isDefault = true,
            enabled = true,
            authType = RepositoryAuthType.NONE,
        )

    private const val BUNDLE_NAME = "Cucuto demo packs"
    private const val BUNDLE_SIZE = 710_000L

    fun discover(
        demo: DemoPacksUi?,
        installed: Boolean,
    ): DiscoverUiState {
        val bundle = bundleCard(if (installed) InstallState.INSTALLED else InstallState.NEW)
        return DiscoverUiState(
            sections =
                listOf(
                    RepositorySection(
                        repository = repository,
                        host = "cucuto.mauri.app",
                        title = "Cucuto packs",
                        loading = false,
                        error = null,
                        loaded = true,
                        cards = listOf(bundle),
                        totalCards = 3,
                    ),
                ),
            repositories = listOf(repository),
            focus = DEMO_BUNDLE_ID,
            demo = demo,
        )
    }

    /** Covers and sprites of the repository's URLs, read from `packs/` (nothing is downloaded). */
    val previews =
        PreviewLoader {
            url,
            _,
            ->
            File(DemoContent.packsDir, url.encodedPath.removePrefix(REPO_PATH)).takeIf { it.isFile }
        }

    private fun bundleCard(state: InstallState): PackCard {
        val entry =
            RepositoryEntry(
                id = DEMO_BUNDLE_ID,
                type = PackCard.TYPE_BUNDLE,
                name = BUNDLE_NAME,
                version = "1.0.0",
                author = "Cucuto contributors",
                license = "CC0-1.0",
                description = "Both demo packs in one file: Chiptune critters and Kitchen orchestra.",
                tags = listOf("demo"),
                packs = packs.map { BundledPack(it.id, it.name, "1.0.0", "CC0-1.0") },
                download = Download("cucuto-demo-packs.soundpack.zip", BUNDLE_SIZE, "0".repeat(SHA256_HEX)),
            )
        return PackCard(
            key = "$REPOSITORY_ID/$DEMO_BUNDLE_ID",
            repositoryId = REPOSITORY_ID,
            entry =
                ResolvedEntry(
                    entry = entry,
                    downloadUrl = url("cucuto-demo-packs.soundpack.zip"),
                    expected = ExpectedFile(BUNDLE_SIZE, "0".repeat(SHA256_HEX)),
                    coverUrl = url("${DemoContent.critters.folder}/cover.svg"),
                    supported = true,
                ),
            cover = url("${DemoContent.critters.folder}/cover.svg"),
            characters =
                packs.flatMap { pack ->
                    pack.characters.map { c ->
                        RemoteCharacter(
                            key = "$REPOSITORY_ID/${pack.id}/${c.id}",
                            name = c.name,
                            sprite = url("${pack.folder}/${c.id}/sprite.svg"),
                            sample = null,
                            seedColor = c.seedColor.toInt(),
                        )
                    }
                },
            bundlePacks = packs.map { it.name },
            state = state,
        )
    }

    // The import review of the downloaded bundle.

    fun downloading(bytes: Long): ImportUi =
        ImportUi.Working(progress = null, download = ImportUi.Download(BUNDLE_NAME, bytes, BUNDLE_SIZE))

    const val BUNDLE_BYTES = BUNDLE_SIZE

    fun review(installing: Boolean = false): ImportUi.Review =
        ImportUi.Review(
            review =
                ImportReview(
                    fileName = "cucuto-demo-packs.soundpack.zip",
                    bundle =
                        BundleManifest(
                            formatVersion = 1,
                            type = PackCard.TYPE_BUNDLE,
                            id = DEMO_BUNDLE_ID,
                            name = BUNDLE_NAME,
                            version = "1.0.0",
                            author = "Cucuto contributors",
                            license = "CC0-1.0",
                            packs = packs.map { it.folder },
                        ),
                    packs = packs.map(::reviewPack),
                    warnings = emptyList(),
                    handle = null,
                ),
            selection = PackSelection(selected = packs.map { it.id }.toSet()),
            installing = installing,
        )

    private fun reviewPack(pack: DemoPack): ReviewPack {
        val manifest =
            PackManifest(
                formatVersion = 1,
                id = pack.id,
                name = pack.name,
                version = "1.0.0",
                author = "Cucuto contributors",
                license = "CC0-1.0",
                description = pack.description,
                cover = "cover.svg",
                characters =
                    pack.characters.map { c ->
                        Character(
                            id = c.id,
                            name = c.name,
                            sprite = "${c.id}/sprite.svg",
                            clips = pack.clips(c.id).map { Clip("${c.id}/clips/${it.name}") },
                        )
                    },
            )
        return ReviewPack(
            PackCandidate(
                root = pack.folder,
                manifest = manifest,
                action = InstallAction.INSTALL,
                installedVersion = null,
                sizeBytes = pack.sizeBytes,
                fileCount = 13,
            ),
            pack.libraryCharacters(),
        )
    }

    private fun key(n: Int) = ChatKey("com.whatsapp", "1000000000%05d@lid".format(100 + n))

    private fun url(path: String) = "$BASE_URL$path".toHttpUrl()

    private const val MINUTE = 60_000L
    private const val REPOSITORY_ID = 1L
    private const val REPO_PATH = "/repo/"
    private const val BASE_URL = "https://cucuto.mauri.app/repo/"
    private const val SHA256_HEX = 64
}
