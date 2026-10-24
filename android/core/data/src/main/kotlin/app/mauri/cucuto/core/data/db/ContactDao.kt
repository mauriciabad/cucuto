package app.mauri.cucuto.core.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import app.mauri.cucuto.core.routing.ContactKeys
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

    /** The oldest 1:1 contact linked to the Android contact [lookupKey] (Signal's derived keys, ADR 0081). */
    @Query("SELECT * FROM contacts WHERE androidLookupKey = :lookupKey AND NOT isGroup ORDER BY id LIMIT 1")
    suspend fun findByLookupKey(lookupKey: String): ContactEntity?

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
     *
     * Then the derived Signal and Molly keys (ADR 0081): every 1:1 contact with a voice that is linked to an Android
     * contact also routes `contact:<lookupKey>` in both apps, flagged `derived`. Sorted so that, for one key, a stored
     * key comes before a derived one, then the oldest contact: the builder keeps the first.
     */
    @Query(
        """
        SELECT k.packageName AS packageName, k.value AS keyValue, c.id AS contactId, c.isGroup AS isGroup,
               c.groupPolicy AS groupPolicy, c.shuffleSeed AS shuffleSeed, a.packId AS packId,
               a.characterId AS characterId, a.disabledClips AS disabledClips, a.hapticOverride AS hapticOverride,
               0 AS derived
        FROM contact_keys AS k
        JOIN contacts AS c ON c.id = k.contactId
        LEFT JOIN assignments AS a ON a.contactId = c.id
        WHERE k.packageName != '' AND (a.contactId IS NOT NULL OR (c.isGroup AND c.groupPolicy = 'PER_SENDER'))
        UNION ALL
        SELECT p.packageName AS packageName, '${ContactKeys.PREFIX}' || c.androidLookupKey AS keyValue,
               c.id AS contactId, c.isGroup AS isGroup, c.groupPolicy AS groupPolicy, c.shuffleSeed AS shuffleSeed,
               a.packId AS packId, a.characterId AS characterId, a.disabledClips AS disabledClips,
               a.hapticOverride AS hapticOverride, 1 AS derived
        FROM contacts AS c
        JOIN assignments AS a ON a.contactId = c.id
        JOIN (SELECT '${ContactKeys.SIGNAL}' AS packageName UNION ALL SELECT '${ContactKeys.MOLLY}') AS p
        WHERE NOT c.isGroup AND c.androidLookupKey IS NOT NULL AND c.androidLookupKey != ''
        ORDER BY packageName, keyValue, derived, contactId
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
    /** A Signal or Molly key derived from the contact's Android link, not stored (ADR 0081). */
    val derived: Boolean = false,
)
