package app.mauri.cucuto.ui.discover

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.repo.RepoError
import app.mauri.cucuto.core.repo.RepositorySnapshot
import app.mauri.cucuto.packspec.RepositoryIndex
import app.mauri.cucuto.packspec.SpecJson
import app.mauri.cucuto.ui.theme.CucutoTheme
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Discover's states on Robolectric: cards and badges, errors, offline copies and empty states. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class DiscoverContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val repo =
        SavedRepository(
            1,
            "https://packs.example.com/repository.json",
            "Friends' packs",
            false,
            true,
            RepositoryAuthType.NONE,
        )
    private val installed = mutableListOf<String>()
    private val retried = mutableListOf<Long>()
    private val signIns = mutableListOf<Long>()

    private fun snapshot(vararg entries: String) =
        RepositorySnapshot(
            indexUrl = repo.url.toHttpUrl(),
            baseUrl = repo.url.toHttpUrl(),
            index = SpecJson.decodeFromString(RepositoryIndex.serializer(), indexJson("Friends' packs", *entries)),
            warnings = emptyList(),
            etag = null,
        )

    private fun state(
        load: RepoLoad,
        installedPacks: List<InstalledRef> = emptyList(),
        query: String = "",
    ) = DiscoverUiState(
        sections = sections(listOf(repo), mapOf(repo.id to load), installedPacks, query, null),
        repositories = listOf(repo),
        query = query,
    )

    private fun show(state: DiscoverUiState) {
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                DiscoverContent(
                    state = state,
                    previews = { _, _ -> null },
                    actions =
                        DiscoverActions(
                            onInstall = { installed += it.id },
                            onRetry = { retried += it.id },
                            onSignIn = { signIns += it.id },
                        ),
                    onOpenRepositories = {},
                )
            }
        }
    }

    @Test
    fun cardsShowTheHostCharactersAndWhatInstallingDoes() {
        val load =
            RepoLoad(
                snapshot =
                    snapshot(
                        packEntry("app.example.cats", "Cats", version = "1.1.0", character = "Tabby"),
                        packEntry("app.example.dogs", "Dogs"),
                    ),
            )
        show(state(load, listOf(InstalledRef("app.example.cats", "1.0.0", null, null))))

        compose.onNodeWithText("packs.example.com").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tabby").assertIsDisplayed()
        compose.onNodeWithText("Update available").assertIsDisplayed()
        compose.onNodeWithText("Update").performClick()
        compose.onNodeWithText("Install").performScrollTo().performClick()
        assertEquals(listOf("app.example.cats", "app.example.dogs"), installed)
    }

    @Test
    fun aCustomVibrationIsDrawnAndAMissingOneIsNot() {
        val custom =
            """{ "mode": "custom", "events": [ { "at": 0, "preset": "CLICK" }, { "at": 200, "preset": "TICK" } ] }"""
        val load =
            RepoLoad(
                snapshot =
                    snapshot(
                        packEntry("app.example.cats", "Cats", character = "Tabby", haptic = custom),
                        packEntry("app.example.bees", "Bees", character = "Bzz"),
                    ),
            )
        show(state(load))

        // The pattern lasts until 50 ms after its last tap.
        compose.onNodeWithContentDescription("Vibration: own pattern, 0.3 seconds").assertIsDisplayed()
        compose.onNodeWithContentDescription("Bzz").assertExists()
        // No x-haptic: nothing is drawn, as nothing vibrates.
        compose.onAllNodesWithContentDescription("Vibration", substring = true).assertCountEquals(1)
    }

    @Test
    fun autoAndNoneVibrationsAreNamed() {
        val load =
            RepoLoad(
                snapshot =
                    snapshot(
                        packEntry("app.example.dogs", "Dogs", character = "Rex", haptic = """{ "mode": "auto" }"""),
                        packEntry("app.example.owls", "Owls", character = "Hoot", haptic = """{ "mode": "none" }"""),
                    ),
            )
        show(state(load))

        compose.onNodeWithContentDescription("Vibration follows the voice").assertIsDisplayed()
        compose.onNodeWithText("No vibration", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aBundleIsOneCardWithItsPacksAndTheirOwnStates() {
        val load =
            RepoLoad(
                snapshot =
                    snapshot(
                        demoBundleEntry(),
                        packEntry("app.mauri.demo.cats", "Cats", character = "Tabby"),
                        packEntry("app.mauri.demo.bells", "Bells"),
                    ),
            )
        show(state(load, listOf(InstalledRef("app.mauri.demo.cats", "1.0.0", null, null))))

        compose.onNodeWithText("Bundle · 2 packs", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cucuto demo packs", substring = true).assertIsDisplayed()
        // Each pack once, inside the bundle, with its own state.
        compose.onAllNodesWithText("Cats").assertCountEquals(1)
        compose.onNodeWithText("Installed").assertIsDisplayed()
        compose.onNodeWithText("1 of 2 installed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Install Bells").performScrollTo().performClick()
        compose.onNodeWithText("Install all").performScrollTo().performClick()
        assertEquals(listOf("app.mauri.demo.bells", DEMO_BUNDLE_ID), installed)
    }

    @Test
    fun aFailedRepositorySaysWhyAndOffersARetryOrASignIn() {
        show(state(RepoLoad(error = RepoError.Unauthorized(401))))
        compose.onNodeWithText("This repository needs a sign-in, or the saved one no longer works.").assertIsDisplayed()
        compose.onNodeWithText("Sign in").performClick()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf(1L), signIns)
        assertEquals(listOf(1L), retried)
    }

    @Test
    fun offlineTheLastCopyStaysWithANote() {
        show(
            state(RepoLoad(snapshot = snapshot(packEntry("app.example.cats", "Cats")), error = RepoError.Network("x"))),
        )
        compose.onNodeWithText("Showing the last copy").assertIsDisplayed()
        compose
            .onNodeWithText(
                "Can't reach the server. Check your internet connection and try again.",
            ).assertIsDisplayed()
        compose.onNodeWithText("Cats").assertIsDisplayed()
    }

    @Test
    fun emptyStates() {
        show(state(RepoLoad(snapshot = snapshot())))
        compose.onNodeWithText("This repository has no packs yet.").assertIsDisplayed()
    }

    @Test
    fun noMatchesOffersToClearTheSearch() {
        show(state(RepoLoad(snapshot = snapshot(packEntry("app.example.cats", "Cats"))), query = "zebra"))
        compose.onNodeWithText("No packs match your search.").assertIsDisplayed()
    }

    @Test
    fun noRepositoriesTurnedOn() {
        show(DiscoverUiState(sections = emptyList(), repositories = listOf(repo.copy(enabled = false))))
        compose.onNodeWithText("No repositories turned on").assertIsDisplayed()
    }
}
