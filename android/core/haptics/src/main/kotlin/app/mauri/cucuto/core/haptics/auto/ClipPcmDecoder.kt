package app.mauri.cucuto.core.haptics.auto

import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import app.mauri.cucuto.core.codec.DecodeWindow
import app.mauri.cucuto.core.codec.PcmSink
import app.mauri.cucuto.core.codec.PlatformAudioDecoder
import app.mauri.cucuto.core.codec.intOr
import app.mauri.cucuto.core.codec.pcmBytesPerSample
import app.mauri.cucuto.core.codec.selectAudioTrack
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

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

    /**
     * A decoder for one worker that decodes files one after another and may keep its platform decoder between them
     * (#315, ADR 0077). Close it when done. The default has nothing to keep and decodes with [decode].
     */
    fun session(): Session = Session { file, maxDurationMs -> decode(file, maxDurationMs) }

    /** One worker's decoder: not thread-safe, used for one file at a time, closed at the end. */
    fun interface Session : AutoCloseable {
        /** The same as [PcmDecoder.decode]. */
        fun decode(
            file: File,
            maxDurationMs: Int,
        ): MonoPcm

        override fun close() {}
    }
}

/**
 * Decodes the start of an audio file to mono PCM with [MediaExtractor] and [MediaCodec][android.media.MediaCodec],
 * the same platform stack the importer verifies clips with and the system plays them with. Channels are averaged; 8,
 * 16, 24 and 32-bit integer and float PCM output are handled.
 *
 * The decoding loop is `:core:codec`'s [PlatformAudioDecoder], shared with the importer's test-decodes: batches of
 * packets when the decoder takes them, and a [session] per worker that keeps its decoder between clips (#315, ADR
 * 0077). The samples are the same as one packet at a time with a new decoder per clip.
 *
 * Blocking and CPU-bound (tens of milliseconds for a clip): call it off the main thread.
 *
 * @param batches Use batches when the decoder supports them (false: always one packet at a time, for comparisons).
 */
class ClipPcmDecoder(
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    batches: Boolean = true,
) : PcmDecoder {
    private val platform = PlatformAudioDecoder(timeoutMs, batches)

    /** Decodes up to [maxDurationMs] of [file] with a decoder of its own. Throws [IOException] when it cannot. */
    override fun decode(
        file: File,
        maxDurationMs: Int,
    ): MonoPcm = session().use { it.decode(file, maxDurationMs) }

    override fun session(): PcmDecoder.Session = Session(platform.session())

    private class Session(
        private val decoding: PlatformAudioDecoder.Session,
    ) : PcmDecoder.Session {
        override fun decode(
            file: File,
            maxDurationMs: Int,
        ): MonoPcm {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val trackFormat = extractor.selectAudioTrack() ?: throw IOException("No audio track in ${file.name}")
                val pcm = MonoPcmSink(trackFormat)
                // Stops feeding packets after the first one past the limit, like the old loop; the extra is cut below.
                decoding.decode(extractor, trackFormat, pcm, listOf(DecodeWindow(untilUs = maxDurationMs * 1000L)))
                return pcm.result(maxDurationMs)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Cannot decode ${file.name}: ${e.message ?: e.javaClass.simpleName}", e)
            } finally {
                extractor.release()
            }
        }

        override fun close() = decoding.close()
    }

    /** Collects decoded PCM as mono floats. */
    private class MonoPcmSink(
        trackFormat: MediaFormat,
    ) : PcmSink {
        private var sampleRate = trackFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 0)
        private var channels = trackFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
        private var encoding = AudioFormat.ENCODING_PCM_16BIT
        private var out = FloatArray(0)
        private var size = 0

        override fun formatChanged(format: MediaFormat) {
            sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
            channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
            encoding = format.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }

        fun result(maxDurationMs: Int): MonoPcm {
            if (sampleRate <= 0) throw IOException("Unknown sample rate")
            val maxFrames = (sampleRate.toLong() * maxDurationMs / 1000).toInt()
            return MonoPcm(out.copyOf(minOf(size, maxFrames)), sampleRate)
        }

        override fun write(
            data: ByteBuffer,
            presentationTimeUs: Long,
        ) {
            val frames = data.remaining() / (pcmBytesPerSample(encoding) * channels)
            ensureCapacity(size + frames)
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) {
                    sum +=
                        when (encoding) {
                            AudioFormat.ENCODING_PCM_8BIT -> {
                                ((data.get().toInt() and 0xFF) - 128) / 128f
                            }

                            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                                val b0 = data.get().toInt() and 0xFF
                                val b1 = data.get().toInt() and 0xFF
                                // Sign-extended by the most significant byte (codec output is little endian).
                                val b2 = data.get().toInt()
                                ((b2 shl 16) or (b1 shl 8) or b0) / 8_388_608f
                            }

                            AudioFormat.ENCODING_PCM_32BIT -> {
                                data.getInt() / 2_147_483_648f
                            }

                            AudioFormat.ENCODING_PCM_FLOAT -> {
                                data.getFloat()
                            }

                            else -> {
                                data.getShort() / 32_768f
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

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}
