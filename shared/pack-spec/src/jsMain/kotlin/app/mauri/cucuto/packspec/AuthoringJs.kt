@file:OptIn(ExperimentalJsExport::class)

package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.authoring.Authoring
import app.mauri.cucuto.packspec.authoring.CharacterDraft
import app.mauri.cucuto.packspec.authoring.ClipDraft
import app.mauri.cucuto.packspec.authoring.ClipDsp
import app.mauri.cucuto.packspec.authoring.DraftFile
import app.mauri.cucuto.packspec.authoring.PackAssembler
import app.mauri.cucuto.packspec.authoring.PackDraft
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

@Serializable
private data class FileDto(val key: String, val extension: String)

@Serializable
private data class ClipDto(val file: FileDto, val label: String? = null, val weight: Double? = null)

@Serializable
private data class CharacterDto(
    val id: String? = null,
    val name: String,
    val sprite: FileDto? = null,
    val seedColor: String? = null,
    val clips: List<ClipDto> = emptyList(),
)

@Serializable
private data class DraftDto(
    val id: String? = null,
    val idSuffix: String,
    val name: String,
    val version: String = Authoring.FIRST_VERSION,
    val author: String,
    val license: String,
    val description: String? = null,
    val cover: FileDto? = null,
    val characters: List<CharacterDto> = emptyList(),
)

private val draftJson = Json { ignoreUnknownKeys = true }

private fun FileDto.toDraft() = DraftFile(key, extension)

/**
 * Lays out a pack from a draft given as JSON (the TypeScript `PackDraft` in web/src/lib/creator/): the canonical layout
 * of `PackAssembler`, shared with the app's creator and `packer build`.
 */
@JsExport
fun assemblePack(draft: String): AssemblyJs {
    val dto = draftJson.decodeFromString(DraftDto.serializer(), draft)
    val packDraft =
        PackDraft(
            id = dto.id,
            idSuffix = dto.idSuffix,
            name = dto.name,
            version = dto.version,
            author = dto.author,
            license = dto.license,
            description = dto.description,
            cover = dto.cover?.toDraft(),
            characters =
                dto.characters.map { c ->
                    CharacterDraft(
                        id = c.id,
                        name = c.name,
                        sprite = c.sprite?.toDraft(),
                        seedColor = c.seedColor,
                        clips = c.clips.map { ClipDraft(it.file.toDraft(), it.label, it.weight) },
                    )
                },
        )
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
    )

