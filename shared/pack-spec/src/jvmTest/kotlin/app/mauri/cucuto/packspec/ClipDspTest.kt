package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.authoring.Authoring
import app.mauri.cucuto.packspec.authoring.ClipDsp
import app.mauri.cucuto.packspec.authoring.Loudness
import app.mauri.cucuto.packspec.authoring.SilenceTrim
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

/** The creators' shared clip pipeline (#47, #48); the website runs the same code through the browser build. */
class ClipDspTest {
    private val rate = Authoring.Audio.SAMPLE_RATE_HZ

    /** [silenceMs] of silence, [toneMs] of a 1 kHz tone at [amplitude], [silenceMs] of silence. */
    private fun tone(amplitude: Float, toneMs: Int = 1000, silenceMs: Int = 500): FloatArray {
        val pad = rate * silenceMs / 1000
        val length = rate * toneMs / 1000
        return FloatArray(pad * 2 + length) { i ->
            if (i in pad until pad + length) amplitude * sin(2 * PI * 1000 * i / rate).toFloat() else 0f
        }
    }

    @Test
    fun `trims silence, normalizes to -16 LUFS and fades the edges`() {
        val source = tone(0.05f)
        val clip = ClipDsp.process(source, 0, 2000)
        assertTrue(clip.durationMs in 1000L..1050L, "duration ${clip.durationMs}")
        assertEquals(-16.0, clip.loudnessLufs!!, 0.5)
        assertTrue(clip.gainDb > 0)
        assertEquals(0f, clip.samples.first())
        assertEquals(0f, clip.samples.last())
        assertEquals(0f, source[0]) // the source is untouched
    }

    @Test
    fun `stays under the true-peak ceiling`() {
        val clip = ClipDsp.process(tone(0.99f), 0, 2000)
        assertTrue(Loudness.truePeakDb(clip.samples) <= Authoring.Audio.TRUE_PEAK_CEILING_DBTP + 0.1)
    }

    @Test
    fun `keeps the cut as is without trimming or normalizing`() {
        val clip = ClipDsp.process(tone(0.05f), 250, 1750, trimSilence = false, normalize = false)
        assertEquals(1500L, clip.durationMs)
        assertEquals(0.0, clip.gainDb)
    }

    @Test
    fun `reports silence`() {
        val clip = ClipDsp.process(FloatArray(rate), 0, 1000)
        assertTrue(clip.isSilent)
        assertNull(SilenceTrim.bounds(FloatArray(rate), rate))
    }
}
