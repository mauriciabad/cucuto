package app.mauri.cucuto.core.codec

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Receives what a decode run produces, on the decoding thread. */
interface PcmSink {
    /** The decoder's output format (sample rate, channel count, `KEY_PCM_ENCODING`). */
    fun formatChanged(format: MediaFormat)

    /**
     * One access unit of PCM: the bytes from [data]'s position to its limit, little endian, presented at
     * [presentationTimeUs]. [data] is only valid during the call.
     */
    fun write(
        data: ByteBuffer,
        presentationTimeUs: Long,
    )
}

/**
 * A stretch of a track to decode: from [seekToUs] (from where the extractor is when negative) until the end of the
 * stream, or until the next packet starts after [untilUs].
 */
class DecodeWindow(
    val untilUs: Long = Long.MAX_VALUE,
    val seekToUs: Long = -1L,
)

/** The decoder did not finish within its deadline. */
class DecodeTimeoutException(
    timeoutMs: Long,
) : IOException("Decoding took longer than $timeoutMs ms")

/**
 * Decodes audio tracks with [MediaCodec], the same platform stack the system plays notification sounds with. Shared by
 * the importer's test-decodes and auto haptics at install (#279, #315, ADR 0077):
 * - **Batches.** A decoder that takes several packets per input buffer (`FEATURE_MultipleFrames`, every
 *   `c2.android.*` audio decoder since Android 15) runs in asynchronous mode and gets as many packets per buffer as
 *   fit, so a clip costs a few round trips to the codec process instead of one per 20 ms packet. Other decoders run
 *   in synchronous mode, one packet at a time.
 * - **Sessions.** A [session] keeps its decoder between files: it stops it and configures it again for the next file
 *   instead of creating a new one. A decoder that failed or timed out is released, never reused.
 *
 * @param timeoutMs How long one [Session.decode] may take, all windows included.
 * @param batches Use batches when the decoder supports them (false: always one packet at a time, for comparisons).
 */
