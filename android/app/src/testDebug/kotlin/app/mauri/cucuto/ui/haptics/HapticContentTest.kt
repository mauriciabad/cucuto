package app.mauri.cucuto.ui.haptics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.ui.theme.CucutoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Compose UI tests on Robolectric for the haptic editor and the per-contact vibration card. Made-up data only. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class HapticContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val chosen = mutableListOf<HapticChoice>()
    private val calls = mutableListOf<String>()

    private fun showOverride(state: HapticOverrideState) {
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                HapticOverrideContent(
                    state = state,
                    onChoose = { chosen += it },
                    onPreview = { calls += "preview" },
                    onEditPattern = { calls += "edit" },
                )
            }
        }
    }

    @Test
    fun theOverrideCardOffersTheFourChoices() {
        showOverride(HapticOverrideState(loading = false, hasVoice = true, choice = HapticChoice.Pack))

        compose.onNodeWithText("Pack").assertIsOn()
        compose.onNodeWithText("Off").performClick()
        compose.onNodeWithText("Custom").performClick()
        compose.onNodeWithText("Make a pattern").performClick()

        assertEquals(listOf(HapticChoice.Off, HapticChoice.Custom), chosen)
        assertEquals(listOf("edit"), calls)
    }

    @Test
    fun withoutAVoiceTheCardExplainsWhy() {
        showOverride(HapticOverrideState(loading = false, hasVoice = false))
        compose.onNodeWithText("Give this chat a voice to choose how it vibrates.").assertIsDisplayed()
    }

    @Test
    fun theEditorShowsTheSelectedEventAndItsControls() {
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                HapticEditorContent(
                    state =
                        HapticEditorState(
                            loading = false,
                            chat = ChatKey("com.whatsapp", "100000000000001@lid"),
                            draft =
                                HapticDraft(
                                    listOf(
                                        HapticEvent.Preset(0, HapticPreset.CLICK, 800),
                                        HapticEvent.Envelope(
                                            220,
                                            listOf(ControlPoint(1000, 800, 120), ControlPoint(0, 400, 180)),
                                        ),
                                    ),
                                    selected = 0,
                                ),
                        ),
                    actions =
                        HapticEditorActions(
                            onSave = { calls += "save" },
                            onStartRecording = { calls += "record" },
                            onDelete = { calls += "delete $it" },
                        ),
                )
            }
        }

        compose.onNodeWithContentDescription("Timeline: 2 events, 0.5 seconds").assertIsDisplayed()
        compose.onNodeWithText("Click at 0 ms").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Start recording").performScrollTo().performClick()
        assertEquals(listOf("save", "delete 0", "record"), calls)
    }

    @Test
    fun anEmptyTimelineCannotBeSaved() {
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                HapticEditorContent(
                    state = HapticEditorState(loading = false, chat = ChatKey("com.whatsapp", "100000000000001@lid")),
                    actions = HapticEditorActions(),
                )
            }
        }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Empty timeline").assertIsDisplayed()
    }
}
