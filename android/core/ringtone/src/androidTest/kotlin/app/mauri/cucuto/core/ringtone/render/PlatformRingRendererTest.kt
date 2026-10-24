package app.mauri.cucuto.core.ringtone.render

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mauri.cucuto.packspec.LoopRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * The platform codec path on a real device (device-testing.md): decode, resample, splice and encode Opus into Ogg.
 * Run with `./gradlew :core:ringtone:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class PlatformRingRendererTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "ring-test").apply { mkdirs() }

    @Test
    fun rendersALoopedRingFileOfTheRightLengthAsOggOpus() {
        // 14 s at 44.1 kHz stereo, loop 4 s to 12 s: 0-4 s once, then 4-12 s eleven times = 92 s.
        val source = wav(File(work, "kettle.wav"), seconds = 14, rate = 44_100, channels = 2)
        val ring = PlatformRingRenderer().render(source, LoopRange(4_000, 12_000), 500, work)
        try {
            assertFalse(ring.copied)
            assertEquals("ogg", ring.extension)
            assertEquals(92_000, ring.plan.totalMs)
            val durationMs = durationUs(ring.file) / 1000
            assertTrue("duration $durationMs ms", durationMs in 91_900..92_100)
        } finally {
            ring.file.delete()
            source.delete()
        }
    }

    @Test
    fun playOnceIsPaddedWithSilenceTo90Seconds() {
        val source = wav(File(work, "once.wav"), seconds = 6, rate = 48_000, channels = 1)
        val ring = PlatformRingRenderer().render(source, null, 0, work)
        try {
            assertEquals(90_000, ring.plan.totalMs)
            assertTrue(durationUs(ring.file) / 1000 in 89_900..90_100)
        } finally {
            ring.file.delete()
            source.delete()
        }
    }

    private fun durationUs(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            return extractor.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION)
        } finally {
            extractor.release()
        }
    }

    /** A 16-bit PCM WAV with a 440 Hz tone. */
    private fun wav(
        file: File,
        seconds: Int,
        rate: Int,
        channels: Int,
    ): File {
        val frames = seconds * rate
        val data = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * 440 * i / rate) * 8000).toInt().toShort()
            repeat(channels) { data.putShort(v) }
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
        header
            .put("fmt ".toByteArray())
            .putInt(16)
            .putShort(1)
            .putShort(channels.toShort())
            .putInt(rate)
        header.putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        header.put("data".toByteArray()).putInt(data.capacity())
        file.outputStream().use {
            it.write(header.array())
            it.write(data.array())
        }
        return file
    }
}
