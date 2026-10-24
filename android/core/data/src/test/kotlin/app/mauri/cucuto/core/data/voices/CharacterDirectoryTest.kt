package app.mauri.cucuto.core.data.voices

import app.mauri.cucuto.core.data.routing.CatalogCharacter
import app.mauri.cucuto.core.data.routing.CatalogClip
import app.mauri.cucuto.core.data.routing.installedPack
import app.mauri.cucuto.core.routing.Haptic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class CharacterDirectoryTest {
    @Test
    fun resolvesSpritesAndClipsInsideThePackFolder() {
        val pack = installedPack(path = "/packs/critters/1.0.0")
        val voices =
            InstalledCharacterDirectory.voicePack(
                pack,
                linkedMapOf(
                    "frog" to
                        CatalogCharacter(
                            clips =
                                listOf(
                                    CatalogClip("characters/frog/clips/ribbit.ogg", 1000),
                                    CatalogClip("../../escape.ogg", 1000),
                                ),
                            haptic = Haptic.Keep,
                            name = "Bleep the frog",
                            sprite = "characters/frog/sprite.svg",
                            seedColor = "#4CAF50",
                        ),
                    "owl" to CatalogCharacter(clips = emptyList(), haptic = Haptic.Auto, sprite = "/etc/passwd"),
                ),
            )

        assertEquals(pack.packId, voices.packId)
        val (frog, owl) = voices.characters
        assertEquals("Bleep the frog", frog.name)
        assertEquals(File("/packs/critters/1.0.0/characters/frog/sprite.svg"), frog.sprite)
        assertEquals(listOf(File("/packs/critters/1.0.0/characters/frog/clips/ribbit.ogg")), frog.clips)
        // Pack paths stay index for index with the files, so a dropped clip never shifts the toggles.
        assertEquals(listOf("characters/frog/clips/ribbit.ogg"), frog.clipPaths)
        assertEquals(0xFF4CAF50.toInt(), frog.seedColor)
        assertEquals("owl", owl.name)
        assertNull(owl.sprite)
        assertNull(owl.seedColor)
    }

    @Test
    fun seedColorsMustBeHashAndSixHexDigits() {
        assertEquals(0xFF000000.toInt(), InstalledCharacterDirectory.parseSeedColor("#000000"))
        assertEquals(0xFFFFFFFF.toInt(), InstalledCharacterDirectory.parseSeedColor("#ffffff"))
        assertNull(InstalledCharacterDirectory.parseSeedColor("4CAF50"))
        assertNull(InstalledCharacterDirectory.parseSeedColor("#4CAF5"))
        assertNull(InstalledCharacterDirectory.parseSeedColor("#GGGGGG"))
    }
}
