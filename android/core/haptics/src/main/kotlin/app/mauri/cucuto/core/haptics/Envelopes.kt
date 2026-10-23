package app.mauri.cucuto.core.haptics

import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.HapticEvent
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToInt

/** A corner of an envelope on an absolute time axis: milliseconds from the envelope start, values in thousandths. */
internal data class EnvelopePoint(
    val timeMs: Int,
    val intensity: Int,
    val sharpness: Int,
)

/**
 * Envelopes as polylines: the implicit start (`BasicEnvelopeBuilder` always starts at intensity 0, with the initial
 * sharpness) followed by one point per control point.
 */
internal object Envelopes {
    /** Sharpness differences count half as much as intensity differences when simplifying. */
    private const val SHARPNESS_WEIGHT = 0.5

    /** The polyline of [envelope], with values clamped to 0..1000 and every duration at least 1 ms. */
    fun polyline(envelope: HapticEvent.Envelope): List<EnvelopePoint> {
        val first = envelope.points.first()
        val initial =
            if (envelope.milliInitialSharpness == HapticEvent.Envelope.DEFAULT_SHARPNESS) {
                first.milliSharpness
            } else {
                envelope.milliInitialSharpness
            }
        val out = ArrayList<EnvelopePoint>(envelope.points.size + 1)
        out += EnvelopePoint(0, 0, milli(initial))
        var time = 0
        for (point in envelope.points) {
            time += point.durationMs.coerceAtLeast(1)
            out += EnvelopePoint(time, milli(point.milliIntensity), milli(point.milliSharpness))
        }
        return out
    }

    /** The envelope event that plays [polyline] at [atMs]. The initial sharpness is omitted when implied. */
    fun toEvent(
        atMs: Int,
        polyline: List<EnvelopePoint>,
    ): HapticEvent.Envelope {
        require(polyline.size >= 2) { "An envelope needs at least one control point" }
        val points =
            (1 until polyline.size).map { i ->
                val p = polyline[i]
                ControlPoint(p.intensity, p.sharpness, p.timeMs - polyline[i - 1].timeMs)
            }
        val initial = polyline[0].sharpness
        val sharpness = if (initial == points[0].milliSharpness) HapticEvent.Envelope.DEFAULT_SHARPNESS else initial
        return HapticEvent.Envelope(atMs, points, sharpness)
    }

    /** Appends a ramp to zero intensity if the last point is not at zero (the envelope API requires it). */
    fun endAtZero(
        polyline: List<EnvelopePoint>,
        rampMs: Int,
    ): List<EnvelopePoint> {
        val last = polyline.last()
        if (last.intensity == 0 && polyline.size >= 2) return polyline
        return polyline + EnvelopePoint(last.timeMs + rampMs.coerceAtLeast(1), 0, last.sharpness)
    }

    /**
     * Cuts [polyline] so it ends by [maxDurationMs], ramping to zero over the last [rampMs]. Returns it unchanged
     * when it is already short enough.
     */
    fun truncate(
        polyline: List<EnvelopePoint>,
        maxDurationMs: Int,
        rampMs: Int,
    ): List<EnvelopePoint> {
        val max = maxDurationMs.coerceAtLeast(1)
        if (polyline.last().timeMs <= max) return polyline
        val ramp = rampMs.coerceIn(1, max)
        val cut = max - ramp
        val out = ArrayList<EnvelopePoint>()
        for (point in polyline) {
            if (point.timeMs <= cut) out += point else break
        }
        if (cut > out.last().timeMs) out += interpolate(polyline, cut)
        out += EnvelopePoint(max.coerceAtLeast(out.last().timeMs + 1), 0, out.last().sharpness)
        return out
    }

    /**
     * Fits [polyline] (which must end at zero) to [limits]: at most `maxDurationMs` long, at most `maxControlPoints`
     * control points, no segment shorter than `minSegmentMs` or longer than `maxSegmentMs`. Shape is kept as well as
     * the point budget allows ([simplify]); long segments are split with interpolated points. Deviations up to
     * [tolerance] thousandths are treated as noise and spend no points.
     */
    fun fit(
        polyline: List<EnvelopePoint>,
        limits: EnvelopeLimits,
        tolerance: Double = 0.0,
    ): List<EnvelopePoint> {
        val ramp = limits.minSegmentMs.coerceAtMost(limits.maxSegmentMs)
        // Segments too short for the device are stretched (not dropped), so short peaks stay perceptible.
        val widened = widenShortSegments(endAtZero(polyline, ramp), limits.minSegmentMs)
        val bounded = truncate(widened, limits.maxDurationMs, ramp)
        val maxPoints = limits.maxControlPoints + 1
        for (budget in maxPoints downTo 2) {
            val simplified = simplify(bounded, budget, limits.minSegmentMs, tolerance)
            val split = splitLongSegments(simplified, limits.maxSegmentMs)
            if (split.size <= maxPoints) return split
        }
        // Not even start and end fit (an absurdly short maximum segment): keep the ends, the framework adapts it.
        return simplify(bounded, 2, limits.minSegmentMs)
    }

