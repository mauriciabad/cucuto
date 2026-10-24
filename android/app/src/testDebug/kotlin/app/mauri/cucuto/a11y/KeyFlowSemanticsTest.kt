package app.mauri.cucuto.a11y

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.contact.ContactDetailContent
import app.mauri.cucuto.contact.previewContactDetailState
import app.mauri.cucuto.core.audio.PcmAudio
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.home.HomeContent
import app.mauri.cucuto.home.previewHomeState
import app.mauri.cucuto.picker.PickerContent
import app.mauri.cucuto.picker.previewPickerState
import app.mauri.cucuto.ui.components.ChoiceButtonGroup
import app.mauri.cucuto.ui.creator.ClipEditorPage
import app.mauri.cucuto.ui.creator.ClipEditorUi
import app.mauri.cucuto.ui.haptics.HapticDraft
import app.mauri.cucuto.ui.haptics.HapticEditorActions
import app.mauri.cucuto.ui.haptics.HapticEditorContent
import app.mauri.cucuto.ui.haptics.HapticEditorState
import app.mauri.cucuto.ui.haptics.HapticTarget
import app.mauri.cucuto.ui.haptics.HapticTimeline
import app.mauri.cucuto.ui.haptics.RhythmRecording
import app.mauri.cucuto.ui.haptics.nextEventMs
import app.mauri.cucuto.ui.motion.ProvideReducedMotionScheme
import app.mauri.cucuto.ui.motion.ReducedMotionScheme
import app.mauri.cucuto.ui.theme.LocalReducedMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What TalkBack users rely on in the key flows (#29): one stop per item, roles, states, headings and actions. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class KeyFlowSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun homeSectionsAreHeadingsAndARecentSenderIsOneStopWithItsActions() {
        compose.showScreen {
            HomeContent(previewHomeState(), {}, {}, {}, {}, {})
        }
        compose.onNode(hasText("Recent senders") and isHeading()).assertIsDisplayed()
        compose
            .onAllNodesWithText("Ada")[0]
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .assert(SemanticsMatcher("open label") { it.config[SemanticsActions.OnClick].label == "Choose a voice" })
    }

    @Test
    fun pickerCellsAreButtonsWithTheirNameStateAndWhatATapDoes() {
        compose.showScreen { PickerContent(previewPickerState(), null, {}, {}, {}, {}) }
        compose.onNode(hasText("Chiptune critters") and isHeading()).assertExists()
        compose
            .onNodeWithContentDescription("Buzz the bee")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        // The name under the avatar is not read a second time.
        compose.onAllNodesWithText("Buzz the bee").assertCountEquals(0)
    }

    @Test
    fun aClipRowIsOneSwitchWithItsNameAndPlayingIsSaidInWords() {
        val state = previewContactDetailState()
        val toggled = mutableListOf<Boolean>()
        compose.showScreen {
            ContactDetailContent(
                state = state.copy(playingClip = state.clips.first().path),
                onBack = {},
                onChangeCharacter = {},
                onUnassign = {},
                onSimulate = {},
                onPlayClip = {},
                onToggleClip = { _, on -> toggled += on },
                onGroupPolicyChange = {},
                onConsumeFailure = {},
            )
        }
        val first = state.clips.first().name
        compose
            .onNode(hasText(first) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .performClick()
        assertEquals(listOf(false), toggled)
        compose
            .onNodeWithContentDescription("Play $first")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing"))
        compose.onNodeWithText("Playing").assertExists()
    }

    @Test
    fun theHapticTimelineAddsEventsWithAnActionAndThePadTakesDoubleTaps() {
        val taps = mutableListOf<Pair<Int, Int>>()
        val presses = mutableListOf<Long>()
        var recording by mutableStateOf<RhythmRecording?>(null)
        val draft = HapticDraft(listOf(HapticEvent.Preset(0, HapticPreset.CLICK, 800)))
        compose.showScreen {
            HapticEditorContent(
                state =
                    HapticEditorState(
                        loading = false,
                        target = HapticTarget.Chat(ChatKey("com.whatsapp", "100000000000001@lid")),
                        draft = draft,
                        recording = recording,
                    ),
                actions =
                    HapticEditorActions(
                        onTapTimeline = { at, tolerance -> taps += at to tolerance },
                        onPress = {
                            presses += it
                            true
                        },
                    ),
            )
        }
        val timeline = compose.onNode(hasContentDescription("1 event", substring = true)).fetchSemanticsNode()
        compose.runOnIdle {
            timeline.config[SemanticsActions.CustomActions]
                .single { it.label == "Add Click after the last event" }
                .action()
        }
        assertEquals(listOf(nextEventMs(HapticTimeline.durationOf(draft.events), empty = false) to 0), taps)

        recording = RhythmRecording()
        compose.onNodeWithContentDescription("Rhythm pad", substring = true, ignoreCase = true).performClick()
        assertEquals(1, presses.size)
    }

    @Test
    fun aChoiceGroupWrapsInsteadOfClippingWithLargeFonts() {
        val options = listOf("Envolvente muy larga", "Tic grave", "Clic")
        compose.showScreen(fontScale = 2f) {
            var selected by remember { mutableStateOf(options.first()) }
            ChoiceButtonGroup(options, selected, { selected = it }, label = { it })
        }
        options.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        assertEquals(emptyList<String>(), compose.clippedTexts())
        compose
            .onNodeWithText("Clic")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performClick()
            .assertIsOn()
    }

    @Test
    fun theClipEditorsOptionsAreWholeRowSwitches() {
        val trimmed = mutableListOf<Boolean>()
        compose.showScreen {
            ClipEditorPage(
                editor =
                    ClipEditorUi(
                        characterKey = "frog",
                        source = PcmAudio(ShortArray(8_000), 8_000),
                        startMs = 0,
                        endMs = 500,
                    ),
                onBack = {},
                onRange = { _, _ -> },
                onZoomIn = {},
                onZoomOut = {},
                onPlay = {},
                onTrimSilence = { trimmed += it },
                onNormalize = {},
                onLabel = {},
                onSave = {},
            )
        }
        compose
            .onNode(hasText("Trim silence") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .performScrollTo()
            .assertIsOn()
            .performClick()
        assertEquals(listOf(false), trimmed)
    }

    @Test
    fun removeAnimationsSnapsTheMotionScheme() {
        var reduced: Any? = null
        var normal: Any? = null
        compose.showScreen {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                ProvideReducedMotionScheme { reduced = MaterialTheme.motionScheme }
            }
            ProvideReducedMotionScheme { normal = MaterialTheme.motionScheme }
        }
        assertSame(ReducedMotionScheme, reduced)
        assertNotEquals(ReducedMotionScheme, normal)
    }
}
