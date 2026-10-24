package app.mauri.cucuto.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.mauri.cucuto.core.data.chats.ChatVoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SampleChatSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.settings() =
        SampleChatSettings(
            PreferenceDataStoreFactory.create(scope = backgroundScope) {
                folder.newFile("settings.preferences_pb").also { it.delete() }
            },
        )

    @Test
    fun startsEmptyAndRemembersTheVoiceAndTheRingtone() =
        runTest {
            val settings = settings()
            assertEquals(SampleChatChoice(), settings.choice.first())

            settings.setVoice(ChatVoice("app.mauri.demo.critters", "frog"))
            settings.setRingtone(ChatVoice("app.mauri.demo.kitchen", "kettle"))
            assertEquals(
                SampleChatChoice(
                    voice = ChatVoice("app.mauri.demo.critters", "frog"),
                    ringtone = ChatVoice("app.mauri.demo.kitchen", "kettle"),
                ),
                settings.choice.first(),
            )

            // "Same as the voice" forgets the picked ringtone and keeps the voice.
            settings.setRingtone(null)
            assertNull(settings.choice.first().ringtone)
            assertEquals(
                "frog",
                settings.choice
                    .first()
                    .voice
                    ?.characterId,
            )
        }

    @Test
    fun androidDefaultWinsUntilACharacterIsPickedAgain() =
        runTest {
            val settings = settings()
            settings.setRingtone(ChatVoice("app.mauri.demo.kitchen", "kettle"))
            settings.setRingtoneDefault()
            assertTrue(settings.choice.first().ringtoneDefault)

            settings.setRingtone(null)
            assertFalse(settings.choice.first().ringtoneDefault)
        }
}
