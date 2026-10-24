package app.mauri.cucuto.core.routing

import app.mauri.cucuto.core.routing.RoutingFixtures.sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingFileTest {
    private val encoded = RoutingCodec.encode(sample)

    @Test
    fun roundTrips() {
        assertEquals(encoded, RoutingFile.unframe(RoutingFile.frame(encoded)))
        assertEquals("", RoutingFile.unframe(RoutingFile.frame("")))
        // Non-ASCII display data survives (UTF-8 length, not char count).
        assertEquals("héllo ñ 🐸", RoutingFile.unframe(RoutingFile.frame("héllo ñ 🐸")))
    }

    @Test
    fun rejectsATornRead() {
        // Two tables of the same length that differ after the middle (another clip in the last route).
        val old = RoutingFile.frame(encoded.replace("clip-3.ogg", "clip-4.ogg"))
        val new = RoutingFile.frame(encoded.replace("clip-3.ogg", "clip-5.ogg"))
        assertEquals(old.size, new.size)
        // The app rewrites in place: a reader may see the new start, then the old rest, or a cut file.
        val mixed = new.copyOf(new.size / 10) + old.copyOfRange(new.size / 10, old.size)
        assertNull(RoutingFile.unframe(mixed))
        assertNull(RoutingFile.unframe(new.copyOf(new.size - 1)))
        assertNull(RoutingFile.unframe(new + byteArrayOf(0)))
    }

    @Test
    fun rejectsCorruptOrForeignFiles() {
        val framed = RoutingFile.frame(encoded)
        val flipped = framed.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        assertNull(RoutingFile.unframe(flipped))
        assertNull(RoutingFile.unframe(ByteArray(0)))
        assertNull(RoutingFile.unframe(encoded.toByteArray()))
        assertNull(RoutingFile.unframe("cucuto-routing-file 2 0 0\n".toByteArray()))
        assertNull(RoutingFile.unframe("cucuto-routing-file 1 x 0\n".toByteArray()))
        assertNull(RoutingFile.unframe(ByteArray(RoutingFile.MAX_BYTES + 1)))
    }

    @Test
    fun acceptsATableAtTheLimit() {
        // Every released module (0.1.1 on) rejects files over 1,048,640 bytes: the limit must never shrink.
        assertEquals(1_048_640, RoutingFile.MAX_BYTES)
        val atLimit = "x".repeat(RoutingContract.MAX_TABLE_BYTES)
        assertTrue(RoutingFile.fits(atLimit))
        assertFalse(RoutingFile.fits(atLimit + "x"))
        // The header is at most 41 bytes, so a table that fits always frames under the module's limit.
        assertEquals(atLimit, RoutingFile.unframe(RoutingFile.frame(atLimit)))
    }

    @Test
    fun measuresUtf8WithoutEncoding() {
        for (text in listOf("", "cucuto", "héllo ñ", "🐸 frog", "aࠀb", "lone \uD83D surrogate")) {
            assertEquals(text, text.toByteArray(Charsets.UTF_8).size, RoutingFile.utf8Length(text))
        }
        assertFalse(RoutingFile.fits("é".repeat(RoutingContract.MAX_TABLE_BYTES / 2 + 1)))
    }
}
