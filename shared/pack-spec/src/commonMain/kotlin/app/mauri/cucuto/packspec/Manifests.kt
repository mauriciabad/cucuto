package app.mauri.cucuto.packspec

import kotlinx.serialization.SerialName
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
data class Clip(val file: String, val label: String? = null, val weight: Double? = null, val `when`: When? = null)

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
data class When(val time: JsonElement? = null, val season: JsonElement? = null, val weekday: JsonElement? = null) {
    /** "day", "night", or "HH:MM-HH:MM" for a range. */
    val timeRange: Pair<String, String>?
        get() = (time as? JsonObject)?.let {
            val from = (it["from"] as? JsonPrimitive)?.contentOrNull
            val to = (it["to"] as? JsonPrimitive)?.contentOrNull
            if (from != null && to != null) from to to else null
        }

    val seasons: List<String> get() = stringOrList(season)
    val weekdays: List<String> get() = stringOrList(weekday)

    private fun stringOrList(e: JsonElement?): List<String> = when (e) {
        null -> emptyList()
        is JsonPrimitive -> listOfNotNull(e.contentOrNull)
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
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

/**
 * One character of a repository entry. [haptic] is the character's `haptic` from `pack.json` (`x-haptic` in the index,
 * an extension key so that older apps accept it): null when the index does not say, which apps treat as "sound only".
 */
@Serializable
data class CharacterPreview(
    val id: String,
    val name: String,
    val sprite: String? = null,
    val sample: String? = null,
    val seedColor: String? = null,
    @SerialName("x-haptic") val haptic: Haptic? = null,
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
