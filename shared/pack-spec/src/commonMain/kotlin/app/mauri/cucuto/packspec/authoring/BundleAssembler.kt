package app.mauri.cucuto.packspec.authoring

import app.mauri.cucuto.packspec.BundleManifest
import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.FileKind
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.SemVer
import app.mauri.cucuto.packspec.SpecJson

/** A bundle being made from finished packs (`packer bundle`, the website's bundle tool). Blank texts are left out. */
data class BundleDraft(
    val id: String,
    val name: String,
    val version: String = Authoring.FIRST_VERSION,
    val author: String? = null,
    val license: String? = null,
    val homepage: String? = null,
    val description: String? = null,
    val cover: DraftFile? = null,
    val tags: List<String> = emptyList(),
    /** The ID of every pack to include, in order. */
    val packIds: List<String>,
)

/** Something a [BundleDraft] still needs. */
enum class BundleProblem {
    BUNDLE_ID_INVALID,
    BUNDLE_NAME_MISSING,
    VERSION_INVALID,
    NO_PACKS,
    TOO_MANY_PACKS,

    /** Two packs with the same ID: they would share a folder. */
    PACK_TWICE,

    /** The bundle ID is one of its packs' IDs; the format asks to keep them distinct (repository updates). */
    ID_CLASH,
    UNSUPPORTED_FILE,
}

/** A bundle ready to write: `bundle.json` and where each pack and the cover go. */
class AssembledBundle(
    val manifest: BundleManifest,
    /** The folder of each pack, in the order of [BundleDraft.packIds]: `packs/<pack id>`. */
    val roots: List<String>,
    /** `cover.<ext>`, or null. */
    val coverPath: String?,
) {
    /** `bundle.json` as written by every tool: pretty-printed UTF-8, newline at the end. */
    val manifestJson: String = SpecJson.encodeToString(BundleManifest.serializer(), manifest) + "\n"

    companion object {
        const val MANIFEST = "bundle.json"
    }
}

/**
 * Turns a [BundleDraft] into the canonical bundle layout, the same as `packer bundle`:
 *
 * ```
 * bundle.json
 * cover.<ext>                (optional)
 * packs/<pack id>/pack.json  (each pack's files, as in its own archive)
 * ```
 *
 * The result still goes through [app.mauri.cucuto.packspec.PackValidator] with every pack inside.
 */
object BundleAssembler {
    /** What [draft] still needs; empty when [assemble] can run. */
    fun problems(draft: BundleDraft): List<BundleProblem> =
        buildList {
            if (!isPackId(draft.id)) add(BundleProblem.BUNDLE_ID_INVALID)
            if (draft.name.isBlank()) add(BundleProblem.BUNDLE_NAME_MISSING)
            if (SemVer.parse(draft.version.trim()) == null) add(BundleProblem.VERSION_INVALID)
            if (draft.packIds.isEmpty()) add(BundleProblem.NO_PACKS)
            if (draft.packIds.size > MAX_PACKS) add(BundleProblem.TOO_MANY_PACKS)
            if (draft.packIds.toSet().size != draft.packIds.size) add(BundleProblem.PACK_TWICE)
            if (draft.id.trim() in draft.packIds) add(BundleProblem.ID_CLASH)
            if (draft.cover != null && FileFormat.of("f.${draft.cover.extension}")?.kind != FileKind.IMAGE) {
                add(BundleProblem.UNSUPPORTED_FILE)
            }
        }

    /** The bundle for [draft]. Requires [problems] to be empty. */
    fun assemble(draft: BundleDraft): AssembledBundle {
        require(problems(draft).isEmpty()) { "The bundle is incomplete: ${problems(draft)}" }
        val roots = draft.packIds.map { "$PACKS_FOLDER/$it" }
        val cover = draft.cover?.let { "cover.${it.extension.lowercase()}" }
        fun text(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() }
        val manifest =
            BundleManifest(
                formatVersion = Limits.FORMAT_VERSION,
                type = "bundle",
                id = draft.id.trim(),
                name = draft.name.trim().take(MAX_NAME),
                version = draft.version.trim(),
                author = text(draft.author)?.take(MAX_AUTHOR),
                license = text(draft.license),
                homepage = text(draft.homepage),
                description = text(draft.description)?.take(MAX_DESCRIPTION),
                cover = cover,
                tags = draft.tags,
                packs = roots,
            )
        return AssembledBundle(manifest, roots, cover)
    }

    /** The file name to suggest: `<name slug>.soundpack.zip`, or the last label of the ID (ADR 0007). */
    fun fileName(draft: BundleDraft): String = Authoring.packFileName(draft.name, draft.id)

    /** A lowercase reverse-domain ID with at least two labels, 3 to 128 characters (`docs/pack-format.md#ids`). */
    fun isPackId(id: String): Boolean = id.length in 3..128 && PACK_ID.matches(id)

    /** Where each pack goes inside a bundle. */
    const val PACKS_FOLDER = "packs"

    private val PACK_ID = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
    private const val MAX_PACKS = 100
    private const val MAX_NAME = 80
    private const val MAX_AUTHOR = 120
    private const val MAX_DESCRIPTION = 2_000
}
