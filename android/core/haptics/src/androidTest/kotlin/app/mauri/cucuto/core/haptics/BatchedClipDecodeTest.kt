package app.mauri.cucuto.core.haptics

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mauri.cucuto.core.haptics.auto.AutoHapticAnalyzer
import app.mauri.cucuto.core.haptics.auto.AutoHaptics
import app.mauri.cucuto.core.haptics.auto.ClipPcmDecoder
import app.mauri.cucuto.core.haptics.auto.MonoPcm
import app.mauri.cucuto.core.routing.Haptic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Auto haptics decode clips in batches with one decoder per worker (#315, ADR 0077). The haptics must stay exactly
 * what they were with a new decoder per clip, one packet at a time (up to v0.1.7): this decodes real clips both ways
 * and compares the samples and the envelopes. The clips are every audio file of the valid spec fixtures (Ogg Opus,
 * Opus, MP3, AAC, FLAC and WAV; clips and ringtones longer than the 2 s that are analysed) and the CC0 demo packs.
 *
 * For local measuring only, the instrumentation argument `clipsDir` (a folder in this test app's files dir) adds
 * every audio file under it; nothing of it is part of the repository.
 */
@RunWith(AndroidJUnit4::class)
class BatchedClipDecodeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val dir = File(instrumentation.targetContext.cacheDir, "batched-decode-${System.nanoTime()}")

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun batchesAndReusedDecodersGiveTheSameHaptics() {
        val clips = representativeClips() + localClips()
        assertTrue("${clips.size} clips", clips.size >= 30)
        Log.i(TAG, "${clips.size} clips; Opus decoder takes batches: ${opusTakesBatches()}")
        val options = AutoHapticAnalyzer.Options.forDevice(HapticCapabilities())

        // Before: a new decoder for every clip, one packet at a time.
        val old = ClipPcmDecoder(batches = false)
        val before = arrayOfNulls<Decoded>(clips.size)
        var envelopes = 0
        // After: one session for all clips, so the decoder is reused and switches between formats; then again in
        // reverse order, so every clip also follows a different one.
        ClipPcmDecoder().session().use { session ->
            clips.forEachIndexed { i, clip ->
                val expected = old.decode(clip, AutoHapticAnalyzer.MAX_DURATION_MS)
                val actual = session.decode(clip, AutoHapticAnalyzer.MAX_DURATION_MS)
                assertEquals(clip.name, expected.sampleRate, actual.sampleRate)
                assertArrayEquals(clip.name, expected.samples, actual.samples, 0f)
                before[i] = Decoded(expected, options).also { if (it.haptic != null) envelopes++ }
                assertEquals(clip.name, before[i], Decoded(actual, options))
            }
            for (i in clips.indices.reversed()) {
                val actual = session.decode(clips[i], AutoHapticAnalyzer.MAX_DURATION_MS)
                assertEquals(clips[i].name, before[i], Decoded(actual, options))
            }
        }
        // Most clips have an envelope: the comparison is not between empty results.
        assertTrue("$envelopes envelopes", envelopes * 2 > clips.size)
    }

    /** A decoded clip without its samples (so a large local folder fits in memory): their checksum and its haptic. */
    private data class Decoded(
        val sampleRate: Int,
        val frames: Int,
        val checksum: Long,
        val haptic: Haptic.Custom?,
    ) {
        constructor(pcm: MonoPcm, options: AutoHapticAnalyzer.Options) : this(
            pcm.sampleRate,
            pcm.samples.size,
            CRC32()
                .apply {
                    val bytes = ByteBuffer.allocate(pcm.samples.size * 4)
                    bytes.asFloatBuffer().put(pcm.samples)
                    update(bytes)
                }.value,
            AutoHapticAnalyzer.analyze(pcm.samples, pcm.sampleRate, options),
        )
    }

    @Test
    fun analyzePackStoresTheSameSetAsBefore() {
        val clips = representativeClips()
        val paths = clips.map { it.relativeTo(dir).invariantSeparatorsPath }
        val before =
            runBlocking {
                AutoHaptics(HapticCapabilities(), Dispatchers.IO, ClipPcmDecoder(batches = false), parallelism = 1)
                    .analyzePack(dir, paths)
            }
        val after = runBlocking { AutoHaptics(HapticCapabilities()).analyzePack(dir, paths) }
        assertEquals(before, after)
        assertEquals(paths, after.clips.keys.toList())
    }

    /** Every audio file of the valid fixtures and the demo packs, copied to [dir] (decoders need real files). */
    private fun representativeClips(): List<File> {
        val assets = instrumentation.context.assets
        val out = mutableListOf<File>()

        fun walk(path: String) {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) {
                if (path.substringAfterLast('.').lowercase() in AUDIO) {
                    val file = File(dir, path).apply { parentFile?.mkdirs() }
                    assets.open(path).use { input -> file.outputStream().use { input.copyTo(it) } }
                    out += file
                }
            } else {
                children.sorted().forEach { walk("$path/$it") }
            }
        }
        listOf("valid", "packs").forEach(::walk)
        return out
    }

    private fun localClips(): List<File> {
        val name = InstrumentationRegistry.getArguments().getString("clipsDir") ?: return emptyList()
        val root = File(instrumentation.targetContext.filesDir, name)
        return root
            .walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in AUDIO }
            .sortedBy { it.path }
            .toList()
    }

    private fun opusTakesBatches(): Boolean {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 1)
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val name = list.findDecoderForFormat(format) ?: return false
        return list.codecInfos
            .first { it.name == name }
            .getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            .isFeatureSupported(CodecCapabilities.FEATURE_MultipleFrames)
    }

    private companion object {
        const val TAG = "BatchedClipDecodeTest"
        val AUDIO = setOf("ogg", "opus", "mp3", "m4a", "flac", "wav")
    }
}
