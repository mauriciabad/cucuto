package app.mauri.cucuto.contact

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.db.DecisionOutcome
import app.mauri.cucuto.ui.theme.CucutoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Compose UI tests on Robolectric, with the made-up preview data. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class ContactDetailContentTest {
    @get:Rule
    val compose = createComposeRule()

    private var simulations = 0
    private val toggles = mutableListOf<Pair<String, Boolean>>()
    private val played = mutableListOf<String>()
    private var changes = 0
    private var removals = 0

    private fun show(
        state: ContactDetailState,
        phoneCalls: (@Composable () -> Unit)? = null,
    ) {
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                ContactDetailContent(
                    state = state,
                    onBack = {},
                    onChangeCharacter = { changes++ },
                    onUnassign = { removals++ },
                    onSimulate = { simulations++ },
                    onPlayClip = { played += it.name },
                    onToggleClip = { clip, on -> toggles += clip.name to on },
                    onGroupPolicyChange = {},
                    onConsumeFailure = {},
                    phoneCalls = phoneCalls,
                )
            }
        }
    }

    @Test
    fun showsTheCharacterAndTheSimulatePathAndSimulates() {
        show(previewContactDetailState())

        compose.onNodeWithText("Speaks as Bleep the frog").assertIsDisplayed()
        compose.onNodeWithText("From Chiptune critters").assertIsDisplayed()
        compose
            .onNodeWithText("In-app preview: the module runs older code until the next reboot.", substring = true)
            .assertIsDisplayed()
        compose
            .onNodeWithContentDescription(
                "Play what this chat's next message will do",
                substring = true,
            ).performClick()
        // The "Messages" row opens the character picker in one tap (ADR 0063).
        compose.onNodeWithText("Bleep the frog · Chiptune critters").performClick()

        assertEquals(1, simulations)
        assertEquals(1, changes)
    }

    @Test
    fun messagesAndPhoneCallsAreTwoRowsOfTheSoundsCard() {
        show(previewContactDetailState(), phoneCalls = { Text("Calls row") })

        compose.onNodeWithText("Sounds").assertIsDisplayed()
        compose.onNodeWithText("Messages").assertIsDisplayed()
        compose.onNodeWithText("Calls row").assertIsDisplayed()
    }

    @Test
    fun aGroupHasNoPhoneCallsRow() {
        val state = previewContactDetailState()
        val key = ChatKey("com.whatsapp", "120300000000001@g.us")
        show(
            state.copy(chatKey = key, chat = state.chat?.copy(key = key, isGroup = true)),
            phoneCalls = { Text("Calls row") },
        )

        compose.onNodeWithText("Messages").assertIsDisplayed()
        compose.onNodeWithText("Calls row").assertDoesNotExist()
    }

    @Test
    fun theModulePathAndItsReportAreShown() {
        val state =
            previewContactDetailState().copy(
                path = SimulatePath.Module,
                simulation = SimulationStatus.Sent(0L),
                report = DecisionRow(9, 0L, DecisionOutcome.SWAPPED, "croak", simulated = true, perSender = false),
            )
        show(state)

        compose.onNodeWithText("Through the module", substring = true).assertIsDisplayed()
        compose.onNodeWithText("The module played croak.").assertIsDisplayed()
    }

    @Test
    fun clipsPlayAndToggle() {
        show(previewContactDetailState())

        compose.onNodeWithContentDescription("Play Ribbit").performClick()
        compose.onNode(isToggleable() and hasText("Croak")).performScrollTo().performClick()

        assertEquals(listOf("Ribbit"), played)
        assertEquals(listOf("Croak" to false), toggles)
    }

    @Test
    fun theLastClipCannotBeSwitchedOff() {
        val state = previewContactDetailState()
        show(state.copy(clips = clipRows(state.character, listOf("characters/frog/clips/croak.ogg"))))

        compose.onNode(isToggleable() and hasText("Ribbit")).assertIsNotEnabled()
    }

    @Test
    fun recentAlertsTellSimulationsFromMessages() {
        show(previewContactDetailState())
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Played Croak"))

        compose.onNodeWithText("Played Ribbit").assertIsDisplayed()
        compose.onNodeWithText("Played Croak").assertIsDisplayed()
        compose.onNodeWithText("Simulated", substring = true).assertIsDisplayed()
    }

    @Test
    fun removingAsksFirst() {
        show(previewContactDetailState())

        compose.onNodeWithContentDescription("Remove voice").performClick()
        compose.onNodeWithText("Remove this voice?").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()

        assertEquals(1, removals)
    }

    @Test
    fun withoutAVoiceItOffersTheCharacterPickerAndCannotSimulate() {
        val state = previewContactDetailState()
        show(
            state.copy(
                chat = state.chat?.copy(voice = null),
                character = null,
                clips = emptyList(),
                decisions = emptyList(),
            ),
        )

        compose.onNodeWithText("No voice yet: WhatsApp's tone plays.").assertIsDisplayed()
        compose.onNodeWithText("No voice yet. Tap to choose one.").performClick()
        assertEquals(1, changes)
        compose.onNodeWithText("Choose a character to simulate a message.").assertIsDisplayed()
        compose
            .onNodeWithContentDescription(
                "Play what this chat's next message will do",
                substring = true,
            ).assertIsNotEnabled()
    }
}
