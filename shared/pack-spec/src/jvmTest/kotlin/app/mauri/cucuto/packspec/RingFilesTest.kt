package app.mauri.cucuto.packspec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.math.PI
import kotlin.math.sin

class RingFilesTest {
    private val fixtures = Path.of(System.getProperty("cucuto.specDir")).resolve("fixtures")

    /** Every case of spec/fixtures/ring-files.json: the layout every ring file renderer must produce. */
    @TestFactory
    fun `golden ring file plans`(): List<DynamicTest> {
        val json = Json.parseToJsonElement(fixtures.resolve("ring-files.json").readText()).jsonObject
        assertEquals(Limits.RING_FILE_MIN_MS, json["ringFileMinMs"]!!.jsonPrimitive.long)
        return json["cases"]!!.jsonArray.map { it.jsonObject }.map { case ->
            DynamicTest.dynamicTest(case["description"]!!.jsonPrimitive.content) {
                val ringtone = Ringtone(file = "r.ogg", loop = case["loop"], fadeInMs = case.long("fadeInMs").toInt())
                val plan = RingFiles.plan(ringtone, case.long("sourceMs"))
                val e = case["expected"]!!.jsonObject
                assertEquals(e.long("loopStartMs"), plan.loopStartMs)
                assertEquals(e.long("loopEndMs"), plan.loopEndMs)
                assertEquals(e.long("loopCount").toInt(), plan.loopCount)
                assertEquals(e.long("silenceMs"), plan.silenceMs)
                assertEquals(e.long("fadeInMs"), plan.fadeInMs)
                assertEquals(e.long("totalMs"), plan.totalMs)
                assertEquals(e["passThrough"]!!.jsonPrimitive.boolean, plan.isPassThrough)
            }
        }
    }

    private fun JsonObject.long(key: String) = this[key]!!.jsonPrimitive.long

    @Test
    fun `pieces are frame ranges of the source`() {
        val plan = RingFiles.plan(LoopRange(4000, 12000), 500, 14000)
        val pieces = plan.pieces(48000)
        assertEquals(RingPiece.Copy(0, 192_000), pieces.first())
        assertEquals(List(11) { RingPiece.Copy(192_000, 384_000) }, pieces.drop(1))
        assertEquals(plan.totalMs * 48, pieces.sumOf { it.frames })

        val once = RingFiles.plan(null, 0, 6000).pieces(8000)
        assertEquals(listOf(RingPiece.Copy(0, 48_000), RingPiece.Silence(672_000)), once)
    }

    @Test
    fun `invalid loops are refused`() {
        assertThrows(IllegalArgumentException::class.java) { RingFiles.plan(LoopRange(5500, null), 0, 6000) }
        assertThrows(IllegalArgumentException::class.java) { RingFiles.plan(LoopRange(4000, 1000), 0, 6000) }
    }

    @Test
    fun `the hook is the loudest stretch, started on its onset`() {
        val rate = 8000
        // 10 s: silence, a quiet hum from 5 s, then a chorus: a strong beat at 6.0 s and the loudest part from 6.2 s
        // to 8.7 s. The loudest 2.5 s start at 6.2 s; the onset snap moves the start back to the beat at 6.0 s.
        val samples = FloatArray(10 * rate) { i ->
            val t = i.toDouble() / rate
            val amplitude = when {
                t < 5.0 -> 0.0
                t >= 6.0 && t < 6.2 -> 0.8
                t >= 6.2 && t < 8.7 -> 0.9
                else -> 0.05
            }
            (amplitude * sin(2 * PI * 440 * t)).toFloat()
        }
        val cut = ClipCuts.findHook(samples, rate)
        assertEquals(ClipCut(6000, 8500), cut)
        assertEquals(Limits.CUT_CLIP_DEFAULT_MS, cut.lengthMs)

        // Restricted to the first 5 s, which are silent, the earliest window wins.
        assertEquals(ClipCut(0, 2000), ClipCuts.findHook(samples, rate, lengthMs = 2000, toMs = 5000))
        // A clip longer than the audio is the whole audio.
        assertEquals(ClipCut(0, 10_000), ClipCuts.findHook(samples, rate, lengthMs = 12_000))
    }

    @Test
    fun `silence gives the start of the search range`() {
        assertEquals(ClipCut(3000, 5500), ClipCuts.findHook(FloatArray(48000 * 8), 48000, fromMs = 3000))
    }
}
