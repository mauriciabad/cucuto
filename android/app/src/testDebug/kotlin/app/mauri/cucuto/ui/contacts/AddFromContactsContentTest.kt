package app.mauri.cucuto.ui.contacts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.home.HomeContent
import app.mauri.cucuto.home.JourneyStep
import app.mauri.cucuto.home.buildHomeState
import app.mauri.cucuto.home.previewHomeStateWithQuestion
import app.mauri.cucuto.ui.theme.CucutoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "Add a chat from contacts" and its entry points on Home (ADR 0065), on Robolectric with made-up data. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class AddFromContactsContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theExplainerSaysWhatIsReadBeforeAnythingIsAsked() {
        var continued = 0
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                AddFromContactsContent(AddFromContactsState(), {}, { continued++ }, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("What Cucuto reads").assertIsDisplayed()
        compose.onNodeWithText("What it never reads").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, continued)
    }

    @Test
    fun rowsTogglePeopleAndThoseAlreadyInCucutoAreLocked() {
        val toggled = mutableListOf<EntryId>()
        var saves = 0
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                AddFromContactsContent(previewContactsState(), {}, {}, {}, {}, { toggled += it }, { saves++ })
            }
        }
        compose.onNodeWithText("Carla").performClick()
        assertEquals(listOf(EntryId(ChatKey.WHATSAPP, "lk-3")), toggled)
        compose.onNodeWithText("Already in your chats").assertIsDisplayed()
        compose.onNodeWithText("Add 2 chats").performClick()
        assertEquals(1, saves)
    }

    @Test
    fun homeOffersItWithNoChatsAndAsksWhoIsThis() {
        var adds = 0
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                HomeContent(
                    state = buildHomeState(emptyList(), emptyList(), "", null, 0).copy(packs = previewPacks()),
                    onQueryChange = {},
                    onTogglePack = {},
                    onDismissRecent = {},
                    onOpenChat = {},
                    onOpenPacks = {},
                    journey = JourneyStep.WaitForMessages,
                    onAddFromContacts = { adds++ },
                )
            }
        }
        compose.onNodeWithText("Add a chat from contacts").performClick()
        assertEquals(1, adds)
    }

    @Test
    fun homeShowsWaitingChatsAndTheQuestion() {
        val opened = mutableListOf<ChatKey>()
        val state = previewHomeStateWithQuestion()
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                HomeContent(
                    state = state,
                    onQueryChange = {},
                    onTogglePack = {},
                    onDismissRecent = {},
                    onOpenChat = {},
                    onOpenPacks = {},
                    onOpenQuestion = { opened += it },
                )
            }
        }
        compose.onNodeWithText("Waiting for a first message").assertIsDisplayed()
        compose.onNodeWithText("Someone you added has this name. Tap to say who it is.").performClick()
        assertEquals(listOf(state.questions.single().key), opened)
    }

    @Test
    fun confirmingPicksAPersonOrNone() {
        val picked = mutableListOf<Long>()
        var none = 0
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                ConfirmLinkContent(
                    previewHomeStateWithQuestion().questions.single(),
                    onBack = {},
                    onPick = { picked += it },
                    onNone = { none++ },
                )
            }
        }
        compose.onNodeWithText(formatNumber("+34600000009"), substring = true).performClick()
        compose.onNodeWithText("None of them").performClick()
        assertEquals(listOf(9L), picked)
        assertEquals(1, none)
    }

    @Test
    fun theButtonWaitsForSomeoneToBePicked() {
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                AddFromContactsContent(previewContactsState().copy(selected = 0), {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Pick someone to add").assertIsNotEnabled()
    }

    private fun previewPacks() =
        listOf(
            app.mauri.cucuto.home
                .PackFilterOption("app.mauri.demo.critters", "Chiptune critters"),
        )
}
