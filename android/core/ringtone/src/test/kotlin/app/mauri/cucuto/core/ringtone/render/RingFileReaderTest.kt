package app.mauri.cucuto.core.ringtone.render

import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.LoopRange
import app.mauri.cucuto.packspec.RingFilePlan
import app.mauri.cucuto.packspec.RingFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parity with packer: the app's ring file splice ([RingFileReader]) against every golden plan in
 * `spec/fixtures/ring-files.json` (docs/pack-format.md#ring-files). A synthetic 48 kHz source whose samples encode
 * their own frame index proves that every piece copies exactly the frames the plan names (sample-exact splices), that
 * the fade-in happens once, and that the silence and the total length match.
 */
class RingFileReaderTest {
    private val rate = 48_000

    @Test
    fun everyGoldenPlanRendersFrameExactly() {
        val fixture = Json.parseToJsonElement(File(specDir(), "fixtures/ring-files.json").readText()).jsonObject
        assertEquals(Limits.RING_FILE_MIN_MS, fixture["ringFileMinMs"]!!.jsonPrimitive.long)
        val cases = fixture["cases"] as JsonArray
        assertTrue(cases.size >= 10)
        for (element in cases) {
            val case = element.jsonObject
            val name = case["description"]!!.jsonPrimitive.content
            val sourceMs = case["sourceMs"]!!.jsonPrimitive.long
            val fadeInMs = case["fadeInMs"]!!.jsonPrimitive.long
            val expected = case["expected"]!!.jsonObject

            // A source decoded to exactly sourceMs at 48 kHz, as the platform decoder plus resampler deliver it.
            val source = IndexSource(frames = RingFilePlan.frame(sourceMs, rate), channels = 2)
            val decodedMs = source.frames * 1000 / rate
            val plan = RingFiles.plan(loopOf(case["loop"]), fadeInMs, decodedMs)

            assertEquals(name, expected["loopStartMs"]!!.jsonPrimitive.long, plan.loopStartMs)
            assertEquals(name, expected["loopEndMs"]!!.jsonPrimitive.long, plan.loopEndMs)
            assertEquals(name, expected["loopCount"]!!.jsonPrimitive.int, plan.loopCount)
            assertEquals(name, expected["silenceMs"]!!.jsonPrimitive.long, plan.silenceMs)
            assertEquals(name, expected["fadeInMs"]!!.jsonPrimitive.long, plan.fadeInMs)
            assertEquals(name, expected["totalMs"]!!.jsonPrimitive.long, plan.totalMs)
            assertEquals(name, expected["passThrough"]!!.jsonPrimitive.boolean, plan.isPassThrough)

            checkRendering(name, source, plan)
        }
    }

    @Test
    fun shortReadsAcrossPieceBoundariesGiveTheSameFile() {
        val source = IndexSource(frames = 6_000L * 48, channels = 1)
        val plan = RingFiles.plan(LoopRange(1000, 5000), 500, 6_000)
        val whole = readAll(RingFileReader(source, plan, rate), chunk = 4096, channels = 1)
        val odd = readAll(RingFileReader(source, plan, rate), chunk = 997, channels = 1)
        assertTrue(whole.contentEquals(odd))
    }

    private fun checkRendering(
        name: String,
        source: IndexSource,
        plan: RingFilePlan,
    ) {
        val reader = RingFileReader(source, plan, rate)
        val totalFrames = RingFilePlan.frame(plan.totalMs, rate)
        assertEquals(name, totalFrames, reader.totalFrames)
        val start = RingFilePlan.frame(plan.loopStartMs, rate)
        val end = RingFilePlan.frame(plan.loopEndMs, rate)
        val loop = end - start
        val fade = RingFilePlan.frame(plan.fadeInMs, rate)
        val soundFrames = start + plan.loopCount * loop

        val chunk = ShortArray(4096 * source.channels)
        var position = 0L
        while (true) {
            val n = reader.read(chunk, 4096)
            if (n == 0) break
            for (i in 0 until n) {
                val p = position + i
                val sourceFrame =
                    when {
                        p < start -> p
                        p < soundFrames -> start + (p - start) % loop
                        else -> -1L
                    }
                for (c in 0 until source.channels) {
                    val actual = chunk[i * source.channels + c]
                    if (sourceFrame < 0) {
                        if (actual.toInt() != 0) throw AssertionError("$name: frame $p should be silence")
                    } else {
                        val raw = source.sample(sourceFrame, c)
                        val want = if (p < fade) Math.round(raw * (p.toFloat() / fade)).toShort() else raw
                        if (actual !=
                            want
                        ) {
                            throw AssertionError("$name: frame $p channel $c is $actual, expected $want")
                        }
                    }
                }
            }
            position += n
        }
        assertEquals(name, totalFrames, position)
    }

    private fun readAll(
        reader: RingFileReader,
        chunk: Int,
        channels: Int,
    ): ShortArray {
        val out = ShortArray((reader.totalFrames * channels).toInt())
        val buffer = ShortArray(chunk * channels)
        var at = 0
        while (true) {
            val n = reader.read(buffer, chunk)
            if (n == 0) break
            buffer.copyInto(out, at, 0, n * channels)
            at += n * channels
        }
        return out
    }

    /** Sample value encodes the frame index and channel, so every misplaced frame is detected. */
    private class IndexSource(
        override val frames: Long,
        override val channels: Int,
    ) : PcmSource {
        fun sample(
            frame: Long,
            channel: Int,
        ): Short = ((frame * 7 + channel * 3) % 30_011 - 15_000).toShort()

        override fun read(
            from: Long,
            into: ShortArray,
            count: Int,
        ) {
            require(from >= 0 && from + count <= frames) { "Read past the source: $from+$count of $frames" }
            for (i in 0 until count) for (c in 0 until channels) into[i * channels + c] = sample(from + i, c)
        }
    }

    private fun loopOf(element: kotlinx.serialization.json.JsonElement?): LoopRange? =
        when (element) {
            null -> {
                LoopRange(null, null)
            }

            is JsonPrimitive -> {
                if (element.booleanOrNull == false) null else LoopRange(null, null)
            }

            is JsonObject -> {
                LoopRange(
                    (element["startMs"] as? JsonPrimitive)?.longOrNull,
                    (element["endMs"] as? JsonPrimitive)?.longOrNull,
                )
            }

            else -> {
                LoopRange(null, null)
            }
        }

    private fun specDir(): File =
        File(checkNotNull(System.getProperty("cucuto.specDir")) { "cucuto.specDir is not set" })
}
