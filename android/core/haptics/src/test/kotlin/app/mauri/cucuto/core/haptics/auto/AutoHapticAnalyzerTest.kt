package app.mauri.cucuto.core.haptics.auto

import app.mauri.cucuto.core.haptics.Envelopes
import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.routing.HapticEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AutoHapticAnalyzerTest {
    private val rate = 48_000

    /** Concatenated sine segments of (milliseconds, peak amplitude, frequency). */
    private fun signal(vararg parts: Triple<Int, Double, Double>): FloatArray {
        val out = ArrayList<Float>()
        var n = 0
        for ((ms, amplitude, hz) in parts) {
            repeat(rate * ms / 1000) {
                out += (amplitude * sin(2 * PI * hz * n / rate)).toFloat()
                n++
            }
        }
        return out.toFloatArray()
    }

    private fun envelopeOf(
        samples: FloatArray,
        options: AutoHapticAnalyzer.Options = AutoHapticAnalyzer.Options(),
    ): HapticEvent.Envelope =
        AutoHapticAnalyzer.analyze(samples, rate, options)!!.events.single() as HapticEvent.Envelope

    @Test
    fun silenceHasNoHaptic() {
        assertNull(AutoHapticAnalyzer.analyze(FloatArray(rate), rate))
        assertNull(AutoHapticAnalyzer.analyze(FloatArray(0), rate))
        // Below -60 dBFS is silence too.
        assertNull(AutoHapticAnalyzer.analyze(signal(Triple(500, 0.0005, 440.0)), rate))
    }

    @Test
    fun followsTheVoice() {
        val samples =
            signal(
                Triple(300, 0.0, 0.0),
                Triple(400, 0.8, 200.0),
                Triple(200, 0.08, 200.0),
                Triple(300, 0.8, 3000.0),
                Triple(200, 0.0, 0.0),
            )
        val envelope = envelopeOf(samples)
        // Starts at the first sound, not at the start of the clip.
        assertEquals(300, envelope.atMs)
        assertEquals(0, envelope.points.last().milliIntensity)
        val end = envelope.atMs + envelope.points.sumOf { it.durationMs }
        // Ends shortly after the sound (900 ms of it plus the ramp to zero).
        assertTrue("ends at $end", end in 1200..1260)
        assertTrue(envelope.points.size <= AutoHapticAnalyzer.MAX_CONTROL_POINTS)
        assertTrue(envelope.points.all { it.durationMs >= AutoHapticAnalyzer.FRAME_MS })

        // Loud, quiet (-20 dB, so about half intensity), loud again; the second loud part is sharper.
        val polyline = Envelopes.polyline(envelope)

        fun at(ms: Int) = Envelopes.interpolate(polyline, ms - envelope.atMs)
        val low = (340..680 step 20).map(::at)
        val quiet = (740..880 step 20).map(::at)
        val high = (960..1180 step 20).map(::at)
        assertTrue(low.toString(), low.all { it.intensity >= 950 })
        assertTrue(quiet.toString(), quiet.all { it.intensity in 400..600 })
        assertTrue(high.toString(), high.all { it.intensity >= 950 })
        assertTrue(low.maxOf { it.sharpness } + 400 < high.minOf { it.sharpness })
        // Plateaus spend no points on noise: a handful of corners describe this clip.
        assertTrue(envelope.points.toString(), envelope.points.size <= 10)
    }

    @Test
    fun clampsToTwoSeconds() {
        val samples = signal(Triple(3500, 0.5, 440.0))
        val envelope = envelopeOf(samples)
        assertEquals(0, envelope.atMs)
        val end = envelope.points.sumOf { it.durationMs }
        assertTrue("ends at $end", end <= AutoHapticAnalyzer.MAX_DURATION_MS)
        assertTrue("ends at $end", end >= AutoHapticAnalyzer.MAX_DURATION_MS - 40)
        assertEquals(0, envelope.points.last().milliIntensity)
        // Segments never exceed the guaranteed 1 s.
        assertTrue(envelope.points.all { it.durationMs <= 1000 })
    }

    @Test
    fun soundStartingTooLateHasNoHaptic() {
        val samples = signal(Triple(1990, 0.0, 0.0), Triple(500, 0.8, 440.0))
        assertNull(AutoHapticAnalyzer.analyze(samples, rate))
    }

    @Test
    fun simplifiesToTheDeviceBudget() {
        // A tremolo: 25 loud/quiet alternations, far more corners than points.
        val parts = (0 until 50).map { Triple(40, if (it % 2 == 0) 0.8 else 0.1, 300.0) }.toTypedArray()
        val samples = signal(*parts)
        for (budget in listOf(4, 8, 16)) {
            val options = AutoHapticAnalyzer.Options(maxControlPoints = budget, minControlPointMs = 40)
            val envelope = envelopeOf(samples, options)
            assertTrue("${envelope.points.size} > $budget", envelope.points.size <= budget)
            assertTrue(envelope.points.all { it.durationMs >= 40 })
            assertEquals(0, envelope.points.last().milliIntensity)
        }
    }

    @Test
    fun optionsFollowTheDevice() {
        val options =
            AutoHapticAnalyzer.Options.forDevice(
                HapticCapabilities.GUARANTEED_ENVELOPES.copy(maxControlPoints = 64, minControlPointMs = 10),
            )
        assertEquals(16, options.maxControlPoints)
        assertEquals(20, options.minControlPointMs)
        val small =
            AutoHapticAnalyzer.Options.forDevice(
                HapticCapabilities.GUARANTEED_ENVELOPES.copy(maxControlPoints = 8, minControlPointMs = 30),
            )
        assertEquals(8, small.maxControlPoints)
        assertEquals(30, small.minControlPointMs)
        assertEquals(16, AutoHapticAnalyzer.Options.forDevice(HapticCapabilities()).maxControlPoints)
    }

    @Test
    fun loudnessMapsToIntensityInDecibels() {
        val options = AutoHapticAnalyzer.Options()
        assertEquals(1000, AutoHapticAnalyzer.intensity(0.5, 0.5, options))
        assertEquals(500, AutoHapticAnalyzer.intensity(0.05, 0.5, options))
        assertEquals(0, AutoHapticAnalyzer.intensity(0.005, 0.5, options))
        assertEquals(0, AutoHapticAnalyzer.intensity(0.0005, 0.0006, options))
        assertEquals(0, AutoHapticAnalyzer.intensity(0.0, 0.5, options))
    }

    @Test
    fun brightnessMapsToSharpness() {
        assertEquals(0, AutoHapticAnalyzer.sharpnessForFrequency(50.0))
        assertEquals(0, AutoHapticAnalyzer.sharpnessForFrequency(100.0))
        assertEquals(500, AutoHapticAnalyzer.sharpnessForFrequency(632.46))
        assertEquals(1000, AutoHapticAnalyzer.sharpnessForFrequency(4000.0))
        assertEquals(1000, AutoHapticAnalyzer.sharpnessForFrequency(12_000.0))
    }

    @Test
    fun framesMeasureLoudnessAndZeroCrossings() {
        val frames = AutoHapticAnalyzer.frames(signal(Triple(100, 1.0, 1000.0)), rate)
        assertEquals(5, frames.size)
        for (frame in frames) {
            // A full-scale sine has an RMS of 1/√2 and crosses zero twice per period.
            assertEquals(0.7071, frame.rms, 0.01)
            assertEquals(1000.0, frame.zeroCrossingRate * rate / 2, 60.0)
        }
    }
}
