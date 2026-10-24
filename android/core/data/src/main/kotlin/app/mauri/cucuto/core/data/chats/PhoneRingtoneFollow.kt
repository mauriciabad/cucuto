package app.mauri.cucuto.core.data.chats

import app.mauri.cucuto.core.data.db.PhoneRingtoneDao
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.core.data.repository.InstalledPackRepository
import app.mauri.cucuto.core.data.routing.PackManifestCatalog
import javax.inject.Inject
import javax.inject.Singleton

/** Whether an installed character has call ringtones (pack format: a character may have only clips). */
fun interface RingtoneCapability {
    suspend fun hasRingtones(voice: ChatVoice): Boolean
}

/** [RingtoneCapability] over the installed packs' `pack.json` (cached by [PackManifestCatalog]). */
@Singleton
class InstalledRingtoneCapability
    @Inject
    constructor(
        private val packs: InstalledPackRepository,
        private val catalog: PackManifestCatalog,
    ) : RingtoneCapability {
        override suspend fun hasRingtones(voice: ChatVoice): Boolean {
            val pack = packs.get(voice.packId) ?: return false
            return (catalog.character(pack, voice.characterId)?.ringtoneCount ?: 0) > 0
        }
    }

/**
 * Keeps a contact's phone ringtone in line with ADR 0063 (owner, #245) when its message voice changes. The two are
 * independent choices:
 *
 * - a ringtone the user never picked ([PhoneRingtoneEntity.followsVoice]) follows the voice when the voice has
 *   ringtones;
 * - a voice without ringtones (or no voice) leaves it empty, or keeps the character that rang before;
 * - a ringtone the user picked never changes with the voice.
 *
 * The kept character is stored in the row itself, so the sync and backups resolve "own character, else voice" as
 * before.
 */
@Singleton
class PhoneRingtoneFollow
    @Inject
    constructor(
        private val dao: PhoneRingtoneDao,
        private val capability: RingtoneCapability,
        private val clock: WallClock,
    ) {
        /** [contactId]'s voice went from [old] to [new] (null: no voice). */
        suspend fun voiceChanged(
            contactId: Long,
            old: ChatVoice?,
            new: ChatVoice?,
        ) {
            if (old == new) return
            val row = dao.get(contactId)
            if (row != null && !row.followsVoice) return
            val newRings = new != null && capability.hasRingtones(new)
            val oldRings = old != null && capability.hasRingtones(old)
            val updated = followed(row, contactId, old, oldRings, newRings) ?: return
            dao.upsert(updated.copy(updatedAt = clock.now()))
        }

        internal companion object {
            /**
             * The row after a voice change, or null when nothing changes. [row] is the stored ringtone (null: none
             * yet); [oldRings] and [newRings] say whether the old and new voices have ringtones.
             */
            fun followed(
                row: PhoneRingtoneEntity?,
                contactId: Long,
                old: ChatVoice?,
                oldRings: Boolean,
                newRings: Boolean,
            ): PhoneRingtoneEntity? {
                if (row != null && !row.followsVoice) return null
                if (newRings) {
                    // Follow the new voice. Without a row, the voice already rings once the contact is linked.
                    if (row == null || row.packId == null) return null
                    return row.copy(packId = null, characterId = null, followsVoice = true)
                }
                // The new voice cannot ring: keep what rang before (a kept character, else the old voice's own).
                if (row?.packId != null) return null
                val kept = old?.takeIf { oldRings } ?: return null
                return (row ?: PhoneRingtoneEntity(contactId = contactId, updatedAt = 0)).copy(
                    packId = kept.packId,
                    characterId = kept.characterId,
                    followsVoice = true,
                )
            }
        }
    }
