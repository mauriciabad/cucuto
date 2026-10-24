@file:OptIn(ExperimentalJsExport::class)

package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.authoring.BundleAssembler
import app.mauri.cucuto.packspec.authoring.BundleDraft
import app.mauri.cucuto.packspec.authoring.HapticPatternText
import app.mauri.cucuto.packspec.authoring.RepositoryIndexer
import app.mauri.cucuto.packspec.zip.ByteArrayInput
import app.mauri.cucuto.packspec.zip.CommonInflater
import app.mauri.cucuto.packspec.zip.ZipArchive
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The browser API of the creators' tools beyond single packs (#191): bundles (`packer bundle`), the repository helper
// (`packer index`) and the haptic pattern text ("Copy for AI" and "Paste pattern", shared with the app's haptic
// editor). Same conventions as AuthoringJs.kt: JSON in and out for structured data, no Long values.

@Serializable
private data class BundleDto(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val author: String? = null,
    val license: String? = null,
    val homepage: String? = null,
    val description: String? = null,
    val cover: FileDto? = null,
    val tags: List<String> = emptyList(),
    val packIds: List<String> = emptyList(),
)

/**
 * The result of [assembleBundle]. When [problems] is empty, [manifestJson] is `bundle.json`, [roots] the folder of each
 * pack (`packs/<pack id>`, in order) and [coverPath] the cover's place; otherwise those are null and empty.
 */
@JsExport
class BundleAssemblyJs internal constructor(
    /** Kinds of `BundleProblem`, for example `BUNDLE_ID_INVALID`. */
    val problems: Array<String>,
    val manifestJson: String?,
    val roots: Array<String>,
    val coverPath: String?,
    val fileName: String?,
)

/** Lays out a bundle from a draft given as JSON (`BundleDraftInput` in web/src/lib/pack-spec/core.ts). */
@JsExport
fun assembleBundle(draft: String): BundleAssemblyJs {
    val dto = draftJson.decodeFromString(BundleDto.serializer(), draft)
    val bundle =
        BundleDraft(
            id = dto.id,
            name = dto.name,
            version = dto.version,
            author = dto.author,
            license = dto.license,
            homepage = dto.homepage,
            description = dto.description,
            cover = dto.cover?.toDraft(),
            tags = dto.tags,
            packIds = dto.packIds,
        )
    val problems = BundleAssembler.problems(bundle)
    if (problems.isNotEmpty()) {
        return BundleAssemblyJs(problems.map { it.name }.toTypedArray(), null, emptyArray(), null, null)
    }
    val assembled = BundleAssembler.assemble(bundle)
    return BundleAssemblyJs(
        problems = emptyArray(),
        manifestJson = assembled.manifestJson,
        roots = assembled.roots.toTypedArray(),
        coverPath = assembled.coverPath,
        fileName = BundleAssembler.fileName(bundle),
    )
}

/** Whether [id] is a valid pack or bundle ID (`docs/pack-format.md#ids`). */
@JsExport
fun isPackId(id: String): Boolean = BundleAssembler.isPackId(id)

/**
 * One archive indexed by [repositoryEntry]. When the archive is valid, [entryJson] is its `repository.json` entry and
 * [previewPaths] / [previewSources] list the preview files to copy (folder path, and path inside the archive).
 */
@JsExport
class RepositoryEntryJs internal constructor(
    val result: PackSpecResult,
    val entryJson: String?,
    val previewPaths: Array<String>,
    val previewSources: Array<String>,
)

/**
 * Validates a pack or bundle archive and makes its repository entry, exactly as `packer index` does: previews under
 * `previews/<id>/`, the download at [downloadUrl] (see [repositoryDownloadUrl]) with the archive's size and [sha256]
 * (lowercase hex, computed by the page with Web Crypto).
 */
