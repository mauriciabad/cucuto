package app.mauri.cucuto.core.pack

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.pack.media.AudioProbe
import app.mauri.cucuto.core.pack.media.MediaVerifier
import app.mauri.cucuto.core.pack.media.PlatformAudioProbe
import app.mauri.cucuto.core.pack.media.PlatformImageProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * How long [PackImporter.prepare] takes with the platform decoders, built from fixture media. Not a pass or fail
 * check: run it by hand and read the `LargePackBenchmark` log lines (total and time per import step).
 *
 * - [preparesALargePack]: shaped like the pack in #241, 98 characters with a clip, two ringtones and a sprite each.
 *   Each clip is a 6 s Opus file decoded whole; each ringtone the same file, whose start and end are decoded as for a
 *   long ringtone.
 * - [preparesAPackWithManyClips]: shaped like the pack in #279, 268 characters with 1,541 short clips in all (five
 *   0.5 s clips for every 6 s one, 1.4 s on average) and an SVG sprite each.
 *
 * Both run "before" (a new decoder for every file, one packet at a time, as up to v0.1.7) once, then "after" (one
 * decoder per worker, batches; ADR 0077).
 */
@RunWith(AndroidJUnit4::class)
class LargePackDeviceBenchmark {
    private val dir = DeviceFixtures.newDir("benchmark")

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun preparesALargePack() {
        val archive = buildLargeArchive()
        measure(archive, before, "large before")
        repeat(RUNS) { run -> measure(archive, PlatformAudioProbe(), "large after, run $run") }
    }

    @Test
    fun preparesAPackWithManyClips() {
        val archive = buildManyClipsArchive()
        measure(archive, before, "many clips before")
        repeat(RUNS) { run -> measure(archive, PlatformAudioProbe(), "many clips after, run $run") }
    }

    /** Up to v0.1.7: no sessions (the default session probes with a new decoder every time), one packet at a time. */
    private val before =
        AudioProbe {
            file,
            format,
            full,
            ->
            PlatformAudioProbe(batches = false).probe(file, format, full)
        }

    private fun measure(
        archive: File,
        audio: AudioProbe,
        label: String,
    ) {
        val root = File(dir, label.replace(Regex("[^a-z0-9]+"), "-"))
        val importer =
            PackImporter(
                store = PackStore(File(root, "packs")),
                workDir = File(root, "work"),
                verifier = MediaVerifier(audio, PlatformImageProbe()),
                ioDispatcher = Dispatchers.IO,
            )
        val phases = linkedMapOf<String, Long>()
        val start = System.nanoTime()
        var last = start
        var lastName = "Start"
        val events =
            runBlocking {
                importer
                    .prepare(ImportSource.of(archive))
                    .onEach { e ->
                        val name = (e as? ImportEvent.Progress)?.progress?.javaClass?.simpleName ?: "Finished"
                        if (name != lastName) {
                            val now = System.nanoTime()
                            phases[lastName] = (phases[lastName] ?: 0) + (now - last) / 1_000_000
                            last = now
                            lastName = name
                        }
                    }.toList()
            }
        val total = (System.nanoTime() - start) / 1_000_000
        val result = (events.last() as ImportEvent.Finished).result
        Log.i(TAG, "$label: $total ms, phases $phases")
        assertTrue(result.toString(), result is ImportResult.Ready)
        runBlocking { importer.discard((result as ImportResult.Ready).import) }
        root.deleteRecursively()
    }

    private fun buildLargeArchive(): File {
        val ogg = DeviceFixtures.file("valid/full-pack/characters/song/ringtones/day.ogg", dir).readBytes()
        val sprite = DeviceFixtures.file("valid/all-formats/characters/jpeg/sprite.jpg", dir).readBytes()
        val characters =
            (0 until LARGE_CHARACTERS).joinToString(",") { i ->
                """{ "id": "c$i", "name": "Character $i", "sprite": "characters/c$i/sprite.jpg",
                "clips": [{ "file": "characters/c$i/clip.ogg" }],
                "ringtones": [{ "file": "characters/c$i/ring0.ogg" }, { "file": "characters/c$i/ring1.ogg" }] }"""
            }
        return zip("large.zip", manifest("app.mauri.benchmark.large", characters)) { zip ->
            for (i in 0 until LARGE_CHARACTERS) {
                zip.stored("characters/c$i/sprite.jpg", sprite)
                for (name in listOf("clip", "ring0", "ring1")) zip.stored("characters/c$i/$name.ogg", ogg)
            }
        }
    }

    private fun buildManyClipsArchive(): File {
        val short = DeviceFixtures.file("valid/all-formats/characters/avif/clip.ogg", dir).readBytes()
        val long = DeviceFixtures.file("valid/full-pack/characters/song/ringtones/day.ogg", dir).readBytes()
        val sprite = DeviceFixtures.file("valid/full-pack/characters/robin/sprite.svg", dir).readBytes()
        val counts =
            List(MANY_CHARACTERS) {
                MANY_CLIPS / MANY_CHARACTERS +
                    if (it < MANY_CLIPS % MANY_CHARACTERS) 1 else 0
            }
        val characters =
            (0 until MANY_CHARACTERS).joinToString(",") { i ->
                val clips = (0 until counts[i]).joinToString(",") { """{ "file": "characters/c$i/clips/$it.ogg" }""" }
                """{ "id": "c$i", "name": "Character $i", "sprite": "characters/c$i/sprite.svg", "clips": [$clips] }"""
            }
        var clip = 0
        return zip("many.zip", manifest("app.mauri.benchmark.many", characters)) { zip ->
            for (i in 0 until MANY_CHARACTERS) {
                zip.stored("characters/c$i/sprite.svg", sprite)
                repeat(counts[i]) { zip.stored("characters/c$i/clips/$it.ogg", if (clip++ % 6 == 5) long else short) }
            }
        }
    }

    private fun manifest(
        id: String,
        characters: String,
    ) = """{ "formatVersion": 1, "type": "pack", "id": "$id", "name": "Benchmark pack",
        "version": "1.0.0", "author": "Cucuto benchmark", "license": "CC0-1.0", "characters": [$characters] }"""

    private fun zip(
        name: String,
        manifest: String,
        media: (ZipOutputStream) -> Unit,
    ): File {
        val out = File(dir, name)
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write(manifest.encodeToByteArray())
            zip.closeEntry()
            media(zip)
        }
        return out
    }

    private fun ZipOutputStream.stored(
        path: String,
        bytes: ByteArray,
    ) {
        val entry = ZipEntry(path)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = CRC32().apply { update(bytes) }.value
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    private companion object {
        const val TAG = "LargePackBenchmark"
        const val LARGE_CHARACTERS = 98
        const val MANY_CHARACTERS = 268
        const val MANY_CLIPS = 1_541
        const val RUNS = 2
    }
}
