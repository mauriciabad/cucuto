package app.mauri.cucuto.core.haptics

import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.HapticEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopesTest {
    private fun p(
        t: Int,
        i: Int,
        s: Int = 500,
    ) = EnvelopePoint(t, i, s)

    @Test
    fun polylineAndEventRoundTrip() {
        val event =
            HapticEvent.Envelope(
                atMs = 220,
                points = listOf(ControlPoint(1000, 800, 120), ControlPoint(0, 400, 180)),
                milliInitialSharpness = 300,
            )
        val polyline = Envelopes.polyline(event)
        assertEquals(listOf(p(0, 0, 300), p(120, 1000, 800), p(300, 0, 400)), polyline)
        assertEquals(event, Envelopes.toEvent(220, polyline))

        // The initial sharpness defaults to the first point's, and is omitted again when implied.
        val implied = HapticEvent.Envelope(0, listOf(ControlPoint(700, 600, 50), ControlPoint(0, 600, 50)))
        assertEquals(p(0, 0, 600), Envelopes.polyline(implied)[0])
        assertEquals(implied, Envelopes.toEvent(0, Envelopes.polyline(implied)))
    }

    @Test
    fun polylineClampsValuesAndDurations() {
        val event = HapticEvent.Envelope(0, listOf(ControlPoint(1500, -3, 0), ControlPoint(-1, 2000, -5)), 1200)
        assertEquals(listOf(p(0, 0, 1000), p(1, 1000, 0), p(2, 0, 1000)), Envelopes.polyline(event))
    }

    @Test
    fun endsAtZero() {
        val open = listOf(p(0, 0), p(100, 800))
        assertEquals(open + p(120, 0), Envelopes.endAtZero(open, 20))
        val closed = open + p(150, 0)
        assertEquals(closed, Envelopes.endAtZero(closed, 20))
    }

    @Test
    fun truncatesWithARampToZero() {
        val long = listOf(p(0, 0), p(1000, 1000), p(3000, 1000), p(3100, 0))
        val cut = Envelopes.truncate(long, maxDurationMs = 2000, rampMs = 20)
        assertEquals(listOf(p(0, 0), p(1000, 1000), p(1980, 1000), p(2000, 0)), cut)
        assertEquals(long, Envelopes.truncate(long, 5000, 20))
    }

    @Test
    fun simplifyKeepsCornersFirst() {
        // A ramp up, a plateau with tiny ripples, a drop: the corners matter, the ripples do not.
        val points =
            listOf(p(0, 0), p(20, 500), p(40, 1000), p(60, 990), p(80, 1000), p(100, 995), p(120, 1000), p(140, 0))
        val simplified = Envelopes.simplify(points, maxPoints = 4, minGapMs = 1)
        assertEquals(listOf(p(0, 0), p(40, 1000), p(120, 1000), p(140, 0)), simplified)
        // Collinear points are dropped even with budget to spare.
        val line = listOf(p(0, 0), p(10, 100), p(20, 200), p(30, 300))
        assertEquals(listOf(p(0, 0), p(30, 300)), Envelopes.simplify(line, 10, 1))
    }

    @Test
    fun simplifyRespectsTheMinimumGap() {
        val points = listOf(p(0, 0), p(5, 1000), p(30, 1000), p(35, 0))
        val simplified = Envelopes.simplify(points, maxPoints = 10, minGapMs = 20)
        for (i in 1 until simplified.size) {
            assertTrue(
                simplified.toString(),
                simplified[i].timeMs - simplified[i - 1].timeMs >= 20 || simplified.size == 2,
            )
        }
    }

    @Test
    fun fitHonoursEveryLimit() {
        // 60 points of a sine-like wobble over 3 s, not ending at zero.
        val wobble = (0..60).map { i -> p(i * 50, if (i == 0) 0 else 500 + (i % 4) * 150, 300 + (i % 3) * 100) }
        val limits = EnvelopeLimits(maxControlPoints = 8, minSegmentMs = 20, maxSegmentMs = 400, maxDurationMs = 2000)
        val fitted = Envelopes.fit(wobble, limits)
        assertTrue("${fitted.size} points", fitted.size - 1 <= limits.maxControlPoints)
        assertEquals(0, fitted.last().intensity)
        assertTrue("ends at ${fitted.last().timeMs}", fitted.last().timeMs <= limits.maxDurationMs)
        for (i in 1 until fitted.size) {
            val gap = fitted[i].timeMs - fitted[i - 1].timeMs
            assertTrue("segment $gap ms in $fitted", gap in limits.minSegmentMs..limits.maxSegmentMs)
        }
    }

    @Test
    fun fitSplitsLongSegments() {
        val flat = listOf(p(0, 0), p(50, 800), p(2550, 800), p(2600, 0))
        val limits =
            EnvelopeLimits(maxControlPoints = 16, minSegmentMs = 20, maxSegmentMs = 1000, maxDurationMs = 16_000)
        val fitted = Envelopes.fit(flat, limits)
        assertEquals(listOf(p(0, 0), p(50, 800), p(883, 800), p(1716, 800), p(2550, 800), p(2600, 0)), fitted)
    }

    @Test
    fun fitWidensATooShortEnvelope() {
        val blip = listOf(p(0, 0), p(3, 1000), p(6, 0))
        val limits =
            EnvelopeLimits(maxControlPoints = 16, minSegmentMs = 20, maxSegmentMs = 1000, maxDurationMs = 16_000)
        val fitted = Envelopes.fit(blip, limits)
        assertEquals(listOf(p(0, 0), p(20, 1000), p(40, 0)), fitted)
    }

    @Test
    fun interpolates() {
        val line = listOf(p(0, 0, 0), p(100, 1000, 1000))
        assertEquals(p(25, 250, 250), Envelopes.interpolate(line, 25))
        assertEquals(p(150, 1000, 1000), Envelopes.interpolate(line, 150))
    }
}
