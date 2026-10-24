package app.mauri.cucuto.ui.ringtone.rules

import android.content.res.Resources
import android.icu.text.ListFormatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.mauri.cucuto.R
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.core.ringtone.variants.RuleContext
import app.mauri.cucuto.packspec.DateSpan
import app.mauri.cucuto.packspec.When
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Ringtone rules in plain words (ADR 0074): "Rings by day", "Rings in summer and autumn", "Live by day and Radio at
 * night", "on 25 December". English and Spanish word order is the same for every pattern used here, so the sentences are built from
 * small string resources and the locale's list format ("a, b and c" / "a, b y c").
 */
class RuleText(
    private val resources: Resources,
    private val locale: Locale,
) {
    private val lists: ListFormatter = ListFormatter.getInstance(locale)

    /** The version's name: its label, else its file name. */
    fun name(variant: RingtoneVariant): String =
        variant.label ?: variant.file.substringAfterLast('/').substringBeforeLast('.')

    /** "by day, in summer, every Saturday and Sunday", or null for no condition. */
    fun condition(condition: When?): String? {
        condition ?: return null
        val parts =
            buildList {
                val time = condition.time
                val keyword = (time as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                when {
                    keyword == "day" -> {
                        add(resources.getString(R.string.ringtone_rules_by_day))
                    }

                    keyword == "night" -> {
                        add(resources.getString(R.string.ringtone_rules_at_night))
                    }

                    condition.timeRange != null -> {
                        val (from, to) = condition.timeRange!!
                        add(resources.getString(R.string.ringtone_rules_time_range, from, to))
                    }
                }
                val seasons = condition.seasons.mapNotNull(::season)
                if (seasons.isNotEmpty()) add(resources.getString(R.string.ringtone_rules_in, lists.format(seasons)))
                val days = condition.weekdays.mapNotNull(::weekday)
                if (days.isNotEmpty()) add(resources.getString(R.string.ringtone_rules_every, lists.format(days)))
                val dates = condition.dateSpans.filterNotNull().map(::dates)
                if (dates.isNotEmpty()) add(lists.format(dates))
            }
        return parts.joinToString(", ").ifEmpty { null }
    }

    /** When [line] rings, as a sentence ("Rings by day"), in the context of all [view]'s lines. */
    fun line(
        line: RuleLine,
        view: RulesView,
    ): String {
        line.shadowedBy?.let { return resources.getString(R.string.ringtone_rules_never, name(it)) }
        val condition = condition(line.condition)
        return when {
            condition != null -> resources.getString(R.string.ringtone_rules_rings, condition)
            view.unconditional -> resources.getString(R.string.ringtone_rules_always_rings)
            else -> resources.getString(R.string.ringtone_rules_rest)
        }
    }

    /** The whole of [view] in one sentence: "Live by day and Radio at night", "Always Live". */
    fun summary(view: RulesView): String {
        if (view.lines.isEmpty()) return ""
        if (view.unconditional) {
            return resources.getString(
                R.string.ringtone_rules_always,
                name(view.lines.first().variant),
            )
        }
        val items =
            view.lines
                .filter { it.shadowedBy == null }
                .map { line ->
                    val words = condition(line.condition) ?: resources.getString(R.string.ringtone_rules_rest_short)
                    resources.getString(R.string.ringtone_rules_item, name(line.variant), words)
                }
        return lists.format(items)
    }

    /** The "otherwise" line, when no condition may hold at some moment. */
    fun fallback(view: RulesView): String? =
        view.fallback?.let { resources.getString(R.string.ringtone_rules_fallback, name(it)) }

    /** The label of a "When" menu choice; [pack] is the pack's own condition for [WhenChoice.PACK]. */
    fun choice(
        choice: WhenChoice,
        pack: When?,
    ): String =
        when (choice) {
            WhenChoice.ANY -> resources.getString(R.string.ringtone_rules_choice_any)
            WhenChoice.DAY -> resources.getString(R.string.ringtone_rules_choice_day)
            WhenChoice.NIGHT -> resources.getString(R.string.ringtone_rules_choice_night)
            WhenChoice.SPRING -> resources.getString(R.string.ringtone_rules_choice_spring)
            WhenChoice.SUMMER -> resources.getString(R.string.ringtone_rules_choice_summer)
            WhenChoice.AUTUMN -> resources.getString(R.string.ringtone_rules_choice_autumn)
            WhenChoice.WINTER -> resources.getString(R.string.ringtone_rules_choice_winter)
            WhenChoice.PACK -> resources.getString(R.string.ringtone_rules_choice_pack, condition(pack).orEmpty())
        }

    private fun season(name: String): String? =
        when (name) {
            RuleContext.SPRING -> resources.getString(R.string.ringtone_rules_spring)
            RuleContext.SUMMER -> resources.getString(R.string.ringtone_rules_summer)
            RuleContext.AUTUMN -> resources.getString(R.string.ringtone_rules_autumn)
            RuleContext.WINTER -> resources.getString(R.string.ringtone_rules_winter)
            else -> null
        }

    private fun weekday(name: String): String? = WEEKDAYS[name]?.getDisplayName(TextStyle.FULL, locale)

    /** "on 25 December", "from 24 December to 6 January" (`when.date`, ADR 0059). */
    private fun dates(span: DateSpan): String {
        val format = DateTimeFormatter.ofPattern(resources.getString(R.string.ringtone_rules_day_pattern), locale)

        fun day(monthDay: Int) = format.format(LocalDate.of(LEAP_YEAR, monthDay / 100, monthDay % 100))
        return if (span.from == span.to) {
            resources.getString(R.string.ringtone_rules_on_day, day(span.from))
        } else {
            resources.getString(R.string.ringtone_rules_day_range, day(span.from), day(span.to))
        }
    }

    private companion object {
        /** Formats 29 February too. */
        const val LEAP_YEAR = 2024
        val WEEKDAYS =
            mapOf(
                "mon" to DayOfWeek.MONDAY,
                "tue" to DayOfWeek.TUESDAY,
                "wed" to DayOfWeek.WEDNESDAY,
                "thu" to DayOfWeek.THURSDAY,
                "fri" to DayOfWeek.FRIDAY,
                "sat" to DayOfWeek.SATURDAY,
                "sun" to DayOfWeek.SUNDAY,
            )
    }
}

/** [RuleText] for the current configuration (language changes recompose it). */
@Composable
fun rememberRuleText(): RuleText {
    val configuration = LocalConfiguration.current
    val resources = LocalContext.current.resources
    val locale = configuration.locales[0] ?: Locale.ROOT
    return remember(resources, locale) { RuleText(resources, locale) }
}
