package app.mauri.cucuto.ui.ringtone.rules

import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatDirectory
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.PhoneRingtoneDao
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.data.db.RingtoneRuleEntry
import app.mauri.cucuto.core.data.voices.CharacterDirectory
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.ringtone.store.RingtoneCatalog
import app.mauri.cucuto.ui.ringtone.RingtonePickerSource
import app.mauri.cucuto.ui.ringtone.RingtoneVariantItem
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the rules screen shows for one character and, optionally, one chat.
 *
 * @property character The character, null while unknown or when it is no longer installed.
 * @property entries Its ringtone entries in `pack.json` order, repeats included (each is a line of the pack's rules).
 * @property chat The chat whose ringtone the screen edits, null without one.
 * @property saved The chat's stored phone ringtone (any character), null when it has none.
 */
data class RingtoneRulesData(
    val character: VoiceCharacter?,
    val packName: String?,
    val entries: List<RingtoneVariantItem>,
    val chat: Chat?,
    val saved: PhoneRingtoneEntity?,
)

/** The rules screen's data and its one write, behind an interface so the view model is tested with a fake. */
interface RingtoneRulesSource {
    fun observe(
        voice: ChatVoice,
        chat: ChatKey?,
    ): Flow<RingtoneRulesData>

    /** Stores [chat]'s ringtone rule, as the picker does ([RingtonePickerSource.save]). */
    suspend fun save(
        chat: ChatKey,
        contactId: Long?,
        character: ChatVoice?,
        followsVoice: Boolean,
        mode: RingtoneMode,
        variant: String?,
        rules: List<RingtoneRuleEntry>,
    )

    /** Whether "Random per call" can re-roll (`READ_PHONE_STATE`). */
    fun canReadPhoneState(): Boolean
}

/** [RingtoneRulesSource] over the installed characters, their ringtones, the chat directory and the picker's write. */
@Singleton
class AppRingtoneRulesSource
    @Inject
    constructor(
        private val chats: ChatDirectory,
        private val characters: CharacterDirectory,
        private val catalog: RingtoneCatalog,
        private val dao: PhoneRingtoneDao,
        private val picker: RingtonePickerSource,
    ) : RingtoneRulesSource {
        override fun observe(
            voice: ChatVoice,
            chat: ChatKey?,
        ): Flow<RingtoneRulesData> {
            val found =
                if (chat == null) {
                    flowOf(null)
                } else {
                    chats.observeChats().map { all -> all.firstOrNull { chat in it.keys } }.distinctUntilChanged()
                }
            return combine(character(voice), found, dao.observeAll()) { (pack, character, entries), chatFound, rows ->
                RingtoneRulesData(
                    character = character,
                    packName = pack,
                    entries = entries,
                    chat = chatFound,
                    saved = chatFound?.contactId?.let { id -> rows.firstOrNull { it.contactId == id } },
                )
            }
        }

        private fun character(voice: ChatVoice): Flow<Triple<String?, VoiceCharacter?, List<RingtoneVariantItem>>> =
            characters
                .observePacks()
                .map { packs ->
                    val pack = packs.firstOrNull { it.packId == voice.packId }
                    val character = pack?.characters?.firstOrNull { it.id == voice.characterId }
                    val ringtones = character?.let { catalog.character(voice.packId, voice.characterId) }
                    val entries = ringtones?.variants.orEmpty().map { RingtoneVariantItem(it, ringtones?.fileOf(it)) }
                    Triple(pack?.name, character, entries)
                }.flowOn(Dispatchers.IO)

        override suspend fun save(
            chat: ChatKey,
            contactId: Long?,
            character: ChatVoice?,
            followsVoice: Boolean,
            mode: RingtoneMode,
            variant: String?,
            rules: List<RingtoneRuleEntry>,
        ) = picker.save(chat, contactId, character, followsVoice, mode, variant, rules)

        override fun canReadPhoneState(): Boolean = picker.canReadPhoneState()
    }

@Module
@InstallIn(SingletonComponent::class)
interface RingtoneRulesModule {
    @Binds fun source(source: AppRingtoneRulesSource): RingtoneRulesSource
}
