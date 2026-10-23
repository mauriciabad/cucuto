package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.media.AudioProbe
import app.mauri.cucuto.packspec.media.ImageProbe
import app.mauri.cucuto.packspec.media.ImageSize
import app.mauri.cucuto.packspec.media.SvgSafety
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.math.abs

class RulesTest {
    private val fixtures = Path.of(System.getProperty("cucuto.specDir")).resolve("fixtures")

    /** A source that reports sizes without holding data, for limits that need huge inputs. */
    private class SizedSource(private val base: PackSource, extra: List<SourceEntry>) : PackSource {
        override val entries = base.entries + extra
        override fun read(path: String, maxBytes: Long) = base.read(path, maxBytes)
        override fun readHead(path: String, count: Int): ByteArray =
            if (base.entries.any { it.path == path }) base.readHead(path, count) else ByteArray(count)
    }

    private fun minimal() = FolderPackSource(fixtures.resolve("valid/minimal-pack"))

    @Test
    fun `file too large`() {
        val src = SizedSource(minimal(), listOf(SourceEntry("characters/beep/clips/huge.wav", Limits.AUDIO_FILE_BYTES + 1, EntryKind.FILE)))
        assertEquals(listOf("FILE_TOO_LARGE"), PackValidator().validate(src).report.errorCodes())
    }

    @Test
    fun `too many files`() {
        val extra = (0..Limits.FILES_PER_PACK).map { SourceEntry("notes/n$it.txt", 1, EntryKind.FILE) }
        assertEquals(listOf("TOO_MANY_FILES"), PackValidator().validate(SizedSource(minimal(), extra)).report.errorCodes())
    }

    @Test
    fun `unpacked too large and large pack warning`() {
        val big = (0 until 11).map { SourceEntry("characters/beep/clips/big$it.wav", 99 * Limits.MiB, EntryKind.FILE) }
        assertEquals(listOf("UNPACKED_TOO_LARGE"), PackValidator().validate(SizedSource(minimal(), big)).report.errorCodes())
        val large = (0 until 3).map { SourceEntry("characters/beep/clips/big$it.wav", 99 * Limits.MiB, EntryKind.FILE) }
        val report = PackValidator(checkMedia = false).validate(SizedSource(minimal(), large)).report
        assertTrue("LARGE_PACK" in report.warningCodes(), report.issues.toString())
    }

    @Test
    fun `audio durations from headers`() {
        val expected = mapOf(
            "valid/all-formats/characters/png/clip.wav" to 300L,
            "valid/all-formats/characters/jpeg/clip.m4a" to 500L,
            "valid/all-formats/characters/webp/clip.aac" to 500L,
            "valid/all-formats/characters/gif/clip.flac" to 400L,
            "valid/all-formats/characters/avif/clip.ogg" to 500L,
            "valid/all-formats/characters/jxl/clip.mp3" to 600L,
            "valid/all-formats/characters/svg/clip.opus" to 500L,
            "valid/full-pack/characters/song/ringtones/day.ogg" to 6000L,
        )
        for ((path, ms) in expected) {
            val format = FileFormat.of(path)!!
            val actual = AudioProbe.durationMs(format, fixtures.resolve(path).readBytes())
            assertNotNull(actual, path)
            assertTrue(abs(actual!! - ms) <= 60, "$path: $actual ms, expected about $ms ms")
        }
    }

    @Test
    fun `image sizes from headers`() {
        val expected = mapOf(
            "valid/all-formats/characters/png/sprite.png" to ImageSize(16, 16),
            "valid/all-formats/characters/jpeg/sprite.jpg" to ImageSize(16, 16),
            "valid/all-formats/characters/webp/sprite.webp" to ImageSize(16, 16),
            "valid/all-formats/characters/gif/sprite.gif" to ImageSize(16, 16),
            "valid/all-formats/characters/avif/sprite.avif" to ImageSize(64, 64),
            "valid/all-formats/characters/jxl/sprite.jxl" to ImageSize(16, 16),
            "invalid/image-too-large/characters/beep/sprite.png" to ImageSize(600, 600),
        )
        for ((path, size) in expected) {
            assertEquals(size, ImageProbe.size(FileFormat.of(path)!!, fixtures.resolve(path).readBytes()), path)
        }
    }

    @Test
    fun `svg rules`() {
        fun check(svg: String) = SvgSafety.check(svg.encodeToByteArray())
        assertEquals(SvgSafety.Result.Safe, check("""<svg xmlns="http://www.w3.org/2000/svg"><defs><linearGradient id="g"/></defs><rect fill="url(#g)" width="1" height="1"/><use href="#g"/></svg>"""))
        assertEquals(SvgSafety.Result.Safe, check("""<?xml version="1.0" encoding="UTF-8"?><!-- c --><svg><style><![CDATA[ rect { fill: red } ]]></style></svg>"""))
        assertTrue(check("""<svg><rect style="fill:url(https://x.test/a)"/></svg>""") is SvgSafety.Result.Unsafe)
        assertTrue(check("""<svg><foreignObject/></svg>""") is SvgSafety.Result.Unsafe)
        assertTrue(check("""<svg><set attributeName="href" to="https://x.test"/></svg>""") is SvgSafety.Result.Unsafe)
        assertTrue(check("""<svg><image href="data:image/svg+xml;base64,PHN2Zz4="/></svg>""") is SvgSafety.Result.Unsafe)
        assertTrue(check("""<svg><rect></svg>""") is SvgSafety.Result.Malformed)
    }

    @Test
    fun `spdx expressions`() {
        for (ok in listOf("CC0-1.0", "MIT OR Apache-2.0", "(MIT AND CC-BY-4.0) OR GPL-3.0-or-later", "GPL-2.0-only WITH Classpath-exception-2.0", "LicenseRef-my-pack", "cc-by-sa-4.0", "Apache-2.0+")) {
            assertTrue(Spdx.isValidExpression(ok), ok)
        }
        for (bad in listOf("Personal use only", "Proprietary", "MIT OR", "(MIT", "CC0", "")) {
            assertFalse(Spdx.isValidExpression(bad), bad)
        }
    }

    @Test
    fun `paths`() {
        for (p in listOf("/a", "../a", "a/../b", "a/./b", "a\\b", "C:/a", "a//b", "a\u0000b")) assertTrue(PackPaths.isUnsafe(p), p)
        for (p in listOf("a", "a/b.ogg", "dir/")) assertFalse(PackPaths.isUnsafe(p), p)
        for (p in listOf("__MACOSX/x", ".DS_Store", "a/.hidden", "Thumbs.db")) assertTrue(PackPaths.isIgnored(p), p)
        for (p in listOf("a b.ogg", "-a.ogg", "é.ogg", "a/b/c/d/e/f/g/h/i.ogg")) assertTrue(PackPaths.isInvalidName(p), p)
    }

    @Test
    fun `semver precedence`() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.10.0", "2.0.0")
        val parsed = ordered.map { SemVer.parse(it)!! }
        assertEquals(parsed, parsed.shuffled(kotlin.random.Random(1)).sorted())
    }
}
