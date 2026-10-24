package app.mauri.cucuto.core.pack

import app.mauri.cucuto.core.pack.media.AudioProbe
import app.mauri.cucuto.core.pack.media.AudioProbeResult
import app.mauri.cucuto.core.pack.media.ImageProbe
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [PackImporter.prepare] on a pack shaped like the one in #241 ([SyntheticPack]: 392 media files, about 300 MB).
 *
 * The decoders are the JVM stand-ins plus a wait that models the platform decoders: on an emulator, MediaCodec took
 * about 1 ms per 20 ms Opus packet, almost all of it waiting for the codec process (ADR 0061). Here it is a tenth of
 * that, so the test stays short, and an image takes 2 ms. Before #241 this took about 11 s on a laptop (4 s reading and
 * checking the archive, 7 s decoding one file at a time); the bound is generous so a slow CI runner never fails it.
 */
class LargePackBenchmarkTest {
    @get:Rule
    val tmp = TemporaryFolder.builder().parentFolder(File("build/tmp").apply { mkdirs() }).build()

    @Test
    fun `prepares a large pack quickly`() {
        val archive = SyntheticPack.write(File(tmp.root, "large.soundpack.zip"))
        val times =
            (0 until RUNS).map { run ->
                val importer = testImporter(tmp.newFolder("app$run"), audio = slowAudio, image = slowImage)
                val start = System.nanoTime()
                val decoding = mutableListOf<ImportProgress.Decoding>()
                val events =
                    runBlocking {
                        importer
                            .prepare(ImportSource.of(archive))
                            .onEach { e ->
                                ((e as? ImportEvent.Progress)?.progress as? ImportProgress.Decoding)?.let(decoding::add)
                            }.toList()
                    }
                val ms = (System.nanoTime() - start) / 1_000_000
                val result = (events.last() as ImportEvent.Finished).result
                assertTrue(result.toString(), result is ImportResult.Ready)
                assertEquals(emptyList<String>(), (result as ImportResult.Ready).import.report.errorCodes())
                // Determinate progress: every file is counted once, up to "392 of 392".
                assertEquals((0..SyntheticPack.MEDIA_FILES).toList(), decoding.map { it.done })
                assertTrue(decoding.all { it.total == SyntheticPack.MEDIA_FILES })
                runBlocking { importer.discard(result.import) }
                println("LargePackBenchmark run $run: $ms ms")
                ms
            }
        val median = times.sorted()[RUNS / 2]
        println("LargePackBenchmark median: $median ms")
        assertTrue("Preparing the large pack took $median ms (median of $times)", median < BOUND_MS)
    }

    private companion object {
        const val RUNS = 3
        const val BOUND_MS = 20_000L

        /** A tenth of the measured platform decoder time per Opus packet. */
        const val NANOS_PER_PACKET = 100_000L
        const val PACKET_MS = 20L

        /** How much of a long ringtone the platform probe decodes: its first and last 2 s. */
        const val PARTIAL_MS = 4_000L

        val slowAudio =
            AudioProbe { file, format, full ->
                headerAudioProbe.probe(file, format, full).also { r ->
                    if (r is AudioProbeResult.Decoded) {
                        val decodedMs = if (full) r.durationMs else minOf(r.durationMs, PARTIAL_MS)
                        val nanos = decodedMs / PACKET_MS * NANOS_PER_PACKET
                        Thread.sleep(nanos / 1_000_000, (nanos % 1_000_000).toInt())
                    }
                }
            }

        val slowImage =
            ImageProbe { file, format ->
                headerImageProbe.probe(file, format).also { Thread.sleep(2) }
            }
    }
}
