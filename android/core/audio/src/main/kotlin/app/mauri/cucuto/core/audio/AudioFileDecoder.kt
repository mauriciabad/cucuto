package app.mauri.cucuto.core.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import app.mauri.cucuto.packspec.authoring.Authoring
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** A decoded file: its audio, and whether it was longer than the decoder kept ([truncated]). */
class DecodedAudio(
    val audio: PcmAudio,
    val truncated: Boolean,
)

/**
 * Decodes an audio file the user picked (any format the platform plays: Opus, Vorbis, MP3, AAC, FLAC, WAV and more)
 * to mono 16-bit PCM with [MediaExtractor] and [MediaCodec], keeping at most [maxDurationMs]. Channels are averaged.
 * Same approach as `:core:haptics`' clip decoder, for whole songs: cancellable and with progress instead of a deadline.
 */
class AudioFileDecoder(
    private val maxDurationMs: Long = Authoring.Audio.MAX_SOURCE_MS,
) {
    /**
     * Decodes [file]. [onProgress] gets 0..1 as decoding advances (when the length is known).
     *
     * @throws IOException when the file has no audio track the platform can decode.
     */
    suspend fun decode(
        file: File,
        onProgress: (Float) -> Unit = {},
    ): DecodedAudio =
        withContext(Dispatchers.Default) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(file.absolutePath)
                val track =
                    (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    } ?: throw IOException("No audio track")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val name =
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
                        ?: throw IOException("No decoder for ${format.getString(MediaFormat.KEY_MIME)}")
                val decoder = MediaCodec.createByCodecName(name).also { codec = it }
                decoder.configure(format, null, null, 0)
                decoder.start()
                Run(decoder, extractor, format, onProgress).decode()
            } catch (e: IOException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Cannot decode: ${e.message ?: e.javaClass.simpleName}", e)
            } finally {
                codec?.let {
                    runCatching { it.stop() }
                    it.release()
                }
                extractor.release()
            }
        }

    private inner class Run(
        private val codec: MediaCodec,
        private val extractor: MediaExtractor,
        format: MediaFormat,
        private val onProgress: (Float) -> Unit,
    ) {
        private val info = MediaCodec.BufferInfo()
        private val durationUs = format.longOr(MediaFormat.KEY_DURATION, 0L)
        private var sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 0)
        private var channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
        private var encoding = AudioFormat.ENCODING_PCM_16BIT
        private var out = ShortArray(0)
        private var size = 0
        private var truncated = false

        /** True once [maxDurationMs] of audio has been kept: the rest is not decoded. */
        private var full = false
        private val maxUs = maxDurationMs * 1000L
        private var lastProgress = -1f

        suspend fun decode(): DecodedAudio {
            var inputDone = false
            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: throw IOException("No input buffer")
                        val length = extractor.readSampleData(buffer, 0)
                        val time = extractor.sampleTime
                        if (length < 0 || time > maxUs) {
                            if (length >= 0) truncated = true
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, length, time, 0)
                            extractor.advance()
                            report(time)
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index >= 0 -> {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(index) ?: throw IOException("No output buffer")
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            append(buffer.slice().order(ByteOrder.LITTLE_ENDIAN))
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || full) break
                    }

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        sampleRate = output.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = output.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                        encoding = output.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                }
            }
            if (sampleRate <= 0) throw IOException("Unknown sample rate")
            onProgress(1f)
            return DecodedAudio(PcmAudio(out.copyOf(size), sampleRate), truncated || full)
        }

        private fun report(timeUs: Long) {
            val total = minOf(durationUs, maxUs)
            if (total <= 0) return
            val progress = (timeUs.toFloat() / total).coerceIn(0f, 1f)
            if (progress - lastProgress >= PROGRESS_STEP) {
                lastProgress = progress
                onProgress(progress)
            }
        }

        private fun append(buffer: ByteBuffer) {
            val bytesPerSample =
                when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> 1
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                    AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
                    else -> 2
                }
            val maxFrames = (sampleRate.toLong() * maxDurationMs / 1000).toInt()
            val frames = minOf(buffer.remaining() / (bytesPerSample * channels), maxFrames - size)
            if (frames < buffer.remaining() / (bytesPerSample * channels)) full = true
            if (frames <= 0) return
            ensureCapacity(size + frames, maxFrames)
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) {
                    sum +=
                        when (encoding) {
                            AudioFormat.ENCODING_PCM_8BIT -> {
                                ((buffer.get().toInt() and 0xFF) - 128) / 128f
                            }

                            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                                val b0 = buffer.get().toInt() and 0xFF
                                val b1 = buffer.get().toInt() and 0xFF
                                val b2 = buffer.get().toInt()
                                ((b2 shl 16) or (b1 shl 8) or b0) / 8_388_608f
                            }

                            AudioFormat.ENCODING_PCM_32BIT -> {
                                buffer.getInt() / 2_147_483_648f
                            }

                            AudioFormat.ENCODING_PCM_FLOAT -> {
                                buffer.getFloat()
                            }

                            else -> {
                                buffer.getShort() / PcmAudio.SHORT_SCALE
                            }
                        }
                }
                out[size++] = ((sum / channels).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
        }

        private fun ensureCapacity(
            frames: Int,
            maxFrames: Int,
        ) {
            if (frames <= out.size) return
            out = out.copyOf(minOf(maxOf(frames, out.size * 2, INITIAL_CAPACITY), maxFrames))
        }
    }

    private fun MediaFormat.intOr(
        key: String,
        default: Int,
    ): Int = if (containsKey(key)) getInteger(key) else default

    private fun MediaFormat.longOr(
        key: String,
        default: Long,
    ): Long = if (containsKey(key)) getLong(key) else default

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val PROGRESS_STEP = 0.02f
        const val INITIAL_CAPACITY = 48_000
    }
}
