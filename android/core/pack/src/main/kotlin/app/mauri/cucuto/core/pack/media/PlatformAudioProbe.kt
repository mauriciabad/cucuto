package app.mauri.cucuto.core.pack.media

import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import app.mauri.cucuto.core.codec.DecodeWindow
import app.mauri.cucuto.core.codec.PcmSink
import app.mauri.cucuto.core.codec.PlatformAudioDecoder
import app.mauri.cucuto.core.codec.intOr
import app.mauri.cucuto.core.codec.pcmBytesPerSample
import app.mauri.cucuto.core.codec.selectAudioTrack
import app.mauri.cucuto.packspec.FileFormat
import java.io.File
import java.nio.ByteBuffer

/**
 * [AudioProbe] with [MediaExtractor] and [MediaCodec][android.media.MediaCodec], the same stack the system uses to
 * play notification sounds. The duration is the container's (`KEY_DURATION`), as for the header checks; when the
 * container has none, it is measured from the decoded frames.
 *
 * Every packet of a clip is decoded. The decoding loop is `:core:codec`'s [PlatformAudioDecoder], shared with auto
 * haptics: batches of packets when the decoder takes them, and a [session] per import worker that keeps its decoder
 * between files (#279, ADR 0077).
 */
class PlatformAudioProbe(
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** Use batches when the decoder supports them (false: always one packet at a time, for comparisons). */
    batches: Boolean = true,
) : AudioProbe {
    private val decoder = PlatformAudioDecoder(timeoutMs, batches)

    override fun probe(
        file: File,
        format: FileFormat,
        full: Boolean,
    ): AudioProbeResult = session().use { it.probe(file, format, full) }

    override fun session(): AudioProbe.Session = Session(decoder.session())

    private class Session(
        private val decoding: PlatformAudioDecoder.Session,
    ) : AudioProbe.Session {
        override fun probe(
            file: File,
            format: FileFormat,
            full: Boolean,
        ): AudioProbeResult {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(file.absolutePath)
                val trackFormat = extractor.selectAudioTrack() ?: return AudioProbeResult.Undecodable("No audio track")
                val declaredUs =
                    if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                        trackFormat.getLong(MediaFormat.KEY_DURATION)
                    } else {
                        -1L
                    }
                val counter = FrameCounter(trackFormat)
                // Long files: the start, then the end (seeking proves the index and the tail are usable too).
                val windows =
                    if (!full && declaredUs > 2 * PARTIAL_DECODE_US) {
                        listOf(
                            DecodeWindow(untilUs = PARTIAL_DECODE_US),
                            DecodeWindow(seekToUs = declaredUs - PARTIAL_DECODE_US),
                        )
                    } else {
                        listOf(DecodeWindow())
                    }
                decoding.decode(extractor, trackFormat, counter, windows)
                when {
                    counter.frames == 0L -> AudioProbeResult.Undecodable("The decoder produced no audio")
                    declaredUs > 0 -> AudioProbeResult.Decoded(declaredUs / 1000)
                    else -> AudioProbeResult.Decoded(counter.endUs / 1000)
                }
            } catch (e: Exception) {
                AudioProbeResult.Undecodable(e.message ?: e.javaClass.simpleName)
            } finally {
                extractor.release()
            }
        }

        override fun close() = decoding.close()
    }

    /** Counts the decoded PCM frames and where the audio ends. */
    private class FrameCounter(
        trackFormat: MediaFormat,
    ) : PcmSink {
        private var sampleRate = trackFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 0)
        private var bytesPerFrame = bytesPerFrame(trackFormat)

        var frames = 0L
            private set
        var endUs = 0L
            private set

        override fun formatChanged(format: MediaFormat) {
            sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
            bytesPerFrame = bytesPerFrame(format)
        }

        override fun write(
            data: ByteBuffer,
            presentationTimeUs: Long,
        ) {
            if (bytesPerFrame <= 0) return
            val n = (data.remaining() / bytesPerFrame).toLong()
            frames += n
            if (sampleRate > 0) endUs = maxOf(endUs, presentationTimeUs + n * 1_000_000L / sampleRate)
        }

        private fun bytesPerFrame(f: MediaFormat): Int =
            f.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1) *
                pcmBytesPerSample(f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT))
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L

        /** How much of a ringtone's start and end is decoded. */
        const val PARTIAL_DECODE_US = 2_000_000L
    }
}
