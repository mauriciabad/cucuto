package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.authoring.Authoring
import app.mauri.cucuto.packspec.authoring.ConditionForm
import app.mauri.cucuto.packspec.authoring.DateForm
import app.mauri.cucuto.packspec.authoring.FormProblem
import app.mauri.cucuto.packspec.authoring.LoopMode
import app.mauri.cucuto.packspec.authoring.Moment
import app.mauri.cucuto.packspec.authoring.RingtoneForm
import app.mauri.cucuto.packspec.authoring.TimeOfDay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

class ConditionsTest {
    private val fullPack =
        SpecJson.decodeFromString(
            PackManifest.serializer(),
            Path
                .of(System.getProperty("cucuto.specDir"))
                .resolve("fixtures/valid/full-pack/pack.json")
                .readText(),
        )

    private fun json(w: When?): JsonElement? = w?.let { SpecJson.encodeToJsonElement(When.serializer(), it) }

    private fun json(r: Ringtone): JsonElement = SpecJson.encodeToJsonElement(Ringtone.serializer(), r)

    @Test
    fun `every condition of the full pack survives the form`() {
        for (c in fullPack.characters) {
            for (clip in c.clips) {
                val form = ConditionForm.of(clip.`when`, clip.oneIn)
                assertTrue(form.problems(clip = true).isEmpty(), "${clip.file}: ${form.problems(true)}")
                assertEquals(json(clip.`when`), json(form.toWhen(clip = true)), clip.file)
                assertEquals(clip.oneIn, form.oneIn)
            }
            for (ringtone in c.ringtones) {
                val form = RingtoneForm.of(ringtone)
                assertTrue(form.problems(durationMs = null).isEmpty(), "${ringtone.label}: ${form.problems(null)}")
                // `loop: true` is the default, so it is left out.
                val expected = if (ringtone.loop == JsonPrimitive(true)) ringtone.copy(loop = null) else ringtone
                assertEquals(json(expected), json(form.toEntry(ringtone.file)), ringtone.label)
            }
        }
    }

    @Test
    fun `the form writes conditions in a canonical form`() {
        val form =
            ConditionForm(
                time = TimeOfDay.HOURS,
                from = "09:00",
                to = "12:30",
                seasons = listOf("winter", "autumn"),
                weekdays = listOf("sun"),
                dates = listOf(DateForm("12-25"), DateForm("10-25", "10-31"), DateForm("01-01", "01-01")),
            )
        val w = form.toWhen(clip = true)!!
        assertEquals("""{"from":"09:00","to":"12:30"}""", w.time.toString())
        assertEquals("""["autumn","winter"]""", w.season.toString())
        assertEquals("\"sun\"", w.weekday.toString())
        assertEquals("""["12-25",{"from":"10-25","to":"10-31"},"01-01"]""", w.date.toString())
        // All four seasons or all seven days are the same as none.
        assertNull(ConditionForm(seasons = ConditionForm.SEASONS).toWhen(clip = false))
        assertNull(ConditionForm(weekdays = ConditionForm.WEEKDAYS).toWhen(clip = false))
        assertTrue(ConditionForm().isEmpty(clip = true))
        assertFalse(ConditionForm(oneIn = 25).isEmpty(clip = true))
    }

    @Test
    fun `moments are written for clips only`() {
        val morning = ConditionForm(moment = Moment.FIRST_OF_DAY, time = TimeOfDay.DAY)
        assertEquals(true, morning.toWhen(clip = true)!!.firstOfDay)
        assertNull(morning.toWhen(clip = false)!!.firstOfDay)
        assertEquals(6, ConditionForm(moment = Moment.BURST, burst = 6).toWhen(clip = true)!!.burst)
        assertNull(ConditionForm(moment = Moment.BURST).toWhen(clip = false))
        assertEquals(Moment.BURST, ConditionForm.of(When(burst = 4)).moment)
        assertEquals(4, ConditionForm.of(When(burst = 4)).burst)
    }

