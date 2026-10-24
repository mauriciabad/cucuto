package app.mauri.cucuto.ui.haptics

import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticDraftTest {
    private val click = HapticEvent.Preset(0, HapticPreset.CLICK, 800)
    private val swell =
        HapticEvent.Envelope(
            200,
            listOf(ControlPoint(800, 500, 40), ControlPoint(800, 500, 120), ControlPoint(0, 300, 100)),
        )

    private fun draft(vararg events: HapticEvent) = HapticDraft(events.toList())

    @Test
    fun startsFromAValidCopyOfAHaptic() {
        val draft = HapticDraft.of(Haptic.Custom(listOf(swell, click)))
        assertEquals(listOf(click, swell), draft.events)
        assertEquals(Haptic.Custom(listOf(click, swell)), draft.haptic)
        assertNull(HapticDraft.of(null).haptic)
        assertFalse(HapticDraft.of(null).canUndo)
    }

    @Test
    fun dropsTheToolWhereThereIsRoomAndSelectsIt() {
        val added = draft(click, swell).add(HapticTool.Tick, 100)
        assertEquals(listOf(0, 100, 200), added.events.map { it.atMs })
        assertEquals(1, added.selected)
        assertEquals(HapticEvent.Preset(100, HapticPreset.TICK, HapticDraft.DEFAULT_TICK_SCALE), added.selectedEvent)

        // Inside the swell (200 to 460 ms) there is no room: the tick goes to the nearest free spot, right before it.
        val nudged = draft(click, swell).add(HapticTool.Tick, 300)
        assertEquals(150, nudged.selectedEvent?.atMs)
        assertEquals(460, draft(click, swell).add(HapticTool.Tick, 400).selectedEvent?.atMs)

        val envelope = draft().add(HapticTool.Envelope, 1000)
        assertEquals(HapticDraft.DEFAULT_ENVELOPE.copy(atMs = 1000), envelope.events.single())
        assertTrue(HapticTimeline.isValid(envelope.events))
    }

    @Test
    fun anEditThatDoesNotFitChangesNothing() {
        val full = HapticDraft(List(HapticTimeline.MAX_EVENTS) { HapticEvent.Preset(it * 60, HapticPreset.TICK, 500) })
        assertFalse(full.canAdd)
        assertSame(full, full.add(HapticTool.Click, 4000))
        // A 5 s envelope leaves no room for anything else.
        val long = HapticEvent.Envelope(0, listOf(ControlPoint(500, 500, 4980), ControlPoint(0, 500, 20)))
        val packed = draft(long)
        assertSame(packed, packed.add(HapticTool.Click, 2000))
    }

    @Test
    fun movingKeepsTheTimelineValidAndCanHopOverANeighbour() {
        val start = draft(click, swell)
        // Into the swell: the click stops right before it.
        val blocked = start.move(0, 190)
        assertEquals(listOf(150, 200), blocked.events.map { it.atMs })
        assertTrue(HapticTimeline.isValid(blocked.events))
        // Past it: the click hops over and stays selected.
        val hopped = start.move(0, 600)
        assertEquals(listOf(swell, click.copy(atMs = 600)), hopped.events)
        assertEquals(1, hopped.selected)
        // Never before 0 or past 5 s.
        assertEquals(50, start.move(1, -50).events[1].atMs)
        assertEquals(
            HapticTimeline.MAX_MS - 260,
            start
                .move(1, 9000)
                .events
                .last()
                .atMs,
        )
    }

    @Test
    fun aDragIsOneUndoStep() {
        val start = draft(click, swell)
        val dragged =
            start
                .select(0)
                .move(0, 30)
                .move(0, 60)
                .move(0, 90)
        assertEquals(90, dragged.events[0].atMs)
        val undone = dragged.undo()
        assertEquals(start.events, undone.events)
        assertFalse(undone.canUndo)
        // A slider drag too, but a new gesture after it is a new step.
        val slid = start.setStrength(0, 100).setStrength(0, 200).setStrength(0, 300)
        val deleted = slid.delete(0)
        assertEquals(listOf(swell), deleted.events)
        assertEquals(listOf(click.copy(milliScale = 300), swell), deleted.undo().events)
        assertEquals(start.events, deleted.undo().undo().events)
        assertFalse(deleted.undo().undo().canUndo)
    }

    @Test
    fun presetsChangeKindAndStrength() {
        val changed = draft(click).setPreset(0, HapticPreset.LOW_TICK).setStrength(0, 1300)
        assertEquals(HapticEvent.Preset(0, HapticPreset.LOW_TICK, 1000), changed.events.single())
    }

    @Test
    fun envelopeStrengthScalesTheShapeAndSharpnessAppliesToEveryPoint() {
        val stronger =
            draft(swell)
                .setStrength(0, 400)
                .setSharpness(0, 900)
                .events
                .single() as HapticEvent.Envelope
        assertEquals(listOf(400, 400, 0), stronger.points.map { it.milliIntensity })
        assertEquals(listOf(900, 900, 900), stronger.points.map { it.milliSharpness })
        assertEquals(HapticEvent.Envelope.DEFAULT_SHARPNESS, stronger.milliInitialSharpness)
    }

    @Test
    fun draggingAPointReshapesTheEnvelopeWithinItsRoom() {
        val next = HapticEvent.Preset(600, HapticPreset.TICK, 500)
        val start = draft(swell, next)
        val moved = start.movePoint(0, 1, 300, 50).events[0] as HapticEvent.Envelope
        assertEquals(ControlPoint(300, 500, 50), moved.points[1])
        // The last point always ends at zero; segments stay between 20 ms and what fits before the tick.
        val last = start.movePoint(0, 2, 900, 1).events[0] as HapticEvent.Envelope
        assertEquals(ControlPoint(0, 300, HapticTimeline.MIN_SEGMENT_MS), last.points[2])
        val stretched = start.movePoint(0, 2, 0, 5000).events[0] as HapticEvent.Envelope
        assertEquals(600, HapticTimeline.endOf(stretched))
        assertTrue(HapticTimeline.isValid(start.movePoint(0, 2, 0, 5000).events))
    }

    @Test
    fun pointsCanBeAddedAndRemoved() {
        val added = draft(swell).addPoint(0).events.single() as HapticEvent.Envelope
        // The longest segment (120 ms, held at 800) is split in two.
        assertEquals(listOf(40, 60, 60, 100), added.points.map { it.durationMs })
        assertEquals(listOf(800, 800, 800, 0), added.points.map { it.milliIntensity })
        val removed = draft(added).removePoint(0, 1).events.single() as HapticEvent.Envelope
        assertEquals(listOf(40, 120, 100), removed.points.map { it.durationMs })
        // Two points is the least: a peak and the end.
        val two = HapticEvent.Envelope(0, listOf(ControlPoint(500, 500, 40), ControlPoint(0, 500, 40)))
        val minimal = draft(two)
        assertSame(minimal, minimal.removePoint(0, 0))
    }

    @Test
    fun recordingsReplaceTheTimelineAndUndoBringsItBack() {
        val start = draft(click, swell).select(1)
        val recorded = start.replaceAll(listOf(HapticEvent.Preset(0, HapticPreset.TICK, 600)))
        assertNull(recorded.selected)
        assertEquals(1, recorded.events.size)
        assertEquals(start.events, recorded.undo().events)
        assertEquals(emptyList<HapticEvent>(), recorded.clear().events)
        assertNull(recorded.clear().haptic)
    }

    @Test
    fun findsTheEventUnderAFinger() {
        val d = draft(click, swell)
        assertEquals(0, d.eventAt(10, 5))
        assertEquals(1, d.eventAt(300, 5))
        assertEquals(1, d.eventAt(195, 10))
        assertNull(d.eventAt(120, 10))
        assertNull(d.eventAt(1000, 10))
    }
}
