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

        /** Sets the per-contact haptic (pack `haptic` JSON), or null to use the character's. */
        suspend fun setHapticOverride(
            contactId: Long,
            hapticJson: String?,
        ) = edit(contactId) { it.copy(hapticOverride = hapticJson) }

        private suspend fun edit(
            contactId: Long,
            change: (AssignmentEntity) -> AssignmentEntity,
        ) {
            db.withWriteTransaction {
                val current = dao.get(contactId) ?: return@withWriteTransaction
                val updated = change(current)
                if (updated != current) dao.upsert(updated.copy(updatedAt = clock.now()))
            }
        }
    }
