package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.routing.Haptic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** What the routing table needs to know about an installed character. */
data class CatalogCharacter(
    val clips: List<CatalogClip>,
    val haptic: Haptic,
)

/**
 * One notification clip of a character.
 *
 * @property file Path from `pack.json` (also the last part of the clip URI).
 * @property milliWeight The pack's `weight` in thousandths.
 * @property autoHaptic The clip's analysed envelope for `auto` haptics, when the app has one.
 */
data class CatalogClip(
    val file: String,
    val milliWeight: Int,
    val autoHaptic: Haptic.Custom? = null,
)

/** Looks up installed characters for the routing table. */
interface CharacterCatalog {
    /** The character, or null if [pack] does not contain it or cannot be read. */
    suspend fun character(
        pack: InstalledPackEntity,
        characterId: String,
    ): CatalogCharacter?
}

/**
 * Reads characters from the `pack.json` of installed packs ([InstalledPackEntity.path]). The importer has already
 * validated the manifest, so this reads only the few fields routing needs and skips anything unexpected. Parsed
 * manifests are cached per installed version.
 *
 * Auto haptics ("haptics follow the voice") need the clip analysis from `:core:audio`; until that exists, clips
 * carry no envelope and `auto` leaves WhatsApp's vibration in place.
 */
@Singleton
class PackManifestCatalog
    @Inject
    constructor() : CharacterCatalog {
        private val json = Json { ignoreUnknownKeys = true }
        private val cache = ConcurrentHashMap<String, Map<String, CatalogCharacter>>()

        override suspend fun character(
            pack: InstalledPackEntity,
            characterId: String,
        ): CatalogCharacter? {
            val cacheKey = "${pack.packId}\u0000${pack.version}\u0000${pack.path}"
            val characters = cache[cacheKey] ?: load(pack).also { cache[cacheKey] = it }
            return characters[characterId]
        }

        private suspend fun load(pack: InstalledPackEntity): Map<String, CatalogCharacter> =
            withContext(Dispatchers.IO) {
                val manifest = File(pack.path, "pack.json")
                val root =
                    runCatching { json.parseToJsonElement(manifest.readText()) as? JsonObject }.getOrNull()
                        ?: return@withContext emptyMap()
                parseCharacters(root)
            }

        internal companion object {
            fun parseCharacters(root: JsonObject): Map<String, CatalogCharacter> {
                val characters = root["characters"] as? JsonArray ?: return emptyMap()
                val result = LinkedHashMap<String, CatalogCharacter>()
                for (element in characters) {
                    val character = element as? JsonObject ?: continue
                    val id = (character["id"] as? JsonPrimitive)?.content ?: continue
                    val clips =
                        (character["clips"] as? JsonArray).orEmpty().mapNotNull { clipElement ->
                            val clip = clipElement as? JsonObject ?: return@mapNotNull null
                            val file = (clip["file"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                            val weight = (clip["weight"] as? JsonPrimitive)?.doubleOrNull ?: 1.0
                            CatalogClip(file, (weight * 1000).roundToInt().coerceAtLeast(1))
                        }
                    result[id] = CatalogCharacter(clips, HapticJson.fromPack(character["haptic"]))
                }
                return result
            }
        }
    }
