package app.mauri.cucuto.packspec.authoring

import app.mauri.cucuto.packspec.DateSpan
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.Ringtone
import app.mauri.cucuto.packspec.When
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** The time-of-day part of a [ConditionForm]. */
@Serializable
enum class TimeOfDay { ANY, DAY, NIGHT, HOURS }

/** A clip's moment of the conversation (docs/pack-format.md#special-clips). */
@Serializable
enum class Moment { ANY, FIRST_OF_DAY, BURST }

/** One span of a `when.date` as typed: `MM-DD`, and [to] null (or equal) for a single day. */
@Serializable
data class DateForm(
    val from: String,
    val to: String? = null,
)

/** Something a [ConditionForm] or [RingtoneForm] still needs. [index] is the date concerned, for date problems. */
@Serializable
data class FormProblem(
    val kind: Kind,
    val index: Int? = null,
) {
    @Serializable
    enum class Kind {
        /** A time is not `HH:MM` (24-hour). */
        TIME_INVALID,

        /** The hours start and end at the same time (`WHEN_INVALID`). */
        TIME_RANGE_EMPTY,

        /** A date is not a calendar day `MM-DD` (`WHEN_INVALID`). */
        DATE_INVALID,
        TOO_MANY_DATES,
        BURST_OUT_OF_RANGE,
        ONE_IN_OUT_OF_RANGE,

        /** Ringtones: the file is shorter than 5 s or longer than 6 min. */
        RINGTONE_DURATION_OUT_OF_RANGE,

        /** Ringtones: the loop ends before it starts, lasts under 1 s, or ends past the audio (`LOOP_POINTS_INVALID`). */
        LOOP_INVALID,
        FADE_IN_OUT_OF_RANGE,
        WEIGHT_OUT_OF_RANGE,
    }
}

/**
 * A `when` condition, and a clip's `oneIn`, as the pack creators edit them: one field per control, so the app's and the
 * website's editors read and write conditions the same way (docs/pack-format.md#ringtones-and-variants and
 * #special-clips). [of] reads any condition the format allows; [toWhen] writes it back in a canonical form (one season
 * as a string, several as a list in calendar order; all four seasons or all seven days are the same as none).
 */