@JsExport
fun repositoryEntry(
    bytes: ByteArray,
    downloadUrl: String,
    sha256: String,
): RepositoryEntryJs {
    val validation = PackValidator().validateArchive(ByteArrayInput(bytes), CommonInflater)
    val result = validation.toResult()
    val content = validation.content
    if (!validation.report.isValid || content == null) return RepositoryEntryJs(result, null, emptyArray(), emptyArray())
    val zip = ZipArchive.open(ByteArrayInput(bytes), CommonInflater).source!!
    val indexed =
        RepositoryIndexer.entry(content, zip.entries.sumOf { it.size }, Download(downloadUrl, bytes.size.toLong(), sha256))
    return RepositoryEntryJs(
        result = result,
        entryJson = outJson.encodeToString(RepositoryEntry.serializer(), indexed.entry),
        previewPaths = indexed.previews.map { it.path }.toTypedArray(),
        previewSources = indexed.previews.map { it.archivePath }.toTypedArray(),
    )
}

/** Where a pack file is downloaded from: `packs/<file>` in the folder, or `<baseUrl>/<file>`. */
@JsExport
fun repositoryDownloadUrl(
    fileName: String,
    baseUrl: String?,
): String = RepositoryIndexer.downloadUrl(fileName, baseUrl)

/**
 * `repository.json` for [entries] (each one from [repositoryEntry]): one entry per ID (the highest version), sorted by
 * ID, then checked with the repository schema; [RepositoryIndexJs.json] is null when the check fails.
 */
@JsExport
class RepositoryIndexJs internal constructor(
    val json: String?,
    val result: PackSpecResult,
)

@JsExport
fun repositoryIndex(
    name: String,
    description: String?,
    homepage: String?,
    generatedAt: String?,
    entries: Array<String>,
): RepositoryIndexJs {
    val parsed = entries.map { draftJson.decodeFromString(RepositoryEntry.serializer(), it) }
    val json = RepositoryIndexer.json(RepositoryIndexer.index(name, description, homepage, generatedAt, parsed))
    val result = validateDocument("repository", json.encodeToByteArray())
    return RepositoryIndexJs(if (result.valid) json else null, result)
}

@Serializable
private data class PromptClipDto(
    val path: String,
    val durationMs: Double? = null,
    /** Rows of `[ms, loudness, sharpness]`, 0 to 100. */
    val loudness: List<List<Int>>? = null,
)

/**
 * "Copy for AI": the prompt for a character's clips ([clips]: JSON `[{path, durationMs, loudness}]`) and its current
 * pattern ([currentEventsJson]: the events as JSON, or null). Shared with the app's haptic editor.
 */
@JsExport
fun hapticPrompt(
    character: String?,
    clips: String,
    currentEventsJson: String?,
): String {
    val list = draftJson.decodeFromString(ListSerializer(PromptClipDto.serializer()), clips)
    // A pattern that does not decode (a half-typed timeline) goes into the prompt as typed, for the AI to fix.
    val current = currentEventsJson?.let { runCatching { hapticPatternJson(it) }.getOrDefault(it) }
    return HapticPatternText.prompt(
        character?.takeIf { it.isNotBlank() },
        list.map { c ->
            HapticPatternText.PromptClip(
                path = c.path,
                durationMs = c.durationMs?.toLong(),
                loudness =
                    c.loudness?.mapNotNull { row ->
                        if (row.size == 3) HapticPatternText.LoudnessRow(row[0], row[1], row[2]) else null
                    },
            )
        },
        current,
    )
}

/** Events (JSON array, as in `pack.json`) as the compact `{"mode":"custom","events":[…]}`; null input → empty. */
@JsExport
fun hapticPatternJson(eventsJson: String): String {
    val events = draftJson.decodeFromString(ListSerializer(HapticEvent.serializer()), eventsJson)
    return HapticPatternText.patternJson(events)
}

/**
 * The result of [parseHapticPattern]: [eventsJson] (the events as JSON, values as written) when the text holds a valid
 * pattern, else [problemJson]: `{"kind": "OutOfRange", "event": 1, "point": 2, "field": "scale", …}` with the fields of
 * `HapticPatternText.Problem` (kinds are its class names).
 */
