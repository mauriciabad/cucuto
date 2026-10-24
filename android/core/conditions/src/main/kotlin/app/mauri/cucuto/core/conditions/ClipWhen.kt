package app.mauri.cucuto.core.conditions

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A pack's `when` condition (docs/pack-format.md): every present key must match (AND), a list matches any of its
 * values (OR). Empty sets mean the key is absent.
 *
 * @property time Day, night or an hour range; null when absent.
 * @property seasons Meteorological seasons, mirrored for the southern hemisphere.
 * @property weekdays Local weekdays.
 */
public data class ClipWhen(
    val time: TimeOfDay? = null,
    val seasons: Set<Season> = emptySet(),
    val weekdays: Set<DayOfWeek> = emptySet(),
) {
    /** True when no key is present (such a `when` is a schema violation, and would always match). */
    val isEmpty: Boolean get() = time == null && seasons.isEmpty() && weekdays.isEmpty()

    public companion object {
        /**
         * Reads the JSON values of a `when` object as plain values: [time] is `"day"`, `"night"` or a `from`/`to`
         * pair of `HH:MM` strings; [seasons] and [weekdays] are the names of the pack format. Returns null when a
         * value is not understood, so callers treat the clip as unconditional (a validated pack never does this).
         */
        public fun of(
            time: String? = null,
            from: String? = null,
            to: String? = null,
            seasons: List<String> = emptyList(),
            weekdays: List<String> = emptyList(),
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
            return ClipWhen(timeOfDay, seasonSet, weekdaySet).takeUnless { it.isEmpty }
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

        private fun parseTime(text: String): LocalTime? {
            if (text.length != 5 || text[2] != ':') return null
            val hour = text.substring(0, 2).toIntOrNull() ?: return null
            val minute = text.substring(3, 5).toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return LocalTime.of(hour, minute)
        }
    }
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
