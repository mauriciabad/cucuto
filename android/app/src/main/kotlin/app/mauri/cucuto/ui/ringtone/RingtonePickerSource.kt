package app.mauri.cucuto.ui.ringtone

import androidx.compose.runtime.Immutable
import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatDirectory
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.PhoneRingtoneDao
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
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

    /** Stores [contactId]'s choice: [voice] null follows the message character. */
    suspend fun save(
        contactId: Long,
        voice: ChatVoice?,
        mode: RingtoneMode,
        variant: String?,
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
            contactId: Long,
            voice: ChatVoice?,
            mode: RingtoneMode,
            variant: String?,
        ) = ringtones.choose(contactId, voice?.packId, voice?.characterId, mode, variant)

        override fun canReadPhoneState(): Boolean = ringtones.canReadPhoneState()
    }

@Module
@InstallIn(SingletonComponent::class)
interface RingtonePickerModule {
    @Binds fun source(source: AppRingtonePickerSource): RingtonePickerSource
}
