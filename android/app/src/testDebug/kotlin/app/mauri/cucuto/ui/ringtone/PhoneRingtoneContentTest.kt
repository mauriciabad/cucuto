package app.mauri.cucuto.ui.ringtone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.ringtone.contacts.AndroidContact
import app.mauri.cucuto.core.ringtone.sync.RingtoneProblem
import app.mauri.cucuto.ui.theme.CucutoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class PhoneRingtoneContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private val actions =
        PhoneRingtoneActions(
            onTurnOn = { events += "on" },
            onLink = { events += "link:${it.lookupKey}" },
            onPickContact = { events += "pick" },
            onUse = { events += "use" },
            onChoose = { events += "choose" },
            onStop = { events += "stop" },
            onTakeOver = { events += "takeover" },
        )

    private val base =
        PhoneRingtoneUiState(
            loading = false,
            enabled = true,
            contactId = 1,
            characterName = "K.K. Slider",
            hasCharacter = true,
            variants = listOf(VariantChip("live.ogg", "Live"), VariantChip("radio.ogg", "Radio")),
        )

    private fun show(state: PhoneRingtoneUiState) {
        compose.setContent { CucutoTheme(dynamicColor = false) { PhoneRingtoneContent(state, actions) } }
    }

    @Test
    fun offExplainsAndTurnsOn() {
        show(base.copy(enabled = false))
        compose.onNodeWithText("Turn on phone ringtones").performClick()
        assertEquals(listOf("on"), events)
    }

    @Test
    fun unlinkedSuggestsContactsAndOffersThePicker() {
        show(base.copy(suggestions = listOf(AndroidContact("ada-key", "Ada"))))
        compose.onNodeWithText("Link Ada").performClick()
        compose.onNodeWithText("Choose contact").performClick()
        assertEquals(listOf("link:ada-key", "pick"), events)
    }

    @Test
    fun linkedShowsWhatRingsTheStatusAndTheWhatsAppHint() {
        show(
            base.copy(
                linked = true,
                linkedName = "Ada",
                using = true,
                modes = listOf(RingtoneMode.PACK, RingtoneMode.RANDOM, RingtoneMode.FIXED),
                mode = RingtoneMode.FIXED,
                fixedVariant = "radio.ogg",
                ringingWith = "Radio",
                exportTitle = "K.K. Slider · Radio",
            ),
        )
        compose.onNodeWithText("Android contact: Ada").assertIsDisplayed()
        compose.onNodeWithText("One version: Radio").assertIsDisplayed()
        compose.onNodeWithText("Rings with Radio").assertIsDisplayed()
        compose.onNodeWithText("“K.K. Slider · Radio”", substring = true).assertIsDisplayed()
        // The rules and versions live in the ringtone picker now (#53): the summary opens it.
        compose.onNodeWithText("K.K. Slider").performClick()
        compose.onNodeWithText("Stop using for calls").performClick()
        assertEquals(listOf("choose", "stop"), events)
    }

    @Test
    fun aMixedRingtoneSaysSoAndTheRuleIsShown() {
        show(
            base.copy(
                linked = true,
                using = true,
                mixed = true,
                modes = listOf(RingtoneMode.PACK, RingtoneMode.TIME),
                mode = RingtoneMode.TIME,
            ),
        )
        compose.onNodeWithText("Day and night").assertIsDisplayed()
        compose.onNodeWithText("Different from the messages' character").assertIsDisplayed()
    }

    @Test
    fun aRingtoneChangedInContactsCanBeTakenOver() {
        show(base.copy(linked = true, using = true, problem = RingtoneProblem.ChangedElsewhere))
        compose.onNodeWithText("This ringtone was changed in Contacts, so Cucuto leaves it alone.").assertIsDisplayed()
        compose.onNodeWithText("Use Cucuto's ringtone again").performClick()
        assertEquals(listOf("takeover"), events)
    }

    @Test
    fun aCharacterWithoutRingtonesSaysSoAndOffersAnotherOne() {
        show(base.copy(variants = emptyList()))
        compose.onNodeWithText("K.K. Slider has no ringtone.").assertIsDisplayed()
        compose.onNodeWithText("Tap above to pick a ringtone from another character.").assertIsDisplayed()
        compose.onNodeWithText("K.K. Slider has no ringtone.").performClick()
        assertEquals(listOf("choose"), events)
    }

    @Test
    fun aChatWithoutContactAsksForAVoiceFirst() {
        show(base.copy(contactId = null, hasCharacter = false, characterName = null, variants = emptyList()))
        compose.onNodeWithText("Give this chat a voice first: its character brings the ringtone.").assertIsDisplayed()
    }

    @Test
    fun linkedButStoppedOffersToRingAgain() {
        show(base.copy(linked = true, using = false))
        compose.onNodeWithText("Ring with K.K. Slider").performClick()
        assertEquals(listOf("use"), events)
    }
}
