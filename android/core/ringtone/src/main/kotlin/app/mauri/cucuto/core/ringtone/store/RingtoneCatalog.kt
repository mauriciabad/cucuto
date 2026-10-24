package app.mauri.cucuto.core.ringtone.store

import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.repository.InstalledPackRepository
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.packspec.PackManifest
import app.mauri.cucuto.packspec.SpecJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A character's ringtones in an installed pack.
 *
 * @property packDir The installed pack folder; variant files resolve inside it ([fileOf]).
 */
data class CharacterRingtones(
    val packId: String,
    val packVersion: String,
    val characterId: String,
    val characterName: String,
    val packDir: File,
    val variants: List<RingtoneVariant>,
) {
    /** The audio file of [variant], or null when its path would leave the pack folder. */
    fun fileOf(variant: RingtoneVariant): File? {
        val path = variant.file
        if (path.isEmpty() || path.startsWith('/') || path.split('/').any { it == ".." }) return null
        return File(packDir, path)
    }
}

/** Reads ringtones of installed characters. */
fun interface RingtoneCatalog {
    /** The character's ringtones, or null when its pack is not installed or the character is unknown. */
    suspend fun character(
        packId: String,
        characterId: String,
    ): CharacterRingtones?
}

/** [RingtoneCatalog] over `installed_packs` and each pack's validated `pack.json`, cached per installed version. */
@Singleton
class InstalledRingtoneCatalog
    @Inject
    constructor(
        private val packs: InstalledPackRepository,
    ) : RingtoneCatalog {
        private val cache = ConcurrentHashMap<String, PackManifest>()

        override suspend fun character(
            packId: String,
            characterId: String,
        ): CharacterRingtones? {
            val pack = packs.get(packId) ?: return null
            val manifest = manifest(pack) ?: return null
            val character = manifest.characters.firstOrNull { it.id == characterId } ?: return null
            return CharacterRingtones(
                packId = pack.packId,
                packVersion = pack.version,
                characterId = character.id,
                characterName = character.name,
                packDir = File(pack.path),
                variants =
                    character.ringtones.map {
                        RingtoneVariant(
                            file = it.file,
                            label = it.label,
                            tags = it.tags,
                            condition = it.`when`,
                            weight = it.weight ?: 1.0,
                            loop = it.loopRange,
                            fadeInMs = it.fadeInMs?.toLong() ?: 0L,
                        )
                    },
            )
        }

        private suspend fun manifest(pack: InstalledPackEntity): PackManifest? {
            val key = "${pack.packId}\u0000${pack.version}\u0000${pack.path}"
            cache[key]?.let { return it }
            return withContext(Dispatchers.IO) {
                runCatching {
                    SpecJson.decodeFromString<PackManifest>(
                        File(pack.path, "pack.json").readText(),
                    )
                }.getOrNull()
            }?.also { cache[key] = it }
        }
    }
