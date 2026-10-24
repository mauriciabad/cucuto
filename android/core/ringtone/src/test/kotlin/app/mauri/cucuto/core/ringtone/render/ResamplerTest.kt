package app.mauri.cucuto.core.ringtone.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ResamplerTest {
    @Test
    fun equalRatesPassThroughUntouched() {
        val input = FloatArray(1000) { (it % 17) / 17f }
        val out = run(Resampler(48_000, 48_000, 1), input, chunk = 333)
        assertArrayEquals(input, out, 0f)
    }

    @Test
    fun outputLengthFollowsTheRateRatio() {
        for ((rate, frames) in listOf(
            44_100 to 44_100,
            44_100 to 12_345,
            22_050 to 1000,
            96_000 to 96_001,
            32_000 to 7,
        )) {
            val out = run(Resampler(rate, 48_000, 2), FloatArray(frames * 2), chunk = 512, channels = 2)
            assertEquals("$rate Hz, $frames frames", frames.toLong() * 48_000 / rate * 2, out.size.toLong())
        }
    }

    @Test
    fun aToneKeepsItsPitchAndLevelFrom44100() {
        val inRate = 44_100
        val freq = 1000.0
        val input = FloatArray(inRate) { (0.5 * sin(2 * PI * freq * it / inRate)).toFloat() }
        val out = run(Resampler(inRate, 48_000, 1), input, chunk = 1024)
        // Skip the edges (the filter's ramp-in and ramp-out), compare against the ideal tone at 48 kHz.
        var worst = 0.0
        for (n in 1000 until out.size - 1000) {
            val ideal = 0.5 * sin(2 * PI * freq * n / 48_000)
            worst = maxOf(worst, abs(out[n] - ideal))
        }
        assertTrue("worst error $worst", worst < 0.005)
    }

    @Test
    fun downsamplingFiltersAboveTheNewNyquist() {
        val inRate = 96_000
        // 30 kHz is above 24 kHz (48 kHz's Nyquist): it must not fold back as an audible tone.
        val input = FloatArray(inRate) { (0.5 * sin(2 * PI * 30_000 * it / inRate)).toFloat() }
        val out = run(Resampler(inRate, 48_000, 1), input, chunk = 4096)
        val peak = (1000 until out.size - 1000).maxOf { abs(out[it]) }
        assertTrue("alias peak $peak", peak < 0.01)
    }

    @Test
    fun stereoChannelsStaySeparate() {
        val input = FloatArray(2 * 4410) { if (it % 2 == 0) 0.25f else -0.25f }
        val out = run(Resampler(44_100, 48_000, 2), input, chunk = 100, channels = 2)
        for (n in 200 until out.size / 2 - 200) {
            assertEquals(0.25f, out[2 * n], 1e-3f)
            assertEquals(-0.25f, out[2 * n + 1], 1e-3f)
        }
    }

    private fun run(
        resampler: Resampler,
        input: FloatArray,
        chunk: Int,
        channels: Int = 1,
    ): FloatArray {
        val out = ArrayList<Float>()
        val sink = { samples: FloatArray, frames: Int -> for (i in 0 until frames * channels) out += samples[i] }
        var at = 0
        while (at < input.size / channels) {
            val n = minOf(chunk, input.size / channels - at)
            resampler.process(input.copyOfRange(at * channels, (at + n) * channels), n, sink)
            at += n
        }
        resampler.finish(sink)
        return out.toFloatArray()
    }
}
