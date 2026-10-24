package app.mauri.cucuto.core.conditions

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.MonthDay

/**
 * A pack's `when` condition (docs/pack-format.md): every present key must match (AND), a list matches any of its
 * values (OR). Empty sets mean the key is absent.
 *
 * @property time Day, night or an hour range; null when absent.
 * @property seasons Meteorological seasons, mirrored for the southern hemisphere.
 * @property weekdays Local weekdays.
 * @property dates Calendar days every year (holidays the pack defines, ADR 0059); any of them matches.
 */
public data class ClipWhen(
    val time: TimeOfDay? = null,
    val seasons: Set<Season> = emptySet(),
    val weekdays: Set<DayOfWeek> = emptySet(),
    val dates: List<DateSpan> = emptyList(),
) {
    /**
     * True when no time key is present. Such a `when` may still hold conversation moments (`firstOfDay`, `burst`),
     * which are not time conditions and never reach [ConditionCompiler].
     */
    val isEmpty: Boolean get() = time == null && seasons.isEmpty() && weekdays.isEmpty() && dates.isEmpty()

    public companion object {
        /**
         * Reads the JSON values of a `when` object as plain values: [time] is `"day"`, `"night"` or a `from`/`to`
         * pair of `HH:MM` strings; [seasons] and [weekdays] are the names of the pack format; [dates] are `MM-DD`
         * spans (one day is a span from and to the same day). Returns null when a value is not understood, so callers
         * treat the clip as unconditional (a validated pack never does this), and when no time key is present.
         */
        public fun of(
            time: String? = null,
            from: String? = null,
            to: String? = null,
            seasons: List<String> = emptyList(),
            weekdays: List<String> = emptyList(),
            dates: List<Pair<String, String>> = emptyList(),
        ): ClipWhen? {
            val timeOfDay =
                when {
                    time == "day" -> {
                        TimeOfDay.Day
                    }

                    time == "night" -> {
                        TimeOfDay.Night
                    }

                    time != null -> {
                        return null
                    }

                    from != null && to != null -> {
                        TimeOfDay.Range(
                            parseTime(from) ?: return null,
                            parseTime(to) ?: return null,
                        )
                    }

                    from != null || to != null -> {
                        return null
                    }

                    else -> {
                        null
                    }
                }
            val seasonSet = seasons.map { name -> Season.entries.firstOrNull { it.key == name } ?: return null }.toSet()
            val weekdaySet = weekdays.map { name -> WEEKDAYS[name] ?: return null }.toSet()
            val spans =
                dates.map { (from, to) ->
                    DateSpan(parseMonthDay(from) ?: return null, parseMonthDay(to) ?: return null)
                }
            return ClipWhen(timeOfDay, seasonSet, weekdaySet, spans).takeUnless { it.isEmpty }
        }

        private val WEEKDAYS: Map<String, DayOfWeek> =
            mapOf(
                "mon" to DayOfWeek.MONDAY,
                "tue" to DayOfWeek.TUESDAY,
                "wed" to DayOfWeek.WEDNESDAY,
                "thu" to DayOfWeek.THURSDAY,
                "fri" to DayOfWeek.FRIDAY,
                "sat" to DayOfWeek.SATURDAY,
                "sun" to DayOfWeek.SUNDAY,
            )

        /** `MM-DD`, or null when it is not a calendar day (`02-29` is one: it matches in leap years). */
        internal fun parseMonthDay(text: String): MonthDay? {
            if (text.length != 5 || text[2] != '-') return null
            val month = text.substring(0, 2).toIntOrNull() ?: return null
            val day = text.substring(3, 5).toIntOrNull() ?: return null
            return runCatching { MonthDay.of(month, day) }.getOrNull()
        }

        private fun parseTime(text: String): LocalTime? {
            if (text.length != 5 || text[2] != ':') return null
            val hour = text.substring(0, 2).toIntOrNull() ?: return null
            val minute = text.substring(3, 5).toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return LocalTime.of(hour, minute)
        }
    }
}

/**
 * One span of a `when.date`: from [from] to [to], both inclusive, every year. A span that ends before it starts wraps
 * the new year (24 December to 6 January).
 */
public data class DateSpan(
    val from: MonthDay,
    val to: MonthDay,
) {
    /** Whether [day] is inside the span. */
    public fun contains(day: MonthDay): Boolean = if (from <= to) day in from..to else day >= from || day <= to
}

/** The `time` key of a `when` condition. */
public sealed interface TimeOfDay {
    /** Between local sunrise and sunset (fixed hours when no place is set). */
    public data object Day : TimeOfDay

    /** Between sunset and the next sunrise. */
    public data object Night : TimeOfDay

    /** From [from] (inclusive) to [to] (exclusive) local time, wrapping midnight when [to] is earlier. */
    public data class Range(
        val from: LocalTime,
        val to: LocalTime,
    ) : TimeOfDay
}

/** Meteorological seasons, by month in the northern hemisphere. */
public enum class Season(
    public val key: String,
) {
    SPRING("spring"),
    SUMMER("summer"),
    AUTUMN("autumn"),
    WINTER("winter"),
    ;

    /** The opposite season, for the southern hemisphere. */
    public val opposite: Season
        get() =
            when (this) {
                SPRING -> AUTUMN
                SUMMER -> WINTER
                AUTUMN -> SPRING
                WINTER -> SUMMER
            }

    public companion object {
        /** The season of [month] (1 to 12) in the northern hemisphere, or the southern one when [southern]. */
        public fun of(
            month: Int,
            southern: Boolean,
        ): Season {
            val north =
                when (month) {
                    3, 4, 5 -> SPRING
                    6, 7, 8 -> SUMMER
                    9, 10, 11 -> AUTUMN
                    else -> WINTER
                }
            return if (southern) north.opposite else north
        }
    }
}
