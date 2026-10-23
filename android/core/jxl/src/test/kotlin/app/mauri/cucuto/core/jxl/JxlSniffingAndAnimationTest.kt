package app.mauri.cucuto.core.jxl

import app.mauri.cucuto.core.jxl.JxlCoilDecoder.Companion.readCapped
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JxlSniffingAndAnimationTest {
    private val codestream = byteArrayOf(0xFF.toByte(), 0x0A, 0x7A, 0x10)
    private val container =
        byteArrayOf(0, 0, 0, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A, 0, 0, 0, 0x14)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun `both JPEG XL signatures are recognised`() {
        assertTrue(JxlImage.isJxl(codestream))
        assertTrue(JxlImage.isJxl(container))
        assertFalse(JxlImage.isJxl(png))
        assertFalse(JxlImage.isJxl(byteArrayOf()))
        assertFalse(JxlImage.isJxl(byteArrayOf(0xFF.toByte())))
        assertFalse(JxlImage.isJxl(container.copyOf(11)))
    }

    @Test
    fun `the Coil factory claims JPEG XL by MIME type or content, without consuming it`() {
        val source = Buffer().write(container)
        assertTrue(JxlCoilDecoder.Factory.isJxl(null, source))
        assertEquals(container.size.toLong(), source.size)
        assertTrue(JxlCoilDecoder.Factory.isJxl(null, Buffer().write(codestream)))
        assertTrue(JxlCoilDecoder.Factory.isJxl("image/jxl", Buffer().write(png)))
        assertFalse(JxlCoilDecoder.Factory.isJxl("image/png", Buffer().write(png)))
        assertFalse(JxlCoilDecoder.Factory.isJxl(null, Buffer()))
    }

    @Test
    fun `sources are read whole up to the cap`() {
        assertArrayEquals(container, Buffer().write(container).readCapped())
        val huge = Buffer().write(ByteArray((JxlCoilDecoder.MAX_FILE_BYTES + 1).toInt()))
        assertThrows(JxlException::class.java) { huge.readCapped() }
    }

    @Test
    fun `loop counts follow JPEG XL semantics`() {
        val forever = LoopCounter(0)
        assertTrue((1..1000).all { forever.repeatAfterLoop() })

        val once = LoopCounter(1)
        assertFalse(once.repeatAfterLoop())

        val thrice = LoopCounter(3)
        assertEquals(listOf(true, true, false), List(3) { thrice.repeatAfterLoop() })
    }

    @Test
    fun `frame delays are clamped so zero-length frames never spin`() {
        assertEquals(MIN_FRAME_MS.toLong(), frameDelayMs(0))
        assertEquals(MIN_FRAME_MS.toLong(), frameDelayMs(3))
        assertEquals(100L, frameDelayMs(100))
    }
}
