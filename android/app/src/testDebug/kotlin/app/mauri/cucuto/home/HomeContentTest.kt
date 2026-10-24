package app.mauri.cucuto.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.ui.theme.CucutoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Compose UI tests on Robolectric, with the made-up preview data. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class HomeContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<ChatKey>()
    private val details = mutableListOf<ChatKey>()
    private val queries = mutableListOf<String>()

    private var demoRequests = 0
    private var demoImports = 0
    private var restores = 0

    private fun show(
        state: HomeState,
        journey: JourneyStep = JourneyStep.None,
    ) {
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                HomeContent(
                    state = state,
                    onQueryChange = { queries += it },
                    onTogglePack = {},
                    onDismissRecent = {},
                    onOpenChat = { opened += it },
                    onOpenPacks = {},
                    onOpenDetail = { details += it },
                    journey = journey,
                    onGetDemoPacks = { demoRequests++ },
                    onImportDemoPacks = { demoImports++ },
                    onRestoreBackup = { restores++ },
                )
            }
        }
    }

    @Test
    fun oneTapOnARecentSenderOpensThePickerForThatChat() {
        val state = previewHomeState()
        show(state)

        compose.onNodeWithText("Recent senders").assertIsDisplayed()
        // Ada is in the strip and in the list; the first match is the strip.
        compose.onAllNodesWithTextFirst("Ada").performClick()

        assertEquals(listOf(state.recent.first().key), opened)
    }

    @Test
    fun aChatWithAVoiceOpensItsDetailAndOneWithoutOpensThePicker() {
        val state = previewHomeState()
        show(state)

        compose.onNodeWithText("Bea").performClick()
        assertEquals(listOf(ChatKey("com.whatsapp", "100000000000002@lid")), details)

        // The list row (the strip shows the same chat first).
        compose.onAllNodes(hasText("Book club")).onLast().performClick()
        assertEquals(listOf(ChatKey("com.whatsapp", "120300000000001@g.us")), opened)
    }

    @Test
    fun assignedChatsShowTheirCharacterAndPack() {
        show(previewHomeState())
        compose.onNodeWithText("Bleep the frog · Chiptune critters").assertIsDisplayed()
        compose.onNodeWithText("Signal · No voice yet: default tone").assertIsDisplayed()
        compose.onNodeWithText("Group · No voice yet: default tone").assertIsDisplayed()
    }

    @Test
    fun typingSearchesAndNoChatsShowsTheEmptyState() {
        show(HomeState(loading = false))
        compose.onNodeWithText("No chats yet").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("ada")
        compose.waitForIdle()
        assertEquals("ada", queries.last())
    }

    @Test
    fun firstRunOffersTheDemoPacksInsteadOfAnEmptyList() {
        show(HomeState(loading = false), JourneyStep.ImportPacks)
        compose.onNodeWithText("Give your chats a voice").assertIsDisplayed()
        compose.onNodeWithText("No chats yet").assertDoesNotExist()
        compose.onNodeWithText("Get the demo packs").performClick()
        assertEquals(1, demoRequests)
        // Offline fallback: a demo packs file already in Downloads.
        compose.onNodeWithText("Choose a file from Downloads").performClick()
        assertEquals(1, demoImports)
        // A new phone: the restore flow starts right here, not only in Settings.
        compose.onNodeWithText("Restore from backup").performClick()
        assertEquals(1, restores)
    }

    @Test
    fun afterTheImportOneTapGivesTheNewestSenderAVoice() {
        val state = previewHomeState()
        show(state, JourneyStep.GiveFirstVoice(state.recent.first()))
        compose.onNodeWithText("Give Ada a voice").assertIsDisplayed()
        compose.onNodeWithText("Choose a voice").performClick()
        assertEquals(listOf(state.recent.first().key), opened)
    }

    @Test
    fun theFirstVoiceIsCelebratedWithItsCharacter() {
        val state = previewHomeState()
        show(state, JourneyStep.FirstVoice(state.voiced.single()))
        compose.onNodeWithText("Bea has a voice!").assertIsDisplayed()
        compose.onNodeWithText("Next time Bea writes, you hear Bleep the frog.").assertIsDisplayed()
    }

    private fun ComposeContentTestRule.onAllNodesWithTextFirst(text: String) = onAllNodes(hasText(text)).onFirst()
}
