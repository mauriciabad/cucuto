package app.mauri.cucuto.core.data.voices

import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.repository.InstalledPackRepository
import app.mauri.cucuto.core.data.routing.CatalogCharacter
import app.mauri.cucuto.core.data.routing.PackManifestCatalog
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.Moment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** An installed pack and its characters, as the character picker and the home screen show them. */
data class VoicePack(
    val packId: String,
    val name: String,
    val characters: List<VoiceCharacter>,
)

/**
 * One assignable character.
 *
 * @property sprite The static sprite file, or null if the manifest names none or it escapes the pack folder.
 * @property seedColor The pack's seed color as ARGB, or null (the app extracts one from the sprite).
 * @property clips The character's notification clips, in `pack.json` order.
 * @property haptic The character's own vibration (routing model; `Keep` leaves WhatsApp's).
 * @property clipPaths Each clip's path from `pack.json`, index for index with [clips]: the name per-contact clip
 *   toggles store (`AssignmentEntity.disabledClips`). Empty when unknown (previews and tests).
 * @property clipAutoHaptics Each clip's auto envelope (analysed at import, ADR 0020), aligned with [clips]; null for
 *   a clip without one. Empty when unknown.
 * @property clipLabels Each clip's `label` from `pack.json`, aligned with [clips]; null for a clip without one. Empty
 *   when unknown.
 * @property clipMoments Each clip's [Moment] (rare, first of the day, burst; ADR 0059), aligned with [clips]. Empty
 *   when unknown.
 * @property ringtoneCount How many call ringtones the character has (0: messages only).
 */
data class VoiceCharacter(
    val packId: String,
    val id: String,
    val name: String,
    val sprite: File?,
    val spriteAnimated: File?,
    val seedColor: Int?,
    val clips: List<File>,
    val haptic: Haptic,
    val clipPaths: List<String> = emptyList(),
    val clipAutoHaptics: List<Haptic.Custom?> = emptyList(),
    val clipLabels: List<String?> = emptyList(),
    val clipMoments: List<Moment> = emptyList(),
    val ringtoneCount: Int = 0,
) {
    val voice: ChatVoice get() = ChatVoice(packId, id)

    /** It can speak for messages: it has notification clips (ADR 0063). A song-only character cannot. */
    val forMessages: Boolean get() = clips.isNotEmpty()

    /** It can ring for calls: it has ringtones (ADR 0063). */
    val forCalls: Boolean get() = ringtoneCount > 0

    /** The auto envelope of [clip], or null when it has none. */
    fun autoHapticOf(clip: File): Haptic.Custom? = clipAutoHaptics.getOrNull(clips.indexOf(clip))
}

/** The characters of every installed pack. */
interface CharacterDirectory {
    /** Installed packs by name, each with its characters in `pack.json` order. */
    fun observePacks(): Flow<List<VoicePack>>
}

/** [CharacterDirectory] over `installed_packs` and each pack's `pack.json` ([PackManifestCatalog], cached). */
@Singleton
class InstalledCharacterDirectory
    @Inject
    constructor(
        private val installedPacks: InstalledPackRepository,
        private val catalog: PackManifestCatalog,
    ) : CharacterDirectory {
        override fun observePacks(): Flow<List<VoicePack>> =
            installedPacks
                .observeAll()
                .map { packs -> packs.map { pack -> voicePack(pack, catalog.characters(pack)) } }
                .flowOn(Dispatchers.IO)

        internal companion object {
            fun voicePack(
                pack: InstalledPackEntity,
                characters: Map<String, CatalogCharacter>,
            ): VoicePack {
                val root = File(pack.path)
                return VoicePack(
                    packId = pack.packId,
                    name = pack.name,
                    characters =
                        characters.map { (id, character) ->
                            val clips =
                                character.clips.mapNotNull { clip ->
                                    root.resolveInside(clip.file)?.let { file -> file to clip }
                                }
                            VoiceCharacter(
                                packId = pack.packId,
                                id = id,
                                name = character.name ?: id,
                                sprite = character.sprite?.let { root.resolveInside(it) },
                                spriteAnimated = character.spriteAnimated?.let { root.resolveInside(it) },
                                seedColor = character.seedColor?.let(::parseSeedColor),
                                clips = clips.map { it.first },
                                haptic = character.haptic,
                                clipPaths = clips.map { it.second.file },
                                clipAutoHaptics = clips.map { it.second.autoHaptic },
                                clipLabels = clips.map { it.second.label },
                                clipMoments = clips.map { it.second.moment },
                                ringtoneCount = character.ringtoneCount,
                            )
                        },
                )
            }

            /** `#RRGGBB` to opaque ARGB, or null when malformed. */
            fun parseSeedColor(value: String): Int? {
                if (value.length != 7 || value[0] != '#') return null
                val rgb = value.substring(1).toIntOrNull(16) ?: return null
                return (0xFF000000.toInt()) or rgb
            }

            /**
             * [path] (a pack path from `pack.json`) inside this folder, or null if it would leave it. The importer has
             * validated paths already; this is defense in depth before the UI opens files.
             */
            private fun File.resolveInside(path: String): File? {
                if (path.isEmpty() || path.startsWith('/') || path.split('/').any { it == ".." }) return null
                return File(this, path)
            }
        }
    }
