package app.mauri.cucuto.core.creator

import app.mauri.cucuto.core.pack.PackStore
import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.FileInput
import app.mauri.cucuto.packspec.authoring.Authoring
import app.mauri.cucuto.packspec.media.AudioProbe
import java.io.File
import java.io.IOException

/** What [PackDuplicator.duplicate] made. */
data class Duplicate(
    val draft: CreatorDraft,
    /** Ringtone variants and animated sprites kept as they are: the creator does not edit them yet. */
    val keptRingtones: Int,
    val keptAnimations: Int,
)

/**
 * "Duplicate and edit" (brief: packs installed from a repository can be duplicated and edited; the original stays
 * untouched): copies the active version of an installed pack into a new draft. The copy gets a new pack ID (so it
 * installs next to the original), version 1.0.0, and keeps everything the format lets a pack carry: the characters'
 * IDs, names, descriptions, tags, sprites and animated sprites, colors, clips with their labels, weights and
 * conditions, ringtone variants with all their settings, and haptics; the pack's homepage and tags. Files are copied
 * as they are, in whatever format the pack uses; a file several entries share is copied once. Unknown `x-` keys are
 * not kept.
 */
class PackDuplicator(
    private val packs: PackStore,
    private val drafts: DraftStore,
) {
    /**
     * Replaces the current draft with a copy of [packId], named [name] (for example "Birds (copy)", localized by the
     * caller). Blocking IO.
     *
     * @throws IOException when the pack is not installed or its files cannot be read.
     */
    fun duplicate(
        packId: String,
        name: (String) -> String,
    ): Duplicate {
        val folder = packs.activePackDir(packId) ?: throw IOException("$packId is not installed")
        val manifest = packs.activeManifest(packId) ?: throw IOException("$packId has no readable manifest")
        drafts.clear()

        val copied = mutableMapOf<String, String>()

        fun copy(
            path: String,
            prefix: String,
        ): String =
            copied.getOrPut(path) {
                val source = File(folder, path)
                val ext = path.substringAfterLast('.', "").lowercase()
                val target = drafts.newMediaFile(prefix, ext)
                source.copyTo(target, overwrite = true)
                target.name
            }

        val characters =
            manifest.characters.map { c ->
                DraftCharacter(
                    id = c.id,
                    name = c.name,
                    description = c.description,
                    tags = c.tags,
                    sprite = copy(c.sprite, "sprite"),
                    spriteAnimated = c.spriteAnimated?.let { copy(it, "sprite-anim") },
                    seedColor = c.seedColor,
                    clips =
                        c.clips.map { clip ->
                            val file = copy(clip.file, "clip")
                            DraftClip(
                                file = file,
                                label = clip.label.orEmpty(),
                                weight = clip.weight ?: 1.0,
                                durationMs = durationMs(drafts.media(file)),
                                `when` = clip.`when`,
                                oneIn = clip.oneIn,
                            )
                        },
                    ringtones = c.ringtones.map { DraftRingtone(copy(it.file, "ringtone"), it) },
                    haptic =
                        when (c.haptic?.mode) {
                            "none" -> HapticMode.NONE
                            "custom" -> HapticMode.CUSTOM
                            else -> HapticMode.AUTO
                        },
                    customHaptic =
                        c.haptic
                            ?.takeIf { it.mode == "custom" }
                            ?.events
                            .orEmpty(),
                )
            }
        val draft =
            CreatorDraft(
                packId = null,
                idSuffix = Authoring.randomIdSuffix(),
                name = name(manifest.name).take(MAX_NAME),
                author = manifest.author,
                license = manifest.license,
                description = manifest.description.orEmpty(),
                cover = manifest.cover?.let { copy(it, "cover") },
                characters = characters,
                copiedFrom = manifest.name,
                homepage = manifest.homepage,
                tags = manifest.tags,
            )
        drafts.save(draft)
        return Duplicate(
            draft = draft,
            keptRingtones = manifest.characters.sumOf { it.ringtones.size },
            keptAnimations = manifest.characters.count { it.spriteAnimated != null },
        )
    }

    private fun durationMs(file: File): Long {
        val format = FileFormat.of(file.name) ?: return 0
        return FileInput(file.toPath()).use { AudioProbe.durationMs(format, it) } ?: 0
    }

    private companion object {
        const val MAX_NAME = 80
    }
}
