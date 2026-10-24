package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.core.routing.RoutingCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HapticOverrideTest {
    /** What the haptic editor makes: presets, an envelope with odd thousandths and an explicit initial sharpness. */
    private val edited =
        Haptic.Custom(
            listOf(
                HapticEvent.Preset(0, HapticPreset.CLICK, 1000),
                HapticEvent.Preset(70, HapticPreset.TICK, 537),
                HapticEvent.Preset(140, HapticPreset.LOW_TICK, 0),
                HapticEvent.Envelope(
                    220,
                    listOf(ControlPoint(1000, 333, 20), ControlPoint(667, 333, 461), ControlPoint(0, 1, 80)),
                ),
                HapticEvent.Envelope(900, listOf(ControlPoint(250, 900, 40), ControlPoint(0, 0, 20)), 125),
            ),
        )

    @Test
    fun everyChoiceRoundTripsThroughItsStoredJson() {
        val choices =
            listOf(HapticOverride.PackDefault, HapticOverride.Auto, HapticOverride.Off, HapticOverride.Custom(edited))
        for (choice in choices) assertEquals(choice, HapticOverride.parse(choice.toJson()))
    }

    @Test
    fun storesThePackFormatShape() {
        assertNull(HapticOverride.PackDefault.toJson())
        assertEquals("""{"mode":"auto"}""", HapticOverride.Auto.toJson())
        assertEquals("""{"mode":"none"}""", HapticOverride.Off.toJson())
        val custom =
            Haptic.Custom(
                listOf(
                    HapticEvent.Preset(0, HapticPreset.CLICK, 800),
                    HapticEvent.Preset(120, HapticPreset.TICK, 1000),
                    HapticEvent.Envelope(220, listOf(ControlPoint(1000, 800, 120), ControlPoint(0, 400, 180)), 250),
                ),
            )
        assertEquals(
            """{"mode":"custom","events":[""" +
                """{"at":0,"preset":"CLICK","scale":0.8},""" +
                """{"at":120,"preset":"TICK"},""" +
                """{"at":220,"initialSharpness":0.25,"envelope":[""" +
                """{"intensity":1,"sharpness":0.8,"ms":120},{"intensity":0,"sharpness":0.4,"ms":180}]}]}""",
            HapticOverride.Custom(custom).toJson(),
        )
    }

    @Test
    fun customJsonReadsBackAsTheSameRoutingHaptic() {
        val json = HapticJson.toJson(edited).toString()
        assertEquals(edited, HapticJson.fromOverride(json))
        // A pack with the same JSON plays the same pattern.
        assertEquals(edited, HapticJson.fromPack(HapticJson.toJson(edited)))
    }

    @Test
    fun editedPatternsRoundTripThroughTheRoutingTableSyntax() {
        val haptic = HapticOverride.parse(HapticOverride.Custom(edited).toJson()) as HapticOverride.Custom
        assertEquals(edited, RoutingCodec.decodeHaptic(RoutingCodec.encodeHaptic(haptic.haptic)))
        assertEquals(Haptic.Off, RoutingCodec.decodeHaptic(RoutingCodec.encodeHaptic(Haptic.Off)))
        assertEquals(Haptic.Auto, RoutingCodec.decodeHaptic(RoutingCodec.encodeHaptic(Haptic.Auto)))
    }

    @Test
    fun unreadableStoredValuesMeanThePacksHaptic() {
        assertEquals(HapticOverride.PackDefault, HapticOverride.parse(null))
        assertEquals(HapticOverride.PackDefault, HapticOverride.parse(""))
        assertEquals(HapticOverride.PackDefault, HapticOverride.parse("{"))
        assertEquals(HapticOverride.PackDefault, HapticOverride.parse("""{"mode":"buzz"}"""))
        assertEquals(HapticOverride.PackDefault, HapticOverride.parse("""{"mode":"custom","events":[]}"""))
    }
}
