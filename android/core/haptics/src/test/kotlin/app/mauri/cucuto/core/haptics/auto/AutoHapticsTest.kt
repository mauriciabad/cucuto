package app.mauri.cucuto.core.haptics.auto

import app.mauri.cucuto.core.haptics.HapticCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/** [AutoHaptics.analyzePack] on several clips at once (#267), with a JVM stand-in for the platform decoder. */
class AutoHapticsTest {
    @get:Rule
    val temp = TemporaryFolder()

    /**
     * Synthetic PCM from the file name: `silent*` is silence, `broken*` cannot be decoded, `bug*` makes the decoder
     * throw something other than an IOException, anything else is a tone burst shaped by its index. Each decode
     * waits [waitMs], like a platform decoder waiting for the codec process.
     */
    private class FakeDecoder(
        private val waitMs: Long = 0,
    ) : PcmDecoder {
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val requests: MutableList<Pair<String, Int>> = Collections.synchronizedList(mutableListOf())

        override fun decode(
            file: File,
            maxDurationMs: Int,
        ): MonoPcm {
            requests += file.name to maxDurationMs
            val now = running.incrementAndGet()
            maxRunning.accumulateAndGet(now, ::maxOf)
            try {
                if (waitMs > 0) Thread.sleep(waitMs)
                val name = file.name
                return when {
                    name.startsWith("broken") -> throw IOException("Cannot decode $name")
                    name.startsWith("bug") -> throw IllegalStateException("Analyser bug")
                    name.startsWith("silent") -> MonoPcm(FloatArray(RATE), RATE)
                    else -> burst(name.filter(Char::isDigit).toIntOrNull() ?: 0, maxDurationMs)
                }
            } finally {
                running.decrementAndGet()
            }
        }

        private fun burst(
            index: Int,
            maxDurationMs: Int,
        ): MonoPcm {
            val ms = minOf(300 + index * 70 % 1_500, maxDurationMs)
            val frequency = 150.0 + index * 97 % 3_000
            val samples =
                FloatArray(RATE * ms / 1000) { i ->
                    val t = i.toDouble() / RATE
                    val gain = (1 - i.toDouble() / (RATE * ms / 1000)) * (0.2 + index % 5 * 0.15)
                    (gain * sin(2 * PI * frequency * t)).toFloat()
                }
            return MonoPcm(samples, RATE)
        }
    }

    private fun packDir(): File = temp.newFolder()

    private val clips: List<String> =
        (0 until 24).map { i ->
            when (i) {
                5 -> "characters/c5/clips/silent.ogg"
                9 -> "characters/c9/clips/broken.ogg"
                13 -> "characters/c13/clips/bug.ogg"
                else -> "characters/c$i/clips/voice$i.ogg"
            }
        }

    private fun haptics(
        decoder: PcmDecoder,
        parallelism: Int,
    ) = AutoHaptics(HapticCapabilities(), Dispatchers.IO, decoder, parallelism)

    @Test
    fun `several at once gives the same result in manifest order`() =
        runBlocking {
            val oneDir = packDir()
            val manyDir = packDir()
            val one = haptics(FakeDecoder(), parallelism = 1).analyzePack(oneDir, clips)
            val many = haptics(FakeDecoder(waitMs = 5), parallelism = 8).analyzePack(manyDir, clips)

            assertEquals(one, many)
            // Undecodable clips are left out (a later run retries them); silent ones are kept as null.
            val expected = clips.filterNot { "broken" in it || "bug" in it }
            assertEquals(expected, many.clips.keys.toList())
            assertNull(many.clips.getValue("characters/c5/clips/silent.ogg"))
            assertNotNull(many.clips.getValue("characters/c0/clips/voice0.ogg"))
            // The stored file is the same, byte for byte.
            assertEquals(
                File(oneDir, AutoHapticFile.RELATIVE_PATH).readText(),
                File(manyDir, AutoHapticFile.RELATIVE_PATH).readText(),
            )
            assertEquals(many, AutoHapticFile.read(manyDir))
        }

    @Test
    fun `decodes at most parallelism clips at once, each once and only its first 2 s`() =
        runBlocking {
            val decoder = FakeDecoder(waitMs = 20)
            haptics(decoder, parallelism = 4).analyzePack(packDir(), clips + clips.take(3))

            assertTrue("max ${decoder.maxRunning.get()}", decoder.maxRunning.get() in 2..4)
            assertEquals(clips.map { File(it).name }.sorted(), decoder.requests.map { it.first }.sorted())
            assertTrue(decoder.requests.all { it.second == AutoHapticAnalyzer.MAX_DURATION_MS })
        }

