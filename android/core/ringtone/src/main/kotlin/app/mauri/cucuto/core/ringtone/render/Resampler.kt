package app.mauri.cucuto.core.ringtone.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming sample-rate converter for ring files: Android's decoders keep the source rate, and the Opus encoder takes
 * 48 kHz (packer has ffmpeg resample to 48 kHz first, so splice positions are frames at 48 kHz everywhere).
 *
 * A windowed-sinc interpolator (Blackman window, [ZERO_CROSSINGS] zero crossings each side, low-pass at 95 % of the
 * lower Nyquist frequency) with a precomputed kernel table. Offline quality is what matters; it runs once per exported
 * variant. Equal rates pass samples through untouched, so 48 kHz sources stay sample-exact.
 *
 * Input and output are interleaved floats with [channels] channels. The output has `floor(inputFrames * outRate /
 * inRate)` frames once [finish] has run.
 */
class Resampler(
    private val inRate: Int,
    private val outRate: Int,
    private val channels: Int,
) {
    private val passThrough = inRate == outRate
    private val cutoff = CUTOFF * min(1.0, outRate.toDouble() / inRate)
    private val half: Int = ceil(ZERO_CROSSINGS / cutoff).toInt()
    private val table = FloatArray(half * RESOLUTION + 2)
    private val weights = DoubleArray(2 * half)

    private var buffer = FloatArray(INITIAL_FRAMES * channels)
    private var bufferStart = 0L
    private var bufferFrames = 0
    private var inputFrames = 0L
    private var nextOut = 0L
    private val out = FloatArray(OUT_CHUNK * channels)

    init {
        require(inRate > 0 && outRate > 0 && channels > 0)
        for (i in table.indices) {
            val d = i.toDouble() / RESOLUTION
            table[i] = if (d >= half) 0f else (cutoff * sinc(cutoff * d) * blackman(d / half)).toFloat()
        }
    }

    /** Feeds [frames] frames of [input]; converted frames go to [sink] (which must copy what it keeps). */
    fun process(
        input: FloatArray,
        frames: Int,
        sink: (FloatArray, Int) -> Unit,
    ) {
        if (frames <= 0) return
        if (passThrough) {
            sink(input, frames)
            return
        }
        append(input, frames)
        produce(final = false, sink)
    }

    /** Flushes the last frames (the input is over). */
    fun finish(sink: (FloatArray, Int) -> Unit) {
        if (!passThrough) produce(final = true, sink)
    }

    private fun append(
        input: FloatArray,
        frames: Int,
    ) {
        val needed = (bufferFrames + frames) * channels
        if (needed > buffer.size) buffer = buffer.copyOf(maxOf(needed, buffer.size * 2))
        input.copyInto(buffer, bufferFrames * channels, 0, frames * channels)
        bufferFrames += frames
        inputFrames += frames
    }

    private fun produce(
        final: Boolean,
        sink: (FloatArray, Int) -> Unit,
    ) {
        val lastOut = if (final) inputFrames * outRate / inRate else Long.MAX_VALUE
        var filled = 0
        while (nextOut < lastOut) {
            val t = nextOut.toDouble() * inRate / outRate
            val center = floor(t).toLong()
            if (!final && center + half >= inputFrames) break
            var sum = 0.0
            for (j in 0 until 2 * half) {
                val k = center - half + 1 + j
                val w = kernel(abs(t - k))
                weights[j] = w
                sum += w
            }
            val norm = if (sum != 0.0) 1.0 / sum else 1.0
            for (c in 0 until channels) {
                var acc = 0.0
                for (j in 0 until 2 * half) {
                    val k = center - half + 1 + j
                    if (k < 0 || k >= inputFrames) continue
                    acc += weights[j] * buffer[((k - bufferStart) * channels + c).toInt()]
                }
                out[filled * channels + c] = (acc * norm).toFloat()
            }
            filled++
            nextOut++
            if (filled == OUT_CHUNK) {
                sink(out, filled)
                filled = 0
            }
        }
        if (filled > 0) sink(out, filled)
        compact()
    }

    /** Drops input frames no future output frame can reach. */
    private fun compact() {
        val keepFrom = floor(nextOut.toDouble() * inRate / outRate).toLong() - half
        val drop = (keepFrom - bufferStart).coerceIn(0, bufferFrames.toLong()).toInt()
        if (drop <= 0) return
        buffer.copyInto(buffer, 0, drop * channels, bufferFrames * channels)
        bufferFrames -= drop
        bufferStart += drop
    }

    private fun kernel(d: Double): Double {
        val x = d * RESOLUTION
        val i = x.toInt()
        if (i >= table.size - 1) return 0.0
        val f = x - i
        return table[i] + (table[i + 1] - table[i]) * f
    }

    private companion object {
        const val ZERO_CROSSINGS = 16
        const val CUTOFF = 0.95
        const val RESOLUTION = 512
        const val INITIAL_FRAMES = 8192
        const val OUT_CHUNK = 4096

        fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        /** Blackman window over `u` in [0, 1] (0 is the center). */
        fun blackman(u: Double): Double = 0.42 + 0.5 * cos(PI * u) + 0.08 * cos(2 * PI * u)
    }
}
