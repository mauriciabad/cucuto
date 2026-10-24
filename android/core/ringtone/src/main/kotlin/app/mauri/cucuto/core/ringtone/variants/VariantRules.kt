package app.mauri.cucuto.core.ringtone.variants

import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.packspec.LoopRange
import app.mauri.cucuto.packspec.When
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import kotlin.random.Random

/**
 * One ringtone entry of a character (docs/pack-format.md, "Ringtones and variants").
 *
 * @property file Path from `pack.json`; it identifies the variant.
 * @property condition The `when` condition, or null (always matches).
 * @property loop The loop range, null for `loop: false`.
 */
data class RingtoneVariant(
    val file: String,
    val label: String? = null,
    val tags: List<String> = emptyList(),
    val condition: When? = null,
    val weight: Double = 1.0,
    val loop: LoopRange? = LoopRange(null, null),
    val fadeInMs: Long = 0,
)

/**
 * What a rule looks at: the phone's local time, the hours that count as day, and the hemisphere for seasons.
 *
 * Day is 07:00 to 19:00 (pack format, `when.time`) until sunrise and sunset from the phone's location land (#118); the
 * southern hemisphere flips seasons (pack format, `when.season`) and has no setting yet, so it defaults to northern.
 */
data class RuleContext(
    val time: LocalDateTime,
    val dayStart: LocalTime = DEFAULT_DAY_START,
    val dayEnd: LocalTime = DEFAULT_DAY_END,
    val southernHemisphere: Boolean = false,
) {
    val isDay: Boolean get() = inRange(time.toLocalTime(), dayStart, dayEnd)

    /** Meteorological season of [time], flipped in the southern hemisphere. */
    val season: String
        get() {
            val north =
                when (time.month) {
                    Month.MARCH, Month.APRIL, Month.MAY -> SPRING
                    Month.JUNE, Month.JULY, Month.AUGUST -> SUMMER
                    Month.SEPTEMBER, Month.OCTOBER, Month.NOVEMBER -> AUTUMN
                    else -> WINTER
                }
            if (!southernHemisphere) return north
            return when (north) {
                SPRING -> AUTUMN
                SUMMER -> WINTER
                AUTUMN -> SPRING
                else -> SUMMER
            }
        }

    companion object {
        val DEFAULT_DAY_START: LocalTime = LocalTime.of(7, 0)
        val DEFAULT_DAY_END: LocalTime = LocalTime.of(19, 0)
        const val SPRING = "spring"
        const val SUMMER = "summer"
        const val AUTUMN = "autumn"
        const val WINTER = "winter"

        /** `[from, to)`, wrapping midnight when `to` is before `from`. */
        fun inRange(
            t: LocalTime,
            from: LocalTime,
            to: LocalTime,
        ): Boolean = if (from <= to) t >= from && t < to else t >= from || t < to
    }
}

/**
 * The per-contact variant rules (brief, Ringtones; docs/pack-format.md, "Variant resolution"). Pure: no Android, no
 * clock; the caller passes the time and the randomness.
 */
object VariantRules {
    private val weekdays =
        mapOf(
            "mon" to DayOfWeek.MONDAY,
            "tue" to DayOfWeek.TUESDAY,
            "wed" to DayOfWeek.WEDNESDAY,
            "thu" to DayOfWeek.THURSDAY,
            "fri" to DayOfWeek.FRIDAY,
            "sat" to DayOfWeek.SATURDAY,
            "sun" to DayOfWeek.SUNDAY,
        )

    /**
     * The variant [mode] picks now. [current] is the variant the contact rings with at the moment: the random rule
     * keeps it unless [reroll] is set, and a re-roll never picks it again when there is another one.
     */
    fun pick(
        variants: List<RingtoneVariant>,
        mode: RingtoneMode,
        fixed: String?,
        context: RuleContext,
        current: String?,
        reroll: Boolean,
        random: Random,
    ): RingtoneVariant? {
        if (variants.isEmpty()) return null
        return when (mode) {
            RingtoneMode.FIXED -> {
                variants.firstOrNull { it.file == fixed } ?: variants.first()
            }

            RingtoneMode.RANDOM -> {
                val kept = variants.firstOrNull { it.file == current }
                if (kept != null && !reroll) kept else weighted(variants, avoid = current, random = random)
            }

            RingtoneMode.TIME -> {
                byTag(variants, if (context.isDay) "day" else "night")
            }

            RingtoneMode.SEASON -> {
                byTag(variants, context.season)
            }

            RingtoneMode.PACK -> {
                followPack(variants, context)
            }
        }
    }

