package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.zip.Crc32
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.zip.CRC32
import kotlin.random.Random

/** The slicing-by-8 CRC-32 (#241) against java.util.zip.CRC32: every length and offset, whole or in pieces. */
class Crc32Test {
    private val data = Random(32).nextBytes(4096)

    private fun reference(offset: Int, length: Int) = CRC32().apply { update(data, offset, length) }.value

    @Test
    fun `matches the JDK for every length and offset`() {
        for (offset in 0 until 9) {
            for (length in 0..300) {
                assertEquals(reference(offset, length), Crc32.of(data, offset, length), "offset $offset, length $length")
            }
        }
        assertEquals(reference(0, data.size), Crc32.of(data))
    }

    @Test
    fun `continues across pieces of any size`() {
        for (piece in listOf(1, 3, 7, 8, 9, 64, 1000)) {
            var crc = 0L
            var at = 0
            while (at < data.size) {
                val n = minOf(piece, data.size - at)
                crc = Crc32.update(crc, data, at, n)
                at += n
            }
            assertEquals(reference(0, data.size), crc, "pieces of $piece")
        }
    }

    @Test
    fun `known value`() {
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray()))
    }
}
