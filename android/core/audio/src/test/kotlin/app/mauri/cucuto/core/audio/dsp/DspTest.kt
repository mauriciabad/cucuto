package app.mauri.cucuto.core.audio.dsp

import app.mauri.cucuto.core.audio.PcmAudio
import app.mauri.cucuto.packspec.authoring.Authoring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

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
    fun `a 1 kHz sine at -20 dBFS measures -20 LUFS`() {
        // BS.1770's reference: K-weighting is ~0 dB at 1 kHz and a full-scale sine is -3.01 LUFS.
        val amplitude = Math.pow(10.0, -20.0 / 20)
        val lufs = Loudness.integratedLufs(sine(48_000, 1_000.0, 3_000, amplitude))!!
        assertEquals(-23.01, lufs, 0.1)
    }

    @Test
    fun `silence has no loudness and short clips are still measured`() {
        assertNull(Loudness.integratedLufs(FloatArray(48_000)))
        val short = Loudness.integratedLufs(sine(48_000, 1_000.0, 250, 0.1))!!
        assertEquals(-23.01, short, 0.3)
    }

    @Test
    fun `quiet parts below the relative gate do not drag loudness down`() {
        val loud = sine(48_000, 1_000.0, 2_000, 0.1)
        val quiet = sine(48_000, 1_000.0, 2_000, 0.001)
        val both = Loudness.integratedLufs(loud + quiet)!!
        assertEquals(Loudness.integratedLufs(loud)!!, both, 0.5)
    }

    @Test
    fun `true peak finds inter-sample overs`() {
        // A sine at a quarter of the sample rate, phase-shifted 45°: samples sit at 0.707 of the real peak.
        val samples = FloatArray(4_800) { sin(PI / 2 * it + PI / 4).toFloat() * 0.5f }
        val samplePeak = samples.maxOf { abs(it) }
        assertEquals(0.354, samplePeak.toDouble(), 0.01)
        assertEquals(0.5, Loudness.truePeak(samples), 0.03)
    }

    @Test
    fun `normalization reaches -16 LUFS unless the peak ceiling stops it`() {
        val quiet = sine(48_000, 1_000.0, 2_000, 0.01)
        val gain = Loudness.normalizationGainDb(quiet, -16.0, -1.5, 40.0)
        Loudness.applyGain(quiet, gain)
        assertEquals(-16.0, Loudness.integratedLufs(quiet)!!, 0.1)

        // A sine at -16 LUFS peaks around -13 dBFS; a spike near full scale caps the gain instead.
        val spiky = sine(48_000, 1_000.0, 2_000, 0.01).also { it[1_000] = 0.9f }
        val capped = Loudness.normalizationGainDb(spiky, -16.0, -1.5, 40.0)
        Loudness.applyGain(spiky, capped)
        assertTrue(Loudness.truePeakDb(spiky) <= -1.4)
        assertTrue(Loudness.integratedLufs(spiky)!! < -16.5)
    }

    @Test
    fun `gain never exceeds the boost limit`() {
        val whisper = sine(48_000, 1_000.0, 1_000, 0.0005)
        assertEquals(24.0, Loudness.normalizationGainDb(whisper, -16.0, -1.5, 24.0), 1e-9)
    }

    @Test
    fun `resampling keeps the duration and the tone`() {
        val input = sine(44_100, 440.0, 1_000, 0.5)
        val output = Resampler.resample(input, 44_100, 48_000)
        assertEquals(48_000, output.size)
        // Same level (inside the passband), and the right frequency: compare with an ideal 440 Hz sine at 48 kHz.
        val middle = output.copyOfRange(4_800, 43_200)
        assertEquals(rmsDb(sine(48_000, 440.0, 1_000, 0.5)), rmsDb(middle), 0.1)
        val ideal = FloatArray(output.size) { (0.5 * sin(2 * PI * 440 * it / 48_000)).toFloat() }
        val error = (4_800 until 43_200).maxOf { abs(output[it] - ideal[it]) }
        assertTrue("max error $error", error < 0.01f)
    }

    @Test
    fun `downsampling filters out what the new rate cannot hold`() {
        // 20 kHz at 48 kHz would alias to 2 kHz at 22.05 kHz; the filter removes it.
        val input = sine(48_000, 20_000.0, 1_000, 0.5)
        val output = Resampler.resample(input, 48_000, 22_050)
        val middle = output.copyOfRange(2_000, 20_000)
        assertTrue(rmsDb(middle) < -50)
    }

    @Test
    fun `silence trimming keeps 20 ms around the sound`() {
        val rate = 48_000
        val samples = FloatArray(rate) // 1 s
        for (i in 24_000 until 28_800) samples[i] = 0.5f // 100 ms of sound at 500 ms
        val bounds = SilenceTrim.bounds(samples, rate)!!
        assertEquals(24_000 - 960, bounds.first)
        assertEquals(28_799 + 960, bounds.last)
        assertNull(SilenceTrim.bounds(FloatArray(100) { 0.001f }, rate)) // -60 dBFS is silence
    }

    @Test
    fun `the clip pipeline cuts, trims, resamples, normalizes and fades`() {
        val rate = 44_100
        val tone = sine(rate, 440.0, 1_000, 0.05)
        val samples = FloatArray(rate * 3)
        tone.copyInto(samples, destinationOffset = rate) // silence, 1 s of tone, silence
        val source = PcmAudio(PcmAudio.toShorts(samples), rate)

        val clip = ClipProcessor.process(source, ClipEdit(startMs = 500, endMs = 2_800))
        assertEquals(Authoring.Audio.SAMPLE_RATE_HZ, clip.sampleRate)
        assertEquals(1_040.0, clip.durationMs.toDouble(), 5.0) // the tone plus 20 ms each side
        assertEquals(-16.0, clip.loudnessLufs!!, 0.2)
        assertTrue(clip.gainDb > 0)
        assertEquals(0f, clip.samples.first(), 1e-6f)
        assertEquals(0f, clip.samples.last(), 1e-6f)

        val untouched = ClipProcessor.process(source, ClipEdit(500, 2_800, trimSilence = false, normalize = false))
        assertEquals(2_300.0, untouched.durationMs.toDouble(), 1.0)
        assertEquals(0.0, untouched.gainDb, 0.0)

        val silent = ClipProcessor.process(source, ClipEdit(0, 900))
        assertTrue(silent.isSilent)
    }

    @Test
    fun `waveform peaks follow the loudest sample of each slice`() {
        val samples = ShortArray(1_000)
        samples[250] = 16_384
        samples[900] = (-32_768).toShort()
        val peaks = Waveform.peaks(PcmAudio(samples, 8_000), 4)
        assertEquals(listOf(0f, 0.5f, 0f, 1f), peaks.toList())
    }
}