    /**
     * The pack's own rule: the entries whose `when` matches (no `when` always matches), the most specific of them (most
     * condition keys), the first of those in manifest order; the first entry when nothing matches.
     */
    fun followPack(
        variants: List<RingtoneVariant>,
        context: RuleContext,
    ): RingtoneVariant {
        val matching = variants.filter { matches(it.condition, context) }
        if (matching.isEmpty()) return variants.first()
        val most = matching.maxOf { keyCount(it.condition) }
        return matching.first { keyCount(it.condition) == most }
    }

    /** Whether [condition] holds at [context]: every present key matches (AND), a list matches any value (OR). */
    fun matches(
        condition: When?,
        context: RuleContext,
    ): Boolean {
        if (condition == null) return true
        val time = condition.time
        if (time != null) {
            val keyword = (time as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val ok =
                when {
                    keyword == "day" -> {
                        context.isDay
                    }

                    keyword == "night" -> {
                        !context.isDay
                    }

                    else -> {
                        range(condition)?.let { (from, to) ->
                            RuleContext.inRange(context.time.toLocalTime(), from, to)
                        }
                            ?: false
                    }
                }
            if (!ok) return false
        }
        if (condition.season != null && context.season !in condition.seasons) return false
        if (condition.weekday != null &&
            condition.weekdays.none { weekdays[it] == context.time.dayOfWeek }
        ) {
            return false
        }
        return true
    }

    /**
     * The next moment after `context.time` when [mode] may pick a different variant (a day/night edge, an hour range
     * edge, midnight for weekdays, the first day of a season), or null when the pick never depends on time.
     */
    fun nextBoundary(
        variants: List<RingtoneVariant>,
        mode: RingtoneMode,
        context: RuleContext,
    ): LocalDateTime? {
        val times = mutableSetOf<LocalTime>()
        var seasons = false
        when (mode) {
            RingtoneMode.FIXED, RingtoneMode.RANDOM -> {
                return null
            }

            RingtoneMode.TIME -> {
                times += context.dayStart
                times += context.dayEnd
            }

            RingtoneMode.SEASON -> {
                seasons = true
            }

            RingtoneMode.PACK -> {
                if (variants.size < 2) return null
                for (variant in variants) {
                    val condition = variant.condition ?: continue
                    val keyword = (condition.time as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    if (keyword == "day" || keyword == "night") {
                        times += context.dayStart
                        times += context.dayEnd
                    }
                    range(condition)?.let { (from, to) -> times += listOf(from, to) }
                    if (condition.weekday != null) times += LocalTime.MIDNIGHT
                    if (condition.season != null) seasons = true
                }
            }
        }
        val now = context.time
        val candidates = mutableListOf<LocalDateTime>()
        for (t in times) {
            val today = now.toLocalDate().atTime(t)
            candidates += if (today.isAfter(now)) today else today.plusDays(1)
        }
        if (seasons) candidates += nextSeasonStart(now.toLocalDate())
        return candidates.minOrNull()
    }

    /** The next 1 March, June, September or December at midnight, strictly after [date]. */
    private fun nextSeasonStart(date: LocalDate): LocalDateTime {
        var d = date.withDayOfMonth(1).plusMonths(1)
        while (d.monthValue % 3 != 0) d = d.plusMonths(1)
        return d.atStartOfDay()
    }

    private fun range(condition: When): Pair<LocalTime, LocalTime>? {
        val (from, to) = condition.timeRange ?: return null
        return runCatching { LocalTime.parse(from) to LocalTime.parse(to) }.getOrNull()
    }

    private fun keyCount(condition: When?): Int =
        if (condition == null) {
            0
        } else {
            listOfNotNull(condition.time, condition.season, condition.weekday).size
        }

    private fun byTag(
        variants: List<RingtoneVariant>,
        tag: String,
    ): RingtoneVariant =
        variants.firstOrNull { v -> v.tags.any { it.equals(tag, ignoreCase = true) } } ?: variants.first()

    /** A random variant by `weight`, never [avoid] when another one exists. */
    fun weighted(
        variants: List<RingtoneVariant>,
        avoid: String?,
        random: Random,
    ): RingtoneVariant {
        val pool = variants.filter { it.file != avoid }.ifEmpty { variants }
        val total = pool.sumOf { it.weight.coerceAtLeast(0.0) }
        if (total <= 0.0) return pool.first()
        var x = random.nextDouble() * total
        for (variant in pool) {
            x -= variant.weight.coerceAtLeast(0.0)
            if (x < 0) return variant
        }
        return pool.last()
    }
}
