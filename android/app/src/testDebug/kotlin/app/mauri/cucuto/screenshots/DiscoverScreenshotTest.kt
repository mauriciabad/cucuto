package app.mauri.cucuto.screenshots

import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.repo.DEFAULT_REPOSITORY_URL
import app.mauri.cucuto.core.repo.RepositorySnapshot
import app.mauri.cucuto.navigation.TopLevelTab
import app.mauri.cucuto.packspec.BundledPack
import app.mauri.cucuto.packspec.CharacterPreview
import app.mauri.cucuto.packspec.Download
import app.mauri.cucuto.packspec.RepositoryEntry
import app.mauri.cucuto.packspec.RepositoryIndex
import app.mauri.cucuto.ui.discover.DEMO_BUNDLE_ID
import app.mauri.cucuto.ui.discover.DiscoverActions
import app.mauri.cucuto.ui.discover.DiscoverContent
import app.mauri.cucuto.ui.discover.DiscoverUiState
import app.mauri.cucuto.ui.discover.InstalledRef
import app.mauri.cucuto.ui.discover.PreviewLoader
import app.mauri.cucuto.ui.discover.RepoLoad
import app.mauri.cucuto.ui.discover.SavedRepository
import app.mauri.cucuto.ui.discover.sections
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import java.io.File

/**
 * Discover, a tab of the app shell: the default repository as the website publishes it, the demo bundle and each demo
 * pack on its own (#247, ADR 0064), with Chiptune critters installed. Covers and sprites come from `packs/` through a
 * fake [PreviewLoader]: nothing is downloaded.
 */
class DiscoverScreenshotTest(
    variant: Variant,
) : ScreenshotTest(variant) {
    @Test
    fun discover() =
        screenshot("discover", store = 5) {
            val repository =
                SavedRepository(
                    id = REPOSITORY_ID,
                    url = DEFAULT_REPOSITORY_URL,
                    name = "Cucuto packs",
                    isDefault = true,
                    enabled = true,
                    authType = RepositoryAuthType.NONE,
                )
            val index =
                RepositoryIndex(
                    formatVersion = 1,
                    name = "Cucuto packs",
                    packs =
                        listOf(
                            bundle(),
                            pack(DemoContent.critters, listOf("chiptune", "animals", "demo")),
                            pack(DemoContent.kitchen, listOf("percussion", "objects", "demo")),
                        ),
                )
            val snapshot = RepositorySnapshot(BASE_URL.toHttpUrl(), BASE_URL.toHttpUrl(), index, emptyList(), null)
            val installed = listOf(InstalledRef(DemoContent.critters.id, "1.0.0", null, null))
            InShell(TopLevelTab.Discover) {
                DiscoverContent(
                    state =
                        DiscoverUiState(
                            sections =
                                sections(
                                    listOf(repository),
                                    mapOf(REPOSITORY_ID to RepoLoad(snapshot = snapshot)),
                                    installed,
                                    query = "",
                                    tag = null,
                                ),
                            repositories = listOf(repository),
                            tags = listOf("demo", "animals", "chiptune", "objects", "percussion"),
                        ),
                    previews = { url, _ -> localFile(url.encodedPath) },
                    actions = DiscoverActions(),
                    onOpenRepositories = {},
                )
            }
        }

    private fun bundle() =
        RepositoryEntry(
            id = DEMO_BUNDLE_ID,
            type = "bundle",
            name = "Cucuto demo packs",
            version = "1.1.0",
            author = "Cucuto contributors",
            license = "CC0-1.0",
            description = "Every Cucuto demo pack in one file.",
            cover = "demo-bundle-cover.svg",
            packs =
                DemoContent.packs.map {
                    BundledPack(it.id, it.name, "1.0.0", "CC0-1.0", "${it.folder}/cover.svg", previews(it))
                },
            download = download(DEMO_BUNDLE_ID, DemoContent.packs.sumOf { it.sizeBytes }),
        )

    private fun pack(
        pack: DemoPack,
        tags: List<String>,
    ) = RepositoryEntry(
        id = pack.id,
        type = "pack",
        name = pack.name,
        version = "1.0.0",
        author = "Cucuto contributors",
        license = "CC0-1.0",
        description = pack.description,
        tags = tags,
        cover = "${pack.folder}/cover.svg",
        characters = previews(pack),
        download = download(pack.id, pack.sizeBytes),
    )

    private fun previews(pack: DemoPack) =
        pack.characters.map { c ->
            CharacterPreview(
                id = c.id,
                name = c.name,
                sprite = "${pack.folder}/${c.id}/sprite.svg",
                seedColor = "#%06X".format(c.seedColor.toInt() and RGB),
            )
        }

    private fun download(
        id: String,
        size: Long,
    ) = Download("packs/$id-1.0.0.soundpack.zip", size, "0".repeat(SHA256_HEX))

    /** `/repo/<path>` on the site is the same file in `packs/`. */
    private fun localFile(path: String): File? =
        File(DemoContent.packsDir, path.removePrefix(REPO_PATH)).takeIf { it.isFile }

    private companion object {
        const val REPOSITORY_ID = 1L
        const val REPO_PATH = "/repo/"
        const val BASE_URL = "https://cucuto.mauri.app/repo/index.json"
        const val SHA256_HEX = 64
        const val RGB = 0xFFFFFF
    }
}
