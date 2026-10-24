package app.mauri.cucuto.sample

import app.mauri.cucuto.core.data.settings.SampleChatSettings
import app.mauri.cucuto.core.data.settings.SampleChatStore
import app.mauri.cucuto.core.data.voices.CharacterDirectory
import app.mauri.cucuto.core.ringtone.store.RingtoneCatalog
import app.mauri.cucuto.ui.ringtone.RingtoneCharacterItem
import app.mauri.cucuto.ui.ringtone.RingtonePackItem
import app.mauri.cucuto.ui.ringtone.RingtoneVariantItem
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The installed characters with ringtones, for the sample chat's "Call ringtone" (read only). */
fun interface SampleRingtones {
    fun observe(): Flow<List<RingtonePackItem>>
}

/** [SampleRingtones] from the installed packs, like the ringtone picker's list: characters in `pack.json` order. */
@Singleton
class InstalledSampleRingtones
    @Inject
    constructor(
        private val characters: CharacterDirectory,
        private val catalog: RingtoneCatalog,
    ) : SampleRingtones {
        override fun observe(): Flow<List<RingtonePackItem>> =
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
    }

@Module
@InstallIn(SingletonComponent::class)
interface SampleChatModule {
    @Binds fun store(store: SampleChatSettings): SampleChatStore

    @Binds fun ringtones(ringtones: InstalledSampleRingtones): SampleRingtones
}
