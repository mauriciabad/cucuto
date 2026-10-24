package app.mauri.cucuto.ui.ringtone.rules

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.ui.theme.CucutoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class RingtoneRulesContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private val actions =
        RingtoneRulesActions(
            onTogglePreview = { events += "play:$it" },
            onMode = { events += "mode:$it" },
            onToggleRow = { index, on -> events += "row:$index:$on" },
            onWhen = { index, choice -> events += "when:$index:$choice" },
            onSave = { events += "save" },
            onReset = { events += "reset" },
        )

    private fun show(state: RingtoneRulesState) {
        compose.setContent { CucutoTheme(dynamicColor = false) { RingtoneRulesContent(state, actions) } }
    }

    @Test
    fun thePacksRulesReadInPlainWordsAndEachVersionPlays() {
        show(previewRingtoneRulesState().copy(chatKey = null, chat = null, saved = null))
        compose.onNodeWithText("Live by day, Radio at night, and Winter at night, in winter").assertIsDisplayed()
        compose.onNodeWithText("Rings at night, in winter").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play Radio").performClick()
        compose
            .onNodeWithText("To change them for one contact, open the ringtone picker from the contact's chat.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Save").assertDoesNotExist()
        assertEquals(listOf("play:bubblegum/radio.ogg"), events)
    }

    @Test
    fun ownRulesShowEachVersionWithItsSwitchAndWhenMenu() {
        show(previewRingtoneRulesState())
        compose.onNodeWithText("For Iris").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your own rules").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your rules: Live at night and Radio by day").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Ring with Winter").performScrollTo().performClick()
        compose.onNodeWithContentDescription("When Live rings: At night").performScrollTo().performClick()
        compose.onNodeWithText("In summer").performClick()
        // Nothing changed yet: saving is off, and the contact's rules can go back to the pack's.
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Reset to the pack's rules").performClick()
        assertEquals(listOf("row:2:true", "when:0:SUMMER", "reset"), events)
    }

    @Test
    fun aCharacterThatDoesNotRingYetCanBeSavedWithItsRule() {
        val base = previewRingtoneRulesState()
        show(base.copy(saved = base.saved!!.copy(mode = RingtoneMode.PACK, rules = emptyList()), ringsNow = false))
        compose
            .onNodeWithText(
                "Bubblegum K.K. doesn't ring for Iris yet. Choose its rules and save: from then on, Bubblegum K.K. " +
                    "rings when Iris calls you.",
            ).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Random per call").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reset to the pack's rules").assertDoesNotExist()
        compose.onNodeWithText("Ring with Bubblegum K.K.").performClick()
        assertEquals(listOf("save"), events)
    }

    @Test
    fun choosingARuleTellsTheViewModel() {
        val base = previewRingtoneRulesState()
        show(base.copy(saved = base.saved!!.copy(mode = RingtoneMode.PACK, rules = emptyList())))
        compose.onNodeWithText("Reset to the pack's rules").assertDoesNotExist()
        compose.onNodeWithText("Random per call").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("mode:RANDOM"), events)
    }
}
