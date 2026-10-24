package app.mauri.cucuto.core.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Band-limited sample rate conversion (windowed sinc, Blackman window, [ZERO_CROSSINGS] zero crossings each side).
 * Opus only takes 8, 12, 16, 24 or 48 kHz, and most files are 44.1 kHz, so every clip goes through here on its way to
 * 48 kHz. When converting down, the kernel is widened so content above the new Nyquist frequency is filtered out
 * instead of aliasing. The kernel is tabulated once and read with linear interpolation.
 */
object Resampler {
    const val ZERO_CROSSINGS = 16

    /** Fraction of the lower Nyquist frequency kept: the transition band sits just below it. */
    private const val PASSBAND = 0.95
    private const val TABLE_STEPS = 512

    fun resample(
        input: FloatArray,
        fromRate: Int,
        toRate: Int,
    ): FloatArray {
        require(fromRate > 0 && toRate > 0) { "Sample rates must be positive" }
        if (fromRate == toRate || input.isEmpty()) return input.copyOf()
        val outputSize = (input.size.toLong() * toRate / fromRate).toInt()
        val ratio = fromRate.toDouble() / toRate
        // Cutoff relative to the input's Nyquist frequency.
        val cutoff = min(1.0, toRate.toDouble() / fromRate) * PASSBAND
        val halfWidth = ZERO_CROSSINGS / cutoff
        val output = FloatArray(outputSize)
        val reach = halfWidth.toInt() + 1
        for (n in 0 until outputSize) {
            val t = n * ratio
            val center = floor(t).toInt()
            var sum = 0.0
            for (k in center - reach + 1..center + reach) {
                if (k < 0 || k >= input.size) continue
                val distance = abs(t - k)
                if (distance >= halfWidth) continue
                sum += input[k] * kernel(distance * cutoff)
            }
            output[n] = (sum * cutoff).toFloat()
        }
        return output
    }

    /** sinc(x) times a Blackman window reaching zero at [ZERO_CROSSINGS], for x = 0..ZERO_CROSSINGS. */
    private val table: FloatArray =
        FloatArray(ZERO_CROSSINGS * TABLE_STEPS + 2) { i ->
            val x = i.toDouble() / TABLE_STEPS
            if (x >= ZERO_CROSSINGS) {
                0f
            } else {
                val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                val w = x / ZERO_CROSSINGS // 0 at the center, 1 at the edge
                val window = 0.42 + 0.5 * cos(PI * w) + 0.08 * cos(2 * PI * w)
                (sinc * window).toFloat()
            }
        }

    private fun kernel(x: Double): Double {
        val position = x * TABLE_STEPS
        val i = position.toInt()
        if (i >= table.size - 1) return 0.0
        val fraction = position - i
        return table[i] + (table[i + 1] - table[i]) * fraction
    }
}
