package app.mauri.cucuto.core.pack.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import app.mauri.cucuto.packspec.FileFormat
import java.io.File

/**
 * [AudioProbe] with [MediaExtractor] and [MediaCodec] (synchronous mode), the same stack the system uses to play
 * notification sounds. The duration is the container's (`KEY_DURATION`), as for the header checks; when the container
 * has none, it is measured from the decoded frames.
 */
class PlatformAudioProbe(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : AudioProbe {
    override fun probe(
        file: File,
        format: FileFormat,
        full: Boolean,
    ): AudioProbeResult {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(file.absolutePath)
            val track =
                (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return AudioProbeResult.Undecodable("No audio track")
            extractor.selectTrack(track)
            val trackFormat = extractor.getTrackFormat(track)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
            val declaredUs =
                if (trackFormat.containsKey(
                        MediaFormat.KEY_DURATION,
                    )
                ) {
                    trackFormat.getLong(MediaFormat.KEY_DURATION)
                } else {
                    -1L
                }
            val name =
                MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(trackFormat)
                    ?: return AudioProbeResult.Undecodable("No decoder for $mime")
            val decoder = MediaCodec.createByCodecName(name).also { codec = it }
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            val run = DecodeRun(decoder, extractor, trackFormat, SystemClock.elapsedRealtime() + timeoutMs)
            if (full || declaredUs <= 0 || declaredUs <= 2 * PARTIAL_DECODE_US) {
                run.decode(untilUs = Long.MAX_VALUE)
            } else {
                // Long files: the start, then the end (seeking proves the index and the tail are usable too).
                run.decode(untilUs = PARTIAL_DECODE_US)
                decoder.flush()
                extractor.seekTo(declaredUs - PARTIAL_DECODE_US, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                run.decode(untilUs = Long.MAX_VALUE)
            }
            when {
                run.frames == 0L -> AudioProbeResult.Undecodable("The decoder produced no audio")
                declaredUs > 0 -> AudioProbeResult.Decoded(declaredUs / 1000)
                else -> AudioProbeResult.Decoded(run.endUs / 1000)
            }
        } catch (e: DecodeTimeoutException) {
            AudioProbeResult.Undecodable("Decoding took longer than $timeoutMs ms")
        } catch (e: Exception) {
            AudioProbeResult.Undecodable(e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
                // Already stopped or in an error state; release below still frees it.
            }
            codec?.release()
            extractor.release()
        }
    }

    private class DecodeTimeoutException : Exception()

    /** Feeds [extractor] samples to [codec] and counts the decoded PCM frames. */
    private class DecodeRun(
        private val codec: MediaCodec,
        private val extractor: MediaExtractor,
        trackFormat: MediaFormat,
        private val deadline: Long,
    ) {
        private val info = MediaCodec.BufferInfo()
        private var sampleRate = trackFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 0)
        private var bytesPerFrame = bytesPerFrame(trackFormat)

        var frames = 0L
            private set
        var endUs = 0L
            private set

        /** Decodes until the end of the stream, or until the input passes [untilUs]. */
        fun decode(untilUs: Long) {
            var inputDone = false
            while (true) {
                if (SystemClock.elapsedRealtime() > deadline) throw DecodeTimeoutException()
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: error("No input buffer")
                        val size = extractor.readSampleData(buffer, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time > untilUs) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    out >= 0 -> {
                        if (info.size > 0 && bytesPerFrame > 0) {
                            val n = (info.size / bytesPerFrame).toLong()
                            frames += n
                            if (sampleRate >
                                0
                            ) {
                                endUs = maxOf(endUs, info.presentationTimeUs + n * 1_000_000L / sampleRate)
                            }
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }

                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        bytesPerFrame = bytesPerFrame(f)
                    }
                }
            }
        }

        private fun bytesPerFrame(f: MediaFormat): Int {
            val channels = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            val bytesPerSample =
                when (f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)) {
                    AudioFormat.ENCODING_PCM_8BIT -> 1
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                    AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                    else -> 2
                }
            return channels * bytesPerSample
        }

        private fun MediaFormat.intOr(
            key: String,
            default: Int,
        ): Int = if (containsKey(key)) getInteger(key) else default
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val DEQUEUE_TIMEOUT_US = 10_000L

        /** How much of a ringtone's start and end is decoded. */
        const val PARTIAL_DECODE_US = 2_000_000L
    }
}
