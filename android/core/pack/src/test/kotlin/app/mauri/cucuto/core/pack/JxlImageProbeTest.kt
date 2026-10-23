package app.mauri.cucuto.core.pack

import app.mauri.cucuto.core.jxl.JxlColorSpace
import app.mauri.cucuto.core.jxl.JxlException
import app.mauri.cucuto.core.jxl.JxlInfo
import app.mauri.cucuto.core.jxl.JxlTooLargeException
import app.mauri.cucuto.core.jxl.JxlUnavailableException
import app.mauri.cucuto.core.pack.media.ImageProbeResult
import app.mauri.cucuto.core.pack.media.JxlImageProbe
import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.Limits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** How the JPEG XL probe maps decoder outcomes to the importer's results (the decoder itself is tested on device). */
class JxlImageProbeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun probe(verify: (ByteArray, Int) -> JxlInfo): ImageProbeResult {
        val file = tmp.newFile().apply { writeBytes(byteArrayOf(0xFF.toByte(), 0x0A)) }
        return JxlImageProbe(verify).probe(file, FileFormat.JXL)
    }

    @Test
    fun `a decoded image reports its size, checked against the pack limit`() {
        var limit = 0
        val result =
            probe { _, max ->
                limit = max
                JxlInfo(48, 32, true, true, 0, JxlColorSpace.SRGB, 12)
            }
        assertEquals(ImageProbeResult.Decoded(48, 32), result)
        assertEquals(Limits.MAX_BITMAP_SIDE_PX, limit)
    }

    @Test
    fun `an oversized image reports its size, so the importer says IMAGE_TOO_LARGE`() {
        val result = probe { _, max -> throw JxlTooLargeException(4096, 16, max) }
        assertEquals(ImageProbeResult.Decoded(4096, 16), result)
    }

    @Test
    fun `any decoder error is a broken image`() {
        val corrupt = probe { _, _ -> throw JxlException("JPEG XL decode error: invalid signature") }
        assertEquals(ImageProbeResult.Undecodable("JPEG XL decode error: invalid signature"), corrupt)
        val unexpected = probe { _, _ -> throw IllegalStateException() }
        assertEquals(ImageProbeResult.Undecodable("IllegalStateException"), unexpected)
    }

    @Test
    fun `no native decoder on this ABI means not checked`() {
        val result = probe { _, _ -> throw JxlUnavailableException(UnsatisfiedLinkError("no cucuto_jxl")) }
        assertTrue(result.toString(), result is ImageProbeResult.NotChecked)
    }
}
