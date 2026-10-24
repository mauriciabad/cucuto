package app.mauri.cucuto.core.ringtone.store

import app.mauri.cucuto.core.data.db.ContactRingtoneWriteEntity
import app.mauri.cucuto.core.data.db.PhoneRingtoneDao
import app.mauri.cucuto.core.data.db.PhoneRingtoneRow
import javax.inject.Inject
import javax.inject.Singleton

/** What the ringtone sync reads and records: the wanted ringtones and the undo record of what it wrote. */
interface PhoneRingtoneStore {
    suspend fun rows(): List<PhoneRingtoneRow>

    suspend fun writes(): List<ContactRingtoneWriteEntity>

    suspend fun upsertWrite(write: ContactRingtoneWriteEntity)

    suspend fun deleteWrite(lookupKey: String)
}

/** [PhoneRingtoneStore] over Room. */
@Singleton
class RoomPhoneRingtoneStore
    @Inject
    constructor(
        private val dao: PhoneRingtoneDao,
    ) : PhoneRingtoneStore {
        override suspend fun rows(): List<PhoneRingtoneRow> = dao.syncRows()

        override suspend fun writes(): List<ContactRingtoneWriteEntity> = dao.writes()

        override suspend fun upsertWrite(write: ContactRingtoneWriteEntity) = dao.upsertWrite(write)

        override suspend fun deleteWrite(lookupKey: String) = dao.deleteWrite(lookupKey)
    }
