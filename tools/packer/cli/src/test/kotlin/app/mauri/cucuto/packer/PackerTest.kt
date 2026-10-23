package app.mauri.cucuto.packer

import app.mauri.cucuto.packspec.DocumentKind
import app.mauri.cucuto.packspec.DocumentValidator
import app.mauri.cucuto.packspec.JvmPackIo
import app.mauri.cucuto.packspec.PackContent
import app.mauri.cucuto.packspec.SpecJson
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText

class PackerTest {
    private val repo = Path.of(System.getProperty("cucuto.repoRoot"))
    private val demoPacks = listOf("chiptune-critters", "kitchen-orchestra").map { repo.resolve("packs/$it") }

    private fun packer(vararg args: String) = Packer().subcommands(Build(), Validate(), Bundle(), Index()).test(args.toList())

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
        val day = frog["ringtones"]!!.jsonArray.first().jsonObject
        assertEquals("day", day["when"]!!.jsonObject["time"]!!.jsonPrimitive.content)
        assertEquals("custom", frog["haptic"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        // A folder of clips expands to every audio file in it, sorted.
        val bird = manifest["characters"]!!.jsonArray[1].jsonObject
        assertEquals(listOf("chirp", "trill", "whistle"), (bird["clips"] as JsonArray).map { it.jsonObject["file"]!!.jsonPrimitive.content.substringAfterLast('/').substringBefore('.') })
        assertTrue("LICENSE" in loaded.pack.files)
    }

    @Test
    fun `bundle and index`(@TempDir tmp: Path) {
        val packs = tmp.resolve("packs")
        for (src in demoPacks) assertEquals(0, packer("build", src.toString(), "-o", packs.toString()).statusCode)
        // Packs and bundles are always written as <id>-<version>.soundpack.zip (ADR 0007).
        assertTrue(packs.resolve("app.mauri.demo.chiptune-critters-1.0.0.soundpack.zip").toFile().isFile)
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

    @Test
    fun `build refuses invalid packs`(@TempDir tmp: Path) {
        val result = packer("build", repo.resolve("spec/fixtures/invalid/clip-too-long").toString(), "-o", tmp.toString())
        assertEquals(1, result.statusCode)
        assertTrue("CLIP_DURATION_OUT_OF_RANGE" in result.output, result.output)
        assertTrue(tmp.toFile().listFiles()!!.isEmpty())
    }
}
