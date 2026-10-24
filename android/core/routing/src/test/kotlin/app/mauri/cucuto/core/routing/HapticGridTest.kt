package app.mauri.cucuto.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticGridTest {
    @Test
    fun levelsMapBothWaysAndCoverTheWholeRange() {
        for (level in 0 until HapticGrid.LEVELS) assertEquals(level, HapticGrid.level(HapticGrid.value(level)))
        assertEquals(0, HapticGrid.value(0))
        assertEquals(1000, HapticGrid.value(HapticGrid.LEVELS - 1))
        // Every value in thousandths lands on the nearest level.
        for (milli in 0..1000) {
            val snapped = HapticGrid.value(HapticGrid.level(milli))
            assertTrue("$milli → $snapped", kotlin.math.abs(snapped - milli) <= 8)
        }
    }

    @Test
    fun snapsEnvelopesOntoTheGrid() {
        val haptic =
            Haptic.Custom(
                listOf(HapticEvent.Envelope(30, listOf(ControlPoint(700, 333, 25), ControlPoint(0, 1000, 40)), 501)),
            )
        val snapped = HapticGrid.snap(haptic)
        assertEquals(
            Haptic.Custom(
                listOf(HapticEvent.Envelope(30, listOf(ControlPoint(698, 333, 40), ControlPoint(0, 1000, 40)), 508)),
            ),
            snapped,
        )
        assertTrue(HapticGrid.isOnGrid(snapped))
        assertFalse(HapticGrid.isOnGrid(haptic))
        // Idempotent, and an on-grid haptic is returned as is.
        assertSame(snapped, HapticGrid.snap(snapped))
    }

    @Test
    fun durationsRoundUpSoTheyNeverDropBelowTheDeviceMinimum() {
        val haptic =
            Haptic.Custom(
                listOf(HapticEvent.Envelope(0, listOf(ControlPoint(1000, 0, 1), ControlPoint(0, 0, 0)))),
            )
        val points = (HapticGrid.snap(haptic).events[0] as HapticEvent.Envelope).points
        assertEquals(listOf(20, 0), points.map { it.durationMs })
    }

    @Test
    fun leavesPresetsAndLongSegmentsAlone() {
        val long = HapticEvent.Envelope(0, listOf(ControlPoint(1000, 0, HapticGrid.MAX_DURATION_MS + 1)))
        val preset = HapticEvent.Preset(0, HapticPreset.CLICK, 777)
        val haptic = Haptic.Custom(listOf(preset, long))
        assertSame(haptic, HapticGrid.snap(haptic))
        assertFalse(HapticGrid.isOnGrid(haptic))
    }

    @Test
    fun analysedEnvelopesFitTheGrid() {
        for (seed in 0 until 100) {
            val snapped = HapticGrid.snap(RoutingFixtures.autoEnvelope(seed))
            assertTrue(HapticGrid.isOnGrid(snapped))
        }
    }
}
