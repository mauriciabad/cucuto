package app.mauri.cucuto.core.ringtone.render

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import app.mauri.cucuto.packspec.LoopRange
import app.mauri.cucuto.packspec.RingFilePlan
import app.mauri.cucuto.packspec.RingFiles
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** A rendered ring file, ready to export. */
data class RenderedRing(
    val file: File,
    val mimeType: String,
    val extension: String,
    val plan: RingFilePlan,
    /** True when the source passed through unchanged (docs/pack-format.md#ring-files, rule 4). */
    val copied: Boolean,
)

class RenderException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Renders ring files. Blocking: call it off the main thread. */
fun interface RingFileRenderer {
    /** Renders the ringtone at [source] with its [loop] (null for `loop: false`) and [fadeInMs] into [workDir]. */
    fun render(
        source: File,
        loop: LoopRange?,
        fadeInMs: Long,
        workDir: File,
    ): RenderedRing
}

/**
 * Ring files with the platform codecs (ADR 0033 consequences, ADR 0039): `MediaExtractor` and `MediaCodec` decode the
 * pack file, [Resampler] brings it to 48 kHz, [RingFileReader] splices the plan from pack-spec's [RingFiles] (the
 * rule packer uses), and the Opus encoder writes Ogg with `MediaMuxer` (API 29+), like `packer ring`: 48 kHz, mono or
 * stereo as the source, 64 kb/s per channel. If this device cannot encode Opus into Ogg, AAC in MPEG-4 (`.m4a`) is the
 * fallback; Telecom's `MediaPlayer` plays both.
 *
 * Pass-through plans copy a compressed source as it is (WAV and FLAC are encoded, so a long song stays small). Codec priming ("encoder-delay" and "encoder-padding" of MP3 and AAC
 * tracks) is trimmed so loop points land where packer (ffmpeg) puts them; the Opus decoder drops its pre-skip itself.
 */
