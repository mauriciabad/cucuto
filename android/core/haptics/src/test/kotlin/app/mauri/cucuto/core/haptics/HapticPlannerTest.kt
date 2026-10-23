package app.mauri.cucuto.core.haptics

import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticPlannerTest {
    private val modern = HapticCapabilities.GUARANTEED_ENVELOPES

    /** Android 17.0 with envelopes and primitives but no `VibrationEffect.Builder`. */
    private val noBuilder =
        modern.copy(
            builderSupported = false,
            primitiveDurationsMs =
                mapOf(
                    HapticPreset.CLICK to 12,
                    HapticPreset.TICK to 6,
                ),
        )

    /** A plain vibrator: no Builder, no envelopes, no primitives. */
    private val basic = HapticCapabilities(amplitudeControl = true)

    /** The example from the brief and the pack format. */
    private val brief =
        Haptic.Custom(
            listOf(
                HapticEvent.Preset(0, HapticPreset.CLICK, 800),
                HapticEvent.Preset(120, HapticPreset.TICK, 500),
                HapticEvent.Envelope(220, listOf(ControlPoint(1000, 800, 120), ControlPoint(0, 400, 180))),
            ),
        )

    @Test
    fun validTimelinesMapOneToOneOntoTheBuilder() {
        assertEquals(HapticPlan.Timeline(brief.events), HapticPlanner.plan(brief, modern))
        // Devices without envelope support still get the Builder: the framework falls back per element.
        assertEquals(
            HapticPlan.Timeline(brief.events),
            HapticPlanner.plan(brief, modern.copy(envelopesSupported = false)),
        )
    }

    @Test
    fun sortsAndMovesOverlappingEvents() {
        val messy =
            Haptic.Custom(
                listOf(
                    HapticEvent.Envelope(100, listOf(ControlPoint(1000, 500, 100), ControlPoint(0, 500, 100))),
                    HapticEvent.Preset(0, HapticPreset.CLICK),
                    HapticEvent.Preset(0, HapticPreset.TICK),
                    // Starts inside the envelope (100..300): moved to its end.
                    HapticEvent.Preset(150, HapticPreset.LOW_TICK),
                ),
            )
        val plan = HapticPlanner.plan(messy, modern) as HapticPlan.Timeline
        assertEquals(listOf(0, 1, 100, 300), plan.events.map { it.atMs })
        assertEquals(HapticPreset.CLICK, (plan.events[0] as HapticEvent.Preset).preset)
        assertEquals(HapticPreset.TICK, (plan.events[1] as HapticEvent.Preset).preset)
    }

    @Test
    fun clampsValuesAndClosesEnvelopes() {
        val wild =
            Haptic.Custom(
                listOf(
                    HapticEvent.Preset(-5, HapticPreset.CLICK, 1500),
                    HapticEvent.Envelope(100, listOf(ControlPoint(800, 1200, 100))),
                ),
            )
        val plan = HapticPlanner.plan(wild, modern) as HapticPlan.Timeline
        assertEquals(HapticEvent.Preset(0, HapticPreset.CLICK, 1000), plan.events[0])
        val envelope = plan.events[1] as HapticEvent.Envelope
        assertEquals(listOf(ControlPoint(800, 1000, 100), ControlPoint(0, 1000, 20)), envelope.points)
    }

    @Test
    fun cutsTheTimelineAtFiveSeconds() {
        val long =
            Haptic.Custom(
                listOf(
                    HapticEvent.Envelope(
                        4000,
                        listOf(ControlPoint(1000, 500, 900), ControlPoint(1000, 500, 900), ControlPoint(0, 500, 100)),
                    ),
                    HapticEvent.Preset(4900, HapticPreset.CLICK),
                    HapticEvent.Preset(6000, HapticPreset.CLICK),
                ),
            )
        for (capabilities in listOf(modern, basic.copy(builderSupported = true))) {
            val plan = HapticPlanner.plan(long, capabilities) as HapticPlan.Timeline
            assertEquals(1, plan.events.size)
            val envelope = plan.events[0] as HapticEvent.Envelope
            assertEquals(1000, envelope.points.sumOf { it.durationMs })
            assertEquals(0, envelope.points.last().milliIntensity)
        }
    }

    @Test
    fun fitsEnvelopesToTheDevice() {
        val dense =
            HapticEvent.Envelope(
                0,
                (1..40).map {
                    ControlPoint(
                        if (it ==
                            40
                        ) {
                            0
                        } else {
                            200 + (it % 5) * 200
                        },
                        500,
                        10,
                    )
                },
            )
        val small = modern.copy(maxControlPoints = 6, minControlPointMs = 20)
        val plan = HapticPlanner.plan(Haptic.Custom(listOf(dense)), small) as HapticPlan.Timeline
        val envelope = plan.events.single() as HapticEvent.Envelope
        assertTrue(envelope.points.toString(), envelope.points.size <= 6)
        assertTrue(envelope.points.all { it.durationMs >= 20 })
        assertEquals(0, envelope.points.last().milliIntensity)
    }

    @Test
    fun withoutTheBuilderEnvelopesAreJoined() {
        val two =
            Haptic.Custom(
                listOf(
                    HapticEvent.Envelope(50, listOf(ControlPoint(1000, 800, 100), ControlPoint(0, 800, 100))),
                    HapticEvent.Envelope(400, listOf(ControlPoint(600, 200, 50), ControlPoint(0, 200, 50))),
                ),
            )
        val plan = HapticPlanner.plan(two, noBuilder) as HapticPlan.Envelope
        assertEquals(
            HapticEvent.Envelope(
                0,
                listOf(
                    ControlPoint(0, 800, 50),
                    ControlPoint(1000, 800, 100),
                    ControlPoint(0, 800, 100),
                    ControlPoint(0, 200, 150),
                    ControlPoint(600, 200, 50),
                    ControlPoint(0, 200, 50),
                ),
            ),
            plan.envelope,
        )
    }

    @Test
    fun withoutTheBuilderPresetsBecomeAComposition() {
        val presets =
            Haptic.Custom(
                listOf(
                    HapticEvent.Preset(30, HapticPreset.CLICK, 900),
                    HapticEvent.Preset(100, HapticPreset.TICK, 400),
                ),
            )
        assertEquals(
            HapticPlan.Primitives(
                listOf(
                    HapticPlan.Primitive(HapticPreset.CLICK, 900, 30),
                    // 100 - (30 + 12 ms for the click primitive).
                    HapticPlan.Primitive(HapticPreset.TICK, 400, 58),
                ),
            ),
            HapticPlanner.plan(presets, noBuilder),
        )
        // An unsupported primitive (LOW_TICK here) falls back to a waveform: a composition with it would not play.
        val low = Haptic.Custom(listOf(HapticEvent.Preset(0, HapticPreset.LOW_TICK)))
        assertTrue(HapticPlanner.plan(low, noBuilder) is HapticPlan.Waveform)
    }

    @Test
    fun aPlainVibratorGetsAWaveform() {
        val plan = HapticPlanner.plan(brief, basic) as HapticPlan.Waveform
        assertEquals(plan.timingsMs.size, plan.amplitudes.size)
        // Click pulse at 0, tick pulse at 120, then the envelope from 220 to 520 in 20 ms steps.
        assertEquals(20L, plan.timingsMs[0])
        assertEquals(100L, plan.timingsMs[1])
        assertEquals(0, plan.amplitudes[1])
        assertTrue(plan.amplitudes[0] > plan.amplitudes[2])
        assertEquals(520L, plan.timingsMs.sum())
        assertTrue(plan.amplitudes.last() > 0)
    }

    @Test
    fun waveformWithoutAmplitudeControlIsOnOff() {
        val plan = HapticPlanner.plan(brief, basic.copy(amplitudeControl = false)) as HapticPlan.Waveform
        assertTrue(plan.amplitudes.toString(), plan.amplitudes.all { it == 0 || it == 255 })
    }

    @Test
    fun nothingPlayableIsNone() {
        val silent = Haptic.Custom(listOf(HapticEvent.Envelope(0, listOf(ControlPoint(0, 0, 50)))))
        assertEquals(HapticPlan.None, HapticPlanner.plan(silent, basic))
        val late = Haptic.Custom(listOf(HapticEvent.Preset(5000, HapticPreset.CLICK)))
        assertEquals(HapticPlan.None, HapticPlanner.plan(late, modern))
    }
}
