package app.mauri.cucuto.packer

import app.mauri.cucuto.packspec.ClipCut
import app.mauri.cucuto.packspec.ClipCuts
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.LoopRange
import app.mauri.cucuto.packspec.RingFilePlan
import app.mauri.cucuto.packspec.RingFiles
import app.mauri.cucuto.packspec.RingPiece
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteRecursively
import kotlin.io.path.fileSize
import kotlin.io.path.outputStream

/**
 * Ring files and auto-cut clips with ffmpeg (docs/pack-format.md#ring-files, ADR 0033). The layout comes from
 * pack-spec ([RingFiles], [ClipCuts]), shared with the app; this object only decodes, splices PCM and encodes.
 *
 * Both outputs are Ogg Opus at 48 kHz (mono or stereo, as the source): Android decodes Opus since 5.0 and reads it
 * from Ogg, and Telecom plays ringtones through `MediaPlayer` (M9 report §3.3). Ring files use 64 kb/s per channel
 * (a 90 s stereo ring file is about 1.5 MB) and clips 96 kb/s, as `--opus` does.
 */
object Ringtones {
    const val RATE = 48_000
    private const val ANALYSIS_RATE = 8_000
    private const val BYTES_PER_SAMPLE = 4 // f32le
    private val bitexact = listOf("-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact")

    /** A rendered ring file: its [plan] (from the decoded duration) and whether the source was copied as is. */
    class RingResult(val plan: RingFilePlan, val copied: Boolean)

    /**
     * Renders the ring file of a ringtone stored at [source] to [out] (Ogg Opus), from its [loop] (null for
     * `loop: false`) and [fadeInMs]. The plan uses the decoded duration of the source, so loop points always fall inside
     * the decoded audio. When the plan is a pass-through and [out] has the source's extension, the source is copied.
     */
    fun renderRingFile(source: Path, loop: LoopRange?, fadeInMs: Long, out: Path, normalize: Boolean = false): RingResult {
        MediaTools.requireTool("ffmpeg")
        val work = Files.createTempDirectory("packer-ring-")
        try {
            val channels = channels(source)
            val pcm = work.resolve("source.f32")
            decode(source, pcm, channels, RATE)
            val sourceMs = pcm.fileSize() / (BYTES_PER_SAMPLE * channels) * 1000 / RATE
            val plan = try {
                RingFiles.plan(loop, fadeInMs, sourceMs)
            } catch (e: IllegalArgumentException) {
                throw PackerException("$source: ${e.message}")
            }
            Files.createDirectories(out.toAbsolutePath().parent)
            if (plan.isPassThrough && !normalize && ext(source) == ext(out)) {
                Files.copy(source, out, StandardCopyOption.REPLACE_EXISTING)
                return RingResult(plan, copied = true)
            }
            val rendered = work.resolve("ring.f32")
            RandomAccessFile(pcm.toFile(), "r").use { src ->
                BufferedOutputStream(rendered.outputStream(), 1 shl 16).use { dst ->
                    splice(src, dst, plan.pieces(RATE), channels, RingFilePlan.frame(plan.fadeInMs, RATE))
                }
            }
            encode(rendered, channels, out, "${64 * channels}k", normalize)
            return RingResult(plan, copied = false)
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            work.deleteRecursively()
        }
    }

