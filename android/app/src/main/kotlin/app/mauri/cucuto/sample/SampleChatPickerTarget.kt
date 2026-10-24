package app.mauri.cucuto.sample

import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.db.RingtoneChoice
import app.mauri.cucuto.core.data.settings.SampleChatChoice
import app.mauri.cucuto.core.data.settings.SampleChatStore
import app.mauri.cucuto.ui.ringtone.RingtonePickerData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The sample chat as the real character and ringtone pickers see it (ADR 0075, #346): what they show comes from
 * [SampleChatStore] and the installed characters, and what they save goes to [SampleChatStore] only. It lives in the
 * `sample` package, so `SampleChatIsolationTest` checks that it reaches no chat, contact, phone ringtone or routing
 * store. The pickers use it when they're opened with no chat key.
 */
@Singleton
class SampleChatPickerTarget
    @Inject
    constructor(
        private val store: SampleChatStore,
        private val ringtones: SampleRingtones,
    ) {
        /** The sample chat's message character, or null before one is picked. */
        fun observeVoice(): Flow<ChatVoice?> = store.choice.map { it.voice }.distinctUntilChanged()

        /** Gives the sample chat [voice] for messages. */
        suspend fun assignVoice(voice: ChatVoice) = store.setVoice(voice)

        /**
         * The ringtone picker's data for the sample chat: no chat (there is none in the database), its ringtone as an
         * in-memory row that is never stored ([ringtoneRow]), the installed characters with ringtones, and the
         * message character as [RingtonePickerData.voice].
         */
        fun observeRingtone(): Flow<RingtonePickerData> =
            combine(store.choice, ringtones.observe()) { choice, packs ->
                RingtonePickerData(chat = null, saved = ringtoneRow(choice), packs = packs, voice = choice.voice)
            }

        /** Rings with [character], or with the message character when null ("Same as the voice"). */
        suspend fun saveRingtone(character: ChatVoice?) = store.setRingtone(character)

        /** Rings with "Android default" (ADR 0073). */
        suspend fun saveAndroidDefault() = store.setRingtoneDefault()

        internal companion object {
            /**
             * [choice]'s ringtone in the shape the ringtone picker reads: "Android default", a character of the
             * user's own (the pack's rule picks the version, ADR 0075 point 2), or null while it follows the voice.
             * Only a value on screen: nothing writes it to the phone ringtone table.
             */
            fun ringtoneRow(choice: SampleChatChoice): PhoneRingtoneEntity? {
                if (choice.ringtoneDefault) {
                    return PhoneRingtoneEntity(contactId = 0, updatedAt = 0, choice = RingtoneChoice.ANDROID_DEFAULT)
                }
                val own = choice.ringtone ?: return null
                return PhoneRingtoneEntity(
                    contactId = 0,
                    packId = own.packId,
                    characterId = own.characterId,
                    updatedAt = 0,
                    followsVoice = false,
                )
            }
        }
    }
