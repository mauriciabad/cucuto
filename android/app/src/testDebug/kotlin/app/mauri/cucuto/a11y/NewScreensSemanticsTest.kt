package app.mauri.cucuto.a11y

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.picker.PickerContent
import app.mauri.cucuto.picker.previewPickerState
import app.mauri.cucuto.sample.SampleChatContent
import app.mauri.cucuto.screenshots.demoSampleChatState
import app.mauri.cucuto.ui.ringtone.PlayingVariant
import app.mauri.cucuto.ui.ringtone.PreviewPlaying
import app.mauri.cucuto.ui.ringtone.RingtonePickerActions
import app.mauri.cucuto.ui.ringtone.RingtonePickerContent
import app.mauri.cucuto.ui.ringtone.RingtonePlayButton
import app.mauri.cucuto.ui.ringtone.previewRingtonePickerState
import app.mauri.cucuto.ui.ringtone.rules.RingtoneRulesActions
import app.mauri.cucuto.ui.ringtone.rules.RingtoneRulesContent
import app.mauri.cucuto.ui.ringtone.rules.previewRingtoneRulesState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What TalkBack users rely on in the screens added on 2026-10-24 (#226): the pickers' folding, search and "Surprise
 * me" (ADR 0072), the ringtone step and its saves (ADR 0073), the rules screen (ADR 0074) and the sample chat (ADR
 * 0075). The base checks (labels, one stop per thing, 48 dp, 200%) run on them through [A11yFlowScreens].
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class NewScreensSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    private val surpriseAll = "Surprise me with a character from any pack"

    @Test
    fun packHeadersAreHeadingsWithTheirCountAndFoldState() {
        compose.showScreen { PickerContent(A11yFlowScreens.rolledPicker(), null, {}, {}, {}, {}) }
        compose
            .onNode(hasContentDescription("Kitchen orchestra, 1 character") and isHeading())
            .assert(hasState("Folded"))
        compose.onNode(hasContentDescription("Chiptune critters, 3 characters") and isHeading()).assertExists()
        compose.onNodeWithContentDescription("Surprise me with a character from Kitchen orchestra").assertExists()
    }

    @Test
    fun aSurprisePickIsAnnouncedByTheTopBarDice() {
        compose.showScreen { PickerContent(previewPickerState(), null, {}, {}, {}, {}) }
        compose
            .onNodeWithContentDescription(surpriseAll)
            .assert(isPoliteLiveRegion())
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun theTopBarDiceSaysWhatCameOut() {
        compose.showScreen { PickerContent(A11yFlowScreens.rolledPicker(), null, {}, {}, {}, {}) }
        compose
            .onNodeWithContentDescription(surpriseAll)
            .assert(isPoliteLiveRegion())
            .assert(hasState("Picked Buzz the bee"))
        // Only one live region says it: the packs' dice stay quiet.
        compose
            .onNodeWithContentDescription("Surprise me with a character from Chiptune critters")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun theRingtonePickerAnnouncesItsSurprisePick() {
        compose.showScreen {
            RingtonePickerContent(A11yFlowScreens.rolledRingtonePicker(), null, RingtonePickerActions())
        }
        compose
            .onNodeWithContentDescription(surpriseAll)
            .assert(isPoliteLiveRegion())
            .assert(hasState("Picked Tootle the kettle"))
    }

    @Test
    fun aSavedRingtoneIsAnnounced() {
        val state = previewRingtonePickerState()
        compose.showScreen {
            RingtonePickerContent(state.copy(assigned = state.selection), null, RingtonePickerActions())
        }
        compose
            .onNode(hasText("Already chosen") and isPoliteLiveRegion())
            .assert(hasState("Saved"))
    }

    @Test
    fun aRingtonePreviewSaysPlayingOrPausedInWords() {
        val key = PlayingVariant(previewRingtonePickerState().chat!!.voice!!, "day.ogg")
        compose.showScreen {
            androidx.compose.foundation.layout.Column {
                RingtonePlayButton(PreviewPlaying(key), name = "Live", onToggle = {}, onStop = {})
                RingtonePlayButton(PreviewPlaying(key, paused = true), name = "Radio", onToggle = {}, onStop = {})
                RingtonePlayButton(null, name = "Winter", onToggle = {}, onStop = {})
            }
        }
        compose.onNodeWithContentDescription("Pause Live").assert(hasState("Playing"))
        compose.onNodeWithContentDescription("Play Radio").assert(hasState("Paused"))
        compose
            .onNodeWithContentDescription("Play Winter")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun theRulesMenusSayWhichOptionIsChosen() {
        compose.showScreen { RingtoneRulesContent(previewRingtoneRulesState(), RingtoneRulesActions()) }
        // The menu button can sit behind the save bar on the test screen: click through semantics.
        compose
            .onNodeWithContentDescription("When Live rings", substring = true)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(hasText("At night") and isSelected(true)).assertExists()
        compose.onNode(hasText("By day") and isSelected(false)).assertExists()
    }

    @Test
    fun theSampleChatAnnouncesItsVoiceAndSaysWhenItPlays() {
        compose.showScreen {
            SampleChatContent(
                state = demoSampleChatState().copy(speaking = true),
                ringing = null,
                onBack = {},
                onChooseVoice = {},
                onChooseRingtone = {},
                onPlayMessage = {},
                onToggleCall = {},
                onGetDemoPacks = {},
            )
        }
        compose.onNode(hasText("Demo", substring = true) and hasText("WhatsApp", substring = true)).assertExists()
        compose.onNode(hasText("Voice: Bleep the frog") and isPoliteLiveRegion()).assertExists()
        compose.onNodeWithText("Play a sample message").assert(hasState("Playing"))
        compose.onAllNodesWithText("Chiptune critters").filterToOne(isHeading()).assertExists()
        compose
            .onNodeWithContentDescription("Bleep the frog")
            .assert(hasState("Selected, Playing"))
    }

    private fun hasState(state: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)

    private fun isSelected(selected: Boolean) = SemanticsMatcher.expectValue(SemanticsProperties.Selected, selected)

    private fun isPoliteLiveRegion() =
        SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
}
