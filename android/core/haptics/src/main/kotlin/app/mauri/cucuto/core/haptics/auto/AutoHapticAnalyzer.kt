package app.mauri.cucuto.core.haptics.auto

import app.mauri.cucuto.core.haptics.EnvelopeLimits
import app.mauri.cucuto.core.haptics.EnvelopePoint
import app.mauri.cucuto.core.haptics.Envelopes
import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.routing.Haptic
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * "Haptics follow the voice" (pack `auto` mode): turns a clip's audio into one basic envelope that rises and falls
 * with it. Pure Kotlin on decoded PCM ([ClipPcmDecoder] does the decoding), so it is tested on the JVM.
 *
 * 1. **Loudness envelope**: RMS of each [FRAME_MS] frame, at most [MAX_DURATION_MS] of audio.
 * 2. **Intensity**: loudness relative to the clip's loudest frame, in decibels, mapped linearly from
 *    `-dynamicRangeDb` (0) to 0 dB (1). Basic envelope intensity is perceptual and so is the decibel scale, so a
 *    voice twice as quiet feels proportionally weaker. Frames below [Options.silenceDbfs] are silent.
 * 3. **Sharpness**: a brightness proxy from the zero-crossing rate. A frame crossing zero `z` times per sample has a
 *    dominant frequency around `z × rate / 2`; that is mapped on a log scale from 100 Hz (0, smooth: growls, hums)
 *    to 4 kHz (1, sharp: whistles, hisses), smoothed over three frames.
 * 4. **Shape**: leading and trailing silence are trimmed (the envelope starts at the first sound), the envelope is
 *    clamped so it ends by [MAX_DURATION_MS], always ends with a ramp to zero intensity (the API requires it) and is
 *    simplified with Ramer–Douglas–Peucker to the point budget, no two points closer than the device allows and
 *    no point spent on wobbles under 3 %.
 *
 * Returns null for silent clips (nothing to feel).
 */
object AutoHapticAnalyzer {
    /** Bump when the output changes, so stored analyses ([AutoHapticFile]) are redone. */
    const val VERSION: Int = 1

    const val FRAME_MS: Int = 20
    const val MAX_DURATION_MS: Int = 2000

    /**
     * BasicEnvelopeBuilder recommends at most 16 control points "for optimal cross-device consistency", and every
     * device with envelope support takes at least 16.
     */
    const val MAX_CONTROL_POINTS: Int = 16

    /** Wobbles up to 3 % (in thousandths) are not worth a control point: nobody feels them. */
    private const val NOISE_TOLERANCE = 30.0

    private const val MIN_SHARPNESS_HZ = 100.0
    private const val MAX_SHARPNESS_HZ = 4000.0

    /**
     * @property maxControlPoints Control point budget: the device's maximum, capped at [MAX_CONTROL_POINTS].
     * @property minControlPointMs The device's shortest segment (at least one frame).
     * @property dynamicRangeDb Loudness range mapped onto intensity 0..1, below the loudest frame.
     * @property silenceDbfs Absolute level below which a frame counts as silence.
     */
    data class Options(
        val maxControlPoints: Int = MAX_CONTROL_POINTS,
        val minControlPointMs: Int = FRAME_MS,
        val dynamicRangeDb: Double = 40.0,
        val silenceDbfs: Double = -60.0,
    ) {
        companion object {
            /** Options for a device: its point budget and shortest segment, within the analyser's own limits. */
            fun forDevice(capabilities: HapticCapabilities): Options =
                Options(
                    maxControlPoints =
                        capabilities.maxControlPoints
                            .takeIf { capabilities.envelopesSupported && it > 0 }
                            ?.coerceAtMost(MAX_CONTROL_POINTS) ?: MAX_CONTROL_POINTS,
                    minControlPointMs = capabilities.minControlPointMs.coerceIn(FRAME_MS, 200),
                )
        }
    }

    /** Loudness and brightness of one frame. */
    internal data class Frame(
        val rms: Double,
        val zeroCrossingRate: Double,
    )

