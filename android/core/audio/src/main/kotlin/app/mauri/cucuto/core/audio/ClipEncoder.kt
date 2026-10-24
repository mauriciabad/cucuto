package app.mauri.cucuto.core.audio

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaMuxer
import android.util.Log
import app.mauri.cucuto.core.audio.dsp.ProcessedClip
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.media.AudioProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** An encoded clip: the [file] written, its [settings] and its [durationMs] as the pack validator measures it. */
data class EncodedClip(
    val file: File,
    val settings: EncoderSettings,
    val durationMs: Long,
)

/**
 * Encodes [ProcessedClip]s with the platform's own encoders ([MediaCodec]) and containers ([MediaMuxer]): Ogg Opus,
 * or AAC in MP4 when Opus is not available. No native library ships for this (ADR 0034).
 *
 * Every file is checked before it is accepted: pack-spec's [AudioProbe], the parser every validator uses, must read
 * it and measure the clip's length within the format's 50 ms tolerance. A file that fails (a device's muxer writing
 * odd headers) is dropped and the next format is tried, so the creator never hands the importer a clip it rejects.
 */
class ClipEncoder(
    private val preference: List<EncoderSettings> = EncoderSettings.PREFERENCE,
) {
    /**
     * Encodes [clip] into a new file in [directory] named [baseName] plus the format's extension.
     *
     * @throws IOException when no format could be encoded and verified.
     */
    suspend fun encode(
        clip: ProcessedClip,
        directory: File,
        baseName: String,
    ): EncodedClip =
        withContext(Dispatchers.Default) {
            val pcm = PcmAudio.toShorts(clip.samples)
            var lastError: Exception? = null
            for (settings in preference) {
                coroutineContext.ensureActive()
                val codecName = encoderFor(settings) ?: continue
                val file = File(directory, "$baseName.${settings.extension}")
                try {
                    encode(pcm, settings, codecName, file)
                    val measured = verify(file, settings, expectedMs = clip.durationMs)
                    return@withContext EncodedClip(file, settings, measured)
                } catch (e: CancellationException) {
                    file.delete()
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Encoding with ${settings.mime} failed (${e.message ?: e.javaClass.simpleName})")
                    file.delete()
                    lastError = e
                }
            }
            throw IOException("No encoder could write this clip", lastError)
        }

    private fun encoderFor(settings: EncoderSettings): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(settings.mediaFormat())

    private suspend fun encode(
        pcm: ShortArray,
        settings: EncoderSettings,
        codecName: String,
        file: File,
    ) {
        val codec = MediaCodec.createByCodecName(codecName)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec.configure(settings.mediaFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(file.path, settings.container.muxerFormat)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var offset = 0
            var inputDone = false
            var samplesWritten = 0
            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: throw IOException("No input buffer")
                        val count = minOf(pcm.size - offset, buffer.remaining() / 2, CHUNK_SAMPLES)
                        val timeUs = offset * MICROS / settings.sampleRate
                        if (count <= 0) {
                            codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, offset, count)
                            codec.queueInputBuffer(index, 0, count * 2, timeUs, 0)
                            offset += count
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(track < 0) { "Output format changed twice" }
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index) ?: throw IOException("No output buffer")
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        // Codec config (OpusHead, AudioSpecificConfig) reaches the muxer through the output format.
                        if (!config && info.size > 0) {
                            check(muxerStarted) { "Encoded data before the output format" }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                            samplesWritten++
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            if (samplesWritten == 0) throw IOException("The encoder produced no audio")
            muxer.stop()
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxer != null) {
                if (!muxerStarted) file.delete()
                runCatching { muxer.release() }
            }
        }
    }

    /** The clip's length as every pack validator measures it; throws when the file does not parse or is off. */
    private fun verify(
        file: File,
        settings: EncoderSettings,
        expectedMs: Long,
    ): Long {
        val bytes = file.readBytes()
        val measured =
            AudioProbe.durationMs(settings.format, bytes) ?: throw IOException("The encoded file does not parse")
        if (abs(measured - expectedMs) > Limits.DURATION_TOLERANCE_MS) {
            throw IOException("Encoded length $measured ms, expected $expectedMs ms")
        }
        return measured
    }

    private companion object {
        const val TAG = "cucuto"
        const val TIMEOUT_US = 10_000L
        const val MICROS = 1_000_000L

        /** 20 ms at 48 kHz, one Opus frame. */
        const val CHUNK_SAMPLES = 960
    }
}
