package app.mauri.cucuto.packs

import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.haptics.auto.AutoHapticAnalyzer
import app.mauri.cucuto.core.haptics.auto.AutoHapticSet
import app.mauri.cucuto.core.haptics.auto.AutoHaptics
import app.mauri.cucuto.core.haptics.auto.MonoPcm
import app.mauri.cucuto.core.haptics.auto.PcmDecoder
import app.mauri.cucuto.core.pack.PackImporter
import app.mauri.cucuto.packspec.PackManifest
import app.mauri.cucuto.packspec.SpecJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.math.PI
import kotlin.math.sin

/**
 * Auto haptics at install (#267) on a pack shaped like the one in #241: 98 characters, each with a notification clip
 * of 1 to 30 s and two ringtones. Clips are real WAV files; the decoder is a JVM stand-in that reads at most the first
 * 2 s of a clip and then waits like a platform decoder: on an emulator, about 40 ms to start the codec and 1 ms per
 * 20 ms Opus packet, almost all of it waiting for the codec process (ADR 0061). Here it is a tenth of that, so the
 * test stays short. Real numbers need the device.
 */
class AutoHapticsBenchmarkTest {
    @Test
    fun `analyses a large pack several clips at once`() {
        val clips = manifest.clipFiles()
        assertEquals(CHARACTERS, clips.size)

        val sequential = measure(clips, parallelism = 1)
        val parallel = measure(clips, parallelism = PARALLELISM)
        println(
            "AutoHapticsBenchmark ${clips.size} clips: 1 at a time ${sequential.ms} ms, $PARALLELISM at once ${parallel.ms} ms",
        )

        // The same result, clip for clip, in manifest order.
        assertEquals(sequential.set, parallel.set)
        assertEquals(
            clips,
            parallel.set.clips.keys
                .toList(),
        )
        assertTrue(
            parallel.set.clips.values
                .all { it != null },
        )
        // Generous, so a loaded CI runner never fails it: a quiet laptop measures 6 to 8x.
        assertTrue(
            "$PARALLELISM at once took ${parallel.ms} ms, 1 at a time ${sequential.ms} ms",
            parallel.ms * 2 < sequential.ms,
        )
    }

    @Test
    fun `decodes only clips, each once and only its first 2 s, and reports every clip`() {
        val decoder = WavDecoder()
        val reports = mutableListOf<Pair<Int, Int>>()
        runBlocking {
            AutoHaptics(HapticCapabilities(), Dispatchers.IO, decoder, PARALLELISM)
                .analyzePack(packDir, manifest.clipFiles()) { done, total -> reports += done to total }
        }
        assertEquals(
            manifest.clipFiles().sorted(),
            decoder.opened
                .map {
                    it.relativeTo(packDir).invariantSeparatorsPath
                }.sorted(),
        )
        assertTrue(decoder.maxDurations.all { it == AutoHapticAnalyzer.MAX_DURATION_MS })
        // Never more than the first 2 s of a 30 s clip: 2 s at 8 kHz, 16 bits.
        assertTrue(decoder.maxBytesRead <= AutoHapticAnalyzer.MAX_DURATION_MS * RATE / 1000 * 2)
        assertEquals((0..CHARACTERS).map { it to CHARACTERS }, reports)
    }

    @Test
    fun `the app uses the importer's bound`() {
        assertEquals(PackImporter.DEFAULT_DECODE_PARALLELISM, AutoHaptics.DEFAULT_PARALLELISM)
    }

    private class Measurement(
        val ms: Long,
        val set: AutoHapticSet,
    )

    private fun measure(
        clips: List<String>,
        parallelism: Int,
    ): Measurement {
        val runs =
            (0 until RUNS).map {
                val haptics = AutoHaptics(HapticCapabilities(), Dispatchers.IO, WavDecoder(), parallelism)
                val start = System.nanoTime()
                val set = runBlocking { haptics.analyzePack(packDir, clips) }
                Measurement((System.nanoTime() - start) / 1_000_000, set)
            }
        return runs.sortedBy { it.ms }[RUNS / 2]
    }

    /** Reads mono 16-bit WAV files like [writeWav] writes them, then waits like the platform decoder would. */
    private class WavDecoder : PcmDecoder {
        val opened: MutableList<File> = Collections.synchronizedList(mutableListOf())
        val maxDurations: MutableList<Int> = Collections.synchronizedList(mutableListOf())

        @Volatile var maxBytesRead = 0

