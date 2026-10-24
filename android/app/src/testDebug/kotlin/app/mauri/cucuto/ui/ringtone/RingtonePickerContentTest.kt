package app.mauri.cucuto.ui.ringtone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.db.RingtoneChoice
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.ui.theme.CucutoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class RingtonePickerContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private val actions =
        RingtonePickerActions(
            onSelect = { events += "select:${it.voice.characterId}" },
            onTogglePreview = { item, file -> events += "play:${item.voice.characterId}:$file" },
            onMode = { events += "mode:$it" },
            onVariant = { events += "variant:$it" },
            onSave = { events += "save" },
            onOpenPacks = { events += "packs" },
            onOpenDiscover = { events += "discover" },
            onFilterPack = { events += "filter:$it" },
            onChooseOther = { events += "other:$it" },
            onSkip = { events += "skip" },
        )

    /** The preview state with playable files, so the play buttons are enabled. */
    private fun state(): RingtonePickerState {
        val base = previewRingtonePickerState()
        return base.copy(
            packs =
                base.packs.map { pack ->
                    pack.copy(
                        characters =
                            pack.characters.map { c ->
                                c.copy(variants = c.variants.map { it.copy(audio = File("/packs/${it.file}")) })
                            },
                    )
                },
        )
    }

    private fun show(
        state: RingtonePickerState,
        step: Boolean = false,
    ) {
        compose.setContent {
            CucutoTheme(dynamicColor = false) { RingtonePickerContent(state, null, actions, step = step) }
        }
    }

    @Test
    fun androidDefaultAndLeaveAsIsSitAboveTheCovers() {
        show(state())
        compose.onNodeWithText("Other choices").assertIsDisplayed()
        compose.onNodeWithText("Android default").performClick()
        compose.onNodeWithText("Leave as is").performClick()
        assertEquals(listOf("other:ANDROID_DEFAULT", "other:UNCHANGED"), events)
    }

    @Test
    fun aChosenAndroidDefaultExplainsItselfAndSaves() {
        show(state().copy(pending = RingtoneChoice.ANDROID_DEFAULT))
        compose.onNodeWithText("Rings with your phone's default ringtone", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Use Android's default").performClick()
        assertEquals(listOf("save"), events)
    }

    @Test
    fun aStoredLeaveAsIsIsAlreadyChosen() {
        show(state().copy(saved = null, savedChoice = RingtoneChoice.UNCHANGED))
        compose.onNodeWithText("Cucuto doesn't touch this contact's ringtone", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Already chosen").assertIsNotEnabled()
    }

    @Test
    fun asStepTwoItIsOptionalWithSkip() {
        show(state(), step = true)
        compose.onNodeWithText("Step 2 of 2 · optional").assertIsDisplayed()
        compose.onNodeWithText("Messages are set", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Skip").performClick()
        assertEquals(listOf("skip"), events)
    }

    @Test
    fun withoutRingtonePacksTheOtherChoicesAreStillThere() {
        show(state().copy(packs = emptyList(), saved = null, savedChoice = null))
        compose.onNodeWithText("No ringtones yet").assertIsDisplayed()
        compose.onNodeWithText("Android default").performScrollTo().performClick()
        assertEquals(listOf("other:ANDROID_DEFAULT"), events)
    }

    @Test
    fun theStoredRingtoneShowsItsVersionsAndRule() {
        show(state())
        compose.onNodeWithText("When Iris calls").assertIsDisplayed()
        compose.onNodeWithText("Same character as the messages").assertIsDisplayed()
        compose.onNodeWithText("Pond song (night)").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play Pond song (night)").performClick()
        compose.onNodeWithText("By season").assertDoesNotExist()
        compose.onNodeWithText("Random per call").performClick()
        // Nothing changed yet: the stored choice is already the one.
        compose.onNodeWithText("Already chosen").assertIsNotEnabled()
        assertEquals(listOf("play:frog:frog/ringtones/night.ogg", "mode:RANDOM"), events)
    }

    @Test
    // Tall enough that the chips clear the bottom bar under the header and the other choices.
    @Config(qualifiers = "w411dp-h1200dp")
    fun packChipsOfferThePacksWithRingtonesAndTheirCount() {
        show(state())
        compose.onNodeWithText("All · 2").assertIsDisplayed()
        compose.onNodeWithText("Kitchen orchestra · 1").performClick()
        assertEquals(listOf("filter:app.mauri.demo.kitchen"), events)
    }

    @Test
    fun noRingtonesLinksToPacksAndDiscover() {
        show(state().copy(packs = emptyList()))
        compose.onNodeWithText("No ringtones yet").assertIsDisplayed()
        compose.onNodeWithText("Open Packs").performClick()
        compose.onNodeWithText("Open Discover").performClick()
        assertEquals(listOf("packs", "discover"), events)
    }

    @Test
    fun aCoverIsPickedWithOneTapAndSaved() {
        val start = state()
        val kettle = start.packs[1].characters.single()
        show(start.copy(edited = RingtoneSelection(kettle.voice, followsVoice = false)))
        compose.onNodeWithText("Chosen for calls. Messages stay with Bleep the frog").assertIsDisplayed()
        compose.onNodeWithText("Ring with Tootle the kettle").performClick()
        assertEquals(listOf("save"), events)
    }

    @Test
    fun theFixedRulePicksAVersionWithItsRadioButton() {
        val start = state()
        val frog = start.packs[0].characters.single()
        show(start.copy(edited = RingtoneSelection(frog.voice, true, RingtoneMode.FIXED, "frog/ringtones/day.ogg")))
        compose.onNodeWithText("Pond song (night)").performClick()
        assertEquals(listOf("variant:frog/ringtones/night.ogg"), events)
    }

    @Test
    fun noRingtonesAtAllPointsToPacks() {
        show(state().copy(packs = emptyList()))
        compose.onNodeWithText("No ringtones yet").assertIsDisplayed()
        compose.onNodeWithText("Open Packs").performClick()
        assertEquals(listOf("packs"), events)
    }
}
