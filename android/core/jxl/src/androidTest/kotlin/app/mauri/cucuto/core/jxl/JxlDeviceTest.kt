package app.mauri.cucuto.core.jxl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.DrawableImage
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.random.Random

/**
 * The real decoder (libcucuto_jxl.so) on the device, on the images of generate-test-images.main.kts: lossless output
 * matches its PNG source pixel for pixel, lossy output matches libjxl, animations coalesce to their source frames,
 * and corrupt, truncated or oversized files only ever produce a [JxlException].
 */
@RunWith(AndroidJUnit4::class)
class JxlDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().context

    private fun bytes(name: String): ByteArray = context.assets.open("jxl/$name").use { it.readBytes() }

    private fun png(name: String): Bitmap =
        checkNotNull(
            BitmapFactory.decodeByteArray(
                bytes(name),
                0,
                bytes(name).size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            ),
        )

    private fun Bitmap.pixels(): IntArray =
        IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private fun assertSamePixels(
        message: String,
        expected: Bitmap,
        actual: Bitmap,
    ) {
        assertEquals("$message: size", expected.width to expected.height, actual.width to actual.height)
        val e = expected.pixels()
        val a = actual.pixels()
        val diff = e.indices.firstOrNull { e[it] != a[it] }
        assertNull(
            "$message: first difference at $diff (${diff?.let { "%08x vs %08x".format(e[it], a[it]) }})",
            diff,
        )
    }

    /** Largest difference of any channel, 0–255. */
    private fun maxChannelDiff(
        expected: Bitmap,
        actual: Bitmap,
    ): Int {
        val e = expected.pixels()
        val a = actual.pixels()
        return e.indices.maxOf { i ->
            (0 until 32 step 8).maxOf { s -> abs((e[i] shr s and 0xFF) - (a[i] shr s and 0xFF)) }
        }
    }

    @Test
    fun readsHeadersWithoutDecoding() {
        assertEquals(
            JxlInfo(64, 64, true, false, 0, JxlColorSpace.SRGB, 1),
            JxlImage.readInfo(bytes("sprite.jxl")),
        )
        assertEquals(
            JxlInfo(128, 128, false, false, 0, JxlColorSpace.SRGB, 1),
            JxlImage.readInfo(bytes("photo.jxl")),
        )
        assertEquals(
            JxlInfo(32, 32, true, true, 0, JxlColorSpace.SRGB, 8),
            JxlImage.readInfo(bytes("anim.jxl")),
        )
        assertEquals(JxlColorSpace.DISPLAY_P3, JxlImage.readInfo(bytes("p3.jxl")).colorSpace)
    }

    @Test
    fun losslessMatchesThePngSourcePixelForPixel() {
        val source = png("sprite.png")
        for (name in listOf("sprite.jxl", "sprite-e7.jxl")) {
            val bitmap = JxlImage.decodeBitmap(bytes(name))
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
            assertTrue(bitmap.hasAlpha())
            assertSamePixels(name, source, bitmap)
        }
    }

    @Test
    fun lossyMatchesLibjxl() {
        val bitmap = JxlImage.decodeBitmap(bytes("photo.jxl"))
        assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), bitmap.colorSpace)
        val diff = maxChannelDiff(png("photo-djxl.png"), bitmap)
        assertTrue("lossy output differs from djxl by $diff/255", diff <= 3)
    }

    @Test
    fun displayP3BitmapsAreTagged() {
        val bitmap = JxlImage.decodeBitmap(bytes("p3.jxl"))
        assertEquals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), bitmap.colorSpace)
    }

    @Test
    fun animationFramesCoalesceToTheirSources() =
        runTest {
            JxlImage.open(bytes("anim.jxl")).use { image ->
                val durations = mutableListOf<Int>()
                image.forEachFrame { frame ->
                    durations += frame.durationMs
                    assertSamePixels("frame ${frame.index}", png("anim-${frame.index}.png"), frame.bitmap)
                }
                assertEquals(List(8) { 100 }, durations)
                image.rewind()
                val first = image.newBitmap()
                assertEquals(100, image.decodeNextFrame(first))
                assertSamePixels("frame 0 after rewind", png("anim-0.png"), first)
            }
        }

    @Test
    fun oversizedImagesAreRejectedFromTheHeader() {
        val oversized = bytes("oversized.jxl")
        val info = JxlImage.readInfo(oversized)
        assertEquals(600 to 600, info.width to info.height)
        assertNull(info.frameCount)
        val e = assertThrows(JxlTooLargeException::class.java) { JxlImage.open(oversized) }
        assertEquals(600, e.width)
        assertThrows(JxlTooLargeException::class.java) { JxlImage.verify(oversized) }
        // A larger limit lets it through.
        assertEquals(600, JxlImage.decodeBitmap(oversized, maxSide = 600).width)

        // Over the decoder's own hard limit: the native side refuses whatever Kotlin asks for.
        val huge = bytes("huge.jxl")
        assertEquals(4096, JxlImage.readInfo(huge, maxSide = JxlImage.HARD_MAX_SIDE).width)
        assertThrows(JxlTooLargeException::class.java) { JxlImage.open(huge, maxSide = JxlImage.HARD_MAX_SIDE) }
        assertThrows(JxlException::class.java) { JxlJni.open(huge, 100_000) }
    }

    @Test
    fun wrongTargetBitmapsAreErrors() {
        JxlImage.open(bytes("sprite.jxl")).use { image ->
            val small = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            assertThrows(JxlException::class.java) { image.decodeNextFrame(small) }
            val rgb565 = Bitmap.createBitmap(64, 64, Bitmap.Config.RGB_565)
            assertThrows(JxlException::class.java) { image.decodeNextFrame(rgb565) }
        }
    }

    @Test
    fun corruptAndTruncatedFilesNeverCrash() {
        val random = Random(77)
        var decoded = 0
        var rejected = 0

        fun attempt(data: ByteArray) {
            try {
                JxlImage.verify(data, maxSide = JxlImage.HARD_MAX_SIDE)
                decoded++
            } catch (e: JxlException) {
                rejected++
            }
        }
        listOf(byteArrayOf(), byteArrayOf(0xFF.toByte(), 0x0A), bytes("sprite.png"), random.nextBytes(4096))
            .forEach(::attempt)
        for (name in listOf("sprite.jxl", "sprite-e7.jxl", "photo.jxl", "anim.jxl", "p3.jxl")) {
            val valid = bytes(name)
            // Every truncation (every 4th byte for the larger photo).
            val step = if (valid.size > 1000) 4 else 1
            for (length in 0 until valid.size step step) attempt(valid.copyOf(length))
            // Random bit flips, byte overwrites and spliced garbage.
            repeat(300) {
                val m = valid.copyOf()
                val at = random.nextInt(m.size)
                when (it % 3) {
                    0 -> m[at] = (m[at].toInt() xor (1 shl random.nextInt(8))).toByte()
                    1 -> repeat(4) { m[random.nextInt(m.size)] = random.nextInt(256).toByte() }
                    else -> random.nextBytes(m, at, minOf(m.size, at + 16))
                }
                attempt(m)
            }
        }
        assertTrue("decoded $decoded, rejected $rejected", rejected > 1000)
    }

    @Test
    fun coilDecodesStillAndAnimatedImages() =
        runBlocking {
            val loader =
                ImageLoader
                    .Builder(context)
                    .components { add(JxlCoilDecoder.Factory()) }
                    .build()

            fun request(data: ByteArray) =
                ImageRequest
                    .Builder(context)
                    .data(data)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build()

            val still = loader.execute(request(bytes("sprite.jxl")))
            assertTrue(still.toString(), still is SuccessResult)
            val bitmap = ((still as SuccessResult).image as BitmapImage).bitmap
            assertSamePixels("Coil still", png("sprite.png"), bitmap)

            val animated = loader.execute(request(bytes("anim.jxl")))
            assertTrue(animated.toString(), animated is SuccessResult)
            val drawable = ((animated as SuccessResult).image as DrawableImage).drawable as JxlAnimatedDrawable
            assertEquals(32, drawable.intrinsicWidth)
            assertEquals(8, drawable.info.frameCount)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync { drawable.start() }
            Thread.sleep(350)
            instrumentation.runOnMainSync {
                assertTrue(drawable.isRunning)
                drawable.stop()
                assertFalse(drawable.isRunning)
                drawable.start() // Reopens the decoder from the bytes.
                assertTrue(drawable.isRunning)
            }
            Thread.sleep(250)
            instrumentation.runOnMainSync { drawable.stop() }

            val broken = loader.execute(request(bytes("photo.jxl").copyOf(500)))
            assertTrue(broken.toString(), broken is ErrorResult)
            assertTrue((broken as ErrorResult).throwable.toString(), broken.throwable is JxlException)

            val tooLarge = loader.execute(request(bytes("oversized.jxl")))
            assertTrue((tooLarge as ErrorResult).throwable.toString(), tooLarge.throwable is JxlTooLargeException)
        }
}
