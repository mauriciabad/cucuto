package app.mauri.cucuto.voice

import app.mauri.cucuto.ui.components.initialOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.random.Random

class VoicePreviewTest {
    private val clips = listOf(File("a.ogg"), File("b.ogg"), File("c.ogg"))

    @Test
    fun neverPicksTheSameClipTwiceInARow() {
        val random = Random(7)
        var last: File? = null
        repeat(200) {
            val next = pickClip(clips, last, random)!!
            assertNotEquals(last, next)
            last = next
        }
    }

    @Test
    fun aSingleClipRepeatsAndNoClipsPlayNothing() {
        assertEquals(File("a.ogg"), pickClip(listOf(File("a.ogg")), File("a.ogg")))
        assertNull(pickClip(emptyList(), null))
    }

    @Test
    fun initialsTakeTheFirstCharacterUppercased() {
        assertEquals("A", initialOf(" ada "))
        assertEquals("É", initialOf("émile"))
        assertEquals("🐸", initialOf("🐸 frogs"))
        assertNull(initialOf("  "))
        assertNull(initialOf(null))
    }
}
