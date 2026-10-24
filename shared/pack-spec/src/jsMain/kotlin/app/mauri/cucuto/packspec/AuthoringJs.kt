@file:OptIn(ExperimentalJsExport::class)

package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.authoring.Authoring
import app.mauri.cucuto.packspec.authoring.CharacterDraft
import app.mauri.cucuto.packspec.authoring.ClipDraft
import app.mauri.cucuto.packspec.authoring.ClipDsp
import app.mauri.cucuto.packspec.authoring.DraftFile
import app.mauri.cucuto.packspec.authoring.HapticPatternText
import app.mauri.cucuto.packspec.authoring.PackAssembler
import app.mauri.cucuto.packspec.authoring.PackDraft
import app.mauri.cucuto.packspec.authoring.PackEditing
import app.mauri.cucuto.packspec.authoring.RingtoneDraft
import app.mauri.cucuto.packspec.zip.ByteArrayInput
import app.mauri.cucuto.packspec.zip.CommonInflater
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The browser API of the pack creators' shared conventions (#48, ADR 0038): the website's Create page lays out packs,
// names files and processes clips with the same Kotlin as the app's creator (#47), so both write the same packs.
// Like PackSpecJs.kt: a small surface, no Long values, FloatArray is a JavaScript Float32Array (no copy).

/** One file of an assembled pack: its [path] in the archive and the [key] of the draft file that fills it. */
@JsExport
class AssembledFileJs internal constructor(
    val path: String,
    val key: String,
)

/**
 * The result of [assemblePack]. When [problems] is empty, [manifestJson] is `pack.json` exactly as every creator writes
 * it and [files] lists the other entries in archive order; otherwise those are null and empty.
 */
@JsExport
class AssemblyJs internal constructor(
    /** `KIND` or `KIND:<character index>`, kinds as in `DraftProblem.Kind` (for example `SPRITE_MISSING:0`). */
    val problems: Array<String>,
    val manifestJson: String?,
    val packId: String?,
    /** The file name to suggest: `<pack name slug>.soundpack.zip`. */
    val fileName: String?,
    val files: Array<AssembledFileJs>,
)

/** A processed clip ([ClipDsp.process]): mono 48 kHz floats, ready to encode. */
@JsExport
class ProcessedClipJs internal constructor(
    val samples: FloatArray,
    val sampleRate: Int,
    val gainDb: Double,
    /** Integrated loudness in LUFS; NaN for silence. */
    val loudnessLufs: Double,
    val silent: Boolean,
    val durationMs: Double,
)

/**
 * A draft as JSON, both ways (the TypeScript `PackDraftInput` in web/src/lib/pack-spec/core.ts). Everything after
 * `characters` is pass-through data a creator keeps from an opened pack (#191, the rules of `PackEditing`).
 */
@Serializable
internal data class FileDto(val key: String, val extension: String)

@Serializable
internal data class ClipDto(
    val file: FileDto,
    val label: String? = null,
    val weight: Double? = null,
    val `when`: When? = null,
)

@Serializable
internal data class RingtoneDto(val file: FileDto, val entry: Ringtone)

@Serializable
internal data class CharacterDto(
    val id: String? = null,
    val name: String,
    val sprite: FileDto? = null,
    val seedColor: String? = null,
    val clips: List<ClipDto> = emptyList(),
    val haptic: Haptic? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val spriteAnimated: FileDto? = null,
    val ringtones: List<RingtoneDto> = emptyList(),
)

@Serializable
internal data class DraftDto(
    val id: String? = null,
    val idSuffix: String,
    val name: String,
    val version: String = Authoring.FIRST_VERSION,
    val author: String,
    val license: String,
    val description: String? = null,
    val cover: FileDto? = null,
    val characters: List<CharacterDto> = emptyList(),
    val homepage: String? = null,
    val tags: List<String> = emptyList(),
)

/** Reads what the website sends: unknown keys (UI-only state) are ignored. */
internal val draftJson = Json { ignoreUnknownKeys = true }

/** Writes what the website reads: every field, nulls left out. */
internal val outJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

internal fun FileDto.toDraft() = DraftFile(key, extension)

private fun DraftFile.toDto(root: String) = FileDto(PackPaths.join(root, key), extension)

