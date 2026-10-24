package app.mauri.cucuto.core.data.repository

import androidx.room3.withWriteTransaction
import app.mauri.cucuto.core.data.db.AssignmentDao
import app.mauri.cucuto.core.data.db.AssignmentEntity
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.di.WallClock
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Which character speaks for which contact. Every change republishes the routing table automatically. */
@Singleton
class AssignmentRepository
    @Inject
    constructor(
        private val db: CucutoDatabase,
        private val dao: AssignmentDao,
        private val clock: WallClock,
    ) {
        fun observeAll(): Flow<List<AssignmentEntity>> = dao.observeAll()

        fun observe(contactId: Long): Flow<AssignmentEntity?> = dao.observe(contactId)

        suspend fun get(contactId: Long): AssignmentEntity? = dao.get(contactId)

        fun observeByPack(packId: String): Flow<List<AssignmentEntity>> = dao.observeByPack(packId)

        /**
         * Gives [contactId] the voice of `(packId, characterId)`. Re-assigning the same character keeps the clip
         * toggles and haptic override; a different character starts fresh.
         */
        suspend fun assign(
            contactId: Long,
            packId: String,
            characterId: String,
        ) = db.withWriteTransaction {
            val current = dao.get(contactId)
            val same = current != null && current.packId == packId && current.characterId == characterId
            dao.upsert(
                if (same) {
                    current.copy(updatedAt = clock.now())
                } else {
                    AssignmentEntity(contactId, packId, characterId, updatedAt = clock.now())
                },
            )
        }

        suspend fun unassign(contactId: Long) = dao.delete(contactId)

        /** Switches one clip (its path from `pack.json`) on or off for [contactId]. No-op when unassigned. */
        suspend fun setClipEnabled(
            contactId: Long,
            file: String,
            enabled: Boolean,
        ) = edit(contactId) { current ->
            val disabled = if (enabled) current.disabledClips - file else (current.disabledClips + file).distinct()
            current.copy(disabledClips = disabled.sorted())
        }

        /**
         * Sets the per-contact haptic (pack `haptic` JSON, see `HapticOverride`), or null to use the character's.
         * Returns false when [contactId] has no voice: the override belongs to the assignment.
         */
        suspend fun setHapticOverride(
            contactId: Long,
            hapticJson: String?,
        ): Boolean = edit(contactId) { it.copy(hapticOverride = hapticJson) }

        /** Applies [change] to [contactId]'s assignment. Returns false when there is none. */
        private suspend fun edit(
            contactId: Long,
            change: (AssignmentEntity) -> AssignmentEntity,
        ): Boolean =
            db.withWriteTransaction {
                val current = dao.get(contactId) ?: return@withWriteTransaction false
                val updated = change(current)
                if (updated != current) dao.upsert(updated.copy(updatedAt = clock.now()))
                true
            }
    }
