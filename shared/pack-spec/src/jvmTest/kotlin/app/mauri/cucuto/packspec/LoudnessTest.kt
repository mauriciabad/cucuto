package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.authoring.Loudness
import app.mauri.cucuto.packspec.authoring.SilenceTrim
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** The shared loudness and silence measures against reference signals (ITU-R BS.1770, packer's thresholds). */
class LoudnessTest {
    private fun sine(
        hz: Double,
        ms: Int,
        amplitude: Double,
        rate: Int = 48_000,
    ) = FloatArray(rate * ms / 1000) { (amplitude * sin(2 * PI * hz * it / rate)).toFloat() }

    @Test
    fun `a 1 kHz sine at -20 dBFS measures -23 LUFS`() {
        // BS.1770's reference: K-weighting is about 0 dB at 1 kHz and a full-scale sine is -3.01 LUFS.
        val lufs = Loudness.integratedLufs(sine(1_000.0, 3_000, 10.0.pow(-20.0 / 20)))!!
        assertEquals(-23.01, lufs, 0.1)
    }

    @Test
    fun `silence has no loudness and clips shorter than a gating block are still measured`() {
        assertNull(Loudness.integratedLufs(FloatArray(48_000)))
        assertEquals(-23.01, Loudness.integratedLufs(sine(1_000.0, 250, 0.1))!!, 0.3)
    }

    @Test
    fun `quiet parts below the relative gate do not drag loudness down`() {
        val loud = sine(1_000.0, 2_000, 0.1)
        val both = Loudness.integratedLufs(loud + sine(1_000.0, 2_000, 0.001))!!
        assertEquals(Loudness.integratedLufs(loud)!!, both, 0.5)
    }

    @Test
    fun `true peak finds inter-sample overs`() {
        // A quarter of the sample rate, phase-shifted 45°: the samples sit at 0.707 of the real peak.
        val samples = FloatArray(4_800) { sin(PI / 2 * it + PI / 4).toFloat() * 0.5f }
        assertEquals(0.354, samples.maxOf { abs(it) }.toDouble(), 0.01)
        assertEquals(0.5, Loudness.truePeak(samples), 0.03)
    }

    @Test
    fun `normalization reaches the target unless the peak ceiling or the gain cap stops it`() {
        val quiet = sine(1_000.0, 2_000, 0.01)
        Loudness.applyGain(quiet, Loudness.normalizationGainDb(quiet, -16.0, -1.5, 40.0))
        assertEquals(-16.0, Loudness.integratedLufs(quiet)!!, 0.1)

        val spiky = sine(1_000.0, 2_000, 0.01).also { it[1_000] = 0.9f }
        Loudness.applyGain(spiky, Loudness.normalizationGainDb(spiky, -16.0, -1.5, 40.0))
        assertTrue(Loudness.truePeakDb(spiky) <= -1.4)
        assertTrue(Loudness.integratedLufs(spiky)!! < -16.5)

        assertEquals(24.0, Loudness.normalizationGainDb(sine(1_000.0, 1_000, 0.0005), -16.0, -1.5, 24.0), 1e-9)
    }

    @Test
    fun `silence trimming keeps 20 ms around the sound`() {
        val samples = FloatArray(48_000)
        for (i in 24_000 until 28_800) samples[i] = 0.5f
        val bounds = SilenceTrim.bounds(samples, 48_000)!!
        assertEquals(24_000 - 960, bounds.first)
        assertEquals(28_799 + 960, bounds.last)
        assertNull(SilenceTrim.bounds(FloatArray(100) { 0.001f }, 48_000)) // -60 dBFS is silence
    }
}
