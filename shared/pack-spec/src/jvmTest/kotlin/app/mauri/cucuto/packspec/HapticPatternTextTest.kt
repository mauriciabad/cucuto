package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.authoring.HapticPatternText
import app.mauri.cucuto.packspec.authoring.HapticPatternText.LoudnessRow
import app.mauri.cucuto.packspec.authoring.HapticPatternText.Parse
import app.mauri.cucuto.packspec.authoring.HapticPatternText.Problem
import app.mauri.cucuto.packspec.authoring.HapticPatternText.PromptClip
import app.mauri.cucuto.packspec.authoring.HapticPatternText.Spot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The haptic pattern text shared by the app's haptic editor and the website's creator (#217, #191). */
class HapticPatternTextTest {
    private val pattern =
        listOf(
            HapticEvent(at = 0, preset = "CLICK", scale = 0.8),
            HapticEvent(at = 120, preset = "TICK"),
            HapticEvent(
                at = 220,
                initialSharpness = 0.3,
                envelope = listOf(ControlPoint(1.0, 0.8, 120), ControlPoint(0.0, 0.4, 180)),
            ),
            HapticEvent(at = 600, envelope = listOf(ControlPoint(0.555, 0.125, 40), ControlPoint(0.0, 0.0, 60))),
            HapticEvent(at = 4950, preset = "LOW_TICK", scale = 0.0),
        )

    private fun valid(text: String): List<HapticEvent> {
        val parsed = HapticPatternText.parse(text)
        assertTrue(parsed is Parse.Valid, "expected a valid pattern, got $parsed")
        return (parsed as Parse.Valid).events
    }

    private fun problem(text: String): Problem {
        val parsed = HapticPatternText.parse(text)
        assertTrue(parsed is Parse.Invalid, "expected a refusal, got $parsed")
        return (parsed as Parse.Invalid).problem
    }

    @Test
    fun `a pattern round-trips through its JSON and passes the validator`() {
        val json = HapticPatternText.patternJson(pattern)
        assertFalse(json.contains("null"), json)
        assertEquals(pattern, valid(json))
        // The same JSON a pack carries: put it in a character and the validator accepts it.
        val haptic = SpecJson.decodeFromString(Haptic.serializer(), json)
        assertEquals(Haptic("custom", pattern), haptic)
    }

    @Test
    fun `the prompt carries the clips, the pattern and a working example`() {
        val rows = listOf(LoudnessRow(0, 0, 50), LoudnessRow(40, 85, 70), LoudnessRow(300, 0, 30))
        val text =
            HapticPatternText.prompt(
                "Owl",
                listOf(PromptClip("clips/owl-hoot.ogg", 1240, rows), PromptClip("clips/owl-2.ogg", null, null)),
                HapticPatternText.patternJson(pattern),
            )
        assertTrue(text.contains("Character: Owl."))
        assertTrue(text.contains("each of its 2 clips"))
        assertTrue(text.contains("Clip 1, \"clips/owl-hoot.ogg\": 1240 ms long."))
        assertTrue(text.contains("ms,loudness,sharpness\n0,0,50\n40,85,70\n300,0,30\n"))
        assertTrue(text.contains("Clip 2, \"clips/owl-2.ogg\": length unknown."))
        assertTrue(text.contains("No loudness data"))
        assertEquals(pattern, valid(text))
        assertEquals(3, valid(text.substringAfter("Example:")).size)

        val empty = HapticPatternText.prompt(null, emptyList(), null)
        assertTrue(empty.contains("No clip details"))
        assertTrue(empty.contains("None yet."))
        val many = HapticPatternText.prompt("Frog", List(8) { PromptClip("clips/c$it.ogg", 1000, null) }, "")
        assertTrue(many.contains("Clip 6,") && !many.contains("Clip 7,"))
        assertTrue(many.contains("(2 more clips not listed.)"))
    }

    @Test
    fun `an AI answer is found inside prose and code fences`() {
        val json = HapticPatternText.patternJson(pattern)
        assertEquals(pattern, valid("Here is your pattern:\n```json\n$json\n```\nEnjoy!"))
        assertEquals(pattern, valid("Sure! $json Hope it helps."))
        assertEquals(pattern, valid("""{"haptic":$json}"""))
        val events = json.substringAfter("\"events\":").dropLast(1)
        assertEquals(pattern, valid(events))
        assertEquals(pattern, valid("""{"mode":"custom","x-note":"by an AI","events":$events}"""))
        assertEquals(listOf(HapticEvent(at = 120, preset = "CLICK")), valid("""[{"at":120.0,"preset":"CLICK"}]"""))
    }

    @Test
    fun `broken patterns are refused with the rule they break`() {
        val event = Spot(event = 1)
        val point = Spot(event = 1, point = 1)
        val cases =
            mapOf(
                "I can't do that." to Problem.NotJson,
                "{\"mode\":\"custom\",\"events\":[" to Problem.NotJson,
                """{"mode":"auto"}""" to Problem.NotCustom("auto"),
                """{"mode":"custom","events":[]}""" to Problem.NoEvents,
                """{"name":"x","events":[]}""" to Problem.UnknownField(Spot(), "name"),
                "[" + List(65) { """{"at":${it * 50},"preset":"TICK"}""" }.joinToString(",") + "]" to Problem.TooManyEvents,
                """[1]""" to Problem.NotAnEvent(1),
                """[{"at":0,"preset":"CLICK","envelope":[]}]""" to Problem.NotAnEvent(1),
                """[{"preset":"CLICK"}]""" to Problem.MissingField(event, "at"),
                """[{"at":0,"preset":"CLICK","note":"hi"}]""" to Problem.UnknownField(event, "note"),
                """[{"at":12.5,"preset":"CLICK"}]""" to Problem.NotWholeNumber(event, "at", "12.5"),
                """[{"at":6000,"preset":"CLICK"}]""" to Problem.OutOfRange(event, "at", 0, 5000, "6000"),
                """[{"at":0,"preset":"CLICK","scale":1.5}]""" to Problem.OutOfRange(event, "scale", 0, 1, "1.5"),
                """[{"at":0,"preset":"BUZZ"}]""" to Problem.UnknownPreset(1, "BUZZ"),
                """[{"at":0,"envelope":[]}]""" to Problem.PointCount(1, 0),
                """[{"at":0,"envelope":[{"intensity":1,"sharpness":0.5}]}]""" to Problem.MissingField(point, "ms"),
                """[{"at":0,"envelope":[{"intensity":1,"sharpness":0.5,"ms":100}]}]""" to Problem.NotClosed(1),
                """[{"at":100,"preset":"CLICK"},{"at":50,"preset":"TICK"}]""" to Problem.Unsorted(2),
                """[{"at":0,"envelope":[{"intensity":0,"sharpness":0.5,"ms":300}]},{"at":200,"preset":"TICK"}]""" to
                    Problem.Overlap(2, 200, 300),
                """[{"at":4900,"envelope":[{"intensity":0,"sharpness":0.5,"ms":300}]}]""" to Problem.TooLong(5200),
            )
        cases.forEach { (text, expected) -> assertEquals(expected, problem(text), text) }
    }
}