class PlatformAudioDecoder(
    private val timeoutMs: Long,
    private val batches: Boolean = true,
) {
    /** Whether a decoder takes several packets per input buffer, by "name|mime". */
    private val batchSupport = ConcurrentHashMap<String, Boolean>()

    /** A decoder for one worker. Not thread-safe: one file at a time. Close it when the worker is done. */
    fun session(): Session = Session()

    private fun supportsBatches(
        name: String,
        mime: String,
    ): Boolean =
        batches &&
            batchSupport.getOrPut("$name|$mime") {
                try {
                    MediaCodecList(MediaCodecList.REGULAR_CODECS)
                        .codecInfos
                        .firstOrNull { it.name == name }
                        ?.getCapabilitiesForType(mime)
                        ?.isFeatureSupported(CodecCapabilities.FEATURE_MultipleFrames) == true
                } catch (e: Exception) {
                    false
                }
            }

    inner class Session internal constructor() : AutoCloseable {
        /** The decoder kept from the previous file (stopped), and its name. */
        private var codec: MediaCodec? = null
        private var codecName: String? = null

        /** Delivers the callbacks of batched runs; they only queue events for the decoding thread. */
        private var callbackThread: HandlerThread? = null

        /**
         * Decodes [windows] of the track selected in [extractor], whose format is [trackFormat], into [sink]. Blocking.
         * Throws [IOException] when there is no decoder ([DecodeTimeoutException] when it takes longer than the
         * timeout), or whatever the codec throws; the decoder is then released, never reused.
         */
        fun decode(
            extractor: MediaExtractor,
            trackFormat: MediaFormat,
            sink: PcmSink,
            windows: List<DecodeWindow> = listOf(DecodeWindow()),
        ) {
            val mime = trackFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            val name =
                MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(trackFormat)
                    ?: throw IOException("No decoder for $mime")
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            try {
                val decoder = obtain(name)
                if (supportsBatches(name, mime)) {
                    val thread = callbackThread ?: HandlerThread("cucuto-audio-decoder").also { it.start() }
                    callbackThread = thread
                    val handler = Handler(thread.looper)
                    for (window in windows) {
                        if (window.seekToUs >= 0) {
                            extractor.seekTo(window.seekToUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        }
                        BatchedRun(decoder, extractor, trackFormat, sink, handler, deadline).decode(window.untilUs)
                    }
                } else {
                    decoder.configure(trackFormat, null, null, 0)
                    decoder.start()
                    val run = DecodeRun(decoder, extractor, sink, deadline)
                    windows.forEachIndexed { i, window ->
                        if (i > 0) decoder.flush()
                        if (window.seekToUs >= 0) {
                            extractor.seekTo(window.seekToUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        }
                        run.decode(window.untilUs)
                    }
                    decoder.stop()
                }
            } catch (e: Throwable) {
                discardCodec()
                throw e
            }
        }

        /** The kept decoder when it has [name] (stopped, ready to configure), or a new one. */
        private fun obtain(name: String): MediaCodec {
            codec?.let { if (codecName == name) return it }
            discardCodec()
            return MediaCodec.createByCodecName(name).also {
                codec = it
                codecName = name
            }
        }

        private fun discardCodec() {
            val c = codec ?: return
            codec = null
            codecName = null
            try {
                c.release()
            } catch (e: Exception) {
                // Nothing left to free.
            }
        }

        override fun close() {
            discardCodec()
            callbackThread?.quitSafely()
            callbackThread = null
        }
    }

    /** Synchronous mode: feeds [extractor] samples to [codec] one at a time. */
    private inner class DecodeRun(
        private val codec: MediaCodec,
        private val extractor: MediaExtractor,
        private val sink: PcmSink,
        private val deadline: Long,
    ) {
        private val info = MediaCodec.BufferInfo()

        /** Decodes until the end of the stream, or until the input passes [untilUs]. */
        fun decode(untilUs: Long) {
            var inputDone = false
            while (true) {
                if (SystemClock.elapsedRealtime() > deadline) throw DecodeTimeoutException(timeoutMs)
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: throw IOException("No input buffer")
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
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(out) ?: throw IOException("No output buffer")
                            sink.write(buffer.unit(info.offset, info.size), info.presentationTimeUs)
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }

                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        sink.formatChanged(codec.outputFormat)
                    }
                }
            }
        }
    }

    /**
     * Asynchronous mode with batches (`MediaCodec.queueInputBuffers`): every input buffer carries as many packets as
     * fit. The callbacks only queue events; the decoding thread does all the work, so a late callback of an earlier
     * run can never touch the codec. One run is one configure, start and stop of [codec].
     */
    private inner class BatchedRun(
        private val codec: MediaCodec,
        private val extractor: MediaExtractor,
        private val trackFormat: MediaFormat,
        private val sink: PcmSink,
        private val handler: Handler,
        private val deadline: Long,
    ) {
        private val events = LinkedBlockingQueue<Event>()

        private val callback =
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                ) {
                    events.add(Event.Input(index))
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo,
                ) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    events.add(
                        Event.Output(
                            index,
                            intArrayOf(info.offset),
                            intArrayOf(info.size),
                            longArrayOf(info.presentationTimeUs),
                            eos,
                        ),
                    )
                }

                override fun onOutputBuffersAvailable(
                    codec: MediaCodec,
                    index: Int,
                    infos: ArrayDeque<MediaCodec.BufferInfo>,
                ) {
                    // Copied: the framework may reuse the infos after this returns.
                    val list = infos.toList()
                    val eos = list.any { it.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 }
                    val offsets = IntArray(list.size) { list[it].offset }
                    val sizes = IntArray(list.size) { list[it].size }
                    val times = LongArray(list.size) { list[it].presentationTimeUs }
                    events.add(Event.Output(index, offsets, sizes, times, eos))
                }

                override fun onOutputFormatChanged(
                    codec: MediaCodec,
                    format: MediaFormat,
                ) {
                    events.add(Event.FormatChanged(format))
                }

                override fun onError(
                    codec: MediaCodec,
                    e: MediaCodec.CodecException,
                ) {
                    events.add(Event.Failed(e))
                }
            }

        /** Decodes until the end of the stream, or until the input passes [untilUs], then stops [codec]. */
        fun decode(untilUs: Long) {
            val format =
                MediaFormat(trackFormat).apply {
                    setInteger(MediaFormat.KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE, BATCH_OUTPUT_BYTES)
                    setInteger(MediaFormat.KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE, BATCH_OUTPUT_BYTES)
                    if (intOr(MediaFormat.KEY_MAX_INPUT_SIZE, 0) < BATCH_INPUT_BYTES) {
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BATCH_INPUT_BYTES)
                    }
                }
            codec.setCallback(callback, handler)
            codec.configure(format, null, null, 0)
            codec.start()
            var inputDone = false
            while (true) {
                val wait = deadline - SystemClock.elapsedRealtime()
                if (wait <= 0) throw DecodeTimeoutException(timeoutMs)
                when (
                    val event =
                        events.poll(wait, TimeUnit.MILLISECONDS) ?: throw DecodeTimeoutException(timeoutMs)
                ) {
                    // After the end of the input, free input buffers stay with the client; stop() takes them back.
                    is Event.Input -> {
                        if (!inputDone) inputDone = fill(event.index, untilUs)
                    }

                    is Event.Output -> {
                        output(event)
                        codec.releaseOutputBuffer(event.index, false)
                        if (event.endOfStream) break
                    }

                    is Event.FormatChanged -> {
                        sink.formatChanged(event.format)
                    }

                    is Event.Failed -> {
                        throw event.error
                    }
                }
            }
            codec.stop()
        }

        /** Hands each access unit of an output buffer to the sink. */
        private fun output(event: Event.Output) {
            if (event.sizes.none { it > 0 }) return
            val buffer = codec.getOutputBuffer(event.index) ?: throw IOException("No output buffer")
            for (i in event.sizes.indices) {
                if (event.sizes[i] > 0) sink.write(buffer.unit(event.offsets[i], event.sizes[i]), event.timesUs[i])
            }
        }

        /** Fills input buffer [index] with the next packets. Returns true when it also carries the end of stream. */
        private fun fill(
            index: Int,
            untilUs: Long,
        ): Boolean {
            val buffer = codec.getInputBuffer(index) ?: throw IOException("No input buffer")
            val infos = ArrayDeque<MediaCodec.BufferInfo>()
            var offset = 0
            var end = false
            while (true) {
                val size = extractor.sampleSize
                val time = extractor.sampleTime
                if (size < 0 || time > untilUs) {
                    end = true
                    break
                }
                if (offset + size > buffer.capacity()) break
                val read = extractor.readSampleData(buffer, offset)
                if (read < 0) {
                    end = true
                    break
                }
                infos.add(MediaCodec.BufferInfo().apply { set(offset, read, time, 0) })
                offset += read
                extractor.advance()
            }
            if (infos.isEmpty() && !end) throw IOException("A packet is larger than the decoder's input buffer")
            if (end) {
                infos.add(
                    MediaCodec.BufferInfo().apply { set(offset, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM) },
                )
            }
            codec.queueInputBuffers(index, infos)
            return end
        }
    }

    private sealed interface Event {
        class Input(
            val index: Int,
        ) : Event

        class Output(
            val index: Int,
            val offsets: IntArray,
            val sizes: IntArray,
            val timesUs: LongArray,
            val endOfStream: Boolean,
        ) : Event

        class FormatChanged(
            val format: MediaFormat,
        ) : Event

        class Failed(
            val error: MediaCodec.CodecException,
        ) : Event
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L

        /** Input buffers of at least 64 KiB: a short Opus clip fits in one or two. */
        const val BATCH_INPUT_BYTES = 64 * 1024

        /** Output batches of up to 512 KiB of PCM (about 2.7 s of 48 kHz 16-bit stereo); the codec may lower it. */
        const val BATCH_OUTPUT_BYTES = 512 * 1024

        /** One access unit of an output buffer, little endian. */
        fun ByteBuffer.unit(
            offset: Int,
            size: Int,
        ): ByteBuffer {
            clear()
            limit(offset + size)
            position(offset)
            return slice().order(ByteOrder.LITTLE_ENDIAN)
        }
    }
}

/** Selects the first audio track of this extractor and returns its format, or null when it has none. */
fun MediaExtractor.selectAudioTrack(): MediaFormat? {
    val track =
        (0 until trackCount).firstOrNull {
            getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null
    selectTrack(track)
    return getTrackFormat(track)
}

/** The integer at [key], or [default] when the format has none. */
fun MediaFormat.intOr(
    key: String,
    default: Int,
): Int = if (containsKey(key)) getInteger(key) else default

/** Bytes per sample of a decoder's PCM output in [encoding] (`KEY_PCM_ENCODING`, 16-bit when absent). */
fun pcmBytesPerSample(encoding: Int): Int =
    when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> 2
    }