        override fun decode(
            file: File,
            maxDurationMs: Int,
        ): MonoPcm {
            opened += file
            maxDurations += maxDurationMs
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(HEADER_BYTES)
                raf.readFully(header)
                val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                if (h.getInt(0) != RIFF) throw IOException("Not a WAV file: ${file.name}")
                val rate = h.getInt(24)
                val dataBytes = h.getInt(40)
                val bytes = minOf(dataBytes.toLong(), rate.toLong() * maxDurationMs / 1000 * 2).toInt()
                synchronized(this) { maxBytesRead = maxOf(maxBytesRead, bytes) }
                val data = ByteArray(bytes)
                raf.readFully(data)
                val pcm = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val samples = FloatArray(bytes / 2) { pcm.get(it) / 32_768f }
                val decodedMs = samples.size * 1000L / rate
                val nanos = CODEC_START_NANOS + decodedMs / PACKET_MS * NANOS_PER_PACKET
                Thread.sleep(nanos / 1_000_000, (nanos % 1_000_000).toInt())
                return MonoPcm(samples, rate)
            }
        }
    }

    companion object {
        private const val CHARACTERS = 98
        private const val RINGTONES_PER_CHARACTER = 2
        private const val RATE = 8_000
        private const val RUNS = 3
        private const val PARALLELISM = 8

        /** A tenth of the codec start and per-packet time measured on an emulator (ADR 0061). */
        private const val CODEC_START_NANOS = 4_000_000L
        private const val NANOS_PER_PACKET = 100_000L
        private const val PACKET_MS = 20L

        private const val HEADER_BYTES = 44
        private const val RIFF = 0x46464952

        @get:ClassRule
        @JvmStatic
        val tmp: TemporaryFolder = TemporaryFolder.builder().parentFolder(File("build/tmp").apply { mkdirs() }).build()

        private lateinit var packDir: File
        private lateinit var manifest: PackManifest

        @BeforeClass
        @JvmStatic
        fun writePack() {
            packDir = tmp.newFolder("large-pack")
            val characters =
                (0 until CHARACTERS).joinToString(",\n") { i ->
                    val clip = "characters/c$i/clips/voice.wav"
                    // 1 to 30 s: the analysis costs the same for all of them, since it reads only the first 2 s.
                    writeWav(File(packDir, clip), seconds = 1 + i % 30, index = i)
                    val ringtones =
                        (0 until RINGTONES_PER_CHARACTER).joinToString(",") { r ->
                            val ringtone = "characters/c$i/ringtones/r$r.wav"
                            writeWav(File(packDir, ringtone), seconds = 1, index = i + r)
                            """{ "file": "$ringtone" }"""
                        }
                    """{ "id": "c$i", "name": "C$i", "sprite": "characters/c$i/sprite.svg",
                       "clips": [{ "file": "$clip" }], "ringtones": [$ringtones] }"""
                }
            val json =
                """
                { "formatVersion": 1, "id": "app.mauri.test.large", "version": "1.0.0", "name": "Large",
                  "author": "Someone", "license": "CC0-1.0", "characters": [$characters] }
                """.trimIndent()
            File(packDir, "pack.json").writeText(json)
            manifest = SpecJson.decodeFromString<PackManifest>(json)
        }

        /** A mono 16-bit WAV file of [seconds]: a decaying tone whose pitch and level depend on [index]. */
        private fun writeWav(
            file: File,
            seconds: Int,
            index: Int,
        ) {
            file.parentFile.mkdirs()
            val frames = RATE * seconds
            val buffer = ByteBuffer.allocate(HEADER_BYTES + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(RIFF).putInt(36 + frames * 2).putInt(0x45564157) // "WAVE"
            buffer
                .putInt(0x20746d66)
                .putInt(16)
                .putShort(1)
                .putShort(1) // "fmt ", PCM, mono
            buffer
                .putInt(RATE)
                .putInt(RATE * 2)
                .putShort(2)
                .putShort(16)
            buffer.putInt(0x61746164).putInt(frames * 2) // "data"
            val frequency = 120.0 + index * 131 % 3_500
            val level = 0.3 + index % 7 * 0.1
            for (f in 0 until frames) {
                val t = f.toDouble() / RATE
                val beat = 0.5 + 0.5 * sin(2 * PI * (1.5 + index % 4) * t)
                buffer.putShort((level * beat * sin(2 * PI * frequency * t) * Short.MAX_VALUE).toInt().toShort())
            }
            file.writeBytes(buffer.array())
        }
    }
}
