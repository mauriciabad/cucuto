package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HapticJsonTest {
    @Test
    fun mapsTheBriefsExampleOneToOne() {
        val json =
            """
            {"mode": "custom", "events": [
              {"at": 0, "preset": "CLICK", "scale": 0.8},
              {"at": 120, "preset": "TICK", "scale": 0.5},
              {"at": 220, "envelope": [
                {"intensity": 1.0, "sharpness": 0.8, "ms": 120},
                {"intensity": 0.0, "sharpness": 0.4, "ms": 180}
              ]},
              {"at": 600, "initialSharpness": 0.25, "envelope": [{"intensity": 0, "sharpness": 0.1, "ms": 10}]},
              {"at": 700, "preset": "LOW_TICK"}
            ]}
            """.trimIndent()
        val expected =
            Haptic.Custom(
                listOf(
                    HapticEvent.Preset(0, HapticPreset.CLICK, 800),
                    HapticEvent.Preset(120, HapticPreset.TICK, 500),
                    HapticEvent.Envelope(220, listOf(ControlPoint(1000, 800, 120), ControlPoint(0, 400, 180))),
                    HapticEvent.Envelope(600, listOf(ControlPoint(0, 100, 10)), milliInitialSharpness = 250),
                    HapticEvent.Preset(700, HapticPreset.LOW_TICK, 1000),
                ),
            )
        assertEquals(expected, HapticJson.fromPack(Json.parseToJsonElement(json)))
        assertEquals(expected, HapticJson.fromOverride(json))
    }

    @Test
    fun modes() {
        assertEquals(Haptic.Auto, HapticJson.fromPack(null))
        assertEquals(Haptic.Auto, HapticJson.fromPack(Json.parseToJsonElement("""{"mode":"auto"}""")))
        // In a pack, "none" means "no vibration from the pack": WhatsApp's own stays.
        assertEquals(Haptic.Keep, HapticJson.fromPack(Json.parseToJsonElement("""{"mode":"none"}""")))
        // As a per-contact override, "none" silences the vibration.
        assertEquals(Haptic.Off, HapticJson.fromOverride("""{"mode":"none"}"""))
        assertEquals(Haptic.Auto, HapticJson.fromOverride("""{"mode":"auto"}"""))
    }

    @Test
    fun unreadableInputFallsBackSafely() {
        assertNull(HapticJson.fromOverride(null))
        assertNull(HapticJson.fromOverride("not json"))
        assertNull(HapticJson.fromOverride("""{"mode":"custom","events":[]}"""))
        assertEquals(Haptic.Auto, HapticJson.fromPack(Json.parseToJsonElement("""{"mode":"custom"}""")))
        assertEquals(
            Haptic.Custom(listOf(HapticEvent.Preset(5, HapticPreset.CLICK, 1000))),
            HapticJson.fromOverride(
                """{"mode":"custom","events":[{"at":1,"preset":"BUZZ"},{"at":5,"preset":"CLICK","scale":7}]}""",
            ),
        )
    }
}