    /**
     * Ramer–Douglas–Peucker driven by a point budget: keeps the first and last point, then repeatedly adds the point
     * that deviates most from the current simplification (vertical distance in intensity, plus half the sharpness
     * distance) until [maxPoints] are kept or no point deviates more than [tolerance]. A point is only added when it
     * is at least [minGapMs] away from both of its kept neighbours.
     */
    fun simplify(
        points: List<EnvelopePoint>,
        maxPoints: Int,
        minGapMs: Int,
        tolerance: Double = 0.0,
    ): List<EnvelopePoint> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        var kept = 2
        val queue =
            PriorityQueue<Candidate>(compareByDescending<Candidate> { it.distance }.thenBy { it.index })

        fun consider(
            from: Int,
            to: Int,
        ) {
            candidate(points, from, to, minGapMs)?.takeIf { it.distance > tolerance }?.let(queue::add)
        }
        consider(0, points.lastIndex)
        while (kept < maxPoints) {
            val next = queue.poll() ?: break
            keep[next.index] = true
            kept++
            consider(next.from, next.index)
            consider(next.index, next.to)
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private class Candidate(
        val from: Int,
        val to: Int,
        val index: Int,
        val distance: Double,
    )

    private fun candidate(
        points: List<EnvelopePoint>,
        from: Int,
        to: Int,
        minGapMs: Int,
    ): Candidate? {
        val a = points[from]
        val b = points[to]
        var best = -1
        var bestDistance = 0.0
        for (i in from + 1 until to) {
            val p = points[i]
            if (p.timeMs - a.timeMs < minGapMs || b.timeMs - p.timeMs < minGapMs) continue
            val f = if (b.timeMs == a.timeMs) 0.0 else (p.timeMs - a.timeMs).toDouble() / (b.timeMs - a.timeMs)
            val intensity = abs(p.intensity - (a.intensity + f * (b.intensity - a.intensity)))
            val sharpness = abs(p.sharpness - (a.sharpness + f * (b.sharpness - a.sharpness)))
            val distance = intensity + SHARPNESS_WEIGHT * sharpness
            if (distance > bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return if (best < 0) null else Candidate(from, to, best, bestDistance)
    }

    /** Stretches segments shorter than [minMs], shifting everything after them. */
    private fun widenShortSegments(
        points: List<EnvelopePoint>,
        minMs: Int,
    ): List<EnvelopePoint> {
        var shift = 0
        val out = ArrayList<EnvelopePoint>(points.size)
        out += points[0]
        for (i in 1 until points.size) {
            val gap = points[i].timeMs - points[i - 1].timeMs
            if (gap < minMs) shift += minMs - gap
            out += points[i].copy(timeMs = points[i].timeMs + shift)
        }
        return out
    }

    /** Splits segments longer than [maxMs] into equal parts with interpolated points. */
    private fun splitLongSegments(
        points: List<EnvelopePoint>,
        maxMs: Int,
    ): List<EnvelopePoint> {
        val out = ArrayList<EnvelopePoint>(points.size)
        out += points[0]
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val parts = (b.timeMs - a.timeMs + maxMs - 1) / maxMs
            for (k in 1 until parts) {
                val t = a.timeMs + (b.timeMs - a.timeMs) * k / parts
                out += interpolate(listOf(a, b), t)
            }
            out += b
        }
        return out
    }

    /** The polyline's value at [timeMs] (clamped to its ends). */
    fun interpolate(
        polyline: List<EnvelopePoint>,
        timeMs: Int,
    ): EnvelopePoint {
        if (timeMs <= polyline.first().timeMs) return polyline.first().copy(timeMs = timeMs)
        for (i in 1 until polyline.size) {
            val a = polyline[i - 1]
            val b = polyline[i]
            if (timeMs <= b.timeMs) {
                val f = if (b.timeMs == a.timeMs) 1.0 else (timeMs - a.timeMs).toDouble() / (b.timeMs - a.timeMs)
                return EnvelopePoint(
                    timeMs,
                    (a.intensity + f * (b.intensity - a.intensity)).roundToInt(),
                    (a.sharpness + f * (b.sharpness - a.sharpness)).roundToInt(),
                )
            }
        }
        return polyline.last().copy(timeMs = timeMs)
    }

    private fun milli(value: Int): Int = value.coerceIn(0, 1000)
}
