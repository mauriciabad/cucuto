package app.mauri.cucuto.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the sample chat remembers (ADR 0075): its message character and, when the user picked one, its ringtone
 * character. Null ringtone means "same as the voice" (ADR 0063's default). [ringtoneDefault] is "Android default"
 * (ADR 0073), picked in the ringtone picker (#346); it wins over [ringtone].
 */
data class SampleChatChoice(
    val voice: ChatVoice? = null,
    val ringtone: ChatVoice? = null,
    val ringtoneDefault: Boolean = false,
)

/**
 * Where the sample chat keeps its choices. Never the contacts, keys, assignments or phone ringtone tables: those feed
 * the routing table, backups and the Android contact link, and the sample chat must reach none of them (ADR 0075).
 */
interface SampleChatStore {
    val choice: Flow<SampleChatChoice>

    suspend fun setVoice(voice: ChatVoice)

    /** [ringtone] null follows the voice. Either way, the ringtone is a character again (not "Android default"). */
    suspend fun setRingtone(ringtone: ChatVoice?)

    /** Rings with "Android default" (ADR 0073); [setRingtone] goes back to a character. */
    suspend fun setRingtoneDefault()
}

/**
 * [SampleChatStore] in the app's settings DataStore. Backups list their settings one by one
 * (`core/backup/SettingsBackup.kt`), so these keys are never backed up or restored.
 */
@Singleton
class SampleChatSettings
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : SampleChatStore {
        override val choice: Flow<SampleChatChoice> =
            dataStore.data
                .map { prefs ->
                    SampleChatChoice(
                        voice = voiceOf(prefs[VOICE_PACK], prefs[VOICE_CHARACTER]),
                        ringtone = voiceOf(prefs[RINGTONE_PACK], prefs[RINGTONE_CHARACTER]),
                        ringtoneDefault = prefs[RINGTONE_DEFAULT] == true,
                    )
                }.distinctUntilChanged()

        override suspend fun setVoice(voice: ChatVoice) {
            dataStore.edit {
                it[VOICE_PACK] = voice.packId
                it[VOICE_CHARACTER] = voice.characterId
            }
        }

        override suspend fun setRingtone(ringtone: ChatVoice?) {
            dataStore.edit {
                it.remove(RINGTONE_DEFAULT)
                if (ringtone == null) {
                    it.remove(RINGTONE_PACK)
                    it.remove(RINGTONE_CHARACTER)
                } else {
                    it[RINGTONE_PACK] = ringtone.packId
                    it[RINGTONE_CHARACTER] = ringtone.characterId
                }
            }
        }

        override suspend fun setRingtoneDefault() {
            dataStore.edit { it[RINGTONE_DEFAULT] = true }
        }

        private fun voiceOf(
            packId: String?,
            characterId: String?,
        ): ChatVoice? = if (packId != null && characterId != null) ChatVoice(packId, characterId) else null

        private companion object {
            val VOICE_PACK = stringPreferencesKey("sample_chat_voice_pack")
            val VOICE_CHARACTER = stringPreferencesKey("sample_chat_voice_character")
            val RINGTONE_PACK = stringPreferencesKey("sample_chat_ringtone_pack")
            val RINGTONE_CHARACTER = stringPreferencesKey("sample_chat_ringtone_character")
            val RINGTONE_DEFAULT = booleanPreferencesKey("sample_chat_ringtone_default")
        }
    }
