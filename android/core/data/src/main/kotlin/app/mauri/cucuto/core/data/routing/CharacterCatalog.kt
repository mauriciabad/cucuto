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

/**
 * An installed character: what the routing table needs (clips, haptic) and what the character picker shows.
 *
 * @property name Display name from `pack.json`, or null if missing.
 * @property sprite Path of the static sprite from `pack.json` (relative to the pack folder).
 * @property spriteAnimated Path of the optional animated sprite.
 * @property seedColor `#RRGGBB` seed of the character's color scheme, when the pack sets one.
 */
data class CatalogCharacter(
    val clips: List<CatalogClip>,
    val haptic: Haptic,
    val name: String? = null,
    val sprite: String? = null,
    val spriteAnimated: String? = null,
    val seedColor: String? = null,
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
        ): CatalogCharacter? = characters(pack)[characterId]

        /** Every character of [pack] in `pack.json` order; empty if the manifest cannot be read. */
        suspend fun characters(pack: InstalledPackEntity): Map<String, CatalogCharacter> {
            val cacheKey = "${pack.packId}\u0000${pack.version}\u0000${pack.path}"
            return cache[cacheKey] ?: load(pack).also { cache[cacheKey] = it }
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
                    result[id] =
                        CatalogCharacter(
                            clips = clips,
                            haptic = HapticJson.fromPack(character["haptic"]),
                            name = character.string("name"),
                            sprite = character.string("sprite"),
                            spriteAnimated = character.string("spriteAnimated"),
                            seedColor = character.string("seedColor"),
                        )
                }
                return result
            }

            private fun JsonObject.string(key: String): String? =
                (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
    }
