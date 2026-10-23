package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.db.PackSource
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.core.routing.RoutingTable

internal const val CRITTERS = "app.mauri.demo.critters"

internal fun installedPack(
    packId: String = CRITTERS,
    version: String = "1.0.0",
    path: String = "/packs/$packId/$version",
) = InstalledPackEntity(
    packId = packId,
    version = version,
    name = packId,
    path = path,
    sha256 = "0".repeat(64),
    sizeBytes = 100,
    source = PackSource.LOCAL,
    installedAt = 1,
)

internal val frogHaptic = Haptic.Custom(listOf(HapticEvent.Preset(0, HapticPreset.CLICK, 900)))

/** An in-memory catalog: `pack ID → character ID → character`. Counts lookups. */
internal class FakeCatalog(
    private val characters: Map<String, Map<String, CatalogCharacter>> =
        mapOf(
            CRITTERS to
                mapOf(
                    "frog" to
                        CatalogCharacter(
                            clips =
                                listOf(
                                    CatalogClip("characters/frog/clips/ribbit.ogg", 2000),
                                    CatalogClip("characters/frog/clips/croak.ogg", 1000),
                                ),
                            haptic = frogHaptic,
                        ),
                    "owl" to
                        CatalogCharacter(
                            clips = listOf(CatalogClip("characters/owl/clips/hoot.ogg", 1000)),
                            haptic = Haptic.Auto,
                        ),
                ),
        ),
) : CharacterCatalog {
    var lookups = 0

    override suspend fun character(
        pack: InstalledPackEntity,
        characterId: String,
    ): CatalogCharacter? {
        lookups++
        return characters[pack.packId]?.get(characterId)
    }
}

/** A mirror that "copies" the clips in [mirrored] to `file:///mirror/…` and records every [retain]. */
internal class FakeMirror(
    private val mirrored: Set<String> = emptySet(),
    private val selfTest: String? = null,
) : ClipMirror {
    val retained = mutableListOf<RoutingTable>()

    override suspend fun clipUri(
        pack: InstalledPackEntity,
        characterId: String,
        file: String,
    ): String =
        if (file in mirrored) {
            "file:///mirror/${pack.packId}/${pack.version}/$file"
        } else {
            ProviderClips.clipUri(pack, characterId, file)
        }

    override suspend fun selfTestUri(): String? = selfTest

    override suspend fun retain(table: RoutingTable) {
        retained += table
    }
}
