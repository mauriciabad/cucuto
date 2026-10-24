package app.mauri.cucuto.packspec.authoring

import app.mauri.cucuto.packspec.Character
import app.mauri.cucuto.packspec.Clip
import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.FileKind
import app.mauri.cucuto.packspec.Haptic
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.PackManifest
import app.mauri.cucuto.packspec.SpecJson

/**
 * A file a creator holds for the pack: [key] is opaque to the assembler (a local path in the app, a Blob ID on the
 * website), [extension] says what it is (`ogg`, `webp`, `png`…, without the dot).
 */
data class DraftFile(
    val key: String,
    val extension: String,
)

/** A pack being made in a creator. Blank optional texts are left out of the manifest. */
data class PackDraft(
    /** The pack ID; null to derive it with [Authoring.suggestPackId] from [author], [name] and [idSuffix]. */
    val id: String?,
    val idSuffix: String,
    val name: String,
    val version: String = Authoring.FIRST_VERSION,
    val author: String,
    val license: String,
    val description: String? = null,
    val cover: DraftFile? = null,
    val characters: List<CharacterDraft>,
)

data class CharacterDraft(
    /** The character ID; null to derive a unique slug from [name]. Fix it once the pack has been shared. */
    val id: String?,
    val name: String,
    val sprite: DraftFile?,
    /** `#RRGGBB`, or null to let apps extract one from the sprite. */
    val seedColor: String?,
    val clips: List<ClipDraft>,
    /** Null for the format's default (`auto`). */
    val haptic: Haptic? = null,
)

data class ClipDraft(
    val file: DraftFile,
    val label: String?,
    /** Null for the default weight (1). */
    val weight: Double? = null,
)

/** Something a draft still needs before it can become a pack. [character] is the index of the character concerned. */
data class DraftProblem(
    val kind: Kind,
    val character: Int? = null,
) {
    enum class Kind {
        PACK_NAME_MISSING,
        AUTHOR_MISSING,
        LICENSE_MISSING,
        NO_CHARACTERS,
        TOO_MANY_CHARACTERS,
        CHARACTER_NAME_MISSING,
        SPRITE_MISSING,
        NO_CLIPS,
        TOO_MANY_CLIPS,
        UNSUPPORTED_FILE,
    }
}

/** One file of an assembled pack: its [path] in the archive and the [key] of the draft file that fills it. */
data class AssembledFile(
    val path: String,
    val key: String,
)

/** A pack ready to write: its manifest, the manifest's bytes and the files to put next to it. */
class AssembledPack(
    val manifest: PackManifest,
    val files: List<AssembledFile>,
) {
    /** `pack.json` as written by every creator: pretty-printed UTF-8 without a byte order mark, newline at the end. */
    val manifestJson: String = SpecJson.encodeToString(PackManifest.serializer(), manifest) + "\n"

    /** The archive's entries in the order creators write them: `pack.json` first, then the files. */
    val paths: List<String> get() = listOf(MANIFEST) + files.map { it.path }

    companion object {
        const val MANIFEST = "pack.json"
    }
}

/**
 * Turns a [PackDraft] into the canonical layout of a pack (the same as `packer build`):
 *
 * ```
 * pack.json
 * cover.<ext>                          (optional)
 * characters/<character id>/sprite.<ext>
 * characters/<character id>/clips/<label slug>.<ext>
 * ```
 *
 * Character IDs and clip file names are slugs of the names and labels, made unique with `-2`, `-3`…. The result still
 * goes through [app.mauri.cucuto.packspec.PackValidator] (every rule, media included) before anyone gets the file.
 */
