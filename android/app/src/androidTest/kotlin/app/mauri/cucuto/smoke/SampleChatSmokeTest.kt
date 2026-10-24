package app.mauri.cucuto.smoke

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import app.mauri.cucuto.R
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test

/** The sample chat (ADR 0075) plays a voice and a ringtone in the app, without the module. */
@HiltAndroidTest
class SampleChatSmokeTest : SmokeTest() {
    @Test
    fun theSampleChatPlaysAMessageAndACall() {
        installDemoPack(CHIPTUNE)
        startApp()

        // With packs and no chats yet, Home's first-run card offers the sample chat.
        tap(R.string.home_journey_sample)
        waitForText(R.string.sample_chat_title)

        // Choosing a voice plays it: the character says it is speaking, and no playback error shows.
        tap(FROG)
        waitForText(R.string.sample_chat_voice_current, FROG)
        val playing = str(R.string.a11y_playing)
        waitFor(
            hasContentDescription(FROG) and hasStateDescription(str(R.string.picker_state_selected) + ", " + playing),
        )
        tap(R.string.sample_chat_play_message)
        compose.onAllNodes(hasText(str(R.string.sample_chat_failed))).assertCountEquals(0)

        // The frog has ringtones: a sample call rings until stopped.
        tap(R.string.sample_chat_play_call)
        waitForText(R.string.sample_chat_stop_call)
        tap(R.string.sample_chat_stop_call)
        waitForText(R.string.sample_chat_play_call)
        compose.onAllNodes(hasText(str(R.string.sample_chat_failed))).assertCountEquals(0)
    }
}
