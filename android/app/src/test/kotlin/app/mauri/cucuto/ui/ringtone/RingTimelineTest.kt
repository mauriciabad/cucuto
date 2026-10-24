package app.mauri.cucuto.ui.ringtone

import app.mauri.cucuto.packspec.LoopRange
import app.mauri.cucuto.ui.ringtone.RingTimeline.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A ringtone preview rings the way the call rings (owner update 2026-10-24, #242; #246): from 0 ms, through the same
 * ring file layout as `RingFilePlan` (pack format, "Ring files"), round and round. The examples are the pack format's.
 */
class RingTimelineTest {
    private val file = File("song.ogg")

    @Test
    fun itStartsAtTheBeginningNeverAtTheLoopPoint() {
        val timeline = RingTimeline.of(RingSound(file, LoopRange(4_000, 12_000), fadeInMs = 500), 14_000)

        assertEquals(0L, timeline.sourceAt(0))
        assertEquals(3_999L, timeline.sourceAt(3_999))
        assertEquals(Segment.Play(0, 4_000), timeline.segments.first())
    }

    @Test
    fun theIntroPlaysOnceThenTheLoopRepeatsAndTheOutroNever() {
        // 0-4 s once, 4-12 s eleven times: 92 s; the outro 12-14 s is left out.
        val timeline = RingTimeline.of(RingSound(file, LoopRange(4_000, 12_000)), 14_000)

        assertEquals(92_000L, timeline.cycleMs)
        assertEquals(12, timeline.segments.size)
        assertEquals(5_000L, timeline.sourceAt(5_000))
        assertEquals(11_999L, timeline.sourceAt(11_999))
        assertEquals(4_000L, timeline.sourceAt(12_000))
        assertEquals(4_500L, timeline.sourceAt(12_500))
        assertTrue(timeline.seeksAt(2))
        assertFalse("the intro runs straight into the first loop", timeline.seeksAt(1))
    }

    @Test
    fun itGoesOnLikeTheCallUntilStopped() {
        val timeline = RingTimeline.of(RingSound(file, LoopRange(4_000, 12_000)), 14_000)

        // Telecom loops the ring file: after 92 s the intro comes back.
        assertEquals(0L, timeline.sourceAt(92_000))
        assertEquals(1_000L, timeline.sourceAt(93_000))
        assertEquals(4_500L, timeline.sourceAt(10 * 92_000 + 12_500))
    }

    @Test
    fun withoutLoopPointsTheWholeFileRepeats() {
        // The 14 s file seven times: 98 s.
        val timeline = RingTimeline.of(RingSound(file), 14_000)

        assertEquals(98_000L, timeline.cycleMs)
        assertEquals(List(7) { Segment.Play(0, 14_000) }, timeline.segments)
        assertEquals(1_000L, timeline.sourceAt(15_000))
    }

    @Test
    fun aRingtoneThatPlaysOnceIsFollowedBySilence() {
        // `loop: false`: the 14 s file, then 76 s of silence: 90 s.
        val timeline = RingTimeline.of(RingSound(file, loop = null), 14_000)

        assertEquals(90_000L, timeline.cycleMs)
        assertEquals(listOf(Segment.Play(0, 14_000), Segment.Silence(76_000)), timeline.segments)
        assertNull(timeline.sourceAt(20_000))
        assertEquals(0L, timeline.sourceAt(90_000))
    }

    @Test
    fun aLongSongIsOneRingFile() {
        val timeline = RingTimeline.of(RingSound(file), 240_000)

        assertEquals(240_000L, timeline.cycleMs)
        assertEquals(200_000L, timeline.sourceAt(200_000))
    }

    @Test
    fun brokenLoopPointsLoopTheWholeFile() {
        val timeline = RingTimeline.of(RingSound(file, LoopRange(4_000, 4_200)), 14_000)

        assertEquals(98_000L, timeline.cycleMs)
        assertEquals(0L, timeline.sourceAt(0))
    }
}
