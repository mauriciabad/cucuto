package app.mauri.cucuto.smoke

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import app.mauri.cucuto.R
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A chat gets its message voice, then its ringtone (the two-step flow of #318), its ringtone rules change and reset,
 * and "Surprise me" picks a voice. Every change must reach the routing table the app publishes for the module.
 */
@HiltAndroidTest
class VoiceSmokeTest : SmokeTest() {
    @Before
    fun aChatAndTheDemoPacks() {
        installDemoPack(CHIPTUNE)
        messageFrom(ANA)
        startApp()
        waitForText(R.string.home_journey_first_title, ANA)
    }

    @Test
    fun messageVoiceThenRingtoneThenAndroidDefaultThenLeaveAsIs() {
        giveAnaAVoice(FROG)
        // Step 2 is optional: skipped, the ringtone follows the voice.
        tap(R.string.ringtone_picker_skip)

        // Home lists the chat with its voice, and the module's table routes it to the frog.
        waitForAnaOnHome()
        val table = waitForAnaRoutedTo("frog")
        assertEquals(CHIPTUNE_ID, table.voices[table.routes.first { it.key == ANA_KEY }.voice].packId)
        tap(ANA)
        waitForText(R.string.contact_rings_with, FROG)

        // Android default: from the contact's Phone calls row.
        tap(R.string.contact_row_calls)
        tap(R.string.ringtone_choice_default)
        tap(R.string.ringtone_save_default)
        waitForText(R.string.ringtone_row_default)
        waitForText(R.string.contact_rings_default)

        // Leave as is.
        tap(R.string.contact_row_calls)
        tap(R.string.ringtone_choice_unchanged)
        tap(R.string.ringtone_save_unchanged)
        waitForText(R.string.ringtone_row_unchanged)

        // The message voice is untouched by the ringtone choices.
        waitForAnaRoutedTo("frog")
    }

    @Test
    fun ownRingtoneRulesSaveAndReset() {
        giveAnaAVoice(FROG)
        tap(R.string.ringtone_picker_skip)
        waitForAnaOnHome()
        tap(ANA)

        // The frog sings by day and by night: its rules are one tap from the Phone calls row.
        tap(R.string.ringtone_rules_open)
        waitForText(R.string.ringtone_rules_title)
        tap(R.string.ringtone_rule_custom)
        tap(R.string.ringtone_rules_use_version, NIGHT_SONG)
        waitForText(R.string.ringtone_rules_last_on)
        tap(R.string.ringtone_rules_save)

        // Saving closes the rules; the contact now rings with its own rules.
        waitForText(R.string.ringtone_row_value, FROG, str(R.string.ringtone_rule_custom))
        tap(R.string.ringtone_rules_open)
        tap(R.string.ringtone_rules_reset)
        waitForText(R.string.ringtone_rules_reset_done)
        tap(R.string.navigate_back)
        waitForText(R.string.ringtone_row_value, FROG, str(R.string.ringtone_rule_pack))
    }

    @Test
    fun surpriseMeInThePicker() {
        tap(R.string.home_journey_first_button)
        waitForText(R.string.picker_title)
        tap(R.string.picker_surprise_all)

        // The roll brings up "Use <character>"; using it assigns that character, then step 2 can be skipped.
        val usePrefix = str(R.string.picker_use, "")
        waitFor(hasText(usePrefix, substring = true))
        tap(R.string.picker_reroll)
        val name =
            waitFor(hasText(usePrefix, substring = true))
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString("")
                .removePrefix(usePrefix)
        check(name.isNotBlank()) { "No character was rolled" }
        tap(str(R.string.picker_use, name))
        waitForText(R.string.ringtone_picker_step)
        tap(R.string.ringtone_picker_skip)

        scrollListTo(hasText(str(R.string.home_chat_voice, name, ""), substring = true))
        waitForAnaRoutedTo(null)
    }

    private companion object {
        const val NIGHT_SONG = "Pond song (night)"
    }
}
