package app.mauri.cucuto.core.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AssignmentDao {
    @Query("SELECT * FROM assignments ORDER BY contactId")
    fun observeAll(): Flow<List<AssignmentEntity>>

    @Query("SELECT * FROM assignments WHERE contactId = :contactId")
    fun observe(contactId: Long): Flow<AssignmentEntity?>

    @Query("SELECT * FROM assignments WHERE contactId = :contactId")
    suspend fun get(contactId: Long): AssignmentEntity?

    /** Contacts whose voice comes from [packId], for "this update removes a character" prompts. */
    @Query("SELECT * FROM assignments WHERE packId = :packId ORDER BY contactId")
    fun observeByPack(packId: String): Flow<List<AssignmentEntity>>

    @Upsert
    suspend fun upsert(assignment: AssignmentEntity)

    @Query("DELETE FROM assignments WHERE contactId = :contactId")
    suspend fun delete(contactId: Long)
}
