package app.mauri.cucuto.core.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Transaction
    @Query("SELECT * FROM contacts ORDER BY displayName IS NULL, displayName COLLATE NOCASE, id")
    fun observeAll(): Flow<List<ContactWithKeys>>

    @Transaction
    @Query("SELECT * FROM contacts WHERE id = :id")
    fun observe(id: Long): Flow<ContactWithKeys?>

    @Transaction
    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun get(id: Long): ContactWithKeys?

    @Query(
        "SELECT contacts.* FROM contacts JOIN contact_keys ON contact_keys.contactId = contacts.id " +
            "WHERE contact_keys.packageName = :packageName AND contact_keys.value = :value",
    )
    suspend fun findByKey(
        packageName: String,
        value: String,
    ): ContactEntity?

    @Insert
    suspend fun insert(contact: ContactEntity): Long

    @Update
    suspend fun update(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun delete(id: Long)

    /** Inserts a key; returns -1 if `(packageName, value)` already belongs to a contact. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKey(key: ContactKeyEntity): Long

    @Query("SELECT * FROM contact_keys WHERE packageName = :packageName AND value = :value")
    suspend fun findKey(
        packageName: String,
        value: String,
    ): ContactKeyEntity?

    @Query(
        "UPDATE contact_keys SET displayName = COALESCE(:displayName, displayName), lastSeenAt = :seenAt " +
            "WHERE id = :keyId",
    )
    suspend fun touchKey(
        keyId: Long,
        displayName: String?,
        seenAt: Long,
    )

    @Query("DELETE FROM contact_keys WHERE id = :keyId")
    suspend fun deleteKey(keyId: Long)

    /** Moves every key of [fromContactId] to [toContactId] (merging two contacts). */
    @Query("UPDATE contact_keys SET contactId = :toContactId WHERE contactId = :fromContactId")
    suspend fun moveKeys(
        fromContactId: Long,
        toContactId: Long,
    )

    /**
     * Every messenger key of a contact that has a voice, or of a per-sender group: the input of the routing
     * table. Phone numbers ([ContactKeyEntity.NO_PACKAGE]) never route.
     */
    @Query(
        """
        SELECT k.packageName AS packageName, k.value AS keyValue, c.id AS contactId, c.isGroup AS isGroup,
               c.groupPolicy AS groupPolicy, c.shuffleSeed AS shuffleSeed, a.packId AS packId,
               a.characterId AS characterId, a.disabledClips AS disabledClips, a.hapticOverride AS hapticOverride
        FROM contact_keys AS k
        JOIN contacts AS c ON c.id = k.contactId
        LEFT JOIN assignments AS a ON a.contactId = c.id
        WHERE k.packageName != '' AND (a.contactId IS NOT NULL OR (c.isGroup AND c.groupPolicy = 'PER_SENDER'))
        ORDER BY k.packageName, k.value
        """,
    )
    fun observeRoutingRows(): Flow<List<RoutingRow>>
}

/** One row of [ContactDao.observeRoutingRows]. */
data class RoutingRow(
    val packageName: String,
    val keyValue: String,
    val contactId: Long,
    val isGroup: Boolean,
    val groupPolicy: GroupPolicy,
    val shuffleSeed: Int,
    val packId: String?,
    val characterId: String?,
    val disabledClips: List<String>?,
    val hapticOverride: String?,
)
