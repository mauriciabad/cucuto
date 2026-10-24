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

    private fun show(state: HomeState) {
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                HomeContent(
                    state = state,
                    onQueryChange = { queries += it },
                    onTogglePack = {},
                    onDismissRecent = {},
                    onOpenChat = { opened += it },
                    onOpenPacks = {},
                    onBack = {},
                    onOpenDetail = { details += it },
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
        compose.onNodeWithText("Group · No voice yet: WhatsApp's tone").assertIsDisplayed()
    }

    @Test
    fun typingSearchesAndNoChatsShowsTheEmptyState() {
        show(HomeState(loading = false))
        compose.onNodeWithText("No chats yet").assertIsDisplayed()
        compose.onNodeWithText("No sound packs yet").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("ada")
        compose.waitForIdle()
        assertEquals("ada", queries.last())
    }

    private fun ComposeContentTestRule.onAllNodesWithTextFirst(text: String) = onAllNodes(hasText(text)).onFirst()
}
