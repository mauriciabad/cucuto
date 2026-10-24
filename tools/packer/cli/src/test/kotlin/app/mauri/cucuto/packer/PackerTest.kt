package app.mauri.cucuto.packer

import app.mauri.cucuto.packspec.DocumentKind
import app.mauri.cucuto.packspec.DocumentValidator
import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.FileInput
import app.mauri.cucuto.packspec.JvmPackIo
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.PackContent
import app.mauri.cucuto.packspec.PackValidator
import app.mauri.cucuto.packspec.SpecJson
import app.mauri.cucuto.packspec.media.AudioProbe
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PackerTest {
    private val repo = Path.of(System.getProperty("cucuto.repoRoot"))
    private val demoPacks = listOf("chiptune-critters", "kitchen-orchestra").map { repo.resolve("packs/$it") }

    private fun packer(vararg args: String) = Packer().subcommands(Build(), Validate(), Bundle(), Index(), Ring(), Cut()).test(args.toList())

    @Test
    fun `demo packs build, validate and are reproducible`(@TempDir tmp: Path) {
        for (src in demoPacks) {
            val a = tmp.resolve("a/${src.fileName}.soundpack.zip")
            val b = tmp.resolve("b/${src.fileName}.soundpack.zip")
            assertEquals(0, packer("build", src.toString(), "-o", a.toString()).statusCode)
            assertEquals(0, packer("build", src.toString(), "-o", b.toString()).statusCode)
            assertArrayEquals(a.readBytes(), b.readBytes(), "$src is not reproducible")
            val report = JvmPackIo.validatePath(a).report
            assertTrue(report.issues.isEmpty(), report.issues.toString())
            ZipFile(a.toFile()).use { zip ->
                val entries = zip.entries().toList()
                assertEquals("pack.json", entries.first().name)
                assertEquals(entries.drop(1).map { it.name }.sorted(), entries.drop(1).map { it.name })
                assertTrue(entries.all { it.timeLocal.year == 1980 })
            }
        }
    }

    @Test
    fun `pack yaml maps onto pack json`() {
        val loaded = PackSources.load(demoPacks[0])
        val manifest = loaded.manifest
        assertEquals(1, manifest["formatVersion"]!!.jsonPrimitive.content.toInt())
        val frog = manifest["characters"]!!.jsonArray.first().jsonObject
        assertEquals("characters/frog/sprite.svg", frog["sprite"]!!.jsonPrimitive.content)
        val clip = frog["clips"]!!.jsonArray.first().jsonObject
        assertEquals("characters/frog/clips/ribbit.ogg", clip["file"]!!.jsonPrimitive.content)
        assertEquals("Ribbit", clip["label"]!!.jsonPrimitive.content)
        // `when: night` on a clip expands like on a ringtone (day and night clips, #118).
        val croak = frog["clips"]!!.jsonArray[1].jsonObject
        assertEquals("night", croak["when"]!!.jsonObject["time"]!!.jsonPrimitive.content)
        val day = frog["ringtones"]!!.jsonArray.first().jsonObject
        assertEquals("day", day["when"]!!.jsonObject["time"]!!.jsonPrimitive.content)
        assertEquals("custom", frog["haptic"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        // A folder of clips expands to every audio file in it, sorted.
        val cat = manifest["characters"]!!.jsonArray[2].jsonObject
        assertEquals(listOf("mew", "mrrp", "purr"), (cat["clips"] as JsonArray).map { it.jsonObject["file"]!!.jsonPrimitive.content.substringAfterLast('/').substringBefore('.') })
        // Special clips (#120): YAML scalars of oneIn, firstOfDay and burst become JSON numbers and booleans.
        val bird = manifest["characters"]!!.jsonArray[1].jsonObject["clips"]!!.jsonArray.map { it.jsonObject }
        assertEquals("true", bird[3]["when"]!!.jsonObject["firstOfDay"]!!.jsonPrimitive.toString())
        assertEquals("3", bird[4]["when"]!!.jsonObject["burst"]!!.jsonPrimitive.toString())
        assertEquals("25", bird[5]["oneIn"]!!.jsonPrimitive.toString())
        assertTrue("LICENSE" in loaded.pack.files)
    }

    @Test
    fun `bundle and index`(@TempDir tmp: Path) {
        val packs = tmp.resolve("packs")
        for (src in demoPacks) assertEquals(0, packer("build", src.toString(), "-o", packs.toString()).statusCode)
        // Packs and bundles are always written as <id>-<version>.soundpack.zip (ADR 0007).
        assertTrue(packs.resolve("app.mauri.demo.chiptune-critters-1.2.0.soundpack.zip").toFile().isFile)
        val bundle = packs.resolve("app.mauri.demo.all-1.0.0.soundpack.zip")
        val result = packer(
            "bundle", "--id", "app.mauri.demo.all", "--name", "Cucuto demo packs", "--version", "1.0.0",
            "--license", "CC0-1.0", "--cover", demoPacks[0].resolve("cover.svg").toString(), "-o", bundle.toString(),
            *demoPacks.map { it.toString() }.toTypedArray(),
        )
        assertEquals(0, result.statusCode, result.output)
        val content = JvmPackIo.validatePath(bundle).content as PackContent.Bundle
        assertEquals(listOf("packs/app.mauri.demo.chiptune-critters", "packs/app.mauri.demo.kitchen-orchestra"), content.manifest.packs)

        val out = tmp.resolve("repo")
        val index = packer("index", "-o", out.toString(), "--name", "Demo", "--generated-at", "2026-10-23T00:00:00Z", packs.toString())
        assertEquals(0, index.statusCode, index.output)
        val json = out.resolve("repository.json")
        assertTrue(DocumentValidator.validate(DocumentKind.REPOSITORY, json.readBytes()).isValid)
        val entries = SpecJson.parseToJsonElement(json.readText()).jsonObject["packs"]!!.jsonArray.map { it as JsonObject }
        assertEquals(listOf("app.mauri.demo.all", "app.mauri.demo.chiptune-critters", "app.mauri.demo.kitchen-orchestra"), entries.map { it["id"]!!.jsonPrimitive.content })
        for (e in entries) {
            val download = e["download"]!!.jsonObject
            val file = out.resolve(download["url"]!!.jsonPrimitive.content)
            assertEquals(Indexer.sha256(file), download["sha256"]!!.jsonPrimitive.content)
            assertEquals(file.toFile().length(), download["size"]!!.jsonPrimitive.content.toLong())
        }
        assertTrue(out.resolve("previews/app.mauri.demo.chiptune-critters/frog.svg").toFile().isFile)
    }

    @Test
    fun `validate reports codes and exit status`() {
        val fixtures = repo.resolve("spec/fixtures")
        val ok = packer("validate", fixtures.resolve("valid/minimal-pack.soundpack.zip").toString())
        assertEquals(0, ok.statusCode, ok.output)
        val bad = packer("validate", "--json", fixtures.resolve("invalid/zip-slip.soundpack.zip").toString())
        assertEquals(1, bad.statusCode)
        assertTrue("UNSAFE_PATH" in bad.output, bad.output)
        val warnings = packer("validate", "--strict", fixtures.resolve("valid/with-warnings").toString())
        assertEquals(1, warnings.statusCode)
    }

    @Test
    fun `output names always end in zip`(@TempDir tmp: Path) {
        val bare = packer("build", demoPacks[0].toString(), "-o", tmp.resolve("x.soundpack").toString())
        assertEquals(2, bare.statusCode, bare.output)
        assertTrue(tmp.toFile().listFiles()!!.isEmpty())
        // Any .zip name is taken as is; the type is detected from the contents, not the name.
        val plain = tmp.resolve("plain.zip")
        assertEquals(0, packer("build", demoPacks[0].toString(), "-o", plain.toString()).statusCode)
        assertEquals(0, packer("validate", plain.toString()).statusCode)
    }

    private val hasFfmpeg = MediaTools.hasTool("ffmpeg") && MediaTools.hasTool("ffprobe")

    private fun durationMs(file: Path): Long = FileInput(file).use { AudioProbe.durationMs(FileFormat.OGG, it)!! }

    private fun assertNear(expected: Long, actual: Long, tolerance: Long = Limits.DURATION_TOLERANCE_MS) =
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected ms, got $actual ms")

    @Test
    fun `ring renders the intro once and repeats the loop to 90 s`(@TempDir tmp: Path) {
        assumeTrue(hasFfmpeg, "needs ffmpeg")
        // Kettle reel: 14 s, loop 4000..12000 ms, so 4 s + 11 x 8 s (spec/fixtures/ring-files.json).
        val out = tmp.resolve("kettle.ogg")
        val result = packer("ring", demoPacks[1].toString(), "-c", "kettle", "-v", "1", "-o", out.toString())
        assertEquals(0, result.statusCode, result.output)
        assertTrue("loop 4000..12000 ms x11" in result.output, result.output)
        assertNear(92_000, durationMs(out))

        // Every ringtone of a pack into a folder.
        val all = packer("ring", demoPacks[0].toString(), "-o", tmp.resolve("rings").toString())
        assertEquals(0, all.statusCode, all.output)
        for (n in 1..2) assertNear(96_000, durationMs(tmp.resolve("rings/app.mauri.demo.chiptune-critters-frog-$n.ogg")))

        // One file name for several ringtones is refused.
        assertEquals(2, packer("ring", demoPacks[0].toString(), "-o", tmp.resolve("x.ogg").toString()).statusCode)
    }

    @Test
    fun `cut finds the hook of a song`(@TempDir tmp: Path) {
        assumeTrue(hasFfmpeg, "needs ffmpeg")
        val song = demoPacks[1].resolve("kettle/ringtones/kettle-reel.ogg")
        val out = tmp.resolve("hook.ogg")
        val result = packer("cut", song.toString(), "-o", out.toString(), "--from-ms", "4000", "--to-ms", "12000")
        assertEquals(0, result.statusCode, result.output)
        // Within the searched loop section (ffmpeg versions may decode a few levels differently, so no exact start).
        val (start, end) = Regex("""(\d+)\.\.(\d+) ms""").find(result.output)!!.destructured.toList().map { it.toLong() }
        assertTrue(start >= 4000 && end <= 12000 && end - start == Limits.CUT_CLIP_DEFAULT_MS, result.output)
        assertNear(Limits.CUT_CLIP_DEFAULT_MS, durationMs(out))
        val fixed = packer("cut", song.toString(), "-o", out.toString(), "--start-ms", "500", "--length-ms", "2000")
        assertEquals(0, fixed.statusCode, fixed.output)
        assertNear(2_000, durationMs(out))
        assertEquals(2, packer("cut", song.toString(), "-o", tmp.resolve("hook.mp3").toString()).statusCode)
    }

    @Test
    fun `pack yaml cut entries become clips`(@TempDir tmp: Path) {
        assumeTrue(hasFfmpeg, "needs ffmpeg")
        val src = tmp.resolve("src").createDirectories()
        demoPacks[1].resolve("kettle/ringtones/kettle-reel.ogg").copyTo(src.resolve("song.ogg"))
        demoPacks[1].resolve("kettle/sprite.svg").copyTo(src.resolve("sprite.svg"))
        src.resolve("pack.yaml").writeText(
            """
            id: io.example.song
            name: Song
            version: 1.0.0
            author: Tests
            license: CC0-1.0
            characters:
              - name: Song
                sprite: sprite.svg
                clips:
                  - cut: song.ogg
                  - cut: song.ogg
                    startMs: 1000
                    lengthMs: 3000
                    label: Opening
                ringtones: song.ogg
            """.trimIndent(),
        )
        val loaded = PackSources.load(src)
        val clips = loaded.manifest["characters"]!!.jsonArray[0].jsonObject["clips"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("characters/song/clips/song-hook.ogg", "characters/song/clips/song-hook-2.ogg"), clips.map { it["file"]!!.jsonPrimitive.content })
        assertEquals(listOf("Song (hook)", "Opening"), clips.map { it["label"]!!.jsonPrimitive.content })
        assertEquals("1000", clips[1]["x-cut"]!!.jsonObject["startMs"]!!.jsonPrimitive.content)
        val report = PackValidator().validate(loaded.pack).report
        assertTrue(report.issues.isEmpty(), report.issues.toString())
    }

    @Test
    fun `pack yaml writes every condition, special clips and ringtone-only characters`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").createDirectories()
        val frog = demoPacks[0].resolve("frog")
        frog.resolve("sprite.svg").copyTo(src.resolve("sprite.svg"))
        for (name in listOf("ribbit", "croak", "hop")) frog.resolve("clips/$name.ogg").copyTo(src.resolve("$name.ogg"))
        frog.resolve("ringtones/pond-song-day.ogg").copyTo(src.resolve("song.ogg"))
        demoPacks[0].resolve("cat/clips/mew.ogg").copyTo(src.resolve("mew.ogg"))
        src.resolve("pack.yaml").writeText(
            """
            id: io.example.conditions
            name: Conditions
            version: 1.0.0
            author: Tests
            license: CC0-1.0
            characters:
              - name: Frog
                sprite: sprite.svg
                clips:
                  - file: ribbit.ogg
                    when: [night, sat, sun]
                  - file: croak.ogg
                    when: [12-25, 12-24..01-06]
                    oneIn: 13
                  - file: hop.ogg
                    label: Good morning
                    when: { firstOfDay: true, time: 05:00-12:00 }
                  - file: mew.ogg
                    label: Impatient
                    when: { burst: 3, date: 10-25..10-31 }
              - name: Song
                sprite: sprite.svg
                ringtones:
                  - file: song.ogg
                    label: Day
                    when: [day, summer, autumn]
                    loop: false
                  - file: song.ogg
                    label: Night
                    when: 22:00-06:00
                    loop: { startMs: 1000 }
                    fadeInMs: 500
                    weight: 2
            """.trimIndent(),
        )
        val loaded = PackSources.load(src)
        val characters = loaded.manifest["characters"]!!.jsonArray.map { it.jsonObject }
        val whens = characters[0]["clips"]!!.jsonArray.map { it.jsonObject["when"].toString() }
        assertEquals(
            listOf(
                """{"time":"night","weekday":["sat","sun"]}""",
                """{"date":["12-25",{"from":"12-24","to":"01-06"}]}""",
                """{"firstOfDay":true,"time":{"from":"05:00","to":"12:00"}}""",
                """{"burst":3,"date":{"from":"10-25","to":"10-31"}}""",
            ),
            whens,
        )
        assertEquals("13", characters[0]["clips"]!!.jsonArray[1].jsonObject["oneIn"].toString())
        val ringtones = characters[1]["ringtones"]!!.jsonArray.map { it.jsonObject }
        assertEquals("""{"time":"day","season":["summer","autumn"]}""", ringtones[0]["when"].toString())
        assertEquals("""{"time":{"from":"22:00","to":"06:00"}}""", ringtones[1]["when"].toString())
        assertTrue("clips" !in characters[1])
        val report = PackValidator().validate(loaded.pack).report
        assertTrue(report.issues.isEmpty(), report.issues.toString())

        // Two kinds of time are refused with a plain message.
        src.resolve("pack.yaml").writeText(src.resolve("pack.yaml").readText().replace("[night, sat, sun]", "[night, day]"))
        val refused = packer("build", src.toString(), "-o", tmp.resolve("out").toString())
        assertEquals(2, refused.statusCode, refused.output)
        assertTrue("only one time of day" in refused.output, refused.output)
    }

    @Test
    fun `build refuses invalid packs`(@TempDir tmp: Path) {
        val result = packer("build", repo.resolve("spec/fixtures/invalid/clip-too-long").toString(), "-o", tmp.toString())
        assertEquals(1, result.statusCode)
        assertTrue("CLIP_DURATION_OUT_OF_RANGE" in result.output, result.output)
        assertTrue(tmp.toFile().listFiles()!!.isEmpty())
    }
}