    @Test
    fun `problems say what to fix`() {
        fun kinds(
            form: ConditionForm,
            clip: Boolean = true,
        ) = form.problems(clip).map { it.kind }
        assertEquals(listOf(FormProblem.Kind.TIME_RANGE_EMPTY), kinds(ConditionForm(time = TimeOfDay.HOURS, from = "08:00", to = "08:00")))
        assertEquals(listOf(FormProblem.Kind.TIME_INVALID), kinds(ConditionForm(time = TimeOfDay.HOURS, from = "24:00")))
        assertEquals(listOf(FormProblem.Kind.TIME_INVALID), kinds(ConditionForm(time = TimeOfDay.HOURS, to = "7:00")))
        assertEquals(
            listOf(FormProblem(FormProblem.Kind.DATE_INVALID, 1)),
            ConditionForm(dates = listOf(DateForm("02-29"), DateForm("02-30"))).problems(clip = false),
        )
        assertEquals(listOf(FormProblem.Kind.DATE_INVALID), kinds(ConditionForm(dates = listOf(DateForm("12-24", "13-01")))))
        assertEquals(listOf(FormProblem.Kind.TOO_MANY_DATES), kinds(ConditionForm(dates = List(51) { DateForm("01-01") })))
        assertEquals(listOf(FormProblem.Kind.BURST_OUT_OF_RANGE), kinds(ConditionForm(moment = Moment.BURST, burst = 1)))
        assertEquals(listOf(FormProblem.Kind.ONE_IN_OUT_OF_RANGE), kinds(ConditionForm(oneIn = 10_001)))
        // Clip-only fields do not matter for a ringtone.
        assertTrue(kinds(ConditionForm(moment = Moment.BURST, burst = 1, oneIn = 1), clip = false).isEmpty())
    }

    @Test
    fun `ringtone forms check the file and the loop`() {
        fun kinds(
            form: RingtoneForm,
            durationMs: Long?,
        ) = form.problems(durationMs).map { it.kind }
        assertEquals(listOf(FormProblem.Kind.RINGTONE_DURATION_OUT_OF_RANGE), kinds(RingtoneForm(), 4_000))
        assertEquals(listOf(FormProblem.Kind.RINGTONE_DURATION_OUT_OF_RANGE), kinds(RingtoneForm(), 361_000))
        assertTrue(kinds(RingtoneForm(), 5_000).isEmpty())
        val section = RingtoneForm(loop = LoopMode.SECTION, loopStartMs = 4_000, loopEndMs = 12_000)
        assertTrue(kinds(section, 14_000).isEmpty())
        assertEquals(listOf(FormProblem.Kind.LOOP_INVALID), kinds(section, 10_000))
        assertEquals(listOf(FormProblem.Kind.LOOP_INVALID), kinds(section.copy(loopEndMs = 4_500), 14_000))
        // An open loop must start at least 1 s before the end of the audio.
        assertEquals(listOf(FormProblem.Kind.LOOP_INVALID), kinds(section.copy(loopEndMs = null, loopStartMs = 13_500), 14_000))
        assertEquals(listOf(FormProblem.Kind.FADE_IN_OUT_OF_RANGE), kinds(RingtoneForm(fadeInMs = 10_001), null))
        assertEquals(listOf(FormProblem.Kind.WEIGHT_OUT_OF_RANGE), kinds(RingtoneForm(weight = 0.0), null))

        val entry = section.copy(label = " Pond song ", fadeInMs = 500, weight = 2.0).toEntry("song.ogg")
        assertEquals(
            """{"file":"song.ogg","label":"Pond song","loop":{"startMs":4000,"endMs":12000},"fadeInMs":500,"weight":2.0}""",
            json(entry).toString(),
        )
        assertEquals(JsonPrimitive(false), RingtoneForm(loop = LoopMode.ONCE).toEntry("a.ogg").loop)
        assertNull(RingtoneForm().toEntry("a.ogg").loop)
    }

    @Test
    fun `tags and homepages`() {
        assertEquals(listOf("animals", "8-bit-games"), Authoring.tags("Animals, 8-bit games,, animals ;"))
        assertEquals(20, Authoring.tags((1..30).joinToString(",") { "t$it" }).size)
        assertEquals(32, Authoring.tags("x".repeat(40)).single().length)
        assertTrue(Authoring.isHomepage(" https://example.com/packs "))
        assertFalse(Authoring.isHomepage("http://example.com"))
        assertFalse(Authoring.isHomepage("https://"))
        assertFalse(Authoring.isHomepage("https://exa mple.com"))
    }
}
