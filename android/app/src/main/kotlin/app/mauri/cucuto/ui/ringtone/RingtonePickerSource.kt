package app.mauri.cucuto.ui.ringtone

import androidx.compose.runtime.Immutable
import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatDirectory
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.PhoneRingtoneDao
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.db.RingtoneChoice
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.data.voices.CharacterDirectory
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.ringtone.PhoneRingtones
import app.mauri.cucuto.core.ringtone.store.RingtoneCatalog
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One version of a character's ringtone, with its file in the installed pack for previews. */
@Immutable
data class RingtoneVariantItem(
    val variant: RingtoneVariant,
    /** The audio file, or null when its path would leave the pack folder. */
    val audio: File?,
) {
    /** Identifies the variant (its path in `pack.json`). */
    val file: String get() = variant.file

    val label: String get() = variant.label ?: variant.file.substringAfterLast('/').substringBeforeLast('.')

    /** What a preview rings: the file with the loop and fade-in its ring file gets (#242). */
    fun sound(): RingSound? = audio?.let { RingSound(it, variant.loop, variant.fadeInMs) }
}

/** A character with ringtones: an album cover in the picker. */
@Immutable
data class RingtoneCharacterItem(
    val character: VoiceCharacter,
    val variants: List<RingtoneVariantItem>,
) {
    val voice: ChatVoice get() = character.voice

    fun variant(file: String?): RingtoneVariantItem? = variants.firstOrNull { it.file == file }
}

@Immutable
data class RingtonePackItem(
    val packId: String,
    val name: String,
    val characters: List<RingtoneCharacterItem>,
)

/**
 * What the ringtone picker shows for one chat.
 *
 * @property chat The chat (null while unknown); its contact holds the phone ringtone.
 * @property saved The stored phone ringtone, or null when the chat has none.
 * @property packs Installed packs with at least one character that has ringtones, characters in `pack.json` order.
 */
data class RingtonePickerData(
    val chat: Chat?,
    val saved: PhoneRingtoneEntity?,
    val packs: List<RingtonePackItem>,
)

/** The ringtone picker's data and its one write, behind an interface so the view model is tested with a fake. */
interface RingtonePickerSource {
    fun observe(chat: ChatKey): Flow<RingtonePickerData>

    /**
     * Stores [chat]'s choice (ADR 0063): [character] null rings with the message character; [followsVoice] says whether
     * it keeps following the voice (false: the user's own pick). A chat without a contact ([contactId] null) gets one.
     */
    suspend fun save(
        chat: ChatKey,
        contactId: Long?,
        character: ChatVoice?,
        followsVoice: Boolean,
        mode: RingtoneMode,
        variant: String?,
    )

    /**
     * Stores "Android default" or "Leave as is" for [chat] (ADR 0073). A chat without a contact ([contactId] null)
     * gets one.
     */
    suspend fun saveOther(
        chat: ChatKey,
        contactId: Long?,
        choice: RingtoneChoice,
    )

    /** Whether "Random per call" can re-roll (`READ_PHONE_STATE`). */
    fun canReadPhoneState(): Boolean
}

/** [RingtonePickerSource] over the chat directory, the installed characters, their ringtones and [PhoneRingtones]. */
@Singleton
class AppRingtonePickerSource
    @Inject
    constructor(
        private val chats: ChatDirectory,
        private val characters: CharacterDirectory,
        private val catalog: RingtoneCatalog,
        private val dao: PhoneRingtoneDao,
        private val ringtones: PhoneRingtones,
    ) : RingtonePickerSource {
        override fun observe(chat: ChatKey): Flow<RingtonePickerData> =
            combine(
                chats.observeChats().map { all -> all.firstOrNull { chat in it.keys } }.distinctUntilChanged(),
                packsWithRingtones(),
                dao.observeAll(),
            ) { found, packs, rows ->
                RingtonePickerData(
                    chat = found,
                    saved = found?.contactId?.let { id -> rows.firstOrNull { it.contactId == id } },
                    packs = packs,
                )
            }

        private fun packsWithRingtones(): Flow<List<RingtonePackItem>> =
            characters
                .observePacks()
                .map { packs ->
                    packs.mapNotNull { pack ->
                        val items =
                            pack.characters.mapNotNull { character ->
                                val ringtones = catalog.character(pack.packId, character.id) ?: return@mapNotNull null
                                val variants =
                                    ringtones.variants
                                        .distinctBy { it.file }
                                        .map { RingtoneVariantItem(it, ringtones.fileOf(it)) }
                                if (variants.isEmpty()) null else RingtoneCharacterItem(character, variants)
                            }
                        if (items.isEmpty()) null else RingtonePackItem(pack.packId, pack.name, items)
                    }
                }.flowOn(Dispatchers.IO)

        override suspend fun save(
            chat: ChatKey,
            contactId: Long?,
            character: ChatVoice?,
            followsVoice: Boolean,
            mode: RingtoneMode,
            variant: String?,
        ) {
            val id = contactId ?: chats.ensureContact(chat)
            ringtones.choose(id, character?.packId, character?.characterId, mode, variant, followsVoice)
        }

        override suspend fun saveOther(
            chat: ChatKey,
            contactId: Long?,
            choice: RingtoneChoice,
        ) {
            ringtones.chooseOther(contactId ?: chats.ensureContact(chat), choice)
        }

        override fun canReadPhoneState(): Boolean = ringtones.canReadPhoneState()
    }

@Module
@InstallIn(SingletonComponent::class)
interface RingtonePickerModule {
    @Binds fun source(source: AppRingtonePickerSource): RingtonePickerSource
}
