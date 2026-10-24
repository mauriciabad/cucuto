package app.mauri.cucuto.ui.haptics

import app.mauri.cucuto.core.data.routing.HapticJson
import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.packspec.authoring.HapticPatternText
import kotlin.math.roundToInt
import app.mauri.cucuto.packspec.HapticEvent as PackHapticEvent

/**
 * One clip as "Copy for AI" describes it.
 *
 * @property path The clip's path from `pack.json` (a pack file name, nothing private).
 * @property durationMs The clip's length, or null when it could not be read.
 * @property loudness The clip's auto envelope (analysed at import, ADR 0020), or null when silent or not analysed.
 */
data class AiClip(
    val path: String,
    val durationMs: Long?,
    val loudness: Haptic.Custom?,
)

/** What "Paste pattern" made of the clipboard. */
sealed interface PatternParse {
    /** A timeline that follows every pack-format rule (`docs/pack-format.md#haptics`). */
    data class Valid(
        val events: List<HapticEvent>,
    ) : PatternParse

    /** The first rule the pasted text breaks. */
    data class Invalid(
        val problem: PatternProblem,
    ) : PatternParse
}

/** Where a [PatternProblem] is: the pattern itself, an event or one of its envelope points (both counted from 1). */
data class PatternSpot(
    val event: Int? = null,
    val point: Int? = null,
)

/** Why a pasted pattern was refused. The screen words each one plainly. */
sealed interface PatternProblem {
    /** No JSON object or array in the text. */
    data object NotJson : PatternProblem

    /** `mode` is something other than `custom`. */
    data class NotCustom(
        val mode: String,
    ) : PatternProblem

    data object NoEvents : PatternProblem

    /** More than [HapticTimeline.MAX_EVENTS] events. */
    data object TooManyEvents : PatternProblem

    /** An event that is not an object with exactly one of `preset` or `envelope`. */
    data class NotAnEvent(
        val event: Int,
    ) : PatternProblem

    data class MissingField(
        val spot: PatternSpot,
        val field: String,
    ) : PatternProblem

    data class UnknownField(
        val spot: PatternSpot,
        val field: String,
    ) : PatternProblem

    /** `at` or `ms` is not a whole number. */
    data class NotWholeNumber(
        val spot: PatternSpot,
        val field: String,
        val value: String,
    ) : PatternProblem

    /** A number outside [min]..[max] (or not a number at all, for fractions). */
    data class OutOfRange(
        val spot: PatternSpot,
        val field: String,
        val min: Int,
        val max: Int,
        val value: String,
    ) : PatternProblem

    data class UnknownPreset(
        val event: Int,
        val name: String,
    ) : PatternProblem

    /** An envelope without 1 to 16 points. */
    data class PointCount(
        val event: Int,
        val count: Int,
    ) : PatternProblem

    /** An envelope whose last point is not at intensity 0. */
    data class NotClosed(
        val event: Int,
    ) : PatternProblem

    data class Unsorted(
        val event: Int,
    ) : PatternProblem

    /** An event that starts before the previous envelope ends. */
    data class Overlap(
        val event: Int,
        val atMs: Int,
        val busyUntilMs: Int,
    ) : PatternProblem

    /** The timeline ends after [HapticTimeline.MAX_MS]. */
    data class TooLong(
        val ms: Int,
    ) : PatternProblem
}

/**
 * "Copy for AI" and "Paste pattern" (#217): Cucuto never calls an AI itself. [export] writes a compact, self-contained
 * prompt (the clips' durations and analysed loudness, the current pattern, the format and an example) that the user
 * pastes into any AI chat; [parse] reads the answer back, checked against the pack format's haptic rules, so a pasted
 * pattern behaves exactly like a pack haptic. The text format itself is shared with the website's creator (#191):
 * `HapticPatternText` in shared/pack-spec; this object maps it to the editor's timeline (thousandths).
 */
object HapticAiText {
    /** Clips described at most, to keep the prompt short. */
    const val MAX_CLIPS: Int = HapticPatternText.MAX_CLIPS

    /** The prompt for [clips] of [character] (null for a scratch pad) and the [current] timeline. */
    fun export(
        character: String?,
        clips: List<AiClip>,
        current: List<HapticEvent>,
    ): String =
        HapticPatternText.prompt(
            character = character,
            clips =
                clips.map { clip ->
                    HapticPatternText.PromptClip(
                        path = clip.path,
                        durationMs = clip.durationMs,
                        loudness =
                            clip.loudness?.let { envelope ->
                                loudnessRows(envelope).map { (t, level, sharp) ->
                                    HapticPatternText.LoudnessRow(t, level, sharp)
                                }
                            },
                    )
                },
            currentJson = current.takeIf { it.isNotEmpty() }?.let(::patternJson),
        )

