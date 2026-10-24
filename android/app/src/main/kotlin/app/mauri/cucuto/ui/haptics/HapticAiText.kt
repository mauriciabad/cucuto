package app.mauri.cucuto.ui.haptics

import app.mauri.cucuto.core.data.routing.HapticJson
import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

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
 * pattern behaves exactly like a pack haptic. The prompt stays in English: it is read by a model, not shown in the UI.
 */
object HapticAiText {
    /** Clips described at most, to keep the prompt short. */
    const val MAX_CLIPS: Int = 6

    /** The prompt for [clips] of [character] (null for a scratch pad) and the [current] timeline. */
    fun export(
        character: String?,
        clips: List<AiClip>,
        current: List<HapticEvent>,
    ): String =
        buildString {
            appendLine("Write a vibration pattern for a phone notification sound (Android, the app Cucuto).")
            appendLine(
                "Make the phone vibrate in time with the sound: a tap on each beat or accent, a swell where a sound " +
                    "is held, nothing where it is quiet. Cucuto's automatic vibration already follows the loudness " +
                    "below; yours should hit the rhythm more precisely. If I attached the audio, listen to it.",
            )
            appendLine("Answer with only the JSON pattern in one code block.")
            appendLine()
            appendLine("## The sound")
            if (clips.isEmpty()) {
                appendLine("No clip details. Use the audio I attached, or ask me to describe the sound.")
            } else {
                if (character != null) appendLine("Character: $character.")
                if (clips.size > 1) {
                    appendLine(
                        "The pattern plays with each of its ${clips.size} clips (one at random per message): make it " +
                            "fit them all, the first one best.",
                    )
                }
                clips.take(MAX_CLIPS).forEachIndexed { i, clip -> appendClip(i + 1, clip) }
                if (clips.size > MAX_CLIPS) appendLine("(${clips.size - MAX_CLIPS} more clips not listed.)")
            }
            appendLine()
            appendLine("## Current pattern")
            if (current.isEmpty()) {
                appendLine("None yet.")
            } else {
                appendLine("Improve it or replace it:")
                appendLine("```json")
                appendLine(patternJson(current))
                appendLine("```")
            }
            appendLine()
            append(FORMAT)
        }.trimEnd() + "\n"

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

    private fun StringBuilder.appendClip(
        number: Int,
        clip: AiClip,
    ) {
        val length = clip.durationMs?.let { "$it ms long" } ?: "length unknown"
        appendLine()
        appendLine("Clip $number, \"${clip.path}\": $length.")
        val loudness = clip.loudness
        if (loudness == null) {
            appendLine("No loudness data (silent, or not analysed yet).")
            return
        }
        appendLine(
            "Loudness, analysed in 20 ms windows over at most the first 2 s and simplified (ms from the start, " +
                "loudness 0-100, sharpness 0-100 from pitch):",
        )
        appendLine("ms,loudness,sharpness")
        loudnessRows(loudness).forEach { (t, level, sharp) -> appendLine("$t,$level,$sharp") }
    }

    private fun percent(milli: Int): Int = (milli / 10f).roundToInt()

    private val FORMAT =
        """
        ## Format (Cucuto pack format, "custom" haptic)
        {"mode":"custom","events":[...]} with 1 to 64 events, sorted by "at".
        - "at": start in whole milliseconds from the start of the sound, 0 to 5000.
        - Preset: {"at":0,"preset":"CLICK","scale":0.8}. "preset" is CLICK (crisp tap), TICK (lighter) or LOW_TICK
          (lightest, lower); "scale" is 0 to 1 (default 1).
        - Envelope, a swell: {"at":220,"initialSharpness":0.3,"envelope":[{"intensity":1,"sharpness":0.8,"ms":120},
          {"intensity":0,"sharpness":0.4,"ms":180}]}. 1 to 16 points; "intensity" and "sharpness" are 0 to 1 (sharp
          feels crisp, low feels soft and deep); "ms" (whole number, 1 to 5000) is the time to reach this point from
          the previous one; the last point must have intensity 0.
        Rules: an event may not start before the previous envelope has ended; leave at least 50 ms after a preset;
        everything ends by 5000 ms. No other fields.

        Example:
        ```json
        {"mode":"custom","events":[{"at":0,"preset":"CLICK","scale":0.9},{"at":180,"preset":"TICK","scale":0.6},{"at":320,"envelope":[{"intensity":0.8,"sharpness":0.5,"ms":60},{"intensity":0,"sharpness":0.3,"ms":240}]}]}
        ```
        """.trimIndent()

