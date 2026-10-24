package app.mauri.cucuto.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavKey
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.packspec.ControlPoint
import app.mauri.cucuto.packspec.HapticEvent
import app.mauri.cucuto.ui.haptics.HapticTarget
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigatorTest {
    private val navigator =
        AppNavigator(
            stacks = TopLevelTab.entries.associateWith { mutableListOf<NavKey>(it.root) },
            selected = mutableStateOf(TopLevelTab.Start),
        )

    private val ada = CucutoDestination.Picker(ChatKey("com.whatsapp", "100000000000001@lid"))

    @Test
    fun opensOnHomeWithOneStackPerTab() {
        assertEquals(TopLevelTab.Home, navigator.currentTab)
        assertEquals(CucutoDestination.Home, navigator.current)
        assertEquals(listOf<NavKey>(CucutoDestination.Home), navigator.visibleKeys)
        TopLevelTab.entries.forEach { assertEquals(listOf<NavKey>(it.root), navigator.stack(it)) }
    }

    @Test
    fun navigatingToATabRootSwitchesTabsInsteadOfPushing() {
        navigator.navigate(CucutoDestination.Packs)

        assertEquals(TopLevelTab.Packs, navigator.currentTab)
        assertEquals(listOf<NavKey>(CucutoDestination.Home), navigator.stack(TopLevelTab.Home))
        // Home stays underneath, so back (and predictive back) from the Packs root reveals Home.
        assertEquals(listOf<NavKey>(CucutoDestination.Home, CucutoDestination.Packs), navigator.visibleKeys)
    }

    @Test
    fun otherDestinationsArePushedOnTheCurrentTabOnlyOnce() {
        navigator.navigate(ada)
        navigator.navigate(ada)

        assertEquals(listOf(CucutoDestination.Home, ada), navigator.stack(TopLevelTab.Home))
        assertEquals(ada, navigator.current)
    }

    @Test
    fun eachTabKeepsItsOwnBackStack() {
        navigator.navigate(ada)
        navigator.navigate(CucutoDestination.Settings)
        navigator.navigate(CucutoDestination.Developer)
        navigator.navigate(CucutoDestination.Home)

        assertEquals(ada, navigator.current)
        navigator.navigate(CucutoDestination.Settings)
        assertEquals(CucutoDestination.Developer, navigator.current)
        assertEquals(
            listOf(CucutoDestination.Home, ada, CucutoDestination.Settings, CucutoDestination.Developer),
            navigator.visibleKeys,
        )
    }

    @Test
    fun backPopsTheTabThenReturnsHomeThenLetsTheSystemCloseTheApp() {
        navigator.navigate(CucutoDestination.Settings)
        navigator.navigate(CucutoDestination.Setup)

        assertTrue(navigator.goBack())
        assertEquals(CucutoDestination.Settings, navigator.current)
        assertTrue(navigator.goBack())
        assertEquals(TopLevelTab.Home, navigator.currentTab)
        assertFalse(navigator.goBack())
        assertEquals(CucutoDestination.Home, navigator.current)
    }

    @Test
    fun reselectingTheCurrentTabPopsItToItsRoot() {
        navigator.navigate(CucutoDestination.Settings)
        navigator.navigate(CucutoDestination.Developer)
        navigator.navigate(CucutoDestination.Gallery)

        navigator.selectTab(TopLevelTab.Settings)

        assertEquals(listOf<NavKey>(CucutoDestination.Settings), navigator.stack(TopLevelTab.Settings))
    }

    @Test
    fun selectingAnotherTabKeepsWhereTheUserWas() {
        navigator.navigate(ada)
        navigator.selectTab(TopLevelTab.Packs)
        navigator.selectTab(TopLevelTab.Home)

        assertEquals(ada, navigator.current)
    }

    @Test
    fun anIncomingPackShowsThePacksRoot() {
        navigator.navigate(CucutoDestination.Packs)
        navigator.navigate(CucutoDestination.Diagnostics)
        navigator.navigate(CucutoDestination.Home)

        navigator.showRoot(TopLevelTab.Packs)

        assertEquals(TopLevelTab.Packs, navigator.currentTab)
        assertEquals(CucutoDestination.Packs, navigator.current)
    }

    @Test
    fun aScreenThatClosesItselfIsRemovedWhereverItIs() {
        navigator.navigate(ada)
        navigator.navigate(CucutoDestination.Diagnostics)
        navigator.navigate(CucutoDestination.Packs)

        navigator.remove(ada)

        assertEquals(listOf<NavKey>(CucutoDestination.Home), navigator.stack(TopLevelTab.Home))
        assertEquals(TopLevelTab.Packs, navigator.currentTab)
    }

    @Test
    fun removingNeverDropsATabRoot() {
        navigator.remove(CucutoDestination.Home)

        assertEquals(listOf<NavKey>(CucutoDestination.Home), navigator.stack(TopLevelTab.Home))
    }

    @Test
    fun tabRootsAreRecognized() {
        TopLevelTab.entries.forEach { assertEquals(it, TopLevelTab.ofRoot(it.root)) }
        assertNull(TopLevelTab.ofRoot(CucutoDestination.Setup))
        assertNull(TopLevelTab.ofRoot(ada))
    }

    @Test
    fun destinationsSurviveSerialization() {
        val all =
            listOf(
                CucutoDestination.Home,
                CucutoDestination.Packs,
                CucutoDestination.Discover,
                CucutoDestination.Settings,
                CucutoDestination.Setup,
                CucutoDestination.Onboarding,
                ada,
                CucutoDestination.Picker(),
                CucutoDestination.ContactDetail(ada.chat!!),
                CucutoDestination.HapticEditor(),
                CucutoDestination.HapticEditor(HapticTarget.Chat(ada.chat!!)),
                CucutoDestination.HapticEditor(
                    HapticTarget.CreatorCharacter(
                        characterKey = "k1",
                        name = "Kettle",
                        pattern =
                            listOf(
                                HapticEvent(at = 0, preset = "CLICK", scale = 0.8),
                                HapticEvent(
                                    at = 100,
                                    envelope = listOf(ControlPoint(1.0, 0.5, 50), ControlPoint(0.0, 0.5, 50)),
                                ),
                            ),
                        clip = "/data/clip.ogg",
                    ),
                ),
                CucutoDestination.Diagnostics,
                CucutoDestination.Developer,
                CucutoDestination.Gallery,
            )
        all.forEach { destination ->
            val json = Json.encodeToString(CucutoDestination.serializer(), destination)
            assertEquals(destination, Json.decodeFromString(CucutoDestination.serializer(), json))
        }
    }
}
