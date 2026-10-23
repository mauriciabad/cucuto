package app.mauri.cucuto.core.jxl

import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JxlImageTest {
    private val data = byteArrayOf(0xFF.toByte(), 0x0A, 1, 2, 3)

    private fun open(
        native: FakeNative,
        maxSide: Int = JxlImage.DEFAULT_MAX_SIDE,
    ) = JxlImage.open(native, data, maxSide, fakeBitmaps)

    @Test
    fun `header values are parsed`() {
        val info = JxlInfo.fromNative(intArrayOf(512, 256, 1, 1, 3, 3, 12))
        assertEquals(JxlInfo(512, 256, true, true, 3, JxlColorSpace.DISPLAY_P3, 12), info)
        val oversized = JxlInfo.fromNative(intArrayOf(4000, 16, 0, 0, 0, 1, -1))
        assertNull(oversized.frameCount)
        assertEquals(JxlColorSpace.SRGB, oversized.colorSpace)
        assertThrows(IllegalArgumentException::class.java) { JxlInfo.fromNative(intArrayOf(1, 2, 3)) }
    }

    @Test
    fun `unknown color codes are unknown`() {
        assertEquals(JxlColorSpace.UNKNOWN, JxlColorSpace.fromCode(0))
        assertEquals(JxlColorSpace.UNKNOWN, JxlColorSpace.fromCode(99))
        assertEquals(JxlColorSpace.entries, JxlColorSpace.entries.map { JxlColorSpace.fromCode(it.code) })
    }

    @Test
    fun `oversized images are rejected from the header, before the decoder opens`() {
        val native = FakeNative(width = 513, height = 16)
        val e = assertThrows(JxlTooLargeException::class.java) { open(native) }
        assertEquals(513, e.width)
        assertEquals(16, e.height)
        assertEquals(512, e.maxSide)
        assertEquals(listOf("readInfo"), native.calls)
        // readInfo still reports the size of an oversized image, without counting frames.
        val info = JxlImage.readInfo(native, data, 512)
        assertEquals(513 to 16, info.width to info.height)
        assertNull(info.frameCount)
    }

    @Test
    fun `512 px per side is allowed and the limit is configurable`() {
        open(FakeNative(width = 512, height = 512)).close()
        assertThrows(JxlTooLargeException::class.java) { open(FakeNative(width = 100, height = 100), maxSide = 64) }
        assertThrows(IllegalArgumentException::class.java) { open(FakeNative(), maxSide = 0) }
        assertThrows(IllegalArgumentException::class.java) { open(FakeNative(), maxSide = JxlImage.HARD_MAX_SIDE + 1) }
    }

    @Test
    fun `a corrupt header is a JxlException`() {
        val native = FakeNative(badHeader = true)
        assertThrows(JxlException::class.java) { open(native) }
        assertEquals(0, native.openHandles)
    }

    @Test
    fun `frames are decoded in order until the end`() {
        val native = FakeNative(frames = listOf(100, 50, 70))
        open(native).use { image ->
            val bitmap = image.newBitmap()
            assertEquals(listOf(100, 50, 70, null), List(4) { image.decodeNextFrame(bitmap) })
            image.rewind()
            assertEquals(100, image.decodeNextFrame(bitmap))
        }
    }

    @Test
    fun `forEachFrame visits every frame`() =
        runTest {
            val native = FakeNative(frames = listOf(100, 50, 70))
            val seen = mutableListOf<Pair<Int, Int>>()
            val count = open(native).use { it.forEachFrame { f -> seen += f.index to f.durationMs } }
            assertEquals(3, count)
            assertEquals(listOf(0 to 100, 1 to 50, 2 to 70), seen)
        }

    @Test
    fun `forEachFrame stops at cancellation, between frames`() =
        runTest {
            val native = FakeNative(frames = List(10) { 40 })
            val image = open(native)
            var frames = 0
            val job =
                launch {
                    image.forEachFrame {
                        frames++
                        if (frames == 2) currentCoroutineContext().cancel()
                    }
                }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(2, frames)
            assertEquals(2, native.calls.count { it == "nextFrame" })
            image.close()
        }

    @Test
    fun `close is idempotent and frees the decoder once`() {
        val native = FakeNative()
        val image = open(native)
        assertFalse(image.isClosed)
        image.close()
        image.close()
        assertTrue(image.isClosed)
        assertEquals(1, native.calls.count { it == "close" })
        assertEquals(0, native.openHandles)
        assertThrows(IllegalStateException::class.java) { image.decodeNextFrame(image.newBitmap()) }
        assertThrows(IllegalStateException::class.java) { image.rewind() }
    }

    @Test
    fun `verify decodes every frame and closes the decoder`() {
        val native = FakeNative(frames = listOf(10, 20, 30, 40))
        val info = JxlImage.verify(native, data, 512, fakeBitmaps)
        assertEquals(4, info.frameCount)
        assertEquals(5, native.calls.count { it == "nextFrame" })
        assertEquals(0, native.openHandles)
    }

    @Test
    fun `verify fails on a broken frame and still closes the decoder`() {
        val native = FakeNative(frames = listOf(10, 20, 30), failAtFrame = 2)
        assertThrows(JxlException::class.java) { JxlImage.verify(native, data, 512, fakeBitmaps) }
        assertEquals(0, native.openHandles)
    }

    @Test
    fun `verify rejects oversized images without decoding`() {
        val native = FakeNative(width = 600, height = 600)
        val e = assertThrows(JxlTooLargeException::class.java) { JxlImage.verify(native, data, 512, fakeBitmaps) }
        assertEquals(600, e.width)
        assertFalse("nextFrame" in native.calls)
    }
}
