package app.mauri.cucuto.core.pack

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.pack.media.AudioProbeResult
import app.mauri.cucuto.core.pack.media.ImageProbeResult
import app.mauri.cucuto.core.pack.media.PlatformAudioProbe
import app.mauri.cucuto.core.pack.media.PlatformImageProbe
import app.mauri.cucuto.packspec.FileFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** The platform decoders on every format of the golden fixtures. */
@RunWith(AndroidJUnit4::class)
class PlatformProbesTest {
    private val dir = DeviceFixtures.newDir("probes")
    private val audio = PlatformAudioProbe()
    private val image = PlatformImageProbe()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun decodesEveryAudioFormat() {
        // Durations measured from the headers by pack-spec (its RulesTest); the platform must agree within 60 ms.
        val expected =
            mapOf(
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
            for (full in listOf(true, false)) {
                val result = audio.probe(DeviceFixtures.file(path, dir), FileFormat.of(path)!!, full)
                assertTrue("$path (full=$full): $result", result is AudioProbeResult.Decoded)
                val actual = (result as AudioProbeResult.Decoded).durationMs
                assertTrue("$path (full=$full): $actual ms, expected about $ms ms", abs(actual - ms) <= 60)
            }
        }
    }

    @Test
    fun oneSessionDecodesEveryFormatLikeFreshProbes() {
        // One session keeps its decoder between files (ADR 0077): mixed formats, the same file twice in a row, clips
        // and ringtones, and a broken file in the middle must give the same results as a new probe for every file,
        // with batches and one packet at a time.
        val files =
            listOf(
                "valid/all-formats/characters/avif/clip.ogg",
                "valid/all-formats/characters/avif/clip.ogg",
                "valid/full-pack/characters/song/ringtones/day.ogg",
                "valid/all-formats/characters/jxl/clip.mp3",
                "invalid/audio-undecodable/characters/beep/clips/beep.ogg",
                "valid/all-formats/characters/svg/clip.opus",
                "valid/all-formats/characters/jpeg/clip.m4a",
                "valid/all-formats/characters/webp/clip.aac",
                "valid/all-formats/characters/gif/clip.flac",
                "valid/all-formats/characters/png/clip.wav",
                "valid/all-formats/characters/avif/clip.ogg",
            ).mapIndexed { i, path -> DeviceFixtures.file(path, java.io.File(dir, "s$i").apply { mkdirs() }) to path }
        for (batches in listOf(true, false)) {
            val probe = PlatformAudioProbe(batches = batches)
            for (full in listOf(true, false)) {
                val fresh = files.map { (file, path) -> probe.probe(file, FileFormat.of(path)!!, full) }
                val reused =
                    probe.session().use { s ->
                        files.map { (file, path) -> s.probe(file, FileFormat.of(path)!!, full) }
                    }

                // Reasons may be worded differently; what counts is decoded or not, and the duration.
                fun List<AudioProbeResult>.outcomes() = map { (it as? AudioProbeResult.Decoded)?.durationMs ?: -1L }
                assertEquals("batches=$batches full=$full", fresh.outcomes(), reused.outcomes())
                fresh.forEachIndexed { i, r ->
                    val undecodable = "audio-undecodable" in files[i].second
                    assertEquals("${files[i].second}: $r", undecodable, r is AudioProbeResult.Undecodable)
                }
            }
        }
    }

    @Test
    fun batchesAndOnePacketAtATimeAgree() {
        val path = "valid/full-pack/characters/song/ringtones/day.ogg"
        val file = DeviceFixtures.file(path, dir)
        for (full in listOf(true, false)) {
            assertEquals(
                PlatformAudioProbe(batches = false).probe(file, FileFormat.OGG, full),
                PlatformAudioProbe(batches = true).probe(file, FileFormat.OGG, full),
            )
        }
    }

    @Test
    fun rejectsAudioThatIsNotAudio() {
        val path = "invalid/audio-undecodable/characters/beep/clips/beep.ogg"
        val result = audio.probe(DeviceFixtures.file(path, dir), FileFormat.OGG, true)
        assertTrue(result.toString(), result is AudioProbeResult.Undecodable)
    }

    @Test
    fun decodesEveryBitmapFormat() {
        val expected =
            mapOf(
                "valid/all-formats/characters/png/sprite.png" to ImageProbeResult.Decoded(16, 16),
                "valid/all-formats/characters/jpeg/sprite.jpg" to ImageProbeResult.Decoded(16, 16),
                "valid/all-formats/characters/webp/sprite.webp" to ImageProbeResult.Decoded(16, 16),
                "valid/all-formats/characters/gif/sprite.gif" to ImageProbeResult.Decoded(16, 16),
                "valid/all-formats/characters/avif/sprite.avif" to ImageProbeResult.Decoded(64, 64),
                // JPEG XL: jxl-rs through :core:jxl (no platform decoder on Android 17).
                "valid/all-formats/characters/jxl/sprite.jxl" to ImageProbeResult.Decoded(16, 16),
                "valid/full-pack/characters/song/album.jxl" to ImageProbeResult.Decoded(16, 16),
                "invalid/image-too-large/characters/beep/sprite.png" to ImageProbeResult.Decoded(600, 600),
            )
        for ((path, result) in expected) {
            assertEquals(path, result, image.probe(DeviceFixtures.file(path, dir), FileFormat.of(path)!!))
        }
        // No platform decoder: SVG (safety rules in pack-spec).
        val svg = "valid/all-formats/characters/svg/sprite.svg"
        val result = image.probe(DeviceFixtures.file(svg, dir), FileFormat.SVG)
        assertTrue("$svg: $result", result is ImageProbeResult.NotChecked)
    }

    @Test
    fun rejectsJxlThatIsNotJxl() {
        // A PNG renamed to .jxl, and a JPEG XL file cut in half.
        val png = DeviceFixtures.file("valid/all-formats/characters/png/sprite.png", dir)
        assertTrue(image.probe(png, FileFormat.JXL) is ImageProbeResult.Undecodable)
        val jxl = DeviceFixtures.file("valid/all-formats/characters/jxl/sprite.jxl", dir)
        val half = java.io.File(dir, "half.jxl").apply { writeBytes(jxl.readBytes().let { it.copyOf(it.size / 2) }) }
        val result = image.probe(half, FileFormat.JXL)
        assertTrue(result.toString(), result is ImageProbeResult.Undecodable)
    }

    @Test
    fun rejectsImagesThatAreNotImages() {
        val path = "invalid/image-undecodable/characters/beep/sprite.png"
        val result = image.probe(DeviceFixtures.file(path, dir), FileFormat.PNG)
        assertTrue(result.toString(), result is ImageProbeResult.Undecodable)
    }
}