@Serializable
data class ConditionForm(
    val time: TimeOfDay = TimeOfDay.ANY,
    /** `HH:MM`, for [TimeOfDay.HOURS]: from (included) and to (excluded), may wrap midnight. */
    val from: String = DEFAULT_FROM,
    val to: String = DEFAULT_TO,
    /** Season names (`spring` … `winter`); empty for all year. */
    val seasons: List<String> = emptyList(),
    /** Weekday names (`mon` … `sun`); empty for every day. */
    val weekdays: List<String> = emptyList(),
    val dates: List<DateForm> = emptyList(),
    /** Clips only. */
    val moment: Moment = Moment.ANY,
    /** Clips only: the message in a row for [Moment.BURST], 2 to 50. */
    val burst: Int = DEFAULT_BURST,
    /** Clips only: a rare clip plays on about one message in [oneIn] (2 to 10,000); null for an ordinary clip. */
    val oneIn: Int? = null,
) {
    /** No condition at all: the clip is in the rotation every time, the ringtone version always matches. */
    fun isEmpty(clip: Boolean): Boolean = toWhen(clip) == null && (!clip || oneIn == null)

    /** What still needs fixing before [toWhen]; [clip] adds the clip-only fields. */
    fun problems(clip: Boolean): List<FormProblem> =
        buildList {
            if (time == TimeOfDay.HOURS) {
                if (clockMinutes(from) == null || clockMinutes(to) == null) {
                    add(FormProblem(FormProblem.Kind.TIME_INVALID))
                } else if (from == to) {
                    add(FormProblem(FormProblem.Kind.TIME_RANGE_EMPTY))
                }
            }
            dates.forEachIndexed { i, d ->
                val toText = d.to?.takeIf { it.isNotBlank() }
                if (DateSpan.parseMonthDay(d.from.trim()) == null ||
                    (toText != null && DateSpan.parseMonthDay(toText.trim()) == null)
                ) {
                    add(FormProblem(FormProblem.Kind.DATE_INVALID, i))
                }
            }
            if (dates.size > MAX_DATES) add(FormProblem(FormProblem.Kind.TOO_MANY_DATES))
            if (clip && moment == Moment.BURST && burst !in BURST_RANGE) {
                add(FormProblem(FormProblem.Kind.BURST_OUT_OF_RANGE))
            }
            if (clip && oneIn != null && oneIn !in ONE_IN_RANGE) add(FormProblem(FormProblem.Kind.ONE_IN_OUT_OF_RANGE))
        }

    /** The condition to write, or null for none. [clip] keeps the moment keys; ringtones never have them. */
    fun toWhen(clip: Boolean): When? {
        val timeJson: JsonElement? =
            when (time) {
                TimeOfDay.ANY -> null
                TimeOfDay.DAY -> JsonPrimitive(DAY)
                TimeOfDay.NIGHT -> JsonPrimitive(NIGHT)
                TimeOfDay.HOURS -> buildJsonObject {
                    put("from", from.trim())
                    put("to", to.trim())
                }
            }
        val dateJson =
            dates
                .map { d ->
                    val start = d.from.trim()
                    val end = d.to?.trim()?.takeIf { it.isNotEmpty() && it != start }
                    if (end == null) {
                        JsonPrimitive(start)
                    } else {
                        buildJsonObject {
                            put("from", start)
                            put("to", end)
                        }
                    }
                }.distinct()
                .let(::oneOrList)
        val w =
            When(
                time = timeJson,
                season = names(seasons, SEASONS),
                weekday = names(weekdays, WEEKDAYS),
                date = dateJson,
                firstOfDay = if (clip && moment == Moment.FIRST_OF_DAY) true else null,
                burst = if (clip && moment == Moment.BURST) burst else null,
            )
        return w.takeUnless { it == When() }
    }

    companion object {
        const val DEFAULT_FROM = "22:00"
        const val DEFAULT_TO = "06:00"
        const val DEFAULT_BURST = 3
        const val DEFAULT_ONE_IN = 25
        const val MAX_DATES = 50
        val BURST_RANGE = 2..50
        val ONE_IN_RANGE = 2..10_000

        /** Calendar order, as the creators list them. */
        val SEASONS = listOf("spring", "summer", "autumn", "winter")
        val WEEKDAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

        /** The form for a pack's [condition] and [oneIn] (both may be absent). */
        fun of(
            condition: When?,
            oneIn: Int? = null,
        ): ConditionForm {
            condition ?: return ConditionForm(oneIn = oneIn)
            val keyword = (condition.time as? JsonPrimitive)?.takeIf { it.isString }?.content
            val range = condition.timeRange
            val time =
                when {
                    keyword == DAY -> TimeOfDay.DAY
                    keyword == NIGHT -> TimeOfDay.NIGHT
                    range != null -> TimeOfDay.HOURS
                    else -> TimeOfDay.ANY
                }
            val dates =
                when (val d = condition.date) {
                    null -> emptyList()
                    is JsonArray -> d.mapNotNull(::dateForm)
                    else -> listOfNotNull(dateForm(d))
                }
            return ConditionForm(
                time = time,
                from = range?.first ?: DEFAULT_FROM,
                to = range?.second ?: DEFAULT_TO,
                seasons = SEASONS.filter { it in condition.seasons },
                weekdays = WEEKDAYS.filter { it in condition.weekdays },
                dates = dates,
                moment =
                    when {
                        condition.firstOfDay == true -> Moment.FIRST_OF_DAY
                        condition.burst != null -> Moment.BURST
                        else -> Moment.ANY
                    },
                burst = condition.burst ?: DEFAULT_BURST,
                oneIn = oneIn,
            )
        }

        /** Minutes after midnight of an `HH:MM` time, or null when it is not one. */
        fun clockMinutes(text: String): Int? {
            val t = text.trim()
            if (t.length != 5 || t[2] != ':') return null
            val h = t.substring(0, 2).toIntOrNull() ?: return null
            val m = t.substring(3, 5).toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59 || !t[0].isDigit() || !t[3].isDigit()) return null
            return h * 60 + m
        }

        private fun dateForm(element: JsonElement): DateForm? =
            when (element) {
                is JsonPrimitive -> element.contentOrNull?.let { DateForm(it) }
                is JsonObject -> {
                    val from = (element["from"] as? JsonPrimitive)?.contentOrNull
                    val to = (element["to"] as? JsonPrimitive)?.contentOrNull
                    from?.let { DateForm(it, to?.takeIf { t -> t != it }) }
                }
                else -> null
            }

        /** The chosen [names] in [order], as one string or a list; null when none or all are chosen. */
        private fun names(
            names: List<String>,
            order: List<String>,
        ): JsonElement? {
            val chosen = order.filter { it in names }
            if (chosen.isEmpty() || chosen.size == order.size) return null
            return oneOrList(chosen.map(::JsonPrimitive))
        }

        private fun oneOrList(items: List<JsonElement>): JsonElement? =
            when (items.size) {
                0 -> null
                1 -> items.single()
                else -> JsonArray(items)
            }

        private const val DAY = "day"
        private const val NIGHT = "night"
    }
}

