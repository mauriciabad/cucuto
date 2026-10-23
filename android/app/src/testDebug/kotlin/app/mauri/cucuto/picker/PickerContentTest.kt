package app.mauri.cucuto.picker

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.ui.theme.CucutoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Compose UI tests on Robolectric, with the made-up preview data. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class PickerContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val taps = mutableListOf<String>()

    private fun show(state: PickerState) {
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                PickerContent(
                    state = state,
                    assignBeat = null,
                    onTap = { character: VoiceCharacter -> taps += character.id },
                    onUnassign = {},
                    onBack = {},
                    onOpenPacks = {},
                )
            }
        }
    }

    @Test
    fun charactersAreGroupedByPackAndTapsReachTheViewModel() {
        show(previewPickerState())

        compose.onNodeWithText("For Ada").assertIsDisplayed()
        compose.onNodeWithText("Chiptune critters").assertIsDisplayed()
        compose.onNodeWithContentDescription("Bleep the frog").performClick()

        assertEquals(listOf("frog"), taps)
    }

    @Test
    fun theSelectedCharacterSaysASecondTapAssigns() {
        show(previewPickerState())

        compose
            .onNodeWithContentDescription("Buzz the bee")
            .assert(hasStateDescription("Selected"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assert(hasClickLabel("Assign"))
        compose.onNodeWithContentDescription("Purr the cat").assert(hasClickLabel("Preview"))
    }

    @Test
    fun withoutPacksThePickerPointsToPacks() {
        show(previewPickerState().copy(packs = emptyList()))
        compose.onNodeWithText("No characters yet").assertIsDisplayed()
        compose.onNodeWithText("Open Packs").assertIsDisplayed()
    }

    @Test
    fun previewModeHasNoChat() {
        show(previewPickerState().copy(chatKey = null, chat = null, selected = null))
        compose.onNodeWithText("Preview characters").assertIsDisplayed()
        compose.onNode(hasContentDescription("Remove voice")).assertDoesNotExist()
    }

    private fun hasClickLabel(label: String) =
        SemanticsMatcher("click label is $label") { node ->
            node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.OnClick) { null }?.label ==
                label
        }
}
