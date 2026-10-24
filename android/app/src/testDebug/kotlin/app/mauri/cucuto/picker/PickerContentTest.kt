package app.mauri.cucuto.picker

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.isHeading
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
                    onOpenPacks = { taps += "packs" },
                    onOpenDiscover = { taps += "discover" },
                    onFilterPack = { taps += "filter:$it" },
                    onSearch = { taps += "search:$it" },
                    onToggleFold = { taps += "fold:$it" },
                    onSurprise = { taps += "surprise:$it" },
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
    fun packChipsShowEveryPackWithMessageCharactersAndTheirCount() {
        show(previewPickerState())

        compose.onNodeWithText("All · 4").assertIsDisplayed()
        compose.onNodeWithText("Chiptune critters · 3").assertIsDisplayed()
        compose.onNodeWithText("Kitchen orchestra · 1").performClick()

        assertEquals(listOf("filter:app.mauri.demo.kitchen"), taps)
    }

    @Test
    fun theEmptyPickerLinksToPacksAndDiscover() {
        show(previewPickerState().copy(packs = emptyList()))
        compose.onNodeWithText("Open Packs").performClick()
        compose.onNodeWithText("Open Discover").performClick()
        assertEquals(listOf("packs", "discover"), taps)
    }

    @Test
    fun withoutPacksThePickerPointsToPacks() {
        show(previewPickerState().copy(packs = emptyList()))
        compose.onNodeWithText("No message voices yet").assertIsDisplayed()
        compose.onNodeWithText("Open Packs").assertIsDisplayed()
        compose.onNodeWithText("Open Discover").assertIsDisplayed()
    }

    @Test
    fun previewModeHasNoChat() {
        show(previewPickerState().copy(chatKey = null, chat = null, selected = null))
        compose.onNodeWithText("Hear the characters").assertIsDisplayed()
        compose.onNode(hasContentDescription("Remove voice")).assertDoesNotExist()
    }

    @Test
    fun aFoldedPackHidesItsCharactersAndItsHeaderUnfoldsIt() {
        val state = previewPickerState()
        show(state.copy(folds = state.folds.with("app.mauri.demo.kitchen", expanded = false)))

        compose.onNodeWithText("Tootle the kettle").assertDoesNotExist()
        compose
            .onNode(hasContentDescription("Kitchen orchestra, 1 character") and isHeading())
            .assert(hasStateDescription("Folded"))
            .assert(hasClickLabel("Unfold"))
            .performClick()
        compose
            .onNode(hasContentDescription("Chiptune critters, 3 characters") and isHeading())
            .assert(hasStateDescription("Unfolded"))
            .assert(hasClickLabel("Fold"))

        assertEquals(listOf("fold:app.mauri.demo.kitchen"), taps)
    }

    @Test
    fun searchShowsOnlyTheMatchesAndSaysWhenNothingMatches() {
        show(previewPickerState().copy(query = "purr"))
        compose.onNodeWithContentDescription("Purr the cat").assertIsDisplayed()
        compose.onNodeWithContentDescription("Bleep the frog").assertDoesNotExist()
        compose.onNodeWithText("Kitchen orchestra").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear search").performClick()
        assertEquals(listOf("search:"), taps)
    }

    @Test
    fun searchWithNoMatchSaysSo() {
        show(previewPickerState().copy(query = "zebra"))
        compose.onNodeWithText("No character matches “zebra”").assertIsDisplayed()
    }

    @Test
    fun theDiceSurpriseFromEveryPackOrFromOnePack() {
        show(previewPickerState())
        compose.onNodeWithContentDescription("Surprise me with a character from any pack").performClick()
        compose.onNodeWithContentDescription("Surprise me with a character from Kitchen orchestra").performClick()
        assertEquals(listOf("surprise:null", "surprise:app.mauri.demo.kitchen"), taps)
    }

    @Test
    fun aRolledCharacterCanBeRerolledOrUsed() {
        val state = previewPickerState()
        val bee = state.selected!!
        show(state.copy(roll = SurpriseRoll("app.mauri.demo.critters", bee, count = 1)))

        compose.onNodeWithText("Re-roll").performClick()
        compose.onNodeWithText("Use Buzz the bee").performClick()

        assertEquals(listOf("surprise:app.mauri.demo.critters", "bee"), taps)
    }

    private fun hasClickLabel(label: String) =
        SemanticsMatcher("click label is $label") { node ->
            node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.OnClick) { null }?.label ==
                label
        }
}