    /**
     * Cuts a notification clip from [source] to [out] (Ogg Opus): at [startMs] if given, else at the hook [ClipCuts]
     * finds within `[fromMs, toMs)`. Returns the range that was cut.
     */
    fun cutClip(
        source: Path,
        out: Path,
        startMs: Long? = null,
        lengthMs: Long = Limits.CUT_CLIP_DEFAULT_MS,
        fromMs: Long = 0,
        toMs: Long? = null,
    ): ClipCut {
        MediaTools.requireTool("ffmpeg")
        if (lengthMs < Limits.CLIP_MIN_MS || lengthMs > Limits.CLIP_MAX_MS) {
            throw PackerException("A clip lasts ${Limits.CLIP_MIN_MS} to ${Limits.CLIP_MAX_MS} ms, not $lengthMs ms")
        }
        val work = Files.createTempDirectory("packer-cut-")
        try {
            val channels = channels(source)
            val pcm = work.resolve("source.f32")
            decode(source, pcm, channels, RATE)
            val durationMs = pcm.fileSize() / (BYTES_PER_SAMPLE * channels) * 1000 / RATE
            val cut = if (startMs != null) {
                if (startMs < 0 || startMs >= durationMs) throw PackerException("Start $startMs ms is outside the $durationMs ms of $source")
                ClipCut(startMs, minOf(startMs + lengthMs, durationMs))
            } else {
                val mono = work.resolve("analysis.f32")
                decode(source, mono, 1, ANALYSIS_RATE)
                val floats = ByteBuffer.wrap(Files.readAllBytes(mono)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                ClipCuts.findHook(FloatArray(floats.remaining()).also { floats.get(it) }, ANALYSIS_RATE, lengthMs, fromMs, toMs)
            }
            if (cut.lengthMs < Limits.CLIP_MIN_MS) throw PackerException("Only ${cut.lengthMs} ms of audio from ${cut.startMs} ms in $source")
            val start = RingFilePlan.frame(cut.startMs, RATE)
            val piece = RingPiece.Copy(start, RingFilePlan.frame(cut.endMs, RATE) - start)
            val clip = work.resolve("clip.f32")
            RandomAccessFile(pcm.toFile(), "r").use { src ->
                BufferedOutputStream(clip.outputStream(), 1 shl 16).use { dst ->
                    splice(src, dst, listOf(piece), channels, RingFilePlan.frame(Limits.CUT_FADE_IN_MS, RATE), RingFilePlan.frame(Limits.CUT_FADE_OUT_MS, RATE))
                }
            }
            encode(clip, channels, out, "96k", normalize = false)
            return cut
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            work.deleteRecursively()
        }
    }

    private fun ext(p: Path) = p.fileName.toString().substringAfterLast('.', "").lowercase()

    /** 1 or 2: sources with more channels are downmixed to stereo. */
    private fun channels(source: Path): Int {
        val out = MediaTools.run(listOf("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=channels", "-of", "csv=p=0", source.toString()))
        val n = out.trim().lines().firstOrNull()?.trim()?.toIntOrNull() ?: throw PackerException("$source has no audio stream")
        return n.coerceIn(1, 2)
    }

    /** Decodes [source] to interleaved 32-bit float PCM. */
    private fun decode(source: Path, out: Path, channels: Int, rate: Int) {
        MediaTools.run(listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", source.toString(), "-vn", "-ac", "$channels", "-ar", "$rate", "-f", "f32le", out.toString()))
    }

    private fun encode(pcm: Path, channels: Int, out: Path, bitrate: String, normalize: Boolean) {
        Files.createDirectories(out.toAbsolutePath().parent)
        val input = listOf("-f", "f32le", "-ar", "$RATE", "-ac", "$channels", "-i", pcm.toString())
        val filters = if (normalize) listOf("-af", MediaTools {}.loudnorm(input, emptyList())) else emptyList()
        MediaTools.run(
            listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-y") + input + filters +
                listOf("-c:a", "libopus", "-b:a", bitrate, "-vbr", "on", "-ar", "$RATE", "-f", "ogg") + bitexact + out.toString(),
        )
    }

    /**
     * Writes [pieces] of [src] (interleaved f32le) to [dst], with linear fades over the first [fadeInFrames] and the last
     * [fadeOutFrames] frames of the output. Frames past the end of the source are written as silence.
     */
    private fun splice(src: RandomAccessFile, dst: OutputStream, pieces: List<RingPiece>, channels: Int, fadeInFrames: Long, fadeOutFrames: Long = 0) {
        val frameBytes = BYTES_PER_SAMPLE * channels
        val sourceFrames = src.length() / frameBytes
        val total = pieces.sumOf { it.frames }
        val chunkFrames = 8192
        val buffer = ByteBuffer.allocate(chunkFrames * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        val bytes = buffer.array()
        var written = 0L
        for (piece in pieces) {
            var done = 0L
            while (done < piece.frames) {
                val n = minOf(chunkFrames.toLong(), piece.frames - done).toInt()
                bytes.fill(0, 0, n * frameBytes)
                if (piece is RingPiece.Copy) {
                    val from = piece.from + done
                    val available = (sourceFrames - from).coerceIn(0, n.toLong()).toInt()
                    if (available > 0) {
                        src.seek(from * frameBytes)
                        src.readFully(bytes, 0, available * frameBytes)
                    }
                }
                for (i in 0 until n) {
                    val position = written + i
                    var gain = 1f
                    if (position < fadeInFrames) gain = position.toFloat() / fadeInFrames
                    val left = total - position - 1
                    if (left < fadeOutFrames) gain = minOf(gain, left.toFloat() / fadeOutFrames)
                    if (gain < 1f) {
                        for (c in 0 until channels) {
                            val at = (i * channels + c) * BYTES_PER_SAMPLE
                            buffer.putFloat(at, buffer.getFloat(at) * gain)
                        }
                    }
                }
                dst.write(bytes, 0, n * frameBytes)
                done += n
                written += n
            }
        }
    }
}
