package app.mauri.cucuto.core.haptics.auto

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoded mono audio: samples in −1..1 at [sampleRate] Hz. */
class MonoPcm(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    val durationMs: Long get() = if (sampleRate == 0) 0 else samples.size * 1000L / sampleRate
}

/** Decodes the start of an audio file to mono PCM. Blocking: call it off the main thread. */
fun interface PcmDecoder {
    /** Decodes up to [maxDurationMs] of [file]. Throws [IOException] when the file cannot be decoded. */
    fun decode(
        file: File,
        maxDurationMs: Int,
    ): MonoPcm
}

/**
 * Decodes the start of an audio file to mono PCM with [MediaExtractor] and [MediaCodec] (synchronous mode), the
 * same platform stack the importer verifies clips with and the system plays them with. Channels are averaged; 8,
 * 16, 24 and 32-bit integer and float PCM output are handled.
 *
 * Blocking and CPU-bound (tens of milliseconds for a clip): call it off the main thread.
 */
class ClipPcmDecoder(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : PcmDecoder {
    /** Decodes up to [maxDurationMs] of [file]. Throws [IOException] when the file cannot be decoded. */
    override fun decode(
        file: File,
        maxDurationMs: Int,
    ): MonoPcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track =
                (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw IOException("No audio track in ${file.name}")
            extractor.selectTrack(track)
            val trackFormat = extractor.getTrackFormat(track)
            val name =
                MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(trackFormat)
                    ?: throw IOException("No decoder for ${trackFormat.getString(MediaFormat.KEY_MIME)}")
            val decoder = MediaCodec.createByCodecName(name).also { codec = it }
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()
            return Run(decoder, extractor, trackFormat, maxDurationMs).decode()
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Cannot decode ${file.name}: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
                // Already stopped or in an error state; release below still frees it.
            }
            codec?.release()
            extractor.release()
        }
    }

    private inner class Run(
        private val codec: MediaCodec,
        private val extractor: MediaExtractor,
        trackFormat: MediaFormat,
        private val maxDurationMs: Int,
    ) {
        private val info = MediaCodec.BufferInfo()
        private val deadline = SystemClock.elapsedRealtime() + timeoutMs
        private var sampleRate = trackFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 0)
        private var channels = trackFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
        private var encoding = AudioFormat.ENCODING_PCM_16BIT
        private var out = FloatArray(0)
        private var size = 0

        private val maxUs = maxDurationMs * 1000L

        fun decode(): MonoPcm {
            var inputDone = false
            while (true) {
                if (SystemClock.elapsedRealtime() >
                    deadline
                ) {
                    throw IOException("Decoding took longer than $timeoutMs ms")
                }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: throw IOException("No input buffer")
                        val length = extractor.readSampleData(buffer, 0)
                        val time = extractor.sampleTime
                        if (length < 0 || time > maxUs) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, length, time, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    index >= 0 -> {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(index) ?: throw IOException("No output buffer")
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            append(buffer.slice().order(ByteOrder.LITTLE_ENDIAN))
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                        encoding = format.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                }
            }
            if (sampleRate <= 0) throw IOException("Unknown sample rate")
            val maxFrames = (sampleRate.toLong() * maxDurationMs / 1000).toInt()
            return MonoPcm(out.copyOf(minOf(size, maxFrames)), sampleRate)
        }

        private fun append(buffer: ByteBuffer) {
            val bytesPerSample =
                when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> 1
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                    AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
                    else -> 2
                }
            val frames = buffer.remaining() / (bytesPerSample * channels)
            ensureCapacity(size + frames)
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
                                // Sign-extended by the most significant byte (codec output is little endian).
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
                                buffer.getShort() / 32_768f
                            }
                        }
                }
                out[size++] = (sum / channels).coerceIn(-1f, 1f)
            }
        }

        private fun ensureCapacity(frames: Int) {
            if (frames <= out.size) return
            out = out.copyOf(maxOf(frames, out.size * 2, 4096))
        }
    }

    private fun MediaFormat.intOr(
        key: String,
        default: Int,
    ): Int = if (containsKey(key)) getInteger(key) else default

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
