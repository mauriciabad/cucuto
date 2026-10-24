package app.mauri.cucuto.core.audio.dsp

import app.mauri.cucuto.core.audio.PcmAudio
import app.mauri.cucuto.packspec.authoring.Authoring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The app's share of the clip pipeline: resampling, waveform peaks and the hand-over to pack-spec's shared `ClipDsp`
 * (whose loudness and silence measures are tested in pack-spec, `LoudnessTest` and `ClipDspTest`).
 */
class DspTest {
    private fun sine(
        rate: Int,
        hz: Double,
        ms: Int,
        amplitude: Double,
    ) = FloatArray(rate * ms / 1000) { (amplitude * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun rmsDb(samples: FloatArray): Double =
        20 * log10(sqrt(samples.sumOf { it.toDouble() * it } / samples.size))

    @Test
    fun `resampling keeps the duration and the tone`() {
        val output = Resampler.resample(sine(44_100, 440.0, 1_000, 0.5), 44_100, 48_000)
        assertEquals(48_000, output.size)
        val middle = output.copyOfRange(4_800, 43_200)
        assertEquals(rmsDb(sine(48_000, 440.0, 1_000, 0.5)), rmsDb(middle), 0.1)
        val ideal = FloatArray(output.size) { (0.5 * sin(2 * PI * 440 * it / 48_000)).toFloat() }
        val error = (4_800 until 43_200).maxOf { abs(output[it] - ideal[it]) }
        assertTrue("max error $error", error < 0.01f)
    }

    @Test
    fun `downsampling filters out what the new rate cannot hold`() {
        // 20 kHz at 48 kHz would alias to 2 kHz at 22.05 kHz; the filter removes it.
        val output = Resampler.resample(sine(48_000, 20_000.0, 1_000, 0.5), 48_000, 22_050)
        assertTrue(rmsDb(output.copyOfRange(2_000, 20_000)) < -50)
    }

    @Test
    fun `the clip pipeline cuts at the source rate, resamples to 48 kHz and runs the shared processing`() {
        val rate = 44_100
        val samples = FloatArray(rate * 3)
        sine(rate, 440.0, 1_000, 0.05).copyInto(samples, destinationOffset = rate) // silence, 1 s of tone, silence
        val source = PcmAudio(PcmAudio.toShorts(samples), rate)

        val clip = ClipProcessor.process(source, ClipEdit(startMs = 500, endMs = 2_800))
        assertEquals(1_040.0, clip.durationMs.toDouble(), 5.0) // the tone plus 20 ms each side
        assertEquals(-16.0, clip.loudnessLufs!!, 0.2)
        assertTrue(clip.gainDb > 0)
        assertEquals(0f, clip.samples.first(), 1e-6f)
        assertEquals(0f, clip.samples.last(), 1e-6f)
        assertEquals(clip.samples.size.toDouble(), clip.durationMs * Authoring.Audio.SAMPLE_RATE_HZ / 1000.0, 48.0)

        val untouched = ClipProcessor.process(source, ClipEdit(500, 2_800, trimSilence = false, normalize = false))
        assertEquals(2_300.0, untouched.durationMs.toDouble(), 1.0)
        assertEquals(0.0, untouched.gainDb, 0.0)

        assertTrue(ClipProcessor.process(source, ClipEdit(0, 900)).isSilent)
    }

    @Test
    fun `waveform peaks follow the loudest sample of each slice`() {
        val samples = ShortArray(1_000)
        samples[250] = 16_384
        samples[900] = (-32_768).toShort()
        val audio = PcmAudio(samples, 8_000)
        assertEquals(listOf(0f, 0.5f, 0f, 1f), Waveform.peaks(audio, 4).toList())
        assertEquals(listOf(0f, 0.5f), Waveform.peaks(audio, 2, from = 0, to = 500).toList())
    }
}