    @Test
    fun `reports progress once per clip, in order, up to the total`() =
        runBlocking {
            val reports = mutableListOf<Pair<Int, Int>>()
            haptics(FakeDecoder(waitMs = 2), parallelism = 8).analyzePack(packDir(), clips) { done, total ->
                reports += done to total
            }
            assertEquals((0..clips.size).map { it to clips.size }, reports)
        }

    @Test
    fun `an empty pack reports zero of zero and writes an empty set`() =
        runBlocking {
            val dir = packDir()
            val reports = mutableListOf<Pair<Int, Int>>()
            val set =
                haptics(
                    FakeDecoder(),
                    parallelism = 8,
                ).analyzePack(dir, emptyList()) { d, t -> reports += d to t }
            assertEquals(listOf(0 to 0), reports)
            assertTrue(set.clips.isEmpty())
            assertEquals(set, AutoHapticFile.read(dir))
        }

    @Test
    fun `clips that all fail still give a result`() =
        runBlocking {
            val broken = (0 until 10).map { "broken$it.ogg" }
            val set = haptics(FakeDecoder(), parallelism = 8).analyzePack(packDir(), broken)
            assertTrue(set.clips.isEmpty())
        }

    @Test
    fun `cancelling stops the analysis and writes nothing`() =
        runBlocking {
            val dir = packDir()
            val many = (0 until 200).map { "voice$it.ogg" }
            val job = async(Dispatchers.Default) { haptics(FakeDecoder(waitMs = 10), 4).analyzePack(dir, many) }
            delay(50)
            job.cancel()
            try {
                job.await()
                fail("Expected a cancellation")
            } catch (_: CancellationException) {
                // Expected.
            }
            assertFalse(File(dir, AutoHapticFile.RELATIVE_PATH).exists())
        }

    /** [FakeDecoder] behind sessions that count how they are used, like the platform decoder kept per worker. */
    private class SessionDecoder(
        private val inner: FakeDecoder = FakeDecoder(waitMs = 2),
    ) : PcmDecoder {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val decodedOutsideSessions = AtomicInteger()
        val concurrentUse = AtomicInteger()

        override fun decode(
            file: File,
            maxDurationMs: Int,
        ): MonoPcm {
            decodedOutsideSessions.incrementAndGet()
            return inner.decode(file, maxDurationMs)
        }

        override fun session(): PcmDecoder.Session {
            opened.incrementAndGet()
            return object : PcmDecoder.Session {
                private val busy = AtomicBoolean()

                override fun decode(
                    file: File,
                    maxDurationMs: Int,
                ): MonoPcm {
                    if (!busy.compareAndSet(false, true)) concurrentUse.incrementAndGet()
                    try {
                        return inner.decode(file, maxDurationMs)
                    } finally {
                        busy.set(false)
                    }
                }

                override fun close() {
                    closed.incrementAndGet()
                }
            }
        }
    }

    @Test
    fun `each worker keeps one session for all its clips and closes it`() =
        runBlocking {
            val decoder = SessionDecoder()
            val sessions = haptics(decoder, parallelism = 4).analyzePack(packDir(), clips)
            val plain = haptics(FakeDecoder(), parallelism = 1).analyzePack(packDir(), clips)

            assertEquals(plain, sessions)
            assertTrue("${decoder.opened} sessions", decoder.opened.get() in 1..4)
            assertEquals(decoder.opened.get(), decoder.closed.get())
            assertEquals(0, decoder.decodedOutsideSessions.get())
            assertEquals(0, decoder.concurrentUse.get())
        }

    @Test
    fun `sessions are closed when the analysis is cancelled`() =
        runBlocking {
            val decoder = SessionDecoder(FakeDecoder(waitMs = 10))
            val many = (0 until 200).map { "voice$it.ogg" }
            val job = async(Dispatchers.Default) { haptics(decoder, 4).analyzePack(packDir(), many) }
            delay(50)
            job.cancel()
            try {
                job.await()
                fail("Expected a cancellation")
            } catch (_: CancellationException) {
                // Expected.
            }
            assertTrue(decoder.opened.get() > 0)
            assertEquals(decoder.opened.get(), decoder.closed.get())
        }

    @Test(expected = IllegalArgumentException::class)
    fun `parallelism must be positive`() {
        haptics(FakeDecoder(), parallelism = 0)
    }

    private companion object {
        const val RATE = 8_000
    }
}
