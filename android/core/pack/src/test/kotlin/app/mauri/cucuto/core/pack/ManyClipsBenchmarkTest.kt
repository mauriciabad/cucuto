package app.mauri.cucuto.core.pack

import app.mauri.cucuto.core.pack.media.AudioProbe
import app.mauri.cucuto.core.pack.media.AudioProbeResult
import app.mauri.cucuto.packspec.FileFormat
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/**
 * [PackImporter.prepare] on a pack shaped like the owner's in #279 ([SyntheticPack.writeManyClips]: 268 characters,
 * 1,541 short clips, 268 SVG sprites, about 32 MB).
 *
 * The audio decoders are the JVM stand-ins plus waits that model the platform decoders, at a fiftieth of the medians
 * measured on an API 37 emulator (ADR 0077): opening the file, creating a decoder, configuring it, and one round trip
 * to the codec process per packet (one at a time) or per batch (several packets per buffer). "Before" creates a decoder
 * for every file and sends one packet at a time; "after" keeps one decoder per worker session and sends batches. The
 * real decoders are measured by `LargePackDeviceBenchmark` on a device.
 */
class ManyClipsBenchmarkTest {
    @get:Rule
    val tmp = TemporaryFolder.builder().parentFolder(File("build/tmp").apply { mkdirs() }).build()

    @Test
    fun `prepares a pack with many clips quickly`() {
        val archive = SyntheticPack.writeManyClips(File(tmp.root, "many.soundpack.zip"))
        val before = prepare(archive, ModeledProbe(batches = false, keepDecoder = false), "before")
        val after = ModeledProbe(batches = true, keepDecoder = true)
        val times = (0 until RUNS).map { prepare(archive, after, "after $it") }
        val median = times.sorted()[RUNS / 2]
        println("ManyClipsBenchmark: before $before ms, after median $median ms (runs $times)")
        // Every worker reused its session, and every session was closed.
        assertEquals(after.sessionsOpened.get(), after.sessionsClosed.get())
        assertTrue(
            "${after.sessionsOpened} sessions",
            after.sessionsOpened.get() <= RUNS * PackImporter.DEFAULT_DECODE_PARALLELISM,
        )
        assertTrue("Before: $before ms, after: $median ms", median < before)
        assertTrue("Preparing the pack took $median ms (median of $times)", median < BOUND_MS)
    }

    private fun prepare(
        archive: File,
        audio: AudioProbe,
        name: String,
    ): Long {
        val importer = testImporter(tmp.newFolder(name.replace(' ', '-')), audio = audio)
        val decoding = mutableListOf<ImportProgress.Decoding>()
        val start = System.nanoTime()
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
        assertEquals((0..SyntheticPack.MANY_MEDIA_FILES).toList(), decoding.map { it.done })
        runBlocking { importer.discard(result.import) }
        println("ManyClipsBenchmark $name: $ms ms")
        return ms
    }

    /** The header stand-in plus the modeled waits of the platform decoders. */
    private class ModeledProbe(
        private val batches: Boolean,
        private val keepDecoder: Boolean,
    ) : AudioProbe {
        val sessionsOpened = AtomicInteger()
        val sessionsClosed = AtomicInteger()

        override fun probe(
            file: File,
            format: FileFormat,
            full: Boolean,
        ): AudioProbeResult = session().use { it.probe(file, format, full) }

        override fun session(): AudioProbe.Session {
            sessionsOpened.incrementAndGet()
            return object : AudioProbe.Session {
                private var hasDecoder = false

                override fun probe(
                    file: File,
                    format: FileFormat,
                    full: Boolean,
                ): AudioProbeResult =
                    headerAudioProbe.probe(file, format, full).also { r ->
                        if (r !is AudioProbeResult.Decoded) return@also
                        var nanos = OPEN_NANOS + CONFIGURE_NANOS
                        if (!keepDecoder || !hasDecoder) nanos += CREATE_NANOS
                        hasDecoder = true
                        nanos +=
                            if (batches) {
                                (file.length() / BATCH_INPUT_BYTES + 2) * BATCH_NANOS
                            } else {
                                r.durationMs / PACKET_MS * PACKET_NANOS
                            }
                        LockSupport.parkNanos(nanos)
                    }

                override fun close() {
                    sessionsClosed.incrementAndGet()
                }
            }
        }
    }

    private companion object {
        const val RUNS = 3
        const val BOUND_MS = 30_000L

        // A fiftieth of the emulator medians (ADR 0077): 25 ms to open a file, 60 ms to create a decoder, 40 ms to
        // configure and start it, 8.4 ms per packet one at a time, about 15 ms per round trip with batches.
        const val OPEN_NANOS = 500_000L
        const val CREATE_NANOS = 1_200_000L
        const val CONFIGURE_NANOS = 800_000L
        const val PACKET_NANOS = 170_000L
        const val BATCH_NANOS = 300_000L
        const val PACKET_MS = 20L
        const val BATCH_INPUT_BYTES = 64 * 1024L
    }
}