@JsExport
class HapticParseJs internal constructor(
    val eventsJson: String?,
    val problemJson: String?,
)

/** "Paste pattern": the pattern in [text], checked with the pack format's haptic rules. */
@JsExport
fun parseHapticPattern(text: String): HapticParseJs =
    when (val parsed = HapticPatternText.parse(text)) {
        is HapticPatternText.Parse.Valid -> {
            HapticParseJs(outJson.encodeToString(ListSerializer(HapticEvent.serializer()), parsed.events), null)
        }

        is HapticPatternText.Parse.Invalid -> {
            HapticParseJs(null, problemJson(parsed.problem).toString())
        }
    }

private fun problemJson(problem: HapticPatternText.Problem): JsonObject =
    buildJsonObject {
        put("kind", kindOf(problem))
        fun spot(spot: HapticPatternText.Spot) {
            spot.event?.let { put("event", it) }
            spot.point?.let { put("point", it) }
        }
        when (problem) {
            HapticPatternText.Problem.NotJson,
            HapticPatternText.Problem.NoEvents,
            HapticPatternText.Problem.TooManyEvents,
            -> Unit

            is HapticPatternText.Problem.NotCustom -> {
                put("value", problem.mode)
            }

            is HapticPatternText.Problem.NotAnEvent -> {
                put("event", problem.event)
            }

            is HapticPatternText.Problem.MissingField -> {
                spot(problem.spot)
                put("field", problem.field)
            }

            is HapticPatternText.Problem.UnknownField -> {
                spot(problem.spot)
                put("field", problem.field)
            }

            is HapticPatternText.Problem.NotWholeNumber -> {
                spot(problem.spot)
                put("field", problem.field)
                put("value", problem.value)
            }

            is HapticPatternText.Problem.OutOfRange -> {
                spot(problem.spot)
                put("field", problem.field)
                put("min", problem.min)
                put("max", problem.max)
                put("value", problem.value)
            }

            is HapticPatternText.Problem.UnknownPreset -> {
                put("event", problem.event)
                put("value", problem.name)
            }

            is HapticPatternText.Problem.PointCount -> {
                put("event", problem.event)
                put("count", problem.count)
            }

            is HapticPatternText.Problem.NotClosed -> {
                put("event", problem.event)
            }

            is HapticPatternText.Problem.Unsorted -> {
                put("event", problem.event)
            }

            is HapticPatternText.Problem.Overlap -> {
                put("event", problem.event)
                put("ms", problem.atMs)
                put("until", problem.busyUntilMs)
            }

            is HapticPatternText.Problem.TooLong -> {
                put("ms", problem.ms)
            }
        }
    }

/** The class name of [problem], written out: class names are not guaranteed to survive minification. */
private fun kindOf(problem: HapticPatternText.Problem): String =
    when (problem) {
        HapticPatternText.Problem.NotJson -> "NotJson"
        HapticPatternText.Problem.NoEvents -> "NoEvents"
        HapticPatternText.Problem.TooManyEvents -> "TooManyEvents"
        is HapticPatternText.Problem.NotCustom -> "NotCustom"
        is HapticPatternText.Problem.NotAnEvent -> "NotAnEvent"
        is HapticPatternText.Problem.MissingField -> "MissingField"
        is HapticPatternText.Problem.UnknownField -> "UnknownField"
        is HapticPatternText.Problem.NotWholeNumber -> "NotWholeNumber"
        is HapticPatternText.Problem.OutOfRange -> "OutOfRange"
        is HapticPatternText.Problem.UnknownPreset -> "UnknownPreset"
        is HapticPatternText.Problem.PointCount -> "PointCount"
        is HapticPatternText.Problem.NotClosed -> "NotClosed"
        is HapticPatternText.Problem.Unsorted -> "Unsorted"
        is HapticPatternText.Problem.Overlap -> "Overlap"
        is HapticPatternText.Problem.TooLong -> "TooLong"
    }
