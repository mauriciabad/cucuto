package app.mauri.cucuto.core.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DecisionLogDao {
    @Query("SELECT * FROM decision_log ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int = CAPACITY): Flow<List<DecisionLogEntity>>

    @Query("SELECT COUNT(*) FROM decision_log")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entry: DecisionLogEntity): Long

    @Query("DELETE FROM decision_log WHERE id NOT IN (SELECT id FROM decision_log ORDER BY id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    /** Appends [entry] and drops the oldest rows beyond [capacity]: a ring buffer. */
    @Transaction
    suspend fun append(
        entry: DecisionLogEntity,
        capacity: Int = CAPACITY,
    ): Long {
        val id = insert(entry)
        trim(capacity)
        return id
    }

    @Query("DELETE FROM decision_log")
    suspend fun clear()

    companion object {
        const val CAPACITY: Int = 500
    }
}

@Dao
interface SeenSenderDao {
    @Query("SELECT * FROM seen_senders ORDER BY lastSeenAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = CAPACITY): Flow<List<SeenSenderEntity>>

    @Query("SELECT * FROM seen_senders WHERE packageName = :packageName AND conversationKey = :conversationKey")
    suspend fun get(
        packageName: String,
        conversationKey: String,
    ): SeenSenderEntity?

    @Insert
    suspend fun insert(sender: SeenSenderEntity)

    @Update
    suspend fun update(sender: SeenSenderEntity)

    @Query(
        "DELETE FROM seen_senders WHERE rowid NOT IN " +
            "(SELECT rowid FROM seen_senders ORDER BY lastSeenAt DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)

    @Query("DELETE FROM seen_senders WHERE packageName = :packageName AND conversationKey = :conversationKey")
    suspend fun delete(
        packageName: String,
        conversationKey: String,
    )

    /**
     * Records one sighting: inserts the conversation, or bumps its count and refreshes the names and last sender.
     * Keeps the newest [capacity] conversations.
     */
    @Transaction
    suspend fun record(
        sighting: SeenSenderEntity,
        capacity: Int = CAPACITY,
    ) {
        val existing = get(sighting.packageName, sighting.conversationKey)
        if (existing == null) {
            insert(sighting.copy(messageCount = 1, firstSeenAt = sighting.lastSeenAt))
        } else {
            update(
                existing.copy(
                    displayName = sighting.displayName ?: existing.displayName,
                    isGroup = sighting.isGroup || existing.isGroup,
                    senderKey = sighting.senderKey ?: existing.senderKey,
                    senderName = sighting.senderName ?: existing.senderName,
                    lastSeenAt = maxOf(existing.lastSeenAt, sighting.lastSeenAt),
                    messageCount = existing.messageCount + 1,
                ),
            )
        }
        trim(capacity)
    }

    companion object {
        const val CAPACITY: Int = 1000
    }
}
