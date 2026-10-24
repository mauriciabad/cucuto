package app.mauri.cucuto.ui.haptics

import app.mauri.cucuto.core.data.routing.HapticJson
import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticAiTextTest {
    private val pattern =
        listOf(
            HapticEvent.Preset(0, HapticPreset.CLICK, 800),
            HapticEvent.Preset(120, HapticPreset.TICK, 1000),
            HapticEvent.Envelope(220, listOf(ControlPoint(1000, 800, 120), ControlPoint(0, 400, 180)), 300),
            HapticEvent.Envelope(600, listOf(ControlPoint(555, 125, 40), ControlPoint(0, 0, 60))),
            HapticEvent.Preset(4950, HapticPreset.LOW_TICK, 0),
        )

    private val loudness =
        Haptic.Custom(
            listOf(HapticEvent.Envelope(0, listOf(ControlPoint(850, 700, 40), ControlPoint(0, 300, 260)), 500)),
        )

    private fun valid(text: String): List<HapticEvent> {
        val parsed = HapticAiText.parse(text)
        assertTrue("expected a valid pattern, got $parsed", parsed is PatternParse.Valid)
        return (parsed as PatternParse.Valid).events
    }

    private fun problem(text: String): PatternProblem {
        val parsed = HapticAiText.parse(text)
        assertTrue("expected a refusal, got $parsed", parsed is PatternParse.Invalid)
        return (parsed as PatternParse.Invalid).problem
    }

    @Test
    fun aPatternRoundTripsThroughItsJson() {
        val json = HapticAiText.patternJson(pattern)
        assertEquals(pattern, valid(json))
        // The same JSON the editor stores as an override and backups carry.
        assertEquals(Haptic.Custom(pattern), HapticJson.fromOverride(json))
    }

    @Test
    fun theExportCarriesTheClipThePatternAndAWorkingExample() {
        val text =
            HapticAiText.export(
                "Owl",
                listOf(AiClip("clips/owl-hoot.ogg", 1240, loudness), AiClip("clips/owl-2.ogg", null, null)),
                pattern,
            )
        assertTrue(text.contains("Character: Owl."))
        assertTrue(text.contains("each of its 2 clips"))
        assertTrue(text.contains("Clip 1, \"clips/owl-hoot.ogg\": 1240 ms long."))
        assertTrue(text.contains("ms,loudness,sharpness\n0,0,50\n40,85,70\n300,0,30\n"))
        assertTrue(text.contains("Clip 2, \"clips/owl-2.ogg\": length unknown."))
        assertTrue(text.contains("No loudness data"))
        // Pasting the export back reads its current pattern (the first code block)...
        assertEquals(pattern, valid(text))
        // ...and the format's example is itself a valid pattern.
        val example = text.substringAfter("Example:")
        assertEquals(3, valid(example).size)
    }

    @Test
    fun theExportWorksWithoutClipsOrPattern() {
        val text = HapticAiText.export(null, emptyList(), emptyList())
        assertTrue(text.contains("No clip details"))
        assertTrue(text.contains("None yet."))
    }

    @Test
    fun theExportListsAtMostSixClips() {
        val clips = List(8) { AiClip("clips/c$it.ogg", 1000, null) }
        val text = HapticAiText.export("Frog", clips, emptyList())
        assertTrue(text.contains("Clip 6,"))
        assertTrue(!text.contains("Clip 7,"))
        assertTrue(text.contains("(2 more clips not listed.)"))
    }

    @Test
    fun loudnessRowsUseAbsoluteTimesAndPercent() {
        assertEquals(
            listOf(Triple(0, 0, 50), Triple(40, 85, 70), Triple(300, 0, 30)),
            HapticAiText.loudnessRows(loudness),
        )
    }

    @Test
    fun anAiAnswerIsFoundInsideProseAndCodeFences() {
        val json = HapticAiText.patternJson(pattern)
        assertEquals(pattern, valid("Here is your pattern:\n```json\n$json\n```\nEnjoy!"))
        assertEquals(pattern, valid("Sure! $json Hope it helps."))
        assertEquals(pattern, valid("""{"haptic":$json}"""))
        val events = json.substringAfter("\"events\":").dropLast(1)
        assertEquals(pattern, valid(events))
        assertEquals(pattern, valid("""{"events":$events}"""))
        assertEquals(pattern, valid("""{"mode":"custom","x-note":"by an AI","events":$events}"""))
        // A whole number written as 120.0 is still a whole number.
        assertEquals(
            listOf(HapticEvent.Preset(120, HapticPreset.CLICK)),
            valid("""[{"at":120.0,"preset":"CLICK"}]"""),
        )
    }

    @Test
    fun brokenPatternsAreRefusedWithTheRuleTheyBreak() {
        val event = PatternSpot(event = 1)
        val point = PatternSpot(event = 1, point = 1)
        val cases =
            mapOf(
                "I can't do that." to PatternProblem.NotJson,
                "{\"mode\":\"custom\",\"events\":[" to PatternProblem.NotJson,
                """{"mode":"auto"}""" to PatternProblem.NotCustom("auto"),
                """{"mode":"custom","events":[]}""" to PatternProblem.NoEvents,
                """{"mode":"custom"}""" to PatternProblem.NoEvents,
                """{"name":"x","events":[]}""" to PatternProblem.UnknownField(PatternSpot(), "name"),
                "[" + List(65) { """{"at":${it * 50},"preset":"TICK"}""" }.joinToString(",") + "]" to
                    PatternProblem.TooManyEvents,
                """[1]""" to PatternProblem.NotAnEvent(1),
                """[{"at":0}]""" to PatternProblem.NotAnEvent(1),
                """[{"at":0,"preset":"CLICK","envelope":[]}]""" to PatternProblem.NotAnEvent(1),
                """[{"preset":"CLICK"}]""" to PatternProblem.MissingField(event, "at"),
                """[{"at":0,"preset":"CLICK","note":"hi"}]""" to PatternProblem.UnknownField(event, "note"),
                """[{"at":12.5,"preset":"CLICK"}]""" to PatternProblem.NotWholeNumber(event, "at", "12.5"),
                """[{"at":"0","preset":"CLICK"}]""" to PatternProblem.NotWholeNumber(event, "at", "\"0\""),
                """[{"at":6000,"preset":"CLICK"}]""" to PatternProblem.OutOfRange(event, "at", 0, 5000, "6000"),
                """[{"at":0,"preset":"CLICK","scale":1.5}]""" to
                    PatternProblem.OutOfRange(event, "scale", 0, 1, "1.5"),
                """[{"at":0,"preset":"BUZZ"}]""" to PatternProblem.UnknownPreset(1, "BUZZ"),
                """[{"at":0,"envelope":[]}]""" to PatternProblem.PointCount(1, 0),
                """[{"at":0,"envelope":[{"intensity":1,"sharpness":0.5}]}]""" to
                    PatternProblem.MissingField(point, "ms"),
                """[{"at":0,"envelope":[{"intensity":1,"sharpness":0.5,"ms":0}]}]""" to
                    PatternProblem.OutOfRange(point, "ms", 1, 5000, "0"),
                """[{"at":0,"envelope":[{"intensity":-0.1,"sharpness":0.5,"ms":10}]}]""" to
                    PatternProblem.OutOfRange(point, "intensity", 0, 1, "-0.1"),
                """[{"at":0,"envelope":[{"intensity":1,"sharpness":0.5,"ms":100}]}]""" to PatternProblem.NotClosed(1),
                """[{"at":100,"preset":"CLICK"},{"at":50,"preset":"TICK"}]""" to PatternProblem.Unsorted(2),
                """[{"at":0,"envelope":[{"intensity":0,"sharpness":0.5,"ms":300}]},{"at":200,"preset":"TICK"}]""" to
                    PatternProblem.Overlap(2, 200, 300),
                """[{"at":4900,"envelope":[{"intensity":0,"sharpness":0.5,"ms":300}]}]""" to
                    PatternProblem.TooLong(5200),
            )
        cases.forEach { (text, expected) -> assertEquals(text, expected, problem(text)) }
        val tooManyPoints = List(17) { """{"intensity":0,"sharpness":0,"ms":10}""" }.joinToString(",")
        assertEquals(PatternProblem.PointCount(1, 17), problem("""[{"at":0,"envelope":[$tooManyPoints]}]"""))
    }
}
