package app.mauri.cucuto.ui.haptics

import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticTimelineTest {
    @Test
    fun sortsAndMovesOverlappingEventsAfterThePreviousOne() {
        val events =
            listOf(
                HapticEvent.Preset(300, HapticPreset.TICK, 500),
                HapticEvent.Envelope(100, listOf(ControlPoint(800, 500, 100), ControlPoint(0, 500, 100))),
                HapticEvent.Preset(0, HapticPreset.CLICK, 800),
            )
        val normalized = HapticTimeline.normalize(events)
        // The click takes 50 ms, the envelope 200 ms: the tick has to wait for the envelope to end.
        assertEquals(listOf(0, 100, 300), normalized.map { it.atMs })
        assertTrue(HapticTimeline.isValid(normalized))
        assertEquals(350, HapticTimeline.durationOf(normalized))
    }

    @Test
    fun clampsValuesAndClosesEnvelopesAtZero() {
        val normalized =
            HapticTimeline.normalize(
                listOf(
                    HapticEvent.Preset(-5, HapticPreset.CLICK, 1500),
                    HapticEvent.Envelope(100, listOf(ControlPoint(1200, -3, 0)), milliInitialSharpness = 2000),
                ),
            )
        assertEquals(HapticEvent.Preset(0, HapticPreset.CLICK, 1000), normalized[0])
        assertEquals(
            HapticEvent.Envelope(
                100,
                listOf(ControlPoint(1000, 0, 1), ControlPoint(0, 0, HapticTimeline.MIN_SEGMENT_MS)),
                milliInitialSharpness = 1000,
            ),
            normalized[1],
        )
    }

    @Test
    fun keepsAtMostSixteenPointsAndSixtyFourEvents() {
        val points = List(20) { ControlPoint(500, 500, 20) }
        val envelope =
            HapticTimeline
                .normalize(
                    listOf(HapticEvent.Envelope(0, points)),
                ).single() as HapticEvent.Envelope
        assertEquals(HapticTimeline.MAX_POINTS, envelope.points.size)
        assertEquals(0, envelope.points.last().milliIntensity)

        val many = List(100) { HapticEvent.Preset(it * 50, HapticPreset.TICK, 500) }
        assertEquals(HapticTimeline.MAX_EVENTS, HapticTimeline.normalize(many).size)
    }

    @Test
    fun dropsWhatEndsAfterFiveSeconds() {
        val events =
            listOf(
                HapticEvent.Preset(4900, HapticPreset.TICK, 500),
                HapticEvent.Envelope(4800, listOf(ControlPoint(500, 500, 300), ControlPoint(0, 500, 100))),
            )
        assertEquals(listOf(4900), HapticTimeline.normalize(events).map { it.atMs })
        assertFalse(HapticTimeline.isValid(emptyList()))
    }

    @Test
    fun theWindowFitsThePatternInHalfSeconds() {
        assertEquals(1500, timelineWindowMs(0))
        assertEquals(1500, timelineWindowMs(900))
        assertEquals(2000, timelineWindowMs(1200))
        assertEquals(5000, timelineWindowMs(4800))
    }
}
