package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.db.RoutingRow
import app.mauri.cucuto.core.routing.Clip
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.Route
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.core.routing.Voice
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Turns database rows into a [RoutingTable]. Pure apart from [CharacterCatalog] lookups, so the result depends only
 * on its inputs: the same rows always give an equal table (with generation 0; the publisher stamps it).
 *
 * Rules:
 * - A key routes only if its contact's character is installed and has at least one enabled clip. If the pack is
 *   missing (not restored yet, uninstalled) the contact keeps WhatsApp's tone rather than failing.
 * - The per-contact haptic override wins over the character's haptic.
 * - Per-sender groups always get a route, with [Route.NO_VOICE] when the group itself has no usable voice, so the
 *   module knows to look up the sender.
 * - Identical voices (same character, enabled clips and haptic) are stored once.
 * - Clip URIs come from [ClipMirror]: a `file://` copy SystemUI reads without starting the app, or the clip
 *   provider's `content://` URI (ADR 0018).
 * - Clips carry their auto envelope only in voices whose haptic is [Haptic.Auto], the only case where the module
 *   uses it, which keeps the table small (issue #98).
 */
class RoutingTableBuilder
    @Inject
    constructor(
        private val catalog: CharacterCatalog,
        private val mirror: ClipMirror,
    ) {
        /** [CharacterCatalog.revisions]: the same rows can give a different table after these. */
        val catalogRevisions: Flow<Long> get() = catalog.revisions

        suspend fun build(
            rows: List<RoutingRow>,
            packs: Map<String, InstalledPackEntity>,
            enabled: Boolean,
        ): RoutingTable {
            val voices = ArrayList<Voice>()
            val voiceIndex = HashMap<Voice, Int>()
            val voiceByContact = HashMap<Long, Int>()
            val routes = ArrayList<Route>(rows.size)

            for (row in rows) {
                val voice =
                    voiceByContact.getOrPut(row.contactId) {
                        val built = voiceFor(row, packs) ?: return@getOrPut Route.NO_VOICE
                        voiceIndex.getOrPut(built) {
                            voices += built
                            voices.lastIndex
                        }
                    }
                val perSender = row.isGroup && row.groupPolicy == GroupPolicy.PER_SENDER
                if (voice == Route.NO_VOICE && !perSender) continue
                var flags = 0
                if (row.isGroup) flags = flags or Route.FLAG_GROUP
                if (perSender) flags = flags or Route.FLAG_PER_SENDER
                routes += Route(row.packageName, row.keyValue, voice, row.shuffleSeed, flags, row.contactId)
            }
            return RoutingTable(
                generation = 0,
                enabled = enabled,
                voices = voices,
                routes = routes,
                selfTestClip = mirror.selfTestUri(),
            )
        }

        private suspend fun voiceFor(
            row: RoutingRow,
            packs: Map<String, InstalledPackEntity>,
        ): Voice? {
            val packId = row.packId ?: return null
            val characterId = row.characterId ?: return null
            val pack = packs[packId] ?: return null
            val character = catalog.character(pack, characterId) ?: return null
            val disabled = row.disabledClips.orEmpty().toHashSet()
            val haptic = HapticJson.fromOverride(row.hapticOverride) ?: character.haptic
            val clips =
                character.clips
                    .filter { it.file !in disabled }
                    .map { clip ->
                        Clip(
                            uri = mirror.clipUri(pack, characterId, clip.file),
                            milliWeight = clip.milliWeight,
                            haptic = if (haptic == Haptic.Auto) clip.autoHaptic else null,
                        )
                    }
            if (clips.isEmpty()) return null
            return Voice(packId, characterId, clips, haptic)
        }
    }
