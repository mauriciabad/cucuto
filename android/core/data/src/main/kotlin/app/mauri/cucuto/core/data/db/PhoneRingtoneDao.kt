package app.mauri.cucuto.core.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PhoneRingtoneDao {
    @Query("SELECT * FROM phone_ringtones ORDER BY contactId")
    fun observeAll(): Flow<List<PhoneRingtoneEntity>>

    @Query("SELECT * FROM phone_ringtones WHERE contactId = :contactId")
    suspend fun get(contactId: Long): PhoneRingtoneEntity?

    @Upsert
    suspend fun upsert(ringtone: PhoneRingtoneEntity)

    @Query("DELETE FROM phone_ringtones WHERE contactId = :contactId")
    suspend fun delete(contactId: Long)

    /**
     * Every phone ringtone joined with its contact's Android link and voice: the input of the ringtone sync. Rows
     * without a link are included (the UI shows them as "not linked yet"). "Leave as is" rows are left out, so the
     * sync treats them like no row, and "Android default" rows have no character (ADR 0073).
     */
    @Query(
        """
        SELECT r.contactId AS contactId, c.androidLookupKey AS lookupKey, c.displayName AS displayName,
               CASE WHEN r.choice = 'CHARACTER' THEN COALESCE(r.packId, a.packId) END AS packId,
               CASE WHEN r.choice = 'CHARACTER' THEN COALESCE(r.characterId, a.characterId) END AS characterId,
               r.mode AS mode, r.variant AS variant, r.rules AS rules, r.choice AS choice
        FROM phone_ringtones AS r
        JOIN contacts AS c ON c.id = r.contactId
        LEFT JOIN assignments AS a ON a.contactId = r.contactId
        WHERE r.choice != 'UNCHANGED'
        ORDER BY r.contactId
        """,
    )
    fun observeSyncRows(): Flow<List<PhoneRingtoneRow>>

    /** [observeSyncRows], once. */
    @Query(
        """
        SELECT r.contactId AS contactId, c.androidLookupKey AS lookupKey, c.displayName AS displayName,
               CASE WHEN r.choice = 'CHARACTER' THEN COALESCE(r.packId, a.packId) END AS packId,
               CASE WHEN r.choice = 'CHARACTER' THEN COALESCE(r.characterId, a.characterId) END AS characterId,
               r.mode AS mode, r.variant AS variant, r.rules AS rules, r.choice AS choice
        FROM phone_ringtones AS r
        JOIN contacts AS c ON c.id = r.contactId
        LEFT JOIN assignments AS a ON a.contactId = r.contactId
        WHERE r.choice != 'UNCHANGED'
        ORDER BY r.contactId
        """,
    )
    suspend fun syncRows(): List<PhoneRingtoneRow>

    @Query("SELECT * FROM contact_ringtone_writes ORDER BY lookupKey")
    fun observeWrites(): Flow<List<ContactRingtoneWriteEntity>>

    @Query("SELECT * FROM contact_ringtone_writes ORDER BY lookupKey")
    suspend fun writes(): List<ContactRingtoneWriteEntity>

    @Upsert
    suspend fun upsertWrite(write: ContactRingtoneWriteEntity)

    @Query("DELETE FROM contact_ringtone_writes WHERE lookupKey = :lookupKey")
    suspend fun deleteWrite(lookupKey: String)
}

/**
 * One row of [PhoneRingtoneDao.observeSyncRows]: a phone ringtone with the character resolved (its own, or the
 * contact's voice; none for [RingtoneChoice.ANDROID_DEFAULT]) and the Android contact it rings through.
 */
data class PhoneRingtoneRow(
    val contactId: Long,
    val lookupKey: String?,
    val displayName: String?,
    val packId: String?,
    val characterId: String?,
    val mode: RingtoneMode,
    val variant: String?,
    /** [RingtoneMode.CUSTOM]'s rules as stored ([RingtoneRules]). */
    val rules: String? = null,
    val choice: RingtoneChoice = RingtoneChoice.CHARACTER,
)