private fun DraftDto.toDraft() =
    PackDraft(
        id = id,
        idSuffix = idSuffix,
        name = name,
        version = version,
        author = author,
        license = license,
        description = description,
        cover = cover?.toDraft(),
        homepage = homepage,
        tags = tags,
        characters =
            characters.map { c ->
                CharacterDraft(
                    id = c.id,
                    name = c.name,
                    sprite = c.sprite?.toDraft(),
                    seedColor = c.seedColor,
                    clips = c.clips.map { ClipDraft(it.file.toDraft(), it.label, it.weight, it.`when`) },
                    haptic = c.haptic,
                    description = c.description,
                    tags = c.tags,
                    spriteAnimated = c.spriteAnimated?.toDraft(),
                    ringtones = c.ringtones.map { RingtoneDraft(it.file.toDraft(), it.entry) },
                )
            },
    )

/** [draft] as JSON, with every file key prefixed by [root] (the pack's folder inside a bundle, else ""). */
private fun draftDto(
    draft: PackDraft,
    root: String,
) = DraftDto(
    id = draft.id,
    idSuffix = draft.idSuffix,
    name = draft.name,
    version = draft.version,
    author = draft.author,
    license = draft.license,
    description = draft.description,
    cover = draft.cover?.toDto(root),
    homepage = draft.homepage,
    tags = draft.tags,
    characters =
        draft.characters.map { c ->
            CharacterDto(
                id = c.id,
                name = c.name,
                sprite = c.sprite?.toDto(root),
                seedColor = c.seedColor,
                clips = c.clips.map { ClipDto(it.file.toDto(root), it.label, it.weight, it.`when`) },
                haptic = c.haptic,
                description = c.description,
                tags = c.tags,
                spriteAnimated = c.spriteAnimated?.toDto(root),
                ringtones = c.ringtones.map { RingtoneDto(it.file.toDto(root), it.entry) },
            )
        },
)

/**
 * Lays out a pack from a draft given as JSON (the TypeScript `PackDraftInput` in web/src/lib/pack-spec/): the
 * canonical layout of `PackAssembler`, shared with the app's creator and `packer build`.
 */
@JsExport
fun assemblePack(draft: String): AssemblyJs {
    val packDraft = draftJson.decodeFromString(DraftDto.serializer(), draft).toDraft()
    val problems = PackAssembler.problems(packDraft)
    if (problems.isNotEmpty()) {
        val codes = problems.map { p -> p.character?.let { "${p.kind.name}:$it" } ?: p.kind.name }
        return AssemblyJs(codes.toTypedArray(), null, null, null, emptyArray())
    }
    val pack = PackAssembler.assemble(packDraft)
    return AssemblyJs(
        problems = emptyArray(),
        manifestJson = pack.manifestJson,
        packId = pack.manifest.id,
        fileName = packFileName(pack.manifest.name, pack.manifest.id),
        files = pack.files.map { AssembledFileJs(it.path, it.key) }.toTypedArray(),
    )
}

/**
 * An archive opened for editing ([openPack]). [result] is the full validation. When the archive is a valid pack (or
 * a valid bundle and a pack was chosen), [draftJson] is the draft (`PackDraftInput`), whose file keys are paths in the
 * archive, and [durationsJson] maps audio paths to their length in milliseconds. [roots] lists a bundle's pack folders.
 */
@JsExport
class OpenedPackJs internal constructor(
    val result: PackSpecResult,
    val roots: Array<String>,
    val draftJson: String?,
    val durationsJson: String?,
)

/**
 * Opens a pack archive for editing in the website's creator (#191): validates it with every rule, then turns the pack
 * (or the bundle's pack in folder [root]) into a draft with `PackEditing`, the same pass-through rules as the app's
 * "Duplicate and edit". A bundle without [root] returns only its [OpenedPackJs.roots].
 */
@JsExport
fun openPack(
    bytes: ByteArray,
    root: String?,
): OpenedPackJs {
    val validation = PackValidator().validateArchive(ByteArrayInput(bytes), CommonInflater)
    val result = validation.toResult()
    val content = validation.content
    if (!validation.report.isValid || content == null) return OpenedPackJs(result, emptyArray(), null, null)
    val roots = content.packs.map { it.root }.toTypedArray()
    val pack =
        when (content) {
            is PackContent.Single -> content.pack
            is PackContent.Bundle -> content.packs.firstOrNull { it.root == root }
        } ?: return OpenedPackJs(result, roots, null, null)
    val draft = PackEditing.draftOf(pack.manifest)
    val durations = buildJsonObject { validation.durationsMs.forEach { (path, ms) -> put(path, ms.toDouble()) } }
    return OpenedPackJs(
        result = result,
        roots = roots,
        draftJson = outJson.encodeToString(DraftDto.serializer(), draftDto(draft, pack.root)),
        durationsJson = durations.toString(),
    )
}

