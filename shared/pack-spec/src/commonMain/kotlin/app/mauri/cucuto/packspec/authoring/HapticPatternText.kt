package app.mauri.cucuto.packspec.authoring

import app.mauri.cucuto.packspec.ControlPoint
import app.mauri.cucuto.packspec.Haptic
import app.mauri.cucuto.packspec.HapticEvent
import app.mauri.cucuto.packspec.Limits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The text format of a custom haptic, shared by the app's haptic editor (#217, ADR 0053) and the website's creator
 * (#191): "Copy for AI" writes a compact, self-contained prompt ([prompt]) that the user pastes into any AI chat, and
 * "Paste pattern" reads the answer back ([parse]), checked against the pack format's haptic rules
 * (`docs/pack-format.md#haptics`, the same as `PackValidator.checkHaptic` plus the schema's limits), so a pasted
 * pattern behaves exactly like a pack haptic. Cucuto never calls an AI itself. The prompt stays in English: it is read
 * by a model, not shown in the UI.
 */
object HapticPatternText {
    /** Clips described at most, to keep the prompt short. */
    const val MAX_CLIPS: Int = 6

    /** Events in a custom haptic (`pack.schema.json`). */
    const val MAX_EVENTS: Int = 64

    /** Control points in one envelope (`pack.schema.json`). */
    const val MAX_POINTS: Int = 16

    /** Latest start and longest timeline, in milliseconds. */
    const val MAX_MS: Int = Limits.HAPTIC_MAX_MS.toInt()

    /** One row of a clip's analysed loudness: absolute time, loudness and sharpness (0 to 100). */
    data class LoudnessRow(
        val ms: Int,
        val loudness: Int,
        val sharpness: Int,
    )

    /**
     * One clip as the prompt describes it.
     *
     * @property path The clip's path in the pack (a pack file name, nothing private).
     * @property durationMs The clip's length, or null when it could not be read.
     * @property loudness The clip's auto envelope as rows, or null when silent or not analysed.
     */
    data class PromptClip(
        val path: String,
        val durationMs: Long?,
        val loudness: List<LoudnessRow>?,
    )

    /**
     * The prompt for [clips] of [character] (null for a scratch pad) and the current pattern as compact JSON
     * ([currentJson], null or blank when there is none yet).
     */
    fun prompt(
        character: String?,
        clips: List<PromptClip>,
        currentJson: String?,
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
            if (currentJson.isNullOrBlank()) {
                appendLine("None yet.")
            } else {
                appendLine("Improve it or replace it:")
                appendLine("```json")
                appendLine(currentJson)
                appendLine("```")
            }
            appendLine()
            append(FORMAT)
        }.trimEnd() + "\n"

    /**
     * [events] as the pack format's compact `{"mode":"custom","events":[…]}` JSON, exactly as `pack.json` would carry
     * them (a scale of 1 and an absent initial sharpness are left out).
     */
    fun patternJson(events: List<HapticEvent>): String =
        compactJson.encodeToString(Haptic.serializer(), Haptic(mode = "custom", events = events))

    private val compactJson = Json { explicitNulls = false }

    private fun StringBuilder.appendClip(
        number: Int,
        clip: PromptClip,
    ) {
        val length = clip.durationMs?.let { "$it ms long" } ?: "length unknown"
        appendLine()
        appendLine("Clip $number, \"${clip.path}\": $length.")
        val loudness = clip.loudness
        if (loudness.isNullOrEmpty()) {
            appendLine("No loudness data (silent, or not analysed yet).")
            return
        }
        appendLine(
            "Loudness, analysed in 20 ms windows over at most the first 2 s and simplified (ms from the start, " +
                "loudness 0-100, sharpness 0-100 from pitch):",
        )
        appendLine("ms,loudness,sharpness")
        loudness.forEach { appendLine("${it.ms},${it.loudness},${it.sharpness}") }
    }

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

    /** What [parse] made of a text. */
    sealed interface Parse {
        /** A timeline that follows every pack-format rule. Values are as written (fractions are not rounded). */
        data class Valid(
            val events: List<HapticEvent>,
        ) : Parse

        /** The first rule the text breaks. */
        data class Invalid(
            val problem: Problem,
        ) : Parse
    }

    /** Where a [Problem] is: the pattern itself, an event or one of its envelope points (both counted from 1). */
    data class Spot(
        val event: Int? = null,
        val point: Int? = null,
    )

    /** Why a pasted pattern was refused. Each creator words them plainly in its own language. */
    sealed interface Problem {
        /** No JSON object or array in the text. */
        data object NotJson : Problem

        /** `mode` is something other than `custom`. */
        data class NotCustom(
            val mode: String,
        ) : Problem

        data object NoEvents : Problem

        /** More than [MAX_EVENTS] events. */
        data object TooManyEvents : Problem

        /** An event that is not an object with exactly one of `preset` or `envelope`. */
        data class NotAnEvent(
            val event: Int,
        ) : Problem

        data class MissingField(
            val spot: Spot,
            val field: String,
        ) : Problem

        data class UnknownField(
            val spot: Spot,
            val field: String,
        ) : Problem

        /** `at` or `ms` is not a whole number. */
        data class NotWholeNumber(
            val spot: Spot,
            val field: String,
            val value: String,
        ) : Problem

        /** A number outside [min]..[max] (or not a number at all, for fractions). */
        data class OutOfRange(
            val spot: Spot,
            val field: String,
            val min: Int,
            val max: Int,
            val value: String,
        ) : Problem

        data class UnknownPreset(
            val event: Int,
            val name: String,
        ) : Problem

        /** An envelope without 1 to [MAX_POINTS] points. */
        data class PointCount(
            val event: Int,
            val count: Int,
        ) : Problem

        /** An envelope whose last point is not at intensity 0. */
        data class NotClosed(
            val event: Int,
        ) : Problem

        data class Unsorted(
            val event: Int,
        ) : Problem

        /** An event that starts before the previous envelope ends. */
        data class Overlap(
            val event: Int,
            val atMs: Int,
            val busyUntilMs: Int,
        ) : Problem

        /** The timeline ends after [MAX_MS]. */
        data class TooLong(
            val ms: Int,
        ) : Problem
    }

    /** The presets of the format (`VibrationEffect.Preset` in API 37). */
    val PRESETS: List<String> = listOf("CLICK", "TICK", "LOW_TICK")

    /**
     * The pattern in [text]: the JSON of the first code block that holds one, else the first JSON object or array in
     * the text. Accepts the full haptic (`{"mode":"custom","events":[…]}`), a character's `{"haptic":{…}}`, a bare
     * `{"events":[…]}` or the events array alone. Everything else follows the pack format strictly.
     */
    fun parse(text: String): Parse {
        val root = extractJson(text) ?: return Parse.Invalid(Problem.NotJson)
        return try {
            Parse.Valid(Reader().read(root))
        } catch (e: Refused) {
            Parse.Invalid(e.problem)
        }
    }

    private val FENCE = Regex("```[A-Za-z0-9_-]*[ \\t]*\\r?\\n?([\\s\\S]*?)```")

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
        val problem: Problem,
    ) : Exception()

    private class Reader {
        fun read(root: JsonElement): List<HapticEvent> {
            val events = eventsOf(root)
            if (events.isEmpty()) refuse(Problem.NoEvents)
            if (events.size > MAX_EVENTS) refuse(Problem.TooManyEvents)
            val parsed = events.mapIndexed { i, element -> event(i + 1, element) }
            checkTimeline(parsed)
            return parsed
        }

        private fun eventsOf(root: JsonElement): JsonArray {
            if (root is JsonArray) return root
            var obj = root as JsonObject
            (obj["haptic"] as? JsonObject)?.takeIf { obj.size == 1 }?.let { obj = it }
            val pattern = Spot()
            obj.keys.firstOrNull { it !in TOP_FIELDS && !it.startsWith("x-") }?.let {
                refuse(Problem.UnknownField(pattern, it))
            }
            obj["mode"]?.let { mode ->
                val name = (mode as? JsonPrimitive)?.content ?: mode.toString()
                if (name != "custom") refuse(Problem.NotCustom(name))
            }
            return obj["events"] as? JsonArray ?: refuse(Problem.NoEvents)
        }

        private fun event(
            n: Int,
            element: JsonElement,
        ): HapticEvent {
            val obj = element as? JsonObject ?: refuse(Problem.NotAnEvent(n))
            val isPreset = "preset" in obj
            if (isPreset == ("envelope" in obj)) refuse(Problem.NotAnEvent(n))
            val spot = Spot(event = n)
            val allowed = if (isPreset) PRESET_FIELDS else ENVELOPE_FIELDS
            obj.keys.firstOrNull { it !in allowed }?.let { refuse(Problem.UnknownField(spot, it)) }
            val at = whole(obj, spot, "at", min = 0)
            return if (isPreset) {
                val name = (obj["preset"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (name == null || name !in PRESETS) {
                    refuse(Problem.UnknownPreset(n, name ?: obj["preset"].toString()))
                }
                HapticEvent(at = at.toLong(), preset = name, scale = fraction(obj, spot, "scale"))
            } else {
                val initial = fraction(obj, spot, "initialSharpness")
                val points = obj["envelope"] as? JsonArray ?: refuse(Problem.PointCount(n, 0))
                if (points.size !in 1..MAX_POINTS) refuse(Problem.PointCount(n, points.size))
                val parsed = points.mapIndexed { i, point -> point(Spot(n, i + 1), point) }
                if (parsed.last().intensity != 0.0) refuse(Problem.NotClosed(n))
                HapticEvent(at = at.toLong(), initialSharpness = initial, envelope = parsed)
            }
        }

        private fun point(
            spot: Spot,
            element: JsonElement,
        ): ControlPoint {
            val obj = element as? JsonObject ?: refuse(Problem.MissingField(spot, "intensity"))
            obj.keys.firstOrNull { it !in POINT_FIELDS }?.let { refuse(Problem.UnknownField(spot, it)) }
            return ControlPoint(
                intensity = fraction(obj, spot, "intensity", required = true)!!,
                sharpness = fraction(obj, spot, "sharpness", required = true)!!,
                ms = whole(obj, spot, "ms", min = 1).toLong(),
            )
        }

        /** The pack format's order, overlap and length rules (`PackValidator.checkHaptic`). */
        private fun checkTimeline(events: List<HapticEvent>) {
            var previousStart = -1L
            var busyUntil = 0L
            events.forEachIndexed { i, event ->
                if (event.at < previousStart) refuse(Problem.Unsorted(i + 1))
                if (event.at < busyUntil) refuse(Problem.Overlap(i + 1, event.at.toInt(), busyUntil.toInt()))
                previousStart = event.at
                if (event.envelope != null) busyUntil = maxOf(busyUntil, event.at + event.durationMs)
            }
            if (busyUntil > MAX_MS) refuse(Problem.TooLong(busyUntil.toInt()))
        }

        private fun whole(
            obj: JsonObject,
            spot: Spot,
            field: String,
            min: Int,
        ): Int {
            val element = obj[field] ?: refuse(Problem.MissingField(spot, field))
            val value =
                (element as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                    ?: refuse(Problem.NotWholeNumber(spot, field, element.toString()))
            if (value % 1.0 != 0.0) refuse(Problem.NotWholeNumber(spot, field, element.toString()))
            if (value < min || value > MAX_MS) {
                refuse(Problem.OutOfRange(spot, field, min, MAX_MS, element.toString()))
            }
            return value.toInt()
        }

        /** A 0 to 1 number, or null when absent and optional. */
        private fun fraction(
            obj: JsonObject,
            spot: Spot,
            field: String,
            required: Boolean = false,
        ): Double? {
            val element = obj[field] ?: if (required) refuse(Problem.MissingField(spot, field)) else return null
            val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            if (value == null || value < 0.0 || value > 1.0) {
                refuse(Problem.OutOfRange(spot, field, 0, 1, element.toString()))
            }
            return value
        }

        private fun refuse(problem: Problem): Nothing = throw Refused(problem)

        private companion object {
            val TOP_FIELDS = setOf("mode", "events")
            val PRESET_FIELDS = setOf("at", "preset", "scale")
            val ENVELOPE_FIELDS = setOf("at", "initialSharpness", "envelope")
            val POINT_FIELDS = setOf("intensity", "sharpness", "ms")
        }
    }
}
