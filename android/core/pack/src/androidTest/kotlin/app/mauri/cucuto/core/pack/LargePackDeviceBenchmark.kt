package app.mauri.cucuto.core.pack

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * How long [PackImporter.prepare] takes with the platform decoders on a pack shaped like the one in #241: 98
 * characters with a clip, two ringtones and a sprite each, built from fixture media. Each clip is a 6 s Opus file
 * decoded whole; each ringtone the same file, whose start and end are decoded as for a long ringtone. Not a pass or
 * fail check: run it by hand and read the `LargePackBenchmark` log lines.
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
        val archive = buildArchive()
        repeat(RUNS) { run ->
            val root = File(dir, "run$run")
            val importer =
                PackImporter(
                    store = PackStore(File(root, "packs")),
                    workDir = File(root, "work"),
                    verifier = MediaVerifier(PlatformAudioProbe(), PlatformImageProbe()),
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
            Log.i(TAG, "run $run: $total ms, phases $phases")
            assertTrue(result.toString(), result is ImportResult.Ready)
            root.deleteRecursively()
        }
    }

    private fun buildArchive(): File {
        val ogg = DeviceFixtures.file("valid/full-pack/characters/song/ringtones/day.ogg", dir).readBytes()
        val sprite = DeviceFixtures.file("valid/all-formats/characters/jpeg/sprite.jpg", dir).readBytes()
        val characters =
            (0 until CHARACTERS).joinToString(",") { i ->
                """{ "id": "c$i", "name": "Character $i", "sprite": "characters/c$i/sprite.jpg",
                "clips": [{ "file": "characters/c$i/clip.ogg" }],
                "ringtones": [{ "file": "characters/c$i/ring0.ogg" }, { "file": "characters/c$i/ring1.ogg" }] }"""
            }
        val manifest =
            """{ "formatVersion": 1, "type": "pack", "id": "app.mauri.benchmark.large", "name": "Large pack",
            "version": "1.0.0", "author": "Cucuto benchmark", "license": "CC0-1.0", "characters": [$characters] }"""
        val out = File(dir, "large.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write(manifest.encodeToByteArray())
            zip.closeEntry()
            for (i in 0 until CHARACTERS) {
                zip.stored("characters/c$i/sprite.jpg", sprite)
                for (name in listOf("clip", "ring0", "ring1")) zip.stored("characters/c$i/$name.ogg", ogg)
            }
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
        const val CHARACTERS = 98
        const val RUNS = 2
    }
}
