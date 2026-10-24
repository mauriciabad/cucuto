package app.mauri.cucuto.ui.ringtone.rules

import androidx.compose.runtime.Immutable
import app.mauri.cucuto.core.data.db.RingtoneRuleEntry
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.core.ringtone.variants.RuleContext
import app.mauri.cucuto.packspec.SpecJson
import app.mauri.cucuto.packspec.When
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * One line of a character's rules as the rules screen shows them (ADR 0074): a ringtone entry, its condition, and the
 * earlier entry that always wins over it, if any (the same condition comes first, so this one never rings).
 */
@Immutable
data class RuleLine(
    val variant: RingtoneVariant,
    val shadowedBy: RingtoneVariant? = null,
) {
    val condition: When? get() = variant.condition
}

/**
 * A list of rules read for people: [lines] in order and the [fallback], the version that rings when no condition holds
 * (the first entry, the pack format's "Variant resolution"), or null when some line always holds anyway.
 */
@Immutable
data class RulesView(
    val lines: List<RuleLine>,
    val fallback: RingtoneVariant?,
) {
    /** No line has a condition: the first one always rings. */
    val unconditional: Boolean get() = lines.none { it.condition != null }

    companion object {
        /** [entries] (the pack's, or a contact's own as variants) read in resolution order. */
        fun of(entries: List<RingtoneVariant>): RulesView {
            val lines =
                entries.mapIndexed { index, variant ->
                    val earlier = entries.take(index).firstOrNull { same(it.condition, variant.condition) }
                    RuleLine(variant, shadowedBy = earlier)
                }
            val covered = covers(entries.map { it.condition })
            return RulesView(lines, fallback = if (covered || entries.isEmpty()) null else entries.first())
        }

        private fun same(
            a: When?,
            b: When?,
        ): Boolean = a == b || (a != null && b != null && normalized(a) == normalized(b))

        private fun normalized(w: When): Triple<String?, Set<String>, Set<String>> =
            Triple(w.time?.toString(), w.seasons.toSet(), w.weekdays.toSet())

        /**
         * Whether some entry holds at every moment: one without a condition, day and night together, or the four
         * seasons. Only single-key conditions count, which is enough to leave out the "otherwise" line in the
         * usual packs; a line that is not needed only says which version is the fallback.
         */
        private fun covers(conditions: List<When?>): Boolean {
            if (conditions.any { it == null }) return true
            val single = conditions.filterNotNull().filter { keys(it) == 1 }
            val times = single.mapNotNull { (it.time as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            if ("day" in times && "night" in times) return true
            val seasons = single.filter { it.season != null }.flatMap { it.seasons }.toSet()
            return seasons.containsAll(SEASONS)
        }

        private fun keys(w: When): Int = listOfNotNull(w.time, w.season, w.weekday).size
    }
}

/** What a version's "When" menu offers in "Your own rules" (ADR 0074). */
enum class WhenChoice {
    ANY,
    DAY,
    NIGHT,
    SPRING,
    SUMMER,
    AUTUMN,
    WINTER,

    /** The pack's own condition, when it is none of the above (an hour range, weekdays, several seasons). */
    PACK,
    ;

    /** The condition this choice stands for; [PACK] needs the pack's. */
    fun condition(pack: When?): When? =
        when (this) {
            ANY -> null
            DAY -> When(time = JsonPrimitive(DAY_KEYWORD))
            NIGHT -> When(time = JsonPrimitive(NIGHT_KEYWORD))
            SPRING -> When(season = JsonPrimitive(RuleContext.SPRING))
            SUMMER -> When(season = JsonPrimitive(RuleContext.SUMMER))
            AUTUMN -> When(season = JsonPrimitive(RuleContext.AUTUMN))
            WINTER -> When(season = JsonPrimitive(RuleContext.WINTER))
            PACK -> pack
        }

    companion object {
        /** The simple choice [condition] is, or [PACK] when it is something else. */
        fun of(condition: When?): WhenChoice {
            if (condition == null) return ANY
            val time = (condition.time as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val seasons = condition.seasons
            return when {
                condition.weekday != null -> PACK
                condition.season == null && time == DAY_KEYWORD -> DAY
                condition.season == null && time == NIGHT_KEYWORD -> NIGHT
                condition.time == null && seasons.size == 1 -> {
                    when (seasons.single()) {
                        RuleContext.SPRING -> SPRING
                        RuleContext.SUMMER -> SUMMER
                        RuleContext.AUTUMN -> AUTUMN
                        RuleContext.WINTER -> WINTER
                        else -> PACK
                    }
                }

                else -> PACK
            }
        }
    }
}

/**
 * One version in "Your own rules": its entry in the pack (file and the pack's condition), whether it is [on], and the
 * [choice] of its "When" menu.
 */
@Immutable
data class RuleRow(
    val variant: RingtoneVariant,
    val packCondition: When?,
    val on: Boolean = true,
    val choice: WhenChoice = WhenChoice.of(packCondition),
) {
    val file: String get() = variant.file

    /** The condition stored for this row: the pack's own when the choice is the pack's, so it compares equal. */
    val condition: When?
        get() = if (choice == WhenChoice.of(packCondition)) packCondition else choice.condition(packCondition)

    /** The choices its menu offers: the simple ones, and the pack's own when that is something else. */
    val choices: List<WhenChoice>
        get() = WhenChoice.entries.filter { it != WhenChoice.PACK || WhenChoice.of(packCondition) == WhenChoice.PACK }
}

/**
 * "Your own rules" being edited (ADR 0074): one row per ringtone entry and the row that rings when no condition holds
 * ([fallback], an index into [rows]). Saved as [rules]: the rows that are on, the fallback first.
 */
@Immutable
data class RulesDraft(
    val rows: List<RuleRow>,
    val fallback: Int = 0,
) {
    val onCount: Int get() = rows.count { it.on }

    /** The fallback row, or the first that is on when the fallback was turned off. */
    val fallbackRow: RuleRow? get() = rows.getOrNull(fallback)?.takeIf { it.on } ?: rows.firstOrNull { it.on }

    /** What is stored: the rows that are on, the fallback first, each with its condition. */
    val rules: List<RingtoneRuleEntry>
        get() {
            val first = fallbackRow ?: return emptyList()
            val ordered = listOf(first) + rows.filter { it.on && it !== first }
            return ordered.map { RingtoneRuleEntry(it.file, it.condition?.let(::json)) }
        }

    /** The rules as variants, for [RulesView] and for the version that rings now. */
    val variants: List<RingtoneVariant>
        get() {
            val first = fallbackRow ?: return emptyList()
            return (listOf(first) + rows.filter { it.on && it !== first }).map { it.variant.copy(condition = it.condition) }
        }

    fun toggle(
        index: Int,
        on: Boolean,
    ): RulesDraft {
        if (!on && onCount <= 1 && rows.getOrNull(index)?.on == true) return this
        val updated = rows.mapIndexed { i, row -> if (i == index) row.copy(on = on) else row }
        val fallbackOn = updated.getOrNull(fallback)?.on == true
        return copy(rows = updated, fallback = if (fallbackOn) fallback else updated.indexOfFirst { it.on }.coerceAtLeast(0))
    }

    fun choose(
        index: Int,
        choice: WhenChoice,
    ): RulesDraft = copy(rows = rows.mapIndexed { i, row -> if (i == index) row.copy(choice = choice) else row })

    fun fallbackTo(index: Int): RulesDraft = if (rows.getOrNull(index)?.on == true) copy(fallback = index) else this

    companion object {
        /** A copy of the pack's rules: every entry on, with its own condition, the first as fallback. */
        fun fromPack(entries: List<RingtoneVariant>): RulesDraft = RulesDraft(entries.map { RuleRow(it, it.condition) })

        /**
         * The contact's saved [rules] over the pack's [entries]: the saved ones first, in their order (the first is the
         * fallback), then the pack's other versions, off. Saved entries the pack no longer has are left out; with none
         * left, a copy of the pack's rules.
         */
        fun fromSaved(
            entries: List<RingtoneVariant>,
            rules: List<RingtoneRuleEntry>,
        ): RulesDraft {
            val saved =
                rules.mapNotNull { rule ->
                    val entry = entries.firstOrNull { it.file == rule.variant } ?: return@mapNotNull null
                    val condition =
                        rule.condition?.let { c ->
                            runCatching { SpecJson.decodeFromJsonElement(When.serializer(), c) }.getOrNull()
                                ?: return@mapNotNull null
                        }
                    RuleRow(entry, entry.condition, on = true, choice = choiceFor(condition, entry.condition))
                }
            if (saved.isEmpty()) return fromPack(entries)
            val files = saved.map { it.file }.toSet()
            val rest = entries.filter { it.file !in files }.distinctBy { it.file }.map { RuleRow(it, it.condition, on = false) }
            return RulesDraft(saved + rest)
        }

        /** Whether [rules] say exactly what the pack's [entries] say (then they are saved as the pack's rules). */
        fun isPack(
            entries: List<RingtoneVariant>,
            rules: List<RingtoneRuleEntry>,
        ): Boolean =
            rules.size == entries.size &&
                rules.zip(entries).all { (rule, entry) ->
                    rule.variant == entry.file && rule.condition == entry.condition?.let(::json)
                }

        /**
         * The menu choice for a saved [condition]. A condition that is none of the simple ones is the pack's own
         * condition (the only other one the menu offers); one that differs from it too reads as "any time".
         */
        private fun choiceFor(
            condition: When?,
            pack: When?,
        ): WhenChoice {
            val choice = WhenChoice.of(condition)
            return if (choice == WhenChoice.PACK && condition != pack) WhenChoice.ANY else choice
        }

        private fun json(w: When): JsonObject =
            SpecJson.encodeToJsonElement(When.serializer(), w) as? JsonObject ?: buildJsonObject { }
    }
}

private val SEASONS = setOf(RuleContext.SPRING, RuleContext.SUMMER, RuleContext.AUTUMN, RuleContext.WINTER)
private const val DAY_KEYWORD = "day"
private const val NIGHT_KEYWORD = "night"