    // Paste.

    /**
     * The pattern in [text]: the JSON of the first code block that holds one, else the first JSON object or array in
     * the text. Accepts the full haptic (`{"mode":"custom","events":[…]}`), a character's `{"haptic":{…}}`, a bare
     * `{"events":[…]}` or the events array alone. Everything else follows the pack format strictly.
     */
    fun parse(text: String): PatternParse {
        val root = extractJson(text) ?: return invalid(PatternProblem.NotJson)
        return try {
            PatternParse.Valid(Reader().read(root))
        } catch (e: Refused) {
            invalid(e.problem)
        }
    }

    private fun invalid(problem: PatternProblem) = PatternParse.Invalid(problem)

    private val FENCE = Regex("```[A-Za-z0-9_-]*[ \\t]*\\r?\\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)

    internal fun extractJson(text: String): JsonElement? {
        val candidates = FENCE.findAll(text).map { it.groupValues[1] } + sequenceOf(text)
        for (candidate in candidates) {
            val start = candidate.indexOfFirst { it == '{' || it == '[' }
            if (start < 0) continue
            val end = candidate.lastIndexOf(if (candidate[start] == '{') '}' else ']')
            if (end <= start) continue
            val element =
                try {
                    Json.parseToJsonElement(candidate.substring(start, end + 1))
                } catch (_: IllegalArgumentException) {
                    // kotlinx.serialization reports malformed JSON as SerializationException, an IllegalArgumentException.
                    null
                }
            if (element is JsonObject || element is JsonArray) return element
        }
        return null
    }

    private class Refused(
        val problem: PatternProblem,
    ) : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Reader {
        fun read(root: JsonElement): List<HapticEvent> {
            val events = eventsOf(root)
            if (events.isEmpty()) refuse(PatternProblem.NoEvents)
            if (events.size > HapticTimeline.MAX_EVENTS) refuse(PatternProblem.TooManyEvents)
            val parsed = events.mapIndexed { i, element -> event(i + 1, element) }
            checkTimeline(parsed)
            return parsed
        }

        private fun eventsOf(root: JsonElement): JsonArray {
            if (root is JsonArray) return root
            var obj = root as JsonObject
            (obj["haptic"] as? JsonObject)?.takeIf { obj.size == 1 }?.let { obj = it }
            val pattern = PatternSpot()
            obj.keys.firstOrNull { it !in TOP_FIELDS && !it.startsWith("x-") }?.let {
                refuse(PatternProblem.UnknownField(pattern, it))
            }
            obj["mode"]?.let { mode ->
                val name = (mode as? JsonPrimitive)?.content ?: mode.toString()
                if (name != "custom") refuse(PatternProblem.NotCustom(name))
            }
            return obj["events"] as? JsonArray ?: refuse(PatternProblem.NoEvents)
        }

        private fun event(
            n: Int,
            element: JsonElement,
        ): HapticEvent {
            val obj = element as? JsonObject ?: refuse(PatternProblem.NotAnEvent(n))
            val isPreset = "preset" in obj
            if (isPreset == ("envelope" in obj)) refuse(PatternProblem.NotAnEvent(n))
            val spot = PatternSpot(event = n)
            val allowed = if (isPreset) PRESET_FIELDS else ENVELOPE_FIELDS
            obj.keys.firstOrNull { it !in allowed }?.let { refuse(PatternProblem.UnknownField(spot, it)) }
            val at = whole(obj, spot, "at", min = 0)
            return if (isPreset) {
                val name = (obj["preset"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val preset =
                    HapticPreset.entries.firstOrNull { it.name == name }
                        ?: refuse(PatternProblem.UnknownPreset(n, name ?: obj["preset"].toString()))
                HapticEvent.Preset(at, preset, fraction(obj, spot, "scale") ?: HapticTimeline.MILLI_ONE)
            } else {
                val initial = fraction(obj, spot, "initialSharpness") ?: HapticEvent.Envelope.DEFAULT_SHARPNESS
                val points = obj["envelope"] as? JsonArray ?: refuse(PatternProblem.PointCount(n, 0))
                if (points.size !in 1..HapticTimeline.MAX_POINTS) refuse(PatternProblem.PointCount(n, points.size))
                val parsed = points.mapIndexed { i, point -> point(PatternSpot(n, i + 1), point) }
                if (parsed.last().milliIntensity != 0) refuse(PatternProblem.NotClosed(n))
                HapticEvent.Envelope(at, parsed, initial)
            }
        }

        private fun point(
            spot: PatternSpot,
            element: JsonElement,
        ): ControlPoint {
            val obj = element as? JsonObject ?: refuse(PatternProblem.MissingField(spot, "intensity"))
            obj.keys.firstOrNull { it !in POINT_FIELDS }?.let { refuse(PatternProblem.UnknownField(spot, it)) }
            return ControlPoint(
                milliIntensity = fraction(obj, spot, "intensity", required = true)!!,
                milliSharpness = fraction(obj, spot, "sharpness", required = true)!!,
                durationMs = whole(obj, spot, "ms", min = 1),
            )
        }

        /** The pack format's order, overlap and length rules (`PackValidator.checkHaptic`). */
        private fun checkTimeline(events: List<HapticEvent>) {
            var previousStart = -1
            var busyUntil = 0
            events.forEachIndexed { i, event ->
                if (event.atMs < previousStart) refuse(PatternProblem.Unsorted(i + 1))
                if (event.atMs < busyUntil) refuse(PatternProblem.Overlap(i + 1, event.atMs, busyUntil))
                previousStart = event.atMs
                if (event is HapticEvent.Envelope) busyUntil = maxOf(busyUntil, HapticTimeline.endOf(event))
            }
            if (busyUntil > HapticTimeline.MAX_MS) refuse(PatternProblem.TooLong(busyUntil))
        }

        private fun whole(
            obj: JsonObject,
            spot: PatternSpot,
            field: String,
            min: Int,
        ): Int {
            val element = obj[field] ?: refuse(PatternProblem.MissingField(spot, field))
            val value =
                (element as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                    ?: refuse(PatternProblem.NotWholeNumber(spot, field, element.toString()))
            if (value % 1.0 != 0.0) refuse(PatternProblem.NotWholeNumber(spot, field, element.toString()))
            if (value < min || value > HapticTimeline.MAX_MS) {
                refuse(PatternProblem.OutOfRange(spot, field, min, HapticTimeline.MAX_MS, element.toString()))
            }
            return value.toInt()
        }

        /** A 0 to 1 number as thousandths, or null when absent and optional. */
        private fun fraction(
            obj: JsonObject,
            spot: PatternSpot,
            field: String,
            required: Boolean = false,
        ): Int? {
            val element = obj[field] ?: if (required) refuse(PatternProblem.MissingField(spot, field)) else return null
            val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            if (value == null || value < 0.0 || value > 1.0) {
                refuse(PatternProblem.OutOfRange(spot, field, 0, 1, element.toString()))
            }
            return (value * HapticTimeline.MILLI_ONE).roundToInt()
        }

        private fun refuse(problem: PatternProblem): Nothing = throw Refused(problem)

        private companion object {
            val TOP_FIELDS = setOf("mode", "events")
            val PRESET_FIELDS = setOf("at", "preset", "scale")
            val ENVELOPE_FIELDS = setOf("at", "initialSharpness", "envelope")
            val POINT_FIELDS = setOf("intensity", "sharpness", "ms")
        }
    }
}
