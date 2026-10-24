package app.mauri.cucuto.ui.ringtone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.db.RingtoneChoice
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

    /** The "Phone calls" row of contact detail (ADR 0063), above the card. */
    private fun showRow(state: PhoneRingtoneUiState) {
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                PhoneCallsRowContent(
                    state,
                    onChoose = { events += "choose" },
                    onTogglePreview = { events += "preview" },
                    onStopPreview = { events += "stop-preview" },
                )
            }
        }
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
        compose.onNodeWithText("Rings with Radio").assertIsDisplayed()
        compose.onNodeWithText("“K.K. Slider · Radio”", substring = true).assertIsDisplayed()
        // "Leave as is" in the ringtone picker replaced "Stop using for calls" (ADR 0073).
        compose.onNodeWithText("Stop using for calls").assertDoesNotExist()
    }

    @Test
    fun theRowShowsWhatRingsAndItsRuleAndOpensThePicker() {
        showRow(
            base.copy(
                using = true,
                modes = listOf(RingtoneMode.PACK, RingtoneMode.RANDOM, RingtoneMode.FIXED),
                mode = RingtoneMode.FIXED,
                fixedVariant = "radio.ogg",
            ),
        )
        compose.onNodeWithText("Phone calls").assertIsDisplayed()
        compose.onNodeWithText("K.K. Slider · One version: Radio").assertIsDisplayed()
        compose.onNodeWithText("Same as the messages").assertIsDisplayed()
        compose.onNodeWithText("K.K. Slider · One version: Radio").performClick()
        assertEquals(listOf("choose"), events)
    }

    @Test
    fun aRingtoneTheUserPickedSaysSo() {
        showRow(
            base.copy(
                using = true,
                mixed = true,
                modes = listOf(RingtoneMode.PACK, RingtoneMode.TIME),
                mode = RingtoneMode.TIME,
            ),
        )
        compose.onNodeWithText("K.K. Slider · Day and night").assertIsDisplayed()
        compose.onNodeWithText("Chosen for calls").assertIsDisplayed()
    }

    @Test
    fun aRingtoneKeptWhileTheMessagesHaveNoneSaysSo() {
        showRow(base.copy(using = true, kept = true, voiceName = "Tweet the bird"))
        compose.onNodeWithText("Kept: Tweet the bird has no ringtone").assertIsDisplayed()
    }

    @Test
    fun theRowRingsTheRingtoneLikeTheCall() {
        showRow(base.copy(using = true, previewSound = RingSound(java.io.File("live.ogg"))))
        compose.onNodeWithContentDescription("Play K.K. Slider").performClick()
        assertEquals(listOf("preview"), events)
    }

    @Test
    fun aRingingPreviewOffersPauseAndStop() {
        val sound = RingSound(java.io.File("live.ogg"))
        // The progress ring redraws every frame while it rings: drive the clock by hand.
        compose.mainClock.autoAdvance = false
        showRow(
            base.copy(
                using = true,
                previewSound = sound,
                preview = PreviewPlaying(sound, cycleMs = 90_000),
            ),
        )
        compose.onNodeWithContentDescription("Pause K.K. Slider").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop K.K. Slider").performClick()
        assertEquals(listOf("stop-preview"), events)
    }

    @Test
    fun aPausedPreviewOffersPlayAndStop() {
        val sound = RingSound(java.io.File("live.ogg"))
        showRow(
            base.copy(
                using = true,
                previewSound = sound,
                preview = PreviewPlaying(sound, cycleMs = 90_000, paused = true, playedMs = 30_000),
            ),
        )
        compose.onNodeWithContentDescription("Play K.K. Slider").performClick()
        compose.onNodeWithContentDescription("Stop K.K. Slider").assertIsDisplayed()
        assertEquals(listOf("preview"), events)
    }

    @Test
    fun aRingtoneChangedInContactsCanBeTakenOver() {
        show(base.copy(linked = true, using = true, problem = RingtoneProblem.ChangedElsewhere))
        compose.onNodeWithText("This ringtone was changed in Contacts, so Cucuto leaves it alone.").assertIsDisplayed()
        compose.onNodeWithText("Use Cucuto's ringtone again").performClick()
        assertEquals(listOf("takeover"), events)
    }

    @Test
    fun withNothingThatRingsTheRowOffersThePickerAndTheCardHidesItsSetUp() {
        // A message character without ringtones (Tweet the bird): the ringtone comes from another character.
        showRow(base.copy(characterName = "Tweet the bird", variants = emptyList()))
        compose.onNodeWithText("No ringtone yet. Tap to choose one.").performClick()
        assertEquals(listOf("choose"), events)
    }

    @Test
    fun theCardHasNothingToSetUpWithoutARingtone() {
        show(base.copy(variants = emptyList()))
        compose.onNodeWithText("Phone ringtone").assertDoesNotExist()
        compose.onNodeWithText("Turn on phone ringtones").assertDoesNotExist()
    }

    @Test
    fun aChatWithoutContactCanStillChooseARingtone() {
        showRow(base.copy(contactId = null, hasCharacter = false, characterName = null, variants = emptyList()))
        compose.onNodeWithText("No ringtone yet. Tap to choose one.").performClick()
        assertEquals(listOf("choose"), events)
    }

    @Test
    fun theRowOpensTheRulesOfACharacterWithSeveralVersions() {
        val kk =
            app.mauri.cucuto.core.data.voices.VoiceCharacter(
                "app.mauri.songs",
                "kk",
                "K.K. Slider",
                null,
                null,
                null,
                emptyList(),
                app.mauri.cucuto.core.routing.Haptic.Keep,
                ringtoneCount = 2,
            )
        var opened: app.mauri.cucuto.core.data.chats.ChatVoice? = null
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                PhoneCallsRowContent(
                    base.copy(ringingCharacter = kk, modes = listOf(RingtoneMode.PACK, RingtoneMode.FIXED)),
                    onChoose = {},
                    onTogglePreview = {},
                    onOpenRules = { opened = it },
                )
            }
        }
        compose.onNodeWithText("See and edit the rules").performClick()
        assertEquals(kk.voice, opened)
    }

    @Test
    fun androidDefaultSaysSoOnTheRowAndTheCardSetsItUp() {
        val default =
            base.copy(
                characterName = null,
                hasCharacter = false,
                variants = emptyList(),
                choice = RingtoneChoice.ANDROID_DEFAULT,
            )
        showRow(default)
        compose.onNodeWithText("Android default").assertIsDisplayed()
        compose.onNodeWithText("The phone's own ringtone").performClick()
        assertEquals(listOf("choose"), events)
    }

    @Test
    fun androidDefaultNeedsTheLinkedContactToClearItsRingtone() {
        show(
            base.copy(
                characterName = null,
                hasCharacter = false,
                variants = emptyList(),
                choice = RingtoneChoice.ANDROID_DEFAULT,
                linked = true,
                linkedName = "Ada",
                using = true,
            ),
        )
        compose.onNodeWithText("Rings with the phone's default ringtone.").assertIsDisplayed()
        compose.onNodeWithText("WhatsApp calls ring on their own", substring = true).assertDoesNotExist()
    }

    @Test
    fun leaveAsIsSaysSoAndHasNothingToSetUp() {
        val unchanged = base.copy(choice = RingtoneChoice.UNCHANGED, linked = true, using = false)
        showRow(unchanged)
        compose.onNodeWithText("Left as is").assertIsDisplayed()
        compose.onNodeWithText("Cucuto doesn't touch it").assertIsDisplayed()
    }

    @Test
    fun leaveAsIsHidesTheCard() {
        show(base.copy(choice = RingtoneChoice.UNCHANGED, linked = true, using = false))
        compose.onNodeWithText("Phone ringtone").assertDoesNotExist()
    }

    @Test
    fun linkedButStoppedOffersToRingAgain() {
        show(base.copy(linked = true, using = false))
        compose.onNodeWithText("Ring with K.K. Slider").performClick()
        assertEquals(listOf("use"), events)
    }
}