    /** Analyses mono [samples] (−1..1) at [sampleRate] Hz. */
    fun analyze(
        samples: FloatArray,
        sampleRate: Int,
        options: Options = Options(),
    ): Haptic.Custom? {
        require(sampleRate > 0) { "Invalid sample rate $sampleRate" }
        val frames = frames(samples, sampleRate)
        if (frames.isEmpty()) return null
        val peak = frames.maxOf { it.rms }
        if (peak <= 0.0 || dbfs(peak) < options.silenceDbfs) return null

        val intensities = IntArray(frames.size) { intensity(frames[it].rms, peak, options) }
        val first = intensities.indexOfFirst { it > 0 }
        val last = intensities.indexOfLast { it > 0 }
        if (first < 0) return null
        val sharpness = sharpness(frames, intensities, sampleRate)

        val atMs = first * FRAME_MS
        val minMs = options.minControlPointMs.coerceAtLeast(1)
        // The envelope must end (after its ramp to zero) by MAX_DURATION_MS from the start of the clip.
        val available = MAX_DURATION_MS - atMs
        if (available < 2 * minMs) return null

        val points = ArrayList<EnvelopePoint>(last - first + 3)
        points += EnvelopePoint(0, 0, sharpness[first])
        for (i in first..last) {
            val time = (i - first + 1) * FRAME_MS
            if (time + minMs > available) break
            points += EnvelopePoint(time, intensities[i], sharpness[i])
        }
        if (points.size < 2) return null
        val polyline = Envelopes.endAtZero(points, minMs)

        val fitted =
            Envelopes.fit(
                polyline,
                EnvelopeLimits(
                    maxControlPoints = options.maxControlPoints.coerceIn(1, MAX_CONTROL_POINTS),
                    minSegmentMs = minMs,
                    maxSegmentMs = GUARANTEED_MAX_SEGMENT_MS,
                    maxDurationMs = available,
                ),
                tolerance = NOISE_TOLERANCE,
            )
        if (fitted.none { it.intensity > 0 }) return null
        return Haptic.Custom(listOf(Envelopes.toEvent(atMs, fitted)))
    }

    /** RMS and zero-crossing rate per [FRAME_MS] frame of the first [MAX_DURATION_MS]; a short tail frame counts. */
    internal fun frames(
        samples: FloatArray,
        sampleRate: Int,
    ): List<Frame> {
        val frameLength = (sampleRate.toLong() * FRAME_MS / 1000).toInt().coerceAtLeast(1)
        val limit = minOf(samples.size.toLong(), sampleRate.toLong() * MAX_DURATION_MS / 1000).toInt()
        val frames = ArrayList<Frame>(limit / frameLength + 1)
        var start = 0
        while (start < limit) {
            val end = minOf(start + frameLength, limit)
            // A partial last frame shorter than half a frame is too short to measure.
            if (end - start < frameLength / 2 && frames.isNotEmpty()) break
            var sum = 0.0
            var mean = 0.0
            for (i in start until end) mean += samples[i]
            mean /= (end - start)
            var crossings = 0
            var previous = 0.0
            for (i in start until end) {
                val x = samples[i] - mean
                sum += x * x
                if (i > start && (x >= 0) != (previous >= 0)) crossings++
                previous = x
            }
            frames += Frame(sqrt(sum / (end - start)), crossings.toDouble() / (end - start))
            start = end
        }
        return frames
    }

    /** Intensity in thousandths for a frame of [rms] in a clip whose loudest frame is [peak]. */
    internal fun intensity(
        rms: Double,
        peak: Double,
        options: Options,
    ): Int {
        if (rms <= 0.0 || dbfs(rms) < options.silenceDbfs) return 0
        val relative = 20 * log10(rms / peak)
        val value = (relative + options.dynamicRangeDb) / options.dynamicRangeDb
        return (value.coerceIn(0.0, 1.0) * 1000).roundToInt()
    }

    /** Sharpness in thousandths for a dominant frequency of [hz]: log scale, 100 Hz → 0, 4 kHz → 1. */
    internal fun sharpnessForFrequency(hz: Double): Int {
        if (hz <= MIN_SHARPNESS_HZ) return 0
        val value = ln(hz / MIN_SHARPNESS_HZ) / ln(MAX_SHARPNESS_HZ / MIN_SHARPNESS_HZ)
        return (value.coerceIn(0.0, 1.0) * 1000).roundToInt()
    }

    /** Per-frame sharpness: three-frame average over sounding frames; silent frames hold the nearest value. */
    private fun sharpness(
        frames: List<Frame>,
        intensities: IntArray,
        sampleRate: Int,
    ): IntArray {
        val raw = IntArray(frames.size) { sharpnessForFrequency(frames[it].zeroCrossingRate * sampleRate / 2) }
        val out = IntArray(frames.size)
        var held = -1
        for (i in frames.indices) {
            if (intensities[i] == 0) continue
            var sum = 0
            var count = 0
            for (j in maxOf(0, i - 1)..minOf(frames.lastIndex, i + 1)) {
                if (intensities[j] > 0) {
                    sum += raw[j]
                    count++
                }
            }
            out[i] = sum / count
            if (held < 0) held = out[i]
        }
        // Silent frames take the previous sounding frame's sharpness (the first sounding one at the start).
        for (i in frames.indices) {
            if (intensities[i] == 0) out[i] = held.coerceAtLeast(0) else held = out[i]
        }
        return out
    }

    private fun dbfs(rms: Double): Double = 20 * log10(rms)

    /** Every device with envelope support takes segments of up to 1 s. */
    private const val GUARANTEED_MAX_SEGMENT_MS = 1000
}
