package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.haptics.auto.AutoHapticFile
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
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

    /**
     * Emits whenever what [character] returns may have changed for packs whose database row did not change (for
     * example a pack's auto haptics were analysed again), so the routing table is rebuilt.
     */
    val revisions: Flow<Long> get() = flowOf(0L)
}

/**
 * Reads characters from the `pack.json` of installed packs ([InstalledPackEntity.path]). The importer has already
 * validated the manifest, so this reads only the few fields routing needs and skips anything unexpected. Parsed
 * manifests are cached per installed version.
 *
 * Auto haptics ("haptics follow the voice", ADR 0012 and 0020) come from the analysis stored next to the pack
 * ([AutoHapticFile]), snapped to [HapticGrid] so the routing table stores each envelope in its compact form. Clips
 * without an analysis carry no envelope, and `auto` leaves WhatsApp's vibration in place for them.
 */
@Singleton
class PackManifestCatalog
    @Inject
    constructor() : CharacterCatalog {
        private val json = Json { ignoreUnknownKeys = true }
        private val cache = ConcurrentHashMap<String, Map<String, CatalogCharacter>>()
        private val revision = MutableStateFlow(0L)

        override val revisions: StateFlow<Long> = revision.asStateFlow()

        override suspend fun character(
            pack: InstalledPackEntity,
            characterId: String,
        ): CatalogCharacter? {
            val cacheKey = "${pack.packId}\u0000${pack.version}\u0000${pack.path}"
            val characters = cache[cacheKey] ?: load(pack).also { cache[cacheKey] = it }
            return characters[characterId]
        }

        /** Forgets what was read from the pack installed in [packPath] (its auto haptics changed) and says so. */
        fun invalidate(packPath: String) {
            cache.keys.removeAll { it.substringAfterLast('\u0000') == packPath }
            revision.update { it + 1 }
        }

        private suspend fun load(pack: InstalledPackEntity): Map<String, CatalogCharacter> =
            withContext(Dispatchers.IO) {
                val dir = File(pack.path)
                val root =
                    runCatching { json.parseToJsonElement(File(dir, "pack.json").readText()) as? JsonObject }
                        .getOrNull() ?: return@withContext emptyMap()
                val autoHaptics = AutoHapticFile.read(dir)?.clips.orEmpty()
                parseCharacters(root, autoHaptics)
            }

        internal companion object {
            fun parseCharacters(
                root: JsonObject,
                autoHaptics: Map<String, Haptic.Custom?> = emptyMap(),
            ): Map<String, CatalogCharacter> {
                val characters = root["characters"] as? JsonArray ?: return emptyMap()
                val snapped = HashMap<String, Haptic.Custom?>()
                val result = LinkedHashMap<String, CatalogCharacter>()
                for (element in characters) {
                    val character = element as? JsonObject ?: continue
                    val id = (character["id"] as? JsonPrimitive)?.content ?: continue
                    val clips =
                        (character["clips"] as? JsonArray).orEmpty().mapNotNull { clipElement ->
                            val clip = clipElement as? JsonObject ?: return@mapNotNull null
                            val file = (clip["file"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                            val weight = (clip["weight"] as? JsonPrimitive)?.doubleOrNull ?: 1.0
                            val autoHaptic = snapped.getOrPut(file) { autoHaptics[file]?.let(HapticGrid::snap) }
                            CatalogClip(file, (weight * 1000).roundToInt().coerceAtLeast(1), autoHaptic)
                        }
                    result[id] = CatalogCharacter(clips, HapticJson.fromPack(character["haptic"]))
                }
                return result
            }
        }
    }
