package app.mauri.cucuto.packspec.authoring

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The creators' clip pipeline on mono 48 kHz floats, with packer's targets ([Authoring.Audio]): cut → trim leading and
 * trailing silence → normalize to −16 LUFS under a −1.5 dBTP ceiling → 5 ms fades at both ends → clamp. Pure common
 * Kotlin, so the website's creator (#48, through the browser build) runs exactly the processing of the app's (#47).
 * Decoding and resampling to 48 kHz stay platform work (MediaCodec in the app, Web Audio in the browser).
 */
object ClipDsp {
    /** A finished clip, ready to encode. [loudnessLufs] is null for silence. */
    class Processed(
        val samples: FloatArray,
        val gainDb: Double,
        val loudnessLufs: Double?,
    ) {
        val durationMs: Long get() = samples.size * MILLIS / Authoring.Audio.SAMPLE_RATE_HZ

        /** True when nothing in the clip rises above the silence threshold. */
        val isSilent: Boolean get() = loudnessLufs == null
    }

    /**
     * Processes `[startMs, endMs)` of [samples] (mono, [Authoring.Audio.SAMPLE_RATE_HZ]). Never changes [samples].
     */
    fun process(
        samples: FloatArray,
        startMs: Long,
        endMs: Long,
        trimSilence: Boolean = true,
        normalize: Boolean = true,
    ): Processed {
        val rate = Authoring.Audio.SAMPLE_RATE_HZ
        val from = (startMs * rate / MILLIS).toInt().coerceIn(0, samples.size)
        val to = (endMs * rate / MILLIS).toInt().coerceIn(from, samples.size)
        var out = samples.copyOfRange(from, to)
        if (trimSilence) {
            val bounds = SilenceTrim.bounds(out, rate)
            if (bounds != null) out = out.copyOfRange(bounds.first, bounds.last + 1)
        }
        val gain =
            if (normalize) {
                Loudness.normalizationGainDb(
                    out,
                    Authoring.Audio.TARGET_LUFS,
                    Authoring.Audio.TRUE_PEAK_CEILING_DBTP,
                    Authoring.Audio.MAX_GAIN_DB,
                )
            } else {
                0.0
            }
        Loudness.applyGain(out, gain)
        fadeEdges(out, rate, Authoring.Audio.EDGE_FADE_MS)
        for (i in out.indices) out[i] = out[i].coerceIn(-1f, 1f)
        val loudness = if (SilenceTrim.bounds(out, rate) == null) null else Loudness.integratedLufs(out)
        return Processed(out, gain, loudness)
    }

    /** Raised-cosine fade-in and fade-out of [fadeMs] (shorter for very short clips), in place. */
    fun fadeEdges(
        samples: FloatArray,
        sampleRate: Int,
        fadeMs: Long,
    ) {
        val length = minOf((sampleRate * fadeMs / MILLIS).toInt(), samples.size / 2)
        for (i in 0 until length) {
            val gain = (0.5 - 0.5 * cos(PI * i / length)).toFloat()
            samples[i] *= gain
            samples[samples.size - 1 - i] *= gain
        }
    }

    private const val MILLIS = 1000L
}

/** Leading and trailing silence, as packer's `silenceremove` trims it (threshold and the silence kept around sound). */
object SilenceTrim {
    /**
     * The first and last sample to keep in [samples]: from [Authoring.Audio.SILENCE_KEEP_MS] before the first sample
     * above [thresholdDbfs] to the same after the last one. Null when everything is below the threshold.
     */
    fun bounds(
        samples: FloatArray,
        sampleRate: Int,
        thresholdDbfs: Double = Authoring.Audio.SILENCE_THRESHOLD_DBFS,
        keepMs: Long = Authoring.Audio.SILENCE_KEEP_MS,
    ): IntRange? {
        val threshold = 10.0.pow(thresholdDbfs / 20).toFloat()
        val first = samples.indexOfFirst { abs(it) > threshold }
        if (first < 0) return null
        val last = samples.indexOfLast { abs(it) > threshold }
        val keep = (sampleRate * keepMs / 1000).toInt()
        return (first - keep).coerceAtLeast(0)..(last + keep).coerceAtMost(samples.size - 1)
    }
}

/**
 * Loudness of mono 48 kHz audio by ITU-R BS.1770-5 / EBU R128, the measure packer's `--normalize` (ffmpeg loudnorm)
 * targets, so clips made in the creators sound as loud as clips made with packer.
 *
 * - Integrated loudness: K-weighting (the standard's 48 kHz biquads), 400 ms blocks overlapping by 75 %, the absolute
 *   gate at −70 LUFS and the relative gate 10 LU below the ungated mean. Clips shorter than one block (the format
 *   allows 200 ms) are measured as a single block.
 * - True peak: 4× oversampling with a windowed-sinc interpolator, as in BS.1770 Annex 2.
 */
object Loudness {
    const val SAMPLE_RATE = 48_000
    private const val BLOCK_SAMPLES = SAMPLE_RATE * 400 / 1000
    private const val STEP_SAMPLES = SAMPLE_RATE * 100 / 1000
    private const val ABSOLUTE_GATE_LUFS = -70.0
    private const val RELATIVE_GATE_LU = -10.0
    private const val OFFSET = -0.691

    /** Integrated loudness in LUFS, or null for silence (nothing above the absolute gate). */
    fun integratedLufs(samples: FloatArray): Double? {
        if (samples.isEmpty()) return null
        val weighted = kWeight(samples)
        val blocks = blockPowers(weighted)
        val aboveAbsolute = blocks.filter { lufsOf(it) > ABSOLUTE_GATE_LUFS }
        if (aboveAbsolute.isEmpty()) return null
        val relativeGate = lufsOf(aboveAbsolute.average()) + RELATIVE_GATE_LU
        val gated = aboveAbsolute.filter { lufsOf(it) > relativeGate }
        return lufsOf(gated.average())
    }

    /** True peak in dBTP (−∞ for silence). */
    fun truePeakDb(samples: FloatArray): Double {
        val peak = truePeak(samples)
        return if (peak <= 0.0) Double.NEGATIVE_INFINITY else 20 * log10(peak)
    }

    /** The highest absolute value of the 4× oversampled signal (linear, 1.0 = full scale). */
    fun truePeak(samples: FloatArray): Double {
        var peak = 0.0
        for (i in samples.indices) {
            peak = max(peak, abs(samples[i].toDouble()))
            for (phase in 1 until OVERSAMPLE) {
                val taps = PHASES[phase - 1]
                var sum = 0.0
                for (j in taps.indices) {
                    val k = i + j - TAPS_EACH_SIDE + 1
                    if (k in samples.indices) sum += samples[k] * taps[j]
                }
                peak = max(peak, abs(sum))
            }
        }
        return peak
    }

    /** The gain in dB that brings [samples] to [targetLufs] without its true peak passing [ceilingDbtp]. */
    fun normalizationGainDb(
        samples: FloatArray,
        targetLufs: Double,
        ceilingDbtp: Double,
        maxGainDb: Double,
    ): Double {
        val loudness = integratedLufs(samples) ?: return 0.0
        val peak = truePeakDb(samples)
        val byLoudness = targetLufs - loudness
        val byPeak = if (peak.isInfinite()) maxGainDb else ceilingDbtp - peak
        return min(min(byLoudness, byPeak), maxGainDb)
    }

    /** Multiplies [samples] by [gainDb] in place. */
    fun applyGain(
        samples: FloatArray,
        gainDb: Double,
    ) {
        if (gainDb == 0.0) return
        val factor = 10.0.pow(gainDb / 20).toFloat()
        for (i in samples.indices) samples[i] *= factor
    }

    private fun lufsOf(meanSquare: Double): Double =
        if (meanSquare <= 0.0) Double.NEGATIVE_INFINITY else OFFSET + 10 * log10(meanSquare)

    /** Mean square of each gating block; one block over everything for audio shorter than a block. */
    private fun blockPowers(weighted: DoubleArray): List<Double> {
        if (weighted.size < BLOCK_SAMPLES) return listOf(weighted.sumOf { it * it } / weighted.size)
        val blocks = ArrayList<Double>()
        var start = 0
        while (start + BLOCK_SAMPLES <= weighted.size) {
            var sum = 0.0
            for (i in start until start + BLOCK_SAMPLES) sum += weighted[i] * weighted[i]
            blocks += sum / BLOCK_SAMPLES
            start += STEP_SAMPLES
        }
        return blocks
    }

    /** The two K-weighting stages: the head's high shelf, then the RLB high-pass (BS.1770 table 1 and 2, 48 kHz). */
    private fun kWeight(samples: FloatArray): DoubleArray {
        val shelf = Biquad(1.53512485958697, -2.69169618940638, 1.19839281085285, -1.69065929318241, 0.73248077421585)
        val highPass = Biquad(1.0, -2.0, 1.0, -1.99004745483398, 0.99007225036621)
        return DoubleArray(samples.size) { highPass.next(shelf.next(samples[it].toDouble())) }
    }

    /** Direct form I biquad with a0 = 1. */
    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun next(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }

    private const val OVERSAMPLE = 4
    private const val TAPS_EACH_SIDE = 6

    /** Interpolation taps for the 3 in-between phases (12 taps each, 48 in all as in Annex 2), Hann-windowed sinc. */
    private val PHASES: List<DoubleArray> =
        (1 until OVERSAMPLE).map { phase ->
            val offset = phase.toDouble() / OVERSAMPLE
            DoubleArray(TAPS_EACH_SIDE * 2) { j ->
                // Tap j multiplies sample i + j - 5; its distance from the interpolated point i + offset:
                val x = (j - TAPS_EACH_SIDE + 1) - offset
                val sinc = sin(PI * x) / (PI * x)
                val window = 0.5 + 0.5 * cos(PI * x / TAPS_EACH_SIDE)
                sinc * window
            }
        }
}
