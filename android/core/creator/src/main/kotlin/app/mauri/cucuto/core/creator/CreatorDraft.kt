package app.mauri.cucuto.core.creator

import app.mauri.cucuto.packspec.Haptic
import app.mauri.cucuto.packspec.HapticEvent
import app.mauri.cucuto.packspec.PackManifest
import app.mauri.cucuto.packspec.authoring.Authoring
import app.mauri.cucuto.packspec.authoring.CharacterDraft
import app.mauri.cucuto.packspec.authoring.ClipDraft
import app.mauri.cucuto.packspec.authoring.DraftFile
import app.mauri.cucuto.packspec.authoring.PackDraft
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.random.Random

/**
 * The pack a person is making in the app, as saved in `files/creator/draft/draft.json` after every change (so leaving
 * the screen or the app never loses work). Media are file names in the draft's media folder ([DraftStore]).
 *
 * IDs follow the shared authoring rules ([Authoring]): while [packId] and a character's `id` are null they are derived
 * from the names; the first install or export fixes them ([withFixedIds]) because assignments refer to them.
 */
@Serializable
data class CreatorDraft(
    val packId: String? = null,
    val idSuffix: String = Authoring.randomIdSuffix(),
    val name: String = "",
    val author: String = "",
    val license: String = Authoring.PERSONAL_USE_LICENSE,
    val description: String = "",
    val version: String = Authoring.FIRST_VERSION,
    /** Media file name of the cover, or null to let apps show the first sprite. */
    val cover: String? = null,
    val characters: List<DraftCharacter> = emptyList(),
    /** The pack this draft was copied from ("duplicate and edit"), for the screen's title only. */
    val copiedFrom: String? = null,
) {
    /** True when nothing was entered yet: no need to ask before replacing it. */
    val isEmpty: Boolean get() = name.isBlank() && description.isBlank() && cover == null && characters.isEmpty()

    fun character(key: String): DraftCharacter? = characters.firstOrNull { it.key == key }

    fun updateCharacter(
        key: String,
        change: (DraftCharacter) -> DraftCharacter,
    ): CreatorDraft = copy(characters = characters.map { if (it.key == key) change(it) else it })

    /** Every media file name the draft refers to. */
    fun mediaFiles(): Set<String> =
        buildSet {
            cover?.let(::add)
            for (c in characters) {
                c.sprite?.let(::add)
                c.clips.forEach { add(it.file) }
            }
        }

    /** The shared authoring model, with media resolved against [mediaDir]. */
    fun toPackDraft(mediaDir: File): PackDraft =
        PackDraft(
            id = packId,
            idSuffix = idSuffix,
            name = name,
            version = version,
            author = author,
            license = license,
            description = description,
            cover = cover?.let { draftFile(mediaDir, it) },
            characters =
                characters.map { c ->
                    CharacterDraft(
                        id = c.id,
                        name = c.name,
                        sprite = c.sprite?.let { draftFile(mediaDir, it) },
                        seedColor = c.seedColor,
                        clips = c.clips.map { ClipDraft(draftFile(mediaDir, it.file), it.label, it.weight) },
                        haptic = c.haptic(),
                    )
                },
        )

    /** Fixes the pack and character IDs to the ones [manifest] (assembled from this draft) was written with. */
    fun withFixedIds(manifest: PackManifest): CreatorDraft =
        copy(
            packId = manifest.id,
            characters = characters.mapIndexed { i, c -> c.copy(id = manifest.characters.getOrNull(i)?.id ?: c.id) },
        )

    private fun draftFile(
        mediaDir: File,
        name: String,
    ) = DraftFile(File(mediaDir, name).path, name.substringAfterLast('.', "").lowercase())

    companion object {
        /** A unique key for a character or clip in the draft (never shown, never written to the pack). */
        fun newKey(random: Random = Random.Default): String =
            Authoring.randomIdSuffix(random) + Authoring.randomIdSuffix(random)
    }
}

@Serializable
data class DraftCharacter(
    val key: String = CreatorDraft.newKey(),
    /** Fixed character ID, or null while it is derived from [name]. */
    val id: String? = null,
    val name: String = "",
    val sprite: String? = null,
    /** `#RRGGBB`. */
    val seedColor: String? = null,
    val clips: List<DraftClip> = emptyList(),
    val haptic: HapticMode = HapticMode.AUTO,
    /** The custom timeline for [HapticMode.CUSTOM] (from the haptic editor, #32, or a duplicated pack). */
    val customHaptic: List<HapticEvent> = emptyList(),
) {
    fun haptic(): Haptic? =
        when (haptic) {
            HapticMode.AUTO -> null
            HapticMode.NONE -> Haptic("none")
            HapticMode.CUSTOM -> if (customHaptic.isEmpty()) null else Haptic("custom", customHaptic)
        }
}

@Serializable
data class DraftClip(
    val key: String = CreatorDraft.newKey(),
    val file: String,
    val label: String = "",
    val weight: Double = 1.0,
    val durationMs: Long,
)

/** The character's vibration: follows the voice (the format's default), none, or a custom pattern. */
@Serializable
enum class HapticMode { AUTO, NONE, CUSTOM }
