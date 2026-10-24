package app.mauri.cucuto.ui.haptics

import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmRecorderTest {
    private val recorder = RhythmRecorder()

    private fun tap(
        atMs: Long,
        holdMs: Long,
    ) {
        assertTrue(recorder.press(BOOT + atMs))
        recorder.release(BOOT + atMs + holdMs)
    }

    @Test
    fun tapLengthPicksTheKindAndTheStrength() {
        assertEquals(HapticEvent.Preset(0, HapticPreset.TICK, 500), RhythmMapping.event(0, 0))
        assertEquals(HapticEvent.Preset(0, HapticPreset.TICK, 700), RhythmMapping.event(0, 70))
        assertEquals(HapticEvent.Preset(0, HapticPreset.CLICK, 850), RhythmMapping.event(0, 125))
        assertEquals(HapticEvent.Preset(0, HapticPreset.CLICK, 1000), RhythmMapping.event(0, 180))
        // A hold lasts as long as the finger stays down, then fades out; longer holds are stronger and softer.
        // At 200 ms the hold is just past the click limit: almost the weakest, crispest swell.
        assertEquals(
            HapticEvent.Envelope(
                0,
                listOf(ControlPoint(610, 690, 20), ControlPoint(610, 690, 180), ControlPoint(0, 690, 80)),
            ),
            RhythmMapping.event(0, 200),
        )
        val long = RhythmMapping.event(0, 1000) as HapticEvent.Envelope
        assertEquals(listOf(1000, 1000, 0), long.points.map { it.milliIntensity })
        assertEquals(300, long.points.first().milliSharpness)
        assertEquals(1000 + RhythmMapping.RELEASE_MS, HapticTimeline.lengthOf(long))
    }

    @Test
    fun recordsTheRhythmFromTheFirstPress() {
        assertTrue(recorder.isEmpty)
        tap(0, 30)
        tap(250, 120)
        tap(500, 400)
        val events = recorder.events(BOOT + 2000)
        assertEquals(listOf(0, 250, 500), events.map { it.atMs })
        assertEquals(HapticPreset.TICK, (events[0] as HapticEvent.Preset).preset)
        assertEquals(HapticPreset.CLICK, (events[1] as HapticEvent.Preset).preset)
        assertEquals(400 + RhythmMapping.RELEASE_MS, HapticTimeline.lengthOf(events[2]))
        assertTrue(HapticTimeline.isValid(events))
        assertEquals(3, recorder.count)
    }

    @Test
    fun aHoldFadesOutBeforeTheNextPress() {
        tap(0, 300)
        tap(330, 20)
        val events = recorder.events(BOOT + 1000)
        val hold = events[0] as HapticEvent.Envelope
        // Only 30 ms between release and the next press: the fade shrinks to fit, the rhythm keeps its timing.
        assertEquals(30, hold.points.last().durationMs)
        assertEquals(330, events[1].atMs)
        assertTrue(HapticTimeline.isValid(events))
    }

    @Test
    fun aPressStillHeldCountsUntilNow() {
        assertTrue(recorder.press(BOOT))
        assertTrue(recorder.isPressed)
        assertFalse(recorder.press(BOOT + 10))
        val events = recorder.events(BOOT + 500)
        assertEquals(500 + RhythmMapping.RELEASE_MS, HapticTimeline.lengthOf(events.single()))
    }

    @Test
    fun stopsAtTheEndOfTheTimeline() {
        tap(0, 20)
        assertFalse(recorder.isFull(BOOT + 4000))
        // A hold that runs past 5 s is cut so that it still fits.
        tap(4000, 3000)
        val events = recorder.events(BOOT + 8000)
        assertTrue(HapticTimeline.isValid(events))
        assertEquals(HapticTimeline.MAX_MS, HapticTimeline.durationOf(events))
        assertTrue(recorder.isFull(BOOT + 4950))
        assertFalse(recorder.press(BOOT + 4960))
    }

    @Test
    fun stopsAfterTheMostEventsAPatternCanHave() {
        repeat(HapticTimeline.MAX_EVENTS) { tap(it * 60L, 10) }
        assertTrue(recorder.isFull(BOOT + 3900))
        assertFalse(recorder.press(BOOT + 3900))
        assertEquals(HapticTimeline.MAX_EVENTS, recorder.events(BOOT + 4000).size)
    }

    @Test
    fun resetForgetsEverything() {
        tap(0, 30)
        recorder.reset()
        assertTrue(recorder.isEmpty)
        assertEquals(emptyList<HapticEvent>(), recorder.events(BOOT + 100))
        assertEquals(0, recorder.elapsedMs(BOOT + 100))
    }

    private companion object {
        /** An `uptimeMillis` value: recordings never start at 0. */
        const val BOOT = 123_456L
    }
}