/** How a ringtone version loops (docs/pack-format.md#ringtones-and-variants). */
@Serializable
enum class LoopMode {
    /** The whole file loops (`loop` left out: the default). */
    WHOLE,

    /** Plays once (`loop: false`). */
    ONCE,

    /** An intro once, then a section loops (`loop: { startMs, endMs }`). */
    SECTION,
}

/**
 * A ringtone version as the pack creators edit it: its label, [condition], loop, fade-in and weight, with the entry's
 * tags kept as they are. [of] reads a pack's entry; [toEntry] writes it back for a file.
 */
@Serializable
data class RingtoneForm(
    val label: String = "",
    val condition: ConditionForm = ConditionForm(),
    val loop: LoopMode = LoopMode.WHOLE,
    /** For [LoopMode.SECTION]: where the loop starts (the end of the intro). */
    val loopStartMs: Int = 0,
    /** For [LoopMode.SECTION]: where the loop ends; null for the end of the file. */
    val loopEndMs: Int? = null,
    val fadeInMs: Int = 0,
    val weight: Double = 1.0,
    val tags: List<String> = emptyList(),
) {
    /** What still needs fixing, for a file of [durationMs] (null when unknown: duration and loop end are not checked). */
    fun problems(durationMs: Long?): List<FormProblem> =
        buildList {
            addAll(condition.problems(clip = false))
            if (durationMs != null &&
                (
                    durationMs < Limits.RINGTONE_MIN_MS - Limits.DURATION_TOLERANCE_MS ||
                        durationMs > Limits.RINGTONE_MAX_MS + Limits.DURATION_TOLERANCE_MS
                )
            ) {
                add(FormProblem(FormProblem.Kind.RINGTONE_DURATION_OUT_OF_RANGE))
            }
            if (loop == LoopMode.SECTION) {
                val end = loopEndMs?.toLong() ?: durationMs
                val tooLong = durationMs != null && end != null && end > durationMs + Limits.DURATION_TOLERANCE_MS
                if (loopStartMs < 0 || (end != null && end - loopStartMs < Limits.MIN_LOOP_MS) || tooLong) {
                    add(FormProblem(FormProblem.Kind.LOOP_INVALID))
                }
            }
            if (fadeInMs !in 0..MAX_FADE_IN_MS) add(FormProblem(FormProblem.Kind.FADE_IN_OUT_OF_RANGE))
            if (!(weight > 0.0 && weight <= MAX_WEIGHT)) add(FormProblem(FormProblem.Kind.WEIGHT_OUT_OF_RANGE))
        }

    /** The entry for [file], defaults left out. */
    fun toEntry(file: String): Ringtone =
        Ringtone(
            file = file,
            label = label.trim().takeIf { it.isNotEmpty() },
            tags = tags,
            `when` = condition.toWhen(clip = false),
            loop =
                when (loop) {
                    LoopMode.WHOLE -> null
                    LoopMode.ONCE -> JsonPrimitive(false)
                    LoopMode.SECTION -> buildJsonObject {
                        put("startMs", loopStartMs)
                        loopEndMs?.let { put("endMs", it) }
                    }
                },
            fadeInMs = fadeInMs.takeIf { it > 0 },
            weight = weight.takeIf { it != 1.0 },
        )

    companion object {
        const val MAX_FADE_IN_MS = 10_000
        const val MAX_WEIGHT = 100.0

        /** The form for a pack's ringtone [entry]. */
        fun of(entry: Ringtone): RingtoneForm {
            val loop = entry.loop
            val mode =
                when {
                    loop is JsonPrimitive && loop.booleanOrNull == false -> LoopMode.ONCE
                    loop is JsonObject -> LoopMode.SECTION
                    else -> LoopMode.WHOLE
                }
            val range = entry.loopRange
            return RingtoneForm(
                label = entry.label.orEmpty(),
                condition = ConditionForm.of(entry.`when`),
                loop = mode,
                loopStartMs = range?.startMs?.toInt() ?: 0,
                loopEndMs = range?.endMs?.toInt(),
                fadeInMs = entry.fadeInMs ?: 0,
                weight = entry.weight ?: 1.0,
                tags = entry.tags,
            )
        }
    }
}
