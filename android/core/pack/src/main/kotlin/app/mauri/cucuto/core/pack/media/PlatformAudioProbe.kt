package app.mauri.cucuto.core.pack.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import app.mauri.cucuto.packspec.FileFormat
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * [AudioProbe] with [MediaExtractor] and [MediaCodec], the same stack the system uses to play notification sounds.
 * The duration is the container's (`KEY_DURATION`), as for the header checks; when the container has none, it is
 * measured from the decoded frames.
 *
 * Every packet of a clip is decoded, as before; what changed for packs with many clips (#279, ADR 0077):
 * - **Batches.** A decoder that takes several packets per input buffer (`FEATURE_MultipleFrames`, every
 *   `c2.android.*` audio decoder since Android 15) runs in asynchronous mode and gets as many packets per buffer as
 *   fit, so a clip costs a few round trips to the codec process instead of one per 20 ms packet. Other decoders run
 *   in synchronous mode, one packet at a time.
 * - **Sessions.** A [session] keeps its decoder between files: it stops it and configures it again for the next file
 *   instead of creating a new one. A decoder that failed or timed out is released, never reused.
 */
class PlatformAudioProbe(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** Use batches when the decoder supports them (false: always one packet at a time, for comparisons). */
    private val batches: Boolean = true,
) : AudioProbe {
    /** Whether a decoder takes several packets per input buffer, by "name|mime". */
    private val batchSupport = ConcurrentHashMap<String, Boolean>()

    override fun probe(
        file: File,
        format: FileFormat,
        full: Boolean,
    ): AudioProbeResult = session().use { it.probe(file, format, full) }

    override fun session(): AudioProbe.Session = Session()

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

    private inner class Session : AudioProbe.Session {
        /** The decoder kept from the previous file (stopped), and its name. */
        private var codec: MediaCodec? = null
        private var codecName: String? = null

        /** Delivers the callbacks of batched runs; they only queue events for the probing thread. */
        private var callbackThread: HandlerThread? = null

        override fun probe(
            file: File,
            format: FileFormat,
            full: Boolean,
        ): AudioProbeResult {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(file.absolutePath)
                val track =
                    (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    } ?: return AudioProbeResult.Undecodable("No audio track")
                extractor.selectTrack(track)
                val trackFormat = extractor.getTrackFormat(track)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME).orEmpty()
                val declaredUs =
                    if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                        trackFormat.getLong(MediaFormat.KEY_DURATION)
                    } else {
                        -1L
                    }
                val name =
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(trackFormat)
                        ?: return AudioProbeResult.Undecodable("No decoder for $mime")
                val decoder = obtain(name)
                val counter = FrameCounter(trackFormat)
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                // Long files: the start, then the end (seeking proves the index and the tail are usable too).
                val partial = !full && declaredUs > 2 * PARTIAL_DECODE_US
                if (supportsBatches(name, mime)) {
                    val thread = callbackThread ?: HandlerThread("cucuto-audio-probe").also { it.start() }
                    callbackThread = thread
                    val handler = Handler(thread.looper)
                    val run = { untilUs: Long ->
                        BatchedRun(decoder, extractor, trackFormat, counter, handler, deadline).decode(untilUs)
                    }
                    if (partial) {
                        run(PARTIAL_DECODE_US)
                        extractor.seekTo(declaredUs - PARTIAL_DECODE_US, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    }
                    run(Long.MAX_VALUE)
                } else {
                    decoder.configure(trackFormat, null, null, 0)
                    decoder.start()
                    val run = DecodeRun(decoder, extractor, counter, deadline)
                    if (partial) {
                        run.decode(untilUs = PARTIAL_DECODE_US)
                        decoder.flush()
                        extractor.seekTo(declaredUs - PARTIAL_DECODE_US, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    }
                    run.decode(untilUs = Long.MAX_VALUE)
                    decoder.stop()
                }
                when {
                    counter.frames == 0L -> AudioProbeResult.Undecodable("The decoder produced no audio")
                    declaredUs > 0 -> AudioProbeResult.Decoded(declaredUs / 1000)
                    else -> AudioProbeResult.Decoded(counter.endUs / 1000)
                }
            } catch (e: DecodeTimeoutException) {
                discardCodec()
                AudioProbeResult.Undecodable("Decoding took longer than $timeoutMs ms")
            } catch (e: Exception) {
                discardCodec()
                AudioProbeResult.Undecodable(e.message ?: e.javaClass.simpleName)
            } finally {
                extractor.release()
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

    private class DecodeTimeoutException : Exception()

    /** Counts the decoded PCM frames and where the audio ends. */
    private class FrameCounter(
        trackFormat: MediaFormat,
    ) {
        private var sampleRate = trackFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 0)
        private var bytesPerFrame = bytesPerFrame(trackFormat)

        var frames = 0L
            private set
        var endUs = 0L
            private set

        fun formatChanged(f: MediaFormat) {
            sampleRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
            bytesPerFrame = bytesPerFrame(f)
        }

        fun add(
            size: Int,
            presentationTimeUs: Long,
        ) {
            if (size <= 0 || bytesPerFrame <= 0) return
            val n = (size / bytesPerFrame).toLong()
            frames += n
            if (sampleRate > 0) endUs = maxOf(endUs, presentationTimeUs + n * 1_000_000L / sampleRate)
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
    }

    /** Synchronous mode: feeds [extractor] samples to [codec] one at a time. */
    private class DecodeRun(
        private val codec: MediaCodec,
        private val extractor: MediaExtractor,
        private val counter: FrameCounter,
        private val deadline: Long,
    ) {
        private val info = MediaCodec.BufferInfo()

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
                        counter.add(info.size, info.presentationTimeUs)
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }

                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        counter.formatChanged(codec.outputFormat)
                    }
                }
            }
        }
    }

    /**
     * Asynchronous mode with batches (`MediaCodec.queueInputBuffers`): every input buffer carries as many packets as
     * fit. The callbacks only queue events; this thread does all the work, so a late callback of an earlier run can
     * never touch the codec. One run is one configure, start and stop of [codec].
     */
    private class BatchedRun(
        private val codec: MediaCodec,
        private val extractor: MediaExtractor,
        private val trackFormat: MediaFormat,
        private val counter: FrameCounter,
        private val handler: Handler,
        private val deadline: Long,
    ) {
        private sealed interface Event

        private class Input(
            val index: Int,
        ) : Event

        private class Output(
            val index: Int,
            val sizes: IntArray,
            val timesUs: LongArray,
            val endOfStream: Boolean,
        ) : Event

        private class FormatChanged(
            val format: MediaFormat,
        ) : Event

        private class Failed(
            val error: MediaCodec.CodecException,
        ) : Event

        private val events = LinkedBlockingQueue<Event>()

        private val callback =
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                ) {
                    events.add(Input(index))
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo,
                ) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    events.add(Output(index, intArrayOf(info.size), longArrayOf(info.presentationTimeUs), eos))
                }

                override fun onOutputBuffersAvailable(
                    codec: MediaCodec,
                    index: Int,
                    infos: ArrayDeque<MediaCodec.BufferInfo>,
                ) {
                    // Copied: the framework may reuse the infos after this returns.
                    val list = infos.toList()
                    val eos = list.any { it.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 }
                    val sizes = IntArray(list.size) { list[it].size }
                    val times = LongArray(list.size) { list[it].presentationTimeUs }
                    events.add(Output(index, sizes, times, eos))
                }

                override fun onOutputFormatChanged(
                    codec: MediaCodec,
                    format: MediaFormat,
                ) {
                    events.add(FormatChanged(format))
                }

                override fun onError(
                    codec: MediaCodec,
                    e: MediaCodec.CodecException,
                ) {
                    events.add(Failed(e))
                }
            }

        /** Decodes until the end of the stream, or until the input passes [untilUs], then stops [codec]. */
        fun decode(untilUs: Long) {
            val format =
                MediaFormat(trackFormat).apply {
                    setInteger(MediaFormat.KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE, BATCH_OUTPUT_BYTES)
                    setInteger(MediaFormat.KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE, BATCH_OUTPUT_BYTES)
                    val input =
                        if (containsKey(
                                MediaFormat.KEY_MAX_INPUT_SIZE,
                            )
                        ) {
                            getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        } else {
                            0
                        }
                    if (input < BATCH_INPUT_BYTES) setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BATCH_INPUT_BYTES)
                }
            codec.setCallback(callback, handler)
            codec.configure(format, null, null, 0)
            codec.start()
            var inputDone = false
            while (true) {
                val wait = deadline - SystemClock.elapsedRealtime()
                if (wait <= 0) throw DecodeTimeoutException()
                when (val event = events.poll(wait, TimeUnit.MILLISECONDS) ?: throw DecodeTimeoutException()) {
                    // After the end of the input, free input buffers stay with the client; stop() takes them back.
                    is Input -> {
                        if (!inputDone) inputDone = fill(event.index, untilUs)
                    }

                    is Output -> {
                        for (i in event.sizes.indices) counter.add(event.sizes[i], event.timesUs[i])
                        codec.releaseOutputBuffer(event.index, false)
                        if (event.endOfStream) break
                    }

                    is FormatChanged -> {
                        counter.formatChanged(event.format)
                    }

                    is Failed -> {
                        throw event.error
                    }
                }
            }
            codec.stop()
        }

        /** Fills input buffer [index] with the next packets. Returns true when it also carries the end of stream. */
        private fun fill(
            index: Int,
            untilUs: Long,
        ): Boolean {
            val buffer = codec.getInputBuffer(index) ?: error("No input buffer")
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
            if (infos.isEmpty() && !end) error("A packet is larger than the decoder's input buffer")
            if (end) {
                infos.add(
                    MediaCodec.BufferInfo().apply { set(offset, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM) },
                )
            }
            codec.queueInputBuffers(index, infos)
            return end
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val DEQUEUE_TIMEOUT_US = 10_000L

        /** How much of a ringtone's start and end is decoded. */
        const val PARTIAL_DECODE_US = 2_000_000L

        /** Input buffers of at least 64 KiB: a short Opus clip fits in one or two. */
        const val BATCH_INPUT_BYTES = 64 * 1024

        /** Output batches of up to 512 KiB of PCM (about 2.7 s of 48 kHz 16-bit stereo); the codec may lower it. */
        const val BATCH_OUTPUT_BYTES = 512 * 1024

        fun MediaFormat.intOr(
            key: String,
            default: Int,
        ): Int = if (containsKey(key)) getInteger(key) else default
    }
}