    /** [events] as the pack format's compact `{"mode":"custom","events":[…]}` JSON. */
    fun patternJson(events: List<HapticEvent>): String = HapticJson.toJson(Haptic.Custom(events)).toString()

    /**
     * [envelope] (an auto envelope) as rows of absolute time, loudness and sharpness (0 to 100): one row where it
     * starts, then one per control point.
     */
    fun loudnessRows(envelope: Haptic.Custom): List<Triple<Int, Int, Int>> =
        envelope.events.filterIsInstance<HapticEvent.Envelope>().flatMap { event ->
            val first =
                event.milliInitialSharpness.takeIf { it != HapticEvent.Envelope.DEFAULT_SHARPNESS }
                    ?: event.points.first().milliSharpness
            var t = event.atMs
            listOf(Triple(t, 0, percent(first))) +
                event.points.map { point ->
                    t += point.durationMs
                    Triple(t, percent(point.milliIntensity), percent(point.milliSharpness))
                }
        }

    private fun percent(milli: Int): Int = (milli / 10f).roundToInt()

    /**
     * The pattern in [text]: the JSON of the first code block that holds one, else the first JSON object or array in
     * the text. Accepts the full haptic (`{"mode":"custom","events":[…]}`), a character's `{"haptic":{…}}`, a bare
     * `{"events":[…]}` or the events array alone. Everything else follows the pack format strictly.
     */
    fun parse(text: String): PatternParse =
        when (val parsed = HapticPatternText.parse(text)) {
            is HapticPatternText.Parse.Valid -> PatternParse.Valid(parsed.events.map(::event))
            is HapticPatternText.Parse.Invalid -> PatternParse.Invalid(problem(parsed.problem))
        }

    /** A checked pack event in the editor's thousandths. */
    private fun event(event: PackHapticEvent): HapticEvent {
        val at = event.at.toInt()
        val preset = event.preset
        if (preset != null) {
            return HapticEvent.Preset(at, HapticPreset.valueOf(preset), milli(event.scale ?: 1.0))
        }
        val points =
            event.envelope.orEmpty().map {
                ControlPoint(
                    milli(it.intensity),
                    milli(it.sharpness),
                    it.ms.toInt(),
                )
            }
        val initial = event.initialSharpness?.let(::milli) ?: HapticEvent.Envelope.DEFAULT_SHARPNESS
        return HapticEvent.Envelope(at, points, initial)
    }

    private fun milli(fraction: Double): Int = (fraction * HapticTimeline.MILLI_ONE).roundToInt()

    private fun spot(spot: HapticPatternText.Spot) = PatternSpot(spot.event, spot.point)

    private fun problem(problem: HapticPatternText.Problem): PatternProblem =
        when (problem) {
            HapticPatternText.Problem.NotJson -> {
                PatternProblem.NotJson
            }

            HapticPatternText.Problem.NoEvents -> {
                PatternProblem.NoEvents
            }

            HapticPatternText.Problem.TooManyEvents -> {
                PatternProblem.TooManyEvents
            }

            is HapticPatternText.Problem.NotCustom -> {
                PatternProblem.NotCustom(problem.mode)
            }

            is HapticPatternText.Problem.NotAnEvent -> {
                PatternProblem.NotAnEvent(problem.event)
            }

            is HapticPatternText.Problem.MissingField -> {
                PatternProblem.MissingField(spot(problem.spot), problem.field)
            }

            is HapticPatternText.Problem.UnknownField -> {
                PatternProblem.UnknownField(spot(problem.spot), problem.field)
            }

            is HapticPatternText.Problem.NotWholeNumber -> {
                PatternProblem.NotWholeNumber(spot(problem.spot), problem.field, problem.value)
            }

            is HapticPatternText.Problem.OutOfRange -> {
                PatternProblem.OutOfRange(spot(problem.spot), problem.field, problem.min, problem.max, problem.value)
            }

            is HapticPatternText.Problem.UnknownPreset -> {
                PatternProblem.UnknownPreset(problem.event, problem.name)
            }

            is HapticPatternText.Problem.PointCount -> {
                PatternProblem.PointCount(problem.event, problem.count)
            }

            is HapticPatternText.Problem.NotClosed -> {
                PatternProblem.NotClosed(problem.event)
            }

            is HapticPatternText.Problem.Unsorted -> {
                PatternProblem.Unsorted(problem.event)
            }

            is HapticPatternText.Problem.Overlap -> {
                PatternProblem.Overlap(problem.event, problem.atMs, problem.busyUntilMs)
            }

            is HapticPatternText.Problem.TooLong -> {
                PatternProblem.TooLong(problem.ms)
            }
        }
}
