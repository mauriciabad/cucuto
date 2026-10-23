package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.zip.CommonInflater
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.zip.Deflater
import kotlin.random.Random

/** The common-code inflater (used by the browser build) must agree with java.util.zip on every kind of block. */
class CommonInflaterTest {
    private fun deflate(data: ByteArray, level: Int, strategy: Int = Deflater.DEFAULT_STRATEGY): ByteArray {
        val d = Deflater(level, true)
        d.setStrategy(strategy)
        d.setInput(data)
        d.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun samples(): List<ByteArray> {
        val random = Random(116)
        val text = "pack.json characters clips ringtones haptic ".repeat(4000).encodeToByteArray()
        val noise = ByteArray(300_000).also(random::nextBytes)
        val mixed = ByteArray(200_000) { i -> if (i % 7 == 0) random.nextInt().toByte() else (i / 13).toByte() }
        return listOf(ByteArray(0), byteArrayOf(42), "a".repeat(70_000).encodeToByteArray(), text, noise, mixed)
    }

    @Test
    fun `agrees with java util zip for stored, fixed and dynamic blocks`() {
        val settings = listOf(
            Deflater.NO_COMPRESSION to Deflater.DEFAULT_STRATEGY, // stored blocks
            Deflater.BEST_SPEED to Deflater.DEFAULT_STRATEGY,
            Deflater.DEFAULT_COMPRESSION to Deflater.DEFAULT_STRATEGY,
            Deflater.BEST_COMPRESSION to Deflater.FILTERED,
            Deflater.DEFAULT_COMPRESSION to Deflater.HUFFMAN_ONLY,
        )
        for (data in samples()) {
            for ((level, strategy) in settings) {
                val compressed = deflate(data, level, strategy)
                val expected = JvmPackIo.inflater.inflate(compressed, data.size)
                assertArrayEquals(data, expected)
                assertArrayEquals(data, CommonInflater.inflate(compressed, data.size), "level $level strategy $strategy size ${data.size}")
            }
        }
    }

    @Test
    fun `rejects a wrong size, truncated data and garbage`() {
        val data = "a sound pack ".repeat(1000).encodeToByteArray()
        val compressed = deflate(data, Deflater.DEFAULT_COMPRESSION)
        assertNull(CommonInflater.inflate(compressed, data.size - 1))
        assertNull(CommonInflater.inflate(compressed, data.size + 1))
        assertNull(CommonInflater.inflate(compressed.copyOf(compressed.size / 2), data.size))
        assertNull(CommonInflater.inflate(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), 10))
    }
}
