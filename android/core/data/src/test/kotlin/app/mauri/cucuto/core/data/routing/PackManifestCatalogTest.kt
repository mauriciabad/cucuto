package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.routing.Haptic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackManifestCatalogTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun readsCharactersFromTheInstalledManifest() =
        runTest {
            val dir = folder.newFolder("critters")
            dir.resolve("pack.json").writeText(
                """
                {
                  "formatVersion": 1, "id": "app.mauri.demo.critters", "version": "1.0.0", "name": "Critters",
                  "characters": [
                    {
                      "id": "frog", "name": "Frog", "sprite": "characters/frog/sprite.svg",
                      "clips": [
                        { "file": "characters/frog/clips/ribbit.ogg", "label": "Ribbit", "weight": 2 },
                        { "file": "characters/frog/clips/croak.ogg", "weight": 0.25 }
                      ],
                      "haptic": { "mode": "none" }
                    },
                    { "id": "song", "name": "Song", "sprite": "s.png", "ringtones": [{ "file": "r.ogg" }] }
                  ]
                }
                """.trimIndent(),
            )
            val catalog = PackManifestCatalog()
            val pack = installedPack(path = dir.path)

            val frog = catalog.character(pack, "frog")!!
            assertEquals(listOf(2000, 250), frog.clips.map { it.milliWeight })
            assertEquals("characters/frog/clips/ribbit.ogg", frog.clips[0].file)
            assertEquals(Haptic.Keep, frog.haptic)

            val song = catalog.character(pack, "song")!!
            assertEquals(0, song.clips.size)
            assertEquals(Haptic.Auto, song.haptic)

            assertNull(catalog.character(pack, "missing"))
        }

    @Test
    fun aMissingOrBrokenManifestHasNoCharacters() =
        runTest {
            val catalog = PackManifestCatalog()
            assertNull(catalog.character(installedPack(path = folder.root.resolve("nope").path), "frog"))
            val broken = folder.newFolder("broken")
            broken.resolve("pack.json").writeText("{ not json")
            assertNull(catalog.character(installedPack(path = broken.path), "frog"))
        }
}