@Singleton
class PlatformRingRenderer
    @Inject
    constructor() : RingFileRenderer {
        override fun render(
            source: File,
            loop: LoopRange?,
            fadeInMs: Long,
            workDir: File,
        ): RenderedRing {
            workDir.mkdirs()
            val pcm = File.createTempFile("ring-", ".pcm", workDir)
            try {
                val channels = decode(source, pcm)
                val frames = pcm.length() / (BYTES_PER_SAMPLE * channels)
                if (frames <= 0) throw RenderException("No audio decoded")
                val plan =
                    try {
                        RingFiles.plan(loop, fadeInMs, frames * 1000 / RATE)
                    } catch (e: IllegalArgumentException) {
                        throw RenderException("Bad loop: ${e.message}", e)
                    }
                val extension = source.extension.lowercase()
                if (plan.isPassThrough && extension in COMPRESSED) {
                    val out = File.createTempFile("ring-", ".$extension", workDir)
                    source.copyTo(out, overwrite = true)
                    return RenderedRing(out, mimeTypeOf(extension), extension, plan, copied = true)
                }
                RandomAccessFile(pcm, "r").use { raf ->
                    val pcmSource = FilePcmSource(raf, channels)
                    val ogg = File.createTempFile("ring-", ".ogg", workDir)
                    try {
                        encode(RingFileReader(pcmSource, plan, RATE), channels, ogg, Container.OggOpus)
                        return RenderedRing(ogg, "audio/ogg", "ogg", plan, copied = false)
                    } catch (e: Exception) {
                        ogg.delete()
                        Log.w(TAG, "Opus in Ogg failed, falling back to AAC", e)
                    }
                    val m4a = File.createTempFile("ring-", ".m4a", workDir)
                    try {
                        encode(RingFileReader(pcmSource, plan, RATE), channels, m4a, Container.Mp4Aac)
                    } catch (e: Exception) {
                        m4a.delete()
                        throw e as? RenderException ?: RenderException("Encoding failed", e)
                    }
                    return RenderedRing(m4a, "audio/mp4", "m4a", plan, copied = false)
                }
            } finally {
                pcm.delete()
            }
        }

        /** Decodes [source] to 48 kHz interleaved 16-bit PCM in [out]; returns the channel count (1 or 2). */
        private fun decode(
            source: File,
            out: File,
        ): Int {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(source.path)
                val track =
                    (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    } ?: throw RenderException("No audio track")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                val trims = mime != MediaFormat.MIMETYPE_AUDIO_OPUS
                var skip = if (trims) format.intOrZero(KEY_ENCODER_DELAY) else 0
                val padding = if (trims) format.intOrZero(KEY_ENCODER_PADDING) else 0
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                var inChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var inRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var float = false
                val outChannels = inChannels.coerceIn(1, 2)
                var resampler: Resampler? = null
                val tail = TailHolder(padding * outChannels)
                val bytes = ByteArray(1 shl 16)
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { stream ->
                    val writer =
                        { samples: FloatArray, frames: Int ->
                            writePcm16(stream, samples, frames * outChannels, bytes)
                        }
                    while (true) {
                        if (!inputDone) {
                            val index = codec.dequeueInputBuffer(TIMEOUT_US)
                            if (index >= 0) {
                                val buffer = codec.getInputBuffer(index)!!
                                val size = extractor.readSampleData(buffer, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val f = codec.outputFormat
                            inChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            float =
                                f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            resampler = null
                        } else if (index >= 0) {
                            val buffer = codec.getOutputBuffer(index)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            var samples = readSamples(buffer.order(ByteOrder.nativeOrder()), float)
                            codec.releaseOutputBuffer(index, false)
                            var frames = samples.size / inChannels
                            if (skip > 0 && frames > 0) {
                                val dropped = minOf(skip, frames)
                                skip -= dropped
                                samples = samples.copyOfRange(dropped * inChannels, samples.size)
                                frames -= dropped
                            }
                            if (frames > 0) {
                                val mixed = downmix(samples, frames, inChannels, outChannels)
                                val r = resampler ?: Resampler(inRate, RATE, outChannels).also { resampler = it }
                                tail.push(mixed, frames * outChannels) { held, count ->
                                    r.process(held, count / outChannels, writer)
                                }
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                    resampler?.finish(writer)
                }
                return outChannels
            } catch (e: RenderException) {
                throw e
            } catch (e: Exception) {
                throw RenderException("Cannot decode ${source.name}", e)
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                extractor.release()
            }
        }

        private fun encode(
            reader: RingFileReader,
            channels: Int,
            out: File,
            container: Container,
        ) {
            val format =
                MediaFormat.createAudioFormat(container.mime, RATE, channels).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_PER_CHANNEL * channels)
                    if (container == Container.Mp4Aac) {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    }
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_FRAMES * channels * BYTES_PER_SAMPLE)
                }
            val codec = MediaCodec.createEncoderByType(container.mime)
            var muxer: MediaMuxer? = null
            var muxerStarted = false
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                muxer = MediaMuxer(out.path, container.muxerFormat)
                val info = MediaCodec.BufferInfo()
                val chunk = ShortArray(CHUNK_FRAMES * channels)
                var fed = 0L
                var inputDone = false
                var track = -1
                while (true) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
                            buffer.clear()
                            val room = minOf(CHUNK_FRAMES, buffer.remaining() / (channels * BYTES_PER_SAMPLE))
                            val frames = reader.read(chunk, room)
                            val timeUs = fed * 1_000_000L / RATE
                            if (frames == 0) {
                                codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buffer.asShortBuffer().put(chunk, 0, frames * channels)
                                codec.queueInputBuffer(index, 0, frames * channels * BYTES_PER_SAMPLE, timeUs, 0)
                                fed += frames
                            }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxerStarted) { "Output format changed twice" }
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0) {
                            check(muxerStarted) { "Encoded data before the output format" }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                check(fed == reader.totalFrames) { "Fed $fed of ${reader.totalFrames} frames" }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
                if (muxerStarted) runCatching { muxer?.stop() }
                runCatching { muxer?.release() }
            }
        }

        private enum class Container(
            val mime: String,
            val muxerFormat: Int,
        ) {
            OggOpus(MediaFormat.MIMETYPE_AUDIO_OPUS, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG),
            Mp4Aac(MediaFormat.MIMETYPE_AUDIO_AAC, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
        }

        /** Reads interleaved 16-bit frames from the decoded PCM file. */
        private class FilePcmSource(
            private val raf: RandomAccessFile,
            override val channels: Int,
        ) : PcmSource {
            override val frames: Long = raf.length() / (BYTES_PER_SAMPLE * channels)
            private var bytes = ByteArray(0)

            override fun read(
                from: Long,
                into: ShortArray,
                count: Int,
            ) {
                val size = count * channels * BYTES_PER_SAMPLE
                if (bytes.size < size) bytes = ByteArray(size)
                raf.seek(from * channels * BYTES_PER_SAMPLE)
                raf.readFully(bytes, 0, size)
                ByteBuffer
                    .wrap(bytes, 0, size)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .get(into, 0, count * channels)
            }
        }

        /** Holds back the last [capacity] samples, so codec padding at the end of a stream can be dropped. */
        private class TailHolder(
            private val capacity: Int,
        ) {
            private var held = FloatArray(0)
            private var count = 0

            fun push(
                samples: FloatArray,
                size: Int,
                release: (FloatArray, Int) -> Unit,
            ) {
                if (capacity == 0) {
                    release(samples, size)
                    return
                }
                val all = FloatArray(count + size)
                held.copyInto(all, 0, 0, count)
                samples.copyInto(all, count, 0, size)
                val out = all.size - capacity
                if (out > 0) {
                    release(all, out)
                    held = all.copyOfRange(out, all.size)
                    count = capacity
                } else {
                    held = all
                    count = all.size
                }
            }
        }

        internal companion object {
            private const val TAG = "cucuto"
            const val RATE = 48_000
            private const val BYTES_PER_SAMPLE = 2
            private const val BITRATE_PER_CHANNEL = 64_000
            private const val CHUNK_FRAMES = 4096
            private const val TIMEOUT_US = 10_000L

            // MediaFormat.KEY_ENCODER_DELAY / KEY_ENCODER_PADDING (API 30), spelled out for readability in logs.
            private const val KEY_ENCODER_DELAY = "encoder-delay"
            private const val KEY_ENCODER_PADDING = "encoder-padding"

            /** Extensions a pass-through plan exports unchanged. */
            private val COMPRESSED = setOf("ogg", "opus", "oga", "mp3", "m4a", "aac")

            fun mimeTypeOf(extension: String): String =
                when (extension) {
                    "ogg", "opus", "oga" -> "audio/ogg"
                    "mp3" -> "audio/mpeg"
                    "m4a", "aac", "mp4" -> "audio/mp4"
                    "flac" -> "audio/flac"
                    "wav" -> "audio/wav"
                    else -> "audio/*"
                }

            private fun MediaFormat.intOrZero(key: String): Int = if (containsKey(key)) getInteger(key) else 0

            private fun readSamples(
                buffer: ByteBuffer,
                float: Boolean,
            ): FloatArray =
                if (float) {
                    val floats = buffer.asFloatBuffer()
                    FloatArray(floats.remaining()).also { floats.get(it) }
                } else {
                    val shorts = buffer.asShortBuffer()
                    FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
                }

            /** Mono stays mono, stereo stays stereo; more channels fold into stereo (even ones left, odd ones right). */
            fun downmix(
                samples: FloatArray,
                frames: Int,
                inChannels: Int,
                outChannels: Int,
            ): FloatArray {
                if (inChannels == outChannels) return samples
                val out = FloatArray(frames * outChannels)
                for (f in 0 until frames) {
                    if (outChannels == 1) {
                        var sum = 0f
                        for (c in 0 until inChannels) sum += samples[f * inChannels + c]
                        out[f] = sum / inChannels
                    } else {
                        var left = 0f
                        var right = 0f
                        var nl = 0
                        var nr = 0
                        for (c in 0 until inChannels) {
                            if (c % 2 == 0) {
                                left += samples[f * inChannels + c]
                                nl++
                            } else {
                                right += samples[f * inChannels + c]
                                nr++
                            }
                        }
                        out[2 * f] = left / nl
                        out[2 * f + 1] = if (nr > 0) right / nr else left / nl
                    }
                }
                return out
            }

            fun writePcm16(
                stream: java.io.OutputStream,
                samples: FloatArray,
                count: Int,
                scratch: ByteArray,
            ) {
                var i = 0
                while (i < count) {
                    val n = minOf(count - i, scratch.size / BYTES_PER_SAMPLE)
                    for (j in 0 until n) {
                        val v = (samples[i + j] * 32767f).coerceIn(-32768f, 32767f).toInt()
                        scratch[2 * j] = (v and 0xFF).toByte()
                        scratch[2 * j + 1] = (v shr 8 and 0xFF).toByte()
                    }
                    stream.write(scratch, 0, n * BYTES_PER_SAMPLE)
                    i += n
                }
            }
        }
    }
