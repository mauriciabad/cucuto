package app.mauri.cucuto.core.creator

import app.mauri.cucuto.core.pack.PackStore
import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.FileInput
import app.mauri.cucuto.packspec.authoring.Authoring
import app.mauri.cucuto.packspec.media.AudioProbe
import java.io.File
import java.io.IOException

/** What [PackDuplicator.duplicate] made, and what it had to leave out. */
data class Duplicate(
    val draft: CreatorDraft,
    /** Ringtones and animated sprites are not edited by the creator yet, so the copy has none. */
    val droppedRingtones: Int,
    val droppedAnimations: Int,
)

/**
 * "Duplicate and edit" (brief: packs installed from a repository can be duplicated and edited; the original stays
 * untouched): copies the active version of an installed pack into a new draft. The copy gets a new pack ID (so it
 * installs next to the original), version 1.0.0, and keeps the characters' IDs, names, sprites, colors, clips with
 * their labels and weights, and custom haptics. Files are copied as they are, in whatever format the pack uses.
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

        fun copy(
            path: String,
            prefix: String,
        ): String {
            val source = File(folder, path)
            val ext = path.substringAfterLast('.', "").lowercase()
            val target = drafts.newMediaFile(prefix, ext)
            source.copyTo(target, overwrite = true)
            return target.name
        }

        val characters =
            manifest.characters.map { c ->
                DraftCharacter(
                    id = c.id,
                    name = c.name,
                    sprite = copy(c.sprite, "sprite"),
                    seedColor = c.seedColor,
                    clips =
                        c.clips.map { clip ->
                            val file = copy(clip.file, "clip")
                            DraftClip(
                                file = file,
                                label = clip.label.orEmpty(),
                                weight = clip.weight ?: 1.0,
                                durationMs = durationMs(drafts.media(file)),
                            )
                        },
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
            )
        drafts.save(draft)
        return Duplicate(
            draft = draft,
            droppedRingtones = manifest.characters.sumOf { it.ringtones.size },
            droppedAnimations = manifest.characters.count { it.spriteAnimated != null },
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
