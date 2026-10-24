package app.mauri.cucuto.screenshots

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Keeps [DemoContent] in step with the demo pack sources in `packs/` (plain JVM, no Robolectric). */
class DemoPacksTest {
    @Test
    fun everySpriteCoverAndClipExists() {
        for (pack in DemoContent.packs) {
            assertExists(pack.cover)
            for (character in pack.characters) {
                assertExists(pack.sprite(character.id))
                assertTrue("${pack.folder}/${character.id} has no clips", pack.clips(character.id).isNotEmpty())
            }
        }
    }

    @Test
    fun namesAndColorsMatchPackYaml() {
        for (pack in DemoContent.packs) {
            val yaml = File(pack.root, "pack.yaml").readText()
            assertTrue("${pack.folder}: id", yaml.contains("id: ${pack.id}\n"))
            assertTrue("${pack.folder}: name", yaml.contains("name: ${pack.name}\n"))
            for (character in pack.characters) {
                val color = "#%06X".format(character.seedColor and 0xFFFFFF)
                assertTrue("${pack.folder}: ${character.name}", yaml.contains("name: ${character.name}\n"))
                assertTrue("${pack.folder}: ${character.name} $color", yaml.contains("seedColor: \"$color\""))
            }
        }
    }

    private fun assertExists(file: File) = assertTrue("missing $file", file.isFile)
}
