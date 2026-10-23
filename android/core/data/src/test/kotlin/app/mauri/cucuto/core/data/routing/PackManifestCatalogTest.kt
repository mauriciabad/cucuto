package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.haptics.auto.AutoHapticFile
import app.mauri.cucuto.core.haptics.auto.AutoHapticSet
import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticGrid
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun clipsCarryTheirStoredAutoHapticsOnTheGrid() =
        runTest {
            val dir = folder.newFolder("owls")
            dir.resolve("pack.json").writeText(
                """
                {
                  "formatVersion": 1, "id": "app.mauri.demo.owls", "version": "1.0.0", "name": "Owls",
                  "characters": [
                    {
                      "id": "owl", "name": "Owl", "sprite": "s.svg",
                      "clips": [ { "file": "hoot.ogg" }, { "file": "quiet.ogg" }, { "file": "new.ogg" } ]
                    }
                  ]
                }
                """.trimIndent(),
            )
            val catalog = PackManifestCatalog()
            val pack = installedPack(path = dir.path)
            // Not analysed yet: no envelopes.
            assertEquals(listOf(null, null, null), catalog.character(pack, "owl")!!.clips.map { it.autoHaptic })

            val hoot =
                Haptic.Custom(
                    listOf(HapticEvent.Envelope(40, listOf(ControlPoint(700, 333, 25), ControlPoint(0, 0, 20)))),
                )
            AutoHapticFile.write(dir, AutoHapticSet(1, 16, 20, linkedMapOf("hoot.ogg" to hoot, "quiet.ogg" to null)))
            val before = catalog.revisions.value
            catalog.invalidate(dir.path)
            assertTrue(catalog.revisions.value > before)

            val clips = catalog.character(pack, "owl")!!.clips
            assertEquals(HapticGrid.snap(hoot), clips[0].autoHaptic)
            // Silent, and not analysed (a newer pack version or a failed decode).
            assertNull(clips[1].autoHaptic)
            assertNull(clips[2].autoHaptic)
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
