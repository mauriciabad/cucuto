package app.mauri.cucuto.packspec.authoring

import app.mauri.cucuto.packspec.PackManifest
import kotlin.random.Random

/**
 * Opening an existing pack in a creator: its manifest as a [PackDraft] that [PackAssembler] writes back with nothing
 * the format allows lost. The pass-through rules are those of the app's "Duplicate and edit" (#225, ADR 0049): the
 * characters' IDs, names, descriptions, tags, sprites and animated sprites, colors, clips with their labels, weights
 * and conditions, ringtone variants with all their settings, and haptics; the pack's cover, homepage and tags. Unknown
 * `x-` keys are not kept. Draft file keys are the manifest's paths (relative to the pack's root), so a creator reads
 * each file from the archive by its key.
 */
object PackEditing {
    /**
     * [manifest] as a draft that keeps its ID and version: what a creator saves as an update after
     * [Authoring.versionAfter]. Set `id` to null (and pick a version) to save a copy that installs next to the original.
     */
    fun draftOf(
        manifest: PackManifest,
        random: Random = Random.Default,
    ): PackDraft {
        fun file(path: String) = DraftFile(path, path.substringAfterLast('.', "").lowercase())
        return PackDraft(
            id = manifest.id,
            idSuffix = Authoring.randomIdSuffix(random),
            name = manifest.name,
            version = manifest.version,
            author = manifest.author,
            license = manifest.license,
            description = manifest.description,
            cover = manifest.cover?.let(::file),
            homepage = manifest.homepage,
            tags = manifest.tags,
            characters =
                manifest.characters.map { c ->
                    CharacterDraft(
                        id = c.id,
                        name = c.name,
                        sprite = file(c.sprite),
                        seedColor = c.seedColor,
                        clips = c.clips.map { ClipDraft(file(it.file), it.label, it.weight, it.`when`) },
                        haptic = c.haptic?.takeIf { it.mode != "auto" },
                        description = c.description,
                        tags = c.tags,
                        spriteAnimated = c.spriteAnimated?.let(::file),
                        ringtones = c.ringtones.map { RingtoneDraft(file(it.file), it) },
                    )
                },
        )
    }
}
