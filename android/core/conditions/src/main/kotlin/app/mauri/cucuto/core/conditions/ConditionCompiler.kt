package app.mauri.cucuto.core.conditions

import app.mauri.cucuto.core.routing.TimeCondition
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Where day and night are computed for: coordinates the user typed or picked from the city list, or one coarse
 * location fix they asked for. Never tracked.
 *
 * @property label What the user picked ("Barcelona"), or null for typed coordinates and location fixes.
 */
public data class Place(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null,
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude $latitude out of range" }
        require(longitude in -180.0..180.0) { "Longitude $longitude out of range" }
    }

    /** Seasons are mirrored south of the equator. */
    val isSouthern: Boolean get() = latitude < 0
}

/**
 * Turns `when` conditions into [TimeCondition]s for the routing table: the instants at which each condition starts
 * and stops applying, from the start of yesterday to [horizonDays] days after today in [zone]. The module then only
 * compares the clock against them (ADR 0037).
 *
 * - Day is from sunrise to sunset at [place] ([SolarCalculator]); with no place, from [fixedDayStart] to
 *   [fixedDayEnd] local time (07:00 to 19:00). Polar day counts as day and polar night as night, as the sky says.
 * - Night is the rest. An hour range is local time and wraps midnight when it ends earlier than it starts.
 * - Seasons and weekdays go by the local date of the instant, so a 22:00 to 06:00 range limited to Saturday stops
 *   at Sunday's midnight. Seasons are mirrored when [place] is south of the equator.
 *
 * Instants are whole seconds (the routing table's resolution). The default horizon covers more than a year, so the
 * module keeps switching correctly even if the app is not opened for months; the app recompiles on every
 * publication, which happens at least once on each day the app runs.
 */