object PackAssembler {
    /** What [draft] still needs; empty when [assemble] can run. */
    fun problems(draft: PackDraft): List<DraftProblem> =
        buildList {
            if (draft.name.isBlank()) add(DraftProblem(DraftProblem.Kind.PACK_NAME_MISSING))
            if (draft.author.isBlank()) add(DraftProblem(DraftProblem.Kind.AUTHOR_MISSING))
            if (draft.license.isBlank()) add(DraftProblem(DraftProblem.Kind.LICENSE_MISSING))
            if (draft.cover != null && !isImage(draft.cover)) add(DraftProblem(DraftProblem.Kind.UNSUPPORTED_FILE))
            if (draft.characters.isEmpty()) add(DraftProblem(DraftProblem.Kind.NO_CHARACTERS))
            if (draft.characters.size > MAX_CHARACTERS) add(DraftProblem(DraftProblem.Kind.TOO_MANY_CHARACTERS))
            draft.characters.forEachIndexed { i, c ->
                if (c.name.isBlank()) add(DraftProblem(DraftProblem.Kind.CHARACTER_NAME_MISSING, i))
                if (c.sprite == null) add(DraftProblem(DraftProblem.Kind.SPRITE_MISSING, i))
                if (c.clips.isEmpty()) add(DraftProblem(DraftProblem.Kind.NO_CLIPS, i))
                if (c.clips.size > MAX_CLIPS) add(DraftProblem(DraftProblem.Kind.TOO_MANY_CLIPS, i))
                val badSprite = c.sprite != null && !isImage(c.sprite)
                if (badSprite || c.clips.any { !isAudio(it.file) }) {
                    add(DraftProblem(DraftProblem.Kind.UNSUPPORTED_FILE, i))
                }
            }
        }

    /** The pack for [draft]. Requires [problems] to be empty. */
    fun assemble(draft: PackDraft): AssembledPack {
        require(problems(draft).isEmpty()) { "The draft is incomplete: ${problems(draft)}" }
        val files = mutableListOf<AssembledFile>()
        val cover =
            draft.cover?.let {
                val path = "cover.${it.extension.lowercase()}"
                files += AssembledFile(path, it.key)
                path
            }
        val characterIds = mutableSetOf<String>()
        // Fixed IDs first, so a derived ID never takes the one a shared character already has.
        draft.characters.mapNotNull { it.id }.forEach { characterIds += it }
        val characters =
            draft.characters.map { c ->
                val id =
                    c.id ?: Authoring
                        .uniqueSlug(Authoring.slug(c.name), characterIds, Authoring.FALLBACK_CHARACTER_SLUG)
                        .also { characterIds += it }
                val folder = "characters/$id"
                val sprite = "$folder/sprite.${c.sprite!!.extension.lowercase()}"
                files += AssembledFile(sprite, c.sprite.key)
                val names = mutableSetOf<String>()
                val clips =
                    c.clips.map { clip ->
                        val label = clip.label?.trim()?.takeIf { it.isNotEmpty() }
                        val ext = clip.file.extension.lowercase()
                        val base =
                            Authoring.uniqueSlug(
                                Authoring.slug(label.orEmpty(), MAX_CLIP_SLUG),
                                names,
                                Authoring.FALLBACK_CLIP_SLUG,
                            )
                        names += base
                        val path = "$folder/clips/$base.$ext"
                        files += AssembledFile(path, clip.file.key)
                        Clip(file = path, label = label?.take(MAX_LABEL), weight = clip.weight?.takeIf { it != 1.0 })
                    }
                Character(
                    id = id,
                    name = c.name.trim().take(MAX_NAME),
                    sprite = sprite,
                    seedColor = c.seedColor,
                    clips = clips,
                    haptic = c.haptic?.takeIf { it.mode != "auto" },
                )
            }
        val manifest =
            PackManifest(
                formatVersion = Limits.FORMAT_VERSION,
                id = draft.id ?: Authoring.suggestPackId(draft.author, draft.name, draft.idSuffix),
                name = draft.name.trim().take(MAX_NAME),
                version = draft.version,
                author = draft.author.trim().take(MAX_AUTHOR),
                license = draft.license.trim(),
                description = draft.description?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_DESCRIPTION),
                cover = cover,
                characters = characters,
            )
        return AssembledPack(manifest, files)
    }

    private fun isImage(file: DraftFile) = FileFormat.of("f.${file.extension}")?.kind == FileKind.IMAGE

    private fun isAudio(file: DraftFile) = FileFormat.of("f.${file.extension}")?.kind == FileKind.AUDIO

    private const val MAX_CHARACTERS = 500
    private const val MAX_CLIPS = 200
    private const val MAX_NAME = 80
    private const val MAX_AUTHOR = 120
    private const val MAX_DESCRIPTION = 2_000
    private const val MAX_LABEL = 80

    /** Room for `-NN.ext` inside the 255-character path limit. */
    private const val MAX_CLIP_SLUG = 48
}
