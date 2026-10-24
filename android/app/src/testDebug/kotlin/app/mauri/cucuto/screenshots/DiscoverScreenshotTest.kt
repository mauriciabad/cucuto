package app.mauri.cucuto.screenshots

import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.repo.DEFAULT_REPOSITORY_URL
import app.mauri.cucuto.core.repo.ExpectedFile
import app.mauri.cucuto.core.repo.ResolvedEntry
import app.mauri.cucuto.navigation.TopLevelTab
import app.mauri.cucuto.packspec.CharacterPreview
import app.mauri.cucuto.packspec.Download
import app.mauri.cucuto.packspec.RepositoryEntry
import app.mauri.cucuto.ui.discover.DiscoverActions
import app.mauri.cucuto.ui.discover.DiscoverContent
import app.mauri.cucuto.ui.discover.DiscoverUiState
import app.mauri.cucuto.ui.discover.InstallState
import app.mauri.cucuto.ui.discover.PackCard
import app.mauri.cucuto.ui.discover.PreviewLoader
import app.mauri.cucuto.ui.discover.RemoteCharacter
import app.mauri.cucuto.ui.discover.RepositorySection
import app.mauri.cucuto.ui.discover.SavedRepository
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import java.io.File

/**
 * Discover, a tab of the app shell: the default repository with the two demo packs, one installed and one new. Covers and sprites come from
 * `packs/` through a fake [PreviewLoader]: nothing is downloaded.
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
            val cards =
                listOf(
                    card(DemoContent.critters, listOf("chiptune", "animals", "demo"), InstallState.INSTALLED),
                    card(DemoContent.kitchen, listOf("percussion", "objects", "demo"), InstallState.NEW),
                )
            InShell(TopLevelTab.Discover) {
                DiscoverContent(
                    state =
                        DiscoverUiState(
                            sections =
                                listOf(
                                    RepositorySection(
                                        repository = repository,
                                        host = "cucuto.mauri.app",
                                        title = "Cucuto packs",
                                        loading = false,
                                        error = null,
                                        loaded = true,
                                        cards = cards,
                                        totalCards = cards.size,
                                    ),
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

    private fun card(
        pack: DemoPack,
        tags: List<String>,
        state: InstallState,
    ): PackCard {
        val entry =
            RepositoryEntry(
                id = pack.id,
                type = "pack",
                name = pack.name,
                version = "1.0.0",
                author = "Cucuto contributors",
                license = "CC0-1.0",
                description = pack.description,
                tags = tags,
                cover = "${pack.folder}/cover.svg",
                characters = pack.characters.map { CharacterPreview(it.id, it.name) },
                download = Download("${pack.folder}.soundpack.zip", pack.sizeBytes, "0".repeat(SHA256_HEX)),
            )
        return PackCard(
            key = "$REPOSITORY_ID/${pack.id}",
            repositoryId = REPOSITORY_ID,
            entry =
                ResolvedEntry(
                    entry = entry,
                    downloadUrl = url("${pack.folder}.soundpack.zip"),
                    expected = ExpectedFile(pack.sizeBytes, "0".repeat(SHA256_HEX)),
                    coverUrl = url("${pack.folder}/cover.svg"),
                    supported = true,
                ),
            cover = url("${pack.folder}/cover.svg"),
            characters =
                pack.characters.map { c ->
                    RemoteCharacter(
                        key = "$REPOSITORY_ID/${pack.id}/${c.id}",
                        name = c.name,
                        sprite = url("${pack.folder}/${c.id}/sprite.svg"),
                        sample = null,
                        seedColor = c.seedColor.toInt(),
                    )
                },
            bundlePacks = emptyList(),
            state = state,
        )
    }

    private fun url(path: String) = "$BASE_URL$path".toHttpUrl()

    /** `/repo/<pack folder>/…` on the site is the same file in `packs/`. */
    private fun localFile(path: String): File? =
        File(DemoContent.packsDir, path.removePrefix(REPO_PATH)).takeIf { it.isFile }

    private companion object {
        const val REPOSITORY_ID = 1L
        const val REPO_PATH = "/repo/"
        const val BASE_URL = "https://cucuto.mauri.app/repo/"
        const val SHA256_HEX = 64
    }
}
