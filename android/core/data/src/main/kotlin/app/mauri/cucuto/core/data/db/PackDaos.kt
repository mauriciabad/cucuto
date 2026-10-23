package app.mauri.cucuto.core.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface InstalledPackDao {
    @Query("SELECT * FROM installed_packs ORDER BY name COLLATE NOCASE, packId")
    fun observeAll(): Flow<List<InstalledPackEntity>>

    @Query("SELECT * FROM installed_packs WHERE packId = :packId")
    suspend fun get(packId: String): InstalledPackEntity?

    @Query("SELECT * FROM installed_packs")
    suspend fun getAll(): List<InstalledPackEntity>

    @Upsert
    suspend fun upsert(pack: InstalledPackEntity)

    @Query("DELETE FROM installed_packs WHERE packId = :packId")
    suspend fun delete(packId: String)
}

@Dao
interface PackRepositoryDao {
    @Query("SELECT * FROM repositories ORDER BY isDefault DESC, addedAt, id")
    fun observeAll(): Flow<List<PackRepositoryEntity>>

    @Query("SELECT * FROM repositories WHERE url = :url")
    suspend fun getByUrl(url: String): PackRepositoryEntity?

    /** Returns the new row ID, or -1 if the URL is already known. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(repository: PackRepositoryEntity): Long

    @Update
    suspend fun update(repository: PackRepositoryEntity)

    @Query("DELETE FROM repositories WHERE id = :id")
    suspend fun delete(id: Long)
}
