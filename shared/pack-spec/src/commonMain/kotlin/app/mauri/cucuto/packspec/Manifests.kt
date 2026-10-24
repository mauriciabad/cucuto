package app.mauri.cucuto.packspec

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** JSON settings for reading manifests (after schema validation) and writing them. */
val SpecJson: Json = Json {
    ignoreUnknownKeys = true // x- extension keys and "$schema"
    explicitNulls = false
    encodeDefaults = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

@Serializable
data class PackManifest(
    val formatVersion: Int,
    val type: String? = null,
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val license: String,
    val homepage: String? = null,
    val description: String? = null,
    val cover: String? = null,
    val tags: List<String> = emptyList(),
    val characters: List<Character>,
)

@Serializable
data class Character(
    val id: String,
    val name: String,
    val description: String? = null,
    val sprite: String,
    val spriteAnimated: String? = null,
    val seedColor: String? = null,
    val tags: List<String> = emptyList(),
    val clips: List<Clip> = emptyList(),
    val ringtones: List<Ringtone> = emptyList(),
    val haptic: Haptic? = null,
)

@Serializable
data class Clip(
    val file: String,
    val label: String? = null,
    val weight: Double? = null,
    val `when`: When? = null,
    /** A rare clip (easter egg): about one message in [oneIn], outside the shuffle bag (docs/pack-format.md). */
    val oneIn: Int? = null,
)

@Serializable
data class Ringtone(
    val file: String,
    val label: String? = null,
    val tags: List<String> = emptyList(),
    val `when`: When? = null,
    val loop: JsonElement? = null,
    val fadeInMs: Int? = null,
    val weight: Double? = null,
) {
    /** Loop range, or null when the ringtone plays once (loop: false). Whole file when both bounds are null. */
    val loopRange: LoopRange?
        get() = when (val l = loop) {
            null -> LoopRange(null, null)
            is JsonPrimitive -> if (l.booleanOrNull == false) null else LoopRange(null, null)
            is JsonObject -> LoopRange(
                (l["startMs"] as? JsonPrimitive)?.longOrNull,
                (l["endMs"] as? JsonPrimitive)?.longOrNull,
            )
            else -> LoopRange(null, null)
        }
}

data class LoopRange(val startMs: Long?, val endMs: Long?)

@Serializable
data class When(
    val time: JsonElement? = null,
    val season: JsonElement? = null,
    val weekday: JsonElement? = null,
    /** Calendar days every year: `"MM-DD"`, `{ "from", "to" }` (inclusive, may wrap the new year) or a list. */
    val date: JsonElement? = null,
    /** Clips only: the chat's first message of the day. */
    val firstOfDay: Boolean? = null,
    /** Clips only: the burst-th message in a row from the chat, each within a minute of the previous one. */
    val burst: Int? = null,
) {
    /** "day", "night", or "HH:MM-HH:MM" for a range. */
    val timeRange: Pair<String, String>?
        get() = (time as? JsonObject)?.let {
            val from = (it["from"] as? JsonPrimitive)?.contentOrNull
            val to = (it["to"] as? JsonPrimitive)?.contentOrNull
            if (from != null && to != null) from to to else null
        }

    val seasons: List<String> get() = stringOrList(season)
    val weekdays: List<String> get() = stringOrList(weekday)

    /**
     * The `date` key as spans, in order; a span whose day is impossible (`02-30`) is null, which validators report as
     * `WHEN_INVALID`. Empty when the key is absent.
     */
    val dateSpans: List<DateSpan?>
        get() = when (val d = date) {
            null -> emptyList()
            is JsonArray -> d.map { DateSpan.of(it) }
            else -> listOf(DateSpan.of(d))
        }

    private fun stringOrList(e: JsonElement?): List<String> = when (e) {
        null -> emptyList()
        is JsonPrimitive -> listOfNotNull(e.contentOrNull)
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }
}

/**
 * One span of a `when.date`: from [from] to [to], both inclusive, as `month * 100 + day` (`1225` for 25 December).
 * A span whose [to] is earlier than [from] wraps the new year (`12-24` to `01-06`). `02-29` exists only in leap years.
 */
data class DateSpan(val from: Int, val to: Int) {
    /** Whether the day [month] (1 to 12), [day] is inside the span. */
    fun contains(month: Int, day: Int): Boolean {
        val d = month * 100 + day
        return if (from <= to) d in from..to else d >= from || d <= to
    }

    companion object {
        /** Days in each month, February counted as 29 (it matches in leap years only). */
        private val MONTH_DAYS = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

        /** `MM-DD` as `month * 100 + day`, or null when it is not a possible calendar day. */
        fun parseMonthDay(text: String): Int? {
            if (text.length != 5 || text[2] != '-') return null
            val month = text.substring(0, 2).toIntOrNull() ?: return null
            val day = text.substring(3, 5).toIntOrNull() ?: return null
            if (month !in 1..12 || day < 1 || day > MONTH_DAYS[month - 1]) return null
            return month * 100 + day
        }

        /** A span from its JSON: a `"MM-DD"` string or a `{ "from", "to" }` object; null when not understood. */
        fun of(element: JsonElement): DateSpan? = when (element) {
            is JsonPrimitive -> element.takeIf { it.isString }?.content?.let(::parseMonthDay)?.let { DateSpan(it, it) }
            is JsonObject -> {
                val from = (element["from"] as? JsonPrimitive)?.contentOrNull?.let(::parseMonthDay)
                val to = (element["to"] as? JsonPrimitive)?.contentOrNull?.let(::parseMonthDay)
                if (from != null && to != null) DateSpan(from, to) else null
            }
            else -> null
        }
    }
}

@Serializable
data class Haptic(val mode: String, val events: List<HapticEvent> = emptyList())

@Serializable
data class HapticEvent(
    val at: Long,
    val preset: String? = null,
    val scale: Double? = null,
    val initialSharpness: Double? = null,
    val envelope: List<ControlPoint>? = null,
) {
    /** Known duration: the sum of envelope transitions, 0 for presets (whose length depends on the device). */
    val durationMs: Long get() = envelope?.sumOf { it.ms } ?: 0L
}

@Serializable
data class ControlPoint(val intensity: Double, val sharpness: Double, val ms: Long)

@Serializable
data class BundleManifest(
    val formatVersion: Int,
    val type: String,
    val id: String,
    val name: String,
    val version: String,
    val author: String? = null,
    val license: String? = null,
    val homepage: String? = null,
    val description: String? = null,
    val cover: String? = null,
    val tags: List<String> = emptyList(),
    val packs: List<String>,
)

@Serializable
data class RepositoryIndex(
    val formatVersion: Int,
    val name: String,
    val description: String? = null,
    val homepage: String? = null,
    val generatedAt: String? = null,
    val packs: List<RepositoryEntry>,
)

@Serializable
data class RepositoryEntry(
    val id: String,
    val type: String,
    val packFormatVersion: Int? = null,
    val name: String,
    val version: String,
    val author: String? = null,
    val license: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val cover: String? = null,
    val characters: List<CharacterPreview>? = null,
    val packs: List<BundledPack>? = null,
    val sizeUnpacked: Long? = null,
    val download: Download,
)

@Serializable
data class CharacterPreview(
    val id: String,
    val name: String,
    val sprite: String? = null,
    val sample: String? = null,
    val seedColor: String? = null,
)

@Serializable
data class BundledPack(
    val id: String,
    val name: String,
    val version: String,
    val license: String? = null,
    val cover: String? = null,
    val characters: List<CharacterPreview>? = null,
)

@Serializable
data class Download(val url: String, val size: Long, val sha256: String)