public class ConditionCompiler(
    private val place: Place?,
    private val zone: ZoneId,
    private val today: LocalDate,
    private val horizonDays: Int = DEFAULT_HORIZON_DAYS,
    private val fixedDayStart: LocalTime = DEFAULT_DAY_START,
    private val fixedDayEnd: LocalTime = DEFAULT_DAY_END,
) {
    init {
        require(horizonDays in 1..MAX_HORIZON_DAYS) { "Horizon $horizonDays days out of range" }
    }

    private val firstDate: LocalDate = today.minusDays(1)
    private val endDate: LocalDate = today.plusDays(horizonDays.toLong())

    /** Start of the published span: midnight at the start of yesterday. */
    public val fromMs: Long = startOfDay(firstDate)

    /** End of the published span (exclusive). */
    public val untilMs: Long = startOfDay(endDate)

    /** Day intervals over the span plus one day either side, computed once and shared by every condition. */
    private val dayIntervals: Intervals by lazy {
        val list = Intervals()
        var date = firstDate.minusDays(1)
        while (!date.isAfter(endDate)) {
            when (val sun = sunTimes(date)) {
                is SunTimes.Regular -> list.add(seconds(sun.sunrise.toEpochMilli()), seconds(sun.sunset.toEpochMilli()))
                SunTimes.PolarDay -> list.add(startOfDay(date), startOfDay(date.plusDays(1)))
                SunTimes.PolarNight -> Unit
            }
            date = date.plusDays(1)
        }
        list.normalized()
    }

    /** Sunrise and sunset on [date]: from [place], or the fixed hours when there is none. */
    public fun sunTimes(date: LocalDate): SunTimes {
        val p = place ?: return SunTimes.Regular(at(date, fixedDayStart).toInstant(), at(date, fixedDayEnd).toInstant())
        return SolarCalculator.sunTimes(date, p.latitude, p.longitude, zone)
    }

    /** Whether it is day at [nowMs] (for the settings screen). */
    public fun isDay(nowMs: Long): Boolean = dayIntervals.contains(nowMs)

    /** The condition as instants over the span. */
    public fun compile(condition: ClipWhen): TimeCondition {
        var set = Intervals().apply { add(fromMs, untilMs) }
        condition.time?.let { set = set.intersect(timeIntervals(it)) }
        if (condition.seasons.isNotEmpty() || condition.weekdays.isNotEmpty()) {
            set = set.intersect(dateIntervals(condition))
        }
        return set.clip(fromMs, untilMs).toCondition(fromMs, untilMs)
    }

    private fun timeIntervals(time: TimeOfDay): Intervals =
        when (time) {
            TimeOfDay.Day -> {
                dayIntervals
            }

            TimeOfDay.Night -> {
                dayIntervals.complement(fromMs, untilMs)
            }

            is TimeOfDay.Range -> {
                val list = Intervals()
                var date = firstDate.minusDays(1)
                while (!date.isAfter(endDate)) {
                    val start = at(date, time.from).toInstant().toEpochMilli()
                    val end =
                        if (time.to.isAfter(time.from)) {
                            at(date, time.to).toInstant().toEpochMilli()
                        } else {
                            at(date.plusDays(1), time.to).toInstant().toEpochMilli()
                        }
                    list.add(start, end)
                    date = date.plusDays(1)
                }
                list.normalized()
            }
        }

    private fun dateIntervals(condition: ClipWhen): Intervals {
        val southern = place?.isSouthern == true
        val list = Intervals()
        var date = firstDate
        while (date.isBefore(endDate)) {
            val seasonOk = condition.seasons.isEmpty() || Season.of(date.monthValue, southern) in condition.seasons
            val weekdayOk = condition.weekdays.isEmpty() || date.dayOfWeek in condition.weekdays
            if (seasonOk && weekdayOk) list.add(startOfDay(date), startOfDay(date.plusDays(1)))
            date = date.plusDays(1)
        }
        return list.normalized()
    }

    private fun at(
        date: LocalDate,
        time: LocalTime,
    ): ZonedDateTime = ZonedDateTime.of(date, time, zone)

    private fun startOfDay(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    public companion object {
        /** A little over a year: the routing table stays right for months without the app (about 5 KB per condition). */
        public const val DEFAULT_HORIZON_DAYS: Int = 400

        /** Bounded by what [TimeCondition] accepts. */
        public const val MAX_HORIZON_DAYS: Int = 790

        /** Fixed day hours when no place is set (the pack format's original default, #75). */
        public val DEFAULT_DAY_START: LocalTime = LocalTime.of(7, 0)
        public val DEFAULT_DAY_END: LocalTime = LocalTime.of(19, 0)

        private fun seconds(ms: Long): Long = Math.floorDiv(ms, 1000L) * 1000L
    }
}

/** Half-open `[start, end)` millisecond intervals, as a flat list of pairs. */
internal class Intervals {
    private val values = ArrayList<Long>()

    val size: Int get() = values.size / 2

    fun start(i: Int): Long = values[2 * i]

    fun end(i: Int): Long = values[2 * i + 1]

    fun add(
        start: Long,
        end: Long,
    ) {
        if (end > start) {
            values += start
            values += end
        }
    }

    /** Sorted, with overlapping and touching intervals merged. */
    fun normalized(): Intervals {
        val order = (0 until size).sortedBy { start(it) }
        val out = Intervals()
        var curStart = 0L
        var curEnd = 0L
        var open = false
        for (i in order) {
            val s = start(i)
            val e = end(i)
            if (open && s <= curEnd) {
                if (e > curEnd) curEnd = e
            } else {
                if (open) out.add(curStart, curEnd)
                curStart = s
                curEnd = e
                open = true
            }
        }
        if (open) out.add(curStart, curEnd)
        return out
    }

    /** What [from] to [until] is not covered by these (normalized) intervals. */
    fun complement(
        from: Long,
        until: Long,
    ): Intervals {
        val out = Intervals()
        var cursor = from
        for (i in 0 until size) {
            if (end(i) <= cursor) continue
            if (start(i) >= until) break
            out.add(cursor, minOf(start(i), until))
            cursor = maxOf(cursor, end(i))
        }
        out.add(cursor, until)
        return out
    }

    /** Intersection of two normalized lists. */
    fun intersect(other: Intervals): Intervals {
        val out = Intervals()
        var i = 0
        var j = 0
        while (i < size && j < other.size) {
            val s = maxOf(start(i), other.start(j))
            val e = minOf(end(i), other.end(j))
            out.add(s, e)
            if (end(i) < other.end(j)) i++ else j++
        }
        return out
    }

    fun clip(
        from: Long,
        until: Long,
    ): Intervals = intersect(Intervals().apply { add(from, until) })

    fun contains(t: Long): Boolean {
        for (i in 0 until size) if (t >= start(i) && t < end(i)) return true
        return false
    }

    /** A normalized list inside `[from, until)` as a [TimeCondition]. */
    fun toCondition(
        from: Long,
        until: Long,
    ): TimeCondition {
        val toggles = ArrayList<Long>(values.size)
        for (v in values) if (v != from && v != until) toggles += v
        val activeAtFrom = size > 0 && start(0) == from
        return TimeCondition(from, until, activeAtFrom, toggles.toLongArray())
    }
}