/**
 * The version to write so an edited pack installs over [installed] as an update (`Authoring.versionAfter`): [current]
 * when it is already higher, else one patch above [installed].
 */
@JsExport
fun versionAfter(
    current: String,
    installed: String?,
): String = Authoring.versionAfter(current, installed)

/** `<pack name slug>.soundpack.zip`, or the last label of the ID when the name has no usable letters (as the app). */
@JsExport
fun packFileName(
    name: String,
    id: String,
): String = Authoring.packFileName(name, id)

/** The ID a creator suggests for a new pack: `local.<author>.<name>-<suffix>`. */
@JsExport
fun suggestPackId(
    author: String,
    name: String,
    suffix: String,
): String = Authoring.suggestPackId(author, name, suffix)

/** A random suffix for [suggestPackId]; pick it once per pack. */
@JsExport
fun randomIdSuffix(): String = Authoring.randomIdSuffix()

/** The license presets the creators offer first (`CC0-1.0`, `CC-BY-4.0`). */
@JsExport
fun licensePresets(): Array<String> = Authoring.LICENSE_PRESETS.toTypedArray()

/** The license a new pack starts with (`Personal use only`: free text, a warning, never an error). */
@JsExport
fun personalUseLicense(): String = Authoring.PERSONAL_USE_LICENSE

/**
 * Cuts, trims, normalizes and fades `[startMs, endMs)` of [samples] (mono floats at 48 kHz, see
 * [Authoring.Audio.SAMPLE_RATE_HZ]); [samples] is not changed.
 */
@JsExport
fun processClip(
    samples: FloatArray,
    startMs: Double,
    endMs: Double,
    trimSilence: Boolean,
    normalize: Boolean,
): ProcessedClipJs {
    val clip = ClipDsp.process(samples, startMs.toLong(), endMs.toLong(), trimSilence, normalize)
    return ProcessedClipJs(
        samples = clip.samples,
        sampleRate = Authoring.Audio.SAMPLE_RATE_HZ,
        gainDb = clip.gainDb,
        loudnessLufs = clip.loudnessLufs ?: Double.NaN,
        silent = clip.isSilent,
        durationMs = clip.durationMs.toDouble(),
    )
}

/**
 * The hook of a song for a notification clip of [lengthMs] (the shared auto-cut heuristic, [ClipCuts.findHook]):
 * `[startMs, endMs]`.
 */
@JsExport
fun findHook(
    samples: FloatArray,
    sampleRate: Int,
    lengthMs: Double,
): Array<Double> {
    val cut = ClipCuts.findHook(samples, sampleRate, lengthMs.toLong())
    return arrayOf(cut.startMs.toDouble(), cut.endMs.toDouble())
}

/** The creators' targets, for the browser UI: sample rate, bitrate, sprite size and clip limits. */
@JsExport
class AuthoringTargetsJs internal constructor(
    val sampleRate: Int,
    val channels: Int,
    val clipBitrate: Int,
    val spriteSize: Int,
    val clipMinMs: Double,
    val clipMaxMs: Double,
    val clipHintMs: Double,
    val maxSourceMs: Double,
    val hookLengthMs: Double,
    val hapticMaxMs: Int,
    val hapticMaxEvents: Int,
    val hapticMaxPoints: Int,
)

@JsExport
fun authoringTargets(): AuthoringTargetsJs =
    AuthoringTargetsJs(
        sampleRate = Authoring.Audio.SAMPLE_RATE_HZ,
        channels = Authoring.Audio.CHANNELS,
        clipBitrate = Authoring.Audio.CLIP_BITRATE_BPS,
        spriteSize = Authoring.Image.SPRITE_SIZE_PX,
        clipMinMs = Limits.CLIP_MIN_MS.toDouble(),
        clipMaxMs = Limits.CLIP_MAX_MS.toDouble(),
        clipHintMs = Limits.LONG_CLIP_HINT_MS.toDouble(),
        maxSourceMs = Authoring.Audio.MAX_SOURCE_MS.toDouble(),
        hookLengthMs = Limits.CUT_CLIP_DEFAULT_MS.toDouble(),
        hapticMaxMs = HapticPatternText.MAX_MS,
        hapticMaxEvents = HapticPatternText.MAX_EVENTS,
        hapticMaxPoints = HapticPatternText.MAX_POINTS,
    )

